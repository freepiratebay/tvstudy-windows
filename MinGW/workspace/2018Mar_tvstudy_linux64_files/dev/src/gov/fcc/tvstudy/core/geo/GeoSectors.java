//
//  GeoSectors.java
//  TVStudy
//
//  Copyright (c) 2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.geo;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;

import java.util.*;
import java.sql.*;
import java.io.*;

import org.xml.sax.*;


//=====================================================================================================================
// Model for editing sector geographies, that is a collection of circular arcs of varying radii.  This class also has
// a string encoding/decoding feature for the sectors, however the encoding does not include center coordinates.

public class GeoSectors extends Geography {

	// type (super)  Always GEO_TYPE_SECTORS.
	// key (super)   Key, null for a new set never saved.
	// name (super)  Set name.
	// center        Center point.
	// sectors       Array of sectors.

	public final GeoPoint center;
	public ArrayList<Sector> sectors;


	//-----------------------------------------------------------------------------------------------------------------
	// See superclass.

	public GeoSectors(String theDbID) {

		super(theDbID, GEO_TYPE_SECTORS);

		center = new GeoPoint();
		sectors = new ArrayList<Sector>();
	}


	//=================================================================================================================
	// Sector class.

	public static class Sector {

		public double azimuth;
		public double radius;


		//-------------------------------------------------------------------------------------------------------------

		private Sector duplicate() {

			Sector newSector = new Sector();

			newSector.azimuth = azimuth;
			newSector.radius = radius;

			return newSector;
		}


		//-------------------------------------------------------------------------------------------------------------

		private boolean isDataValid(ErrorLogger errors) {

			if ((azimuth < 0.) || (azimuth >= 360.)) {
				if (null != errors) {
					errors.reportValidationError("Bad sector azimuth, must be from 0 to less than 360 degrees.");
				}
				return false;
			}

			if ((radius < MIN_DISTANCE) || (radius > MAX_DISTANCE)) {
				if (null != errors) {
					errors.reportValidationError("Bad sector radius, must be from " + MIN_DISTANCE + " to " +
						MAX_DISTANCE + ".");
				}
				return false;
			}

			return true;
		}


		//-------------------------------------------------------------------------------------------------------------
		// XML support.

		public void writeAttributes(Writer xml) throws IOException {

			xml.append(" AZIMUTH=\"");
			xml.append(AppCore.formatAzimuth(azimuth));
			xml.append("\" RADIUS=\"");
			xml.append(AppCore.formatDistance(radius));
			xml.append('"');
		}


		//-------------------------------------------------------------------------------------------------------------

		public boolean parseAttributes(String element, Attributes attrs, ErrorLogger errors) {

			String str = attrs.getValue("AZIMUTH");
			azimuth = -1.;
			if (null != str) {
				try {
					azimuth = Double.parseDouble(str);
				} catch (NumberFormatException nfe) {
				}
			}
			if ((azimuth < 0.) || (azimuth >= 360.)) {
				if (null != errors) {
					errors.reportError("Bad AZIMUTH attribute in " + element + " tag.");
				}
				return false;
			}

			str = attrs.getValue("RADIUS");
			radius = MIN_DISTANCE - 1.;
			if (null != str) {
				try {
					radius = Double.parseDouble(str);
				} catch (NumberFormatException nfe) {
				}
			}
			if ((radius < MIN_DISTANCE) || (radius > MAX_DISTANCE)) {
				if (null != errors) {
					errors.reportError("Bad RADIUS attribute in " + element + " tag.");
				}
				return false;
			}

			return true;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The string encoding/decoding capability.  Decode replaces any existing data.  Encode does not check validity
	// first so could create an invalid result, caller must validate first if needed.  Decode will fail and not change
	// any state if the string is invalid.  The static validate method just parses and validates.

	public String encodeAsString() {

		StringBuilder s = new StringBuilder();
		boolean first = true;
		double firstAz = 0., lastAz = 0., lastRad = 0.;
		for (Sector sector : sectors) {
			if (first) {
				firstAz = sector.azimuth + 360.;
				first = false;
			} else {
				s.append(String.format(Locale.US, "%f,%f,%f;", lastAz, lastRad, sector.azimuth));
			}
			lastAz = sector.azimuth;
			lastRad = sector.radius;
		}
		s.append(String.format(Locale.US, "%f,%f,%f", lastAz, lastRad, firstAz));

		return s.toString();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Returns null on success, else a non-null error message, on error original sector data is unchanged.

	public String decodeFromString(String theString) {

		ArrayList<Sector> newSectors = new ArrayList<Sector>();
		String errmsg = parseString(theString, newSectors);
		if (null == errmsg) {
			sectors = newSectors;
		}
		return errmsg;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Returns null on valid data, else a non-null error message.

	public static String validateString(String theString) {

		return parseString(theString, null);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Parse sector data from string, place in newSectors array if non-null, return a message string on error.  The
	// array may be modified even after an error return, it should be transient object and empty to start.

	private static String parseString(String theString, ArrayList<Sector> newSectors) {

		String[] sects = theString.split(";"), fields;
		int nSect = 0;
		double az, rad, firstAz = -1., lastAz = -1.;
		String str;
		Sector theSector;

		for (String sect : sects) {

			if (0 == sect.trim().length()) {
				continue;
			}
			nSect++;

			fields = sect.split(",");
			if (3 != fields.length) {
				return "Bad format in sector list";
			}

			az = -1.;
			str = fields[0].trim();
			if (str.length() > 0) {
				try {
					az = Double.parseDouble(str);
				} catch (NumberFormatException ne) {
				}
			}
			if ((az < 0.) || (az > 360.)) {
				if (firstAz < 0.) {
					return "Bad sector list, missing or bad start azimuth";
				} else {
					return "Bad sector list, missing or bad start azimuth after " + AppCore.formatAzimuth(lastAz);
				}
			}
			if (firstAz < 0.) {
				firstAz = az + 360.;
			} else {
				if (az != lastAz) {
					return "Bad sector list, out of sequence or gap after " + AppCore.formatAzimuth(lastAz);
				}
			}

			rad = MIN_DISTANCE - 1.;
			str = fields[1].trim();
			if (str.length() > 0) {
				try {
					rad = Double.parseDouble(str);
				} catch (NumberFormatException ne) {
				}
			}
			if ((rad < MIN_DISTANCE) || (rad > MAX_DISTANCE)) {
				return "Bad sector list, missing or bad radius at " + AppCore.formatAzimuth(az);
			}

			lastAz = -1.;
			str = fields[2].trim();
			if (str.length() > 0) {
				try {
					lastAz = Double.parseDouble(str);
				} catch (NumberFormatException ne) {
				}
			}
			if (lastAz <= az) {
				return "Bad sector list, missing or bad end azimuth at " + AppCore.formatAzimuth(az);
			}

			if ((lastAz - az) < 1.) {
				return "Bad sector list, sector at " + AppCore.formatAzimuth(az) + " spans less than 1 degree";
			}

			if (null != newSectors) {
				theSector = new Sector();
				theSector.azimuth = az;
				theSector.radius = rad;
				newSectors.add(theSector);
			}
		}

		if (nSect < 2) {
			return "Bad sector list, must have at least 2 sectors";
		}

		if (lastAz != firstAz) {
			return "Bad sector list, gap at start/end";
		}

		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean loadData(ErrorLogger errors) {

		if (null == key) {
			return false;
		}

		DbConnection db = DbCore.connectDb(dbID, errors);
		if (null == db) {
			return false;
		}

		sectors = new ArrayList<Sector>();
		Sector sector;
		String errmsg = null;

		try {
			db.query("SELECT latitude, longitude FROM geography WHERE geo_key = " + key);
			if (db.next()) {
				center.setLatLon(db.getDouble(1), db.getDouble(2));
			} else {
				errmsg = "Sectors geography data not found for key " + key + ".";
			}
			if (null == errmsg) {
				db.query("SELECT azimuth, radius FROM geo_sectors WHERE geo_key = " + key + " ORDER BY azimuth");
				while (db.next()) {
					sector = new Sector();
					sector.azimuth = db.getDouble(1);
					sector.radius = db.getDouble(2);
					sectors.add(sector);
				}
				if (sectors.size() < 2) {
					errmsg = "Bad sectors geography data for key " + key + ".";
				}
			}
		} catch (SQLException se) {
			errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
			db.reportError(se);
		}

		DbCore.releaseDb(db);

		if (null != errmsg) {
			if (null != errors) {
				errors.reportError(errmsg);
			}
			return false;
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public Geography duplicate() {

		GeoSectors newGeo = new GeoSectors(dbID);

		super.duplicateTo(newGeo);

		newGeo.center.setLatLon(center);

		if (null != sectors) {
			newGeo.sectors = new ArrayList<Sector>();
			for (Sector sector : sectors) {
				newGeo.sectors.add(sector.duplicate());
			}
		}

		return newGeo;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isDataValid(ErrorLogger errors) {

		if (!super.isDataValid(errors)) {
			return false;
		}

		if ((0. == center.latitude) || (0. == center.longitude)) {
			if (null != errors) {
				errors.reportValidationError("Sectors center latitude and longitude must be provided.");
			}
			return false;
		}

		if ((center.latitude < Source.LATITUDE_MIN) || (center.latitude > Source.LATITUDE_MAX)) {
			if (null != errors) {
				errors.reportValidationError("Bad sectors center latitude, must be " + Source.LATITUDE_MIN + " to " +
					Source.LATITUDE_MAX + " degrees.");
			}
			return false;
		}

		if ((center.longitude < Source.LONGITUDE_MIN) || (center.longitude > Source.LONGITUDE_MAX)) {
			if (null != errors) {
				errors.reportValidationError("Bad sectors center longitude, must be " + Source.LONGITUDE_MIN + " to " +
					Source.LONGITUDE_MAX + " degrees.");
			}
			return false;
		}

		return areSectorsValid(errors);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Objects may be used just to support editing of a sectors list without other properties, so the sectors list
	// validity check is broken out as a separate method.

	public boolean areSectorsValid(ErrorLogger errors) {

		if (sectors.size() < 2) {
			if (null != errors) {
				errors.reportValidationError("Bad sectors, must contain at least 2 sectors.");
			}
			return false;
		}

		double lastAz = -1.;
		for (Sector sector : sectors) {
			if (!sector.isDataValid(errors)) {
				return false;
			}
			if (sector.azimuth <= lastAz) {
				if (null != errors) {
					errors.reportValidationError("Bad sectors, azimuths duplicated or out of order.");
				}
				return false;
			}
			if ((sector.azimuth - lastAz) < 1.) {
				if (null != errors) {
					errors.reportValidationError("Bad sectors, sector spans less than 1 degree of azimuth.");
				}
				return false;
			}
			lastAz = sector.azimuth;
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Save the geography.  This simply deletes all then inserts all.

	protected String saveGeography(DbConnection db) throws SQLException {

		String errmsg = super.saveGeography(db, center, 0., 0., 0);
		if (null != errmsg) {
			return errmsg;
		}

		db.update("DELETE FROM geo_sectors WHERE geo_key = " + key);

		StringBuilder query = new StringBuilder("INSERT INTO geo_sectors VALUES");
		int startLength = query.length();
		String sep = " (";

		for (Sector sector : sectors) {

			query.append(sep);
			query.append(String.valueOf(key));
			query.append(',');
			query.append(String.valueOf(sector.azimuth));
			query.append(',');
			query.append(String.valueOf(sector.radius));

			if (query.length() > DbCore.MAX_QUERY_LENGTH) {
				query.append(')');
				db.update(query.toString());
				query.setLength(startLength);
				sep = " (";
			} else {
				sep = "),(";
			}
		}

		if (query.length() > startLength) {
			query.append(')');
			db.update(query.toString());
		}

		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// XML support.

	protected void writeAttributes(Writer xml) throws IOException {

		center.writeAttributes(xml);
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected boolean parseAttributes(String element, Attributes attrs, ErrorLogger errors) {

		return center.parseAttributes(element, attrs, errors);
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected boolean hasElements() {

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected void writeElements(Writer xml) throws IOException {

		for (Sector theSector : sectors) {
			xml.append("<SECTOR");
			theSector.writeAttributes(xml);
			xml.append("/>\n");
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected boolean parseElement(String element, Attributes attrs, String content, ErrorLogger errors) {

		if (!element.equals("SECTOR")) {
			return true;
		}

		Sector theSector = new Sector();
		if (!theSector.parseAttributes(element, attrs, errors)) {
			return false;
		}

		sectors.add(theSector);
		return true;
	}
}
