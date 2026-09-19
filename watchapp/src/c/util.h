#pragma once
#include <pebble.h>

// Integer tuple readers. The sender picks the tuple width, so read by length
// rather than trusting a union member.
bool util_read_uint(const DictionaryIterator *iter, uint32_t key, uint32_t *out);
bool util_read_text(const DictionaryIterator *iter, uint32_t key, char *dst, size_t size);
void util_copy_text(char *dst, size_t size, const char *src);
void util_format_time(char *dst, size_t size, uint32_t seconds);
