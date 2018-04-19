//
//  SourceEditDataWL.java
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
// Mutable model subclass to support editing of wireless source records, see SourceEditData.  Wireless records have
// fewer restrictions and requirements than others, there is no MX logic applied in scenario building and they do not
// have related properties such as facility ID.  Also the operating frequency and bandwidth of a wireless record are
// undefined until run-time, those are provided as study parameters.

public class SourceEditDataWL extends SourceEditData {

	private SourceWL source;

	public String sectorID;


	//-----------------------------------------------------------------------------------------------------------------
	// Create an object for an existing source record from a study database.

	public SourceEditDataWL(StudyEditData theStudy, SourceWL theSource) {

		super(theStudy, theSource.dbID, Source.RECORD_TYPE_WL, Integer.valueOf(theSource.key), theSource.service,
			theSource.country, theSource.isLocked, theSource.userRecordID, theSource.extDbKey, theSource.extRecordID);

		source = theSource;

		callSign = source.callSign;
		sectorID = source.sectorID;
		city = source.city;
		state = source.state;
		fileNumber = source.fileNumber;
		location.setLatLon(source.location);
		heightAMSL = source.heightAMSL;
		overallHAAT = source.overallHAAT;
		peakERP = source.peakERP;
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

		setAllAttributes(source.attributes);
	}


	//-----------------------------------------------------------------------------------------------------------------

	private SourceEditDataWL(StudyEditData theStudy, String theDbID, Integer theKey, Service theService,
			Country theCountry, boolean theIsLocked, Integer theUserRecordID, Integer theExtDbKey,
			String theExtRecordID) {

		super(theStudy, theDbID, Source.RECORD_TYPE_WL, theKey, theService, theCountry, theIsLocked, theUserRecordID,
			theExtDbKey, theExtRecordID);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Accessor for the underlying record object.  May be null.

	public Source getSource() {

		return source;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create an identical copy.

	public SourceEditDataWL copy() {

		SourceEditDataWL theCopy = new SourceEditDataWL(study, dbID, key, service, country, isLocked, userRecordID,
			extDbKey, extRecordID);

		theCopy.source = source;

		theCopy.callSign = callSign;
		theCopy.sectorID = sectorID;
		theCopy.city = city;
		theCopy.state = state;
		theCopy.fileNumber = fileNumber;
		theCopy.location.setLatLon(location);
		theCopy.heightAMSL = heightAMSL;
		theCopy.overallHAAT = overallHAAT;
		theCopy.peakERP = peakERP;
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

		theCopy.setAllAttributes(attributes);

		return theCopy;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create a new source object with no association to an existing source record or any underlying primary record.

	public static SourceEditDataWL createSource(StudyEditData newStudy, String newDbID, Service newService,
			Country newCountry, boolean newIsLocked, ErrorLogger errors) {

		return createSource(newStudy, newDbID, newService, newCountry, newIsLocked, null, null, null, errors);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create a source that is a primary record in a generic data set import.

	public static SourceEditDataWL createExtSource(ExtDb extDb, Service newService, Country newCountry,
			ErrorLogger errors) {

		if ((Source.RECORD_TYPE_WL != extDb.recordType) || !extDb.isGeneric()) {
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

		return createSource(null, extDb.dbID, newKey, newService, newCountry, true, null, extDb.dbKey,
			String.valueOf(newKey));
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Private versions of createSource() allow anything to be established, these are only for use by other source-
	// creation methods, e.g. makeSource().

	private static SourceEditDataWL createSource(StudyEditData newStudy, String newDbID, Service newService,
			Country newCountry, boolean newIsLocked, Integer newUserRecordID, Integer newExtDbKey,
			String newExtRecordID, ErrorLogger errors) {

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

		return createSource(newStudy, newDbID, newKey, newService, newCountry, newIsLocked, newUserRecordID,
			newExtDbKey, newExtRecordID);
	}

	private static SourceEditDataWL createSource(StudyEditData newStudy, String newDbID, Integer newKey,
			Service newService, Country newCountry, boolean newIsLocked, Integer newUserRecordID, Integer newExtDbKey,
			String newExtRecordID) {

		if (null == newExtRecordID) {
			newExtDbKey = null;
		}

		SourceEditDataWL newSource = new SourceEditDataWL(newStudy, newDbID, newKey, newService, newCountry,
			newIsLocked, newUserRecordID, newExtDbKey, newExtRecordID);

		newSource.callSign = "";
		newSource.sectorID = "";
		newSource.city = "";
		newSource.state = "";
		newSource.fileNumber = "";
		newSource.heightAMSL = 0.;
		newSource.overallHAAT = 0.;
		newSource.peakERP = Source.ERP_DEF;
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
		newSource.useGenericVerticalPattern = false;

		return newSource;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create a new source object associated with an underlying primary record.

	public static SourceEditDataWL makeSourceWL(ExtDbRecordWL theRecord, StudyEditData theStudy, boolean theIsLocked) {
		return makeSourceWL(theRecord, theStudy, theIsLocked, null);
	}

	public static SourceEditDataWL makeSourceWL(ExtDbRecordWL theRecord, StudyEditData theStudy, boolean theIsLocked,
			ErrorLogger errors) {

		SourceEditDataWL theSource = createSource(theStudy, theRecord.extDb.dbID, theRecord.service,
			theRecord.country, theIsLocked, null, theRecord.extDb.dbKey, theRecord.extRecordID, errors);
		if (null == theSource) {
			return null;
		}

		// Back to ExtDbRecordWL for the rest.

		if (!theRecord.updateSource(theSource, errors)) {
			return null;
		}

		return theSource;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create a new source object as part of an import data set drawing from an external data set record.

	public static SourceEditDataWL makeExtSourceWL(ExtDbRecordWL theRecord, ExtDb extDb) {
		return makeExtSourceWL(theRecord, extDb, null);
	}

	public static SourceEditDataWL makeExtSourceWL(ExtDbRecordWL theRecord, ExtDb extDb, ErrorLogger errors) {

		SourceEditDataWL theSource = createExtSource(extDb, theRecord.service, theRecord.country, errors);
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
	// altered including study, service, and locked status.

	public SourceEditData deriveSource(StudyEditData newStudy, boolean newIsLocked, ErrorLogger errors) {
		return deriveSourceWL(newStudy, service, country, newIsLocked, false, errors);
	}

	public SourceEditDataWL deriveSourceWL(Service newService, Country newCountry, boolean newIsLocked) {
		return deriveSourceWL(study, newService, newCountry, newIsLocked, true, null);
	}

	public SourceEditDataWL deriveSourceWL(Service newService, Country newCountry, boolean newIsLocked,
			ErrorLogger errors) {
		return deriveSourceWL(study, newService, newCountry, newIsLocked, true, errors);
	}

	private SourceEditDataWL deriveSourceWL(StudyEditData newStudy, Service newService, Country newCountry,
			boolean newIsLocked, boolean clearPrimaryIDs, ErrorLogger errors) {

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

		return deriveSourceWL(newStudy, newDbID, newService, newCountry, newIsLocked, newUserRecordID, newExtDbKey,
			newExtRecordID, errors);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Private derivation supports changing primary IDs.

	private SourceEditDataWL deriveSourceWL(StudyEditData newStudy, String newDbID, Service newService,
			Country newCountry, boolean newIsLocked, Integer newUserRecordID, Integer newExtDbKey,
			String newExtRecordID, ErrorLogger errors) {

		SourceEditDataWL newSource = createSource(newStudy, newDbID, newService, newCountry, newIsLocked,
			newUserRecordID, newExtDbKey, newExtRecordID, errors);

		newSource.callSign = callSign;
		newSource.sectorID = sectorID;
		newSource.city = city;
		newSource.state = state;
		newSource.fileNumber = fileNumber;
		newSource.location.setLatLon(location);
		newSource.heightAMSL = heightAMSL;
		newSource.overallHAAT = overallHAAT;
		newSource.peakERP = peakERP;
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

		newSource.setAllAttributes(attributes);

		// Load and copy pattern data as needed, see comments in SourceEditData.

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

		// Check for individual changes on an editable source.

		if (!sectorID.equals(source.sectorID)) {
			return true;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// See comments in superclass and in SourceEditDataTV.

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
			"0," +
			service.key + "," +
			"false," +
			"false," +
			"0," +
			"'" + db.clean(callSign) + "'," +
			"'" + db.clean(sectorID) + "'," +
			"0," +
			"'" + db.clean(city) + "'," +
			"'" + db.clean(state) + "'," +
			country.key + "," +
			"0," +
			"''," +
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
			"0.," +
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
			"0," +
			"0," +
			"0," +
			"0," +
			"0," +
			"'" + db.clean(newAttributes) + "')");

		savePatterns(db);

		source = new SourceWL(dbID, db.getDatabase(), key.intValue(), service, callSign, sectorID, city, state,
			country, fileNumber, location.latitude, location.longitude, heightAMSL, heightAMSL, overallHAAT,
			overallHAAT, peakERP, antennaID, hasHorizontalPattern, hpatName, horizontalPatternOrientation,
			hasVerticalPattern, vpatName, verticalPatternElectricalTilt, verticalPatternMechanicalTilt,
			verticalPatternMechanicalTiltOrientation, hasMatrixPattern, mpatName, useGenericVerticalPattern, isLocked,
			userRecordID, extDbKey, extRecordID, newModCount, newAttributes);

		horizontalPatternChanged = false;
		verticalPatternChanged = false;
		matrixPatternChanged = false;
		attributesChanged = false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add wireless sources to a scenario from a list of source objects, see comments in ExtDbRecordWL.

	public static int addRecords(ExtDb extDb, ScenarioEditData scenario, int searchType, String query,
			GeoPoint searchCenter, double searchRadius, ErrorLogger errors) {

		if (!extDb.isGeneric() || (Source.RECORD_TYPE_WL != extDb.recordType) ||
				!Study.isRecordTypeAllowed(scenario.study.study.studyType, Source.RECORD_TYPE_WL) ||
				(ExtDbSearch.SEARCH_TYPE_UNDESIREDS != searchType)) {
			return 0;
		}

		// Get necessary study parameters.

		double kmPerDeg = scenario.study.getKilometersPerDegree();

		double[] wirelessCullDistance = scenario.study.getWirelessCullDistance(Parameter.WL_OVERLAP_COUNT - 1);
		if (null == wirelessCullDistance) {
			if (null != errors) {
				errors.reportError("Cannot load wireless culling distance table from parameters.");
			}
			return -1;
		}

		// Get desired TV source, there should be exactly one but as long as there is at least one this continues.

		ArrayList<SourceEditData> theSources = scenario.sourceData.getDesiredSources(Source.RECORD_TYPE_TV);
		if (theSources.isEmpty()) {
			if (null != errors) {
				errors.reportError("There is no desired TV station in the scenario.");
			}
			return -1;
		}
		SourceEditDataTV desSource = (SourceEditDataTV)theSources.get(0);

		// Do the search.  An empty result in this case is not an error.

		LinkedList<SourceEditData> sources = findImportRecords(extDb, query, searchCenter, searchRadius, kmPerDeg,
			errors);
		if (null == sources) {
			return -1;
		}
		if (sources.isEmpty()) {
			return 0;
		}

		// Scan records and check distances.  Also check for existing identical records in the scenario

		ArrayList<SourceEditData> existSources = scenario.sourceData.getSources(Source.RECORD_TYPE_WL);

		ListIterator<SourceEditData> lit = sources.listIterator(0);
		SourceEditDataWL undSource;
		boolean remove;
		double ixDistance, checkDist;
		int haatIndex, erpIndex;

		while (lit.hasNext()) {

			undSource = (SourceEditDataWL)(lit.next());
			remove = false;

			for (SourceEditData existSource : existSources) {
				if (undSource.extDbKey.equals(existSource.extDbKey) &&
						undSource.extRecordID.equals(existSource.extRecordID)) {
					remove = true;
					break;
				}
			}

			if (!remove) {

				remove = true;

				if (Source.HEIGHT_DERIVE == undSource.overallHAAT) {
					haatIndex = 0;
				} else {
					for (haatIndex = 1; haatIndex < Parameter.WL_CULL_HAAT_COUNT; haatIndex++) {
						if (undSource.overallHAAT > Parameter.wirelessCullHAAT[haatIndex]) {
							break;
						}
					}
					haatIndex--;
				}

				for (erpIndex = 1; erpIndex < Parameter.WL_CULL_ERP_COUNT; erpIndex++) {
					if (undSource.peakERP > Parameter.wirelessCullERP[erpIndex]) {
						break;
					}
				}
				erpIndex--;

				ixDistance = wirelessCullDistance[(haatIndex * Parameter.WL_CULL_ERP_COUNT) + erpIndex];

				// Check distance.

				if (desSource.isParent) {
					for (SourceEditDataTV dtsSource : desSource.getDTSSources()) {
						if (dtsSource.siteNumber > 0) {
							checkDist = ixDistance + dtsSource.getRuleExtraDistance();
							if (dtsSource.location.distanceTo(undSource.location, kmPerDeg) <= checkDist) {
								remove = false;
								break;
							}
						}
					}
				} else {
					checkDist = ixDistance + desSource.getRuleExtraDistance();
					if (desSource.location.distanceTo(undSource.location, kmPerDeg) <= checkDist) {
						remove = false;
					}
				}
			}

			if (remove) {
				lit.remove();
			}
		}

		// Add the new records to the scenario.

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

		for (SourceEditData aSource : newSources) {
			scenario.sourceData.addOrReplace(aSource, false, true);
		}

		return newSources.size();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Encode source data as an XML description, see comments in superclass.  The desired flag is supported here
	// although it is not currently being used, wireless records are interference sources only in the study engine,
	// no coverage analysis is ever done.  But that could change in the future.  The record ID values for the wireless
	// data are not persistent, they are specific to one data set import, so these are never by-reference exports, all
	// fields are always included.  On import the record is never tied to a data set.

	protected boolean writeToXML(Writer xml, boolean standalone, boolean isDesiredFlag, boolean isUndesiredFlag,
			ErrorLogger errors) throws IOException {

		if (!isDataValid(errors)) {
			return false;
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
		xml.append(" CELL_SITE_ID=\"" + AppCore.xmlclean(callSign) + '"');
		xml.append(" SECTOR_ID=\"" + AppCore.xmlclean(sectorID) + '"');
		xml.append(" CITY=\"" + AppCore.xmlclean(city) + '"');
		xml.append(" STATE=\"" + AppCore.xmlclean(state) + '"');
		xml.append(" COUNTRY=\"" + country.countryCode + '"');
		xml.append(" REFERENCE_NUMBER=\"" + AppCore.xmlclean(fileNumber) + '"');

		// Superclass does the rest.

		writeAttributes(xml, errors);

		xml.append("</SOURCE>\n");

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Make a new source object using values from an XML attributes list.

	protected static SourceEditDataWL makeSourceWithAttributesWL(String element, Attributes attrs,
			StudyEditData theStudy, String theDbID, Service theService, Country theCountry, boolean theIsLocked,
			Integer theUserRecordID, Integer theExtDbKey, String theExtRecordID, ErrorLogger errors) {

		String str;

		SourceEditDataWL newSource = createSource(theStudy, theDbID, theService, theCountry, theIsLocked,
			theUserRecordID, theExtDbKey, theExtRecordID, errors);
		if (null == newSource) {
			return null;
		}

		// Parse attributes for the source.

		if (!newSource.parseAttributesWL(element, attrs, errors)) {
			return null;
		}

		return newSource;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Make a new import data set source object using values from an XML attributes list.

	protected static SourceEditDataWL makeExtSourceWithAttributesWL(String element, Attributes attrs, ExtDb extDb,
			Service theService, Country theCountry, ErrorLogger errors) {

		String str;

		SourceEditDataWL newSource = createExtSource(extDb, theService, theCountry, errors);
		if (null == newSource) {
			return null;
		}

		if (!newSource.parseAttributesWL(element, attrs, errors)) {
			return null;
		}

		return newSource;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Parse an XML attributes list when building a new source.  Return false on error.  Applies all value checks as
	// in isDataValid(), as well as ensuring required attributes are always present.

	private boolean parseAttributesWL(String element, Attributes attrs, ErrorLogger errors) {

		String str;

		// Cell site ID is required (stored in call sign field internally), sector ID is optional.

		str = attrs.getValue("CELL_SITE_ID");
		if (null == str) {
			str = "";
		} else {
			str = str.trim();
		}
		if (0 == str.length()) {
			if (null != errors) {
				errors.reportError("Missing or bad CELL_SITE_ID attribute in " + element + " tag.");
			}
			return false;
		}
		if (str.length() > Source.MAX_CALL_SIGN_LENGTH) {
			str = str.substring(0, Source.MAX_CALL_SIGN_LENGTH);
		}
		callSign = str;

		str = attrs.getValue("SECTOR_ID");
		if (null != str) {
			str = str.trim();
			if (str.length() > Source.MAX_SECTOR_ID_LENGTH) {
				str = str.substring(0, Source.MAX_CALL_SIGN_LENGTH);
			}
			sectorID = str;
		}

		// City, state, and file number also optional.

		str = attrs.getValue("CITY");
		if (null != str) {
			str = str.trim();
			if (str.length() > Source.MAX_CITY_LENGTH) {
				str = str.substring(0, Source.MAX_CITY_LENGTH);
			}
			city = str;
		}

		str = attrs.getValue("STATE");
		if (null != str) {
			str = str.trim();
			if (str.length() > Source.MAX_STATE_LENGTH) {
				str = str.substring(0, Source.MAX_STATE_LENGTH);
			}
			state = str;
		}

		str = attrs.getValue("REFERENCE_NUMBER");
		if (null != str) {
			str = str.trim();
			if (str.length() > Source.MAX_FILE_NUMBER_LENGTH) {
				str = str.substring(0, Source.MAX_FILE_NUMBER_LENGTH);
			}
			fileNumber = str;
		}

		// Superclass does the rest.

		return parseAttributes(element, attrs, errors);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Methods in the Record interface, most are in the superclass.

	public String getCallSign() {

		if (sectorID.length() > 0) {
			return callSign + "-" + sectorID;
		}
		return callSign;
	}
}
