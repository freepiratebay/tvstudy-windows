//
//  SearchAPI.java
//  TVStudy
//
//  Copyright (c) 2015-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.api;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.editdata.*;

import java.util.*;
import java.sql.*;
import java.io.*;


//=====================================================================================================================
// APIOperation subclass for searching external station data or the user record table for records.  The initial
// OP_START operation returns a generic search form, however that is not always used directly.  Other operations may
// use the addSearchFields() method to embed the search in a form on another page.  In any case the search form will
// send an OP_RUN here.  That request may contain a NEXT_OP to specify an operation to chain once a record is found.
// If the initial search returns just one record the chain is immediate, otherwise an intermediate results form is
// shown allowing the user to choose the desired record, and the next operation is submitted by that form.  If no next
// operation is provided, once a search is complete the OP_SHOW operation is called to simply display the record.
// Note at the moment this only supports TV records.

public class SearchAPI extends APIOperation {

	public static final String OP_START = "search";
	public static final String OP_RUN = "searchrun";
	public static final String OP_SHOW = "searchshow";

	public static final String KEY_USER_RECORD_ID = "user_record_id";
	public static final String KEY_EXT_DB_KEY = "ext_db_key";
	public static final String KEY_EXT_RECORD_ID = "ext_record_id";

	public static final String KEY_RECORD_ID = "record_id";
	public static final String KEY_FILE_NUMBER = "file_number";

	public static final String KEY_INCLUDE_ARCHIVED = "include_archived";

	// Maximum number of records matching search to show on result page.

	private static final int MAX_RECORDS = 200;

	// Error logger.  The logger is used to collect errors from core API calls, it does not actually log or report,
	// the error message is immediately passed off to handleError().

	private ErrorLogger errors;


	//-----------------------------------------------------------------------------------------------------------------

	public static boolean canHandleOperation(String op) {

		return (OP_START.equals(op) || OP_RUN.equals(op) || OP_SHOW.equals(op));
	}


	//-----------------------------------------------------------------------------------------------------------------

	public SearchAPI(String op, HashMap<String, String> theParams, String theError) {

		super(op, theParams, theError);

		errors = new ErrorLogger();

		dispatchOperation(op);
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected void dispatchOperation(String op) {

		if (OP_START.equals(op)) {
			doOpStart();
			return;
		}

		if (OP_RUN.equals(op)) {
			doOpRun();
			return;
		}

		if (OP_SHOW.equals(op)) {
			doOpShow();
			return;
		}

		super.dispatchOperation(op);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Show the basic search form, chaining to the run operation.  Display a list of available station data.

	private void doOpStart() {

		StringBuilder page = new StringBuilder();

		addPageHeader(page, "TVStudy - Record Search", 0, errorMessage);

		addFormStart(page, OP_RUN, OP_START, "New Search", OP_SHOW);

		page.append("<br>Station data:<br><br>");

		page.append("<select name=\"" + KEY_EXT_DB_KEY + "\">\n");

		Integer extDbKey = Integer.valueOf(-1);

		String value = parameters.get(KEY_EXT_DB_KEY);
		if (null != value) {
			try {
				extDbKey = Integer.valueOf(value);
				if (0 == extDbKey.intValue()) {
					extDbKey = null;
				}
			} catch (NumberFormatException e) {
			}
		}

		ArrayList<KeyedRecord> list = ExtDb.getExtDbList(dbID, Source.RECORD_TYPE_TV, errors);
		if (null == list) {
			handleError(errors.toString(), backOp);
			return;
		}

		boolean doUser = true;

		for (KeyedRecord theDb : list) {
			if (doUser && ((theDb.key < ExtDb.RESERVED_KEY_RANGE_START) ||
					(theDb.key > ExtDb.RESERVED_KEY_RANGE_END))) {
				if (null == extDbKey) {
					page.append("<option value=\"0\" selected>User records</option>\n");
				} else {
					page.append("<option value=\"0\">User records</option>\n");
				}
				doUser = false;
			}
			if ((null != extDbKey) && (extDbKey.intValue() == theDb.key)) {
				page.append("<option value=\"" + theDb.key + "\" selected>" + theDb.name + "</option>\n");
			} else {
				page.append("<option value=\"" + theDb.key + "\">" + theDb.name + "</option>\n");
			}
		}

		if (doUser) {
			if (null == extDbKey) {
				page.append("<option value=\"0\" selected>User records</option>\n");
			} else {
				page.append("<option value=\"0\">User records</option>\n");
			}
		}

		page.append("</select><br>\n");

		addSearchFields(page, parameters);

		addFormEnd(page, "Search");

		addPageFooter(page);

		resultPage = page.toString();
		status = STATUS_PAGE;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This is a search query, it may match one record or many.  Some criteria are assumed to only match one record
	// and will chain to the next operation in this request.  A more general search may match multiple records, in
	// which case this will return a page with a form for the user to select the desired record, or repeat the search.
	// First check if the request has a specific record ID, those are primary identifiers and the next operation will
	// have to query for the record regardless, so there is no need to do anything here, just chain immediately.  Note
	// other operations may consider file number to be a primary ID equivalent to a user or station data record ID,
	// but here it is a search criteria.  It is possible for a file number to match more than one record.

	private void doOpRun() {

		if (null == backOp) {
			backOp = OP_START;
			backOpLabel = "New Search";
		}

		if (null == nextOp) {
			nextOp = OP_SHOW;
		}

		if ((null != parameters.get(KEY_USER_RECORD_ID)) || (null != parameters.get(KEY_EXT_RECORD_ID))) {
			chainToOperation(nextOp);
			return;
		}

		// If the station data key is missing or 0, this will be a search of the user record table.  Otherwise verify
		// the key, fail if it does not identify a valid data set.

		ExtDb extDb = null;

		Integer theKey = null;
		String value = parameters.get(KEY_EXT_DB_KEY);
		if (null != value) {
			try {
				theKey = Integer.valueOf(value);
				if (0 == theKey.intValue()) {
					theKey = null;
				}
			} catch (NumberFormatException e) {
				handleError("ERROR: Invalid station data key", OP_START);
				return;
			}
		}

		if (null != theKey) {
			extDb = ExtDb.getExtDb(dbID, theKey, errors);
			if (null == extDb) {
				handleError(errors.toString(), OP_START);
				return;
			}
			if (Source.RECORD_TYPE_TV != extDb.recordType) {
				handleError("ERROR: Invalid station data key", OP_START);
				return;
			}
		}

		// Compose the query string.  If the record ID or file number are set, all other criteria are ignored as those
		// are expected to match just one specific record.  For a file number search if archived records are included
		// there may be multiple matches, however those are historical versions of the same record and it is assumed
		// they would not vary with respect to other search criteria.  Otherwise a search is performed combining all
		// criteria with AND.  The search is always restricted by channel and record type even with ID or file number.

		int dbType = ExtDb.DB_TYPE_NOT_SET;
		int version = 0;
		boolean isSrc = true;
		if (null != extDb) {
			dbType = extDb.type;
			version = extDb.version;
			isSrc = extDb.isGeneric();
		}

		StringBuilder query = new StringBuilder();

		try {

			boolean hasCrit = false, hasChannel = false;

			if (isSrc) {
				if (SourceEditData.addRecordIDQuery(dbType, parameters.get(KEY_RECORD_ID), query, false)) {
					hasCrit = true;
				}
			} else {
				if (ExtDbRecord.addRecordIDQuery(dbType, version, parameters.get(KEY_RECORD_ID), query, false)) {
					hasCrit = true;
				}
			}

			if (!hasCrit) {
				if (isSrc) {
					if (SourceEditData.addFileNumberQuery(dbType, parameters.get(KEY_FILE_NUMBER), query, false)) {
						hasCrit = true;
					}
				} else {
					if (ExtDbRecord.addFileNumberQuery(dbType, version, parameters.get(KEY_FILE_NUMBER), query,
							false)) {
						hasCrit = true;
					}
				}
			}

			if (!hasCrit) {

				if (isSrc) {
					if (SourceEditData.addFacilityIDQuery(dbType, parameters.get(RecordAPI.KEY_FACILITY_ID), query,
							false)) {
						hasCrit = true;
					}
				} else {
					if (ExtDbRecord.addFacilityIDQuery(dbType, version, parameters.get(RecordAPI.KEY_FACILITY_ID),
							query, false)) {
						hasCrit = true;
					}
				}

				if (isSrc) {
					if (SourceEditData.addServiceQuery(dbType, parameters.get(RecordAPI.KEY_SERVICE), query,
							hasCrit)) {
						hasCrit = true;
					}
				} else {
					if (ExtDbRecord.addServiceQuery(dbType, version, parameters.get(RecordAPI.KEY_SERVICE), query,
							hasCrit)) {
						hasCrit = true;
					}
				}

				if (isSrc) {
					if (SourceEditData.addCallSignQuery(dbType, parameters.get(RecordAPI.KEY_CALL_SIGN), query,
							hasCrit)) {
						hasCrit = true;
					}
				} else {
					if (ExtDbRecord.addCallSignQuery(dbType, version, parameters.get(RecordAPI.KEY_CALL_SIGN), query,
							hasCrit)) {
						hasCrit = true;
					}
				}

				if (isSrc) {
					if (SourceEditData.addChannelQuery(dbType, parameters.get(RecordAPI.KEY_CHANNEL), query,
							hasCrit)) {
						hasChannel = true;
						hasCrit = true;
					}
				} else {
					if (ExtDbRecord.addChannelQuery(dbType, version, parameters.get(RecordAPI.KEY_CHANNEL), query,
							hasCrit)) {
						hasChannel = true;
						hasCrit = true;
					}
				}

				if (isSrc) {
					if (SourceEditData.addStatusQuery(dbType, parameters.get(RecordAPI.KEY_STATUS), query, hasCrit)) {
						hasCrit = true;
					}
				} else {
					if (ExtDbRecord.addStatusQuery(dbType, version, parameters.get(RecordAPI.KEY_STATUS), query,
							hasCrit)) {
						hasCrit = true;
					}
				}

				if (isSrc) {
					if (SourceEditData.addCityQuery(dbType, parameters.get(RecordAPI.KEY_CITY), query, hasCrit)) {
						hasCrit = true;
					}
				} else {
					if (ExtDbRecord.addCityQuery(dbType, version, parameters.get(RecordAPI.KEY_CITY), query,
							hasCrit)) {
						hasCrit = true;
					}
				}

				if (isSrc) {
					if (SourceEditData.addStateQuery(dbType, parameters.get(RecordAPI.KEY_STATE), query, hasCrit)) {
						hasCrit = true;
					}
				} else {
					if (ExtDbRecord.addStateQuery(dbType, version, parameters.get(RecordAPI.KEY_STATE), query,
							hasCrit)) {
						hasCrit = true;
					}
				}

				// Some search criteria must be provided.

				if (!hasCrit) {
					handleError("Please provide at least one search condition", OP_START);
					return;
				}
			}

			// The search is always restricted to legal channel range, unless a specific channel was added above.

			if (!hasChannel) {
				if (isSrc) {
					SourceEditData.addChannelRangeQuery(dbType, SourceTV.CHANNEL_MIN, SourceTV.CHANNEL_MAX, query,
						true);
				} else {
					ExtDbRecord.addChannelRangeQuery(dbType, version, SourceTV.CHANNEL_MIN, SourceTV.CHANNEL_MAX,
						query, true);
				}
			}

			// Archived records are ignored by default, but can optionally be included.

			if (!isSrc) {

				value = parameters.get(KEY_INCLUDE_ARCHIVED);
				boolean includeArchived = ((null != value) && Boolean.valueOf(value).booleanValue());

				ExtDbRecord.addRecordTypeQuery(dbType, version, true, includeArchived, query, true);
			}

		} catch (IllegalArgumentException ie) {
			handleError("ERROR: " + ie.getMessage(), OP_START);
			return;
		}

		// Do the search.

		ArrayList<Record> results = new ArrayList<Record>();

		if (isSrc) {
			java.util.List<SourceEditData> sources = null;
			if (ExtDb.DB_TYPE_NOT_SET == dbType) {
				sources = SourceEditData.findUserRecords(dbID, Source.RECORD_TYPE_TV, query.toString(), errors);
			} else {
				sources = SourceEditData.findImportRecords(extDb, query.toString(), errors);
			}
			if (null == sources) {
				handleError(errors.toString(), OP_START);
				return;
			}
			results.addAll(sources);
		} else {
			LinkedList<ExtDbRecordTV> records = ExtDbRecordTV.findRecordsTV(extDb, query.toString(), errors);
			if (null == records) {
				handleError(errors.toString(), OP_START);
				return;
			}
			results.addAll(records);
		}

		int recordCount = results.size();
		if (0 == recordCount) {
			handleError("Search did not find any matching records", OP_START);
			return;
		}

		// If just a single record matched place specific record ID in parameters and chain immediately.

		if (1 == recordCount) {
			if (null == extDb) {
				parameters.put(KEY_USER_RECORD_ID, results.get(0).getRecordID());
			} else {
				parameters.put(KEY_EXT_DB_KEY, String.valueOf(extDb.dbKey));
				parameters.put(KEY_EXT_RECORD_ID, results.get(0).getRecordID());
			}
			chainToOperation(nextOp);
			return;
		}

		// Compose the page showing multiple results.  First sort the records.

		Collections.sort(results, new Comparator<Record>() {
			public int compare(Record thisRecord, Record otherRecord) {
				int result = thisRecord.getSortCountry().compareTo(otherRecord.getSortCountry());
				if (0 == result) {
					result = thisRecord.getState().compareTo(otherRecord.getState());
					if (0 == result) {
						result = thisRecord.getCity().compareTo(otherRecord.getCity());
						if (0 == result) {
							result = thisRecord.getSortChannel().compareTo(otherRecord.getSortChannel());
							if (0 == result) {
								result = thisRecord.getSortStatus().compareTo(otherRecord.getSortStatus());
							}
						}
					}
				}
				return result;
			}
		});

		StringBuilder page = new StringBuilder();

		addPageHeader(page, "TVStudy - Search Results", 0, errorMessage);

		addFormStart(page, nextOp, backOp, backOpLabel, null);

		String key;
		if (null == extDb) {
			key = KEY_USER_RECORD_ID;
		} else {
			key = KEY_EXT_RECORD_ID;
			page.append("<input type=\"hidden\" name=\"" + KEY_EXT_DB_KEY + "\" value=\"" + extDb.dbKey + "\">\n");
		}

		if (recordCount > MAX_RECORDS) {
			page.append("<br>Too many records, only the first " + MAX_RECORDS + " are shown.<br>\n");
		}

		page.append("<br>Choose record:<br><br>\n");
		int theCount = 0;
		for (Record theRecord : results) {
			if (++theCount > MAX_RECORDS) {
				break;
			}
			page.append("<input type=\"radio\" name=\"" + key + "\" value=\"" + theRecord.getRecordID() + "\">");
			addRecordInfo(page, theRecord);
		}

		page.append("<br>\n");

		// Include hidden search fields so they pass through the next operation for it's back operation.

		addHiddenFields(page, parameters);

		addFormEnd(page, "Choose");

		// Back button.

		addFormStart(page, backOp);
		addHiddenFields(page, parameters);
		addFormEnd(page, backOpLabel);

		addPageFooter(page);

		resultPage = page.toString();
		status = STATUS_PAGE;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Display a record.

	private void doOpShow() {

		if (null == backOp) {
			backOp = OP_START;
			backOpLabel = "New Search";
		}

		SearchResult theResult = getSearchResult(parameters);
		if (null != theResult.errorMessage) {
			handleError(theResult.errorMessage, OP_START);
			return;
		}

		StringBuilder page = new StringBuilder();

		addPageHeader(page, "TVStudy - Record Found", 0, errorMessage);

		addResultSummary(page, theResult);

		addFormStart(page, backOp);
		addHiddenFields(page, parameters);
		addFormEnd(page, backOpLabel);

		addPageFooter(page);

		resultPage = page.toString();
		status = STATUS_PAGE;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add the search fields to a form.  This has two sections, the first is for parameters that are expected to match
	// no more than one record, those are record ID or file number.  The second section is for a general search that
	// may return multiple results.  Note the caller must include something in the form to set KEY_EXT_DB_KEY, that is
	// missing or the value 0 for the user record table, else a valid station data key.

	public static void addSearchFields(StringBuilder page, HashMap<String, String> theParams) {

		page.append("<br>Search for records:<br><br>\n");
		page.append("<table>\n");

		page.append("<tr><td>Record ID</td>\n");
		String value = theParams.get(KEY_RECORD_ID);
		if (null != value) {
			page.append("<td><input type=\"text\" name=\"" + KEY_RECORD_ID + "\" value=\"" + value + "\"></td>\n");
		} else {
			page.append("<td><input type=\"text\" name=\"" + KEY_RECORD_ID + "\"></td>\n");
		}

		page.append("<tr><td>File number</td>\n");
		value = theParams.get(KEY_FILE_NUMBER);
		if (null != value) {
			page.append("<td><input type=\"text\" name=\"" + KEY_FILE_NUMBER + "\" value=\"" + value + "\"></td>\n");
		} else {
			page.append("<td><input type=\"text\" name=\"" + KEY_FILE_NUMBER + "\"></td>\n");
		}

		value = theParams.get(RecordAPI.KEY_FACILITY_ID);
		page.append("<tr><td>Facility ID</td>\n");
		if (null != value) {
			page.append("<td><input type=\"text\" name=\"" + RecordAPI.KEY_FACILITY_ID + "\" value=\"" + value +
				"\"></td>\n");
		} else {
			page.append("<td><input type=\"text\" name=\"" + RecordAPI.KEY_FACILITY_ID + "\"></td>\n");
		}

		value = theParams.get(RecordAPI.KEY_CALL_SIGN);
		page.append("<tr><td>Call sign</td>\n");
		if (null != value) {
			page.append("<td><input type=\"text\" name=\"" + RecordAPI.KEY_CALL_SIGN + "\" value=\"" + value +
				"\"></td>\n");
		} else {
			page.append("<td><input type=\"text\" name=\"" + RecordAPI.KEY_CALL_SIGN + "\"></td>\n");
		}

		value = theParams.get(RecordAPI.KEY_CHANNEL);
		page.append("<tr><td>Channel</td>\n");
		if (null != value) {
			page.append("<td><input type=\"text\" name=\"" + RecordAPI.KEY_CHANNEL + "\" value=\"" + value +
				"\"></td>\n");
		} else {
			page.append("<td><input type=\"text\" name=\"" + RecordAPI.KEY_CHANNEL + "\"></td>\n");
		}

		value = theParams.get(RecordAPI.KEY_SERVICE);
		page.append("<tr><td>Service</td>");
		page.append("<td><select name=\"" + RecordAPI.KEY_SERVICE + "\">\n");
		page.append("<option value=\"\">(any)</option>\n");
		for (Service theService : Service.getAllServices()) {
			if (theService.serviceCode.equals(value)) {
				page.append("<option value=\"" + theService.serviceCode + "\" selected>" + theService.name +
					"</option>\n");
			} else {
				page.append("<option value=\"" + theService.serviceCode + "\">" + theService.name + "</option>\n");
			}
		}
		page.append("</select></td>\n");

		value = theParams.get(RecordAPI.KEY_STATUS);
		page.append("<tr><td>Status</td>\n");
		page.append("<td><select name=\"" + RecordAPI.KEY_STATUS + "\">\n");
		page.append("<option value=\"\">(any)</option>\n");
		for (KeyedRecord stat : ExtDbRecord.getStatusList()) {
			if (stat.name.equals(value)) {
				page.append("<option value=\"" + stat.name + "\" selected>" + stat.name + "</option>\n");
			} else {
				page.append("<option value=\"" + stat.name + "\">" + stat.name + "</option>\n");
			}
		}
		page.append("</select></td>\n");

		value = theParams.get(RecordAPI.KEY_CITY);
		page.append("<tr><td>City</td>\n");
		if (null != value) {
			page.append("<td><input type=\"text\" name=\"" + RecordAPI.KEY_CITY + "\" value=\"" + value +
				"\"></td>\n");
		} else {
			page.append("<td><input type=\"text\" name=\"" + RecordAPI.KEY_CITY + "\"></td>\n");
		}

		value = theParams.get(RecordAPI.KEY_STATE);
		page.append("<tr><td>State</td>\n");
		if (null != value) {
			page.append("<td><input type=\"text\" name=\"" + RecordAPI.KEY_STATE + "\" value=\"" + value +
				"\"></td>\n");
		} else {
			page.append("<td><input type=\"text\" name=\"" + RecordAPI.KEY_STATE + "\"></td>\n");
		}
	
		value = theParams.get(KEY_INCLUDE_ARCHIVED);
		page.append("<tr><td>Include archived records</td>\n");
		if ((null != value) && Boolean.valueOf(value).booleanValue()) {
			page.append("<td><input type=\"checkbox\" name=\"" + KEY_INCLUDE_ARCHIVED +
				"\" value=\"true\" checked></td>\n");
		} else {
			page.append("<td><input type=\"checkbox\" name=\"" + KEY_INCLUDE_ARCHIVED + "\" value=\"true\"></td>\n");
		}

		page.append("</table><br>\n");
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add the parameters used for searching as hidden fields.  This does not include the fields that match a specific
	// record, those are record ID or file number.

	public static void addHiddenFields(StringBuilder page, HashMap<String, String> theParams) {

		String value = theParams.get(KEY_EXT_DB_KEY);
		if (null != value) {
			page.append("<input type=\"hidden\" name=\"" + KEY_EXT_DB_KEY + "\" value=\"" + value + "\">\n");
		}

		value = theParams.get(RecordAPI.KEY_CALL_SIGN);
		if (null != value) {
			page.append("<input type=\"hidden\" name=\"" + RecordAPI.KEY_CALL_SIGN + "\" value=\"" + value + "\">\n");
		}

		value = theParams.get(RecordAPI.KEY_CHANNEL);
		if (null != value) {
			page.append("<input type=\"hidden\" name=\"" + RecordAPI.KEY_CHANNEL + "\" value=\"" + value + "\">\n");
		}

		value = theParams.get(RecordAPI.KEY_SERVICE);
		if (null != value) {
			page.append("<input type=\"hidden\" name=\"" + RecordAPI.KEY_SERVICE + "\" value=\"" + value + "\">\n");
		}

		value = theParams.get(RecordAPI.KEY_STATUS);
		if (null != value) {
			page.append("<input type=\"hidden\" name=\"" + RecordAPI.KEY_STATUS + "\" value=\"" + value + "\">\n");
		}

		value = theParams.get(RecordAPI.KEY_CITY);
		if (null != value) {
			page.append("<input type=\"hidden\" name=\"" + RecordAPI.KEY_CITY + "\" value=\"" + value + "\">\n");
		}

		value = theParams.get(RecordAPI.KEY_STATE);
		if (null != value) {
			page.append("<input type=\"hidden\" name=\"" + RecordAPI.KEY_STATE + "\" value=\"" + value + "\">\n");
		}

		value = theParams.get(RecordAPI.KEY_FACILITY_ID);
		if (null != value) {
			page.append("<input type=\"hidden\" name=\"" + RecordAPI.KEY_FACILITY_ID + "\" value=\"" + value +
				"\">\n");
		}

		value = theParams.get(KEY_INCLUDE_ARCHIVED);
		if (null != value) {
			page.append("<input type=\"hidden\" name=\"" + KEY_INCLUDE_ARCHIVED + "\" value=\"" + value + "\">\n");
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a description of a record to a page.

	public static void addRecordInfo(StringBuilder page, Record theRecord) {

		page.append(theRecord.getCallSign());
		page.append(' ');
		page.append(theRecord.getChannel());
		page.append(' ');
		page.append(theRecord.getServiceCode());
		page.append(' ');
		String str = theRecord.getStatus();
		if (str.length() > 0) {
			page.append(str);
			page.append(' ');
		}
		page.append(theRecord.getCity());
		page.append(", ");
		page.append(theRecord.getState());
		page.append("<br>\n");
	}


	//=================================================================================================================
	// The result of a search, or of any form that specifically identifies either an external station data record or a
	// user-created custom record, will include parameters providing a user record ID, or a station data key and either
	// a record ID or a file number.  The getSearchResult() method parses those parameters and loads the record,
	// returning a SearchResult object.  If an error occurs the errorMessage property in the result object is non-null.

	public static class SearchResult {

		public SourceEditDataTV source;
		public ExtDbRecordTV record;

		public String errorMessage;


		//-------------------------------------------------------------------------------------------------------------
		// Convenience methods.

		public boolean isDigital() {

			if (null != source) {
				return source.service.isDigital();
			}
			if (null != record) {
				return record.service.isDigital();
			}
			return false;
		}


		//-------------------------------------------------------------------------------------------------------------

		public boolean isLPTV() {

			if (null != source) {
				return source.service.isLPTV();
			}
			if (null != record) {
				return record.service.isLPTV();
			}
			return false;
		}


		//-------------------------------------------------------------------------------------------------------------

		public boolean isClassA() {

			if (null != source) {
				return source.service.isClassA();
			}
			if (null != record) {
				return record.service.isClassA();
			}
			return false;
		}


		//-------------------------------------------------------------------------------------------------------------

		public java.util.Date getSequenceDate() {

			if (null != source) {
				java.util.Date seqDate = AppCore.parseDate(source.getAttribute(Source.ATTR_SEQUENCE_DATE));
				if (null == seqDate) {
					seqDate = new java.util.Date();
				}
				return seqDate;
			}
			if (null != record) {
				return record.sequenceDate;
			}
			return null;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static SearchResult getSearchResult(HashMap<String, String> parameters) {

		StringBuilder query = new StringBuilder();
		ErrorLogger errors = new ErrorLogger();
		SearchResult theResult = new SearchResult();

		// If a user record ID exists that takes priority.

		String value = parameters.get(KEY_USER_RECORD_ID);
		if (null != value) {

			try {
				SourceEditData.addRecordIDQuery(ExtDb.DB_TYPE_NOT_SET, value, query, false);
			} catch (IllegalArgumentException ie) {
				theResult.errorMessage = "ERROR: " + ie.toString();
				return theResult;
			}

			ArrayList<SourceEditData> sources =
				SourceEditData.findUserRecords(dbID, Source.RECORD_TYPE_TV, query.toString(), errors);
			if (null == sources) {
				theResult.errorMessage = errors.toString();
			} else {
				if (sources.isEmpty()) {
					theResult.errorMessage = "ERROR: Record not found";
				} else {
					theResult.source = (SourceEditDataTV)sources.get(0);
				}
			}

			return theResult;
		}

		// This is an external station data record lookup, the data key must exist and be valid.

		int theKey = 0;
		value = parameters.get(KEY_EXT_DB_KEY);
		if (null != value) {
			try {
				theKey = Integer.parseInt(value);
			} catch (NumberFormatException e) {
			}
		}
		if (theKey <= 0) {
			theResult.errorMessage = "ERROR: Missing or invalid station data key";
			return theResult;
		}

		ExtDb extDb = ExtDb.getExtDb(dbID, theKey, errors);
		if (null == extDb) {
			theResult.errorMessage = errors.toString();
			return theResult;
		}
		if (Source.RECORD_TYPE_TV != extDb.recordType) {
			theResult.errorMessage = "ERROR: Invalid station data key";
			return theResult;
		}

		// Extract the record ID or file number (record ID has priority) and attempt to load the record.  This must
		// match only one record; multiple matches are an error.  When file number is used exclude archived records.

		value = parameters.get(KEY_EXT_RECORD_ID);
		if (null != value) {
			try {
				if (extDb.isGeneric()) {
					SourceEditData.addRecordIDQuery(extDb.type, value, query, false);
				} else {
					ExtDbRecord.addRecordIDQuery(extDb.type, extDb.version, value, query, false);
				}
			} catch (IllegalArgumentException ie) {
				theResult.errorMessage = "ERROR: " + ie.getMessage();
				return theResult;
			}
		} else {
			value = parameters.get(KEY_FILE_NUMBER);
			if (null != value) {
				try {
					if (extDb.isGeneric()) {
						SourceEditData.addFileNumberQuery(extDb.type, value, query, false);
					} else {
						ExtDbRecord.addFileNumberQuery(extDb.type, extDb.version, value, query, false);
						ExtDbRecord.addRecordTypeQuery(extDb.type, extDb.version, true, false, query, true);
					}
				} catch (IllegalArgumentException ie) {
					theResult.errorMessage = "ERROR: " + ie.getMessage();
					return theResult;
				}
			} else {
				theResult.errorMessage = "ERROR: Missing record ID or file number";
				return theResult;
			}
		}

		if (extDb.isGeneric()) {
			LinkedList<SourceEditData> sources = SourceEditData.findImportRecords(extDb, query.toString(), errors);
			if (null == sources) {
				theResult.errorMessage = errors.toString();
			} else {
				if (sources.isEmpty()) {
					theResult.errorMessage = "ERROR: Record not found";
				} else {
					if (sources.size() > 1) {
						theResult.errorMessage = "ERROR: ID or file number matches more than one record";
					} else {
						theResult.source = (SourceEditDataTV)(sources.getFirst());
					}
				}
			}
		} else {
			LinkedList<ExtDbRecordTV> records = ExtDbRecordTV.findRecordsTV(extDb, query.toString(), errors);
			if (null == records) {
				theResult.errorMessage = errors.toString();
			} else {
				if (records.isEmpty()) {
					theResult.errorMessage = "ERROR: Record not found";
				} else {
					if (records.size() > 1) {
						theResult.errorMessage = "ERROR: ID or file number matches more than one record";
					} else {
						theResult.record = records.getFirst();
					}
				}
			}
		}

		return theResult;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a full description of a search result.  Or the error message if set.

	public static void addResultSummary(StringBuilder page, SearchResult theResult) {

		if (null != theResult.source) {
			doSummary(page, theResult.source, SourceEditData.getSourceComment(theResult.source));
		} else {
			if (null != theResult.record) {
				doSummary(page, theResult.record, "");
			} else {
				page.append("<br><b>" + theResult.errorMessage + "</b><br><br>\n");
			}
		}
	}

	private static void doSummary(StringBuilder page, Record theRecord, String theComment) {

		page.append("<table>\n");

		page.append("<tr><td>Call sign</td><td>");
		page.append(theRecord.getCallSign());
		page.append("</td>\n");

		page.append("<tr><td>Channel</td><td>");
		page.append(theRecord.getChannel());
		page.append("</td>\n");

		page.append("<tr><td>Service</td><td>");
		page.append(theRecord.getService());
		page.append("</td>\n");

		String str = theRecord.getStatus();
		if (str.length() > 0) {
			page.append("<tr><td>Status</td><td>");
			page.append(str);
			page.append("</td>\n");
		}

		page.append("<tr><td>City</td><td>");
		page.append(theRecord.getCity());
		page.append("</td>\n");

		page.append("<tr><td>State</td><td>");
		page.append(theRecord.getState());
		page.append("</td>\n");

		page.append("<tr><td>Facility ID</td><td>");
		page.append(theRecord.getFacilityID());
		page.append("</td>\n");

		if (theRecord.hasRecordID()) {

			page.append("<tr><td>Station data</td><td>");
			page.append(theRecord.getStationData());
			page.append("</td>\n");

			page.append("<tr><td>Record ID</td><td>");
			page.append(theRecord.getRecordID());
			page.append("</td>\n");
		}

		str = theRecord.getFileNumber();
		if (str.length() > 0) {
			page.append("<tr><td>File number</td><td>");
			page.append(str);
			page.append("</td>\n");
		}

		page.append("<tr><td>Country</td><td>");
		page.append(theRecord.getCountry());
		page.append("</td>\n");

		str = theRecord.getZone();
		if (str.length() > 0) {
			page.append("<tr><td>Zone</td><td>");
			page.append(str);
			page.append("</td>\n");
		}

		str = theRecord.getFrequencyOffset();
		if (str.length() > 0) {
			page.append("<tr><td>Frequency offset</td><td>");
			page.append(str);
			page.append("</td>\n");
		}

		str = theRecord.getEmissionMask();
		if (str.length() > 0) {
			page.append("<tr><td>Emission mask</td><td>");
			page.append(str);
			page.append("</td>\n");
		}

		page.append("<tr><td>Latitude</td><td>");
		page.append(theRecord.getLatitude());
		page.append("</td>\n");

		page.append("<tr><td>Longitude</td><td>");
		page.append(theRecord.getLongitude());
		page.append("</td>\n");

		if (theRecord.isDTS()) {

			page.append("<tr><td># DTS sites</td><td>");
			page.append(theRecord.getSiteCount());
			page.append("</td>\n");

		} else {

			page.append("<tr><td>Height AMSL</td><td>");
			page.append(theRecord.getHeightAMSL());
			page.append("</td>\n");

			page.append("<tr><td>HAAT</td><td>");
			page.append(theRecord.getOverallHAAT());
			page.append("</td>\n");

			page.append("<tr><td>Peak ERP</td><td>");
			page.append(theRecord.getPeakERP());
			page.append("</td>\n");

			if (theRecord.hasHorizontalPattern()) {

				page.append("<tr><td>Azimuth pattern</td><td>");
				page.append(theRecord.getHorizontalPatternName());
				page.append("</td>\n");

				page.append("<tr><td>Orientation</td><td>");
				page.append(theRecord.getHorizontalPatternOrientation());
				page.append("</td>\n");
			}
		}

		if ((null != theComment) && (theComment.length() > 0)) {
			page.append("<tr><td>Comment</td><td width=\"200\">");
			page.append(theComment);
			page.append("</td>\n");
		}

		page.append("</table><br>\n");
	}
}
