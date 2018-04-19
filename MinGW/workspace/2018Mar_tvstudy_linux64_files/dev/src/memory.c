//
//  memory.c
//  TVStudy
//
//  Copyright (c) 2012-2016 Hammett & Edison, Inc.  All rights reserved.


// Functions for memory allocation.

// These are currently just wrappers around the standard library malloc(), free(), etc., but could be replaced if
// needed in environments where those are not reliable or efficient.  The allocation functions will exit the process
// on allocation failure.


#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>

#include "global.h"
#include "memory.h"


//---------------------------------------------------------------------------------------------------------------------
// Allocate a block.

// Arguments:

//   size  Size of memory block needed.

// Returns pointer to memory, will never return NULL.

void *mem_alloc(size_t size) {

	void *ptr;

	if ((ptr = (void *)malloc(size)) == NULL) {
		fputs("**Memory allocation failed (1)\n", stderr);
		exit(1);
	}

	return(ptr);
}


//---------------------------------------------------------------------------------------------------------------------
// Allocate a block and zero the contents.

// Arguments:

//   size  Size of memory block needed.

// Returns pointer to zeroed memory, will never return NULL.

void *mem_zalloc(size_t size) {

	void *ptr;

	if ((ptr = (void *)malloc(size)) == NULL) {
		fputs("**Memory allocation failed (2)\n", stderr);
		exit(1);
	}

	memset(ptr, 0, size);

	return(ptr);
}


//---------------------------------------------------------------------------------------------------------------------
// Arguments:

//   count  Number of units to be stored.
//   size   Size of individual units.

// Returns pointer to memory of total size (count * size), will never return NULL.

void *mem_calloc(size_t count, size_t size) {

	void *ptr;

	if ((ptr = calloc(count, size)) == NULL) {
		fputs("**Memory allocation failed (3)\n", stderr);
		exit(1);
	}

	return(ptr);
}


//---------------------------------------------------------------------------------------------------------------------
// Arguments:

//   ptr    Pointer to existing block to be re-allocated, or NULL for a new allocation.
//   size   New size of the block.

// Returns pointer to memory, will never return NULL.

void *mem_realloc(void *ptr, size_t size) {

	if ((ptr = realloc(ptr, size)) == NULL) {
		fputs("**Memory allocation failed (4)\n", stderr);
		exit(1);
	}

	return(ptr);
}


//---------------------------------------------------------------------------------------------------------------------
// Arguments:

//   ptr  Pointer to memory to release.

void mem_free(void *ptr) {

	free(ptr);
}


//---------------------------------------------------------------------------------------------------------------------
// Copy with byte re-ordering to a specified alignment size.  Assumes the count is an integral multiple of the
// alignment size, if not the copy will be short.

// Arguments:

//   to     Destination.
//   from   Source.
//   count  Total byte count.
//   align  Alignment size, bytes.

void memswabcpy(void *to, void *from, size_t count, size_t align) {

	size_t i, j, k;

	u_int8_t *t = (u_int8_t *)to;
	u_int8_t *f = (u_int8_t *)from;
	for (i = 0; i < count; i += align) {
		for (j = 0, k = align - 1; j < align; j++, k--) {
			t[i + j] = f[i + k];
		}
	}
}


//---------------------------------------------------------------------------------------------------------------------
// Implementations of standard library strlcpy() and strlcat() functions, with the names changed to lcpystr() and
// lcatstr().  These are needed for environments like Linux that do not provide the functions in the standard library,
// and this is basic, well-established code so there is no problem with just always embedding it as source.

/*
 * Copyright (c) 1998 Todd C. Miller <Todd.Miller@courtesan.com>
 *
 * Permission to use, copy, modify, and distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

/*	$OpenBSD: strlcpy.c,v 1.11 2006/05/05 15:27:38 millert Exp $	*/

/*
 * Copy src to string dst of size siz.  At most siz-1 characters
 * will be copied.  Always NUL terminates (unless siz == 0).
 * Returns strlen(src); if retval >= siz, truncation occurred.
 */
size_t
lcpystr(char *dst, const char *src, size_t siz)
{
	char *d = dst;
	const char *s = src;
	size_t n = siz;

	/* Copy as many bytes as will fit */
	if (n != 0) {
		while (--n != 0) {
			if ((*d++ = *s++) == '\0')
				break;
		}
	}

	/* Not enough room in dst, add NUL and traverse rest of src */
	if (n == 0) {
		if (siz != 0)
			*d = '\0';		/* NUL-terminate dst */
		while (*s++)
			;
	}

	return(s - src - 1);	/* count does not include NUL */
}

/*	$OpenBSD: strlcat.c,v 1.13 2005/08/08 08:05:37 espie Exp $	*/

/*
 * Appends src to string dst of size siz (unlike strncat, siz is the
 * full size of dst, not space left).  At most siz-1 characters
 * will be copied.  Always NUL terminates (unless siz <= strlen(dst)).
 * Returns strlen(src) + MIN(siz, strlen(initial dst)).
 * If retval >= siz, truncation occurred.
 */
size_t
lcatstr(char *dst, const char *src, size_t siz)
{
	char *d = dst;
	const char *s = src;
	size_t n = siz;
	size_t dlen;

	/* Find the end of dst and adjust bytes left but don't go past end */
	while (n-- != 0 && *d != '\0')
		d++;
	dlen = d - dst;
	n = siz - dlen;

	if (n == 0)
		return(dlen + strlen(s));
	while (*s != '\0') {
		if (n != 1) {
			*d++ = *s;
			n--;
		}
		s++;
	}
	*d = '\0';

	return(dlen + (s - src));	/* count does not include NUL */
}
