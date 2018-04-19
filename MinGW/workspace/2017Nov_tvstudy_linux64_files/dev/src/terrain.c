//
//  terrain.c
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.


// Functions for extracting terrain elevations from the terrain databases.  Available databases:

//   NED 1/3-second U.S.
//   NED 1- and 2-second U.S.
//   CDED 0.75-second Canada
//   CEM 1-second Mexico
//   USGS 3-second U.S.
//   SRTM 3-second Canada and Mexico
//   GTOPO 30-second global

// The GTOPO database is 30-second data with global coverage.  USGS is 3-second data covering the continental U.S.,
// Alaska, Hawaii, and Puerto Rico, this is the older USGS data derived from DMA sources.  SRTM is 3-second data and is
// included for Canada and Mexico in areas outside the USGS database coverage for roughly 5 degrees beyond the borders,
// up to the limit of SRTM coverage at 60 degrees of latitude.  However SRTM coverage is spotty in Canada due to data
// gaps.  NED is 2-second in Alaska and 1-second in other areas, covering Alaska, the continental U.S. extending well
// into Canada and Mexico, and also covering Hawaii and Puerto Rico.  CDED is nominally 0.75-second data covering
// Canada, but the longitude spacing rises to 1.5 seconds above 68 degrees latitude, and 3 seconds above 80 degrees
// latitude.  CEM is 1-second data covering Mexico.

// NED 1/3-second data is now supported, however the data set is not distributed with the application.  A conversion
// utility is provided to convert downloaded blocks in GridFloat format and install them in the database directory.
// No index file is present in that directory, the index will be initialized to "unknown" for all blocks so a file open
// will be attempted for any access and the index updated accordingly on the fly.

// Lookup requests can never fail, lookup will fall back to lower-resolution data as needed to complete any request,
// ultimately arriving at GTOPO 30-second if needed, which has 100% global coverage.  Requesting 30-second will use
// GTOPO data only.  Requesting 3-second will start with USGS, if data is not found there SRTM is checked, and finally
// GTOPO.  Requesting 1-second will check all databases in the order CDED, CEM, NED, USGS, SRTM, and GTOPO.  Due to the
// overlap of NED into Canada and Mexico, if that database were preferred it would be used for most border-area lookups
// even in foreign territory.  CDED and CEM have very little overlap into U.S. territory, so preferring those over NED
// results in the foreign data source being used in foreign territory, and NED in U.S. territory.  Requesting 1/3-
// second adds NED13 ahead of CDED then proceeds as for 1-second.  Fall back is at the cell level; a cell covers 7.5
// minutes of latitude and longitude.

// The files for all databases are stored in a standard format.  Each file covers a geographic area of 1x1 degrees,
// within each file are a matrix of cell records, each cell covers 7.5x7.5 minutes.  Each cell contains a matrix of
// elevation points on a regular grid.  The number of points on the latitude and longitude axes can vary between cells,
// and latitude and longitude point counts do not have to be the same in a cell.  The origin of all matrices is always
// at the south-east corner, order within a serialized matrix is row-major east-to-west then south-to-north.

// The grid in each cell covers the entire cell including the edges, adjacent cells overlap by one row and column so
// interpolation can always be performed without having to retrieve a neighboring cell.  However there are two
// fundamentally different types of data.  Some databases have values that are samples at the intersection of the grid
// lines forming the matrix, others have "pixel" data where each point is a sample value centered in the grid cell.
// For grid data overlap is accomplished by an extra row and column only on the north and west edges; for pixel data,
// overlap must include the south and east edges as well.

// Individual elevations are signed integers in units of meters.  Data for cells in a file can be stored uncompressed
// (16 bits per value) or compressed.  The storage format can vary from cell to cell within a file.  Currently only one
// compression method is implemented, that is converting all values in a cell to positive deltas above the minimum
// elevation in the cell and then bit-packing those delta values with 1-15 bits per point.  Cells may also be
// "zero-delta" meaning the entire cell is a uniform elevation, those cells do not have a data record, the cell header
// provides a complete description.

// NOTE: All databases are processed as if they use coordinates in the WGS84 coordinate system, i.e. NAD83.  Coordinate
// values passed to all functions should be WGS84/NAD83.  That is correct for NED, CDED, CEM, SRTM, and GTOPO.  The
// USGS database was actually produced using the WGS72 coordinate system; however for consistency with legacy software
// that assumed WGS84 for the USGS data, no correction is made here.  The resulting positional error is insignificant
// given the low accuracy of the original data used to produce that database.


#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <math.h>
#include <time.h>
#include <fcntl.h>
#include <sys/stat.h>
#ifdef __BUILD_LINUX
#include <sys/sysctl.h>
#else
#include <sys/sysctl.h>
#endif

#include "global.h"
#include "terrain.h"
#include "memory.h"


//---------------------------------------------------------------------------------------------------------------------
// See header.

time_t UserTerrainVersion = 0;
int UserTerrainRequested = 0;
int UserTerrainUsed = 0;


//---------------------------------------------------------------------------------------------------------------------

#define SEGMENT_LENGTH_KILOMETERS 16.   // Length of a segment in the profile routine, see below.

typedef struct tcel {         // One cached terrain data cell.
	short databaseIndex;      // Database index.
	short cellLatIndex;       // Global cell index coordinates.
	short cellLonIndex;
	short latPointCount;      // Number of latitude points (rows) in cell point grid.
	short lonPointCount;      // Number of longitude points (columns) in grid.
	short gridOffset;         // 0 for grid-intersection data, 1 for cell-center data.
	short cellElevation;      // Cell minimum elevation, for all points when latPointCount and lonPointCount == 1.
	short *cellData;          // Elevation data matrix, or NULL.
	struct tcel *cachePrev;   // For caching linked lists.
	struct tcel *cacheNext;
	struct tcel *indexPrev;
	struct tcel *indexNext;
	size_t cacheSize;         // Size of data assumed when cached, if this needs to change use update_cache_size().
} TCELL;

static void check_memory_size();
static void coord(double latStart, double lonStart, double bearing, double arcLength, double *latEnd, double *lonEnd);
static int load_cell(short databaseIndex, short cellLatIndex, short cellLonIndex, TCELL **cell);
static void unpack(u_int8_t *cellDataBuffer, int pointCount, int bitCount, short minElevation, short *cellData);
static int cache_init();
static int get_tcell(short databaseIndex, short cellLatIndex, short cellLonIndex, TCELL **cell);
static void update_cache_size(TCELL *cell, size_t newSize);
static void release_cell(TCELL *cell);
static TCELL *cell_map_get(short databaseIndex, short cellLatIndex, short cellLonIndex);
static void cell_map_put(TCELL *cell);
static void cell_map_remove(TCELL *cell);
static void update_cache_stats(int databaseIndex, int statsIndex);

#define TRN_DB0_INDEX   0   // Starting index for searching databases, all are checked from this to end.
#define TRN_DB1_INDEX   1
#define TRN_DB3_INDEX   4
#define TRN_DB30_INDEX  6

#define N_DATABASES  7

static int DbNumber[N_DATABASES] = {   // Database numbers in the order they will be searched.
    TRN_DB_NUMBER_NED13,
	TRN_DB_NUMBER_CDED,
	TRN_DB_NUMBER_CEM,
	TRN_DB_NUMBER_NED,
	TRN_DB_NUMBER_USGS,
	TRN_DB_NUMBER_SRTM,
	TRN_DB_NUMBER_GTOPO
};

static char *DbName[N_DATABASES] = {   // Database directory names in search order.
	TRN_DB_NAME_NED13,
	TRN_DB_NAME_CDED,
	TRN_DB_NAME_CEM,
	TRN_DB_NAME_NED,
	TRN_DB_NAME_USGS,
	TRN_DB_NAME_SRTM,
	TRN_DB_NAME_GTOPO
};

static FILE *StatsOut = NULL;   // If set by calling report_terrain_stats(), stats are maintained and logged.

#define STATS_TOTAL     0   // Constants used with update_cache_stats().
#define STATS_SEAWATER  1
#define STATS_CACHED    2
#define STATS_LOADED    3

#define STATS_COUNT  4

#define STATS_REPORT_INTERVAL  15


//---------------------------------------------------------------------------------------------------------------------
// Initialize for terrain extraction, load database block indexes and initialize the caching system.  This will be
// called indirectly from other functions, however if client code needs the user-generated block index timestamp or
// needs to change the memory allocation this must be called early, before any other function.  Once the cache is
// initialized the size cannot be changed, and other automatic calls set a fraction of 1.

// The fraction argument is >1 to reduce memory use.  The cache size is set to the total memory available less an
// amount for other memory use in the engine, the UI application, and the OS.  That amount is half of all memory but
// no more than a fixed maximum.  The actual limit for this process is simply that total divided by the argument.  If
// the resulting size is below a fixed minimum this will return an error of -1 in which case the caller should clean
// up and exit the process because all attempts to use terrain extraction later will fail.

// The cache is also limited to a total number of cached cells regardless of size.  That limit is computed using a
// "target" cell data size, roughly what is needed for 1-second terrain.  That limit is also used to control how study
// grids are segmented, see get_max_grid_count().  If data finer than 1-second is being used the cache may not hold
// an entire study grid area because the size limit will dominate, but the cache is still reasonably effective.

// Arguments:

//   fraction  Divisor of fraction of memory to use, aka total number of processes sharing memory.

// Return is 0 if all is well, -1 if there is insufficient memory for terrain caching.

#define MAX_RESERVED_SIZE 4000000000L
#define MIN_CACHE_SIZE    1500000000L
#define TARGET_CELL_SIZE  700000

static size_t TotalCacheSize = 0;   // Total size of available memory.
static size_t MaxCacheSize = 0;     // Maximum size of the cache for this process, total / fraction.
static int MaxCachedCells = 0;      // Maximum number of individual cells cached, based on target size.

static u_int8_t *FileStatus[N_DATABASES];   // File status arrays for all databases, used by load_cell().

int initialize_terrain(int fraction) {

	static int result = 1;
	if (result <= 0) {
		return result;
	}

	check_memory_size();

	if (fraction < 1) {
		fraction = 1;
	}

	MaxCacheSize = TotalCacheSize / fraction;
	if (MaxCacheSize < MIN_CACHE_SIZE) {
		result = -1;
		return result;
	}
	result = 0;

	MaxCachedCells = MaxCacheSize / TARGET_CELL_SIZE;

	// Read the database status arrays.  If something goes wrong during a read set all blocks to does-not-exist, the
	// entire database is assumed unavailable if it is not properly indexed.  Along the way update the user-generated
	// index timestamp, see comments above.

	int idx, i;
	struct stat st;
	time_t lastModTime;
	char fname[MAX_STRING];

	for (i = 0; i < N_DATABASES; i++) {

		FileStatus[i] = (u_int8_t *)mem_alloc(TRN_FILE_STATUS_SIZE);
		snprintf(fname, MAX_STRING, "%s/%s/blocks.idx", DBASE_DIRECTORY_NAME, DbName[i]);
		lastModTime = 0;

		if ((idx = open(fname, O_RDONLY|O_BINARY)) >= 0) {

			if (read(idx, FileStatus[i], TRN_FILE_STATUS_SIZE) == TRN_FILE_STATUS_SIZE) {
				if (!fstat(idx, &st)) {
#ifdef __BUILD_LINUX
					lastModTime = st.st_mtime;
#else
					lastModTime = st.st_mtimespec.tv_sec;
#endif
				}
			}

			close(idx);
		}

		if (lastModTime) {
			if (TRN_DB_NUMBER_NED13 == DbNumber[i]) {
				UserTerrainVersion = lastModTime;
			}
		} else {
			memset(FileStatus[i], TRN_STATUS_NODATA, TRN_FILE_STATUS_SIZE);
		}
	}

	return result;
}


//---------------------------------------------------------------------------------------------------------------------
// Complement to the previous function, determine the largest fraction count (representing the smallest amount of
// memory allocation) that will not cause an insufficient-memory error from initialize_terrain().  If this returns
// 0 that means total memory is insufficient even for a single process.

int get_max_memory_fraction() {

	check_memory_size();

	return TotalCacheSize / MIN_CACHE_SIZE;
}


//---------------------------------------------------------------------------------------------------------------------
// Determine the total size of the cell cache if all memory is used, for calculations in previous functions.

static void check_memory_size() {

	if (TotalCacheSize) {
		return;
	}

	DWORDLONG totalMemory = 0;

#ifndef __BUILD_LINUX
	struct sysinfo si;
	if (!sysinfo(&si)) {
		totalMemory = si.totalram * si.mem_unit;
	}
#else
	int mib[2] = {CTL_HW, HW_MEMSIZE};
	size_t len = sizeof(totalMemory);
	sysctl(mib, 2, &totalMemory, &len, NULL, 0);	
#endif	

	DWORDLONG reservedMemory = totalMemory / 2;
	if (reservedMemory > MAX_RESERVED_SIZE) {
		reservedMemory = MAX_RESERVED_SIZE;
	}
	DWORDLONG cachesize = totalMemory - reservedMemory;
	if(cachesize>MAX_RESERVED_SIZE)
		cachesize=MAX_RESERVED_SIZE;

	TotalCacheSize = (size_t) cachesize;
}


//---------------------------------------------------------------------------------------------------------------------
// Determine a limit on the size of a study grid based on the current cache cell count limit, the goal is to be sure
// study grids are small enough so all terrain data needed can be cached.  (However that is based on an assumption for
// the size of individual cells and may not hold depending on terrain data being used; see discussion above).  This
// applies only to global grid mode where multiple sources with overlapping coverages may be studied together.  The
// study grid is always allowed to be large enough for any single station's coverage regardless of this limit, so in
// local mode it is irrelevant because all stations are studied separately on individual grids.  This depends on study
// parameters so a study must be open.

// Arguments:

//   cellSize     Target grid cell size, edge dimension in kilometers.
//   kmPerDegree  Spherical earth kilometers per degree of arc.

// Return is the maximum count of study cells in a grid.  Since the study will always disregard the limit if needed to
// study any single station in isolation this can be 0 if a value can't be determined.  A return of -1 means there is
// insufficient memory available for terrain caching, the run should abort.

int get_max_grid_count(double cellSize, double kmPerDegree) {

	if (initialize_terrain(1)) {
		return -1;
	}

	int terr_edge = sqrt(MaxCachedCells) / TRN_CELLS_PER_DEGREE;
	double cell_edge = (((double)terr_edge * kmPerDegree) - 600.) / cellSize;
	int maxGridCount = (int)(cell_edge * cell_edge);

	if (StatsOut) {
		fprintf(StatsOut, "TERR:  maxGridCount = %d\n", maxGridCount);
	}

	return maxGridCount;
}


//---------------------------------------------------------------------------------------------------------------------
// Used to indicate what types of terrain are needed for a study, currently all this does is set UserTerrainRequested
// for an NED 1/3-second request, see discussion in terrain.h and cache.c.  But someday this might do optimization
// like flushing terrain of an unneeded type out of the cache.  This may be called repeatedly, effect is cumulative.

// Arguments:

//   db  TERR_DB* value that will be used in later terrain_profile() and/or terrain_point() calls.

void add_requested_terrain(int db) {

	if (TERR_DB0 == db) {
		UserTerrainRequested = 1;
	}
}

void clear_requested_terrain() {

	UserTerrainRequested = 0;
	UserTerrainUsed = 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Retrieve a terrain profile, that is the elevations of incremental equally-spaced points along a straight earth-
// surface path.  The database argument indicates the nominal database resolution as discussed above:

//   TERR_DB0   1/3-second data
//   TERR_DB1   1-second data
//   TERR_DB3   3-second data
//   TERR_DB30  30-second data

// Arguments:

//   latStartDegrees, lonStartDegrees  Start coordinates in degrees, WGS84 (NAD83), positive north and west.
//   bearingDegrees                    Profile path bearing in degrees true.
//   distanceKilometers                Path length in kilometers.
//   pointsPerKilometer                Number of points to extract per kilometer.
//   database                          Desired database, with automatic fallback as described above.
//   maximumProfilePointCount          Maximum number of points to return (size of profileElevations).
//   profileElevations                 Destination storage for profile points.
//   profilePointCount                 Return actual number of points in profile.
//   kmPerDegree                       Spherical earth kilometers per degree of arc for distance calculations.

// Return value is an error code, -1 for a serious error that probably means the run must abort, 1 for something less
// serious like missing data, 0 for success.

int terrain_profile(double latStartDegrees, double lonStartDegrees, double bearingDegrees, double distanceKilometers,
		double pointsPerKilometer, int database, int maximumProfilePointCount, float *profileElevations,
		int *profilePointCount, double kmPerDegree) {

	*profilePointCount = 0;

	short databaseIndex = TRN_DB30_INDEX;
	switch (database) {
		case TERR_DB0: {
			databaseIndex = TRN_DB0_INDEX;
			break;
		}
		case TERR_DB1: {
			databaseIndex = TRN_DB1_INDEX;
			break;
		}
		case TERR_DB3: {
			databaseIndex = TRN_DB3_INDEX;
			break;
		}
		case TERR_DB30:
		default: {
			databaseIndex = TRN_DB30_INDEX;
			break;
		}
	}

	// Convert arguments to radians.

	double cellSizeRadians = (1. / (double)TRN_CELLS_PER_DEGREE) * DEGREES_TO_RADIANS;
	double pointsPerRadian = pointsPerKilometer * kmPerDegree * RADIANS_TO_DEGREES;
	double latStartRadians = latStartDegrees * DEGREES_TO_RADIANS;
	double lonStartRadians = lonStartDegrees * DEGREES_TO_RADIANS;
	double bearingRadians = bearingDegrees * DEGREES_TO_RADIANS;

	// Compute total number of points in profile.  If this exceeds maximumProfilePointCount, profile is truncated.

	int profileEndPointCount = (int)(distanceKilometers * pointsPerKilometer) + 1;
	if (profileEndPointCount > maximumProfilePointCount) {
		profileEndPointCount = maximumProfilePointCount;
	}

	// The profile path is divided into fixed-length segments.  The spherical coord() routine is used to determine
	// the great-circle-path coordinates at each of the segment breaks, always calculating from the start point, not
	// from the previous segment break.  Between the break points linear methods are used to determine the coordinates
	// of each individual profile point.  This is done for performance to avoid having to perform the full spherical
	// coordinate calculation at each incremental point.

	// The coord() routine will always return continuous path coordinates even for a path that crosses 180 degrees
	// longitude, so this incremental algorithm will always succeed.  The actual coordinates occurring along the path
	// may thus be over- or under-range in longitude (>180 or <-180 degrees), the load_cell() routine will adjust the
	// cell index coordinates as needed.  However that means the index coordinates in the cell structure may be
	// different than requested so this must always use the locally-computed values.

	// This algorithm will produce inaccurate results for paths that pass close to either pole.  It will not fail, but
	// on any high-latitude path segment the simplifying assumption of geographic coordinate linearity will yield an
	// actual path that deviates from the true path by a significant amount.  However such paths are assumed to rarely
	// if ever occur in real-world applications so the potential inaccuracy is not a concern.

	int segmentPointCount = (int)(SEGMENT_LENGTH_KILOMETERS * pointsPerKilometer);

	// Initialize for main loop.

	double segmentStartLat, segmentStartLon, arcLength, latDeltaRadians, lonDeltaRadians, cellFirstPointLat,
		cellFirstPointLon, pointunitsPerRadian, latDeltaPointunits, lonDeltaPointunits, latPointunits, lonPointunits,
		offset, lonFraction, latFraction, a, b, c, d, e, f;
	double segmentEndLat = latStartRadians;
	double segmentEndLon = lonStartRadians;

	short cellLatIndex, cellLonIndex;
	int i, cellLatPointCount, cellLonPointCount, cellLastPointIndex, latPointCount, lonPointCount, gridOffset,
		latPointunitIndex, lonPointunitIndex, pointunitIndex;
	short curDatabaseIndex = TRN_DB30_INDEX;
	int nextPointIndex = 0;
	int segmentEndPointCount = 0;
	int segmentLastPointIndex = -1;
	short previousCellLatIndex = -1;
	short previousCellLonIndex = -1;
	int cellPointCount = 0;
	int errorCode = 0;

	TCELL *curCell = NULL;

	// Top of segment/cell loop.  If past the end of the previous segment, compute parameters for the next one.

	while (nextPointIndex < profileEndPointCount) {

		if (nextPointIndex > segmentLastPointIndex) {

			segmentStartLat = segmentEndLat;
			segmentStartLon = segmentEndLon;
			segmentEndPointCount += segmentPointCount;
			arcLength = (double)segmentEndPointCount / pointsPerRadian;
			coord(latStartRadians, lonStartRadians, bearingRadians, arcLength, &segmentEndLat, &segmentEndLon);
			latDeltaRadians = (segmentEndLat - segmentStartLat) / (double)segmentPointCount;
			lonDeltaRadians = (segmentEndLon - segmentStartLon) / (double)segmentPointCount;

			if (segmentEndPointCount > profileEndPointCount) {
				segmentEndPointCount = profileEndPointCount;
			}
			segmentLastPointIndex = segmentEndPointCount - 1;

			cellFirstPointLat = segmentStartLat;
			cellFirstPointLon = segmentStartLon;

		// If not past the end of the segment this is a cell transition, compute the coords for the first point in the
		// next cell using the current segment deltas.

		} else {

			cellFirstPointLat += (double)cellPointCount * latDeltaRadians;
			cellFirstPointLon += (double)cellPointCount * lonDeltaRadians;
		}

		// Compute coords of the last point in the current cell.  Compare the point index of that point to the next
		// segment break, if beyond, set the exit point to the segment break.  Along the way this yields the global
		// index values to the current cell.  As discussed above the longitude coordinate may pass outside the range
		// of +/-180 degrees, so a floor() operation is necessary in case the biased radians value becomes negative.

		cellLatIndex = (short)((cellFirstPointLat + HALF_PI) / cellSizeRadians);
		cellLonIndex = (short)floor((cellFirstPointLon + PI) / cellSizeRadians);
		if (latDeltaRadians < 0.) {
			cellLatPointCount = (int)((((double)cellLatIndex * cellSizeRadians) -
				(cellFirstPointLat + HALF_PI)) / latDeltaRadians);
		} else {
			if (latDeltaRadians > 0.) {
				cellLatPointCount = (int)((((double)(cellLatIndex + 1) * cellSizeRadians) -
					(cellFirstPointLat + HALF_PI)) / latDeltaRadians);
			} else {
				cellLatPointCount = 2e9;
			}
		}
		if (cellLatPointCount < 0) {
			cellLatPointCount = 2e9;
		}
		if (lonDeltaRadians < 0.) {
			cellLonPointCount = (int)((((double)cellLonIndex * cellSizeRadians) -
				(cellFirstPointLon + PI)) / lonDeltaRadians);
		} else {
			if (lonDeltaRadians > 0.) {
				cellLonPointCount = (int)((((double)(cellLonIndex + 1) * cellSizeRadians) -
					(cellFirstPointLon + PI)) / lonDeltaRadians);
			} else {
				cellLonPointCount = 2e9;
			}
		}
		if (cellLonPointCount < 0) {
			cellLonPointCount = 2e9;
		}
		cellLastPointIndex = nextPointIndex +
			((cellLatPointCount < cellLonPointCount) ? cellLatPointCount : cellLonPointCount);
		if (cellLastPointIndex > segmentLastPointIndex) {
			cellLastPointIndex = segmentLastPointIndex;
		}

		// If cell is different than last time, retrieve the cell.  If the retrieval fails due to missing data continue
		// trying databases in the list, however other errors will exit immediately.

		if ((cellLatIndex != previousCellLatIndex) || (cellLonIndex != previousCellLonIndex)) {
			for (curDatabaseIndex = databaseIndex; curDatabaseIndex < N_DATABASES; curDatabaseIndex++) {
				errorCode = load_cell(curDatabaseIndex, cellLatIndex, cellLonIndex, &curCell);
				if (errorCode <= 0) {
					break;
				}
			}
			if (errorCode) {
				break;
			}
			previousCellLatIndex = cellLatIndex;
			previousCellLonIndex = cellLonIndex;
		}

		// Compute the total number of points to extract from this cell.  A special case is latPointCount and
		// lonPointCount both 1, that means all elevations in the cell are the same.

		cellPointCount = cellLastPointIndex - nextPointIndex + 1;

		if ((curCell->latPointCount < 2) || (curCell->lonPointCount < 2)) {

			float elev = (float)curCell->cellElevation;

			for (i = 0; i < cellPointCount; i++) {
				profileElevations[nextPointIndex++] = elev;
			}

		// Convert the lat/lon point deltas and compute the initial point coordinates so all represent units of point
		// index within the cell.  This had to wait until now because the point spacing can be variable between cells.

		} else {

			latPointCount = (int)curCell->latPointCount;
			lonPointCount = (int)curCell->lonPointCount;
			gridOffset = (int)curCell->gridOffset;

			pointunitsPerRadian = (double)(latPointCount - gridOffset - 1) / cellSizeRadians;
			latDeltaPointunits = latDeltaRadians * pointunitsPerRadian;
			latPointunits = ((cellFirstPointLat + HALF_PI) -
				((double)cellLatIndex * cellSizeRadians)) * pointunitsPerRadian;

			pointunitsPerRadian = (double)(lonPointCount - gridOffset - 1) / cellSizeRadians;
			lonDeltaPointunits = lonDeltaRadians * pointunitsPerRadian;
			lonPointunits = ((cellFirstPointLon + PI) -
				((double)cellLonIndex * cellSizeRadians)) * pointunitsPerRadian;

			// Extract points from the cell, retrieve four surrounding points and interpolate.  Depending on the type
			// of data (grid or cell-center) conversion to integer coordinates may truncate or round.  For cell-center
			// data there is also an implicit offset because the cell has an extra starting row and column.

			short *cellData = curCell->cellData;

			if (gridOffset) {
				offset = 0.5;
			} else {
				offset = 0.;
			}

			for (i = 0; i < cellPointCount; i++) {

				latPointunitIndex = (int)(latPointunits + offset);
				lonPointunitIndex = (int)(lonPointunits + offset);

				latFraction = (latPointunits - (double)latPointunitIndex) + offset;
				lonFraction = (lonPointunits - (double)lonPointunitIndex) + offset;

				pointunitIndex = (latPointunitIndex * lonPointCount) + lonPointunitIndex;

				a = (double)cellData[pointunitIndex + lonPointCount + 1];
				b = (double)cellData[pointunitIndex + lonPointCount];
				c = (double)cellData[pointunitIndex + 1];
				d = (double)cellData[pointunitIndex];

				e = b + ((a - b) * lonFraction);
				f = d + ((c - d) * lonFraction);

				profileElevations[nextPointIndex++] = (float)(f + ((e - f) * latFraction));

				latPointunits += latDeltaPointunits;
				lonPointunits += lonDeltaPointunits;
			}
		}
	}

	// Return actual number of points extracted, even if an error aborted the lookup there might still be some data.

	*profilePointCount = nextPointIndex;

	return errorCode;
}


//---------------------------------------------------------------------------------------------------------------------
// Retrieve a single elevation at specified geographic coordinates, see comments above for terrain_profile().

// Arguments:

//   latDegrees, lonDegrees  Coordinates in degrees, WGS84 (NAD83), latitude positive north, longitude positive west.
//   database                Desired database.
//   elevation               Return point elevation in meters.

// Return 0 for success, <0 for a serious error, >0 for a minor error (missing data).

int terrain_point(double latDegrees, double lonDegrees, int database, float *elevation) {

	*elevation = 0.;

	short databaseIndex = TRN_DB30_INDEX;
	switch (database) {
		case TERR_DB0: {
			databaseIndex = TRN_DB0_INDEX;
			break;
		}
		case TERR_DB1: {
			databaseIndex = TRN_DB1_INDEX;
			break;
		}
		case TERR_DB3: {
			databaseIndex = TRN_DB3_INDEX;
			break;
		}
		case TERR_DB30:
		default: {
			databaseIndex = TRN_DB30_INDEX;
			break;
		}
	}

	// Initialize.

	double cellSizeRadians = (1. / (double)TRN_CELLS_PER_DEGREE) * DEGREES_TO_RADIANS;
	double latRadians = latDegrees * DEGREES_TO_RADIANS;
	double lonRadians = lonDegrees * DEGREES_TO_RADIANS;

	double pointunitsPerRadian, latPointunits, lonPointunits, offset, lonFraction, latFraction, a, b, c, d, e, f;

	short cellLatIndex, cellLonIndex;
	int latPointCount, lonPointCount, gridOffset, latPointunitIndex, lonPointunitIndex, pointunitIndex;
	short curDatabaseIndex = TRN_DB30_INDEX;
	int errorCode = 0;

	// Compute the global index values to the needed cell and retrieve it, if found extract the point.  See comments
	// in terrain_profile(), this is just a simplified single-point version of the same algorithm.

	TCELL *curCell;

	cellLatIndex = (short)((latRadians + HALF_PI) / cellSizeRadians);
	cellLonIndex = (short)floor((lonRadians + PI) / cellSizeRadians);

	for (curDatabaseIndex = databaseIndex; curDatabaseIndex < N_DATABASES; curDatabaseIndex++) {
		errorCode = load_cell(curDatabaseIndex, cellLatIndex, cellLonIndex, &curCell);
		if (errorCode <= 0) {
			break;
		}
	}
	if (errorCode) {
		return errorCode;
	}

	if ((curCell->latPointCount < 2) || (curCell->lonPointCount < 2)) {

		*elevation = (float)curCell->cellElevation;

	} else {

		latPointCount = (int)curCell->latPointCount;
		lonPointCount = (int)curCell->lonPointCount;
		gridOffset = (int)curCell->gridOffset;

		pointunitsPerRadian = (double)(latPointCount - gridOffset - 1) / cellSizeRadians;
		latPointunits = ((latRadians + HALF_PI) - ((double)cellLatIndex * cellSizeRadians)) * pointunitsPerRadian;

		pointunitsPerRadian = (double)(lonPointCount - gridOffset - 1) / cellSizeRadians;
		lonPointunits = ((lonRadians + PI) - ((double)cellLonIndex * cellSizeRadians)) * pointunitsPerRadian;

		short *cellData = curCell->cellData;

		if (gridOffset) {
			offset = 0.5;
		} else {
			offset = 0.;
		}

		latPointunitIndex = (int)(latPointunits + offset);
		lonPointunitIndex = (int)(lonPointunits + offset);

		lonFraction = (lonPointunits - (double)lonPointunitIndex) + offset;
		latFraction = (latPointunits - (double)latPointunitIndex) + offset;

		pointunitIndex = (latPointunitIndex * lonPointCount) + lonPointunitIndex;

		a = (double)cellData[pointunitIndex + lonPointCount + 1];
		b = (double)cellData[pointunitIndex + lonPointCount];
		c = (double)cellData[pointunitIndex + 1];
		d = (double)cellData[pointunitIndex];

		e = b + ((a - b) * lonFraction);
		f = d + ((c - d) * lonFraction);

		*elevation = (float)(f + ((e - f) * latFraction));
	}

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Compute an array of HAAT values at regularily-spaced azimuths, using compute_haat_radial().

// Arguments:

//   startLat, startLon  Transmitter point coordinates, degrees positive north latitude and west longitude.
//   heightAMSL          Transmitter height AMSL, meters.
//   haat                Array to hold results.
//   haatCount           Number of evenly-spaced radials to compute (size of haat[]).
//   terrainDb           Terrain database, TERR_DB0, TERR_DB1, TERR_DB3, or TERR_DB30.
//   startDist, endDist  Start and end distance for average on each radial, kilometers.
//   distInc             Increment between points on each radial, kilometers.
//   kmPerDegree         Spherical earth distance constant, kilometers per degree of arc.
//   debugOut            Print debugging lines if non-NULL.

// Return 0 for success, else error code passed through from compute_haat_radial().

int compute_haat(double startLat, double startLon, double heightAMSL, double *haat, int haatCount, int terrainDb,
		double startDist, double endDist, double distInc, double kmPerDegree, FILE *debugOut) {

	double azmStep = 360. / (double)haatCount;

	double azm;
	int i, err;

	for (i = 0; i < haatCount; i++) {

		azm = (double)i * azmStep;
		err = compute_haat_radial(startLat, startLon, azm, heightAMSL, (haat + i), terrainDb, startDist, endDist,
			distInc, kmPerDegree, debugOut);
		if (err) return err;
	}

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Compute an HAAT value on a radial.  This does not use terrain_profile(), it incrementally uses terrain_point().

// Arguments:

//   startLat, startLon  Transmitter point coordinates, degrees positive north latitude and west longitude.
//   azimuth             Radial azimuth in degrees true.
//   heightAMSL          Transmitter height AMSL, meters.
//   haat                Return result.
//   terrainDb           Terrain database, TERR_DB0, TERR_DB1, TERR_DB3, or TERR_DB30.
//   startDist, endDist  Start and end distance for average on each radial, kilometers.
//   distInc             Increment between points on each radial, kilometers.
//   kmPerDegree         Spherical earth distance constant, kilometers per degree of arc.
//   debugOut            Print debugging lines if non-NULL.

// Return 0 for success, else error code passed through from terrain_point().

int compute_haat_radial(double startLat, double startLon, double azimuth, double heightAMSL, double *haat,
		int terrainDb, double startDist, double endDist, double distInc, double kmPerDegree, FILE *debugOut) {

	double startLatRad = startLat * DEGREES_TO_RADIANS;
	double startLonRad = startLon * DEGREES_TO_RADIANS;

	double azmRad = azimuth * DEGREES_TO_RADIANS;

	double startDistRad = (startDist / kmPerDegree) * DEGREES_TO_RADIANS;
	double endDistRad = (endDist / kmPerDegree) * DEGREES_TO_RADIANS;
	double distIncRad = (distInc / kmPerDegree) * DEGREES_TO_RADIANS;

	if (endDistRad <= (startDistRad + distIncRad)) {
		endDistRad = startDistRad + distIncRad;
	}
	endDistRad += distIncRad * 0.1;

	double latRad, lonRad, dist, lat, lon;
	float elev;
	int err;

	double avet = 0.;
	int np = 0;
	double distRad = startDistRad;

	if (debugOut) {
		fprintf(debugOut, "az=%.3f\n", azimuth);
	}

	while (distRad < endDistRad) {

		coord(startLatRad, startLonRad, azmRad, distRad, &latRad, &lonRad);
		lat = latRad * RADIANS_TO_DEGREES;
		lon = lonRad * RADIANS_TO_DEGREES;

		err = terrain_point(lat, lon, terrainDb, &elev);
		if (err) return err;

		if (debugOut) {
			dist = distRad * RADIANS_TO_DEGREES * kmPerDegree;
			fprintf(debugOut, "%.3f,%.9f,%.9f,%.3f\n", dist, lat, lon, elev);
		}

		avet += (double)elev;
		np++;
		distRad += distIncRad;
	}

	avet /= (double)np;
	*haat = heightAMSL - avet;

	if (debugOut) {
		fprintf(debugOut, "avet=%.3f haat=%.3f\n", avet, *haat);
	}

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Routine computes geographic coordinates of an end point given a start point and the bearing and distance to the end.
// Uses spherical methods only.  All arguments passed/returned are in radians.  The end point longitude is not adjusted
// for crossing of the 180-degree line so this will always produce an incrementally continuous path.  In other words if
// the bearing is westerly the end point longitude will always be greater than the start, even if that means it is
// greater than 180 degrees west.  Longitude index coordinates are adjusted as needed in load_cell().

// Arguments:

//   latStart, lonStart  Start point latitude and longitude, radians.
//   bearing             Bearing, radians.
//   arcLength           Distance, great-circle arc length, radians.
//   latEnd, lonEnd      Return end point latitude and longitude, radians.

static void coord(double latStart, double lonStart, double bearing, double arcLength, double *latEnd, double *lonEnd) {

	double sinLatStart, cosLatStart, cosArcLength, sinLat, cosLon, temp, lat, lon;

	sinLatStart = sin(latStart);
	cosLatStart = cos(latStart);
	cosArcLength = cos(arcLength);

	sinLat = (sinLatStart * cosArcLength) + (cosLatStart * sin(arcLength) * cos(bearing));
	if (sinLat < -1.) {
		sinLat = -1.;
	}
	if (sinLat > 1.) {
		sinLat = 1.;
	}
	lat = asin(sinLat);

	temp = cosLatStart * cos(lat);
	if (fabs(temp) < 1.e-30) {
		lon = lonStart;
	} else {
		cosLon = (cosArcLength - (sinLatStart * sinLat)) / temp;
		if (cosLon < -1.) {
			cosLon = -1.;
		}
		if (cosLon > 1.) {
			cosLon = 1.;
		}
		lon = acos(cosLon);
		if (bearing > PI) {
			lon = lonStart + lon;
		} else {
			lon = lonStart - lon;
		}
	}

	*latEnd = lat;
	*lonEnd = lon;
}


//---------------------------------------------------------------------------------------------------------------------
// Cell retrieval routine.  This handles all databases; the internal file and cell structures are identical for all,
// and the open file list and cache contain data from any.  This is the only routine used to retrieve cell data; the
// cache management routines must not be called directly.  Also this must only be used in a sequential manner (one cell
// at a time), any previously-returned cell structure may be invalidated on the next call.

// The cell cache and index are initialized on the first call.  Cached cells are kept in a linked list with the most
// recently used cells at the head and the oldest ones at the tail, when a cell is used it is linked or re-linked to
// the head of the list.  When a cache size limit is reached, cells from the end of the list are discarded to make room
// for the new cell.  The memory for the cache is managed by cache_init() and get_tcell(), see comments there.  A
// search index is also maintained for efficient retrieval of cells in the cache.

// On the first call, this also reads into memory an index for each database which is used to determine the status of
// any particular database file: the file may exist in the database, it may be non-seawater terrain but not exist (it
// is outside the area covered by that particular database), or it may be seawater.  If a database file is seawater,
// any request for a cell in that file will return a pointer to a static cell containing all zero elevations; that cell
// is allocated on the on the first call, and is re-used for all later seawater cases (no seawater cell will ever be
// found in the cache).

// Arguments:

//   databaseIndex               Database index.
//   cellLatIndex, cellLonIndex  Global cell coordinates.
//   cell                        Set to cell pointer, NULL on error.

// Return is non-zero on error, file I/O or bad file errors are <0, value >0 means missing data.  If an error <0 is
// returned it indicates an unrecoverable condition and the caller must exit; >0 processing can continue, typically
// that will be to try again with a different database.  The usual >0 return is value 1 indicating the cell does not
// exist.  However if an individual file that an index says should exist fails to open, the file ID is returned.  Also
// -1 will be returned if there is insufficient memory available for caching.

#define MAX_OPEN_FILES 32   // Max number of database files kept open.

static int load_cell(short databaseIndex, short cellLatIndex, short cellLonIndex, TCELL **cell) {

	static int nextFileIndex = -1;                    // Index to slot to use on next open.
	static int fileDescriptors[MAX_OPEN_FILES];       // Open file descriptor numbers.
	static TRN_HEADER *fileHeaders[MAX_OPEN_FILES];   // Headers from open files.
	static int swapBytes[MAX_OPEN_FILES];             // Flags indicating if byte ordering is reversed.
	static TCELL seawaterCell;                        // Seawater (zero-elevation) cell and data value.
	static u_int8_t *cellDataBuffer;                  // Temporary storage for cell data.
	static int maxCellLonIndex;                       // For checking longitude index range.

	int i, fileDescriptor;
	char fileName[MAX_STRING];

	*cell = NULL;

	// Initialize on the first call, cell cache first.

	if (nextFileIndex < 0) {

		if (cache_init()) {
			return -1;
		}

		// Initialize the open files list, allocate header storage.

		for (i = 0; i < MAX_OPEN_FILES; i++) {
			fileDescriptors[i] = -1;
			fileHeaders[i] = (TRN_HEADER *)mem_alloc(sizeof(TRN_HEADER));
			memset(fileHeaders[i], 0, sizeof(TRN_HEADER));
			swapBytes[i] = 0;
		}
		nextFileIndex = 0;

		// Initialize the seawater (zero-delta) static cell.

		seawaterCell.latPointCount = 1;
		seawaterCell.lonPointCount = 1;
		seawaterCell.gridOffset = 0;
		seawaterCell.cellElevation = 0;
		seawaterCell.cellData = NULL;
		seawaterCell.cachePrev = NULL;
		seawaterCell.cacheNext = NULL;
		seawaterCell.indexPrev = NULL;
		seawaterCell.indexNext = NULL;

		// Cell data buffer.

		cellDataBuffer = (u_int8_t *)mem_alloc(TRN_MAX_CELL_SIZE);

		// Longitude range value.

		maxCellLonIndex = 360 * TRN_CELLS_PER_DEGREE;
	}

	// The longitude index may be over- or under-range, the path algorithm works with a continuous path even when the
	// 180-degree longitude line is crossed.  Just apply a modulo to the coordinates here and proceed.

	while (cellLonIndex < 0) {
		cellLonIndex += maxCellLonIndex;
	}
	while (cellLonIndex >= maxCellLonIndex) {
		cellLonIndex -= maxCellLonIndex;
	}

	// Check the status of the file containing the cell.

	int fileLatIndex = (int)(cellLatIndex / TRN_CELLS_PER_DEGREE);
	int fileLonIndex = (int)(cellLonIndex / TRN_CELLS_PER_DEGREE);
	int fileID = (fileLatIndex * 10000) + (fileLonIndex * 10) + DbNumber[databaseIndex];

	int theStatus = FileStatus[databaseIndex][(fileLatIndex * 360) + fileLonIndex];

	// If it is a seawater file, return a pointer to a static cell structure representing all zeros (a zero-delta cell
	// with minimum elevation set to 0).  The cell ID is updated, all other parameters are fixed.  Cache stats are
	// logged if debugging is on, see update_cache_stats().

	if (TRN_STATUS_SEAWATER == theStatus) {
		if (StatsOut) {
			update_cache_stats(databaseIndex, STATS_SEAWATER);
		}
		seawaterCell.databaseIndex = databaseIndex;
		seawaterCell.cellLatIndex = cellLatIndex;
		seawaterCell.cellLonIndex = cellLonIndex;
		*cell = &seawaterCell;
		return 0;
	}

	// File is not in the database, return not-found.  Note any not-found result is not counted in the stats at all
	// because there will immediately be another lookup for the next-lower-resolution database; eventually the cell
	// will be found and added to the stats for that lookup.

	if (TRN_STATUS_NODATA == theStatus) {
		return 1;
	}

	// Search the cache for the requested cell.  If not found this will obtain a blank cell structure for the new cell
	// data.  That cell will have already been placed in the cache and index so it must be used or released.  If found
	// check for the missing-data code, see below.

	TCELL *curCell = NULL;
	if (get_tcell(databaseIndex, cellLatIndex, cellLonIndex, &curCell)) {
		if ((curCell->latPointCount < 0) || (curCell->lonPointCount < 0)) {
			return 1;
		}
		*cell = curCell;
		if (StatsOut) {
			update_cache_stats(databaseIndex, STATS_CACHED);
		}
		return 0;
	}

	// Cell was not in the cache, need to read from file.  Check if the file is already open, if not attempt to open.

	int fileIndex = -1;
	TRN_HEADER *fileHeader;

	for (i = 0; i < MAX_OPEN_FILES; i++) {
		if (fileDescriptors[i] < 0) {
			continue;
		}
		if (fileID == fileHeaders[i]->fileID) {
			fileIndex = i;
			fileDescriptor = fileDescriptors[fileIndex];
			fileHeader = fileHeaders[i];
			break;
		}
	}

	if (fileIndex < 0) {

		if (fileDescriptors[nextFileIndex] >= 0) {
			close(fileDescriptors[nextFileIndex]);
			fileDescriptors[nextFileIndex] = -1;
		}

		int fileLatWholeDegrees, fileLonWholeDegrees;
		char fileLatDirection, fileLonDirection;

		if (fileLatIndex < 90) {
			fileLatDirection = 's';
			fileLatWholeDegrees = 90 - fileLatIndex;
		} else {
			fileLatDirection = 'n';
			fileLatWholeDegrees = fileLatIndex - 90;
		}
		if (fileLonIndex < 180) {
			fileLonDirection = 'e';
			fileLonWholeDegrees = 180 - fileLonIndex;
		} else {
			fileLonDirection = 'w';
			fileLonWholeDegrees = fileLonIndex - 180;
		}
		snprintf(fileName, MAX_STRING, "%s/%s/%c%02d%c%03d.trn", DBASE_DIRECTORY_NAME, DbName[databaseIndex],
			fileLatDirection, fileLatWholeDegrees, fileLonDirection, fileLonWholeDegrees);

		// If the file fails to open release the cell and update the file index to indicate it is missing.  If the
		// original index status indicated the file should exist return the file ID as error code.

		if ((fileDescriptor = open(fileName, O_RDONLY|O_BINARY)) < 0) {
			release_cell(curCell);
			FileStatus[databaseIndex][(fileLatIndex * 360) + fileLonIndex] = TRN_STATUS_NODATA;
			if (TRN_STATUS_DATA == theStatus) {
				return fileID;
			}
			return 1;
		}

		// Open succeeded, read the file header, check the magic number.  Detect when the header is byte-swapped (can
		// occur with any file format version) and re-order as needed.

		fileHeader = fileHeaders[nextFileIndex];

		TRN_HEADER fileHeaderBuffer;

		if (read(fileDescriptor, &fileHeaderBuffer, sizeof(TRN_HEADER)) != sizeof(TRN_HEADER)) {
			return -2;
		}

		swapBytes[nextFileIndex] = 0;

		if ((TRN_MAGIC_V1_SWAP == fileHeaderBuffer.magicNumber) ||
				(TRN_MAGIC_V2_SWAP == fileHeaderBuffer.magicNumber) ||
				(TRN_MAGIC_V2_U_SWAP == fileHeaderBuffer.magicNumber)) {

			swapBytes[nextFileIndex] = 1;

			memswabcpy(&(fileHeader->magicNumber), &(fileHeaderBuffer.magicNumber), 4, 4);
			memswabcpy(&(fileHeader->fileID), &(fileHeaderBuffer.fileID), 4, 4);
			memcpy(fileHeader->cellFlags, fileHeaderBuffer.cellFlags, TRN_CELLS_PER_FILE);
			memswabcpy(fileHeader->minimumElevation, fileHeaderBuffer.minimumElevation, (2 * TRN_CELLS_PER_FILE), 2);
			memswabcpy(fileHeader->latPointCount, fileHeaderBuffer.latPointCount, (2 * TRN_CELLS_PER_FILE), 2);
			memswabcpy(fileHeader->lonPointCount, fileHeaderBuffer.lonPointCount, (2 * TRN_CELLS_PER_FILE), 2);
			memswabcpy(fileHeader->recordSize, fileHeaderBuffer.recordSize, (4 * TRN_CELLS_PER_FILE), 4);
			memswabcpy(fileHeader->recordOffset, fileHeaderBuffer.recordOffset, (4 * TRN_CELLS_PER_FILE), 4);

		} else {

			memcpy(fileHeader, &fileHeaderBuffer, sizeof(TRN_HEADER));
		}

		// With version 1 files, the file index range only supported north latitude and west longitude.  But otherwise
		// the format is identical so just fix the file ID by biasing the coordinates and call it version 2.  For V2
		// user-created files, set a global flags indicating that user files were used in the run then change to V2.

		if (TRN_MAGIC_V1 == fileHeader->magicNumber) {
			fileHeader->fileID = (((fileHeader->fileID / 10000) + 90) * 10000) +
				((((fileHeader->fileID % 10000) / 10) + 180) * 10) + (fileHeader->fileID % 10);
			fileHeader->magicNumber = TRN_MAGIC_V2;
		}

		if (TRN_MAGIC_V2_U == fileHeader->magicNumber) {
			UserTerrainRequested = 1;
			UserTerrainUsed = 1;
			fileHeader->magicNumber = TRN_MAGIC_V2;
		}

		if ((fileHeader->magicNumber != TRN_MAGIC_V2) || (fileHeader->fileID != fileID)) {
			return -3;
		}

		fileDescriptors[nextFileIndex] = fileDescriptor;
		fileIndex = nextFileIndex;

		if (++nextFileIndex == MAX_OPEN_FILES) {
			nextFileIndex = 0;
		}
	}

	// Compute local cell index, check for missing-data code.  Missing-data cells are still cached to avoid having to
	// repeat the lookup later but they will never be returned to the caller, see above.

	int cellIndex = (((int)cellLatIndex - (fileLatIndex * TRN_CELLS_PER_DEGREE)) * TRN_CELLS_PER_DEGREE) +
		((int)cellLonIndex - (fileLonIndex * TRN_CELLS_PER_DEGREE));

	if (fileHeader->cellFlags[cellIndex] & TRN_NO_DATA_MASK) {
		curCell->latPointCount = -1;
		curCell->lonPointCount = -1;
		return 1;
	}

	// Count this as a loaded block in the stats, from here on any errors are fatal.

	if (StatsOut) {
		update_cache_stats(databaseIndex, STATS_LOADED);
	}

	// Get compression key and data block size.

	int compressionType = (int)(fileHeader->cellFlags[cellIndex] & TRN_COMPRESSION_MASK);
	size_t size = fileHeader->recordSize[cellIndex];
	if (size > TRN_MAX_CELL_SIZE) {
		return -4;
	}

	// If the cell has data, read it into a temporary buffer.  Only zero-delta cells have no actual data.

	int cellPointCount = (int)fileHeader->latPointCount[cellIndex] * (int)fileHeader->lonPointCount[cellIndex];
	size_t cellDataSize = 0;

	if (size > 0) {

		lseek(fileDescriptor, fileHeader->recordOffset[cellIndex], SEEK_SET);
		if (read(fileDescriptor, cellDataBuffer, size) != size) {
			return -5;
		}
		cellDataSize = cellPointCount * sizeof(short);

	} else {

		if (compressionType != TRN_COMPRESSION_ZERO) {
			return -6;
		}
		cellDataSize = 0;
	}

	update_cache_size(curCell, cellDataSize);

	// Initialize the cell.  If cell is compressed, unpack it, otherwise just copy; however byte-swapping may be needed
	// in that case.  The bit-packed compressed data is always a byte stream so byte order does not vary.

	curCell->cellElevation = fileHeader->minimumElevation[cellIndex];

	if (size > 0) {

		curCell->latPointCount = (short)fileHeader->latPointCount[cellIndex];
		curCell->lonPointCount = (short)fileHeader->lonPointCount[cellIndex];

		if (fileHeader->cellFlags[cellIndex] & TRN_GRID_OFFSET_MASK) {
			curCell->gridOffset = 1;
		} else {
			curCell->gridOffset = 0;
		}

		curCell->cellData = (short *)mem_alloc(cellDataSize);

		if (compressionType != TRN_COMPRESSION_NONE) {

			if ((compressionType < 1) || (compressionType > 15)) {
				return -7;
			}
			unpack(cellDataBuffer, cellPointCount, compressionType, curCell->cellElevation, curCell->cellData);

		} else {

			if (swapBytes[fileIndex]) {
				memswabcpy(curCell->cellData, cellDataBuffer, cellDataSize, 2);
			} else {
				memcpy(curCell->cellData, cellDataBuffer, cellDataSize);
			}
		}

	// Zero-delta cells get a special pattern, with latPointCount and lonPointCount set to 1 other code knows the
	// single-point cell elevation is to be used for any and all points in the cell.

	} else {

		curCell->latPointCount = 1;
		curCell->lonPointCount = 1;
		curCell->gridOffset = 0;
	}

	*cell = curCell;
	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Routine to unpack a bit-packed data block.

// Arguments:

//   cellDataBuffer   The packed data string.
//   pointCount       Number of points in the string.
//   bitCount         Bit length per point, 1-15.
//   minElevation     Minimum elevation.
//   cellData         Return unpacked data.

static void unpack(u_int8_t *cellDataBuffer, int pointCount, int bitCount, short minElevation, short *cellData) {

	// Shift tables for unpacking routine.

	static int shiftTable[15][8] = {
		{ 0, 1, 2, 3, 4, 5, 6, 7},
		{ 0, 2, 4, 6, 0, 2, 4, 6},
		{ 0, 3, 6, 1, 4, 7, 2, 5},
		{ 0, 4, 0, 4, 0, 4, 0, 4},
		{ 0, 5, 2, 7, 4, 1, 6, 3},
		{ 0, 6, 4, 2, 0, 6, 4, 2},
		{ 0, 7, 6, 5, 4, 3, 2, 1},
		{ 0, 0, 0, 0, 0, 0, 0, 0},
		{ 0, 1, 2, 3, 4, 5, 6, 7},
		{ 0, 2, 4, 6, 0, 2, 4, 6},
		{ 0, 3, 6, 1, 4, 7, 2, 5},
		{ 0, 4, 0, 4, 0, 4, 0, 4},
		{ 0, 5, 2, 7, 4, 1, 6, 3},
		{ 0, 6, 4, 2, 0, 6, 4, 2},
		{ 0, 7, 6, 5, 4, 3, 2, 1},
	};
	static int incrementTable[15][8] = {
		{ 0, 0, 0, 0, 0, 0, 0, 1},
		{ 0, 0, 0, 1, 0, 0, 0, 1},
		{ 0, 0, 1, 0, 0, 1, 0, 1},
		{ 0, 1, 0, 1, 0, 1, 0, 1},
		{ 0, 1, 0, 1, 1, 0, 1, 1},
		{ 0, 1, 1, 1, 0, 1, 1, 1},
		{ 0, 1, 1, 1, 1, 1, 1, 1},
		{ 1, 1, 1, 1, 1, 1, 1, 1},
		{ 1, 1, 1, 1, 1, 1, 1, 2},
		{ 1, 1, 1, 2, 1, 1, 1, 2},
		{ 1, 1, 2, 1, 1, 2, 1, 2},
		{ 1, 2, 1, 2, 1, 2, 1, 2},
		{ 1, 2, 1, 2, 2, 1, 2, 2},
		{ 1, 2, 2, 2, 1, 2, 2, 2},
		{ 1, 2, 2, 2, 2, 2, 2, 2}
	};

	// Union used for unpacking.

	union {
		u_int32_t integer;
		u_int8_t byte[4];
	} buffer;

	// Initialize.

	u_int32_t mask = (1 << bitCount) - 1;
	int *shiftTableRow = shiftTable[bitCount - 1];
	int *incrementTableRow = incrementTable[bitCount - 1];

#ifdef __BIG_ENDIAN__
	buffer.byte[3] = cellDataBuffer[0];
	buffer.byte[2] = cellDataBuffer[1];
	buffer.byte[1] = cellDataBuffer[2];
	buffer.byte[0] = cellDataBuffer[3];
#else
	buffer.byte[0] = cellDataBuffer[0];
	buffer.byte[1] = cellDataBuffer[1];
	buffer.byte[2] = cellDataBuffer[2];
	buffer.byte[3] = cellDataBuffer[3];
#endif

	int byteIndex = 4;
	int tableRowIndex = 0;
	short pointDelta;
	int i;

	for (i = 0; i < pointCount; i++) {

		pointDelta = (short)((buffer.integer >> shiftTableRow[tableRowIndex]) & mask);
		cellData[i] = minElevation + pointDelta;

		if (incrementTableRow[tableRowIndex] > 0) {

			buffer.integer >>= 8;
#ifdef __BIG_ENDIAN__
			buffer.byte[0] = cellDataBuffer[byteIndex++];
#else
			buffer.byte[3] = cellDataBuffer[byteIndex++];
#endif

			if (incrementTableRow[tableRowIndex] > 1) {

				buffer.integer >>= 8;
#ifdef __BIG_ENDIAN__
				buffer.byte[0] = cellDataBuffer[byteIndex++];
#else
				buffer.byte[3] = cellDataBuffer[byteIndex++];
#endif
			}
		}

		if (++tableRowIndex > 7) {
			tableRowIndex = 0;
		}
	}
}


//---------------------------------------------------------------------------------------------------------------------
// Initialize the cache and search index.  The cached cells are kept in both a flat list and an index structrure.  The
// flat list holds cells in a most-recently-used-first order, so cells at the tail have not been used in many calls
// and are released and re-used when the cache limit is reached.  The number of cells in the flat list is limited, so
// all those structures are allocated to start and are re-used, initially all are in a free list.

// The indexing structure used for searches is a tree, each node has four children sub-dividing it's area by quadrant
// from a center point.  The bottom nodes at level 0 have children that are simply lists of cells, those are the leaves
// and the final stage of a search is linear through a leaf list.  The maximum number of levels is set for a target of
// 10-20 cells per leaf (that level never varies because the range of cell index values is constant).  New node paths
// are added all the way down to level 0 so splitting and merging of nodes does not occur.  Index nodes are allocated
// in pools, to start create the first pool and set up the top node, others will be added when needed.

// Arguments:

//   (none)

// This will do nothing and return -1 if there is insufficient memory for caching, else it returns 0.

static TCELL *FreeCellsHead = NULL;   // List of free cells.

static TCELL *CachedCellsHead = NULL;   // Head of cached cell list.
static TCELL *CachedCellsTail = NULL;   // Tail of cached cell list.

size_t CacheSize = 0;   // Current total size of the cache.

typedef struct {      // Index tree node structure.
	int level;        // Level 0 nodes have cell lists as children.
	int latIndex;     // Mid-point of the area covered by this node.
	int lonIndex;
	int latSize;      // Size of each quadrant covered by this node.
	int lonSize;
	void *child[4];   // Child node or cell list for each quadrant.
} NODE;

#define TOP_NODE_LEVEL 9

static NODE *MapTopNode = NULL;

#define NODE_POOL_SIZE 10000

typedef struct npl {
	int freeNodeIndex;
	NODE nodes[NODE_POOL_SIZE];
	struct npl *next;
} NODE_POOL;

static NODE_POOL *MapNodePools = NULL;

static int cache_init() {

	if (initialize_terrain(1)) {
		return -1;
	}

	int i;

	FreeCellsHead = (TCELL *)mem_zalloc((size_t)MaxCachedCells * sizeof(TCELL));

	TCELL *cell = FreeCellsHead;
	for (i = 0; i < MaxCachedCells; i++, cell++) {
		cell->cacheNext = cell + 1;
	}
	(cell - 1)->cacheNext = NULL;

	NODE_POOL *pool = (NODE_POOL *)mem_alloc(sizeof(NODE_POOL));
	MapNodePools = pool;
	memset(pool, 0, sizeof(NODE_POOL));

	MapTopNode = pool->nodes;
	pool->freeNodeIndex = 1;

	MapTopNode->level = TOP_NODE_LEVEL;
	MapTopNode->latIndex = 90 * TRN_CELLS_PER_DEGREE;
	MapTopNode->lonIndex = 180 * TRN_CELLS_PER_DEGREE;
	MapTopNode->latSize = MapTopNode->latIndex;
	MapTopNode->lonSize = MapTopNode->lonIndex;

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Routine to get a cell for a specified database at specified cell index coordinates.  This will first search the
// cache, if the cell is found there it is returned along with a non-zero return value.  If not found in the cache a
// new cell structure is obtained, releasing an old one if the cache is full.  That structure is initialized with the
// argument index values and placed in the cache and index.  The caller must fill in actual cell data, or if unable to
// do so, immediately call release_cell() on the cell.

// When a new cell structure is returned the data size is set to the largest possible cell size and the cache size is
// incremented by that amount.  Once the caller knows the actual size of cell data it should call update_cache_size()
// on the cell to set the new size, even if (especially if) the size is zero.

// When the cell is found in the cache, it is moved to the head of the main cache list, so the oldest cells (those not
// used in the longest time) are at the tail of that list to be released when the cache is full.

// Arguments:

//   databaseIndex               Database, the internal index number, not the ID number from file headers.
//   cellLatIndex, cellLonIndex  Global cell coordinates.
//   cell                        Set to cell pointer, NULL on error.

// Return is non-zero if the cell was found in the cache, 0 if a new cell was set up.

static int get_tcell(short databaseIndex, short cellLatIndex, short cellLonIndex, TCELL **cell) {

	TCELL *newCell = cell_map_get(databaseIndex, cellLatIndex, cellLonIndex);

	if (newCell) {

		if (newCell->cachePrev) {
			newCell->cachePrev->cacheNext = newCell->cacheNext;
			if (newCell->cacheNext) {
				newCell->cacheNext->cachePrev = newCell->cachePrev;
			} else {
				CachedCellsTail = newCell->cachePrev;
			}
			newCell->cachePrev = NULL;
			CachedCellsHead->cachePrev = newCell;
			newCell->cacheNext = CachedCellsHead;
			CachedCellsHead = newCell;
		}

		*cell = newCell;
		return 1;
	}

	// Because the max cache size is not a hard limit and the individual cell size is very small compared to the max,
	// just make sure the current size is less than the max before continuing, regardless of how much less.

	while ((CacheSize >= MaxCacheSize) || (NULL == FreeCellsHead)) {
		release_cell(CachedCellsTail);
	}

	newCell = FreeCellsHead;
	FreeCellsHead = newCell->cacheNext;
	newCell->cacheNext = NULL;

	newCell->databaseIndex = databaseIndex;
	newCell->cellLatIndex = cellLatIndex;
	newCell->cellLonIndex = cellLonIndex;

	if (CachedCellsHead) {
		CachedCellsHead->cachePrev = newCell;
		newCell->cacheNext = CachedCellsHead;
		CachedCellsHead = newCell;
	} else {
		CachedCellsHead = newCell;
		CachedCellsTail = newCell;
	}

	cell_map_put(newCell);

	// Set the cell size to the largest possible size.  The actual size can't be known until the caller reads data
	// from a file.  Once the size is known the caller should call update_cache_size().

	newCell->cacheSize = TRN_MAX_CELL_SIZE;
	CacheSize += TRN_MAX_CELL_SIZE;

	*cell = newCell;
	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Update the size on a cached cell.

// Argments:

//   cell     The cell to update.
//   newSize  New size to set, may be zero.

static void update_cache_size(TCELL *cell, size_t newSize) {

	CacheSize -= cell->cacheSize;
	cell->cacheSize = newSize;
	CacheSize += newSize;
}


//---------------------------------------------------------------------------------------------------------------------
// Release a cell structure for re-use.  This frees up the cell data block (if any), removes the cell from the cache
// and index, and places it on the free cell list.  Structure content is zeroed.

// Arguments:

//   cell  The cell to release.

static void release_cell(TCELL *cell) {

	if (cell->cachePrev) {
		cell->cachePrev->cacheNext = cell->cacheNext;
	} else {
		CachedCellsHead = cell->cacheNext;
	}
	if (cell->cacheNext) {
		cell->cacheNext->cachePrev = cell->cachePrev;
	} else {
		CachedCellsTail = cell->cachePrev;
	}

	cell_map_remove(cell);

	mem_free(cell->cellData);
	CacheSize -= cell->cacheSize;

	memset(cell, 0, sizeof(TCELL));

	cell->cacheNext = FreeCellsHead;
	FreeCellsHead = cell;
}


//---------------------------------------------------------------------------------------------------------------------
// Search the map for a given set of coordinates, returns NULL if not found.  The coordinates and node location of the
// end of the search are kept in statics for use by cell_map_put() and cell_map_remove().

// Arguments:

//   databaseIndex               The database to match.
//   cellLatIndex, cellLonIndex  Cell coordinates to match.

// Returns NULL if not found, else pointer to the cell.

static short LastDatabaseIndex = -1;
static short LastCellLatIndex = 0;
static short LastCellLonIndex = 0;
static NODE *LastNode = NULL;
static int LastQuadIndex = 0;
static TCELL *LastCell = NULL;

static TCELL *cell_map_get(short databaseIndex, short cellLatIndex, short cellLonIndex) {

	TCELL *cell = NULL;

	LastDatabaseIndex = databaseIndex;
	LastCellLatIndex = cellLatIndex;
	LastCellLonIndex = cellLonIndex;

	NODE *node = MapTopNode;
	int quadIndex = 0;

	while (node) {

		if (cellLatIndex >= node->latIndex) {
			if (cellLonIndex >= node->lonIndex) {
				quadIndex = 0;
			} else {
				quadIndex = 1;
			}
		} else {
			if (cellLonIndex >= node->lonIndex) {
				quadIndex = 3;
			} else {
				quadIndex = 2;
			}
		}

		LastNode = node;
		LastQuadIndex = quadIndex;

		if (node->level > 0) {

			node = (NODE *)(node->child[quadIndex]);

		} else {

			cell = (TCELL *)(node->child[quadIndex]);
			while (cell) {
				if ((databaseIndex == cell->databaseIndex) && (cellLatIndex == cell->cellLatIndex) &&
						(cellLonIndex == cell->cellLonIndex)) {
					break;
				}
				cell = cell->indexNext;
			}
			break;
		}
	}

	LastCell = cell;

	return cell;
}


//---------------------------------------------------------------------------------------------------------------------
// Add a cell to the map, add a new node path as needed and link in the cell.  The basic assumption is that the three
// index values form coordinates that uniquely and specifically identify a cell; there can never be multiple cells
// with the same coordinates.  So if a cell already exists at the coordinates do nothing, that must be the same cell.

// Arguments:

//   cell  The cell to store in the map.

static void cell_map_put(TCELL *cell) {

	if ((LastDatabaseIndex != cell->databaseIndex) || (LastCellLatIndex != cell->cellLatIndex) ||
			(LastCellLonIndex != cell->cellLonIndex)) {
		cell_map_get(cell->databaseIndex, cell->cellLatIndex, cell->cellLonIndex);
	}

	if (LastCell) {
		return;
	}

	if (LastNode->level > 0) {

		NODE *node;
		NODE_POOL *pool = MapNodePools;

		while (pool->next && (NODE_POOL_SIZE == pool->freeNodeIndex)) {
			pool = pool->next;
		}

		while (LastNode->level > 0) {

			if (NODE_POOL_SIZE == pool->freeNodeIndex) {
				pool->next = (NODE_POOL *)mem_alloc(sizeof(NODE_POOL));
				pool = pool->next;
				pool->freeNodeIndex = 0;
				pool->next = NULL;
			}
			node = pool->nodes + pool->freeNodeIndex++;
			memset(node, 0, sizeof(NODE));

			node->level = LastNode->level - 1;
			node->latSize = LastNode->latSize / 2;
			node->lonSize = LastNode->lonSize / 2;
			switch (LastQuadIndex) {
				case 0:
				default: {
					node->latIndex = LastNode->latIndex + node->latSize;
					node->lonIndex = LastNode->lonIndex + node->lonSize;
					break;
				}
				case 1: {
					node->latIndex = LastNode->latIndex + node->latSize;
					node->lonIndex = LastNode->lonIndex - node->lonSize;
					break;
				}
				case 2: {
					node->latIndex = LastNode->latIndex - node->latSize;
					node->lonIndex = LastNode->lonIndex - node->lonSize;
					break;
				}
				case 3: {
					node->latIndex = LastNode->latIndex - node->latSize;
					node->lonIndex = LastNode->lonIndex + node->lonSize;
					break;
				}
			}

			LastNode->child[LastQuadIndex] = node;
			LastNode = node;
			if (LastCellLatIndex >= node->latIndex) {
				if (LastCellLonIndex >= node->lonIndex) {
					LastQuadIndex = 0;
				} else {
					LastQuadIndex = 1;
				}
			} else {
				if (LastCellLonIndex >= node->lonIndex) {
					LastQuadIndex = 3;
				} else {
					LastQuadIndex = 2;
				}
			}
		}
	}

	cell->indexPrev = NULL;
	cell->indexNext = (TCELL *)(LastNode->child[LastQuadIndex]);
	LastNode->child[LastQuadIndex] = cell;
	if (cell->indexNext) {
		cell->indexNext->indexPrev = cell;
	}
	LastCell = cell;
}


//---------------------------------------------------------------------------------------------------------------------
// Remove a cell from the map.  The node path is not removed even if now empty.

// Arguments:

//   cell  The cell to remove from the map.  If it isn't actually there, this does nothing.

static void cell_map_remove(TCELL *cell) {

	if ((LastDatabaseIndex != cell->databaseIndex) || (LastCellLatIndex != cell->cellLatIndex) ||
			(LastCellLonIndex != cell->cellLonIndex)) {
		cell_map_get(cell->databaseIndex, cell->cellLatIndex, cell->cellLonIndex);
	}

	if (LastCell) {
		if (LastCell->indexPrev) {
			LastCell->indexPrev->indexNext = LastCell->indexNext;
		} else {
			LastNode->child[LastQuadIndex] = LastCell->indexNext;
		}
		if (LastCell->indexNext) {
			LastCell->indexNext->indexPrev = LastCell->indexPrev;
		}
		LastCell = NULL;
	}
}


//---------------------------------------------------------------------------------------------------------------------
// Called to set a FILE stream to write cache statistics reports.  Can be called at any time, set to NULL to stop.

void report_terrain_stats(FILE *statsOut) {

	StatsOut = statsOut;
	if (StatsOut) {
		update_cache_stats(-1, -1);
	}
}


//---------------------------------------------------------------------------------------------------------------------
// Maintain cache statistics and report periodically.  Clear if either argument is < 0.

static void update_cache_stats(int databaseIndex, int statsIndex) {

	if (!StatsOut) {
		return;
	}

	static time_t lastReport = -1;
	static int counts[N_DATABASES][STATS_COUNT], showSizes = 1, showNoFree = 1;

	time_t now = time(NULL);
	int i, j;

	if ((lastReport < 0) || (databaseIndex < 0) || (statsIndex < 0)) {

		lastReport = now;
		for (i = 0; i < N_DATABASES; i++) {
			for (j = 0; j < STATS_COUNT; j++) {
				counts[i][j] = 0;
			}
		}

	} else {

		if ((now - lastReport) >= STATS_REPORT_INTERVAL) {
			if (showSizes) {
				fprintf(StatsOut, "TERR:  TotalCacheSize = %ld  MaxCacheSize=%ld  MaxCachedCells=%d\n", TotalCacheSize,
					MaxCacheSize, MaxCachedCells);
				showSizes = 0;
			}
			fprintf(StatsOut, "TERR:  CacheSize=%ld\n", CacheSize);
			if (showNoFree && !FreeCellsHead) {
				fprintf(StatsOut, "TERR:  free cell list is empty\n");
				showNoFree = 0;
			}
			for (i = 0; i < N_DATABASES; i++) {
				if (counts[i][STATS_TOTAL]) {
					fprintf(StatsOut, "TERR:  db=%d lookups=%d seawater=%d cached=%d loaded=%d\n", i,
						counts[i][STATS_TOTAL], counts[i][STATS_SEAWATER], counts[i][STATS_CACHED],
						counts[i][STATS_LOADED]);
					for (j = 0; j < STATS_COUNT; j++) {
						counts[i][j] = 0;
					}
				}
			}
			lastReport = now;
		}

		counts[databaseIndex][STATS_TOTAL]++;
		counts[databaseIndex][statsIndex]++;
	}
}
