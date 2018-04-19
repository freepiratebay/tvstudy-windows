//
//  parser.h
//  TVStudy
//
//  Copyright (c) 2016-2017 Hammett & Edison, Inc.  All rights reserved.


typedef struct nameval {
  char *name;
  char *value;
  struct nameval *next;
} CONFIG_NAMEVAL;

typedef struct section {
  char *name;
  CONFIG_NAMEVAL *nvlist;
  struct section *next;
} CONFIG_SECTION;

#define COMMENT_PREFIX_CHAR '#'

int fgetnl(char *buf, int siz, FILE *stream);
int fgetnlc(char *buf, int siz, FILE *stream, int *lnum);

char *next_token(char *ptr);
int token_atoi(char *s, int lo, int hi, int *i);
int token_atof(char *s, double lo, double hi, double *d);

int config_loadfile(char *filename);
CONFIG_SECTION *config_getsection(char *sectionname);
char *config_getvalue(char *name);
char *config_getsectionvalue(char *sectionname, char *name);
void config_free();

int trim_string(char *s);
