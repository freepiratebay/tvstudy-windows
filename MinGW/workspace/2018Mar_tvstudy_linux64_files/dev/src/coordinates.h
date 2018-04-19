//
//  coordinates.h
//  TVStudy
//
//  Copyright (c) 2016 Hammett & Edison, Inc.  All rights reserved.


#define CNV_N27N83  1   // Convert NAD27 to NAD83/WGS84
#define CNV_N83N27  2   // Convert NAD83/WGS84 to NAD27
#define CNV_W72N83  3   // Convert WGS72 to WGS84
#define CNV_N83W72  4   // Convert WGS84 to WGS72

#define CNV_EAREA  1   // Errors.  Coordinates outside conversion area.
#define CNV_EFILE  2   // File I/O error for NADCON algorithm.
#define CNV_ECONV  3   // Failure to converge during iteration.

typedef struct {         // A bounding box structure using integer coordinates of varying units.
	int southLatIndex;
	int eastLonIndex;
	int northLatIndex;
	int westLonIndex;
} INDEX_BOUNDS;

#define INVALID_LATLON_INDEX  1e8   // Value larger (or smaller if negated) than any valid lat or lon index.

int convert_coords(double xlat, double xlon, int jflg, double *ylat, double *ylon);
void bear_distance(double lat1, double lon1, double lat2, double lon2, double *bear, double *rbear, double *dist,
	double kmPerDegree);
void coordinates(double lat1, double lon1, double bear, double dist, double *lat2, double *lon2, double kmPerDegree);
char *latlon_string(double latlon, int isLon);
void initialize_bounds(INDEX_BOUNDS *bounds);
void extend_bounds_index(INDEX_BOUNDS *bounds, int latIndex, int lonIndex);
void extend_bounds_latlon(INDEX_BOUNDS *bounds, double lat, double lon);
void extend_bounds_bounds(INDEX_BOUNDS *bounds, INDEX_BOUNDS *box);
void extend_bounds_radius(INDEX_BOUNDS *bounds, double lat, double lon, double radius, double kmPerDegree);
int inside_bounds_index(INDEX_BOUNDS *bounds, int latIndex, int lonIndex);
int inside_bounds_latlon(INDEX_BOUNDS *bounds, double lat, double lon);
int inside_bounds_bounds(INDEX_BOUNDS *bounds, INDEX_BOUNDS *box);
int overlaps_bounds(INDEX_BOUNDS *bounds, INDEX_BOUNDS *box);
