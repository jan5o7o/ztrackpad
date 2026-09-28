# Changelog

Notable changes per release, newest first. The version is `versionName` in
`AndroidManifest.xml`, and every release also increments `versionCode` — Android refuses an
update that does not, so the two move together. Each entry here is the release's notes.

Keep this file free of development-tree names: it is exported to the public repo unchanged, so
it can only ever describe the app as shipped.

## Unreleased

Nothing yet.

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
