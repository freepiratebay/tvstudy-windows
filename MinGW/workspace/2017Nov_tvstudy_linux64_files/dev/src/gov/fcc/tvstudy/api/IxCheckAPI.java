//
//  IxCheckAPI.java
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
import java.text.*;
import java.net.*;


//=====================================================================================================================
// APIOperation subclass for setting up and managing interference-check study runs; requests here return various pages
// for determining the study record and settings, ending with a request to run the study using a StudyBuildIxCheck
// object.  The OP_START operation sends a form page with fields to provide information to search for a record to study
// or to create a user-entered record, those operations are handled by other classes, see SearchAPI and RecordAPI.
// OP_SETUP is chained from those other operations once a specific record has been identified, it presents a form to
// enter study-specific parameters.  That form sends an OP_RUN.  OP_CACHE shows a page for doing maintenance on the
// cached output files from past studies, with various actions to update the cache index and delete cached output.

public class IxCheckAPI extends APIOperation implements StatusLogger {

	public static final String OP_START = "ixcheck";
	public static final String OP_SETUP = "ixchecksetup";
	public static final String OP_RUN = "ixcheckrun";

	public static final String KEY_REPLICATION_CHANNEL = "replication_channel";
	public static final String KEY_TEMPLATE_KEY = "template_key";
	public static final String KEY_STUDY_EXT_DB_KEY = "study_ext_db_key";
	public static final String KEY_OUTPUT_CONFIG = "output_config";
	public static final String KEY_MAP_OUTPUT_CONFIG = "map_output_config";
	public static final String KEY_CELL_SIZE = "cell_size";
	public static final String KEY_PROFILE_PPK = "profile_ppk";
	public static final String KEY_PROFILE_PT_INC = "profile_pt_inc";
	public static final String KEY_PROTECT_NON_BL = "protect_non_bl";
	public static final String KEY_LPTV_PROTECT_BL = "lptv_protect_bl";
	public static final String KEY_CLASS_A_PROTECT_LPTV = "class_a_protect_lptv";
	public static final String KEY_INCLUDE_FOREIGN = "include_foreign";
	public static final String KEY_INCLUDE_USERIDS = "include_userids";
	public static final String KEY_CP_EXCLUDES_BASELINE = "cp_excludes_baseline";
	public static final String KEY_EXCLUDE_APPS = "ignore_apps";
	public static final String KEY_EXCLUDE_PENDING = "ignore_pending";
	public static final String KEY_EXCLUDE_POST_TRANSITION = "ignore_post_trans";
	public static final String KEY_EXCLUDE_ARNS = "exclude_arns";
	public static final String KEY_FILING_CUTOFF = "filing_cutoff";

	public static final String KEY_RERUN = "rerun";
	public static final String KEY_SHOWPAST = "showpast";

	public static final String OP_CACHE = "ixcheckcache";

	public static final String KEY_ACTION = "cache_action";
	public static final String ACTION_CLEANUP = "cleanup";
	public static final String ACTION_DELETE = "delete";
	public static final String KEY_DAYS = "delete_days";

	// Engine output file top directory.

	private static String outPath = outRootPath + File.separator + AppCore.OUT_DIRECTORY_NAME;

	// Error logger for collecting errors from core API.

	private ErrorLogger errors;

	// The object managing the study build and run.

	private StudyBuildIxCheck ixCheck;

	private IxCheckAPI outerThis = this;


	//-----------------------------------------------------------------------------------------------------------------
	// Called by servletInit() when a new servlet container is starting, do a silent cache cleanup.

	protected static void servletStartup(String theDbID) {

		StudyBuildIxCheck.cacheCleanup(theDbID, outPath, null);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static boolean canHandleOperation(String op) {

		return (OP_START.equals(op) || OP_SETUP.equals(op) || OP_RUN.equals(op) || OP_CACHE.equals(op));
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Most operations start with loading the interference-check study default configuration.

	public IxCheckAPI(String op, HashMap<String, String> theParams, String theError) {

		super(op, theParams, theError);

		errors = new ErrorLogger();

		if (!OP_CACHE.equals(op)) {

			ixCheck = new StudyBuildIxCheck(dbID);

			ixCheck.outPath = outPath;
			ixCheck.indexFileName = INDEX_FILE_NAME;
			ixCheck.logFileName = LOG_FILE_NAME;

			ixCheck.loadDefaults();
			if ((ixCheck.templateKey <= 0) || (null == ixCheck.extDb) || ixCheck.extDb.deleted ||
					(null == ixCheck.fileOutputConfig) || !ixCheck.fileOutputConfig.isValid() ||
					(null == ixCheck.mapOutputConfig) || !ixCheck.mapOutputConfig.isValid()) {
				handleError("ERROR: Could not load default study settings", backOp);
				return;
			}
		}

		// Branch out to specific operation.

		dispatchOperation(op);
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected void dispatchOperation(String op) {

		if (OP_START.equals(op)) {
			doOpStart();
			return;
		}

		if (OP_SETUP.equals(op)) {
			doOpSetup();
			return;
		}

		if (OP_RUN.equals(op)) {
			doOpRun();
			return;
		}

		if (OP_CACHE.equals(op)) {
			doOpCache();
			return;
		}

		super.dispatchOperation(op);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Compose the start page for search or new-record request.

	private void doOpStart() {

		StringBuilder page = new StringBuilder();

		addPageHeader(page, "TVStudy - Interference Check", 0, errorMessage);

		page.append("Find record for interference check study<br>");

		// Search form to find study record.

		addFormStart(page, SearchAPI.OP_RUN, OP_START, "New Search", OP_SETUP);

		page.append("<br>Station data:<br><br>");
		page.append("<select name=\"" + SearchAPI.KEY_EXT_DB_KEY + "\">\n");
		ArrayList<KeyedRecord> list = ExtDb.getExtDbList(dbID, Source.RECORD_TYPE_TV, errors);
		if (null == list) {
			handleError(errors.toString(), backOp);
			return;
		}
		boolean doUser = true;
		for (KeyedRecord theDb : list) {
			if (doUser && ((theDb.key < ExtDb.RESERVED_KEY_RANGE_START) ||
					(theDb.key > ExtDb.RESERVED_KEY_RANGE_END))) {
				page.append("<option value=\"0\">User records</option>\n");
				doUser = false;
			}
			if (ixCheck.extDb.key.intValue() == theDb.key) {
				page.append("<option value=\"" + theDb.key + "\" selected>" + theDb.name + "</option>\n");
			} else {
				page.append("<option value=\"" + theDb.key + "\">" + theDb.name + "</option>\n");
			}
		}
		if (doUser) {
			page.append("<option value=\"0\">User records</option>\n");
		}
		page.append("</select><br>\n");

		SearchAPI.addSearchFields(page, parameters);

		addFormEnd(page, "Search");

		// Option to create a new user record.  Forward any search parameters to the edit form as a convenience.

		addFormStart(page, RecordAPI.OP_START, OP_START, "Cancel", OP_SETUP);
		SearchAPI.addHiddenFields(page, parameters);
		addFormEnd(page, "Create New Record");

		addPageFooter(page);

		resultPage = page.toString();
		status = STATUS_PAGE;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Setup for a run, first load the record to confirm the search result is valid.  If the station data key is 0 it
	// indicates a user record search.  If the station data key is missing use the default from study configuration.
	// That will not occur if this request is from the search form, but requests may come from other sources and one
	// with nothing but the external record ID or a file number is valid, see SearchAPI.getSearchResult().

	private void doOpSetup() {

		if (null == backOp) {
			backOp = OP_START;
			backOpLabel = "New Search";
		}

		if (null == parameters.get(SearchAPI.KEY_EXT_DB_KEY)) {
			parameters.put(SearchAPI.KEY_EXT_DB_KEY, String.valueOf(ixCheck.extDb.dbKey));
		}

		SearchAPI.SearchResult target = SearchAPI.getSearchResult(parameters);
		if (null != target.errorMessage) {
			handleError(target.errorMessage, backOp);
			return;
		}

		// Compose the page.

		StringBuilder page = new StringBuilder();

		addPageHeader(page, "TVStudy Interference Check", 0, errorMessage);

		page.append("<br>Selected record<br><br>\n");
		SearchAPI.addResultSummary(page, target);

		page.append("<br>Study settings:<br><br>\n");

		addFormStart(page, OP_RUN, backOp, backOpLabel, null);

		if (null != target.source) {
			page.append("<input type=\"hidden\" name=\"" + SearchAPI.KEY_USER_RECORD_ID + "\" value=\"" +
				target.source.userRecordID + "\">\n");
		} else {
			page.append("<input type=\"hidden\" name=\"" + SearchAPI.KEY_EXT_DB_KEY + "\" value=\"" +
				target.record.extDb.dbKey + "\">\n");
			page.append("<input type=\"hidden\" name=\"" + SearchAPI.KEY_EXT_RECORD_ID + "\" value=\"" +
				target.record.extRecordID + "\">\n");
		}

		page.append("<input type=\"hidden\" name=\"" + KEY_SHOWPAST + "\" value=\"true\">\n");

		page.append("<table>\n");

		page.append("<tr><td>Replicate to channel</td>\n");
		String value = parameters.get(KEY_REPLICATION_CHANNEL);
		if (null != value) {
			page.append("<td><input type=\"text\" name=\"" + KEY_REPLICATION_CHANNEL + "\" value=\"" + value +
				"\"></td>\n");
		} else {
			if ((null != target.record) && (target.record.replicateToChannel > 0)) {
				page.append("<td><input type=\"text\" name=\"" + KEY_REPLICATION_CHANNEL + "\" value=\"" +
					String.valueOf(target.record.replicateToChannel) + "\"></td>\n");
			} else {
				page.append("<td><input type=\"text\" name=\"" + KEY_REPLICATION_CHANNEL + "\"></td>\n");
			}
		}

		page.append("<tr><td>Template</td>\n");
		page.append("<td><select name=\"" + KEY_TEMPLATE_KEY + "\">\n");
		ArrayList<KeyedRecord> list = Template.getTemplateInfoList(dbID, errors);
		if (null == list) {
			handleError(errors.toString(), backOp);
			return;
		}
		Template.Info theInfo;
		for (KeyedRecord theItem : list) {
			theInfo = (Template.Info)theItem;
			if (theInfo.isLocked && !theInfo.isLockedInStudy) {
				if (theInfo.key == ixCheck.templateKey) {
					page.append("<option value=\"" + theInfo.key + "\" selected>" + theInfo.name + "</option>\n");
				} else {
					page.append("<option value=\"" + theInfo.key + "\">" + theInfo.name + "</option>\n");
				}
			}
		}
		page.append("</select></td>\n");

		page.append("<tr><td>Study station data</td>\n");
		page.append("<td><select name=\"" + KEY_STUDY_EXT_DB_KEY + "\">\n");
		list = ExtDb.getExtDbList(dbID, ExtDb.DB_TYPE_LMS, StudyBuildIxCheck.MIN_LMS_VERSION, errors);
		if (null == list) {
			handleError(errors.toString(), backOp);
			return;
		}
		int defKey = ixCheck.extDb.key.intValue();
		if (null != target.record) {
			defKey = target.record.extDb.dbKey.intValue();
		}
		for (KeyedRecord theDb : list) {
			if (defKey == theDb.key) {
				page.append("<option value=\"" + theDb.key + "\" selected>" + theDb.name + "</option>\n");
			} else {
				page.append("<option value=\"" + theDb.key + "\">" + theDb.name + "</option>\n");
			}
		}
		page.append("</select></td>\n");

		page.append("<tr><td>Output file settings</td>\n");
		page.append("<td><select name=\"" + KEY_OUTPUT_CONFIG + "\">\n");
		boolean didSelect = false;
		for (OutputConfig theConf : OutputConfig.getConfigs(dbID, OutputConfig.CONFIG_TYPE_FILE)) {
			if (theConf.equals(ixCheck.fileOutputConfig)) {
				page.append("<option value=\"" + theConf.getCodes() + "\"selected>" + theConf.name + "</option>\n");
				didSelect = true;
			} else {
				page.append("<option value=\"" + theConf.getCodes() + "\">" + theConf.name + "</option>\n");
			}
		}
		if (!didSelect) {
			page.append("<option value=\"" + ixCheck.fileOutputConfig.getCodes() + "\"selected>" +
				ixCheck.fileOutputConfig.name + "</option>\n");
		}
		page.append("</select></td>\n");

		page.append("<tr><td>Map output settings</td>\n");
		page.append("<td><select name=\"" + KEY_OUTPUT_CONFIG + "\">\n");
		didSelect = false;
		for (OutputConfig theConf : OutputConfig.getConfigs(dbID, OutputConfig.CONFIG_TYPE_MAP)) {
			if (theConf.equals(ixCheck.mapOutputConfig)) {
				page.append("<option value=\"" + theConf.getCodes() + "\"selected>" + theConf.name + "</option>\n");
				didSelect = true;
			} else {
				page.append("<option value=\"" + theConf.getCodes() + "\">" + theConf.name + "</option>\n");
			}
		}
		if (!didSelect) {
			page.append("<option value=\"" + ixCheck.mapOutputConfig.getCodes() + "\"selected>" +
				ixCheck.mapOutputConfig.name + "</option>\n");
		}
		page.append("</select></td>\n");

		boolean isLPTV = target.isLPTV();
		boolean isClassA = target.isClassA();
		boolean isPostWindow = ((null != ixCheck.filingWindowEndDate) && (null != target.getSequenceDate()) &&
			target.getSequenceDate().after(ixCheck.filingWindowEndDate));

		// The cell size is a menu selection in the UI, the actual parameter can have any value in legal range but
		// the UI is restricted to discrete choices.

		value = parameters.get(KEY_CELL_SIZE);
		if (null == value) {
			value = ixCheck.cellSize;
			if (null == value) {
				if (isLPTV || (isClassA && isPostWindow)) {
					value = ixCheck.defaultCellSizeLPTV;
				} else {
					value = ixCheck.defaultCellSize;
				}
				if (null == value) {
					value = "";
				}
			}
		}
		double defsiz = StudyBuildIxCheck.CELL_SIZES[0];
		try {
			defsiz = Double.parseDouble(value);
		} catch (NumberFormatException nfe) {
		}
		page.append("<tr><td>Cell size, km</td>\n");
		page.append("<td><select name=\"" + KEY_CELL_SIZE + "\">\n");
		String str;
		for (double siz : StudyBuildIxCheck.CELL_SIZES) {
			str = AppCore.formatDecimal(siz, 1);
			if (defsiz >= siz) {
				page.append("<option value=\"" + str + "\"selected>" + str + "</option>\n");
				defsiz = 0.;
			} else {
				page.append("<option value=\"" + str + "\">" + str + "</option>\n");
			}
		}
		page.append("</select></td>\n");

		// Profile resolution in the UI is entered as the distance between points, however the parameters are always
		// resolution in points/km.  The request can specify this either way, with distance increment preferred.

		value = parameters.get(KEY_PROFILE_PT_INC);
		if (null == value) {
			value = parameters.get(KEY_PROFILE_PPK);
			if (null == value) {
				value = ixCheck.profilePpk;
				if (null == value) {
					if (isLPTV || (isClassA && isPostWindow)) {
						value = ixCheck.defaultProfilePpkLPTV;
					} else {
						value = ixCheck.defaultProfilePpk;
					}
					if (null == value) {
						value = "";
					}
				}
			}
			try {
				double ppk = Double.parseDouble(value);
				if (ppk > 0.) {
					value = AppCore.formatDecimal((1. / ppk), 2);
				}
			} catch (NumberFormatException nfe) {
			}
		}
		page.append("<tr><td>Profile point spacing, km</td>\n");
		page.append("<td><input type=\"text\" name=\"" + KEY_PROFILE_PT_INC + "\" value=\"" + value + "\"></td>\n");

		value = parameters.get(KEY_PROTECT_NON_BL);
		page.append("<tr><td>Protect records not on baseline channel</td>\n");
		if (((null != value) && Boolean.valueOf(value).booleanValue()) ||
				((null == value) && (ixCheck.protectPreBaseline || isLPTV))) {
			page.append("<td><input type=\"checkbox\" name=\"" + KEY_PROTECT_NON_BL +
				"\" value=\"true\" checked></td>\n");
		} else {
			page.append("<td><input type=\"checkbox\" name=\"" + KEY_PROTECT_NON_BL + "\" value=\"true\"></td>\n");
		}

		if (isLPTV) {
			value = parameters.get(KEY_LPTV_PROTECT_BL);
			page.append("<tr><td>Protect baseline records from LPTV</td>\n");
			if (((null != value) && Boolean.valueOf(value).booleanValue()) ||
					((null == value) && ixCheck.protectBaselineFromLPTV)) {
				page.append("<td><input type=\"checkbox\" name=\"" + KEY_LPTV_PROTECT_BL +
					"\" value=\"true\" checked></td>\n");
			} else {
				page.append("<td><input type=\"checkbox\" name=\"" + KEY_LPTV_PROTECT_BL +
					"\" value=\"true\"></td>\n");
			}
		}

		if (isClassA && !isPostWindow) {
			value = parameters.get(KEY_CLASS_A_PROTECT_LPTV);
			page.append("<tr><td>Protect LPTV records from Class A</td>\n");
			if ((null != value) && Boolean.valueOf(value).booleanValue()) {
				page.append("<td><input type=\"checkbox\" name=\"" + KEY_CLASS_A_PROTECT_LPTV +
					"\" value=\"true\" checked></td>\n");
			} else {
				page.append("<td><input type=\"checkbox\" name=\"" + KEY_CLASS_A_PROTECT_LPTV +
					"\" value=\"true\"></td>\n");
			}
		}

		value = parameters.get(KEY_INCLUDE_FOREIGN);
		page.append("<tr><td>Include non-U.S. records</td>\n");
		if (((null != value) && Boolean.valueOf(value).booleanValue()) ||
				((null == value) && ixCheck.includeForeign)) {
			page.append("<td><input type=\"checkbox\" name=\"" + KEY_INCLUDE_FOREIGN +
				"\" value=\"true\" checked></td>\n");
		} else {
			page.append("<td><input type=\"checkbox\" name=\"" + KEY_INCLUDE_FOREIGN + "\" value=\"true\"></td>\n");
		}

		value = parameters.get(KEY_INCLUDE_USERIDS);
		if (null == value) {
			value = "";
		}
		page.append("<tr><td>User records to include</td>\n");
		page.append("<td><textarea rows=\"4\" cols=\"18\" name=\"" + KEY_INCLUDE_USERIDS + "\">" + value +
			"</textarea></td>\n");

		value = parameters.get(KEY_CP_EXCLUDES_BASELINE);
		page.append("<tr><td>CP excludes station's baseline</td>\n");
		if (((null != value) && Boolean.valueOf(value).booleanValue()) ||
				((null == value) && ixCheck.excludeApps)) {
			page.append("<td><input type=\"checkbox\" name=\"" + KEY_CP_EXCLUDES_BASELINE +
				"\" value=\"true\" checked></td>\n");
		} else {
			page.append("<td><input type=\"checkbox\" name=\"" + KEY_CP_EXCLUDES_BASELINE +
				"\" value=\"true\"></td>\n");
		}

		value = parameters.get(KEY_EXCLUDE_APPS);
		page.append("<tr><td>Exclude all APP records</td>\n");
		if (((null != value) && Boolean.valueOf(value).booleanValue()) ||
				((null == value) && ixCheck.excludeApps)) {
			page.append("<td><input type=\"checkbox\" name=\"" + KEY_EXCLUDE_APPS +
				"\" value=\"true\" checked></td>\n");
		} else {
			page.append("<td><input type=\"checkbox\" name=\"" + KEY_EXCLUDE_APPS + "\" value=\"true\"></td>\n");
		}

		value = parameters.get(KEY_EXCLUDE_PENDING);
		page.append("<tr><td>Exclude all pending records</td>\n");
		if (((null != value) && Boolean.valueOf(value).booleanValue()) ||
				((null == value) && ixCheck.excludePending)) {
			page.append("<td><input type=\"checkbox\" name=\"" + KEY_EXCLUDE_PENDING +
				"\" value=\"true\" checked></td>\n");
		} else {
			page.append("<td><input type=\"checkbox\" name=\"" + KEY_EXCLUDE_PENDING + "\" value=\"true\"></td>\n");
		}

		value = parameters.get(KEY_EXCLUDE_POST_TRANSITION);
		page.append("<tr><td>Exclude all post-transition CP, APP, and BL records</td>\n");
		if (((null != value) && Boolean.valueOf(value).booleanValue()) ||
				((null == value) && ixCheck.excludePostTransition)) {
			page.append("<td><input type=\"checkbox\" name=\"" + KEY_EXCLUDE_POST_TRANSITION +
				"\" value=\"true\" checked></td>\n");
		} else {
			page.append("<td><input type=\"checkbox\" name=\"" + KEY_EXCLUDE_POST_TRANSITION +
				"\" value=\"true\"></td>\n");
		}

		value = parameters.get(KEY_EXCLUDE_ARNS);
		if (null == value) {
			value = "";
		}
		page.append("<tr><td>Record to exclude (ARNs)</td>\n");
		page.append("<td><textarea rows=\"4\" cols=\"18\" name=\"" + KEY_EXCLUDE_ARNS + "\">" + value +
			"</textarea></td>\n");

		value = parameters.get(KEY_FILING_CUTOFF);
		if (null == value) {
			if (null != ixCheck.filingCutoffDate) {
				value = AppCore.formatDate(ixCheck.filingCutoffDate);
			} else {
				value = "";
			}
		}
		page.append("<tr><td>Filing cutoff date</td>\n");
		page.append("<td><input type=\"text\" name=\"" + KEY_FILING_CUTOFF + "\" value=\"" + value + "\"></td>\n");

		page.append("</table>\n");

		addFormEnd(page, "Run Study");

		// Back button.

		addFormStart(page, backOp);
		SearchAPI.addHiddenFields(page, parameters);
		addFormEnd(page, backOpLabel);

		addPageFooter(page);

		resultPage = page.toString();
		status = STATUS_PAGE;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Do a run.  First load the target record.  See comments for doOpSetup(), this request also may be sent directly
	// (meaning not from a page returned by the setup op) with nothing other than the external record ID or a file
	// number, all other parameters have defaults.

	private void doOpRun() {

		if (null == parameters.get(SearchAPI.KEY_EXT_DB_KEY)) {
			parameters.put(SearchAPI.KEY_EXT_DB_KEY, String.valueOf(ixCheck.extDb.dbKey));
		}

		SearchAPI.SearchResult target = SearchAPI.getSearchResult(parameters);
		if (null != target.errorMessage) {
			handleError(target.errorMessage, backOp);
			return;
		}

		ixCheck.source = target.source;
		ixCheck.record = target.record;

		boolean isLPTV, isDigital;
		int chan;
		if (null != target.source) {
			isLPTV = target.source.service.isLPTV();
			isDigital = target.source.service.isDigital();
			chan = target.source.channel;
		} else {
			isLPTV = target.record.service.isLPTV();
			isDigital = target.record.service.isDigital();
			chan = target.record.channel;
		}

		// Parse the study settings and parameters from the form, error-check and set values.  First the replication
		// channel.  If the study record is digital and the replication channel is the same as the current channel,
		// just silently ignore the replication.  Analog facilities can be replicated to digital on the same channel.
		// Here the channel is checked against the full legal channel range; it is possible the channel will be out of
		// range for the actual study if hat sets a narrower range, but that won't be checked until the study is built.

		String value = parameters.get(KEY_REPLICATION_CHANNEL);
		if (null != value) {
			int c = 0;
			try {
				c = Integer.parseInt(value);
			} catch (NumberFormatException ne) {
			}
			if ((c < SourceTV.CHANNEL_MIN) || (c > SourceTV.CHANNEL_MAX)) {
				handleError("ERROR: Invalid replication channel", OP_SETUP);
				return;
			}
			if ((c != chan) || !isDigital) {
				ixCheck.replicate = true;
				ixCheck.replicationChannel = c;
			}
		}

		// Get the template key, if none use the default set in the study build object.

		value = parameters.get(KEY_TEMPLATE_KEY);
		if (null != value) {
			int t = 0;
			try {
				t = Integer.parseInt(value);
			} catch (NumberFormatException ne) {
			}
			Template.Info theInfo = null;
			if (t > 0) {
				theInfo = Template.getTemplateInfo(dbID, t);
			}
			if ((null == theInfo) || !theInfo.isLocked || theInfo.isLockedInStudy) {
				handleError("ERROR: Invalid study template selection", OP_SETUP);
				return;
			}
			ixCheck.templateKey = t;
		}

		// If the study build data set is not specified, attempt to use the same data as for the record search; if
		// that does not exist, e.g. the target is a user record, just stay with the default in the build object.  If
		// that is not set it means there were no suitable station data sets found at all, must fail.

		value = parameters.get(KEY_STUDY_EXT_DB_KEY);
		if (null != value) {
			int k = 0;
			try {
				k = Integer.parseInt(value);
			} catch (NumberFormatException ne) {
			}
			ExtDb theDb = null;
			if (k > 0) {
				theDb = ExtDb.getExtDb(dbID, Integer.valueOf(k));
			}
			if ((null == theDb) || (Source.RECORD_TYPE_TV != theDb.recordType)) {
				handleError("ERROR: Invalid station data set selection", OP_SETUP);
				return;
			}
			ixCheck.extDb = theDb;
		} else {
			if (null != target.record) {
				ixCheck.extDb = target.record.extDb;
			}
		}
		if (null == ixCheck.extDb) {
			handleError("ERROR: No station data available", backOp);
			return;
		}

		value = parameters.get(KEY_OUTPUT_CONFIG);
		if (null != value) {
			ixCheck.fileOutputConfig = new OutputConfig(OutputConfig.CONFIG_TYPE_FILE, value);
		}
		value = parameters.get(KEY_MAP_OUTPUT_CONFIG);
		if (null != value) {
			ixCheck.mapOutputConfig = new OutputConfig(OutputConfig.CONFIG_TYPE_MAP, value);
		}

		// Neither cell size nor resolution has to be set in the build object; the build will revert to the actual
		// template values if needed.

		value = parameters.get(KEY_CELL_SIZE);
		if (null != value) {
			double d = Parameter.MIN_CELL_SIZE - 1.;
			try {
				d = Double.parseDouble(value);
			} catch (NumberFormatException ne) {
			}
			if ((d < Parameter.MIN_CELL_SIZE) || (d > Parameter.MAX_CELL_SIZE)) {
				handleError("ERROR: Bad cell size", OP_SETUP);
				return;
			}
			ixCheck.cellSize = value;
		}

		// The profile resolution can be specified as either a point spacing or resolution, spacing is preferred for
		// the argument but the parameter is always resolution so the value must be converted.

		value = parameters.get(KEY_PROFILE_PT_INC);
		if (null != value) {
			double d = Parameter.MIN_PATH_TERR_RES - 1.;
			try {
				d = Double.parseDouble(value);
				if (d > 0.) {
					d = 1. / d;
				}
			} catch (NumberFormatException ne) {
			}
			if ((d < Parameter.MIN_PATH_TERR_RES) || (d > Parameter.MAX_PATH_TERR_RES)) {
				handleError("ERROR: Bad profile point spacing", OP_SETUP);
				return;
			}
			ixCheck.profilePpk = String.valueOf(d);
		} else {
			value = parameters.get(KEY_PROFILE_PPK);
			if (null != value) {
				double d = Parameter.MIN_PATH_TERR_RES - 1.;
				try {
					d = Double.parseDouble(value);
				} catch (NumberFormatException ne) {
				}
				if ((d < Parameter.MIN_PATH_TERR_RES) || (d > Parameter.MAX_PATH_TERR_RES)) {
					handleError("ERROR: Bad profile resolution", OP_SETUP);
					return;
				}
				ixCheck.profilePpk = value;
			}
		}

		value = parameters.get(KEY_PROTECT_NON_BL);
		if (null != value) {
			ixCheck.protectPreBaseline = Boolean.valueOf(value).booleanValue();
		} else {
			ixCheck.protectPreBaseline = isLPTV;
		}

		value = parameters.get(KEY_LPTV_PROTECT_BL);
		if (null != value) {
			ixCheck.protectBaselineFromLPTV = Boolean.valueOf(value).booleanValue();
		}

		value = parameters.get(KEY_CLASS_A_PROTECT_LPTV);
		if (null != value) {
			ixCheck.protectLPTVFromClassA = Boolean.valueOf(value).booleanValue();
		}

		value = parameters.get(KEY_INCLUDE_FOREIGN);
		if (null != value) {
			ixCheck.includeForeign = Boolean.valueOf(value).booleanValue();
		}

		ixCheck.includeUserRecords = null;
		value = parameters.get(KEY_INCLUDE_USERIDS);
		if (null != value) {
			ixCheck.includeUserRecords = new TreeSet<Integer>();
			int id;
			for (String uid : value.split("\\s+")) {
				id = 0;
				try {
					id = Integer.parseInt(uid);
				} catch (NumberFormatException nfe) {
				}
				if (id <= 0) {
					handleError("ERROR: Bad user record ID in include list", OP_SETUP);
					return;
				}
				ixCheck.includeUserRecords.add(Integer.valueOf(id));
			}
		}

		value = parameters.get(KEY_CP_EXCLUDES_BASELINE);
		if (null != value) {
			ixCheck.cpExcludesBaseline = Boolean.valueOf(value).booleanValue();
		}

		value = parameters.get(KEY_EXCLUDE_APPS);
		if (null != value) {
			ixCheck.excludeApps = Boolean.valueOf(value).booleanValue();
		}

		value = parameters.get(KEY_EXCLUDE_PENDING);
		if (null != value) {
			ixCheck.excludePending = Boolean.valueOf(value).booleanValue();
		}

		value = parameters.get(KEY_EXCLUDE_POST_TRANSITION);
		if (null != value) {
			ixCheck.excludePostTransition = Boolean.valueOf(value).booleanValue();
		}

		ixCheck.excludeCommands = null;
		value = parameters.get(KEY_EXCLUDE_ARNS);
		if (null != value) {
			ixCheck.excludeCommands = new TreeSet<String>();
			for (String cmd : value.split("\\s+")) {
				ixCheck.excludeCommands.add(cmd);
			}
		}

		value = parameters.get(KEY_FILING_CUTOFF);
		if (null != value) {
			ixCheck.filingCutoffDate = AppCore.parseDate(value);
			if (null == ixCheck.filingCutoffDate) {
				handleError("ERROR: Bad cutoff date, use format YYYY-MM-DD or MM/DD/YYYY", OP_SETUP);
				return;
			}
		}

		// All properties are set, initialize the study run object.

		if (!ixCheck.initialize(errors)) {
			handleError(errors.toString(), OP_SETUP);
			return;
		}

		// Decide if the study will actually run vs. showing cached results.  If the rerun flag is set this will be a
		// new run regardless, otherwise get a list of past runs.  If there are none, again this will be a new run.  If
		// there is at least one past run, then check the showpast flag.  If that is not set just redirect to the most
		// recent past run (retrieving the past run list leaves the build object state set to that most-recent run),
		// otherwise show an index page of all past runs and including a link to trigger a re-run.

		value = parameters.get(KEY_RERUN);
		if (((null == value) || !Boolean.valueOf(value).booleanValue())) {

			ArrayList<StudyBuildIxCheck.RunIndex> pastRuns = ixCheck.getPastRuns(errors);
			if (null == pastRuns) {
				handleError(errors.toString(), OP_SETUP);
				return;
			}

			if (!pastRuns.isEmpty()) {

				value = parameters.get(KEY_SHOWPAST);
				if ((null == value) || !Boolean.valueOf(value).booleanValue()) {

					resultURL = ixCheck.getIndexURLPath();
					status = STATUS_URL;

				} else {

					StringBuilder page = new StringBuilder();

					addPageHeader(page, "TVStudy - Past Study Results", 0, null);

					page.append("<br>");
					page.append(ixCheck.getStudyDescription());
					page.append("<br>\n");

					page.append("<br>Past study results<br><br>\n");
					DateFormat dateFmt = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.LONG, Locale.US);
					for (StudyBuildIxCheck.RunIndex theRun : pastRuns) {
						page.append("<a href=\"");
						page.append(theRun.indexURLPath);
						page.append("\">");
						page.append(dateFmt.format(theRun.runDate));
						page.append("</a><br>\n");
					}

					// Add a link to re-run the study with all settings explicit and the rerun flag set.

					page.append("<br><a href=\"/tvstudy/api?op=");
					page.append(OP_RUN);

					if (null != target.source) {
						page.append('&');
						page.append(SearchAPI.KEY_USER_RECORD_ID);
						page.append('=');
						page.append(String.valueOf(target.source.userRecordID));
					} else {
						page.append('&');
						page.append(SearchAPI.KEY_EXT_DB_KEY);
						page.append('=');
						page.append(String.valueOf(target.record.extDb.dbKey));
						page.append('&');
						page.append(SearchAPI.KEY_EXT_RECORD_ID);
						page.append('=');
						page.append(target.record.extRecordID);
					}

					if (ixCheck.replicate) {
						page.append('&');
						page.append(KEY_REPLICATION_CHANNEL);
						page.append('=');
						page.append(String.valueOf(ixCheck.replicationChannel));
					}

					page.append('&');
					page.append(KEY_TEMPLATE_KEY);
					page.append('=');
					page.append(String.valueOf(ixCheck.templateKey ));

					page.append('&');
					page.append(KEY_STUDY_EXT_DB_KEY);
					page.append('=');
					page.append(String.valueOf(ixCheck.extDb.dbKey));

					page.append('&');
					page.append(KEY_OUTPUT_CONFIG);
					page.append('=');
					page.append(ixCheck.fileOutputConfig.getCodes());

					page.append('&');
					page.append(KEY_MAP_OUTPUT_CONFIG);
					page.append('=');
					page.append(ixCheck.mapOutputConfig.getCodes());

					if (null != ixCheck.cellSize) {
						page.append('&');
						page.append(KEY_CELL_SIZE);
						page.append('=');
						page.append(ixCheck.cellSize);
					}

					if (null != ixCheck.profilePpk) {
						page.append('&');
						page.append(KEY_PROFILE_PPK);
						page.append('=');
						page.append(ixCheck.profilePpk);
					}

					if (ixCheck.protectPreBaseline) {
						page.append('&');
						page.append(KEY_PROTECT_NON_BL);
						page.append("=true");
					}

					if (ixCheck.protectBaselineFromLPTV) {
						page.append('&');
						page.append(KEY_LPTV_PROTECT_BL);
						page.append("=true");
					}

					if (ixCheck.protectLPTVFromClassA) {
						page.append('&');
						page.append(KEY_CLASS_A_PROTECT_LPTV);
						page.append("=true");
					}

					page.append('&');
					page.append(KEY_INCLUDE_FOREIGN);
					page.append('=');
					page.append(String.valueOf(ixCheck.includeForeign));

					if (null != ixCheck.includeUserRecords) {
						page.append('&');
						page.append(KEY_INCLUDE_USERIDS);
						char sep = '=';
						for (Integer uid : ixCheck.includeUserRecords) {
							page.append(sep);
							page.append(String.valueOf(uid));
							sep = '+';
						}
					}

					page.append('&');
					page.append(KEY_CP_EXCLUDES_BASELINE);
					page.append('=');
					page.append(String.valueOf(ixCheck.cpExcludesBaseline));

					page.append('&');
					page.append(KEY_EXCLUDE_APPS);
					page.append('=');
					page.append(String.valueOf(ixCheck.excludeApps));

					page.append('&');
					page.append(KEY_EXCLUDE_PENDING);
					page.append('=');
					page.append(String.valueOf(ixCheck.excludePending));

					page.append('&');
					page.append(KEY_EXCLUDE_POST_TRANSITION);
					page.append('=');
					page.append(String.valueOf(ixCheck.excludePostTransition));

					if (null != ixCheck.excludeCommands) {
						page.append('&');
						page.append(KEY_EXCLUDE_ARNS);
						char sep = '=';
						for (String arn : ixCheck.excludeCommands) {
							page.append(sep);
							try {page.append(URLEncoder.encode(arn, "UTF-8"));} catch (Throwable t) {};
							sep = '+';
						}
					}

					if (null != ixCheck.filingCutoffDate) {
						page.append('&');
						page.append(KEY_FILING_CUTOFF);
						page.append('=');
						page.append(AppCore.formatDate(ixCheck.filingCutoffDate));
					}

					page.append('&');
					page.append(KEY_RERUN);
					page.append("=true");

					page.append("\">Re-run study now</a><br>\n");

					addPageFooter(page);

					resultPage = page.toString();
					status = STATUS_PAGE;
				}

				return;
			}
		}

		// No past runs or rerun flag set, run the study.  First call willRunStudy() to register the new run in the
		// cache.  Then write an initial in-progress index so that file always exists, the return from the immediate
		// operation will always be a redirect to that file.  Start runStudy() on a secondary thread, when the run
		// completes write a final results index file.  Calls to reportStatus() will update the in-progress index.

		if (!ixCheck.willRunStudy(errors)) {
			handleError(errors.toString(), OP_SETUP);
			return;
		}

		try {
			writeInProgressIndex(ixCheck.getOutDirectoryPath(), ixCheck.getStudyDescription(),
				"Study queued, waiting to start...");
		} catch (IOException ie) {
		}

		new Thread() {
			public void run() {

				String message = "Study complete";
				errors.clearErrors();
				if (!ixCheck.runStudy(outerThis, errors)) {
					if (errors.hasErrors()) {
						message = errors.toString();
					} else {
						message = "Errors occurred during the study run, see log file for details";
					}
				}

				try {
					writeFileIndex(ixCheck.getOutDirectoryPath(), ixCheck.getStudyDescription(), message,
						ixCheck.getStudyReport(), ixCheck.getOutputFiles());
				} catch (IOException e) {
				}
			}
		}.start();

		resultURL = ixCheck.getIndexURLPath();
		status = STATUS_URL;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The build object will call this to report status during the build and run, update the in-progress index.

	public synchronized void reportStatus(String message) {

		try {
			writeInProgressIndex(ixCheck.getOutDirectoryPath(), ixCheck.getStudyDescription(), message);
		} catch (IOException ie) {
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Log messages should not be sent here, the build object writes directly to the log file.

	public void logMessage(String message) {
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void showMessage(String message) {
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Cache operations.  If KEY_ACTION is set run the action and show a confirmation page.  Otherwise show a cache
	// status report and forms with the action buttons.  The cache methods in StudyBuildIxCheck do most of the work.

	private void doOpCache() {

		StringBuilder page = new StringBuilder();

		addPageHeader(page, "TVStudy Interference Check Cache Maintenance", 0, errorMessage);

		String value = parameters.get(KEY_ACTION);
		if (null != value) {

			addFormStart(page, OP_CACHE);

			if (ACTION_DELETE.equals(value)) {

				int days = 60;
				value = parameters.get(KEY_DAYS);
				if (null != value) {
					try {
						int i = Integer.parseInt(value);
						if (i >= 0) {
							days = i;
						}
					} catch (NumberFormatException ne) {
					}
				}
				StudyBuildIxCheck.cacheDelete(dbID, outPath, days, page);

			} else {

				if (ACTION_CLEANUP.equals(value)) {

					StudyBuildIxCheck.cacheCleanup(dbID, outPath, page);

				} else {

					page.append("<br><b>ERROR: Unknown cache maintenance action '" + value + "'</b><br><br>\n");
				}
			}

			addFormEnd(page, "OK");

		} else {

			StudyBuildIxCheck.cacheReport(dbID, outPath, page);

			addFormStart(page, OP_CACHE);
			page.append("<input type=\"hidden\" name=\"" + KEY_ACTION + "\" value=\"" + ACTION_DELETE + "\">\n");
			page.append("Delete study output older than&nbsp;\n");
			page.append("<input type=\"text\" name=\"" + KEY_DAYS + "\" size=\"8\" value=\"60\">\n");
			page.append("&nbsp;days.<br>\n");
			addFormEnd(page, "Delete");

			addFormStart(page, OP_CACHE);
			page.append("<input type=\"hidden\" name=\"" + KEY_ACTION + "\" value=\"" + ACTION_CLEANUP + "\">\n");
			page.append("Cache cleanup repairs the cache index and removes inaccessible output.<br>\n");
			page.append("A cleanup may be needed if 'Page not found' errors are appearing.<br>\n");
			addFormEnd(page, "Cleanup");
		}

		addPageFooter(page);

		resultPage = page.toString();
		status = STATUS_PAGE;
	}
}
