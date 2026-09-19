#include "actions.h"
#include "browse.h"
#include "comm.h"
#include "now_playing.h"
#include "protocol.h"

enum { ROW_BROWSE = 0, ROW_LOVE, ROW_SHUFFLE, ROW_REPEAT, ROW_COUNT };

static Window *s_window;
static MenuLayer *s_menu;

static const char *repeat_row_title(uint8_t flags) {
  const uint8_t mode = (uint8_t)((flags & NP_FLAG_REPEAT_MASK) >> NP_FLAG_REPEAT_SHIFT);
  if (mode == 1) {
    return "Repeat: One";
  }
  return mode == 2 ? "Repeat: All" : "Repeat: Off";
}

static uint16_t num_rows(MenuLayer *m, uint16_t section, void *ctx) { return ROW_COUNT; }

static void draw_row(GContext *ctx, const Layer *cell, MenuIndex *idx, void *data) {
  const uint8_t flags = now_playing_flags();
  switch (idx->row) {
    case ROW_BROWSE:
      menu_cell_basic_draw(ctx, cell, "Browse library", "Ultrasonic library", NULL);
      break;
    case ROW_LOVE:
      menu_cell_basic_draw(ctx, cell, (flags & NP_FLAG_LOVED) ? "Unlove" : "Love",
                           now_playing_title(), NULL);
      break;
    case ROW_SHUFFLE:
      menu_cell_basic_draw(ctx, cell, "Shuffle queue", "Reorders the play queue", NULL);
      break;
    default:
      menu_cell_basic_draw(ctx, cell, repeat_row_title(flags), "Select to cycle", NULL);
      break;
  }
}

static void send_or_complain(uint8_t cmd) {
  if (!comm_send(cmd, 0, 0, 0)) {
    now_playing_set_status("Busy - try again");
  }
}

static void select_row(MenuLayer *m, MenuIndex *idx, void *ctx) {
  switch (idx->row) {
    case ROW_BROWSE:
      browse_open_root();
      break;
    case ROW_LOVE:
      send_or_complain(CMD_LOVE);
      window_stack_remove(s_window, true);
      break;
    case ROW_SHUFFLE:
      send_or_complain(CMD_SHUFFLE);
      window_stack_remove(s_window, true);
      break;
    default:
      send_or_complain(CMD_REPEAT);
      break;
  }
}

static void window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  s_menu = menu_layer_create(layer_get_bounds(root));
  menu_layer_set_callbacks(s_menu, NULL,
                           (MenuLayerCallbacks){.get_num_rows = num_rows,
                                                .draw_row = draw_row,
                                                .select_click = select_row});
  menu_layer_set_highlight_colors(s_menu, PBL_IF_COLOR_ELSE(GColorOrange, GColorBlack),
                                  GColorWhite);
  menu_layer_set_click_config_onto_window(s_menu, window);
  layer_add_child(root, menu_layer_get_layer(s_menu));
}

static void window_unload(Window *window) {
  menu_layer_destroy(s_menu);
  s_menu = NULL;
}

void actions_init(void) {
  s_window = window_create();
  window_set_window_handlers(s_window, (WindowHandlers){.load = window_load, .unload = window_unload});
}

void actions_deinit(void) { window_destroy(s_window); }

void actions_show(void) {
  if (!window_stack_contains_window(s_window)) {
    window_stack_push(s_window, true);
  }
}

void actions_hide(void) {
  if (window_stack_contains_window(s_window)) {
    window_stack_remove(s_window, false);
  }
}

void actions_refresh(void) {
  if (s_menu != NULL) {
    menu_layer_reload_data(s_menu);
  }
}
