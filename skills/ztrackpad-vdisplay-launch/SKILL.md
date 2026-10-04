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

The display's own launch op goes through ztrackpad's Shizuku shell bridge, so it works
for headless displays too (`am start --display` from adb can be refused for those):

```bash
cd ~/ztrackpad/skills/ztrackpad-vdisplay && ./scripts/vdisplay launch com.android.chrome --url 'https://www.youtube.com/'
# or, for an app that is not URL-driven:
./scripts/vdisplay launch com.android.settings
```

A bare package name is resolved by `am`; `pkg/.Activity` names an explicit component.
The reply is `ok launch display=<id> target=<pkg> …` — the display id must match the
`id` from step 2's `status`.

The adb fallback still exists if the broadcast route is down, with its limits:

```bash
adb shell am start --display <id> -f 0x10000000 \
    -n com.android.chrome/com.google.android.apps.chrome.Main \
    -a android.intent.action.VIEW -d 'https://www.youtube.com'
```

- If the app is already running, the start can be **delivered to the running instance**
  instead of creating one on the display: the answer is then `Activity not started,
  intent has been delivered to currently running top-most instance`. That is not
  necessarily a failure — check where the task landed (step 4) before force-stopping
  anything.
- To drive the app with the pad afterwards: `vdisplay target <id>` (or `vdisplay
  target <pkg>` to name it by package).

## 4. Verify from the display's own pixels, or from the task list

The composite of the phone screen shows the floating window plus every overlay on it;
`shot` captures the display's pixels directly instead:

```bash
cd ~/ztrackpad/skills/ztrackpad-vdisplay && ./scripts/vdisplay shot --name app-state
adb pull /data/local/tmp/app-state.png ~/vd.png
```

Read the PNG; do not trust the verb. Only a **floating** display has pixels to capture;
`shot` on a headless display fails cleanly (`error: shot: could not capture`). For a
floating display the same check can also come from the task list:

```bash
adb shell dumpsys activity activities | grep -A 6 "Display #<id>"
```

`logcat` is **not** a result channel here: Termux sees only its own UID's logs, so the
app's lines never appear in it.

## Traps

- **`target=<id>` in `status`** is whatever the trackpad drives. Point the pad at the
  display before injecting input at it: `vdisplay target <id>` — or `vdisplay target
  <pkg>` to name it by package.
- **Headless (`create --headless`) cannot be driven or verified:** no surface, no
  pixels, and `shot` fails cleanly on it. Keep it for work that needs no screen.
- **`hide` is not `destroy`** — hiding keeps the display and its apps running, so `show` brings the
  same `id` back.
- A phone-display `screencap` is **not** the page's own pixels. For HTML inside a WebView,
  capture over CDP instead: `~/android-webview-cdp/cdp.mjs --shot`.

## Checklist

| step | check | good |
|---|---|---|
| adb | `adb devices -l` | `device`, not `offline` |
| display | `scripts/vdisplay status` | `kind=floating surface=alive`, note the `id` |
| launch | `vdisplay launch <pkg> [--url]` | `ok launch display=<id> target=<pkg>` |
| placed | `dumpsys activity activities \| grep -A 6 "Display #<id>"` | the app's task is on that display |
| target | `vdisplay target <id>` | the display the trackpad drives |
| visible | `vdisplay shot --name v` + `adb pull` + read the PNG | the app's content, without the phone composite |
