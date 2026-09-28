#!/usr/bin/env bash
# tests/smoke.sh - end-to-end checks for a running trackpad that need no fingers.
#
# Everything here talks to the app's own broadcast API and reads its one-line status
# reply, so there is no tapping, no screenshot and no eyes needed. This is the
# counterpart to the verification list in AGENTS.md: those rows need a human finger
# (two-finger gestures, how a thing *feels*), these do not.
#
#   tests/smoke.sh [--with-display] [--with-popup] [package-id]
#
#   --with-display   also create and destroy a headless virtual display. Off by
#                    default because it is the only check with a visible side effect
#                    on the device, even though it cleans up after itself.
#
#   --with-popup     also open, raise and close two pop-up windows. Off by default for
#                    the same reason, and a louder one: these are visible windows, not an
#                    invisible display. Cleans up with force-stop.
#
# The package id is discovered, never hardcoded: the argument wins, then $TRACKPAD_PKG,
# then the single installed package whose name contains "trackpad". That is deliberate -
# the private build and the public build have different package ids, so this file has to
# work unchanged in both trees. A hardcoded id would also trip the private-string guard in
# ~/.local/bin/ztrackpad-sync, which is the same reason.
set -uo pipefail

with_display=false
with_popup=false
pkg=""
for a in "$@"; do
    case "$a" in
        --with-display) with_display=true ;;
        --with-popup)   with_popup=true ;;
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
echo "== the documented implicit-broadcast trap"

implicit=$(adb shell am broadcast -a "$pkg.VDISPLAY" --es op status 2>&1)
if printf '%s' "$implicit" | grep -q 'data="'; then
    bad "an implicit broadcast reached the receiver - README/AGENTS say it must not"
    info "on API 26+ a manifest receiver is not an implicit-broadcast target; -n is required"
else
    ok "implicit broadcast did not reach it (the -n component is required)"
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
if [ -n "$b_keys" ] && [ -n "$b_tasks" ]; then
    ok "bubbles reported (keys=$b_keys tasks=$b_tasks)"
    state_line bubbles --es arg "tasks=off,keys=off" >/dev/null
    if [ "$(state_line bubbles | sed -n 's/^ok bubbles //p')" = "keys=off tasks=off" ]; then
        ok "both optional dots turned off"
    else
        bad "turning both dots off did not take"
    fi
    state_line bubbles --es arg "keys=$b_keys,tasks=$b_tasks" >/dev/null
    if [ "$(state_line bubbles | sed -n 's/^ok bubbles //p')" = "keys=$b_keys tasks=$b_tasks" ]; then
        ok "dots restored (keys=$b_keys tasks=$b_tasks)"
    else
        bad "dots not restored to keys=$b_keys tasks=$b_tasks"
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

echo
if [ "$fail" = "0" ]; then
    printf 'smoke: %d passed\n' "$pass"
    exit 0
fi
printf 'smoke: %d passed, %d FAILED\n' "$pass" "$fail"
exit 1
