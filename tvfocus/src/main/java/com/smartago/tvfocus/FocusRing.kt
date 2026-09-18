package com.smartago.tvfocus

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The GLOBAL traveling focus ring, ported from the web launcher this kit grew out of
 * (`.tv-focus-ring`): ONE floating yellow ring per window that ANIMATES between focused
 * elements (left/top/width/height transition 300ms cubic-bezier(.25,.8,.25,1)), padded
 * 6px around the element, radius = element radius + 6, with its own breathing glow
 * (`tvFocusGlow`: 0 0 10px 4px @.3 ⇄ 0 0 18px 8px @.6, 1.2s ease-in-out infinite).
 *
 * Like the web `loop()` (requestAnimationFrame), the overlay RE-READS the focused
 * element's bounds EVERY FRAME — the ring can never go stale when layouts shift
 * (keyboard insets, scrolling, width animations). It hides the moment focus moves to
 * an unregistered element (web: no active element / data-hide-focus-outline).
 *
 * Elements therefore do NOT draw their own yellow focused border — the ring does.
 * (Web `data-hide-focus-outline` parity = don't register a ring target; e.g. the wheel
 * dialog's OK button and the home app tiles keep their own styling.)
 */
private val RingYellow = Color(0xFFFFCC00)
private val RING_PAD = 6.dp

/** One registered focusable; coords are re-read every frame while it owns the ring. */
internal class RingNode(val corner: Dp, val color: Color) {
    var coords: LayoutCoordinates? = null
}

class FocusRingState {
    internal var node by mutableStateOf<RingNode?>(null)
    internal var hostCoords: LayoutCoordinates? = null

    /** A rect (host coordinates) the ring must NOT paint over — see [FocusRingOccluder]. */
    internal var occluder by mutableStateOf<Rect?>(null)
    internal var occluderCornerPx: Float = 0f

    /**
     * Proportional size of the ring (border, pad, glow). An app that composes at a
     * px-as-dp scale keeps 1f; an app on a ÷2 canvas passes 0.5f so the ring
     * keeps the SAME visual weight relative to their elements.
     */
    internal var scale: Float = 1f

    /**
     * Hide the ring while a full-screen overlay owns the window.
     *
     * The host draws the ring AFTER its content, so it floats above everything in the window —
     * including the screensaver, which left a stray yellow rectangle glowing over the artwork
     * around whatever still held focus underneath. The focus itself must stay where it is (the
     * user has to land back on it when they wake the screen), so the ring is suppressed rather
     * than moved. Counted, so overlapping overlays cannot un-hide it early.
     */
    private var suppressCount by mutableStateOf(0)
    internal val suppressed: Boolean get() = suppressCount > 0

    fun suppress() { suppressCount++ }
    fun release() { if (suppressCount > 0) suppressCount-- }

    /**
     * How long the ring takes to travel to a newly focused element. 0 = it is simply there.
     *
     * The glide is the web app's behaviour and it earns its keep across a screen: you see WHERE
     * the ring went. Inside a small popup menu it does the opposite — rows sit ~78dp apart, so
     * 300ms of travel over that distance reads as a bounce rather than as movement, and it is the
     * only thing on screen that moves. [InstantFocusRing] turns it off for those.
     */
    internal var travelMs by mutableStateOf(300)
}

/**
 * Ring jumps instead of gliding, for as long as this is composed. For popup menus — see
 * [FocusRingState.travelMs].
 */
@Composable
fun InstantFocusRing(active: Boolean = true) {
    val ring = LocalFocusRing.current ?: return
    DisposableEffect(ring, active) {
        val previous = ring.travelMs
        if (active) ring.travelMs = 0
        onDispose { ring.travelMs = previous }
    }
}

/** Hide the window's traveling focus ring for as long as [active] is true. */
@Composable
fun HideFocusRing(active: Boolean = true) {
    val ring = LocalFocusRing.current ?: return
    DisposableEffect(ring, active) {
        if (active) ring.suppress()
        onDispose { if (active) ring.release() }
    }
}

/** Null in windows without a host (elements keep their own focus styling there). */
val LocalFocusRing = staticCompositionLocalOf<FocusRingState?> { null }

/** Wrap a WINDOW's content once; draws the traveling ring above everything. */
@Composable
fun FocusRingHost(modifier: Modifier = Modifier, scale: Float = 1f, content: @Composable BoxScope.() -> Unit) {
    val state = remember { FocusRingState() }
    state.scale = scale
    CompositionLocalProvider(LocalFocusRing provides state) {
        Box(modifier.onGloballyPositioned { state.hostCoords = it }) {
            content()
            FocusRingOverlay(state)
        }
    }
}

@Composable
private fun FocusRingOverlay(state: FocusRingState) {
    if (state.suppressed) return
    // While another window (a dialog) owns the screen, this window's ring must not keep
    // animating underneath it — the pulse re-blurs its glow every frame, and two windows'
    // rings at once doubled the frame cost of every dialog.
    if (!androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused) return
    val node = state.node ?: return
    val d = LocalDensity.current
    val s = state.scale
    val ringPad = RING_PAD * s
    val padPx = with(d) { ringPad.toPx() }

    // web loop() parity: poll the focused element's rect every frame
    var raw by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(node) {
        while (true) {
            withFrameNanos { }
            val c = node.coords
            val h = state.hostCoords
            raw = if (c != null && c.isAttached && h != null && h.isAttached) {
                h.localBoundingBoxOf(c, clipBounds = false)
            } else null
        }
    }
    val r = raw ?: return
    val target = Rect(r.left - padPx, r.top - padPx, r.right + padPx, r.bottom + padPx)

    // rect chases the target with the web's 300ms cubic-bezier(.25,.8,.25,1) transfer
    val rect = remember { Animatable(target, Rect.VectorConverter) }
    val travelMs = state.travelMs
    LaunchedEffect(target, travelMs) {
        if (travelMs <= 0) rect.snapTo(target)
        else rect.animateTo(target, tween(travelMs, easing = CubicBezierEasing(0.25f, 0.8f, 0.25f, 1f)))
    }

    // tvFocusGlow, breathing in BRIGHTNESS instead of the web's size: animating blur/spread
    // means a fresh gaussian mask every frame (skia caches by sigma+shape), and that single
    // pulse measured 250ms/frame on the emulator's software GL with the ring parked on a wide
    // row. Fixed at the web animation's midpoint (14px 6px) with the same 0.3⇄0.6 alpha
    // rhythm, the mask renders once and every following frame draws it from the cache.
    val pulse = rememberInfiniteTransition(label = "tv-focus-glow")
    val p by pulse.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "tv-focus-glow-p",
    )
    val v = rect.value
    // NO graphicsLayer here, ever. Wrapping the glow in one to animate its alpha squares the
    // halo off — a layer is allocated at the node's size and the glow lives OUTSIDE those
    // bounds — and, because the ring resizes every frame as it travels, the layer surface was
    // being reallocated every frame too: the "everything drags" report. The alpha goes
    // straight into cssGlow instead; that costs nothing now that blur/spread are FIXED, since
    // skia's mask cache is keyed on sigma and shape, not on colour.
    val occluder = state.occluder
    val occluderCorner = state.occluderCornerPx
    Box(
        Modifier
            .offset { IntOffset(v.left.roundToInt(), v.top.roundToInt()) }
            .size(with(d) { v.width.toDp() }, with(d) { v.height.toDp() })
            // FocusRingOccluder: glow AND border skip the occluder's rounded rect (moved into this
            // box's local frame) — a floating control there reads as sitting above the ring.
            .then(
                if (occluder == null) Modifier else Modifier.drawWithContent {
                    val local = Rect(
                        occluder.left - v.left, occluder.top - v.top,
                        occluder.right - v.left, occluder.bottom - v.top,
                    )
                    val path = androidx.compose.ui.graphics.Path().apply {
                        addRoundRect(
                            androidx.compose.ui.geometry.RoundRect(
                                local, androidx.compose.ui.geometry.CornerRadius(occluderCorner, occluderCorner),
                            ),
                        )
                    }
                    clipPath(path, androidx.compose.ui.graphics.ClipOp.Difference) {
                        this@drawWithContent.drawContent()
                    }
                },
            )
            // clipOut: the ring is transparent — the glow must stay OUTSIDE its box
            // (CSS box-shadow parity) or it tints the element's background yellow.
            .cssGlow(color = node.color, alpha = 0.3f + 0.3f * p, blur = (14 * s).dp, spread = (6 * s).dp, corner = node.corner + ringPad, clipOut = true)
            .border(4.dp * s, node.color, RoundedCornerShape(node.corner + ringPad)),
    )
}

/**
 * A HOLE IN THE RING. The host paints the ring after all of its content, so nothing inside the
 * window can sit on top of it. A floating control that must LOOK like it sits above the ring of
 * whatever lies under it (a floating door button over a widget board: the widget's ring was crossing the button) does the reverse: it stays exactly where it is in the tree
 * — focus, navigation and layout untouched — and tells the ring not to paint inside its rect.
 * [rect] in host coordinates (see [hostLocalRect]), [cornerPx] the control's own corner radius.
 * One at a time; null clears. No-op in windows without a host.
 *
 * (Re-parenting the control above the ring was tried first and dropped: the moved node lost focus
 * on the way, the door closed itself, and focus fell on a parked node at 0,0.)
 */
@Composable
fun FocusRingOccluder(rect: Rect?, cornerPx: Float = 0f) {
    val ring = LocalFocusRing.current ?: return
    DisposableEffect(ring, rect, cornerPx) {
        ring.occluder = rect
        ring.occluderCornerPx = cornerPx
        onDispose { ring.occluder = null }
    }
}

/** [coords] of a node in this window as a rect in the host's frame — where [AboveFocusRing] content is laid out. */
fun hostLocalRect(ring: FocusRingState, coords: LayoutCoordinates): Rect? {
    val h = ring.hostCoords ?: return null
    if (!h.isAttached || !coords.isAttached) return null
    return h.localBoundingBoxOf(coords, clipBounds = false)
}

/**
 * Register this focusable as a ring target: while focused, the window ring wraps it
 * ([corner] = the element's own corner radius; the ring adds the 6px pad itself).
 * No-op in windows without a FocusRingHost.
 *
 * [within]: keep the ring on this element while focus is anywhere INSIDE it, not only on it.
 * For rows that open an inline text field: the field takes real focus (the keyboard needs it),
 * and with plain isFocused the row would drop the ring at exactly the moment the user starts
 * typing — the camera form is where that was first seen on screen.
 */
fun Modifier.focusRingTarget(corner: Dp = 16.dp, color: Color = RingYellow, within: Boolean = false): Modifier = composed {
    val ring = LocalFocusRing.current ?: return@composed Modifier
    val node = remember(corner, color) { RingNode(corner, color) }

    // A NODE THAT LEAVES THE COMPOSITION TAKES THE RING WITH IT.
    //
    // The "lost focus" callback below only reaches a node that is still there. Anything that
    // DISAPPEARS while holding the ring leaves it painted where it used to be, and the screen ends
    // up with two rings — one real, one ghost. Measured: sidebar → pick a category → OK; the
    // sidebar collapses (its expanded row is removed, so no `false` ever arrives), focus lands on
    // the first app, and the ring stays behind on the category icon (seen on a real TV).
    androidx.compose.runtime.DisposableEffect(node) {
        onDispose { if (ring.node === node) ring.node = null }
    }

    Modifier
        .onGloballyPositioned { node.coords = it }
        .onFocusChanged { st ->
            when {
                // direct focus always wins the ring
                st.isFocused -> ring.node = node
                // focus is somewhere inside: claim UNLESS the current owner is one of our own
                // children — a row must not steal the ring back from its icon buttons (they are
                // targets of their own; the plain text field is not, so the row keeps it there)
                within && st.hasFocus -> {
                    val cur = ring.node
                    val curCenter = cur?.coords?.takeIf { it.isAttached }?.boundsInRoot()?.center
                    val mine = node.coords?.takeIf { it.isAttached }?.boundsInRoot()
                    val ownChild = cur !== node && curCenter != null && mine != null && mine.contains(curCenter)
                    if (!ownChild) ring.node = node
                }
                ring.node === node && !(within && st.hasFocus) -> ring.node = null
            }
        }
}
