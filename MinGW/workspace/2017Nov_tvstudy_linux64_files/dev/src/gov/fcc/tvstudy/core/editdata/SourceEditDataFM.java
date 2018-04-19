//
//  SourceEditDataFM.java
//  TVStudy
//
//  Copyright (c) 2015-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.editdata;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.geo.*;

import java.util.*;
import java.util.regex.*;
import java.io.*;
import java.sql.*;

import javax.xml.parsers.*;

import org.xml.sax.*;
import org.xml.sax.helpers.*;


//=====================================================================================================================
// Mutable model subclass to support editing of FM sources, see SourceEditData.  Similar to TV, but simpler.

public class SourceEditDataFM extends SourceEditData implements Record {

	public final int facilityID;
	public final int stationClass;

	private SourceFM source;

	public boolean isIBOC;
	public int channel;
	public String status;
	public int statusType;
	public String appARN;
	public double ibocFraction;

	public int serviceAreaMode;
	public double serviceAreaArg;
	public double serviceAreaCL;
	public int serviceAreaKey;

	// See getRuleExtraDistance().

	public static final double DEFAULT_RULE_EXTRA_DISTANCE = 125.;


	//-----------------------------------------------------------------------------------------------------------------
	// Create an object for an existing source record from a study database.

	public SourceEditDataFM(StudyEditData theStudy, SourceFM theSource) {

		super(theStudy, theSource.dbID, Source.RECORD_TYPE_FM, Integer.valueOf(theSource.key), theSource.service,
			theSource.country, theSource.isLocked, theSource.userRecordID, theSource.extDbKey, theSource.extRecordID);

		source = theSource;

		facilityID = source.facilityID;
		stationClass = source.stationClass;

		isIBOC = source.isIBOC;
		callSign = source.callSign;
		channel = source.channel;
		city = source.city;
		state = source.state;
		status = source.status;
		statusType = ExtDbRecord.getStatusType(source.status);
		fileNumber = source.fileNumber;
		appARN = "";
		for (int i = 0; i < fileNumber.length(); i++) {
			if (Character.isDigit(fileNumber.charAt(i))) {
				appARN = fileNumber.substring(i);
				break;
			}
		}
		location.setLatLon(source.location);
		heightAMSL = source.heightAMSL;
		overallHAAT = source.overallHAAT;
		peakERP = source.peakERP;
		ibocFraction = source.ibocFraction;
		antennaID = source.antennaID;
		hasHorizontalPattern = source.hasHorizontalPattern;
		horizontalPattern = null;
		horizontalPatternChanged = false;
		horizontalPatternOrientation = source.horizontalPatternOrientation;
		hasVerticalPattern = source.hasVerticalPattern;
		verticalPattern = null;
		verticalPatternChanged = false;
		verticalPatternElectricalTilt = source.verticalPatternElectricalTilt;
		verticalPatternMechanicalTilt = source.verticalPatternMechanicalTilt;
		verticalPatternMechanicalTiltOrientation = source.verticalPatternMechanicalTiltOrientation;
		hasMatrixPattern = source.hasMatrixPattern;
		matrixPattern = null;
		matrixPatternChanged = false;
		useGenericVerticalPattern = source.useGenericVerticalPattern;

		serviceAreaMode = source.serviceAreaMode;
		serviceAreaArg = source.serviceAreaArg;
		serviceAreaCL = source.serviceAreaCL;
		serviceAreaKey = source.serviceAreaKey;

		setAllAttributes(source.attributes);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Private constructor to support other methods e.g. copy(), createSource(), just sets the final properties.

	private SourceEditDataFM(StudyEditData theStudy, String theDbID, Integer theKey, int theFacilityID,
			Service theService, int theStationClass, Country theCountry, boolean theIsLocked, Integer theUserRecordID,
			Integer theExtDbKey, String theExtRecordID) {

		super(theStudy, theDbID, Source.RECORD_TYPE_FM, theKey, theService, theCountry, theIsLocked, theUserRecordID,
			theExtDbKey, theExtRecordID);

		facilityID = theFacilityID;
		stationClass = theStationClass;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Accessor for the underlying record object.  May be null.

	public Source getSource() {

		return source;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create an identical copy.

	public SourceEditDataFM copy() {

		SourceEditDataFM theCopy = new SourceEditDataFM(study, dbID, key, facilityID, service, stationClass, country,
			isLocked, userRecordID, extDbKey, extRecordID);

		theCopy.source = source;

		theCopy.isIBOC = isIBOC;
		theCopy.callSign = callSign;
		theCopy.channel = channel;
		theCopy.city = city;
		theCopy.state = state;
		theCopy.status = status;
		theCopy.statusType = statusType;
		theCopy.fileNumber = fileNumber;
		theCopy.appARN = appARN;
		theCopy.location.setLatLon(location);
		theCopy.heightAMSL = heightAMSL;
		theCopy.overallHAAT = overallHAAT;
		theCopy.peakERP = peakERP;
		theCopy.ibocFraction = ibocFraction;
		theCopy.antennaID = antennaID;
		theCopy.hasHorizontalPattern = hasHorizontalPattern;
		theCopy.horizontalPattern = horizontalPattern;
		theCopy.horizontalPatternChanged = horizontalPatternChanged;
		theCopy.horizontalPatternOrientation = horizontalPatternOrientation;
		theCopy.hasVerticalPattern = hasVerticalPattern;
		theCopy.verticalPattern = verticalPattern;
		theCopy.verticalPatternChanged = verticalPatternChanged;
		theCopy.verticalPatternElectricalTilt = verticalPatternElectricalTilt;
		theCopy.verticalPatternMechanicalTilt = verticalPatternMechanicalTilt;
		theCopy.verticalPatternMechanicalTiltOrientation = verticalPatternMechanicalTiltOrientation;
		theCopy.hasMatrixPattern = hasMatrixPattern;
		theCopy.matrixPattern = matrixPattern;
		theCopy.matrixPatternChanged = matrixPatternChanged;
		theCopy.useGenericVerticalPattern = useGenericVerticalPattern;

		theCopy.serviceAreaMode = serviceAreaMode;
		theCopy.serviceAreaArg = serviceAreaArg;
		theCopy.serviceAreaCL = serviceAreaCL;
		theCopy.serviceAreaKey = serviceAreaKey;

		theCopy.setAllAttributes(attributes);

		return theCopy;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create a new source object with no association to an existing source record or any underlying primary record.
	// Assign a new key using the study context if any, else assign a temporary key.

	public static SourceEditDataFM createSource(StudyEditData newStudy, String newDbID, int newFacilityID,
			Service newService, int newStationClass, Country newCountry, boolean newIsLocked, ErrorLogger errors) {

		return createSource(newStudy, newDbID, newFacilityID, newService, newStationClass, newCountry, newIsLocked,
			null, null, null, errors);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create a source that is a primary record in a generic data set import.  The ExtDb object provides the source
	// key, the record ID is the source key as a string.  These are always locked and never have a study.

	public static SourceEditDataFM createExtSource(ExtDb extDb, int newFacilityID, Service newService,
			int newStationClass, Country newCountry, ErrorLogger errors) {

		if ((Source.RECORD_TYPE_FM != extDb.recordType) || !extDb.isGeneric()) {
			if (null != errors) {
				errors.reportError("Cannot create new record, invalid record type or data type.");
			}
			return null;
		}

		Integer newKey = extDb.getNewRecordKey();
		if (null == newKey) {
			if (null != errors) {
				errors.reportError("Cannot create new record, no keys available.");
			}
			return null;
		}

		return createSource(null, extDb.dbID, newKey, newFacilityID, newService, newStationClass, newCountry, true,
			null, extDb.dbKey, String.valueOf(newKey));
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Private versions of createSource() allow anything to be established.

	private static SourceEditDataFM createSource(StudyEditData newStudy, String newDbID, int newFacilityID,
			Service newService, int newStationClass, Country newCountry, boolean newIsLocked, Integer newUserRecordID,
			Integer newExtDbKey, String newExtRecordID, ErrorLogger errors) {

		Integer newKey;
		if (null != newStudy) {
			newKey = newStudy.getNewSourceKey();
			newDbID = newStudy.dbID;
		} else {
			newKey = getTemporaryKey();
		}
		if (null == newKey) {
			if (null != errors) {
				errors.reportError("Cannot create new record, no keys available.");
			}
			return null;
		}

		return createSource(newStudy, newDbID, newKey, newFacilityID, newService, newStationClass, newCountry,
			newIsLocked, newUserRecordID, newExtDbKey, newExtRecordID);
	}

	private static SourceEditDataFM createSource(StudyEditData newStudy, String newDbID, Integer newKey,
			int newFacilityID, Service newService, int newStationClass, Country newCountry, boolean newIsLocked,
			Integer newUserRecordID, Integer newExtDbKey, String newExtRecordID) {

		if (null == newExtRecordID) {
			newExtDbKey = null;
		}

		SourceEditDataFM newSource = new SourceEditDataFM(newStudy, newDbID, newKey, newFacilityID, newService,
			newStationClass, newCountry, newIsLocked, newUserRecordID, newExtDbKey, newExtRecordID);

		newSource.isIBOC = false;
		newSource.callSign = "";
		newSource.channel = 0;
		newSource.city = "";
		newSource.state = "";
		newSource.status = "";
		newSource.statusType = ExtDbRecord.STATUS_TYPE_OTHER;
		newSource.fileNumber = "";
		newSource.appARN = "";
		newSource.heightAMSL = 0.;
		newSource.overallHAAT = 0.;
		newSource.peakERP = Source.ERP_DEF;
		newSource.ibocFraction = SourceFM.IBOC_FRACTION_DEF;
		newSource.antennaID = null;
		newSource.hasHorizontalPattern = false;
		newSource.horizontalPattern = null;
		newSource.horizontalPatternChanged = false;
		newSource.horizontalPatternOrientation = 0.;
		newSource.hasVerticalPattern = false;
		newSource.verticalPattern = null;
		newSource.verticalPatternChanged = false;
		newSource.verticalPatternElectricalTilt = 0.;
		newSource.verticalPatternMechanicalTilt = 0.;
		newSource.verticalPatternMechanicalTiltOrientation = 0.;
		newSource.hasMatrixPattern = false;
		newSource.matrixPattern = null;
		newSource.matrixPatternChanged = false;
		newSource.useGenericVerticalPattern = true;

		newSource.serviceAreaMode = Source.SERVAREA_CONTOUR_DEFAULT;
		newSource.serviceAreaCL = Source.SERVAREA_CL_DEFAULT;

		return newSource;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create a new source object associated with an underlying primary record.

	public static SourceEditDataFM makeSourceFM(ExtDbRecordFM theRecord, StudyEditData theStudy, boolean theIsLocked) {
		return makeSourceFM(theRecord, theStudy, theIsLocked, null);
	}

	public static SourceEditDataFM makeSourceFM(ExtDbRecordFM theRecord, StudyEditData theStudy, boolean theIsLocked,
			ErrorLogger errors) {

		SourceEditDataFM theSource = createSource(theStudy, theRecord.extDb.dbID, theRecord.facilityID,
			theRecord.service, theRecord.stationClass, theRecord.country, theIsLocked, null, theRecord.extDb.dbKey,
			theRecord.extRecordID, errors);
		if (null == theSource) {
			return null;
		}

		// Back to ExtDbRecordFM for the rest.

		if (!theRecord.updateSource(theSource, errors)) {
			return null;
		}

		return theSource;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create a new import data set source object from an external data set record.

	public static SourceEditDataFM makeExtSourceFM(ExtDbRecordFM theRecord, ExtDb extDb) {
		return makeExtSourceFM(theRecord, extDb, null);
	}

	public static SourceEditDataFM makeExtSourceFM(ExtDbRecordFM theRecord, ExtDb extDb, ErrorLogger errors) {

		SourceEditDataFM theSource = createExtSource(extDb, theRecord.facilityID, theRecord.service,
			theRecord.stationClass, theRecord.country, errors);
		if (null == theSource) {
			return null;
		}

		if (!theRecord.updateSource(theSource, errors)) {
			return null;
		}

		return theSource;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Derive a new source from the current, assigning a new key and allowing various immutable properties to be
	// altered including study, facility ID, service, country, and locked status.

	public SourceEditData deriveSource(StudyEditData newStudy, boolean newIsLocked, ErrorLogger errors) {
		return deriveSourceFM(newStudy, facilityID, service, stationClass, country, newIsLocked, false, errors);
	}

	public SourceEditDataFM deriveSourceFM(int newFacilityID, Service newService, int newStationClass,
			Country newCountry, boolean newIsLocked) {
		return deriveSourceFM(study, newFacilityID, newService, newStationClass, newCountry, newIsLocked, true, null);
	}

	public SourceEditDataFM deriveSourceFM(int newFacilityID, Service newService, int newStationClass,
			Country newCountry, boolean newIsLocked, ErrorLogger errors) {
		return deriveSourceFM(study, newFacilityID, newService, newStationClass, newCountry, newIsLocked, true,
			errors);
	}

	private SourceEditDataFM deriveSourceFM(StudyEditData newStudy, int newFacilityID, Service newService,
			int newStationClass, Country newCountry, boolean newIsLocked, boolean clearPrimaryIDs,
			ErrorLogger errors) {

		String newDbID = dbID;
		Integer newUserRecordID = userRecordID;
		Integer newExtDbKey = extDbKey;
		String newExtRecordID = extRecordID;

		if ((null != newStudy) && !newStudy.dbID.equals(dbID)) {
			newDbID = newStudy.dbID;
			clearPrimaryIDs = true;
		}

		if (clearPrimaryIDs) {
			newUserRecordID = null;
			newExtDbKey = null;
			newExtRecordID = null;
		}

		if (!isLocked && newIsLocked) {
			if (null != errors) {
				errors.reportError("Unlocked records cannot be locked again.");
			}
			return null;
		}

		return deriveSourceFM(newStudy, newDbID, newFacilityID, newService, newStationClass, newCountry,
			newIsLocked, newUserRecordID, newExtDbKey, newExtRecordID, errors);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Private derivation supports changing primary IDs.

	private SourceEditDataFM deriveSourceFM(StudyEditData newStudy, String newDbID, int newFacilityID,
			Service newService, int newStationClass, Country newCountry, boolean newIsLocked, Integer newUserRecordID,
			Integer newExtDbKey, String newExtRecordID, ErrorLogger errors) {

		SourceEditDataFM newSource = createSource(newStudy, newDbID, newFacilityID, newService, newStationClass,
			newCountry, newIsLocked, newUserRecordID, newExtDbKey, newExtRecordID, errors);
		if (null == newSource) {
			return null;
		}

		newSource.isIBOC = isIBOC;
		newSource.callSign = callSign;
		newSource.channel = channel;
		newSource.city = city;
		newSource.state = state;
		newSource.status = status;
		newSource.statusType = statusType;
		newSource.fileNumber = fileNumber;
		newSource.appARN = appARN;
		newSource.location.setLatLon(location);
		newSource.heightAMSL = heightAMSL;
		newSource.overallHAAT = overallHAAT;
		newSource.peakERP = peakERP;
		newSource.ibocFraction = ibocFraction;
		newSource.antennaID = antennaID;
		newSource.hasHorizontalPattern = false;
		newSource.horizontalPattern = null;
		newSource.horizontalPatternChanged = false;
		newSource.horizontalPatternOrientation = horizontalPatternOrientation;
		newSource.hasVerticalPattern = false;
		newSource.verticalPattern = null;
		newSource.verticalPatternChanged = false;
		newSource.verticalPatternElectricalTilt = verticalPatternElectricalTilt;
		newSource.verticalPatternMechanicalTilt = verticalPatternMechanicalTilt;
		newSource.verticalPatternMechanicalTiltOrientation = verticalPatternMechanicalTiltOrientation;
		newSource.hasMatrixPattern = false;
		newSource.matrixPattern = null;
		newSource.matrixPatternChanged = false;
		newSource.useGenericVerticalPattern = useGenericVerticalPattern;

		newSource.serviceAreaMode = serviceAreaMode;
		newSource.serviceAreaArg = serviceAreaArg;
		newSource.serviceAreaCL = serviceAreaCL;
		newSource.serviceAreaKey = serviceAreaKey;

		newSource.setAllAttributes(attributes);

		// Load and copy pattern data as needed, see comments above.

		if (hasHorizontalPattern) {
			if ((null == horizontalPattern) && (null != source)) {
				horizontalPattern = source.getHorizontalPattern(errors);
			}
			if (null != horizontalPattern) {
				newSource.hasHorizontalPattern = true;
				newSource.horizontalPattern = horizontalPattern.copy();
				newSource.horizontalPatternChanged = true;
			}
		}

		if (hasVerticalPattern) {
			if ((null == verticalPattern) && (null != source)) {
				verticalPattern = source.getVerticalPattern(errors);
			}
			if (null != verticalPattern) {
				newSource.hasVerticalPattern = true;
				newSource.verticalPattern = verticalPattern.copy();
				newSource.verticalPatternChanged = true;
			}
		}

		if (hasMatrixPattern) {
			if ((null == matrixPattern) && (null != source)) {
				matrixPattern = source.getMatrixPattern(errors);
			}
			if (null != matrixPattern) {
				newSource.hasMatrixPattern = true;
				newSource.matrixPattern = matrixPattern.copy();
				newSource.matrixPatternChanged = true;
			}
		}

		return newSource;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isGeographyInUse(int theGeoKey) {

		return (((Source.SERVAREA_GEOGRAPHY_FIXED == serviceAreaMode) ||
			(Source.SERVAREA_GEOGRAPHY_RELOCATED == serviceAreaMode)) && (theGeoKey == serviceAreaKey));
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int getGeographyKey() {

		if ((Source.SERVAREA_GEOGRAPHY_FIXED == serviceAreaMode) ||
				(Source.SERVAREA_GEOGRAPHY_RELOCATED == serviceAreaMode)) {
			return serviceAreaKey;
		}
		return 0;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Validity and change checks per usual pattern.

	public boolean isDataValid(ErrorLogger errors) {

		if (isLocked) {
			return true;
		}

		if ((channel < SourceFM.CHANNEL_MIN) || (channel > SourceFM.CHANNEL_MAX)) {
			if (null != errors) {
				errors.reportValidationError("Bad channel, must be " + SourceFM.CHANNEL_MIN + " to " +
					SourceFM.CHANNEL_MAX + ".");
			}
			return false;
		}
		if (0 == city.length()) {
			if (null != errors) {
				errors.reportValidationError("A city name must be provided.");
			}
			return false;
		}
		if (0 == state.length()) {
			if (null != errors) {
				errors.reportValidationError("A state code must be provided.");
			}
			return false;
		}

		if (isIBOC && ((ibocFraction < SourceFM.IBOC_FRACTION_MIN) || (ibocFraction > SourceFM.IBOC_FRACTION_MAX))) {
			if (null != errors) {
				double minPct = SourceFM.IBOC_FRACTION_MIN * 100., maxPct = SourceFM.IBOC_FRACTION_MAX * 100.;
				errors.reportValidationError(
					String.format("The IBOC ERP must be between %.1f%% and %.1f%% of the peak ERP", minPct, maxPct));
			}
			return false;
		}

		if ((Source.SERVAREA_CONTOUR_LR_PERCENT == serviceAreaMode) ||
				(Source.SERVAREA_CONTOUR_LR_RUN_ABOVE == serviceAreaMode) ||
				(Source.SERVAREA_CONTOUR_LR_RUN_BELOW == serviceAreaMode)) {
			if ((serviceAreaArg < Source.SERVAREA_ARGUMENT_MIN) || (serviceAreaArg > Source.SERVAREA_ARGUMENT_MAX)) {
				errors.reportValidationError("Bad contour mode argument, must be " + Source.SERVAREA_ARGUMENT_MIN +
					" to " + Source.SERVAREA_ARGUMENT_MAX + ".");
				return false;
			}
		}

		if ((Source.SERVAREA_CONTOUR_DEFAULT == serviceAreaMode) ||
				(Source.SERVAREA_CONTOUR_FCC == serviceAreaMode) ||
				(Source.SERVAREA_CONTOUR_LR_PERCENT == serviceAreaMode) ||
				(Source.SERVAREA_CONTOUR_LR_RUN_ABOVE == serviceAreaMode) ||
				(Source.SERVAREA_CONTOUR_LR_RUN_BELOW == serviceAreaMode)) {
			if ((serviceAreaCL != Source.SERVAREA_CL_DEFAULT) && ((serviceAreaCL < Source.SERVAREA_CL_MIN) ||
					(serviceAreaCL > Source.SERVAREA_CL_MAX))) {
				errors.reportValidationError("Bad contour level, must be " + Source.SERVAREA_CL_MIN + " to " +
					Source.SERVAREA_CL_MAX + ".");
				return false;
			}
		}

		if (((Source.SERVAREA_GEOGRAPHY_FIXED == serviceAreaMode) ||
				(Source.SERVAREA_GEOGRAPHY_RELOCATED == serviceAreaMode)) && (serviceAreaKey <= 0)) {
			errors.reportValidationError("Bad or missing service area geography.");
			return false;
		}

		if (!super.isDataValid(errors)) {
			return false;
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// A locked record is non-editable and so cannot have changes, but may still report changed if it is a new record
	// never saved to the database (source == null), or due to conditions in the superclass.

	public boolean isDataChanged() {

		if (null == source) {
			return true;
		}

		if (super.isDataChanged()) {
			return true;
		}

		if (isLocked) {
			return false;
		}

		if (facilityID != source.facilityID) {
			return true;
		}
		if (stationClass != source.stationClass) {
			return true;
		}
		if (isIBOC != source.isIBOC) {
			return true;
		}
		if (channel != source.channel) {
			return true;
		}
		if (!status.equals(source.status)) {
			return true;
		}
		if (ibocFraction != source.ibocFraction) {
			return true;
		}

		if (serviceAreaMode != source.serviceAreaMode) {
			return true;
		}
		if (serviceAreaArg != source.serviceAreaArg) {
			return true;
		}
		if (serviceAreaCL != source.serviceAreaCL) {
			return true;
		}
		if (((Source.SERVAREA_GEOGRAPHY_FIXED == serviceAreaMode) ||
				(Source.SERVAREA_GEOGRAPHY_RELOCATED == serviceAreaMode)) &&
				(serviceAreaKey != source.serviceAreaKey)) {
			return true;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// See comments in the superclass and in SourceEditDataTV.

	public void save(DbConnection db) throws SQLException {

		db.update("DELETE FROM source WHERE source_key=" + key);

		int newModCount = 0;
		if (null != source) {
			newModCount = source.modCount + 1;
		}

		String hpatName = "";
		if (hasHorizontalPattern) {
			if (null != horizontalPattern) {
				hpatName = horizontalPattern.name;
			} else {
				if (null != source) {
					hpatName = source.horizontalPatternName;
				}
			}
		}

		String vpatName = "";
		if (hasVerticalPattern) {
			if (null != verticalPattern) {
				vpatName = verticalPattern.name;
			} else {
				if (null != source) {
					vpatName = source.verticalPatternName;
				}
			}
		}

		String mpatName = "";
		if (hasMatrixPattern) {
			if (null != matrixPattern) {
				mpatName = matrixPattern.name;
			} else {
				if (null != source) {
					mpatName = source.matrixPatternName;
				}
			}
		}

		int theServiceAreaKey = 0;
		if ((Source.SERVAREA_GEOGRAPHY_FIXED == serviceAreaMode) ||
				(Source.SERVAREA_GEOGRAPHY_RELOCATED == serviceAreaMode)) {
			theServiceAreaKey = serviceAreaKey;
		}

		String newAttributes = getAllAttributes();

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
		"VALUES (" +
			key + "," +
			recordType + "," +
			"true," +
			newModCount + "," +
			facilityID + "," +
			service.key + "," +
			"false," +
			isIBOC + "," +
			stationClass + "," +
			"'" + db.clean(callSign) + "'," +
			"''," +
			channel + "," +
			"'" + db.clean(city) + "'," +
			"'" + db.clean(state) + "'," +
			country.key + "," +
			"0," +
			"'" + db.clean(status) + "'," +
			"'" + db.clean(fileNumber) + "'," +
			"0," +
			"0," +
			"0," +
			location.latitude + "," +
			location.longitude + "," +
			"0," +
			"''," +
			heightAMSL + "," +
			heightAMSL + "," +
			"0," +
			overallHAAT + "," +
			overallHAAT + "," +
			peakERP + "," +
			(10. * Math.log10(peakERP)) + "," +
			ibocFraction + "," +
			((null == antennaID) ? "null" : "'" + db.clean(antennaID) + "'") + "," +
			hasHorizontalPattern + "," +
			"'" + db.clean(hpatName) + "'," +
			horizontalPatternOrientation + "," +
			hasVerticalPattern + "," +
			"'" + db.clean(vpatName) + "'," +
			verticalPatternElectricalTilt + "," +
			verticalPatternMechanicalTilt + "," +
			verticalPatternMechanicalTiltOrientation + "," +
			hasMatrixPattern + "," +
			"'" + db.clean(mpatName) + "'," +
			useGenericVerticalPattern + "," +
			"0," +
			isLocked + "," +
			((null == userRecordID) ? "0" : userRecordID) + "," +
			((null == extDbKey) ? "0" : extDbKey) + "," +
			((null == extRecordID) ? "null" : "'" + db.clean(extRecordID) + "'") + "," +
			"0," +
			"0," +
			serviceAreaMode + "," +
			serviceAreaArg + "," +
			serviceAreaCL + "," +
			theServiceAreaKey + "," +
			"0," +
			"'" + db.clean(newAttributes) + "')");

		savePatterns(db);

		source = new SourceFM(dbID, db.getDatabase(), key.intValue(), facilityID, service, isIBOC, stationClass,
			callSign, channel, city, state, country, status, fileNumber, location.latitude, location.longitude,
			heightAMSL, heightAMSL, overallHAAT, overallHAAT, peakERP, ibocFraction, antennaID, hasHorizontalPattern,
			hpatName, horizontalPatternOrientation, hasVerticalPattern, vpatName, verticalPatternElectricalTilt,
			verticalPatternMechanicalTilt, verticalPatternMechanicalTiltOrientation, hasMatrixPattern, mpatName,
			useGenericVerticalPattern, isLocked, userRecordID, extDbKey, extRecordID, newModCount, serviceAreaMode,
			serviceAreaArg, serviceAreaCL, theServiceAreaKey, newAttributes);

		horizontalPatternChanged = false;
		verticalPatternChanged = false;
		matrixPatternChanged = false;
		attributesChanged = false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// See comments in SourceEditDataTV.

	private static class SearchDelta {
		int delta;
		double maximumDistance;
	}

	public static int addRecords(ExtDb extDb, ScenarioEditData scenario, int searchType, String query,
			GeoPoint searchCenter, double searchRadius, int minimumChannel, int maximumChannel, boolean disableMX,
			boolean setUndesired, ErrorLogger errors) {

		int studyType = scenario.study.study.studyType;
		if (!extDb.isGeneric() || (Source.RECORD_TYPE_FM != extDb.recordType) ||
				!Study.isRecordTypeAllowed(studyType, Source.RECORD_TYPE_FM) ||
				(((ExtDbSearch.SEARCH_TYPE_DESIREDS == searchType) ||
					(ExtDbSearch.SEARCH_TYPE_PROTECTEDS == searchType)) &&
					(Study.STUDY_TYPE_FM != studyType) && (Study.STUDY_TYPE_TV6_FM != studyType))) {
			return 0;
		}

		double coChanMX = scenario.study.getCoChannelMxDistance();
		double kmPerDeg = scenario.study.getKilometersPerDegree();

		int minChannel = SourceFM.CHANNEL_MIN;
		int maxChannel = SourceFM.CHANNEL_MAX;
		if (((ExtDbSearch.SEARCH_TYPE_DESIREDS == searchType) || (ExtDbSearch.SEARCH_TYPE_PROTECTEDS == searchType)) &&
				(Study.STUDY_TYPE_TV6_FM == studyType)) {
			maxChannel = SourceFM.CHANNEL_MAX_NCE;
		}

		if (minimumChannel > minChannel) {
			minChannel = minimumChannel;
		}
		if ((maximumChannel > 0) && (maximumChannel < maxChannel)) {
			maxChannel = maximumChannel;
		}
		if (minChannel > maxChannel) {
			return 0;
		}

		StringBuilder q = new StringBuilder();
		boolean hasCrit = false;
		if ((null != query) && (query.length() > 0)) {
			q.append(query);
			hasCrit = true;
		}

		ArrayList<SourceEditData> theSources = null;
		Collection<SearchDelta> deltas = null;

		if (ExtDbSearch.SEARCH_TYPE_DESIREDS == searchType) {

			try {
				addChannelRangeQuery(extDb.type, minChannel, maxChannel, q, hasCrit);
			} catch (IllegalArgumentException ie) {
			}

		} else {

			if (ExtDbSearch.SEARCH_TYPE_PROTECTEDS == searchType) {

				theSources = scenario.sourceData.getUndesiredSources(Source.RECORD_TYPE_FM);
				if (theSources.isEmpty()) {
					if (null != errors) {
						errors.reportError("There are no undesired FM stations in the scenario.");
					}
					return -1;
				}

			} else {

				theSources = scenario.sourceData.getDesiredSources(Source.RECORD_TYPE_FM);
				if (theSources.isEmpty()) {
					if (null != errors) {
						errors.reportError("There are no desired FM stations in the scenario.");
					}
					return -1;
				}
			}

			HashMap<Integer, SearchDelta> searchDeltas = new HashMap<Integer, SearchDelta>();
			SearchDelta searchDelta;

			for (IxRuleEditData theRule : scenario.study.ixRuleData.getActiveRows()) {

				if (Source.RECORD_TYPE_FM != theRule.serviceType.recordType) {
					continue;
				}

				searchDelta = searchDeltas.get(Integer.valueOf(theRule.channelDelta.delta));

				if (null == searchDelta) {

					searchDelta = new SearchDelta();
					searchDelta.delta = theRule.channelDelta.delta;
					searchDelta.maximumDistance = theRule.distance;

					searchDeltas.put(Integer.valueOf(searchDelta.delta), searchDelta);

				} else {

					if (theRule.distance > searchDelta.maximumDistance) {
						searchDelta.maximumDistance = theRule.distance;
					}
				}
			}

			deltas = searchDeltas.values();

			int desChan, undChan, numChans = 0, maxChans = (maxChannel - minChannel) + 1, iChan;
			SourceEditDataFM theSource;

			boolean[] searchChans = new boolean[maxChans];

			for (SourceEditData aSource : theSources) {
				theSource = (SourceEditDataFM)aSource;

				for (SearchDelta theDelta : deltas) {

					if (ExtDbSearch.SEARCH_TYPE_PROTECTEDS == searchType) {

						undChan = theSource.channel;
						desChan = undChan - theDelta.delta;

						if ((desChan < minChannel) || (desChan > maxChannel)) {
							continue;
						}

						iChan = desChan - minChannel;
						if (searchChans[iChan]) {
							continue;
						}

					} else {

						desChan = theSource.channel;
						undChan = desChan + theDelta.delta;

						if ((undChan < minChannel) || (undChan > maxChannel)) {
							continue;
						}

						iChan = undChan - minChannel;
						if (searchChans[iChan]) {
							continue;
						}
					}

					if (searchChans[iChan]) {
						continue;
					}

					searchChans[iChan] = true;
					numChans++;

					if (numChans == maxChans) {
						break;
					}
				}

				if (numChans == maxChans) {
					break;
				}
			}

			if (0 == numChans) {
				return 0;
			}

			if (numChans < maxChans) {

				StringBuilder chanList = new StringBuilder();
				char sep = '(';
				for (iChan = 0; iChan < maxChans; iChan++) {
					if (searchChans[iChan]) {
						chanList.append(sep);
						chanList.append(String.valueOf(iChan + minChannel));
						sep = ',';
					}
				}
				chanList.append(')');
				try {
					addMultipleChannelQuery(extDb.type, chanList.toString(), q, hasCrit);
				} catch (IllegalArgumentException ie) {
				}

			} else {

				try {
					addChannelRangeQuery(extDb.type, minChannel, maxChannel, q, hasCrit);
				} catch (IllegalArgumentException ie) {
				}
			}
		}

		LinkedList<SourceEditData> sources = findImportRecords(extDb, q.toString(), searchCenter, searchRadius,
			kmPerDeg, errors);

		if (null == sources) {
			return -1;
		}
		if (sources.isEmpty()) {
			return 0;
		}

		removeAllMX(scenario, sources, disableMX, coChanMX, kmPerDeg);

		if (ExtDbSearch.SEARCH_TYPE_DESIREDS != searchType) {

			ListIterator<SourceEditData> lit = sources.listIterator(0);
			SourceEditDataFM theSource, desSource, undSource;
			boolean remove;
			int chanDelt;
			double checkDist;

			while (lit.hasNext()) {

				theSource = (SourceEditDataFM)(lit.next());
				remove = true;

				for (SourceEditData aSource : theSources) {

					if (ExtDbSearch.SEARCH_TYPE_PROTECTEDS == searchType) {
						desSource = theSource;
						undSource = (SourceEditDataFM)aSource;
					} else {
						desSource = (SourceEditDataFM)aSource;
						undSource = theSource;
					}

					chanDelt = undSource.channel - desSource.channel;

					for (SearchDelta theDelta : deltas) {

						if (theDelta.delta != chanDelt) {
							continue;
						}

						checkDist = theDelta.maximumDistance + getRuleExtraDistance(scenario.study, desSource.service,
							desSource.stationClass, desSource.country, desSource.channel, desSource.peakERP);

						if (desSource.location.distanceTo(undSource.location, kmPerDeg) <= checkDist) {
							remove = false;
							break;
						}
					}

					if (!remove) {
						break;
					}
				}

				if (remove) {
					lit.remove();
				}
			}
		}

		SourceEditData newSource;
		ArrayList<SourceEditData> newSources = new ArrayList<SourceEditData>();

		for (SourceEditData theSource : sources) {
			newSource = scenario.study.findSharedSource(theSource.extDbKey, theSource.extRecordID);
			if (null == newSource) {
				newSource = theSource.deriveSource(scenario.study, true, errors);
				if (null == newSource) {
					return -1;
				}
			}
			newSources.add(newSource);
		}

		boolean isDesired = true, isUndesired = true;
		if ((ExtDbSearch.SEARCH_TYPE_DESIREDS == searchType) || (ExtDbSearch.SEARCH_TYPE_PROTECTEDS == searchType)) {
			if (disableMX) {
				isUndesired = false;
			} else {
				isUndesired = setUndesired;
			}
		} else {
			isDesired = false;
			if (disableMX) {
				isUndesired = false;
			}
		}

		for (SourceEditData aSource : newSources) {
			scenario.sourceData.addOrReplace(aSource, isDesired, isUndesired);
		}

		return newSources.size();
	}


	//-----------------------------------------------------------------------------------------------------------------

	private static void removeAllMX(ScenarioEditData scenario, LinkedList<SourceEditData> sources, boolean disableMX,
			double coChanMX, double kmPerDeg) {

		ArrayList<SourceEditData> existSources = scenario.sourceData.getSources(Source.RECORD_TYPE_FM);

		ListIterator<SourceEditData> lit = sources.listIterator(0);
		SourceEditDataFM theSourceFM, existSourceFM;

		while (lit.hasNext()) {
			theSourceFM = (SourceEditDataFM)(lit.next());
			for (SourceEditData existSource : existSources) {
				existSourceFM = (SourceEditDataFM)existSource;
				if ((theSourceFM.extDbKey.equals(existSourceFM.extDbKey) &&
						theSourceFM.extRecordID.equals(existSourceFM.extRecordID)) ||
						(!disableMX && ExtDbRecordFM.areRecordsMX(theSourceFM, existSourceFM, coChanMX, kmPerDeg))) {
					lit.remove();
					break;
				}
			}
		}

		if (disableMX) {
			return;
		}

		Comparator<SourceEditData> prefComp = new Comparator<SourceEditData>() {
			public int compare(SourceEditData theSource, SourceEditData otherSource) {
				if (((SourceEditDataFM)theSource).isPreferredRecord((SourceEditDataFM)otherSource)) {
					return -1;
				}
				return 1;
			}
		};

		Collections.sort(sources, prefComp);

		int recCount = sources.size() - 1;
		for (int recIndex = 0; recIndex < recCount; recIndex++) {
			theSourceFM = (SourceEditDataFM)(sources.get(recIndex));
			lit = sources.listIterator(recIndex + 1);
			while (lit.hasNext()) {
				if (ExtDbRecordFM.areRecordsMX(theSourceFM, (SourceEditDataFM)(lit.next()), coChanMX, kmPerDeg)) {
					lit.remove();
					recCount--;
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	private boolean isPreferredRecord(SourceEditDataFM otherSource) {

		if (service.preferenceRank > otherSource.service.preferenceRank) {
			return true;
		}
		if (service.preferenceRank < otherSource.service.preferenceRank) {
			return false;
		}

		if (statusType < otherSource.statusType) {
			return true;
		}
		if (statusType > otherSource.statusType) {
			return false;
		}

		if (extRecordID.compareTo(otherSource.extRecordID) > 0) {
			return true;
		}
		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Determine the rule extra distance for this source.

	public double getRuleExtraDistance() {

		return getRuleExtraDistance(study, service, stationClass, country, channel, peakERP);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Determine the rule extra distance for a service, class, country, channel, and ERP.  This involves determining
	// the service contour, normalizing the ERP, and looking up the distance from study parameters.  If the study is
	// null parameters are not available for this, just return the default.  In practice that should never happen since
	// this is meant for logic that builds scenarios and that should only occur within an existing study.  If the ERP
	// is not valid, return the default.  An option may set the rule-extra to the maximum calculation distance.  Note
	// this uses the same parameters as TV, using full-service UHF TV digital as the reference as with TV records.
	// This is a worst-case maximum of the FCC propagation curves, for which FM and low VHF TV analog are equivalent.

	public static double getRuleExtraDistance(StudyEditData study, Service service, int stationClass, Country country,
			int channel, double peakERP) {

		if (null == study) {
			return DEFAULT_RULE_EXTRA_DISTANCE;
		}

		if (study.getUseMaxRuleExtraDistance()) {
			return study.getMaximumDistance();
		}

		if (peakERP <= 0.) {
			return DEFAULT_RULE_EXTRA_DISTANCE;
		}

		int countryIndex = country.key - 1;
		int curv = study.getCurveSetFM(countryIndex);

		// Service at F(50,10) is rare and not fully supported, but it can be selected so handle it just in case.

		if (Parameter.CURVE_FCC_F10 == curv) {
			return 300.;
		}

		double contourLevel;

		switch (service.serviceType.key) {

			case ServiceType.SERVTYPE_FM_FULL:
			default: {
				if (channel > 220) {
					switch (stationClass) {
						default: {
							contourLevel = study.getContourFM(countryIndex);
							break;
						}
						case ExtDbRecordFM.FM_CLASS_B: {
							contourLevel = study.getContourFMB(countryIndex);
							break;
						}
						case ExtDbRecordFM.FM_CLASS_B1: {
							contourLevel = study.getContourFMB1(countryIndex);
							break;
						}
					}
				} else {
					contourLevel = study.getContourFMED(countryIndex);
				}
				break;
			}

			case ServiceType.SERVTYPE_FM_LP: {
				contourLevel = study.getContourFMLP(countryIndex);
				break;
			}

			case ServiceType.SERVTYPE_FM_TX: {
				contourLevel = study.getContourFMTX(countryIndex);
				break;
			}
		}

		double erp = (10. * Math.log10(peakERP)) + (study.getContourUhfDigital(Country.US - 1) - contourLevel);

		int curvref = study.getCurveSetDigital(Country.US - 1);

		if (Parameter.CURVE_FCC_F90 == curvref) {
			if (Parameter.CURVE_FCC_F50 == curv) {
				erp += 8.;
			}
		} else {
			if (Parameter.CURVE_FCC_F50 == curvref) {
				if (Parameter.CURVE_FCC_F90 == curv) {
					erp -= 8.;
				}
			}
		}

		if (erp < study.getRuleExtraDistanceERPLow()) {
			return study.getRuleExtraDistanceLow();
		} else {
			if (erp < study.getRuleExtraDistanceERPMedium()) {
				return study.getRuleExtraDistanceLowMedium();
			} else {
				if (erp < study.getRuleExtraDistanceERPHigh()) {
					return study.getRuleExtraDistanceMediumHigh();
				} else {
					return study.getRuleExtraDistanceHigh();
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Encode source data as an XML description, see comments in the superclass and in SourceEditDataTV.

	protected boolean writeToXML(Writer xml, boolean standalone, boolean isDesiredFlag, boolean isUndesiredFlag,
			ErrorLogger errors) throws IOException {

		if (!isDataValid(errors)) {
			return false;
		}

		// If the source is from a generic data set the extRecordID is not exported, those are not portable.

		String theExtRecordID = extRecordID;
		if (null != theExtRecordID) {
			ExtDb theDb = ExtDb.getExtDb(dbID, extDbKey, true);
			if ((null == theDb) || theDb.isGeneric()) {
				theExtRecordID = null;
			}
		}

		xml.append("<SOURCE");
		if (standalone) {
			xml.append(" LOCKED=\"false\"");
		} else {
			xml.append(" DESIRED=\"" + isDesiredFlag + "\"");
			xml.append(" UNDESIRED=\"" + isUndesiredFlag + '"');
			xml.append(" LOCKED=\"" + isLocked + '"');
		}
		xml.append(" SERVICE=\"" + service.serviceCode + '"');

		// A locked source based on an external data record is exported as just a reference to the record ID.  But in
		// standalone mode the record ID is omitted and save of all properties proceeds regardless.

		if ((null != theExtRecordID) && !standalone) {
			xml.append(" RECORD_ID=\"" + theExtRecordID + '"');
			if (isLocked) {
				xml.append("/>\n");
				return true;
			}
		}

		xml.append(" ID=\"" + facilityID + '"');
		xml.append(" CLASS=\"" + AppCore.xmlclean(ExtDbRecordFM.FM_CLASS_CODES[stationClass]) + '"');
		xml.append(" CALL_SIGN=\"" + AppCore.xmlclean(callSign) + '"');
		xml.append(" CITY=\"" + AppCore.xmlclean(city) + '"');
		xml.append(" STATE=\"" + AppCore.xmlclean(state) + '"');
		xml.append(" COUNTRY=\"" + country.countryCode + '"');

		xml.append(" CHANNEL=\"" + channel + '"');
		xml.append(" STATUS=\"" + status + '"');
		xml.append(" FILE_NUMBER=\"" + AppCore.xmlclean(fileNumber) + '"');

		xml.append(" IBOC=\"" + isIBOC + '"');
		if (isIBOC) {
			xml.append(" IBOC_ERP=\"" + AppCore.formatERP(peakERP * ibocFraction) + '"');
		}

		// Superclass does the rest.

		writeAttributes(xml, errors);

		xml.append("</SOURCE>\n");

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Make a new source object using values from an XML attributes list.

	protected static SourceEditDataFM makeSourceWithAttributesFM(String element, Attributes attrs,
			StudyEditData theStudy, String theDbID, Service theService, Country theCountry, boolean theIsLocked,
			Integer theUserRecordID, Integer theExtDbKey, String theExtRecordID, ErrorLogger errors) {

		String str;

		int theFacilityID = 0;
		str = attrs.getValue("ID");
		if (null != str) {
			try {
				theFacilityID = Integer.parseInt(str);
			} catch (NumberFormatException nfe) {
				if (null != errors) {
					errors.reportError("Bad ID attribute in " + element + " tag.");
				}
				return null;
			}
		} else {
			if (null != errors) {
				errors.reportError("Missing ID attribute in " + element + " tag.");
			}
			return null;
		}

		int theStationClass = ExtDbRecordFM.FM_CLASS_OTHER;
		str = attrs.getValue("CLASS");
		if ((null != str) && (str.length() > 0)) {
			for (int i = 1; i < ExtDbRecordFM.FM_CLASS_CODES.length; i++) {
				if (str.equalsIgnoreCase(ExtDbRecordFM.FM_CLASS_CODES[i])) {
					theStationClass = i;
					break;
				}
			}
		}

		SourceEditDataFM newSource = createSource(theStudy, theDbID, theFacilityID, theService, theStationClass,
			theCountry, theIsLocked, theUserRecordID, theExtDbKey, theExtRecordID, errors);
		if (null == newSource) {
			return null;
		}

		// Parse attributes for the source.

		if (!newSource.parseAttributesFM(element, attrs, errors)) {
			return null;
		}

		return newSource;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Make a new generic import data set source object using values from an XML attributes list.

	protected static SourceEditDataFM makeExtSourceWithAttributesFM(String element, Attributes attrs, ExtDb extDb,
			Service theService, Country theCountry, ErrorLogger errors) {

		String str;

		int theFacilityID = 0;
		str = attrs.getValue("ID");
		if (null != str) {
			try {
				theFacilityID = Integer.parseInt(str);
			} catch (NumberFormatException nfe) {
				if (null != errors) {
					errors.reportError("Bad ID attribute in " + element + " tag.");
				}
				return null;
			}
		} else {
			if (null != errors) {
				errors.reportError("Missing ID attribute in " + element + " tag.");
			}
			return null;
		}

		int theStationClass = ExtDbRecordFM.FM_CLASS_OTHER;
		str = attrs.getValue("CLASS");
		if ((null != str) && (str.length() > 0)) {
			for (int i = 1; i < ExtDbRecordFM.FM_CLASS_CODES.length; i++) {
				if (str.equalsIgnoreCase(ExtDbRecordFM.FM_CLASS_CODES[i])) {
					theStationClass = i;
					break;
				}
			}
		}

		SourceEditDataFM newSource = createExtSource(extDb, theFacilityID, theService, theStationClass, theCountry,
			errors);
		if (null == newSource) {
			return null;
		}

		if (!newSource.parseAttributesFM(element, attrs, errors)) {
			return null;
		}

		return newSource;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Parse an XML attributes list when building a new source.  Return false on error.  Applies all value checks as
	// in isDataValid(), as well as ensuring required attributes are always present.

	private boolean parseAttributesFM(String element, Attributes attrs, ErrorLogger errors) {

		String str;

		callSign = attrs.getValue("CALL_SIGN");
		if ((null == callSign) || (0 == callSign.length())) {
			if (null != errors) {
				errors.reportError("Missing or bad CALL_SIGN attribute in " + element + " tag.");
			}
			return false;
		}
		if (callSign.length() > Source.MAX_CALL_SIGN_LENGTH) {
			callSign = callSign.substring(0, Source.MAX_CALL_SIGN_LENGTH);
		}

		city = attrs.getValue("CITY");
		if ((null == city) || (0 == city.length())) {
			if (null != errors) {
				errors.reportError("Missing or bad CITY attribute in " + element + " tag.");
			}
			return false;
		}
		if (city.length() > Source.MAX_CITY_LENGTH) {
			city = city.substring(0, Source.MAX_CITY_LENGTH);
		}

		state = attrs.getValue("STATE");
		if ((null == state) || (0 == state.length())) {
			if (null != errors) {
				errors.reportError("Missing or bad STATE attribute in " + element + " tag.");
			}
			return false;
		}
		if (state.length() > Source.MAX_STATE_LENGTH) {
			state = state.substring(0, Source.MAX_STATE_LENGTH);
		}

		str = attrs.getValue("CHANNEL");
		if (null != str) {
			try {
				channel = Integer.parseInt(str);
			} catch (NumberFormatException nfe) {
			}
		}
		if ((channel < SourceFM.CHANNEL_MIN) || (channel > SourceFM.CHANNEL_MAX)) {
			if (null != errors) {
				errors.reportError("Missing or bad CHANNEL attribute in " + element + " tag.");
			}
			return false;
		}

		str = attrs.getValue("STATUS");
		if (null != str) {
			status = str;
			statusType = ExtDbRecord.getStatusType(str);
			if (ExtDbRecord.STATUS_TYPE_OTHER != statusType) {
				status = ExtDbRecord.STATUS_CODES[statusType];
			}
			if (status.length() > Source.MAX_STATUS_LENGTH) {
				status = status.substring(0, Source.MAX_STATUS_LENGTH);
			}
		}

		str = attrs.getValue("FILE_NUMBER");
		if (null != str) {
			if (str.length() > Source.MAX_FILE_NUMBER_LENGTH) {
				str = str.substring(0, Source.MAX_FILE_NUMBER_LENGTH);
			}
			fileNumber = str;
			appARN = "";
			for (int i = 0; i < fileNumber.length(); i++) {
				if (Character.isDigit(fileNumber.charAt(i))) {
					appARN = fileNumber.substring(i);
					break;
				}
			}
		}

		// Get superclass attributes, need ERP to be defined.

		if (!parseAttributes(element, attrs, errors)) {
			return false;
		}

		// IBOC ERP is used to compute a fraction of the peak ERP if present, otherwise default to 1%.

		str = attrs.getValue("IBOC");
		isIBOC = ((null != str) && Boolean.parseBoolean(str));

		ibocFraction = SourceFM.IBOC_FRACTION_DEF;
		if (isIBOC) {
			str = attrs.getValue("IBOC_ERP");
			if (null != str) {
				ibocFraction = 0.;
				try {
					ibocFraction = Double.parseDouble(str) / peakERP;
				} catch (NumberFormatException nfe) {
				}
				if ((ibocFraction < SourceFM.IBOC_FRACTION_MIN) || (ibocFraction > SourceFM.IBOC_FRACTION_MAX)) {
					if (null != errors) {
						errors.reportError("Bad IBOC_ERP attribute in " + element + " tag.");
					}
					return false;
				}
			}
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Methods in the Record interface.  Some are in the superclass.

	public String getFacilityID() {

		return String.valueOf(facilityID);
	}

	public String getSortFacilityID() {

		return String.format(Locale.US, "%07d", facilityID);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getChannel() {

		return String.valueOf(channel) + ExtDbRecordFM.FM_CLASS_CODES[stationClass];
	}

	public String getSortChannel() {

		return String.format(Locale.US, "%03d%02d", channel, stationClass);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getFrequency() {

		if (0 == channel) {
			return "";
		}

		return String.format(Locale.US, "%.1f MHz", 87.9 + ((double)(channel - 200) * 0.2));
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getStatus() {

		return status;
	}

	public String getSortStatus() {

		return String.valueOf(statusType);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getARN() {

		return appARN;
	}
}
