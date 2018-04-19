//
//  cell.c
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.


// Functions related to laying out cell grids, and initializing cells and study points, including loading population
// data, also updating cell-level cache files.


#include "tvstudy.h"


//---------------------------------------------------------------------------------------------------------------------

static int load_population(INDEX_BOUNDS gridIndex, SOURCE *source);
static FIELD *add_field(FIELD ***fieldPtr, SOURCE *source, short percentTime, double bear, double revBear, double dist,
	short cached, double lat, double lon);
static void init_storage();
static void init_point_pools();
static void init_cen_point_pools();
static void init_field_pools();

static int GridPopulationLoaded = 0;   // True if population already loaded for entire grid, only in global mode.

// Public globals.

INDEX_BOUNDS CellBounds;        // Cell limits of current study grid, units of arc-seconds.
INDEX_BOUNDS GridIndex;         // Grid limits in units of grid index, longitude is arc-seconds in global mode.
int GridCount = 0;              // Size of cell array.
TVPOINT **Cells = NULL;           // Cell array, lists of study points in each cell.  A cell has no separate struct.
int GridMaxCount = 0;           // Allocated size of cell array.
int CellLatSize = 0;            // Latitude size of study cells, constant per CellSize.
int GridLatCount = 0;           // Latitude grid count.
int CellLonSize = 0;            // Longitude size of study cells, minimum in global mode.
int GridLonCount = 0;           // Longitude grid count, maximum in global mode.
int *CellLonSizes = NULL;       // Actual longitude cell size per grid row in global mode.
double *CellAreas = NULL;       // Cell area per grid row in global mode, square kilometers.
int *GridLonCounts = NULL;      // Actual longitude grid count per row in global mode; indexing is based on maximum.
int *CellEastLonIndex = NULL;   // East longitude index for each row in global mode, may be outside grid limits.
int GridMaxLatCount = 0;        // Allocated size of longitude size and count arrays.

TVPOINT *Points = NULL;            // List of study points in points mode.
POINT_INFO *PointInfos = NULL;   // Additional info structures for points in points mode.


//---------------------------------------------------------------------------------------------------------------------
// Get the longitude size of a cell at a specified latitude index for global grid mode.  The latitude size of cells is
// fixed for any grid type, but for a global grid the longitude size varies in steps according to latitude range, the
// band breaks have to be computed for the current cell size.

// The target is for the cell area to change by 2% across a band.  Area is based on spherical earth so cell area is
// directly proportional to the cosine of the latitude, the cosine is incrementally changed by 2% then the longitude
// size and latitude break point are computed.  However the longitude size is an integer value so must change by a
// whole number of seconds, and always by at least 1 second.  That means the actual area change will be more than 2%,
// increasing at smaller cell sizes.  Error ranges for typical sizes are listed below.  This can't be improved as long
// as the cell sizes must be integers; and lowering the target below 2% does not significantly change this result.

//   Cell size      Area errors

//      10 km     +1.13%   -1.16%
//       5        +1.31    -1.20
//       2        +1.71    -1.54
//       1        +2.24    -1.89
//     0.5        +3.72    -4.61
//     0.1       +14.39   -19.20

// Banding stops at 75 degrees latitude, studies are not performed beyond that as many algorithms would begin to fail
// closer to the poles.  The grid is layed out in one hemisphere, coordinates are simply inverted to get the other.
// The constant MAX_LAT_BANDS is set large enough for any cell size, with current parameters this can never produce
// more than about 90 bands at any reasonable cell size (the tested safe range is 0.02 to 50 km).

// Arguments:

//   latIndex  The latitude in arc-seconds.

// Return is the longitude cell size in arc-seconds.

int global_lon_size(int latIndex) {

	#define MAX_LAT_BANDS 100

	static int initForStudyKey = 0;
	static int cellBandLatIndex[MAX_LAT_BANDS], cellBandLonSize[MAX_LAT_BANDS], cellBandCount;

	if (StudyKey != initForStudyKey) {

		double latRealSize = (Params.CellSize / Params.KilometersPerDegree) * 3600.;
		double lonRealSizeTarget = (latRealSize * latRealSize * 1.01) / (double)CellLatSize;

		double cosLat = 1.;
		int maxLatIndex = 75 * 3600, bandLatIndex, bandLonSize;

		do {

			bandLatIndex = (int)((acos(cosLat) * RADIANS_TO_DEGREES * 3600.) + 0.5);
			bandLonSize = (int)((lonRealSizeTarget / cosLat) + 0.5);

			if ((cellBandCount > 0) && (bandLonSize <= cellBandLonSize[cellBandCount - 1])) {
				bandLonSize = cellBandLonSize[cellBandCount - 1] + 1;
				cosLat = lonRealSizeTarget / (double)bandLonSize;
				bandLatIndex = (int)((acos(cosLat) * RADIANS_TO_DEGREES * 3600.) + 0.5);
			}

			cellBandLatIndex[cellBandCount] = bandLatIndex;
			cellBandLonSize[cellBandCount++] = bandLonSize;

			cosLat *= 0.98;

		} while ((bandLatIndex < maxLatIndex) && (cellBandCount < MAX_LAT_BANDS));

		initForStudyKey = StudyKey;
	}

	int i;

	latIndex = abs(latIndex);

	for (i = 1; i < cellBandCount; i++) {
		if (latIndex < cellBandLatIndex[i]) {
			break;
		}
	}
	i--;

	return cellBandLonSize[i];
}


//---------------------------------------------------------------------------------------------------------------------
// Compute the area of a cell in square kilometers at a given latitude index and longitude size.

// Arguments:

//   latIndex  Latitude in arc-seconds.
//   lonSize   Longitude size in arc-seconds.

// Returns area in square kilometers.

double cell_area(int latIndex, int lonSize) {
	return (((double)CellLatSize / 3600.) * Params.KilometersPerDegree) *
		((((double)lonSize / 3600.) * cos(((double)latIndex / 3600.) * DEGREES_TO_RADIANS)) *
		Params.KilometersPerDegree);
}


//---------------------------------------------------------------------------------------------------------------------
// Initialize the study grid for a given area and cell size.  In local-grid mode this will never be more than the
// coverage area of one source being studied; in global-grid mode it may be an arbitrarily large area encompassing the
// coverages of multiple sources (if limits on the size are needed to avoid allocation failures, the caller has to
// check that).  The cell longitude size argument must be the limiting case (smallest value) when multiple sources are
// involved.  Currently the only error is calling this in points mode instead of grid mode.

// Arguments:

//   cellBounds   Desired bounds of the study grid, units of arc-seconds.
//   cellLonSize  Limiting, meaning smallest, longitude cell size.  The grid is uniform for this size, however in
//                  global mode rows further north may have larger cells and thus be partially empty.

// Return is <0 for serious error, >0 for minor error, 0 for no error.

int grid_setup(INDEX_BOUNDS cellBounds, int cellLonSize) {

	if (STUDY_MODE_GRID != StudyMode) {
		log_error("grid_setp() called in points mode");
		return 1;
	}

	CellLonSize = cellLonSize;

	// Set the cell bounds and determine the size, allocate memory.  This will always align the bounds to the sizes.
	// Although the bounds of individual sources should already be aligned, when this is a composite covering many
	// sources re-alignment may be needed, and it's easy enough to just always do it to be safe.

	CellBounds.southLatIndex = (int)floor((double)cellBounds.southLatIndex / (double)CellLatSize) * CellLatSize;
	CellBounds.eastLonIndex = (int)floor((double)cellBounds.eastLonIndex / (double)CellLonSize) * CellLonSize;

	GridLatCount = (((cellBounds.northLatIndex - 1) - CellBounds.southLatIndex) / CellLatSize) + 1;
	GridLonCount = (((cellBounds.westLonIndex - 1) - CellBounds.eastLonIndex) / CellLonSize) + 1;

	CellBounds.northLatIndex = CellBounds.southLatIndex + (GridLatCount * CellLatSize);
	CellBounds.westLonIndex = CellBounds.eastLonIndex + (GridLonCount * CellLonSize);

	GridCount = GridLatCount * GridLonCount;
	if (GridCount > GridMaxCount) {
		GridMaxCount = GridCount;
		Cells = (TVPOINT **)mem_realloc(Cells, (GridMaxCount * sizeof(TVPOINT *)));
	}

	init_storage();

	// In global grid mode, also allocate arrays for storing the variable longitude size and count per latitude row
	// and initialize that data.  Indexing into the cell grid is always based on the maximum longitude count, but
	// individual rows may have fewer cells.  The cell edges are globally aligned (the whole point of "global" mode)
	// so the east lon index in each row will also vary, and may be outside the overall grid limits.  As a result the
	// calculated number of cells in a given row may be more than the allocated grid size so the count is truncated.
	// However that does not mean any cells will be missed.  In global mode all service area limits are extended by one
	// longitude cell size to the west so the grid will always have an extra cell allocated on the west end of every
	// row to deal with rows that are shifted by the changing east end longitude.

	if (GRID_TYPE_GLOBAL == Params.GridType) {

		if (GridLatCount > GridMaxLatCount) {
			GridMaxLatCount = GridLatCount;
			CellLonSizes = (int *)mem_realloc(CellLonSizes, (GridMaxLatCount * sizeof(int)));
			CellAreas = (double *)mem_realloc(CellAreas, (GridMaxLatCount * sizeof(double)));
			GridLonCounts = (int *)mem_realloc(GridLonCounts, (GridMaxLatCount * sizeof(int)));
			CellEastLonIndex = (int *)mem_realloc(CellEastLonIndex, (GridMaxLatCount * sizeof(int)));
		}

		memset(CellLonSizes, 0, (GridMaxLatCount * sizeof(int)));
		memset(CellAreas, 0, (GridMaxLatCount * sizeof(double)));
		memset(GridLonCounts, 0, (GridMaxLatCount * sizeof(int)));
		memset(CellEastLonIndex, 0, (GridMaxLatCount * sizeof(int)));

		int latGridIndex, lonSize, eastLonIndex, latIndex = CellBounds.southLatIndex;

		for (latGridIndex = 0; latGridIndex < GridLatCount; latGridIndex++, latIndex += CellLatSize) {
			lonSize = global_lon_size(latIndex);
			eastLonIndex = (int)floor((double)CellBounds.eastLonIndex / (double)lonSize) * lonSize;
			CellLonSizes[latGridIndex] = lonSize;
			CellAreas[latGridIndex] = cell_area(latIndex, lonSize);
			GridLonCounts[latGridIndex] = (((CellBounds.westLonIndex - 1) - eastLonIndex) / lonSize) + 1;
			if (GridLonCounts[latGridIndex] > GridLonCount) {
				GridLonCounts[latGridIndex] = GridLonCount;
			}
			CellEastLonIndex[latGridIndex] = eastLonIndex;
		}
	}

	// Also define the grid limits as an INDEX_BOUNDS structure in units of grid index, see comments in cell_setup().
	// This is a convenience because some functions take this form as argument; it is just copies of other globals.

	GridIndex.southLatIndex = 0;
	GridIndex.northLatIndex = GridLatCount;
	if (GRID_TYPE_GLOBAL == Params.GridType) {
		GridIndex.eastLonIndex = CellBounds.eastLonIndex;
		GridIndex.westLonIndex = CellBounds.westLonIndex;
	} else {
		GridIndex.eastLonIndex = 0;
		GridIndex.westLonIndex = GridLonCount;
	}

	// See load_grid_population() below.

	GridPopulationLoaded = 0;

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Do a population query to load Census points into the entire grid.  This can optionally be called before any call
// to cell_setup() (or even after, although that probably isn't very efficient), depending on how much of the grid is
// expected to load from cache during cell_setup() calls.

// In local grid mode this is not useful, and not used.  Even when cache does not load in cell_setup(), the query
// that subsequently occurs there is exactly the same as would happen here, because the grid covers just one source at
// a time.  But for completeness this supports being called in local mode, a source argument may be provided to pass
// along to load_population() for setting up source-specific cells.

// In global mode the grid may encompass many sources.  In that case when it is expected that much or all of the grid
// will NOT be filled in by data from cell caches, it is faster to do a single whole-grid population query here rather
// than multiple overlapping queries as each individual source is processed in cell_setup().  But at some point when
// most data will load from cache, it is faster to let a few remaining queries occur over smaller sub-grids in
// cell_setup().  This is entirely performance optimization; the end result is the same regardless.  See study.c.

// When this is called it sets a flag that will prevent any further queries, here or in cell_setup().

// Arguments:

//   source  In local mode, the source being studied; ignored and may be NULL in global mode.

// Return value is <0 for major error, >0 for minor error, 0 for no error.

int load_grid_population(SOURCE *source) {

	if (STUDY_MODE_GRID != StudyMode) {
		log_error("load_grid_population() called in points mode");
		return 1;
	}

	if (GridPopulationLoaded) {
		return 0;
	}

	int err = load_population(GridIndex, source);
	if (err) {
		return err;
	}

	GridPopulationLoaded = 1;

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Set up cells in the study grid for a source.  First attempt to load desired and undesired data from cache.  If the
// desired cache load does not fully succeed, query population and also process zero-population cells to add study
// points to all cells in the source's cell grid (that may not be the entire study grid in global mode).  Test those
// points against the source's service area and add new desired field structures as needed to points inside the area.
// There is no explicit enumeration of the service area points; those points are defined implicitly by the presence of
// a desired field at each.  Once desired fields are fully defined from cache or otherwise, all points are checked to
// be sure fields for all undesired sources are present.  That check must be performed regardless of whether undesired
// cache reads were error-free because there is never any guarantee that an undesired cache will contain all data
// needed.  This returns non-zero on error, <0 is a fatal error, >0 means the run can proceed although the source study
// probably can't as data was not properly loaded.  First a sanity check, make sure the source's grid is fully
// contained in the study grid.

// Arguments:

//   source              The source to set up.
//   cacheCount          Optional counter to accumulate number of fields read from cache, for stats reporting.
//   reloadCensusPoints  True if Census point lists need to be reloaded for study points read from cache.

// Return <0 for serious error, >0 for minor error, 0 for no error.

int cell_setup(SOURCE *source, int *cacheCount, int reloadCensusPoints) {

	if (STUDY_MODE_GRID != StudyMode) {
		log_error("cell_setup() called in points mode");
		return 1;
	}

	if (!inside_bounds_bounds(&CellBounds, &(source->cellBounds))) {
		log_error("Source coverage is outside study grid");
		return 1;
	}

	int err;

	// Attempt a read from the desired cell cache, if this returns >0 the grid still needs to be initialized, some data
	// may have loaded from cache but possibly not all so the grid needs to be checked.

	int initGrid = 1;
	if (source->dcache) {
		err = read_cell_cache(source, CACHE_DES, 0, NULL, cacheCount);
		if (err < 0) {
			return err;
		}
		initGrid = err;
		source->dcache = 0;
	}

	// Next step is to load undesired signals from cache for sources not already loaded.  This happens first even if
	// the grid initialization will be done because this may define some of the points needed thus saving the effort of
	// looking up ground elevations and determining country for no-population cells.

	UNDESIRED *undesireds = source->undesireds;
	int undesiredIndex, undesiredCount = source->undesiredCount;
	SOURCE *usource;

	for (undesiredIndex = 0; undesiredIndex < undesiredCount; undesiredIndex++) {
		hb_log();
		usource = SourceKeyIndex[undesireds[undesiredIndex].sourceKey];
		if (!usource) {
			log_error("Source structure index is corrupted");
			exit(1);
		}
		if (usource->ucache) {
			err = read_cell_cache(usource, CACHE_UND, source->sourceKey, &(undesireds[undesiredIndex].ucacheChecksum),
				cacheCount);
			if (err < 0) {
				return err;
			}
			usource->ucache = 0;
		}
	}

	// If the desired source is DTS and self-interference is being analyzed the source must also have undesired fields
	// at each point.  The source does not appear in it's own list of undesireds so a separate check has to be done
	// to make sure this case is handled.  However the source could already have been handled as an undesired because
	// it is also an undesired to some other desired at the point in global grid mode.

	if (source->isParent && Params.CheckSelfInterference && source->ucache) {
		err = read_cell_cache(source, CACHE_UND, source->sourceKey, &(source->ucacheChecksum), cacheCount);
		if (err < 0) {
			return err;
		}
		source->ucache = 0;
	}

	// Determine a bounding box for this source's coverage area in units of grid index.  This does not assume the
	// bounds are aligned to the cell edges in the current grid.  In global mode the longitude values in the structure
	// just hold the original cell index range because the grid index range has to be calculated for each row.

	INDEX_BOUNDS gridIndex;

	gridIndex.southLatIndex = (source->cellBounds.southLatIndex - CellBounds.southLatIndex) / CellLatSize;
	gridIndex.northLatIndex = (((source->cellBounds.northLatIndex - 1) - CellBounds.southLatIndex) / CellLatSize) + 1;
	if (GRID_TYPE_GLOBAL == Params.GridType) {
		gridIndex.eastLonIndex = source->cellBounds.eastLonIndex;
		gridIndex.westLonIndex = source->cellBounds.westLonIndex;
	} else {
		gridIndex.eastLonIndex = (source->cellBounds.eastLonIndex - CellBounds.eastLonIndex) / CellLonSize;
		gridIndex.westLonIndex = (((source->cellBounds.westLonIndex - 1) - CellBounds.eastLonIndex) / CellLonSize) + 1;
	}
	source->gridIndex = gridIndex;

	// These parameters for longitude layout will vary in global grid mode so will be recalculated row-by-row, this
	// initially sets the values that will be used for local mode.

	int lonSize = CellLonSize;
	int eastLonIndex = CellBounds.eastLonIndex;
	int eastLonGridIndex = gridIndex.eastLonIndex;
	int westLonGridIndex = gridIndex.westLonIndex;

	// Initialize the portion of the cell grid for this source if needed.  First load population data to add missing
	// points for cells with population, unless population has already been loaded, see load_grid_population().  Then
	// do a scan over the relevant grid range, for any points that do not have desired fields for this source determine
	// if the point is inside or outside the service area, adding fields as needed.  Note this does not check any
	// existing fields to see if they are at points outside the service area; if they were loaded from cache it is
	// assumed they are valid.  For cells with no population a study point is added at the geographic center.

	int latGridIndex, lonGridIndex, latIndex, lonIndex;
	double bear, revBear, dist, bear1, dist1;
	TVPOINT **pointPtr, *point;
	FIELD **fieldPtr, *field;
	SOURCE *dtsSource;

	// Note load_population() may need to be called even if a cache load was successful, because the Census points in
	// each study point are not cached and have to be reloaded by query.  However that is only done if the points are
	// are actually needed, so it's up to the caller to decide.

	if (!GridPopulationLoaded && (initGrid || reloadCensusPoints)) {
		err = load_population(gridIndex, source);
		if (err) {
			return err;
		}
	}

	if (initGrid) {

		double halfCellLat = (double)CellLatSize / 7200.;
		double halfCellLon = (double)CellLonSize / 7200.;
		double lat, lon;

		// Make sure either contour or geography is defined.  If both exist geography is used, the contour may exist on
		// a geography-based area because it was needed for replication.

		if (!source->contour && !source->geography) {
			log_error("Missing service area definition for sourceKey=%d", source->sourceKey);
			return 1;
		}

		int inServiceArea;

		latIndex = CellBounds.southLatIndex + (gridIndex.southLatIndex * CellLatSize);
		for (latGridIndex = gridIndex.southLatIndex; latGridIndex < gridIndex.northLatIndex;
				latGridIndex++, latIndex += CellLatSize) {

			hb_log();

			if (GRID_TYPE_GLOBAL == Params.GridType) {
				lonSize = CellLonSizes[latGridIndex];
				eastLonIndex = CellEastLonIndex[latGridIndex];
				eastLonGridIndex = (gridIndex.eastLonIndex - eastLonIndex) / lonSize;
				westLonGridIndex = (((gridIndex.westLonIndex - 1) - eastLonIndex) / lonSize) + 1;
				if (westLonGridIndex > GridLonCounts[latGridIndex]) {
					westLonGridIndex = GridLonCounts[latGridIndex];
				}
				halfCellLon = (double)lonSize / 7200.;
			}

			lonIndex = eastLonIndex + (eastLonGridIndex * lonSize);
			for (lonGridIndex = eastLonGridIndex; lonGridIndex < westLonGridIndex;
					lonGridIndex++, lonIndex += lonSize) {

				// If there are no points at the grid index, the center point must be checked; otherwise the location
				// of existing points that do not already have a desired field for this source must be checked.  If a
				// desired field already exists it was loaded from cache, just continue.  That would be an unusual
				// situation but it can happen if cache was partially loaded before an error.

				pointPtr = Cells + ((latGridIndex * GridLonCount) + lonGridIndex);
				point = *pointPtr;

				do {

					if (point) {

						fieldPtr = &(point->fields);
						field = *fieldPtr;
						while (field && (field->a.isUndesired || (field->sourceKey != source->sourceKey))) {
							fieldPtr = &(field->next);
							field = field->next;
						}
						if (field) {
							pointPtr = &(point->next);
							point = point->next;
							continue;
						}

						lat = point->latitude;
						lon = point->longitude;

					} else {

						lat = ((double)latIndex / 3600.) + halfCellLat;
						lon = ((double)lonIndex / 3600.) + halfCellLon;
					}

					bear_distance(source->latitude, source->longitude, lat, lon, &bear, &revBear, &dist,
						Params.KilometersPerDegree);

					// For DTS, the point has to be inside at least one individual DTS transmitter service area.
					// Depending on the TruncateDTS flag it may also have to be inside either the reference facility
					// contour, or a boundary around a reference point which may be a circle or a sectors geography.
					// The parent source record coordinates are the reference point, and in either case the boundary
					// is set as a service area geography on the parent source.  The final bearing and distance stored
					// in the study point are from the reference point.  Note the truncation flag is ignored when the
					// study mode is not individual service areas.

					if (source->isParent) {

						inServiceArea = 0;

						for (dtsSource = source->dtsSources; dtsSource; dtsSource = dtsSource->next) {

							if (dtsSource->geography) {

								if (inside_geography(lat, lon, dtsSource->geography)) {
									inServiceArea = 1;
									break;
								}

							} else {

								bear_distance(dtsSource->latitude, dtsSource->longitude, lat, lon, &bear1, NULL,
									&dist1, Params.KilometersPerDegree);
								if (dist1 <= interp_cont(bear1, dtsSource->contour)) {
									inServiceArea = 1;
									break;
								}
							}
						}

						if (inServiceArea && Params.TruncateDTS && (STUDY_AREA_SERVICE == StudyAreaMode)) {
							if (!inside_geography(lat, lon, source->geography)) {
								bear_distance(source->dtsRefSource->latitude, source->dtsRefSource->longitude,
									lat, lon, &bear1, NULL, &dist1, Params.KilometersPerDegree);
								if (dist1 > interp_cont(bear1, source->dtsRefSource->contour)) {
									inServiceArea = 0;
								}
							}
						}

					// Non-DTS is a simple geography or contour check.

					} else {

						if (source->geography) {

							inServiceArea = inside_geography(lat, lon, source->geography);

						} else {

							inServiceArea = (dist <= interp_cont(bear, source->contour));
						}
					}

					// If coordinates inside the area, add a desired field to the point.  If there is no point this is
					// a center point in a zero-population cell, add the point to the cell first; look up ground
					// elevation, determine country geographically, and look up clutter category as needed.

					if (inServiceArea) {

						if (!point) {

							point = get_point();
							*pointPtr = point;

							point->latitude = lat;
							point->longitude = lon;
							point->cellLatIndex = latIndex;
							point->cellLonIndex = lonIndex;

							// If find_country() does not determine the country, use the source's.

							err = find_country(lat, lon);
							if (err < 0) {
								return err;
							} else {
								if (err > 0) {
									point->countryKey = (short)err;
								} else {
									point->countryKey = source->countryKey;
								}
							}

							if (GRID_TYPE_GLOBAL == Params.GridType) {
								point->b.area = (float)CellAreas[latGridIndex];
							} else {
								point->b.area = (float)source->cellArea;
							}

							err = terrain_point(lat, lon, Params.TerrPathDb, &(point->elevation));
							if (err) {
								log_error("Terrain lookup failed: lat=%.8f lon=%.8f db=%d err=%d", lat, lon,
									Params.TerrPathDb, err);
								return err;
							}

							if (Params.ApplyClutter) {
								point->landCoverType = land_cover(lat, lon, Params.LandCoverVersion);
								if (point->landCoverType < 0) {
									point->clutterType = CLUTTER_UNKNOWN;
								} else {
									point->clutterType = (short)Params.LandCoverClutter[point->landCoverType];
								}
							} else {
								point->landCoverType = LANDCOVER_UNKNOWN;
								point->clutterType = CLUTTER_UNKNOWN;
							}

							// See discussion of this flag in load_population().  It is set to 1 here because the
							// status of Census points at this study point is fully resolved; there aren't any.

							point->cenPointStatus = 1;

							fieldPtr = &(point->fields);
						}

						add_field(&fieldPtr, source, 0, bear, revBear, dist, 0, lat, lon);
					}

					if (point) {
						pointPtr = &(point->next);
						point = point->next;
					}

				} while (point);
			}
		}
	}

	// Last step is to make a pass over points with desired fields and make sure they also have all undesired fields
	// as needed.  This checks the limiting distance to each point from the undesired's interference rule and only adds
	// fields to points inside the distance.  Of course the distance will have to be checked again later because there
	// may also be fields at points beyond the distance because they are needed for a different desired in global mode.

	// For a DTS operation the distance check may be performed by one of two methods selected by parameter.  The first
	// (original) method is based only on the distance to the study point from the DTS reference point, when that is
	// inside the distance, all individual sources are included even if some are outside the distance; similarily if
	// the reference point is outside, no sources are included even if some are actually inside.  During coverage
	// analysis, the source-to-cell distance check will use just the distance to the reference point, and include
	// sources as interferers all-or-nothing.  If the parameter selects the alternate method, the distance check is
	// applied to each individual source, if any one is inside the entire operation is included here, but individual
	// source-to-cell distances will be applied later during coverage analysis and not all sources will necessarily be
	// included as interferers at the point.  To keep storage indexing from getting too complicated, if a DTS operation
	// is placed in the field list, field structures for all it's sources must always be added as a complete set linked
	// in sequence.  So with the alternate method some fields may be calculated but never actually used.

	if ((source->undesiredCount > 0) || (source->isParent && Params.CheckSelfInterference)) {

		int hasDesired;
		double mindist;
		FIELD *selfIxUndField;

		for (latGridIndex = gridIndex.southLatIndex; latGridIndex < gridIndex.northLatIndex; latGridIndex++) {

			hb_log();

			if (GRID_TYPE_GLOBAL == Params.GridType) {
				lonSize = CellLonSizes[latGridIndex];
				eastLonIndex = CellEastLonIndex[latGridIndex];
				eastLonGridIndex = (gridIndex.eastLonIndex - eastLonIndex) / lonSize;
				westLonGridIndex = (((gridIndex.westLonIndex - 1) - eastLonIndex) / lonSize) + 1;
				if (westLonGridIndex > GridLonCounts[latGridIndex]) {
					westLonGridIndex = GridLonCounts[latGridIndex];
				}
			}

			pointPtr = Cells + ((latGridIndex * GridLonCount) + eastLonGridIndex);
			for (lonGridIndex = eastLonGridIndex; lonGridIndex < westLonGridIndex; lonGridIndex++, pointPtr++) {

				for (point = *pointPtr; point; point = point->next) {

					for (undesiredIndex = 0; undesiredIndex < undesiredCount; undesiredIndex++) {
						undesireds[undesiredIndex].field = NULL;
					}
					hasDesired = 0;
					selfIxUndField = NULL;

					fieldPtr = &(point->fields);
					field = *fieldPtr;
					while (field) {
						if (field->a.isUndesired) {
							for (undesiredIndex = 0; undesiredIndex < undesiredCount; undesiredIndex++) {
								if ((field->sourceKey == undesireds[undesiredIndex].sourceKey) &&
										(field->a.percentTime == undesireds[undesiredIndex].percentTime)) {
									undesireds[undesiredIndex].field = field;
									break;
								}
							}
							if (source->isParent && Params.CheckSelfInterference &&
									(field->sourceKey == source->sourceKey) &&
									(field->a.percentTime == Params.SelfIxUndesiredTime)) {
								selfIxUndField = field;
							}
						} else {
							if (field->sourceKey == source->sourceKey) {
								if (source->isParent) {
									for (dtsSource = source->dtsSources; dtsSource; dtsSource = dtsSource->next) {
										if (field->next) {
											field = field->next;
											if ((field->status < 0) || (field->fieldStrength > -999.)) {
												hasDesired = 1;
											}
										} else {
											break;
										}
									}
								} else {
									if ((field->status < 0) || (field->fieldStrength > -999.)) {
										hasDesired = 1;
									}
								}
							}
						}
						fieldPtr = &(field->next);
						field = field->next;
					}

					if (!hasDesired) {
						continue;
					}

					for (undesiredIndex = 0; undesiredIndex < undesiredCount; undesiredIndex++) {

						if (undesireds[undesiredIndex].field) {
							continue;
						}

						usource = SourceKeyIndex[undesireds[undesiredIndex].sourceKey];
						if (!usource) {
							log_error("Source structure index is corrupted");
							exit(1);
						}

						// The distance check is bypassed for some undesireds, see find_undesired() in source.c for
						// details.  In some cases if a source appears in the undesired list it gets fields at every
						// study point.  But bear_distance() is still called to get bearings for the field structure.

						bear_distance(usource->latitude, usource->longitude, point->latitude, point->longitude,
							&bear, &revBear, &dist, Params.KilometersPerDegree);

						if (undesireds[undesiredIndex].checkIxDistance) {

							if (usource->isParent && Params.CheckIndividualDTSDistance) {
								mindist = 9999.;
								for (dtsSource = usource->dtsSources; dtsSource; dtsSource = dtsSource->next) {
									bear_distance(dtsSource->latitude, dtsSource->longitude, point->latitude,
										point->longitude, NULL, NULL, &dist1, Params.KilometersPerDegree);
									if (dist1 < mindist) {
										mindist = dist1;
									}
								}
							} else {
								mindist = dist;
							}

							if (mindist > undesireds[undesiredIndex].ixDistance) {
								continue;
							}
						}

						add_field(&fieldPtr, usource, undesireds[undesiredIndex].percentTime, bear, revBear, dist, 0,
							point->latitude, point->longitude);
					}

					// Add undesired fields for the desired source for DTS self-interference analysis if needed.

					if (source->isParent && Params.CheckSelfInterference && !selfIxUndField) {

						bear_distance(source->latitude, source->longitude, point->latitude, point->longitude, &bear,
							&revBear, &dist, Params.KilometersPerDegree);

						add_field(&fieldPtr, source, (short)Params.SelfIxUndesiredTime, bear, revBear, dist, 0,
							point->latitude, point->longitude);
					}
				}
			}
		}
	}

	// Done.

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Do population queries, add points as needed to the cell grid to accumulate population per country and compute the
// study point coordinates as needed.  The cell grid may be in any state here, if existing points are found they may
// be updated as needed or not disturbed.  Returns non-zero on error.  Note the argument bounds structure holds units
// of grid index in the current cell grid rather than arc-second index values, except in global mode the longitude
// values are the arc-seconds values because the grid index range has to be calculated row-by-row.

// Tabulation of population can be disabled for any country, or even all of them, if the CenYear[?] parameter is 0.  If
// all countries are disabled the study results will report area only.

// This does nothing special with regard to over-range longitude grids, that is grids with west longitude index that
// falls west of the 180-degree line and so containing points with longitude values >180 degrees, or vice-versa for
// longitudes <-180 degrees.  In the population database tables, points that fall close to the 180-degree line have
// been duplicated with one having actual coordinates and the other over-range coordinates, so one or the other will
// fall in an over-range study grid.  A grid that is over-range in both directions (west >180 degrees AND east <-180
// degrees) is very unlikely, but will also work correctly.  In that case the opposite sides of the grid represent an
// "overlap" region that includes duplicate cells, that is cells representing the same actual cell area but with
// over-range longitudes in opposite directions and with study points linked to different desired stations.  In that
// case the duplicate Census points will correctly be assigned to just one or the other of the duplicate cells.

// Arguments:

//   gridIndex  Bounds of grid area to load, nominally in units of grid index based on current cell sizes; however
//                when GridType is GRID_TYPE_GLOBAL, the longitude values in this structure are still in units of arc-
//                seconds, and the longitude grid index range is computed locally for each row.
//   source     In local grid mode, the source being studied; ignored and may be NULL in global mode.

// Return <0 for serious error, >0 for minor error, 0 for no error.

static int load_population(INDEX_BOUNDS gridIndex, SOURCE *source) {

	// Determine the desired cell index range for the population query.  In global grid mode the longitude grid index
	// range may change row-to-row, so every row has to be checked to set the bounds.  The query may include points
	// outside the cell range in some rows, but the grid index range will exclude those points.

	int lonSize = CellLonSize;
	int eastLonIndex = CellBounds.eastLonIndex;
	int eastLonGridIndex = gridIndex.eastLonIndex;
	int westLonGridIndex = gridIndex.westLonIndex;

	int latIndex, lonIndex, latGridIndex, lonGridIndex;

	INDEX_BOUNDS popBox;

	popBox.southLatIndex = CellBounds.southLatIndex + (gridIndex.southLatIndex * CellLatSize);
	popBox.northLatIndex = CellBounds.southLatIndex + (gridIndex.northLatIndex * CellLatSize);

	if (GRID_TYPE_GLOBAL == Params.GridType) {

		popBox.eastLonIndex = gridIndex.eastLonIndex;
		popBox.westLonIndex = gridIndex.westLonIndex;

		for (latGridIndex = gridIndex.southLatIndex; latGridIndex < gridIndex.northLatIndex; latGridIndex++) {

			lonSize = CellLonSizes[latGridIndex];
			eastLonIndex = CellEastLonIndex[latGridIndex];

			eastLonGridIndex = (gridIndex.eastLonIndex - eastLonIndex) / lonSize;
			lonIndex = eastLonIndex + (eastLonGridIndex * lonSize);
			if (lonIndex < popBox.eastLonIndex) {
				popBox.eastLonIndex = lonIndex;
			}

			westLonGridIndex = (((gridIndex.westLonIndex - 1) - eastLonIndex) / lonSize) + 1;
			if (westLonGridIndex > GridLonCounts[latGridIndex]) {
				westLonGridIndex = GridLonCounts[latGridIndex];
			}
			lonIndex = eastLonIndex + (westLonGridIndex * lonSize);
			if (lonIndex > popBox.westLonIndex) {
				popBox.westLonIndex = lonIndex;
			}
		}

	} else {

		popBox.eastLonIndex = eastLonIndex + (eastLonGridIndex * lonSize);
		popBox.westLonIndex = eastLonIndex + (westLonGridIndex * lonSize);
	}

	// Run the queries.

	char query[MAX_QUERY];
	MYSQL_RES *myResult;
	my_ulonglong rowCount, rowIndex;
	MYSQL_ROW fields;

	int countryIndex, countryKey, pop, err;
	double lat, lon;
	char *countryCode;
	TVPOINT **pointPtr, *point;
	CEN_POINT *cenPoint;

	for (countryIndex = 0; countryIndex < MAX_COUNTRY; countryIndex++) {

		if (!Params.CenYear[countryIndex]) {
			continue;
		}

		countryKey = countryIndex + 1;
		switch (countryKey) {
			case CNTRY_USA:
			default:
				countryCode = "us";
				break;
			case CNTRY_CAN:
				countryCode = "ca";
				break;
			case CNTRY_MEX:
				countryCode = "mx";
				break;
		}

		snprintf(query, MAX_QUERY, "SELECT lat_index, lon_index, latitude, longitude, population, households, id FROM %s.pop_%s_%d WHERE (lat_index BETWEEN %d AND %d) AND (lon_index BETWEEN %d AND %d) ORDER BY 1, 2;",
			DbName, countryCode, Params.CenYear[countryIndex], popBox.southLatIndex, (popBox.northLatIndex - 1),
			popBox.eastLonIndex, (popBox.westLonIndex - 1));
		if (mysql_query(MyConnection, query)) {
			log_db_error("Population query failed (1)");
			return -1;
		}

		myResult = mysql_store_result(MyConnection);
		if (!myResult) {
			log_db_error("Population query failed (2)");
			return -1;
		}

		rowCount = mysql_num_rows(myResult);

		for (rowIndex = 0; rowIndex < rowCount; rowIndex++) {

			hb_log();

			fields = mysql_fetch_row(myResult);
			if (!fields) {
				mysql_free_result(myResult);
				log_db_error("Population query failed (3)");
				return -1;
			}

			latIndex = atoi(fields[0]);
			lonIndex = atoi(fields[1]);
			lat = atof(fields[2]);
			lon = atof(fields[3]);
			pop = atoi(fields[4]);

			if (Params.RoundPopCoords[countryIndex]) {
				lat = rint(lat * 3600.) / 3600.;
				lon = rint(lon * 3600.) / 3600.;
			}

			// This is paranoia, zero-population entries should never exist in any database.

			if (!pop) {
				continue;
			}

			// Compute the cell index for the point, ignore anything that is outside the study grid.

			latGridIndex = (latIndex - CellBounds.southLatIndex) / CellLatSize;
			if ((latGridIndex < gridIndex.southLatIndex) || (latGridIndex >= gridIndex.northLatIndex)) {
				continue;
			}

			if (GRID_TYPE_GLOBAL == Params.GridType) {
				lonSize = CellLonSizes[latGridIndex];
				eastLonIndex = CellEastLonIndex[latGridIndex];
				if (lonIndex >= eastLonIndex) {
					lonGridIndex = (lonIndex - eastLonIndex) / lonSize;
				} else {
					lonGridIndex = -1;
				}
				eastLonGridIndex = (gridIndex.eastLonIndex - eastLonIndex) / lonSize;
				westLonGridIndex = (((gridIndex.westLonIndex - 1) - eastLonIndex) / lonSize) + 1;
				if (westLonGridIndex > GridLonCounts[latGridIndex]) {
					westLonGridIndex = GridLonCounts[latGridIndex];
				}
			} else {
				lonGridIndex = (lonIndex - eastLonIndex) / lonSize;
			}
			if ((lonGridIndex < eastLonGridIndex) || (lonGridIndex >= westLonGridIndex)) {
				continue;
			}

			// Determine if this Census point creates a new study point, is added to an existing study point, or is
			// ignored.  Study points may already be defined from a cache load or an earlier population query for an
			// overlapping cell grid.  Census points are not cached so in the cache load case the Census point list
			// needs to be reconstructed, but it is assumed that the aggregate values for coordinates and totals will
			// not change, this is just restoring the detail breakdown.  If the Census points are already defined from
			// a previous overlapping query, nothing needs to be done here, the Census point is ignored.  Finding the
			// matching study point always means matching the country key, a study point never aggregates Census points
			// across country boundaries.  When the aggregate method is all (one Census point per study point) the
			// point coordinates must also match.  The cenPointStatus flag is 0 for a study point loaded from cache,
			// -1 for a point being newly constructed during this query, and 1 if already complete from a previous
			// query or grid setup.

			pointPtr = Cells + ((latGridIndex * GridLonCount) + lonGridIndex);
			point = *pointPtr;

			if (POINT_METHOD_ALL == Params.StudyPointMethod) {

				while (point && ((point->cenPointStatus < 0) || (countryKey != point->countryKey) ||
						(lat != point->latitude) || (lon != point->longitude))) {
					pointPtr = &(point->next);
					point = point->next;
				}

			} else {

				while (point && (countryKey != point->countryKey)) {
					pointPtr = &(point->next);
					point = point->next;
				}
			}

			if (!point) {

				point = get_point();
				*pointPtr = point;

				point->countryKey = countryKey;

				point->cellLatIndex = CellBounds.southLatIndex + (latGridIndex * CellLatSize);
				point->cellLonIndex = eastLonIndex + (lonGridIndex * lonSize);

				point->cenPointStatus = -1;
			}

			if (point->cenPointStatus <= 0) {

				cenPoint = get_cen_point();
				cenPoint->latitude = lat;
				cenPoint->longitude = lon;
				cenPoint->population = pop;
				cenPoint->households = atoi(fields[5]);
				lcpystr(cenPoint->blockID, fields[6], BLOCK_ID_L);

				cenPoint->next = point->censusPoints;
				point->censusPoints = cenPoint;
			}
		}

		mysql_free_result(myResult);
	}

	// Scan the grid range and update the study points as needed.  When constructing a new study point, the total
	// population and households are computed, and the study point coordinates are derived from the Census point
	// coordinates by one of several different methods.  The study point ground elevation and land cover parameters
	// are also set.  Area is also determined, when a cell contains multiple study points the total cell area is
	// proportioned to the points according to total population.

	int cellTotalPop, maxPop;
	double cellTotalArea, cenlat, cenlon, minDist, dlat, dlon, dist;

	double halfCellLat = (double)CellLatSize / 7200.;
	double halfCellLon = (double)CellLonSize / 7200.;

	for (latGridIndex = gridIndex.southLatIndex; latGridIndex < gridIndex.northLatIndex; latGridIndex++) {

		hb_log();

		if (GRID_TYPE_GLOBAL == Params.GridType) {
			lonSize = CellLonSizes[latGridIndex];
			eastLonIndex = CellEastLonIndex[latGridIndex];
			eastLonGridIndex = (gridIndex.eastLonIndex - eastLonIndex) / lonSize;
			westLonGridIndex = (((gridIndex.westLonIndex - 1) - eastLonIndex) / lonSize) + 1;
			if (westLonGridIndex > GridLonCounts[latGridIndex]) {
				westLonGridIndex = GridLonCounts[latGridIndex];
			}
			cellTotalArea = CellAreas[latGridIndex];
			halfCellLon = (double)lonSize / 7200.;
		} else {
			cellTotalArea = source->cellArea;
		}

		pointPtr = Cells + ((latGridIndex * GridLonCount) + eastLonGridIndex);
		for (lonGridIndex = eastLonGridIndex; lonGridIndex < westLonGridIndex; lonGridIndex++, pointPtr++) {

			cellTotalPop = 0;

			for (point = *pointPtr; point; point = point->next) {

				if (point->cenPointStatus < 0) {

					lat = 0.;
					lon = 0.;
					maxPop = 0;

					for (cenPoint = point->censusPoints; cenPoint; cenPoint = cenPoint->next) {

						pop = cenPoint->population;
						point->a.population += pop;
						point->households += cenPoint->households;

						switch (Params.StudyPointMethod) {

							case POINT_METHOD_CENTROID:
							default: {
								lat += cenPoint->latitude * (double)pop;
								lon += cenPoint->longitude * (double)pop;
								break;
							}

							case POINT_METHOD_LARGEST: {
								if (pop > maxPop) {
									lat = cenPoint->latitude;
									lon = cenPoint->longitude;
									maxPop = pop;
								}
								break;
							}

							case POINT_METHOD_CENTER:
							case POINT_METHOD_ALL: {
								break;
							}
						}
					}

					switch (Params.StudyPointMethod) {

						case POINT_METHOD_CENTROID:
						default: {
							lat /= (double)(point->a.population);
							lon /= (double)(point->a.population);
							if (Params.RoundPopCoords[point->countryKey - 1]) {
								lat = rint(lat * 3600.) / 3600.;
								lon = rint(lon * 3600.) / 3600.;
							}
							break;
						}

						case POINT_METHOD_LARGEST: {
							break;
						}

						case POINT_METHOD_CENTER: {
							lat = ((double)point->cellLatIndex / 3600.) + halfCellLat;
							lon = ((double)point->cellLonIndex / 3600.) + halfCellLon;
							break;
						}

						case POINT_METHOD_ALL: {
							lat = cenPoint->latitude;
							lon = cenPoint->longitude;
							break;
						}
					}

					if (Params.StudyPointToNearestCP && ((POINT_METHOD_CENTROID == Params.StudyPointMethod) ||
							(POINT_METHOD_CENTER == Params.StudyPointMethod))) {
						cenlat = lat;
						cenlon = lon;
						minDist = 99999.;
						for (cenPoint = point->censusPoints; cenPoint; cenPoint = cenPoint->next) {
							dlat = cenlat - cenPoint->latitude;
							dlon = cenlon - cenPoint->longitude;
							dist = sqrt((dlat * dlat) + (dlon * dlon));
							if (dist < minDist) {
								lat = cenPoint->latitude;
								lon = cenPoint->longitude;
								minDist = dist;
							}
						}
					}

					point->latitude = lat;
					point->longitude = lon;

					err = terrain_point(lat, lon, Params.TerrPathDb, &(point->elevation));
					if (err) {
						log_error("Terrain lookup failed: lat=%.8f lon=%.8f db=%d err=%d", lat, lon, Params.TerrPathDb,
							err);
						return err;
					}

					if (Params.ApplyClutter) {
						point->landCoverType = land_cover(lat, lon, Params.LandCoverVersion);
						if (point->landCoverType < 0) {
							point->clutterType = CLUTTER_UNKNOWN;
						} else {
							point->clutterType = (short)Params.LandCoverClutter[point->landCoverType];
						}
					} else {
						point->landCoverType = LANDCOVER_UNKNOWN;
						point->clutterType = CLUTTER_UNKNOWN;
					}
				}

				cellTotalPop += point->a.population;
			}

			// Do a second pass to compute areas as needed, then set the status to done.  That happens on all points
			// in the queried grid range; all points now either have Census points, or are known to be area-only.

			for (point = *pointPtr; point; point = point->next) {

				if (point->cenPointStatus < 0) {
					point->b.area = (float)(((double)point->a.population / (double)cellTotalPop) * cellTotalArea);
				}

				point->cenPointStatus = 1;
			}
		}
	}

	// Done.

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// In points mode, load the point coordinates and build the points list.

// Arguments:
//    pointSetKey  Point set.

// Return value is the usual.

int load_points(int pointSetKey) {

	if (!StudyKey) {
		log_error("load_points() called with no study open");
		return 1;
	}

	if (STUDY_MODE_POINTS != StudyMode) {
		log_error("load_points() called in grid mode");
		return 1;
	}

	// Zero out all storage, including any existing cell grid.

	init_storage();

	TVPOINT *point, **pointPtr = &Points;
	POINT_INFO *pointInfo;

	int numPoints = 0, maxPoints = 0;

	// Query table for point coordinates and receiver heights.

	char query[MAX_QUERY];
	MYSQL_RES *myResult;
	my_ulonglong rowCount, rowIndex;
	MYSQL_ROW fields;

	snprintf(query, MAX_QUERY, "SELECT point_name, latitude, longitude, receive_height, antenna_key, antenna_orientation FROM %s.geo_point_set WHERE geo_key = %d ORDER BY point_name;", DbName, pointSetKey);
	if (mysql_query(MyConnection, query)) {
		log_db_error("Study points query failed (1)");
		return -1;
	}

	myResult = mysql_store_result(MyConnection);
	if (!myResult) {
		log_db_error("Study points query failed (2)");
		return -1;
	}

	rowCount = mysql_num_rows(myResult);
	if (!rowCount) {
		mysql_free_result(myResult);
		log_error("No study points found for studyKey=%d pointSetKey=%d", StudyKey, pointSetKey);
		return 1;
	}

	int num = 0;

	for (rowIndex = 0; rowIndex < rowCount; rowIndex++) {

		fields = mysql_fetch_row(myResult);
		if (!fields) {
			mysql_free_result(myResult);
			log_db_error("Study points query failed (3)");
			return -1;
		}

		point = get_point();
		*pointPtr = point;
		pointPtr = &(point->next);

		point->cellLatIndex = INVALID_LATLON_INDEX;
		point->cellLonIndex = INVALID_LATLON_INDEX;

		if (numPoints >= maxPoints) {
			maxPoints += 50;
			PointInfos = (POINT_INFO *)mem_realloc(PointInfos, (maxPoints * sizeof(POINT_INFO)));
		}

		point->a.pointIndex = numPoints;

		pointInfo = PointInfos + numPoints;
		pointInfo->receiveAnt = NULL;

		if (strlen(fields[0]) > 0) {
			lcpystr(pointInfo->pointName, fields[0], MAX_STRING);
		} else {
			num++;
			snprintf(pointInfo->pointName, MAX_STRING, "Point #%d", num);
		}

		point->latitude = atof(fields[1]);
		point->longitude = atof(fields[2]);

		point->b.receiveHeight = atof(fields[3]);

		pointInfo->antennaKey = atoi(fields[4]);

		pointInfo->receiveOrient = atof(fields[5]);

		numPoints++;
	}

	// Second pass over the loaded points to determine country, ground elevation, and land clutter as needed, and to
	// load receive patterns.

	int err = 0;

	for (point = Points; point; point = point->next) {

		err = find_country(point->latitude, point->longitude);
		if (err < 0) {
			return err;
		} else {
			if (err > 0) {
				point->countryKey = (short)err;
			} else {
				point->countryKey = CNTRY_USA;
			}
		}

		err = terrain_point(point->latitude, point->longitude, Params.TerrPathDb, &(point->elevation));
		if (err) {
			log_error("Terrain lookup failed: lat=%.8f lon=%.8f db=%d err=%d", point->latitude, point->longitude,
				Params.TerrPathDb, err);
			return err;
		}

		if (Params.ApplyClutter) {
			point->landCoverType = land_cover(point->latitude, point->longitude, Params.LandCoverVersion);
			if (point->landCoverType < 0) {
				point->clutterType = CLUTTER_UNKNOWN;
			} else {
				point->clutterType = (short)Params.LandCoverClutter[point->landCoverType];
			}
		} else {
			point->landCoverType = LANDCOVER_UNKNOWN;
			point->clutterType = CLUTTER_UNKNOWN;
		}

		pointInfo = PointInfos + point->a.pointIndex;

		if (pointInfo->antennaKey > 0) {
			pointInfo->receiveAnt = get_receive_antenna(pointInfo->antennaKey);
		}
	}

	// Done.

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// In points mode, clear fields out of the point structures and re-initialize field pools.  Called between scenarios.

int clear_points() {

	if (STUDY_MODE_POINTS != StudyMode) {
		log_error("clear_points() called in grid mode");
		return 1;
	}

	TVPOINT *point;
	for (point = Points; point; point = point->next) {
		point->fields = NULL;
	}

	init_field_pools();

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Set up study points for a particular source in points mode.  In this mode arbitrary study points are being analyzed
// with no surrounding grid or cell structure hence no population or area reporting, just coverage and interference
// conditions at the individual points.  This runs through the points list and adds desired and undesired field
// structures as needed for the analysis.  Note there is no cell-level caching in points mode.

// Arguments:

//   source  The source to set up.

// Return <0 for serious error, >0 for minor error, 0 for no error.

int points_setup(SOURCE *source) {

	if (STUDY_MODE_POINTS != StudyMode) {
		log_error("points_setup() called in grid mode");
		return 1;
	}

	double bear, revBear, dist, bear1, dist1, mindist, lat, lon;
	TVPOINT *point;
	FIELD **fieldPtr, *field;
	SOURCE *dtsSource, *usource;

	UNDESIRED *undesireds = source->undesireds;
	int undesiredIndex, undesiredCount = source->undesiredCount;

	if (!source->contour && !source->geography) {
		log_error("Missing service area definition for sourceKey=%d", source->sourceKey);
		return 1;
	}

	int hasDesired, inServiceArea;
	FIELD *selfIxUndField;

	// Loop over the points.  First check the field list, there should not already be any desired fields for this
	// source but if there is one, skip the point.  Along the way also identify any matching undesired fields in the
	// list, those may legitimately already be present due to a relationship to a different desired.

	for (point = Points; point; point = point->next) {

		for (undesiredIndex = 0; undesiredIndex < undesiredCount; undesiredIndex++) {
			undesireds[undesiredIndex].field = NULL;
		}
		selfIxUndField = NULL;
		hasDesired = 0;

		fieldPtr = &(point->fields);
		field = *fieldPtr;
		while (field) {
			if (field->a.isUndesired) {
				for (undesiredIndex = 0; undesiredIndex < undesiredCount; undesiredIndex++) {
					if ((field->sourceKey == undesireds[undesiredIndex].sourceKey) &&
							(field->a.percentTime == undesireds[undesiredIndex].percentTime)) {
						undesireds[undesiredIndex].field = field;
						break;
					}
				}
				if (source->isParent && Params.CheckSelfInterference && (field->sourceKey == source->sourceKey) &&
						(field->a.percentTime == Params.SelfIxUndesiredTime)) {
					selfIxUndField = field;
				}
			} else {
				if (field->sourceKey == source->sourceKey) {
					hasDesired = 1;
					break;
				}
			}
			fieldPtr = &(field->next);
			field = field->next;
		}

		if (hasDesired) {
			continue;
		}

		// Always create a desired field, regardless of distance.  For reporting purposes every desired source in the
		// scenario needs to be represented at every point.  If the point is beyond the maximum distance, set up the
		// field in a manner indicating it is un-calculated but fully defined.  Undesired fields will not be added in
		// that case.  Also for informational purposes determine if the point is inside the service area.  That does
		// not affect calculations, just reporting.  See comments in cell_setup() regarding the service area tests.

		lat = point->latitude;
		lon = point->longitude;

		bear_distance(source->latitude, source->longitude, lat, lon, &bear, &revBear, &dist,
			Params.KilometersPerDegree);

		if (source->isParent) {

			inServiceArea = 0;

			for (dtsSource = source->dtsSources; dtsSource; dtsSource = dtsSource->next) {

				if (dtsSource->geography) {

					if (inside_geography(lat, lon, dtsSource->geography)) {
						inServiceArea = 1;
						break;
					}

				} else {

					bear_distance(dtsSource->latitude, dtsSource->longitude, lat, lon, &bear1, NULL, &dist1,
						Params.KilometersPerDegree);
					if (dist1 <= interp_cont(bear1, dtsSource->contour)) {
						inServiceArea = 1;
						break;
					}
				}
			}

			if (inServiceArea && Params.TruncateDTS) {
				if (!inside_geography(lat, lon, source->geography)) {
					bear_distance(source->dtsRefSource->latitude, source->dtsRefSource->longitude, lat, lon, &bear1,
						NULL, &dist1, Params.KilometersPerDegree);
					if (dist1 > interp_cont(bear1, source->dtsRefSource->contour)) {
						inServiceArea = 0;
					}
				}
			}

		} else {

			if (source->geography) {

				inServiceArea = inside_geography(lat, lon, source->geography);

			} else {

				inServiceArea = (dist <= interp_cont(bear, source->contour));
			}
		}

		field = add_field(&fieldPtr, source, 0, bear, revBear, dist, (short)inServiceArea, lat, lon);
		hasDesired = (field->status < 0);

		// If the point is not excluded by distance, add any missing undesired fields.  This checks the limiting
		// distance for each undesired from it's interference rule and does not add fields that would be excluded by
		// that test during calculations.  Again see comments in cell_setup() for details of the logic.

		if (hasDesired) {

			for (undesiredIndex = 0; undesiredIndex < undesiredCount; undesiredIndex++) {

				if (undesireds[undesiredIndex].field) {
					continue;
				}

				usource = SourceKeyIndex[undesireds[undesiredIndex].sourceKey];
				if (!usource) {
					log_error("Source structure index is corrupted");
					exit(1);
				}

				bear_distance(usource->latitude, usource->longitude, point->latitude, point->longitude, &bear,
					&revBear, &dist, Params.KilometersPerDegree);

				if (undesireds[undesiredIndex].checkIxDistance) {

					if (usource->isParent && Params.CheckIndividualDTSDistance) {
						mindist = 9999.;
						for (dtsSource = usource->dtsSources; dtsSource; dtsSource = dtsSource->next) {
							bear_distance(dtsSource->latitude, dtsSource->longitude, point->latitude, point->longitude,
								NULL, NULL, &dist1, Params.KilometersPerDegree);
							if (dist1 < mindist) {
								mindist = dist1;
							}
						}
					} else {
						mindist = dist;
					}

					if (mindist > undesireds[undesiredIndex].ixDistance) {
						continue;
					}
				}

				add_field(&fieldPtr, usource, undesireds[undesiredIndex].percentTime, bear, revBear, dist, 0,
					point->latitude, point->longitude);
			}

			// Add undesired fields for the desired source for DTS self-interference analysis if needed.

			if (source->isParent && Params.CheckSelfInterference && !selfIxUndField) {

				bear_distance(source->latitude, source->longitude, point->latitude, point->longitude, &bear, &revBear,
					&dist, Params.KilometersPerDegree);

				add_field(&fieldPtr, source, (short)Params.SelfIxUndesiredTime, bear, revBear, dist, 0,
					point->latitude, point->longitude);
			}
		}
	}

	// Done.

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Add field structure or structures to a field list.  In the case of DTS, fields for all the individual transmitter
// sources are added immediately after one for the parent source.  The set will always be kept together including in
// the caches.  The DTS parent field is a placeholder, it will never have an actual field value so it is flagged as
// calculated here.  Caller provides bearings and distance for the first field, for DTS additional bearing and distance
// calculations are needed for the additional fields so the point latitude and longitude are also provided.  For a
// desired field (percentTime is 0) the maximum signal calculation distance is checked, if beyond that distance the
// field is marked calculated with a no-result placeholder field strength.

static FIELD *add_field(FIELD ***fieldPtr, SOURCE *source, short percentTime, double bear, double revBear, double dist,
		short cached, double lat, double lon) {

	FIELD *field = get_field();
	**fieldPtr = field;
	*fieldPtr = &(field->next);

	field->sourceKey = source->sourceKey;
	field->a.percentTime = percentTime;
	field->bearing = (float)bear;
	field->reverseBearing = (float)revBear;
	field->distance = (float)dist;
	field->b.cached = cached;

	if (source->isParent) {

		field->fieldStrength = 0.;
		field->status = 0;

		SOURCE *dtsSource;
		FIELD *dtsField;

		for (dtsSource = source->dtsSources; dtsSource; dtsSource = dtsSource->next) {

			bear_distance(dtsSource->latitude, dtsSource->longitude, lat, lon, &bear, &revBear, &dist,
				Params.KilometersPerDegree);

			dtsField = get_field();
			**fieldPtr = dtsField;
			*fieldPtr = &(dtsField->next);

			dtsField->sourceKey = dtsSource->sourceKey;
			dtsField->a.percentTime = percentTime;
			dtsField->bearing = (float)bear;
			dtsField->reverseBearing = (float)revBear;
			dtsField->distance = (float)dist;
			dtsField->b.cached = cached;

			if ((0 == percentTime) && (dist > Params.MaximumDistance)) {
				dtsField->fieldStrength = -999.;
				dtsField->status = 0;
			} else {
				dtsField->status = -1;
			}
		}

	} else {

		if ((0 == percentTime) && (dist > Params.MaximumDistance)) {
			field->fieldStrength = -999.;
			field->status = 0;
		} else {
			field->status = -1;
		}
	}

	return field;
}


//---------------------------------------------------------------------------------------------------------------------
// Clear or free storage from a previous setup, zero out the grid array, release point storage from a points setup,
// and initialize the point and field pools.

static void init_storage() {

	if (GridMaxCount) {
		memset(Cells, 0, (GridMaxCount * sizeof(TVPOINT *)));
	}

	Points = NULL;
	if (PointInfos) {
		mem_free(PointInfos);
		PointInfos = NULL;
	}

	init_point_pools();
	init_cen_point_pools();
	init_field_pools();
}


//---------------------------------------------------------------------------------------------------------------------
// Functions to manage allocation of POINT, CEN_POINT, and FIELD structures for the study grid, using large pre-
// allocated pools of those structures to reduce load on the low-level allocator.  These structures are never freed
// once allocated; the existing pools can be cleared and re-used for a new study grid.  The init functions must always
// be called before the first call to the get functions, and may be called again at any time to clear pools for re-use
// and zero all content.

#define POINT_POOL_SIZE 50000

typedef struct ppl {
	TVPOINT points[POINT_POOL_SIZE];
	int freePointIndex;
	struct ppl *next;
} POINT_POOL;

static POINT_POOL *pointPoolHead = NULL;
static POINT_POOL *pointPool = NULL;

static void init_point_pools() {

	if (pointPoolHead) {
		for (pointPool = pointPoolHead; pointPool; pointPool = pointPool->next) {
			memset(pointPool->points, 0, (POINT_POOL_SIZE * sizeof(TVPOINT)));
			pointPool->freePointIndex = 0;
		}
	} else {
		pointPoolHead = (POINT_POOL *)mem_zalloc(sizeof(POINT_POOL));
	}

	pointPool = pointPoolHead;
}


//---------------------------------------------------------------------------------------------------------------------

#define CEN_POINT_POOL_SIZE 100000

typedef struct cpl {
	CEN_POINT cenPoints[CEN_POINT_POOL_SIZE];
	int freeCenPointIndex;
	struct cpl *next;
} CEN_POINT_POOL;

static CEN_POINT_POOL *cenPointPoolHead = NULL;
static CEN_POINT_POOL *cenPointPool = NULL;

static void init_cen_point_pools() {

	if (cenPointPoolHead) {
		for (cenPointPool = cenPointPoolHead; cenPointPool; cenPointPool = cenPointPool->next) {
			memset(cenPointPool->cenPoints, 0, (CEN_POINT_POOL_SIZE * sizeof(CEN_POINT)));
			cenPointPool->freeCenPointIndex = 0;
		}
	} else {
		cenPointPoolHead = (CEN_POINT_POOL *)mem_zalloc(sizeof(CEN_POINT_POOL));
	}

	cenPointPool = cenPointPoolHead;
}


//---------------------------------------------------------------------------------------------------------------------

#define FIELD_POOL_SIZE 200000

typedef struct fpl {
	FIELD fields[FIELD_POOL_SIZE];
	int freeFieldIndex;
	struct fpl *next;
} FIELD_POOL;

static FIELD_POOL *fieldPoolHead = NULL;
static FIELD_POOL *fieldPool = NULL;

static void init_field_pools() {

	if (fieldPoolHead) {
		for (fieldPool = fieldPoolHead; fieldPool; fieldPool = fieldPool->next) {
			memset(fieldPool->fields, 0, (FIELD_POOL_SIZE * sizeof(FIELD)));
			fieldPool->freeFieldIndex = 0;
		}
	} else {
		fieldPoolHead = (FIELD_POOL *)mem_zalloc(sizeof(FIELD_POOL));
	}

	fieldPool = fieldPoolHead;
}


//---------------------------------------------------------------------------------------------------------------------
// Get a POINT, CEN_POINT, or FIELD structure from a pool.  These never fail, if allocation fails exit() is called.

TVPOINT *get_point() {

	if (POINT_POOL_SIZE == pointPool->freePointIndex) {
		if (!pointPool->next) {
			pointPool->next = (POINT_POOL *)mem_zalloc(sizeof(POINT_POOL));
		}
		pointPool = pointPool->next;
	}

	return(pointPool->points + pointPool->freePointIndex++);
}


//---------------------------------------------------------------------------------------------------------------------

CEN_POINT *get_cen_point() {

	if (CEN_POINT_POOL_SIZE == cenPointPool->freeCenPointIndex) {
		if (!cenPointPool->next) {
			cenPointPool->next = (CEN_POINT_POOL *)mem_zalloc(sizeof(CEN_POINT_POOL));
		}
		cenPointPool = cenPointPool->next;
	}

	return(cenPointPool->cenPoints + cenPointPool->freeCenPointIndex++);
}


//---------------------------------------------------------------------------------------------------------------------

FIELD *get_field() {

	if (FIELD_POOL_SIZE == fieldPool->freeFieldIndex) {
		if (!fieldPool->next) {
			fieldPool->next = (FIELD_POOL *)mem_zalloc(sizeof(FIELD_POOL));
		}
		fieldPool = fieldPool->next;
	}

	return(fieldPool->fields + fieldPool->freeFieldIndex++);
}


//---------------------------------------------------------------------------------------------------------------------
// Construct an isolated point structure for arbitrary-location field strength calculations outside the main analysis
// code (this is not used in points mode, this is for standalone calculations done during pre-study checks for some
// study types).  The point will not be part of a cell or any points list.  The cell index values are set invalid and
// area and population are 0.  The point will contain a field structure to compute a desired signal for the specified
// source, or a list of field structures if the source is DTS.  However unlike during normal study calculations, in the
// case of DTS there is no initial placeholder field for the parent source, only fields for the actual DTS transmitter
// sources are included.  Memory is allocated directly not from the pools, so the return must be freed by free_point().

// Arguments:

//   lat, lon  Latitude and longitude of point
//   target    Source to project

// Return is NULL if an error occurs.

TVPOINT *make_point(double lat, double lon, SOURCE *target) {

	TVPOINT *point = (TVPOINT *)mem_zalloc(sizeof(TVPOINT));

	point->latitude = lat;
	point->longitude = lon;
	point->cellLatIndex = INVALID_LATLON_INDEX;
	point->cellLonIndex = INVALID_LATLON_INDEX;

	int err = find_country(lat, lon);
	if (err < 0) {
		free_point(point);
		return NULL;
	} else {
		if (err > 0) {
			point->countryKey = (short)err;
		} else {
			point->countryKey = target->countryKey;
		}
	}

	err = terrain_point(lat, lon, Params.TerrPathDb, &(point->elevation));
	if (err) {
		log_error("Terrain lookup failed: lat=%.8f lon=%.8f db=%d err=%d", lat, lon, Params.TerrPathDb, err);
		free_point(point);
		return NULL;
	}

	if (Params.ApplyClutter) {
		point->landCoverType = land_cover(lat, lon, Params.LandCoverVersion);
		if (point->landCoverType < 0) {
			point->clutterType = CLUTTER_UNKNOWN;
		} else {
			point->clutterType = (short)Params.LandCoverClutter[point->landCoverType];
		}
	} else {
		point->landCoverType = LANDCOVER_UNKNOWN;
		point->clutterType = CLUTTER_UNKNOWN;
	}

	SOURCE *source, *sources;
	FIELD *field, **fieldPtr = &(point->fields);
	double bear, revBear, dist;

	if (target->isParent) {
		sources = target->dtsSources;
	} else {
		sources = target;
		target->next = NULL;
	}

	for (source = sources; source; source = source->next) {

		bear_distance(source->latitude, source->longitude, lat, lon, &bear, &revBear, &dist,
			Params.KilometersPerDegree);

		field = (FIELD *)mem_zalloc(sizeof(FIELD));
		*fieldPtr = field;
		fieldPtr = &(field->next);

		field->sourceKey = source->sourceKey;
		field->a.isUndesired = 0;
		field->bearing = (float)bear;
		field->reverseBearing = (float)revBear;
		field->distance = (float)dist;
		field->status = -1;
	}

	return point;
}


//---------------------------------------------------------------------------------------------------------------------
// Free memory for a point from make_point().  If a grid point structure is inadvertently passed this will do nothing.

// Arguments:

//   point  The point to free

void free_point(TVPOINT *point) {

	if ((point->cellLatIndex != INVALID_LATLON_INDEX) || (point->cellLonIndex != INVALID_LATLON_INDEX)) {
		return;
	}

	FIELD *field = point->fields, *nextField;
	while (field) {
		nextField = field->next;
		field->next = NULL;
		mem_free(field);
		field = nextField;
	}
	mem_free(point);
}
