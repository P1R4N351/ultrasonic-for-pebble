#pragma once
#include <pebble.h>

void now_playing_init(void);
void now_playing_deinit(void);
void now_playing_push(void);
void now_playing_apply(const DictionaryIterator *iter);
void now_playing_set_status(const char *text);
uint8_t now_playing_flags(void);
const char *now_playing_title(void);
void now_playing_arm_watchdog(void);
