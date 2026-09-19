#!/usr/bin/env python3
"""Mutation check for the companion's unit tests: each defect must fail the NAMED test.

Needs JDK 17, a Gradle toolchain JDK 21 and an Android SDK. Same guards as
mutate_watch.py: needle exactly once, old != new, file byte-changed, restore hash-verified.
A mutant caught by some other test is WRONG-REASON, not CAUGHT.

Exit: 0 all caught at the named test | 1 survivor or wrong reason | 2 SETUP-ERROR.
"""

import glob
import hashlib
import os
import pathlib
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
APP = ROOT / "companion"
SRC = APP / "app/src/main/java/io/github/p1r4n351/ultrasonic/pebble"
RESULTS = APP / "app/build/test-results/testDebugUnitTest"
ENV = dict(os.environ, ANDROID_HOME="/opt/android-sdk",
           JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64")

MUTANTS = [
    ("key-drift", "Protocol.kt", "  const val NP_POS = 14\n", "  const val NP_POS = 15\n",
     "ContractMatchesWatchappTest.messageKeysMatchPackageJson"),
    ("cmd-drift", "Protocol.kt", "  const val PREV = 4\n", "  const val PREV = 13\n",
     "ContractMatchesWatchappTest.commandsMatchProtocolH"),
    ("budget-ignores-nul", "Protocol.kt", "  const val ITEM_LABEL = 39\n",
     "  const val ITEM_LABEL = 40\n",
     "ContractMatchesWatchappTest.byteBudgetsLeaveRoomForTheWatchNul"),
    ("utf8-2byte-miscounted", "Protocol.kt", "    cp < 0x800 -> 2\n", "    cp < 0x800 -> 1\n",
     "ProtocolTest.multibyteIsNeverSplit"),
    ("list-id-wraps-to-zero", "ListRegistry.kt",
     "    nextId = if (nextId >= MAX_ID) 1 else nextId + 1\n",
     "    nextId = if (nextId >= MAX_ID) 0 else nextId + 1\n",
     "ListRegistryTest.idsWrapWithoutEverIssuingZero"),
    ("heart-metadata-outranks-button", "NowPlayingMapper.kt",
     "    s.heartOffOffered -> true\n", "    s.metadataHeart != null -> s.metadataHeart\n",
     "NowPlayingMapperTest.ultrasonicsHeartButtonOutranksMetadata"),
    ("kind-overrides-metadata", "UltrasonicIds.kt",
     "    if (mdBrowsable == true || mdPlayable == true) {\n", "    if (false) {\n",
     "UltrasonicIdsTest.playAllRowPlaysOnSelectDespiteSharingTheAlbumKind"),
]


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def run_tests():
    """Return (gradle rc, set of failed 'Class.method', total test count)."""
    for f in glob.glob(str(RESULTS / "*.xml")):
        os.remove(f)
    done = subprocess.run(["./gradlew", "--no-daemon", "--console=plain", "testDebugUnitTest"],
                          cwd=APP, env=ENV, capture_output=True, text=True, timeout=900)
    failed, total = set(), 0
    for f in glob.glob(str(RESULTS / "*.xml")):
        root = ET.parse(f).getroot()
        total += int(root.get("tests", 0))
        for tc in root.iter("testcase"):
            if tc.find("failure") is not None or tc.find("error") is not None:
                failed.add("{}.{}".format(tc.get("classname").split(".")[-1], tc.get("name")))
    return done.returncode, failed, total


def one(name, fname, old, new, expect):
    path = SRC / fname
    original, before = path.read_bytes(), sha(path)
    text = original.decode()
    if old == new or text.count(old) != 1:
        print("SETUP-ERROR {}: needle count {} (old==new: {})".format(name, text.count(old), old == new))
        return 2
    path.write_text(text.replace(old, new, 1))
    if sha(path) == before:
        print("SETUP-ERROR {}: file unchanged after swap".format(name))
        path.write_bytes(original)
        return 2
    try:
        rc, failed, total = run_tests()
    finally:
        path.write_bytes(original)
    if sha(path) != before:
        print("SETUP-ERROR {}: restore mismatch".format(name))
        return 2
    if total == 0:
        print("SETUP-ERROR {}: no test results (compile error or harness fault), rc={}".format(name, rc))
        return 2
    if rc == 0 or not failed:
        print("SURVIVED {}: {} tests green -- the named check is BLIND".format(name, total))
        return 1
    if expect not in failed:
        print("WRONG-REASON {}: failed {} but not {}".format(name, sorted(failed), expect))
        return 1
    print("CAUGHT {} at {} ({} failing of {})".format(name, expect, len(failed), total), flush=True)
    return 0


def main():
    worst = 0
    for m in MUTANTS:
        worst = max(worst, one(*m))
    rc, failed, total = run_tests()
    print("clean after restore: rc={} failed={} total={}".format(rc, sorted(failed), total))
    if rc != 0 or failed or total == 0:
        worst = max(worst, 2)
    print("MUTATION RESULT exit {}".format(worst))
    return worst


if __name__ == "__main__":
    sys.exit(main())
