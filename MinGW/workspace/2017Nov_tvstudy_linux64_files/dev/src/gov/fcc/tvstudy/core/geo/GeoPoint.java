//
//  GeoPoint.java
//  TVStudy
//
//  Copyright (c) 2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.geo;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;

import java.io.*;

import org.xml.sax.*;


//=====================================================================================================================
// Utility class for working with sets of geographic coordinates.  Coordinates are degrees positive north and west.
// To support editors and I/O code, values may also be converted to/from broken-out degrees, minutes, and seconds
// values plus north-south and east-west indicators.  Coordinates should be in NAD83 before being used, if NAD27
// coordinates are set those can be converted in place, see convertFromNAD27().

public class GeoPoint {

	public static final double PI = 3.14159265358979323;
	public static final double TWO_PI = 6.2831853071795862;
	public static final double RADIANS_TO_DEGREES = 57.295779513082323;
	public static final double DEGREES_TO_RADIANS = 0.017453292519943295;

	public double latitude;
	public double longitude;

	public int latitudeNS;
	public int latitudeDegrees;
	public int latitudeMinutes;
	public double latitudeSeconds;

	public int longitudeWE;
	public int longitudeDegrees;
	public int longitudeMinutes;
	public double longitudeSeconds;


	//-----------------------------------------------------------------------------------------------------------------

	public boolean equals(Object other) {

		return ((null != other) && (latitude == ((GeoPoint)other).latitude) &&
			(longitude == ((GeoPoint)other).longitude));
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Convenience.

	public void setLatLon(GeoPoint thePoint) {
		setLatLon(thePoint.latitude, thePoint.longitude);
	}

	public void setLatLon(double theLat, double theLon) {

		latitude = theLat;
		longitude = theLon;

		updateDMS();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Methods convert between degrees-minutes-seconds and latitude-longitude properties.

	public void updateDMS() {

		double ll = Math.abs(latitude);
		latitudeDegrees = (int)ll;
		latitudeMinutes = (int)((ll - (double)latitudeDegrees) * 60.);
		latitudeSeconds = (((ll - (double)latitudeDegrees) * 60.) - (double)latitudeMinutes) * 60.;
		if (latitudeSeconds >= 59.995) {
			latitudeSeconds = 0;
			if (60 == ++latitudeMinutes) {
				latitudeMinutes = 0;
				++latitudeDegrees;
			}
		}
		if (latitude < 0) {
			latitudeNS = 1;
		} else {
			latitudeNS = 0;
		}

		ll = Math.abs(longitude);
		longitudeDegrees = (int)ll;
		longitudeMinutes = (int)((ll - (double)longitudeDegrees) * 60.);
		longitudeSeconds = (((ll - (double)longitudeDegrees) * 60.) - (double)longitudeMinutes) * 60.;
		if (longitudeSeconds >= 59.995) {
			longitudeSeconds = 0;
			if (60 == ++longitudeMinutes) {
				longitudeMinutes = 0;
				++longitudeDegrees;
			}
		}
		if (longitude < 0) {
			longitudeWE = 1;
		} else {
			longitudeWE = 0;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void updateLatLon() {

		latitude = (double)Math.abs(latitudeDegrees) + ((double)latitudeMinutes / 60.) + (latitudeSeconds / 3600.);
		if (0 != latitudeNS) {
			latitude *= -1.;
		}

		longitude = (double)Math.abs(longitudeDegrees) + ((double)longitudeMinutes / 60.) +
			(longitudeSeconds / 3600.);
		if (0 != longitudeWE) {
			longitude *= -1.;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Convert coordinates from NAD27 to NAD83.  This is a simplified port of the study engine code found in
	// coordinates.c, see that for detailed comments.  Note there is no protection against repeated calls which would
	// have a cumulative and incorrect result.  This must be called only once (if at all) immediately after populating
	// the main properties.

	private static final double A = 6.3782064e6;
	private static final double F = 3.390075e-3;
	private static final double DA = -6.94e1;
	private static final double DF = -3.7264639e-5;

	private static final double[] xlats = {
		 20.,  62.,     56.,  57.,   46.,   18.,  17.,  50.,  14.,   13.,  -15.
	};
	private static final double[] xlatn = {
		 50.,  64.,     57.,  58.,   77.,   23.,  19.,  75.,  20.,   16.,  -14.
	};
	private static final double[] xlone = {
		 63., 168.,    169., 169.,  128.,  154.,  64.,  51.,  87., -146.,  169.
	};
	private static final double[] xlonw = {
		131., 172.,    171., 171.,  194.,  161.,  68., 128., 114., -144.,  171.
	};
	private static final int[] ialg = {
		   1,    1,       1,    1,     1,     1,    1,    2,    2,     2,    2
	};
	private static final int[] ncl = {
		 273,   81,     121,   41,   529,   281,   81,    0,    0,     0,    0
	};
	private static final int[] nrw = {
		 121,   41,      61,   21,   249,   201,   41,    0,    0,     0,    0
	};
	private static final double[] dx = {
		0.25, 0.05, 0.01667, 0.05, 0.125, 0.025, 0.05, -10., -12., -100., -115.
	};
	private static final double[] dy = {
		  0.,   0.,      0.,   0.,    0.,    0.,   0., 158., 130., -248.,  118.
	};
	private static final double[] dz = {
		  0.,   0.,      0.,   0.,    0.,    0.,   0., 187., 190.,  259.,  426.
	};
	private static float[][][] nadla = {
		null, null,    null, null,  null,  null, null, null, null,  null,  null
	};
	private static float[][][] nadlo = {
		null, null,    null, null,  null,  null, null, null, null,  null,  null
	};

	private static final String NADCON_DIR_NAME = "nadcon";

	private static final String lafil[] = {
		"conus_eb.las",
		"stlrnc_eb.las",
		"stgeorge_eb.las",
		"stpaul_eb.las",
		"alaska_eb.las",
		"hawaii_eb.las",
		"prvi_eb.las",
		"",
		"",
		"",
		""
	};
	private static final String lofil[] = {
		"conus_eb.los",
		"stlrnc_eb.los",
		"stgeorge_eb.los",
		"stpaul_eb.los",
		"alaska_eb.los",
		"hawaii_eb.los",
		"prvi_eb.los",
		"",
		"",
		"",
		""
	};

	// Conversion is supported in defined regions, some use NADCON algorithm which is simple lookup and interpolation
	// in shift tables (stored in binary data files), others use abridged Molodensky formula.  Outside those regions
	// the conversion is undefined, just set the coordinates unchanged.  Return is false if no conversion is made.
	// This does not populate the DMS fields, if needed caller must use updateDMS() after.

	public boolean convertFromNAD27() {

		int ir;
		for (ir = 0; ir < ialg.length; ir++) {
			if ((latitude >= xlats[ir]) && (latitude <= xlatn[ir]) &&
					(longitude >= xlone[ir]) && (longitude <= xlonw[ir])) {
				break;
			}
		}
		if (ir == ialg.length) {
			return false;
		}

		double dp = 0., dl = 0.;

		if (1 == ialg[ir]) {

			float[][] bla = nadla[ir];
			float[][] blo = nadlo[ir];

			if ((null == bla) || (null == blo)) {

				String fname;
				DataInputStream nadfil;
				int irw, icl;

				bla = new float[nrw[ir]][ncl[ir]];

				fname = AppCore.dbaseDirectoryPath + File.separator + NADCON_DIR_NAME + File.separator + lafil[ir];

				try {

					nadfil = new DataInputStream(new BufferedInputStream(new FileInputStream(new File(fname))));

					nadfil.skipBytes((ncl[ir] + 1) * 4);
					for (irw = 0; irw < nrw[ir]; irw++) {
						nadfil.skipBytes(4);
						for (icl = 0; icl < ncl[ir]; icl++) {
							bla[irw][icl] = nadfil.readFloat();
						}
					}

					nadfil.close();

				} catch (IOException ie) {
					return false;
				}

				blo = new float[nrw[ir]][ncl[ir]];

				fname = AppCore.dbaseDirectoryPath + File.separator + NADCON_DIR_NAME + File.separator + lofil[ir];

				try {

					nadfil = new DataInputStream(new BufferedInputStream(new FileInputStream(new File(fname))));

					nadfil.skipBytes((ncl[ir] + 1) * 4);
					for (irw = 0; irw < nrw[ir]; irw++) {
						nadfil.skipBytes(4);
						for (icl = 0; icl < ncl[ir]; icl++) {
							blo[irw][icl] = nadfil.readFloat();
						}
					}

					nadfil.close();

				} catch (IOException ie) {
					return false;
				}

				nadla[ir] = bla;
				nadlo[ir] = blo;
			}

			double ygrid = ((latitude - xlats[ir]) / dx[ir]);
			double xgrid = ((xlonw[ir] - longitude) / dx[ir]);

			int irw0 = (int)ygrid;
			if (irw0 < 0) {
				irw0 = 0;
			}
			if (irw0 > (nrw[ir] - 2)) {
				irw0 = nrw[ir] - 2;
			}
			int irw1 = irw0 + 1;

			int icl0 = (int)xgrid;
			if (icl0 < 0) {
				icl0 = 0;
			}
			if (icl0 > (ncl[ir] - 2)) {
				icl0 = ncl[ir] - 2;
			}
			int icl1 = icl0 + 1;

			double t1 = (double)bla[irw0][icl0];
			double t2 = (double)bla[irw1][icl0];
			double t3 = (double)bla[irw0][icl1];
			double t4 = (double)bla[irw1][icl1];
			double a1 = t1;
			double b1 = t3 - t1;
			double c1 = t2 - t1;
			double d1 = t4 - t3 - t2 + t1;

			t1 = (double)blo[irw0][icl0];
			t2 = (double)blo[irw1][icl0];
			t3 = (double)blo[irw0][icl1];
			t4 = (double)blo[irw1][icl1];
			double a2 = t1;
			double b2 = t3 - t1;
			double c2 = t2 - t1;
			double d2 = t4 - t3 - t2 + t1;

			double yfrac = ygrid - (double)irw0;
			double xfrac = xgrid - (double)icl0;

			dp = a1 + (b1 * xfrac) + (c1 * yfrac) + (d1 * xfrac * yfrac);
			dl = -(a2 + (b2 * xfrac) + (c2 * yfrac) + (d2 * xfrac * yfrac));

		} else {

			double xp = latitude * DEGREES_TO_RADIANS;
			double xl = -longitude * DEGREES_TO_RADIANS;
			double sinxp = Math.sin(xp);
			double sinxl = Math.sin(xl);
			double cosxp = Math.cos(xp);
			double cosxl = Math.cos(xl);

			double e2 = (2. * F) - (F * F);
			double tmp = Math.sqrt(1. - (e2 * sinxp * sinxp));
			double rm = (A * (1. - e2)) / (tmp * tmp * tmp);
			double rn = A / tmp;

			dp = ((dz[ir] * cosxp) - (dx[ir] * sinxp * cosxl) - (dy[ir] * sinxp * sinxl) +
				(((A * DF) + (F * DA)) * Math.sin(2. * xp))) / (4.848136e-6 * rm);
			dl = ((dy[ir] * cosxl) - (dx[ir] * sinxl)) / (4.848136e-6 * rn * cosxp);
		}

		latitude += dp / 3600.;
		longitude -= dl / 3600.;

		updateDMS();

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Support for XML import/export, see SourceEditData.

	public void writeAttributes(Writer xml) throws IOException {

		xml.append(" LATITUDE83=\"");
		xml.append(AppCore.formatLatitude(latitude));
		xml.append("\" LONGITUDE83=\"");
		xml.append(AppCore.formatLongitude(longitude));
		xml.append('"');
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Look for NAD83 attributes, if not both found try alternate NAD27 attributes or legacy attributes that may be
	// either, and convert from NAD27 as needed.  The legacy attributes are NAD83 for wireless, NAD27 otherwise.  If
	// the recordType argument is zero, the legacy attributes are not supported.

	public boolean parseAttributes(String element, Attributes attrs, ErrorLogger errors) {
		return parseAttributes(element, attrs, 0, errors);
	}

	public boolean parseAttributes(String element, Attributes attrs, int recordType, ErrorLogger errors) {

		double theLatitude = Source.LATITUDE_MIN - 1.;
		double theLongitude = Source.LONGITUDE_MIN - 1.;

		String latstr = attrs.getValue("LATITUDE83");
		String lonstr = attrs.getValue("LONGITUDE83");
		boolean doConvert = false;
		if ((null == latstr) || (null == lonstr)) {
			latstr = attrs.getValue("LATITUDE27");
			lonstr = attrs.getValue("LONGITUDE27");
			doConvert = true;
			if ((recordType > 0) && ((null == latstr) || (null == lonstr))) {
				latstr = attrs.getValue("LATITUDE");
				lonstr = attrs.getValue("LONGITUDE");
				doConvert = (Source.RECORD_TYPE_WL != recordType);
			}
		}
		if ((null != latstr) && (null != lonstr)) {
			theLatitude = AppCore.parseLatitude(latstr);
			theLongitude = AppCore.parseLongitude(lonstr);
		}

		if ((theLatitude < Source.LATITUDE_MIN) || (theLatitude > Source.LATITUDE_MAX) ||
				(theLongitude < Source.LONGITUDE_MIN) || (theLongitude > Source.LONGITUDE_MAX)) {
			if (null != errors) {
				errors.reportError("Missing or bad LATITUDE83/LONGITUDE83 attributes in " + element + " tag.");
			}
			return false;
		}

		latitude = theLatitude;
		longitude = theLongitude;

		if (doConvert) {
			convertFromNAD27();
		} else {
			updateDMS();
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Compute the distance between this point and another using spherical methods.

	public double distanceTo(GeoPoint end, double kmPerDeg) {
		return distanceTo(end.latitude, end.longitude, kmPerDeg);
	}

	public double distanceTo(double endLat, double endLon, double kmPerDeg) {

		double xla1 = latitude * DEGREES_TO_RADIANS;
		double xlo1 = longitude * DEGREES_TO_RADIANS;
		double xla2 = endLat * DEGREES_TO_RADIANS;
		double xlo2 = endLon * DEGREES_TO_RADIANS;

		double delo = xlo1 - xlo2;
		while (delo < -PI) {
			delo += TWO_PI;
		}
		while (delo > PI) {
			delo -= TWO_PI;
		}

		double cosdi = (Math.sin(xla1) * Math.sin(xla2)) + (Math.cos(xla1) * Math.cos(xla2) * Math.cos(delo));
		if (cosdi < -1.) {
			cosdi = -1.;
		}
		if (cosdi > 1.) {
			cosdi = 1.;
		}

		return Math.acos(cosdi) * RADIANS_TO_DEGREES * kmPerDeg;
	}
}
