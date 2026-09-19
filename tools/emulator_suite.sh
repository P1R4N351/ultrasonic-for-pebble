#!/usr/bin/env bash
# Build the watchapp and run the fake-companion scenario on each target emulator.
# Expects pebble-tool 5.x with SDK 4.33.1 on PATH (`pebble sdk install latest`),
# plus its bundled qemu-pebble + pypkjs. SDL_VIDEODRIVER=dummy lets qemu-pebble
# render without a display (needed on a headless build box).
#
#   bash tools/emulator_suite.sh [platform ...]     # default: flint emery
#
# Exit: 0 all green | 1 build/warnings/scenario failure | 2 environment.
# P10 RELAXATIONS: none — loops iterate a fixed platform list.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ROOT
readonly APP="${ROOT}/watchapp"
# Screenshots are regenerable run output and live under /tmp: clearing them is
# then a plain temp-dir wipe, not a project-tree write.
readonly SHOTS=/tmp/ultrasonic-pebble-shots
export SDL_VIDEODRIVER=dummy PYTHONWARNINGS=ignore

log() { printf '%s %s\n' "$(date -u +%H:%M:%SZ)" "$*"; }

build() {
    local blog=/tmp/usp-suite-build.log warnings
    (cd "${APP}" && pebble clean >/dev/null 2>&1 || true)
    if ! (cd "${APP}" && pebble build >"${blog}" 2>&1); then
        log "BUILD FAILED"; tail -30 "${blog}"; return 1
    fi
    # The SDK demotes unused-variable/format-truncation to warnings and still
    # exits 0, so the exit code alone cannot fail on them. Count them here.
    warnings="$(grep -cE '\.c:[0-9]+:[0-9]+: (warning|error)' "${blog}" || true)"
    if [[ "${warnings}" != "0" ]]; then
        log "BUILD has ${warnings} C warning(s):"
        grep -E '\.c:[0-9]+:[0-9]+: (warning|error)' "${blog}"
        return 1
    fi
    grep -A3 'APP MEMORY USAGE' "${blog}" | grep -E 'MEMORY|RAM|heap' || true
    log "build ok, 0 warnings"
}

run_platform() {
    local platform="$1"
    pebble kill >/dev/null 2>&1 || true
    if ! timeout 240 pebble install --emulator "${platform}" "${APP}/build/watchapp.pbw" \
        >/tmp/usp-install.log 2>&1; then
        log "SETUP-ERROR: install on ${platform} failed"; tail -5 /tmp/usp-install.log; return 2
    fi
    timeout 900 python3 "${ROOT}/tools/fake_companion.py" "${platform}" \
        "${APP}/package.json" "${SHOTS}" 2>&1 | grep -v SyntaxWarning
    return "${PIPESTATUS[0]}"
}

main() {
    local platforms=("$@") rc=0 p status
    if [[ ${#platforms[@]} -eq 0 ]]; then
        platforms=(flint emery)
    fi
    command -v pebble >/dev/null || { log "SETUP-ERROR: pebble not on PATH"; return 2; }
    command -v python3 >/dev/null || { log "SETUP-ERROR: python3 not on PATH"; return 2; }
    build || return 1
    mkdir -p "${SHOTS}"
    find "${SHOTS}" -mindepth 1 -delete
    for p in "${platforms[@]}"; do
        log "=== ${p}"
        status=0
        run_platform "${p}" || status=$?
        if [[ ${status} -eq 2 ]]; then
            rc=2
        elif [[ ${status} -ne 0 && ${rc} -eq 0 ]]; then
            rc=1
        fi
    done
    pebble kill >/dev/null 2>&1 || true
    log "suite exit ${rc}"
    return "${rc}"
}

main "$@"
