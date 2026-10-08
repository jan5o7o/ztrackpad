# AGENTS.md — So7o Z Trackpad

## What this is

A standalone Android input app: floating trackpad + pointer + programmable keys
panel + display picker. Built on-device in Termux with a **hand-rolled build**
(`build.sh`) — there is **no Gradle, no Kotlin, and no SDK install**; the app is plain
Java. The build downloads only the platform jar it compiles against.

Reference environment — everything the verification list below was measured on — is a
**Galaxy Z Fold 4** (SM-F936B), aarch64, Android 16 / One UI, in Termux, with no desktop.
Nothing in the build is device-specific, so another host with the same tools should work;
read "verified working" at the end of this file as *on that device*.

## A PocketJS direction (2026-10-08)

The UI-layer work that follows this app is **PocketJS** — a JSX/QuickJS UI framework whose Android
host is built without Gradle, the way `build.sh` is. It builds *on this device*: the local shim kit
is `~/pocketjs-termux/` (its README carries the state an agent inherits) and the checkout is
`~/pocketjs` at `5a60ab9`.

The plan is that **this app becomes the shell** — input, displays, windows, capture — and PocketJS
becomes what the panels are written in. Nothing in this repo implements that yet.

- Write-ups: `~/ideas/brainstorms/2026-10-08-ztrackpad-pocketjs-feasibility.md` (the decision, the
  build attempt, the one linker fix) and `…-shell-pocketjs-userspace.md` (what to build on it).
- **Done when** a PocketJS surface renders inside a `TYPE_ACCESSIBILITY_OVERLAY` window owned by
  `TrackpadService` rather than an Activity, click-through still reaches the app underneath, and one
  `shell.*` call returns shell data to guest JS.
- **Stop if** the port needs more than a backend swap. The single global `ui_*` tree and the host's
  `static` state are the likely reasons. The fallbacks are already measured: a QuickJS wrapper AAR
  (`io.github.taoweiji.quickjs:quickjs-android`, 1.4 MB, Java API) for scripted logic, or the
  existing WebView shell for content.
- Anything vendored from it (PocketJS and QuickJS are MIT) needs an entry in
  `THIRD_PARTY_LICENSES.md`.

## Branches and releases

`main` is **releases only**: a pull request is required, the `build` check has to pass, and
nothing may be pushed to it directly — not even by the maintainer. `dev` is where work lands and
feature branches pull-request into it. Every push to either branch, and every pull request, runs
`.github/workflows/build.yml`: it installs the platform and build-tools, builds, signs with a
**throwaway** key, and asserts that no development identity is in the tracked tree or inside
`classes.dex`. It cannot run `tests/smoke.sh` — that needs a device — so the device checks stay
manual, which is the whole reason the verification list below exists.

Release steps live in [RELEASING.md](RELEASING.md).

Two invariants matter more than the tooling: **no secret in a tracked file** (the signing
password comes from `$KSPASS` or `~/.ztrackpad-kspass`, nowhere else), and **the release keystore
never enters the repository** — it is what lets an existing install update in place, and losing it
means no install can ever update again. Screenshots and recordings need the same care, because
every check here reads text and cannot see inside an image.

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
package discovery, `shizuku=ready`, the status schema, the keys-layout, pad-lock, flick and
scroll-mark round-trips (all restored to their prior value afterwards), the three-dot
`bubbles` round-trip and the implicit-broadcast trap. Two lifecycles are off by default
because they have side effects: `tests/smoke.sh --with-display` (a visible display) and
`--with-clip` (which CLOBBERS THE SYSTEM CLIPBOARD - it is the only way to exercise the
clipboard modal end to end, so it is opt-in). Run it after installing, against whatever is
installed - it discovers the package id instead of hardcoding one, so it needs no renaming in
the public tree. Pass the package id explicitly (`tests/smoke.sh app.so7o.ztrackpad`) when a
second trackpad build is installed, or discovery refuses to guess between them.

`tests/cleaner.sh` is separate and needs no device: `Cleaner` is pure Java, so it compiles
and runs with plain `javac`/`java` (34 checks). Run it after touching any cleaning rule.

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
| `java/.../Cleaner.java` | the clipboard text transform (terminal artifacts -> clean text). **Pure java, no android imports**, so `tests/cleaner.sh` runs it without the platform jar |
| `skills/ztrackpad-vdisplay/` | pi skill + `scripts/vdisplay` for driving it from Termux |
| `skills/ztrackpad-vdisplay-launch/` | pi skill for putting an app on that display and verifying it from pixels |
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
`TrackpadService.vdisplayCommand(op, headless, spec, arg, w, h, url)`, which does the real work
on the main thread. Scripts go through it; `skills/ztrackpad-vdisplay/scripts/vdisplay` is the
reference implementation, and `extensions/vdisplay.ts` wraps it for pi.

```bash
adb shell am broadcast -n app.so7o.ztrackpad/.VDisplayReceiver -a app.so7o.ztrackpad.VDISPLAY --es op status
```

Ops: `status`, `create` (+`--ez headless true`, + `--ei w W --ei h H` for a requested
size), `show`, `hide`, `destroy`, `keys`
(`--es spec '<layout>'`, no spec = read it back), `keys-reset`, and `lock`
(`--es arg on|off|toggle`) - the lock is the one non-display op, and it drives the same
`setPadLocked` the pad's lock dot does, so the two cannot disagree. `flick`
(`--es arg on|off`, no arg = read it back) is the second non-display op: it switches the
edge strips between live scrolling and Lite's bank-and-scroll-on-release feel, through the
same `setFlickScroll` the CONTROLS row uses. `marks` (`--es arg on|off`) is its cosmetic
twin: whether the pad draws the dotted edge-strip markers, through the same
`setShowScrollMarks` the CONTROLS row uses. `clip` is the clipboard modal: no arg reads its
state back (`panel=`, `ready=`, `junk=`, `join=`, `lines=`, `chars=`), `--es arg
show|hide|toggle` opens and closes it, `read` pulls the system clipboard into the textarea,
`clean` runs the ticked transforms, `junk on|off` and `join on|off` set the two checkboxes,
`copy` writes the text back and closes, and `--es arg set --es spec '<text>'` puts text on the
clipboard.
`tasks` (no arg) lists
the floating ("pop-up view") windows on the display the panels live on, and with
`--es arg show|hide|toggle` drives the panel that lists them; `taskfocus --es arg <TASK_ID>`
brings one to the front. `tasks` replies `ok tasks n=<count> display=<d>` followed by
`<id>:<pkg>:<visible|hidden>:<floating|fullscreen>` per window. `keys-mode` (`full`, or
`favorites` to use the saved custom layout) and `bubbles` (`keys=on|off,tasks=on|off`) mirror
the CONTROLS panel's rows - same setters, so a finger and a script cannot disagree - and
`controls --es arg show|hide|toggle` is that panel. `split --es arg up|down` is the old pad
button, dropped from the UI for the scroll strip it sat on.

The headless-testing ops are the reason this receiver exists for agents: `launch`
(`--es arg <package-or-component>`, + `--es url <uri>` for a VIEW data) starts an app on
`ownVirtualDisplayId` from inside the Shizuku shell process - the same route `seedVirtualDisplay`
uses - because a plain `am start --display` from adb can be refused for displays like ours and
the shell both owns the display and holds `INTERNAL_SYSTEM_WINDOW`, which
`ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay` (AOSP 16) checks first.
`target --es arg <id|package>` moves the pad's input target through `setTargetDisplay` - the
picker's exact code path - and with no arg reads it back. `shot --es arg <name>` runs
`screencap -d` inside the shell process at the SurfaceFlinger value for the ztrackpad display
and verifies the PNG really exists (`/data/local/tmp/<name>.png`); a headless display has no
pixels to composite, so it fails cleanly.

- **The pad's own dot has no on/off pref, deliberately.** It is the only way to show the pad
  (which has no close button) and it carries the gear, so hiding it would strand the way back
  to everything, including the row that would un-hide it. `⌨` and `▤` are the optional ones;
  the escape hatch if both are hidden is `op=bubbles` or MainActivity.
- **The panel's HELP rows point at the PUBLIC repo**, as `res/values/strings.xml` strings
  (`help_guide_url`, `help_issues_url`), so they need no rebranding if this tree is exported,
  and a missing anchor degrades to the top of the README instead of 404ing. The row wording
  was picked by a TypeSafe (Jev) judgment: "Show the ▤ windows dot" 1.60 of 2 where "Keys
  bubble" scored 0.71 and "Show the ▤ dot" 0.72, and "Favorite shortcuts" over "Favorites
  only" at 0.99. That is a judgment, not a measurement.

- **The `-n` component is mandatory**: an implicit broadcast never reaches a
  manifest-declared receiver on API 26+, and the failure is silent (result=0 with no
  `data=`), which is easy to misread as success.
- `status` replies with `shizuku=ready id=N kind=floating|headless|none window=shown|hidden
  surface=alive|detached vsize=WxH target=N padlocked=true|false flick=on|off marks=on|off
  keys=default|custom`;
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
- **The clipboard modal is the one focusable window, and it has to be.** Android only lets an
  app read the clipboard when it owns the focused window
  (`ClipboardService.clipboardAccessAllowed` -> `WindowManagerInternal.isUidFocused`); there is
  no accessibility-service exemption, and shell cannot read it either (measured: `dumpsys
  clipboard` prints nothing and there is no `cmd clipboard`). So the `✂` modal takes focus
  while it is open - which is also what lets the keys panel type into its textarea - and hands
  focus back when it closes.
- **A focusable window must set `FLAG_NOT_TOUCH_MODAL`.** A focusable window without it is
  modal and swallows every touch outside its own bounds, which silently killed the `✂` dot
  while the modal was open (measured at points well clear of the panel). The modal also sets
  `FLAG_LAYOUT_NO_LIMITS` like the other panels.
- **`OnPrimaryClipChangedListener` only delivers to a focused app**, so the dot cannot
  "appear on copy" from the background: the callback never arrives while the overlays are
  unfocused and fires the moment the modal takes focus (measured both ways). The dot is
  therefore always present when enabled, the text is read when the modal opens, and
  `clipready=` reports whether a delivery has ever been seen.
- **The clipboard modal's textarea sits in a box of its own** - `fieldBg` fill (darker than
  `panelSolid`, recessed) with a `fieldStroke` border, `CLIP_FIELD_PAD_DP` of padding inside
  it and `CLIP_FIELD_MARGIN_DP` of margin around it. The EditText carries no padding and no
  background; the box owns both. `fitClipPanel` has to subtract *both* (margin + padding) when
  it computes the text width, or the text wraps differently from the `StaticLayout` that
  measured it and the window comes out the wrong height.
- **The textarea is `weight 1`, not a measured height.** The box takes whatever the window
  leaves after the title, hint, checkboxes and buttons, so the text scrolls inside it. That is
  what makes the modal resizable: shrink it and the textarea scrolls instead of the window
  fighting back.
- **The modal is resizable, and the first grip drag turns the auto-fit off.** Grips are the
  shared ones (`CLIP_KEY` = `x_`), so the geometry persists like every other panel; the grip
  calls the `onSized` hook added to `addResizeGrips`, which sets `clipSized` (persisted). While
  `clipSized` is set, `fitClipPanel` returns early and the text scrolls in the user's window;
  `clip fit` (the op - there is no UI for it) clears the flag and re-fits. Without that flag
  the two would fight: every Clean or keystroke re-measures the text and would undo the resize.
- **`reset` is the TEXT, not the window** (`resetClipText`): it re-reads the clipboard into the
  textarea, undoing a Clean or a hand edit. Nothing is written to the clipboard until `copy`,
  so the original is always there to go back to - that is what makes it safe, and why it costs
  one read. `clip reset` is the same code path as the button.
- **`reset` lives in the title bar, not the options row.** In the options row it was a small
  chip wedged between the checkboxes and the action buttons, and a tap that missed it landed on
  `cancel` and closed the modal - measured, twice, while trying to verify it. In the bar it gets
  a corner of its own and, being a child of the bar, wins the touch over the bar's drag listener
  (the same reason the pad's docked dots work). Its right margin is `dp(14)`, not `dp(4)`: the
  panel's corner radius is `theme.radius` (`dp(18)`), so a smaller margin lets the pressed pill
  spill outside the rounded corner - the identical trap the pad's first docked dot hit.
- **A copy cannot be detected from the background on this device - four routes measured, all
  negative (2026-10-06).** This is why the `✂` dot is always visible and the text is read when
  the modal opens; do not spend another session on it without a new idea.
  1. **`OnPrimaryClipChangedListener`** fires only while this app owns the focused window
     (unfocused copy: nothing; the same copy with the modal open: two callbacks).
  2. **Reading the clipboard** needs focus (AOSP `clipboardAccessAllowed` -> `isUidFocused`),
     and there is no shell route: `dumpsys clipboard` prints nothing and there is no
     `cmd clipboard` on this build.
  3. **The SystemUI "copied" overlay produces no accessibility event.** The service declares
     `typeWindowStateChanged` and events *do* arrive (an app switch logged one, from
     `com.android.systemui`), but a copy logged none - so the overlay is not in the a11y window
     list. A temporary probe in `onAccessibilityEvent` proved it.
  4. **Samsung's own clipboard service** (`getSystemService("semclipboard")` ->
     `SemClipboardManager`, `com.samsung.android.clipboard.ACCESS_SEMCLIPBOARD`, protectionLevel
     `normal` so a third-party app can hold it) registers its listener
     (`registerClipboardEventListener(SemClipboardEventListener)`) without error, and then never
     fires - not unfocused, and not even with the modal open, where the AOSP listener did fire.
     Probed by reflection; `framework.jar` carries the class, so no SDK jar is needed.

  A dot that "appears on copy" therefore needs a signal from outside: any app or script can
  poke the exported receiver (`--es op clip --es arg ping`, which shows the dot and arms its
  clock). That is the only real copy trigger available, and `scripts/clipcopy` is it in one
  step - it writes the clipboard through ztrackpad and pings.
- **Auto-hide is off by default, and the ping is what opens the window.** `clipAutoHide` +
  `clipHideMinutes` (default 2) drive a single `postDelayed` (`clipHideTask`); the dot is on
  when `showClipBubble && (!clipAutoHide || clipReady)`, so `clipReady` is now "inside the
  active window" rather than the old "a delivery was ever seen". A ping, a modal open, and a
  focused clipboard change all open the window; the task refuses to run while the modal is
  open, and closing the modal re-arms it. Two footguns are handled deliberately: switching
  auto-hide on opens the window immediately, and a service start with auto-hide on starts in
  the window - either way a restart cannot leave the dot gone with no way back.
- **The modal's height is measured, not guessed** (`clipTextHeight` lays the text out with a
  `StaticLayout`, `fitClipPanel` sizes the window): an overlay window has a fixed height, and
  the requirement is 70% of the screen wide and as tall as the text needs, capped at
  `CLIP_TEXT_MAX_H_FRAC` (past that the textarea scrolls), then centred. A keystroke only
  re-measures through a debounce (`scheduleClipFit`), not on every character.
- **The keyboard stays down until the textarea is tapped**
  (`SOFT_INPUT_STATE_ALWAYS_HIDDEN`), so opening the modal never shoves a keyboard over the
  screen; the modal is centred, so the keyboard does not cover it when it does appear.
  Disabling the IME instead (`FLAG_ALT_FOCUSABLE_IM` + `setShowSoftInputOnFocus(false)`) was
  the first cut and it left no way to type at all - do not go back to it.
- **`Cleaner` must stay android-free.** It is the tested transform, and `tests/cleaner.sh`
  compiles it with plain `javac`; an `android.*` import there breaks that test silently. New
  junk class = new named static method + a case in `tests/CleanerTest.java`.
- **The `Cleaner`'s rules, and the order they must run in.** `stripAnsi` -> `stripControl` ->
  `stripInvisible` -> `stripGutter` -> `stripBox` -> `normaliseSpaces` -> `tidyLines`
  (+ opt-in `unwrap`). Two of those orderings are load-bearing:
  - **The gutter runs BEFORE the box rules.** The gutter's own marks are box-range chars
    (`○` `●` `│`), so the box rules would cut them first and the pattern would never match.
  - **Two gutter shapes.** A *digit* counter requires the pane bar (` 4○│`, `▾3●│`) - a bare
    leading number is real content too often, and that limitation is pinned by a test. A
    *letter* counter does not (`z✓ │`, `L○ │`, `L○ startup.`): herdr labels some rows with a
    letter and a circle or check after it, a letter glued to `○`/`●`/`✓`/`✔` is not prose,
    and the bar is the first thing a copy drops. The letter form is capped at three letters,
    so a word before a check (`This✓`) survives.
  - **A second pane's gutter lands mid-line** when the copy spanned two panes side by side
    (`GUTTER_INLINE`: 2+ spaces, digits, a circle, the bar -> one space). The circle is
    required, or a markdown table's `a   │ b` would be joined.
  - **`>= 3` box chars are chrome and dropped - unless they are one LEADING run**
    (`leadingBoxRun`). That run is a horizontal rule with content after it (`───│ Two notes:
    ...`), so the rule goes and the text stays; a title bar interleaves box chars with words,
    which is what still tells the two apart. Before this, a rule silently ate the sentence
    after it - measured on a real clipboard copy.
- **What `clean` does is two persisted checkboxes, not a fixed pipeline**: `clean junk`
  (`Cleaner.clean`) and `remove new lines` (`joinClipLines` - every `\n` becomes a space, runs
  of spaces collapse to one, then trim). Neither is applied on open: the raw copy is what you
  are shown, and cleaning stays an explicit press. `joinClipLines` eats indentation, which is
  exactly why it is a checkbox and not the default; the guarded unwrap stays on the long-press
  of `clean`. Both prefs are read in `onServiceConnected` and written by the same setters the
  `clip junk|join` ops call, so a finger and a script cannot disagree.
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
- `screencap` on this device defaults to the **cover screen**, and `-d` takes the display
  **uniqueId**, not the HWC id: `-d 0` and `-d 3` both fail with `Display Id 'x' is not
  valid`, while `-d 4630946474867211650` (from `dumpsys SurfaceFlinger --display-id`)
  works.
- **A floating window is minimized by tapping its own `-` button**, at
  `right - dp(124), top + dp(22)` of the task's `mBounds` (measured 2026-09-27: a window at
  `300,300 - 1500,1500` has it at `1220,350`; tapping it gives `visible=false` with the task
  still alive, still `mode=freeform`, and `translucent=true` as the tell). There is **no
  minimize API** on this build: nothing in `cmd activity` beyond
  `lock`/`resize`/`resizeable`/`focus`, nothing per-task in `cmd window`/`wm` (only
  `set-display-windowing-mode`), and no `minimiz` anywhere in AOSP's `Task.java` - the state
  belongs to Samsung's multi-window and Google's wm-shell. So the floating-window list
  minimizes by injecting that tap, then re-reads the list and parks whatever is still
  visible (`am task resize`, which always works), so a chrome change degrades instead of
  silently doing nothing. `am task focus <id>` restores a minimized window - verified for
  `visible=false` freeform tasks.
- **Panel default positions can be baked** without fighting a layout the user already set:
  `restoreGeometryAt` applies its x/y only when no pref is saved, so it decides where a
  fresh install first appears and leaves an existing drag alone. The tasks panel's default
  is `dp(173), dp(509)`, taken from where it was put by hand.
- **Never call `am stack move-task`.** It does move the task, and then throws
  `ClassCastException: TaskFragment cannot be cast to Task` in
  `Task.resumeTopActivityUncheckedLocked` *inside system_server*, which took system_server
  down and restarted the framework (measured 2026-09-27: wireless adb died and needed a new
  port and a fresh pairing, every floating task was lost). The legacy `stack` command is
  broken on Android 16's TaskFragment hierarchy. App processes survive a system_server
  restart (they are forked from zygote), which is why Termux and Shizuku came through it -
  but the framework, the window state and the adb session do not.

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
- **Edge scrolling** runs at a quarter of the two-finger rate, in small flushes:
  `EDGE_SCROLL_FACTOR` 0.25 and `EDGE_FLUSH_PX` 12px of finger travel per flush (against
  `SCROLL_STEP` 36 for the two-finger drag), and a flush is *capped* at that 12px with the
  leftover carried, so a fast flick arrives in the same small steps instead of one jump.
  The factor scales only the injected distance, never the travel accounting - a flush
  consumes 12px whatever the gain - which is why it is the one knob for "how far does the
  page move". Verified twice through the injected values with a temporary log, same 100px
  swipe: at 0.5 it produced `-8.4` eight times (**64 units**, and still felt fast), and at
  0.25 it produces `-4.2` eight times (**33.6 units**, ~0.35px of content per px of finger)
  with the whole 100px of travel still accounted for.
- **The throttle is what a fast flick loses to.** `SCROLL_THROTTLE` is 40ms for both paths, so
  a 120ms flick gets three flushes: ~13 units where the travel implies ~35. If the strip feels
  dead on quick flick, shorten the throttle for the edge
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
- **The two displays SWAP IDS WHEN FOLDED**, so never treat an id as "the cover screen".
  Folded: **display 0 = 904x2316 with `canHostTasks=true`** and display 1 = 1812x2176
  with `canHostTasks=false`; unfolded it is the exact reverse (`wm size` +
  `dumpsys display` + `dumpsys window displays`, measured 2026-09-27). This is why
  `surfaceDisplayId` must stay `Display.DEFAULT_DISPLAY` - it follows the active screen
  for free - and why any window geometry computed in "the" display space has to be read
  from the display the window is actually on, at the moment of use. Caching it at
  service connect (`screenW`/`screenH`) is wrong the moment the phone is folded or
  unfolded, and `getCurrentWindowMetrics()` follows the active screen too, so it does
  not help for a task on the other one. `DisplayManager.getDisplay(id).getRealMetrics()`
  is the one that answers per-display.
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
- **Flick-to-scroll toggle**: `vdisplay flick on|off` round-trips exactly with `status`
  (`flick=on|off`), rejects anything else, and the CONTROLS row uses the same `setFlickScroll`,
  so the two cannot disagree. With the toggle on, the log shows `flick scroll on release
  move=<px>` on finger-up (values like -236/-416/412 measured), i.e. the edge strip banks
  the whole gesture and spends it as one event on release; with it off the strip scrolls
  live as before. The *feel* of the gain and the delivery is a finger judgment - the
  tuning log: as wheel events the gain went 2.0 -> 1.0 -> 0.8 -> 0.4 and it STILL felt
  fast, which pinned it on the delivery (an event teleports the page, and targets scale
  wheel deltas per-app). On the surface display the flick is now Lite's own mechanism -
  one 260ms stroke of the banked distance, gain back at Lite's 2.0 - and the wheel
  glide (FLICK_STEP 20/40ms) survives only for retargeted displays a stroke cannot reach.
- **Scroll marks**: `vdisplay marks on|off` round-trips (and no-arg reads), `status`
  reports `marks=`, and the CONTROLS row draws/clears the dotted markers on the pad's
  sides via the same `setShowScrollMarks` - a column of small dots at the strip centres
  (`dp(EDGE_SCROLL_DP)/2` in from each edge, `dp(1.4)` radius every `dp(12)`, starting
  `dp(64)` down - two dots dropped from the top so the columns clear the docked control
  dots with room to spare), drawn as circles by the
  `PadSurface` view in a new `Theme.scrollMark` role, no theme rebuild on toggle. The
  render itself is confirmed only by the op round-trip; it has not been eyeballed on
  the device.
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

- **Clipboard modal, end to end** (`tests/smoke.sh --with-clip`, 2026-10-06): a 140-byte
  junk specimen (ANSI + pane title bar + zellij gutter + padded columns) went onto the
  clipboard, `clip show` read it back (112 chars - the focusable window reads), `clip clean`
  took it to 67, `clip copy` closed the modal, and re-opening read the 67-char cleaned text
  back, so the write-back landed. `tests/cleaner.sh` passes 34 checks in Termux with no
  device at all.
- **The `clean` checkboxes work and persist**, measured: `clip junk off` + `clip join on`
  flattened the cleaned 4-line specimen to 1 line (`lines=4 -> 1`), and `clip junk on`
  stripped 112 chars to 67. The smoke test pins and restores both options, because they are
  persisted - an earlier run's `join on` leaked into the next run and made the flatten check
  read `1 -> 1`.
- **The cleaner against the owner's real clipboard copy** (729 bytes of a herdr pane that
  spanned two columns, 2026-10-06): `clip clean` took it 677 -> 574 chars, and the cleaned text
  read back through `termux-clipboard-get` held exactly the prose - `z✓ │` and `L○ │` gone, the
  mid-line `4○│` replaced by a space (`suite). - End-to-end:`), and the `───│ Two notes: ...`
  sentence kept. Before the letter rule those two lines kept a stray `z✓` / `L`, and the rule
  line was dropped whole, losing the sentence after it.
- **The modal resizes and re-fits on device**: an injected grip drag took it `1268x1061` ->
  `1268x733` (and a later one `1126x812`), `sized` flipped to `on` and the geometry survived a
  reinstall; `clip fit` put it back to `1268x1061`, centred.
- **The `reset` button works from the title bar**: with a junk specimen read in (78 chars,
  4 lines), `clean` took it to 63 chars on 1 line, and a tap on the chip at `(1456,855)`
  logged `clip reset 63 -> 78 chars` and left the textarea back at 4 lines. The op path is in
  the smoke test (`clip reset` 67 -> 112 chars).
- **Auto-hide's clock is exact**: `clip ping` at `23:12:22.713` logged
  `clip dot auto-hidden after 1 min` at `23:13:22.714` - 60s, to the millisecond. `hide-now`
  took the dot away (`dot=off ready=off`) and a second ping brought it back, both measured;
  the smoke test covers those three steps rather than sleeping through a window.
- **`scripts/clipcopy` works end to end**: `printf '%s' "$junk" | clipcopy` left the clipboard
  holding the specimen and the dot up with `ready=on`, the 1-minute clock armed.
- **The modal's geometry is exact**, measured from `dumpsys window`: the panel is
  `(272,817)(1268x541)` on a 1812x2176 display (1268 = 0.7 x 1812, centred; the height grew
  from 422 when the textarea got its padded box) and the `✂` dot is
  `(18,1414)(99x99)` = `dp(8)` / 0.65 x 2176 / `dp(44)`.
- **Editing works from both inputs**: `vdisplay type XYZ` - the same Shizuku key path the keys
  panel uses - took the textarea from 87 to 90 chars, and tapping the textarea made the system
  keyboard visible (`mViewVisibility=0x0`) while opening the modal alone left it hidden
  (`0x8`).
- **The `✂` dot toggles the modal both ways**, measured with `input tap` on it at `(67,1464)`:
  hidden -> shown and shown -> hidden (the second only after `FLAG_NOT_TOUCH_MODAL` was added).
- **The clipboard listener is focus-gated**, confirmed both ways: unfocused, a clipboard write
  produced no callback (`clipready=off`); with the modal focused, the same write logged
  `clipboard changed (focused)`.

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
- **The testing ops — `launch`, `target`, `shot`, and `create --w/--h` — are
  build-verified only, not run on device.** The branch landed with the operator's live
  session and a concurrent display test running, so no display could be created or
  destroyed and nothing was installed. The launch mechanism is the same shell bridge
  the seed already uses (verified in AGENTS above), but a headless `shot` may fail on
  some builds, and the floating-window resize path has not been exercised. Run
  `tests/smoke.sh` (its new fail-closed checks run without a display) after the next
  install to see real replies.
- No `✕` on the trackpad (pre-existing), so the pad can only be dismissed from the
  `●` bubble. Removed deliberately on request; re-add in one line if missed.

## Device notes (Galaxy Z Fold 4 / F936B, One UI, Android 16)

- Wireless ADB port changes; discover via mDNS
  (`_adb-tls-connect._tcp`, see `~/adbdiscover.py`) then `adb connect`.
- DeX + XREAL glasses are the main desktop use case (clicking desktop icons
  under the trackpad).