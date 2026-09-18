# TV Focus Kit

**D-pad focus for Jetpack Compose on Android TV, the way a launcher needs it.**
One traveling focus ring, explicit arrow rules per element, pointer and remote sharing the same focus, and a crash-proof `FocusRequester`. Extracted from a shipping Android TV launcher; MIT.

[![build](https://github.com/smartago/tv-focus-kit/actions/workflows/build.yml/badge.svg)](https://github.com/smartago/tv-focus-kit/actions)
[![JitPack](https://jitpack.io/v/smartago/tv-focus-kit.svg)](https://jitpack.io/#smartago/tv-focus-kit)
![minSdk 23](https://img.shields.io/badge/minSdk-23-informational)
![license MIT](https://img.shields.io/badge/license-MIT-green)

```kotlin
FocusRingHost(Modifier.fillMaxSize().keyboardInputMode().navAudit()) {
    Tile(
        Modifier
            .focusId("tile-3")
            .navKeys(left = "tile-2", right = "tile-4", up = "rail-0", down = "tile-7", backTo = "rail-0")
            .focusRingTarget(corner = 14.dp)
            .pointerFocus(),
    )
}
```

---

## The five problems it solves

Every one of these was found on real TV boxes, not in an emulator. The comments in the source carry the full story of each.

**1. Compose's default focus search walks by screen position and leaves your page.**
Press DOWN on the last row of a settings page and the ring flies to the sidebar, because that is the nearest focusable in that direction. `navKeys` lets every element say where each arrow goes, by id; unruled keys still fall through to Compose. A rule that names the element itself is a *wall*. A fallback chain (`"widget-a,widget-b,rail-0"`) takes the first target that exists. Targets that are mid-layout get a few frames to appear (`focusSticky`) instead of the key falling through.

**2. `FocusRequester is not initialized` — from inside key dispatch, where you cannot catch it.**
Point `focusProperties { left = requester }` at a requester nothing holds and the app dies the moment the user presses LEFT. This was a launcher's top crash: 79 reports in one day. `FocusAnchor` counts its holders and yields `FocusRequester.Default` when nobody is attached, so the worst case is a plain geometric move.

**3. Per-element focus borders look wrong on a TV; one ring that *travels* looks right.**
`FocusRingHost` draws a single ring above the whole window that animates between targets (300 ms, `cubic-bezier(.25,.8,.25,1)`) and re-reads the focused element's bounds **every frame**, so it never goes stale when a row slides or a keyboard appears. Breathing glow, rendered from a cached blur mask so it costs nothing per frame. `InstantFocusRing` for popups, `HideFocusRing` for overlays, `FocusRingOccluder` to punch a hole where a floating control must look like it sits above the ring.

**4. An air mouse and the D-pad must drive the same focus — and *touch mode* silently drops it.**
A pointer event puts the window into Android's touch mode, where `requestFocus()` reports success and the focus is thrown away a moment later. `pointerFocus` moves focus on hover-move and on tap-release (never on press — the layout moves under the finger), leaving touch mode first through Compose's own `InputModeManager`. `keyboardInputMode` at the root handles taps that land on nothing.

**5. Predictive back (targetSdk 35+) fires your key handler *and* the outer `BackHandler`.**
Both run: your handler goes back one page, then the root `BackHandler` closes the whole overlay. `BackClaim` is a one-line referee: whoever handled BACK claims it, the outer handler asks first. Zero change on older Android.

Plus **`navAudit`** — a gap detector. Switch it on in debug builds and every arrow press that no rule covers names the focused element and the direction in logcat, so the holes on a screen are listed by *using* it, not by reading every call site.

---

## Install

JitPack, until the Maven Central listing is up:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io") { content { includeGroup("com.github.smartago") } }
    }
}

// build.gradle.kts
dependencies {
    implementation("com.github.smartago:tv-focus-kit:1.0.0")
}
```

Requirements: minSdk 23, Compose BOM 2024.12 or newer. No other dependencies.

## Use

| You want | Do this |
|---|---|
| a single traveling ring in the window | wrap the window's content once in `FocusRingHost { }` |
| an element the ring wraps when focused | `Modifier.focusRingTarget(corner = its corner radius)` |
| the ring to stay on a row while a field inside it has focus | `focusRingTarget(within = true)` |
| an element other rules can target | `Modifier.focusId("my-id")` |
| explicit arrow / BACK behaviour | `Modifier.navKeys(left = …, right = …, up = …, down = …, backTo = … / onBack = { })` |
| the pointer to move the same focus | `Modifier.pointerFocus()` **before** `clickable` |
| taps on empty space not to strand the window in touch mode | `Modifier.keyboardInputMode()` on the root |
| a safe requester for `focusProperties` | `val a = rememberFocusAnchor()` → `Modifier.focusAnchor(a)` → `focusProperties { left = a.orDefault() }` |
| focus something now, from code | `FocusNav.focusSticky("id")` / `FocusNav.focus("id")` |
| focus to move *before* the focused node is removed | `FocusNav.park("where-it-should-go")` |
| the ring to jump, not glide (popup menus) | `InstantFocusRing()` inside the popup |
| the ring hidden under a full-screen overlay | `HideFocusRing()` inside the overlay |
| predictive-back to stop double-firing | `BackHandler { if (!BackClaim.consume()) close() }` |
| to see which arrows have no rule | `FocusNav.auditGaps = true` + `Modifier.navAudit()` on the page frame |
| the kit's logs in your own buffer | `TvFocus.log = object : TvFocus.Logger { … }` |

Run the [sample](sample/src/main/java/com/smartago/tvfocus/sample/MainActivity.kt) on an Android TV emulator: a rail and a grid, every arrow ruled, `auditGaps` on.

## Design notes

- Ids are app-global strings. That is deliberate: rules are written where the element is, and read like the layout.
- `navKeys` consumes a key **only** if its target exists right now; otherwise Compose's own search runs. Nothing here replaces Compose focus — it steers it.
- A target that never existed is never retried (a typo should not eat keys). A target that existed but is not attached is retried for ~8 frames, once; a second press always falls through.
- The ring is drawn by the host, not by the element. Elements with their own focus styling simply do not register as targets.

## Who made this

[Smartago](https://smartago.net) — the people behind [Premium TV Launcher UI (PLUI)](https://github.com/smartago/plui-tv-launcher), where this code runs on thousands of TV boxes. The kit is the launcher's focus layer, verbatim, with the product names removed.

MIT © 2026 Smartago
