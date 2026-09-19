#pragma once
// Wire contract with the companion (io.github.p1r4n351.ultrasonic.pebble).
// Key numbers live in package.json; the companion's KeysMatchWatchappTest
// fails if its Kotlin constants drift from that file.

#include <pebble.h>

enum {
  CMD_HELLO = 1,
  CMD_PLAY_PAUSE = 2,
  CMD_NEXT = 3,
  CMD_PREV = 4,
  CMD_LOVE = 5,
  CMD_SHUFFLE = 6,
  CMD_REPEAT = 7,
  CMD_VOL_UP = 8,
  CMD_VOL_DOWN = 9,
  CMD_BROWSE_ROOT = 10,
  CMD_OPEN = 11,
  CMD_PLAY_ITEM = 12,
};

enum {
  NP_STATE_NONE = 0,
  NP_STATE_PAUSED = 1,
  NP_STATE_PLAYING = 2,
  NP_STATE_BUFFERING = 3,
  NP_STATE_ENDED = 4,
};

#define NP_FLAG_LOVED 0x01
#define NP_FLAG_SHUFFLE 0x02
#define NP_FLAG_REPEAT_SHIFT 2
#define NP_FLAG_REPEAT_MASK 0x0C

#define ITEM_FLAG_BROWSABLE 0x01
#define ITEM_FLAG_PLAYABLE 0x02

// Buffer sizes include the terminating NUL. The companion truncates to
// (size - 1) UTF-8 bytes on a code-point boundary, so these are the contract.
#define NP_TITLE_SIZE 64
#define NP_LINE_SIZE 48
#define STATUS_SIZE 64
#define ITEM_LABEL_SIZE 40
#define ITEM_SUB_SIZE 32
#define LIST_TITLE_SIZE 32

#define MAX_ITEMS 40
#define MAX_DEPTH 6
