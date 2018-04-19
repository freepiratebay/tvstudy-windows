//
//  ExtDbRecordFM.java
//  TVStudy
//
//  Copyright (c) 2015-2018 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core;

import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.editdata.*;
import gov.fcc.tvstudy.core.geo.*;

import java.util.*;
import java.text.*;
import java.sql.*;


//=====================================================================================================================
// Concrete subclass of ExtDbRecord for FM records.  See the superclass for details.

public class ExtDbRecordFM extends ExtDbRecord implements Record {

	// Mappings for status codes.  The value order of the types generally defines a record-preference order, see
	// isPreferredRecord().

	public static final String[] CDBS_STATUS_CODES = {
		"APP", "CP", "CP MOD", "LIC", "STA"
	};
	public static final int[] CDBS_STATUS_TYPES = {
		STATUS_TYPE_APP, STATUS_TYPE_CP, STATUS_TYPE_CP, STATUS_TYPE_LIC, STATUS_TYPE_STA
	};

	// List of station classes, the stationClass property is an index into this array.  There is currently no benefit
	// to putting this in a root database enumeration table.

	public static final int FM_CLASS_OTHER = 0;
	public static final int FM_CLASS_C = 1;
	public static final int FM_CLASS_C0 = 2;
	public static final int FM_CLASS_C1 = 3;
	public static final int FM_CLASS_C2 = 4;
	public static final int FM_CLASS_C3 = 5;
	public static final int FM_CLASS_B = 6;
	public static final int FM_CLASS_B1 = 7;
	public static final int FM_CLASS_A = 8;
	public static final int FM_CLASS_D = 9;
	public static final int FM_CLASS_L1 = 10;
	public static final int FM_CLASS_L2 = 11;

	public static final String[] FM_CLASS_CODES = {
		"", "C", "C0", "C1", "C2", "C3", "B", "B1", "A", "D", "L1", "L2"
	};

	// Properties.

	public int facilityID;
	public boolean isIBOC;
	public int stationClass;
	public String callSign;
	public int channel;
	public String status;
	public int statusType;
	public String filePrefix;
	public String appARN;
	public boolean daIndicated;

	// See isOperating().

	private boolean isOperatingFacility;
	private boolean operatingStatusSet;


	//-----------------------------------------------------------------------------------------------------------------
	// Search a CDBS database for FM records.  The query does an inner join on tables fm_eng_data, application, and
	// facility.  Records that have an unknown service or country, bad facility IDs, or bad channel are ignored.  If
	// the database type does not support FM records return an empty list.

	public static LinkedList<ExtDbRecord> findRecordsImpl(ExtDb extDb, String query, GeoPoint searchCenter,
			double searchRadius, double kmPerDegree, ErrorLogger errors) {
		LinkedList<ExtDbRecordFM> records = findRecordsFM(extDb, query, searchCenter, searchRadius, kmPerDegree,
			errors);
		if (null != records) {
			return new LinkedList<ExtDbRecord>(records);
		}
		return null;
	}

	public static LinkedList<ExtDbRecordFM> findRecordsFM(ExtDb extDb, String query) {
		return findRecordsFM(extDb, query, null, 0., 0., null);
	}

	public static LinkedList<ExtDbRecordFM> findRecordsFM(ExtDb extDb, String query, ErrorLogger errors) {
		return findRecordsFM(extDb, query, null, 0., 0., errors);
	}

	private static LinkedList<ExtDbRecordFM> findRecordsFM(ExtDb extDb, String query, GeoPoint searchCenter,
			double searchRadius, double kmPerDegree, ErrorLogger errors) {

		if (ExtDb.DB_TYPE_CDBS_FM != extDb.type) {
			return new LinkedList<ExtDbRecordFM>();
		}

		LinkedList<ExtDbRecordFM> result = null;

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
					"fm_eng_data.application_id," +
					"fm_eng_data.facility_id," +
					"fm_eng_data.station_class," +
					"fm_eng_data.station_channel," +
					"(CASE WHEN (facility.fac_callsign <> '') " +
						"THEN facility.fac_callsign ELSE application.fac_callsign END)," +
					"(CASE WHEN (facility.comm_city <> '') " +
						"THEN facility.comm_city ELSE application.comm_city END)," +
					"(CASE WHEN (facility.comm_state <> '') " +
						"THEN facility.comm_state ELSE application.comm_state END)," +
					"fm_eng_data.fm_dom_status," +
					"application.file_prefix," +
					"application.app_arn," +
					"fm_eng_data.lat_dir," +
					"fm_eng_data.lat_deg," +
					"fm_eng_data.lat_min," +
					"fm_eng_data.lat_sec," +
					"fm_eng_data.lon_dir," +
					"fm_eng_data.lon_deg," +
					"fm_eng_data.lon_min," +
					"fm_eng_data.lon_sec," +
					"fm_eng_data.rcamsl_horiz_mtr," +
					"fm_eng_data.rcamsl_vert_mtr," +
					"fm_eng_data.haat_horiz_rc_mtr," +
					"fm_eng_data.haat_vert_rc_mtr," +
					"fm_eng_data.max_horiz_erp," +
					"fm_eng_data.horiz_erp," +
					"fm_eng_data.max_vert_erp," +
					"fm_eng_data.vert_erp," +
					"fm_eng_data.antenna_id," +
					"fm_eng_data.ant_rotation," +
					"fm_eng_data.eng_record_type," +
					"fm_app_indicators.da_ind," +
					"fm_eng_data.asd_service," +
					"facility.fac_country," +
					"(CASE WHEN (app_tracking.accepted_date IS NOT NULL " +
						"AND (app_tracking.accepted_date <> '')) " +
						"THEN app_tracking.accepted_date " +
						"ELSE fm_eng_data.last_change_date END) AS sequence_date, " +
					"facility.digital_status, " +
					"facility.fac_status " +
				"FROM " +
					"fm_eng_data " +
					"JOIN facility USING (facility_id) " +
					"JOIN application USING (application_id) " +
					"LEFT JOIN fm_app_indicators USING (application_id) " +
					"LEFT JOIN app_tracking USING (application_id) " +
				whrStr +
				"ORDER BY " +
					"application_id DESC");

				result = new LinkedList<ExtDbRecordFM>();
				ExtDbRecordFM theRecord;

				Service theService;
				Country theCountry;
				int appID, chan, i, antID, statType;
				double d1, d2, d3, d4;
				String str, recID, dir, stat, pfx, arn;
				GeoPoint thePoint = new GeoPoint();

				SimpleDateFormat dateFormat;
				dateFormat = new SimpleDateFormat("MM/dd/yyyy", Locale.US);
				java.util.Date seqDate = null;

				while (db.next()) {

					theService = cdbsCodeCache.get(db.getString(31));
					if (null == theService) {
						continue;
					}

					theCountry = Country.getCountry(db.getString(32));
					if (null == theCountry) {
						continue;
					}

					appID = db.getInt(1);
					if (appID <= 0) {
						continue;
					}
					recID = String.valueOf(appID);

					chan = db.getInt(4);
					if ((chan < SourceFM.CHANNEL_MIN) || (chan > SourceFM.CHANNEL_MAX)) {
						continue;
					}

					// Extract sequence date, see isPreferredRecord().

					seqDate = null;
					try {
						str = db.getString(33);
						if (null != str) {
							seqDate = dateFormat.parse(str);
						}
					} catch (ParseException pe) {
					}
					if (null == seqDate) {
						seqDate = new java.util.Date(0);
					}

					// Facility status may affect other properties.

					String facStat = db.getString(35);
					if (null == facStat) {
						facStat = "";
					}

					// A facility status of "EXPER" is experimental regardless of anything else.

					stat = db.getString(8);
					if (null == stat) {
						stat = "";
					}
					statType = STATUS_TYPE_OTHER;

					if (facStat.equals("EXPER")) {
						statType = STATUS_TYPE_EXP;
					} else {

						for (i = 0; i < CDBS_STATUS_CODES.length; i++) {
							if (CDBS_STATUS_CODES[i].equalsIgnoreCase(stat)) {
								statType = CDBS_STATUS_TYPES[i];
								break;
							}
						}
					}

					if (STATUS_TYPE_OTHER != statType) {
						stat = STATUS_CODES[statType];
					}

					// Extract coordinates first, if a search radius is set, check that.

					thePoint.latitudeNS = 0;
					dir = db.getString(11);
					if ((null != dir) && dir.equalsIgnoreCase("S")) {
						thePoint.latitudeNS = 1;
					}
					thePoint.latitudeDegrees = db.getInt(12);
					thePoint.latitudeMinutes = db.getInt(13);
					thePoint.latitudeSeconds = db.getDouble(14);

					thePoint.longitudeWE = 0;
					dir = db.getString(15);
					if ((null != dir) && dir.equalsIgnoreCase("E")) {
						thePoint.longitudeWE = 1;
					}
					thePoint.longitudeDegrees = db.getInt(16);
					thePoint.longitudeMinutes = db.getInt(17);
					thePoint.longitudeSeconds = db.getDouble(18);

					thePoint.updateLatLon();
					thePoint.convertFromNAD27();

					if ((null != searchCenter) && (searchRadius > 0.) &&
							(searchCenter.distanceTo(thePoint, kmPerDegree) > searchRadius)) {
						continue;
					}

					// Save the record in the results.

					theRecord = new ExtDbRecordFM(extDb);

					theRecord.service = theService;
					theRecord.country = theCountry;
					theRecord.extRecordID = recID;

					theRecord.facilityID = db.getInt(2);
					theRecord.channel = chan;

					theRecord.stationClass = FM_CLASS_OTHER;
					str = db.getString(3);
					if ((null != str) && (str.length() > 0)) {
						for (i = 1; i < FM_CLASS_CODES.length; i++) {
							if (str.equalsIgnoreCase(FM_CLASS_CODES[i])) {
								theRecord.stationClass = i;
								break;
							}
						}
					}

					str = db.getString(5);
					if (null == str) {
						str = "";
					} else {
						if (str.length() > Source.MAX_CALL_SIGN_LENGTH) {
							str = str.substring(0, Source.MAX_CALL_SIGN_LENGTH);
						}
					}
					theRecord.callSign = str;

					str = db.getString(6);
					if (null == str) {
						str = "";
					} else {
						if (str.length() > Source.MAX_CITY_LENGTH) {
							str = str.substring(0, Source.MAX_CITY_LENGTH);
						}
					}
					theRecord.city = str;
					str = db.getString(7);
					if (null == str) {
						str = "";
					} else {
						if (str.length() > Source.MAX_STATE_LENGTH) {
							str = str.substring(0, Source.MAX_STATE_LENGTH);
						}
					}
					theRecord.state = str;

					if (stat.length() > Source.MAX_STATUS_LENGTH) {
						stat = stat.substring(0, Source.MAX_STATUS_LENGTH);
					}
					theRecord.status = stat;
					theRecord.statusType = statType;

					pfx = db.getString(9);
					arn = db.getString(10);
					if (null == arn) {
						arn = "";
					} else {
						if (arn.length() > Source.MAX_FILE_NUMBER_LENGTH) {
							arn = arn.substring(0, Source.MAX_FILE_NUMBER_LENGTH);
						}
					}
					if (null == pfx) {
						pfx = "";
					} else {
						if ((pfx.length() + arn.length()) > Source.MAX_FILE_NUMBER_LENGTH) {
							pfx = pfx.substring(0, Source.MAX_FILE_NUMBER_LENGTH - arn.length());
						}
					}
					theRecord.filePrefix = pfx;
					theRecord.appARN = arn;

					theRecord.location.setLatLon(thePoint);

					d1 = db.getDouble(19);
					d2 = db.getDouble(20);
					theRecord.heightAMSL = (d1 > d2 ? d1 : d2);
					d1 = db.getDouble(21);
					d2 = db.getDouble(22);
					theRecord.overallHAAT = (d1 > d2 ? d1 : d2);

					d1 = db.getDouble(23);
					d2 = db.getDouble(24);
					d3 = (d1 > d2 ? d1 : d2);
					d1 = db.getDouble(25);
					d2 = db.getDouble(26);
					d4 = (d1 > d2 ? d1 : d2);
					theRecord.peakERP = (d3 > d4 ? d3 : d4);

					antID = db.getInt(27);
					if (antID > 0) {
						theRecord.antennaRecordID = String.valueOf(antID);
						theRecord.antennaID = theRecord.antennaRecordID;
					}
					theRecord.horizontalPatternOrientation = Math.IEEEremainder(db.getDouble(28), 360.);
					if (theRecord.horizontalPatternOrientation < 0.) theRecord.horizontalPatternOrientation += 360.;

					// If the facility status is FVOID, PRCAN, or LICAN, treat this as an archived record regardless
					// of other fields.

					if ((facStat.equals("FVOID") || facStat.equals("PRCAN") || facStat.equals("LICAN"))) {
						theRecord.isArchived = true;
					} else {
						str = db.getString(29);
						if (null != str) {
							if (str.equalsIgnoreCase("P")) {
								theRecord.isPending = true;
							} else {
								if (str.equalsIgnoreCase("A")) {
									theRecord.isArchived = true;
								}
							}
						}
					}

					str = db.getString(30);
					if (null != str) {
						theRecord.daIndicated = str.equalsIgnoreCase("Y");
					}

					theRecord.sequenceDate = seqDate;

					str = db.getString(34);
					if (null != str) {
						theRecord.isIBOC = str.equalsIgnoreCase("H");
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

	public static ExtDbRecordFM findRecordFM(String theDbID, Integer extDbKey, String extRecordID) {
		return findRecordFM(theDbID, extDbKey, extRecordID, null);
	}

	public static ExtDbRecordFM findRecordFM(String theDbID, Integer extDbKey, String extRecordID,
			ErrorLogger errors) {
		ExtDb extDb = ExtDb.getExtDb(theDbID, extDbKey, errors);
		if (null == extDb) {
			return null;
		}
		return findRecordFM(extDb, extRecordID, errors);
	}

	public static ExtDbRecordFM findRecordFM(ExtDb extDb, String extRecordID) {
		return findRecordFM(extDb, extRecordID, null);
	}

	public static ExtDbRecordFM findRecordFM(ExtDb extDb, String extRecordID, ErrorLogger errors) {

		if (Source.RECORD_TYPE_FM != extDb.recordType) {
			return null;
		}

		StringBuilder query = new StringBuilder();
		try {
			addRecordIDQueryFM(extDb.type, extDb.version, extRecordID, query, false);
		} catch (IllegalArgumentException ie) {
			if (null != errors) {
				errors.logMessage(ie.getMessage());
			}
			return null;
		}

		LinkedList<ExtDbRecordFM> theRecs = findRecordsFM(extDb, query.toString(), null, 0., 0., errors);

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
	// Batch search for individual records by ID, used to efficiently find multiple records since individual use of
	// findRecordTV() may have very poor performance.

	public static HashMap<String, ExtDbRecordFM> batchFindRecordFM(ExtDb extDb, HashSet<String> theExtRecordIDs) {
		return batchFindRecordFM(extDb, theExtRecordIDs, null);
	}

	public static HashMap<String, ExtDbRecordFM> batchFindRecordFM(ExtDb extDb, HashSet<String> theExtRecordIDs,
			ErrorLogger errors) {

		HashMap<String, ExtDbRecordFM> result = new HashMap<String, ExtDbRecordFM>();

		if (Source.RECORD_TYPE_FM != extDb.recordType) {
			return result;
		}

		boolean doSearch = false;
		StringBuilder query = new StringBuilder("fm_eng_data.application_id IN ");
		String sep = "(";

		int applicationID;

		for (String theID : theExtRecordIDs) {

			applicationID = 0;
			try {
				applicationID = Integer.parseInt(theID);
			} catch (NumberFormatException ne) {
			}
			if (applicationID <= 0) {
				continue;
			}

			query.append(sep);
			query.append(String.valueOf(applicationID));
			sep = ",";

			doSearch = true;
		}

		if (doSearch) {

			query.append(")");

			LinkedList<ExtDbRecordFM> theRecs = findRecordsFM(extDb, query.toString(), null, 0., 0., errors);
			if (null == theRecs) {
				return null;
			}

			for (ExtDbRecordFM theRec : theRecs) {
				result.put(theRec.extRecordID, theRec);
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static boolean doesRecordIDExistFM(ExtDb extDb, String extRecordID) {

		if (Source.RECORD_TYPE_FM != extDb.recordType) {
			return false;
		}

		StringBuilder query = new StringBuilder();
		try {
			addRecordIDQueryFM(extDb.type, extDb.version, extRecordID, query, false);
		} catch (IllegalArgumentException ie) {
			return false;
		}

		DbConnection db = extDb.connectDb();
		if (null == db) {
			return false;
		}

		boolean result = false;

		try {

			db.query("SELECT COUNT(*) FROM fm_eng_data WHERE " + query.toString());

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
	// method adds a record ID search.  The argument is validated as a number > 0 for application ID match.

	public static boolean addRecordIDQueryFM(int dbType, int version, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if (ExtDb.DB_TYPE_CDBS_FM == dbType) {

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

			query.append("(fm_eng_data.application_id = ");
			query.append(str);
			query.append(')');

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Search by file number.  See superclass for details.

	public static boolean addFileNumberQueryFM(int dbType, int version, String prefix, String arn, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if (ExtDb.DB_TYPE_CDBS_FM == dbType) {

			if (combine) {
				query.append(" AND ");
			}

			if (prefix.length() > 0) {
				query.append("((UPPER(application.file_prefix) = '");
				query.append(prefix);
				query.append("') AND (UPPER(application.app_arn) = '");
				query.append(arn);
				query.append("'))");
			} else {
				query.append("(UPPER(application.app_arn) = '");
				query.append(arn);
				query.append("')");
			}

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query clause to search by facility ID.  The version that takes a string argument is in the superclass.

	public static boolean addFacilityIDQueryFM(int dbType, int version, int facilityID, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if (ExtDb.DB_TYPE_CDBS_FM == dbType) {

			if (combine) {
				query.append(" AND ");
			}

			query.append("(fm_eng_data.facility_id = ");
			query.append(String.valueOf(facilityID));
			query.append(')');

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query clause to search by service.  Uses the code map, see loadCache() in the superclass, and allows the
	// service key to map to more than one service code.

	public static boolean addServiceQueryFM(int dbType, int version, int serviceKey, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if (ExtDb.DB_TYPE_CDBS_FM == dbType) {

			if (combine) {
				query.append(" AND ");
			}

			query.append("(fm_eng_data.asd_service IN ");
			String s = "('";

			for (Map.Entry<String, Service> e : cdbsCodeCache.entrySet()) {
				if (e.getValue().key == serviceKey) {
					query.append(s);
					query.append(e.getKey());
					s = "','";
				}
			}

			query.append("'))");

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query clause to search by services matching isOperating flag.

	public static boolean addServiceTypeQueryFM(int dbType, int version, int operatingMatch, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if (ExtDb.DB_TYPE_CDBS_FM == dbType) {

			if (combine) {
				query.append(" AND ");
			}

			Service serv;

			query.append("(fm_eng_data.asd_service IN ");
			String s = "('";

			for (Map.Entry<String, Service> e : cdbsCodeCache.entrySet()) {
				serv = e.getValue();
				if ((Source.RECORD_TYPE_FM == serv.serviceType.recordType) &&
						((FLAG_MATCH_SET != operatingMatch) || serv.isOperating) &&
						((FLAG_MATCH_CLEAR != operatingMatch) || !serv.isOperating)) {
					query.append(s);
					query.append(e.getKey());
					s = "','";
				}
			}

			query.append("'))");

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query search clause for call sign, case-insensitive head-anchored match.

	public static boolean addCallSignQueryFM(int dbType, int version, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if (ExtDb.DB_TYPE_CDBS_FM == dbType) {

			if ((null == str) || (0 == str.length())) {
				return false;
			}

			if (combine) {
				query.append(" AND ");
			}

			query.append("(UPPER(CASE WHEN (facility.fac_callsign <> '') " +
				"THEN facility.fac_callsign ELSE application.fac_callsign END) REGEXP '^D*");
			query.append(DbConnection.clean(str.toUpperCase()));
			query.append(".*')");

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query search clause for a specific channel search.  See superclass for argument-checking forms.

	public static boolean addChannelQueryFM(int dbType, int version, int channel, int minimumChannel,
			int maximumChannel, StringBuilder query, boolean combine) throws IllegalArgumentException {

		if (ExtDb.DB_TYPE_CDBS_FM == dbType) {

			if ((minimumChannel > 0) && (maximumChannel > 0) &&
					((channel < minimumChannel) || (channel > maximumChannel))) {
				throw new IllegalArgumentException("The channel must be in the range " + minimumChannel + " to " +
					maximumChannel + ".");
			}

			if (combine) {
				query.append(" AND ");
			}

			query.append("(fm_eng_data.station_channel = ");
			query.append(String.valueOf(channel));
			query.append(')');

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query search clause for a channel range search.  The range arguments are assumed valid.

	public static boolean addChannelRangeQueryFM(int dbType, int version, int minimumChannel, int maximumChannel,
			StringBuilder query, boolean combine) throws IllegalArgumentException {

		if (ExtDb.DB_TYPE_CDBS_FM == dbType) {

			if (combine) {
				query.append(" AND ");
			}

			query.append("(fm_eng_data.station_channel BETWEEN ");
			query.append(String.valueOf(minimumChannel));
			query.append(" AND ");
			query.append(String.valueOf(maximumChannel));
			query.append(')');

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query search clause for matching a list of channels.  This is not meant to process direct user input, the
	// argument string is assumed to be a valid SQL value list composed by other code e.g. "(7,8,12,33)", containing
	// valid channel numbers.

	public static boolean addMultipleChannelQueryFM(int dbType, int version, String str, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if (ExtDb.DB_TYPE_CDBS_FM == dbType) {

			if (combine) {
				query.append(" AND ");
			}

			query.append("(fm_eng_data.station_channel IN ");
			query.append(str);
			query.append(')');

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query search clause for status.

	public static boolean addStatusQueryFM(int dbType, int version, int statusType, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if (ExtDb.DB_TYPE_CDBS_FM == dbType) {

			switch (statusType) {

				case STATUS_TYPE_STA:
				case STATUS_TYPE_CP:
				case STATUS_TYPE_LIC:
				case STATUS_TYPE_APP:
				case STATUS_TYPE_EXP: {
					break;
				}

				default: {
					throw new IllegalArgumentException("Unknown status code.");
				}
			}

			if (combine) {
				query.append(" AND ");
			}

			if (STATUS_TYPE_EXP == statusType) {

				query.append("(facility.fac_status = 'EXPER')");

			} else {

				query.append("(UPPER(fm_eng_data.fm_dom_status) IN ");
				String s = "('";

				for (int i = 0; i < CDBS_STATUS_TYPES.length; i++) {
					if (CDBS_STATUS_TYPES[i] == statusType) {
						query.append(s);
						query.append(CDBS_STATUS_CODES[i]);
						s = "','";
					}
				}

				query.append("'))");
			}

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add query clause for city name search, case-insensitive unanchored match, '*' wildcards are allowed.

	public static boolean addCityQueryFM(int dbType, int version, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if (ExtDb.DB_TYPE_CDBS_FM == dbType) {

			if ((null == str) || (0 == str.length())) {
				return false;
			}

			if (combine) {
				query.append(" AND ");
			}

			query.append("(UPPER(CASE WHEN (facility.comm_city <> '') " +
				"THEN facility.comm_city ELSE application.comm_city END) LIKE '%");
			query.append(DbConnection.clean(str.toUpperCase()).replace('*', '%'));
			query.append("%')");

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add query clause for state code search, this is an exact string match but still case-insensitive.

	public static boolean addStateQueryFM(int dbType, int version, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if (ExtDb.DB_TYPE_CDBS_FM == dbType) {

			if ((null == str) || (0 == str.length())) {
				return false;
			}

			if (combine) {
				query.append(" AND ");
			}

			query.append("(UPPER(CASE WHEN (facility.comm_state <> '') " +
				"THEN facility.comm_state ELSE application.comm_state END) = '");
			query.append(DbConnection.clean(str.toUpperCase()));
			query.append("')");

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add clause for country search.  See superclass for forms that resolve string and key arguments.

	public static boolean addCountryQueryFM(int dbType, int version, Country country, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if (ExtDb.DB_TYPE_CDBS_FM == dbType) {

			if (combine) {
				query.append(" AND ");
			}

			query.append("(UPPER(facility.fac_country) = '");
			query.append(country.countryCode);
			query.append("')");

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add record type search query clause.  Current records are always included, archived records may optionally be
	// included.  Any unknown record types are treated as current, so this excludes rather than includes, meaning
	// if includeArchived is true it does nothing.

	public static boolean addRecordTypeQueryFM(int dbType, int version, boolean includeArchived, StringBuilder query,
			boolean combine) {

		if (ExtDb.DB_TYPE_CDBS_FM == dbType) {

			if (includeArchived) {
				return false;
			}

			if (combine) {
				query.append(" AND ");
			}

			query.append("((fm_eng_data.eng_record_type <> 'A') AND " +
				"(facility.fac_status NOT IN ('FVOID', 'PRCAN', 'LICAN')))");

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add records as desired sources to the scenario using a station data search.  See comments in ExtDbRecordTV for
	// details; the concepts are similar here.

	private static class SearchDelta {
		int delta;
		double maximumDistance;
	}

	public static int addRecords(ExtDb extDb, ScenarioEditData scenario, int searchType, String query,
			GeoPoint searchCenter, double searchRadius, int minimumChannel, int maximumChannel, boolean disableMX,
			boolean mxFacilityIDOnly, boolean preferOperating, boolean setUndesired, ErrorLogger errors) {

		// Desired and protected searches are allowed in general-purpose FM studies and TV channel 6 vs. FM studies.

		int studyType = scenario.study.study.studyType;
		if (extDb.isGeneric() || (Source.RECORD_TYPE_FM != extDb.recordType) ||
				!Study.isRecordTypeAllowed(studyType, Source.RECORD_TYPE_FM) ||
				(((ExtDbSearch.SEARCH_TYPE_DESIREDS == searchType) ||
					(ExtDbSearch.SEARCH_TYPE_PROTECTEDS == searchType)) &&
					(Study.STUDY_TYPE_FM != studyType) && (Study.STUDY_TYPE_TV6_FM != studyType))) {
			return 0;
		}

		// Get necessary study parameters.

		double coChanMX = scenario.study.getCoChannelMxDistance();
		double kmPerDeg = scenario.study.getKilometersPerDegree();

		// For desired or protected FM record in a TV6-FM study restrict channel to the NCE band.

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
				addChannelRangeQueryFM(extDb.type, extDb.version, minChannel, maxChannel, q, hasCrit);
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

			// Build a list of search deltas, accumulate worst-case maximum distance.

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

			// Build list of channels to search.

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

			// No channels is not an error.

			if (0 == numChans) {
				return 0;
			}

			// Add the channel list or range to the query.

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
					addMultipleChannelQueryFM(extDb.type, extDb.version, chanList.toString(), q, hasCrit);
				} catch (IllegalArgumentException ie) {
				}

			} else {

				try {
					addChannelRangeQueryFM(extDb.type, extDb.version, minChannel, maxChannel, q, hasCrit);
				} catch (IllegalArgumentException ie) {
				}
			}
		}

		// Do the search.

		LinkedList<ExtDbRecordFM> records = findRecordsFM(extDb, q.toString(), searchCenter, searchRadius, kmPerDeg,
			errors);

		if (null == records) {
			return -1;
		}
		if (records.isEmpty()) {
			return 0;
		}

		// Remove mutually-exclusive records.

		removeAllMX(scenario, records, disableMX, mxFacilityIDOnly, preferOperating, coChanMX, kmPerDeg);

		// For protecteds or undesireds searches, eliminate any new records that are outside the maximum distance
		// limits from the rules.

		if (ExtDbSearch.SEARCH_TYPE_DESIREDS != searchType) {

			ListIterator<ExtDbRecordFM> lit = records.listIterator(0);
			ExtDbRecordFM theRecord;
			SourceEditDataFM theSource;
			boolean remove;
			int chanDelt;
			double checkDist;

			while (lit.hasNext()) {

				theRecord = lit.next();
				remove = true;

				for (SourceEditData aSource : theSources) {
					theSource = (SourceEditDataFM)aSource;

					if (ExtDbSearch.SEARCH_TYPE_PROTECTEDS == searchType) {
						chanDelt = theSource.channel - theRecord.channel;
					} else {
						chanDelt = theRecord.channel - theSource.channel;
					}

					for (SearchDelta theDelta : deltas) {

						if (theDelta.delta != chanDelt) {
							continue;
						}

						if (ExtDbSearch.SEARCH_TYPE_PROTECTEDS == searchType) {
							checkDist = theDelta.maximumDistance + theRecord.getRuleExtraDistance(scenario.study);
							if (theRecord.location.distanceTo(theSource.location, kmPerDeg) <= checkDist) {
								remove = false;
								break;
							}
						} else {
							checkDist = theDelta.maximumDistance + theSource.getRuleExtraDistance();
							if (theSource.location.distanceTo(theRecord.location, kmPerDeg) <= checkDist) {
								remove = false;
								break;
							}
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

		// Add the new records to the scenario.

		SourceEditData newSource;
		ArrayList<SourceEditData> newSources = new ArrayList<SourceEditData>();

		if (null != errors) {
			errors.clearErrors();
		} else {
			errors = new ErrorLogger(null, null);
		}

		for (ExtDbRecordFM theRecord : records) {
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
	// Filter a list of new records for MX relationships, see comments in ExtDbRecordTV.

	private static void removeAllMX(ScenarioEditData scenario, LinkedList<ExtDbRecordFM> records, boolean disableMX,
			boolean mxFacilityIDOnly, boolean preferOperating, double coChanMX, double kmPerDeg) {

		ArrayList<SourceEditData> theSources = scenario.sourceData.getSources(Source.RECORD_TYPE_FM);

		ListIterator<ExtDbRecordFM> lit = records.listIterator(0);
		ExtDbRecordFM theRecord;
		SourceEditDataFM theSource;
		while (lit.hasNext()) {
			theRecord = lit.next();
			for (SourceEditData aSource : theSources) {
				theSource = (SourceEditDataFM)aSource;
				if (theRecord.extRecordID.equals(theSource.extRecordID) ||
						(!disableMX && areRecordsMX(theRecord, theSource, mxFacilityIDOnly, coChanMX, kmPerDeg))) {
					lit.remove();
					break;
				}
			}
		}

		if (disableMX) {
			return;
		}

		// Check remaining records for MX pairs and pick one using isPreferredRecord().

		final boolean prefOp = preferOperating;
		Comparator<ExtDbRecordFM> prefComp = new Comparator<ExtDbRecordFM>() {
			public int compare(ExtDbRecordFM theRecord, ExtDbRecordFM otherRecord) {
				if (theRecord.isPreferredRecord(otherRecord, prefOp)) {
					return -1;
				}
				return 1;
			}
		};

		Collections.sort(records, prefComp);

		int recCount = records.size() - 1;
		for (int recIndex = 0; recIndex < recCount; recIndex++) {
			theRecord = records.get(recIndex);
			lit = records.listIterator(recIndex + 1);
			while (lit.hasNext()) {
				if (areRecordsMX(theRecord, lit.next(), mxFacilityIDOnly, coChanMX, kmPerDeg)) {
					lit.remove();
					recCount--;
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	private ExtDbRecordFM(ExtDb theExtDb) {

		super(theExtDb, Source.RECORD_TYPE_FM);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update a source record object from this object's properties, this is called by makeSource() in SourceEditData
	// when creating a new source encapsulating this record.

	public boolean updateSource(SourceEditDataFM theSource) {
		return updateSource(theSource, null);
	}

	public boolean updateSource(SourceEditDataFM theSource, ErrorLogger errors) {

		if (!extDb.dbID.equals(theSource.dbID) || (facilityID != theSource.facilityID) ||
				(stationClass != theSource.stationClass) || !service.equals(theSource.service) ||
				!country.equals(theSource.country)) {
			if (null != errors) {
				errors.reportError("ExtDbRecordFM.updateSource(): non-matching source object.");
			}
			return false;
		}

		if (ExtDb.DB_TYPE_CDBS_FM != extDb.type) {
			if (null != errors) {
				errors.reportError("ExtDbRecordFM.updateSource(): unsupported external record type.");
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

		theSource.isIBOC = isIBOC;
		theSource.callSign = callSign;
		theSource.channel = channel;
		theSource.city = city;
		theSource.state = state;
		theSource.status = status;
		theSource.statusType = statusType;
		theSource.fileNumber = filePrefix + appARN;
		theSource.appARN = appARN;
		theSource.location.setLatLon(location);
		theSource.heightAMSL = heightAMSL;
		theSource.overallHAAT = overallHAAT;
		theSource.peakERP = peakERP;

		// If the station is operating IBOC, determine the digital power level.  This is stored in the record as a
		// fraction but will be displayed/edited as a second ERP value.  The digital power defaults to 1%.  But also
		// check the if_notification table for a value which may be up to 10%.  This is not directly linked to the
		// engineering data.  Look up applications with the service FD and match to the engineering record using
		// facility ID.  If there is more than one record, take the most-recent based on ARN.  Then compute a fraction
		// from the analog and digital values in the if_notification table.

		theSource.ibocFraction = SourceFM.IBOC_FRACTION_DEF;

		if (isIBOC) {

			DbConnection db = extDb.connectDb(errors);
			if (null != db) {
				try {

					db.query(
					"SELECT " +
						"if_notification.digital_erp / if_notification.analog_erp " +
					"FROM " +
						"if_notification " +
						"JOIN application USING (application_id) " +
					"WHERE " +
						"application.app_service = 'FD' " +
						"AND application.facility_id = " + facilityID + " " +
					"ORDER BY " +
						"application.app_arn DESC " +
					"LIMIT 1");

					if (db.next()) {
						theSource.ibocFraction = db.getDouble(1);
						if (theSource.ibocFraction < SourceFM.IBOC_FRACTION_MIN) {
							theSource.ibocFraction = SourceFM.IBOC_FRACTION_MIN;
						}
						if (theSource.ibocFraction > SourceFM.IBOC_FRACTION_MAX) {
							theSource.ibocFraction = SourceFM.IBOC_FRACTION_MAX;
						}
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
		}

		// Get horizontal pattern data if needed.  If pattern data does not exist set pattern to omni.  A pattern with
		// too few points or bad data values will also revert to omni, see pattern-load methods in the superclass for
		// details.  If the study parameter says to trust the DA indicator and that is not set don't even look for the
		// pattern just use omni.  If there is no study context, default to ignoring the DA indicator.  Note there are
		// no vertical patterns on these records.

		if ((null != antennaRecordID) && (daIndicated || (null == theSource.study) ||
				!theSource.study.getTrustPatternFlag())) {

			String theName = null, make, model;

			DbConnection db = extDb.connectDb(errors);
			if (null != db) {
				try {

					db.query(
					"SELECT " +
						"ant_make, " +
						"ant_model_num " +
					"FROM " +
						"ant_make " +
					"WHERE " +
						"antenna_id = " + antennaRecordID);

					if (db.next()) {

						make = db.getString(1);
						if (null == make) {
							make = "";
						}
						model = db.getString(2);
						if (null == model) {
							model = "";
						}
						theName = make + "-" + model;
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

		// Save sequence date in source attributes.

		theSource.setAttribute(Source.ATTR_SEQUENCE_DATE, AppCore.formatDate(sequenceDate));

		// Apply defaults and corrections, see discussion in ExtDbRecordTV.  Small difference here, if the ERP is 0 do
		// not assume that is dBk assume the value is missing and set a default of 1 watt; only assume dBk if < 0.

		if (null == theSource.study) {
			theSource.useGenericVerticalPattern = true;
		} else {
			theSource.useGenericVerticalPattern = theSource.study.getUseGenericVpat(theSource.country.key - 1);
		}

		if ((Country.MX == country.key) && (ServiceType.SERVTYPE_FM_FULL == service.serviceType.key) &&
				(null != theSource.study)) {

			if (0. == peakERP) {

				switch (stationClass) {

					case FM_CLASS_A:
					default: {
						theSource.peakERP = theSource.study.getDefaultMexicanFMAERP();
						break;
					}

					case FM_CLASS_B: {
						theSource.peakERP = theSource.study.getDefaultMexicanFMBERP();
						break;
					}

					case FM_CLASS_B1: {
						theSource.peakERP = theSource.study.getDefaultMexicanFMB1ERP();
						break;
					}

					case FM_CLASS_C: {
						theSource.peakERP = theSource.study.getDefaultMexicanFMCERP();
						break;
					}

					case FM_CLASS_C1: {
						theSource.peakERP = theSource.study.getDefaultMexicanFMC1ERP();
						break;
					}
				}

				errors.logMessage(makeMessage(this, "Used default for missing ERP."));
			}

			if ((0. == heightAMSL) && (0. == overallHAAT)) {

				switch (stationClass) {

					case FM_CLASS_A:
					default: {
						theSource.overallHAAT = theSource.study.getDefaultMexicanFMAHAAT();
						break;
					}

					case FM_CLASS_B: {
						theSource.overallHAAT = theSource.study.getDefaultMexicanFMBHAAT();
						break;
					}

					case FM_CLASS_B1: {
						theSource.overallHAAT = theSource.study.getDefaultMexicanFMB1HAAT();
						break;
					}

					case FM_CLASS_C: {
						theSource.overallHAAT = theSource.study.getDefaultMexicanFMCHAAT();
						break;
					}

					case FM_CLASS_C1: {
						theSource.overallHAAT = theSource.study.getDefaultMexicanFMC1HAAT();
						break;
					}
				}

				errors.logMessage(makeMessage(this, "Used default for missing HAAT."));
			}
		}

		if ((Country.US != country.key) && (0. == heightAMSL) && (0. != overallHAAT)) {
			theSource.heightAMSL = Source.HEIGHT_DERIVE;
			errors.logMessage(makeMessage(this, "Derived missing AMSL from HAAT."));
		}

		if (0. == peakERP) {
			theSource.peakERP = Source.ERP_DEF;
			errors.logMessage(makeMessage(this, "Used default for missing ERP."));
		}

		if (peakERP < 0.) {
			theSource.peakERP = Math.pow(10., (peakERP / 10.));
			errors.logMessage(makeMessage(this, "Converted ERP from dBk to kilowatts."));
		}

		// Done.

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Determine if two records (contained in ExtDbRecordFM or SourceEditDataFM objects) are mutually-exclusive.  The
	// primary test is facility ID; any two records with the same facility ID are MX.  Beyond that are back-up checks
	// to detect co-channel MX cases with differing facility IDs.  Those apply only for matching channel and country.
	// Most often those checks are needed to detect MX applications for a new facility, but also to catch cases such as
	// DTV rule-making records that have a facility ID that does not match the actual station.  Optionally the backup
	// tests can be skipped so only facility ID is considered.

	public static boolean areRecordsMX(ExtDbRecordFM a, ExtDbRecordFM b, boolean facIDOnly, double mxDist,
			double kmPerDeg) {
		return areRecordsMX(
			a.facilityID, a.channel, a.country.key, a.state, a.city, a.location,
			b.facilityID, b.channel, b.country.key, b.state, b.city, b.location,
			facIDOnly, mxDist, kmPerDeg);
	}

	public static boolean areRecordsMX(ExtDbRecordFM a, SourceEditDataFM b, boolean facIDOnly, double mxDist,
			double kmPerDeg) {
		return areRecordsMX(
			a.facilityID, a.channel, a.country.key, a.state, a.city, a.location,
			b.facilityID, b.channel, b.country.key, b.state, b.city, b.location,
			facIDOnly, mxDist, kmPerDeg);
	}

	public static boolean areRecordsMX(SourceEditDataFM a, SourceEditDataFM b, boolean facIDOnly, double mxDist,
			double kmPerDeg) {
		return areRecordsMX(
			a.facilityID, a.channel, a.country.key, a.state, a.city, a.location,
			b.facilityID, b.channel, b.country.key, b.state, b.city, b.location,
			facIDOnly, mxDist, kmPerDeg);
	}

	private static boolean areRecordsMX(int a_facilityID, int a_channel, int a_country_key, String a_state,
			String a_city, GeoPoint a_location, int b_facilityID, int b_channel, int b_country_key, String b_state,
			String b_city, GeoPoint b_location, boolean facIDOnly, double mxDist, double kmPerDeg) {

		if (a_facilityID == b_facilityID) {
			return true;
		}

		if (facIDOnly) {
			return false;
		}

		if (a_channel != b_channel) {
			return false;
		}

		if (a_country_key != b_country_key) {
			return false;
		}

		if (a_state.equalsIgnoreCase(b_state) && a_city.equalsIgnoreCase(b_city)) {
			return true;
		}

		if ((mxDist > 0.) && (a_location.distanceTo(b_location, kmPerDeg) < mxDist)) {
			return true;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check another record and determine if this record is preferred over the other, presumably mutually-exclusive.
	// Returns true if this record is preferred, false otherwise.

	public boolean isPreferredRecord(ExtDbRecordFM otherRecord, boolean preferOperating) {

		// The first test is to prefer an "operating facility" record, see isOperating() for details.  This test can
		// be disabled by argument.

		if (preferOperating) {
			if (isOperating()) {
				if (!otherRecord.isOperating()) {
					return true;
				}
			} else {
				if (otherRecord.isOperating()) {
					return false;
				}
			}
		}

		// Next test the ranking of the record service, in case services are not the same.

		if (service.preferenceRank > otherRecord.service.preferenceRank) {
			return true;
		}
		if (service.preferenceRank < otherRecord.service.preferenceRank) {
			return false;
		}

		// Same operating status, same service ranking, check the record status and prefer according to the order
		// reflected by the STATUS_TYPE_* constants.

		if (statusType < otherRecord.statusType) {
			return true;
		}
		if (statusType > otherRecord.statusType) {
			return false;
		}

		// Prefer the record with the more-recent sequence date.

		if (sequenceDate.after(otherRecord.sequenceDate)) {
			return true;
		}
		if (sequenceDate.before(otherRecord.sequenceDate)) {
			return false;
		}

		// This must return a consistent non-equal result for sorting use (A.isPreferred(B) != B.isPreferred(A)), so if
		// all else was equal compare record IDs.  The ID sequence is arbitrary but since they are unique this is
		// guaranteed to never see the records as equal.

		if (extRecordID.compareTo(otherRecord.extRecordID) > 0) {
			return true;
		}
		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Determine if this record represents an actual operating facility.  The test result is cached.

	public boolean isOperating() {

		if (operatingStatusSet) {
			return isOperatingFacility;
		}

		// If the record service is non-operating (allotments, rule-makings, etc.), the record cannot be operating.
		// Archived records are never operating.  Status codes LIC and STA are always operating.  CP and OTHER are
		// operating for non-U.S., not operating for U.S.  OTHER is operating for non-U.S. but not for U.S.  EXP or
		// unknown are never operating.

		if (!service.isOperating || isArchived) {
			isOperatingFacility = false;
		} else {
			switch (statusType) {
				case STATUS_TYPE_LIC:
				case STATUS_TYPE_STA: {
					isOperatingFacility = true;
					break;
				}
				case STATUS_TYPE_CP:
				case STATUS_TYPE_OTHER: {
					if (Country.US == country.key) {
						isOperatingFacility = false;
					} else {
						isOperatingFacility = true;
					}
					break;
				}
				case STATUS_TYPE_EXP:
				default: {
					isOperatingFacility = false;
					break;
				}
			}
		}

		operatingStatusSet = true;

		return isOperatingFacility;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get the rule extra distance, see details in SourceEditDataFM.

	public double getRuleExtraDistance(StudyEditData study) {

		return SourceEditDataFM.getRuleExtraDistance(study, service, stationClass, country, channel, peakERP);
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

	public String getCallSign() {

		return callSign;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getChannel() {

		return String.valueOf(channel) + FM_CLASS_CODES[stationClass];
	}

	public String getSortChannel() {

		return String.format(Locale.US, "%03d%02d", channel, stationClass);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getFrequency() {

		return String.format(Locale.US, "%.1f MHz", 87.9 + ((double)(channel - 200) * 0.2));
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getStatus() {

		return status + (isArchived ? " *A" : "");
	}

	public String getSortStatus() {

		return String.valueOf(statusType);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getFileNumber() {

		return filePrefix + appARN;
	}

	public String getARN() {

		return appARN;
	}
}
