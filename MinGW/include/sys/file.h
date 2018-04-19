#ifndef _FILE_H
#define _FILE_H
/*
 * This file is part of the Mingw32 package.
 *
 * This file.h maps to the root fcntl.h
 * TODO?
 */
#include <fcntl.h>
#include "flock.h"

#define mkdir(path,mode) _mkdir(path)

#endif