#!/usr/bin/env python3
"""Diagnostic: does AppRunState stop/start relaunch the watchapp, and is HELLO seen after it?"""

import json
import pathlib
import subprocess
import sys
import time
import uuid

from libpebble2.communication import PebbleConnection
from libpebble2.communication.transports.websocket import WebsocketTransport
from libpebble2.protocol.apps import AppRunState, AppRunStateStart, AppRunStateStop
from libpebble2.services.appmessage import AppMessageService


def main():
    platform, pkg_path, out = sys.argv[1], sys.argv[2], pathlib.Path(sys.argv[3])
    pkg = json.loads(pathlib.Path(pkg_path).read_text())["pebble"]
    app = uuid.UUID(pkg["uuid"])
    port = json.loads(pathlib.Path("/tmp/pb-emulator.json").read_text())[platform]["4.33.1"]["pypkjs"]["port"]
    pebble = PebbleConnection(WebsocketTransport("ws://localhost:{}/".format(port)))
    pebble.connect()
    pebble.run_async()
    t0 = time.monotonic()
    svc = AppMessageService(pebble)
    svc.register_handler("appmessage", lambda tid, u, d: print("{:6.2f}s appmessage uuid={} {}".format(
        time.monotonic() - t0, u, d), flush=True))
    pebble.register_endpoint(AppRunState, lambda p: print("{:6.2f}s apprunstate {}".format(
        time.monotonic() - t0, p), flush=True))
    shot = ["pebble", "screenshot", "--no-open", "--emulator", platform]
    pebble.send_packet(AppRunState(data=AppRunStateStop(uuid=app)))
    print("{:6.2f}s sent stop".format(time.monotonic() - t0), flush=True)
    time.sleep(2.0)
    subprocess.run(shot + [str(out / "after-stop.png")], capture_output=True, timeout=60)
    pebble.send_packet(AppRunState(data=AppRunStateStart(uuid=app)))
    print("{:6.2f}s sent start".format(time.monotonic() - t0), flush=True)
    time.sleep(8.0)
    subprocess.run(shot + [str(out / "after-start.png")], capture_output=True, timeout=60)
    print("{:6.2f}s done".format(time.monotonic() - t0), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
