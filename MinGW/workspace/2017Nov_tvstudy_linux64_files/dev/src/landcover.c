//
//  landcover.c
//  TVStudy
//
//  Copyright (c) 2012-2016 Hammett & Edison, Inc.  All rights reserved.


// Functions related to determining land-cover categorization at geographic coordinates, using NLCD data.


#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <math.h>

#include "global.h"
#include "landcover.h"
#include "memory.h"


//---------------------------------------------------------------------------------------------------------------------

static int aea_project(double lat, double lon, double *x, double *y);
static int8_t *get_tile(int xTileIndex, int yTileIndex, int dbVersion);


//---------------------------------------------------------------------------------------------------------------------
// Retrieve land cover category code at given coordinates.  This is based on NLCD data, however codes have been
// re-assigned for data efficiency as detailed below.  Only CONUS data was used so the Alaska-only codes were not
// assigned since they never appear in the actual data.

//   NLCD                                 New
//   code  Category                       code

//    11   Open Water                       0
//    12   Perennial Ice/Snow               1
//    21   Developed, Open Space            2
//    22   Developed, Low Intensity         3
//    23   Developed, Medium Intensity      4
//    24   Developed, High Intensity        5
//    31   Barren Land (Rock/Sand/Clay)     6
//    41   Deciduous Forest                 7
//    42   Evergreen Forest                 8
//    43   Mixed Forest                     9
//    51   Dwarf Scrub (Alaska only)        -
//    52   Shrub/Scrub                     10
//    71   Grassland/Herbaceous            11
//    72   Sedge/Herbaceous (Alaska only)   -
//    73   Lichens (Alaska only)            -
//    74   Moss (Alaska only)               -
//    81   Pasture/Hay                     12
//    82   Cultivated Crops                13
//    90   Woody Wetlands                  14
//    95   Emergent Herbaceous Wetlands    15

// Arguments:

//   lat, lon   Latitude and longitude, degrees positive north and west, NAD83.
//   dbVersion  Database version, 2006 or 2011.

// Return is the land-cover category code, -1 if the point is outside the data.  No error return.

int land_cover(double lat, double lon, int dbVersion) {

	double x, y;

	if (aea_project(lat, lon, &x, &y)) {
		return LANDCOVER_UNKNOWN;
	}

	int xIndex = (int)rint((x - LC_GRID_X0) / LC_GRID_RES);
	if (xIndex < 0) {
		return LANDCOVER_UNKNOWN;
	}
	int xTileIndex = xIndex / LC_TILE_SIZE_X;
	if (xTileIndex >= LC_TILE_NX) {
		return LANDCOVER_UNKNOWN;
	}
	xIndex %= LC_TILE_SIZE_X;

	int yIndex = (int)rint((LC_GRID_Y0 - y) / LC_GRID_RES);
	if (yIndex < 0) {
		return LANDCOVER_UNKNOWN;
	}
	int yTileIndex = yIndex / LC_TILE_SIZE_Y;
	if (yTileIndex >= LC_TILE_NY) {
		return LANDCOVER_UNKNOWN;
	}
	yIndex %= LC_TILE_SIZE_Y;

	int8_t *tileData = get_tile(xTileIndex, yTileIndex, dbVersion);
	if (!tileData) {
		return LANDCOVER_UNKNOWN;
	}

	return (int)tileData[(yIndex * LC_TILE_SIZE_X) + xIndex];
}


//---------------------------------------------------------------------------------------------------------------------
// Return the category as a string description.

const char *land_cover_string(double lat, double lon, int dbVersion) {

	switch (land_cover(lat, lon, dbVersion)) {
		default:
			return "(unknown)";
		case LANDCOVER_OPEN_WATER:
			return "Open Water";
		case LANDCOVER_PERENNIAL_ICE_SNOW:
			return "Perennial Ice/Snow";
		case LANDCOVER_DEVELOPED_OPEN_SPACE:
			return "Developed, Open Space";
		case LANDCOVER_DEVELOPED_LOW_INTENSITY:
			return "Developed, Low Intensity";
		case LANDCOVER_DEVELOPED_MEDIUM_INTENSITY:
			return "Developed, Medium Intensity";
		case LANDCOVER_DEVELOPED_HIGH_INTENSITY:
			return "Developed, High Intensity";
		case LANDCOVER_BARREN_LAND_ROCK_SAND_CLAY:
			return "Barren Land (Rock/Sand/Clay)";
		case LANDCOVER_DECIDUOUS_FOREST:
			return "Deciduous Forest";
		case LANDCOVER_EVERGREEN_FOREST:
			return "Evergreen Forest";
		case LANDCOVER_MIXED_FOREST:
			return "Mixed Forest";
		case LANDCOVER_SHRUB_SCRUB:
			return "Shrub/Scrub";
		case LANDCOVER_GRASSLAND_HERBACEOUS:
			return "Grassland/Herbaceous";
		case LANDCOVER_PASTURE_HAY:
			return "Pasture/Hay";
		case LANDCOVER_CULTIVATED_CROPS:
			return "Cultivated Crops";
		case LANDCOVER_WOODY_WETLANDS:
			return "Woody Wetlands";
		case LANDCOVER_EMERGENT_HERBACEOUS_WETLANDS:
			return "Emergent Herbaceous Wetlands";
	}
}


//---------------------------------------------------------------------------------------------------------------------
// Determine X-Y coordinates for point lookup in the NLCD database.  This is an Albers equal-area conic projection
// using fixed parameters.

// Arguments:

//   lat, lon   Latitude and longitude, NAD83 degrees positive north and west.
//   x, y       Projected coordinates in meters.

// Return is 0 for success, -1 on error.

#define PROJ_A   6378137.     // Major axis and flattening for the GRS80 ellipsoid.
#define PROJ_RF  298.257222

#define PROJ_PHI1  45.5   // Standard parallels for projection.
#define PROJ_PHI2  29.5
#define PROJ_LAM0  96.    // Projection origin.
#define PROJ_PHI0  23.

static int aea_project(double lat, double lon, double *x, double *y) {

	static int proj_init = 1;
	static double proj_e, proj_es, proj_n, proj_c, proj_rho0;

	double lam, phi, sinphi, con, ml, rho;

	// Initialize the projection on the first call.

	if (proj_init) {

		double f = 1. / PROJ_RF;
		proj_es = f * (2. - f);
		proj_e = sqrt(proj_es);

		phi = PROJ_PHI1 * DEGREES_TO_RADIANS;
		sinphi = sin(phi);
		double m1 = cos(phi) / sqrt(1. - (proj_es * sinphi * sinphi));
		con = proj_e * sinphi;
		double ml1 = (1. - proj_es) * ((sinphi / (1. - (con * con))) - ((.5 / proj_e) * log((1. - con) / (1. + con))));

		phi = PROJ_PHI2 * DEGREES_TO_RADIANS;
		sinphi = sin(phi);
		double m2 = cos(phi) / sqrt(1. - (proj_es * sinphi * sinphi));
		con = proj_e * sinphi;
		double ml2 = (1. - proj_es) * ((sinphi / (1. - (con * con))) - ((.5 / proj_e) * log((1. - con) / (1. + con))));

		proj_n = ((m1 * m1) - (m2 * m2)) / (ml2 - ml1);

		proj_c = (m1 * m1) + (proj_n * ml1);

		phi = PROJ_PHI0 * DEGREES_TO_RADIANS;
		sinphi = sin(phi);
		con = proj_e * sinphi;
		ml = (1. - proj_es) * ((sinphi / (1. - (con * con))) - ((.5 / proj_e) * log((1. - con) / (1. + con))));
		proj_rho0 = sqrt(proj_c - (proj_n * ml)) / proj_n;

		proj_init = 0;
	}

	lam = ((PROJ_LAM0 - lon) * DEGREES_TO_RADIANS) * proj_n;
	phi = lat * DEGREES_TO_RADIANS;

	sinphi = sin(phi);
	con = proj_e * sinphi;
	ml = (1. - proj_es) * ((sinphi / (1. - (con * con))) - ((.5 / proj_e) * log((1. - con) / (1. + con))));
	rho = proj_c - (proj_n * ml);
	if (rho < 0.) {
		*x = 0.;
		*y = 0.;
		return -1;
	}
	rho = sqrt(rho) / proj_n;

	*x = (rho * sin(lam)) * PROJ_A;
	*y = (proj_rho0 - (rho * cos(lam))) * PROJ_A;

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Retrieve a data tile from the NLCD database.  The original database is a full raster image of projected data points
// covering all of CONUS.  That grid was sub-divided into fixed-size tiles and each stored in a separate file using a
// run-length compression, using re-assigned category codes (see above).  This returns a single raster tile.  The data
// points are either the new category code, or -1 for no-data.  If a tile does not exist or an error occurs, NULL is
// returned.  A fixed number of recently-used tiles are cached.

// Arguments:

//   xTileIndex, yTileIndex   Tile index coordinates.
//   dbVersion                Data version, 2006 or 2011.

// Return is tile data, or NULL if not found or error occurred.

typedef struct {    // Structure for caching tiles.
	int ageCount;   // Age count, zeroed when used, incremented when not, when cache is full the max age is replaced.
	int xIndex;     // Tile coordinates.
	int yIndex;
	int8_t *data;   // Tile data.
} TILE;

#define TILE_CACHE_SIZE  30

static int8_t *get_tile(int xTileIndex, int yTileIndex, int dbVersion) {

	static TILE tileCache[TILE_CACHE_SIZE];
	static u_int8_t *dataBuffer;
	static char tilePath[MAX_STRING];
	static int tileMap[LC_TILE_NX][LC_TILE_NY], cacheInit = 1, initForVersion = 0;

	int i, j, xIndex, yIndex;

	// Initialize on the first call.

	if (cacheInit) {

		for (i = 0; i < TILE_CACHE_SIZE; i++) {
			tileCache[i].data = NULL;
		}

		dataBuffer = (u_int8_t *)mem_alloc(LC_TILE_SIZE_X * LC_TILE_SIZE_Y);

		cacheInit = 0;
	}

	// Initialize when the database version changes, clear the cache and tile indexes.

	if (initForVersion != dbVersion) {

		for (i = 0; i < TILE_CACHE_SIZE; i++) {
			tileCache[i].ageCount = 0;
			tileCache[i].xIndex = -1;
			tileCache[i].yIndex = -1;
		}

		for (xIndex = 0; xIndex < LC_TILE_NX; xIndex++) {
			for (yIndex = 0; yIndex < LC_TILE_NY; yIndex++) {
				tileMap[xIndex][yIndex] = -1;
			}
		}

		switch (dbVersion) {
			case 2006:
			default:
				snprintf(tilePath, MAX_STRING, "%s/%s", DBASE_DIRECTORY_NAME, LC_DB_NAME_NLCD2006);
				break;
			case 2011:
				snprintf(tilePath, MAX_STRING, "%s/%s", DBASE_DIRECTORY_NAME, LC_DB_NAME_NLCD2011);
				break;
		}

		initForVersion = dbVersion;
	}

	// An index map is maintained so missing files don't have to be checked repeatedly.  A map value of -1 means the
	// tile has never been checked, 0 means it has been checked and doesn't exist or contents are invalid, 1 means it
	// exists and has valid data so it may be in the cache.

	switch (tileMap[xTileIndex][yTileIndex]) {

		case -1:
			break;

		case 0:
			return NULL;

		case 1:
			for (i = 0; i < TILE_CACHE_SIZE; i++) {
				if ((xTileIndex == tileCache[i].xIndex) && (yTileIndex == tileCache[i].yIndex)) {
					tileCache[i].ageCount = -1;
					for (j = 0; j < TILE_CACHE_SIZE; j++) {
						if (tileCache[j].xIndex >= 0) {
							tileCache[j].ageCount++;
						}
					}
					return tileCache[i].data;
				}
			}
			break;
	}

	// Tile may exist and isn't cached, open the file.

	char tileName[MAX_STRING], fileName[MAX_STRING];
	snprintf(tileName, MAX_STRING, "x%02dy%02d.tile", xTileIndex, yTileIndex);
	snprintf(fileName, MAX_STRING, "%s/%s", tilePath, tileName);
	FILE *tileFile = fopen(fileName, "rb");
	if (NULL == tileFile) {
		tileMap[xTileIndex][yTileIndex] = 0;
		return NULL;
	}

	// Files have a header that is just the file name repeated.

	char checkName[MAX_STRING];
	size_t readSize = strlen(tileName);
	if ((fread(checkName, 1, readSize, tileFile) != readSize) || memcmp(checkName, tileName, readSize)) {
		fclose(tileFile);
		tileMap[xTileIndex][yTileIndex] = 0;
		return NULL;
	}

	// Read to EOF.

	readSize = fread(dataBuffer, 1, (LC_TILE_SIZE_X * LC_TILE_SIZE_Y), tileFile);
	fclose(tileFile);

	// Find an available cache slot, use an empty slot, or the one with the largest age counter.

	int maxCount = 0, cacheIndex = 0;
	for (i = 0; i < TILE_CACHE_SIZE; i++) {
		if (tileCache[i].xIndex < 0) {
			cacheIndex = i;
			break;
		}
		if (tileCache[i].ageCount > maxCount) {
			maxCount = tileCache[i].ageCount;
			cacheIndex = i;
		}
	}

	// Clear the cache slot in case an error occurs.

	tileCache[cacheIndex].ageCount = 0;
	tileCache[cacheIndex].xIndex = -1;
	tileCache[cacheIndex].yIndex = -1;

	int8_t *tileData = tileCache[cacheIndex].data;
	if (!tileData) {
		tileData = (int8_t *)mem_alloc(LC_TILE_SIZE_X * LC_TILE_SIZE_Y);
		tileCache[cacheIndex].data = tileData;
	}

	// Expand the data, it is a byte stream using run-length compression.  If the high bit is set the byte represents a
	// run of missing-data points, all lower bits are the run count minus one.  If the high bit is clear it is a run of
	// data values, the next three bits are the run count minus one, the lower bits are the data value.

	int in = 0, inSize = readSize, out = 0, outSize = LC_TILE_SIZE_X * LC_TILE_SIZE_Y, runCount;
	u_int8_t byte;
	int8_t value;

	while ((in < inSize) && (out < outSize)) {

		byte = dataBuffer[in++];

		if (byte & 0x80) {

			runCount = (int)(byte & 0x7f) + 1;
			value = -1;

		} else {

			runCount = (int)((byte & 0x70) >> 4) + 1;
			value = (int8_t)(byte & 0x0f);
		}

		for (i = 0; (i < runCount) && (out < outSize); i++, out++) {
			tileData[out] = value;
		}
	}

	if ((in < inSize) || (out < outSize)) {
		tileMap[xTileIndex][yTileIndex] = 0;
		return NULL;
	}

	tileCache[cacheIndex].xIndex = xTileIndex;
	tileCache[cacheIndex].yIndex = yTileIndex;

	tileMap[xTileIndex][yTileIndex] = 1;

	return tileData;
}
