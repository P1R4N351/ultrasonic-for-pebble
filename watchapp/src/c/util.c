#include "util.h"

bool util_read_uint(const DictionaryIterator *iter, uint32_t key, uint32_t *out) {
  const Tuple *t = dict_find(iter, key);
  if (t == NULL || out == NULL) {
    return false;
  }
  switch (t->length) {
    case 1:
      *out = t->value->uint8;
      return true;
    case 2:
      *out = t->value->uint16;
      return true;
    case 4:
      *out = t->value->uint32;
      return true;
    default:
      APP_LOG(APP_LOG_LEVEL_WARNING, "key %lu: unexpected int width %u",
              (unsigned long)key, (unsigned)t->length);
      return false;
  }
}

void util_copy_text(char *dst, size_t size, const char *src) {
  if (dst == NULL || size == 0) {
    return;
  }
  if (src == NULL) {
    dst[0] = '\0';
    return;
  }
  strncpy(dst, src, size - 1);
  dst[size - 1] = '\0';
}

bool util_read_text(const DictionaryIterator *iter, uint32_t key, char *dst, size_t size) {
  const Tuple *t = dict_find(iter, key);
  if (t == NULL || t->type != TUPLE_CSTRING) {
    return false;
  }
  util_copy_text(dst, size, t->value->cstring);
  return true;
}

void util_format_time(char *dst, size_t size, uint32_t seconds) {
  const uint32_t capped = seconds > 359999 ? 359999 : seconds;
  const uint32_t h = capped / 3600;
  const uint32_t m = (capped % 3600) / 60;
  const uint32_t s = capped % 60;
  if (h > 0) {
    snprintf(dst, size, "%lu:%02lu:%02lu", (unsigned long)h, (unsigned long)m, (unsigned long)s);
  } else {
    snprintf(dst, size, "%lu:%02lu", (unsigned long)m, (unsigned long)s);
  }
}
