//
//  SourceFM.java
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.data;

import gov.fcc.tvstudy.core.*;

import java.util.*;
import java.sql.*;


//=====================================================================================================================
// Source subclass for an FM record.  See Source.java for details.

public class SourceFM extends Source {

	public static final int CHANNEL_MIN = 200;
	public static final int CHANNEL_MAX_NCE = 220;
	public static final int CHANNEL_MAX = 300;

	public static final double IBOC_FRACTION_MIN = 0.01;
	public static final double IBOC_FRACTION_MAX = 0.1;
	public static final double IBOC_FRACTION_DEF = 0.01;

	// Database properties (+ are local, others in superclass):

	//  key                                       Source key, unique within a database and always > 0.
	//  name                                      Composed from call sign, city, and state.
	// +facilityID                                Facility reference ID, may have any value including <= 0.
	//  service                                   Service, never null.
	//  isIBOC                                    True if station is operating IBOC (hybrid mode only).
	// +stationClass                              Class of station, ExtDbRecordFM.FM_CLASS_*.
	//  callSign                                  Station call sign, never null or empty.
	// +channel                                   Channel.
	//  city                                      City name, never null or empty.
	//  state                                     State code, never null or empty.
	//  country                                   Country, never null.
	// +status                                    Record status, never null but may be empty.
	//  fileNumber                                File number, never null, may be empty.
	//  location                                  Geographic location.
	//  heightAMSL                                Antenna height AMSL, meters.  From station data or original input.
	//  actualHeightAMSL                          Actual height AMSL used, derived/modified by study engine as needed.
	//  overallHAAT                               Antenna overall HAAT, meters.
	//  actualOverallHAAT                         Actual HAAT, derived by study engine if needed.
	//  peakERP                                   Peak ERP, kW.
	// +ibocFraction                              IBOC power as fraction of peak ERP.
	//  antennaID                                 Original antenna ID if available, else null.  Informational only.
	//  hasHorizontalPattern                      True if the source has horizontal pattern data, false for omni.
	//  horizontalPatternName                     Name for the pattern, i.e. antenna type, empty string for omni.
	//  horizontalPatternOrientation              Orientation of the horizontal pattern, degrees true.
	//  hasVerticalPattern                        True if the source has vertical pattern, false for generic or matrix.
	//  verticalPatternName                       Name for the pattern, empty string for generic.
	//  verticalPatternElectricalTilt             Vertical pattern electrical tilt, degrees of depression.
	//  verticalPatternMechanicalTilt             Vertical pattern mechancial tilt, degrees of depression.
	//  verticalPatternMechanicalTiltOrientation  Orientation of the vertical pattern mechanical tilt, degrees true.
	//  hasMatrixPattern                          True if source has a matrix pattern.
	//  matrixPatternName                         Name for the matrix pattern.
	//  useGenericVerticalPattern                 True if source can use generic vertical pattern if others don't exist.
	//  isLocked                                  True if source cannot be edited (it can be shared b/t scenarios).
	//  userRecordID                              >0 for a source based on a user-created primary record.
	//  extDbKey                                  External data key, or null for local record.
	//  extRecordID                               External data primary record key, or null for local records.
	// +serviceAreaMode                           Mode for defining the service area for desired coverage analysis.
	// +serviceAreaArg                            Service area contour mode argument value, varies with mode.
	// +serviceAreaCL                             Specific contour value, or -999. to use parameters.
	// +serviceAreaKey                            Service area geography key.

	public final int facilityID;
	public final boolean isIBOC;
	public final int stationClass;
	public final int channel;
	public final String status;
	public final double ibocFraction;

	public final int serviceAreaMode;
	public final double serviceAreaArg;
	public final double serviceAreaCL;
	public final int serviceAreaKey;


	//-----------------------------------------------------------------------------------------------------------------

	public SourceFM(String theDbID, String theDbName, int theKey, int theFacilityID, Service theService,
			boolean theIsIBOC, int theStationClass, String theCallSign, int theChannel, String theCity,
			String theState, Country theCountry, String theStatus, String theFileNumber, double theLatitude,
			double theLongitude, double theHeightAMSL, double theActualHeightAMSL, double theOverallHAAT,
			double theActualOverallHAAT, double thePeakERP, double theIBOCFraction, String theAntennaID,
			boolean theHasHorizontalPattern, String theHorizontalPatternName, double theHorizontalPatternOrientation,
			boolean theHasVerticalPattern, String theVerticalPatternName, double theVerticalPatternElectricalTilt,
			double theVerticalPatternMechanicalTilt, double theVerticalPatternMechanicalTiltOrientation,
			boolean theHasMatrixPattern, String theMatrixPatternName, boolean theUseGenericVerticalPattern,
			boolean theIsLocked, Integer theUserRecordID, Integer theExtDbKey, String theExtRecordID,
			int theModCount, int theServiceAreaMode, double theServiceAreaArg, double theServiceAreaCL,
			int theServiceAreaKey, String theAttributes) {

		super(theDbID, theDbName, RECORD_TYPE_FM, theKey, (theCallSign + " " + theChannel + " " + theStatus),
			theService, theCallSign, theCity, theState, theCountry, theFileNumber, theLatitude, theLongitude,
			theHeightAMSL, theActualHeightAMSL, theOverallHAAT, theActualOverallHAAT, thePeakERP, theAntennaID,
			theHasHorizontalPattern, theHorizontalPatternName, theHorizontalPatternOrientation, theHasVerticalPattern,
			theVerticalPatternName, theVerticalPatternElectricalTilt, theVerticalPatternMechanicalTilt,
			theVerticalPatternMechanicalTiltOrientation, theHasMatrixPattern, theMatrixPatternName,
			theUseGenericVerticalPattern, theIsLocked, theUserRecordID, theExtDbKey, theExtRecordID, theModCount,
			theAttributes);

		facilityID = theFacilityID;
		isIBOC = theIsIBOC;
		stationClass = theStationClass;
		channel = theChannel;
		status = theStatus;
		ibocFraction = theIBOCFraction;

		serviceAreaMode = theServiceAreaMode;
		serviceAreaArg = theServiceAreaArg;
		serviceAreaCL = theServiceAreaCL;
		serviceAreaKey = theServiceAreaKey;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Retrieve FM sources from a database.  Optionally can be restricted by a query WHERE clause.

	public static void getSources(DbConnection db, String theDbID, String theDbName, List<Source> sources)
			throws SQLException {
		getSources(db, theDbID, theDbName, null, sources);
	}

	public static void getSources(DbConnection db, String theDbID, String theDbName, String theQuery,
			List<Source> sources) throws SQLException {

		int theKey;
		Service theService;
		Country theCountry;
		Integer theRecID, theExtDbKey;

		db.setDatabase(theDbName);

		String whrStr = "";
		if ((null != theQuery) && (theQuery.length() > 0)) {
			whrStr = "AND (" + theQuery + ") ";
		}

		db.query(
		"SELECT " +
			"source_key, " +
			"facility_id, " +
			"service_key, " +
			"is_iboc, " +
			"station_class, " +
			"call_sign, " +
			"channel, " +
			"city, " +
			"state, " +
			"country_key, " +
			"status, " +
			"file_number, " +
			"latitude, " +
			"longitude, " +
			"height_amsl, " +
			"actual_height_amsl, " +
			"overall_haat, " +
			"actual_overall_haat, " +
			"peak_erp, " +
			"iboc_fraction, " +
			"antenna_id, " +
			"has_horizontal_pattern, " +
			"horizontal_pattern_name, " +
			"horizontal_pattern_orientation, " +
			"has_vertical_pattern, " +
			"vertical_pattern_name, " +
			"vertical_pattern_electrical_tilt, " +
			"vertical_pattern_mechanical_tilt, " +
			"vertical_pattern_mechanical_tilt_orientation, " +
			"has_matrix_pattern, " +
			"matrix_pattern_name, " +
			"use_generic_vertical_pattern, " +
			"locked, " +
			"user_record_id, " +
			"ext_db_key, " +
			"ext_record_id, " +
			"mod_count, " +
			"service_area_mode, " +
			"service_area_arg, " +
			"service_area_cl, " +
			"service_area_key, " +
			"attributes " +
		"FROM " +
			"source " +
		"WHERE " +
			"record_type = " + RECORD_TYPE_FM + " " +
			whrStr +
		"ORDER BY " +
			"source_key");

		while (db.next()) {

			theService = Service.getService(db.getInt(3));
			if (null == theService) {
				continue;
			}
			if (theService.serviceType.recordType != RECORD_TYPE_FM) {
				continue;
			}

			theCountry = Country.getCountry(db.getInt(10));
			if (null == theCountry) {
				continue;
			}

			theKey = db.getInt(34);
			if (0 == theKey) {
				theRecID = null;
			} else {
				theRecID = Integer.valueOf(theKey);
			}

			theKey = db.getInt(35);
			if (0 == theKey) {
				theExtDbKey = null;
			} else {
				theExtDbKey = Integer.valueOf(theKey);
			}

			sources.add(new SourceFM(
				theDbID,
				theDbName,
				db.getInt(1),
				db.getInt(2),
				theService,
				db.getBoolean(4),
				db.getInt(5),
				db.getString(6),
				db.getInt(7),
				db.getString(8),
				db.getString(9),
				theCountry,
				db.getString(11),
				db.getString(12),
				db.getDouble(13),
				db.getDouble(14),
				db.getDouble(15),
				db.getDouble(16),
				db.getDouble(17),
				db.getDouble(18),
				db.getDouble(19),
				db.getDouble(20),
				db.getString(21),
				db.getBoolean(22),
				db.getString(23),
				db.getDouble(24),
				db.getBoolean(25),
				db.getString(26),
				db.getDouble(27),
				db.getDouble(28),
				db.getDouble(29),
				db.getBoolean(30),
				db.getString(31),
				db.getBoolean(32),
				db.getBoolean(33),
				theRecID,
				theExtDbKey,
				db.getString(36),
				db.getInt(37),
				db.getInt(38),
				db.getDouble(39),
				db.getDouble(40),
				db.getInt(41),
				db.getString(42)));
		}
	}
}
