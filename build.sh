#!/usr/bin/env bash
# Build both halves and write the artifacts to dist/.
#
#   bash build.sh [--suite]     # --suite also runs the flint + emery emulator suite
#
# Needs: pebble-tool (5.x) with SDK 4.33.1 (`pebble sdk install latest`) on PATH,
# and an Android SDK with build-tools 36.0.0 + platforms;android-36. JDK 17 is
# used for the Gradle build; the unit tests need a JDK 21 (Gradle provisions one
# via the toolchain declaration in companion/app/build.gradle.kts).
#
# Exit: 0 ok | 1 build/test failure | 2 environment.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly ROOT
readonly DIST="${ROOT}/dist"
readonly VERSION="0.1.1"

step() { printf '%s %s\n' "$(date -u +%H:%M:%SZ)" "$*"; }

require() {
    command -v "$1" >/dev/null 2>&1 \
        || { echo "SETUP-ERROR: $1 not on PATH ($2)" >&2; return 2; }
}

build_watchapp() {
    local blog=/tmp/usp-watchapp-build.log warnings
    (cd "${ROOT}/watchapp" && pebble clean >/dev/null 2>&1 || true)
    if ! (cd "${ROOT}/watchapp" && pebble build >"${blog}" 2>&1); then
        tail -30 "${blog}"; return 1
    fi
    # SDK demotes unused-variable/format-truncation to warnings and still exits 0.
    warnings="$(grep -cE '\.c:[0-9]+:[0-9]+: (warning|error)' "${blog}" || true)"
    if [[ "${warnings}" != "0" ]]; then
        echo "BUILD has ${warnings} C warning(s):"
        grep -E '\.c:[0-9]+:[0-9]+: (warning|error)' "${blog}"
        return 1
    fi
    grep -A3 'APP MEMORY USAGE' "${blog}" | grep -E 'MEMORY|RAM|heap' || true
}

build_companion() {
    (cd "${ROOT}/companion" && chmod +x gradlew && \
        ./gradlew --no-daemon --console=plain testDebugUnitTest assembleDebug \
        >/tmp/usp-companion-build.log 2>&1) \
        || { tail -40 /tmp/usp-companion-build.log; return 1; }
}

publish() {
    mkdir -p "${DIST}"
    cp "${ROOT}/watchapp/build/watchapp.pbw" \
        "${DIST}/ultrasonic-pebble-${VERSION}.pbw"
    cp "${ROOT}/companion/app/build/outputs/apk/debug/app-debug.apk" \
        "${DIST}/ultrasonic-for-pebble-${VERSION}-debug.apk"
    (cd "${DIST}" && shasum -a 256 "ultrasonic-pebble-${VERSION}.pbw" \
        "ultrasonic-for-pebble-${VERSION}-debug.apk")
}

main() {
    require pebble "pebble sdk install latest"
    require javac "install JDK 17 (Temurin, Zulu, or OpenJDK)"
    require adb "install Android platform-tools (only needed to sideload)" || true
    if [[ "${1:-}" == "--suite" ]]; then
        step "emulator suite"
        bash "${ROOT}/tools/emulator_suite.sh"
    else
        step "watchapp"
        build_watchapp
    fi
    step "companion"
    build_companion
    step "publish"
    publish
}

main "$@"
