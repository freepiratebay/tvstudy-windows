//
//  memory.h
//  TVStudy
//
//  Copyright (c) 2016 Hammett & Edison, Inc.  All rights reserved.

#include <stdint.h>
#define u_int8_t uint8_t

void *mem_alloc(size_t size);
void *mem_zalloc(size_t size);
void *mem_calloc(size_t count, size_t size);
void *mem_realloc(void *ptr, size_t size);
void mem_free(void *ptr);
void memswabcpy(void *to, void *from, size_t count, size_t align);
size_t lcpystr(char *dst, const char *src, size_t siz);
size_t lcatstr(char *dst, const char *src, size_t siz);
