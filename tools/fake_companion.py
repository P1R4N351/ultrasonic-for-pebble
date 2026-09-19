#!/usr/bin/env python3
"""Drive the ultrasonic-pebble watchapp in the SDK emulator as if this were the companion.

Runs inside the Pebble SDK venv (libpebble2 + pebble-tool). Speaks AppMessage
to the watch over the emulator's pypkjs websocket, presses buttons with `pebble emu-button`,
and saves screenshots with `pebble screenshot`. Every watch->phone command the scenario
provokes is asserted by value; screenshots are for eyes.

Exit: 0 all steps passed | 1 a step failed | 2 usage/environment.
"""

import argparse
import json
import pathlib
import subprocess
import sys
import threading
import time
import uuid

import png
from libpebble2.communication import PebbleConnection
from libpebble2.communication.transports.websocket import WebsocketTransport
from libpebble2.protocol.apps import AppRunState, AppRunStateStart, AppRunStateStop
from libpebble2.services.appmessage import AppMessageService, CString, Uint8, Uint16, Uint32

EMULATOR_STATE = pathlib.Path("/tmp/pb-emulator.json")
SDK_VERSION = "4.33.1"
WAIT_S = 6.0
ACK_WAIT_S = 5.0
CLI_TIMEOUT_S = 60

CMD = {"HELLO": 1, "PLAY_PAUSE": 2, "NEXT": 3, "PREV": 4, "LOVE": 5, "SHUFFLE": 6,
       "REPEAT": 7, "VOL_UP": 8, "VOL_DOWN": 9, "BROWSE_ROOT": 10, "OPEN": 11, "PLAY_ITEM": 12}


class Link:
    """One AppMessage conversation with the watchapp; inbound commands are queued."""

    def __init__(self, platform, keys, app_uuid):
        state = json.loads(EMULATOR_STATE.read_text())
        port = state[platform][SDK_VERSION]["pypkjs"]["port"]
        self.keys = keys
        self.names = {v: k for k, v in keys.items()}
        self.app_uuid = app_uuid
        self.cond = threading.Condition()
        self.inbox = []
        self.acks = {}
        self.pebble = PebbleConnection(WebsocketTransport("ws://localhost:{}/".format(port)))
        self.pebble.connect()
        self.pebble.run_async()
        self.svc = AppMessageService(self.pebble)
        self.svc.register_handler("appmessage", self._on_message)
        self.svc.register_handler("ack", lambda tid, u: self._on_ack(tid, True))
        self.svc.register_handler("nack", lambda tid, u: self._on_ack(tid, False))
        self.runstate = []
        self.pebble.register_endpoint(AppRunState, self._on_runstate)

    def _on_runstate(self, packet):
        with self.cond:
            self.runstate.append(packet)
            self.cond.notify_all()

    def _wait_stopped(self, timeout):
        deadline = time.monotonic() + timeout
        with self.cond:
            while True:
                for p in self.runstate:
                    if isinstance(p.data, AppRunStateStop) and p.data.uuid == self.app_uuid:
                        return True
                left = deadline - time.monotonic()
                if left <= 0:
                    return False
                self.cond.wait(left)

    def _on_message(self, tid, app_uuid, data):
        if app_uuid != self.app_uuid:
            return
        named = {self.names.get(k, k): v for k, v in data.items()}
        with self.cond:
            self.inbox.append(named)
            self.cond.notify_all()

    def _on_ack(self, tid, ok):
        with self.cond:
            self.acks[tid] = ok
            self.cond.notify_all()

    def relaunch(self, attempts=2):
        """Stop, wait for the firmware's stop echo, then start. Returns True once stopped."""
        for _ in range(attempts):
            with self.cond:
                self.runstate.clear()
            self.pebble.send_packet(AppRunState(data=AppRunStateStop(uuid=self.app_uuid)))
            if self._wait_stopped(3.0):
                time.sleep(0.5)
                with self.cond:
                    self.inbox.clear()
                self.pebble.send_packet(AppRunState(data=AppRunStateStart(uuid=self.app_uuid)))
                return True
            time.sleep(1.0)
        return False

    def expect(self, cmd_name, timeout=WAIT_S):
        """Return the first queued message whose CMD is cmd_name, or None on timeout."""
        deadline = time.monotonic() + timeout
        with self.cond:
            while True:
                for i, msg in enumerate(self.inbox):
                    if msg.get("CMD") == CMD[cmd_name]:
                        return self.inbox.pop(i)
                left = deadline - time.monotonic()
                if left <= 0:
                    return None
                self.cond.wait(left)

    def send(self, fields):
        """Send one dictionary and wait for the watch's ACK. Returns True on ACK."""
        payload = {self.keys[name]: value for name, value in fields.items()}
        tid = self.svc.send_message(self.app_uuid, payload)
        deadline = time.monotonic() + ACK_WAIT_S
        with self.cond:
            while tid not in self.acks:
                left = deadline - time.monotonic()
                if left <= 0:
                    return False
                self.cond.wait(left)
            return self.acks.pop(tid)


class Scenario:
    def __init__(self, link, platform, outdir):
        self.link = link
        self.platform = platform
        self.outdir = outdir
        self.results = []

    def check(self, name, ok, detail=""):
        self.results.append((name, bool(ok), detail))
        shown = " -- " + detail if (detail and not ok) else ""
        print("{} {}{}".format("PASS" if ok else "FAIL", name, shown), flush=True)
        return ok

    def cli(self, args):
        cmd = ["pebble"] + args + ["--emulator", self.platform]
        done = subprocess.run(cmd, capture_output=True, text=True, timeout=CLI_TIMEOUT_S)
        if done.returncode != 0:
            self.check("cli " + " ".join(args), False, done.stderr.strip()[-200:])
        return done.returncode == 0

    def button(self, name, hold_ms=0):
        if hold_ms > 0:
            ok = self.cli(["emu-button", "push", name, "--duration", str(hold_ms)])
        else:
            ok = self.cli(["emu-button", "click", name])
        time.sleep(0.6)
        return ok

    def shot(self, label):
        time.sleep(0.8)
        path = self.outdir / "{}-{}.png".format(self.platform, label)
        self.cli(["screenshot", str(path), "--no-open"])
        return path

    def pixels(self, label):
        """Screenshot and return the decoded pixel rows, or None if capture failed."""
        path = self.shot(label)
        if not path.exists():
            return None
        width, _, rows, info = png.Reader(filename=str(path)).read_flat()
        return width * info["planes"], bytes(rows)

    def expect_cmd(self, step, cmd_name, **want):
        msg = self.link.expect(cmd_name)
        if msg is None:
            return self.check(step, False, "no {} within {}s".format(cmd_name, WAIT_S)), None
        bad = {k: (msg.get(k), v) for k, v in want.items() if msg.get(k) != v}
        return self.check(step, not bad, "got {}".format(msg) if bad else ""), msg

    def send_ok(self, step, fields):
        return self.check(step, self.link.send(fields), "watch did not ACK")


def now_playing(title, artist, album, state, pos, dur, flags, status=""):
    return {"NP_TITLE": CString(title), "NP_ARTIST": CString(artist), "NP_ALBUM": CString(album),
            "NP_STATE": Uint8(state), "NP_POS": Uint32(pos), "NP_DUR": Uint32(dur),
            "NP_FLAGS": Uint8(flags), "STATUS": CString(status)}


def send_list(sc, step, list_id, req, title, items):
    ok = sc.send_ok(step + " header", {"LIST_ID": Uint16(list_id), "REQ": Uint8(req),
                                        "LIST_TITLE": CString(title),
                                        "LIST_COUNT": Uint16(len(items))})
    for i, (label, sub, flags) in enumerate(items):
        ok = sc.send_ok("{} item {}".format(step, i),
                        {"LIST_ID": Uint16(list_id), "ITEM_INDEX": Uint16(i),
                         "ITEM_LABEL": CString(label), "ITEM_SUB": CString(sub),
                         "ITEM_FLAGS": Uint8(flags)}) and ok
    return ok


def run_now_playing(sc):
    if not sc.check("relaunch (stop echo seen)", sc.link.relaunch()):
        return
    sc.expect_cmd("hello on launch", "HELLO", ARG=0, LIST_ID=0, REQ=0)
    sc.send_ok("now playing", now_playing(
        "Moonlight Sonata", "Ludwig van Beethoven", "Piano Sonatas", 2, 83, 245, 0x01 | (2 << 2)))
    sc.shot("np-playing")
    sc.button("select")
    sc.expect_cmd("select -> play/pause", "PLAY_PAUSE")
    sc.button("up")
    sc.expect_cmd("up -> prev", "PREV")
    sc.button("down")
    sc.expect_cmd("down -> next", "NEXT")
    sc.button("up", hold_ms=900)
    sc.expect_cmd("long up -> volume up", "VOL_UP")
    sc.button("down", hold_ms=900)
    sc.expect_cmd("long down -> volume down", "VOL_DOWN")
    sc.send_ok("unicode + paused", now_playing(
        "Jai Ho (You Are My Destiny) — Pussycat Dolls mix", "A. R. Rahmān", "Slumdog Millionaire",
        1, 3725, 7384, 0x02))
    sc.shot("np-unicode-paused")


def run_actions(sc):
    sc.button("select", hold_ms=900)
    sc.shot("actions")
    sc.button("down")
    sc.button("select")
    sc.expect_cmd("actions -> love", "LOVE")
    sc.button("select", hold_ms=900)
    sc.button("down")
    sc.button("down")
    sc.button("down")
    sc.button("select")
    sc.expect_cmd("actions -> repeat", "REPEAT")
    sc.button("back")


def run_browse(sc):
    sc.button("select", hold_ms=900)
    sc.button("up")
    sc.button("up")
    sc.button("up")
    sc.button("select")
    ok, msg = sc.expect_cmd("actions -> browse root", "BROWSE_ROOT")
    if not ok or not sc.check("browse root carries a request id", msg.get("REQ", 0) != 0,
                              "got {}".format(msg)):
        return
    sc.shot("browse-loading")
    root = [("Music Library", "", 1), ("Artists", "", 1), ("Albums", "", 1), ("Playlists", "", 1)]
    send_list(sc, "root list", 7, msg["REQ"], "Ultrasonic", root)
    sc.shot("browse-root")
    sc.button("down")
    sc.button("select")
    ok, msg = sc.expect_cmd("open artists", "OPEN", ARG=1, LIST_ID=7)
    if not ok:
        return
    # A header whose REQ does not match the pending request must not land.
    # Checked while a request IS pending, by comparing pixels before/after.
    before = sc.pixels("stale-before")
    wrong_req = msg["REQ"] % 255 + 1
    sc.send_ok("stale header delivered", {"LIST_ID": Uint16(99), "REQ": Uint8(wrong_req),
                                          "LIST_TITLE": CString("STALE"),
                                          "LIST_COUNT": Uint16(1)})
    after = sc.pixels("stale-after")
    sc.check("stale header ignored (screen unchanged)", before is not None and before == after)
    albums = [("Guru", "A. R. Rahman", 3), ("Roja", "A. R. Rahman", 3),
              ("Play all", "", 2)]
    send_list(sc, "album list", 8, msg["REQ"], "A. R. Rahman", albums)
    sc.shot("browse-albums")
    sc.button("select", hold_ms=900)
    sc.expect_cmd("long select album -> play item", "PLAY_ITEM", ARG=0, LIST_ID=8)
    sc.shot("after-play")


def bottom_rows(capture, rows):
    stride, flat = capture
    return flat[-(stride * rows):]


def run_watchdog(sc):
    """An unanswered HELLO must raise the bottom banner within the watchdog window."""
    if not sc.check("relaunch for watchdog", sc.link.relaunch()):
        return
    sc.expect_cmd("hello (left unanswered)", "HELLO")
    early = sc.pixels("watchdog-early")
    time.sleep(7.0)
    late = sc.pixels("watchdog-late")
    if early is None or late is None:
        sc.check("watchdog banner appears", False, "screenshot capture failed")
        return
    sc.check("watchdog banner appears", bottom_rows(early, 50) != bottom_rows(late, 50))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("platform")
    ap.add_argument("package_json")
    ap.add_argument("outdir")
    args = ap.parse_args()
    pkg = json.loads(pathlib.Path(args.package_json).read_text())["pebble"]
    if not EMULATOR_STATE.exists():
        print("SETUP-ERROR: no running emulator state at {}".format(EMULATOR_STATE), file=sys.stderr)
        return 2
    outdir = pathlib.Path(args.outdir)
    outdir.mkdir(parents=True, exist_ok=True)
    link = Link(args.platform, pkg["messageKeys"], uuid.UUID(pkg["uuid"]))
    sc = Scenario(link, args.platform, outdir)
    run_now_playing(sc)
    run_actions(sc)
    run_browse(sc)
    run_watchdog(sc)
    failed =[r for r in sc.results if not r[1]]
    print("SUMMARY {}: {}/{} passed".format(args.platform, len(sc.results) - len(failed),
                                            len(sc.results)), flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
