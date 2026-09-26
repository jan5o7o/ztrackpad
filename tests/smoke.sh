#!/usr/bin/env bash
# tests/smoke.sh - end-to-end checks for a running trackpad that need no fingers.
#
# Everything here talks to the app's own broadcast API and reads its one-line status
# reply, so there is no tapping, no screenshot and no eyes needed. This is the
# counterpart to the verification list in AGENTS.md: those rows need a human finger
# (two-finger gestures, how a thing *feels*), these do not.
#
#   tests/smoke.sh [--with-display] [package-id]
#
#   --with-display   also create and destroy a headless virtual display. Off by
#                    default because it is the only check with a visible side effect
#                    on the device, even though it cleans up after itself.
#
# The package id is discovered, never hardcoded: the argument wins, then $TRACKPAD_PKG,
# then the single installed package whose name contains "trackpad". That is deliberate -
# the private build and the public build have different package ids, so this file has to
# work unchanged in both trees. A hardcoded id would also trip the private-string guard in
# ~/.local/bin/ztrackpad-sync, which is the same reason.
set -uo pipefail

with_display=false
pkg=""
for a in "$@"; do
    case "$a" in
        --with-display) with_display=true ;;
        -h|--help) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
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
