//
//  fcc_curve.c
//  TVStudy
//
//  Copyright (c) 2012-2016 Hammett & Edison, Inc.  All rights reserved.


// FCC propagation curves lookup.  This is based on a previous C version derived from original FCC FORTRAN code (not
// the code in the main_flr DTV study program, an older version).  Modifications were needed for DTV study purposes, in
// particular this will never fail to return a result, previously fatal error conditions now revert to defaults, and
// vertical-plane patterns are applied in the same manner as in main_flr.  Also support for FM channels has been
// eliminated.  Following are edited comments from the previous C version.

// Library subroutine TVFMFS - performs lookups on FCC propagation curves.  Translated from old FORTRAN version, which
// was based largely on a program listing of the program CURVES obtained from the FCC, which described itself as:

//   TV and FM Field Strength Calculations

//   Program by Ken Kidder, based on "Field Strength Calculations for TV and FM Broadcasting", by Gary S. Kalagian,
//   Report No. RS 76-01, January 1976.

// This version also includes an additional curve set not in the FCC program, that is the F(50,90) curves.  In
// accordance with comments by the FCC in the Sixth Further Notice regarding Docket 87-268 on Digital TV, F(50,90)
// field strength at a given height/distance point on the curves is lower than the F(50,50) curve value at that point
// by the same amount that F(50,10) is higher than F(50,50) at that point.  Thus a new set of curves for F(50,90) was
// constructed based on the other curve sets, since the digitized points are common between the two.  Note this means
// the F(50,90) set is defined only where F(50,50) and F(50,10) overlap, when off the F(50,90) curves on the low-
// distance side, this reverts to F(50,50).


// This module can be built for use outside the tvstudy engine, where all the baggage in tvstudy.h is undesireable.  If
// the symbol __BUILD_TVSTUDY is defined this is being built for the engine.  Aside from header differences, that also
// changes the function declaration and adds calls to vpat_lookup() to adjust the lookups by an elevation pattern.

#ifdef __BUILD_TVSTUDY
#include "tvstudy.h"
#else
#include <math.h>

#include "global.h"
#include "fcc_curve.h"
#endif


//---------------------------------------------------------------------------------------------------------------------
// The curve interpolation routine can return field given HAAT and distance; it cannot return distance given HAAT and
// field.  For that, an iterative routine is used, repeatedly calling the interpolation routine for a range of
// distances until two distances sufficiently close together that bracket the desired field are found, then using
// linear interpolation.  The following parameters control this routine:

//   STEP   Initial step size in kilometers, used to generate the range of distances looked up on the first iteration.
//   DSTEP  Amount by which step is divided for each subsequent iteration to increase the resolution.
//   NPAS   Number of iterations to perform.  This determines the final distance resolution at which linear
//            interpolation to the answer is used.  This minimum resolution is: STEP/(DSTEP^(NPAS-1))
//   MSTEP  The maximum number of points that would ever be looked up in a single iteration; this is based on the
//            maximum distance ranges for the curve families and on the parameters above.  For example, if a curve
//            family extends from 15 to 500 km, and STEP is 50, a total of 10 points will be looked up on the first
//            iteration.  In this case, MSTEP should be at least 11 (10, plus one for safety).  Of course, MSTEP must
//            never be less than (int)DSTEP+1, which is the maximum number of points that will be looked up on each
//            iteration after the first.

// Warning!  The current settings of these parameters have been chosen to exactly match the results given by the FCC
// CURVES program.  Do not change these parameters without studying that program first!

// The point counts and curve mininum and maximum distance limits are also defined as constants below.  Note the
// distance limits do not match the first and last points in the tables; the interpolation routine will extrapolate
// if needed.  The curves were originally digitized with the table points in english units (feet and miles), and were
// then directly converted to metric units (meters and kilometers) causing the points to be at irregular values.

#define STEP 81.25   // Initial distance step for iterative lookup.
#define DSTEP 10.    // Delta divisor for step size in iteration.
#define NPAS 3       // Max number of iterative passes.
#define MSTEP 11     // Max number of lookup points in one iteration.

#define ND50 25   // Number of distances in curve data tables for F(50,50).
#define ND10 30   // Number of distances in tables for F(50,10).
#define ND90 20   // Number of distances in tables for F(50,90).
#define NHGT 13   // Number of heights in all tables.

#define D50MN 1.5    // Minimum distance for F(50,50) curves.
#define D50MX 300.   // Maximum distance for F(50,50) curves.
#define D10MN 15.    // Minimum distance for F(50,10) curves.
#define D10MX 500.   // Maximum distance for F(50,10) curves.
#define D90MN 15.    // Minimum distance for F(50,90) curves.
#define D90MX 300.   // Maximum distance for F(50,90) curves.
#define HMNT 30.5    // Minimum height for all curves for TV.
#define HMX 1600.    // Maximum height for all curves.

static void fcc_itplbv(int, int, double *, double *, double *, int, double *, double *, double *);


//---------------------------------------------------------------------------------------------------------------------
// The main curve look-up function.

// Arguments:

//   power     Effective radiated power in dBk.
//   field     Field intensity in dBu.
//   dist      Distance in kilometers.
//   height    Antenna height above average terrain in meters.
//   iband     Channel band, BAND_*.  TV VHF low and FM are synonymous, likewise TV UHF and wireless.
//   mode1     Computation mode:
//               FCC_DST  Compute distance given field and power.
//               FCC_FLD  Compute field given distance and power.
//               FCC_PWR  Compute power given field and distance.
//   mode2     Curve set:
//               FCC_F50  F(50,50) curves.
//               FCC_F10  F(50,10) curves.
//               FCC_F90  F(50,90) curves.
//   mode3     Method for calculation when off the curves on the near distance end:
//               OFF_CURV_METH_FS        Straight free-space (discontinuity from curve end).
//               OFF_CURV_METH_FS_SCALE  Free-space scaled to match last curve value (no discontinuity).
//               OFF_CURV_METH_NONE      No calculation just hold last curve value.
//   source    Source, needed to pass to vpat_lookup() for possible elevation pattern adjustment.
//   azm       Azimuth, also needed to pass to vpat_lookup() in case it is needed for mechanical tilt.
//   dep       If non-NULL return depression angle, lookup angle, and vertical pattern values, for debugging.
//   lup
//   vpt

// Return value is 0 for normal error-free result else a status/error code, as follows:

//   FCC_HGTLO  Height less than 30 meters, 30 used (30.5 for TV).
//   FCC_HGTHI  Height greater than 1600 meters, 1600 used.
//   FCC_DSTFS  Distance too small, field computed by free space.
//   FCC_FLDFS  Field too large, distance computed by free space.
//   FCC_DST50  Distance too small for F(50,10/90), used F(50,50).
//   FCC_FLD50  Field too large for F(50,10/90), used F(50,50).
//   FCC_DSTHI  Distance too large, maximum distance used.
//   FCC_FLDLO  Field too small, returned maximum distance.

#ifdef __BUILD_TVSTUDY
int fcc_curve(double *power, double *field, double *dist, double height, int iband, int mode1, int mode2, int mode3,
		SOURCE *source, double azm, double *dep, double *lup, double *vpt) {
#else
int fcc_curve(double *power, double *field, double *dist, double height, int iband, int mode1, int mode2, int mode3) {
#endif

	// Digitized curve data.  First are distance point arrays for each of the three sets F(50,50), F(50,10), and
	// F(50,90), followed by the height point array which is the same for all.

	static double d50[ND50] = {
		  1.609344,   3.218688,   4.828032,   6.437376,   8.046721,
		 16.093441,  32.186882,  48.280319,  64.373764,  80.467201,  96.560638, 112.654083, 128.747528, 144.840958, 160.934402, 177.027847, 193.121277, 209.214722, 225.308167, 241.401596, 257.495056, 273.588470, 289.681915, 305.775360, 321.868805
	};
	static double d10[ND10] = {
		 16.093441,  32.186882,  48.280319,  64.373764,  80.467201,  96.560638, 112.654083, 128.747528, 144.840958, 160.934402, 177.027847, 193.121277, 209.214722, 225.308167, 241.401596, 257.495056, 273.588470, 289.681915, 305.775360, 321.868805,
		337.962250, 354.055695, 370.149109, 386.242554, 402.335999, 418.429443, 434.522888, 450.616333, 466.709747, 482.803192
	};
	static double d90[ND90] = {
		 16.093441,  32.186882,  48.280319,  64.373764,  80.467201,  96.560638, 112.654083, 128.747528, 144.840958,	160.934402, 177.027847, 193.121277, 209.214722, 225.308167, 241.401596, 257.495056, 273.588470, 289.681915, 305.775360, 321.868805
	};
	static double hgt[NHGT] = {
		  30.48,
		  60.96,
		 121.92,
		 182.88,
		 243.84,
		 304.80,
		 381.00,
		 457.20,
		 533.40,
		 609.60,
		 914.40,
		1219.20,
		1524.00
	};

	// Next are the field strength curves themselves.  First is the set for use at low VHF frequencies, with one table
	// for F(50,50), one for F(50,10), and one for F(50,90).  In the arrays, the data is stored row-major with heights
	// numbering the rows and distances the columns.

	static double f55lv[NHGT * ND50] = {
		 92.0,  79.7,  72.7,  67.8,  64.0,  52.0,  39.4,  31.0,  25.3,  20.3,  16.2,  12.8,   9.8,   6.9,   4.0,   1.5,  -1.1,  -3.6,  -5.8,  -8.1, -10.6, -13.0, -15.1, -17.2, -19.2,
		 98.0,  85.9,  79.0,  73.8,  70.0,  58.0,  45.5,  37.0,  29.5,  23.5,  18.1,  14.5,  11.0,   8.2,   5.5,   2.9,   0.3,  -2.2,  -4.8,  -7.0,  -9.4, -11.7, -14.0, -16.1, -18.3,
		100.6,  91.0,  84.8,  80.0,  76.0,  64.0,  51.5,  43.0,  35.5,  28.8,  22.0,  17.1,  13.4,  10.2,   7.4,   4.8,   2.2,  -0.3,  -3.0,  -5.2,  -7.6, -10.0, -12.2, -14.6, -16.9,
		101.5,  93.4,  87.8,  83.3,  79.6,  67.6,  55.0,  46.7,  39.0,  32.0,  25.3,  19.8,  15.2,  11.8,   8.9,   6.0,   3.7,   1.0,  -1.4,  -3.9,  -6.1,  -8.7, -11.0, -13.2, -15.6,
		101.9,  94.6,  89.4,  85.4,  82.0,  70.0,  57.6,  49.0,  41.5,  34.4,  27.7,  22.0,  17.0,  13.1,  10.1,   7.2,   4.8,   2.0,  -0.3,  -2.7,  -5.1,  -7.6, -10.0, -12.1, -14.6,
		102.0,  95.0,  90.4,  86.8,  83.7,  72.0,  59.6,  51.0,  43.6,  36.7,  29.9,  23.9,  18.8,  14.7,  11.5,   8.4,   5.7,   3.0,   0.6,  -1.8,  -4.2,  -6.6,  -9.0, -11.2, -13.6,
		102.1,  95.6,  91.2,  87.7,  85.0,  73.9,  61.7,  53.2,  45.9,  39.1,  32.0,  26.0,  21.0,  16.8,  13.1,   9.9,   7.0,   4.1,   1.7,  -0.7,  -3.2,  -5.6,  -8.0, -10.2, -12.5,
		102.2,  95.9,  91.8,  88.3,  85.8,  75.4,  63.3,  55.1,  47.9,  41.5,  34.4,  28.3,  23.2,  18.8,  14.9,  11.1,   8.0,   5.2,   2.7,   0.2,  -2.2,  -4.6,  -7.0,  -9.2, -11.6,
		102.3,  96.0,  92.0,  88.9,  86.3,  76.7,  64.9,  57.0,  50.0,  43.5,  36.7,  30.7,  25.2,  20.4,  16.0,  12.5,   9.1,   6.2,   3.8,   1.1,  -1.3,  -3.6,  -6.1,  -8.4, -10.6,
		102.4,  96.1,  92.2,  89.2,  86.7,  77.9,  66.2,  58.5,  51.5,  45.0,  38.2,  32.4,  27.0,  22.0,  17.3,  13.7,  10.1,   7.1,   4.6,   2.0,  -0.4,  -2.7,  -5.1,  -7.6, -10.0,
		102.5,  96.3,  92.5,  89.9,  87.6,  80.2,  70.0,  62.6,  55.4,  48.9,  42.5,  36.9,  31.0,  25.7,  21.0,  17.1,  13.6,  10.3,   7.8,   5.1,   2.8,   0.5,  -2.1,  -4.5,  -6.8,
		102.5,  96.5,  92.5,  90.1,  88.0,  81.3,  72.4,  65.0,  57.8,  51.2,  44.9,  39.1,  33.2,  28.1,  23.5,  19.8,  16.1,  13.0,  10.4,   8.0,   5.5,   3.1,   0.6,  -2.0,  -4.1,
		102.5,  96.5,  92.5,  90.2,  88.1,  81.9,  74.2,  66.5,  59.6,  53.0,  46.4,  40.8,  35.0,  30.0,  25.5,  21.8,  18.3,  15.0,  12.4,  10.0,   7.7,   5.1,   2.8,   0.2,  -2.0
	};
	static double f51lv[NHGT * ND10] = {
		 52.2,  41.4,  36.4,  33.0,  30.0,  26.7,  23.5,  20.4,  17.4,  14.5,  11.5,   8.5,   5.9,   3.0,   0.6,  -2.0,  -4.3,  -6.6,  -8.7, -10.5, -12.5, -14.6, -16.6, -18.6, -20.5, -22.4, -24.3, -26.2, -28.1, -30.0,
		 58.4,  47.0,  40.9,  36.0,  31.9,  28.0,  24.9,  22.0,  19.0,  16.1,  13.1,  10.1,   7.7,   4.9,   2.0,  -0.4,  -3.0,  -5.1,  -7.4,  -9.4, -11.4, -13.4, -15.5, -17.4, -19.3, -21.2, -23.2, -25.0, -27.0, -29.0,
		 64.3,  53.0,  45.9,  39.9,  35.0,  30.5,  26.9,  24.0,  20.9,  18.2,  15.3,  12.4,   9.8,   6.9,   4.1,   1.6,  -1.0,  -3.4,  -5.8,  -8.0, -10.1, -12.0, -14.1, -16.0, -18.0, -19.9, -21.9, -23.7, -25.6, -27.4,
		 68.0,  56.5,  49.0,  43.0,  37.7,  32.8,  28.8,  25.6,  22.5,  19.8,  16.9,  13.9,  11.0,   8.2,   5.7,   2.9,   0.3,  -2.2,  -4.6,  -6.9,  -9.0, -11.0, -13.0, -15.0, -17.0, -18.9, -20.9, -22.5, -24.6, -26.3,
		 70.5,  59.0,  51.7,  45.4,  40.0,  34.9,  30.4,  27.0,  23.9,  21.0,  18.2,  15.1,  12.3,   9.7,   6.9,   4.1,   1.6,  -1.0,  -3.4,  -5.7,  -8.0, -10.0, -12.0, -14.0, -16.0, -17.9, -19.9, -21.7, -23.6, -25.4,
		 72.3,  60.9,  53.7,  47.5,  41.9,  36.8,  32.0,  28.4,  25.0,  22.0,  19.2,  16.2,  13.4,  10.7,   8.0,   5.3,   2.7,   0.0,  -2.5,  -4.9,  -7.0,  -9.0, -11.2, -13.2, -15.1, -17.0, -19.0, -21.0, -23.0, -24.6,
		 74.2,  63.0,  56.0,  50.0,  44.4,  39.2,  34.9,  30.8,  27.0,  23.9,  20.8,  17.8,  14.8,  12.0,   9.1,   6.7,   3.9,   1.1,  -1.4,  -3.9,  -6.0,  -8.0, -10.2, -12.2, -14.2, -16.2, -18.1, -20.0, -22.0, -23.7,
		 75.9,  64.8,  57.9,  52.0,  46.7,  41.6,  37.1,  33.0,  29.0,  25.5,  22.0,  19.0,  16.0,  13.2,  10.3,   7.9,   5.0,   2.2,  -0.2,  -2.8,  -5.0,  -7.0,  -9.2, -11.3, -13.3, -15.3, -17.2, -19.2, -21.1, -22.8,
		 77.0,  66.2,  59.6,  54.0,  48.5,  43.5,  39.2,  35.0,  30.8,  26.9,  23.2,  20.0,  17.1,  14.2,  11.6,   9.0,   6.0,   3.3,   0.9,  -1.8,  -4.0,  -6.2,  -8.2, -10.5, -12.5, -14.6, -16.3, -18.4, -20.2, -22.0,
		 78.2,  67.6,  60.9,  55.2,  50.0,  45.0,  40.7,  36.2,  32.0,  28.0,  24.1,  21.0,  18.0,  15.3,  12.5,  10.0,   7.0,   4.4,   1.8,  -0.8,  -3.0,  -5.3,  -7.4,  -9.8, -11.8, -14.0, -15.8, -17.8, -19.6, -21.3,
		 80.8,  71.2,  64.5,  58.9,  53.9,  49.0,  44.2,  39.8,  35.4,  31.3,  27.6,  24.4,  21.6,  18.9,  16.0,  13.6,  10.7,   8.0,   5.2,   2.8,   0.3,  -2.0,  -4.5,  -7.0,  -9.0, -11.1, -13.2, -15.0, -17.0, -19.0,
		 81.8,  73.8,  67.0,  61.4,  56.3,  51.7,  46.9,  42.0,  37.8,  33.8,  30.0,  27.0,  24.1,  21.5,  18.8,  16.1,  13.6,  10.9,   8.1,   5.3,   3.0,   0.4,  -1.9,  -4.3,  -6.7,  -9.0, -11.0, -12.9, -14.9, -16.9,
		 82.2,  75.5,  69.0,  63.3,  58.4,  53.5,  48.8,  44.0,  39.7,  35.7,  32.1,  29.1,  26.1,  23.5,  20.9,  18.0,  15.7,  13.0,  10.2,   7.5,   5.0,   2.6,   0.0,  -2.4,  -4.6,  -6.9,  -9.0, -11.0, -13.0, -15.0
	};
	static double f59lv[NHGT * ND90] = {
		 51.8,  37.4,  25.6,  17.6,  10.6,   5.7,   2.1,  -0.8,  -3.6,  -6.5,  -8.5, -10.7, -13.1, -14.6, -16.8, -19.2, -21.7, -23.6, -25.7, -27.9,
		 57.6,  44.0,  33.1,  23.0,  15.1,   8.2,   4.1,   0.0,  -2.6,  -5.1,  -7.3,  -9.5, -12.1, -14.5, -16.0, -18.4, -20.4, -22.9, -24.8, -27.2,
		 63.7,  50.0,  40.1,  31.1,  22.6,  13.5,   7.3,   2.8,  -0.5,  -3.4,  -5.7,  -8.0, -10.4, -12.9, -14.5, -16.8, -19.0, -21.0, -23.4, -25.8,
		 67.2,  53.5,  44.4,  35.0,  26.3,  17.8,  10.8,   4.8,   1.1,  -2.0,  -4.9,  -6.5,  -9.0, -11.0, -13.5, -15.1, -17.7, -19.8, -21.8, -24.3,
		 69.5,  56.2,  46.3,  37.6,  28.8,  20.5,  13.6,   7.0,   2.3,  -0.8,  -3.8,  -5.5,  -8.3, -10.3, -12.3, -14.3, -16.8, -19.0, -20.8, -23.5,
		 71.7,  58.3,  48.3,  39.7,  31.5,  23.0,  15.8,   9.2,   4.4,   1.0,  -2.4,  -4.8,  -7.4,  -9.5, -11.6, -13.7, -15.9, -18.0, -19.9, -22.3,
		 73.6,  60.4,  50.4,  41.8,  33.8,  24.8,  17.1,  11.2,   6.6,   2.3,  -1.0,  -3.8,  -6.6,  -8.6, -10.5, -13.1, -15.1, -17.1, -19.0, -21.1,
		 74.9,  61.8,  52.3,  43.8,  36.3,  27.2,  19.5,  13.4,   8.6,   4.3,   0.2,  -3.0,  -5.6,  -7.8,  -9.9, -12.3, -14.2, -16.2, -18.2, -20.4,
		 76.4,  63.6,  54.4,  46.0,  38.5,  29.9,  22.2,  15.4,  10.0,   5.1,   1.8,  -1.8,  -4.7,  -6.6,  -9.4, -11.6, -13.2, -15.5, -17.7, -19.4,
		 77.6,  64.8,  56.1,  47.8,  40.0,  31.4,  24.1,  17.8,  12.0,   6.6,   3.3,  -0.8,  -3.8,  -6.1,  -8.5, -10.8, -12.4, -14.6, -17.0, -19.2,
		 79.6,  68.8,  60.7,  51.9,  43.9,  36.0,  29.6,  22.2,  16.0,  10.7,   6.6,   2.8,  -1.0,  -3.3,  -5.8,  -8.0,  -9.7, -12.2, -14.2, -16.4,
		 80.8,  71.0,  63.0,  54.2,  46.1,  38.1,  31.3,  24.4,  18.4,  13.2,   9.6,   5.2,   1.9,  -0.7,  -2.8,  -5.1,  -7.4,  -9.7, -12.1, -13.5,
		 81.6,  72.9,  64.0,  55.9,  47.6,  39.3,  32.8,  26.0,  20.3,  15.3,  11.5,   7.5,   3.9,   1.3,  -0.9,  -2.6,  -5.5,  -7.4,  -9.8, -11.5
	};

	// Next is the set of three tables for use at high VHF frequencies.

	static double f55hv[NHGT * ND50] = {
		 94.6,  82.8,  75.7,  70.7,  66.8,  55.0,  42.5,  34.0,  26.3,  20.7,  16.3,  12.9,   9.9,   7.0,   4.3,   1.5,  -1.0,  -3.5,  -5.7,  -8.0, -10.4, -12.8, -15.0, -17.2, -19.1,
		100.7,  88.9,  81.8,  76.9,  73.0,  61.0,  48.6,  40.0,  32.0,  24.1,  18.5,  14.4,  11.2,   8.3,   5.5,   2.9,   0.5,  -2.0,  -4.3,  -6.9,  -9.2, -11.5, -13.8, -16.0, -18.2,
		101.6,  92.3,  86.6,  82.2,  78.8,  67.2,  54.7,  46.1,  38.1,  30.1,  23.0,  17.0,  13.5,  10.5,   7.5,   4.8,   2.3,  -0.3,  -2.7,  -5.0,  -7.3,  -9.8, -12.0, -14.4, -16.8,
		101.8,  93.9,  88.7,  84.8,  81.6,  70.8,  58.1,  49.8,  41.7,  33.8,  26.2,  20.0,  15.2,  12.0,   9.0,   6.2,   3.7,   1.0,  -1.2,  -3.7,  -6.0,  -8.4, -10.7, -13.0, -15.5,
		101.9,  94.6,  89.8,  86.2,  83.2,  73.2,  60.7,  52.1,  44.0,  36.1,  28.8,  22.1,  17.0,  13.7,  10.4,   7.5,   4.8,   2.2,  -0.1,  -2.5,  -4.9,  -7.3,  -9.7, -12.0, -14.4,
		102.0,  95.0,  90.5,  87.0,  84.5,  75.0,  62.5,  54.2,  46.0,  38.0,  30.6,  24.0,  18.9,  15.0,  11.5,   8.6,   5.8,   3.2,   0.9,  -1.5,  -4.0,  -6.3,  -8.7, -11.0, -13.4,
		102.3,  95.4,  91.3,  88.0,  85.7,  77.0,  65.0,  56.7,  48.8,  40.9,  33.5,  26.8,  21.2,  17.0,  13.1,  10.0,   7.0,   4.4,   2.0,  -0.5,  -3.0,  -5.3,  -7.6, -10.0, -12.3,
		102.3,  95.7,  91.8,  88.7,  86.3,  78.1,  67.6,  59.0,  51.0,  43.5,  36.3,  29.6,  23.9,  19.0,  14.9,  11.2,   8.2,   5.5,   3.0,   0.6,  -2.0,  -4.3,  -6.6,  -9.0, -11.3,
		102.3,  95.9,  92.0,  89.1,  87.0,  79.1,  69.5,  61.0,  53.3,  46.0,  39.0,  32.0,  26.0,  21.0,  16.2,  12.7,   9.5,   6.5,   4.0,   1.5,  -1.0,  -3.5,  -5.8,  -8.2, -10.5,
		102.4,  96.0,  92.1,  89.5,  87.3,  80.0,  71.0,  62.8,  55.0,  47.9,  41.0,  34.0,  28.0,  22.6,  17.5,  13.6,  10.5,   7.4,   4.9,   2.2,  -0.2,  -2.6,  -5.0,  -7.3,  -9.8,
		102.4,  96.2,  92.6,  90.0,  88.0,  81.1,  73.9,  66.3,  58.7,  52.0,  45.0,  38.2,  32.0,  26.3,  21.1,  17.0,  14.0,  10.7,   8.0,   5.6,   3.0,   0.6,  -1.8,  -4.2,  -6.6,
		102.4,  96.2,  92.6,  90.0,  88.0,  81.8,  74.8,  67.4,  60.3,  53.8,  47.0,  40.6,  34.4,  28.8,  23.8,  19.8,  16.6,  13.1,  10.4,   8.2,   5.5,   3.1,   0.9,  -1.8,  -4.0,
		102.5,  96.5,  92.7,  90.1,  88.0,  82.0,  75.0,  68.0,  61.1,  54.6,  48.1,  42.0,  36.1,  30.6,  25.5,  21.8,  18.5,  15.1,  12.3,  10.1,   7.5,   5.1,   2.9,   0.3,  -1.9
	};
	static double f51hv[NHGT * ND10] = {
		 55.4,  44.4,  39.2,  34.0,  29.9,  26.6,  23.5,  20.3,  17.4,  14.3,  11.3,   8.6,   5.8,   2.9,   0.3,  -2.1,  -4.4,  -6.7,  -8.9, -10.8, -12.9, -14.8, -16.9, -18.8, -20.7, -22.7, -24.6, -26.4, -28.2, -30.1,
		 61.6,  50.0,  43.5,  38.0,  32.5,  28.2,  25.0,  22.0,  19.0,  16.0,  13.0,  10.0,   7.2,   4.7,   1.9,  -0.7,  -3.2,  -5.4,  -7.8,  -9.8, -11.8, -13.8, -15.8, -17.7, -19.7, -21.4, -23.3, -25.2, -27.1, -29.0,
		 67.7,  55.8,  48.6,  42.7,  35.9,  31.0,  27.0,  24.0,  21.0,  18.1,  15.1,  12.2,   9.4,   6.8,   3.8,   1.2,  -1.4,  -3.8,  -6.1,  -8.2, -10.3, -12.3, -14.3, -16.3, -18.3, -20.1, -22.0, -24.0, -25.9, -27.7,
		 71.0,  59.1,  52.0,  45.6,  38.8,  33.4,  28.9,  25.5,  22.4,  19.6,  16.7,  13.7,  10.8,   8.1,   5.2,   2.7,   0.0,  -2.3,  -4.8,  -7.0,  -9.0, -11.1, -13.1, -15.1, -17.0, -19.0, -20.9, -22.9, -24.8, -26.5,
		 73.5,  61.7,  54.6,  48.0,  41.0,  35.4,  30.7,  27.0,  23.8,  20.8,  18.0,  15.0,  12.0,   9.5,   6.5,   3.9,   1.2,  -1.2,  -3.8,  -6.0,  -8.2, -10.2, -12.2, -14.2, -16.2, -18.0, -20.0, -21.9, -23.9, -25.5,
		 75.3,  63.7,  56.5,  50.0,  43.0,  37.4,  32.3,  28.3,  25.0,  22.0,  19.1,  16.3,  13.3,  10.6,   7.8,   5.0,   2.4,   0.0,  -2.6,  -5.0,  -7.1,  -9.3, -11.2, -13.3, -15.3, -17.2, -19.1, -21.0, -23.0, -24.9,
		 77.1,  66.5,  59.0,  52.5,  45.8,  40.0,  35.0,  30.4,  26.9,  23.5,  20.5,  17.6,  14.7,  12.0,   9.0,   6.4,   3.7,   1.0,  -1.4,  -4.0,  -6.0,  -8.2, -10.2, -12.3, -14.3, -16.2, -18.2, -20.0, -22.0, -23.9,
		 78.6,  68.9,  61.5,  54.9,  48.2,  43.0,  37.4,  32.9,  28.8,  25.0,  22.0,  18.8,  15.9,  13.0,  10.3,   7.5,   4.9,   2.1,  -0.3,  -3.0,  -5.1,  -7.4,  -9.4, -11.4, -13.5, -15.4, -17.4, -19.2, -21.1, -23.0,
		 79.6,  70.8,  63.6,  56.9,  50.8,  45.4,  40.0,  35.0,  30.4,  26.4,  23.0,  19.9,  17.0,  14.1,  11.5,   8.8,   6.0,   3.3,   0.8,  -2.0,  -4.2,  -6.5,  -8.6, -10.6, -12.8, -14.8, -16.8, -18.5, -20.3, -22.1,
		 80.4,  72.0,  65.2,  58.8,  53.0,  47.6,  42.0,  36.8,  32.0,  27.7,  24.0,  20.7,  18.0,  15.2,  12.5,   9.8,   7.0,   4.3,   1.7,  -1.0,  -3.3,  -5.6,  -7.8,  -9.8, -12.0, -14.0, -16.0, -18.0, -19.6, -21.5,
		 82.0,  75.0,  68.6,  62.5,  57.0,  52.0,  46.8,  41.5,  35.8,  31.0,  27.6,  24.0,  21.4,  18.8,  16.0,  13.1,  10.6,   7.9,   5.0,   2.5,   0.0,  -2.4,  -4.7,  -6.9,  -9.0, -11.1, -13.1, -15.1, -17.0, -19.0,
		 82.4,  75.9,  69.8,  64.0,  58.9,  53.8,  48.9,  43.7,  38.2,  33.6,  30.0,  26.8,  24.0,  21.2,  18.7,  15.9,  13.2,  10.6,   8.0,   5.2,   2.8,   0.2,  -2.0,  -4.3,  -6.5,  -9.0, -11.0, -13.0, -15.0, -16.8,
		 82.5,  76.2,  70.2,  64.9,  59.8,  54.8,  50.0,  45.0,  40.1,  35.5,  32.0,  28.9,  26.0,  23.4,  20.7,  18.0,  15.4,  12.8,  10.0,   7.3,   4.9,   2.2,  -0.1,  -2.4,  -4.7,  -7.0,  -9.0, -11.0, -13.0, -15.0
	};
	static double f59hv[NHGT * ND90] = {
		 54.6,  40.6,  28.8,  18.6,  11.5,   6.0,   2.3,  -0.5,  -3.4,  -5.7,  -8.3, -10.6, -12.8, -14.3, -16.3, -18.7, -21.2, -23.3, -25.5, -27.4,
		 60.4,  47.2,  36.5,  26.0,  15.7,   8.8,   3.8,   0.4,  -2.4,  -5.0,  -7.2,  -9.0, -11.2, -13.3, -15.7, -17.7, -19.8, -22.2, -24.2, -26.6,
		 66.7,  53.6,  43.6,  33.5,  24.3,  15.0,   7.0,   3.0,   0.0,  -3.1,  -5.5,  -7.6, -10.0, -12.2, -13.8, -15.8, -18.2, -20.2, -22.7, -25.4,
		 70.6,  57.1,  47.6,  37.8,  28.8,  19.0,  11.1,   4.9,   1.6,  -1.6,  -4.3,  -6.3,  -8.8, -10.5, -12.6, -14.7, -16.8, -19.1, -21.2, -24.0,
		 72.9,  59.7,  49.6,  40.0,  31.2,  22.2,  13.5,   7.0,   3.6,   0.0,  -3.0,  -5.4,  -7.6,  -9.7, -11.5, -13.7, -15.8, -18.2, -20.2, -22.8,
		 74.7,  61.3,  51.9,  42.0,  33.0,  23.8,  15.7,   9.5,   5.0,   1.0,  -1.9,  -4.7,  -6.9,  -8.8, -10.8, -13.0, -15.0, -17.4, -19.4, -21.8,
		 76.9,  63.5,  54.4,  45.1,  36.0,  27.0,  18.6,  12.0,   7.1,   2.7,  -0.5,  -3.6,  -5.9,  -8.0, -10.0, -12.4, -14.3, -16.2, -18.6, -20.6,
		 77.6,  66.3,  56.5,  47.1,  38.8,  29.6,  21.8,  14.9,   9.2,   4.8,   0.4,  -2.4,  -4.9,  -7.0,  -9.1, -11.5, -13.5, -15.3, -17.7, -19.6,
		 78.6,  68.2,  58.4,  49.7,  41.2,  32.6,  24.0,  17.0,  11.6,   6.0,   2.4,  -0.9,  -4.0,  -6.1,  -8.5, -10.8, -13.0, -14.9, -17.2, -19.0,
		 79.6,  70.0,  60.4,  51.2,  42.8,  34.4,  26.0,  19.2,  13.2,   7.3,   3.2,   0.3,  -3.2,  -5.4,  -8.1, -10.2, -12.2, -14.3, -16.3, -18.6,
		 80.2,  72.8,  64.0,  54.9,  47.0,  38.0,  29.6,  22.5,  16.8,  11.2,   6.4,   4.0,   0.0,  -2.8,  -4.8,  -7.1,  -9.4, -11.5, -13.4, -15.7,
		 81.2,  73.7,  65.0,  56.6,  48.7,  40.2,  32.3,  25.1,  19.4,  14.0,   9.6,   6.4,   2.2,  -0.4,  -2.3,  -4.9,  -7.0,  -8.8, -11.6, -13.2,
		 81.5,  73.8,  65.8,  57.3,  49.4,  41.4,  34.0,  27.2,  21.1,  15.5,  11.6,   8.1,   4.2,   1.2,  -0.5,  -3.0,  -5.2,  -7.0,  -9.4, -11.1
	};

	// And last, three tables for use at UHF frequencies.

	static double f55u[NHGT * ND50] = {
		 92.0,  80.0,  72.9,  67.9,  63.8,  51.9,  39.0,  27.5,  17.8,  13.0,  10.1,   7.0,   4.2,   1.6,  -1.0,  -3.2,  -5.0,  -7.2,  -9.1, -11.0, -13.1, -15.1, -17.2, -19.3, -21.4,
		 97.9,  86.0,  79.0,  74.0,  70.0,  58.0,  45.2,  33.5,  22.7,  16.0,  11.7,   8.5,   5.5,   2.8,   0.2,  -2.0,  -4.2,  -6.3,  -8.4, -10.3, -12.3, -14.2, -16.2, -18.3, -20.1,
		100.7,  91.0,  84.7,  80.0,  76.0,  64.0,  51.2,  39.6,  28.2,  19.6,  14.4,  10.8,   7.7,   4.7,   1.9,  -0.4,  -2.7,  -4.9,  -7.0,  -8.9, -10.9, -12.8, -14.8, -16.8, -18.7,
		101.5,  93.0,  87.4,  83.3,  79.5,  67.6,  54.6,  43.0,  31.5,  22.3,  16.8,  12.5,   9.3,   6.0,   3.2,   0.7,  -1.5,  -3.8,  -5.9,  -7.9,  -9.9, -11.7, -13.8, -15.8, -17.7,
		101.9,  94.1,  89.0,  85.1,  81.5,  70.0,  57.2,  45.7,  34.5,  25.1,  19.1,  14.2,  10.8,   7.5,   4.6,   1.9,  -0.4,  -2.9,  -5.0,  -7.0,  -9.0, -10.8, -12.8, -14.8, -16.8,
		102.0,  94.8,  90.0,  86.3,  82.9,  72.0,  59.1,  48.0,  37.3,  28.3,  21.7,  16.3,  12.4,   8.9,   5.7,   3.0,   0.5,  -2.0,  -4.2,  -6.1,  -8.0, -10.0, -11.9, -13.9, -15.9,
		102.1,  95.2,  90.8,  87.3,  84.1,  73.8,  61.0,  50.5,  40.3,  31.8,  24.7,  19.0,  14.5,  10.6,   7.1,   4.3,   1.7,  -0.9,  -3.2,  -5.2,  -7.1,  -9.0, -11.0, -13.0, -15.0,
		102.2,  95.6,  91.3,  88.0,  85.0,  75.3,  62.6,  52.3,  42.7,  34.1,  27.0,  21.3,  16.3,  12.0,   8.5,   5.6,   2.8,   0.0,  -2.3,  -4.3,  -6.2,  -8.2, -10.2, -12.2, -14.1,
		102.3,  95.9,  91.8,  88.6,  85.8,  76.5,  64.0,  53.9,  44.3,  36.0,  29.3,  23.4,  18.0,  13.6,   9.7,   6.7,   3.8,   1.0,  -1.6,  -3.6,  -5.5,  -7.5,  -9.5, -11.4, -13.2,
		102.4,  96.0,  92.0,  88.9,  86.2,  77.2,  65.0,  55.0,  45.7,  37.6,  31.0,  25.0,  19.8,  15.0,  10.8,   7.7,   4.8,   1.9,  -0.9,  -3.0,  -4.8,  -6.8,  -8.9, -10.8, -12.5,
		102.5,  96.3,  92.5,  89.6,  87.3,  79.6,  68.2,  58.4,  49.4,  41.7,  35.4,  29.8,  24.5,  19.8,  15.0,  11.5,   8.2,   5.0,   2.0,  -0.2,  -2.2,  -4.3,  -6.3,  -8.3, -10.0,
		102.5,  96.5,  92.8,  90.0,  87.9,  80.5,  70.0,  60.8,  52.1,  44.8,  38.6,  33.0,  28.0,  23.4,  18.8,  14.8,  11.1,   7.8,   4.6,   1.9,  -0.1,  -2.2,  -4.2,  -6.1,  -8.0,
		102.5,  96.5,  93.0,  90.3,  88.1,  81.0,  71.1,  62.5,  54.0,  46.7,  41.0,  35.7,  30.8,  26.0,  21.8,  17.5,  13.7,  10.0,   6.7,   3.7,   1.7,  -0.4,  -2.3,  -4.4,  -6.3
	};
	static double f51u[NHGT * ND10] = {
		 52.2,  41.6,  35.0,  30.3,  27.0,  23.8,  20.8,  17.8,  14.8,  12.0,   9.2,   6.6,   4.0,   1.2,  -1.3,  -3.8,  -6.0,  -8.4, -10.3, -12.5, -14.5, -16.5, -18.5, -20.5, -22.4, -24.2, -26.0, -27.8, -29.5, -31.0,
		 58.3,  46.7,  38.0,  32.1,  28.3,  25.2,  22.2,  19.3,  16.5,  13.4,  10.7,   8.0,   5.1,   2.5,  -0.2,  -2.4,  -4.9,  -7.2,  -9.3, -11.3, -13.5, -15.5, -17.4, -19.3, -21.3, -23.2, -25.0, -27.0, -28.5, -30.1,
		 64.7,  52.4,  43.0,  35.3,  30.8,  27.6,  24.5,  21.3,  18.5,  15.6,  12.7,   9.9,   7.1,   4.4,   1.8,  -0.8,  -3.1,  -5.5,  -7.7,  -9.8, -12.0, -14.0, -15.9, -17.8, -19.8, -21.6, -23.4, -25.5, -27.1, -28.9,
		 68.0,  56.0,  46.3,  37.6,  32.6,  29.1,  26.0,  23.0,  20.0,  17.1,  14.0,  11.2,   8.8,   6.0,   3.2,   0.8,  -1.7,  -4.1,  -6.2,  -8.4, -10.4, -12.7, -14.6, -16.5, -18.6, -20.4, -22.2, -24.2, -26.0, -27.9,
		 70.5,  58.5,  48.8,  40.0,  34.7,  30.4,  27.2,  24.2,  21.2,  18.3,  15.2,  12.6,  10.0,   7.3,   4.6,   1.9,  -0.5,  -3.0,  -5.2,  -7.4,  -9.6, -11.7, -13.8, -15.6, -17.7, -19.6, -21.3, -23.3, -25.0, -27.0,
		 72.3,  60.3,  50.8,  42.4,  36.7,  32.0,  28.4,  25.4,  22.4,  19.7,  16.5,  13.8,  11.0,   8.3,   5.7,   3.0,   0.6,  -2.0,  -4.3,  -6.6,  -8.8, -10.8, -13.0, -14.9, -17.0, -18.9, -20.8, -22.7, -24.4, -26.3,
		 74.1,  62.3,  52.9,  45.1,  39.0,  34.5,  30.4,  27.0,  23.9,  21.0,  18.0,  15.3,  12.5,   9.7,   7.0,   4.4,   1.8,  -0.7,  -3.2,  -5.4,  -7.7,  -9.8, -12.0, -14.0, -16.0, -17.9, -19.9, -21.8, -23.7, -25.6,
		 75.4,  63.9,  54.9,  47.1,  40.8,  36.4,  32.2,  28.8,  25.2,  22.1,  19.3,  16.4,  13.8,  10.9,   8.1,   5.6,   2.9,   0.3,  -2.2,  -4.5,  -6.7,  -8.9, -11.0, -13.0, -15.0, -17.0, -19.1, -21.0, -22.8, -24.8,
		 76.4,  65.2,  56.3,  48.7,  42.4,  37.9,  33.9,  30.2,  26.6,  23.4,  20.3,  17.3,  14.8,  11.9,   9.1,   6.7,   3.9,   1.3,  -1.2,  -3.6,  -5.8,  -7.9, -10.0, -12.2, -14.2, -16.2, -18.2, -20.2, -22.0, -24.0,
		 77.4,  66.2,  57.6,  50.0,  43.7,  39.0,  35.1,  31.7,  27.8,  24.6,  21.3,  18.3,  15.7,  12.8,  10.0,   7.6,   4.8,   2.1,  -0.4,  -2.8,  -5.0,  -7.1,  -9.2, -11.3, -13.4, -15.4, -17.5, -19.4, -21.3, -23.2,
		 79.5,  69.3,  60.9,  53.6,  47.7,  43.1,  39.2,  35.8,  32.0,  28.3,  24.9,  21.7,  18.8,  15.9,  13.1,  10.6,   7.9,   5.1,   2.2,   0.0,  -2.2,  -4.3,  -6.6,  -8.9, -11.0, -13.0, -15.0, -17.0, -19.0, -21.0,
		 80.7,  71.2,  63.0,  56.1,  50.2,  46.0,  42.1,  38.7,  35.0,  31.3,  27.8,  24.3,  21.2,  18.2,  15.5,  12.8,  10.0,   7.3,   4.7,   2.1,   0.0,  -2.2,  -4.6,  -6.8,  -8.8, -10.8, -12.9, -14.9, -16.9, -18.9,
		 81.3,  72.6,  64.5,  58.0,  52.4,  48.0,  44.3,  40.7,  37.3,  33.8,  30.3,  27.0,  23.7,  20.5,  17.4,  14.7,  12.0,   9.2,   6.5,   4.0,   1.8,  -0.4,  -2.8,  -5.0,  -7.0,  -9.0, -11.0, -13.0, -15.0, -16.8
	};
	static double f59u[NHGT * ND90] = {
		 51.6,  36.4,  20.0,   5.3,  -1.0,  -3.6,  -6.8,  -9.4, -11.6, -14.0, -15.6, -16.6, -18.4, -19.4, -20.7, -22.4, -24.2, -26.0, -28.3, -30.3,
		 57.7,  43.7,  29.0,  13.3,   3.7,  -1.8,  -5.2,  -8.3, -10.9, -13.0, -14.7, -16.4, -17.7, -19.3, -20.4, -22.2, -23.5, -25.2, -27.3, -28.9,
		 63.3,  50.0,  36.2,  21.1,   8.4,   1.2,  -2.9,  -5.9,  -9.1, -11.8, -13.5, -15.3, -16.9, -18.4, -19.6, -21.0, -22.5, -24.1, -25.9, -27.6,
		 67.2,  53.2,  39.7,  25.4,  12.0,   4.5,  -1.0,  -4.4,  -8.0, -10.7, -12.6, -14.2, -16.4, -17.8, -19.0, -20.6, -21.7, -23.5, -25.4, -27.0,
		 69.5,  55.9,  42.6,  29.0,  15.5,   7.8,   1.2,  -2.6,  -6.2,  -9.1, -11.4, -13.4, -15.8, -17.3, -18.6, -19.9, -21.1, -22.6, -24.4, -26.2,
		 71.7,  57.9,  45.2,  32.2,  19.9,  11.4,   4.2,  -0.6,  -4.6,  -8.3, -10.5, -12.8, -15.0, -16.7, -17.9, -19.0, -20.6, -21.8, -23.5, -25.2,
		 73.5,  59.7,  48.1,  35.5,  24.6,  14.9,   7.6,   2.0,  -2.7,  -6.8,  -9.4, -11.9, -14.3, -16.1, -17.4, -18.6, -19.8, -21.3, -22.8, -24.6,
		 75.2,  61.3,  49.7,  38.3,  27.4,  17.6,  10.4,   3.8,  -1.2,  -5.1,  -8.1, -10.8, -13.8, -15.5, -16.7, -18.0, -19.3, -20.7, -22.2, -23.7,
		 76.6,  62.8,  51.5,  39.9,  29.6,  20.7,  12.9,   5.8,   0.6,  -4.0,  -6.9,  -9.7, -12.8, -15.1, -16.3, -17.7, -18.9, -20.3, -21.6, -22.8,
		 77.0,  63.8,  52.4,  41.4,  31.5,  23.0,  14.9,   7.9,   2.2,  -3.0,  -5.9,  -8.7, -11.9, -14.6, -16.0, -17.2, -18.4, -19.9, -21.2, -22.2,
		 79.7,  67.1,  55.9,  45.2,  35.7,  27.7,  20.4,  13.2,   7.6,   1.7,  -1.9,  -5.3,  -8.8, -11.9, -13.5, -15.0, -16.5, -17.7, -18.8, -20.0,
		 80.3,  68.8,  58.6,  48.1,  39.4,  31.2,  23.9,  17.3,  11.8,   6.3,   1.8,  -2.1,  -5.6,  -9.0, -11.7, -13.0, -14.4, -15.7, -16.9, -18.1,
		 80.7,  69.6,  60.5,  50.0,  41.0,  34.0,  27.1,  20.9,  14.7,   9.8,   4.7,   0.4,  -3.7,  -7.1, -10.0, -11.3, -12.8, -13.8, -15.3, -16.6
	};

	// Miscellaneous local variables.

	int i, ierr, ipas, nstep, ndst;
	double d[MSTEP], h[MSTEP], f[MSTEP], fsrch, delta, d1, d2, *dst, *f50, *f10, *f90, *fld, ftmp, dmax, fs_scale,
		dtmp;
#ifdef __BUILD_TVSTUDY
	double dp[MSTEP], lu[MSTEP], vp[MSTEP];

	if (dep) {
		*dep = 0.;
	}
	if (lup) {
		*lup = 0.;
	}
	if (vpt) {
		*vpt = 0.;
	}
#endif

	// Begin preliminaries, error-check operating mode keys.

	ierr = 0;
	if ((mode1 != FCC_DST) && (mode1 != FCC_PWR) && (mode1 != FCC_FLD)) {
		mode1 = FCC_DST;
	}
	if ((mode2 != FCC_F50) && (mode2 != FCC_F10) && (mode2 != FCC_F90)) {
		mode2 = FCC_F50;
	}

	// Error check height against limits.

	if (height < HMNT) {
		ierr = FCC_HGTLO;
		height = HMNT;
	}
	if (height > HMX) {
		ierr = FCC_HGTHI;
		height = HMX;
	}

	// Set table pointers based on channel band.

	switch (iband) {
		case BAND_VLO1:
		case BAND_VLO2:
		case BAND_FMED:
		case BAND_FM: {
			f50 = f55lv;
			f10 = f51lv;
			f90 = f59lv;
			break;
		}
		case BAND_VHI: {
			f50 = f55hv;
			f10 = f51hv;
			f90 = f59hv;
			break;
		}
		case BAND_UHF:
		case BAND_WL:
		default: {
			f50 = f55u;
			f10 = f51u;
			f90 = f59u;
			break;
		}
	}

	// In FCC_DST mode, find distance given field and power.  As discussed in comments earlier, this must use an
	// iterative routine to bracket then interpolate the distance to the desired field.  The h[] array defines the
	// height values for the sweep lookup, this is needed so the interpolation routine can be generic, but in this
	// situation all the heights are fixed.

	if (mode1 == FCC_DST) {

		fsrch = *field - *power;

		for (i = 0; i < MSTEP; i++) {
			h[i] = height;
		}

		// The loop here is to allow a repeat run with the curve set changed, for example if the request was for
		// F(50,10) but the distance drops below the minimum for that family, this will switch to F(50,50) and try
		// again.  First initialize for the selected curve set on this loop.

		while (1) {

			switch (mode2) {
				case FCC_F50:     // F(50,50)
				default: {
					ndst = ND50;
					dst = d50;
					fld = f50;
					d1 = D50MN;
					d2 = D50MX;
					break;
				}
				case FCC_F10: {   // F(50,10)
					ndst = ND10;
					dst = d10;
					fld = f10;
					d1 = D10MN;
					d2 = D10MX;
					break;
				}
				case FCC_F90: {   // F(50,90)
					ndst = ND90;
					dst = d90;
					fld = f90;
					d1 = D90MN;
					d2 = D90MX;
					break;
				}
			}

			// Loop for iterations, on the first pass the points are STEP km apart and the whole range of the curve set
			// is used.  First action is to compute distances across the range selected, and determine the number of
			// points.

			delta = STEP;

			for (ipas = 0; ipas < NPAS; ipas++) {

				for (i = 0; i < MSTEP; i++) {
					if ((d[i] = d1 + ((double)i * delta)) >= d2) {
						d[i++] = d2;
						break;
					}
				}

				nstep = i;

				// Call interpolation routine to get the sweep of lookup points.

				fcc_itplbv(ndst, NHGT, dst, hgt, fld, nstep, d, h, f);

				// Modify the lookup points by the vertical pattern, if needed.  Note parameters may disable elevation
				// pattern adjustment for contour lookup, that is checked in vpat_lookup() and if adjustment is off it
				// will immediately return all 0 values.

#ifdef __BUILD_TVSTUDY
				if (source) {
					for (i = 0; i < nstep; i++) {
						vp[i] = vpat_lookup(source, height, azm, d[i], 0., Params.ReceiveHeight, VPAT_MODE_CONTOUR,
							(dp + i), (lu + i));
						f[i] += vp[i];
					}
				}
#endif

				// On the first pass, check to see if lookup field is too large on F(50,10) or F(50,90) curves, if so
				// change to F(50,50) and try again.  If on F(50,50) already, transition to free-space.  However that
				// transition usually results in a large discontinuity, so other methods may be used per a study
				// parameter to eliminate the discontinuity; scale the free-space curve to match up with the end of
				// the tabulated curve, or just always use the minimum distance.

				if (ipas == 0) {

					if (fsrch > f[0]) {

						if (mode2 != FCC_F50) {
							ierr = FCC_FLD50;
							mode2 = FCC_F50;
							break;
						}

						ierr = FCC_FLDFS;

						switch (mode3) {

							case OFF_CURV_METH_FS:
							default: {
								fs_scale = 1.;
								break;
							}

							case OFF_CURV_METH_FS_SCALE: {
								fs_scale = d[0] / pow(10., ((106.92 - f[0]) / 20.));
								break;
							}

							case OFF_CURV_METH_NONE: {
								*dist = d[0];
								return(ierr);
							}
						}

						// Free-space distance lookup may be complicated by the need to apply the vertical pattern
						// correction.  Since that varies with distance, this has to be an iterative routine.  Note
						// if elevation pattern adjustment is disabled vpat_lookup() always returns 0 and this will
						// only make one pass with no adjustment.

#ifdef __BUILD_TVSTUDY
						if (source) {
							vp[0] = vpat_lookup(source, height, azm, d[0], 0., Params.ReceiveHeight, VPAT_MODE_CONTOUR,
								dp, lu);
							delta = vp[0];
							double ftmpl = 0., deltal = 0., deltat;
							int iter = 0;
							while (1) {
								*dist = pow(10., ((106.92 - (fsrch - delta)) / 20.)) * fs_scale;
								vp[1] = vpat_lookup(source, height, azm, *dist, 0., Params.ReceiveHeight,
									VPAT_MODE_CONTOUR, (dp + 1), (lu + 1));
								ftmp = vp[1] - delta;
								if ((fabs(ftmp) < 0.01) || (++iter > 50)) {
									break;
								}
								if (((ftmpl < 0.) && (ftmp > 0.)) || ((ftmpl > 0.) && (ftmp < 0.))) {
									deltat = deltal + ((delta - deltal) * (-ftmpl / (ftmp - ftmpl)));
									deltal = delta;
									delta = deltat;
								} else {
									deltal = delta;
									delta += ftmp;
								}
								ftmpl = ftmp;
								dp[0] = dp[1];
								lu[0] = lu[1];
								vp[0] = vp[1];
							}
							if (dep) {
								*dep = dp[0];
							}
							if (lup) {
								*lup = lu[0];
							}
							if (vpt) {
								*vpt = vp[0];
							}
						} else
#endif
							*dist = pow(10., ((106.92 - fsrch) / 20.)) * fs_scale;

						return(ierr);
					}

					// If lookup field is too small, return the maximum distance.

					if (fsrch <= f[nstep - 1]) {
						ierr = FCC_FLDLO;
						*dist = d[nstep - 1];
						return(ierr);
					}
				}

				// Search lookup sweep for fields that bracket desired field.  Always search from the end, that is the
				// larger distances inward.

				for (i = nstep - 2; i > 0; i--) {
					if ((fsrch <= f[i]) && (fsrch > f[i + 1])) {
						break;
					}
				}

				// Set up for next sweep (d1, d2 are used outside loop if this last pass).

				delta /= DSTEP;
				d1 = d[i];
				d2 = d[i + 1];
			}

			// If out of the iteration loop early, the curve set was changed, loop to start over with the new set.

			if (ipas < NPAS) {
				continue;
			}

			// Use linear interpolation to arrive at final answer.  Note if vertical pattern application was disabled
			// by parameter, the earlier vpat_lookup() calls will have set all values in dp, lu, and vp to 0.

			ftmp = (fsrch - f[i]) / (f[i + 1] - f[i]);
			*dist = d1 + ((d2 - d1) * ftmp);
#ifdef __BUILD_TVSTUDY
			if (source) {
				if (dep) {
					*dep = dp[i] + ((dp[i + 1] - dp[i]) * ftmp);
				}
				if (lup) {
					*lup = lu[i] + ((lu[i + 1] - lu[i]) * ftmp);
				}
				if (vpt) {
					*vpt = vp[i] + ((vp[i + 1] - vp[i]) * ftmp);
				}
			}
#endif
			break;
		}

	// If in FCC_FLD mode compute field given power and distance, in FCC_PWR mode compute power given field and
	// distance.  Very similar functions so they share code.  First error check distance, if less than D50MN,
	// transition to free-space.  The options to eliminate the discontinuity at that transition are also here.

	} else {

		if (*dist < D50MN) {

			ierr = FCC_DSTFS;

			switch (mode3) {

				case OFF_CURV_METH_FS:
				default: {
					dtmp = *dist;
					ftmp = 106.92 - (20. * log10(dtmp));
					break;
				}

				case OFF_CURV_METH_FS_SCALE: {
					d[0] = D50MN;
					h[0] = height;
					fcc_itplbv(ND50, NHGT, d50, hgt, f50, 1, d, h, f);
					dtmp = *dist;
					ftmp = 106.92 - (20. * log10(dtmp / (d[0] / pow(10., ((106.92 - f[0]) / 20.)))));
					break;
				}

				case OFF_CURV_METH_NONE: {
					d[0] = D50MN;
					h[0] = height;
					fcc_itplbv(ND50, NHGT, d50, hgt, f50, 1, d, h, f);
					dtmp = d[0];
					ftmp = f[0];
					break;
				}
			}

#ifdef __BUILD_TVSTUDY
			if (source) {
				vp[0] = vpat_lookup(source, height, azm, dtmp, 0., Params.ReceiveHeight, VPAT_MODE_CONTOUR, dp, lu);
				ftmp += vp[0];
				if (dep) {
					*dep = dp[0];
				}
				if (lup) {
					*lup = lu[0];
				}
				if (vpt) {
					*vpt = vp[0];
				}
			}
#endif

			if (mode1 == FCC_FLD) {
				*field = ftmp + *power;
			} else {
				*power = *field - ftmp;
			}

			return(ierr);
		}

		// If F(50,10/90) requested but the lookup distance is less than D10MN/D90MN, switch to F(50,50).

		if (mode2 == FCC_F10) {
			if (*dist < D10MN) {
				ierr = FCC_DST50;
				mode2 = FCC_F50;
			}
		} else {
			if (mode2 == FCC_F90) {
				if (*dist < D90MN) {
					ierr = FCC_DST50;
					mode2 = FCC_F50;
				}
			}
		}

		// Set values and pointers based on curve set.

		switch (mode2) {
			case FCC_F50:     // F(50,50)
			default: {
				ndst = ND50;
				dst = d50;
				fld = f50;
				dmax = D50MX;
				break;
			}
			case FCC_F10: {   // F(50,10)
				ndst = ND10;
				dst = d10;
				fld = f10;
				dmax = D10MX;
				break;
			}
			case FCC_F90: {   // F(50,90)
				ndst = ND90;
				dst = d90;
				fld = f90;
				dmax = D90MX;
				break;
			}
		}

		// If distance is greater than maximum, substitute the maximum.

		if (*dist > dmax) {
			ierr = FCC_DSTHI;
			d[0] = dmax;
		} else {
			d[0] = *dist;
		}

		// Call interpolation routine, modify the field by the vertical pattern, compute and return the result.

		h[0] = height;
		fcc_itplbv(ndst, NHGT, dst, hgt, fld, 1, d, h, f);

#ifdef __BUILD_TVSTUDY
		if (source) {
			vp[0] = vpat_lookup(source, height, azm, d[0], 0., Params.ReceiveHeight, VPAT_MODE_CONTOUR, dp, lu);
			f[0] += vp[0];
			if (dep) {
				*dep = dp[0];
			}
			if (lup) {
				*lup = lu[0];
			}
			if (vpt) {
				*vpt = vp[0];
			}
		}
#endif

		if (mode1 == FCC_FLD) {
			*field = f[0] + *power;
		} else {
			*power = *field - f[0];
		}
	}

	// All done.

	return(ierr);
}


//---------------------------------------------------------------------------------------------------------------------
// Interpolation subroutine, this is a bi-variate surface fitting procedure, refer to the original FCC CURVES program
// and to the paper referenced in comments above for details.  Sorry about the shortage of comments, but there were
// none in the original source.

// This has been modified from the original, taking into account the context in which it is being used.  It was a full
// general-purpose routine, however many of the conditions that had exception-handling could never have occurred given
// the limited range of possible values in context.  Much of that unnecessary code has been eliminated.

// Arguments:

//    lx  Number of columns in table.
//    ly  Number of rows in table.
//    x   Column enumeration values.
//    y   Row enumeration values.
//    z   Data table.
//    n   Number of points to lookup.
//    u   Column values for lookup points.
//    v   Row values for lookup points.
//    w   Return result of lookups.

void fcc_itplbv(int lx, int ly, double *x, double *y, double *z, int n, double *u, double *v, double *w) {

	int lxm1, lxp1, lym1, lyp1, ixpv, iypv, k, ix, iy, imn, imx, jx, jy, jx1, jy1;
	double za[5][2], zb[2][5], zab[3][3], zx[4][4], zy[4][4], zxy[4][4], x3, x4, a3, y3, y4, b3, z33, z43, z34, z44,
		x2, a2, z23, z24, x5, a4, z53, z54, a1, a5, y2, b2, z32, z42, y5, b4, z35, z45, b1, b5, w2, w3, sw, wx2, wx3,
		wy2, wy3, w1, w4, w5, zx3b3, zx4b3, zy3a3, zy4a3, a, b, c, d, e, a3sq, b3sq, p02, p03, p12, p13, p20, p21, p22,
		p23, p30, p31, p32, p33, dy, q0, q1, q2, q3, dx;

	lxm1 = lx - 1;
	lxp1 = lx + 1;
	lym1 = ly - 1;
	lyp1 = ly + 1;
	ixpv = -1;
	iypv = -1;
	for (k = 0; k < n; k++) {
		if (u[k] >= x[lxm1]) {
			ix = lx;
		} else {
			if (u[k] < x[0]) {
				ix = 0;
			} else {
				imn = 1;
				imx = lxm1;
				do {
					ix = (imn + imx) / 2;
					if (u[k] >= x[ix]) {
						imn = ix + 1;
					} else {
						imx = ix;
					}
				} while (imx > imn);
				ix = imx;
			}
		}
		if (v[k] >= y[lym1]) {
			iy = ly;
		} else {
			if (v[k] < y[0]) {
				iy = 0;
			} else {
				imn = 1;
				imx = lym1;
				do {
					iy = (imn + imx) / 2;
					if (v[k] >= y[iy]) {
						imn = iy + 1;
					} else {
						imx = iy;
					}
				} while (imx > imn);
				iy = imx;
			}
		}
		if ((ix != ixpv) || (iy != iypv)) {
			ixpv = ix;
			iypv = iy;
			if (ix == 0) {
				jx = 1;
			} else {
				if (ix == lx) {
					jx = lxm1;
				} else {
					jx = ix;
				}
			}
			if (iy == 0) {
				jy = 1;
			} else {
				if (iy == ly) {
					jy = lym1;
				} else {
					jy = iy;
				}
			}
			x3 = x[jx - 1];
			x4 = x[jx];
			a3 = 1. / (x4 - x3);
			y3 = y[jy - 1];
			y4 = y[jy];
			b3 = 1. / (y4 - y3);
			z33 = z[(jx - 1) + ((jy - 1) * lx)];
			z43 = z[jx + ((jy - 1) * lx)];
			z34 = z[(jx - 1) + (jy * lx)];
			z44 = z[jx + (jy * lx)];
			za[2][0] = (z43 - z33) * a3;
			za[2][1] = (z44 - z34) * a3;
			zb[0][2] = (z34 - z33) * b3;
			zb[1][2] = (z44 - z43) * b3;
			zab[1][1] = (zb[1][2] - zb[0][2]) * a3;
			if (jx > 1) {
				x2 = x[jx - 2];
				a2 = 1. / (x3 - x2);
				z23 = z[(jx - 2) + ((jy - 1) * lx)];
				z24 = z[(jx - 2) + (jy * lx)];
				za[1][0] = (z33 - z23) * a2;
				za[1][1] = (z34 - z24) * a2;
				if (jx == lxm1) {
					za[3][0] = (2. * za[2][0]) - za[1][0];
					za[3][1] = (2. * za[2][1]) - za[1][1];
				}
			}
			if (jx < lxm1) {
				x5 = x[jx + 1];
				a4 = 1. / (x5 - x4);
				z53 = z[(jx + 1) + ((jy - 1) * lx)];
				z54 = z[(jx + 1) + (jy * lx)];
				za[3][0] = (z53 - z43) * a4;
				za[3][1] = (z54 - z44) * a4;
				if (jx == 1) {
					za[1][0] = (2. * za[2][0]) - za[3][0];
					za[1][1] = (2. * za[2][1]) - za[3][1];
				}
			}
			zab[0][1] = (za[1][1] - za[1][0]) * b3;
			zab[2][1] = (za[3][1] - za[3][0]) * b3;
			if (jx > 2) {
				a1 = 1. / (x2 - x[jx - 3]);
				za[0][0] = (z23 - z[(jx - 3) + ((jy - 1) * lx)]) * a1;
				za[0][1] = (z24 - z[(jx - 3) + (jy * lx)]) * a1;
			} else {
				za[0][0] = (2. * za[1][0]) - za[2][0];
				za[0][1] = (2. * za[1][1]) - za[2][1];
			}
			if (jx < (lx - 2)) {
				a5 = 1. / (x[jx + 2] - x5);
				za[4][0] = (z[(jx + 2) + ((jy - 1) * lx)] - z53) * a5;
				za[4][1] = (z[(jx + 2) + (jy * lx)] - z54) * a5;
			} else {
				za[4][0] = (2. * za[3][0]) - za[2][0];
				za[4][1] = (2. * za[3][1]) - za[2][1];
			}
			if (jy > 1) {
				y2 = y[jy - 2];
				b2 = 1. / (y3 - y2);
				z32 = z[(jx - 1) + ((jy - 2) * lx)];
				z42 = z[jx + ((jy - 2) * lx)];
				zb[0][1] = (z33 - z32) * b2;
				zb[1][1] = (z43 - z42) * b2;
				if (jy == lym1) {
					zb[0][3] = (2. * zb[0][2]) - zb[0][1];
					zb[1][3] = (2. * zb[1][2]) - zb[1][1];
				}
			}
			if (jy < lym1) {
				y5 = y[jy + 1];
				b4 = 1. / (y5 - y4);
				z35 = z[(jx - 1) + ((jy + 1) * lx)];
				z45 = z[jx + ((jy + 1) * lx)];
				zb[0][3] = (z35 - z34) * b4;
				zb[1][3] = (z45 - z44) * b4;
				if (jy == 1) {
					zb[0][1] = (2. * zb[0][2]) - zb[0][3];
					zb[1][1] = (2. * zb[1][2]) - zb[1][3];
				}
			}
			zab[1][0] = (zb[1][1] - zb[0][1]) * a3;
			zab[1][2] = (zb[1][3] - zb[0][3]) * a3;
			if (jy > 2) {
				b1 = 1. / (y2 - y[jy - 3]);
				zb[0][0] = (z32 - z[(jx - 1) + ((jy - 3) * lx)]) * b1;
				zb[1][0] = (z42 - z[jx + ((jy - 3) * lx)]) * b1;
			} else {
				zb[0][0] = (2. * zb[0][1]) - zb[0][2];
				zb[1][0] = (2. * zb[1][1]) - zb[1][2];
			}
			if (jy < (ly - 2)) {
				b5 = 1. / (y[jy + 2] - y5);
				zb[0][4] = (z[(jx - 1) + ((jy + 2) * lx)] - z35) * b5;
				zb[1][4] = (z[jx + ((jy + 2) * lx)] - z45) * b5;
			} else {
				zb[0][4] = (2. * zb[0][3]) - zb[0][2];
				zb[1][4] = (2. * zb[1][3]) - zb[1][2];
			}
			if (jx < lxm1) {
				if (jy > 1) {
					zab[2][0] = ((z53 - z[(jx + 1) + ((jy - 2) * lx)]) * b2 - zb[1][1]) * a4;
					if (jy < lym1) {
						zab[2][2] = ((z[(jx + 1) + ((jy + 1) * lx)] - z54) * b4 - zb[1][3]) * a4;
					} else {
						zab[2][2] = (2. * zab[2][1]) - zab[2][0];
					}
				} else {
					zab[2][2] = ((z[(jx + 1) + ((jy + 1) * lx)] - z54) * b4 - zb[1][3]) * a4;
					zab[2][0] = (2. * zab[2][1]) - zab[2][2];
				}
				if (jx == 1) {
					zab[0][0] = (2. * zab[1][0]) - zab[2][0];
					zab[0][2] = (2. * zab[1][2]) - zab[2][2];
				}
			}
			if (jx > 1) {
				if (jy > 1) {
					zab[0][0] = (zb[0][1] - (z23 - z[(jx - 2) + ((jy - 2) * lx)]) * b2) * a2;
					if (jy < lym1) {
						zab[0][2] = (zb[0][3] - (z[(jx - 2) + ((jy + 1) * lx)] - z24) * b4) * a2;
					} else {
						zab[0][2] = (2. * zab[0][1]) - zab[0][0];
					}
				} else {
					zab[0][2] = (zb[0][3] - (z[(jx - 2) + ((jy + 1) * lx)] - z24) * b4) * a2;
					zab[0][0] = (2. * zab[0][1]) - zab[0][2];
				}
				if (jx == lxm1) {
					zab[2][0] = (2. * zab[1][0]) - zab[0][0];
					zab[2][2] = (2. * zab[1][2]) - zab[0][2];
				}
			}
			for (jy = 1; jy < 3; jy++) {
				for (jx = 1; jx < 3; jx++) {
					w2 = fabs(za[jx + 2][jy - 1] - za[jx + 1][jy - 1]);
					w3 = fabs(za[jx][jy - 1] - za[jx - 1][jy - 1]);
					sw = w2 + w3;
					if (sw >= 1.e-7) {
						wx2 = w2 / sw;
						wx3 = w3 / sw;
					} else {
						wx2 = 0.5;
						wx3 = 0.5;
					}
					zx[jx][jy] = wx2 * za[jx][jy - 1] + wx3 * za[jx + 1][jy - 1];
					w2 = fabs(zb[jx - 1][jy + 2] - zb[jx - 1][jy + 1]);
					w3 = fabs(zb[jx - 1][jy] - zb[jx - 1][jy - 1]);
					sw = w2 + w3;
					if (sw >= 1.e-7) {
						wy2 = w2 / sw;
						wy3 = w3 / sw;
					} else {
						wy2 = 0.5;
						wy3 = 0.5;
					}
					zy[jx][jy] = wy2 * zb[jx - 1][jy] + wy3 * zb[jx - 1][jy + 1];
					zxy[jx][jy] = wy2 * (wx2 * zab[jx - 1][jy - 1] + wx3 * zab[jx][jy - 1]) +
						wy3 * (wx2 * zab[jx - 1][jy] + wx3 * zab[jx][jy]);
				}
			}
			if (ix == 0) {
				w2 = a4 * (3. * a3 + a4);
				w1 = 2. * a3 * (a3 - a4) + w2;
				for (jy = 1; jy < 3; jy++) {
					zx[0][jy] = (w1 * za[0][jy - 1] + w2 * za[1][jy - 1]) / (w1 + w2);
					zy[0][jy] = (2. * zy[1][jy]) - zy[2][jy];
					zxy[0][jy] = (2. * zxy[1][jy]) - zxy[2][jy];
					for (jx1 = 1; jx1 < 3; jx1++) {
						jx = 3 - jx1;
						zx[jx][jy] = zx[jx - 1][jy];
						zy[jx][jy] = zy[jx - 1][jy];
						zxy[jx][jy] = zxy[jx - 1][jy];
					}
				}
				x3 -= 1. / a4;
				z33 -= za[1][0] / a4;
				for (jy = 0; jy < 5; jy++) {
					zb[1][jy] = zb[0][jy];
				}
				for (jy = 1; jy < 4; jy++) {
					zb[0][jy] -= zab[0][jy - 1] / a4;
				}
				a3 = a4;
				za[2][0] = za[1][0];
				for (jy = 0; jy < 3; jy++) {
					zab[1][jy] = zab[0][jy];
				}
			}
			if (ix == lx) {
				w4 = a2 * (3. * a3 + a2);
				w5 = 2. * a3 * (a3 - a2) + w4;
				for (jy = 1; jy < 3; jy++) {
					zx[3][jy] = (w4 * za[3][jy - 1] + w5 * za[4][jy - 1]) / (w4 + w5);
					zy[3][jy] = (2. * zy[2][jy]) - zy[1][jy];
					zxy[3][jy] = (2. * zxy[2][jy]) - zxy[1][jy];
					for (jx = 1; jx < 3; jx++) {
						zx[jx][jy] = zx[jx + 1][jy];
						zy[jx][jy] = zy[jx + 1][jy];
						zxy[jx][jy] = zxy[jx + 1][jy];
					}
				}
				x3 = x4;
				z33 = z43;
				for (jy = 0; jy < 5; jy++) {
					zb[0][jy] = zb[1][jy];
				}
				a3 = a2;
				za[2][0] = za[3][0];
				for (jy = 0; jy < 3; jy++) {
					zab[1][jy] = zab[2][jy];
				}
			}
			if (iy == 0) {
				w2 = b4 * (3. * b3 + b4);
				w1 = 2. * b3 * (b3 - b4) + w2;
				for (jx = 1; jx < 3; jx++) {
					if (((ix > 0) || (jx == 2)) && ((ix < lx) || (jx == 1))) {
						zy[jx][0] = (w1 * zb[jx - 1][0] + w2 * zb[jx - 1][1]) / (w1 + w2);
						zx[jx][0] = (2. * zx[jx][1]) - zx[jx][2];
						zxy[jx][0] = (2. * zxy[jx][1]) - zxy[jx][2];
					}
					for (jy1 = 1; jy1 < 3; jy1++) {
						jy = 3 - jy1;
						zy[jx][jy] = zy[jx][jy - 1];
						zx[jx][jy] = zx[jx][jy - 1];
						zxy[jx][jy] = zxy[jx][jy - 1];
					}
				}
				y3 -= 1. / b4;
				z33 -= zb[0][1] / b4;
				za[2][0] -= zab[1][0] / b4;
				zb[0][2] = zb[0][1];
				zab[1][1] = zab[1][0];
				b3 = b4;
				if ((ix == 0) || (ix == lx)) {
					if (ix == 0) {
						jx = 1;
						jx1 = 2;
					} else {
						jx = 2;
						jx1 = 1;
					}
					zx[jx][1] = zx[jx1][1] + zx[jx][2] - zx[jx1][2];
					zy[jx][1] = zy[jx1][1] + zy[jx][2] - zy[jx1][2];
					zxy[jx][1] = zxy[jx1][1] + zxy[jx][2] - zxy[jx1][2];
				}
			}
			if (iy == ly) {
				w4 = b2 * (3. * b3 + b2);
				w5 = 2. * b3 * (b3 - b2) + w4;
				for (jx = 1; jx < 3; jx++) {
					if (((ix > 0) || (jx == 2)) && ((ix < lx) || (jx == 1))) {
						zy[jx][3] = (w4 * zb[jx - 1][3] + w5 * zb[jx - 1][4]) / (w4 + w5);
						zx[jx][3] = (2. * zx[jx][2]) - zx[jx][1];
						zxy[jx][3] = (2. * zxy[jx][2]) - zxy[jx][1];
					}
					for (jy = 1; jy < 3; jy++) {
						zy[jx][jy] = zy[jx][jy + 1];
						zx[jx][jy] = zx[jx][jy + 1];
						zxy[jx][jy] = zxy[jx][jy + 1];
					}
				}
				y3 = y4;
				z33 += zb[0][2] / b3;
				za[2][0] += zab[1][1] / b3;
				zb[0][2] = zb[0][3];
				zab[1][1] = zab[1][2];
				b3 = b2;
				if ((ix == 0) || (ix == lx)) {
					if (ix == 0) {
						jx = 1;
						jx1 = 2;
					} else {
						jx = 2;
						jx1 = 1;
					}
					zx[jx][2] = zx[jx1][2] + zx[jx][1] - zx[jx1][1];
					zy[jx][2] = zy[jx1][2] + zy[jx][1] - zy[jx1][1];
					zxy[jx][2] = zxy[jx1][2] + zxy[jx][1] - zxy[jx1][1];
				}
			}
			zx3b3 = (zx[1][2] - zx[1][1]) * b3;
			zx4b3 = (zx[2][2] - zx[2][1]) * b3;
			zy3a3 = (zy[2][1] - zy[1][1]) * a3;
			zy4a3 = (zy[2][2] - zy[1][2]) * a3;
			a = zab[1][1] - zx3b3 - zy3a3 + zxy[1][1];
			b = zx4b3 - zx3b3 - zxy[2][1] + zxy[1][1];
			c = zy4a3 - zy3a3 - zxy[1][2] + zxy[1][1];
			d = zxy[2][2] - zxy[2][1] - zxy[1][2] + zxy[1][1];
			e = a + a - b - c;
			a3sq = a3 * a3;
			b3sq = b3 * b3;
			p02 = (2. * (zb[0][2] - zy[1][1]) + zb[0][2] - zy[1][2]) * b3;
			p03 = (-2. * zb[0][2] + zy[1][2] + zy[1][1]) * b3sq;
			p12 = (2. * (zx3b3 - zxy[1][1]) + zx3b3 - zxy[1][2]) * b3;
			p13 = (-2. * zx3b3 + zxy[1][2] + zxy[1][1]) * b3sq;
			p20 = (2. * (za[2][0] - zx[1][1]) + za[2][0] - zx[2][1]) * a3;
			p21 = (2. * (zy3a3 - zxy[1][1]) + zy3a3 - zxy[2][1]) * a3;
			p22 = (3. * (a + e) + d) * a3 * b3;
			p23 = (-3. * e - b - d) * a3 * b3sq;
			p30 = (-2. * za[2][0] + zx[2][1] + zx[1][1]) * a3sq;
			p31 = (-2. * zy3a3 + zxy[2][1] + zxy[1][1]) * a3sq;
			p32 = (-3. * e - c - d) * b3 * a3sq;
			p33 = (d + e + e) * a3sq * b3sq;
		}
		dy = v[k] - y3;
		q0 = z33 + dy * (zy[1][1] + dy * (p02 + dy * p03));
		q1 = zx[1][1] + dy * (zxy[1][1] + dy * (p12 + dy * p13));
		q2 = p20 + dy * (p21 + dy * (p22 + dy * p23));
		q3 = p30 + dy * (p31 + dy * (p32 + dy * p33));
		dx = u[k] - x3;
		w[k] = q0 + dx * (q1 + dx * (q2 + dx * q3));
	}
}
