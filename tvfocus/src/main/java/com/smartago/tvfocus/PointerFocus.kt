package com.smartago.tvfocus

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInputModeManager

/**
 * **The pointer and the D-pad drive the SAME focus.**
 *
 * On a TV box the two are not alternatives: a user with an air mouse puts the cursor on a row,
 * then picks up the remote and presses DOWN. If the pointer never moved the focus, the ring is
 * still wherever the D-pad left it and the two inputs disagree about what is selected — the next
 * OK opens something the user was not looking at. The question that started it: *"what happens if a user
 * has an air mouse and plays between air mouse and D-pad?"*
 *
 * So two events focus, which is what the launcher's own card component has always done:
 *  - **hover** — the pointer enters and then MOVES inside the element (web `:hover`);
 *  - **tap-up** — a click or tap, on RELEASE.
 *
 * **Hover alone is not enough**, which is the trap this had fallen into: a touch never hovers,
 * and an air mouse can land a click on an element the cursor reached without a Move in between.
 *
 * **Never at press.** Focusing on the DOWN edge moves the layout under the finger — a rail
 * expands, the row slides, and the click is cancelled before it lands. At release the element is
 * still where it was pressed.
 *
 * **PHANTOM-ENTER GUARD**: when a layout shift slides an element UNDER a
 * stationary cursor, Compose synthesizes an Enter. Focusing on that steals focus with the user's
 * hand nowhere near the mouse — opening a settings overlay collapsed the rail and a category grabbed
 * the ring. So an Enter only ARMS the hover; a real Move inside the element commits it.
 *
 * **TOUCH MODE — why asking for focus is not enough.** A pointer event puts the window into
 * Android's *touch mode*, and there the platform clears focus and refuses it to anything that is
 * not editable. `requestFocus()` then reports SUCCESS and the focus is dropped a moment later —
 * traced exactly that way on the emulator, with the next D-pad press falling out of the pane.
 *
 * So each of the two events below asks the window to leave touch mode FIRST, through Compose's
 * own [InputModeManager]: `requestInputMode(InputMode.Keyboard)` is the supported way to say
 * "this window is being driven like a remote now", and it is a WINDOW-level switch, which is why
 * one call from any element fixes the whole screen. Only then is focus requested.
 *
 * A retry one frame later was tried instead and changed nothing, which is the tell: the request
 * was never the problem, the mode was.
 *
 * Place it BEFORE `clickable` in the chain: its [FocusRequester] binds to the focus target that
 * `clickable` creates. It reads events in the Main pass and consumes nothing, so `clickable`'s own
 * tap/OK handling still fires exactly once.
 */
fun Modifier.pointerFocus(): Modifier = composed {
    val requester = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    Modifier
        .focusRequester(requester)
        .pointerInput(Unit) {
            awaitPointerEventScope {
                var pendingHover = false
                while (true) {
                    // FINAL pass: `clickable` sits BELOW this in the chain and handles the
                    // up-event in the Main pass, which includes its own focus handling. Asking
                    // in Main meant our request landed first and was then undone; in Final we
                    // are the last word on where the ring goes.
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    when (event.type) {
                        PointerEventType.Enter -> pendingHover = true
                        PointerEventType.Exit -> pendingHover = false
                        PointerEventType.Move -> if (pendingHover) {
                            pendingHover = false
                            takeFocus(inputMode, requester)
                        }
                        PointerEventType.Release -> takeFocus(inputMode, requester)
                        else -> {}
                    }
                }
            }
        }
}

/**
 * **The other half of the touch-mode rule: the taps that land on NOTHING.**
 *
 * [pointerFocus] leaves touch mode on every element that takes focus from a pointer — but a tap
 * on the wallpaper, on a panel's empty area, on a caption, or anywhere else with no focusable
 * under it still puts the window into touch mode, and nothing there asks it back. The window is a
 * WINDOW: the mode outlives the screen that set it, so every page opened afterwards composes with
 * no focus, the ring drops to the first focusable in the tree (the sidebar) and the page's landing
 * effect drags it home again. That is the "it sticks on the other screens too" report, and it is
 * one mode switch behind it, not a bug in each page.
 *
 * So put this at the ROOT of a TV screen's tree, where every pointer event in the window passes
 * through: on release it asks for keyboard mode and NOTHING else. It never requests focus, so it
 * cannot take the ring from wherever the user left it — and if a child's [pointerFocus] is also
 * about to move the ring, both are asking for the same mode and the order does not matter.
 *
 * Not needed in a phone UI: there touch mode is what the device actually is.
 */
fun Modifier.keyboardInputMode(): Modifier = composed {
    val inputMode = LocalInputModeManager.current
    Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                // Final pass, consuming nothing: whatever a child did with this event, we only
                // read it. RELEASE is the same edge pointerFocus acts on — asking on the DOWN
                // edge would flip the window mid-gesture for no gain.
                val event = awaitPointerEvent(PointerEventPass.Final)
                @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
                if (event.type == PointerEventType.Release) {
                    inputMode.requestInputMode(InputMode.Keyboard)
                }
            }
        }
    }
}

/**
 * Leave touch mode, then take the focus — in that order, or the platform throws the focus away
 * again (see the note on [pointerFocus]).
 */
// requestInputMode is still experimental; it is the only supported way out of touch mode.
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun takeFocus(inputMode: InputModeManager, requester: FocusRequester) {
    inputMode.requestInputMode(InputMode.Keyboard)
    runCatching { requester.requestFocus() }
}
