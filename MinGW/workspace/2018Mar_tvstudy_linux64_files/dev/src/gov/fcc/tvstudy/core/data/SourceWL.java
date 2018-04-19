//
//  SourceWL.java
//  TVStudy
//
//  Copyright (c) 2015-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.data;

import gov.fcc.tvstudy.core.*;

import java.util.*;
import java.sql.*;


//=====================================================================================================================
// Data record subclass for a wireless source.  See details in Source.java.

public class SourceWL extends Source {

	// Database properties (+ are local, others in superclass):

	//  key                                       Source key, unique within a database and always > 0.
	//  name                                      Composed from other information.
	//  service                                   Service, never null.
	//  callSign                                  Site ID string, never null or empty.
	// +sectorID                                  Sector ID string, never null, may be empty.
	//  city                                      City name, never null, may be empty.
	//  state                                     State code, never null, may be empty.
	//  country                                   Country, never null.
	//  fileNumber                                Reference number, never null, may be empty.
	//  location                                  Geographic location.
	//  heightAMSL                                Antenna height AMSL, meters.
	//  actualHeightAMSL                          Actual height AMSL used, derived/modified by study engine as needed.
	//  overallHAAT                               Antenna overall HAAT, meters.
	//  actualOverallHAAT                         Actual HAAT, derived by study engine if needed.
	//  peakERP                                   Peak ERP, kW.
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

	public final String sectorID;


	//-----------------------------------------------------------------------------------------------------------------

	public SourceWL(String theDbID, String theDbName, int theKey, Service theService, String theCallSign,
			String theSectorID, String theCity, String theState, Country theCountry, String theFileNumber,
			double theLatitude, double theLongitude, double theHeightAMSL, double theActualHeightAMSL,
			double theOverallHAAT, double theActualOverallHAAT, double thePeakERP, String theAntennaID,
			boolean theHasHorizontalPattern, String theHorizontalPatternName, double theHorizontalPatternOrientation,
			boolean theHasVerticalPattern, String theVerticalPatternName, double theVerticalPatternElectricalTilt,
			double theVerticalPatternMechanicalTilt, double verticalPatternMechanicalTiltOrientation,
			boolean theHasMatrixPattern, String theMatrixPatternName, boolean theUseGenericVerticalPattern,
			boolean theIsLocked, Integer theUserRecordID, Integer theExtDbKey, String theExtRecordID,
			int theModCount, String theAttributes) {

		super(theDbID, theDbName, RECORD_TYPE_WL, theKey, (theCallSign + " " + theSectorID), theService, theCallSign,
			theCity, theState, theCountry, theFileNumber, theLatitude, theLongitude, theHeightAMSL,
			theActualHeightAMSL, theOverallHAAT, theActualOverallHAAT, thePeakERP, theAntennaID,
			theHasHorizontalPattern, theHorizontalPatternName, theHorizontalPatternOrientation, theHasVerticalPattern,
			theVerticalPatternName, theVerticalPatternElectricalTilt, theVerticalPatternMechanicalTilt,
			verticalPatternMechanicalTiltOrientation, theHasMatrixPattern, theMatrixPatternName,
			theUseGenericVerticalPattern, theIsLocked, theUserRecordID, theExtDbKey, theExtRecordID, theModCount,
			theAttributes);

		sectorID = theSectorID;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Retrieve wireless sources from a database, optionally restricted by a search clause.

	public static void getSources(DbConnection db, String theDbID, String theDbName, List<Source> sources)
			throws SQLException {
		getSources(db, theDbID, theDbName, null, sources);
	}

	public static void getSources(DbConnection db, String theDbID, String theDbName, String theQuery,
			List<Source> sources) throws SQLException {

		int theKey, i;
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
			"service_key, " +
			"call_sign, " +
			"sector_id, " +
			"city, " +
			"state, " +
			"country_key, " +
			"file_number, " +
			"latitude, " +
			"longitude, " +
			"height_amsl, " +
			"actual_height_amsl, " +
			"overall_haat, " +
			"actual_overall_haat, " +
			"peak_erp, " +
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
			"attributes " +
		"FROM " +
			"source " +
		"WHERE " +
			"record_type = " + RECORD_TYPE_WL + " " +
			whrStr +
		"ORDER BY " +
			"source_key");

		while (db.next()) {

			theService = Service.getService(db.getInt(2));
			if (null == theService) {
				continue;
			}
			if (theService.serviceType.recordType != RECORD_TYPE_WL) {
				continue;
			}

			theCountry = Country.getCountry(db.getInt(7));
			if (null == theCountry) {
				continue;
			}

			theKey = db.getInt(29);
			if (0 == theKey) {
				theRecID = null;
			} else {
				theRecID = Integer.valueOf(theKey);
			}

			theKey = db.getInt(30);
			if (0 == theKey) {
				theExtDbKey = null;
			} else {
				theExtDbKey = Integer.valueOf(theKey);
			}

			sources.add(new SourceWL(
				theDbID,
				theDbName,
				db.getInt(1),
				theService,
				db.getString(3),
				db.getString(4),
				db.getString(5),
				db.getString(6),
				theCountry,
				db.getString(8),
				db.getDouble(9),
				db.getDouble(10),
				db.getDouble(11),
				db.getDouble(12),
				db.getDouble(13),
				db.getDouble(14),
				db.getDouble(15),
				db.getString(16),
				db.getBoolean(17),
				db.getString(18),
				db.getDouble(19),
				db.getBoolean(20),
				db.getString(21),
				db.getDouble(22),
				db.getDouble(23),
				db.getDouble(24),
				db.getBoolean(25),
				db.getString(26),
				db.getBoolean(27),
				db.getBoolean(28),
				theRecID,
				theExtDbKey,
				db.getString(31),
				db.getInt(32),
				db.getString(33)));
		}
	}
}
