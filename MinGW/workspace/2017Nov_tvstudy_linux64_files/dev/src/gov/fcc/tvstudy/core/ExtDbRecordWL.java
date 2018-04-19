//
//  ExtDbRecordWL.java
//  TVStudy
//
//  Copyright (c) 2015-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core;

import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.editdata.*;
import gov.fcc.tvstudy.core.geo.*;

import java.util.*;
import java.text.*;
import java.sql.*;


//=====================================================================================================================
// Concrete subclass of ExtDbRecord for wireless records.  See the superclass for details.

public class ExtDbRecordWL extends ExtDbRecord implements Record {

	public String cellSiteID;
	public String sectorID;
	public String referenceNumber;


	//-----------------------------------------------------------------------------------------------------------------
	// Search a wireless database for records.  See superclass and ExtDbRecordTV for details.

	public static LinkedList<ExtDbRecord> findRecordsImpl(ExtDb extDb, String query, GeoPoint searchCenter,
			double searchRadius, double kmPerDegree, ErrorLogger errors) {
		LinkedList<ExtDbRecordWL> records = findRecordsWL(extDb, query, searchCenter, searchRadius, kmPerDegree,
			errors);
		if (null != records) {
			return new LinkedList<ExtDbRecord>(records);
		}
		return null;
	}

	public static LinkedList<ExtDbRecordWL> findRecordsWL(ExtDb extDb, String query) {
		return findRecordsWL(extDb, query, null, 0., 0., null);
	}

	public static LinkedList<ExtDbRecordWL> findRecordsWL(ExtDb extDb, String query, ErrorLogger errors) {
		return findRecordsWL(extDb, query, null, 0., 0., errors);
	}

	private static LinkedList<ExtDbRecordWL> findRecordsWL(ExtDb extDb, String query, GeoPoint searchCenter,
			double searchRadius, double kmPerDegree, ErrorLogger errors) {

		if (ExtDb.DB_TYPE_WIRELESS != extDb.type) {
			return new LinkedList<ExtDbRecordWL>();
		}

		// Service is fixed for these records.  Country will default to US if the database value is blank/unknown.

		Service theService = Service.getService("WL");
		if (null == theService) {
			if (null != errors) {
				errors.reportError("ExtDbRecordWL.findRecords(): could not find service.");
			}
			return null;
		}

		Country defCountry = Country.getCountry(Country.US);
		if (null == defCountry) {
			if (null != errors) {
				errors.reportError("ExtDbRecordWL.findRecords(): could not find country.");
			}
			return null;
		}

		LinkedList<ExtDbRecordWL> result = null;

		DbConnection db = extDb.connectDb(errors);
		if (null != db) {
			try {

				String whrStr = "";
				if ((null != query) && (query.length() > 0)) {
					whrStr =
					"WHERE " +
						"(" + query + ") ";
				}

				db.query(
				"SELECT " +
					"cell_key," +
					"cell_site_id," +
					"sector_id," +
					"cell_lat," +
					"cell_lon," +
					"rc_amsl," +
					"haat," +
					"erp," +
					"az_ant_id," +
					"orientation," +
					"el_ant_id," +
					"e_tilt," +
					"m_tilt, " +
					"m_tilt_orientation, " +
					"reference_number, " +
					"city, " +
					"state, " +
					"country " +
				"FROM " +
					ExtDb.WIRELESS_BASE_TABLE + " " +
				whrStr +
				"ORDER BY 1");

				result = new LinkedList<ExtDbRecordWL>();
				ExtDbRecordWL theRecord;

				int cellKey, antID;
				String recID, str;
				double lat, lon;

				while (db.next()) {

					cellKey = db.getInt(1);
					if (cellKey <= 0) {
						continue;
					}
					recID = String.valueOf(cellKey);

					// Extract coordinates first, if a search radius is set, check that.

					lat = db.getDouble(4);
					lon = db.getDouble(5);

					if ((null != searchCenter) && (searchRadius > 0.) &&
							(searchCenter.distanceTo(lat, lon, kmPerDegree) > searchRadius)) {
						continue;
					}

					// Save the record in the results.

					theRecord = new ExtDbRecordWL(extDb);

					theRecord.extRecordID = recID;

					theRecord.service = theService;

					str = db.getString(2);
					if (null == str) {
						str = "";
					} else {
						if (str.length() > Source.MAX_CALL_SIGN_LENGTH) {
							str = str.substring(0, Source.MAX_CALL_SIGN_LENGTH);
						}
					}
					theRecord.cellSiteID = str;

					str = db.getString(3);
					if (null == str) {
						str = "";
					} else {
						if (str.length() > Source.MAX_SECTOR_ID_LENGTH) {
							str = str.substring(0, Source.MAX_SECTOR_ID_LENGTH);
						}
					}
					theRecord.sectorID = str;

					theRecord.location.setLatLon(lat, lon);

					theRecord.heightAMSL = db.getDouble(6);
					theRecord.overallHAAT = db.getDouble(7);

					theRecord.peakERP = db.getDouble(8);

					antID = db.getInt(9);
					if (antID > 0) {
						theRecord.antennaRecordID = String.valueOf(antID);
						theRecord.antennaID = theRecord.antennaRecordID;
					}
					theRecord.horizontalPatternOrientation = Math.IEEEremainder(db.getDouble(10), 360.);
					if (theRecord.horizontalPatternOrientation < 0.) theRecord.horizontalPatternOrientation += 360.;

					antID = db.getInt(11);
					if (antID > 0) {
						theRecord.elevationAntennaRecordID = String.valueOf(antID);
					}
					theRecord.verticalPatternElectricalTilt = db.getDouble(12);
					theRecord.verticalPatternMechanicalTilt = db.getDouble(13);
					theRecord.verticalPatternMechanicalTiltOrientation = Math.IEEEremainder(db.getDouble(14), 360.);
					if (theRecord.verticalPatternMechanicalTiltOrientation < 0.)
						theRecord.verticalPatternMechanicalTiltOrientation += 360.;

					theRecord.referenceNumber = db.getString(15);

					theRecord.city = db.getString(16);
					theRecord.state = db.getString(17);

					theRecord.country = Country.getCountry(db.getString(18));
					if (null == theRecord.country) {
						theRecord.country = defCountry;
					}

					result.add(theRecord);
				}

				extDb.releaseDb(db);

			} catch (SQLException se) {
				extDb.releaseDb(db);
				result = null;
				DbConnection.reportError(errors, se);
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Convenience to do a search for a specific record by record ID.

	public static ExtDbRecordWL findRecordWL(ExtDb extDb, String extRecordID) {
		return findRecordWL(extDb, extRecordID, null);
	}

	public static ExtDbRecordWL findRecordWL(ExtDb extDb, String extRecordID, ErrorLogger errors) {

		if (Source.RECORD_TYPE_WL != extDb.recordType) {
			return null;
		}

		StringBuilder query = new StringBuilder();
		try {
			addRecordIDQueryWL(extDb.type, extDb.version, extRecordID, query, false);
		} catch (IllegalArgumentException ie) {
			if (null != errors) {
				errors.logMessage(ie.getMessage());
			}
			return null;
		}

		LinkedList<ExtDbRecordWL> theRecs = findRecordsWL(extDb, query.toString(), null, 0., 0., errors);

		if (null == theRecs) {
			return null;
		}
		if (theRecs.isEmpty()) {
			if (null != errors) {
				errors.logMessage("Record not found for record ID '" + extRecordID + "'.");
			}
			return null;
		}

		return theRecs.getFirst();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static boolean doesRecordIDExistWL(ExtDb extDb, String extRecordID) {

		if (Source.RECORD_TYPE_WL != extDb.recordType) {
			return false;
		}

		StringBuilder query = new StringBuilder();
		try {
			addRecordIDQueryWL(extDb.type, extDb.version, extRecordID, query, false);
		} catch (IllegalArgumentException ie) {
			return false;
		}

		DbConnection db = extDb.connectDb();
		if (null == db) {
			return false;
		}

		boolean result = false;

		try {

			db.query("SELECT COUNT(*) FROM " + ExtDb.WIRELESS_BASE_TABLE + " WHERE " + query.toString());

			if (db.next()) {
				result = (db.getInt(1) > 0);
			}

		} catch (SQLException se) {
			db.reportError(se);
		}

		extDb.releaseDb(db);

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Methods to support composing query clauses for findRecords() searching, see superclass for details.  The first
	// method adds a record ID search, the argument is validated as a number > 0.

	public static boolean addRecordIDQueryWL(int dbType, int version, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if (ExtDb.DB_TYPE_WIRELESS == dbType) {

			if ((null == str) || (0 == str.length())) {
				return false;
			}

			int theID = 0;
			try {
				theID = Integer.parseInt(str);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("The record ID must be a number.");
			}
			if (theID <= 0) {
				throw new IllegalArgumentException("The record ID must be greater than 0.");
			}

			if (combine) {
				query.append(" AND ");
			}

			query.append("(" + ExtDb.WIRELESS_BASE_TABLE + ".cell_key = ");
			query.append(str);
			query.append(')');

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// For a file number query, the input string parsing the superclass did is not really appropriate but mostly
	// harmless, just put the two parts of the string together with a wildcard in case a hyphen was removed.

	public static boolean addFileNumberQueryWL(int dbType, int version, String prefix, String arn, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if (ExtDb.DB_TYPE_WIRELESS == dbType) {

			if (combine) {
				query.append(" AND ");
			}

			query.append("(UPPER(" + ExtDb.WIRELESS_BASE_TABLE + ".reference_number) LIKE '");
			if (prefix.length() > 0) {
				query.append(prefix);
				query.append('%');
			}
			query.append(arn);
			query.append("')");

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// For a call sign search, if the argument string contains a '-' split on that into cell site ID and sector ID
	// searches, else just match the cell site ID.  If the first character is a '-' just search sector ID.

	public static boolean addCallSignQueryWL(int dbType, int version, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if (ExtDb.DB_TYPE_WIRELESS == dbType) {

			if ((null == str) || (0 == str.length())) {
				return false;
			}

			if (combine) {
				query.append(" AND ");
			}

			String siteID = null, sectID = null;
			int h = str.indexOf('-');
			if (h >= 0) {
				if (h > 0) {
					siteID = str.substring(0, h);
				}
				sectID = str.substring(h + 1);
			} else {
				siteID = str;
			}

			if (null != siteID) {
				query.append("(UPPER(" + ExtDb.WIRELESS_BASE_TABLE + ".cell_site_id) LIKE '");
				query.append(DbConnection.clean(siteID.toUpperCase()));
				query.append("%')");
			}

			if (null != sectID) {
				if (null != siteID) {
					query.append(" AND ");
				}
				query.append("(UPPER(" + ExtDb.WIRELESS_BASE_TABLE + ".sector_id) LIKE '");
				query.append(DbConnection.clean(sectID.toUpperCase()));
				query.append("%')");
			}

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add query clause for city name search, case-insensitive unanchored match, '*' wildcards are allowed.

	public static boolean addCityQueryWL(int dbType, int version, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if (ExtDb.DB_TYPE_WIRELESS == dbType) {

			if ((null == str) || (0 == str.length())) {
				return false;
			}

			if (combine) {
				query.append(" AND ");
			}

			query.append("(UPPER(" + ExtDb.WIRELESS_BASE_TABLE + ".city) LIKE '%");
			query.append(DbConnection.clean(str.toUpperCase()).replace('*', '%'));
			query.append("%')");

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add query clause for state code search, this is an exact string match but still case-insensitive.

	public static boolean addStateQueryWL(int dbType, int version, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if (ExtDb.DB_TYPE_WIRELESS == dbType) {

			if ((null == str) || (0 == str.length())) {
				return false;
			}

			if (combine) {
				query.append(" AND ");
			}

			query.append("(UPPER(" + ExtDb.WIRELESS_BASE_TABLE + ".state) = '");
			query.append(DbConnection.clean(str.toUpperCase()));
			query.append("')");

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add clause for country search.  See superclass for forms that resolve string and key arguments.

	public static boolean addCountryQueryWL(int dbType, int version, Country country, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if (ExtDb.DB_TYPE_WIRELESS == dbType) {

			if (combine) {
				query.append(" AND ");
			}

			query.append("(UPPER(" + ExtDb.WIRELESS_BASE_TABLE + ".country) = '");
			query.append(country.countryCode);
			query.append("')");

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add wireless sources to a scenario.  This is only used for an OET-74 study type, where the scenario will always
	// contain exactly one desired TV source.  However there may still be some search criteria applied so those are
	// passed as in the TV and FM classes.  This is a different concept than for other record types.  These records are
	// never desired sources studied for coverage, they are only potential interferers to TV records, so desired and
	// protected searches are not supported and always return 0.  The channel relationship (spectral overlap) is
	// determined by scenario parameters, the wireless records do not have the frequency assigned individually in the
	// data set, so there are no channel-related search criteria.  There are no MX or operating-record concepts, all
	// wireless records in a data set are assumed to be in simultaneous operation.  This applies distance table checks
	// based on HAAT and ERP to cull the wireless records.  The culling distance tables are in study parameters.  If
	// the HAAT is undefined the maximum is assumed.  This is always using the worst-case of 5 MHz spectral overlap so
	// it will find all records that might interfere regardless of the frequency and bandwidth parameters.

	public static int addRecords(ExtDb extDb, ScenarioEditData scenario, int searchType, String query,
			GeoPoint searchCenter, double searchRadius, ErrorLogger errors) {

		if (extDb.isGeneric() || (Source.RECORD_TYPE_WL != extDb.recordType) ||
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
		SourceEditDataTV theSource = (SourceEditDataTV)theSources.get(0);

		// Do the search.  An empty result in this case is not an error.

		LinkedList<ExtDbRecordWL> records = findRecordsWL(extDb, query, searchCenter, searchRadius, kmPerDeg, errors);

		if (null == records) {
			return -1;
		}
		if (records.isEmpty()) {
			return 0;
		}

		// Scan records and check distances.  Also check for existing identical records in the scenario

		ArrayList<SourceEditData> oldSources = scenario.sourceData.getSources(Source.RECORD_TYPE_WL);

		ListIterator<ExtDbRecordWL> lit = records.listIterator(0);
		ExtDbRecordWL undRecord;
		boolean remove;
		double ixDistance, checkDist;
		int haatIndex, erpIndex;

		while (lit.hasNext()) {

			undRecord = lit.next();
			remove = false;

			for (SourceEditData oldSource : oldSources) {
				if (undRecord.extDb.key.equals(oldSource.extDbKey) &&
						undRecord.extRecordID.equals(oldSource.extRecordID)) {
					remove = true;
					break;
				}
			}

			if (!remove) {

				remove = true;

				if (Source.HEIGHT_DERIVE == undRecord.overallHAAT) {
					haatIndex = 0;
				} else {
					for (haatIndex = 1; haatIndex < Parameter.WL_CULL_HAAT_COUNT; haatIndex++) {
						if (undRecord.overallHAAT > Parameter.wirelessCullHAAT[haatIndex]) {
							break;
						}
					}
					haatIndex--;
				}

				for (erpIndex = 1; erpIndex < Parameter.WL_CULL_ERP_COUNT; erpIndex++) {
					if (undRecord.peakERP > Parameter.wirelessCullERP[erpIndex]) {
						break;
					}
				}
				erpIndex--;

				ixDistance = wirelessCullDistance[(haatIndex * Parameter.WL_CULL_ERP_COUNT) + erpIndex];

				// Check distance, see comments in ExtDbRecordTV.addUndesireds().

				if (theSource.isParent) {
					for (SourceEditDataTV dtsSource : theSource.getDTSSources()) {
						if (dtsSource.siteNumber > 0) {
							checkDist = ixDistance + dtsSource.getRuleExtraDistance();
							if (dtsSource.location.distanceTo(undRecord.location, kmPerDeg) <= checkDist) {
								remove = false;
								break;
							}
						}
					}
				} else {
					checkDist = ixDistance + theSource.getRuleExtraDistance();
					if (theSource.location.distanceTo(undRecord.location, kmPerDeg) <= checkDist) {
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

		if (null != errors) {
			errors.clearErrors();
		} else {
			errors = new ErrorLogger(null, null);
		}

		for (ExtDbRecordWL theRecord : records) {
			newSource = scenario.study.findSharedSource(theRecord.extDb.key, theRecord.extRecordID);
			if (null == newSource) {
				newSource = SourceEditData.makeSource(theRecord, scenario.study, true, errors);
				if (null == newSource) {
					if (errors.hasErrors()) {
						return -1;
					}
					continue;
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

	private ExtDbRecordWL(ExtDb theExtDb) {

		super(theExtDb, Source.RECORD_TYPE_WL);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update a source record object from this object's properties, this is called by makeSource() in SourceEditDataWL
	// when creating a new source encapsulating this record.  See ExtDbRecordTV.updateSourceTV() for details.

	public boolean updateSource(SourceEditDataWL theSource) {
		return updateSource(theSource, null);
	}

	public boolean updateSource(SourceEditDataWL theSource, ErrorLogger errors) {

		if (!extDb.dbID.equals(theSource.dbID) || !service.equals(theSource.service) ||
				!country.equals(theSource.country)) {
			if (null != errors) {
				errors.reportError("ExtDbRecordWL.updateSource(): non-matching source object.");
			}
			return false;
		}

		if (ExtDb.DB_TYPE_WIRELESS != extDb.type) {
			if (null != errors) {
				errors.reportError("ExtDbRecordWL.updateSource(): unsupported external record type.");
			}
			return false;
		}

		// Some of the logic below depends on checking for reported errors from other methods.  If the caller did not
		// provide an error logger, create a temporary one.  Also saves having to check for null everywhere.

		if (null != errors) {
			errors.clearErrors();
		} else {
			errors = new ErrorLogger(null, null);
		}
		boolean error = false;

		// Begin copying properties.

		theSource.callSign = cellSiteID;
		theSource.sectorID = sectorID;
		theSource.city = city;
		theSource.state = state;
		theSource.fileNumber = referenceNumber;
		theSource.location.setLatLon(location);
		theSource.heightAMSL = heightAMSL;
		theSource.overallHAAT = overallHAAT;
		theSource.peakERP = peakERP;

		// Get horizontal pattern data if needed.  If pattern data does not exist set pattern to omni.  A pattern with
		// too few points or bad data values will also revert to omni, see pattern-load methods below for details.

		if (null != antennaRecordID) {

			String theName = null;

			DbConnection db = extDb.connectDb(errors);
			if (null != db) {
				try {

					db.query("SELECT name FROM " + ExtDb.WIRELESS_INDEX_TABLE + " WHERE ant_id = " + antennaRecordID);
					if (db.next()) {
						theName = db.getString(1);
						if (theName.length() > Source.MAX_PATTERN_NAME_LENGTH) {
							theName = theName.substring(0, Source.MAX_PATTERN_NAME_LENGTH);
						}
					} else {
						errors.logMessage(makeMessage(this, "Antenna record ID " + antennaRecordID + " not found."));
					}

					extDb.releaseDb(db);

				} catch (SQLException se) {
					extDb.releaseDb(db);
					error = true;
					DbConnection.reportError(errors, se);
				}

			} else {
				error = true;
			}

			if (error) {
				return false;
			}

			if (null != theName) {

				ArrayList<AntPattern.AntPoint> thePoints = getAntennaPattern(this, errors);

				if ((null != thePoints) && thePoints.isEmpty()) {
					thePoints = null;
				}

				if (null != thePoints) {

					theSource.antennaID = antennaID;
					theSource.hasHorizontalPattern = true;
					theSource.horizontalPattern = new AntPattern(theSource.dbID, AntPattern.PATTERN_TYPE_HORIZONTAL,
						theName, thePoints);
					theSource.horizontalPatternChanged = true;

				} else {

					if (errors.hasErrors()) {
						return false;
					}
				}
			}
		}

		theSource.horizontalPatternOrientation = horizontalPatternOrientation;

		// Now the vertical pattern, similar logic to the horizontal.

		if (null != elevationAntennaRecordID) {

			String theName = null;

			DbConnection db = extDb.connectDb(errors);
			if (null != db) {
				try {

					db.query("SELECT name FROM " + ExtDb.WIRELESS_INDEX_TABLE + " WHERE ant_id = " +
						elevationAntennaRecordID);
					if (db.next()) {
						theName = db.getString(1);
						if (theName.length() > Source.MAX_PATTERN_NAME_LENGTH) {
							theName = theName.substring(0, Source.MAX_PATTERN_NAME_LENGTH);
						}
					} else {
						errors.logMessage(makeMessage(this, "Antenna record ID " + elevationAntennaRecordID +
							" not found."));
					}

					extDb.releaseDb(db);

				} catch (SQLException se) {
					extDb.releaseDb(db);
					error = true;
					DbConnection.reportError(errors, se);
				}

			} else {
				error = true;
			}

			if (error) {
				return false;
			}

			if (null != theName) {

				ArrayList<AntPattern.AntPoint> thePoints = getElevationPattern(this, errors);

				if ((null != thePoints) && thePoints.isEmpty()) {
					thePoints = null;
				}

				if (null != thePoints) {

					theSource.hasVerticalPattern = true;
					theSource.verticalPattern = new AntPattern(theSource.dbID, AntPattern.PATTERN_TYPE_VERTICAL,
						theName, thePoints);
					theSource.verticalPatternChanged = true;

				} else {

					if (errors.hasErrors()) {
						return false;
					}
				}
			}
		}

		theSource.verticalPatternElectricalTilt = verticalPatternElectricalTilt;
		theSource.verticalPatternMechanicalTilt = verticalPatternMechanicalTilt;
		theSource.verticalPatternMechanicalTiltOrientation = verticalPatternMechanicalTiltOrientation;

		// Done.

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Methods in the Record interface, most are in the superclass.

	public String getCallSign() {

		if (sectorID.length() > 0) {
			return cellSiteID + "-" + sectorID;
		}
		return cellSiteID;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getFileNumber() {

		return referenceNumber;
	}
}
