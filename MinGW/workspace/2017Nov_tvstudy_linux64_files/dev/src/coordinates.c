//
//  coordinates.c
//  TVStudy
//
//  Copyright (c) 2012-2016 Hammett & Edison, Inc.  All rights reserved.


// Functions related to geographic coordinates.


#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <math.h>

#include "global.h"
#include "coordinates.h"
#include "memory.h"


//---------------------------------------------------------------------------------------------------------------------
// Function for converting geographic coordinates between datums and coordinate systems.  Supported conversions:

//   NAD27 to NAD83/WGS84
//   NAD83/WGS84 to NAD27
//   WGS72 to NAD83/WGS84
//   NAD83/WGS84 to WGS72

// The NAD27<->NAD83 conversion algorithm for CONUS, Alaska, Hawaii, and Puerto Rico is the official NGS algorithm
// described in:

//   NOAA Technical Memorandum NOS NGS-50
//   NADCON - The Application of Minimum Curvature-Derived Surfaces in the Transformation of Positional Data from
//     the North American Datum of 1927 to the North American Datum of 1983
//   Warren T. Dewhurst.

// Formulas for NAD27<->WGS84 conversions for Canada, Mexico, and Guam, and the formula for WGS84<->WGS72 conversion,
// are from:

//   DMA Technical Report Number 8350.2
//   Department of Defense World Geodetic System 1984

// Note that this treats NAD83 and WGS84 as identical and inter-changeable; the distinction between a datum and a
// coordinate system is not significant in the software using this function.  Also the term NAD27 is being used
// somewhat broadly, it encompasses NAD27 for CONUS, and the separate "old island" datums for Hawaii and Guam.

// For NADCON conversions, adjacent regions may be handled by different conversion algorithms.  That may result in
// discontinuities when converting points on either side of the region boundaries, hence this routine is not suitable
// for use incrementally on a continuous path; it should be used to convert path endpoints or isolated individual
// points only.  Also, NADCON conversions are not guaranteed to be reciprocal.  A conversion may return coordinates in
// a different region, or outside any region, thus the inverse conversion may not return the original coordinates.

// Arguments:

//   xlat   Input coordinates in degrees, latitude positive north,
//   xlon     longitude positive west.
//   jflg   Conversion type:
//            CNV_N27N83   NAD27 to NAD83/WGS84.
//            CNV_N83N27   NAD83/WGS84 to NAD27.
//            CNV_W72N83   WGS72 to NAD83/WGS84.
//            CNV_N83W72   NAD83/WGS84 to WGS72.
//   ylat   Output coordinates, north latitude,
//   ylon     west longitude, in degrees.

// The return is 0 for success, else an error code:

//   CNV_EAREA  Coordinates outside conversion areas.
//   CNV_EFILE  Database file open or read error in NADCON algorithm.
//   CNV_ECONV  Iterated conversion would not converge.

// For any non-0 return no conversion is performed and the output coordinates are set to the input coordinates.

#define NR 11   // Number of conversion regions for NAD27<->NAD83/WGS84.

#define A   6.3782064e6      // Parameters for the Clarke ellipsoid of 1866, for the DMA TR 8350.2 formulas.
#define F   3.390075e-3
#define DA  -6.94e1
#define DF  -3.7264639e-5

int convert_coords(double xlat, double xlon, int jflg, double *ylat, double *ylon) {

	// Static tables defining conversion regions for NAD27<->NAD83/WGS84:

	//   xlats, xlatn   bounds of the region
	//   xlone, xlonw
	//   ialg           algorithm key, 1=NADCON, 2=DMA TR 8350.2
	//   ncl, nrw       column and row counts for NADCON region table
	//   dx             point spacing for NADCON region, first coefficient for DMA TR 8350.2 region
	//   dy, dz         coefficients for DMA TR 8350.2 region
	//   nadla, nadlo   NADCON table storage, loaded from file on first use

	// Order is important in the region list.  Some regions overlap and where that occurs the first region listed takes
	// precedence.  A converted point may fall in a different region vs. the original, so an inverse conversion might
	// not return the original coordinates.

	static double xlats[NR] = {
		 20.,  62.,     56.,  57.,   46.,   18.,  17.,  50.,  14.,   13.,  -15.
	};
	static double xlatn[NR] = {
		 50.,  64.,     57.,  58.,   77.,   23.,  19.,  75.,  20.,   16.,  -14.
	};
	static double xlone[NR] = {
		 63., 168.,    169., 169.,  128.,  154.,  64.,  51.,  87., -146.,  169.
	};
	static double xlonw[NR] = {
		131., 172.,    171., 171.,  194.,  161.,  68., 128., 114., -144.,  171.
	};
	static int ialg[NR] = {
		   1,    1,       1,    1,     1,     1,    1,    2,    2,     2,    2
	};
	static int ncl[NR] = {
		 273,   81,     121,   41,   529,   281,   81,    0,    0,     0,    0
	};
	static int nrw[NR] = {
		 121,   41,      61,   21,   249,   201,   41,    0,    0,     0,    0
	};
	static double dx[NR] = {
		0.25, 0.05, 0.01667, 0.05, 0.125, 0.025, 0.05, -10., -12., -100., -115.
	};
	static double dy[NR] = {
		  0.,   0.,      0.,   0.,    0.,    0.,   0., 158., 130., -248.,  118.
	};
	static double dz[NR] = {
		  0.,   0.,      0.,   0.,    0.,    0.,   0., 187., 190.,  259.,  426.
	};
	static float *nadla[NR] = {
		NULL, NULL,    NULL, NULL,  NULL,  NULL, NULL, NULL, NULL,  NULL,  NULL
	};
	static float *nadlo[NR] = {
		NULL, NULL,    NULL, NULL,  NULL,  NULL, NULL, NULL, NULL,  NULL,  NULL
	};

#ifdef __BIG_ENDIAN__
	static char *lafil[NR] = {
		"nadcon/conus_eb.las",
		"nadcon/stlrnc_eb.las",
		"nadcon/stgeorge_eb.las",
		"nadcon/stpaul_eb.las",
		"nadcon/alaska_eb.las",
		"nadcon/hawaii_eb.las",
		"nadcon/prvi_eb.las",
		"",
		"",
		"",
		""
	};
	static char *lofil[NR] = {
		"nadcon/conus_eb.los",
		"nadcon/stlrnc_eb.los",
		"nadcon/stgeorge_eb.los",
		"nadcon/stpaul_eb.los",
		"nadcon/alaska_eb.los",
		"nadcon/hawaii_eb.los",
		"nadcon/prvi_eb.los",
		"",
		"",
		"",
		""
	};
#else
	static char *lafil[NR] = {
		"nadcon/conus.las",
		"nadcon/stlrnc.las",
		"nadcon/stgeorge.las",
		"nadcon/stpaul.las",
		"nadcon/alaska.las",
		"nadcon/hawaii.las",
		"nadcon/prvi.las",
		"",
		"",
		"",
		""
	};
	static char *lofil[NR] = {
		"nadcon/conus.los",
		"nadcon/stlrnc.los",
		"nadcon/stgeorge.los",
		"nadcon/stpaul.los",
		"nadcon/alaska.los",
		"nadcon/hawaii.los",
		"nadcon/prvi.los",
		"",
		"",
		"",
		""
	};
#endif

	// Misc. local variables.

	FILE *nadfil;
	int i, ir, ip, irw0, irw1, icl0, icl1, i1, i2, i3, i4, siz;
	double xlat1, xlon1, ygrid, xgrid, yfrac, xfrac, dp, dl, xp, xl, e2, tmp, rm, rn, sinxp, sinxl, cosxp, cosxl, dlat,
		dlon, elat, elon, t1, t2, t3, t4, a1, b1, c1, d1, a2, b2, c2, d2;
	float *bla, *blo, *buf;
	char fname[MAX_STRING];

	// Initialize.  Input coordinates are copied due to the possible iterative nature of the conversion, see below.
	// Output coordinates are set to input in case an error occurs.

	xlat1 = xlat;
	xlon1 = xlon;

	*ylat = xlat;
	*ylon = xlon;

	// For NAD27<->NAD83/WGS84 conversions, determine what region the point falls in.  The other conversions,
	// WGS72<->WGS84, apply anywhere.

	if ((jflg == CNV_N27N83) || (jflg == CNV_N83N27)) {
		for (ir = 0; ir < NR; ir++) {
			if ((xlat >= xlats[ir]) && (xlat <= xlatn[ir]) && (xlon >= xlone[ir]) && (xlon <= xlonw[ir])) {
				break;
			}
		}
		if (ir == NR) {
			return(CNV_EAREA);
		}
	}

	// The conversion process is in a loop because conversions from NAD83/WGS84 require iteration.  The loop will exit
	// after too many iterations.

	ip = 0;

	do {

		// NAD27<->NAD83/WGS84 conversions.  If in a region that uses the NADCON conversion algorithm, get the table
		// data, NADCON is just table lookup and interpolation.

		if ((jflg == CNV_N27N83) || (jflg == CNV_N83N27)) {

			if (ialg[ir] == 1) {

				bla = nadla[ir];
				blo = nadlo[ir];

				// If the table has not yet been loaded, do that.  Note the files contain an extra header row and index
				// column, those are skipped when loading.

				if (NULL == bla) {

					siz = ncl[ir] * sizeof(float);

					bla = (float *)mem_alloc(2 * nrw[ir] * siz);
					buf = bla;

					snprintf(fname, MAX_STRING, "%s/%s", DBASE_DIRECTORY_NAME, lafil[ir]);
					if (NULL == (nadfil = fopen(fname, "rb"))) {
						mem_free(bla);
						return(CNV_EFILE);
					}
					fseek(nadfil, (siz + sizeof(float)), SEEK_CUR);

					for (i = 0; i < nrw[ir]; i++, buf += ncl[ir]) {
						fseek(nadfil, sizeof(float), SEEK_CUR);
						if (fread(buf, 1, siz, nadfil) != siz) {
							fclose(nadfil);
							mem_free(bla);
							return(CNV_EFILE);
						}
					}
					fclose(nadfil);

					blo = buf;

					snprintf(fname, MAX_STRING, "%s/%s", DBASE_DIRECTORY_NAME, lofil[ir]);
					if (NULL == (nadfil = fopen(fname, "rb"))) {
						mem_free(bla);
						return(CNV_EFILE);
					}
					fseek(nadfil, (siz + sizeof(float)), SEEK_CUR);

					for (i = 0; i < nrw[ir]; i++, buf += ncl[ir]) {
						fseek(nadfil, sizeof(float), SEEK_CUR);
						if (fread(buf, 1, siz, nadfil) != siz) {
							fclose(nadfil);
							mem_free(bla);
							return(CNV_EFILE);
						}
					}
					fclose(nadfil);

					nadla[ir] = bla;
					nadlo[ir] = blo;
				}

				// Determine the lookup table row and column.

				ygrid = ((xlat1 - xlats[ir]) / dx[ir]);
				xgrid = ((xlonw[ir] - xlon1) / dx[ir]);

				irw0 = (int)ygrid;
				if (irw0 < 0) {
					irw0 = 0;
				}
				if (irw0 > (nrw[ir] - 2)) {
					irw0 = nrw[ir] - 2;
				}
				irw1 = irw0 + 1;

				icl0 = (int)xgrid;
				if (icl0 < 0) {
					icl0 = 0;
				}
				if (icl0 > (ncl[ir] - 2)) {
					icl0 = ncl[ir] - 2;
				}
				icl1 = icl0 + 1;

				i1 = (irw0 * ncl[ir]) + icl0;
				i2 = (irw1 * ncl[ir]) + icl0;
				i3 = (irw0 * ncl[ir]) + icl1;
				i4 = (irw1 * ncl[ir]) + icl1;

				// Extract table points and interpolate.

				t1 = (double)bla[i1];
				t2 = (double)bla[i2];
				t3 = (double)bla[i3];
				t4 = (double)bla[i4];
				a1 = t1;
				b1 = t3 - t1;
				c1 = t2 - t1;
				d1 = t4 - t3 - t2 + t1;

				t1 = (double)blo[i1];
				t2 = (double)blo[i2];
				t3 = (double)blo[i3];
				t4 = (double)blo[i4];
				a2 = t1;
				b2 = t3 - t1;
				c2 = t2 - t1;
				d2 = t4 - t3 - t2 + t1;

				yfrac = ygrid - (double)irw0;
				xfrac = xgrid - (double)icl0;

				dp = a1 + (b1 * xfrac) + (c1 * yfrac) + (d1 * xfrac * yfrac);
				dl = -(a2 + (b2 * xfrac) + (c2 * yfrac) + (d2 * xfrac * yfrac));

			// NAD27<->NAD83/WGS84 in a region using the abridged Molodensky formulas based on tables 7.2, 7.4, and 7.5
			// in DMA TR 8350.2.

			} else {

				xp = xlat1 * DEGREES_TO_RADIANS;
				xl = -xlon1 * DEGREES_TO_RADIANS;
				sinxp = sin(xp);
				sinxl = sin(xl);
				cosxp = cos(xp);
				cosxl = cos(xl);

				e2 = (2. * F) - (F * F);
				tmp = sqrt(1. - (e2 * sinxp * sinxp));
				rm = (A * (1. - e2)) / (tmp * tmp * tmp);
				rn = A / tmp;

				dp = ((dz[ir] * cosxp) - (dx[ir] * sinxp * cosxl) - (dy[ir] * sinxp * sinxl) +
					(((A * DF) + (F * DA)) * sin(2. * xp))) / (4.848136e-6 * rm);
				dl = ((dy[ir] * cosxl) - (dx[ir] * sinxl)) / (4.848136e-6 * rn * cosxp);
			}

		// WGS72<->WGS84 conversions, formula from table 7.1 in DMA TR 8350.2.

		} else {

			xp = xlat1 * DEGREES_TO_RADIANS;
			dp = (1.455271e-1 * cos(xp)) + (6.437642e-3 * sin(2. * xp));
			dl = 5.54e-1;
		}

		// Lat/lon deltas computed; the rest is common to all conversions.

		dlat = dp / 3600.;
		dlon = dl / 3600.;

		// If this is the first pass through, check the conversion flag, for either conversion to NAD83/WGS84, done.
		// For either conversion from NAD83/WGS84, iterate.  For the first time through, NAD83/WGS84 coordinates were
		// used as if they were other to get an initial set of deltas.  Use those deltas inversely to get the first
		// guess at the actual other coords, then loop.

		if (ip == 0) {

			if ((jflg == CNV_N27N83) || (jflg == CNV_W72N83)) {
				*ylat = xlat + dlat;
				*ylon = xlon - dlon;
				return(0);
			}

			xlat1 = xlat - dlat;
			xlon1 = xlon + dlon;

		// On second and later iterations, check how close the NAD83/WGS84 coords just computed from the guessed other
		// coords are to the actual NAD83/WGS84 coords; if not close enough, modify the guess and loop.

		} else {

			elat = (xlat1 + dlat) - xlat;
			elon = (xlon1 - dlon) - xlon;

			if ((fabs(elat) < 1.e-10) && (fabs(elon) < 1.e-10)) {
				*ylat = xlat1;
				*ylon = xlon1;
				return(0);
			}

			xlat1 -= elat;
			xlon1 -= elon;
		}

	} while (++ip < 20);

	// If here iteration failed.

	return(CNV_ECONV);
}


//---------------------------------------------------------------------------------------------------------------------
// Compute bearing, reverse bearing, and distance between two sets of coordinates, using spherical trig.  Because the
// caller does not always need one or both of the bearings, the return pointer(s) for the bearings may be NULL to
// suppress bearing calculation.  However the forward bearing must be computed if the reverse bearing is needed.  The
// distance must always be computed even if only a bearing is needed, so the distance pointer must always be non-NULL.
// The option to compute reverse bearing exists because that calculation is simpler if done simultaneously with the
// distance calculation.  This will give correct results for longitudes >180 or <-180 degrees.

// Arguments:

//  lat1, lon1   First point coordinates, degrees, latitude positive north and longitude positive west.
//  lat2, lon2   Second point coordinates, as above.
//  bear         Return forward bearing in degrees true, may be NULL if bearings are not needed.
//  rbear        Return reciprocal bearing in degrees true, may be NULL, but if non-NULL bear must also be non-NULL.
//  dist         Return distance in kilometers, must always be non-NULL.
//  kmPerDegree  Spherical earth kilometers per degree of arc.

void bear_distance(double lat1, double lon1, double lat2, double lon2, double *bear, double *rbear, double *dist,
		double kmPerDegree) {

	double xla1, xlo1, xla2, xlo2, delo, sinxla1, sinxla2, cosxla1, cosxla2, di, cosdi, sindi, temp;

	// Convert arguments to radians and pre-compute some trig functions.

	xla1 = lat1 * DEGREES_TO_RADIANS;
	xlo1 = lon1 * DEGREES_TO_RADIANS;
	xla2 = lat2 * DEGREES_TO_RADIANS;
	xlo2 = lon2 * DEGREES_TO_RADIANS;
	sinxla1 = sin(xla1);
	sinxla2 = sin(xla2);
	cosxla1 = cos(xla1);
	cosxla2 = cos(xla2);

	delo = xlo1 - xlo2;
	while (delo < -PI) {
		delo += TWO_PI;
	}
	while (delo > PI) {
		delo -= TWO_PI;
	}

	// Compute the distance.

	cosdi = (sinxla1 * sinxla2) + (cosxla1 * cosxla2 * cos(delo));
	if (cosdi < -1.) {
		cosdi = -1.;
	}
	if (cosdi > 1.) {
		cosdi = 1.;
	}
	di = acos(cosdi);
	*dist = di * RADIANS_TO_DEGREES * kmPerDegree;

	// Now compute the bearing, if requested.

	if (bear) {
		sindi = sin(di);
		temp = sindi * cosxla1;
		if (fabs(temp) < 1.e-30) {
			*bear = 0.;
		} else {
			temp = (sinxla2 - (sinxla1 * cosdi)) / temp;
			if (temp < -1.) {
				temp = -1.;
			}
			if (temp > 1.) {
				temp = 1.;
			}
			if (delo < 0.) {
				*bear = (TWO_PI - acos(temp)) * RADIANS_TO_DEGREES;
			} else {
				*bear = acos(temp) * RADIANS_TO_DEGREES;
			}
		}
		if (*bear < 0.) {
			*bear += 360.;
		}
		if (*bear >= 360.) {
			*bear -= 360.;
		}

		// And finally the reverse-path bearing, if requested.

		if (rbear) {
			temp = sindi * cosxla2;
			if (fabs(temp) < 1.e-30) {
				*rbear = 0.;
			} else {
				temp = (sinxla1 - (sinxla2 * cosdi)) / temp;
				if (temp < -1.) {
					temp = -1.;
				}
				if (temp > 1.) {
					temp = 1.;
				}
				if (delo > 0.) {
					*rbear = (TWO_PI - acos(temp)) * RADIANS_TO_DEGREES;
				} else {
					*rbear = acos(temp) * RADIANS_TO_DEGREES;
				}
			}
			if (*rbear < 0.) {
				*rbear += 360.;
			}
			if (*rbear >= 360.) {
				*rbear -= 360.;
			}
		}
	}
}


//---------------------------------------------------------------------------------------------------------------------
// Determine coordinates of an end point given start coordinates and bearing and distance, using spherical methods.

// This deliberately does not correct the end point longitude on paths that cross the 180-degree line, so the end point
// coordinates are always continuous along a path with respect to the start.  In other words, if the path bearing is
// westerly the end point longitude will be numerically greater than the start even if that means it is a value greater
// than 180 degrees.  Likewise for an easterly bearing and -180 degrees.  See further discussion related to grid-layout
// code, i.e. in source.c.  All other routines are designed to work correctly with over- or under-range longitude
// values, including bear_distance() above, and the functions in terrain.c.

// Arguments:

//   lat1, lon1   Start point coordinates, degrees, latitude positive north and longitude positive west.
//   bear         Path bearing in degrees true.
//   dist         Path length in kilometers.
//   lat2, lon2   Return end point coordinates, as above, however the longitude will always have the same sign as the
//                  start point even if that means |lat2| > 180.
//   kmPerDegree  Spherical earth kilometers per degree of arc.

void coordinates(double lat1, double lon1, double bear, double dist, double *lat2, double *lon2, double kmPerDegree) {

	double xla1, xlo1, di, br, sinxla1, cosxla1, cosdi, sinxla, xla, temp, cosxlo, xlo;

	// Convert arguments to radians, and pre-compute some trig functions.

	xla1 = lat1 * DEGREES_TO_RADIANS;
	xlo1 = lon1 * DEGREES_TO_RADIANS;
	di = (dist / kmPerDegree) * DEGREES_TO_RADIANS;
	while (bear >= 360.) bear -= 360.;
	while (bear < 0.) bear += 360.;
	br = bear * DEGREES_TO_RADIANS;
	sinxla1 = sin(xla1);
	cosxla1 = cos(xla1);
	cosdi = cos(di);

	// Compute latitude.

	sinxla = (sinxla1 * cosdi) + (cosxla1 * sin(di) * cos(br));
	if (sinxla < -1.) {
		sinxla = -1.;
	}
	if (sinxla > 1.) {
		sinxla = 1.;
	}
	xla = asin(sinxla);

	// Compute longitude.

	temp = cosxla1 * cos(xla);
	if (fabs(temp) < 1.e-30) {
		xlo = xlo1;
	} else {
		cosxlo = (cosdi - (sinxla1 * sinxla)) / temp;
		if (cosxlo < -1) {
			cosxlo = -1.;
		}
		if (cosxlo > 1.) {
			cosxlo = 1.;
		}
		xlo = acos(cosxlo);
		if (br > PI) {
			xlo = xlo1 + xlo;
		} else {
			xlo = xlo1 - xlo;
		}
	}

	// Return result.

	*lat2 = xla * RADIANS_TO_DEGREES;
	*lon2 = xlo * RADIANS_TO_DEGREES;
}


//---------------------------------------------------------------------------------------------------------------------
// Convert a latitude or longitude into formatted degrees-minutes-seconds string.

// Arguments:

//   latlon  The latitude or longitude in decimal degrees.
//   isLon   True if value is longitude, else latitude.

// Return is value points to storage that will be re-used on the next call!

char *latlon_string(double latlon, int isLon) {

	static char string[MAX_STRING];

	char nsew = ' ';
	double ll = latlon;
	if (isLon) {
		if (latlon < 0.) {
			nsew = 'E';
			ll = -latlon;
		} else {
			nsew = 'W';
		}
	} else {
		if (latlon < 0.) {
			nsew = 'S';
			ll = -latlon;
		} else {
			nsew = 'N';
		}
	}

	int d = (int)ll;
	int m = (int)((ll - (double)d) * 60.);
	double s = (((ll - (double)d) * 60.) - (double)m) * 60.;
	if (s >= 59.95) {
		s = 0.;
		if (++m == 60) {
			m = 0;
			++d;
		}
	}

	snprintf(string, MAX_STRING, "%3d %2d %5.2f %c", d, m, s, nsew);

	return string;
}


//---------------------------------------------------------------------------------------------------------------------
// Routines for working with bounding boxes in integer units of geographic coordinates, typically arc-seconds but that
// is not required except when using the *_latlon() and *_radius() functions.  First one initializes a bounding box
// with a null area so it can be used to accumulate limits.

// Arguments:

//   bounds  The bounds to initialize.

void initialize_bounds(INDEX_BOUNDS *bounds) {

	bounds->southLatIndex = INVALID_LATLON_INDEX;
	bounds->eastLonIndex = INVALID_LATLON_INDEX;
	bounds->northLatIndex = -INVALID_LATLON_INDEX;
	bounds->westLonIndex = -INVALID_LATLON_INDEX;
}


//---------------------------------------------------------------------------------------------------------------------
// Extend a bounding box with index coordinates.  There is an important fundamental concept here.  The values in the
// bounding box structure are integers but they represent zero-width lines of exact latitude and longitude.  This
// function assumes the index values it is being given were determined from floating-point values using floor(), i.e.
// integer arc-seconds as in extend_bounds_latlon() below.  Thus north and west are extended by the arguments plus one.
// In other words, a set of index coordinates do not represent a single point, they represent a 1-by-1-unit box.  That
// convention means a bounding box is never null, it will always enclose a non-zero area.

// Arguments:

//   bounds              The bounds to extend; units are arbitrary.
//   latIndex, lonIndex  Point the bounds are extended to include; units must match bounds.

void extend_bounds_index(INDEX_BOUNDS *bounds, int latIndex, int lonIndex) {

	if (latIndex < bounds->southLatIndex) {
		bounds->southLatIndex = latIndex;
	}
	if (lonIndex < bounds->eastLonIndex) {
		bounds->eastLonIndex = lonIndex;
	}
	if (++latIndex > bounds->northLatIndex) {
		bounds->northLatIndex = latIndex;
	}
	if (++lonIndex > bounds->westLonIndex) {
		bounds->westLonIndex = lonIndex;
	}
}


//---------------------------------------------------------------------------------------------------------------------
// Extend a bounding box with latitude-longitude coords in degrees.

// Arguments:

//   bounds    The bounds to extend; units are arc-seconds.
//   lat, lon  Latitude and longitude of the point, degrees, positive north and west.

void extend_bounds_latlon(INDEX_BOUNDS *bounds, double lat, double lon) {

	int latIndex = (int)floor(lat * 3600.);
	int lonIndex = (int)floor(lon * 3600.);
	extend_bounds_index(bounds, latIndex, lonIndex);
}


//---------------------------------------------------------------------------------------------------------------------
// Extend a bounding box to enclose another one (aka union).

// Arguments:

//   bounds  The bounds to extend; units are arbitrary.
//   box     Area the bounds are extended to include; units must match bounds.

void extend_bounds_bounds(INDEX_BOUNDS *bounds, INDEX_BOUNDS *box) {

	if (box->southLatIndex < bounds->southLatIndex) {
		bounds->southLatIndex = box->southLatIndex;
	}
	if (box->eastLonIndex < bounds->eastLonIndex) {
		bounds->eastLonIndex = box->eastLonIndex;
	}
	if (box->northLatIndex > bounds->northLatIndex) {
		bounds->northLatIndex = box->northLatIndex;
	}
	if (box->westLonIndex > bounds->westLonIndex) {
		bounds->westLonIndex = box->westLonIndex;
	}
}


//---------------------------------------------------------------------------------------------------------------------
// Extend a bounding box to enclose a given radius around latitude-longitude coords.  Simply extending by points on the
// circle in four cardinal directions is sufficient assuming the radius is small relative to earth radius.  Note this
// will always produce a box that is the correct size even when within the radius distance of 180 degrees east or west
// longitude; see comments for the coordinates() routine above.  However the index values for longitude can as a result
// be outside the range of +/- (180 * 3600).  All other routines are designed to work correctly in that case.

// Arguments:

//   bounds       The bounds to extend; units must be arc-seconds.
//   lat, lon     Center point coordinates, degrees, positive north and west.
//   radius       Radius in kilometers.
//   kmPerDegree  Spherical earth kilometers per degree.

void extend_bounds_radius(INDEX_BOUNDS *bounds, double lat, double lon, double radius, double kmPerDegree) {

	double plat, plon;

	coordinates(lat, lon, 0., radius, &plat, &plon, kmPerDegree);
	extend_bounds_latlon(bounds, plat, plon);
	coordinates(lat, lon, 90., radius, &plat, &plon, kmPerDegree);
	extend_bounds_latlon(bounds, plat, plon);
	coordinates(lat, lon, 180., radius, &plat, &plon, kmPerDegree);
	extend_bounds_latlon(bounds, plat, plon);
	coordinates(lat, lon, 270., radius, &plat, &plon, kmPerDegree);
	extend_bounds_latlon(bounds, plat, plon);
}


//---------------------------------------------------------------------------------------------------------------------
// Test if an index point is inside a bounding box.  As discussed above, this assumes coordinates were determined from
// floating-point values using floor().  The rule is that a point falling precisely on the south or east edge is inside
// the bounds, precisely on the north or west edge is outside.

// Arguments:

//   bounds              The bounds to test; units are arbitrary.
//   latIndex, lonIndex  The point being tested; units must match bounds.

// Return 1 for inside, 0 for outside; see discussion above regarding edges.

int inside_bounds_index(INDEX_BOUNDS *bounds, int latIndex, int lonIndex) {

	if (latIndex < bounds->southLatIndex) {
		return 0;
	}
	if (lonIndex < bounds->eastLonIndex) {
		return 0;
	}
	if (latIndex >= bounds->northLatIndex) {
		return 0;
	}
	if (lonIndex >= bounds->westLonIndex) {
		return 0;
	}

	return 1;
}


//---------------------------------------------------------------------------------------------------------------------
// Test if a latitude-longitude point is inside a bounding box.

// Arguments:

//   bounds    The bounds to test; units must be arc-seconds.
//   lat, lon  Latitude and longitude of the point being tested, degrees, positive north and west.

// Return 1 for inside, 0 for outside; see discussion above regarding edges.

int inside_bounds_latlon(INDEX_BOUNDS *bounds, double lat, double lon) {

	int latIndex = (int)floor(lat * 3600.);
	int lonIndex = (int)floor(lon * 3600.);
	return inside_bounds_index(bounds, latIndex, lonIndex);
}


//---------------------------------------------------------------------------------------------------------------------
// Test if one bounding box is entirely within another.

// Arguments:

//   bounds   The bounds to test; units are arbitrary.
//   box      The bounds being tested; units must match bounds.

// Return 1 if the box is entirely inside the bounds, else 0.

int inside_bounds_bounds(INDEX_BOUNDS *bounds, INDEX_BOUNDS *box) {

	if (box->southLatIndex < bounds->southLatIndex) {
		return 0;
	}
	if (box->eastLonIndex < bounds->eastLonIndex) {
		return 0;
	}
	if (box->northLatIndex > bounds->northLatIndex) {
		return 0;
	}
	if (box->westLonIndex > bounds->westLonIndex) {
		return 0;
	}

	return 1;
}


//---------------------------------------------------------------------------------------------------------------------
// Test if one bounding box overlaps another.

// Arguments:

//   bounds   First bounds; units are arbitrary.
//   box      Second bounds; units must match bounds.

// Return 1 for any overlap, 0 for no overlap.  Coincident edges is not overlap.

int overlaps_bounds(INDEX_BOUNDS *bounds, INDEX_BOUNDS *box) {

	if (box->southLatIndex >= bounds->northLatIndex) {
		return 0;
	}
	if (box->eastLonIndex >= bounds->westLonIndex) {
		return 0;
	}
	if (box->northLatIndex <= bounds->southLatIndex) {
		return 0;
	}
	if (box->westLonIndex <= bounds->eastLonIndex) {
		return 0;
	}

	return 1;
}
