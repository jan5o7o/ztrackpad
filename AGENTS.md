# AGENTS.md — Z Trackpad

## What this is

A standalone Android input app: floating trackpad + pointer + programmable keys
panel + display picker. Built on-device in Termux with a **hand-rolled build**
(`build.sh`) — there is **no Gradle, no Kotlin, and no SDK install**; the app is plain
Java. The build downloads only the platform jar it compiles against.

Reference environment — everything the verification list below was measured on — is a
**Galaxy Z Fold 4** (SM-F936B), aarch64, Android 16 / One UI, in Termux, with no desktop.
Nothing in the build is device-specific, so another host with the same tools should work;
read "verified working" at the end of this file as *on that device*.

## Publishing

Development happens in a private repo; `jan5o7o/ztrackpad` is **generated** from it, not
cloned from it — so edits made in the public tree are overwritten by the next sync. If you
are reading this in the generated copy, open an issue rather than a pull request.

In the development repo, **read `PUBLISHING.md` before any work that ends in a public
deployment**: it covers the local `public` branch, the three trees, the `ztrackpad-sync`
guards (dirty worktree, private-string leak scan, generated-path pre-flight), the
invariants (no secret in a tracked file, rebranding only ever happens on `public`) and the
merge-never-rebase trap. `PUBLISHING.md` is private-only and is excluded from the export,
so it is absent from the generated tree by design.

## What an agent cannot do here

Stop and ask rather than improvising around these. They are the only steps in the
whole workflow that are not shell-doable, and a human is required for each.

- **Enabling wireless debugging** — a Developer-options toggle. Assume it is already on,
  then verify with `adb devices`.
- **Starting Shizuku** — needs the Shizuku app, or a human-initiated start. It is not
  persistent without root, so this recurs after **every reboot**.
- **Granting Shizuku's permission** — a runtime dialog; `adb shell pm grant` does not
  cover it.
- **Tapping the UI** — possible via `adb shell input tap X Y`, but fragile: the panels
  move and resize, and the display picker grows a row per display, so a tap one row off
  hits the wrong control. Prefer the broadcast API in `skills/ztrackpad-vdisplay/`.

Everything else — packages, fetching the jar, build, sign, `adb install`, enabling the
accessibility service, verifying, and the virtual display's lifecycle — is shell-only.

## Build & install

```bash
cd ~/ztrackpad
./build.sh                      # aapt2 -> aidl -> javac -> d8 -> apksigner
adb install -r out/ztrackpad.apk
```

`tests/smoke.sh` covers everything that can be checked without fingers: adb device and
package discovery, `shizuku=ready`, the 8-field status schema, the keys-layout and pad-lock
round-trips (both restored to their prior value afterwards) and the implicit-broadcast trap.
The virtual-display lifecycle is off by default because it has a visible side effect:
`tests/smoke.sh --with-display`. Run it after installing, against whatever is installed —
it discovers the package id instead of hardcoding one, so it needs no renaming in the public
tree.

Signing needs the keystore password, which is **deliberately not in the repo**: set
`KSPASS` in the environment, or keep it in `~/.ztrackpad-kspass`. `build.sh` fails
closed when neither is present.

- `sdk/platforms/android-36/android.jar` is gitignored; restore it from
  `https://dl.google.com/android/repository/platform-36_r02.zip`.
- `libs/*.jar` (Shizuku family) are committed so the build is reproducible.
- `keystore.jks` (signing key) and `out/`, `build/` are gitignored.
- If `adb install` fails, copy APK to `/sdcard` and `pm install -r` via adb shell.

## Architecture

| File | Role |
|---|---|
| `java/.../TrackpadService.java` | the whole UI + gesture logic (accessibility service) |
| `java/.../ShellUserService.java` | runs **inside Shizuku with shell UID**; does the real input injection |
| `java/.../ShizukuInputHandler.java` | client side: permission, bind user service, wrappers |
| `java/.../VDisplayReceiver.java` | broadcast entry point so scripts can create/show/hide/destroy the virtual display |
| `java/.../Theme.java` | the five visual presets and every themed colour/radius |
| `skills/ztrackpad-vdisplay/` | pi skill + `scripts/vdisplay` for driving it from Termux |
| `extensions/vdisplay.ts` | pi extension exposing that script as a `vdisplay` tool |
| `aidl/.../IShellService.aidl` | binder interface between the two |
| `build.sh` | the hand-rolled build pipeline |
| `libs/` | shizuku api/provider/aidl/shared + androidx-annotation jars |

Key mechanism: the shell process (shell UID) calls
`InputManager.injectInputEvent` via reflection. That bypasses accessibility
gesture arbitration — which is exactly why a Shizuku side exists at all
(`dispatchGesture` cannot sustain a finger-driven drag; it keeps cancelling it).

Display targeting: every injected event carries a `displayId`, now a field on
`ShizukuInputHandler` (`setDisplayId`). `TrackpadService` keeps two sizes —
`screenW/H` for the **surface** display the panels live on, `outW/H` for the
**target** display the pointer drives. `ShellUserService` already supported this
(`setDisplayId.invoke(ev, displayId)`), so **no shell-side change was needed** —
only the client's hardcoded `0`s.

## Scripting the virtual display

`VDisplayReceiver` (exported, action `app.so7o.ztrackpad.VDISPLAY`) forwards to
`TrackpadService.vdisplayCommand(op, headless, spec, arg)`, which does the real work on the
main thread. Scripts go through it; `skills/ztrackpad-vdisplay/scripts/vdisplay` is the reference
implementation, and `extensions/vdisplay.ts` wraps it for pi.

```bash
adb shell am broadcast -n app.so7o.ztrackpad/.VDisplayReceiver -a app.so7o.ztrackpad.VDISPLAY --es op status
```

Ops: `status`, `create` (+`--ez headless true`), `show`, `hide`, `destroy`, `keys`
(`--es spec '<layout>'`, no spec = read it back), `keys-reset`, and `lock`
(`--es arg on|off|toggle`) - the lock is the one non-display op, and it drives the same
`setPadLocked` the pad's lock dot does, so the two cannot disagree.

- **The `-n` component is mandatory**: an implicit broadcast never reaches a
  manifest-declared receiver on API 26+, and the failure is silent (result=0 with no
  `data=`), which is easy to misread as success.
- `status` replies with `shizuku=ready id=N kind=floating|headless|none window=shown|hidden
  surface=alive|detached vsize=WxH target=N padlocked=true|false keys=default|custom`;
  scripts parse that line.
- `create` is asynchronous — the floating display is built from the SurfaceView's
  surface callback, so poll `status` until `kind=floating`.
- The receiver is exported with no permission: any app can toggle the display.
  Deliberate (it keeps the adb one-liner simple), and the worst case is a display
  appearing or disappearing.

## Conventions

- **One app, one APK.** No build variants, no companion APK. The pad and the virtual display
  both need the accessibility service and Shizuku, so splitting them by package would
  multiply grants and shell processes without removing a dependency - and a keys-vs-no-keys
  variant is a visibility flag, not a boundary. If subsystem isolation is ever needed it is
  by **class**, never by package.
- **Java 8 syntax** (`javac -source 8 -target 8`), no lambdas (d8 desugaring
  risk) — use anonymous classes everywhere.
- Haptics: `tick()` → `VibrationEffect.createPredefined(EFFECT_CLICK)`, gated on
  `haptic_feedback_enabled`, once per press.
- Auto-repeat: `attachRepeat(view, action, repeatable)` — 420ms delay, 60ms
  interval; never tick per repeat.
- Press feedback: `keyBgState(normal, pressed)` = state-list drawable.
- Window geometry helpers are generic (`saveGeometry(lp, prefix)` etc.), shared
  by pad and keys panel. `PAD_KEY=""` preserves old pref names; `KEYS_KEY="k_"`.
- **Resize grips**: all four corners resize, but only the bottom-right one is drawn
  (`addResizeGrips(..., includeTopLeft)`) and it is a `GripView` — three diagonal strokes,
  no background. An invisible grip is just a `View` with no background carrying the same
  touch listener, so do not "remove" a grip to tidy the UI: that removes the resize too.
  The pad passes `includeTopLeft=false` when the `◐` menu button lives in that corner —
  it did while the button was in the title bar, and no longer does now that the button
  sits below the handle.
- **Never test Gravity bits with `&`.** `Gravity.LEFT` is 3 (`0b011`) and `Gravity.RIGHT`
  is 5 (`0b101`), so they share bit 0 and `(side & Gravity.RIGHT) != 0` is true for LEFT
  as well. That silently gave every docked dot a `rightMargin` and no `leftMargin`, so the
  theme dot sat flush against the pad edge while its mirror was inset correctly — a
  visible asymmetry with no error anywhere. Pass an explicit `alignRight` boolean, never a
  gravity mask.
- **The theme + opacity menu** hangs off the `◐` dot just under the pad's title bar,
  NOT in the handle: inside it the button fights the drag gesture, and the whole bar
  should stay draggable. It replaced both a hamburger and an earlier floating bubble —
  one control, one job. The `▣` display picker is docked the same way, mirrored on the
  right, via `addDockedDot`; the lock is a third dot beside the theme one (`slot` counts
  inward on a side, so two dots never land on each other). Opacity scales
  only panel *fills* via `fill()`, and the slider applies on release: `applyTheme()`
  rebuilds the panels, which inside a `SeekBar` touch callback would delete the slider
  mid-drag, so the release posts the rebuild instead.
- Overlay z-order follows **add order**; raise via remove+re-add (`raise()`).
  The pad must stay above the keys panel to win overlap taps.
- **The pad's gesture grammar is decided at ACTION_DOWN**, never on first move: the hold
  timer (`scheduleHold`), the tap-then-drag window and the edge strip all have to claim a
  touch there, or a slow press gets misread as a drag (or a drag as a click). The edge
  strip is scroll-only on purpose - the pointer is elsewhere, so a click from there would
  land where the finger never was - and it does not disturb `dragArmed`, so an armed drag
  survives a scroll.
- **`updateViewLayout` queues, it does not apply.** `setPanelsTouchable(false)` returns
  before the window manager has acted, so click-through must post the injection a couple of
  frames later and hold `FLAG_NOT_TOUCHABLE` for the gesture's whole duration
  (`injectThroughPanels(inject, gestureMs)`, `TOUCHABLE_SETTLE_MS`). Injecting immediately
  after the flag request looked correct and silently did nothing whenever the pointer was
  over a panel - and a long press needed the flag up for all of `HOLD_MS`, or its UP landed
  on a touchable window again.
- **The split divider needs a touch, not a mouse.** The drags that move app windows go out
  as `SOURCE_MOUSE` (`mouseDown/move/up`); the divider ignores those, and moves for a
  `SOURCE_TOUCHSCREEN` DOWN/MOVE/UP with one shared `downTime` (`shizuku.touch(...)`). Same
  panels-non-touchable rule as click-through, including the settle delay before the DOWN -
  the divider is often under the pad.
- **Split geometry comes from `dumpsys window` over the Shizuku bridge**, not from
  `AccessibilityWindowInfo`: retrieving windows needs `flagRetrieveInteractiveWindows` and
  this service runs `flagDefault`, while the shell bridge is already there and needs no
  permission. `parseSplit` takes the two biggest app windows in the visible list as the
  panes (our windows, system chrome and the IME all get filtered out, because some of them
  are bigger than a pane), the window named `SplitDivider` as the divider, and refuses
  anything that is not a stacked top/bottom pair. Each press is therefore a shell round trip
  - which is also why the buttons are not repeatable: a hold would queue work that lands
  long after the finger is gone.
- **The split buttons sit on the left edge-scroll strip** and take over their `dp(58)` of
  its height, and they are added *before* `addResizeGrips` on purpose - a FrameLayout gives
  touches to the newest child first, so where a short pad makes them overlap a corner, the
  grip still wins.
- **A docked dot or grip beats the touch surface underneath it.** The strips live in the
  surface, so the theme/lock dots and the display dot blank out their slice of the top of
  each strip, and the four corner grips own their corners. Fine, but it is why the strip
  can never be touched there.
- **The key layout is data** (`DEFAULT_KEYS_SPEC`, in `TrackpadService`), parsed by
  `buildKeyRowsFromSpec`. The built-in layout and a custom one take exactly the same path,
  which is why `op=keys` can hand the current layout back to be edited. Adding or moving a
  key means editing that string, not Java — and `padRow` is no longer something to get
  wrong by hand, because each row's widths are summed as it is built.
- **Escapes must survive every split level.** `splitEscaped` KEEPS the backslashes;
  `unescape` is applied only to a leaf value. Unescaping early meant `\:` had already
  become a bare `:` by the time the field split ran, so every key carrying `;m=` silently
  disappeared — visible only as a short row.
- **`adb shell` re-parses its arguments in a shell on the device.** It does not exec argv
  directly, so a keys spec containing `|` or `;` is run as shell syntax and silently
  truncated (`/system/bin/sh: sp:space: inaccessible`). `scripts/vdisplay` quotes the spec
  for the remote shell via `sq()`; a raw `am broadcast` line does not.
- **Colours and radii come from `Theme`, never from a literal.** They used to be inline
  hex, and the same literal meant different things in different panels (`0x66FFFFFF` was
  a panel border in one place and dim text in another), so fields are named by role.
  Adding a preset = one `static` block + one `PRESETS` entry.
- **An emoji in overlay text ignores `setTextColor`.** The padlock was `\uD83D\uDD12`
  and rendered in the emoji font's own colours no matter what the theme said, so the lock
  is now drawn (`LockDot`: ring + a thin outlined body with a shackle arc) in `textDim`.
  One icon in both states on purpose - the pad's state is the handle's word, `≡ MOVE` or
  `≡ LOCKED`, so the dot does not flicker under the finger that just tapped it. Any other
  icon in a themed dot has the same choice to make. The per-dot colour roles are
  `bubbleTrack` / `bubbleKeys` / `bubbleDisplay` / `bubbleTheme`.
- **A theme change rebuilds the panels** (`applyTheme`), because every background is a
  generated drawable built at construction time. Save geometry first, or the rebuild
  falls back to defaults, and restore every panel's visibility including the theme
  panel itself — otherwise you cannot try presets in a row. Bubbles are restyled in
  place instead, since their positions are not persisted.
- `Theme.DEFAULT` mirrors the original hardcoded values exactly. Keep it that way: it is
  the regression test, and it is verifiable by eye.
- Displays: **never persist a display id** — they are reused (`Overlay #1` is id 7
  on this device, not 2). Persist `displayKey()` = name + *physical mode size*
  (physical rather than `getWidth()` so rotation does not change the key, and size
  because this device has two displays both called "Built-in Screen").
- `DisplayManager.getDisplays()` is filtered for apps and yields only the default
  display on this device; `DISPLAY_CATEGORY_PRESENTATION` is empty too. But
  `getDisplay(id)` is *not* filtered — so enumerate by probing ids 0..63, with a
  cached `dumpsys display` sweep via Shizuku as a fallback.
- This SDK's `Display` has **no** `getUniqueId()` and **no** `getDensity()`
  (use `getRealMetrics(DisplayMetrics).densityDpi`).

## Known behaviours / gotchas

- `setCursorVisibility` does not exist on this Samsung build; hiding the system
  pointer uses `InputManager.setPointerIconType(0 /* TYPE_NULL */)`.
- Samsung `FreecessHandler` may freeze the background app — the accessibility
  service keeps it alive once enabled.
- Click-through (`injectThroughPanels`) drops `FLAG_NOT_TOUCHABLE` for ~110ms
  while injecting; only used from tap paths (finger already up). The drag path
  must NOT use it.
- `screencap` on this device defaults to the **cover screen**; use
  `screencap -d <HWC-display-id>` (see `dumpsys SurfaceFlinger --display-id`).

## Verification status

Confirmed on-device (Galaxy Z Fold 4 / SM-F936B, One UI, Android 16) unless marked
otherwise. Keep
this list honest — do not move rows up without actually re-testing.

**Verified working**

- **`tests/smoke.sh` passes against the installed build** — 11 checks, 14 with
  `--with-display`: device and package discovery, `shizuku=ready`, the 8-field status schema,
  the keys-layout and pad-lock round-trips, the documented implicit-broadcast trap, and
  `destroy -> create --headless -> destroy` on a real display (display 61 released). It ran on
  device for the first time on 2026-09-25 and immediately caught a bug in itself: it restored
  the keys *spec* but not the `default`-vs-`custom` flag, so a built-in layout came back as
  `keys=custom`. Restoring a built-in layout now goes through `keys-reset`, and both cases are
  confirmed — built-in stays `default`, and a real custom spec survives byte-identical.

- Overlay windows: bubble, keys bubble, drawn pointer, trackpad pad, keys panel —
  all `TYPE_ACCESSIBILITY_OVERLAY`, drag + 4-corner resize + geometry persistence.
- Trackpad: tap → click, hold-still-then-move → drag, tap-then-drag (300ms window).
- **Hold-to-repeat** on the keys panel (34 × `KEYCODE_DEL` in a 2.5s hold) and on
  the trackpad `⌫` + arrows (`attachRepeat`, 420ms then every 60ms).
- Haptics: `EFFECT_CLICK`, `usage: TOUCH`, attributed to `app.so7o.ztrackpad`
  (checked via `dumpsys vibrator_manager`).
- Shizuku: shell user service bound as shell UID, `InputManager.injectInputEvent`
  through reflection; `rikka.shizuku` 13.1.5.
- Arrow keys via `performGlobalAction(GLOBAL_ACTION_DPAD_*)` — all four accepted.
- Keys-panel macros: `CTRL+b`, `CTRL+a`, `CTRL+p`, `CTRL+b b`, `ALT+v`, plus
  `ESC`/`TAB`/modifier meta codes (e.g. `?` → `key 76 meta=1`).
- Click-through toggle fires: `panels touchable=false → true` around tap injection.
- **Two-finger tap → right-click**, confirmed by hand (adb cannot inject
  multitouch, so this needs the owner's fingers to test).
- **Pad lock** (the dot beside the theme dot, under the handle). With the lock on, an
  injected drag on the move handle *and* on the bottom-right grip left the frame at
  `1242,1470-1812,2176` exactly; unlocked, the same handle drag moved it by the finger
  delta (`-127,-308` → `1116,1164-1686,1870`) and a reverse drag restored the original
  frame, and the grip drag resized until it clamped at the `dp(240)` minimum width.
  Both states screenshotted: the dot is the same thin grey outline lock in each, and the
  handle reads `≡ LOCKED` while frozen. The flag persists
  across a service restart (it came back locked after `adb install -r`), and `status`
  reports `padlocked=`.
- **Edge scrolling**: a touch that starts within `dp(28)` of the pad's left or right edge
  scrolls instead of moving the pointer, through the same `scrollBy()` the two-finger
  drag uses. Measured: a 120px finger swipe on the strip moved a Settings page **431px**,
  in the touch/natural direction (finger up, content down), while the same swipe in the
  middle of the pad moved the screen by 0.4 (pointer only, no scroll). The pad was
  **locked** for that test, which is correct: the lock freezes geometry, not scrolling.
  Injected swipes, so the *feel* (gain/step) is unverified by hand.
- **Split-divider buttons** (the two round ↑/↓ dots on the pad's *left* edge, `dp(16)` apart).
  Each press nudges the divider by a fixed **8% of the screen height** - 174px on this
  2176px display - with ↑ raising it so the BOTTOM pane grows and ↓ the reverse. Measured
  exact over four presses: `1088 -> 914 -> 740` then back `740 -> 914 -> 1088`, each one
  landing on the wanted value with grab offset 0. Twelve presses across runs have all been
  exact, so a nudge is trusted where a *target* was not: aiming the divider at a rung of
  50/75/90% produced landings like `y=150 -> y=1057` and `93% -> 15%`, and One UI snapping
  or flinging it is the likeliest reason. One press right after `adb install -r` did
  nothing (service still settling) - retry rather than debug.
- **`ParseSplit` filters can legitimately find nothing.** `split: no divider found` appeared
  while the stage was mid-transition and the panes' frames sat at y=-74..3799, i.e. off
  screen. The windows were there; they just were not panels of a split at that instant.
- **The ruler is the screen, not the panes**: after a resize the lower pane's window often
  stops filling its pane (Termux keeps its old height at the top and leaves dead screen
  below), so a pane-union "area" shrinks - measured `area 0..1582 divider 463 share 71%`
  when the truth was 77%. The panes are now used only to decide *whether* the split is
  stacked, and the divider only for where it is.
- **Click-through works** - a tap with the pointer under one of our own panels does reach the
  window beneath. Measured in split screen, with the pointer's position verified from its own
  window frame right before each tap: with focus on the browser pane, a pad click in the
  Termux pane flipped focus to `com.termux` both **clear of the pad** and **under it**
  (pointer at `1303,1542`, inside both the pad and the pane). It used to fail the second
  case - see the `FLAG_NOT_TOUCHABLE` race in the conventions below.
- **A split-screen pane is chosen by the pointer's position, not by focus** (measured):
  a pad click lands in whichever pane is under the pointer, and touching a pane also
  *focuses* it, so keys then follow it. Both panes are one display, so nothing in the
  injection path needs to know a split exists.
- **An injected drag does move the split divider**: `input swipe 906 369 906 719` moved
  the boundary from y=394 to y=892. Swipes *at* the boundary row (394/401) or 25px below
  it did nothing, so the grab region the divider window reports overshoots the pane edge -
  use the divider's own `touchableRegion` (`dumpsys window`, the
  `Embedded{StageCoordinatorSplitDivider}` window, measured (795..1016, 872..926) for a
  divider at ~900), never the boundary between the two pane frames.
- **Edge scrolling** runs at half the two-finger rate, in small flushes: `EDGE_SCROLL_FACTOR`
  0.5 and `EDGE_FLUSH_PX` 12px of finger travel per flush (against `SCROLL_STEP` 36 for the
  two-finger drag), and a flush is *capped* at that 12px with the leftover carried, so a fast
  flick arrives in the same small steps instead of one jump. Verified through the injected
  values with a temporary log: a 100px swipe over 450ms produced `-8` eight times (64 units,
  the whole travel accounted for), and over 120ms three times (24 units) - 8 = 12 x 0.7,
  where SCROLL_STEP-sized flushes sent 26.
- **The throttle is what a fast flick loses to.** `SCROLL_THROTTLE` is 40ms for both paths, so
  a 120ms flick gets three flushes: 24 units where the travel implies ~70, down from 39 when
  the flush was 18px. If the strip feels dead on quick flick, shorten the throttle for the edge
  path rather than enlarging the flush - the increment size is what was asked for.
- **The two-finger path is untouched** by any of that: `scrollBy(dy)` still uses `SCROLL_GAIN`
  1.4 with a 36px step and no cap. `scrollBy(dy, gain, step, capFlush)` is the shared engine.
- **Smoke test of the strip**: `down pad=... at 18,173 ... EDGE` in the log means a touch
  landed in the left strip (x=18 < `dp(28)`); the pointer also has to be over a window that
  scrolls for anything visible to happen - Termux ignores injected wheel events entirely.
- **Bubble edge snap**: dragging a floating dot and releasing sends it to the nearer
  vertical edge; measured on window frames after injected swipes - left lands at 18
  (= `dp(8)`), right at 1695 (= `1812 − 99 − 18`), and a drag ending at the bottom
  clamps y to 2059 (= `2176 − 99 − 18`). Same pass: `input tap` on a bubble's centre
  still toggled its panel, and a 60ms flick no longer toggled it. Injected swipes, not
  a finger, so the *feel* of the 160ms animation is unconfirmed.
- A bubble only becomes a drag after the `ViewConfiguration` touch slop; below it the
  touch is a tap, above it the dot follows the finger and never fires the tap.
- Z-order: **cursor > pad > keys panel > bubbles** (via `raise()`).
- **Display picker**: enumerates displays the public API hides — cover screen found
  by id probing, logged as `enumerate: public=1 probed=1 swept=0 total=2`, i.e. no
  Shizuku needed for enumeration.
- **Retarget**: picking display 1 logs `target display -> 1 (Built-in Screen)
  904x2316`, and back to `-> 0 ... 1812x2176`. Sizes come from the chosen display,
  not the surface.
- **Cursor handoff**: `setPointerIconType(1)` (system pointer shown) when the target
  differs from the surface, and `setPointerIconType(0)` (hidden, we draw ours) when
  they match again.
- **Per-display injection lands**: `input -d 7 tap` twice into the Calculator
  running on the Overlay #1 virtual display produced `77` in its display area, so
  displayId-routed injection genuinely reaches a non-default display.
- **Shell-side virtual display creation works**: `createVirtualDisplay` from
  `ShellUserService` succeeded once it used the `com.android.shell` package context
  (`virtual display created: 9`), and the display is enumerated as
  `display 9 'ztrackpad' 1920×1080`.
- **A shell-created display hosts tasks**: `am start --display 9
  com.android.settings/.Settings` put a Settings task on display 9 with
  `visible=true` and `sz=2`.
- **Theme presets**: `theme applied: default` / `contrast` / `light` logged on switch;
  High contrast renders solid black with white borders at radius 4, Light inverts to dark
  ink on pale panels with a black arrow, and both keep panel positions and move the
  active row marker. `Default` renders identically to the pre-theme build.
- **Opacity slider**: dragging moved it 64% → 84% with the live label tracking, and the
  panel fills scaled (a preset's baked-in alpha is multiplied, text and borders are not).
- **Invisible resize grips**: dragging the pad's *undrawn* top-right corner changed it
  `588x713 → 540x713` (clamping to the `dp(240)` minimum, as a −250px drag should).
- **All four pad corners, and both docked dots** (measured on device after pairing):
  the pad's top-left corner resized `603x669 → 540x545`, origin moving and width clamping
  at the minimum; the theme dot's left edge measured 1239 against a pad edge of 1210, and
  the display dot's right edge 1789 against ~1810 — both `dp(14)`; and only **two**
  floating bubbles remain (`⌨`, `●`) where there were four.
- **Shell process lifecycle / client-death watchdog**: on bind the log shows
  `watchdog: watching client pid N`; a surviving `adb install -r` then produces
  `watchdog: client N is gone - releasing display and exiting` from the old process.
  Three rapid installs left exactly **1** shell process each time (it previously
  accumulated to 25), with 0 orphan virtual displays.
- **Runtime keys customisation**: `vdisplay keys '<spec>'` round-trips exactly - including
  a `'` label and the `|` `;` `:` separators - `status` flips to `keys=custom`, a custom
  3-row layout renders centred, and `keys-reset` restores `rows=8 keys=75`. The built-in
  spec reproduces the old hand-built layout exactly: 13 keys in row 1, 11 in row 2.
- **Auto-rebind after the shell service dies**: killing the shell process logs
  `shell service disconnected` → `rebinding shell service (attempt 1)` →
  `shell service bound` about 1.8s later, with no accessibility-service restart. The
  cursor is re-synced on the new binding (`setPointerIconType(0)`), and injection works
  again straight away (`down pad` then `click at 906,1088`).
- **Per-row show/hide for the floating display**: hiding detaches only the surface
  (`screen surface destroyed`) while display 16 and its launcher/taskbar keep running;
  showing logs `virtual display surface re-attached` and keeps the **same** display id
  rather than creating a new one.
- **Surface-backed virtual display, driven end to end.** `create floating display`
  created display 10 (`virtual display created: 10 (surface-backed)`) rendering into a
  `SurfaceView` in a top-half overlay (`screen surface created 1812x1025`). Display 10
  reports `state=ON`, unlike the headless variant's `state=OFF`. Launching the
  Calculator on it and clicking via the pad logged `click at 954,696` and
  `click at 1382,696`, and the Calculator read `46` — exactly the buttons at those
  coordinates. **This is the end-to-end proof that ztrackpad can control another
  display.**
- **AIDL can carry a `Surface`** given `aidl/android/view/Surface.aidl` — the aidl tool
  resolves imports by path and does not read framework parcelables from `android.jar`.
- **Clean floating display, no recursion**: with exactly one shell process,
  `create floating display` created a single display
  (`virtual display created: 12 (surface-backed)`), SurfaceFlinger reported exactly one
  `ztrackpad` virtual display, and the Calculator rendered cleanly in the top half.

**Implemented, verified mechanically, NOT visually confirmed**

- **Clicking a desktop icon underneath the trackpad.** The injection path runs and
  the panels do go non-touchable, but no one has watched a real desktop icon
  activate. If it fails, suspect the launcher window not seeing the tap inside the
  non-touchable window; widen the hold around injection.
- **Window drag on DeX / freeform.** Issues exactly one `SOURCE_MOUSE` DOWN/UP pair
  and nothing more (the earlier repeat-DOWN bug is fixed), but no window has been
  observed actually moving.

**Not testable via adb — needs a human finger**

- **Two-finger drag (scroll)**: still untested; only the two-finger *tap* has been
  confirmed. The logic mirrors the one-finger path, so it is plausible but unproven.
- Press-colour highlight (`keyBgState` → `#2A6FB0`).

- **Shizuku lifecycle.** Three separate failures, all now handled, and they are easy to
  confuse: the *user service* dying (rebind with a growing delay, bounded at 10 attempts),
  the *Shizuku binder* dying (told via `OnBinderDeadListener`), and Shizuku coming back
  (`OnBinderReceivedListener`, non-sticky so it cannot loop with `start()`). Both
  listeners are registered *with a Handler* so their `setState` → UI work lands on the
  main thread. `stop()` restores the system pointer **before** unbinding, or
  `setPointerIconType(0)` is left in force with nothing drawing an arrow.
- **Always call `shizuku.stop()` when tearing down.** `removeAll()` did not, so the user
  service was never unbound on a clean shutdown — the abrupt path the watchdog had to
  compensate for.

**Known regressions / gaps**

- The `surface = null` variant stays headless and is **not** a control target; only the
  surface-backed one is. Both buttons exist and the footer labels say which is which.
- The picker sets the **target** only. Moving the *panels* to another display is not
  implemented — display 1 reports `canHostTasks=false`, so the folded/cover case
  needs its own investigation.
- **Leaked Shizuku user-service processes — FIXED** by a client-death watchdog.
  Previously every app restart spawned another `app.so7o.ztrackpad:shell` process (≈25 had
  accumulated, ≈50 MB RSS each ≈1.3 GB) because `stop()` is skipped on a hard kill or
  `adb install -r`. Each leaked process kept its own static `vdHolder`, so its virtual
  display was never released — which orphaned a display, or, with two surviving, made the
  floating window mirror itself (SurfaceFlinger listed two `ztrackpad` displays).

  Now the client calls `registerClient(pid)` on bind, and the shell process polls
  `/proc/<pid>` every 2s, releasing its display and killing itself when the app is gone.
  The manual cleanup below is only needed to clear a process left by an older build:
  `adb shell 'for p in $(ps -A | grep "ztrackpad:shell" | awk "{print \$2}"); do kill -9 $p; done'`.
- **Shizuku restart is the one lifecycle path not proven on device.** Killing the user
  service rebinds correctly (verified), but `OnBinderReceivedListener` needs Shizuku
  itself to stop and start - which takes the Shizuku app or a fresh adb start, and would
  disturb whatever else is using it. Treat that path as written-but-untested.
- No `✕` on the trackpad (pre-existing), so the pad can only be dismissed from the
  `●` bubble. Removed deliberately on request; re-add in one line if missed.

## Device notes (Galaxy Z Fold 4 / F936B, One UI, Android 16)

- Wireless ADB port changes; discover via mDNS
  (`_adb-tls-connect._tcp`, see `~/adbdiscover.py`) then `adb connect`.
- DeX + XREAL glasses are the main desktop use case (clicking desktop icons
  under the trackpad).