// Ultrasonic for Pebble — watch side.
// Copyright (c) 2026 PiSCES.
// SPDX-License-Identifier: MPL-2.0
//
// P10 RELAXATIONS: rule 9 — SDK callbacks are function pointers by API design;
// rule 3 — Pebble windows allocate their layers on load, bounded by MAX_DEPTH + 2.

#include <pebble.h>
#include "actions.h"
#include "browse.h"
#include "comm.h"
#include "now_playing.h"
#include "protocol.h"

static void init(void) {
  now_playing_init();
  actions_init();
  browse_init();
  now_playing_push();
  if (!comm_init()) {
    now_playing_set_status("Watch messaging failed to open");
    return;
  }
  if (!comm_send(CMD_HELLO, 0, 0, 0)) {
    now_playing_set_status("Could not reach phone");
    return;
  }
  now_playing_arm_watchdog();
}

static void deinit(void) {
  comm_deinit();
  browse_deinit();
  actions_deinit();
  now_playing_deinit();
}

int main(void) {
  init();
  app_event_loop();
  deinit();
}
