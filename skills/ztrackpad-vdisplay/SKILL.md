---
name: ztrackpad-vdisplay
description: Create, show, hide and destroy ztrackpad's Android virtual display from a script; launch apps on it; point the trackpad's input at a display; and capture the display's own pixels. Use when asked to open or close a second/virtual screen, float a display, run apps on a background display, switch which display the trackpad drives, or check whether the virtual display is up.
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
scripts/vdisplay create --w 1812 --h 2176      # a display at these dimensions
scripts/vdisplay create --headless --w 1812 --h 2176
scripts/vdisplay show
scripts/vdisplay hide
scripts/vdisplay destroy
scripts/vdisplay launch com.android.settings            # package (resolved by am)
scripts/vdisplay launch com.android.settings/.Settings   # explicit component
scripts/vdisplay launch com.android.chrome --url 'https://www.youtube.com/'
scripts/vdisplay target 0                  # pad input -> the phone display
scripts/vdisplay target                    # read the current target back
scripts/vdisplay shot                      # -> /data/local/tmp/vdisplay-<id>.png
scripts/vdisplay shot --name verify        # -> /data/local/tmp/verify.png
scripts/vdisplay lock on                   # freeze the pad's position and size
scripts/vdisplay flick on                  # edge strips bank the gesture, scroll on release
scripts/vdisplay marks on                   # dotted lines where the edge scroll strips are
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

`create --w W --h H` sizes the next display instead of the defaults (1920x1080 for
the headless variant; the floating variant sizes its window — and therefore its
display — to the request, clamped to the phone screen). The headless variant takes
the size exactly; a floating display cannot be bigger than the screen it is drawn on,
so `status` reports the size that actually came up.

## Output

`status` prints one line of `key=value` pairs:

```
shizuku=ready id=25 kind=floating window=shown surface=alive vsize=1245x1397 target=0 padlocked=false flick=off marks=on keys=default
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
| `flick` | `on` or `off` — whether the edge strips bank the gesture and scroll once on release |
| `marks` | `on` or `off` — whether the pad shows dotted lines where the edge strips are |
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
- **Put an app on it** with `scripts/vdisplay launch <package-or-component>` — or, if
the script is unavailable, `am start --display <id> -f 0x10000000 -n <component>`
from adb. The adb route can be refused for headless displays; `launch` goes through
ztrackpad's shell bridge and does not have that limit.

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

## Launching an app on it

```bash
scripts/vdisplay launch com.android.settings             # package: am resolves it
scripts/vdisplay launch com.android.settings/.Settings    # explicit component
scripts/vdisplay launch com.android.chrome --url 'https://…'
```

`launch` starts the app on the display ztrackpad owns (`status`'s `id`). It runs inside
ztrackpad's Shizuku shell process, so it never crosses adb and the platform cannot
refuse it for a headless display: the shell is that display's owner and holds
`INTERNAL_SYSTEM_WINDOW`, which the framework checks first
(`ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay`).
The reply is `ok launch display=<id> target=<pkg>` plus am's own first line
(`Starting: Intent { … }`, or a warning when the app was already on top).

## Pointing the trackpad at it

The pad's input target (`target=` in `status`) was picker-only until now; the `target`
op uses the picker's exact code path, so a script cannot get a different result from a
finger:

```bash
scripts/vdisplay target 0          # the phone display
scripts/vdisplay target <id>       # the display id `status` just reported
scripts/vdisplay target com.android.settings   # the display that package is running on
scripts/vdisplay target            # read it back: `ok target 0 Built-in Screen`
```

## Capturing the display's own pixels

The floating display renders into a window on the phone, so a plain `screencap` gets
you the phone composite — including every overlay on it. `shot` bypasses that: it
runs `screencap -d` inside the shell process against the SurfaceFlinger value for the
ztrackpad display, and writes a PNG the agent can pull.

```bash
scripts/vdisplay shot              # /data/local/tmp/vdisplay-<id>.png
scripts/vdisplay shot --name verify
adb pull /data/local/tmp/verify.png ~/verify.png
```

- The PNG lands in `/data/local/tmp` (shell-writable) so `adb pull` can reach it.
- A **headless** display has no pixels to composite in the first place — `shot` fails
  cleanly with `error: shot: could not capture` rather than hanging.

## Driving it

Point the trackpad at the display before injecting input:

1. `scripts/vdisplay target <id>` — the `target` op does exactly what tapping the
   `▣` picker's row does (same setter), and `scripts/vdisplay target` with no argument
   reads the current target back.
2. Then drag on the pad to move the pointer and tap to click.

Pointer visibility depends on the target: on the phone screen the arrow is an overlay
there; on a floating display it is drawn over that window; on an external display
(XREAL/DeX) it is a second overlay window opened *on* that display, because a window
we own cannot be composited into another display's output.

### Driving it from the script

The input ops route through the same Shizuku path as the pad's own clicks and keys,
so every event carries the *target* display's id — they drive the app **on the
display**, not the phone under it:

```bash
scripts/vdisplay tap 500 300      # tap at these px on the target display
scripts/vdisplay type hello       # type into the focused field, spaces included
scripts/vdisplay press ENTER      # one keycode (any KeyEvent name or integer)
```

- Coordinates are **display pixels** of the current target display, not phone-screen
  pixels. When the two differ, aim with `shot` pulled through OCR or an image editor.
- `shot` after a `tap`/`type` is the standard proof: the display changed or it did not.
- `type` takes the whole remainder of the command line, so text with spaces needs no
  quoting; `press` takes exactly one keycode token.

## Automating a whole test pass

The pieces chain: create a display, launch the app under test on it, set the target so
the pad drives it, then verify from the display's own pixels.

```bash
scripts/vdisplay create --headless --w 1812 --h 2176   # or create (floating)
scripts/vdisplay launch com.android.settings
scripts/vdisplay target "$(scripts/vdisplay status | sed -n 's/.* id=\([0-9-]*\).*/\1/p')"
scripts/vdisplay shot --name app-state
```

## Testing the display

`scripts/vdisplay-test` is the standard driving test and part of the stack: it runs the
whole agent loop — create (headless then floating), size, launch, placement, target switch
and readback, shot (clean fail on headless, real pixels on floating), and a tap+type
interaction probe — asserting each step (11 checks, PASS/FAIL summary, non-zero exit on
failure). Run it after any change to the display or its ops:

```bash
scripts/vdisplay-test          # both shapes
scripts/vdisplay-test headless # or just one
```

Verified matrix on the reference device (Z Fold 4 / Android 16): headless 1812x2176
launches an app with its task on that display; `target` switches and reads back; `shot`
fails cleanly on headless and yields a real PNG on floating; the tap+type probe changes
the screen. The interaction proof is screen-change, not OCR — it proves delivery, not
content.
