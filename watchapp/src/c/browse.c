#include "browse.h"
#include "actions.h"
#include "comm.h"
#include "now_playing.h"
#include "protocol.h"
#include "util.h"

typedef struct {
  char label[ITEM_LABEL_SIZE];
  char sub[ITEM_SUB_SIZE];
  uint8_t flags;
  bool present;
} Item;

typedef struct {
  Window *window;
  MenuLayer *menu;
  Item items[MAX_ITEMS];
  char title[LIST_TITLE_SIZE];
  uint16_t list_id;
  uint16_t count;
  uint8_t depth;
  bool loaded;
} ListView;

static ListView s_views[MAX_DEPTH];
static int8_t s_pending_depth = -1;
static uint8_t s_pending_req;
static uint8_t s_next_req = 1;

static uint16_t num_sections(MenuLayer *m, void *ctx) { return 1; }

static int16_t header_height(MenuLayer *m, uint16_t section, void *ctx) {
  return MENU_CELL_BASIC_HEADER_HEIGHT;
}

static void draw_header(GContext *ctx, const Layer *cell, uint16_t section, void *data) {
  const ListView *v = data;
  menu_cell_basic_header_draw(ctx, cell, v->title);
}

static uint16_t num_rows(MenuLayer *m, uint16_t section, void *data) {
  const ListView *v = data;
  return (v->loaded && v->count > 0) ? v->count : 1;
}

static void draw_row(GContext *ctx, const Layer *cell, MenuIndex *idx, void *data) {
  const ListView *v = data;
  if (!v->loaded) {
    menu_cell_basic_draw(ctx, cell, "Loading...", NULL, NULL);
    return;
  }
  if (v->count == 0) {
    menu_cell_basic_draw(ctx, cell, "(empty)", NULL, NULL);
    return;
  }
  const Item *it = &v->items[idx->row];
  if (!it->present) {
    menu_cell_basic_draw(ctx, cell, "...", NULL, NULL);
    return;
  }
  menu_cell_basic_draw(ctx, cell, it->label, it->sub[0] != '\0' ? it->sub : NULL, NULL);
}

static void reset_view(ListView *v) {
  for (uint16_t i = 0; i < MAX_ITEMS; i++) {
    v->items[i].present = false;
  }
  v->loaded = false;
  v->count = 0;
  v->list_id = 0;
  util_copy_text(v->title, sizeof(v->title), "Loading...");
}

static uint8_t next_req(void) {
  const uint8_t req = s_next_req;
  s_next_req = (uint8_t)(s_next_req == 255 ? 1 : s_next_req + 1);
  return req;
}

static void request(uint8_t depth, uint8_t cmd, uint16_t arg, uint16_t list_id) {
  if (depth >= MAX_DEPTH) {
    now_playing_set_status("Library too deep for the watch");
    return;
  }
  ListView *v = &s_views[depth];
  reset_view(v);
  const uint8_t req = next_req();
  if (!comm_send(cmd, arg, list_id, req)) {
    now_playing_set_status("Busy - try again");
    return;
  }
  s_pending_depth = (int8_t)depth;
  s_pending_req = req;
  if (window_stack_contains_window(v->window)) {
    menu_layer_reload_data(v->menu);
  } else {
    window_stack_push(v->window, true);
  }
}

static void close_all(void) {
  for (int8_t d = MAX_DEPTH - 1; d >= 0; d--) {
    if (window_stack_contains_window(s_views[d].window)) {
      window_stack_remove(s_views[d].window, false);
    }
  }
  actions_hide();
}

static void play(const ListView *v, uint16_t index) {
  if (!comm_send(CMD_PLAY_ITEM, index, v->list_id, 0)) {
    now_playing_set_status("Busy - try again");
    return;
  }
  now_playing_set_status("Starting playback...");
  close_all();
}

static const Item *selected_item(const ListView *v, uint16_t row) {
  if (!v->loaded || row >= v->count || !v->items[row].present) {
    return NULL;
  }
  return &v->items[row];
}

static void select_click(MenuLayer *m, MenuIndex *idx, void *data) {
  const ListView *v = data;
  const Item *it = selected_item(v, idx->row);
  if (it == NULL) {
    return;
  }
  if (it->flags & ITEM_FLAG_BROWSABLE) {
    request((uint8_t)(v->depth + 1), CMD_OPEN, idx->row, v->list_id);
  } else if (it->flags & ITEM_FLAG_PLAYABLE) {
    play(v, idx->row);
  }
}

static void select_long(MenuLayer *m, MenuIndex *idx, void *data) {
  const ListView *v = data;
  const Item *it = selected_item(v, idx->row);
  if (it != NULL && (it->flags & ITEM_FLAG_PLAYABLE)) {
    play(v, idx->row);
  }
}

static ListView *view_for_window(Window *window) {
  for (uint8_t d = 0; d < MAX_DEPTH; d++) {
    if (s_views[d].window == window) {
      return &s_views[d];
    }
  }
  return NULL;
}

static void window_load(Window *window) {
  ListView *v = view_for_window(window);
  if (v == NULL) {
    return;
  }
  Layer *root = window_get_root_layer(window);
  v->menu = menu_layer_create(layer_get_bounds(root));
  menu_layer_set_callbacks(v->menu, v,
                           (MenuLayerCallbacks){.get_num_sections = num_sections,
                                                .get_header_height = header_height,
                                                .draw_header = draw_header,
                                                .get_num_rows = num_rows,
                                                .draw_row = draw_row,
                                                .select_click = select_click,
                                                .select_long_click = select_long});
  menu_layer_set_highlight_colors(v->menu, PBL_IF_COLOR_ELSE(GColorOrange, GColorBlack),
                                  GColorWhite);
  menu_layer_set_click_config_onto_window(v->menu, window);
  layer_add_child(root, menu_layer_get_layer(v->menu));
}

static void window_unload(Window *window) {
  ListView *v = view_for_window(window);
  if (v == NULL) {
    return;
  }
  if (s_pending_depth == (int8_t)v->depth) {
    s_pending_depth = -1;
  }
  menu_layer_destroy(v->menu);
  v->menu = NULL;
}

static void apply_header(const DictionaryIterator *iter, uint16_t list_id) {
  uint32_t req = 0;
  uint32_t count = 0;
  if (!util_read_uint(iter, MESSAGE_KEY_REQ, &req) ||
      !util_read_uint(iter, MESSAGE_KEY_LIST_COUNT, &count)) {
    return;
  }
  if (s_pending_depth < 0 || req != s_pending_req) {
    APP_LOG(APP_LOG_LEVEL_DEBUG, "stale list header req=%lu", (unsigned long)req);
    return;
  }
  ListView *v = &s_views[s_pending_depth];
  s_pending_depth = -1;
  v->list_id = list_id;
  v->count = (uint16_t)(count > MAX_ITEMS ? MAX_ITEMS : count);
  util_read_text(iter, MESSAGE_KEY_LIST_TITLE, v->title, sizeof(v->title));
  v->loaded = true;
  if (v->menu != NULL) {
    menu_layer_reload_data(v->menu);
  }
}

static ListView *view_for_list(uint16_t list_id) {
  for (uint8_t d = 0; d < MAX_DEPTH; d++) {
    if (s_views[d].loaded && s_views[d].list_id == list_id) {
      return &s_views[d];
    }
  }
  return NULL;
}

static void apply_item(const DictionaryIterator *iter, uint16_t list_id, uint32_t index) {
  ListView *v = view_for_list(list_id);
  if (v == NULL || index >= v->count) {
    return;
  }
  Item *it = &v->items[index];
  uint32_t flags = 0;
  it->label[0] = '\0';
  it->sub[0] = '\0';
  util_read_text(iter, MESSAGE_KEY_ITEM_LABEL, it->label, sizeof(it->label));
  util_read_text(iter, MESSAGE_KEY_ITEM_SUB, it->sub, sizeof(it->sub));
  it->flags = util_read_uint(iter, MESSAGE_KEY_ITEM_FLAGS, &flags) ? (uint8_t)flags : 0;
  it->present = true;
  if (v->menu != NULL) {
    menu_layer_reload_data(v->menu);
  }
}

void browse_apply(const DictionaryIterator *iter) {
  uint32_t list_id = 0;
  uint32_t index = 0;
  if (!util_read_uint(iter, MESSAGE_KEY_LIST_ID, &list_id) || list_id == 0) {
    return;
  }
  if (util_read_uint(iter, MESSAGE_KEY_ITEM_INDEX, &index)) {
    apply_item(iter, (uint16_t)list_id, index);
  } else {
    apply_header(iter, (uint16_t)list_id);
  }
}

void browse_open_root(void) { request(0, CMD_BROWSE_ROOT, 0, 0); }

void browse_init(void) {
  for (uint8_t d = 0; d < MAX_DEPTH; d++) {
    ListView *v = &s_views[d];
    memset(v, 0, sizeof(*v));
    v->depth = d;
    reset_view(v);
    v->window = window_create();
    window_set_window_handlers(v->window,
                               (WindowHandlers){.load = window_load, .unload = window_unload});
  }
}

void browse_deinit(void) {
  for (uint8_t d = 0; d < MAX_DEPTH; d++) {
    window_destroy(s_views[d].window);
  }
}
