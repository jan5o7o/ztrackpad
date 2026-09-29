---
name: ztrackpad-vdisplay-launch
description: Put an app on ztrackpad's floating virtual display (Chrome at a URL, YouTube, anything launchable) and verify it from pixels. Covers recovering a missing adb connection when wireless debugging or the mDNS advert has gone stale on this phone. Use when asked to open an app or a site on the second/floating screen, to screenshot what is on it, or when `adb devices` is empty here.
---

# ztrackpad-vdisplay-launch

The display's own life cycle — create/show/hide/destroy, keys, pad lock, tasks — belongs to the
sibling skill `../ztrackpad-vdisplay/SKILL.md`; read that for the ops and the `status` schema.
This skill is the half it does not cover: **getting an app onto the display, and proving it got
there.**

Order: adb → display → launch → verify. Each step can fail *silently*, so each has a check.

## 1. adb, when `adb devices` is empty

Wireless debugging has to be on (Developer options). Toggling it is a **human step** — per
`AGENTS.md`, stop and ask rather than improvising around it.

```bash
adb devices -l                    # empty?
python3 ~/adbdiscover.py          # mDNS advert: serial, addresses, port
```

**Trust the address, not the port.** mDNS records outlive a Wi-Fi change: the address is whatever
IP the phone had when the record was published, and that port then refuses. Compare both against
the *current* IP (`termux-wifi`; `ip -4 addr show wlan0` prints nothing on this device) and
connect against the current address:

```bash
adb connect 127.0.0.1:<port>      # same device: loopback dodges the IP churn entirely
```

`connected to 127.0.0.1:<port>` plus `device` in `adb devices` → done. An already-authorized key
needs no pairing code; if adb asks for one, it is on the "Pair device with pairing code" screen
and only a human can read it.

When every advertised port refuses, find the live one by scanning — and know that several *other*
app sockets on this phone accept TCP and then hang, which shows up as `offline` rather than as an
error:

```bash
# connect-scan 127.0.0.1 and the Wi-Fi IP over the full port range, then try each hit
ADB_TRACE=all adb connect <ip>:<port>   # prints the real reason: 'failed to connect', or offline
```

`offline` is a stale entry from a failed handshake, not a busy device; `adb disconnect` clears
them. A port that completes the handshake reports `connected` and appears as `device`.

## 2. The display

```bash
cd ~/ztrackpad/skills/ztrackpad-vdisplay && ./scripts/vdisplay status
```

`id` is what step 3 needs; `kind=floating` with `surface=alive` is the state an app can go on.
`create` is asynchronous — poll `status` until `kind=floating`.

Without adb the same broadcast can still be *sent*, because Termux's own `am` wrapper needs no
shell:

```bash
am broadcast -n app.so7o.ztrackpad/.VDisplayReceiver -a app.so7o.ztrackpad.VDISPLAY --es op create
```

Fire and forget: on API 14+ termux-am does not wait for the broadcast result, so it answers
`Broadcast sent without waiting for result` and the `status` line is lost. Use it to create the
display, then read `status` over adb once the connection is back.

## 3. Launch an app on it

```bash
adb shell am start --display <id> -f 0x10000000 \
    -n com.android.chrome/com.google.android.apps.chrome.Main \
    -a android.intent.action.VIEW -d 'https://www.youtube.com'
```

- `--display` needs shell, so this step is adb-only. Termux's `am` prints its usage and exits if
  handed `--display`, and the app's own receiver has no launch op, so there is no third route.
- If the app is already running, the start can be **delivered to the running instance** instead of
  creating one on the display: the answer is then `Activity not started, intent has been delivered
  to currently running top-most instance`. That is not necessarily a failure — check where the task
  landed (step 4) before force-stopping anything.

## 4. Verify from pixels, or from the task list

The floating display renders into a window on the phone's screen, so an ordinary capture shows it:

```bash
adb shell screencap -p /data/local/tmp/s.png && adb pull /data/local/tmp/s.png ~/vd.png
adb shell dumpsys activity activities | grep -A 6 "Display #<id>"
```

Read the PNG; do not trust the verb. `screencap` warns about multiple displays and picks one
itself — `-d` wants a SurfaceFlinger token from `dumpsys SurfaceFlinger --display-id`, not an
Android display id.

`logcat` is **not** a result channel here: Termux sees only its own UID's logs, so the app's lines
never appear in it.

## Traps

- **`target=0` in `status`** means the pad still drives the phone display. Hand the pad the display
  with the ▣ picker before injecting input at it — there is no scriptable set-target.
- **Headless (`create --headless`) cannot be verified or driven:** no surface, no pixels, every
  capture route times out. Keep it for work that needs no screen.
- **`hide` is not `destroy`** — hiding keeps the display and its apps running, so `show` brings the
  same `id` back.
- **A phone-display `screencap` is not the page's own pixels.** For HTML inside a WebView, capture
  over CDP instead: `~/android-webview-cdp/cdp.mjs --shot`.

## Checklist

| step | check | good |
|---|---|---|
| adb | `adb devices -l` | `device`, not `offline` |
| display | `scripts/vdisplay status` | `kind=floating surface=alive`, note the `id` |
| launch | `am start --display <id> …` | created a task, *or* delivered to a running instance |
| placed | `dumpsys activity activities \| grep -A 6 "Display #<id>"` | the app's task is on that display |
| visible | `screencap` + read the PNG | the app's content in the top-half window |
