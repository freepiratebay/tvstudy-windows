//
//  cache.c
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.


// Functions for working with flat-file caching system.

// Each source record in a study has a source cache file containing the source parameters, pattern data, and contour.
// There are a few calculated values in the source cache record (most significant is the contour data), but most values
// are simply copies of the values from the source database record.  The source cache is more a "fingerprint" than a
// cache.  Cached values are compared to the current values from the database, if there is any difference all cached
// data for the source is discarded.

// A source that has been studied for coverage has a desired cell cache containing desired field strength data for the
// source.  The desired cell cache is always complete; all study points in the source's service area are in its desired
// cell cache, and nothing more.  The desired cache is always entirely replaced on write, and on read all records in
// the file are always loaded.

// A source that has been studied as an undesired will have an undesired cell cache containing undesired field strength
// data for the source.  New data may be appended to existing undesired cell caches on write during subsequent study
// runs.  An undesired cache may thus contain data not needed for a particular study, and not all data needed is
// necessarily in the cache.  On read, only needed cells (in the current study grid) are loaded, others are skipped.

// In a global-grid study there will be just one undesired cache per source, containing all points studied regardless
// of the desired source involved.  In a local-grid study, there is a separate undesired cell cache for every unique
// pairing of desired and undesired sources because the cell grids are unique to a specific desired source.

// To simplify caching and allow data from different caches to easily be merged, in all cell caches each data record
// contains just one field strength value for one study point in one cell, along with the complete cell and point
// parameters.  In other words when a study point has multiple field strengths and/or a cell has multiple study points,
// there will be multiple records in the caches with the cell and point parameters repeated redundantly in each.

// For DTS facilities, only the parent source has caches; data for the individual DTS transmitter sources is in-line
// following data for the parent source, using abbreviated record formats.

// Multiple processes can simultaneously use the same set of cache files, flock() is used to manage concurrent access.
// The source cache file is always opened and locked before any operation on any file for a particular source.  Reads
// need a shared lock, writes need an exclusive lock.  This depends on the study database locking.  Any two processes
// sharing the same cache files are using the same study database, meaning they can only have a shared database run
// lock.  That means the database contents cannot change during execution in either process, so both have exactly the
// same potential calculation results that might be cached and there is no concern about conflicting data.  The only
// concern is avoiding duplication should both processes try to cache new results for the same source.

// When writing desired-signal caches the entire file is always replaced in one lock context so there is no possibility
// of duplication.  However when writing undesired-signal caches, new data may be appended to an existing cache file.
// To avoid duplication, undesired cache writes will occur only if there has been no other write to a particular cache
// file by some other process since it was last read or written by this one.  Caching is always optional so the
// inability to write new data to cache is never a problem, results that were not cached will simply be re-calcuated
// if needed later.  The record checksum is used to detect writes, the checksum from the last record in a file is
// stored after any read or write operation, then compared to actual file contents before any write.


#include "tvstudy.h"

#include <sys/file.h>
#include <sys/stat.h>
#include <sys/errno.h>


//---------------------------------------------------------------------------------------------------------------------
// Each cache file starts with a header including a magic number and version information.  See do_source_read() for
// details of the terrain-related header fields.  In the cell caches there are running checksum values, and in desired
// signal caches the magic number is written again at the end.  The purpose of those features is primarily to detect
// invalid external manipulation of the file contents that changes the record sequence, the original design included a
// separate utility for manipulating cache files.  That never materialized, however those features have been preserved
// since they add some degree of robustness in detecting file corruption and also provide a means to detect concurrent
// use by other processes.  Desired and undesired cell caches have the same record format.

#define SOURCE_FILE_MAGIC          4163647
#define DESIRED_CELL_FILE_MAGIC    4153648
#define UNDESIRED_CELL_FILE_MAGIC  4143649

typedef struct {
	int magicNumber;
	int cacheVersion;
	time_t userTerrainVersion;
	int userTerrainRequested;
	int userTerrainUsed;
} CACHE_HEADER;

typedef struct {
	INDEX_BOUNDS cellBounds;
	double latitude;
	double longitude;
	double dtsMaximumDistance;
	double frequency;
	double heightAMSL;
	double heightAGL;
	double overallHAAT;
	double peakERP;
	double contourERP;
	double hpatOrientation;
	double vpatElectricalTilt;
	double vpatMechanicalTilt;
	double vpatTiltOrientation;
	double cellArea;
	size_t vpatSize;
	size_t mpatSize;
	int studyModCount;
	int sourceModCount;
	int serviceAreaModCount;
	int cellLonSize;
	unsigned short sourceKey;
	short serviceTypeKey;
	short channel;
	short countryKey;
	short hasHpat;
	short hasConthpat;
	short hasVpat;
	short hasMpat;
	short serviceAreaValid;
	short hasContour;
	short contourMode;
	short contourCount;
} SOURCE_CACHE_RECORD;

typedef struct {
	double latitude;
	double longitude;
	double heightAMSL;
	double heightAGL;
	double overallHAAT;
	double peakERP;
	double contourERP;
	double hpatOrientation;
	double vpatElectricalTilt;
	double vpatMechanicalTilt;
	double vpatTiltOrientation;
	size_t vpatSize;
	size_t mpatSize;
	int serviceAreaModCount;
	unsigned short sourceKey;
	short hasHpat;
	short hasConthpat;
	short hasVpat;
	short hasMpat;
	short hasContour;
	short contourMode;
	short contourCount;
} DTS_SOURCE_CACHE_RECORD;

typedef struct {
	double latitude;
	double longitude;
	int cellLatIndex;
	int cellLonIndex;
	int population;
	int households;
	float area;
	float elevation;
	float bearing;
	float reverseBearing;
	float distance;
	float fieldStrength;
	unsigned short sourceKey;
	short countryKey;
	short landCoverType;
	short clutterType;
	short percentTime;
	short status;
	u_int32_t checksum;
} CELL_CACHE_RECORD;

typedef struct {
	float bearing;
	float reverseBearing;
	float distance;
	float fieldStrength;
	unsigned short sourceKey;
	short status;
} DTS_CELL_CACHE_RECORD;

static char *source_file(unsigned short sourceKey);
static char *desired_cell_file(unsigned short sourceKey);
static char *undesired_cell_file(unsigned short sourceKey, unsigned short desiredSourceKey);
static int do_source_read(SOURCE *source, FILE *cache);
static int do_read_data_blocks(FILE *cache, SOURCE *source, SOURCE *toSource, short hasHpat, short hasConthpat,
	short hasVpat, size_t vpatSize, short hasMpat, size_t mpatSize, short hasContour, short contourMode,
	short contourCount);
static int is_diff(double d1, double d2);
static int fis_diff(float f1, float f2);


//---------------------------------------------------------------------------------------------------------------------
// Functions to build file names.

// Arguments:

//   sourceKey  Source key for the source.

// Return source cache file name.

static char *source_file(unsigned short sourceKey) {

	static char name[MAX_STRING];

	snprintf(name, MAX_STRING, "%s\\%s\\%d\\source\\%05d", CACHE_DIRECTORY_NAME, DatabaseID, StudyKey, sourceKey);

	return name;
}


//---------------------------------------------------------------------------------------------------------------------
// Arguments:

//   sourceKey  Source key for the desired source.

// Return desired cell cache file name.

static char *desired_cell_file(unsigned short sourceKey) {

	static char name[MAX_STRING];

	snprintf(name, MAX_STRING, "%s\\%s\\%d\\desired_cell\\%05d", CACHE_DIRECTORY_NAME, DatabaseID, StudyKey, sourceKey);

	return name;
}


//---------------------------------------------------------------------------------------------------------------------
// Undesired cell files are specific to the desired sources in local-grid mode so both source keys are in the name.

// Arguments:

//   sourceKey         Source key for the undesired source.
//   desiredSourceKey  Source key for the related desired source when GridType is GRID_TYPE_LOCAL, else not used.

// Return undesired cell cache file name.

static char *undesired_cell_file(unsigned short sourceKey, unsigned short desiredSourceKey) {

	static char name[MAX_STRING];

	if (GRID_TYPE_GLOBAL == Params.GridType) {
		snprintf(name, MAX_STRING, "%s\\%s\\%d\\undesired_cell\\%05d", CACHE_DIRECTORY_NAME, DatabaseID, StudyKey,
			sourceKey);
	} else {
		snprintf(name, MAX_STRING, "%s\\%s\\%d\\undesired_cell\\%05d_%05d", CACHE_DIRECTORY_NAME, DatabaseID, StudyKey,
			sourceKey, desiredSourceKey);
	}

	return name;
}


//---------------------------------------------------------------------------------------------------------------------
// Clear all cached data for a source.  Deletes all files related to the source, in the undesired cell directory this
// will match files for signals from the source and those to the source from others in local-grid studies.

// Arguments:

//   source  The source to clear.

void clear_cache(SOURCE *source) {

	char command[MAX_STRING];

	snprintf(command, MAX_STRING, "for /r %s\\%s\\%d\\ %%i in (*%05d*) do del /Q %%i", CACHE_DIRECTORY_NAME, DatabaseID, StudyKey,
		source->sourceKey);
	system(command);

	source->serviceAreaValid = 0;
	source->cached = 0;
	source->hasDesiredCache = 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Verify the state of the cache, create all directories as needed.  Should be called before any cache operations are
// performed, other functions will not attempt to create directories if files do not open.  Return non-zero on error,
// any error here is fatal.  Note if the study needs_update flag is set all source records will be updated regardless
// so all existing caches would be cleared as sources are loaded.  To speed things along later, in that case do a
// wholesale delete of the entire study cache here.  Also clears out cruft left over for deleted source keys, the UI
// app tries to do that but isn't always successful.

// This must be called after any change to the global StudyKey.

// Arguments:

//   studyNeedsUpdate  True if the needs_update flag is set on the study record.

// Return is <0 on a serious error, >0 on a minor error, 0 for no error.

int cache_setup(int studyNeedsUpdate) {

	if (!StudyKey) {
		log_error("cache_setup() called with no study open");
		return 1;
	}

	if (mkdir(CACHE_DIRECTORY_NAME, 0750)) {
		if (errno != EEXIST) {
			log_error("Cache setup failed: mkdir %s returned errno=%d", CACHE_DIRECTORY_NAME, errno);
			return -1;
		}
	}

	char dir[2 * MAX_STRING];

	snprintf(dir, (2 * MAX_STRING), "%s/%s", CACHE_DIRECTORY_NAME, DatabaseID);
	if (mkdir(dir, 0750)) {
		if (errno != EEXIST) {
			log_error("Cache setup failed: mkdir %s returned errno=%d", dir, errno);
			return -1;
		}
	}

	if (studyNeedsUpdate) {
		char command[MAX_STRING];
		snprintf(command, MAX_STRING, "rd /S/Q/ %s\\%s\\%d", CACHE_DIRECTORY_NAME, DatabaseID, StudyKey);
		system(command);
	}

	snprintf(dir, (2 * MAX_STRING), "%s\\%s\\%d", CACHE_DIRECTORY_NAME, DatabaseID, StudyKey);
	if (mkdir(dir, 0750)) {
		if (errno != EEXIST) {
			log_error("Cache setup failed: mkdir %s returned errno=%d", dir, errno);
			return -1;
		}
	}

	snprintf(dir, (2 * MAX_STRING), "%s\\%s\\%d\\source", CACHE_DIRECTORY_NAME, DatabaseID, StudyKey);
	if (mkdir(dir, 0750)) {
		if (errno != EEXIST) {
			log_error("Cache setup failed: mkdir %s returned errno=%d", dir, errno);
			return -1;
		}
	}

	snprintf(dir, (2 * MAX_STRING), "%s\\%s\\%d\\desired_cell", CACHE_DIRECTORY_NAME, DatabaseID, StudyKey);
	if (mkdir(dir, 0750)) {
		if (errno != EEXIST) {
			log_error("Cache setup failed: mkdir %s returned errno=%d", dir, errno);
			return -1;
		}
	}

	snprintf(dir, (2 * MAX_STRING), "%s\\%s\\%d\\undesired_cell", CACHE_DIRECTORY_NAME, DatabaseID, StudyKey);
	if (mkdir(dir, 0750)) {
		if (errno != EEXIST) {
			log_error("Cache setup failed: mkdir %s returned errno=%d", dir, errno);
			return -1;
		}
	}

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Read cached data for a source.  If the cache read fails due to missing file, bad read, mismatched data values, etc.,
// this will clear the cache for the source so everything is re-calculated.  If source->needsUpdate is set meaning the
// record has been changed, this will just clear the cache without any attempt to read.  For all cache reads, a failure
// to open the file is not logged as an error, so these can be called speculatively.  Once the file is open, however,
// problems reading and verifying content are logged, and the file is also deleted.  This returns true if source data
// is successfully read from cache, false if not meaning derived values must be re-calculated.

// Arguments:

//   source         The source to read.

// Return is true if the source was read from cache, false on read failure or any error.

int read_source_cache(SOURCE *source) {

	int result = 0;

	source->cached = 0;
	source->hasDesiredCache = 0;

	// If the read succeeds, check for a desired cell cache for the source.  It's enough to just test if the file
	// exists here because this is only a "hint" used for optimizing population queries; nothing bad happens later if
	// the file can't actually be read.

	if (!source->needsUpdate) {
		FILE *cache = fopen(source_file(source->sourceKey), "rb");
		if (cache) {
			flock(fileno(cache), LOCK_SH);
			result = do_source_read(source, cache);
			if (result) {
				source->cached = 1;
				if (!access(desired_cell_file(source->sourceKey), R_OK)) {
					source->hasDesiredCache = 1;
				}
			}
			fclose(cache);
		}
	}

	if (!result) {
		clear_cache(source);
	}

	return result;
}

// Do the actual cache file read, return true for successful read, false for bad data or error.

static int do_source_read(SOURCE *source, FILE *cache) {

	static int showVersionMessageForStudyKey = 0, showTerrainMessageForStudyKey = 0;

	// Read the header, check magic number and file version.  If the version does not match the old data is discarded,
	// however assuming this really is a version change and not just a bad file this is going to happen a whole bunch
	// of times, so show a generic message and only show it once per study run.

	CACHE_HEADER header;
	size_t recordSize = sizeof(CACHE_HEADER);
	if ((fread(&header, 1, recordSize, cache) != recordSize) || (SOURCE_FILE_MAGIC != header.magicNumber)) {
		log_error("Bad cache for sourceKey=%d: missing or invalid file header", source->sourceKey);
		return 0;
	}

	if (TVSTUDY_CACHE_VERSION != header.cacheVersion) {
		if (StudyKey != showVersionMessageForStudyKey) {
			log_message("Cache version number changed, old cache data cleared");
			showVersionMessageForStudyKey = StudyKey;
		}
		return 0;
	}

	// Check the header fields related to user-generated terrain data use, see comments in terrain.h about the version
	// and flags.  The three fields in the cache header are simply copies of the like-named globals as of the time the
	// cache file was written.  If the state of the requested flag or version in the header do not match the current
	// state of the globals, discard the entire cache, except the version is not checked if both requested flags are
	// false.  A change in the requested flag is unlikely here since currently that could only follow a change in the
	// study parameters which should separately invalidate all caches by setting needsUpdate; but it never hurts to be
	// paranoid.  Even if user-generated data was not actually used in the original run the cache has to be discarded
	// if the version changes, since the change might be that new terrain data has been added that will be used now.
	// If the requested flag and version check passes, update the global used flag according to the header used flag.
	// The used flag is checked to generate a message in report outputs indicating the run depends on user-generated
	// data.  The version-change case follows the same logic as for the file version above; a once-per-study message.

	if ((header.userTerrainRequested != UserTerrainRequested) ||
			(header.userTerrainRequested && (header.userTerrainVersion != UserTerrainVersion))) {
		if (StudyKey != showTerrainMessageForStudyKey) {
			log_message("Terrain database modified, old cache data cleared");
			showTerrainMessageForStudyKey = StudyKey;
		}
		return 0;
	}
	if (header.userTerrainUsed) {
		UserTerrainUsed = 1;
	}

	// Read the source record, if short discard the cache.

	SOURCE_CACHE_RECORD record;
	recordSize = sizeof(SOURCE_CACHE_RECORD);
	if (fread(&record, 1, recordSize, cache) != recordSize) {
		log_error("Bad cache for sourceKey=%d: short read (1)", source->sourceKey);
		return 0;
	}

	// If the modCount values for the study or source records do not match, the database records have changed since
	// this cache was written.  That occurs when edits are made then another study engine using a different cache
	// location does the record updates and clears the needsUpdate flags.  The update calculations do not have to be
	// repeated here, but the cache is invalid so is discarded.  Also if the modCount for a geography record defining
	// the service area changes the cache is invalid.

	if ((record.studyModCount != StudyModCount) || (record.sourceModCount != source->modCount) ||
			(record.serviceAreaModCount != source->serviceAreaModCount)) {
		return 0;
	}

	// Compare parameters from the record to the source, these should always match.  If not that means the database
	// record changed without needs_update and mod_count being changed, in other words an app did not follow protocol
	// or somebody used an SQL shell to manually alter the data.  But regardless of the cause, the entire cache for
	// the source is assumed invalid.

	if ((record.sourceKey != source->sourceKey) ||
			(record.serviceTypeKey != source->serviceTypeKey) ||
			(record.channel != source->channel) ||
			(record.countryKey != source->countryKey) ||
			is_diff(record.latitude, source->latitude) ||
			is_diff(record.longitude, source->longitude) ||
			is_diff(record.dtsMaximumDistance, source->dtsMaximumDistance) ||
			is_diff(record.heightAMSL, source->actualHeightAMSL) ||
			is_diff(record.heightAGL, source->heightAGL) ||
			is_diff(record.overallHAAT, source->actualOverallHAAT) ||
			is_diff(record.peakERP, source->peakERP) ||
			is_diff(record.contourERP, source->contourERP) ||
			(record.hasHpat && !source->hasHpat) || (!record.hasHpat && source->hasHpat) ||
			is_diff(record.hpatOrientation, source->hpatOrientation) ||
			(record.hasVpat && !source->hasVpat) || (!record.hasVpat && source->hasVpat) ||
			(record.hasMpat && !source->hasMpat) || (!record.hasMpat && source->hasMpat) ||
			is_diff(record.vpatElectricalTilt, source->vpatElectricalTilt) ||
			is_diff(record.vpatMechanicalTilt, source->vpatMechanicalTilt) ||
			is_diff(record.vpatTiltOrientation, source->vpatTiltOrientation)) {
		log_error("Bad cache for sourceKey=%d: parameters don't match", source->sourceKey);
		return 0;
	}

	// Restore derived values from cache.

	source->cellArea = record.cellArea;
	source->cellBounds = record.cellBounds;
	source->cellLonSize = record.cellLonSize;
	source->serviceAreaValid = record.serviceAreaValid;

	// Special behavior for wireless records, restore the frequency from the cache record.  When a study is first
	// opened, wireless records do not have a frequency assigned.  That is set by scenario parameter when a scenario
	// runs.  The scenario load will check for a frequency change and will reset the source's cache as needed.  This
	// restore is just to initialize the frequency to the last-cached value at study open.  See source.c.

	if (RECORD_TYPE_WL == source->recordType) {
		source->frequency = record.frequency;
	}

	// Read pattern and contour data as needed.

	if (do_read_data_blocks(cache, source, source, record.hasHpat, record.hasConthpat, record.hasVpat, record.vpatSize,
			record.hasMpat, record.mpatSize, record.hasContour, record.contourMode, record.contourCount)) {
		return 0;
	}

	// For a DTS parent source, the reference facility and the individual DTS transmitter sources are stored in the
	// parent's file immediately following.  The individual transmitters use a shortened record format since much of
	// the information is always the same as the parent.  The reference facility is a full record.  Note the secondary
	// sources do not have separate cell bounds or size information, the parent defines those for the whole service
	// area.  Likewise the service-area-valid flag is only on the parent.

	if (source->isParent) {

		SOURCE *dtsSource = source->dtsRefSource;

		if (fread(&record, 1, recordSize, cache) != recordSize) {
			log_error("Bad cache for sourceKey=%d: short read (2)", source->sourceKey);
			return 0;
		}

		// This should never happen since the reference facility always uses a contour service area never a geography.

		if (record.serviceAreaModCount != dtsSource->serviceAreaModCount) {
			return 0;
		}

		if ((record.sourceKey != dtsSource->sourceKey) ||
				(record.serviceTypeKey != dtsSource->serviceTypeKey) ||
				(record.channel != dtsSource->channel) ||
				(record.countryKey != dtsSource->countryKey) ||
				is_diff(record.latitude, dtsSource->latitude) ||
				is_diff(record.longitude, dtsSource->longitude) ||
				is_diff(record.heightAMSL, dtsSource->actualHeightAMSL) ||
				is_diff(record.heightAGL, dtsSource->heightAGL) ||
				is_diff(record.overallHAAT, dtsSource->actualOverallHAAT) ||
				is_diff(record.peakERP, dtsSource->peakERP) ||
				is_diff(record.contourERP, dtsSource->contourERP) ||
				(record.hasHpat && !dtsSource->hasHpat) || (!record.hasHpat && dtsSource->hasHpat) ||
				is_diff(record.hpatOrientation, dtsSource->hpatOrientation) ||
				(record.hasVpat && !dtsSource->hasVpat) || (!record.hasVpat && dtsSource->hasVpat) ||
				(record.hasMpat && !dtsSource->hasMpat) || (!record.hasMpat && dtsSource->hasMpat) ||
				is_diff(record.vpatElectricalTilt, dtsSource->vpatElectricalTilt) ||
				is_diff(record.vpatMechanicalTilt, dtsSource->vpatMechanicalTilt) ||
				is_diff(record.vpatTiltOrientation, dtsSource->vpatTiltOrientation)) {
			log_error("Bad cache for sourceKey=%d: parameters don't match for DTS reference sourceKey=%d",
				source->sourceKey, dtsSource->sourceKey);
			return 0;
		}

		dtsSource->cellArea = source->cellArea;
		dtsSource->cellLonSize = source->cellLonSize;

		if (do_read_data_blocks(cache, source, dtsSource, record.hasHpat, record.hasConthpat, record.hasVpat,
				record.vpatSize, record.hasMpat, record.mpatSize, record.hasContour, record.contourMode,
				record.contourCount)) {
			return 0;
		}

		DTS_SOURCE_CACHE_RECORD dtsRecord;
		recordSize = sizeof(DTS_SOURCE_CACHE_RECORD);

		for (dtsSource = source->dtsSources; dtsSource; dtsSource = dtsSource->next) {

			if (fread(&dtsRecord, 1, recordSize, cache) != recordSize) {
				log_error("Bad cache for sourceKey=%d: short read (3)", source->sourceKey);
				return 0;
			}

			if (dtsRecord.serviceAreaModCount != dtsSource->serviceAreaModCount) {
				return 0;
			}

			if ((dtsRecord.sourceKey != dtsSource->sourceKey) ||
					is_diff(dtsRecord.latitude, dtsSource->latitude) ||
					is_diff(dtsRecord.longitude, dtsSource->longitude) ||
					is_diff(dtsRecord.heightAMSL, dtsSource->actualHeightAMSL) ||
					is_diff(dtsRecord.heightAGL, dtsSource->heightAGL) ||
					is_diff(dtsRecord.overallHAAT, dtsSource->actualOverallHAAT) ||
					is_diff(dtsRecord.peakERP, dtsSource->peakERP) ||
					is_diff(dtsRecord.contourERP, dtsSource->contourERP) ||
					(dtsRecord.hasHpat && !dtsSource->hasHpat) || (!dtsRecord.hasHpat && dtsSource->hasHpat) ||
					is_diff(dtsRecord.hpatOrientation, dtsSource->hpatOrientation) ||
					(dtsRecord.hasVpat && !dtsSource->hasVpat) || (!dtsRecord.hasVpat && dtsSource->hasVpat) ||
					(dtsRecord.hasMpat && !dtsSource->hasMpat) || (!dtsRecord.hasMpat && dtsSource->hasMpat) ||
					is_diff(dtsRecord.vpatElectricalTilt, dtsSource->vpatElectricalTilt) ||
					is_diff(dtsRecord.vpatMechanicalTilt, dtsSource->vpatMechanicalTilt) ||
					is_diff(dtsRecord.vpatTiltOrientation, dtsSource->vpatTiltOrientation)) {
				log_error("Bad source cache for sourceKey=%d: parameters don't match for DTS sourceKey=%d",
					source->sourceKey, dtsSource->sourceKey);
				return 0;
			}

			dtsSource->cellArea = source->cellArea;
			dtsSource->cellLonSize = source->cellLonSize;

			if (do_read_data_blocks(cache, source, dtsSource, dtsRecord.hasHpat, dtsRecord.hasConthpat,
					dtsRecord.hasVpat, dtsRecord.vpatSize, dtsRecord.hasMpat, dtsRecord.mpatSize, dtsRecord.hasContour,
					dtsRecord.contourMode, dtsRecord.contourCount)) {
				return 0;
			}
		}
	}

	// All good.

	return 1;
}

// Read data blocks for patterns and contour following a source cache record.  Return 1 on error, 0 on success.

static int do_read_data_blocks(FILE *cache, SOURCE *source, SOURCE *toSource, short hasHpat, short hasConthpat,
		short hasVpat, size_t vpatSize, short hasMpat, size_t mpatSize, short hasContour, short contourMode,
		short contourCount) {

	size_t recordSize;

	// Horizontal pattern data, size is always 360 doubles.

	if (hasHpat) {

		recordSize = 360 * sizeof(double);
		double *pat = (double *)mem_alloc(recordSize);

		if (fread(pat, 1, recordSize, cache) != recordSize) {
			mem_free(pat);
			log_error("Bad cache for sourceKey=%d: data block short read (1)", source->sourceKey);
			return 1;
		}

		toSource->hpat = pat;
	}

	// Derived horizontal pattern for contour projection, also always 360 doubles.

	if (hasConthpat) {

		recordSize = 360 * sizeof(double);
		double *pat = (double *)mem_alloc(recordSize);

		if (fread(pat, 1, recordSize, cache) != recordSize) {
			mem_free(pat);
			log_error("Bad cache for sourceKey=%d: data block short read (5)", source->sourceKey);
			return 1;
		}

		toSource->conthpat = pat;
	}

	// The VPAT and MPAT structures are allocated as a single block, internal pointers have to be updated after read.
	// Size is variable, read from main source record.

	if (hasVpat) {

		recordSize = vpatSize;
		VPAT *vpt = (VPAT *)mem_alloc(recordSize);

		if (fread(vpt, 1, recordSize, cache) != recordSize) {
			mem_free(vpt);
			log_error("Bad cache for sourceKey=%d: data block short read (2)", source->sourceKey);
			return 1;
		}

		vpt->dep = (double *)(vpt + 1);
		vpt->pat = vpt->dep + vpt->np;

		toSource->vpat = vpt;
	}

	if (hasMpat) {

		recordSize = mpatSize;
		MPAT *mpt = (MPAT *)mem_alloc(recordSize);

		if (fread(mpt, 1, recordSize, cache) != recordSize) {
			mem_free(mpt);
			log_error("Bad cache for sourceKey=%d: data block short read (3)", source->sourceKey);
			return 1;
		}

		mpt->value = (double *)(mpt + 1);
		mpt->np = (int *)(mpt->value + mpt->ns);
		mpt->angle = (double **)(mpt->np + mpt->ns);
		mpt->pat = mpt->angle + mpt->ns;
		double *ptr = (double *)(mpt->pat + mpt->ns);
		int i;
		for (i = 0; i < mpt->ns; i++) {
			mpt->angle[i] = ptr;
			ptr += mpt->np[i];
			mpt->pat[i] = ptr;
			ptr += mpt->np[i];
		}

		toSource->mpat = mpt;
	}

	// Read contour data if it exists.

	if (hasContour) {

		CONTOUR *contour = contour_alloc(toSource->latitude, toSource->longitude, (int)contourMode, (int)contourCount);
		recordSize = contourCount * sizeof(double);
		if (fread(contour->distance, 1, recordSize, cache) != recordSize) {
			contour_free(contour);
			log_error("Bad cache for sourceKey=%d: data block short read (4)", source->sourceKey);
			return 1;
		}
		toSource->contour = contour;
	}

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Write a source record, and contour data if it exists.  The only error condition returned here is a failure to open
// the file, and that is non-fatal.  All cache writes are essentially fire-and-forget, the caller can always continue
// regardless of success or failure on a cache write.  Because of robustness on reads, bad or obsolete data won't load
// and read errors will clear the cache.

// This will write a new file regardless of the current value of the cached flag, it may be called to save contour data
// for a source previously cached without that.

// Arguments:

//   source         The source to write.

// Return is <0 for serious error, >0 for minor error, 0 for no error.  Currently only minor errors possible here.

int write_source_cache(SOURCE *source) {

	FILE *cache = fopen(source_file(source->sourceKey), "wb");
	if (!cache) {
		log_error("Source cache write canceled for sourceKey=%d: cannot open file", source->sourceKey);
		return 1;
	}

	flock(fileno(cache), LOCK_EX);

	CACHE_HEADER header;

	header.magicNumber = SOURCE_FILE_MAGIC;
	header.cacheVersion = TVSTUDY_CACHE_VERSION;
	header.userTerrainVersion = UserTerrainVersion;
	header.userTerrainRequested = UserTerrainRequested;
	header.userTerrainUsed = UserTerrainUsed;

	fwrite(&header, 1, sizeof(CACHE_HEADER), cache);

	SOURCE_CACHE_RECORD record;
	memset(&record, 0, sizeof(SOURCE_CACHE_RECORD));

	record.sourceKey = source->sourceKey;
	record.serviceTypeKey = source->serviceTypeKey;
	record.channel = source->channel;
	record.countryKey = source->countryKey;
	record.latitude = source->latitude;
	record.longitude = source->longitude;
	record.dtsMaximumDistance = source->dtsMaximumDistance;
	record.frequency = source->frequency;
	record.heightAMSL = source->actualHeightAMSL;
	record.heightAGL = source->heightAGL;
	record.overallHAAT = source->actualOverallHAAT;
	record.peakERP = source->peakERP;
	record.contourERP = source->contourERP;
	record.hasHpat = source->hasHpat;
	record.hasConthpat = (NULL != source->conthpat);
	record.hpatOrientation = source->hpatOrientation;
	record.hasVpat = source->hasVpat;
	if (source->hasVpat) {
		record.vpatSize = source->vpat->siz;
	} else {
		record.vpatSize = 0;
	}
	record.vpatElectricalTilt = source->vpatElectricalTilt;
	record.vpatMechanicalTilt = source->vpatMechanicalTilt;
	record.vpatTiltOrientation = source->vpatTiltOrientation;
	record.hasMpat = source->hasMpat;
	if (source->hasMpat) {
		record.mpatSize = source->mpat->siz;
	} else {
		record.mpatSize = 0;
	}
	record.cellArea = source->cellArea;
	record.cellBounds = source->cellBounds;
	record.cellLonSize = source->cellLonSize;
	record.serviceAreaValid = source->serviceAreaValid;
	if (source->contour) {
		record.hasContour = 1;
		record.contourMode = (short)source->contour->mode;
		record.contourCount = (short)source->contour->count;
	} else {
		record.hasContour = 0;
		record.contourMode = 0;
		record.contourCount = 0;
	}
	record.studyModCount = StudyModCount;
	record.sourceModCount = source->modCount;
	record.serviceAreaModCount = source->serviceAreaModCount;

	fwrite(&record, 1, sizeof(SOURCE_CACHE_RECORD), cache);
	if (record.hasHpat) {
		fwrite(source->hpat, 1, (360 * sizeof(double)), cache);
	}
	if (record.hasConthpat) {
		fwrite(source->conthpat, 1, (360 * sizeof(double)), cache);
	}
	if (record.hasVpat) {
		fwrite(source->vpat, 1, record.vpatSize, cache);
	}
	if (record.hasMpat) {
		fwrite(source->mpat, 1, record.mpatSize, cache);
	}
	if (record.hasContour) {
		fwrite(source->contour->distance, 1, (record.contourCount * sizeof(double)), cache);
	}

	if (source->isParent) {

		SOURCE *dtsSource = source->dtsRefSource;

		memset(&record, 0, sizeof(SOURCE_CACHE_RECORD));

		record.sourceKey = dtsSource->sourceKey;
		record.serviceTypeKey = dtsSource->serviceTypeKey;
		record.channel = dtsSource->channel;
		record.countryKey = dtsSource->countryKey;
		record.latitude = dtsSource->latitude;
		record.longitude = dtsSource->longitude;
		record.frequency = dtsSource->frequency;
		record.heightAMSL = dtsSource->actualHeightAMSL;
		record.heightAGL = dtsSource->heightAGL;
		record.overallHAAT = dtsSource->actualOverallHAAT;
		record.peakERP = dtsSource->peakERP;
		record.contourERP = dtsSource->contourERP;
		record.hasHpat = dtsSource->hasHpat;
		record.hasConthpat = (NULL != dtsSource->conthpat);
		record.hpatOrientation = dtsSource->hpatOrientation;
		record.hasVpat = dtsSource->hasVpat;
		if (dtsSource->hasVpat) {
			record.vpatSize = dtsSource->vpat->siz;
		} else {
			record.vpatSize = 0;
		}
		record.vpatElectricalTilt = dtsSource->vpatElectricalTilt;
		record.vpatMechanicalTilt = dtsSource->vpatMechanicalTilt;
		record.vpatTiltOrientation = dtsSource->vpatTiltOrientation;
		record.hasMpat = dtsSource->hasMpat;
		if (dtsSource->hasMpat) {
			record.mpatSize = dtsSource->mpat->siz;
		} else {
			record.mpatSize = 0;
		}
		if (dtsSource->contour) {
			record.hasContour = 1;
			record.contourMode = (short)dtsSource->contour->mode;
			record.contourCount = (short)dtsSource->contour->count;
		} else {
			record.hasContour = 0;
			record.contourMode = 0;
			record.contourCount = 0;
		}
		record.serviceAreaModCount = dtsSource->serviceAreaModCount;

		fwrite(&record, 1, sizeof(SOURCE_CACHE_RECORD), cache);
		if (record.hasHpat) {
			fwrite(dtsSource->hpat, 1, (360 * sizeof(double)), cache);
		}
		if (record.hasConthpat) {
			fwrite(dtsSource->conthpat, 1, (360 * sizeof(double)), cache);
		}
		if (record.hasVpat) {
			fwrite(dtsSource->vpat, 1, record.vpatSize, cache);
		}
		if (record.hasMpat) {
			fwrite(dtsSource->mpat, 1, record.mpatSize, cache);
		}
		if (record.hasContour) {
			fwrite(dtsSource->contour->distance, 1, (record.contourCount * sizeof(double)), cache);
		}

		DTS_SOURCE_CACHE_RECORD dtsRecord;

		for (dtsSource = source->dtsSources; dtsSource; dtsSource = dtsSource->next) {

			memset(&dtsRecord, 0, sizeof(DTS_SOURCE_CACHE_RECORD));

			dtsRecord.sourceKey = dtsSource->sourceKey;
			dtsRecord.latitude = dtsSource->latitude;
			dtsRecord.longitude = dtsSource->longitude;
			dtsRecord.heightAMSL = dtsSource->actualHeightAMSL;
			dtsRecord.heightAGL = dtsSource->heightAGL;
			dtsRecord.overallHAAT = dtsSource->actualOverallHAAT;
			dtsRecord.peakERP = dtsSource->peakERP;
			dtsRecord.contourERP = dtsSource->contourERP;
			dtsRecord.hasHpat = dtsSource->hasHpat;
			dtsRecord.hasConthpat = (NULL != dtsSource->conthpat);
			dtsRecord.hpatOrientation = dtsSource->hpatOrientation;
			dtsRecord.hasVpat = dtsSource->hasVpat;
			if (dtsSource->hasVpat) {
				dtsRecord.vpatSize = dtsSource->vpat->siz;
			} else {
				dtsRecord.vpatSize = 0;
			}
			dtsRecord.vpatElectricalTilt = dtsSource->vpatElectricalTilt;
			dtsRecord.vpatMechanicalTilt = dtsSource->vpatMechanicalTilt;
			dtsRecord.vpatTiltOrientation = dtsSource->vpatTiltOrientation;
			dtsRecord.hasMpat = dtsSource->hasMpat;
			if (dtsSource->hasMpat) {
				dtsRecord.mpatSize = dtsSource->mpat->siz;
			} else {
				dtsRecord.mpatSize = 0;
			}
			if (dtsSource->contour) {
				dtsRecord.hasContour = 1;
				dtsRecord.contourMode = (short)dtsSource->contour->mode;
				dtsRecord.contourCount = (short)dtsSource->contour->count;
			} else {
				dtsRecord.hasContour = 0;
				dtsRecord.contourMode = 0;
				dtsRecord.contourCount = 0;
			}
			dtsRecord.serviceAreaModCount = dtsSource->serviceAreaModCount;

			fwrite(&dtsRecord, 1, sizeof(DTS_SOURCE_CACHE_RECORD), cache);
			if (dtsRecord.hasHpat) {
				fwrite(dtsSource->hpat, 1, (360 * sizeof(double)), cache);
			}
			if (dtsRecord.hasConthpat) {
				fwrite(dtsSource->conthpat, 1, (360 * sizeof(double)), cache);
			}
			if (dtsRecord.hasVpat) {
				fwrite(dtsSource->vpat, 1, dtsRecord.vpatSize, cache);
			}
			if (dtsRecord.hasMpat) {
				fwrite(dtsSource->mpat, 1, dtsRecord.mpatSize, cache);
			}
			if (dtsRecord.hasContour) {
				fwrite(dtsSource->contour->distance, 1, (dtsRecord.contourCount * sizeof(double)), cache);
			}
		}
	}

	fclose(cache);

	source->cached = 1;

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Load signals from cell cache into the grid.  This behaves with slight differences for desired vs. undesired.

// For undesired, the desired source key is needed because the cache is desired-source-specific in local grid mode.
// The desiredSourceKey argument does not need to be valid for a desired read, or for any read in global grid mode.

// For desired signals, this is expected to fully define the points that comprise the coverage area of the source.  For
// undesired, the load may be nothing, some of what is needed, all of what is needed, or more than what is needed.  The
// caller must never make any assumption for an undesired load and must always perform additional checks to be sure all
// undesireds have fields if needed at points for the desired being studied.

// The return will be non-zero on error.  If the return is >0 it indicates data was not fully loaded but the study can
// proceed as long as further checks are made to fill in missing data.  Generally for undesired calculations that is
// going to occur regardless of return value here since the caller can never assume an undesired cache is complete, but
// for desired the cache is always complete so the caller only has to check for undefined cells on a non-zero return.

// If the return is <0 for any call a more serious error occurred and it is not safe to proceed with any study, the
// process should be aborted.

// Note cell caches are only used in grid mode, points mode does not cache calculation results.

// Arguments:

//   source            Source for which data is being read.
//   cacheType         CACHE_DES or CACHE_UND.
//   desiredSourceKey  These must be defined for a CACHE_UND read when GridType is GRID_TYPE_LOCAL, they are not used
//   ucacheChecksum     in global mode.
//   cacheCount        Optional accumulator to count number of fields read from cache.  May be NULL.

// Return <0 for serious error, >0 for minor error, 0 for no error.

int read_cell_cache(SOURCE *source, int cacheType, unsigned short desiredSourceKey, int *ucacheChecksum,
		int *cacheCount) {

	if (STUDY_MODE_GRID != StudyMode) {
		return 0;
	}

	char *fileName = NULL;
	int magicNumber = 0;

	if (CACHE_DES == cacheType) {

		fileName = desired_cell_file(source->sourceKey);
		magicNumber = DESIRED_CELL_FILE_MAGIC;

	} else {

		fileName = undesired_cell_file(source->sourceKey, desiredSourceKey);
		magicNumber = UNDESIRED_CELL_FILE_MAGIC;

		if (GRID_TYPE_GLOBAL == Params.GridType) {
			source->ucacheChecksum = 0;
		} else {
			*ucacheChecksum = 0;
		}
	}

	// The source record cache file is always the lock point for all cache files for the source.  If the source file
	// does not exist, assume no other files exist either.

	FILE *lock = fopen(source_file(source->sourceKey), "rb");
	if (!lock) {
		return 1;
	}
	flock(fileno(lock), LOCK_SH);

	FILE *cache = fopen(fileName, "rb");
	if (!cache) {
		fclose(lock);
		return 1;
	}

	CACHE_HEADER header;
	size_t recordSize = sizeof(CACHE_HEADER);
	if ((fread(&header, 1, recordSize, cache) != recordSize) || (header.magicNumber != magicNumber)) {
		fclose(cache);
		unlink(fileName);
		fclose(lock);
		log_error("Bad cell cache for sourceKey=%d cacheType=%d: missing or invalid file header", source->sourceKey,
			cacheType);
		return 1;
	}

	// A version number mismatch for file or terrain data is not logged, but the cache contents are discarded.  Such
	// mismatches shouldn't occur since the source cache record should have had the same header values and if those
	// did not match the entire set of cache files for the source was deleted, see do_source_read().  If the version
	// checks pass, update the global user terrain usage flag.

	if ((header.cacheVersion != TVSTUDY_CACHE_VERSION) || (header.userTerrainRequested != UserTerrainRequested) ||
			(header.userTerrainRequested && (header.userTerrainVersion != UserTerrainVersion))) {
		fclose(cache);
		unlink(fileName);
		fclose(lock);
		return 1;
	}
	if (header.userTerrainUsed) {
		UserTerrainUsed = 1;
	}

	CELL_CACHE_RECORD record;
	recordSize = sizeof(CELL_CACHE_RECORD);

	SOURCE *dtsSource;
	DTS_CELL_CACHE_RECORD dtsRecord;
	size_t dtsRecordSize = sizeof(DTS_CELL_CACHE_RECORD), dtsSkipSize = 0;
	if (source->isParent) {
		for (dtsSource = source->dtsSources; dtsSource; dtsSource = dtsSource->next) {
			dtsSkipSize += dtsRecordSize;
		}
	}

	int lonSize = CellLonSize;
	int eastLonIndex = CellBounds.eastLonIndex;
	int lonCount = GridLonCount;
	int latGridIndex, lonGridIndex, in_grid, newCacheCount = 0;
	TVPOINT **pointPtr, *point;
	FIELD **fieldPtr, *field, **dtsFieldPtr, *dtsField;

	// Loop for records from cache.  First check the sum value for the record, that is a running XOR of some of the
	// record parameters, seeded by the source key.  The value is in each record so bad data is detected and the read
	// aborted but anything already read can be assumed valid.  The purpose is not primarily to detect low-level data
	// corruption, it is to detect invalid changes in record sequence from outside manipulation of the cache files.
	// A separate cache data post-processing tool was planned but was never developed (and probably never will be) so
	// the mechanism is somewhat obsolete.  However it still has some utility in the logic for detecting concurrent
	// writes to undesired caches so it is being left in place for now.

	u_int32_t checksum = ((u_int32_t)(source->sourceKey)) | (((u_int32_t)(source->sourceKey)) << 16);

	while (fread(&record, 1, recordSize, cache) == recordSize) {

		checksum ^= (u_int32_t)(record.cellLatIndex);
		checksum ^= (u_int32_t)(record.cellLonIndex);
		checksum ^= ((u_int32_t)(record.population)) << 16;
		if (checksum != record.checksum) {
			fclose(cache);
			unlink(fileName);
			fclose(lock);
			log_error("Bad cell cache for sourceKey=%d cacheType=%d: bad checksum", source->sourceKey, cacheType);
			return 1;
		}

		// Check the source key, this is implied by the file name but file names can be changed.

		if (source->sourceKey != record.sourceKey) {
			fclose(cache);
			unlink(fileName);
			fclose(lock);
			log_error("Bad cell cache for sourceKey=%d cacheType=%d: source key doesn't match", source->sourceKey,
				cacheType);
			return 1;
		}

		// Check if the cell is inside the study grid, and determine the grid index along the way.  In global mode due
		// to the longitude alignment shifts between rows a cell may be part of the grid but still be partially outside
		// the grid bounds, so this can't just directly check the cell index coordinates against the bounds.

		in_grid = 1;
		if (record.cellLatIndex < CellBounds.southLatIndex) {
			in_grid = 0;
		} else {
			latGridIndex = (record.cellLatIndex - CellBounds.southLatIndex) / CellLatSize;
			if (latGridIndex >= GridLatCount) {
				in_grid = 0;
			} else {
				if (GRID_TYPE_GLOBAL == Params.GridType) {
					lonSize = CellLonSizes[latGridIndex];
					eastLonIndex = CellEastLonIndex[latGridIndex];
					lonCount = GridLonCounts[latGridIndex];
				}
				if (record.cellLonIndex < eastLonIndex) {
					in_grid = 0;
				} else {
					lonGridIndex = (record.cellLonIndex - eastLonIndex) / lonSize;
					if (lonGridIndex >= lonCount) {
						in_grid = 0;
					}
				}
			}
		}

		// For a desired, the grid should be big enough for all the source's cells so a cell outside the grid is an
		// error.  That doesn't necessarily mean the cache is bad, it could be an error in other code setting the grid
		// bounds, so don't delete the cache.  For an undesired just ignore cells outside the grid.

		if (!in_grid) {
			if (CACHE_DES == cacheType) {
				fclose(cache);
				fclose(lock);
				log_error("Cell cache read failed for sourceKey=%d cacheType=%d: latIndex=%d lonIndex=%d outside grid",
					source->sourceKey, cacheType, record.cellLatIndex, record.cellLonIndex);
				return 1;
			}
			if (source->isParent) {
				fseek(cache, dtsSkipSize, SEEK_CUR);
			}
			continue;
		}

		// Search for an existing study point in the cell.  If found make sure all parameters exactly match the cache
		// record, if not return a fatal error.  A mismatch means a discrepancy exists between this cache and either
		// another cache or recent calculation results, however there is no way to determine which is correct so it is
		// not safe to proceed.  When all Census points are being studied there may be multiple points for the same
		// country in the cell so the coordinates are also part of the identifying match.

		pointPtr = Cells + ((latGridIndex * GridLonCount) + lonGridIndex);
		point = *pointPtr;

		if (POINT_METHOD_ALL == Params.StudyPointMethod) {
			while (point && ((record.countryKey != point->countryKey) || (record.latitude != point->latitude) ||
					(record.longitude != point->longitude))) {
				pointPtr = &(point->next);
				point = point->next;
			}
		} else {
			while (point && (record.countryKey != point->countryKey)) {
				pointPtr = &(point->next);
				point = point->next;
			}
		}

		if (point) {

			if (is_diff(record.latitude, point->latitude) || is_diff(record.longitude, point->longitude) ||
					(record.cellLatIndex != point->cellLatIndex) || (record.cellLonIndex != point->cellLonIndex) ||
					(record.population != point->a.population) || (record.households != point->households) ||
					fis_diff(record.area, point->b.area) || fis_diff(record.elevation, point->elevation) ||
					(record.landCoverType != point->landCoverType) || (record.clutterType != point->clutterType)) {
				fclose(cache);
				fclose(lock);
				log_error("Cell cache read failed for sourceKey=%d cacheType=%d: existing point does not match",
					source->sourceKey, cacheType);
				log_error("  countryKey=%d", record.countryKey);
				log_error("                               cache                    point");
				log_error("     latIndex  %22d  %22d", record.cellLatIndex, point->cellLatIndex);
				log_error("     lonIndex  %22d  %22d", record.cellLonIndex, point->cellLonIndex);
				log_error("     latitude  %22.15e  %22.15e", record.latitude, point->latitude);
				log_error("    longitude  %22.15e  %22.15e", record.longitude, point->longitude);
				log_error("   population  %22d  %22d", record.population, point->a.population);
				log_error("   households  %22d  %22d", record.households, point->households);
				log_error("         area  %22.7e  %22.7e", record.area, point->b.area);
				log_error("    elevation  %22.7e  %22.7e", record.elevation, point->elevation);
				log_error("landCoverType  %22d  %22d", record.landCoverType, point->landCoverType);
				log_error("  clutterType  %22d  %22d", record.clutterType, point->clutterType);
				return -1;
			}

		} else {

			point = get_point();
			*pointPtr = point;

			point->latitude = record.latitude;
			point->longitude = record.longitude;
			point->cellLatIndex = record.cellLatIndex;
			point->cellLonIndex = record.cellLonIndex;
			point->a.population = record.population;
			point->households = record.households;
			point->b.area = record.area;
			point->elevation = record.elevation;
			point->countryKey = record.countryKey;
			point->landCoverType = record.landCoverType;
			point->clutterType = record.clutterType;
		}

		// Search for a matching field structure, fatal error if found.  For any given source, regardless of desired
		// or undesired, cache must be loaded before any other intialization and only loaded once.  Only new fields
		// are ever appended to a cache, so there should never be any situation resulting in duplicates.  As with a
		// mismatch in point parameters, if somehow a duplicate does occur there is no way to know which is correct so
		// it is not safe to proceed.

		fieldPtr = &(point->fields);
		field = *fieldPtr;
		while (field && ((field->sourceKey != source->sourceKey) || (field->a.percentTime != record.percentTime))) {
			fieldPtr = &(field->next);
			field = field->next;
		}
		if (field) {
			fclose(cache);
			fclose(lock);
			log_error("Cell cache read failed for sourceKey=%d cacheType=%d: duplicate field at point",
				source->sourceKey, cacheType);
			return -1;
		}

		field = get_field();
		*fieldPtr = field;

		field->bearing = record.bearing;
		field->reverseBearing = record.reverseBearing;
		field->distance = record.distance;
		field->fieldStrength = record.fieldStrength;
		field->sourceKey = record.sourceKey;
		field->a.percentTime = record.percentTime;
		field->status = record.status;
		field->b.cached = 1;

		newCacheCount++;

		// For a DTS source, the main record is immediately followed by shorter DTS records for each actual source,
		// always in the expected sequence (verified in read_source_cache()).  The parent source record is just a
		// placeholder; it never has a real field value.  If any of the actual source fields are missing or out of
		// order, the entire set has to be discarded.  The DTS reference facility is never involved here.

		if (source->isParent) {

			dtsFieldPtr = &(field->next);

			for (dtsSource = source->dtsSources; dtsSource; dtsSource = dtsSource->next) {

				if (fread(&dtsRecord, 1, dtsRecordSize, cache) != dtsRecordSize) {
					*fieldPtr = NULL;
					fclose(cache);
					unlink(fileName);
					fclose(lock);
					log_error("Bad cell cache for sourceKey=%d cacheType=%d: unexpected end-of-file\n",
						source->sourceKey, cacheType);
					return 1;
				}

				if (dtsSource->sourceKey != dtsRecord.sourceKey) {
					*fieldPtr = NULL;
					fclose(cache);
					unlink(fileName);
					fclose(lock);
					log_error("Bad cell cache for sourceKey=%d cacheType=%d: source key doesn't match in DTS record",
						source->sourceKey, cacheType);
					return 1;
				}

				dtsField = get_field();
				*dtsFieldPtr = dtsField;
				dtsFieldPtr = &(dtsField->next);

				dtsField->bearing = dtsRecord.bearing;
				dtsField->reverseBearing = dtsRecord.reverseBearing;
				dtsField->distance = dtsRecord.distance;
				dtsField->fieldStrength = dtsRecord.fieldStrength;
				dtsField->sourceKey = dtsRecord.sourceKey;
				dtsField->a.percentTime = field->a.percentTime;
				dtsField->status = dtsRecord.status;
				dtsField->b.cached = 1;

				newCacheCount++;
			}
		}
	}

	fclose(cache);

	// For a desired cache, the magic number is written again at the end so short reads are detectable.  The last
	// record read should have picked up that value.

	if (CACHE_DES == cacheType) {
		int checkMagic = 0;
		memcpy(&checkMagic, &record, sizeof(int));
		if (checkMagic != header.magicNumber) {
			unlink(fileName);
			fclose(lock);
			log_error("Bad cell cache for sourceKey=%d cacheType=%d: bad or missing end-of-file mark",
				source->sourceKey, cacheType);
			return 1;
		}

	// For an undesired cache, save the checksum from the last record.  It will be compared to the last record read
	// back from the file on cache write to detect concurrent use of the file by another process.

	} else {
		if (GRID_TYPE_GLOBAL == Params.GridType) {
			source->ucacheChecksum = checksum;
		} else {
			*ucacheChecksum = checksum;
		}
	}

	fclose(lock);

	if (cacheCount) {
		*cacheCount += newCacheCount;
	}

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Test two floating-point values for a significant difference.

static int is_diff(double d1, double d2) {

	union {
		double d;
		int64_t i;
	} t1, t2;

	t1.d = d1;
	t2.d = d2;

	int64_t diff = t2.i - t1.i;

	if ((diff > -16) && (diff < 16)) {
		return 0;
	}

	return 1;
}

static int fis_diff(float f1, float f2) {

	union {
		float f;
		int32_t i;
	} t1, t2;

	t1.f = f1;
	t2.f = f2;

	int32_t diff = t2.i - t1.i;

	if ((diff > -4) && (diff < 4)) {
		return 0;
	}

	return 1;
}


//---------------------------------------------------------------------------------------------------------------------
// Write cell data to cache.  There are differences here between desired and undesired.

// For a desired, this is always a complete dump of all study points for the source and the cache file is overwritten
// if it exists.  For a desired source this needs to fully define the set of points in the coverage area.  All fields
// should have been calculated by now however the cache is not invalid even with un-calculated fields, when those
// records are loaded again later the un-calculated status comes along too, so the cache is written regardless of
// whether field calculations are complete.

// For an undesired, this only writes newly-calculated fields, and appends to the existing cache.  The assumption is
// the cache was loaded earlier so anything not new is already in the cache.  Because of the running checksum per
// record, for an undesired opening the file is more complicated; it must be opened for read first, the last record
// read to get the last checksum value, then re-opened for append.  (The purpose of that now is to verify the stored
// last-record checksum to detect concurrent use of the file.)

// In either case, the appropriate grid index range from the source structure, gridIndex or ugridIndex, is used so
// this only scans cells that might contain data for the source, avoiding a full grid scan in global mode.

// Usual error return, but a fatal error (<0) will never be returned here, see discussion for write_source_cache().

// Arguments:

//   source            Source for which data is being written.
//   cacheType         CACHE_DES or CACHE_UND.
//   desiredSourceKey  These must be defined for a CACHE_UND read when GridType is GRID_TYPE_LOCAL, they are not used
//   ucacheChecksum     in global mode.

// Return <0 for serious error, >0 for minor error, 0 for no error.

int write_cell_cache(SOURCE *source, int cacheType, unsigned short desiredSourceKey, int *ucacheChecksum) {

	if (STUDY_MODE_GRID != StudyMode) {
		return 0;
	}

	FILE *lock = fopen(source_file(source->sourceKey), "rb");
	if (!lock) {
		return 1;
	}
	flock(fileno(lock), LOCK_EX);

	char *fileName = NULL;
	FILE *cache = NULL;
	INDEX_BOUNDS gridIndex;

	// The header will always be written or re-written, to update the terrain-related flags.

	CACHE_HEADER header;
	header.cacheVersion = TVSTUDY_CACHE_VERSION;
	header.userTerrainVersion = UserTerrainVersion;
	header.userTerrainRequested = UserTerrainRequested;
	header.userTerrainUsed = UserTerrainUsed;

	CELL_CACHE_RECORD record;
	size_t recordSize = sizeof(CELL_CACHE_RECORD);

	SOURCE *dtsSource;
	DTS_CELL_CACHE_RECORD dtsRecord;
	size_t dtsRecordSize = sizeof(DTS_CELL_CACHE_RECORD);

	u_int32_t checksum = ((u_int32_t)(source->sourceKey)) | (((u_int32_t)(source->sourceKey)) << 16);

	if (CACHE_DES == cacheType) {

		fileName = desired_cell_file(source->sourceKey);
		header.magicNumber = DESIRED_CELL_FILE_MAGIC;

		cache = fopen(fileName, "wb");

		gridIndex = source->gridIndex;

	} else {

		fileName = undesired_cell_file(source->sourceKey, desiredSourceKey);
		header.magicNumber = UNDESIRED_CELL_FILE_MAGIC;

		// Read back the last record checksum and compare to stored value, if it does not match, assume another
		// process wrote to the file and don't do any further writes from this process.  (Because ucacheChecksum will
		// now be 0 so next time it also won't match unless the file is deleted, except there is a very, very, very
		// small chance a real checksum could be 0 so I should have an explicit disabled flag, call that a TBD.)  That
		// is not an error, reads from the file will continue.  If the last-record read fails, truncate the file.

		int lastcheck;
		if (GRID_TYPE_GLOBAL == Params.GridType) {
			lastcheck = source->ucacheChecksum;
			source->ucacheChecksum = 0;
		} else {
			lastcheck = *ucacheChecksum;
			*ucacheChecksum = 0;
		}

		cache = fopen(fileName, "rb+");

		if (!cache) {

			cache = fopen(fileName, "wb");

		} else {

			size_t fullRecordSize = recordSize;
			if (source->isParent) {
				for (dtsSource = source->dtsSources; dtsSource; dtsSource = dtsSource->next) {
					fullRecordSize += dtsRecordSize;
				}
			}

			if (fseek(cache, -fullRecordSize, SEEK_END) || (fread(&record, 1, recordSize, cache) != recordSize)) {

				cache = freopen(NULL, "w", cache);

			} else {

				if (record.checksum != lastcheck) {
					fclose(cache);
					fclose(lock);
					return 0;
				}
				checksum = lastcheck;

				fseek(cache, 0, SEEK_SET);
			}
		}

		gridIndex = source->ugridIndex;
	}

	if (!cache) {
		fclose(lock);
		log_error("Cell cache write canceled for sourceKey=%d cacheType=%d: cannot open file", source->sourceKey,
			cacheType);
		return 1;
	}

	// Write or re-write the header, seek to the end in case this is adding to an existing file.

	fwrite(&header, 1, sizeof(CACHE_HEADER), cache);
	fseek(cache, 0, SEEK_END);

	// Scan the cells in the grid range, search points for fields matching the indicated source and type (desired or
	// undesired).  By convention the longitude values in the gridIndex structure in global mode are the original cell
	// index range, because the grid index range has to be re-computed from those values for each row of the grid.

	int latGridIndex, lonGridIndex;
	TVPOINT **pointPtr, *point;
	FIELD *field;

	int eastLonIndex = CellBounds.eastLonIndex;
	int eastLonGridIndex = gridIndex.eastLonIndex;
	int westLonGridIndex = gridIndex.westLonIndex;

	for (latGridIndex = gridIndex.southLatIndex; latGridIndex < gridIndex.northLatIndex; latGridIndex++) {

		if (GRID_TYPE_GLOBAL == Params.GridType) {
			eastLonIndex = CellEastLonIndex[latGridIndex];
			eastLonGridIndex = (gridIndex.eastLonIndex - eastLonIndex) / CellLonSizes[latGridIndex];
			westLonGridIndex = (((gridIndex.westLonIndex - 1) - eastLonIndex) / CellLonSizes[latGridIndex]) + 1;
			if (westLonGridIndex > GridLonCounts[latGridIndex]) {
				westLonGridIndex = GridLonCounts[latGridIndex];
			}
		}

		pointPtr = Cells + ((latGridIndex * GridLonCount) + eastLonGridIndex);
		for (lonGridIndex = eastLonGridIndex; lonGridIndex < westLonGridIndex; lonGridIndex++, pointPtr++) {

			for (point = *pointPtr; point; point = point->next) {

				for (field = point->fields; field; field = field->next) {

					if (source->sourceKey != field->sourceKey) {
						continue;
					}

					if (CACHE_DES == cacheType) {
						if (field->a.isUndesired) {
							continue;
						}
					} else {
						if (!(field->a.isUndesired)) {
							continue;
						}
						if ((field->status < 0) || field->b.cached) {
							continue;
						}
					}

					record.latitude = point->latitude;
					record.longitude = point->longitude;
					record.cellLatIndex = point->cellLatIndex;
					record.cellLonIndex = point->cellLonIndex;
					record.population = point->a.population;
					record.households = point->households;
					record.area = point->b.area;
					record.elevation = point->elevation;
					record.bearing = field->bearing;
					record.reverseBearing = field->reverseBearing;
					record.distance = field->distance;
					record.fieldStrength = field->fieldStrength;
					record.sourceKey = field->sourceKey;
					record.countryKey = point->countryKey;
					record.landCoverType = point->landCoverType;
					record.clutterType = point->clutterType;
					record.percentTime = field->a.percentTime;
					record.status = field->status;

					checksum ^= (u_int32_t)(record.cellLatIndex);
					checksum ^= (u_int32_t)(record.cellLonIndex);
					checksum ^= ((u_int32_t)(record.population)) << 16;
					record.checksum = checksum;

					fwrite(&record, 1, recordSize, cache);

					field->b.cached = 1;

					// For DTS, write the records for the individual transmitter sources, the field structures will
					// immediately follow in sequence; if not something is seriously messed up, cancel the write and
					// delete the file.

					if (source->isParent) {

						for (dtsSource = source->dtsSources; dtsSource; dtsSource = dtsSource->next) {

							field = field->next;
							if (!field || (field->sourceKey != dtsSource->sourceKey)) {
								fclose(cache);
								unlink(fileName);
								fclose(lock);
								log_error("Missing or out-of-order fields for DTS sourceKey=%d", source->sourceKey);
								return 1;
							}

							dtsRecord.bearing = field->bearing;
							dtsRecord.reverseBearing = field->reverseBearing;
							dtsRecord.distance = field->distance;
							dtsRecord.fieldStrength = field->fieldStrength;
							dtsRecord.sourceKey = field->sourceKey;
							dtsRecord.status = field->status;

							fwrite(&dtsRecord, 1, dtsRecordSize, cache);

							field->b.cached = 1;
						}
					}
				}
			}
		}
	}

	// For desired, write magic number again as end-of-file marker.

	if (CACHE_DES == cacheType) {
		fwrite(&header.magicNumber, 1, sizeof(int), cache);
		source->hasDesiredCache = 1;

	// For undesired, update the last-record checksum.

	} else {
		if (GRID_TYPE_GLOBAL == Params.GridType) {
			source->ucacheChecksum = checksum;
		} else {
			*ucacheChecksum = checksum;
		}
	}

	fclose(cache);
	fclose(lock);
	return 0;
}
