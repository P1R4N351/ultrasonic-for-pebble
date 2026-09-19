#pragma once
#include <pebble.h>

void browse_init(void);
void browse_deinit(void);
void browse_open_root(void);
void browse_apply(const DictionaryIterator *iter);
