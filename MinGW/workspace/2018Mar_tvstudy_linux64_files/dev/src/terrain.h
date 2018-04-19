//
//  terrain.h
//  TVStudy
//
//  Copyright (c) 2016-2017 Hammett & Edison, Inc.  All rights reserved.


// Header file for terrain.c module.
#include <stdint.h>
#include <inttypes.h>
#include <sys/types.h>

#define TERR_DB0   0   // Values for terrain lookup request.  These do not identify a specific database, they represent
#define TERR_DB1   1   //  target resolutions, multiple databases may be searched in pre-defined sequence for each.
#define TERR_DB3   2
#define TERR_DB30  3

#define TRN_DB_NUMBER_USGS        1   // Database identification numbers (in file headers) and directory names.
#define TRN_DB_NAME_USGS     "usgs"
#define TRN_DB_NUMBER_NED         2
#define TRN_DB_NAME_NED       "ned"
#define TRN_DB_NUMBER_GTOPO       3
#define TRN_DB_NAME_GTOPO   "gtopo"
#define TRN_DB_NUMBER_SRTM        4
#define TRN_DB_NAME_SRTM     "srtm"
#define TRN_DB_NUMBER_CDED        5
#define TRN_DB_NAME_CDED     "cded"
#define TRN_DB_NUMBER_CEM         6
#define TRN_DB_NAME_CEM       "cem"
#define TRN_DB_NUMBER_NED13       7
#define TRN_DB_NAME_NED13   "ned13"

#define TRN_CELLS_PER_DEGREE  8   // Cells per degree.
#define TRN_CELLS_PER_FILE   64   // Cells per file.

#define TRN_STATUS_SEAWATER  0   // Values found in the block indices - block is seawater, all zero elevations;
#define TRN_STATUS_NODATA    1   //  block does not exist in the database, but it might in another;
#define TRN_STATUS_DATA      2   //  block exists in the database;
#define TRN_STATUS_UNKNOWN   3   //  unknown, attempt to open block file to determine status.

#define TRN_FILE_STATUS_SIZE 64800   // Size of file status arrays.

#define TRN_MAGIC_V1   49796   // Magic numbers for database files.  V1 is legacy, should not occur.
#define TRN_MAGIC_V2   49798   // V2 is normal case for all files.
#define TRN_MAGIC_V2_U 49799   // V2_U is V2 created by post-install user action as opposed to distribution.

#define TRN_MAGIC_V1_SWAP   -2067660800   // Magic numbers byte-swapped.
#define TRN_MAGIC_V2_SWAP   -2034106368
#define TRN_MAGIC_V2_U_SWAP -2017329152

typedef struct {                                    // Header from a database file.
	int32_t magicNumber;                            // Magic number.
	int32_t fileID;                                 // File ID, south lat * 10000 + east lon * 10 + db #.
	u_int8_t cellFlags[TRN_CELLS_PER_FILE];         // Compression type and other flags, see below.
	int16_t minimumElevation[TRN_CELLS_PER_FILE];   // Minimum elevations for delta-encoded cells.
	int16_t latPointCount[TRN_CELLS_PER_FILE];      // Number of latitude points (rows) in cell.
	int16_t lonPointCount[TRN_CELLS_PER_FILE];      // Number of longitude points (columns) in cell.
	u_int32_t recordSize[TRN_CELLS_PER_FILE];       // Sizes of cell data blocks.
	u_int32_t recordOffset[TRN_CELLS_PER_FILE];     // File offsets to cell data blocks.
} TRN_HEADER;

// Masks for extracting bits from the cellFlags value.

#define TRN_COMPRESSION_MASK 0x1F   // Compression code.
#define TRN_GRID_OFFSET_MASK 0x20   // Grid offset flag, true for cell-center data with extra row/column.
#define TRN_NO_DATA_MASK     0x40   // Missing data flag, if set the cell has no data.

// Compression types.  Values 1-15 mean bit-packing with that many bits per value.

#define TRN_COMPRESSION_ZERO 0    // Constant elevation cell.
#define TRN_COMPRESSION_NONE 16   // No compression, signed 16-bit values.

#define TRN_MAX_CELL_SIZE 3700000   // Max size of a cell data record in any file.  Unpacked size of a 1/3-second cell.

// Global flags related to user-generated terrain data.  The version is the most-recent modification time from block
// index files for any database that might contain user-generated files, currently just NED 1/3-second.  The requested
// flag is set when a study is opened if a database that might have user-generated data is requested by parameters.
// The used flag is cleared when a study is opened, then set if any user-generated file is actually opened during
// extraction or if any cache files are loaded that were written when the flag was true.  That is all handled by the
// add_requested_terrain() and clear_requested_terrain() functions.  The version and flags are used to invalidate
// caches and report in output files when a run depends on user-generated data.  If the version is needed for cache
// checks before any actual extraction, initialize_terrain() must be called directly first.

extern time_t UserTerrainVersion;
extern int UserTerrainRequested;
extern int UserTerrainUsed;

int initialize_terrain(int fraction);
int get_max_memory_fraction();
int get_max_grid_count(double cellSize, double kmPerDegree);
void add_requested_terrain(int db);
void clear_requested_terrain();
int terrain_profile(double latStartDegrees, double lonStartDegrees, double bearingDegrees, double distanceKilometers,
	double pointsPerKilometer, int database, int maximumProfilePointCount, float *profileElevations,
	int *profilePointCount, double kmPerDegree);
int terrain_point(double latDegrees, double lonDegrees, int database, float *elevation);
int compute_haat(double startLat, double startLon, double heightAMSL, double *haat, int haatCount, int terrainDb,
	double startDist, double endDist, double distInc, double kmPerDegree, FILE *debugOut);
int compute_haat_radial(double startLat, double startLon, double azimuth, double heightAMSL, double *haat,
	int terrainDb, double startDist, double endDist, double distInc, double kmPerDegree, FILE *debugOut);
void report_terrain_stats(FILE *statsOut);
