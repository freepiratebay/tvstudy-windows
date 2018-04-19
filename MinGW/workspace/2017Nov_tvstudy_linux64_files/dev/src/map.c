//
//  map.c
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.


// Functions related to geographic data manipulation and map data output.


#include "tvstudy.h"


//---------------------------------------------------------------------------------------------------------------------

typedef struct ply {    // Data for an area polygon.
	GEOPOINTS *pts;     // Latitude and longitude data.
	short *isBorder;    // Identify points used for border-distance check.
	struct ply *next;   // For list.
	int countryKey;     // Country key for this area.
} COUNTRY_POLY;

static int NoData = 0;
static COUNTRY_POLY *PolyHead = NULL;

// Rendering parameters, maximum straight-line segment length, and maximum deviation of straight segment from curve,
// in kilometers at target map scale.  Constants represent 0.5 inches and 0.002 inches respectively.

static double Dres = 1.27e-5 * RENDER_MAP_SCALE;
static double Flat = 5.08e-8 * RENDER_MAP_SCALE;

static int load_polys();
static MAPFILE *do_open_mapfile(int fileFormat, char *baseName, int sumFile, int shapeType, int nAttr,
	SHAPEATTR *attrs, char *infoName, int folderAttrIndex);
static void write_short_el(short val, FILE *out);
static void write_int_el(int val, FILE *out);
static void write_double_el(double val, FILE *out);
static void write_int_eb(int val, FILE *out);
static void simul(double *ae, double *ce, double *a, double *b, double *c, double *d);
static void curv(double a, double b, double c, double d, double x, double *f0, double *fp, double *fr);


//---------------------------------------------------------------------------------------------------------------------
// Check a set of coordinates to determine country.  This reads a set of bounding polygons from the root database which
// define the known country areas.  May return 0 if the test point does not fall in any known area or if lookup fails;
// will return <0 on a serious error.

// Regarding over-range longitude values (west longitude >180 degrees or east longitude <-180 degrees), no correction
// is made here.  The individual boundaries in the database must always be continuous for the polygon test to succeed,
// so the boundary data itself may contain over-range longitude values.  If a region is near (or spans) the 180-degree
// line and may need to be tested against over-range points on either side, the region boundary must be duplicated in
// the database, one version having west longitudes with points >180 degrees as needed and the other having east
// longitudes with points <-180 degrees as needed.

// Arguments:

//   lat, lon  Point coordinates, degrees, positive north and west.

// Return is <0 for an error, 0 if no country could be found, else CNTRY_USA, CNTRY_CAN, or CNTRY_MEX (all >0).

int find_country(double lat, double lon) {

	if (NoData) return 0;
	if (!PolyHead) {
		int err = load_polys();
		if (err) return err;
		if (NoData) return 0;
	}

	COUNTRY_POLY *poly;
	for (poly = PolyHead; poly; poly = poly->next) {
		if (inside_poly(lat, lon, poly->pts)) {
			return poly->countryKey;
		}
	}

	// If the point is not in any polygon, default to U.S.  As of this writing only non-U.S. polygons are present in
	// the database, so U.S. is implied by no match to any other country.

	return CNTRY_USA;
}


//---------------------------------------------------------------------------------------------------------------------
// Determine shortest distances to country borders.

// Arguments:

//   lat, lon  Point coordinates, degrees, positive north and west.
//   dist      Array of MAX_COUNTRY size, will be set to distances by country.  Distance is 0 if no data is found.

// Return is <0 for an error.

int get_border_distances(double lat, double lon, double *borderDist) {

	int countryIndex;
	for (countryIndex = 0; countryIndex < MAX_COUNTRY; countryIndex++) {
		borderDist[countryIndex] = 0.;
	}

	if (NoData) return 0;
	if (!PolyHead) {
		int err = load_polys();
		if (err) return err;
		if (NoData) return 0;
	}

	COUNTRY_POLY *poly;
	GEOPOINTS *pts;
	double dist;
	int i;

	for (countryIndex = 0; countryIndex < MAX_COUNTRY; countryIndex++) {
		borderDist[countryIndex] = 99999.;
	}

	for (poly = PolyHead; poly; poly = poly->next) {
		countryIndex = poly->countryKey - 1;
		pts = poly->pts;
		for (i = 0; i < pts->nPts; i++) {
			if (poly->isBorder[i]) {
				bear_distance(lat, lon, pts->ptLat[i], pts->ptLon[i], NULL, NULL, &dist, Params.KilometersPerDegree);
				if (dist < borderDist[countryIndex]) {
					borderDist[countryIndex] = dist;
				}
			}
		}
	}

	for (countryIndex = 0; countryIndex < MAX_COUNTRY; countryIndex++) {
		if (99999. == borderDist[countryIndex]) {
			borderDist[countryIndex] = 0.;
		}
	}

	// Unless there actually was data tagged U.S. in the database (usually isn't because it would be redundant for the
	// continental case), the U.S. distance is set to the smaller of all others.

	if (0. == borderDist[CNTRY_USA - 1]) {
		dist = 99999.;
		for (countryIndex = 0; countryIndex < MAX_COUNTRY; countryIndex++) {
			if (CNTRY_USA != (countryIndex + 1)) {
				if (borderDist[countryIndex] < dist) {
					dist = borderDist[countryIndex];
				}
			}
		}
		borderDist[CNTRY_USA - 1] = dist;
	}

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Load polygons from database.

static int load_polys() {

	if (!MyConnection) {
		log_error("load_polys() called with no open database connection");
		return 1;
	}

	char query[MAX_QUERY];
	MYSQL_RES *myResult;
	my_ulonglong rowCount, rowIndex;
	MYSQL_ROW fields;

	snprintf(query, MAX_QUERY, "SELECT country_key, poly_seq, latitude, longitude, is_border FROM %s.country_poly ORDER BY poly_seq, vertex_seq;", DbName);
	if (mysql_query(MyConnection, query)) {
		log_db_error("Country border query failed (1)");
		return -1;
	}

	myResult = mysql_store_result(MyConnection);
	if (!myResult) {
		log_db_error("Country border query failed (2)");
		return -1;
	}

	rowCount = mysql_num_rows(myResult);
	if (!rowCount) {
		mysql_free_result(myResult);
		NoData = 1;
		return 0;
	}

	double *latbuf = NULL, *lonbuf = NULL;
	short *bordbuf = NULL;
	int maxbuf = 0, countryKey = 0, polyseq = -1, lastpolyseq = -1, np = 0, i;
	COUNTRY_POLY *poly;
	COUNTRY_POLY **polyLink = &PolyHead;
	GEOPOINTS *pts;

	for (rowIndex = 0; rowIndex <= rowCount; rowIndex++) {

		if (rowIndex < rowCount) {

			fields = mysql_fetch_row(myResult);
			if (!fields) {
				mysql_free_result(myResult);
				log_db_error("Country border query failed (3)");
				return -1;
			}

			polyseq = atoi(fields[1]);

		} else {

			polyseq = -1;
		}

		if (polyseq != lastpolyseq) {

			if (np) {

				poly = (COUNTRY_POLY *)mem_alloc(sizeof(COUNTRY_POLY));
				pts = geopoints_alloc(np);
				poly->pts = pts;
				pts->nPts = np;
				poly->isBorder = (short *)mem_alloc(np * sizeof(short));
				poly->countryKey = countryKey;
				poly->next = NULL;

				pts->xlts = 999.;
				pts->xlne = 999.;
				pts->xltn = -999.;
				pts->xlnw = -999.;

				for (i = 0; i < np; i++) {
					if (latbuf[i] < pts->xlts) {
						pts->xlts = latbuf[i];
					}
					if (latbuf[i] > pts->xltn) {
						pts->xltn = latbuf[i];
					}
					if (lonbuf[i] < pts->xlne) {
						pts->xlne = lonbuf[i];
					}
					if (lonbuf[i] > pts->xlnw) {
						pts->xlnw = lonbuf[i];
					}
					pts->ptLat[i] = latbuf[i];
					pts->ptLon[i] = lonbuf[i];
					poly->isBorder[i] = bordbuf[i];
				}

				*polyLink = poly;
				polyLink = &(poly->next);

				np = 0;
			}

			lastpolyseq = polyseq;
		}

		if (rowIndex < rowCount) {

			if (!np) {
				countryKey = atoi(fields[0]);
			}

			if (np == maxbuf) {
				maxbuf += 10000;
				latbuf = (double *)mem_realloc(latbuf, (maxbuf * sizeof(double)));
				lonbuf = (double *)mem_realloc(lonbuf, (maxbuf * sizeof(double)));
				bordbuf = (short *)mem_realloc(bordbuf, (maxbuf * sizeof(short)));
			}

			latbuf[np] = atof(fields[2]);
			lonbuf[np] = atof(fields[3]);
			bordbuf[np++] = atoi(fields[4]);
		}
	}

	mysql_free_result(myResult);

	mem_free(latbuf);
	mem_free(lonbuf);
	mem_free(bordbuf);

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// API for creating output of geographic data in either ESRI shapefile or KML format.  First function is to open a new
// file, or in the case of shapefile format, a set of related files with a common base name.  Originally this only
// supported shapefiles so the API is structured around that format.

// Arguments:

//  fileFormat       File format, MAP_FILE_*
//  baseName         Base name for the file(s), extensions are added.  Must not include any directory path!
//  shapeType        Shape type in the file, SHP_TYPE_*.
//  nAttr            Number of shape attributes (database fields).
//  attrs            Array of attribute descriptions.
//  infoName         Descriptive name, only used for KML in the <NAME> element.  If NULL, baseName is used.
//  folderAttrIndex  For KML output, if >= 0 sort feature output into folders using this attribute value.

// Return is a MAPFILE structure, or NULL on error.

MAPFILE *open_mapfile(int fileFormat, char *baseName, int shapeType, int nAttr, SHAPEATTR *attrs, char *infoName,
		int folderAttrIndex) {
	return do_open_mapfile(fileFormat, baseName, 0, shapeType, nAttr, attrs, infoName, folderAttrIndex);
}

MAPFILE *open_sum_mapfile(int fileFormat, char *baseName, int shapeType, int nAttr, SHAPEATTR *attrs, char *infoName,
		int folderAttrIndex) {
	return do_open_mapfile(fileFormat, baseName, 1, shapeType, nAttr, attrs, infoName, folderAttrIndex);
}

static MAPFILE *do_open_mapfile(int fileFormat, char *baseName, int sumFile, int shapeType, int nAttr,
		SHAPEATTR *attrs, char *infoName, int folderAttrIndex) {

	static int nameLen = 0;
	static char *fileName = NULL;

	int baseLen = strlen(baseName) + 1;
	if ((baseLen + 10) > nameLen) {
		nameLen = baseLen + 10;
		fileName = (char *)mem_realloc(fileName, nameLen);
	}

	// Argument sanity checks.

	if ((MAP_FILE_SHAPE != fileFormat) && (MAP_FILE_KML != fileFormat)) {
		log_error("In open_mapfile: Unknown file format");
		return NULL;
	}

	if ((SHP_TYPE_NULL != shapeType) && (SHP_TYPE_POINT != shapeType) &&
			(SHP_TYPE_POLYLINE != shapeType) && (SHP_TYPE_POLYGON != shapeType)) {
		log_error("In open_mapfile: Unsupported shape type");
		return NULL;
	}

	if (nAttr < 1) {
		log_error("In open_mapfile: Bad attribute count");
		return NULL;
	}

	// Allocate and initialize the map file structure.

	MAPFILE *map = (MAPFILE *)mem_zalloc(sizeof(MAPFILE));
	map->fileFormat = fileFormat;
	map->baseName = mem_alloc(baseLen);
	lcpystr(map->baseName, baseName, baseLen);
	map->shapeType = shapeType;
	map->numAttr = nAttr;
	map->folderAttrIndex = -1;

	// Setup for shapefile format, error check attribute lengths.

	if (MAP_FILE_SHAPE == fileFormat) {

		map->attrLen = (int *)mem_zalloc(nAttr * sizeof(int));
		map->totalSize = 100;

		int i, j, max, reclen = 0;

		for (i = 0; i < nAttr; i++) {

			switch (attrs[i].type) {

				default: {
					close_mapfile(map);
					log_error("In open_mapfile: Unknown attribute type");
					return NULL;
					break;
				}

				case SHP_ATTR_CHAR: {
					max = 254;
					map->attrLen[i] = attrs[i].length;
					break;
				}

				case SHP_ATTR_NUM: {
					max = 18;
					map->attrLen[i] = -attrs[i].length;
					break;
				}

				case SHP_ATTR_BOOL: {
					max = 1;
					map->attrLen[i] = 1;
					break;
				}
			}

			if ((attrs[i].length < 1) || (attrs[i].length > max)) {
				close_mapfile(map);
				log_error("In open_mapfile: Bad attribute length");
				return NULL;
			}

			if (attrs[i].precision > (attrs[i].length - 1)) {
				close_mapfile(map);
				log_error("In open_mapfile: Bad attribute precision");
				return NULL;
			}

			reclen += attrs[i].length;
		}

		// Create the coordinate system definition file, this is currently hard-coded for NAD83.

		snprintf(fileName, nameLen, "%s.prj", baseName);
		FILE *prj;
		if (sumFile) {
			prj = open_sum_file(fileName);
		} else {
			prj = open_file(fileName);
		}
		if (!prj) {
			close_mapfile(map);
			return NULL;
		}
		fputs("GEOGCS[\"GCS_North_American_1983\",DATUM[\"D_North_American_1983\",SPHEROID[\"GRS_1980\",6378137,298.257222101]],PRIMEM[\"Greenwich\",0],UNIT[\"Degree\",0.017453292519943295]]", prj);
		fclose(prj);

		// Open the shape, index, and DBF files.

		snprintf(fileName, nameLen, "%s.shp", baseName);
		if (sumFile) {
			map->mapFile = open_sum_file(fileName);
		} else {
			map->mapFile = open_file(fileName);
		}
		if (!(map->mapFile)) {
			close_mapfile(map);
			return NULL;
		}

		snprintf(fileName, nameLen, "%s.shx", baseName);
		if (sumFile) {
			map->indexFile = open_sum_file(fileName);
		} else {
			map->indexFile = open_file(fileName);
		}
		if (!(map->indexFile)) {
			close_mapfile(map);
			return NULL;
		}

		snprintf(fileName, nameLen, "%s.dbf", baseName);
		if (sumFile) {
			map->dbFile = open_sum_file(fileName);
		} else {
			map->dbFile = open_file(fileName);
		}
		if (!(map->dbFile)) {
			close_mapfile(map);
			return NULL;
		}

		// Write empty headers to the shape and index files, real data will be over-written on close.

		for (i = 0; i < 25; i++) {
			write_int_el(0, map->mapFile);
			write_int_el(0, map->indexFile);
		}

		// Write header to the DBF file.  This is not documented by ESRI and there is some difference of opinion about
		// the format in other documentation sources; but the following seems to work.  The record count will be
		// written back to the header on close.

		time_t theTime;
		time(&theTime);
		struct tm *tim = localtime(&theTime);

		fputc(3, map->dbFile);
		fputc(tim->tm_mday, map->dbFile);
		fputc((tim->tm_mon + 1), map->dbFile);
		fputc(tim->tm_year, map->dbFile);

		write_int_el(0, map->dbFile);

		short s = 33 + (nAttr * 32);
		write_short_el(s, map->dbFile);

		s = reclen + 1;
		write_short_el(s, map->dbFile);

		for (i = 0; i < 5; i++) {
			write_int_el(0, map->dbFile);
		}

		char tmp[11];

		for (i = 0; i < nAttr; i++) {

			memset(tmp, 0, 11);
			lcpystr(tmp, attrs[i].name, 11);
			fwrite(tmp, 1, 11, map->dbFile);

			fputc(attrs[i].type, map->dbFile);

			write_int_el(0, map->dbFile);

			fputc(attrs[i].length, map->dbFile);
			fputc(attrs[i].precision, map->dbFile);

			fputc(0, map->dbFile);
			fputc(0, map->dbFile);

			fputc(1, map->dbFile);

			for (j = 0; j < 11; j++) {
				fputc(0, map->dbFile);
			}
		}

		fputc('\r', map->dbFile);

	// For KML format, open the file, write the schema header.

	} else {

		snprintf(fileName, nameLen, "%s.kml", baseName);
		if (sumFile) {
			map->mapFile = open_sum_file(fileName);
		} else {
			map->mapFile = open_file(fileName);
		}
		if (!(map->mapFile)) {
			close_mapfile(map);
			return NULL;
		}

		fputs("<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n", map->mapFile);
		fputs("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n", map->mapFile);
		if (!infoName) {
			infoName = baseName;
		}

		fprintf(map->mapFile, "<Document>\n<Schema name=\"%s\" id=\"%s\">\n", baseName, baseName);

		map->attrName = (char **)mem_zalloc(nAttr * sizeof(char *));

		int i, attrNameLen;
		char *fieldType;

		for (i = 0; i < nAttr; i++) {

			attrNameLen = strlen(attrs[i].name) + 1;
			map->attrName[i] = (char *)mem_alloc(attrNameLen);
			lcpystr(map->attrName[i], attrs[i].name, attrNameLen);

			switch (attrs[i].type) {

				case SHP_ATTR_CHAR:
				default: {
					fieldType = "string";
					break;
				}

				case SHP_ATTR_NUM: {
					if (0 == attrs[i].precision) {
						fieldType = "int";
					} else {
						fieldType = "double";
					}
					break;
				}

				case SHP_ATTR_BOOL: {
					fieldType = "bool";
					break;
				}
			}

			fprintf(map->mapFile, "<SimpleField name=\"%s\" type=\"%s\"></SimpleField>\n", attrs[i].name, fieldType);
		}

		fputs("</Schema>\n", map->mapFile);

		fputs("<Style id=\"styles\">\n", map->mapFile);
		fputs("<IconStyle><color>ffff0000</color><scale>0.5</scale></IconStyle>\n", map->mapFile);
		fputs("<LineStyle><color>ff00ff00</color><width>3</width></LineStyle>\n", map->mapFile);
		fputs("<PolyStyle><outline>1</outline><fill>0</fill></PolyStyle>\n", map->mapFile);
		fputs("</Style>\n", map->mapFile);

		fprintf(map->mapFile, "<Folder>\n<name>%s</name>\n", infoName);

		// Output can be sorted into subfolders using the value of a specified attribute.  The folders are created on
		// the fly in write_shape(), content for each goes to a temporary file then all of those are copied into the
		// main output file in close_mapfile().  This is kind of a hack, but it's easier that restructuring a lot of
		// code that writes output on-the-fly and so generates the features in interleaved order.

		if ((folderAttrIndex >= 0) && (folderAttrIndex < nAttr)) {
			map->folderAttrIndex = folderAttrIndex;
		}
	}

	// All done.

	return map;
}


//---------------------------------------------------------------------------------------------------------------------
// Write a shape to a map file.

// Arguments:

//   map            The map file.
//   ptLat, ptLon   Shape coordinates for points, otherwise not used.
//   pts            Shape data points for poly-line or polygon, otherwise NULL.
//   nParts         Number of parts in a poly-line or polygon shape.
//   iParts         Array of starting index values for parts.  Ignored and may be NULL if nParts is 1.
//   attrData       List of attribute values (always as strings, numbers must be formatted by caller).
//                    The list must match the count and lengths specified when the file was opened.

// Return is 0 for success, -1 for any error.

int write_shape(MAPFILE *map, double ptLat, double ptLon, GEOPOINTS *pts, int nParts, int *iParts, char **attrData) {

	// Sanity checks on arguments.

	int i, j, j0, j1;

	if ((SHP_TYPE_POLYLINE == map->shapeType) || (SHP_TYPE_POLYGON == map->shapeType)) {

		if (!pts) {
			log_error("In write_shape: Null points argument");
			return -1;
		}

		int minpts;
		if (SHP_TYPE_POLYLINE == map->shapeType) {
			minpts = 2;
		} else {
			minpts = 4;
		}
		if (pts->nPts < minpts) {
			log_error("In write_shape: Bad point count");
			return -1;
		}

		if (nParts < 1) {
			log_error("In write_shape: Bad part count");
			return -1;
    	}

		if (1 == nParts) {

			if (SHP_TYPE_POLYGON == map->shapeType) {
				j1 = pts->nPts - 1;
				if ((pts->ptLat[0] != pts->ptLat[j1]) || (pts->ptLon[0] != pts->ptLon[j1])) {
					log_error("In write_shape: Polygon not closed");
					return -1;
				}
			}

		} else {

			for (i = 0; i < nParts; i++) {

				j0 = iParts[i];
				if (i < (nParts - 1)) {
					j1 = iParts[i + 1] - 1;
				} else {
					j1 = pts->nPts - 1;
				}

				if (((j1 - j0) + 1) < minpts) {
					log_error("In write_shape: Bad part point count");
					return -1;
				}

				if (SHP_TYPE_POLYGON == map->shapeType) {
					if ((pts->ptLat[j0] != pts->ptLat[j1]) || (pts->ptLon[j0] != pts->ptLon[j1])) {
						log_error("In write_shape: Polygon part not closed");
						return -1;
					}
				}
			}
		}
	}

	// Shapefile format output, first determine bounding box.  This is done even for points because it must update the
	// bounds for the whole file.

	if (MAP_FILE_SHAPE == map->fileFormat) {

		double xmin, ymin, xmax, ymax, x, y;

		switch (map->shapeType) {

			case SHP_TYPE_NULL:
			default: {
				xmin = 0.;
				ymin = 0.;
				xmax = 0.;
				ymax = 0.;
				break;
			}

			case SHP_TYPE_POINT: {
				xmin = -ptLon;
				ymin = ptLat;
				xmax = -ptLon;
				ymax = ptLat;
				break;
			}

			case SHP_TYPE_POLYLINE:
			case SHP_TYPE_POLYGON: {
				xmin = -pts->ptLon[0];
				ymin = pts->ptLat[0];
				xmax = -pts->ptLon[0];
				ymax = pts->ptLat[0];
				for (i = 1; i < pts->nPts; i++) {
					x = -pts->ptLon[i];
					y = pts->ptLat[i];
					if (x < xmin) {
						xmin = x;
					}
					if (y < ymin) {
						ymin = y;
					}
					if (x > xmax) {
						xmax = x;
					}
					if (y > ymax) {
						ymax = y;
					}
				}
				break;
			}
		}

		if (0 == map->recordNum) {
			map->xmin = xmin;
			map->ymin = ymin;
			map->xmax = xmax;
			map->ymax = ymax;
		} else {
			if (xmin < map->xmin) {
				map->xmin = xmin;
			}
			if (ymin < map->ymin) {
				map->ymin = ymin;
			}
			if (xmax > map->xmax) {
				map->xmax = xmax;
			}
			if (ymax > map->ymax) {
				map->ymax = ymax;
			}
		}

		// Write the shape and index data.

		int siz;

		switch (map->shapeType) {

			case SHP_TYPE_NULL:
			default: {
				siz = 4;
				break;
			}

			case SHP_TYPE_POINT: {
				siz = 20;
				break;
			}

			case SHP_TYPE_POLYLINE:
			case SHP_TYPE_POLYGON: {
				siz = 44 + (nParts * 4) + (pts->nPts * 16);
				break;
			}
		}

		map->totalSize += siz + 8;

		map->recordNum++;

		write_int_eb(((int)ftell(map->mapFile) / 2), map->indexFile);
		write_int_eb((siz / 2), map->indexFile);

		write_int_eb(map->recordNum, map->mapFile);
		write_int_eb((siz / 2), map->mapFile);

		write_int_el(map->shapeType, map->mapFile);

		switch (map->shapeType) {

			case SHP_TYPE_NULL:
			default: {
				break;
			}

			case SHP_TYPE_POINT: {
				x = -ptLon;
				y = ptLat;
				write_double_el(x, map->mapFile);
				write_double_el(y, map->mapFile);
				break;
			}

			case SHP_TYPE_POLYLINE:
			case SHP_TYPE_POLYGON: {

				write_double_el(xmin, map->mapFile);
				write_double_el(ymin, map->mapFile);
				write_double_el(xmax, map->mapFile);
				write_double_el(ymax, map->mapFile);

				write_int_el(nParts, map->mapFile);
				write_int_el(pts->nPts, map->mapFile);
				if (1 == nParts) {
					write_int_el(0, map->mapFile);
				} else {
					for (i = 0; i < nParts; i++) {
   						write_int_el(iParts[i], map->mapFile);
					}
				}

				for (i = 0; i < pts->nPts; i++) {
					x = -pts->ptLon[i];
					y = pts->ptLat[i];
					write_double_el(x, map->mapFile);
					write_double_el(y, map->mapFile);
				}

				break;
			}
		}

		// Write attributes.

		fputc(' ', map->dbFile);

		int len, flen, padleft, padright;

		for (i = 0; i < map->numAttr; i++) {

			len = strlen(attrData[i]);
			flen = map->attrLen[i];

			if (flen < 0) {
				flen = -flen;
				padright = 0;
				if (len > flen) {
					len = flen;
					padleft = 0;
				} else {
					padleft = flen - len;
				}
			} else {
				padleft = 0;
				if (len > flen) {
					len = flen;
					padright = 0;
				} else {
					padright = flen - len;
				}
			}

			for (j = 0; j < padleft; j++) {
				fputc(' ', map->dbFile);
			}
			fwrite(attrData[i], 1, len, map->dbFile);
			for (j = 0; j < padright; j++) {
				fputc(' ', map->dbFile);
			}
		}

	// KML format.  Check if output is being sorted into folders, if so search for an existing folder, create one as
	// needed.  Output for each folder goes to a temporary file.  If the sorting attribute is an empty string, or a
	// string that is too long for the folder ID field, or anything else goes wrong setting up the folder, the output
	// goes directly to the main file.  Creating the folder temporary file will only be attempted once, to avoid
	// churning the filesystem if there are issues like a full temporary file partition.

	} else {

		FILE *outFile = map->mapFile;

		if (map->folderAttrIndex >= 0) {

			char *id = attrData[map->folderAttrIndex];
			int len = strlen(id);
			if ((len > 0) && (len < MAP_FOLDER_ID_LEN)) {

				MAP_FOLDER *folder = map->folders, **folderLink = &(map->folders);

				while (folder) {
					if (0 == strcmp(folder->id, id)) {
						break;
					}
					folderLink = &(folder->next);
					folder = folder->next;
				}

				if (!folder) {

					folder = (MAP_FOLDER *)mem_zalloc(sizeof(MAP_FOLDER));
					*folderLink = folder;

					lcpystr(folder->id, id, MAP_FOLDER_ID_LEN);
					lcpystr(folder->tempFileName, "/tmp/tvstudykmlXXXXXXXX", MAX_STRING);

					int fd = mkstemp(folder->tempFileName);
					if (fd >= 0) {
						folder->tempFile = fdopen(fd, "w");
					}
				}

				if (folder->tempFile) {
					outFile = folder->tempFile;
				}
			}
		}

		// Start place element, write attributes in extended data element.

		fprintf(outFile, "<Placemark>\n<ExtendedData><SchemaData schemeUrl=\"#%s\">\n", map->baseName);

		for (i = 0; i < map->numAttr; i++) {
			fprintf(outFile, "<SimpleData name=\"%s\">%s</SimpleData>\n", map->attrName[i], attrData[i]);
		}

		fputs("</SchemaData></ExtendedData>\n<styleUrl>#styles</styleUrl>\n", outFile);

		double x, y;

		switch (map->shapeType) {

			case SHP_TYPE_NULL:
			default: {
				break;
			}

			case SHP_TYPE_POINT: {
				x = -ptLon;
				y = ptLat;
				fprintf(outFile, "<Point><coordinates>\n%f,%f\n</coordinates></Point>\n", x, y);
				break;
			}

			case SHP_TYPE_POLYLINE:
			case SHP_TYPE_POLYGON: {

				if (1 == nParts) {

					fputs("<LineString><coordinates>\n", outFile);
					for (i = 0; i < pts->nPts; i++) {
						x = -pts->ptLon[i];
						y = pts->ptLat[i];
						fprintf(outFile, "%f,%f\n", x, y);
					}
					fputs("</coordinates></LineString>\n", outFile);

				} else {

					fputs("<MultiGeometry>\n", outFile);

					for (i = 0; i < nParts; i++) {

						j0 = iParts[i];
						if (i < (nParts - 1)) {
							j1 = iParts[i + 1] - 1;
						} else {
							j1 = pts->nPts - 1;
						}

						fputs("<LineString><coordinates>\n", outFile);
						for (j = j0; j <= j1; j++) {
							x = -pts->ptLon[j];
							y = pts->ptLat[j];
							fprintf(outFile, "%f,%f\n", x, y);
						}
						fputs("</coordinates></LineString>\n", outFile);
					}

					fputs("</MultiGeometry>\n", outFile);
				}

				break;
			}
		}

		fputs("</Placemark>\n", outFile);
	}

	// Done.

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Close a map file.  Do final output, write back headers, close all files, and free the structure memory.

// Arguments:

//   map  The map file to close.

void close_mapfile(MAPFILE *map) {

	int i;

	// Shapefile format, write headers to shape and index files.

	if (MAP_FILE_SHAPE == map->fileFormat) {

		if (map->mapFile && map->indexFile && map->dbFile) {

			fseek(map->mapFile, 0, SEEK_SET);
			fseek(map->indexFile, 0, SEEK_SET);

			write_int_eb(9994, map->mapFile);
			write_int_eb(9994, map->indexFile);

			for (i = 0; i < 5; i++) {
				write_int_eb(0, map->mapFile);
				write_int_eb(0, map->indexFile);
			}

			write_int_eb((map->totalSize / 2), map->mapFile);
			write_int_eb((50 + (4 * map->recordNum)), map->indexFile);

			write_int_el(1000, map->mapFile);
			write_int_el(1000, map->indexFile);

			write_int_el(map->shapeType, map->mapFile);
			write_int_el(map->shapeType, map->indexFile);

			write_double_el(map->xmin, map->mapFile);
			write_double_el(map->xmin, map->indexFile);
			write_double_el(map->ymin, map->mapFile);
			write_double_el(map->ymin, map->indexFile);
			write_double_el(map->xmax, map->mapFile);
			write_double_el(map->xmax, map->indexFile);
			write_double_el(map->ymax, map->mapFile);
			write_double_el(map->ymax, map->indexFile);

			for (i = 0; i < 4; i++) {
				write_double_el(0., map->mapFile);
				write_double_el(0., map->indexFile);
			}

			// EOF and record count to DBF file.

			fputc(0x1a, map->dbFile);

			fseek(map->dbFile, 4, SEEK_SET);
			write_int_el(map->recordNum, map->dbFile);
		}

		// Close files, free memory.

		if (map->mapFile) {
			fclose(map->mapFile);
			map->mapFile = NULL;
		}

		if (map->indexFile) {
			fclose(map->indexFile);
			map->indexFile = NULL;
		}

		if (map->dbFile) {
			fclose(map->dbFile);
			map->dbFile = NULL;
		}

		if (map->attrLen) {
			mem_free(map->attrLen);
			map->attrLen = NULL;
		}

	// KML format.  First check if output is being sorted into folders, if so close the folder temporary files, and
	// copy content from all into the main file.

	} else {

		if (map->mapFile) {

			if (map->folderAttrIndex >= 0) {

				MAP_FOLDER *folder;
				size_t nb;
#define BUFLEN 1048576
				unsigned char buf[BUFLEN];

				while (map->folders) {

					folder = map->folders;
					map->folders = folder->next;
					folder->next = NULL;

					if (folder->tempFile) {

						fclose(folder->tempFile);
						folder->tempFile = fopen(folder->tempFileName, "rb");

						if (folder->tempFile) {

							fprintf(map->mapFile, "<Folder>\n<name>%s</name>\n", folder->id);
							while ((nb = fread(buf, 1, BUFLEN, folder->tempFile))) {
								fwrite(buf, 1, nb, map->mapFile);
								hb_log();
							}
							fputs("</Folder>\n", map->mapFile);

							fclose(folder->tempFile);
						}

						unlink(folder->tempFileName);
					}

					mem_free(folder);
				}
			}

			// Close root elements, close files, free memory.

			fputs("</Folder>\n</Document>\n</kml>\n", map->mapFile);

			fclose(map->mapFile);
			map->mapFile = NULL;
		}

		if (map->attrName) {
			for (i = 0; i < map->numAttr; i++) {
				if (map->attrName[i]) {
					mem_free(map->attrName[i]);
					map->attrName[i] = NULL;
				}
			}
			mem_free(map->attrName);
			map->attrName = NULL;
		}
	}

	if (map->baseName) {
		mem_free(map->baseName);
		map->baseName = NULL;
	}

	mem_free(map);
}


//---------------------------------------------------------------------------------------------------------------------
// Functions for low-level writes to shape files, write 16- and 32-bit integers and 64-bit floating point in proper
// byte order, most writes are little-endian, but 32-bit integers need to be written in big-endian for some data.

static void write_short_el(short val, FILE *out) {

#ifdef __BIG_ENDIAN__
	union {
		short v;
		unsigned char c[2];
	} b;

	b.v = val;
	fputc(b.c[1], out);
	fputc(b.c[0], out);
#else
	fwrite(&val, 1, 2, out);
#endif
}


//---------------------------------------------------------------------------------------------------------------------

static void write_int_el(int val, FILE *out) {

#ifdef __BIG_ENDIAN__
	union {
		int v;
		unsigned char c[4];
	} b;

	b.v = val;
	fputc(b.c[3], out);
	fputc(b.c[2], out);
	fputc(b.c[1], out);
	fputc(b.c[0], out);
#else
	fwrite(&val, 1, 4, out);
#endif
}


//---------------------------------------------------------------------------------------------------------------------

static void write_double_el(double val, FILE *out) {

#ifdef __BIG_ENDIAN__
	union {
		double v;
		unsigned char c[8];
	} b;

	b.v = val;
	fputc(b.c[7], out);
	fputc(b.c[6], out);
	fputc(b.c[5], out);
	fputc(b.c[4], out);
	fputc(b.c[3], out);
	fputc(b.c[2], out);
	fputc(b.c[1], out);
	fputc(b.c[0], out);
#else
	fwrite(&val, 1, 8, out);
#endif
}


//---------------------------------------------------------------------------------------------------------------------

static void write_int_eb(int val, FILE *out) {

#ifdef __BIG_ENDIAN__
	fwrite(&val, 1, 4, out);
#else
	union {
		int v;
		unsigned char c[4];
	} b;

	b.v = val;
	fputc(b.c[3], out);
	fputc(b.c[2], out);
	fputc(b.c[1], out);
	fputc(b.c[0], out);
#endif
}


//---------------------------------------------------------------------------------------------------------------------
// Returned the rendered service area boundary for a source, either a geography or contour.  The geography must be
// loaded or the contour projected, otherwise this returns NULL.  If both contour and geography are present, the
// geography is preferred.

// Arguments:

//   source  The desired source.

// Return rendered boundary, or NULL if none available.  No errors.

GEOPOINTS *render_service_area(SOURCE *source) {

	if (source->geography) {
		return render_geography(source->geography);
	}

	if (source->contour) {
		return render_contour(source->contour);
	}

	return NULL;
}


//---------------------------------------------------------------------------------------------------------------------
// Render a contour to a series of geographic coordinates, optimized for a particular map scale.  This uses a curve-
// fit to the tabulated contour data rendered at incremental points with spacing adjusted dynamically for curvature.
// The rendered data is cached in the contour structure, if that already exists it is just returned.

// Arguments:

//   contour  The contour to render.

// The return is always the GEOPOINTS structure; no errors are possible.

GEOPOINTS *render_contour(CONTOUR *contour) {

	if (contour->points) {
		return contour->points;
	}

	// Set up for the main loop.

	GEOPOINTS *pts = geopoints_alloc(0);
	contour->points = pts;

	double lat = contour->latitude, lon = contour->longitude, *rd = contour->distance;
	int np = contour->count;

	double at[16], cd[4], ax0, ax1, ax2, r0, r1, r2, d1, d2, sln0, sln1, cx, az, azn, a, b, c, d, ds, ph, rc,
		dseg, dsegl, dsn, phn, rcn, azi, rcdel, lat1, lon1;
	int n, j, pass;

	at[0] = 1.;
	at[1] = 1.;
	at[2] = 0.;
	at[3] = 0.;
	at[6] = 1.;
	at[7] = 1.;

	sln1 = 0.;

	// Loop over the contour points.  First collect azimuth and distance at three sequential points.  On the first
	// loop no actual rendering will occur, just set up parameters for the first curve fit.

	double step = 360. / (double)np;

	for (n = -1; n < np; n++) {

		ax0 = (double)n * step;
		j = n;
		if (j < 0) {
			j += np;
		}
		r0 = rd[j];

		ax1 = ax0 + step;
		j = n + 1;
		if (j >= np) {
			j -= np;
		}
		r1 = rd[j];

		ax2 = ax1 + step;
		j = n + 2;
		if (j >= np) {
			j -= np;
		}
		r2 = rd[j];

		// Determine the desired slopes of the curve at each endpoint.  The first end comes from the previous loop,
		// compute the second.  On the first time through the loop, that's all.

		sln0 = sln1;

		d1 = (r1 - r0) / (ax1 - ax0);
		d2 = (r2 - r1) / (ax2 - ax1);
		if (((d1 >= 0.) && (d2 <= 0.)) || ((d1 <= 0.) && (d2 >= 0.))) {
			sln1 = 0.;
		} else {
			cx = cos(((d1 / (d1 + d2)) - 0.5) * PI);
			sln1 = cx * ((r2 - r0) / (ax2 - ax0));
		}

		if (n < 0) {
			continue;
		}

		// Set up the rest of the matrix and solve for the curve coefficients.

		at[4] = ax0;
		at[5] = ax1;
		at[8] = ax0 * ax0;
		at[9] = ax1 * ax1;
		at[10] = 2. * ax0;
		at[11] = 2. * ax1;
		at[12] = at[8] * ax0;
		at[13] = at[9] * ax1;
		at[14] = 3. * at[8];
		at[15] = 3. * at[9];
		cd[0] = r0;
		cd[1] = r1;
		cd[2] = sln0;
		cd[3] = sln1;

		simul(at, cd, &a, &b, &c, &d);

		// Evaluate the curve at the first point, then enter a loop to render incrementally.

		az = ax0;
		curv(a, b, c, d, az, &ds, &ph, &rc);

		do {

			coordinates(lat, lon, az, ds, &lat1, &lon1, Params.KilometersPerDegree);
			geopoints_addpoint(pts, lat1, lon1);

			// Determine the next point to render.  Set the target segment length, then estimate the point based on
			// that length assuming the curvature remains constant.

			if (rc > Flat) {
				dseg = 2. * sqrt(Flat * ((2. * rc) - Flat));
			} else {
				dseg = 2. * Flat;
			}
			if (dseg > Dres) {
				dseg = Dres;
			}
			azi = asin((dseg * sin(ph * DEGREES_TO_RADIANS)) /
				sqrt((ds * ds) + (dseg * dseg) - (2. * ds * dseg * cos(ph * DEGREES_TO_RADIANS)))) *
				RADIANS_TO_DEGREES;

			// Evaluate the curve at the estimated point, check the actual segment length and curvature at the point
			// (length within 20% of target, no more than 50% change in curvature), adjust and loop as needed.

			pass = 0;

			while (1) {

				azn = az + azi;
				if (azn > ax1) {
					azn = ax1;
					azi = azn - az;
				}
				curv(a, b, c, d, azn, &dsn, &phn, &rcn);

				dsegl = sqrt((ds * ds) + (dsn * dsn) - (2. * ds * dsn * cos(azi * DEGREES_TO_RADIANS)));
				if (rc > rcn) {
					rcdel = rc / rcn;
				} else {
					rcdel = rcn / rc;
				}

				// On the first pass, looking for reasons to make the segment longer.

				if (!pass) {
					if (((dsegl / dseg) < 0.8) && (rcdel <= 1.5) && (azn < ax1)) {
						azi *= 1.1;
						continue;
					}
					pass = 1;
				}

				// On the second pass, looking for reasons to make it shorter.

				if (((dsegl / dseg) > 1.2) || ((rcdel > 1.5) && (dsegl > (5. * Flat)))) {
					azi *= 0.9;
					continue;
				}

				break;
			}

			az = azn;
			ds = dsn;
			ph = phn;
			rc = rcn;

		} while (az < ax1);
	}

	// The contour must be an explicitly-closed polygon.

	geopoints_addpoint(pts, pts->ptLat[0], pts->ptLon[0]);

	return pts;
}


//---------------------------------------------------------------------------------------------------------------------
// Solve four simultaneous equations.

// Arguments:

//  ae          Equation coefficients.
//  ce          Solution column.
//  a, b, c, d  Return solution coefficients.

static void simul(double *ae, double *ce, double *a, double *b, double *c, double *d) {

	double aq[16], aa[4], bb[4], cc[4], dd[4], a1[4], a2[4], b1[4], b2[4], c1[4], a3, a4;
	int i;

	for (i = 0; i < 16; i++) {
		if (fabs(ae[i]) < TINY) {
			if (ae[i] < 0.) {
				ae[i] = -TINY;
			} else {
				ae[i] = TINY;
			}
		}
	}
	for (i = 0; i < 4; i++) {
		if (fabs(ce[i]) < TINY) {
			if (ce[i] < 0.) {
				ce[i] = -TINY;
			} else {
				ce[i] = TINY;
			}
		}
	}

	for (i = 0; i < 4; i++) {
		aq[i * 4] = ae[i];
		aq[(i * 4) + 1] = ae[i + 4];
		aq[(i * 4) + 2] = ae[i + 8];
		aq[(i * 4) + 3] = ae[i + 12];
	}
	for (i = 0; i < 3; i++) {
		aa[i] = aq[i + 1] / aq[0];
		bb[i] = aq[i + 5] / aq[4];
		cc[i] = aq[i + 9] / aq[8];
		dd[i] = aq[i + 13] / aq[12];
	}
	aa[3] = ce[0] / aq[0];
	bb[3] = ce[1] / aq[4];
	cc[3] = ce[2] / aq[8];
	dd[3] = ce[3] / aq[12];
	for (i = 0; i < 4; i++) {
		a1[i] = bb[i] - aa[i];
		b1[i] = cc[i] - aa[i];
		c1[i] = dd[i] - aa[i];
	}
	for (i = 0; i < 2; i++) {
		aa[i] = a1[i + 1] / a1[0];
		bb[i] = b1[i + 1] / b1[0];
		cc[i] = c1[i + 1] / c1[0];
	}
	aa[2] = a1[3] / a1[0];
	bb[2] = b1[3] / b1[0];
	cc[2] = c1[3] / c1[0];
	for (i = 0; i < 3; i++) {
		a2[i] = bb[i] - aa[i];
		b2[i] = cc[i] - aa[i];
	}
	aa[0] = a2[1] / a2[0];
	aa[1] = a2[2] / a2[0];
	bb[0] = b2[1] / b2[0];
	bb[1] = b2[2] / b2[0];
	a3 = bb[0] - aa[0];
	a4 = bb[1] - aa[1];

	*d = a4 / a3;
	*c = (a2[2] - (*d * a2[1])) / a2[0];
	*b = (a1[3] - (*c * a1[1]) - (*d * a1[2])) / a1[0];
	*a = (ce[0] - (*b * aq[1]) - (*c * aq[2]) - (*d * aq[3])) / aq[0];
}


//---------------------------------------------------------------------------------------------------------------------
// Evaluate polynomial curve in polar coordinates.

// Arguments:

//   a, b, c, d  Coefficients.
//   x           Argument.
//   f0          Return curve value.
//   fp          Return angle between radius vector and tangent line.
//   fr          Return radius of curvature.

static void curv(double a, double b, double c, double d, double x, double *f0, double *fp, double *fr) {

	double f0l, f1, f2, fa, fb, fpl;

	f0l = a + (b * x) + (c * x * x) + (d * x * x * x);
	*f0 = f0l;

	f1 = (b + (2. * c * x) + (3. * d * x * x)) * RADIANS_TO_DEGREES;
	f2 = ((2. * c) + (6. * d * x)) * (RADIANS_TO_DEGREES * RADIANS_TO_DEGREES);

	if (fabs(f1) > 1.e-10) {
		fpl = atan(f0l / f1) * RADIANS_TO_DEGREES;
		if (fpl >= 0.) {
			fpl = 180. - fpl;
		} else {
			fpl = fabs(fpl);
		}
	} else {
		fpl = 90.;
	}
	*fp = fpl;

	fa = pow(((f0l * f0l) + (f1 * f1)), 1.5);
	fb = fabs((f0l * f0l) + (2. * (f1 * f1)) - (f0l * f2));
	if (fb < 1.e-10) {
		fb = 1.e-10;
	}
	*fr = fa / fb;
}


//---------------------------------------------------------------------------------------------------------------------
// Load a geography by key.  The geographies are cached here.

// Arguments:

//   geoKey  The geography key.

// Return the GEOGRAPHY structure, NULL on error or not found.

GEOGRAPHY *get_geography(int geoKey) {

	if (!StudyKey) {
		log_error("get_geography() called with no study open");
		return NULL;
	}

	static GEOGRAPHY *cacheHead = NULL;
	static int initForStudyKey = 0;

	GEOGRAPHY *geo;

	if (StudyKey != initForStudyKey) {

		 while (cacheHead) {
			geo = cacheHead;
			cacheHead = geo->next;
			geo->next = NULL;
			geography_free(geo);
		}

		initForStudyKey = StudyKey;

	} else {

		for (geo = cacheHead; geo; geo = geo->next) {
			if (geoKey == geo->geoKey) {
				return geo;
			}
		}
	}

	// Query for basic geography record.

	char query[MAX_QUERY];
	MYSQL_RES *myResult;
	MYSQL_ROW fields;

	int type, i;
	double lat, lon;

	snprintf(query, MAX_QUERY,
			"SELECT geo_type, latitude, longitude, radius, width, height FROM %s.geography WHERE geo_key = %d;",
			DbName, geoKey);
	if (mysql_query(MyConnection, query)) {
		log_db_error("Geography query failed (1)");
		return NULL;
	}

	myResult = mysql_store_result(MyConnection);
	if (!myResult) {
		log_db_error("Geography query failed (2)");
		return NULL;
	}

	if (!mysql_num_rows(myResult)) {
		mysql_free_result(myResult);
		log_error("Geography not found for geoKey=%d", geoKey);
		return NULL;
	}

	fields = mysql_fetch_row(myResult);
	if (!fields) {
		mysql_free_result(myResult);
		log_db_error("Geography query failed (3)");
		return NULL;
	}

	type = atoi(fields[0]);
	lat = atof(fields[1]);
	lon = atof(fields[2]);

	// Circle and box are fully defined by the first query.  For sectors and polygon, query data from secondary table.

	switch (type) {

		case GEO_TYPE_CIRCLE: {

			geo = geography_alloc(geoKey, type, lat, lon, 0);
			geo->a.radius = atof(fields[3]);

			mysql_free_result(myResult);

			break;
		}

		case GEO_TYPE_BOX: {

			geo = geography_alloc(geoKey, type, lat, lon, 0);
			geo->a.width = atof(fields[4]);
			geo->b.height = atof(fields[5]);

			mysql_free_result(myResult);

			break;
		}

		case GEO_TYPE_SECTORS: {

			mysql_free_result(myResult);

			snprintf(query, MAX_QUERY,
					"SELECT azimuth, radius FROM %s.geo_sectors WHERE geo_key = %d ORDER BY azimuth;",
					DbName, geoKey);
			if (mysql_query(MyConnection, query)) {
				log_db_error("Sectors geography query failed (1) for geoKey=%d", geoKey);
				return NULL;
			}

			myResult = mysql_store_result(MyConnection);
			if (!myResult) {
				log_db_error("Sectors geography query failed (2) for geoKey=%d", geoKey);
				return NULL;
			}

			int ns = (int)mysql_num_rows(myResult);
			if (ns < 2) {
				mysql_free_result(myResult);
				log_error("Bad sectors geography data for geoKey=%d", geoKey);
				return NULL;
			}

			geo = geography_alloc(geoKey, type, lat, lon, ns);
			double *azm = geo->a.sectorAzimuth;
			double *rad = geo->b.sectorRadius;

			for (i = 0; i < ns; i++) {

				fields = mysql_fetch_row(myResult);
				if (NULL == fields) {
					mysql_free_result(myResult);
					log_db_error("Sectors geography query failed (3) for geoKey=%d", geoKey);
					geography_free(geo);
					return NULL;
				}

				azm[i] = atof(fields[0]);
				rad[i] = atof(fields[1]);
			}

			mysql_free_result(myResult);

			break;
		}

		case GEO_TYPE_POLYGON: {

			mysql_free_result(myResult);

			snprintf(query, MAX_QUERY,
					"SELECT latitude, longitude FROM %s.geo_polygon WHERE geo_key = %d ORDER BY vertex_key;",
					DbName, geoKey);
			if (mysql_query(MyConnection, query)) {
				log_db_error("Polygon geography query failed (1) for geoKey=%d", geoKey);
				return NULL;
			}

			myResult = mysql_store_result(MyConnection);
			if (!myResult) {
				log_db_error("Polygon geography query failed (2) for geoKey=%d", geoKey);
				return NULL;
			}

			int np = (int)mysql_num_rows(myResult);
			if (np < 3) {
				mysql_free_result(myResult);
				log_error("Bad polygon geography data for geoKey=%d", geoKey);
				return NULL;
			}

			// Duplicate the first point at the end to explicitly close the polygon if needed.

			geo = geography_alloc(geoKey, type, lat, lon, (np + 1));
			GEOPOINTS *pts = geo->a.polygon;

			for (i = 0; i < np; i++) {

				fields = mysql_fetch_row(myResult);
				if (NULL == fields) {
					mysql_free_result(myResult);
					log_db_error("Polygon geography query failed (3) for geoKey=%d", geoKey);
					geography_free(geo);
					return NULL;
				}

				geopoints_addpoint(pts, atof(fields[0]), atof(fields[1]));
			}

			if ((pts->ptLat[np - 1] != pts->ptLat[0]) || (pts->ptLon[np - 1] != pts->ptLon[0])) {
				geopoints_addpoint(pts, pts->ptLat[0], pts->ptLon[0]);
			}

			mysql_free_result(myResult);

			break;
		}
	}

	// Done.

	geo->next = cacheHead;
	cacheHead = geo;

	return geo;
}


//---------------------------------------------------------------------------------------------------------------------
// Relocate geography for a source to the source coordinates.  This creates a derived geography structure that is
// unique to the source, never shared, identified by a key that is the negative of the source key.

// Arguments:

//   source  The source to update.

void relocate_geography(SOURCE *source) {

	if (SERVAREA_GEOGRAPHY_RELOCATED != source->serviceAreaMode) {
		return;
	}
	if (!source->geography) {
		return;
	}

	GEOGRAPHY *geo = source->geography;
	if ((geo->latitude == source->latitude) && (geo->longitude == source->longitude)) {
		return;
	}

	source->serviceAreaMode = SERVAREA_GEOGRAPHY_FIXED;
	source->serviceAreaKey = -(int)source->sourceKey;

	int count = 0, i;
	switch (geo->type) {

		case GEO_TYPE_SECTORS: {
			count = geo->nSectors;
			break;
		}

		case GEO_TYPE_POLYGON: {
			count = geo->a.polygon->nPts;
			break;
		}
	}

	GEOGRAPHY *newGeo = geography_alloc(source->serviceAreaKey, geo->type, source->latitude, source->longitude, count);
	source->geography = newGeo;

	switch (geo->type) {

		case GEO_TYPE_CIRCLE: {
			newGeo->a.radius = geo->a.radius;
			break;
		}

		case GEO_TYPE_BOX: {
			newGeo->a.width = geo->a.width;
			newGeo->b.height = geo->b.height;
			break;
		}

		case GEO_TYPE_SECTORS: {
			size_t siz = count * sizeof(double);
			memcpy(newGeo->a.sectorAzimuth, geo->a.sectorAzimuth, siz);
			memcpy(newGeo->b.sectorRadius, geo->b.sectorRadius, siz);
			break;
		}

		case GEO_TYPE_POLYGON: {
			double bear, dist, lat1, lon1, lat = geo->latitude, lon = geo->longitude, nlat = newGeo->latitude,
				nlon = newGeo->longitude, *plat = geo->a.polygon->ptLat, *plon = geo->a.polygon->ptLon;
			int np = geo->a.polygon->nPts;
			GEOPOINTS *pts = newGeo->a.polygon;
			for (i = 0; i < np; i++) {
				bear_distance(lat, lon, plat[i], plon[i], &bear, NULL, &dist, Params.KilometersPerDegree);
				coordinates(nlat, nlon, bear, dist, &lat1, &lon1, Params.KilometersPerDegree);
				geopoints_addpoint(pts, lat1, lon1);
			}
			break;
		}
	}

	if (geo->geoKey < 0) {
		geography_free(geo);
	}
}


//---------------------------------------------------------------------------------------------------------------------
// Render a geography to a series of geographic coordinates, optimized for a particular map scale.  The rendered data
// is cached in the structure, if that already exists it is just returned.

// Arguments:

//   geo  The geography to render.

// The return is always the GEOPOINTS structure; no errors are possible.

GEOPOINTS *render_geography(GEOGRAPHY *geo) {

	if (geo->points) {
		return geo->points;
	}

	GEOPOINTS *pts = geopoints_alloc(0);
	geo->points = pts;

	double lat = geo->latitude, lon = geo->longitude, lat1, lon1;

	switch (geo->type) {

		case GEO_TYPE_CIRCLE: {

			double ds = geo->a.radius, dseg, azi, az;

			if (ds > Flat) {
				dseg = 2. * sqrt(Flat * ((2. * ds) - Flat));
			} else {
				dseg = 2. * Flat;
			}
			if (dseg > Dres) {
				dseg = Dres;
			}
			azi = asin(dseg / sqrt((ds * ds) + (dseg * dseg))) * RADIANS_TO_DEGREES;

			for (az = 0.; az < 360.; az += azi) {
				coordinates(lat, lon, az, ds, &lat1, &lon1, Params.KilometersPerDegree);
				geopoints_addpoint(pts, lat1, lon1);
			}

			coordinates(lat, lon, 0., ds, &lat1, &lon1, Params.KilometersPerDegree);
			geopoints_addpoint(pts, lat1, lon1);

			break;
		}

		case GEO_TYPE_BOX: {

			int ewcount = (int)(geo->a.width / Dres) + 2;
			int nscount = (int)(geo->b.height / Dres) + 2;

			int side, pti, ptinc, ptend, iter;
			double ewdist, ewbear, nsdist, dlat, lat2, lon2, dist, error;

			for (side = 1; side <= 4; side++) {

				switch (side) {
					case 1:
						nsdist = geo->b.height * 0.5;
						pti = 0;
						ptinc = 1;
						ptend = ewcount;
						break;
					case 2:
						ewdist = geo->a.width * 0.5;
						pti = nscount;
						ptinc = -1;
						ptend = 0;
						break;
					case 3:
						nsdist = geo->b.height * -0.5;
						pti = ewcount;
						ptinc = -1;
						ptend = 0;
						break;
					case 4:
						ewdist = geo->a.width * -0.5;
						pti = 0;
						ptinc = 1;
						ptend = nscount;
						break;
				}

				for (; pti != ptend; pti += ptinc) {

					switch (side) {
						case 1:
						case 3:
							ewdist = (((double)pti / (double)ewcount) - 0.5) * geo->a.width;
							if (ewdist < 0.) {
								ewbear = 90.;
								ewdist = -ewdist;
							} else {
								ewbear = 270.;
							}
							break;
						case 2:
						case 4:
							nsdist = (((double)pti / (double)nscount) - 0.5) * geo->b.height;
							break;
					}

					coordinates(lat, lon, ewbear, ewdist, &lat1, &lon1, Params.KilometersPerDegree);
					dlat = nsdist / Params.KilometersPerDegree;
					iter = 20;
					do {
						coordinates((lat + dlat), lon, ewbear, ewdist, &lat2, &lon2, Params.KilometersPerDegree);
						bear_distance(lat1, lon1, lat2, lon2, NULL, NULL, &dist, Params.KilometersPerDegree);
						error = (dist - fabs(nsdist)) / fabs(nsdist);
						dlat *= 1. + (error * 0.95);
					} while ((fabs(error) > 0.001) && (--iter > 0));

					geopoints_addpoint(pts, lat2, lon2);
				}
			}

			geopoints_addpoint(pts, pts->ptLat[0], pts->ptLon[0]);

			break;
		}

		case GEO_TYPE_SECTORS: {

			double ds, dseg, az1, az2, azi, az;
			int i;

			for (i = 0; i < geo->nSectors; i++) {

				az1 = geo->a.sectorAzimuth[i];
				if (i < (geo->nSectors - 1)) {
					az2 = geo->a.sectorAzimuth[i + 1];
				} else {
					az2 = geo->a.sectorAzimuth[0] + 360.;
				}
				ds = geo->b.sectorRadius[i];

				if (ds > Flat) {
					dseg = 2. * sqrt(Flat * ((2. * ds) - Flat));
				} else {
					dseg = 2. * Flat;
				}
				if (dseg > Dres) {
					dseg = Dres;
				}
				azi = asin(dseg / sqrt((ds * ds) + (dseg * dseg))) * RADIANS_TO_DEGREES;

				for (az = az1; az < az2; az += azi) {
					coordinates(lat, lon, az, ds, &lat1, &lon1, Params.KilometersPerDegree);
					geopoints_addpoint(pts, lat1, lon1);
				}

				coordinates(lat, lon, az2, ds, &lat1, &lon1, Params.KilometersPerDegree);
				geopoints_addpoint(pts, lat1, lon1);
			}

			coordinates(lat, lon, geo->a.sectorAzimuth[0], geo->b.sectorRadius[0], &lat1, &lon1,
				Params.KilometersPerDegree);
			geopoints_addpoint(pts, lat1, lon1);

			break;
		}

		case GEO_TYPE_POLYGON: {

			GEOPOINTS *poly = geo->a.polygon;
			double *plat = poly->ptLat, *plon = poly->ptLon, bear, dist, dist1, lat2, lon2;

			int np, i, j;

			for (i = 0; i < (poly->nPts - 1); i++) {

				lat1 = plat[i];
				lon1 = plon[i];

				geopoints_addpoint(pts, lat1, lon1);

				bear_distance(lat1, lon1, plat[i + 1], plon[i + 1], &bear, NULL, &dist, Params.KilometersPerDegree);
				np = (int)(dist / Dres) + 1;

				for (j = 1; j < np; j++) {

					dist1 = ((double)j / (double)np) * dist;
					coordinates(lat1, lon1, bear, dist1, &lat2, &lon2, Params.KilometersPerDegree);
					geopoints_addpoint(pts, lat2, lon2);			
				}
			}

			geopoints_addpoint(pts, plat[i], plon[i]);

			break;
		}
	}

	return pts;
}


//---------------------------------------------------------------------------------------------------------------------
// Test whether a point is inside a geography.

// Arguments:

//   plat, plon  Coordinates of point to test, north latitude and west longitude.
//   geography   The geography.

// The return is true for inside, false for outside.

int inside_geography(double plat, double plon, GEOGRAPHY *geo) {

	switch (geo->type) {

		case GEO_TYPE_CIRCLE: {
			double dist;
			bear_distance(geo->latitude, geo->longitude, plat, plon, NULL, NULL, &dist, Params.KilometersPerDegree);
			return (dist <= geo->a.radius);
			break;
		}

		case GEO_TYPE_BOX: {
			return inside_poly(plat, plon, render_geography(geo));
			break;
		}

		case GEO_TYPE_SECTORS: {
			double bear, dist;
			bear_distance(geo->latitude, geo->longitude, plat, plon, &bear, NULL, &dist, Params.KilometersPerDegree);
			if (bear >= geo->a.sectorAzimuth[0]) {
				int i;
				for (i = 0; i < (geo->nSectors - 1); i++) {
					if (bear < geo->a.sectorAzimuth[i + 1]) {
						return (dist <= geo->b.sectorRadius[i]);
					}
				}
			}
			return (dist <= geo->b.sectorRadius[geo->nSectors - 1]);
			break;
		}

		case GEO_TYPE_POLYGON: {
			return inside_poly(plat, plon, render_geography(geo));
			break;
		}
	}

	return 0;
}


//---------------------------------------------------------------------------------------------------------------------
// Allocate a geography structure.

// Arguments:

//   geoKey  Geography key.
//   type    GEO_TYPE_*.
//   lat     Center/reference point coordinates.
//   lon
//   count   Allocation size for sectors or pre-allocation size for polygon.  May be 0 for polygon.

// Return is the new structure.

GEOGRAPHY *geography_alloc(int geoKey, int type, double lat, double lon, int count) {

	GEOGRAPHY *geo = (GEOGRAPHY *)mem_zalloc(sizeof(GEOGRAPHY));

	geo->geoKey = geoKey;
	geo->type = type;
	geo->latitude = lat;
	geo->longitude = lon;

	switch (geo->type) {

		case GEO_TYPE_SECTORS: {
			geo->nSectors = count;
			geo->a.sectorAzimuth = (double *)mem_alloc(count * sizeof(double));
			geo->b.sectorRadius = (double *)mem_alloc(count * sizeof(double));
			break;
		}

		case GEO_TYPE_POLYGON: {
			geo->a.polygon = geopoints_alloc(count);
			break;
		}
	}

	return geo;
}


//---------------------------------------------------------------------------------------------------------------------
// Free storage for a geography.

// Arguments:

//   geo  The geography to free.

void geography_free(GEOGRAPHY *geo) {

	switch (geo->type) {

		case GEO_TYPE_SECTORS: {
			mem_free(geo->a.sectorAzimuth);
			geo->a.sectorAzimuth = NULL;
			mem_free(geo->b.sectorRadius);
			geo->b.sectorRadius = NULL;
			break;
		}

		case GEO_TYPE_POLYGON: {
			geopoints_free(geo->a.polygon);
			geo->a.polygon = NULL;
			break;
		}
	}

	if (geo->points) {
		geopoints_free(geo->points);
		geo->points = NULL;
	}

	mem_free(geo);
}


//---------------------------------------------------------------------------------------------------------------------
// Allocate a GEOPOINTS structure of specified size.  If size is 0, a default initial size is used.

#define POINTS_ALLOC_INC  100

GEOPOINTS *geopoints_alloc(int np) {

	if (np <= 0) {
		np = POINTS_ALLOC_INC;
	}

	GEOPOINTS *pts = (GEOPOINTS *)mem_zalloc(sizeof(GEOPOINTS));
	pts->ptLat = (double *)mem_alloc(np * sizeof(double));
	pts->ptLon = (double *)mem_alloc(np * sizeof(double));
	pts->maxPts = np;

	return pts;
}


//---------------------------------------------------------------------------------------------------------------------
// Add a point, increase allocated space as needed.

void geopoints_addpoint(GEOPOINTS *pts, double lat, double lon) {

	if (pts->nPts == pts->maxPts) {

		pts->maxPts += POINTS_ALLOC_INC;

		pts->ptLat = (double *)mem_realloc(pts->ptLat, (pts->maxPts * sizeof(double)));
		pts->ptLon = (double *)mem_realloc(pts->ptLon, (pts->maxPts * sizeof(double)));
	}

	pts->ptLat[pts->nPts] = lat;
	pts->ptLon[pts->nPts++] = lon;

	if (1 == pts->nPts) {
		pts->xlts = lat;
		pts->xltn = lat;
		pts->xlne = lon;
		pts->xlnw = lon;
	} else {
		if (lat < pts->xlts) {
			pts->xlts = lat;
		}
		if (lat > pts->xltn) {
			pts->xltn = lat;
		}
		if (lon < pts->xlne) {
			pts->xlne = lon;
		}
		if (lon > pts->xlnw) {
			pts->xlnw = lon;
		}
	}
}


//---------------------------------------------------------------------------------------------------------------------
// Free memory for a GEOPOINTS structure.  Note this does not check for linked structure, caller must do that.

void geopoints_free(GEOPOINTS *pts) {

	if (pts) {

		mem_free(pts->ptLat);
		pts->ptLat = NULL;
		mem_free(pts->ptLon);
		pts->ptLon = NULL;

		mem_free(pts);
	}
}


//---------------------------------------------------------------------------------------------------------------------
// Evaluate a test point against a polygon.  This uses the "even-odd" rule for determining insideness.  The polygon may
// be compound with multiple islands and holes, the parts of a compound poly are separated by 999,999 coordinate value
// pairs in data array.  The test is based on assuming latitude and longitude are planar X-Y coordinates, the vertex
// list for each sub-poly is assumed to be closed (first and last points identical), and the polygons are assumed to
// never cross each other or themselves.

// Because of the treament of geographic coordinates as planar, this does not produce accurate results for regions near
// the poles.  It is assumed those will not occur in practice and the inaccuracy is of no concern.

// Arguments:

//   plat, plon  Coordinates of point being tested, nominally geographic coordinates in degrees positive north and
//                 west; however in fact the units and sign convention are arbitrary, as long as they are consistent
//                 with the polygon data provided and allow the 999,999 break-point convention.
//   pts         Polygon vertex point coordinates.  May contain +999,+999 break points to separate parts of a compound
//                 region, parts are implicitly "lakes" or "islands" depending on nesting.  All parts must be closed
//                 explicitly (first and last point coordinates identical).

// Return is 1 if the point is inside, 0 if not.

int inside_poly(double plat, double plon, GEOPOINTS *pts) {

	if ((plat < pts->xlts) || (plat > pts->xltn) || (plon < pts->xlne) || (plon > pts->xlnw)) {
		return 0;
	}

	int i, q1, q2, count, c;
	double x, y, x1, y1, x2, y2, dx, yt;

	// A table for fast determination of crossing, indexed by the quadrant numbers for two segment endpoints.  A 1
	// means crossing occurs, 0 means it does not, a -1 means the endpoints are in non-adjacent quadrants and further
	// checking is needed to determine whether there is a crossing or not.

	static int ctbl[4][4] = {
		{ 0,  1, -1,  0},
		{ 1,  0,  0, -1},
		{-1,  0,  0,  0},
		{ 0, -1,  0,  0}
	};

	// Set the test point.

	x = plon;
	y = plat;

	// Loop for points, count up the number of polygon segments that cross a line from the test point parallel to the
	// positive Y axis.  The first step is to obtain segment endpoint data, the first endpoint comes from the previous
	// loop (undefined on the first loop, that's OK because the quadrant number will be -1, see below).

	count = 0;
	x2 = 0.;
	y2 = 0.;
	q2 = -1;
	for (i = 0; i < pts->nPts; i++) {
		x1 = x2;
		y1 = y2;
		q1 = q2;
		x2 = pts->ptLon[i];
		y2 = pts->ptLat[i];

		// Sub-polygon breaks in a compound polygon are marked by 999 values in the data.  Nothing special needs to be
		// done except to be sure not to test an invalid segment across the break, flag by setting the quadrant to -1.
		// Using the even-odd rule means the winding directions of the sub-polys don't make any difference, the test
		// works regardless.  But this does assume that none of the sub-polys cross each other or themselves.

		if (y2 == 999.) {
			q2 = -1;
			continue;
		}

		// Determine the quadrant relative to the test point which contains the second endpoint.  The quadrants are
		// numbered 0-1-2-3 clockwise starting with +x,+y, a point that falls on an axis line belongs to the quadrant
		// that is counter-clockwise of the line (actually this is flipped if longitudes are positive west, but the
		// logic works out the same, only the preceding description is backwards).  If a point is coincident with the
		// test point, the test point is arbitrarily considered inside the poly, the test is done.

		if (x2 < x) {
			if (y2 < y) {
				q2 = 2;
			} else {
				q2 = 1;
			}
		} else {
			if (x2 > x) {
				if (y2 > y) {
					q2 = 0;
				} else {
					q2 = 3;
				}
			} else {
				if (y2 < y) {
					q2 = 2;
				} else {
					if (y2 > y) {
						q2 = 0;
					} else {
						count = 1;
						break;
					}
				}
			}
		}

		// If the endpoint from the previous loop was undefined, that's all.

		if (q1 < 0) {
			continue;
		}

		// A static table indexed by the endpoint quadrants tells what to do.  If the table value is < 0, further
		// checking is needed, otherwise the table value is 0 for no crossing or 1 for crossing, and so is simply added
		// to the crossing count.  In the cases that need checking, the possibility exists that the test point falls
		// exactly on the segment; in this case, as with the coincident-point case above, the test point is inside, the
		// test is done.

		if ((c = ctbl[q1][q2]) < 0) {
			if ((dx = x2 - x1) == 0.) {
				count = 1;
				break;
			}
			yt = y1 + (((x - x1) / dx) * (y2 - y1));
			if (yt > y) {
				c = 1;
			} else {
				if (yt < y) {
					c = 0;
				} else {
					count = 1;
					break;
				}
			}
		}
		count += c;
	}

	// Done, return one's bit of crossing count, which will be set for odd counts (inside) and clear for even counts
	// (outside).

	return(count & 1);
}
