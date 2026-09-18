package com.smartago.tvfocus

/**
 * WHO TOOK THE BACK KEY — the referee between key handlers and the system's own back.
 *
 * THE PROBLEM (measured on Android 16). BACK navigation in a D-pad app is naturally built on
 * **key events**: a handler catches `Key.Back`, does its job (return to the parent page, close
 * the drawer, leave move mode) and returns `true` to consume it. Up to Android 14 that was
 * enough: if somebody consumed KEYCODE_BACK, the system never called `onBackPressed`.
 *
 * From targetSdk 35 the back gesture arrives through a **separate channel** (predictive back)
 * that never asks the key handlers. So BOTH run: our handler does its job correctly, and right
 * after it the root screen's `BackHandler` — which believes nobody dealt with it — closes
 * whatever it hosts. In practice: BACK inside a settings overlay returned to the parent page
 * AND closed the whole overlay at the same time.
 *
 * WHY THIS AND NOT A RESTRUCTURE. The "proper" Compose answer is nested `BackHandler`s instead
 * of key rules — but that means rewriting every `backTo` and every BACK handler in an app that
 * works TODAY on every user's device, tested only on the platform that already behaves
 * differently. Instead: whoever handles BACK DECLARES it here, and the outer `BackHandler` asks
 * first whether somebody inside already took it. On older Android [consume] is never called
 * (the key was consumed, the system does not proceed) — so ZERO behaviour change there. On new
 * Android the double fire goes away.
 *
 * The time window is not "hoping it is fast enough": the claim and the question happen inside
 * the SAME input cycle, milliseconds apart. It exists only so that a claim never followed by a
 * system back (older Android) does not sit around and swallow a later, real BACK.
 *
 * Usage, at the root of the screen:
 * ```
 * BackHandler { if (!BackClaim.consume()) closeOverlay() }
 * ```
 * [Modifier.navKeys] claims automatically whenever its `backTo`/`onBack` rule acts.
 */
object BackClaim {
    private const val WINDOW_MS = 1000L
    private var at = 0L

    /** "I took it" — called by every key handler that actually acted on BACK. */
    fun claim() {
        at = android.os.SystemClock.uptimeMillis()
    }

    /**
     * The outer `BackHandler` asks THIS first. `true` = somebody inside already did the work,
     * do nothing more. The answer is consumed.
     */
    fun consume(): Boolean {
        val claimed = at != 0L && android.os.SystemClock.uptimeMillis() - at < WINDOW_MS
        at = 0L
        return claimed
    }
}
