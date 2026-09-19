#pragma once
#include <pebble.h>

bool comm_init(void);
void comm_deinit(void);
// Queue a command for the companion. Returns false when the bounded queue is
// full; the caller surfaces that rather than dropping it silently.
bool comm_send(uint8_t cmd, uint16_t arg, uint16_t list_id, uint8_t req);
