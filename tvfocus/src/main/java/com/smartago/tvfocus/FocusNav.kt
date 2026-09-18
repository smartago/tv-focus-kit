package com.smartago.tvfocus

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * A port of the spatial-navigation model of the web launcher this kit grew out of:
 * every focusable OBJECT declares what each key does — LEFT/RIGHT/UP/DOWN jump to a named
 * focus id (web data-*-focus-id), BACK jumps or runs an action. Unmapped keys fall through
 * to Compose's default geometric search.
 *
 * Usage:
 *   Modifier.focusId("nav-general")                       // register this node under an id
 *   Modifier.navKeys(right = "page-content", down = "nav-appearance", backTo = "sidebar")
 *
 * Ids are app-global; focusing an id that is not currently in the composition is a no-op
 * (returns false, so the key falls through to default handling).
 */
object FocusNav {
    private val registry = HashMap<String, FocusRequester>()

    /**
     * The last registered id that actually took focus — kept by [focusId] itself, so every
     * node that can be targeted is also tracked without anyone opting in.
     *
     * This is what lets a modal put focus back where the user was without each caller having
     * to name it (a modal component can rely on it).
     */
    @Volatile
    var current: String? = null
        internal set

    /**
     * EVERY id the focused node answers to — a node commonly carries several
     * (`nav-general` + `page-first` + `nav-active` are all the same rail item), and [current]
     * can only name one of them: whichever `focusId` happened to be last in the chain.
     *
     * That ambiguity was not cosmetic. Every "has the handover landed?" retry compared
     * [current] to the id it had asked for, and on a multi-id node the comparison was false
     * FOREVER — so the loop kept calling requestFocus() every 50ms, snatching the ring back
     * from under the user's D-pad for as long as it ran. Ask [isFocused] instead.
     */
    private val focusedIds = java.util.Collections.synchronizedSet(HashSet<String>())

    /** True when the node registered under [id] is the one holding focus right now. */
    fun isFocused(id: String): Boolean = focusedIds.contains(id)

    internal fun setFocused(id: String, focused: Boolean) {
        if (focused) {
            focusedIds.add(id)
            current = id
            // The trace that makes a one-frame flash readable. A ring that jumps to the sidebar
            // and back is over before a screenshot can catch it, and the watchdog's one-second
            // tick sails straight past it — but every stop it made is a line here.
            if (auditGaps) TvFocus.log.i("FocusNav", "focus -> $id")
        } else {
            focusedIds.remove(id)
        }
    }

    /** Stable requester for an id (created on first use, reused forever). */
    fun requester(id: String): FocusRequester = registry.getOrPut(id) { FocusRequester() }

    /** Try to focus a registered node. False when the id isn't attached right now. */
    fun focus(id: String): Boolean =
        registry[id]?.let { runCatching { it.requestFocus() }.isSuccess } ?: false

    /**
     * Web resolveIds parity: [ids] may be a comma-separated fallback chain
     * ("page-first,page-content") — the first attachable target wins.
     */
    fun focusChain(ids: String): Boolean =
        ids.split(',').any { it.isNotBlank() && focus(it.trim()) }

    /**
     * True when at least one of [ids] is a real target — some node registered it at least once.
     *
     * Public because callers sometimes have to decide BEFORE acting: a launcher that promises
     * "press UP again for the top" must not make that promise when the top bar is switched off,
     * the title pill is hidden and no widget is opted in. Then there is no top, and the honest
     * answer is a silent wall rather than a toast that leads nowhere (measured on the emulator
     * with all three switched off).
     */
    fun hasTarget(ids: String): Boolean = known(ids)

    /** True when this id has EVER been registered — a real neighbour, just not attached now. */
    private fun known(ids: String): Boolean =
        ids.split(',').any { it.isNotBlank() && registry.containsKey(it.trim()) }

    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Focus [ids] — and when the target is a REAL neighbour that simply is not attached at this
     * instant, keep trying for a few frames instead of giving up.
     *
     * This is the fix for a bug that only showed on a real box: walking UP the app rail, the row
     * that reappears (the widgets come back and the page re-lays-out) is not composed yet at the
     * moment the key arrives. `focusChain` returned false, the key fell through to Compose's
     * geometric search, and that walks by SCREEN POSITION — so the ring flew to the top-left
     * control, the Widgets button. The emulator laid out fast enough to hide it.
     *
     * A target we have never heard of is NOT retried: it returns false and the old fall-through
     * stands, so an optional chain (a widget nobody opted in) still degrades to geometry.
     */
    fun focusSticky(ids: String, frames: Int = 8): Boolean {
        if (focusChain(ids)) {
            stickyFailed.remove(ids)
            return true
        }
        if (!known(ids)) {
            // A chain nobody ever registered is not a timing problem, it is a wiring problem —
            // and it used to fail in complete silence. Naming the ids is the difference between
            // "the key does nothing" and knowing which target never existed.
            if (auditGaps) TvFocus.log.w("FocusNav", "sticky '$ids' -> NO id ever registered")
            return false
        }
        // NEVER TWICE. Consuming the key buys a target a few frames to finish laying out. A target
        // that already failed to appear is not mid-layout, it is gone — and eating the key a second
        // time turns this node into a dead end.
        //
        // Not theory: on a real box, DOWN from the Widgets button aimed at a chain of opted-in
        // widget ids that were all switched off. Every id was "known", none was attached, and the
        // button swallowed every DOWN press for good — the only way back to the apps was to walk
        // sideways to another header button. First press waits; second press always falls through.
        val failedAt = stickyFailed[ids]
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILED_MEMORY_MS) return false
        var left = frames
        retryHandler.postDelayed(
            object : Runnable {
                override fun run() {
                    if (focusChain(ids)) {
                        stickyFailed.remove(ids)
                        return
                    }
                    if (--left > 0) {
                        retryHandler.postDelayed(this, 24)
                    } else {
                        stickyFailed[ids] = System.currentTimeMillis()
                        if (auditGaps) TvFocus.log.w("FocusNav", "sticky '$ids' -> never attached")
                    }
                }
            },
            24,
        )
        return true
    }

    /** ids whose sticky retry ran out, and when — see [focusSticky]. */
    private val stickyFailed = HashMap<String, Long>()
    private const val FAILED_MEMORY_MS = 4000L

    /**
     * Move focus to [ids] BEFORE removing the node that currently holds it.
     *
     * When a focused node leaves the composition, Compose falls back to the first focusable
     * in the WHOLE tree — on these TV layouts that is the sidebar, which expands while the
     * one traveling ring flies over to it and straight back. An app whose dialogs are real windows with their own ring host does not hit
     * this; anywhere a dialog is composed INLINE, whatever closes or swaps it must park focus first.
     *
     * Not a no-op alias: calling it names the rule at the call site, which is the point.
     */
    fun park(ids: String): Boolean = focusChain(ids)

    /**
     * Drop what we think has focus, so the next "has it landed?" check can only be answered
     * by a real focus change. [current] outlives the node it names — a modal that reopens
     * would otherwise read the id its own button held the previous time it was on screen.
     */
    fun forgetCurrent() { current = null }

    /**
     * **The gap detector.** Off by default; the app switches it on in debug builds.
     *
     * The rule these screens are built on is that a focused object knows, in advance, where the
     * ring goes for each of the four arrows and for BACK — so the logical order can never be
     * lost. A direction with no rule does not fail loudly: it quietly falls through to Compose's
     * geometric search, which walks by SCREEN POSITION and cheerfully leaves the page for the
     * sidebar. That is invisible until somebody presses that key on that screen, which is how
     * "I chose Find automatically and got thrown into the sidebar" reached a user rather than a log.
     *
     * With this on, every unruled press names itself — the ids of the focused node and the
     * direction — so the gaps can be listed by using the app instead of by reading 76 call sites.
     */
    @Volatile
    var auditGaps: Boolean = false

    /**
     * Set while a NATIVE view owns the D-pad — the browser's web page, the Choices offer.
     *
     * Those pages come written for a remote, so the Compose host deliberately hands focus over
     * and stops holding it. To anything watching from the Compose side that is indistinguishable
     * from focus having been lost, which is why it has to be said out loud: without this the
     * watchdog reports a page that is working exactly as designed, and a watchdog that cries wolf
     * gets switched off.
     */
    @Volatile
    var nativeOwnsInput: Boolean = false

    internal fun reportGap(direction: String) {
        if (!auditGaps) return
        val who = synchronized(focusedIds) { focusedIds.toList() }.sorted().joinToString("+")
            .ifEmpty { "(nothing focused)" }
        TvFocus.log.w("FocusNav", "NAV GAP: $who has no rule for $direction — geometric search will run")
    }
}

/**
 * The other half of the detector: the place a direction key ends up when **nothing** consumed it.
 *
 * The first version reported from inside `navKeys` itself, and it lied. A node commonly carries
 * SEVERAL navKeys elements — the input box has one for BACK, one for UP, one for DOWN — and each
 * one that does not handle the key would announce a gap even though the next element in the chain
 * handled it perfectly. Pressing UP on an input box logged "no rule for UP" while the ring was
 * moving to the header, which is exactly the kind of false alarm that teaches people to ignore a
 * log.
 *
 * `onKeyEvent` on a container fires during the BUBBLE phase — after the focused node and all its
 * modifiers have had their turn. Arriving here therefore means one thing only: nobody had a rule,
 * and Compose's geometric search is about to walk by screen position. Put it on the frame that
 * holds a page's content.
 */
fun Modifier.navAudit(): Modifier = onKeyEvent { e ->
    if (e.type == KeyEventType.KeyDown && FocusNav.auditGaps) {
        when (e.key) {
            Key.DirectionLeft -> FocusNav.reportGap("LEFT")
            Key.DirectionRight -> FocusNav.reportGap("RIGHT")
            Key.DirectionUp -> FocusNav.reportGap("UP")
            Key.DirectionDown -> FocusNav.reportGap("DOWN")
            else -> {}
        }
    }
    false // never consume: this only watches
}

/**
 * Register this node under [id] so navKeys/focus can target it — and report when it holds
 * focus, which is how [FocusNav.current] stays true without any extra bookkeeping.
 */
fun Modifier.focusId(id: String): Modifier =
    focusRequester(FocusNav.requester(id))
        .onFocusChanged { FocusNav.setFocused(id, it.isFocused) }

/**
 * Per-object key rules (web data-*-focus-id parity). A mapped key CONSUMES the event only
 * if the jump target exists; otherwise default focus search runs. [onBack] wins over [backTo].
 */
fun Modifier.navKeys(
    left: String? = null,
    right: String? = null,
    up: String? = null,
    down: String? = null,
    backTo: String? = null,
    onBack: (() -> Unit)? = null,
): Modifier = onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (e.key) {
        Key.DirectionLeft -> jump(left)
        Key.DirectionRight -> jump(right)
        Key.DirectionUp -> jump(up)
        Key.DirectionDown -> jump(down)
        // BACK is NOT audited when it is unruled, and that is deliberate: it has a real owner
        // further out (the page's own handler, then the activity's BackHandler), so falling through is the
        // designed path rather than a hole. Only a named target that could not be reached is
        // worth a word.
        Key.Back -> when {
            // SAY THAT YOU TOOK IT. On recent Android a consumed key no longer stops the system's
            // own back — without the claim the outer BackHandler acts on top of us (see BackClaim).
            onBack != null -> { BackClaim.claim(); onBack(); true }
            backTo != null -> jump(backTo).also { if (it) BackClaim.claim() }
            else -> false
        }
        else -> false
    }
}

/**
 * One jump. False (the key falls through) when there is no target or it is not attached.
 *
 * With [FocusNav.auditGaps] on it also says what it tried. That single line separates the two
 * failures that look identical on a TV — the ring simply does not move:
 *  - `jump 'x' -> MISSING`: nothing is registered under that id (typo, or the node is not composed).
 *  - `jump 'x' -> requested` with **no** following `focus -> x`: the requester was attached, so
 *    this returned true, but the focus never moved. That is the silent veto — a scroll container
 *    or a focus group refusing a transfer across its boundary.
 */
private fun jump(target: String?): Boolean {
    if (target == null) return false
    // A WALL — a rule naming the very node it is written on — must simply eat the key.
    //
    // It used to take the long way round: requestFocus on the node that ALREADY holds focus,
    // which reports that nothing moved, so focusSticky retried for a few frames, gave up, and
    // then REMEMBERED the failure for four seconds. Inside that window the wall returned false,
    // the key fell through to Compose's geometric search, and the ring flew off to whatever was
    // nearby — measured at the app grid's "+": first press held, second did nothing, third
    // jumped up into a widget.
    // isFocused, not `current`: a node commonly answers to SEVERAL ids and `current` names
    // only the last one registered, so comparing against it misses the wall half the time.
    //
    // IN CHAIN ORDER, and that ORDER IS THE POINT. The first version asked "is ANY id in the
    // chain the focused node?" and ate the key if so — which broke every fallback chain that ends
    // on itself. Widget edit mode is exactly that shape: UP from the top row is
    // "wedit-toggle,widget-slot-<p>", so the wall at the tail swallowed the press and the owner
    // could not reach «End edit» at all — no way out of edit mode (reported from a TV).
    // Walk it: a real target that takes focus wins; reaching MYSELF means the chain has run out
    // of places to go, and only then is it a wall.
    for (id in target.split(',')) {
        val one = id.trim()
        if (one.isEmpty()) continue
        if (FocusNav.isFocused(one)) return true          // the chain came home: a wall
        if (FocusNav.focus(one)) return true              // landed on a real neighbour
    }
    val ok = FocusNav.focusSticky(target)
    if (FocusNav.auditGaps) TvFocus.log.i("FocusNav", "jump '$target' -> ${if (ok) "requested" else "MISSING"}")
    return ok
}
