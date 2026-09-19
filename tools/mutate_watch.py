#!/usr/bin/env python3
"""Mutation check for the emulator suite: each injected defect must turn the suite red.

Runs where the Pebble SDK is installed. For each mutant: verify the needle occurs EXACTLY ONCE, old != new,
and the file is byte-changed after the swap (a no-op mutant is a SETUP-ERROR, never a
"survivor"); run the suite on one platform; require a failure naming the expected step;
restore the file byte-for-byte and verify the hash.

Exit: 0 every mutant caught for the right reason | 1 a mutant survived or was caught
for the wrong reason | 2 SETUP-ERROR (the instrument itself is broken).
"""

import hashlib
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "watchapp" / "src" / "c"
PLATFORM = "emery"

MUTANTS = [
    ("up-down-swapped", "now_playing.c",
     "static void up_click(ClickRecognizerRef r, void *ctx) { send_or_complain(CMD_PREV); }",
     "static void up_click(ClickRecognizerRef r, void *ctx) { send_or_complain(CMD_NEXT); }",
     "FAIL up -> prev"),
    ("stale-guard-removed", "browse.c",
     "  if (s_pending_depth < 0 || req != s_pending_req) {",
     "  if (s_pending_depth < 0) {",
     "FAIL stale header ignored"),
    ("no-hello", "main.c",
     "  if (!comm_send(CMD_HELLO, 0, 0, 0)) {",
     "  if (false) {",
     "FAIL hello on launch"),
    ("long-select-opens", "browse.c",
     "  if (it != NULL && (it->flags & ITEM_FLAG_PLAYABLE)) {\n    play(v, idx->row);",
     "  if (it != NULL && (it->flags & ITEM_FLAG_PLAYABLE)) {\n"
     "    request((uint8_t)(v->depth + 1), CMD_OPEN, idx->row, v->list_id);",
     "FAIL long select album -> play item"),
    ("watchdog-disarmed", "main.c",
     "  now_playing_arm_watchdog();",
     "  (void)0;",
     "FAIL watchdog banner appears"),
    ("req-not-written", "comm.c",
     "  return dict_write_uint8(iter, MESSAGE_KEY_REQ, c->req) == DICT_OK;",
     "  return true;",
     "FAIL browse root carries a request id"),
]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run_suite():
    done = subprocess.run(["bash", str(ROOT / "tools" / "emulator_suite.sh"), PLATFORM],
                          capture_output=True, text=True, timeout=1500)
    return done.returncode, done.stdout + done.stderr


def apply(name, path, old, new):
    text = path.read_text()
    if old == new:
        return "SETUP-ERROR {}: old == new, mutant is a no-op".format(name)
    count = text.count(old)
    if count != 1:
        return "SETUP-ERROR {}: needle occurs {} times in {}".format(name, count, path.name)
    before = sha(path)
    path.write_text(text.replace(old, new, 1))
    if sha(path) == before:
        return "SETUP-ERROR {}: file unchanged after swap".format(name)
    return None


def one(name, fname, old, new, expect):
    path = SRC / fname
    original = path.read_bytes()
    original_sha = sha(path)
    err = apply(name, path, old, new)
    if err is not None:
        path.write_bytes(original)
        print(err, flush=True)
        return 2
    try:
        rc, out = run_suite()
    finally:
        path.write_bytes(original)
    if sha(path) != original_sha:
        print("SETUP-ERROR {}: restore did not reproduce the original bytes".format(name))
        return 2
    if rc == 2:
        print("SETUP-ERROR {}: suite reported an environment fault\n{}".format(name, out[-600:]))
        return 2
    if rc == 0:
        print("SURVIVED {}: suite stayed green -- this check is BLIND".format(name), flush=True)
        return 1
    if expect not in out:
        failures = [l for l in out.splitlines() if l.startswith("FAIL") or "BUILD" in l]
        print("WRONG-REASON {}: red, but not at '{}': {}".format(name, expect, failures[:4]))
        return 1
    print("CAUGHT {} at '{}'".format(name, expect), flush=True)
    return 0


def main():
    worst = 0
    for m in MUTANTS:
        worst = max(worst, one(*m))
    rc, out = run_suite()
    print("clean suite after restore: exit {}".format(rc))
    if rc != 0:
        worst = max(worst, 2)
    print("MUTATION RESULT exit {}".format(worst))
    return worst


if __name__ == "__main__":
    sys.exit(main())
