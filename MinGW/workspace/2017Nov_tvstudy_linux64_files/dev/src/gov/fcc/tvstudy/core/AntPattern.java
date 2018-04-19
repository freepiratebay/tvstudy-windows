//
//  AntPattern.java
//  TVStudy
//
//  Copyright (c) 2016-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core;

import gov.fcc.tvstudy.core.data.*;

import java.util.*;
import java.sql.*;


//=====================================================================================================================
// Mutable data model for all types of pattern data.  This can contain either azimuth or elevation patterns, and both
// can be "matrix" patterns with multiple azimuth/elevation slices.  In a matrix elevation pattern the slices are at
// varying azimuths, in a matrix azimuth pattern the slices are at varying frequencies.

public class AntPattern {

	// Constants for value range checking and input processing.

	public static final double AZIMUTH_MIN = 0.;
	public static final double AZIMUTH_MAX = 359.999;
	public static final double AZIMUTH_ROUND = 1000.;

	public static final double FREQUENCY_MIN = 10.;
	public static final double FREQUENCY_MAX = 5000.;

	public static final double DEPRESSION_MIN = -90.;
	public static final double DEPRESSION_MAX = 90.;
	public static final double DEPRESSION_ROUND = 1000.;

	public static final double FIELD_MIN = 0.001;
	public static final double FIELD_MAX = 1.;
	public static final double FIELD_ROUND = 1000.;

	public static final double FIELD_MAX_CHECK = 0.977;

	public static final int PATTERN_REQUIRED_POINTS = 2;

	public static final double TILT_MIN = -10.;
	public static final double TILT_MAX = 11.1;

	public static final double GAIN_MIN = 0.;
	public static final double GAIN_MAX = 60.;

	// Type constants.

	public static final int PATTERN_TYPE_HORIZONTAL = 1;   // Transmit azimuth pattern, always non-matrix.
	public static final int PATTERN_TYPE_VERTICAL = 2;     // Transmit elevation pattern, may be matrix by azimuth.
	public static final int PATTERN_TYPE_RECEIVE = 3;      // Receive azimuth pattern, may be matrix by frequency.

	// Reserved names.

	public static final String NEW_ANTENNA_NAME = "(new)";
	public static final String GENERIC_ANTENNA_NAME = "(generic)";

	// Properties.  Only one or the other of points or slices is non-null.  Note the key is only used for receive
	// antenna patterns, it is null on others.  Gain is only used in receive pattern mode.

	public final String dbID;

	public final int type;
	public Integer key;
	public String name;

	public double gain;

	private ArrayList<AntPoint> points;
	private TreeMap<Double, AntSlice> slices;


	//=================================================================================================================
	// Data class for points.  The angle may be azimuth in degrees true or vertical angle in degrees of depression.

	public static class AntPoint {

		public double angle;
		public double relativeField;


		//-------------------------------------------------------------------------------------------------------------

		public AntPoint(double theAngle, double theRelativeField) {

			angle = theAngle;
			relativeField = theRelativeField;
		}


		//-------------------------------------------------------------------------------------------------------------

		public AntPoint copy() {

			return new AntPoint(angle, relativeField);
		}
	}


	//=================================================================================================================
	// Data class for slices in a matrix pattern.  The pattern may be an elevation pattern, in which case value is an
	// azimuth, or a receive pattern, in which case value is a frequency.

	public static class AntSlice {

		public double value;
		public ArrayList<AntPoint> points;


		//-------------------------------------------------------------------------------------------------------------

		public AntSlice(double theValue) {

			value = theValue;
			points = new ArrayList<AntPoint>();
		}


		//-------------------------------------------------------------------------------------------------------------

		public AntSlice(double theValue, ArrayList<AntPoint> thePoints) {

			value = theValue;
			points = thePoints;
		}


		//-------------------------------------------------------------------------------------------------------------

		public AntSlice copy() {

			ArrayList<AntPoint> newPoints = new ArrayList<AntPoint>();
			for (AntPoint thePoint : points) {
				newPoints.add(thePoint.copy());
			}
			return new AntSlice(value, newPoints);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public AntPattern(String theDbID, int theType, String theName) {

		dbID = theDbID;
		type = theType;
		name = theName;
		points = new ArrayList<AntPoint>();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public AntPattern(String theDbID, int theType, String theName, ArrayList<AntPoint> thePoints) {

		dbID = theDbID;
		type = theType;
		name = theName;
		points = thePoints;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// A horizontal pattern cannot be a matrix.  Note varying argument order to prevent name clash.

	public AntPattern(String theDbID, String theName, int theType, ArrayList<AntSlice> theSlices) {

		dbID = theDbID;
		type = theType;
		name = theName;
		if ((PATTERN_TYPE_HORIZONTAL == type) || (theSlices.size() < 2)) {
			if (!theSlices.isEmpty()) {
				points = theSlices.get(0).points;
			} else {
				points = new ArrayList<AntPoint>();
			}
		} else {
			slices = new TreeMap<Double, AntSlice>();
			for (AntSlice theSlice : theSlices) {
				slices.put(Double.valueOf(theSlice.value), theSlice);
			}
		}
	}


	//-------------------------------------------------------------------------------------------------------------

	public AntPattern copy() {

		AntPattern newPattern;

		if (null != points) {

			ArrayList<AntPoint> newPoints = new ArrayList<AntPoint>();
			for (AntPoint thePoint : points) {
				newPoints.add(thePoint.copy());
			}
			newPattern = new AntPattern(dbID, type, name, newPoints);

		} else {

			ArrayList<AntSlice> newSlices = new ArrayList<AntSlice>();
			for (AntSlice theSlice : slices.values()) {
				newSlices.add(theSlice.copy());
			}
			newPattern = new AntPattern(dbID, name, type, newSlices);
		}

		newPattern.key = key;
		newPattern.gain = gain;

		return newPattern;
	}

			
	//-----------------------------------------------------------------------------------------------------------------

	public boolean isSimple() {

		return (null != points);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The points array and point objects are modified directly by the editor.

	public ArrayList<AntPoint> getPoints() {

		return points;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isMatrix() {

		return (null != slices);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public double minimumValue() {

		if (null != slices) {
			return slices.firstEntry().getValue().value;
		}
		return -1.;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public double maximumValue() {

		if (null != slices) {
			return slices.lastEntry().getValue().value;
		}
		return -1.;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The editor does not use this, it's for the save code.  Slice objects are not modified directly.

	public Collection<AntSlice> getSlices() {

		if (null == slices) {
			return null;
		}
		return slices.values();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// When converting a simple pattern to a matrix, a slice value must be provided for the existing pattern plus a
	// value for the new second slice.  A horizontal pattern cannot be a matrix.  The values must be different.

	public boolean convertToMatrix(Double firstValue, Double secondValue) {

		if ((PATTERN_TYPE_HORIZONTAL == type) || (null == points) || firstValue.equals(secondValue)) {
			return false;
		}

		slices = new TreeMap<Double, AntSlice>();

		AntSlice theSlice = new AntSlice(firstValue.doubleValue(), points);
		slices.put(firstValue, theSlice);

		points = null;

		theSlice = new AntSlice(secondValue.doubleValue());
		slices.put(secondValue, theSlice);

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public ArrayList<Double> getSliceValues() {

		if (null == slices) {
			return null;
		}

		ArrayList<Double> theValues = new ArrayList<Double>();
		for (AntSlice theSlice : slices.values()) {
			theValues.add(Double.valueOf(theSlice.value));
		}
		return theValues;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean containsSlice(Double theValue) {

		if (null == slices) {
			return false;
		}

		return slices.containsKey(theValue);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public ArrayList<AntPoint> getSlicePoints(Double theValue) {

		if (null == slices) {
			return null;
		}

		AntSlice theSlice = slices.get(theValue);
		if (null == theSlice) {
			return null;
		}
		return theSlice.points;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Changing the slice value can't be done directly by the editor since the map has to be updated.  If the slice
	// does not exist or the new value already exists this fails.

	public boolean changeSliceValue(Double oldValue, Double newValue) {

		if ((null == slices) || !slices.containsKey(oldValue) || slices.containsKey(newValue)) {
			return false;
		}

		AntSlice theSlice = slices.remove(oldValue);
		theSlice.value = newValue.doubleValue();
		slices.put(newValue, theSlice);
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// If the slice already exists do nothing.  Adding a slice to a simple pattern will fail, see convertToMatrix().

	public boolean addSlice(Double newValue) {

		if (null == slices) {
			return false;
		}

		if (!slices.containsKey(newValue)) {
			slices.put(newValue, new AntSlice(newValue.doubleValue()));
		}
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Removing the next-to-last slice converts to a simple pattern.

	public boolean removeSlice(Double theValue) {

		if (null == slices) {
			return false;
		}

		boolean result = (null != slices.remove(theValue));
		if (1 == slices.size()) {
			points = slices.firstEntry().getValue().points;
			slices = null;
		}
		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check validity.

	public boolean isDataValid() {
		return isDataValid(null);
	}

	public boolean isDataValid(ErrorLogger errors) {

		switch (type) {

			case PATTERN_TYPE_HORIZONTAL: {

				if (points.size() < PATTERN_REQUIRED_POINTS) {
					if (null != errors) {
						errors.reportValidationError("Bad azimuth pattern, must have " + PATTERN_REQUIRED_POINTS +
							" or more points.");
					}
					return false;
				}

				double lastAz = AZIMUTH_MIN - 1.;

				for (AntPoint thePoint : points) {
					if ((thePoint.angle < AZIMUTH_MIN) || (thePoint.angle > AZIMUTH_MAX)) {
						if (null != errors) {
							errors.reportValidationError("Bad pattern point azimuth, must be " + AZIMUTH_MIN + " to " +
								AZIMUTH_MAX + ".");
						}
						return false;
					}
					if (thePoint.angle <= lastAz) {
						if (null != errors) {
							errors.reportValidationError("Bad azimuth pattern, duplicate or out-of-order points.");
						}
						return false;
					}
					lastAz = thePoint.angle;
					if ((thePoint.relativeField < FIELD_MIN) || (thePoint.relativeField > FIELD_MAX)) {
						if (null != errors) {
							errors.reportValidationError("Bad pattern point relative field, must be " + FIELD_MIN +
								" to " + FIELD_MAX + ".");
						}
						return false;
					}
				}

				return true;
			}

			case PATTERN_TYPE_VERTICAL: {

				Collection<AntSlice> theSlices;

				if (null == slices) {

					if (points.size() < PATTERN_REQUIRED_POINTS) {
						if (null != errors) {
							errors.reportValidationError("Bad elevation pattern, must have " +
								PATTERN_REQUIRED_POINTS + " or more points.");
						}
						return false;
					}

					theSlices = new ArrayList<AntSlice>();
					theSlices.add(new AntSlice(AZIMUTH_MIN, points));

				} else {

					if (slices.size() < PATTERN_REQUIRED_POINTS) {
						if (null != errors) {
							errors.reportValidationError("Bad matrix elevation pattern, must have " +
								PATTERN_REQUIRED_POINTS + " or more azimuths.");
						}
						return false;
					}

					theSlices = slices.values();
				}

				double lastDep = 0.;

				for (AntSlice theSlice : theSlices) {

					if ((theSlice.value < AZIMUTH_MIN) || (theSlice.value > AZIMUTH_MAX)) {
						if (null != errors) {
							errors.reportValidationError("Bad matrix elevation pattern azimuth, must be " +
								AZIMUTH_MIN + " to " + AZIMUTH_MAX + ".");
						}
						return false;
					}
					if (theSlice.points.size() < PATTERN_REQUIRED_POINTS) {
						if (null != errors) {
							errors.reportValidationError("Bad elevation pattern, must have " +
								PATTERN_REQUIRED_POINTS + " or more points.");
						}
						return false;
					}

					lastDep = DEPRESSION_MIN - 1.;

					for (AntPoint thePoint : theSlice.points) {
						if ((thePoint.angle < DEPRESSION_MIN) || (thePoint.angle > DEPRESSION_MAX)) {
							if (null != errors) {
								errors.reportValidationError("Bad pattern point vertical angle, must be " +
									DEPRESSION_MIN + " to " + DEPRESSION_MAX + ".");
							}
							return false;
						}
						if (thePoint.angle <= lastDep) {
							if (null != errors) {
								errors.reportValidationError("Bad elevation pattern, duplicate or out-of-order " +
									"points.");
							}
							return false;
						}
						lastDep = thePoint.angle;
						if ((thePoint.relativeField < FIELD_MIN) || (thePoint.relativeField > FIELD_MAX)) {
							if (null != errors) {
								errors.reportValidationError("Bad pattern point relative field, must be " + FIELD_MIN +
									" to " + FIELD_MAX + ".");
							}
							return false;
						}
					}
				}
			
				return true;
			}

			// A receive pattern must have a 1.0 value somewhere in the data, but not necessarily in every slice of a
			// matrix.  This is not enforced for horizontal or vertical pattern types because data from CDBS/LMS may
			// not always contain a 1.0 however that data is considered valid anyway.  The 1.0 in those pattern types
			// will be required when the pattern is edited so that is checked by the editor.

			case PATTERN_TYPE_RECEIVE: {

				if ((gain < GAIN_MIN) || (gain > GAIN_MAX)) {
					if (null != errors) {
						errors.reportValidationError("Bad receive antenna gain, must be " + GAIN_MIN + " to " +
							GAIN_MAX + ".");
					}
					return false;
				}

				Collection<AntSlice> theSlices;

				if (null == slices) {

					if (points.size() < PATTERN_REQUIRED_POINTS) {
						if (null != errors) {
							errors.reportValidationError("Bad receive pattern, must have " + PATTERN_REQUIRED_POINTS +
								" or more points.");
						}
						return false;
					}

					theSlices = new ArrayList<AntSlice>();
					theSlices.add(new AntSlice(FREQUENCY_MIN, points));

				} else {

					if (slices.size() < PATTERN_REQUIRED_POINTS) {
						if (null != errors) {
							errors.reportValidationError("Bad receive pattern, must have " + PATTERN_REQUIRED_POINTS +
								" or more frequencies.");
						}
						return false;
					}

					theSlices = slices.values();
				}

				double lastAz = 0., patmax = 0.;

				for (AntSlice theSlice : theSlices) {

					if ((theSlice.value < FREQUENCY_MIN) || (theSlice.value > FREQUENCY_MAX)) {
						if (null != errors) {
							errors.reportValidationError("Bad receive pattern frequency, must be " + FREQUENCY_MIN +
								" to " + FREQUENCY_MAX + ".");
						}
						return false;
					}
					if (theSlice.points.size() < PATTERN_REQUIRED_POINTS) {
						if (null != errors) {
							errors.reportValidationError("Bad receive pattern, must have " + PATTERN_REQUIRED_POINTS +
								" or more points.");
						}
						return false;
					}

					lastAz = AZIMUTH_MIN - 1.;

					for (AntPoint thePoint : theSlice.points) {
						if ((thePoint.angle < AZIMUTH_MIN) || (thePoint.angle > AZIMUTH_MAX)) {
							if (null != errors) {
								errors.reportValidationError("Bad pattern point azimuth, must be " + AZIMUTH_MIN +
									" to " + AZIMUTH_MAX + ".");
							}
							return false;
						}
						if (thePoint.angle <= lastAz) {
							if (null != errors) {
								errors.reportValidationError("Bad receive pattern, duplicate or out-of-order points.");
							}
							return false;
						}
						lastAz = thePoint.angle;
						if ((thePoint.relativeField < FIELD_MIN) || (thePoint.relativeField > FIELD_MAX)) {
							if (null != errors) {
								errors.reportValidationError("Bad pattern point relative field, must be " + FIELD_MIN +
									" to " + FIELD_MAX + ".");
							}
							return false;
						}
						if (thePoint.relativeField > patmax) {
							patmax = thePoint.relativeField;
						}
					}
				}

				if (patmax < 1.) {
					if (null != errors) {
						errors.reportValidationError("Bad receive antenna, pattern must have a 1.");
					}
					return false;
				}

				return true;
			}
		}

		if (null != errors) {
			errors.reportValidationError("Bad pattern, unknown type.");
		}
		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Save, this can only be done on a receive pattern, caller should have checked validity.  This also checks for
	// indirect reference from running studies via geographies to defer the save; see comments in Geography.save().  

	public boolean save() {
		return save(null);
	}

	public boolean save(ErrorLogger errors) {

		if (PATTERN_TYPE_RECEIVE != type) {
			errors.reportError("Antenna save failed, invalid pattern type.");
			return false;
		}

		DbConnection db = DbCore.connectDb(dbID, errors);
		if (null == db) {
			return false;
		}

		String errmsg = null;

		try {

			db.update("LOCK TABLES receive_antenna_index WRITE, antenna_key_sequence WRITE, " +
				"receive_pattern WRITE, geography_receive_antenna WRITE, study_geography WRITE, study WRITE");

			if (null != key) {
				db.query(
				"SELECT " +
					"COUNT(*) " +
				"FROM " +
					"geography_receive_antenna " +
					"JOIN study_geography USING (geo_key)" +
					"JOIN study USING (study_key) " +
				"WHERE " +
					"(geography_receive_antenna.antenna_key = " + key + ") " +
					"AND (study.study_lock IN (" + Study.LOCK_RUN_EXCL + "," + Study.LOCK_RUN_SHARE + "))");
				if (db.next() && (db.getInt(1) > 0)) {
					errmsg = "Changes cannot be saved now, the antenna is in use by a running study.\n" +
						"Try again after study runs are finished.";
				}
			}

			if (null == errmsg) {
				db.query("SELECT antenna_key FROM receive_antenna_index WHERE UPPER(name) = '" +
					db.clean(name.toUpperCase()) + "'");
				if (db.next()) {
					if ((null == key) || (db.getInt(1) != key.intValue())) {
						errmsg = "Antenna name '" + name + "' already exists." +
						"\nkey = " + ((null == key) ? "null" : String.valueOf(key)) +
						"\nantenna_key = " + String.valueOf(db.getInt(1));
					}
				}
			}

			if (null == errmsg) {

				if (null == key) {
					db.update("UPDATE antenna_key_sequence SET antenna_key = antenna_key + 1");
					db.query("SELECT antenna_key FROM antenna_key_sequence");
					db.next();
					key = Integer.valueOf(db.getInt(1));
				} else {
					db.update("DELETE FROM receive_antenna_index WHERE antenna_key = " + key);
					db.update("DELETE FROM receive_pattern WHERE antenna_key = " + key);
				}

				db.update("INSERT INTO receive_antenna_index VALUES (" + key + ",'" + db.clean(name) + "'," +
					gain + ")");

				StringBuilder query = new StringBuilder("INSERT INTO receive_pattern VALUES");
				int startLength = query.length();
				String sep = " (";

				if (null != points) {
					for (AntPoint thePoint : points) {
						query.append(sep);
						query.append(String.valueOf(key));
						query.append(",-1,");
						query.append(String.valueOf(thePoint.angle));
						query.append(',');
						query.append(String.valueOf(thePoint.relativeField));
						if (query.length() > DbCore.MAX_QUERY_LENGTH) {
							query.append(')');
							db.update(query.toString());
							query.setLength(startLength);
							sep = " (";
						} else {
							sep = "),(";
						}
					}
				} else {
					for (AntSlice theSlice : slices.values()) {
						for (AntPoint thePoint : theSlice.points) {
							query.append(sep);
							query.append(String.valueOf(key));
							query.append(',');
							query.append(String.valueOf(theSlice.value));
							query.append(',');
							query.append(String.valueOf(thePoint.angle));
							query.append(',');
							query.append(String.valueOf(thePoint.relativeField));
							if (query.length() > DbCore.MAX_QUERY_LENGTH) {
								query.append(')');
								db.update(query.toString());
								query.setLength(startLength);
								sep = " (";
							} else {
								sep = "),(";
							}
						}
					}
				}
				if (query.length() > startLength) {
					query.append(')');
					db.update(query.toString());
				}
			}

		} catch (SQLException se) {
			errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
			db.reportError(se);
		}

		try {
			db.update("UNLOCK TABLES");
		} catch (SQLException se) {
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
	// Get a list of receive antenna patterns in a database.

	public static ArrayList<KeyedRecord> getReceiveAntennaList(String theDbID) {
		return getReceiveAntennaList(theDbID, null);
	}

	public static ArrayList<KeyedRecord> getReceiveAntennaList(String theDbID, ErrorLogger errors) {

		ArrayList<KeyedRecord> result = null;

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null != db) {
			try {

				result = new ArrayList<KeyedRecord>();

				db.query(
				"SELECT " +
					"antenna_key, " +
					"name " +
				"FROM " +
					 "receive_antenna_index " +
				"ORDER BY 2");

				while (db.next()) {
					result.add(new KeyedRecord(db.getInt(1), db.getString(2)));
				}

				DbCore.releaseDb(db);

			} catch (SQLException se) {
				DbCore.releaseDb(db);
				result = null;
				DbConnection.reportError(errors, se);
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get receive pattern data by key.  Returns null on error.

	public static AntPattern getReceiveAntenna(String theDbID, int theKey) {
		return getReceiveAntenna(theDbID, theKey, null);
	}

	public static AntPattern getReceiveAntenna(String theDbID, int theKey, ErrorLogger errors) {

		String theName = null;
		double theGain = 0.;
		ArrayList<AntSlice> theSlices = null;

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null != db) {
			try {

				db.query(
				"SELECT " +
					"name, " +
					"gain " +
				"FROM " +
					"receive_antenna_index " +
				"WHERE " +
					"antenna_key = " + theKey);

				if (db.next()) {

					theName = db.getString(1);
					theGain = db.getDouble(2);

					db.query(
					"SELECT " +
						"frequency, " +
						"azimuth, " +
						"relative_field " +
					"FROM " +
						 "receive_pattern " +
					"WHERE " +
						"antenna_key = " + theKey + " " +
					"ORDER BY 1, 2");

					ArrayList<AntPoint> thePoints = null;
					double theFreq, lastFreq = FREQUENCY_MIN - 1.;

					while (db.next()) {

						theFreq = db.getDouble(1);

						if (theFreq != lastFreq) {
							if (null == theSlices) {
								theSlices = new ArrayList<AntSlice>();
							}
							thePoints = new ArrayList<AntPoint>();
							theSlices.add(new AntSlice(theFreq, thePoints));
							lastFreq = theFreq;
						}

						thePoints.add(new AntPoint(db.getDouble(2), db.getDouble(3)));
					}
				}

				DbCore.releaseDb(db);

				if ((null == theSlices) && (null != errors)) {
					errors.reportError("Receive antenna not found for key " + theKey);
				}

			} catch (SQLException se) {
				DbCore.releaseDb(db);
				theSlices = null;
				DbConnection.reportError(errors, se);
			}
		}

		if (null == theSlices) {
			return null;
		}

		AntPattern thePattern;
		if (1 == theSlices.size()) {
			thePattern = new AntPattern(theDbID, PATTERN_TYPE_RECEIVE, theName, theSlices.get(0).points);
		} else {
			thePattern = new AntPattern(theDbID, theName, PATTERN_TYPE_RECEIVE, theSlices);
		}
		thePattern.key = Integer.valueOf(theKey);
		thePattern.gain = theGain;

		return thePattern;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Delete a receive antenna.  First check if it is in use by existing geographies, this just checks as-saved data,
	// the caller has to check active UI directly to find out about usage in unsaved state.

	public static boolean deleteReceiveAntenna(String theDbID, int theKey) {
		return deleteReceiveAntenna(theDbID, theKey, null);
	}

	public static boolean deleteReceiveAntenna(String theDbID, int theKey, ErrorLogger errors) {

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null == db) {
			return false;
		}

		String errmsg = null;
		int refCount = 0, errtyp = AppCore.ERROR_MESSAGE;

		try {

			db.update("LOCK TABLES receive_antenna_index WRITE, receive_pattern WRITE, " +
				"geography_receive_antenna WRITE");

			db.query("SELECT COUNT(*) FROM geography_receive_antenna WHERE antenna_key = " + theKey);
			db.next();
			refCount = db.getInt(1);

			if (0 == refCount) {

				db.update("DELETE FROM receive_antenna_index WHERE antenna_key = " + theKey);
				db.update("DELETE FROM receive_pattern WHERE antenna_key = " + theKey);

			} else {
				errmsg = "The antenna is in use and cannot be deleted.";
				errtyp = AppCore.WARNING_MESSAGE;
			}

		} catch (SQLException se) {
			errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
			db.reportError(se);
		}

		try {
			db.update("UNLOCK TABLES");
		} catch (SQLException se) {
			db.reportError(se);
		}

		DbCore.releaseDb(db);

		if (null != errmsg) {
			if (null != errors) {
				errors.reportError(errmsg, errtyp);
			}
			return false;
		}

		return true;
	}
}
