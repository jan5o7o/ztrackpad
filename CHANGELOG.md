# Changelog

Notable changes per release, newest first. The version is `versionName` in
`AndroidManifest.xml`, and every release also increments `versionCode` — Android refuses an
update that does not, so the two move together. Each entry here is the release's notes.

Keep this file free of development-tree names: it is exported to the public repo unchanged, so
it can only ever describe the app as shipped.

## Unreleased

### Features

- **Flick to scroll** (CONTROLS > SCROLLING): the pad's edge strips normally scroll
  live while your finger moves. Flip this on and they bank the whole gesture and
  spend it as one smooth motion on release, the feel of So7o Z Trackpad Lite, ported as an
  option. Also scriptable: `vdisplay flick on|off`, and `status` reports `flick=`.
- **Scroll marks** (CONTROLS > SCROLLING): dotted lines down the pad's sides mark where
  the edge-scroll strips are, like a laptop trackpad. On by default; `vdisplay marks
  on|off` scripts it, and `status` reports `marks=`.
- **First-run launcher**: the Open Accessibility Settings button is the first thing
  under the title (it used to sit below the whole guide), and the top padding follows
  the real status-bar inset so Android 15+'s edge-to-edge drawing no longer cuts it off.
- The pad's bottom buttons gain hairline seams between them, and the action bar floats
  off the pad's bottom edge.

### Bugfixes

- `scripts/vdisplay tap` now consumes **both** coordinates (`tap <x> <y>`) instead of
  dropping the second one and sending x alone. This matches the contract 0.6.0
  documented and what `scripts/vdisplay-test` actually calls (`tap 500 300`), so the
  driving test's taps now land where they say. `type` and `press` were already correct.

### Extension

- The `vdisplay` pi extension now also exposes `tap <x> <y>`, `type <text>` and
  `press <KEYCODE>`, so an agent can drive the app on a display straight from pi
  instead of hand-rolling `adb shell input -d <id>` (display-routed either way).

## 0.6.0 — 2026-10-04

### Virtual display testing ops

The virtual display is now a first-class agent/test surface: `scripts/vdisplay` gained
four operations, and a standard driving test ships with them.

- `launch <package-or-component> [--url]` — start an app on the display from the shell
  privilege path ztrackpad already owns, which works where `am start --display` from adb is
  refused (headless displays, in particular).
- `target [<id>|<package>]` — move the pad's input target programmatically (same code path
  as the ▣ picker); call with no argument to read it back. The reply names the display.
- `shot [--name N]` — capture the display's own pixels to `/data/local/tmp/<N>.png`;
  headless displays fail cleanly instead of hanging.
- `create --w W --h H` — size the next display explicitly (headless included).
- `tap <x> <y>`, `type <text>`, `press <KEYCODE>` — display-routed input: every event
  carries the target display's id via the same Shizuku path the pad's own clicks and keys
  use, so a script drives the app *inside* the display, not the phone under it.
- The ▣ display picker now lists the app's own floating display (it is shell-owned and
  invisible to app-side display enumeration), so tap-to-control works: tap the square,
  tap the display, and the pad's keyboard and pointer are aimed into it.
- `scripts/vdisplay-test` — the standard driving test: creates, launches, aims the pad,
  and drives taps/keystrokes into the app on headless and floating displays, proving each
  step from the display's own pixels. Change-proofs use `shot`, never the phone screen.
- Known residual: launching a *bare-package* app onto a just-created floating display can
  silently land no task in rare timing windows (works on a settled display; component and
  URL launches are unaffected). The launch loop retries with force-stop, and the test
  reports the outcome honestly.

## 0.5.0 — 2026-09-30

**A face for the launcher, and the last of the split-divider code out of the build.** 0.4.0's
notes said the split-divider buttons were gone from the pad, which was true of the pad; the code
that nudged the divider itself was still in the APK that shipped. This is the release where both
are gone.

- **The app has an icon.** It shipped without one, so the launcher drew its own placeholder. The
  icon is the pointer the app already draws on screen, reduced to what survives a launcher's mask
  at 48px: an adaptive icon, so the launcher supplies the shape, with a monochrome layer for
  Android 13's themed icons.
- **The `split` broadcast op is gone**, so nothing on the pad's scroll strip is a button any more.
- **The APK's uncompressed entries are packed 4-byte aligned** instead of landing wherever the
  packer happened to put them. The earlier builds passed that check by luck — a new icon file was
  enough to fail it, which is how it was found.
- The README's hero clip and its screenshots are retaken from a recording of the app in use,
  including one of the CONTROLS panel, and the notes on what was cut from that recording are
  updated with them.

## 0.4.0 — 2026-09-28

**Getting back to a window that is hidden behind other windows.** Floating windows had no way to
reach each other — the one at the back is simply covered, and nothing on screen names it. A new
dot opens a list of the windows on the display: the full-screen app first, then every floating
window, front-most first. Tap a row to bring it forward, or tap the full-screen row to minimize
what is in the way.

- **Floating-window list** — with per-row state (`full screen`, `hidden`, `parked`), and the
  minimize checked before falling back to shrinking a window into a strip at the bottom edge
- **A CONTROLS panel in the pad** (the new gear dot, outermost on the right), holding the keys
  layout choice, on/off switches for the `⌨` and `▤` dots, and two links: a guide to customising
  keys, and the issue tracker
- **Full keyboard or your favourite shortcuts** — the keys panel can show the built-in layout or
  a short spec of your own, switchable without a rebuild
- **The split-divider buttons are gone from the pad.** That edge is the scroll strip, and a thumb
  there is scrolling; the nudge they drove is now `op=split --es arg up|down`
- **Edge scrolling moves half as far** — a 100px swipe down the pad's side now scrolls 33.6 units
  instead of 64, and finer is the point
- A guide for customising the keys panel, and `README` coverage of the new features

## 0.2 — 2026-09-28

First release under the new name, and the first cut through the new flow: work lands on
`dev`, a pull request carries it to `main`, and CI builds the exact tree that ships.

- Renamed the app to **So7o Z Trackpad**.
- Added [a guide to customising the keys panel](README.md#customizing-the-keys-panel).
- Reworked the opening of the README around what the app is for, with a clip of it driving a
  real browser.

## 0.1 — 2026-09-26

First release, with a signed APK attached to the release.

- Floating trackpad with a drawn pointer, injected as a real `SOURCE_MOUSE` event through
  Shizuku, so a window can actually be grabbed and dragged
- Programmable keys panel — sticky modifiers, hold-to-repeat, and a layout replaceable at
  runtime with one spec string
- Display picker, plus a virtual display this app creates and can drive
- Five themes with an opacity control, a pad lock, edge scrolling, and split-divider nudges
- Scriptable through `am broadcast`; `tests/smoke.sh` covers the checks that need no fingers
