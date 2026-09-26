# Screenshots and recordings

Taken on the reference device — Galaxy Z Fold 4 (SM-F936B), Android 16 / One UI — on the
**unfolded inner display**, 1812×2176.

| file | what it shows |
|---|---|
| `fold4-unfolded.jpg` | the whole app in use: keys panel, pad with `≡ LOCKED`, docked dots, split nudges, floating dots, pointer |
| `theme-and-display-picker.jpg` | the theme menu with the opacity slider, and the display picker listing both built-in screens plus the two virtual-display options |

Recordings live in `../media/`. Each is cut where the screen showed something personal — the
windows are listed under **Cutting footage** below:

| file | length | what it shows |
|---|---|---|
| `demo-browser.mp4` | 75s | the pointer driving a browser — it happens to be this repo's own GitHub page |
| `demo-termux.mp4` | 57s | Termux, the keys panel, and the theme menu |
| `demo-vdisplay.mp4` | 75s | the display picker, the floating display it creates, and apps driven on that display |

Compact GIFs are embedded in the README, because a GIF is the only medium that animates inline
(see below). The first two are cut from the recordings above; the third has its own source, noted
under the table:

| gif | size | cut | shows |
|---|---|---|---|
| `demo.gif` | 0.14 MB | browser 11.2–15s | the pointer driving this repo's GitHub page, keys panel and pad over Termux |
| `demo-termux.gif` | 0.27 MB | termux 28.5–35.5s | the theme menu with the preset switching from Default to High contrast |
| `demo-vdisplay.gif` | 0.64 MB | vdisplay 16–20.5s | creating a floating display, then the new display up with its own launcher |

**Cut the Termux clip between ~28s and ~36s.** Its last seconds showed `neofetch`, which prints
the kernel line containing the firmware build string that was blurred out of the screenshots, and
a GIF cut at the tail would have republished exactly what was removed. That footage is gone from
the recording itself now (see **Cutting footage** below), so the tail is safe for new cuts — but
the window stands for the source clips these GIFs came from, and OCR is still not the check: at
540 px it read that line as gibberish and reported no match.

**Cut the browser clip inside 11.2–15 s.** It cross-fades twice while the split layout settles,
around 10.4–11.1 s and 15.25–15.75 s, and frames inside a fade are blended and unusable — an
earlier hero ended on one. Both bounds come from mapping the clip frame by frame.

Both recordings were re-encoded to 540×650 for the web. The **full** GIFs (16 MB and 7.4 MB) stay
out of the repo, but the **compact GIFs** are committed and embedded in the README — `../media/demo.gif`
as the hero, 0.14 MB, the 11.2–15 s window of the browser clip, and `../media/demo-termux.gif`,
0.27 MB, cut the same way. All three are 480 px at 10 fps on a 16-colour palette. The content is
mostly monochrome UI chrome, so the small palette costs little: the two quiet clips land at 0.14
and 0.27 MB, while `demo-vdisplay.gif` — which redraws a whole launcher over a photo wallpaper —
lands at 0.64 MB.

`demo-vdisplay.gif` comes from the third recording, the 75 s one, whose **recording is committed
too** as `../media/demo-vdisplay.mp4` (2.1 MB): the clip carries the creation moment, the recording
carries the rest of that session on the display it made — a launcher, YouTube, a browser. The
window is 16–20.5 s, found the same way as the others, by mapping the clip — the picker opens at
12.5 s, `create floating display (top half)` is picked at 16.2 s, and the new display is up with
its launcher by 17 s. That picker row names the display `ztrackpad`, the public name, and no
private pattern appears in any frame reviewed. OCR was no help again: it read two of the three
frames checked as empty and the third as `atrackpad`, so the check that counted was eyes on
full-resolution frames — the one thing the leak scan cannot do.

That is not decoration, it is the only option: **a committed video cannot play.** GitHub serves
`.mp4` as `application/octet-stream` (an image gets `image/jpeg`, which is why images render),
its blob page offers only a download, and its markdown strips `<video>`, `<source>` and `<iframe>`
from a committed `.md`. So a GIF is the one medium that animates inline — and no amount of
hosting elsewhere changes that, because the constraint is the sanitiser, not the server.

Capturing your own: `screencap` defaults to the **cover** screen on this device, so the
inner display needs `screencap -d <hwc-display-id>` — ids from
`dumpsys SurfaceFlinger --display-id`.

**Before adding a picture here, look at it.** The private-string check in
`ztrackpad-sync` reads text, so it cannot see a package name, an app label or a notification
baked into a screenshot. Images are the one part of the tree that only a human can review.

Both screenshots have the **kernel build string blurred out**: it is the device's firmware
build ID, which the picture does not need. Redact by measurement rather than by guesswork —
crop the region, blur that crop, composite it back:

```bash
magick shot.jpg -alpha off \( +clone -crop 1005x48+700+386 +repage -blur 0x10 \) \
    -geometry +700+386 -composite -quality 90 out.jpg
```

Then check the redaction instead of trusting it: crop the band from both the original and
the result and run `tesseract` over each. The blurred band returns nothing; the original
reads `Linux 5.10.236-android12-9-31998796-abF936BXXSCIZH3` straight back, which proves the
check is not just passing because OCR gave up.

## Cutting footage

**A recording may not show the browser's history suggestions, a private hostname, or the kernel
build string, and the window is cut rather than blurred.** Each recording here had one:
`demo-browser.mp4` loses 27–32.8s, `demo-termux.mp4` loses 57.5s to the end, and
`demo-vdisplay.mp4` loses 50–56s. In the first and third the address bar's suggestion list is
drawn from browser history, so it republished whatever hosts that history held — the lists in
both named private ones. The Termux case was worse: the rule against the kernel string
was already written on this page, and the published recording broke it anyway, because the rule
had been applied to the screenshots and to the GIF cut windows and never to the footage between
them.

Cutting rather than blurring is deliberate. The dropdown is an animated overlay that grows and
moves as it filters, so a blur box cannot be tracked across frames, while a cut is provable by
the absence of frames where a blur can only be spot-checked. All three windows sit after the GIF
windows, so the cuts in the table above still line up with the recordings.

To re-check a recording, sample it at full resolution every 2s and OCR every frame. Treat a clean
sweep as necessary and not sufficient: OCR missed the browser's suggestion list at 1s sampling and
caught it at 2s, and it read two of three frames of the display panel as empty. Frames around an
address bar are for eyes.
