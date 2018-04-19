//
//  ExtDbRecord.java
//  TVStudy
//
//  Copyright (c) 2012-2018 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core;

import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.editdata.*;
import gov.fcc.tvstudy.core.geo.*;

import java.util.*;
import java.text.*;
import java.sql.*;


//=====================================================================================================================
// Data class to hold a record from external station data.  This is an abstract superclass, concrete subclasses exist
// for different record types.  This is not a database record representation class, nor is it part of an editor model.
// It's role is to encapsulate the SQL code and translation logic needed to create SourceEditData objects from external
// station data, using databases created by the ExtDb class methods.  However the subclasses here are not one-to-one
// with ExtDb data types, they follow the Source and SourceEditData record type subclasses, so a subclass here may draw
// data from several different station data types.  Subclasses implement two main methods, static method findRecords()
// is used for search queries, and an instance method updateSource??() is used to apply data to a source object.  Both
// are typed specifically to the subclass so are not declared here.  As with the related class sets like Source, the
// properties and code related to antenna patterns is here in the superclass.

public abstract class ExtDbRecord implements Record {

	// Status code enumeration used by some subclasses, here for convenience.

	public static final int STATUS_TYPE_STA = 0;
	public static final int STATUS_TYPE_CP = 1;
	public static final int STATUS_TYPE_LIC = 2;
	public static final int STATUS_TYPE_APP = 3;
	public static final int STATUS_TYPE_OTHER = 4;
	public static final int STATUS_TYPE_EXP = 5;
	public static final int STATUS_TYPE_AMD = 6;

	public static final String[] STATUS_CODES = {
		"STA", "CP", "LIC", "APP", "", "EXP", "AMD"
	};

	// Options for query composing methods that match logical flags, tested flag may be set, clear, or any.

	public static final int FLAG_MATCH_SET = 1;
	public static final int FLAG_MATCH_CLEAR = 2;
	public static final int FLAG_MATCH_ANY = 3;

	// Instance properties common to all record types.

	public final ExtDb extDb;
	public final int recordType;

	public String extRecordID;

	public Service service;
	public String city;
	public String state;
	public Country country;
	public final GeoPoint location;
	public double heightAMSL;
	public double overallHAAT;
	public double peakERP;
	public String antennaID;
	public String antennaRecordID;
	public double horizontalPatternOrientation;
	public String elevationAntennaRecordID;
	public double verticalPatternElectricalTilt;
	public double verticalPatternMechanicalTilt;
	public double verticalPatternMechanicalTiltOrientation;
	public boolean isPending;
	public boolean isArchived;
	public java.util.Date sequenceDate;

	// A message string used many times.

	public static final String BAD_TYPE_MESSAGE = "Unknown or unsupported station data type.";


	//-----------------------------------------------------------------------------------------------------------------
	// Get list of KeyedRecord objects representing status codes.

	private static ArrayList<KeyedRecord> statusList = null;

	public static ArrayList<KeyedRecord> getStatusList() {

		if (null == statusList) {
			statusList = new ArrayList<KeyedRecord>();
			statusList.add(new KeyedRecord(STATUS_TYPE_LIC, STATUS_CODES[STATUS_TYPE_LIC]));
			statusList.add(new KeyedRecord(STATUS_TYPE_CP, STATUS_CODES[STATUS_TYPE_CP]));
			statusList.add(new KeyedRecord(STATUS_TYPE_APP, STATUS_CODES[STATUS_TYPE_APP]));
			statusList.add(new KeyedRecord(STATUS_TYPE_STA, STATUS_CODES[STATUS_TYPE_STA]));
			statusList.add(new KeyedRecord(STATUS_TYPE_EXP, STATUS_CODES[STATUS_TYPE_EXP]));
		}

		return statusList;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Main search method to retrieve records, branches to subclass implementation based on the record type of the
	// station data set.  The query string is the WHERE clause only, usually composed with the add*Query() methods
	// below, also a center point and radius may be provided to limit the search, a radius of 0 means unlimited.
	// Return is null on error, an empty array if no match.  Returns a LinkedList because that is better for post-
	// processing large search results.  Note generic import data sets are not handled here because those data sets
	// do not vend objects of an ExtDbRecord subclass; see SourceEditData.findImportRecords().

	public static LinkedList<ExtDbRecord> findRecords(String theDbID, Integer extDbKey, String query) {
		return findRecords(theDbID, extDbKey, query, null, 0., 0., null);
	}

	public static LinkedList<ExtDbRecord> findRecords(String theDbID, Integer extDbKey, String query,
			ErrorLogger errors) {
		return findRecords(theDbID, extDbKey, query, null, 0., 0., errors);
	}

	public static LinkedList<ExtDbRecord> findRecords(String theDbID, Integer extDbKey, String query,
			GeoPoint searchCenter, double searchRadius, double kmPerDegree) {
		return findRecords(theDbID, extDbKey, query, searchCenter, searchRadius, kmPerDegree, null);
	}

	public static LinkedList<ExtDbRecord> findRecords(String theDbID, Integer extDbKey, String query,
			GeoPoint searchCenter, double searchRadius, double kmPerDegree, ErrorLogger errors) {
		ExtDb theExtDb = ExtDb.getExtDb(theDbID, extDbKey, errors);
		if (null == theExtDb) {
			return null;
		}
		return findRecords(theExtDb, query, searchCenter, searchRadius, kmPerDegree, errors);
	}

	public static LinkedList<ExtDbRecord> findRecords(ExtDb theExtDb, String query) {
		return findRecords(theExtDb, query, null, 0., 0., null);
	}

	public static LinkedList<ExtDbRecord> findRecords(ExtDb theExtDb, String query, ErrorLogger errors) {
		return findRecords(theExtDb, query, null, 0., 0., errors);
	}

	public static LinkedList<ExtDbRecord> findRecords(ExtDb theExtDb, String query, GeoPoint searchCenter,
			double searchRadius, double kmPerDegree) {
		return findRecords(theExtDb, query, searchCenter, searchRadius, kmPerDegree, null);
	}

	public static LinkedList<ExtDbRecord> findRecords(ExtDb theExtDb, String query, GeoPoint searchCenter,
			double searchRadius, double kmPerDegree, ErrorLogger errors) {

		switch (theExtDb.type) {

			case ExtDb.DB_TYPE_CDBS:
			case ExtDb.DB_TYPE_LMS:
			case ExtDb.DB_TYPE_LMS_LIVE: {
				return ExtDbRecordTV.findRecordsImpl(theExtDb, query, searchCenter, searchRadius, kmPerDegree, errors);
			}

			case ExtDb.DB_TYPE_WIRELESS: {
				return ExtDbRecordWL.findRecordsImpl(theExtDb, query, searchCenter, searchRadius, kmPerDegree, errors);
			}

			case ExtDb.DB_TYPE_CDBS_FM: {
				return ExtDbRecordFM.findRecordsImpl(theExtDb, query, searchCenter, searchRadius, kmPerDegree, errors);
			}
		}

		if (null != errors) {
			errors.reportError(BAD_TYPE_MESSAGE);
		}
		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get an ExtDbRecord subclass instance for an external data set record using the record ID.

	public static ExtDbRecord findRecord(String theDbID, Integer extDbKey, String theExtRecordID) {
		return findRecord(theDbID, extDbKey, theExtRecordID, null);
	}

	public static ExtDbRecord findRecord(String theDbID, Integer extDbKey, String theExtRecordID, ErrorLogger errors) {
		ExtDb theExtDb = ExtDb.getExtDb(theDbID, extDbKey, errors);
		if (null == theExtDb) {
			return null;
		}
		return findRecord(theExtDb, theExtRecordID, errors);
	}

	public static ExtDbRecord findRecord(ExtDb theExtDb, String theExtRecordID) {
		return findRecord(theExtDb, theExtRecordID, null);
	}

	public static ExtDbRecord findRecord(ExtDb theExtDb, String theExtRecordID, ErrorLogger errors) {

		switch (theExtDb.recordType) {
			case Source.RECORD_TYPE_TV: {
				return ExtDbRecordTV.findRecordTV(theExtDb, theExtRecordID, errors);
			}
			case Source.RECORD_TYPE_WL: {
				return ExtDbRecordWL.findRecordWL(theExtDb, theExtRecordID, errors);
			}
			case Source.RECORD_TYPE_FM: {
				return ExtDbRecordFM.findRecordFM(theExtDb, theExtRecordID, errors);
			}
		}

		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Do a fast check to determine if a given record ID exists in a data set.  Returns false on errors.

	public static boolean doesRecordIDExist(String theDbID, Integer theExtDbKey, String theExtRecordID) {

		ExtDb theExtDb = ExtDb.getExtDb(theDbID, theExtDbKey);
		if (null == theExtDb) {
			return false;
		}

		return doesRecordIDExist(theExtDb, theExtRecordID);
	}

	public static boolean doesRecordIDExist(ExtDb theExtDb, String theExtRecordID) {

		switch (theExtDb.recordType) {
			case Source.RECORD_TYPE_TV: {
				return ExtDbRecordTV.doesRecordIDExistTV(theExtDb, theExtRecordID);
			}
			case Source.RECORD_TYPE_WL: {
				return ExtDbRecordWL.doesRecordIDExistWL(theExtDb, theExtRecordID);
			}
			case Source.RECORD_TYPE_FM: {
				return ExtDbRecordFM.doesRecordIDExistFM(theExtDb, theExtRecordID);
			}
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Methods to support composing query clauses for findRecords() searching, subclasses provide data-specific SQL
	// code in methods with TV/WL/FM name suffixes.  These superclass implementations will branch out to subclasses by
	// data set type.  In general the search arguments to these are assumed to be user input, thus validation or other
	// pre-processing is often appropriate.  An IllegalArgumentException is thrown if validation fails or an unknown
	// data set type is passed.  If the input string is null or empty the methods do nothing.  If a clause will be
	// added and the combine argument is true the new clause is preceded with " AND ".  The return is true if a clause
	// is added.  See RecordFind for typical use.

	public static boolean addRecordIDQuery(int dbType, int version, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		switch (dbType) {

			case ExtDb.DB_TYPE_CDBS:
			case ExtDb.DB_TYPE_LMS:
			case ExtDb.DB_TYPE_LMS_LIVE: {
				return ExtDbRecordTV.addRecordIDQueryTV(dbType, version, str, query, combine);
			}

			case ExtDb.DB_TYPE_WIRELESS: {
				return ExtDbRecordWL.addRecordIDQueryWL(dbType, version, str, query, combine);
			}

			case ExtDb.DB_TYPE_CDBS_FM: {
				return ExtDbRecordFM.addRecordIDQueryFM(dbType, version, str, query, combine);
			}
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Search by file number.  A file number conceptually consists of a file prefix and an ARN (application reference
	// number).  The prefix is alphabetic, the ARN suffix is alphanumeric but usually begins with a numerical digit,
	// and the two may or may not be separated by a '-'.  Also a prefix string of "BLANK" may be used when there is no
	// prefix.  The ARN itself is sufficient to be unique in most contexts so the prefix is always optional.

	public static boolean addFileNumberQuery(int dbType, int version, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if ((null == str) || (0 == str.length())) {
			return false;
		}

		String[] parts = parseFileNumber(str);

		return addFileNumberQuery(dbType, version, parts[0], parts[1], query, combine);
	}

	public static String[] parseFileNumber(String str) {

		String[] parts = new String[2];
		parts[1] = "";

		char cc;
		StringBuilder pre = new StringBuilder();

		for (int i = 0; i < str.length(); i++) {
			cc = str.charAt(i);
			if (Character.isDigit(cc)) {
				parts[1] = DbConnection.clean(str.substring(i).toUpperCase());
				break;
			}
			if ('-' == cc) {
				parts[1] = DbConnection.clean(str.substring(i + 1).toUpperCase());
				break;
			}
			pre.append(cc);
		}

		parts[0] = DbConnection.clean(pre.toString().toUpperCase());
		if (parts[0].equals("BLANK")) {
			parts[0] = "";
		}

		return parts;
	}

	public static boolean addFileNumberQuery(int dbType, int version, String prefix, String arn, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		switch (dbType) {

			case ExtDb.DB_TYPE_CDBS:
			case ExtDb.DB_TYPE_LMS:
			case ExtDb.DB_TYPE_LMS_LIVE: {
				return ExtDbRecordTV.addFileNumberQueryTV(dbType, version, prefix, arn, query, combine);
			}

			case ExtDb.DB_TYPE_WIRELESS: {
				return ExtDbRecordWL.addFileNumberQueryWL(dbType, version, prefix, arn, query, combine);
			}

			case ExtDb.DB_TYPE_CDBS_FM: {
				return ExtDbRecordFM.addFileNumberQueryFM(dbType, version, prefix, arn, query, combine);
			}
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query clause to search by facility ID.  The first version converts string input, second takes a numerical
	// facility ID argument.

	public static boolean addFacilityIDQuery(int dbType, int version, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if ((null == str) || (0 == str.length())) {
			return false;
		}

		int facilityID = 0;
		try {
			facilityID = Integer.parseInt(str);
		} catch (NumberFormatException ne) {
			throw new IllegalArgumentException("The facility ID must be a number.");
		}

		return addFacilityIDQuery(dbType, version, facilityID, query, combine);
	}

	public static boolean addFacilityIDQuery(int dbType, int version, int facilityID, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		switch (dbType) {

			case ExtDb.DB_TYPE_CDBS:
			case ExtDb.DB_TYPE_LMS:
			case ExtDb.DB_TYPE_LMS_LIVE: {
				return ExtDbRecordTV.addFacilityIDQueryTV(dbType, version, facilityID, query, combine);
			}

			case ExtDb.DB_TYPE_CDBS_FM: {
				return ExtDbRecordFM.addFacilityIDQueryFM(dbType, version, facilityID, query, combine);
			}
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query clause to search by service.  The string argument is a two-letter service code that must resolve to
	// a Service object, the second version takes an integer service key.

	public static boolean addServiceQuery(int dbType, int version, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if ((null == str) || (0 == str.length())) {
			return false;
		}

		Service theService = Service.getService(str);
		if (null == theService) {
			throw new IllegalArgumentException("Unknown service code.");
		}

		return addServiceQuery(dbType, version, theService.key, query, combine);
	}

	public static boolean addServiceQuery(int dbType, int version, int serviceKey, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		switch (dbType) {

			case ExtDb.DB_TYPE_CDBS:
			case ExtDb.DB_TYPE_LMS:
			case ExtDb.DB_TYPE_LMS_LIVE: {
				return ExtDbRecordTV.addServiceQueryTV(dbType, version, serviceKey, query, combine);
			}

			case ExtDb.DB_TYPE_CDBS_FM: {
				return ExtDbRecordFM.addServiceQueryFM(dbType, version, serviceKey, query, combine);
			}
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query search clause for call sign, case-insensitive head-anchored match.

	public static boolean addCallSignQuery(int dbType, int version, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		switch (dbType) {

			case ExtDb.DB_TYPE_CDBS:
			case ExtDb.DB_TYPE_LMS:
			case ExtDb.DB_TYPE_LMS_LIVE: {
				return ExtDbRecordTV.addCallSignQueryTV(dbType, version, str, query, combine);
			}

			case ExtDb.DB_TYPE_WIRELESS: {
				return ExtDbRecordWL.addCallSignQueryWL(dbType, version, str, query, combine);
			}

			case ExtDb.DB_TYPE_CDBS_FM: {
				return ExtDbRecordFM.addCallSignQueryFM(dbType, version, str, query, combine);
			}
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query search clause for a specific channel search.  If the caller provides a channel range the channel
	// must be in that range, otherwise anything is allowed.  The range arguments are assumed to be valid if >0.

	public static boolean addChannelQuery(int dbType, int version, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {
		return addChannelQuery(dbType, version, str, 0, 0, query, combine);
	}

	public static boolean addChannelQuery(int dbType, int version, String str, int minimumChannel, int maximumChannel,
			StringBuilder query, boolean combine) throws IllegalArgumentException {

		if ((null == str) || (0 == str.length())) {
			return false;
		}

		int channel = 0;
		try {
			channel = Integer.parseInt(str);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("The channel must be a number.");
		}

		return addChannelQuery(dbType, version, channel, minimumChannel, maximumChannel, query, combine);
	}

	public static boolean addChannelQuery(int dbType, int version, int channel, StringBuilder query, boolean combine)
			throws IllegalArgumentException {
		return addChannelQuery(dbType, version, channel, 0, 0, query, combine);
	}

	public static boolean addChannelQuery(int dbType, int version, int channel, int minimumChannel, int maximumChannel,
			StringBuilder query, boolean combine) throws IllegalArgumentException {

		switch (dbType) {

			case ExtDb.DB_TYPE_CDBS:
			case ExtDb.DB_TYPE_LMS:
			case ExtDb.DB_TYPE_LMS_LIVE: {
				return ExtDbRecordTV.addChannelQueryTV(dbType, version, channel, minimumChannel, maximumChannel, query,
					combine);
			}

			case ExtDb.DB_TYPE_CDBS_FM: {
				return ExtDbRecordFM.addChannelQueryFM(dbType, version, channel, minimumChannel, maximumChannel, query,
					combine);
			}
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query search clause for a channel range search.  The range arguments are assumed valid.

	public static boolean addChannelRangeQuery(int dbType, int version, int minimumChannel, int maximumChannel,
			StringBuilder query, boolean combine) throws IllegalArgumentException {

		switch (dbType) {

			case ExtDb.DB_TYPE_CDBS:
			case ExtDb.DB_TYPE_LMS:
			case ExtDb.DB_TYPE_LMS_LIVE: {
				return ExtDbRecordTV.addChannelRangeQueryTV(dbType, version, minimumChannel, maximumChannel, query,
					combine);
			}

			case ExtDb.DB_TYPE_CDBS_FM: {
				return ExtDbRecordFM.addChannelRangeQueryFM(dbType, version, minimumChannel, maximumChannel, query,
					combine);
			}
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query search clause for matching a list of channels.  This is not meant to process direct user input, the
	// argument string is assumed to be a valid SQL value list composed by other code e.g. "(7,8,12,33)", containing
	// valid channel numbers.

	public static boolean addMultipleChannelQuery(int dbType, int version, String str, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		switch (dbType) {

			case ExtDb.DB_TYPE_CDBS:
			case ExtDb.DB_TYPE_LMS:
			case ExtDb.DB_TYPE_LMS_LIVE: {
				return ExtDbRecordTV.addMultipleChannelQueryTV(dbType, version, str, query, combine);
			}

			case ExtDb.DB_TYPE_CDBS_FM: {
				return ExtDbRecordFM.addMultipleChannelQueryFM(dbType, version, str, query, combine);
			}
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query search clause for status.  The string argument is treated as a source record status code matched to
	// just the internal status type list, see getStatusType() below.  Second form takes a status type directly.

	public static boolean addStatusQuery(int dbType, int version, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if ((null == str) || (0 == str.length())) {
			return false;
		}

		return addStatusQuery(dbType, version, getStatusType(str), query, combine);
	}

	public static boolean addStatusQuery(int dbType, int version, int statusType, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		switch (dbType) {

			case ExtDb.DB_TYPE_CDBS:
			case ExtDb.DB_TYPE_LMS:
			case ExtDb.DB_TYPE_LMS_LIVE: {
				return ExtDbRecordTV.addStatusQueryTV(dbType, version, statusType, query, combine);
			}

			case ExtDb.DB_TYPE_CDBS_FM: {
				return ExtDbRecordFM.addStatusQueryFM(dbType, version, statusType, query, combine);
			}
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add query clause for city name search, case-insensitive unanchored match, '*' wildcards are allowed.

	public static boolean addCityQuery(int dbType, int version, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		switch (dbType) {

			case ExtDb.DB_TYPE_CDBS:
			case ExtDb.DB_TYPE_LMS:
			case ExtDb.DB_TYPE_LMS_LIVE: {
				return ExtDbRecordTV.addCityQueryTV(dbType, version, str, query, combine);
			}

			case ExtDb.DB_TYPE_WIRELESS: {
				return ExtDbRecordWL.addCityQueryWL(dbType, version, str, query, combine);
			}

			case ExtDb.DB_TYPE_CDBS_FM: {
				return ExtDbRecordFM.addCityQueryFM(dbType, version, str, query, combine);
			}
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add query clause for state code search, this is an exact string match but still case-insensitive.

	public static boolean addStateQuery(int dbType, int version, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		switch (dbType) {

			case ExtDb.DB_TYPE_CDBS:
			case ExtDb.DB_TYPE_LMS:
			case ExtDb.DB_TYPE_LMS_LIVE: {
				return ExtDbRecordTV.addStateQueryTV(dbType, version, str, query, combine);
			}

			case ExtDb.DB_TYPE_WIRELESS: {
				return ExtDbRecordWL.addStateQueryWL(dbType, version, str, query, combine);
			}

			case ExtDb.DB_TYPE_CDBS_FM: {
				return ExtDbRecordFM.addStateQueryFM(dbType, version, str, query, combine);
			}
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add clause for country search, this is resolved to a Country object by string code or key.

	public static boolean addCountryQuery(int dbType, int version, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		Country country = Country.getCountry(str);
		if (null == country) {
			throw new IllegalArgumentException("Unknown country code.");
		}

		return addCountryQuery(dbType, version, country, query, combine);
	}

	public static boolean addCountryQuery(int dbType, int version, int countryKey, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		Country country = Country.getCountry(countryKey);
		if (null == country) {
			throw new IllegalArgumentException("Unknown country key.");
		}

		return addCountryQuery(dbType, version, country, query, combine);
	}

	public static boolean addCountryQuery(int dbType, int version, Country country, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		switch (dbType) {

			case ExtDb.DB_TYPE_CDBS:
			case ExtDb.DB_TYPE_LMS:
			case ExtDb.DB_TYPE_LMS_LIVE: {
				return ExtDbRecordTV.addCountryQueryTV(dbType, version, country, query, combine);
			}

			case ExtDb.DB_TYPE_WIRELESS: {
				return ExtDbRecordWL.addCountryQueryWL(dbType, version, country, query, combine);
			}

			case ExtDb.DB_TYPE_CDBS_FM: {
				return ExtDbRecordFM.addCountryQueryFM(dbType, version, country, query, combine);
			}
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add record type search query clause.

	public static boolean addRecordTypeQuery(int dbType, int version, boolean includeArchived, StringBuilder query,
			boolean combine) {

		switch (dbType) {

			case ExtDb.DB_TYPE_CDBS:
			case ExtDb.DB_TYPE_LMS:
			case ExtDb.DB_TYPE_LMS_LIVE: {
				return ExtDbRecordTV.addRecordTypeQueryTV(dbType, version, includeArchived, query, combine);
			}

			case ExtDb.DB_TYPE_CDBS_FM: {
				return ExtDbRecordFM.addRecordTypeQueryFM(dbType, version, includeArchived, query, combine);
			}

			case ExtDb.DB_TYPE_WIRELESS: {
				return false;
			}
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Translate status string to type code.  This is the generic case for general use and the user record table.

	public static int getStatusType(String theStatus) {

		for (int i = 0; i < STATUS_CODES.length; i++) {
			if ((STATUS_TYPE_OTHER != i) && STATUS_CODES[i].equalsIgnoreCase(theStatus)) {
				return i;
			}
		}

		return STATUS_TYPE_OTHER;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The constructor is private, instances are obtained from findRecords() search method.

	protected ExtDbRecord(ExtDb theExtDb, int theRecordType) {

		super();

		extDb = theExtDb;
		recordType = theRecordType;

		location = new GeoPoint();

		sequenceDate = new java.util.Date();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String toString() {

		return getCallSign() + " " + getChannel() + " " + getServiceCode() + " " + getStatus() + " " +
			getCity() + ", " + getState();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Retrieve an antenna pattern.  Returns null if the pattern is not found or has any bad data, the checks include
	// a minimum number of points, azimuths and relative fields in range, and no duplicate points.  Values are also
	// rounded, see PatternEditor.  If values less than zero are seen they are assumed to be relative dB and are
	// converted to relative field.  Relative field values less than a minimum are set to the minimum.  A not-found
	// condition does not cause an error message; the antenna_id may not have any data if it is there just to identify
	// the make and model of the antenna.  An empty array is returned in that case.  If the record argument is non-null
	// it changes error handling, in that case some failures are logged as messages using identifying information from
	// the record, otherwise those failures are reported as errors. 

	public static ArrayList<AntPattern.AntPoint> getAntennaPattern(String theDbID, Integer extDbKey,
			String theAntRecordID) {
		return getAntennaPattern(theDbID, extDbKey, theAntRecordID, null);
	}

	public static ArrayList<AntPattern.AntPoint> getAntennaPattern(String theDbID, Integer extDbKey,
			String theAntRecordID, ErrorLogger errors) {
		ExtDb theExtDb = ExtDb.getExtDb(theDbID, extDbKey, errors);
		if (null == theExtDb) {
			return null;
		}
		return getAntennaPattern(theExtDb, theAntRecordID, errors, null);
	}

	protected static ArrayList<AntPattern.AntPoint> getAntennaPattern(ExtDbRecord theRecord, ErrorLogger errors) {
		return getAntennaPattern(theRecord.extDb, theRecord.antennaRecordID, errors, theRecord);
	}

	private static ArrayList<AntPattern.AntPoint> getAntennaPattern(ExtDb theExtDb, String theAntRecordID,
			ErrorLogger errors, ExtDbRecord theRecord) {

		if ((null == theAntRecordID) || (0 == theAntRecordID.length())) {
			return null;
		}

		String query = "";

		switch (theExtDb.type) {

			case ExtDb.DB_TYPE_CDBS:
			case ExtDb.DB_TYPE_CDBS_FM: {

				query = 
				"SELECT " +
					"azimuth, " +
					"field_value " +
				"FROM " +
					"ant_pattern " +
				"WHERE " +
					"antenna_id = " + theAntRecordID + " " +
				"ORDER BY 1";

				break;
			}

			// LMS antenna ID is defined in the lkp_antenna table for baseline records, except for DTS records which
			// are actually normal records pulled in by reference from a baseline entry.

			case ExtDb.DB_TYPE_LMS: {

				if ((null != theRecord) && (Source.RECORD_TYPE_TV == theRecord.recordType) &&
						((ExtDbRecordTV)theRecord).isBaseline && !((ExtDbRecordTV)theRecord).service.isDTS) {

					query =
					"SELECT " +
						"lkp_antenna_field_value.rafv_azimuth, " +
						"lkp_antenna_field_value.rafv_field_value " +
					"FROM " +
						"lkp_antenna " +
						"JOIN lkp_antenna_field_value ON (lkp_antenna_field_value.rafv_antenna_record_id = " +
							"lkp_antenna.rant_antenna_record_id) " +
					"WHERE " +
						"lkp_antenna.rant_antenna_id = '" + theAntRecordID + "' " +
					"ORDER BY 1";

				} else {

					query =
					"SELECT " +
						"aafv_azimuth, " +
						"aafv_field_value " +
					"FROM " +
						"app_antenna_field_value " +
					"WHERE " +
						"aafv_aant_antenna_record_id = '" + theAntRecordID + "' " +
					"ORDER BY 1";
				}

				break;
			}

			case ExtDb.DB_TYPE_LMS_LIVE: {

				if ((null != theRecord) && (Source.RECORD_TYPE_TV == theRecord.recordType) &&
						((ExtDbRecordTV)theRecord).isBaseline && !((ExtDbRecordTV)theRecord).service.isDTS) {

					query =
					"SELECT " +
						"(CASE WHEN lkp_antenna_field_value.rafv_azimuth IN ('','null') THEN 0::FLOAT " +
							"ELSE lkp_antenna_field_value.rafv_azimuth::FLOAT END), " +
						"(CASE WHEN lkp_antenna_field_value.rafv_field_value IN ('','null') THEN 0::FLOAT " +
							" ELSE lkp_antenna_field_value.rafv_field_value::FLOAT END) " +
					"FROM " +
						"mass_media.lkp_antenna " +
						"JOIN mass_media.lkp_antenna_field_value ON " +
							"(lkp_antenna_field_value.rafv_antenna_record_id = " +
								"lkp_antenna.rant_antenna_record_id) " +
					"WHERE " +
						"lkp_antenna.rant_antenna_id = '" + theAntRecordID + "' " +
					"ORDER BY 1";

				} else {

					query =
					"SELECT " +
						"(CASE WHEN aafv_azimuth IN ('','null') THEN 0::FLOAT ELSE aafv_azimuth::FLOAT END), " +
						"(CASE WHEN aafv_field_value IN ('','null') THEN 0::FLOAT ELSE aafv_field_value::FLOAT END) " +
					"FROM " +
						"mass_media.app_antenna_field_value " +
					"WHERE " +
						"aafv_aant_antenna_record_id = '" + theAntRecordID + "' " +
					"ORDER BY 1";
				}

				break;
			}

			case ExtDb.DB_TYPE_WIRELESS: {

				query =
				"SELECT " +
					"degree, " +
					"relative_field " +
				"FROM " +
					ExtDb.WIRELESS_PATTERN_TABLE + " " +
				"WHERE " +
					"ant_id = " + theAntRecordID + " " +
				"ORDER BY 1";

				break;
			}

			case ExtDb.DB_TYPE_GENERIC_TV:
			case ExtDb.DB_TYPE_GENERIC_WL:
			case ExtDb.DB_TYPE_GENERIC_FM: {

				query = 
				"SELECT " +
					"azimuth, " +
					"relative_field " +
				"FROM " +
					"source_horizontal_pattern " +
				"WHERE " +
					"source_key = " + theAntRecordID + " " +
				"ORDER BY 1";

				break;
			}

			default: {
				return null;
			}
		}

		ArrayList<AntPattern.AntPoint> result = null;

		boolean badData = false, showDbWarning = false;
		String errmsg = null;

		DbConnection db = theExtDb.connectDb(errors);
		if (null != db) {
			try {

				db.query(query);

				result = new ArrayList<AntPattern.AntPoint>();
				double az, pat, lastAz = AntPattern.AZIMUTH_MIN - 1., patMax = AntPattern.FIELD_MIN;

				while (db.next()) {

					az = Math.rint(db.getDouble(1) * AntPattern.AZIMUTH_ROUND) / AntPattern.AZIMUTH_ROUND;
					if ((az < AntPattern.AZIMUTH_MIN) || (az > AntPattern.AZIMUTH_MAX)) {
						badData = true;
						errmsg = "azimuth out of range";
						break;
					}
					if (az <= lastAz) {
						badData = true;
						errmsg = "duplicate azimuths";
						break;
					}
					lastAz = az;

					pat = Math.rint(db.getDouble(2) * AntPattern.FIELD_ROUND) / AntPattern.FIELD_ROUND;
					if (pat < 0.) {
						pat = Math.pow(10., (-pat / 20.));
						showDbWarning = true;
					}
					if (pat < AntPattern.FIELD_MIN) {
						pat = AntPattern.FIELD_MIN;
					}
					if (pat > AntPattern.FIELD_MAX) {
						badData = true;
						errmsg = "relative field out of range";
						break;
					}
					if (pat > patMax) {
						patMax = pat;
					}

					result.add(new AntPattern.AntPoint(az, pat));
				}

				// An empty result is not a failure; it just means the antenna is omni-directional.  The empty array
				// will be returned.

				if (!result.isEmpty()) {

					if (!badData && (result.size() < AntPattern.PATTERN_REQUIRED_POINTS)) {
						badData = true;
						errmsg = "not enough points";
					}

					// If the pattern maximum is too low a value don't use the data, otherwise if it is not 1.0 (or
					// very close) just log a warning message but still use the pattern.

					if (!badData && (patMax < 0.5)) {
						badData = true;
						errmsg = "max value is too small";
					}

					if (!badData && (null != errors) && showDbWarning) {
						String msg = "Pattern for antenna record ID " + theAntRecordID +
							" has negative values, assumed to be dB.";
						if (null != theRecord) {
							msg = makeMessage(theRecord, msg);
						}
						errors.logMessage(msg);
					}

					if (!badData && (null != errors) && (patMax < AntPattern.FIELD_MAX_CHECK)) {
						String msg = "Pattern for antenna record ID " + theAntRecordID + " does not have a 1.";
						if (null != theRecord) {
							msg = makeMessage(theRecord, msg);
						}
						errors.logMessage(msg);
					}
				}

				theExtDb.releaseDb(db);

			} catch (SQLException se) {
				theExtDb.releaseDb(db);
				result = null;
				DbConnection.reportError(errors, se);
			}
		}

		if (badData) {
			if (null != errors) {
				String msg = "Pattern for antenna record ID " + theAntRecordID + " is bad, " + errmsg + ".";
				if (null != theRecord) {
					errors.logMessage(makeMessage(theRecord, msg));
				} else {
					errors.reportError(msg);
				}
			}
			result = null;
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Retrieve a vertical pattern.  Bad data checks similar to horizontal above.  This is also used during load of a
	// matrix pattern from CDBS to retrieve the 10-degree-increment columns that are in the main pattern table, that is
	// determined by fieldSuffix, an empty string gets the normal elevation pattern, "0", "10", ... will get the matrix
	// pattern columns.  That argument is ignored for LMS; earlier checks will have already determined that the LMS
	// elevation pattern table does not contain a matrix pattern else this would not be called.  Also similar error-
	// handling as for horizontal above.

	public static ArrayList<AntPattern.AntPoint> getElevationPattern(String theDbID, Integer extDbKey,
			String theAntRecordID) {
		return getElevationPattern(theDbID, extDbKey, theAntRecordID, null);
	}

	public static ArrayList<AntPattern.AntPoint> getElevationPattern(String theDbID, Integer extDbKey,
			String theAntRecordID, ErrorLogger errors) {
		ExtDb theExtDb = ExtDb.getExtDb(theDbID, extDbKey, errors);
		if (null == theExtDb) {
			return null;
		}
		return getElevationPattern(theExtDb, theAntRecordID, "", errors, null);
	}

	protected static ArrayList<AntPattern.AntPoint> getElevationPattern(ExtDbRecord theRecord, ErrorLogger errors) {
		return getElevationPattern(theRecord.extDb, theRecord.elevationAntennaRecordID, "", errors, theRecord);
	}

	private static ArrayList<AntPattern.AntPoint> getElevationPattern(ExtDb theExtDb, String theAntRecordID,
			String fieldSuffix, ErrorLogger errors, ExtDbRecord theRecord) {

		if ((null == theAntRecordID) || (0 == theAntRecordID.length())) {
			return null;
		}

		String query = "";
		boolean isCDBS = false;

		switch (theExtDb.type) {

			case ExtDb.DB_TYPE_CDBS:
			case ExtDb.DB_TYPE_CDBS_FM: {

				query = 
				"SELECT " +
					"depression_angle," +
					"field_value" + fieldSuffix + " " +
				"FROM " +
					"elevation_pattern " +
				"WHERE " +
					"elevation_antenna_id = " + theAntRecordID + " " +
				"ORDER BY 1";

				isCDBS = true;

				break;
			}

			case ExtDb.DB_TYPE_LMS: {

				query =
				"SELECT " +
					"aaep_depression_angle," +
					"aaep_field_value " +
				"FROM " +
					"app_antenna_elevation_pattern " +
				"WHERE " +
					"(aaep_antenna_record_id = '" + theAntRecordID + "') " +
					"AND (aaep_azimuth = 0.) " +
				"ORDER BY 1";

				break;
			}

			case ExtDb.DB_TYPE_LMS_LIVE: {

				query =
				"SELECT " +
					"aaep_depression_angle," +
					"aaep_field_value " +
				"FROM " +
					"mass_media.app_antenna_elevation_pattern " +
				"WHERE " +
					"(aaep_antenna_record_id = '" + theAntRecordID + "') " +
					"AND (aaep_azimuth = 0.) " +
				"ORDER BY 1";

				break;
			}

			case ExtDb.DB_TYPE_WIRELESS: {

				query =
				"SELECT " +
					"degree, " +
					"relative_field " +
				"FROM " +
					ExtDb.WIRELESS_PATTERN_TABLE + " " +
				"WHERE " +
					"ant_id = " + theAntRecordID + " " +
				"ORDER BY 1";

				break;
			}

			case ExtDb.DB_TYPE_GENERIC_TV:
			case ExtDb.DB_TYPE_GENERIC_WL:
			case ExtDb.DB_TYPE_GENERIC_FM: {

				query = 
				"SELECT " +
					"depression_angle, " +
					"relative_field " +
				"FROM " +
					"source_vertical_pattern " +
				"WHERE " +
					"source_key = " + theAntRecordID + " " +
				"ORDER BY 1";

				break;
			}

			default: {
				return null;
			}
		}

		ArrayList<AntPattern.AntPoint> result = null;

		boolean badData = false, allZero = true, showDbWarning = false;
		String errmsg = null;

		DbConnection db = theExtDb.connectDb(errors);
		if (null != db) {
			try {

				db.query(query);

				result = new ArrayList<AntPattern.AntPoint>();
				double dep, pat, lastDep = AntPattern.DEPRESSION_MIN - 1., patMax = AntPattern.FIELD_MIN;

				while (db.next()) {

					dep = Math.rint(db.getDouble(1) * AntPattern.DEPRESSION_ROUND) / AntPattern.DEPRESSION_ROUND;
					if ((dep < AntPattern.DEPRESSION_MIN) || (dep > AntPattern.DEPRESSION_MAX)) {
						badData = true;
						errmsg = "vertical angle out of range";
						break;
					}
					if (dep <= lastDep) {
						badData = true;
						errmsg = "duplicate vertical angles";
						break;
					}
					lastDep = dep;

					pat = Math.rint(db.getDouble(2) * AntPattern.FIELD_ROUND) / AntPattern.FIELD_ROUND;
					if (pat < 0.) {
						pat = Math.pow(10., (-pat / 20.));
						showDbWarning = true;
					}
					if (pat < AntPattern.FIELD_MIN) {
						pat = AntPattern.FIELD_MIN;
					} else {
						allZero = false;
					}
					if (pat > AntPattern.FIELD_MAX) {
						badData = true;
						break;
					}
					if (pat > patMax) {
						patMax = pat;
					}

					result.add(new AntPattern.AntPoint(dep, pat));
				}

				// Here, an empty result may be an error; in CDBS, elevation pattern keys are never just identifiers.
				// But this is not an error in LMS as all records have a common antenna identifier which may or may
				// not have pattern data associated.  Also if the data column is all zeros treat that as a not-found
				// condition, that will occur when a matrix pattern is loaded as a normal pattern or vice-versa.

				if (result.isEmpty() || allZero) {

					if (!badData && isCDBS) {
						badData = true;
						errmsg = "pattern data not found";
					}

				} else {

					if (!badData && (result.size() < AntPattern.PATTERN_REQUIRED_POINTS)) {
						badData = true;
						errmsg = "not enough points";
					}

					if (!badData && fieldSuffix.equals("") && (patMax < 0.5)) {
						badData = true;
						errmsg = "max value is too small";
					}

					if (!badData && (null != errors) && fieldSuffix.equals("") && showDbWarning) {
						String msg = "Pattern for elevation antenna record ID " + theAntRecordID +
							" has negative values, assumed to be dB.";
						if (null != theRecord) {
							msg = makeMessage(theRecord, msg);
						}
						errors.logMessage(msg);
					}

					if (!badData && (null != errors) && fieldSuffix.equals("") &&
							(patMax < AntPattern.FIELD_MAX_CHECK)) {
						String msg = "Pattern for elevation antenna record ID " + theAntRecordID +
							" does not have a 1.";
						if (null != theRecord) {
							msg = makeMessage(theRecord, msg);
						}
						errors.logMessage(msg);
					}
				}

				theExtDb.releaseDb(db);

			} catch (SQLException se) {
				theExtDb.releaseDb(db);
				result = null;
				DbConnection.reportError(errors, se);
			}
		}

		if (badData) {
			if (null != errors) {
				String msg = "Pattern for elevation antenna record ID " + theAntRecordID + " is bad, " + errmsg + ".";
				if (null != theRecord) {
					errors.logMessage(makeMessage(theRecord, msg));
				} else {
					errors.reportError(msg);
				}
			}
			result = null;
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Retrieve a per-azimuth vertical pattern, a.k.a. a matrix pattern.

	public static ArrayList<AntPattern.AntSlice> getMatrixPattern(String theDbID, Integer extDbKey,
			String theAntRecordID) {
		return getMatrixPattern(theDbID, extDbKey, theAntRecordID, null);
	}

	public static ArrayList<AntPattern.AntSlice> getMatrixPattern(String theDbID, Integer extDbKey,
			String theAntRecordID, ErrorLogger errors) {
		ExtDb theExtDb = ExtDb.getExtDb(theDbID, extDbKey, errors);
		if (null == theExtDb) {
			return null;
		}
		return getMatrixPattern(theExtDb, theAntRecordID, errors, null);
	}

	protected static ArrayList<AntPattern.AntSlice> getMatrixPattern(ExtDbRecord theRecord, ErrorLogger errors) {
		return getMatrixPattern(theRecord.extDb, theRecord.elevationAntennaRecordID, errors, theRecord);
	}

	private static ArrayList<AntPattern.AntSlice> getMatrixPattern(ExtDb theExtDb, String theAntRecordID,
			ErrorLogger errors, ExtDbRecord theRecord) {

		if ((null == theAntRecordID) || (0 == theAntRecordID.length())) {
			return null;
		}

		String query = "";

		switch (theExtDb.type) {

			case ExtDb.DB_TYPE_CDBS:
			case ExtDb.DB_TYPE_CDBS_FM: {

				query =
				"SELECT " +
					"azimuth," +
					"depression_angle," +
					"field_value " +
				"FROM " +
					"elevation_pattern_addl " +
				"WHERE " +
					"elevation_antenna_id = " + theAntRecordID + " " +
				"ORDER BY 1, 2";

				break;
			}

			case ExtDb.DB_TYPE_LMS: {

				query =
				"SELECT " +
					"aaep_azimuth," +
					"aaep_depression_angle," +
					"aaep_field_value " +
				"FROM " +
					"app_antenna_elevation_pattern " +
				"WHERE " +
					"aaep_antenna_record_id = '" + theAntRecordID + "' " +
				"ORDER BY 1, 2";

				break;
			}

			case ExtDb.DB_TYPE_LMS_LIVE: {

				query =
				"SELECT " +
					"aaep_azimuth," +
					"aaep_depression_angle," +
					"aaep_field_value " +
				"FROM " +
					"mass_media.app_antenna_elevation_pattern " +
				"WHERE " +
					"aaep_antenna_record_id = '" + theAntRecordID + "' " +
				"ORDER BY 1, 2";

				break;
			}

			case ExtDb.DB_TYPE_GENERIC_TV:
			case ExtDb.DB_TYPE_GENERIC_WL:
			case ExtDb.DB_TYPE_GENERIC_FM: {

				query = 
				"SELECT " +
					"azimuth, " +
					"depression_angle, " +
					"relative_field " +
				"FROM " +
					"source_matrix_pattern " +
				"WHERE " +
					"source_key = " + theAntRecordID + " " +
				"ORDER BY 1, 2";

				break;
			}

			default: {
				return null;
			}
		}

		ArrayList<AntPattern.AntSlice> result = new ArrayList<AntPattern.AntSlice>();

		AntPattern.AntSlice theSlice = null;
		ArrayList<AntPattern.AntPoint> thePattern = null;
		double patMax = AntPattern.FIELD_MIN;

		// For CDBS, start by getting the standard 10-degree azimuth set from the main elevation pattern table, this is
		// handled by the private version of getElevationPattern(), see above.

		if ((ExtDb.DB_TYPE_CDBS == theExtDb.type) || (ExtDb.DB_TYPE_CDBS_FM == theExtDb.type)) {
			for (int iaz = 0; iaz < 360; iaz += 10) {
				thePattern = getElevationPattern(theExtDb, theAntRecordID, String.valueOf(iaz), errors, theRecord);
				if (null == thePattern) {
					return null;
				}
				for (AntPattern.AntPoint thePoint : thePattern) {
					if (thePoint.relativeField > patMax) {
						patMax = thePoint.relativeField;
					}
				}
				theSlice = new AntPattern.AntSlice((double)iaz, thePattern);
				result.add(theSlice);
			}
		}

		// Now a query on the additional-points pattern table for CDBS, or the main elevation pattern table for any
		// other data set type.  In CDBS this may provide additional full pattern slices at azimuths other than the
		// 10-degree set, and/or it/ may contain additional depression angles for the 10-degree slices.  For all others
		// this is the only query and provides all data.  Earlier checks will have determined that a matrix pattern is
		// present, else this would not have been called.

		boolean badData = false;
		String errmsg = null;

		AntPattern.AntPoint thePoint, newPoint;

		DbConnection db = theExtDb.connectDb(errors);
		if (null != db) {
			try {

				db.query(query);

				thePattern = null;
				double az, dep, pat, lastAz = AntPattern.AZIMUTH_MIN - 1., lastDep = 0.;
				boolean newpat = false;
				int i;

				while (db.next()) {

					az = Math.rint(db.getDouble(1) * AntPattern.AZIMUTH_ROUND) / AntPattern.AZIMUTH_ROUND;
					if ((az < AntPattern.AZIMUTH_MIN) || (az > AntPattern.AZIMUTH_MAX)) {
						badData = true;
						errmsg = "azimuth out of range";
						break;
					}

					if (az != lastAz) {

						if (newpat && (thePattern.size() < AntPattern.PATTERN_REQUIRED_POINTS)) {
							badData = true;
							errmsg = "not enough points, at azimuth " + theSlice.value;
							break;
						}

						newpat = true;
						for (i = 0; i < result.size(); i++) {
							theSlice = result.get(i);
							if (az == theSlice.value) {
								newpat = false;
								break;
							}
							if (az < theSlice.value) {
								break;
							}
						}

						if (newpat) {
							thePattern = new ArrayList<AntPattern.AntPoint>();
							theSlice = new AntPattern.AntSlice(az, thePattern);
							result.add(i, theSlice);
						} else {
							thePattern = theSlice.points;
						}

						lastAz = az;
						lastDep = AntPattern.DEPRESSION_MIN - 1.;
					}

					dep = Math.rint(db.getDouble(2) * AntPattern.DEPRESSION_ROUND) / AntPattern.DEPRESSION_ROUND;
					if ((dep < AntPattern.DEPRESSION_MIN) || (dep > AntPattern.DEPRESSION_MAX)) {
						badData = true;
						errmsg = "vertical angle out of range, at azimuth " + theSlice.value;
						break;
					}
					if (dep <= lastDep) {
						badData = true;
						errmsg = "duplicate vertical angles, at azimuth " + theSlice.value;
						break;
					}
					lastDep = dep;

					pat = Math.rint(db.getDouble(3) * AntPattern.FIELD_ROUND) / AntPattern.FIELD_ROUND;
					if (pat < AntPattern.FIELD_MIN) {
						pat = AntPattern.FIELD_MIN;
					}
					if (pat > AntPattern.FIELD_MAX) {
						badData = true;
						errmsg = "field value greater than 1, at azimuth " + theSlice.value;
						break;
					}
					if (pat > patMax) {
						patMax = pat;
					}

					newPoint = new AntPattern.AntPoint(dep, pat);

					if (newpat) {

						thePattern.add(newPoint);

					} else {

						for (i = 0; i < thePattern.size(); i++) {
							thePoint = thePattern.get(i);
							if (dep == thePoint.angle) {
								badData = true;
								errmsg = "duplicate vertical angles, at azimuth " + theSlice.value;
								break;
							}
							if (dep  < thePoint.angle) {
								break;
							}
						}

						if (badData) {
							break;
						}

						thePattern.add(i, newPoint);
					}
				}

				if (!badData && newpat && (thePattern.size() < AntPattern.PATTERN_REQUIRED_POINTS)) {
					badData = true;
					errmsg = "not enough points, at azimuth " + theSlice.value;
				}

				if (!badData && (null != errors) && (patMax < AntPattern.FIELD_MAX_CHECK)) {
					String msg = "Pattern for elevation antenna record ID " + theAntRecordID + " does not have a 1.";
					if (null != theRecord) {
						msg = makeMessage(theRecord, msg);
					}
					errors.logMessage(msg);
				}

				theExtDb.releaseDb(db);

			} catch (SQLException se) {
				theExtDb.releaseDb(db);
				result = null;
				DbConnection.reportError(errors, se);
			}
		}

		if (badData) {
			if (null != errors) {
				String msg = "Pattern for elevation antenna record ID " + theAntRecordID + " is bad, " + errmsg + ".";
				if (null != theRecord) {
					errors.logMessage(makeMessage(theRecord, msg));
				} else {
					errors.reportError(msg);
				}
			}
			result = null;
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Compose a data warning message including record identifiers, used with logMessage() in error reporter objects.

	public static String makeMessage(Record theRecord, String theMessage) {

		return String.format(Locale.US, "%-6.6s %-8.8s %-3.3s %-2.2s %-6.6s: %s", theRecord.getFacilityID(),
			theRecord.getCallSign(), theRecord.getChannel(), theRecord.getServiceCode(), theRecord.getStatus(),
			theMessage);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Fill the cache of CDBS/LMS service code mappings for use by subclasses.  This is called after similar methods
	// in core.data classes the first time any database is opened, see comments in DbCore.openDb().

	protected static HashMap<String, Service> cdbsCodeCache = new HashMap<String, Service>();
	protected static HashMap<String, Service> lmsCodeCache = new HashMap<String, Service>();

	public static void loadCache(DbConnection db) throws SQLException {

		cdbsCodeCache.clear();
		lmsCodeCache.clear();

		Service theService;

		db.query("SELECT cdbs_service_code, service_key FROM cdbs_service");
		while (db.next()) {
			theService = Service.getService(Integer.valueOf(db.getInt(2)));
			if (null != theService) {
				cdbsCodeCache.put(db.getString(1), theService);
			}
		}

		db.query("SELECT lms_service_code, service_key FROM lms_service");
		while (db.next()) {
			theService = Service.getService(Integer.valueOf(db.getInt(2)));
			if (null != theService) {
				lmsCodeCache.put(db.getString(1), theService);
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Build comment text intended to show as a tool-tip pop-up in tables.  Shows various details that generally are
	// not significant enough to have separate columns in a display table.

	public String makeCommentText() {

		StringBuilder s = new StringBuilder();
		ArrayList<String> comments = getComments();
		if ((null != comments) && !comments.isEmpty()) {
			String pfx = "<HTML>";
			for (String theComment : comments) {
				s.append(pfx);
				s.append(theComment);
				pfx = "<BR>";
			}
			s.append("</HTML>");
		}
		return s.toString();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// May be overridden by subclass to provide lines for the comment text.

	protected ArrayList<String> getComments() {

		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Methods in the Record interface.

	public String getRecordType() {

		return extDb.getTypeName();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isSource() {

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean hasRecordID() {

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isReplication() {

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getStationData() {

		return ExtDb.getExtDbDescription(extDb.dbID, extDb.key);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getRecordID() {

		return extRecordID;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getFacilityID() {

		return "";
	}

	public String getSortFacilityID() {

		return "0";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getService() {

		return service.name;
	}

	public String getServiceCode() {

		return service.serviceCode;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isDTS() {

		return service.isDTS;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getSiteCount() {

		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getCallSign() {

		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getChannel() {

		return "";
	}

	public String getSortChannel() {

		return "0";
	}

	public String getOriginalChannel() {

		return getChannel();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getFrequency() {

		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getCity() {

		return city;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getState() {

		return state;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getCountry() {

		return country.name;
	}

	public String getCountryCode() {

		return country.countryCode;
	}

	public String getSortCountry() {

		return String.valueOf(country.key);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getZone() {

		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getStatus() {

		return "";
	}

	public String getSortStatus() {

		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getFileNumber() {

		return "";
	}

	public String getARN() {

		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getSequenceDate() {

		return AppCore.formatDate(sequenceDate);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getSortSequenceDate() {

		return String.format(Locale.US, "%013d", sequenceDate.getTime());
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getFrequencyOffset() {

		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getEmissionMask() {

		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getLatitude() {

		return AppCore.formatLatitude(location.latitude);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getLongitude() {

		return AppCore.formatLongitude(location.longitude);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getHeightAMSL() {

		return AppCore.formatHeight(heightAMSL) + " m";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getOverallHAAT() {

		return AppCore.formatHeight(overallHAAT) + " m";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getPeakERP() {

		return AppCore.formatERP(peakERP) + " kW";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean hasHorizontalPattern() {

		return (null != antennaRecordID);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getHorizontalPatternName() {

		if (null != antennaRecordID) {
			if ((null != antennaID) && (antennaID.length() > 0)) {
				return "ID " + antennaID;
			} else {
				return "unknown";
			}
		}
		return "Omnidirectional";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getHorizontalPatternOrientation() {

		if (null != antennaRecordID) {
			return AppCore.formatAzimuth(horizontalPatternOrientation) + " deg";
		}
		return "";
	}
}
