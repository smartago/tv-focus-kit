package com.smartago.tvfocus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/**
 * A [FocusRequester] that knows whether any node is currently holding it.
 *
 * WHY THIS EXISTS — this was a launcher's top crash (79 reports in one day on one build):
 *
 *     java.lang.IllegalStateException: FocusRequester is not initialized
 *         at androidx.compose.ui.focus.FocusRequester.findFocusTargetNode
 *         at androidx.compose.ui.focus.FocusOwnerImpl.focusSearch
 *         at ...AndroidComposeView$keyInputModifier$1.invoke
 *         at ...MainActivity.dispatchKeyEvent
 *
 * A `focusProperties { left = someRequester }` target must be attached to a composed node.
 * Point one at a requester nothing holds and Compose throws the moment the user presses that
 * direction — from INSIDE key dispatch, where a runCatching of ours cannot reach it, so the
 * whole app goes down. A home screen did exactly that: `sidebarFocus` is attached only to the
 * category matching the current selection, and `firstTileFocus` only when the category has apps,
 * yet the tiles and the "+" bar named them unconditionally. Select a category that is no longer
 * rendered (deactivated, deleted, mid-reload) or open an empty one, press LEFT or DOWN, and the
 * launcher dies.
 *
 * [orDefault] closes that hole: when nothing holds the anchor it yields [FocusRequester.Default],
 * which means "just do the normal geometric search" — the user gets a slightly less opinionated
 * move instead of a crash.
 *
 * The counter (rather than a boolean) matters during a reorder: the incoming node attaches
 * before the outgoing one detaches, and a boolean would be left reading false.
 */
@Stable
class FocusAnchor {
    val requester = FocusRequester()
    private var holders by mutableIntStateOf(0)

    internal fun attach() { holders++ }
    internal fun detach() { holders-- }

    /** Safe to name from `focusProperties`. */
    fun orDefault(): FocusRequester = if (holders > 0) requester else FocusRequester.Default

    /** Direct focus request; false when nothing holds the anchor (never throws). */
    fun request(): Boolean =
        holders > 0 && runCatching { requester.requestFocus() }.isSuccess
}

@Composable
fun rememberFocusAnchor(): FocusAnchor = remember { FocusAnchor() }

/** Attach this node to [anchor] — the counterpart of [FocusAnchor.orDefault]. */
fun Modifier.focusAnchor(anchor: FocusAnchor): Modifier = composed {
    DisposableEffect(anchor) {
        anchor.attach()
        onDispose { anchor.detach() }
    }
    focusRequester(anchor.requester)
}
