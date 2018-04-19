//
//  SourceTV.java
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.data;

import gov.fcc.tvstudy.core.*;

import java.util.*;
import java.sql.*;


//=====================================================================================================================
// Source subclass for representing a TV record.  See details in Source.java.

public class SourceTV extends Source {

	public static final int CHANNEL_MIN = 2;
	public static final int CHANNEL_MAX = 69;

	// Database properties (+ are local, others in superclass):

	//  key                                       Source key, unique within a database and always > 0.
	//  name                                      Composed from call sign, city, and state.
	// +facilityID                                Facility reference ID, may have any value including <= 0.
	//  service                                   Service, never null.
	// +isDRT                                     True for a DRT facility record.
	//  callSign                                  Station call sign, never null or empty.
	// +channel                                   Channel.
	//  city                                      City name, never null or empty.
	//  state                                     State code, never null or empty.
	//  country                                   Country, never null.
	// +zone                                      Zone for U.S. records, may be the null object for undefined.
	// +status                                    Record status, never null but may be empty.
	//  fileNumber                                File number, never null, may be empty.
	// +signalType                                Signal type for digital, null object for analog.
	// +frequencyOffset                           Frequency offset, null object for none.
	// +emissionMask                              Emission mask if applicable, else the null object.
	//  location                                  Geographic location.
	// +dtsMaximumDistance                        For a DTS parent source, distance radius for 73.626(c) check, this
	//                                              may be from station data or zero to trigger use of a table value.
	// +dtsSectors                                Alternative to dtsMaximumDistance, a serialized text definition of a
	//                                              sectors geography, see GeoSectors.  If non-empty this is preferred.
	//  heightAMSL                                Antenna height AMSL, meters.  From station data or original input.
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
	// +siteNumber                                Site identification number, may be from ext db or user-entered.  Any
	//                                              value >=0 except for DTS.  Must be 0 on DTS parent, in DTS sources
	//                                              list 0 is the reference facility, >0 is actual operating sources.
	//  isLocked                                  True if source cannot be edited (it can be shared b/t scenarios).
	//  userRecordID                              >0 for a source based on a user-created primary record.
	//  extDbKey                                  External data key, or null for local record.
	//  extRecordID                               External data primary record key, or null for local records.
	// +originalSourceKey                         For a replication source, the source key of original, or null.
	// +parentSourceKey                           For a DTS source, the source key of the parent, else null.
	// +dtsSources                                List of DTS sources for a DTS parent.
	// +serviceAreaMode                           Mode for defining the service area for desired coverage analysis.
	// +serviceAreaArg                            Service area contour mode argument value, varies with mode.
	// +serviceAreaCL                             Specific contour value, or -999. to use parameters.
	// +serviceAreaKey                            Service area geography key.
	// +dtsTimeDelay                              Time delay in microseconds for DTS source, for self-IX check.

	public final int facilityID;
	public final boolean isDRT;
	public final int channel;
	public final Zone zone;
	public final String status;
	public final SignalType signalType;
	public final FrequencyOffset frequencyOffset;
	public final EmissionMask emissionMask;
	public final double dtsMaximumDistance;
	public final String dtsSectors;
	public final int siteNumber;

	public final Integer originalSourceKey;
	public final Integer parentSourceKey;

	public final ArrayList<SourceTV> dtsSources;

	public final int serviceAreaMode;
	public final double serviceAreaArg;
	public final double serviceAreaCL;
	public final int serviceAreaKey;

	public final double dtsTimeDelay;

	// This is set true if (null != dtsSources), but currently getSources() does checks that ensure that will be true
	// if and only if (service.isDTS && (null == parentSourceKey)).

	public final boolean isParent;


	//-----------------------------------------------------------------------------------------------------------------

	public SourceTV(String theDbID, String theDbName, int theKey, int theFacilityID, Service theService,
			boolean theIsDRT, String theCallSign, int theChannel, String theCity, String theState, Country theCountry,
			Zone theZone, String theStatus, String theFileNumber, SignalType theSignalType,
			FrequencyOffset theFrequencyOffset, EmissionMask theEmissionMask, double theLatitude, double theLongitude,
			double theDTSMaximumDistance, String theDTSSectors, double theHeightAMSL, double theActualHeightAMSL,
			double theOverallHAAT, double theActualOverallHAAT, double thePeakERP, String theAntennaID,
			boolean theHasHorizontalPattern, String theHorizontalPatternName, double theHorizontalPatternOrientation,
			boolean theHasVerticalPattern, String theVerticalPatternName, double theVerticalPatternElectricalTilt,
			double theVerticalPatternMechanicalTilt, double theVerticalPatternMechanicalTiltOrientation,
			boolean theHasMatrixPattern, String theMatrixPatternName, boolean theUseGenericVerticalPattern,
			int theSiteNumber, boolean theIsLocked, Integer theUserRecordID, Integer theExtDbKey,
			String theExtRecordID, Integer theOriginalSourceKey, Integer theParentSourceKey,
			ArrayList<SourceTV> theDTSSources, int theModCount, int theServiceAreaMode, double theServiceAreaArg,
			double theServiceAreaCL, int theServiceAreaKey, double theDTSTimeDelay, String theAttributes) {

		super(theDbID, theDbName, RECORD_TYPE_TV, theKey, (theCallSign + " " + theChannel + " " + theStatus),
			theService, theCallSign, theCity, theState, theCountry, theFileNumber, theLatitude, theLongitude,
			theHeightAMSL, theActualHeightAMSL, theOverallHAAT, theActualOverallHAAT, thePeakERP, theAntennaID,
			theHasHorizontalPattern, theHorizontalPatternName, theHorizontalPatternOrientation, theHasVerticalPattern,
			theVerticalPatternName, theVerticalPatternElectricalTilt, theVerticalPatternMechanicalTilt,
			theVerticalPatternMechanicalTiltOrientation, theHasMatrixPattern, theMatrixPatternName,
			theUseGenericVerticalPattern, theIsLocked, theUserRecordID, theExtDbKey, theExtRecordID, theModCount,
			theAttributes);

		facilityID = theFacilityID;
		isDRT = theIsDRT;
		channel = theChannel;
		zone = theZone;
		status = theStatus;
		signalType = theSignalType;
		frequencyOffset = theFrequencyOffset;
		emissionMask = theEmissionMask;
		dtsMaximumDistance = theDTSMaximumDistance;
		dtsSectors = theDTSSectors;
		siteNumber = theSiteNumber;

		originalSourceKey = theOriginalSourceKey;
		parentSourceKey = theParentSourceKey;
		dtsSources = theDTSSources;
		isParent = (null != dtsSources);

		serviceAreaMode = theServiceAreaMode;
		serviceAreaArg = theServiceAreaArg;
		serviceAreaCL = theServiceAreaCL;
		serviceAreaKey = theServiceAreaKey;

		dtsTimeDelay = theDTSTimeDelay;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Retrieve TV sources from a database.  This takes two queries, one to get most of the sources, the other to get
	// just the DTS sources which are attached to a separate parent source.  The parent is really a pseudo-source in
	// that case, it acts as a placeholder and provides the DTS reference point coordinates and information for the
	// service area bounds, but many other fields in that record are not used.  First get the DTS sources, group into
	// lists by parent source key.  One source in each group is special, it represents the pre-DTS reference facility
	// that defines the service area bounding contour.  That record is identified by site_number = 0, all others will
	// always be >0, so the reference facility is first in the list as ordered.  Also among other DTS sources for the
	// same parent, site_number should be unique but that is not enforced here.

	// Optionally this may be restricted by a query WHERE clause.  That is a little inefficient right now because the
	// first query still loads all DTS secondaries unrestricted so those will be available for any DTS parent that
	// might appear in the restricted main query (search conditions apply to the parent not the secondaries).  But
	// it's not too bad given that DTS records are a relatively small subset in any given database, at least for the
	// moment.  It's not that hard to fix, do the main query first then build a list of parent keys to match and load
	// DTS secondaries after, but it's time I don't have right now.

	public static void getSources(DbConnection db, String theDbID, String theDbName, List<Source> sources)
			throws SQLException {
		getSources(db, theDbID, theDbName, null, sources);
	}

	public static void getSources(DbConnection db, String theDbID, String theDbName, String theQuery,
			List<Source> sources) throws SQLException {

		int theKey, lastParentKey = 0;
		Integer theRecID, theExtDbKey, theOriginalKey, theParentKey;
		String theExtRecID;
		Service theService;
		Country theCountry;

		ArrayList<SourceTV> theDTSSources = null;
		HashMap<Integer, ArrayList<SourceTV>> dtsMap = new HashMap<Integer, ArrayList<SourceTV>>();

		db.setDatabase(theDbName);

		db.query(
		"SELECT " +
			"source_key, " +
			"facility_id, " +
			"service_key, " +
			"call_sign, " +
			"channel, " +
			"city, " +
			"state, " +
			"country_key, " +
			"zone_key, " +
			"status, " +
			"file_number, " +
			"frequency_offset_key, " +
			"emission_mask_key, " +
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
			"site_number, " +
			"locked, " +
			"user_record_id, " +
			"ext_db_key, " +
			"ext_record_id, " +
			"original_source_key, " +
			"parent_source_key, " +
			"service_area_mode, " +
			"service_area_arg, " +
			"service_area_cl, " +
			"service_area_key, " +
			"dts_time_delay, " +
			"signal_type_key, " +
			"attributes " +
		"FROM " +
			"source " +
		"WHERE " +
			"record_type = " + RECORD_TYPE_TV + " " +
			"AND (parent_source_key > 0) " +
		"ORDER BY " +
			"parent_source_key, site_number, source_key");

		while (db.next()) {

			theService = Service.getService(db.getInt(3));
			if (null == theService) {
				continue;
			}
			if (theService.serviceType.recordType != RECORD_TYPE_TV) {
				continue;
			}

			theCountry = Country.getCountry(db.getInt(8));
			if (null == theCountry) {
				continue;
			}

			theKey = db.getInt(35);
			if (0 == theKey) {
				theRecID = null;
			} else {
				theRecID = Integer.valueOf(theKey);
			}

			theKey = db.getInt(36);
			if (0 == theKey) {
				theExtDbKey = null;
			} else {
				theExtDbKey = Integer.valueOf(theKey);
			}

			theKey = db.getInt(38);
			if (0 == theKey) {
				theOriginalKey = null;
			} else {
				theOriginalKey = Integer.valueOf(theKey);
			}

			theKey = db.getInt(39);
			theParentKey = Integer.valueOf(theKey);
			if (theKey != lastParentKey) {
				theDTSSources = new ArrayList<SourceTV>();
				dtsMap.put(theParentKey, theDTSSources);
				lastParentKey = theKey;
			}

			theDTSSources.add(new SourceTV(
				theDbID,
				theDbName,
				db.getInt(1),
				db.getInt(2),
				theService,
				false,
				db.getString(4),
				db.getInt(5),
				db.getString(6),
				db.getString(7),
				theCountry,
				Zone.getZone(db.getInt(9)),
				db.getString(10),
				db.getString(11),
				SignalType.getSignalType(db.getInt(45)),
				FrequencyOffset.getFrequencyOffset(db.getInt(12)),
				EmissionMask.getEmissionMask(db.getInt(13)),
				db.getDouble(14),
				db.getDouble(15),
				0.,
				"",
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
				db.getInt(33),
				db.getBoolean(34),
				theRecID,
				theExtDbKey,
				db.getString(37),
				theOriginalKey,
				theParentKey,
				null,
				0,
				db.getInt(40),
				db.getDouble(41),
				db.getDouble(42),
				db.getInt(43),
				db.getDouble(44),
				db.getString(46)));
		}

		// Now query everything else, this is all the non-DTS sources and the DTS parent pseudo-sources.

		int theSourceKey;

		String whrStr = "";
		if ((null != theQuery) && (theQuery.length() > 0)) {
			whrStr = "AND (" + theQuery + ") ";
		}

		db.query(
		"SELECT " +
			"source_key, " +
			"facility_id, " +
			"service_key, " +
			"call_sign, " +
			"channel, " +
			"city, " +
			"state, " +
			"country_key, " +
			"zone_key, " +
			"status, " +
			"file_number, " +
			"frequency_offset_key, " +
			"emission_mask_key, " +
			"latitude, " +
			"longitude, " +
			"dts_maximum_distance, " +
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
			"site_number, " +
			"locked, " +
			"user_record_id, " +
			"ext_db_key, " +
			"ext_record_id, " +
			"original_source_key, " +
			"mod_count, " +
			"is_drt, " +
			"service_area_mode, " +
			"service_area_arg, " +
			"service_area_cl, " +
			"service_area_key, " +
			"signal_type_key, " +
			"dts_sectors, " +
			"attributes " +
		"FROM " +
			"source " +
		"WHERE " +
			"record_type = " + RECORD_TYPE_TV + " " +
			"AND (parent_source_key = 0) " +
			whrStr +
		"ORDER BY " +
			"source_key");

		while (db.next()) {

			theSourceKey = db.getInt(1);

			theService = Service.getService(db.getInt(3));
			if (null == theService) {
				continue;
			}
			if (theService.serviceType.recordType != RECORD_TYPE_TV) {
				continue;
			}

			theCountry = Country.getCountry(db.getInt(8));
			if (null == theCountry) {
				continue;
			}

			// For a DTS parent get the sources list, if that is missing ignore the record.

			if (theService.isDTS) {
				theDTSSources = dtsMap.remove(Integer.valueOf(theSourceKey));
				if (null == theDTSSources) {
					continue;
				}
			} else {
				theDTSSources = null;
			}

			theKey = db.getInt(36);
			if (0 == theKey) {
				theRecID = null;
			} else {
				theRecID = Integer.valueOf(theKey);
			}

			theKey = db.getInt(37);
			if (0 == theKey) {
				theExtDbKey = null;
			} else {
				theExtDbKey = Integer.valueOf(theKey);
			}

			theKey = db.getInt(39);
			if (0 == theKey) {
				theOriginalKey = null;
			} else {
				theOriginalKey = Integer.valueOf(theKey);
			}

			sources.add(new SourceTV(
				theDbID,
				theDbName,
				theSourceKey,
				db.getInt(2),
				theService,
				db.getBoolean(41),
				db.getString(4),
				db.getInt(5),
				db.getString(6),
				db.getString(7),
				theCountry,
				Zone.getZone(db.getInt(9)),
				db.getString(10),
				db.getString(11),
				SignalType.getSignalType(db.getInt(46)),
				FrequencyOffset.getFrequencyOffset(db.getInt(12)),
				EmissionMask.getEmissionMask(db.getInt(13)),
				db.getDouble(14),
				db.getDouble(15),
				db.getDouble(16),
				db.getString(47),
				db.getDouble(17),
				db.getDouble(18),
				db.getDouble(19),
				db.getDouble(20),
				db.getDouble(21),
				db.getString(22),
				db.getBoolean(23),
				db.getString(24),
				db.getDouble(25),
				db.getBoolean(26),
				db.getString(27),
				db.getDouble(28),
				db.getDouble(29),
				db.getDouble(30),
				db.getBoolean(31),
				db.getString(32),
				db.getBoolean(33),
				db.getInt(34),
				db.getBoolean(35),
				theRecID,
				theExtDbKey,
				db.getString(38),
				theOriginalKey,
				null,
				theDTSSources,
				db.getInt(40),
				db.getInt(42),
				db.getDouble(43),
				db.getDouble(44),
				db.getInt(45),
				0.,
				db.getString(48)));
		}
	}
}
