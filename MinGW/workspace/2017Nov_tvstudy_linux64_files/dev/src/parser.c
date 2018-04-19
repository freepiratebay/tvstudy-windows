//
//  parser.c
//  TVStudy
//
//  Copyright (c) 2016-2017 Hammett & Edison, Inc.  All rights reserved.


// Functions related to reading from files and parsing lines.  Includes API for reading configuration files in the
// format [section] .. name=value ..


#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <ctype.h>
#include <math.h>

#include "global.h"
#include "parser.h"
#include "memory.h"


//---------------------------------------------------------------------------------------------------------------------
// Global variables.

static CONFIG_SECTION *config_head;
static CONFIG_SECTION *config_current;


//---------------------------------------------------------------------------------------------------------------------
// Prototypes.

static int do_line(char *line);
static int do_line_section(char *line);
static int do_line_nameval(char *line);


//---------------------------------------------------------------------------------------------------------------------
// Replacement for fgets().  Read always continues to the next line-end or EOF, if the return buffer is too short the
// line is truncated.  A single newline, single carriage return, or sequence of carriage return then linefeed, are all
// recognized as line end.  The line-end characters are discarded.  An implied line end occurs at EOF if any characters
// follow the last actual line end, in other words EOF is returned only if no characters are read.  Note the cr-lf
// support does a read-ahead so this is not suitable for blocking streams such as tty input.

// Arguments:

//   buf     Return buffer, always terminated.
//   siz     Size of buffer including terminator.
//   stream  Input stream.

// Return the number of characters read not including the line end, if >siz truncation occurred; return -1 if at EOF.

int fgetnl(char *buf, int siz, FILE *stream) {

	int len = siz - 1, i = 0, n = 0, c, c1;
	char chr;

	while (((chr = (char)(c = getc(stream))) != '\n') && (chr != '\r') && (c != EOF)) {
		if (i < len) {
			buf[i++] = chr;
		}
		n++;
	}

	if (n || (c != EOF)) {
		buf[i] = '\0';
		if ('\r' == chr) {
			if (((chr = (char)(c1 = getc(stream))) != '\n') && (c1 != EOF)) {
				ungetc(c1, stream);
			}
		}
		return n;
	}

	return -1;
}


//---------------------------------------------------------------------------------------------------------------------
// Wrapper around fgetnl() that skips over comments, those are lines beginning with the COMMENT_PREFIX_CHAR character.
// Optionally this can update a line counter passed by reference.

// Arguments:

//   buf     See fgetnl().
//   siz     "
//   stream  "
//   lnum    Pointer to line counter, if non-NULL this is incremented for each line read including comments.

// Return see fgetnl().

int fgetnlc(char *buf, int siz, FILE *stream, int *lnum) {

	int n;

	do {
		n = fgetnl(buf, siz, stream);
		if (lnum && (n >= 0)) {
			(*lnum)++;
		}
	} while ((n >= 0) && (COMMENT_PREFIX_CHAR == buf[0]));

	return n;
}


//---------------------------------------------------------------------------------------------------------------------
// Incremental CSV input line tokenizer.  Call with a non-NULL argument starts processing a new line which is copied to
// static, subsequent calls with a NULL argument continue parsing that line.  Each call scans for the next comma and
// returns a pointer to the token it delimits (points into the static line copy, nulls are written over the commas in
// that string).  Returns NULL if the next token is empty or the end of line has been reached.  Leading and trailing
// white-space is stripped, a token that is entirely white-space is empty.

// Arguments:

//   ptr  Pointer to start a new line, NULL to continue.

// Return is the next token string or NULL for an empty token.

char *next_token(char *ptr) {

	static char buf[MAX_STRING], *pos = NULL;

	// Check for new string, copy and initialize.

	if (ptr) {
		lcpystr(buf, ptr, MAX_STRING);
		pos = buf;
	}

	if (pos == NULL) {
		return NULL;
	}

	// Search for start of token, check for separator indicating a null token.

	char *tok = pos;
	while (isspace(*tok)) {
		tok++;
	}
	if ((*tok == ',') || (*tok == '\0')) {
		pos = tok;
		if (*pos == ',') {
			pos++;
		}
		return NULL;
	}

	// The token is not null; scan for the separator.

	char *sep = tok + 1;
	while ((*sep != ',') && (*sep != '\0')) {
		sep++;
	}
	pos = sep;
	if (*pos == ',') {
		pos++;
	}

	// Scan back over trailing white-space, write terminator, return.

	do {
		sep--;
	} while (isspace(*sep));
	*(sep + 1) = '\0';
	return tok;
}


//---------------------------------------------------------------------------------------------------------------------
// Enhanced atoi() function, does checks to confirm the string being convereted actually contains a properly-formatted
// number and also applies a range check to the value.  This assumes the argument string has leading and trailing white
// space stripped and is never an empty string, in other words it's assuming a value returned by next_token().  This
// uses strtod() for the string conversion and if the terminating character returned is not a null that is an error.

// Arguments:

//   s   String to convert, if NULL an error is returned.
//   lo  Low limit, return error if value is less.
//   hi  High limit, return error if value is greater.
//   i   Return value by reference.

// Return is 0 for success, non-zero for any error.

int token_atoi(char *s, int lo, int hi, int *i) {

	if (!s) {
		return 1;
	}

	char *t;

	double d = strtod(s, &t);
	if (*t != '\0') {
		return 1;
	}

	int ii = (int)rint(d);
	if ((ii < lo) || (ii > hi)) {
		return 1;
	}

	*i = ii;
	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Enhanced atof() function, see comments for token_atoi() above.

// Arguments:

//   s   String to convert, if NULL an error is returned.
//   lo  Low limit, return error if value is less.
//   hi  High limit, return error if value is greater.
//   i   Return value by reference.

// Return is 0 for success, non-zero for any error.

int token_atof(char *s, double lo, double hi, double *d) {

	if (!s) {
		return 1;
	}

	char *t;

	double dd = strtod(s, &t);
	if (*t != '\0') {
		return 1;
	}

	if ((dd < lo) || (dd > hi)) {
		return 1;
	}

	*d = dd;
	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Open and read a configuration file in a name=value format grouped by [section] lines.  The content of the file is
// fully parsed into internal state for use by the access functions.  Loading another file replaces existing state.

// Arguments:

//   filename  Name of the config file to read.

// Return non-0 on error, the return value is the line number where error occurred or -1 if file open failed.  After
// an error the config may still be partially loaded, up to the point of error.

int config_loadfile(char *filename) {

	config_free();

	FILE *in = fopen(filename, "r");
	if (!in) return -1;

	char line[MAX_STRING];
	int linenumber = 0;

	// Ignore blank lines.

	while (fgetnl(line, MAX_STRING, in) >= 0) {
		linenumber++;
		if (trim_string(line)) {
			if (do_line(line)) {
				fclose(in);
				return linenumber;
			}
		}
	}

	fclose(in);
	return 0;
}

// Process a non-empty line from the file.  Return non-zero on error.  Ignore comment lines starting with ;

static int do_line(char *line) {

    if (';' == line[0]) return 0;

	// If starting '[' is section, else nameval.

	if ('[' == line[0]) {
		return do_line_section(line);
	} else {
		return do_line_nameval(line);
	}
}

// Found [section].  Return non-0 on error.  If line doesn't end with ']', error.

static int do_line_section(char *line) {

	char *name = line + 1;
	int namelen = strlen(name);
	if (']' != name[namelen - 1]) return 1;
	name[namelen - 1] = '\0';

	// If the section name is empty string, error.

	namelen = trim_string(name);
	if (0 == namelen) return 1;

	// Check if a section by the same name already exists, if so just add to that.

	CONFIG_SECTION **link = &config_head;
	config_current = config_head;
	while (config_current) {
		if (!strcmp(name, config_current->name)) return 0;
		link = &(config_current->next);
		config_current = config_current->next;
	}

	// Create a new section.

	config_current = (CONFIG_SECTION *)mem_alloc(sizeof(CONFIG_SECTION));
	*link = config_current;
	config_current->next = NULL;

	config_current->name = (char *)mem_alloc(namelen + 1);
	strncpy(config_current->name, name, namelen);
	config_current->name[namelen] = '\0';

	config_current->nvlist = NULL; 

	return 0;
}

// Found name=value, this is added in the current section.  Return non-0 on error.  If no current section, error.

static int do_line_nameval(char *line) {

	if (config_current == NULL) return 1;

	// Find the = in name=value, if not there, error.

	int len = strlen(line), p = 0;
	for (p = 0; p < len; p++) {
		if ('=' == line[p]) {
			break;
		}
	}
    if (p >= len) return 1;
	line[p] = '\0';

	// Split line into name and value, empty string for either is an error.

	char *name = line;
	int namelen = trim_string(name);
	if (0 == namelen) return 1;

	char *value = line + p + 1;
	int valuelen = trim_string(value);
	if (0 == valuelen) return 1;

	// Scan the names in the section, if already present replace value, else add new.

	CONFIG_NAMEVAL **link = &(config_current->nvlist);
	CONFIG_NAMEVAL *nv = config_current->nvlist;
	while (nv) {
		if (!strcmp(name, nv->name)) break;
		link = &(nv->next);
		nv = nv->next;
	}

	if (!nv) {

		nv = (CONFIG_NAMEVAL *)mem_alloc(sizeof(CONFIG_NAMEVAL));
		*link = nv;
		nv->next = NULL;

		nv->name = (char *)mem_alloc(namelen + 1);
		strncpy(nv->name, name, namelen);
		nv->name[namelen] = '\0';

		nv->value = NULL;
	}

	nv->value = (char *)mem_realloc(nv->value, valuelen + 1);
	strncpy(nv->value, value, valuelen);
	nv->value[valuelen] = '\0';

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Searches through all sections for matching sectionname, sets as current for subsequent config_getvalue() calls.

// Arguments:

//   sectionname  Name of the section to find, match is literal.

// Returns pointer to the section, or NULL if not found.

CONFIG_SECTION *config_getsection(char *sectionname) {

	config_current = config_head;
	while (config_current) {
		if (!strcmp(config_current->name, sectionname)) return config_current;
		config_current = config_current->next;
	}

	return NULL;
}


//---------------------------------------------------------------------------------------------------------------------
// Get a value in the current section, must be preceded by a config_getsection() call to set the section.

// Arguments:

//   name  Name to find.

// Return is the value, or NULL if not found/no section.

char *config_getvalue(char *name) {

	if (!config_current) return NULL;

	CONFIG_NAMEVAL *currnv = config_current->nvlist;
	while (currnv) {
		if (!strcmp(name, currnv->name)) return currnv->value;
		currnv = currnv->next;
	}

	return NULL;
}


//---------------------------------------------------------------------------------------------------------------------
// Get by section and name.  Optimized a bit to check if the current section is already the one needed.

// Arguments:

//   sectionname  Name of the section.
//   name         Name of the value.

// Returns value string or NULL if not found.

char *config_getsectionvalue(char *sectionname, char *name) {

	if (!config_current || strcmp(config_current->name, sectionname)) {
		if (!config_getsection(sectionname)) {
			return NULL;
		}
	}
	return config_getvalue(name);
}


//---------------------------------------------------------------------------------------------------------------------
// Release any currently-loaded configuration file data.

void config_free() {	

	CONFIG_NAMEVAL *currentnv;
	CONFIG_NAMEVAL *nextnv;
	CONFIG_SECTION *nextsection;

	config_current = config_head;
	while (config_current) {

		currentnv = config_current->nvlist;
		while (currentnv) {
			nextnv = currentnv->next;				
			mem_free(currentnv->value);
			mem_free(currentnv->name);
			mem_free(currentnv);
			currentnv = nextnv;
		}

		nextsection = config_current->next;
		mem_free(config_current->name);
		mem_free(config_current);
		config_current = nextsection;
	}

	config_head = NULL;
}


//---------------------------------------------------------------------------------------------------------------------
// Trim leading and trailing whitespace from a character string, modify in place.

// Arguments:

//   s  The string to trim.

// Return the final string length.

int trim_string(char *s) {

	int len = strlen(s);

	int f = 0;
	while ((f < len) && isspace(s[f])) f++;

	if (f == len) {
		s[0] = '\0';
		return 0;
	}

	int l = len - 1;
	while ((l > 0) && isspace(s[l])) l--;

	if (f > 0) {
		int j;
		for (j = f; j <= l; j++) {
			s[j - f] = s[j];
		}
		l -= f;
	}

	len = l + 1;
	s[len] = '\0';

	return len;
}
