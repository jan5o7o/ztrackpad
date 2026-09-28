# Changelog

Notable changes per release, newest first. The version is `versionName` in
`AndroidManifest.xml`, and every release also increments `versionCode` — Android refuses an
update that does not, so the two move together. Each entry here is the release's notes.

Keep this file free of development-tree names: it is exported to the public repo unchanged, so
it can only ever describe the app as shipped.

## Unreleased

Nothing yet.

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
