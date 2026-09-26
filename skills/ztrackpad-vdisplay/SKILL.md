---
name: ztrackpad-vdisplay
description: Create, show, hide and destroy ztrackpad's Android virtual display from a script, and read its state. Use when asked to open or close a second/virtual screen, float a display, run apps on a background display, or check whether the virtual display is up.
---

# ztrackpad-vdisplay

Scriptable control for the virtual display that **ztrackpad** owns, so it can be
driven from Termux instead of tapping the `▣` picker.

## Requirements

- ztrackpad installed, its **accessibility service enabled**, and **Shizuku granted**.
  The display work belongs to that service — it owns the overlay windows and the
  Shizuku shell binding — so nothing here works without it.
- An **adb connection** from Termux (`adb devices` shows the device). Sending a
  broadcast needs shell, and Termux has no `am` of its own.

## Run

Scripts live next to this file; run them from here.

```bash
scripts/vdisplay status                 # one line of key=value
scripts/vdisplay create                 # floating: visible in the top half
scripts/vdisplay create --headless      # headless: runs apps, nothing to look at
scripts/vdisplay show
scripts/vdisplay hide
scripts/vdisplay destroy
```

Equivalent one-liners, if the script is unavailable:

```bash
adb shell am broadcast -n app.so7o.ztrackpad/.VDisplayReceiver \
    -a app.so7o.ztrackpad.VDISPLAY --es op status
```

## The two kinds of display

| kind | Created with | Renders? | Can host apps? | Use for |
|---|---|---|---|---|
| `floating` | `create` | yes — a SurfaceView in a window pinned to the top half of the screen | yes | a second screen you can see and drive |
| `headless` | `create --headless` | no — no render target, so it is `state=OFF` and its windows are invisible | yes | running apps off-screen; **not** a control target |

They are mutually exclusive: ztrackpad holds one display slot, so creating one
releases the other.

## Output

`status` prints one line of `key=value` pairs:

```
shizuku=ready id=25 kind=floating window=shown surface=alive vsize=1245x1397 target=0 padlocked=false keys=default
```

| key | meaning |
|---|---|
| `shizuku` | `ready` or `no` — without it nothing else works |
| `id` | display id, or `-1` when none exists |
| `kind` | `floating`, `headless`, or `none` |
| `window` | `shown` or `hidden` — the floating window's visibility |
| `surface` | `alive` or `detached` — whether a surface is attached |
| `vsize` | the display's size in px |
| `target` | which display the trackpad is currently driving |
| `padlocked` | whether the trackpad refuses to move or resize |
| `keys` | `default` or `custom` — whether the keys panel is the built-in layout |

## Things worth knowing

- **`create` returns before the display exists.** The floating display is created from
  the SurfaceView's surface callback, so it is asynchronous. Poll `status` until
  `kind=floating` (it takes a round trip or two).
- **`hide` is not `destroy`.** `hide` drops the surface but keeps the display and the
  apps running on it, so `show` brings the same `id` back. `destroy` releases the
  display and kills what was on it.
- **A fresh floating display is seeded** with Samsung's secondary launcher. Without
  content an empty surface-backed display *mirrors* the default display, which shows
  up as an infinite mirror because the window is drawn on that same display.
- **The receiver is exported without a permission**, so any app on the device can
  toggle the display. The worst case is a display appearing or disappearing.
- **Put an app on it** with `am start --display <id> -f 0x10000000 -n <component>`.

## Locking the pad

The pad has a lock dot next to its theme dot: tapped, the handle stops saying `MOVE`, and
the pad stops moving and resizing until you tap it again. The dot itself looks the same in
both states; the word on the handle is the state. The lock persists across restarts.

```bash
scripts/vdisplay lock on
scripts/vdisplay lock off
scripts/vdisplay lock           # toggle
```

## Customising the keys panel

The key layout is data, not code — no rebuild:

```bash
scripts/vdisplay keys              # print the current layout
scripts/vdisplay keys '<spec>'     # set a custom one
scripts/vdisplay keys-reset        # back to the built-in layout
```

A spec is rows separated by `|`, keys by `,`:

```
esc:escape,up:dpad_up,dn:dpad_down,ok:enter|sp:space;w=8,x:del;w=5|ct:mod;m=ctrl,a:a
```

| part | meaning |
|---|---|
| `label:keycode` | one key; the label is what gets drawn |
| `;m=ctrl+alt` | meta to send with the key (`ctrl`, `alt`, `shift`, `meta`, `fn`) |
| `;n=2` | send it twice — that is how the tmux `CTRL+b b` macro works |
| `;w=6` | width in row units; a normal key is 1 |
| keycode `mod` | makes it a **sticky** modifier instead of a key that sends; needs `;m=` |

- Every row is **13 units** wide and is centred for you, so you never position anything —
  just make sure each row's widths add up to no more than 13.
- `keycode` is any `KeyEvent` name (`escape`, `tab`, `b`, `move_home`, `page_up`,
  `dpad_left`, `forward_del`, `0`) or a raw integer.
- **Escape a separator with a backslash** to use it as a label: `\:`, `\;`, `\,`, `\|`.
  The built-in layout does this for its `:` `;` `,` and `|` keys.
- A bad key is **skipped with a log line**, not fatal:
  `adb logcat -s ZTrackpad:* | grep 'keys:'`.
- `vdisplay keys` with no argument prints the layout currently in force, which is the
  built-in one until you set a custom spec — so you can copy it and edit.

Reading the layout back gives `spec=...`; `status` reports `keys=default` or `keys=custom`.

### The quoting trap if you call am broadcast yourself

`adb shell` does not exec argv directly — it joins the arguments and hands the string to a
shell **on the device**. A spec containing `|` or `;` is therefore parsed as shell syntax
and silently truncated:

```
/system/bin/sh: sp:space: inaccessible or not found
```

`scripts/vdisplay` quotes the spec for the remote shell, so use it rather than a raw
`am broadcast` line.

## Driving it

Point the trackpad at the display before injecting input:

1. Open the `▣` picker and tap the display's row (this sets the *target*), **or** it
   arrives as a target automatically via the broadcast-free UI only — there is no
   scriptable "set target" yet.
2. Then drag on the pad to move the pointer and tap to click.

Pointer visibility depends on the target: on the phone screen the arrow is an overlay
there; on a floating display it is drawn over that window; on an external display
(XREAL/DeX) it is a second overlay window opened *on* that display, because a window
we own cannot be composited into another display's output.
