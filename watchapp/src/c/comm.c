#include "comm.h"
#include "browse.h"
#include "now_playing.h"
#include "protocol.h"

#define QUEUE_LEN 4
#define INBOX_SIZE 512
#define OUTBOX_SIZE 64
#define MAX_RETRIES 1
// A busy outbox (APP_MSG_BUSY) re-arms a short timer; after this many
// consecutive stalls the head command is dropped and reported.
#define MAX_STALLS 10
#define STALL_RETRY_MS 200

typedef struct {
  uint8_t cmd;
  uint8_t req;
  uint16_t arg;
  uint16_t list_id;
} Command;

static Command s_queue[QUEUE_LEN];
static uint8_t s_head;
static uint8_t s_count;
static bool s_in_flight;
static uint8_t s_retries;
static uint8_t s_stalls;
static AppTimer *s_stall_timer;

static void pump(void);

static void pop_head(void) {
  s_head = (uint8_t)((s_head + 1) % QUEUE_LEN);
  s_count--;
  s_retries = 0;
  s_stalls = 0;
}

static void stall_fired(void *ctx) {
  s_stall_timer = NULL;
  pump();
}

static void note_stall(void) {
  s_stalls++;
  if (s_stalls > MAX_STALLS) {
    pop_head();
    now_playing_set_status("Watch outbox stuck");
  }
  if (s_stall_timer == NULL && s_count > 0) {
    s_stall_timer = app_timer_register(STALL_RETRY_MS, stall_fired, NULL);
  }
}

static bool write_command(DictionaryIterator *iter, const Command *c) {
  if (dict_write_uint8(iter, MESSAGE_KEY_CMD, c->cmd) != DICT_OK) {
    return false;
  }
  if (dict_write_uint16(iter, MESSAGE_KEY_ARG, c->arg) != DICT_OK) {
    return false;
  }
  if (dict_write_uint16(iter, MESSAGE_KEY_LIST_ID, c->list_id) != DICT_OK) {
    return false;
  }
  return dict_write_uint8(iter, MESSAGE_KEY_REQ, c->req) == DICT_OK;
}

static void pump(void) {
  if (s_in_flight || s_count == 0) {
    return;
  }
  DictionaryIterator *iter = NULL;
  const AppMessageResult begin = app_message_outbox_begin(&iter);
  if (begin != APP_MSG_OK || iter == NULL) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "outbox_begin failed: %d", (int)begin);
    note_stall();
    return;
  }
  if (!write_command(iter, &s_queue[s_head])) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "dict write failed for cmd %u", s_queue[s_head].cmd);
    pop_head();
    note_stall();
    return;
  }
  const AppMessageResult sent = app_message_outbox_send();
  if (sent != APP_MSG_OK) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "outbox_send failed: %d", (int)sent);
    note_stall();
    return;
  }
  s_stalls = 0;
  s_in_flight = true;
}

static void outbox_sent(DictionaryIterator *iter, void *ctx) {
  s_in_flight = false;
  if (s_count > 0) {
    pop_head();
  }
  pump();
}

static void outbox_failed(DictionaryIterator *iter, AppMessageResult reason, void *ctx) {
  s_in_flight = false;
  APP_LOG(APP_LOG_LEVEL_WARNING, "outbox failed: %d (retry %u)", (int)reason, s_retries);
  if (s_count == 0) {
    return;
  }
  if (s_retries < MAX_RETRIES) {
    s_retries++;
  } else {
    pop_head();
    now_playing_set_status("Phone did not answer");
  }
  pump();
}

static void inbox_received(DictionaryIterator *iter, void *ctx) {
  now_playing_apply(iter);
  browse_apply(iter);
}

static void inbox_dropped(AppMessageResult reason, void *ctx) {
  APP_LOG(APP_LOG_LEVEL_WARNING, "inbox dropped: %d", (int)reason);
}

bool comm_init(void) {
  s_head = 0;
  s_count = 0;
  s_in_flight = false;
  s_retries = 0;
  s_stalls = 0;
  s_stall_timer = NULL;
  app_message_register_inbox_received(inbox_received);
  app_message_register_inbox_dropped(inbox_dropped);
  app_message_register_outbox_sent(outbox_sent);
  app_message_register_outbox_failed(outbox_failed);
  const AppMessageResult r = app_message_open(INBOX_SIZE, OUTBOX_SIZE);
  if (r != APP_MSG_OK) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "app_message_open failed: %d", (int)r);
    return false;
  }
  return true;
}

void comm_deinit(void) {
  if (s_stall_timer != NULL) {
    app_timer_cancel(s_stall_timer);
    s_stall_timer = NULL;
  }
  app_message_deregister_callbacks();
}

bool comm_send(uint8_t cmd, uint16_t arg, uint16_t list_id, uint8_t req) {
  if (s_count >= QUEUE_LEN) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "command queue full, cmd %u refused", cmd);
    return false;
  }
  const uint8_t tail = (uint8_t)((s_head + s_count) % QUEUE_LEN);
  s_queue[tail] = (Command){.cmd = cmd, .req = req, .arg = arg, .list_id = list_id};
  s_count++;
  pump();
  return true;
}
