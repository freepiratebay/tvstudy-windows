//
//  fcc_curve.h
//  TVStudy
//
//  Copyright (c) 2016 Hammett & Edison, Inc.  All rights reserved.


#define FCC_DST  1   // Mode key.  Compute distance from field/power.
#define FCC_FLD  2   // Compute field from distance/power.
#define FCC_PWR  3   // Compute power from distance/field.

#define FCC_F50  1   // Curve set key.  F(50,50).  These are used in study parameters, must match database!
#define FCC_F10  2   // F(50,10).
#define FCC_F90  3   // F(50,90).

#define FCC_HGTLO  1   // Return status code.  Height <30.5, used 30.5.
#define FCC_HGTHI  2   // Height >1600, used 1600.
#define FCC_DSTFS  3   // Distance too small, used free-space.
#define FCC_FLDFS  4   // Field too large, used free-space.
#define FCC_DST50  5   // Distance too small for F(50,10/90), used F(50,50).
#define FCC_FLD50  6   // Field too large for F(50,10/90), used F(50,50).
#define FCC_DSTHI  7   // Distance to large, used max distance.
#define FCC_FLDLO  8   // Field too small, returned max distance.

#define OFF_CURV_METH_FS        1   // Options for off-curve lookup: straight free-space (with discontinuity),
#define OFF_CURV_METH_FS_SCALE  2   //  free-space scaled to match last curve value (no discontinuity),
#define OFF_CURV_METH_NONE      3   //  no lookup just hold last curve value.

#define BAND_VLO1  1   // Channel band keys.  TV channel 2-4.
#define BAND_VLO2  2   // TV channel 5-6.
#define BAND_VHI   3   // TV channel 7-13.
#define BAND_UHF   4   // TV channel 14-51.
#define BAND_WL    5   // Wireless does not have channel numbers.
#define BAND_FMED  6   // FM channel 200-220.
#define BAND_FM    7   // FM channel 221-300.


// This is only needed when not being built for the tvstudy engine, see fcc_curve.c and tvstudy.h.

#ifndef __BUILD_TVSTUDY
int fcc_curve(double *power, double *field, double *dist, double height, int iband, int mode1, int mode2, int mode3);
#endif

