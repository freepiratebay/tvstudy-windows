//
//  landcover.h
//  TVStudy
//
//  Copyright (c) 2016 Hammett & Edison, Inc.  All rights reserved.


#define LANDCOVER_UNKNOWN                       -1
#define LANDCOVER_OPEN_WATER                     0
#define LANDCOVER_PERENNIAL_ICE_SNOW             1
#define LANDCOVER_DEVELOPED_OPEN_SPACE           2
#define LANDCOVER_DEVELOPED_LOW_INTENSITY        3
#define LANDCOVER_DEVELOPED_MEDIUM_INTENSITY     4
#define LANDCOVER_DEVELOPED_HIGH_INTENSITY       5
#define LANDCOVER_BARREN_LAND_ROCK_SAND_CLAY     6
#define LANDCOVER_DECIDUOUS_FOREST               7
#define LANDCOVER_EVERGREEN_FOREST               8
#define LANDCOVER_MIXED_FOREST                   9
#define LANDCOVER_SHRUB_SCRUB                   10
#define LANDCOVER_GRASSLAND_HERBACEOUS          11
#define LANDCOVER_PASTURE_HAY                   12
#define LANDCOVER_CULTIVATED_CROPS              13
#define LANDCOVER_WOODY_WETLANDS                14
#define LANDCOVER_EMERGENT_HERBACEOUS_WETLANDS  15

#define LC_DB_NAME_NLCD2006  "nlcd"
#define LC_DB_NAME_NLCD2011  "nlcd2011"

#define LC_GRID_X0  -2493045.   // Projected location of top-left corner of data grid.
#define LC_GRID_Y0   3310005.

#define LC_GRID_RES  30.   // Grid resolution.

#define LC_TILE_SIZE_X  3584   // Width and height of individual data tiles.
#define LC_TILE_SIZE_Y  3072

#define LC_TILE_NX  45   // Column and row count of tiles.
#define LC_TILE_NY  34

int land_cover(double lat, double lon, int dbVersion);
const char *land_cover_string(double lat, double lon, int dbVersion);
