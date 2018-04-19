//
//  Source.java
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.data;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.geo.*;

import java.util.*;
import java.sql.*;


//=====================================================================================================================
// Data representation class for a source.  This is an abstract superclass, concrete subclasses SourceTV, SourceWL,
// and SourceFM are used to represent various different record types.  In the database all of the different source
// types are in the same table, but in the app each has a separate set of classes for model and editor.  However a
// number of properties are common and so are present here in the superclass, including pattern-related properties,
// so pattern retrieval code is here.  Also this class has the database table creation and copy code.

public abstract class Source extends KeyedRecord {

	// Record types, corresponding to subclasses.

	public static final int RECORD_TYPE_TV = 1;
	public static final int RECORD_TYPE_WL = 2;
	public static final int RECORD_TYPE_FM = 3;

	// Constants for value range checking and input processing.

	public static final double LATITUDE_MIN = -73.;
	public static final double LATITUDE_MAX = 73.;

	public static final double LONGITUDE_MIN = -180.;
	public static final double LONGITUDE_MAX = 180.;

	public static final double DISTANCE_MIN = 50.;
	public static final double DISTANCE_MAX = 200.;

	public static final double HEIGHT_MIN = -1000.;
	public static final double HEIGHT_MAX = 10000.;

	public static final double HEIGHT_DERIVE = -999.;
	public static final String HEIGHT_DERIVE_LABEL = "(derive)";

	public static final double ERP_MIN = 0.00001;
	public static final double ERP_MAX = 5000.;
	public static final double ERP_DEF = 0.001;

	public static final double TIME_DELAY_MIN = -500.;
	public static final double TIME_DELAY_MAX = 500.;

	// Maximum lengths for various database table fields.  The object properties in many cases are in the subclasses,
	// but since the table structure itself is common the lengths are all defined here.

	public static final int MAX_CALL_SIGN_LENGTH = 12;
	public static final int MAX_SECTOR_ID_LENGTH = 3;
	public static final int MAX_CITY_LENGTH = 20;
	public static final int MAX_STATE_LENGTH = 2;
	public static final int MAX_STATUS_LENGTH = 6;
	public static final int MAX_FILE_NUMBER_LENGTH = 255;
	public static final int MAX_PATTERN_NAME_LENGTH = 255;

	// Constants for defining service area.

	public static final int SERVAREA_CONTOUR_DEFAULT = 0;
	public static final int SERVAREA_CONTOUR_FCC = 1;
	public static final int SERVAREA_CONTOUR_LR_PERCENT = 2;
	public static final int SERVAREA_CONTOUR_LR_RUN_ABOVE = 3;
	public static final int SERVAREA_CONTOUR_LR_RUN_BELOW = 4;
	public static final int SERVAREA_GEOGRAPHY_FIXED = 5;
	public static final int SERVAREA_GEOGRAPHY_RELOCATED = 6;
	public static final int SERVAREA_NO_BOUNDS = 7;
	public static final int SERVAREA_CONTOUR_FCC_ADD_DIST = 8;
	public static final int SERVAREA_CONTOUR_FCC_ADD_PCNT = 9;
	public static final int SERVAREA_RADIUS = 10;

	public static final double SERVAREA_ARGUMENT_MIN = 0.;
	public static final double SERVAREA_ARGUMENT_MAX = 500.;

	public static final double SERVAREA_CL_MIN = 0.;
	public static final double SERVAREA_CL_MAX = 120.;

	public static final double SERVAREA_CL_DEFAULT = -999.;
	public static final String SERVAREA_CL_DEFAULT_LABEL = "(default)";

	// Keys for the attributes map, here for convenience, attributes are opaque in this class.  Keys that start with
	// TRANSIENT_ATTR_PREFIX are dropped when exporting, see SourceEditData.

	public static final String TRANSIENT_ATTR_PREFIX = "-";

	public static final String ATTR_SEQUENCE_DATE = "sequenceDate";
	public static final String ATTR_LICENSEE = "licensee";
	public static final String ATTR_IS_SHARING_HOST = "isSharingHost";
	public static final String ATTR_IS_BASELINE = "isBaseline";

	public static final String ATTR_IS_PRE_BASELINE = "-isPreBaseline";
	public static final String ATTR_IS_PROPOSAL = "-isProposal";

	// Database ID, also retain the database name for pattern retrieval.

	public final String dbID;
	public final String dbName;

	// Record type.

	public final int recordType;

	// Database properties:

	// key (super)                               Source key, unique within a database and always > 0.
	// name (super)                              Composed from other properties, see subclasses.
	// service                                   Service, never null.
	// callSign                                  Station call sign, never null or empty.
	// city                                      City name, never null, may be empty.
	// state                                     State code, never null, may be empty.
	// country                                   Country, never null.
	// fileNumber                                File or other reference number, never null, may be empty.
	// location                                  Geographic location.
	// heightAMSL                                Antenna height AMSL, meters.  From station data or original input.
	//                                             May be the flag value HEIGHT_DERIVE to derive HAMSL from HAAT.
	// actualHeightAMSL                          Actual height AMSL used, derived/modified by study engine as needed.
	//                                             This is a study engine cache field, it is display-only in the UI.
	// overallHAAT                               Antenna overall HAAT, meters.  May be HEIGHT_DERIVE to compute.
	// actualOverallHAAT                         Actual HAAT, derived by study engine as needed.
	// peakERP                                   Peak ERP, kW.
	// antennaID                                 Original antenna ID if available, else null.  Informational only.
	// hasHorizontalPattern                      True if the source has horizontal pattern data.
	// horizontalPatternName                     Name for the pattern, i.e. antenna type.
	// horizontalPatternOrientation              Orientation of the horizontal pattern, degrees true.
	// hasVerticalPattern                        True if the source has vertical pattern.
	// verticalPatternName                       Name for the vertical pattern.
	// verticalPatternElectricalTilt             Vertical pattern electrical tilt, degrees of depression.
	// verticalPatternMechanicalTilt             Vertical pattern mechancial tilt, degrees of depression.
	// verticalPatternMechanicalTiltOrientation  Orientation of the vertical pattern mechanical tilt, degrees true.
	// hasMatrixPattern                          True if source has a matrix pattern.
	// matrixPatternName                         Name for the matrix pattern.
	// useGenericVerticalPattern                 True if source can use generic vertical pattern if others don't exist.
	// isLocked                                  True if source cannot be edited (it can be shared b/t scenarios).
	// userRecordID                              >0 for a source based on a user-created primary record.  Locked
	//                                             sources always have an association with an immutable primary record,
	//                                             so either userRecordID, or extDbKey and extRecordID, are defined.
	//                                             Unlocked sources may or may not have a primary record, but a source
	//                                             with no primary record is always unlocked.
	// extDbKey                                  External data key, or null for local record.  This may differ from
	//                                             the key on a study containing this source.  If so the source is
	//                                             treated as permanent in that study database, since the data it came
	//                                             from may no longer exist.  See StudyEditData.
	// extRecordID                               External data primary record key, or null for local records.

	public final Service service;
	public final String callSign;
	public final String city;
	public final String state;
	public final Country country;
	public final String fileNumber;
	public final GeoPoint location;
	public final double heightAMSL;
	public final double actualHeightAMSL;
	public final double overallHAAT;
	public final double actualOverallHAAT;
	public final double peakERP;
	public final String antennaID;
	public final boolean hasHorizontalPattern;
	public final String horizontalPatternName;
	public final double horizontalPatternOrientation;
	public final boolean hasVerticalPattern;
	public final String verticalPatternName;
	public final double verticalPatternElectricalTilt;
	public final double verticalPatternMechanicalTilt;
	public final double verticalPatternMechanicalTiltOrientation;
	public final boolean hasMatrixPattern;
	public final String matrixPatternName;
	public final boolean useGenericVerticalPattern;
	public final boolean isLocked;
	public final Integer userRecordID;
	public final Integer extDbKey;
	public final String extRecordID;

	// Database record modification count.

	public final int modCount;

	// Literal string representation of the attributes field, opaque in this class, see SourceEditData.

	public final String attributes;


	//-----------------------------------------------------------------------------------------------------------------
	// Text names for record types.

	public static String getRecordTypeName(int theType) {

		switch (theType) {
			case RECORD_TYPE_TV: {
				return "TV";
			}
			case RECORD_TYPE_WL: {
				return "Wireless";
			}
			case RECORD_TYPE_FM: {
				return "FM";
			}
			default: {
				return "??";
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// List of record types, optionally filtered by study type.

	public static ArrayList<KeyedRecord> getRecordTypes() {
		return getRecordTypes(0);
	}

	public static ArrayList<KeyedRecord> getRecordTypes(int studyType) {

		ArrayList<KeyedRecord> result = new ArrayList<KeyedRecord>();

		if ((0 == studyType) || Study.isRecordTypeAllowed(studyType, RECORD_TYPE_TV)) {
			result.add(new KeyedRecord(RECORD_TYPE_TV, getRecordTypeName(RECORD_TYPE_TV)));
		}

		if ((0 == studyType) || Study.isRecordTypeAllowed(studyType, RECORD_TYPE_WL)) {
			result.add(new KeyedRecord(RECORD_TYPE_WL, getRecordTypeName(RECORD_TYPE_WL)));
		}

		if ((0 == studyType) || Study.isRecordTypeAllowed(studyType, RECORD_TYPE_FM)) {
			result.add(new KeyedRecord(RECORD_TYPE_FM, getRecordTypeName(RECORD_TYPE_FM)));
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Only for use by subclasses.

	protected Source(String theDbID, String theDbName, int theRecordType, int theKey, String theName,
			Service theService, String theCallSign, String theCity, String theState, Country theCountry,
			String theFileNumber, double theLatitude, double theLongitude, double theHeightAMSL,
			double theActualHeightAMSL, double theOverallHAAT, double theActualOverallHAAT, double thePeakERP,
			String theAntennaID, boolean theHasHorizontalPattern, String theHorizontalPatternName,
			double theHorizontalPatternOrientation, boolean theHasVerticalPattern, String theVerticalPatternName,
			double theVerticalPatternElectricalTilt, double theVerticalPatternMechanicalTilt,
			double theVerticalPatternMechanicalTiltOrientation, boolean theHasMatrixPattern,
			String theMatrixPatternName, boolean theUseGenericVerticalPattern, boolean theIsLocked,
			Integer theUserRecordID, Integer theExtDbKey, String theExtRecordID, int theModCount,
			String theAttributes) {

		super(theKey, theName);

		dbID = theDbID;
		dbName = theDbName;

		recordType = theRecordType;

		service = theService;
		callSign = theCallSign;
		city = theCity;
		state = theState;
		country = theCountry;
		fileNumber = theFileNumber;
		location = new GeoPoint();
		location.setLatLon(theLatitude, theLongitude);
		heightAMSL = theHeightAMSL;
		actualHeightAMSL = theActualHeightAMSL;
		overallHAAT = theOverallHAAT;
		actualOverallHAAT = theActualOverallHAAT;
		peakERP = thePeakERP;
		antennaID = theAntennaID;
		hasHorizontalPattern = theHasHorizontalPattern;
		horizontalPatternName = theHorizontalPatternName;
		horizontalPatternOrientation = theHorizontalPatternOrientation;
		hasVerticalPattern = theHasVerticalPattern;
		verticalPatternName = theVerticalPatternName;
		verticalPatternElectricalTilt = theVerticalPatternElectricalTilt;
		verticalPatternMechanicalTilt = theVerticalPatternMechanicalTilt;
		verticalPatternMechanicalTiltOrientation = theVerticalPatternMechanicalTiltOrientation;
		hasMatrixPattern = theHasMatrixPattern;
		matrixPatternName = theMatrixPatternName;
		useGenericVerticalPattern = theUseGenericVerticalPattern;
		isLocked = theIsLocked;
		userRecordID = theUserRecordID;
		extDbKey = theExtDbKey;
		extRecordID = theExtRecordID;

		modCount = theModCount;

		attributes = theAttributes;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get horizontal pattern data for source.  Returns null on error, or if hasHorizontalPattern is false.

	public AntPattern getHorizontalPattern() {
		return getHorizontalPattern(null);
	}

	public AntPattern getHorizontalPattern(ErrorLogger errors) {

		if (!hasHorizontalPattern) {
			return null;
		}

		ArrayList<AntPattern.AntPoint> thePoints = null;

		DbConnection db = DbCore.connectDb(dbID, errors);
		if (null != db) {
			try {

				db.setDatabase(dbName);

				db.query(
				"SELECT " +
					"azimuth, " +
					"relative_field " +
				"FROM " +
					"source_horizontal_pattern " +
				"WHERE " +
					"source_key = " + key + " " +
				"ORDER BY 1");

				thePoints = new ArrayList<AntPattern.AntPoint>();

				while (db.next()) {
					thePoints.add(new AntPattern.AntPoint(db.getDouble(1), db.getDouble(2)));
				}

				DbCore.releaseDb(db);

				if (thePoints.isEmpty()) {
					if (null != errors) {
						errors.reportError("Azimuth pattern data not found for source " + key);
					}
					thePoints = null;
				}

			} catch (SQLException se) {
				DbCore.releaseDb(db);
				thePoints = null;
				DbConnection.reportError(errors, se);
			}
		}

		if (null == thePoints) {
			return null;
		}

		return new AntPattern(dbID, AntPattern.PATTERN_TYPE_HORIZONTAL, horizontalPatternName, thePoints);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Retrieve the vertical pattern data for this source.

	public AntPattern getVerticalPattern() {
		return getVerticalPattern(null);
	}

	public AntPattern getVerticalPattern(ErrorLogger errors) {

		if (!hasVerticalPattern) {
			return null;
		}

		ArrayList<AntPattern.AntPoint> thePoints = null;

		DbConnection db = DbCore.connectDb(dbID, errors);
		if (null != db) {
			try {

				db.setDatabase(dbName);

				db.query(
				"SELECT " +
					"depression_angle, " +
					"relative_field " +
				"FROM " +
					"source_vertical_pattern " +
				"WHERE " +
					"source_key = " + key + " " +
				"ORDER BY 1");

				thePoints = new ArrayList<AntPattern.AntPoint>();

				while (db.next()) {
					thePoints.add(new AntPattern.AntPoint(db.getDouble(1), db.getDouble(2)));
				}

				DbCore.releaseDb(db);

				if (thePoints.isEmpty()) {
					if (null != errors) {
						errors.reportError("Elevation pattern data not found for source " + key);
					}
					thePoints = null;
				}

			} catch (SQLException se) {
				DbCore.releaseDb(db);
				thePoints = null;
				DbConnection.reportError(errors, se);
			}
		}

		if (null == thePoints) {
			return null;
		}

		return new AntPattern(dbID, AntPattern.PATTERN_TYPE_VERTICAL, verticalPatternName, thePoints);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get vertical matrix pattern data for source.  Returns null on error, or if hasMatrixPattern is false.

	public AntPattern getMatrixPattern() {
		return getMatrixPattern(null);
	}

	public AntPattern getMatrixPattern(ErrorLogger errors) {

		if (!hasMatrixPattern) {
			return null;
		}

		ArrayList<AntPattern.AntSlice> theSlices = null;

		DbConnection db = DbCore.connectDb(dbID, errors);
		if (null != db) {
			try {

				db.setDatabase(dbName);

				db.query(
				"SELECT " +
					"azimuth, " +
					"depression_angle, " +
					"relative_field " +
				"FROM " +
					 "source_matrix_pattern " +
				"WHERE " +
					"source_key = " + key + " " +
				"ORDER BY 1, 2");

				ArrayList<AntPattern.AntPoint> thePoints = null;
				double theAz, lastAz = AntPattern.AZIMUTH_MIN - 1.;

				while (db.next()) {

					theAz = db.getDouble(1);

					if (theAz != lastAz) {
						if (null == theSlices) {
							theSlices = new ArrayList<AntPattern.AntSlice>();
						}
						thePoints = new ArrayList<AntPattern.AntPoint>();
						theSlices.add(new AntPattern.AntSlice(theAz, thePoints));
						lastAz = theAz;
					}

					thePoints.add(new AntPattern.AntPoint(db.getDouble(2), db.getDouble(3)));
				}

				DbCore.releaseDb(db);

				if (null == theSlices) {
					if (null != errors) {
						errors.reportError("Matrix pattern data not found for source " + key);
					}
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

		return new AntPattern(dbID, matrixPatternName, AntPattern.PATTERN_TYPE_VERTICAL, theSlices);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create tables in a new database.

	public static void createTables(DbConnection db, String theDbName) throws SQLException {

		db.setDatabase(theDbName);

		db.update(
		"CREATE TABLE source (" +
			"source_key INT NOT NULL PRIMARY KEY," +
			"record_type INT NOT NULL," +
			"needs_update BOOLEAN NOT NULL," +
			"mod_count INT NOT NULL," +
			"facility_id INT NOT NULL," +
			"service_key INT NOT NULL," +
			"is_drt BOOLEAN NOT NULL," +
			"is_iboc BOOLEAN NOT NULL," +
			"station_class INT NOT NULL," +
			"call_sign CHAR(12) NOT NULL," +
			"sector_id CHAR(3) NOT NULL," +
			"channel INT NOT NULL," +
			"city CHAR(20) NOT NULL," +
			"state CHAR(2) NOT NULL," +
			"country_key INT NOT NULL," +
			"zone_key INT NOT NULL," +
			"status CHAR(6) NOT NULL," +
			"file_number VARCHAR(255) NOT NULL," +
			"signal_type_key INT NOT NULL," +
			"frequency_offset_key INT NOT NULL," +
			"emission_mask_key INT NOT NULL," +
			"latitude DOUBLE NOT NULL," +
			"longitude DOUBLE NOT NULL," +
			"dts_maximum_distance DOUBLE NOT NULL," +
			"dts_sectors VARCHAR(5000) NOT NULL," +
			"height_amsl FLOAT NOT NULL," +
			"actual_height_amsl FLOAT NOT NULL," +
			"height_agl DOUBLE NOT NULL," +
			"overall_haat FLOAT NOT NULL," +
			"actual_overall_haat FLOAT NOT NULL," +
			"peak_erp FLOAT NOT NULL," +
			"contour_erp DOUBLE NOT NULL," +
			"iboc_fraction FLOAT NOT NULL," +
			"antenna_id VARCHAR(255)," +
			"has_horizontal_pattern BOOLEAN NOT NULL," +
			"horizontal_pattern_name VARCHAR(255) NOT NULL," +
			"horizontal_pattern_orientation FLOAT NOT NULL," +
			"has_vertical_pattern BOOLEAN NOT NULL," +
			"vertical_pattern_name VARCHAR(255) NOT NULL," +
			"vertical_pattern_electrical_tilt FLOAT NOT NULL," +
			"vertical_pattern_mechanical_tilt FLOAT NOT NULL," +
			"vertical_pattern_mechanical_tilt_orientation FLOAT NOT NULL," +
			"has_matrix_pattern BOOLEAN NOT NULL," +
			"matrix_pattern_name VARCHAR(255) NOT NULL," +
			"use_generic_vertical_pattern BOOLEAN NOT NULL," +
			"site_number INT NOT NULL," +
			"locked BOOLEAN NOT NULL," +
			"user_record_id INT NOT NULL," +
			"ext_db_key INT NOT NULL," +
			"ext_record_id VARCHAR(255)," +
			"original_source_key INT NOT NULL," +
			"parent_source_key INT NOT NULL," +
			"service_area_mode INT NOT NULL," +
			"service_area_arg DOUBLE NOT NULL," +
			"service_area_cl DOUBLE NOT NULL," +
			"service_area_key INT NOT NULL," +
			"dts_time_delay DOUBLE NOT NULL," +
			"attributes VARCHAR(20000) NOT NULL DEFAULT ''" +
		")");

		db.update(
		"CREATE TABLE source_horizontal_pattern (" +
			"source_key INT NOT NULL," +
			"azimuth FLOAT NOT NULL," +
			"relative_field FLOAT NOT NULL," +
			"INDEX (source_key)" +
		")");

		db.update(
		"CREATE TABLE source_vertical_pattern (" +
			"source_key INT NOT NULL," +
			"depression_angle FLOAT NOT NULL," +
			"relative_field FLOAT NOT NULL," +
			"INDEX (source_key)" +
		")");

		db.update(
		"CREATE TABLE source_matrix_pattern (" +
			"source_key INT NOT NULL," +
			"azimuth FLOAT NOT NULL," +
			"depression_angle FLOAT NOT NULL," +
			"relative_field FLOAT NOT NULL," +
			"INDEX (source_key)" +
		")");
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create all tables and copy contents from another database.

	public static void copyTables(DbConnection db, String theDbName, String fromDbName) throws SQLException {

		createTables(db, theDbName);

		db.update(
		"INSERT INTO source (" +
			"source_key," +
			"record_type," +
			"needs_update," +
			"mod_count," +
			"facility_id," +
			"service_key," +
			"is_drt," +
			"is_iboc," +
			"station_class," +
			"call_sign," +
			"sector_id," +
			"channel," +
			"city," +
			"state," +
			"country_key," +
			"zone_key," +
			"status," +
			"file_number," +
			"signal_type_key," +
			"frequency_offset_key," +
			"emission_mask_key," +
			"latitude," +
			"longitude," +
			"dts_maximum_distance," +
			"dts_sectors," +
			"height_amsl," +
			"actual_height_amsl," +
			"height_agl," +
			"overall_haat," +
			"actual_overall_haat," +
			"peak_erp," +
			"contour_erp," +
			"iboc_fraction," +
			"antenna_id," +
			"has_horizontal_pattern," +
			"horizontal_pattern_name," +
			"horizontal_pattern_orientation," +
			"has_vertical_pattern," +
			"vertical_pattern_name," +
			"vertical_pattern_electrical_tilt," +
			"vertical_pattern_mechanical_tilt," +
			"vertical_pattern_mechanical_tilt_orientation," +
			"has_matrix_pattern," +
			"matrix_pattern_name," +
			"use_generic_vertical_pattern," +
			"site_number," +
			"locked," +
			"user_record_id," +
			"ext_db_key," +
			"ext_record_id," +
			"original_source_key," +
			"parent_source_key," +
			"service_area_mode," +
			"service_area_arg," +
			"service_area_cl," +
			"service_area_key," +
			"dts_time_delay," +
			"attributes) " +
		"SELECT " +
			"source_key," +
			"record_type," +
			"needs_update," +
			"mod_count," +
			"facility_id," +
			"service_key," +
			"is_drt," +
			"is_iboc," +
			"station_class," +
			"call_sign," +
			"sector_id," +
			"channel," +
			"city," +
			"state," +
			"country_key," +
			"zone_key," +
			"status," +
			"file_number," +
			"signal_type_key," +
			"frequency_offset_key," +
			"emission_mask_key," +
			"latitude," +
			"longitude," +
			"dts_maximum_distance," +
			"dts_sectors," +
			"height_amsl," +
			"actual_height_amsl," +
			"height_agl," +
			"overall_haat," +
			"actual_overall_haat," +
			"peak_erp," +
			"contour_erp," +
			"iboc_fraction," +
			"antenna_id," +
			"has_horizontal_pattern," +
			"horizontal_pattern_name," +
			"horizontal_pattern_orientation," +
			"has_vertical_pattern," +
			"vertical_pattern_name," +
			"vertical_pattern_electrical_tilt," +
			"vertical_pattern_mechanical_tilt," +
			"vertical_pattern_mechanical_tilt_orientation," +
			"has_matrix_pattern," +
			"matrix_pattern_name," +
			"use_generic_vertical_pattern," +
			"site_number," +
			"locked," +
			"user_record_id," +
			"ext_db_key," +
			"ext_record_id," +
			"original_source_key," +
			"parent_source_key," +
			"service_area_mode," +
			"service_area_arg," +
			"service_area_cl," +
			"service_area_key," +
			"dts_time_delay," +
			"attributes " +
		"FROM " +
			fromDbName + ".source");

		db.update(
		"INSERT INTO source_horizontal_pattern (" +
			"source_key," +
			"azimuth," +
			"relative_field) " +
		"SELECT " +
			"source_key," +
			"azimuth," +
			"relative_field " +
		"FROM " +
			fromDbName + ".source_horizontal_pattern");

		db.update(
		"INSERT INTO source_vertical_pattern (" +
			"source_key," +
			"depression_angle," +
			"relative_field) " +
		"SELECT " +
			"source_key," +
			"depression_angle," +
			"relative_field " +
		"FROM " +
			fromDbName + ".source_vertical_pattern");

		db.update(
		"INSERT INTO source_matrix_pattern (" +
			"source_key," +
			"azimuth," +
			"depression_angle," +
			"relative_field) " +
		"SELECT " +
			"source_key," +
			"azimuth," +
			"depression_angle," +
			"relative_field " +
		"FROM " +
			fromDbName + ".source_matrix_pattern");
	}
}
