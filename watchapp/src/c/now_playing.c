#include "now_playing.h"
#include "actions.h"
#include "comm.h"
#include "protocol.h"
#include "util.h"

#define STRIP_W 20
#define ICON_H 11
#define WATCHDOG_MS 6000
#define TIME_TEXT_SIZE 24
#define FLAGS_TEXT_SIZE 32

typedef struct {
  char title[NP_TITLE_SIZE];
  char artist[NP_LINE_SIZE];
  char album[NP_LINE_SIZE];
  char status[STATUS_SIZE];
  char times[TIME_TEXT_SIZE];
  char flag_text[FLAGS_TEXT_SIZE];
  uint32_t pos;
  uint32_t dur;
  uint8_t state;
  uint8_t flags;
  bool has_data;
} NowPlaying;

static NowPlaying s_np;
static Window *s_window;
static StatusBarLayer *s_status_bar;
static TextLayer *s_title;
static TextLayer *s_artist;
static TextLayer *s_album;
static TextLayer *s_times;
static TextLayer *s_flags;
static TextLayer *s_banner;
static Layer *s_progress;
static Layer *s_strip;
static AppTimer *s_watchdog;
static GFont s_banner_font;
static GRect s_banner_area;

#define BANNER_MAX_LINES 3
#define BANNER_PAD 4

static GColor accent(void) {
  return PBL_IF_COLOR_ELSE(GColorOrange, GColorBlack);
}

// Horizontal-scanline triangle: no GPath, so nothing is allocated at draw time.
static void fill_triangle(GContext *ctx, int16_t x, int16_t top, bool points_right) {
  const int16_t half = ICON_H / 2;
  const int16_t width = half + 1;
  for (int16_t i = 0; i < ICON_H; i++) {
    const int16_t dist = (int16_t)(i > half ? i - half : half - i);
    const int16_t len = (int16_t)(width - dist);
    const int16_t y = (int16_t)(top + i);
    if (points_right) {
      graphics_draw_line(ctx, GPoint(x, y), GPoint((int16_t)(x + len - 1), y));
    } else {
      graphics_draw_line(ctx, GPoint((int16_t)(x + width - len), y),
                         GPoint((int16_t)(x + width - 1), y));
    }
  }
}

static void draw_skip(GContext *ctx, int16_t cx, int16_t cy, bool forward) {
  const int16_t top = (int16_t)(cy - ICON_H / 2);
  const int16_t tri_x = (int16_t)(cx - 4);
  fill_triangle(ctx, tri_x, top, forward);
  const int16_t bar_x = forward ? (int16_t)(tri_x + ICON_H / 2 + 1) : (int16_t)(tri_x - 2);
  graphics_fill_rect(ctx, GRect(bar_x, top, 2, ICON_H), 0, GCornerNone);
}

static void draw_play_pause(GContext *ctx, int16_t cx, int16_t cy) {
  const int16_t top = (int16_t)(cy - ICON_H / 2);
  if (s_np.state == NP_STATE_PLAYING || s_np.state == NP_STATE_BUFFERING) {
    graphics_fill_rect(ctx, GRect((int16_t)(cx - 4), top, 3, ICON_H), 0, GCornerNone);
    graphics_fill_rect(ctx, GRect((int16_t)(cx + 1), top, 3, ICON_H), 0, GCornerNone);
  } else {
    fill_triangle(ctx, (int16_t)(cx - 3), top, true);
  }
}

static void strip_update(Layer *layer, GContext *ctx) {
  const GRect b = layer_get_bounds(layer);
  graphics_context_set_fill_color(ctx, accent());
  graphics_fill_rect(ctx, b, 0, GCornerNone);
  graphics_context_set_fill_color(ctx, GColorWhite);
  graphics_context_set_stroke_color(ctx, GColorWhite);
  const int16_t cx = (int16_t)(b.size.w / 2);
  draw_skip(ctx, cx, (int16_t)(b.size.h / 5), false);
  draw_play_pause(ctx, cx, (int16_t)(b.size.h / 2));
  draw_skip(ctx, cx, (int16_t)(b.size.h * 4 / 5), true);
}

static void progress_update(Layer *layer, GContext *ctx) {
  const GRect b = layer_get_bounds(layer);
  graphics_context_set_stroke_color(ctx, GColorBlack);
  graphics_draw_rect(ctx, b);
  if (s_np.dur == 0) {
    return;
  }
  const uint32_t pos = s_np.pos > s_np.dur ? s_np.dur : s_np.pos;
  const int16_t inner = (int16_t)(b.size.w - 2);
  const int16_t filled = (int16_t)((uint32_t)inner * pos / s_np.dur);
  graphics_context_set_fill_color(ctx, accent());
  graphics_fill_rect(ctx, GRect(1, 1, filled, (int16_t)(b.size.h - 2)), 0, GCornerNone);
}

static void refresh_times(void) {
  char pos[10];
  char dur[10];
  util_format_time(pos, sizeof(pos), s_np.pos);
  if (s_np.dur == 0) {
    util_copy_text(s_np.times, sizeof(s_np.times), pos);
  } else {
    util_format_time(dur, sizeof(dur), s_np.dur);
    snprintf(s_np.times, sizeof(s_np.times), "%s / %s", pos, dur);
  }
  if (s_times != NULL) {
    text_layer_set_text(s_times, s_np.times);
    layer_mark_dirty(s_progress);
  }
}

static const char *repeat_label(uint8_t flags) {
  const uint8_t mode = (uint8_t)((flags & NP_FLAG_REPEAT_MASK) >> NP_FLAG_REPEAT_SHIFT);
  if (mode == 1) {
    return "RPT 1";
  }
  return mode == 2 ? "RPT ALL" : "";
}

static void refresh_flags(void) {
  const char *loved = (s_np.flags & NP_FLAG_LOVED) ? "LOVED" : "";
  const char *shuffle = (s_np.flags & NP_FLAG_SHUFFLE) ? "SHUF" : "";
  snprintf(s_np.flag_text, sizeof(s_np.flag_text), "%s %s %s", loved, shuffle,
           repeat_label(s_np.flags));
  if (s_flags != NULL) {
    text_layer_set_text(s_flags, s_np.flag_text);
  }
}

// Bottom-anchored banner sized to its text, capped at BANNER_MAX_LINES.
static void layout_banner(void) {
  const bool empty = s_np.status[0] == '\0';
  layer_set_hidden(text_layer_get_layer(s_banner), empty);
  if (empty) {
    return;
  }
  const GRect probe = GRect(0, 0, s_banner_area.size.w, s_banner_area.size.h);
  const GSize one = graphics_text_layout_get_content_size(
      "Ag", s_banner_font, probe, GTextOverflowModeWordWrap, GTextAlignmentCenter);
  const GSize all = graphics_text_layout_get_content_size(
      s_np.status, s_banner_font, probe, GTextOverflowModeWordWrap, GTextAlignmentCenter);
  const int16_t cap = (int16_t)(one.h * BANNER_MAX_LINES);
  const int16_t h = (int16_t)((all.h > cap ? cap : all.h) + BANNER_PAD * 2);
  const int16_t bottom = (int16_t)(s_banner_area.origin.y + s_banner_area.size.h);
  layer_set_frame(text_layer_get_layer(s_banner),
                  GRect(s_banner_area.origin.x, (int16_t)(bottom - h), s_banner_area.size.w, h));
}

static void refresh_text(void) {
  if (s_title == NULL) {
    return;
  }
  text_layer_set_text(s_title, s_np.title);
  text_layer_set_text(s_artist, s_np.artist);
  text_layer_set_text(s_album, s_np.album);
  text_layer_set_text(s_banner, s_np.status);
  layout_banner();
  refresh_flags();
  refresh_times();
  layer_mark_dirty(s_strip);
}

static void send_or_complain(uint8_t cmd) {
  if (!comm_send(cmd, 0, 0, 0)) {
    now_playing_set_status("Busy - try again");
  }
}

static void up_click(ClickRecognizerRef r, void *ctx) { send_or_complain(CMD_PREV); }
static void up_long(ClickRecognizerRef r, void *ctx) { send_or_complain(CMD_VOL_UP); }
static void select_click(ClickRecognizerRef r, void *ctx) { send_or_complain(CMD_PLAY_PAUSE); }
static void select_long(ClickRecognizerRef r, void *ctx) { actions_show(); }
static void down_click(ClickRecognizerRef r, void *ctx) { send_or_complain(CMD_NEXT); }
static void down_long(ClickRecognizerRef r, void *ctx) { send_or_complain(CMD_VOL_DOWN); }

static void click_config(void *ctx) {
  window_single_click_subscribe(BUTTON_ID_UP, up_click);
  window_long_click_subscribe(BUTTON_ID_UP, 0, up_long, NULL);
  window_single_click_subscribe(BUTTON_ID_SELECT, select_click);
  window_long_click_subscribe(BUTTON_ID_SELECT, 0, select_long, NULL);
  window_single_click_subscribe(BUTTON_ID_DOWN, down_click);
  window_long_click_subscribe(BUTTON_ID_DOWN, 0, down_long, NULL);
}

static TextLayer *make_text(Layer *root, GRect frame, const char *font_key) {
  TextLayer *t = text_layer_create(frame);
  text_layer_set_font(t, fonts_get_system_font(font_key));
  text_layer_set_background_color(t, GColorClear);
  text_layer_set_text_color(t, GColorBlack);
  text_layer_set_overflow_mode(t, GTextOverflowModeTrailingEllipsis);
  layer_add_child(root, text_layer_get_layer(t));
  return t;
}

// Geometry for the two target screens: flint 144x168 (bw), emery 200x228 (color).
typedef struct {
  int16_t title_y, title_h, artist_y, artist_h, album_y, album_h;
  int16_t bar_y, times_y, flags_y, line_h;
  const char *title_font;
  const char *artist_font;
  const char *small_font;
} Geometry;

static Geometry geometry_for(GRect b) {
  const int16_t top = STATUS_BAR_LAYER_HEIGHT;
  if (b.size.w >= 200) {
    return (Geometry){top + 2, 64, top + 66, 30, top + 96, 26, top + 130,
                      top + 138, top + 164, 24,
                      FONT_KEY_GOTHIC_28_BOLD, FONT_KEY_GOTHIC_24, FONT_KEY_GOTHIC_18};
  }
  return (Geometry){top, 54, top + 54, 24, top + 76, 22, top + 102,
                    top + 108, top + 128, 20,
                    FONT_KEY_GOTHIC_24_BOLD, FONT_KEY_GOTHIC_18, FONT_KEY_GOTHIC_14};
}

static void create_text_layers(Layer *root, GRect b, const Geometry *g) {
  const int16_t x = 4;
  const int16_t w = (int16_t)(b.size.w - STRIP_W - 6);
  s_title = make_text(root, GRect(x, g->title_y, w, g->title_h), g->title_font);
  s_artist = make_text(root, GRect(x, g->artist_y, w, g->artist_h), g->artist_font);
  s_album = make_text(root, GRect(x, g->album_y, w, g->album_h), g->small_font);
  s_times = make_text(root, GRect(x, g->times_y, w, g->line_h), g->small_font);
  s_flags = make_text(root, GRect(x, g->flags_y, w, g->line_h), g->small_font);
  s_banner_font = fonts_get_system_font(g->small_font);
  s_banner_area = GRect(0, g->title_y, (int16_t)(b.size.w - STRIP_W),
                        (int16_t)(b.size.h - g->title_y));
  s_banner = make_text(root, s_banner_area, g->small_font);
  text_layer_set_overflow_mode(s_banner, GTextOverflowModeWordWrap);
  text_layer_set_background_color(s_banner, GColorBlack);
  text_layer_set_text_color(s_banner, GColorWhite);
  text_layer_set_text_alignment(s_banner, GTextAlignmentCenter);
}

static void window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  const GRect b = layer_get_bounds(root);
  const Geometry g = geometry_for(b);
  s_status_bar = status_bar_layer_create();
  status_bar_layer_set_colors(s_status_bar, accent(), GColorWhite);
  layer_set_frame(status_bar_layer_get_layer(s_status_bar),
                  GRect(0, 0, (int16_t)(b.size.w - STRIP_W), STATUS_BAR_LAYER_HEIGHT));
  layer_add_child(root, status_bar_layer_get_layer(s_status_bar));
  create_text_layers(root, b, &g);
  s_progress = layer_create(GRect(4, g.bar_y, (int16_t)(b.size.w - STRIP_W - 8), 6));
  layer_set_update_proc(s_progress, progress_update);
  layer_add_child(root, s_progress);
  s_strip = layer_create(GRect((int16_t)(b.size.w - STRIP_W), 0, STRIP_W, b.size.h));
  layer_set_update_proc(s_strip, strip_update);
  layer_add_child(root, s_strip);
  refresh_text();
}

static void window_unload(Window *window) {
  text_layer_destroy(s_title);
  text_layer_destroy(s_artist);
  text_layer_destroy(s_album);
  text_layer_destroy(s_times);
  text_layer_destroy(s_flags);
  text_layer_destroy(s_banner);
  layer_destroy(s_progress);
  layer_destroy(s_strip);
  status_bar_layer_destroy(s_status_bar);
  s_title = NULL;
}

static void tick(struct tm *now, TimeUnits changed) {
  if (s_np.state != NP_STATE_PLAYING || s_np.dur == 0 || s_np.pos >= s_np.dur) {
    return;
  }
  s_np.pos++;
  refresh_times();
}

static void watchdog_fired(void *ctx) {
  s_watchdog = NULL;
  if (!s_np.has_data) {
    now_playing_set_status("No reply. Is the Ultrasonic for Pebble app installed?");
  }
}

void now_playing_arm_watchdog(void) {
  if (s_watchdog != NULL) {
    app_timer_cancel(s_watchdog);
  }
  s_watchdog = app_timer_register(WATCHDOG_MS, watchdog_fired, NULL);
}

static bool apply_texts(const DictionaryIterator *iter) {
  bool any = false;
  any |= util_read_text(iter, MESSAGE_KEY_NP_TITLE, s_np.title, sizeof(s_np.title));
  any |= util_read_text(iter, MESSAGE_KEY_NP_ARTIST, s_np.artist, sizeof(s_np.artist));
  any |= util_read_text(iter, MESSAGE_KEY_NP_ALBUM, s_np.album, sizeof(s_np.album));
  return any;
}

static bool apply_numbers(const DictionaryIterator *iter) {
  uint32_t v = 0;
  bool any = false;
  if (util_read_uint(iter, MESSAGE_KEY_NP_STATE, &v)) {
    s_np.state = (uint8_t)(v <= NP_STATE_ENDED ? v : NP_STATE_NONE);
    any = true;
  }
  if (util_read_uint(iter, MESSAGE_KEY_NP_POS, &v)) {
    s_np.pos = v;
    any = true;
  }
  if (util_read_uint(iter, MESSAGE_KEY_NP_DUR, &v)) {
    s_np.dur = v;
    any = true;
  }
  if (util_read_uint(iter, MESSAGE_KEY_NP_FLAGS, &v)) {
    s_np.flags = (uint8_t)v;
    any = true;
  }
  return any;
}

void now_playing_apply(const DictionaryIterator *iter) {
  const bool texts = apply_texts(iter);
  const bool numbers = apply_numbers(iter);
  const bool status = util_read_text(iter, MESSAGE_KEY_STATUS, s_np.status, sizeof(s_np.status));
  if (texts || numbers) {
    s_np.has_data = true;
  }
  if (texts || numbers || status) {
    refresh_text();
    actions_refresh();
  }
}

void now_playing_set_status(const char *text) {
  util_copy_text(s_np.status, sizeof(s_np.status), text);
  refresh_text();
}

uint8_t now_playing_flags(void) { return s_np.flags; }

const char *now_playing_title(void) { return s_np.title; }

void now_playing_init(void) {
  memset(&s_np, 0, sizeof(s_np));
  util_copy_text(s_np.title, sizeof(s_np.title), "Ultrasonic");
  util_copy_text(s_np.artist, sizeof(s_np.artist), "Connecting to phone...");
  s_window = window_create();
  window_set_click_config_provider(s_window, click_config);
  window_set_window_handlers(s_window, (WindowHandlers){.load = window_load, .unload = window_unload});
  tick_timer_service_subscribe(SECOND_UNIT, tick);
}

void now_playing_push(void) { window_stack_push(s_window, true); }

void now_playing_deinit(void) {
  tick_timer_service_unsubscribe();
  if (s_watchdog != NULL) {
    app_timer_cancel(s_watchdog);
    s_watchdog = NULL;
  }
  window_destroy(s_window);
}
