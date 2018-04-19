//
//  GeoPointSet.java
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
// Model for editing point sets for use in point-mode studies.

public class GeoPointSet extends Geography {

	// Used to set defaults in editor.

	public static final KeyedRecord GENERIC_ANTENNA = new KeyedRecord(0, AntPattern.GENERIC_ANTENNA_NAME);

	// type (super)  Always GEO_TYPE_POINT_SET.
	// key (super)   Key, null for a new set never saved.
	// name (super)  Set name.
	// points        Array of points.

	public ArrayList<StudyPoint> points;


	//-----------------------------------------------------------------------------------------------------------------
	// See superclass.

	public GeoPointSet(String theDbID) {

		super(theDbID, GEO_TYPE_POINT_SET);

		points = new ArrayList<StudyPoint>();
	}


	//=================================================================================================================
	// StudyPoint class.  If antenna.key is 0, generic OET-69 antennas are used.  If useAntennaOrientation is true the
	// receive antenna orientation is fixed at the antennaOrientation value, otherwise the usual behavior of orienting
	// the receive antenna toward the current desired station being analyzed is used.

	public static class StudyPoint extends GeoPoint {

		public String name;
		public double receiveHeight;
		public KeyedRecord antenna;
		public boolean useAntennaOrientation;
		public double antennaOrientation;


		//-------------------------------------------------------------------------------------------------------------

		public StudyPoint duplicate() {

			StudyPoint newPoint = new StudyPoint();

			newPoint.name = name;
			newPoint.setLatLon(this);
			newPoint.receiveHeight = receiveHeight;
			newPoint.antenna = antenna;
			newPoint.useAntennaOrientation = useAntennaOrientation;
			newPoint.antennaOrientation = antennaOrientation;

			return newPoint;
		}


		//-------------------------------------------------------------------------------------------------------------

		public boolean isDataValid(ErrorLogger errors) {

			if (null == name) {
				name = "";
			}
			name = name.trim();
			if (name.length() > DbCore.NAME_MAX_LENGTH) {
				name = name.substring(0, DbCore.NAME_MAX_LENGTH);
			}

			if ((0. == latitude) || (0. == longitude)) {
				if (null != errors) {
					errors.reportValidationError("Point latitude and longitude must be provided.");
				}
				return false;
			}

			if ((latitude < Source.LATITUDE_MIN) || (latitude > Source.LATITUDE_MAX)) {
				if (null != errors) {
					errors.reportValidationError("Bad point latitude, must be " + Source.LATITUDE_MIN + " to " +
						Source.LATITUDE_MAX + " degrees.");
				}
				return false;
			}

			if ((longitude < Source.LONGITUDE_MIN) || (longitude > Source.LONGITUDE_MAX)) {
				if (null != errors) {
					errors.reportValidationError("Bad point longitude, must be " + Source.LONGITUDE_MIN + " to " +
						Source.LONGITUDE_MAX + " degrees.");
				}
				return false;
			}

			if ((receiveHeight < MIN_RECEIVE_HEIGHT) || (receiveHeight > MAX_RECEIVE_HEIGHT)) {
				if (null != errors) {
					errors.reportValidationError("Bad point receive height, must be between " + MIN_RECEIVE_HEIGHT +
						" and " + MAX_RECEIVE_HEIGHT);
				}
				return false;
			}

			if ((null == antenna) || (antenna.key < 0)) {
				if (null != errors) {
					errors.reportValidationError("Receive antenna must be selected.");
				}
				return false;
			}

			if (useAntennaOrientation) {
				if ((antennaOrientation < 0.) || (antennaOrientation >= 360.)) {
					if (null != errors) {
						errors.reportValidationError("Bad antenna orientation, must be 0 to less than 360 degrees.");
					}
					return false;
				}
			}

			return true;
		}


		//-------------------------------------------------------------------------------------------------------------
		// XML support.

		public void writeAttributes(Writer xml) throws IOException {

			super.writeAttributes(xml);

			xml.append(" NAME=\"");
			xml.append(AppCore.xmlclean(name));
			xml.append("\" HEIGHT=\"");
			xml.append(AppCore.formatHeight(receiveHeight));
			xml.append('"');

			if ((null != antenna) && (antenna.key > 0)) {
				xml.append(" ANTENNA=\"");
				xml.append(AppCore.xmlclean(antenna.name));
				xml.append('"');
			}

			if (useAntennaOrientation) {
				xml.append(" ORIENT=\"");
				xml.append(AppCore.formatAzimuth(antennaOrientation));
				xml.append('"');
			}
		}


		//-------------------------------------------------------------------------------------------------------------
		// Receive antennas are matched by name, but failures are non-fatal, point will just revert to generic.  The
		// current list of antennas in the database is cached with a short timeout from last use so it's scope should
		// be just one XML file import.  Names not found are logged once per file.

		private static HashMap<String, KeyedRecord> receivePatterns = new HashMap<String, KeyedRecord>();
		private static HashSet<String> patternsNotFound = new HashSet<String>();
		private static long lastPatternSearch = 0;
		private static String lastSearchDbID = "";
		private static final long PATTERN_CACHE_TIMEOUT = 2000;   // milliseconds

		public boolean parseAttributes(String element, Attributes attrs, String theDbID, ErrorLogger errors) {

			if (!super.parseAttributes(element, attrs, errors)) {
				return false;
			}

			name = attrs.getValue("NAME");
			if (null == name) {
				name = "";
			}
			name = name.trim();
			if (name.length() > DbCore.NAME_MAX_LENGTH) {
				name = name.substring(0, DbCore.NAME_MAX_LENGTH);
			}

			String str = attrs.getValue("HEIGHT");
			receiveHeight = MIN_RECEIVE_HEIGHT - 1.;
			if (null != str) {
				try {
					receiveHeight = Double.parseDouble(str);
				} catch (NumberFormatException nfe) {
				}
			}
			if ((receiveHeight < MIN_RECEIVE_HEIGHT) || (receiveHeight >= MAX_RECEIVE_HEIGHT)) {
				if (null != errors) {
					errors.reportError("Bad HEIGHT attribute in " + element + " tag.");
				}
				return false;
			}

			str = attrs.getValue("ANTENNA");
			if (null != str) {
				str = str.trim();
				if (0 == str.length()) {
					str = null;
				}
			}
			if (null != str) {
				long now = System.currentTimeMillis();
				if (!lastSearchDbID.equals(theDbID) || ((now - lastPatternSearch) > PATTERN_CACHE_TIMEOUT)) {
					receivePatterns.clear();
					patternsNotFound.clear();
					ArrayList<KeyedRecord> patList = AntPattern.getReceiveAntennaList(theDbID);
					if (null != patList) {
						for (KeyedRecord pat : patList) {
							receivePatterns.put(pat.name, pat);
						}
					}
					receivePatterns.put(GENERIC_ANTENNA.name, GENERIC_ANTENNA);
					lastSearchDbID = theDbID;
				}
				lastPatternSearch = now;
				antenna = receivePatterns.get(str);
				if (null == antenna) {
					if ((null != errors) && patternsNotFound.add(str)) {
						errors.logMessage("Receive antenna '" + str + "' not found, reverting to generic.");
					}
					antenna = GENERIC_ANTENNA;
				}
			} else {
				antenna = GENERIC_ANTENNA;
			}

			str = attrs.getValue("ORIENT");
			if (null != str) {
				useAntennaOrientation = true;
				antennaOrientation = -1.;
				try {
					antennaOrientation = Double.parseDouble(str);
				} catch (NumberFormatException nfe) {
				}
				if ((antennaOrientation < 0.) || (antennaOrientation >= 360.)) {
					if (null != errors) {
						errors.reportError("Bad ORIENT attribute in " + element + " tag.");
					}
					return false;
				}
			}

			return true;
		}
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

		points = new ArrayList<StudyPoint>();
		StudyPoint point;
		String errmsg = null;

		try {

			db.query("SELECT " +
				"geo_point_set.point_name," +
				"geo_point_set.latitude," +
				"geo_point_set.longitude," +
				"geo_point_set.receive_height," +
				"geo_point_set.antenna_key," +
				"receive_antenna_index.name," +
				"geo_point_set.antenna_orientation " +
			"FROM " +
				"geo_point_set " +
				"LEFT JOIN receive_antenna_index USING (antenna_key) " +
			"WHERE " +
				"geo_key = " + key + " ORDER BY point_name");

			int antKey;
			String antName;

			while (db.next()) {

				point = new StudyPoint();

				point.name = db.getString(1);

				point.setLatLon(db.getDouble(2), db.getDouble(3));

				point.receiveHeight = db.getDouble(4);

				antKey = db.getInt(5);
				antName = db.getString(6);
				if ((0 == antKey) || (null == antName)) {
					point.antenna = GENERIC_ANTENNA;
				} else {
					point.antenna = new KeyedRecord(antKey, antName);
				}

				point.antennaOrientation = db.getDouble(7);
				if (point.antennaOrientation >= 0.) {
					point.useAntennaOrientation = true;
				} else {
					point.antennaOrientation = 0.;
				}

				points.add(point);
			}

			if (points.isEmpty()) {
				errmsg = "Point set geography data not found for key " + key + ".";
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

		GeoPointSet newGeo = new GeoPointSet(dbID);

		super.duplicateTo(newGeo);

		if (null != points) {
			newGeo.points = new ArrayList<StudyPoint>();
			for (StudyPoint point : points) {
				newGeo.points.add(point.duplicate());
			}
		}

		return newGeo;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isDataValid(ErrorLogger errors) {

		if (!super.isDataValid(errors)) {
			return false;
		}

		if (points.size() < 1) {
			if (null != errors) {
				errors.reportValidationError("Point set must contain at least 1 point.");
			}
			return false;
		}

		for (StudyPoint point : points) {
			if (!point.isDataValid(errors)) {
				return false;
			}
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Save the point set.  See superclass.  This simply deletes all then inserts all, no individual point updates.
	// This also has to update the key map indicating which receive antennas are in use by this geography.

	protected String saveGeography(DbConnection db) throws SQLException {

		String errmsg = super.saveGeography(db, new GeoPoint(), 0., 0., 0.);
		if (null != errmsg) {
			return errmsg;
		}

		db.update("DELETE FROM geo_point_set WHERE geo_key = " + key);

		HashSet<Integer> antKeySet = new HashSet<Integer>();

		StringBuilder query = new StringBuilder("INSERT INTO geo_point_set VALUES");
		int startLength = query.length(), antKey;
		String sep = " (";

		for (StudyPoint point : points) {

			antKey = 0;
			if ((null != point.antenna) && (point.antenna.key > 0)) {
				antKey = point.antenna.key;
				antKeySet.add(Integer.valueOf(antKey));
			}

			query.append(sep);
			query.append(String.valueOf(key));
			query.append(",'");
			query.append(db.clean(point.name));
			query.append("',");
			query.append(String.valueOf(point.latitude));
			query.append(',');
			query.append(String.valueOf(point.longitude));
			query.append(',');
			query.append(String.valueOf(point.receiveHeight));
			query.append(',');
			query.append(String.valueOf(antKey));
			query.append(',');
			query.append(point.useAntennaOrientation ? String.valueOf(point.antennaOrientation) : "-1");

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

		db.update("LOCK TABLES geography_receive_antenna WRITE");

		db.update("DELETE FROM geography_receive_antenna WHERE geo_key = " + key);

		if (!antKeySet.isEmpty()) {

			query = new StringBuilder("INSERT INTO geography_receive_antenna VALUES");
			startLength = query.length();
			sep = " (";

			for (Integer theKey : antKeySet) {

				query.append(sep);
				query.append(String.valueOf(key));
				query.append(',');
				query.append(String.valueOf(theKey));

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
		}

		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// XML support.

	protected boolean hasElements() {

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected void writeElements(Writer xml) throws IOException {

		for (StudyPoint thePoint : points) {
			xml.append("<POINT");
			thePoint.writeAttributes(xml);
			xml.append("/>\n");
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected boolean parseElement(String element, Attributes attrs, String content, ErrorLogger errors) {

		if (!element.equals("POINT")) {
			return true;
		}

		StudyPoint thePoint = new StudyPoint();
		if (!thePoint.parseAttributes(element, attrs, dbID, errors)) {
			return false;
		}

		points.add(thePoint);
		return true;
	}
}
