#!/usr/bin/env bash
# Hardware matrix for the companion: inject watch commands through the debug-only
# InjectCommandReceiver and assert on Ultrasonic's own state: its media session (dumpsys)
# for track/heart/play state, its player via our controller for repeat. Every state change
# is reverted before exit, and logcat is never cleared: the device is shared.
#
#   bash tools/device_matrix.sh <adb-serial> [--with-play]
#
# --with-play adds one audible PLAY_PAUSE -> 2 s -> PLAY_PAUSE. PLAY_ITEM is never run:
# it replaces Ultrasonic's queue, which a controller cannot restore.
# Exit: 0 all pass | 1 a check failed | 2 environment.
# P10 RELAXATIONS: rule 10 — no `set -e`: a failing probe must never skip restore(),
# which puts Ultrasonic's state back. Every wait is a bounded poll.

set -uo pipefail

readonly SERIAL="${1:?usage: device_matrix.sh <adb-serial> [--with-play]}"
readonly WITH_PLAY="${2:-}"
readonly PKG=io.github.p1r4n351.ultrasonic.pebble
readonly TAG=UltrasonicPebble
readonly POLL_MAX=40          # x 0.25 s = 10 s per wait
SINCE=""                      # logcat -T marker; logcat is never cleared (shared device)
PASS=0
FAIL=0

adbs() { adb -s "${SERIAL}" "$@"; }

check() {
    local name="$1" ok="$2" detail="${3:-}"
    if [[ "${ok}" == "1" ]]; then
        PASS=$((PASS + 1)); printf 'PASS %s\n' "${name}"
    else
        FAIL=$((FAIL + 1)); printf 'FAIL %s -- %s\n' "${name}" "${detail}"
    fi
}

inject() {
    adbs shell am broadcast -n "${PKG}/.InjectCommandReceiver" \
        --ei cmd "$1" --ei arg "${2:-0}" --ei list "${3:-0}" --ei req "${4:-0}" >/dev/null
}

session() {
    adbs shell dumpsys media_session | sed -n '/package=org.moire.ultrasonic/,/queueTitle/p'
}

# Poll the session until it contains $1 (fixed string). Echo 1 on success, 0 on timeout.
await_session() {
    local i
    for ((i = 0; i < POLL_MAX; i++)); do
        if session | grep -qF -- "$1"; then echo 1; return; fi
        sleep 0.25
    done
    echo 0
}

# Poll our log for a line matching regex $1; print the last match (empty on timeout).
await_log() {
    local i line
    for ((i = 0; i < POLL_MAX; i++)); do
        line="$(adbs logcat -d -T "${SINCE}" -s "${TAG}:I" | grep -E -- "$1" | tail -1)"
        if [[ -n "${line}" ]]; then printf '%s' "${line}"; return; fi
        sleep 0.25
    done
}

# Start-of-step marker for logcat -T. adb joins argv into ONE remote shell string, so the
# format travels as a single quoted word. A malformed marker makes every log check vacuous,
# so it aborts the run as a SETUP-ERROR instead of surfacing as findings.
mark() {
    SINCE="$(adbs shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')"
    if [[ ! "${SINCE}" =~ ^[0-9]{2}-[0-9]{2}\ [0-9]{2}:[0-9]{2}:[0-9]{2}\.000$ ]]; then
        printf 'SETUP-ERROR: logcat marker malformed: %q\n' "${SINCE}" >&2
        exit 2
    fi
}

list_id() { sed -nE "s/.*list ([0-9]+) req=.*/\1/p" <<<"$1"; }

# Index of the first logged item whose label contains $2, in log line $1 (-1 if absent).
item_index() {
    python3 - "$1" "$2" <<'PY'
import sys
line, want = sys.argv[1], sys.argv[2]
items = line.split("-> ", 1)[1].split(", ") if "-> " in line else []
print(next((i for i, t in enumerate(items) if want in t), -1))
PY
}

music_volume() {
    adbs shell dumpsys audio | grep -A6 -E '^- STREAM_MUSIC:' | sed -nE 's/^ +streamVolume:([0-9]+).*/\1/p'
}

browse() {
    local line id idx
    mark
    inject 10 0 0 21
    line="$(await_log "list [0-9]+ req=21 'Ultrasonic'")"
    check "browse root returns Ultrasonic's root" "$([[ -n "${line}" ]] && echo 1 || echo 0)" "no list logged"
    [[ -n "${line}" ]] || return
    printf '     %s\n' "${line#*: }"
    id="$(list_id "${line}")"
    idx="$(item_index "${line}" "Albums")"
    check "root lists Albums" "$([[ "${idx}" -ge 0 ]] && echo 1 || echo 0)" "${line}"
    [[ "${idx}" -ge 0 ]] || return
    mark
    inject 11 "${idx}" "${id}" 22
    line="$(await_log "list [0-9]+ req=22 'Albums")"
    check "open Albums returns albums from Navidrome" "$([[ -n "${line}" ]] && echo 1 || echo 0)" "no list"
    [[ -n "${line}" ]] || return
    printf '     %s\n' "${line#*: }"
    # Regression (found on hardware 2026-09-18): Ultrasonic sends albums with no
    # browsable/playable metadata; the watch ignores flagless rows, so albums were inert.
    check "albums arrive browsable and playable" "$([[ "${line}" == *"[bp]{MEDIA_ALBUM_ITEM}"* && "${line}" != *"[]{"* ]] && echo 1 || echo 0)" "${line}"
    id="$(list_id "${line}")"
    mark
    inject 11 0 "${id}" 23
    line="$(await_log "list [0-9]+ req=23 ")"
    check "open first album returns its tracks" "$([[ "${line}" == *"[p]{MEDIA_ALBUM_SONG_ITEM}"* ]] && echo 1 || echo 0)" "${line}"
    printf '     %s\n' "${line#*: }"
    # "Play All" shares the album's kind; it must be playable-only so SELECT plays it.
    # Ultrasonic always prepends it to an album's children, so absence is a failure too.
    check "Play All row plays on select (not re-opens the album)" \
        "$([[ "${line}" == *"Play All[p]{MEDIA_ALBUM_ITEM}"* ]] && echo 1 || echo 0)" "${line}"
    mark
    inject 11 0 999 24
    line="$(await_log "cmd=11 req=24 result=")"
    check "stale list id is refused, not guessed" "$([[ "${line}" == *"result=false"* ]] && echo 1 || echo 0)" "${line}"
}

# Ultrasonic's repeatMode as the companion's controller reports it (0 none, 1 one, 2 all).
# dumpsys is NOT used for repeat: its legacy custom-action names did not refresh on
# REPEAT_MODE in a 2026-09-18 run while the controller-side value did.
pushed_repeat() {
    mark; inject 1
    await_log "push np .*repeat=" | sed -nE 's/.*repeat=([0-9]).*/\1/p'
}
await_pushed_repeat_not() {
    local i v
    for ((i = 0; i < POLL_MAX; i++)); do
        v="$(adbs logcat -d -T "${SINCE}" -s "${TAG}:I" | sed -nE 's/.*push np .*repeat=([0-9]).*/\1/p' | tail -1)"
        if [[ -n "${v}" && "${v}" != "$1" ]]; then echo "${v}"; return; fi
        sleep 0.25
    done
}

# Ultrasonic 4.8.0 names its heart button "Love" when the track is unstarred (the button
# stars it) and "Dislike" when starred.
heart_button() { session | grep -oE "mName='(Love|Dislike)" | head -1 | sed "s/mName='//"; }

toggles() {
    local h0 flipped
    h0="$(heart_button)"
    flipped=$([[ "${h0}" == "Love" ]] && echo Dislike || echo Love)
    inject 5
    check "love toggles Ultrasonic's heart (${h0} -> ${flipped})" "$(await_session "mName='${flipped}")" "$(heart_button)"
    inject 5
    check "love again restores it (-> ${h0})" "$(await_session "mName='${h0}")" "$(heart_button)"
    local r0 r1 r2 r3
    r0="$(pushed_repeat)"
    mark; inject 7; r1="$(await_pushed_repeat_not "${r0}")"
    mark; inject 7; r2="$(await_pushed_repeat_not "${r1}")"
    mark; inject 7; r3="$(await_pushed_repeat_not "${r2}")"
    check "repeat cycles through all three modes (${r0} -> ${r1} -> ${r2} -> ${r3})" \
        "$([[ -n "${r1}" && -n "${r2}" && "${r3}" == "${r0}" && "${r1}" != "${r2}" ]] && echo 1 || echo 0)" ""
}

transport() {
    local before after v0 v1 v2
    before="$(session | sed -nE 's/.*description=([^,]+),.*/\1/p')"
    inject 3
    after="$(for ((i = 0; i < POLL_MAX; i++)); do d="$(session | sed -nE 's/.*description=([^,]+),.*/\1/p')"; [[ "${d}" != "${before}" ]] && { echo "${d}"; break; }; sleep 0.25; done)"
    check "next changes track (${before} -> ${after:-unchanged})" "$([[ -n "${after}" ]] && echo 1 || echo 0)" ""
    inject 4
    check "previous returns to ${before}" "$(await_session "description=${before},")" "$(session | grep -o 'description=[^,]*')"
    v0="$(music_volume)"
    inject 8; sleep 1; v1="$(music_volume)"
    inject 9; sleep 1; v2="$(music_volume)"
    check "volume up then down (${v0} -> ${v1} -> ${v2})" "$([[ "${v1}" -gt "${v0}" && "${v2}" == "${v0}" ]] && echo 1 || echo 0)" ""
}

play_once() {
    inject 2
    check "play/pause -> PLAYING" "$(await_session "state=PLAYING")" "$(session | grep -o 'state=[A-Z]*([0-9])')"
    sleep 2
    inject 2
    check "play/pause -> PAUSED" "$(await_session "state=PAUSED")" "$(session | grep -o 'state=[A-Z]*([0-9])')"
}

# Put Ultrasonic back where the run found it, whatever failed in between. Bounded.
restore() {
    local want_heart="$1" want_repeat="$2" i
    for ((i = 0; i < 2; i++)); do
        [[ "$(heart_button)" == "${want_heart}" ]] && break
        inject 5; sleep 1.5
    done
    local now
    now="$(pushed_repeat)"
    for ((i = 0; i < 3; i++)); do
        [[ "${now}" == "${want_repeat}" ]] && break
        mark; inject 7; now="$(await_pushed_repeat_not "${now}")"
    done
    # Empty == empty would pass vacuously; an unknown baseline is a failure to verify.
    check "restored heart=${want_heart} repeat=${want_repeat}" \
        "$([[ -n "${want_heart}" && -n "${want_repeat}" && "$(heart_button)" == "${want_heart}" \
              && "${now}" == "${want_repeat}" ]] && echo 1 || echo 0)" \
        "heart=$(heart_button) repeat=${now:-unknown}"
}

main() {
    local heart0 repeat0
    adbs get-state >/dev/null 2>&1 || { echo "SETUP-ERROR: ${SERIAL} not reachable"; return 2; }
    adbs shell pm path "${PKG}" >/dev/null 2>&1 || { echo "SETUP-ERROR: ${PKG} not installed"; return 2; }
    # A backgrounded app with no bound client is frozen by Samsung's Freecess between
    # broadcasts, which stalls commands mid-flight. In real use the Pebble app keeps the
    # service bound; foregrounding the status screen gives the same unfrozen process.
    adbs shell input keyevent KEYCODE_WAKEUP
    adbs shell am start -n "${PKG}/.StatusActivity" >/dev/null
    sleep 2
    mark
    inject 1
    local hello
    hello="$(await_log "push np ")"
    check "hello pushes now-playing from Ultrasonic" "$([[ -n "${hello}" ]] && echo 1 || echo 0)" "no push logged"
    printf '     %s\n' "${hello#*: }"
    heart0="$(heart_button)"
    repeat0="$(sed -nE 's/.*repeat=([0-9]).*/\1/p' <<<"${hello}")"
    printf 'baseline: heart=%s repeat=%s track=%s\n' "${heart0}" "${repeat0}" \
        "$(session | sed -nE 's/.*description=([^,]+),.*/\1/p')"
    browse
    toggles
    transport
    if [[ "${WITH_PLAY}" == "--with-play" ]]; then play_once; fi
    restore "${heart0}" "${repeat0}"
    adbs shell input keyevent KEYCODE_BACK
    local fails
    fails="$(adbs logcat -d -s WatchLink:W | grep -c 'send to' || true)"
    printf 'watch sends not Success during run: %s (FailedDifferentAppOpen when the watchapp is closed)\n' "${fails}"
    printf 'SUMMARY %d/%d passed\n' "${PASS}" "$((PASS + FAIL))"
    [[ "${FAIL}" -eq 0 ]]
}

main
