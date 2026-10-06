#!/usr/bin/env bash
# tests/smoke.sh - end-to-end checks for a running trackpad that need no fingers.
#
# Everything here talks to the app's own broadcast API and reads its one-line status
# reply, so there is no tapping, no screenshot and no eyes needed. This is the
# counterpart to the verification list in AGENTS.md: those rows need a human finger
# (two-finger gestures, how a thing *feels*), these do not.
#
#   tests/smoke.sh [--with-display] [--with-popup] [--with-clip] [package-id]
#
#   --with-display   also create and destroy a headless virtual display. Off by
#                    default because it is the only check with a visible side effect
#                    on the device, even though it cleans up after itself.
#
#   --with-popup     also open, raise and close two pop-up windows. Off by default for
#                    the same reason, and a louder one: these are visible windows, not an
#                    invisible display. Cleans up with force-stop.
#
#   --with-clip      also drive the clipboard modal: set a junk specimen, open it, clean
#                    it, copy it back and read it again. Off by default because it
#                    CLOBBERS THE SYSTEM CLIPBOARD, which is the user's data, and because
#                    it opens a visible window.
#
# The package id is discovered, never hardcoded: the argument wins, then $TRACKPAD_PKG,
# then the single installed package whose name contains "trackpad". That is deliberate -
# the private build and the public build have different package ids, so this file has to
# work unchanged in both trees. A hardcoded id would also trip the private-string guard in
# ~/.local/bin/ztrackpad-sync, which is the same reason.
set -uo pipefail

with_display=false
with_popup=false
with_clip=false
pkg=""
for a in "$@"; do
    case "$a" in
        --with-display) with_display=true ;;
        --with-popup)   with_popup=true ;;
        --with-clip)    with_clip=true ;;
        -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*) echo "smoke: unknown option '$a'" >&2; exit 2 ;;
        *)  pkg="$a" ;;
    esac
done

pass=0; fail=0
ok()   { printf '  PASS  %s\n' "$1"; pass=$((pass + 1)); }
bad()  { printf '  FAIL  %s\n' "$1"; fail=$((fail + 1)); }
info() { printf '        %s\n' "$1"; }

# A field out of the status line: "id=16 kind=none ..." -> "16"
field() { printf '%s' "$1" | tr ' ' '\n' | sed -n "s/^$2=//p"; }

# Single-quote a value for the REMOTE shell. `adb shell` does not exec argv directly: it
# joins the arguments and hands the string to a shell on the device, so a keys spec full
# of '|' and ';' is parsed as shell syntax and silently truncated (see AGENTS.md and
# skills/ztrackpad-vdisplay/scripts/vdisplay, which does the same thing).
sq() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }

# The whole broadcast dance, returning just the data="..." payload (empty on failure).
state_line() {
    adb shell am broadcast -n "$pkg/.VDisplayReceiver" -a "$pkg.VDISPLAY" \
        --es op "${1:-status}" "${@:2}" 2>&1 \
        | sed -n 's/.*data="\(.*\)"[[:space:]]*$/\1/p' | head -1
}

echo "== smoke: a device, a package, and a live service"

if [ "$(adb get-state 2>/dev/null)" != "device" ]; then
    bad "no adb device (adb get-state)"
    info "connect first: adb connect <phone-ip>:<port> (wireless debugging)"
    exit 1
fi
ok "adb device present"

if [ -z "$pkg" ] && [ -n "${TRACKPAD_PKG:-}" ]; then pkg="$TRACKPAD_PKG"; fi
if [ -z "$pkg" ]; then
    found=$(adb shell pm list packages 2>/dev/null | sed -n 's/^package:\(.*trackpad.*\)$/\1/p')
    n=$(printf '%s\n' "$found" | grep -c . || true)
    if [ "$n" != "1" ]; then
        bad "expected exactly one installed 'trackpad' package, found $n"
        printf '%s\n' "$found" | sed 's/^/        /'
        info "only one build may be installed - two overlay services would both inject input"
        exit 1
    fi
    pkg="$found"
fi
ok "package: $pkg"

st=$(state_line status)
if [ -z "$st" ]; then
    bad "status returned nothing - is the accessibility service enabled?"
    info "raw reply: $(adb shell am broadcast -n "$pkg/.VDisplayReceiver" -a "$pkg.VDISPLAY" --es op status 2>&1 | tail -2 | tr '\n' ' ')"
    exit 1
fi
ok "status replied"
info "$st"

echo
echo "== dependency and schema"

if [ "$(field "$st" shizuku)" = "ready" ]; then
    ok "shizuku=ready (real input injection available)"
else
    bad "shizuku=$(field "$st" shizuku) - start Shizuku and grant this app permission"
    info "without it clicks fall back to dispatchGesture and drag does not work"
fi

missing=""
for k in id kind window surface vsize target padlocked keys; do
    [ -n "$(field "$st" "$k")" ] || missing="$missing $k"
done
if [ -z "$missing" ]; then
    ok "status line carries all 8 documented fields"
else
    bad "status is missing:$missing"
    info "README and SKILL.md document these as the status schema"
fi

echo
echo "== keys layout round-trip (original restored afterwards)"

# Remember whether it was the built-in layout or a custom one, not just the spec: setting
# the spec back always leaves `keys=custom`, so restoring a built-in layout has to go
# through keys-reset or the test silently promotes the app to a custom layout.
keys_was=$(field "$(state_line status)" keys)
original=$(state_line keys | sed -n 's/^spec=//p')
if [ -z "$original" ]; then
    bad "'keys' with no spec did not hand back a layout"
else
    ok "keys read back (${#original} chars)"
fi

state_line keys --es spec "$(sq 'aa:a,bb:b')" >/dev/null
if [ "$(field "$(state_line status)" keys)" = "custom" ]; then
    ok "custom layout accepted, status says keys=custom"
else
    bad "status did not report keys=custom after setting one"
fi

if [ "$keys_was" = "custom" ] && [ -n "$original" ]; then
    state_line keys --es spec "$(sq "$original")" >/dev/null
else
    state_line keys-reset >/dev/null
fi
after=$(field "$(state_line status)" keys)
if [ "$after" = "$keys_was" ]; then
    ok "layout restored ($after)"
else
    bad "layout not restored: expected keys=$keys_was, got keys=$after"
fi

echo
echo "== pad lock round-trip (original restored afterwards)"

was=$(field "$(state_line status)" padlocked)
state_line lock --es arg on >/dev/null
if [ "$(field "$(state_line status)" padlocked)" = "true" ]; then
    ok "lock on -> padlocked=true"
else
    bad "lock on did not report padlocked=true"
fi
state_line lock --es arg off >/dev/null
if [ "$(field "$(state_line status)" padlocked)" = "false" ]; then
    ok "lock off -> padlocked=false"
else
    bad "lock off did not report padlocked=false"
fi
if [ "$was" = "true" ]; then
    state_line lock --es arg on >/dev/null
    info "pad was locked before this run, left locked"
fi

echo
echo "== flick-to-scroll round-trip (original restored afterwards)"

# The edge-strip scroll feel: live (off) vs banked-and-spent-on-release (on). Same
# broadcast journey as the lock, so the setters agree with the CONTROLS row.
flick_was=$(field "$(state_line status)" flick)
state_line flick --es arg on >/dev/null
if [ "$(field "$(state_line status)" flick)" = "on" ]; then
    ok "flick on -> status flick=on"
else
    bad "flick on did not report flick=on"
fi
state_line flick --es arg off >/dev/null
if [ "$(field "$(state_line status)" flick)" = "off" ]; then
    ok "flick off -> status flick=off"
else
    bad "flick off did not report flick=off"
fi
if printf '%s' "$(state_line flick --es arg maybe)" | grep -q '^error:'; then
    ok "flick rejects anything but on|off"
else
    bad "flick accepted something other than on|off"
fi
if [ "$flick_was" = "on" ]; then
    state_line flick --es arg on >/dev/null
    info "flick was on before this run, left on"
fi

echo
echo "== scroll-marks round-trip (original restored afterwards)"

# Cosmetic twin of flick: same broadcast journey, same setter as the CONTROLS row.
marks_was=$(field "$(state_line status)" marks)
state_line marks --es arg on >/dev/null
if [ "$(field "$(state_line status)" marks)" = "on" ]; then
    ok "marks on -> status marks=on"
else
    bad "marks on did not report marks=on"
fi
state_line marks --es arg off >/dev/null
if [ "$(field "$(state_line status)" marks)" = "off" ]; then
    ok "marks off -> status marks=off"
else
    bad "marks off did not report marks=off"
fi
if printf '%s' "$(state_line marks --es arg maybe)" | grep -q '^error:'; then
    ok "marks rejects anything but on|off"
else
    bad "marks accepted something other than on|off"
fi
if [ "$marks_was" = "on" ]; then
    state_line marks --es arg on >/dev/null
    info "marks was on before this run, left on"
fi

echo
echo "== the documented implicit-broadcast trap"

implicit=$(adb shell am broadcast -a "$pkg.VDISPLAY" --es op status 2>&1)
if printf '%s' "$implicit" | grep -q 'data="'; then
    bad "an implicit broadcast reached the receiver - README/AGENTS say it must not"
    info "on API 26+ a manifest receiver is not an implicit-broadcast target; -n is required"
else
    ok "implicit broadcast did not reach it (the -n component is required)"
fi

echo
echo "== the testing ops' fail-closed paths"

# The testing ops fail closed without a display, and must not touch a live one. All of
# these run only when no display is up, so a concurrent test cannot be disturbed.
kind_now=$(field "$(state_line status)" kind)
if [ "$kind_now" = "none" ]; then
    if printf '%s' "$(state_line launch --es arg com.android.settings)" | grep -q '^error:'; then
        ok "launch without a display fails closed"
    else
        bad "launch without a display did not fail closed"
    fi
    if printf '%s' "$(state_line shot)" | grep -q '^error:'; then
        ok "shot without a display fails closed"
    else
        bad "shot without a display did not fail closed"
    fi
    if printf '%s' "$(state_line create --ei w 400 --ei h 200)" | grep -q '^error:'; then
        ok "create rejects an improbable size (400x200)"
    else
        bad "create accepted an improbable size"
    fi
else
    info "a display is already up (kind=$kind_now) - skipping the no-display checks"
fi
if printf '%s' "$(state_line target --es arg 999998)" | grep -q '^error:'; then
    ok "target with a bogus display id fails closed"
else
    bad "target with a bogus display id did not fail closed"
fi
if printf '%s' "$(state_line target)" | grep -q '^ok target'; then
    ok "target reads the current target back"
else
    bad "target did not read the current target back"
fi

echo
echo "== floating windows (pop-up view)"

# Read over the shell bridge from `dumpsys activity activities`, because cross-app tasks
# need REAL_GET_TASKS and only the shell UID holds it. Reply format:
#   ok tasks n=<count> display=<d> <id>:<pkg>:<visible|hidden>:<floating|fullscreen> ...
# Floating windows first (a fullscreen task in the dump comes first but belongs last, since
# it is what the floating ones are sitting on top of), then the visible fullscreen app.
# The shape is worth checking even with nothing floating, because the rows that matter are
# the hidden ones - a window behind another, or minimized, is the whole reason the list
# exists - and a naive parse also picks up freeform ROOT tasks and the zero-size
# per-desktop containers, which look identical in the dump.
tasks_line=$(state_line tasks)
info "$tasks_line"
case "$tasks_line" in
    "ok tasks n="*) ok "tasks listed" ;;
    *) bad "tasks did not reply (got '$(printf '%s' "$tasks_line" | cut -c1-60)')" ;;
esac

if [ -n "$(field "$tasks_line" display)" ]; then
    ok "list is scoped to a display"
else
    bad "no display= in the tasks reply"
fi

declared=$(field "$tasks_line" n)
rows=$(printf '%s' "$tasks_line" | tr ' ' '\n' | grep -cE '^[0-9]+:.+:(visible|hidden):(floating|fullscreen)$' || true)
strays=$(printf '%s' "$tasks_line" | tr ' ' '\n' \
    | grep -vE '^(ok|tasks|n=[0-9]+|display=[0-9]+|[0-9]+:.+:(visible|hidden):(floating|fullscreen))$' | grep -c . || true)
if [ -n "$declared" ] && [ "$declared" = "$rows" ] && [ "$strays" = "0" ]; then
    ok "n=$declared matches $rows well-formed rows"
else
    bad "malformed tasks reply: n=$declared, $rows rows, $strays stray token(s)"
fi

if $with_popup; then
    echo
    echo "== pop-up window round-trip (--with-popup)"

    # windowingMode is an INT here: 5 is FREEFORM. The word "freeform" throws
    # NumberFormatException, which reads like the launch failed when only the argument was
    # wrong. On this device that lands in Samsung's pop-up chrome, and a Samsung pop-up
    # window IS a freeform task - so one code path covers both shapes.
    pop_a=com.sec.android.app.popupcalculator
    pop_b=com.android.settings
    adb shell am force-stop "$pop_a" >/dev/null 2>&1
    adb shell am force-stop "$pop_b" >/dev/null 2>&1
    sleep 1
    adb shell am start --windowingMode 5 -n "$pop_a/.Calculator" >/dev/null 2>&1
    sleep 3
    adb shell am start --windowingMode 5 -n "$pop_b/.Settings" >/dev/null 2>&1
    sleep 3

    id_a=$(printf '%s' "$(state_line tasks)" | tr ' ' '\n' \
        | sed -n "s/^\([0-9]\+\):$pop_a:visible:floating$/\1/p" | head -1)
    id_b=$(printf '%s' "$(state_line tasks)" | tr ' ' '\n' \
        | sed -n "s/^\([0-9]\+\):$pop_b:visible:floating$/\1/p" | head -1)
    if [ -n "$id_a" ] && [ -n "$id_b" ]; then
        ok "both pop-up windows listed as visible (tasks $id_a, $id_b)"
    else
        bad "pop-up windows missing from the list (got '$id_a' and '$id_b')"
        info "raw: $(state_line tasks)"
    fi

    # Raise the one that is now behind. Cross-app task focus needs MANAGE_ACTIVITY_TASKS,
    # which is the other half of why this goes over the bridge.
    if [ -n "$id_a" ]; then
        state_line taskfocus --es arg "$id_a" >/dev/null
        sleep 2
        order=$(state_line tasks)
        # The full-screen row is first by design now, so "raised it" means ahead of the other
        # floating window rather than at the very top of the list.
        a_pos=$(printf '%s' "$order" | tr ' ' '\n' | grep -n "^$id_a:" | cut -d: -f1)
        b_pos=$(printf '%s' "$order" | tr ' ' '\n' | grep -n "^$id_b:" | cut -d: -f1)
        if [ -n "$a_pos" ] && [ -n "$b_pos" ] && [ "$a_pos" -lt "$b_pos" ]; then
            ok "taskfocus $id_a put it ahead of task $id_b"
        else
            bad "taskfocus $id_a did not come first ($id_a at $a_pos, $id_b at $b_pos)"
            info "$order"
        fi
    fi

    adb shell am force-stop "$pop_a" >/dev/null 2>&1
    adb shell am force-stop "$pop_b" >/dev/null 2>&1
    sleep 2
    left=$(printf '%s' "$(state_line tasks)" | tr ' ' '\n' \
        | grep -cE ":($pop_a|$pop_b):" || true)
    if [ "$left" = "0" ]; then
        ok "closed windows left the list"
    else
        bad "$left row(s) survived force-stop"
    fi
fi

echo
echo "== controls: keys mode and the optional dots (original restored afterwards)"

# Both ops drive the same setters the CONTROLS panel's rows do, so this round-trips what the
# rows toggle. What it cannot check is that the dot actually disappears - that was measured by
# hand (3 bubbles -> 2 -> 3 in `dumpsys window windows`) and is not repeated here, because
# counting them would mean matching the bubble's pixel size and would pass silently if that
# size ever changed.
km_was=$(state_line keys-mode | sed -n 's/^ok keys-mode //p')
case "$km_was" in
    full|favorites) ok "keys-mode reported ($km_was)" ;;
    *) bad "keys-mode reported nothing sane (got '$km_was')" ;;
esac

state_line keys-mode --es arg favorites >/dev/null
if [ "$(state_line keys-mode | sed -n 's/^ok keys-mode //p')" = "favorites" ]; then
    ok "keys-mode favorites accepted"
else
    bad "keys-mode did not switch to favorites"
fi
# With no custom layout saved, favorites falls back to the built-in keyboard rather than
# showing an empty panel, so the summary says which layout is really on screen.
info "favorites resolves to: $(state_line keys-mode --es arg favorites | sed -n 's/^ok keys-mode favorites //p')"

if state_line keys-mode --es arg sideways | grep -q '^error'; then
    ok "keys-mode rejects anything but full|favorites"
else
    bad "keys-mode accepted an invalid mode"
fi

state_line keys-mode --es arg "$km_was" >/dev/null
if [ "$(state_line keys-mode | sed -n 's/^ok keys-mode //p')" = "$km_was" ]; then
    ok "keys-mode restored ($km_was)"
else
    bad "keys-mode not restored to $km_was"
fi

b_was=$(state_line bubbles | sed -n 's/^ok bubbles //p')
b_keys=$(printf '%s' "$b_was" | sed -n 's/.*keys=\(on\|off\).*/\1/p')
b_tasks=$(printf '%s' "$b_was" | sed -n 's/.*tasks=\(on\|off\).*/\1/p')
b_clip=$(printf '%s' "$b_was" | sed -n 's/.*clip=\(on\|off\).*/\1/p')
if [ -n "$b_keys" ] && [ -n "$b_tasks" ] && [ -n "$b_clip" ]; then
    ok "bubbles reported (keys=$b_keys tasks=$b_tasks clip=$b_clip)"
    state_line bubbles --es arg "tasks=off,keys=off,clip=off" >/dev/null
    if [ "$(state_line bubbles | sed -n 's/^ok bubbles //p')" = "keys=off tasks=off clip=off" ]; then
        ok "all three optional dots turned off"
    else
        bad "turning the optional dots off did not take"
    fi
    state_line bubbles --es arg "keys=$b_keys,tasks=$b_tasks,clip=$b_clip" >/dev/null
    if [ "$(state_line bubbles | sed -n 's/^ok bubbles //p')" = "keys=$b_keys tasks=$b_tasks clip=$b_clip" ]; then
        ok "dots restored (keys=$b_keys tasks=$b_tasks clip=$b_clip)"
    else
        bad "dots not restored to keys=$b_keys tasks=$b_tasks clip=$b_clip"
    fi
    if state_line bubbles --es arg keys=maybe | grep -q '^error'; then
        ok "bubbles rejects anything but on|off"
    else
        bad "bubbles accepted an invalid value"
    fi
else
    bad "bubbles did not report keys=/tasks= (got '$b_was')"
fi

if $with_display; then
    echo
    echo "== virtual display lifecycle (--with-display)"
    state_line destroy >/dev/null
    if [ "$(field "$(state_line status)" kind)" = "none" ]; then
        ok "destroy -> kind=none"
    else
        bad "destroy did not leave kind=none"
    fi
    state_line create --ez headless true >/dev/null
    for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
        [ "$(field "$(state_line status)" kind)" = "headless" ] && break
        sleep 0.3
    done
    if [ "$(field "$(state_line status)" kind)" = "headless" ]; then
        ok "create --headless -> kind=headless"
        did=$(field "$(state_line status)" id)
        state_line destroy >/dev/null
        if [ "$(field "$(state_line status)" kind)" = "none" ]; then
            ok "destroy released display $did"
        else
            bad "display $did did not go away"
        fi
    else
        bad "headless display never came up (polled 6s)"
    fi
fi

if $with_clip; then
    echo
    echo "== clipboard modal (--with-clip; clobbers the system clipboard)"
    # The two `clean` options are persisted, so this section pins them and puts them back,
    # like every other round-trip here.
    junk_was=$(field "$(state_line clip)" junk)
    join_was=$(field "$(state_line clip)" join)
    state_line clip --es arg junk --es spec on >/dev/null
    state_line clip --es arg join --es spec off >/dev/null
    # Start from a closed, auto-fitting modal. It keeps its textarea contents between opens
    # until the posted read lands, so an open panel would hand the poll below stale numbers.
    state_line clip --es arg hide >/dev/null
    state_line clip --es arg fit >/dev/null
    # A junk specimen with a real pane title bar, a zellij gutter and padded columns, so
    # the Cleaner has something to strip. printf supplies the ESC and the box bytes.
    junk=$(printf '$ adb logcat -s ZTrackpad\n\x1b[32m\xe2\x94\x8c\xe2\x94\x80 herdr \xe2\x94\x80 pop \xe2\x94\x80\xe2\x94\x80\xe2\x94\x90\x1b[0m\n\xe2\x96\xbe3\xe2\x97\x8f\xe2\x94\x82 Done \xe2\x80\x94 wrapped line\n 1\xe2\x97\x8b\xe2\x94\x82 (JSON config)\n 2\xe2\x97\x8b\xe2\x94\x82 total   0\n')
    set_out=$(adb shell am broadcast -n "$pkg/.VDisplayReceiver" -a "$pkg.VDISPLAY" \
        --es op clip --es arg set --es spec "$(sq "$junk")" 2>&1 \
        | sed -n 's/.*data="\(.*\)"[[:space:]]*$/\1/p' | head -1)
    if [ -n "$set_out" ]; then
        ok "clip set wrote a $(printf '%s' "$junk" | wc -c | tr -d ' ') char specimen"
    else
        bad "clip set did not answer"
    fi

    state_line clip --es arg show >/dev/null
    sleep 0.6            # the read is posted CLIP_SETTLE_MS after the window takes focus
    raw=""
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        raw=$(field "$(state_line clip)" chars)
        [ -n "$raw" ] && [ "$raw" != "0" ] && break
        sleep 0.3
    done
    if [ -n "$raw" ] && [ "$raw" != "0" ]; then
        ok "clip show read the clipboard ($raw chars - the focusable window works)"
    else
        bad "clip show read nothing (needs window focus; is the screen on and unlocked?)"
    fi

    state_line clip --es arg clean >/dev/null
    cleaned=$(field "$(state_line clip)" chars)
    if [ -n "$cleaned" ] && [ "$cleaned" -lt "$raw" ]; then
        ok "clip clean stripped the junk ($raw -> $cleaned chars)"
    else
        bad "clip clean did not shrink the text ($raw -> $cleaned)"
    fi

    # `reset` (the modal's own button) puts the clipboard's own text back into the textarea,
    # undoing a Clean or a hand edit. Nothing is written until `copy`, so the original is
    # always on the clipboard to re-read.
    state_line clip --es arg reset >/dev/null
    back=$(field "$(state_line clip)" chars)
    if [ "$back" = "$raw" ]; then
        ok "clip reset put the textarea back to the clipboard's text ($cleaned -> $back chars)"
    else
        bad "clip reset did not restore the text (wanted $raw, got $back)"
    fi
    state_line clip --es arg clean >/dev/null   # leave it cleaned for the copy below

    state_line clip --es arg copy >/dev/null
    if [ "$(field "$(state_line clip)" panel)" = "hidden" ]; then
        ok "clip copy closed the modal"
    else
        bad "clip copy left the modal open"
    fi

    state_line clip --es arg show >/dev/null
    sleep 0.6
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        again=$(field "$(state_line clip)" chars)
        [ "$again" = "$cleaned" ] && break
        sleep 0.3
    done
    if [ "$again" = "$cleaned" ]; then
        ok "re-reading the clipboard returns the copied text ($again chars)"
    else
        bad "the copy did not land on the clipboard (wanted $cleaned, read $again)"
    fi

    # The two checkboxes the modal's `clean` honours. "remove new lines" flattens the
    # text, so the line count is what proves it (the char count does not change: a
    # newline and the space that replaces it are both one char).
    before_lines=$(field "$(state_line clip)" lines)
    state_line clip --es arg junk --es spec off >/dev/null
    state_line clip --es arg join --es spec on >/dev/null
    if [ "$(field "$(state_line clip)" join)" = "on" ] && [ "$(field "$(state_line clip)" junk)" = "off" ]; then
        ok "clip junk/join round-trip (junk=off join=on)"
    else
        bad "clip junk/join did not take"
    fi
    state_line clip --es arg clean >/dev/null
    after_lines=$(field "$(state_line clip)" lines)
    if [ "$after_lines" = "1" ] && [ "$before_lines" != "1" ]; then
        ok "clean with 'remove new lines' flattens to one line ($before_lines -> $after_lines)"
    else
        bad "'remove new lines' did not flatten ($before_lines -> $after_lines lines)"
    fi
    state_line clip --es arg join --es spec off >/dev/null
    state_line clip --es arg junk --es spec on >/dev/null
    if state_line clip --es arg junk --es spec maybe | grep -q '^error'; then
        ok "clip junk rejects anything but on|off"
    else
        bad "clip junk accepted an invalid value"
    fi
    # put the owner's options back, whatever they were
    state_line clip --es arg junk --es spec "$junk_was" >/dev/null
    state_line clip --es arg join --es spec "$join_was" >/dev/null
    state_line clip --es arg hide >/dev/null

    # Auto-hide: the dot lives only inside its window, which a ping opens. The timer's effect
    # is checked through `hide-now` rather than by sleeping a minute out. The panel has to be
    # closed first - the hide deliberately refuses to run while the modal is open.
    ah_was=$(field "$(state_line clip)" autohide)
    hm_was=$(field "$(state_line clip)" hide)
    state_line clip --es arg auto-hide --es spec on >/dev/null
    state_line clip --es arg hide-after --es spec 1 >/dev/null
    state_line clip --es arg ping >/dev/null
    if [ "$(field "$(state_line clip)" autohide)" = "on" ] \
            && [ "$(field "$(state_line clip)" dot)" = "on" ]; then
        ok "clip auto-hide on + ping -> dot=on (hide=1m)"
    else
        bad "clip ping did not show the dot"
    fi
    state_line clip --es arg hide-now >/dev/null
    if [ "$(field "$(state_line clip)" dot)" = "off" ] \
            && [ "$(field "$(state_line clip)" ready)" = "off" ]; then
        ok "the hide the timer would do takes the dot away (dot=off ready=off)"
    else
        bad "hide-now left the dot up"
    fi
    state_line clip --es arg ping >/dev/null
    if [ "$(field "$(state_line clip)" dot)" = "on" ]; then
        ok "a second ping brings it back"
    else
        bad "ping did not restore the dot"
    fi
    if state_line clip --es arg hide-after --es spec 0 | grep -q '^error'; then
        ok "clip hide-after rejects 0 minutes"
    else
        bad "clip hide-after accepted 0"
    fi
    state_line clip --es arg hide-after --es spec "${hm_was%m}" >/dev/null
    state_line clip --es arg auto-hide --es spec "$ah_was" >/dev/null
fi

echo
if [ "$fail" = "0" ]; then
    printf 'smoke: %d passed\n' "$pass"
    exit 0
fi
printf 'smoke: %d passed, %d FAILED\n' "$pass" "$fail"
exit 1
