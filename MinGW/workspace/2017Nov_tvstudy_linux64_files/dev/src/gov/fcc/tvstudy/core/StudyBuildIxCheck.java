//
//  StudyBuildIxCheck.java
//  TVStudy
//
//  Copyright (c) 2015-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core;

import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.editdata.*;
import gov.fcc.tvstudy.core.geo.*;

import java.util.*;
import java.sql.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.text.*;


//=====================================================================================================================
// Class to build and run TV interference-check studies, used from both GUI context and API, see IxCheckAPI.  This is
// initialized with either an ExtDbRecordTV or SourceEditDataTV object, then runStudy() or buildStudy() is called.
// This is also a StudyBuild subclass for use by StudyRunPanel in the GUI.

public class StudyBuildIxCheck extends StudyBuild {

	// Minimum LMS version for the build station data, must always be LMS.

	public static final int MIN_LMS_VERSION = 6;

	// Name to match to find defaults for templates, data sets, and output configurations.

	public static final String DEFAULT_CONFIG_NAME = "Interference Check";

	// Prefix for auto-generated study names, see willRunStudy().

	public static final String STUDY_NAME_PREFIX = "IxCheck";

	// Default file name for study run log.

	public static final String LOG_FILE_NAME = "log.txt";

	// List of cell sizes for use in UI.  The underlying parameters and arguments can actually have any value in legal
	// range but both the GUI and API present the parameter as a menu for choosing among these discrete values.

	public static final double[] CELL_SIZES = {2., 1., 0.5};

	// To configure an instance, public properties are set directly then initialize() must be called.  See superclass
	// properties for the proposal source/record object.  The study engine output path may be provided, otherwise a
	// default is used.  The index file name is set for servlet requests so getIndexURLPath() can return the URL once
	// the output path is known.  The study engine log file may be specified, if null the usual default is used.  All
	// three are typically set for servlet operations but left null for GUI operations.

	public String outPath;
	public String indexFileName;
	public String logFileName;

	// Template, data set, and output configuration.

	public int templateKey;
	public ExtDb extDb;
	public OutputConfig fileOutputConfig;
	public OutputConfig mapOutputConfig;

	// Values for cell size and profile resolution, if provided the values are set in the study parameters once the
	// study is created.  If null, default values are used.

	public String cellSize;
	public String profilePpk;

	// Default values for cell size and resolution vary between services.  These are not inputs, they come from the
	// app configuration and are applied to the input values as needed.

	public String defaultCellSize;
	public String defaultProfilePpk;
	public String defaultCellSizeLPTV;
	public String defaultProfilePpkLPTV;

	// AM station search distances, also from configuration.

	public double amSearchDistanceND;
	public double amSearchDistanceDA;

	// The buildFullStudy flag means a full study build and possibly auto-run will be performed.  If false a new study
	// will be created with the proposal record(s) and scenario, but with no protected station records or interference
	// scenarios.  The user can then manually create those scenarios, see GUI classes and buildFromScenario().

	public boolean buildFullStudy = true;

	// The proposal "before" record, typically a baseline record, may be set explicitly.  If the didSetBefore flag is
	// false these are ignored and the "before" will be set automatically, see makeDesiredList().  Otherwise the source
	// or record set will be used.  Those may be null along with didSetBefore true, which means there is no "before". 

	public SourceEditDataTV beforeSource;
	public ExtDbRecordTV beforeRecord;
	public boolean didSetBefore;

	// Scenario-building configuration.  The ARN exclusion list may be null or empty.  Otherwise the strings provided
	// are directly matched to ARNs in records from search results, case-insensitive but otherwise literal.  A match
	// excludes the record.  There is no error-checking, if the ARNs do not exist or if the text is malformed it is
	// harmless because there will be no match.  The user record inclusion list is similar, but it causes the listed
	// user records to be included in any record search done during the study build.  Invalid IDs are ignored.

	public boolean protectPreBaseline;
	public boolean protectBaselineFromLPTV;
	public boolean protectLPTVFromClassA;

	public boolean includeForeign;
	public TreeSet<Integer> includeUserRecords;
	public boolean cpExcludesBaseline;
	public boolean excludeApps;
	public boolean excludePending;
	public boolean excludePostTransition;
	public TreeSet<String> excludeCommands;

	private HashSet<String> excludeARNs;
	private HashSet<Integer> excludeFacilityIDs;
	private HashSet<String> excludeCallSigns;

	public java.util.Date baselineDate;
	public java.util.Date filingWindowEndDate;

	public java.util.Date filingCutoffDate;

	private StringBuilder studyReport;

	private String outDirectoryPath;
	private ArrayList<String> outputFiles;

	// The mutable study model, created in buildStudy().

	private StudyEditData study;
	private ArrayList<IxRule> rules;

	// State for the scenario-generation process, see buildStudyScenarios().

	private SourceEditDataTV proposalSource;
	private SourceEditDataTV proposalBeforeSource;
	private ScenarioEditData proposalScenario;

	private ArrayList<SourceEditData> userRecordSources;

	private class Protected {
		private SourceEditDataTV source;
		private boolean receivesIX;
		private ArrayList<Undesired> undesiredList;
		private ScenarioEditData buildScenario;
		private HashMap<Integer, Undesired> undesiredMap;
	}

	private static class Undesired {
		private SourceEditDataTV source;
		private boolean causesIX;
		private ArrayList<Integer> excludes;
	}

	private ArrayList<Protected> protectedList;

	private HashSet<Integer> baselineExcludedIndex;

	private ExtDbRecordTV.BaselineIndex baselineIndex;

	private HashMap<Integer, ArrayList<ExtDbRecordTV>> searchCache;
	private HashMap<Integer, ArrayList<ExtDbRecordTV>> baselineCache;

	private TreeMap<String, ExtDbRecordTV> excludedRecords;
	private TreeMap<Integer, SourceEditDataTV> includedSources;

	private class IXProbeResult {
		private Integer desiredSourceKey;
		private Integer undesiredSourceKey;
		private boolean causesIX;
	}

	private ScenarioBuilder builder;

	// Run status properties updated in runStudy(), interval for updating status with callback.

	private int runStatusTotal;
	private int runStatusDone;
	private static final long STATUS_UPDATE_INTERVAL = 2950L;   // milliseconds
	private long lastStatusUpdate;


	//-----------------------------------------------------------------------------------------------------------------

	public StudyBuildIxCheck(String theDbID) {

		super(theDbID);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Load default properties.  The default template, station data, and output configuration all first attempt to
	// find an entry with a fixed name.  If the named template does not exist or is not properly locked, the default
	// template is used.  If the named data set is not found, use the LMS live server if available, else use the most-
	// recent TV set.  If the named output config is not found use the last-used config, that method will return a
	// fixed default if there is no last-used state.  Ignore all errors.  This can only be used before initialize().

	public void loadDefaults() {

		if (initialized) {
			return;
		}

		templateKey = Template.getTemplateKeyForName(dbID, DEFAULT_CONFIG_NAME);
		if (templateKey > 0) {
			Template.Info theInfo = Template.getTemplateInfo(dbID, templateKey);
			if ((null == theInfo) || !theInfo.isLocked || theInfo.isLockedInStudy) {
				templateKey = 1;
			}
		} else {
			templateKey = 1;
		}

		extDb = ExtDb.getExtDb(dbID, DEFAULT_CONFIG_NAME);
		if ((null == extDb) || (Source.RECORD_TYPE_TV != extDb.recordType) || (ExtDb.DB_TYPE_LMS != extDb.type) ||
				(extDb.version < MIN_LMS_VERSION)) {
			extDb = ExtDb.getExtDb(dbID, Integer.valueOf(ExtDb.KEY_LMS_LIVE));
			if (null == extDb) {
				extDb = ExtDb.getExtDb(dbID, Integer.valueOf(ExtDb.KEY_MOST_RECENT_LMS));
				if ((null != extDb) && (extDb.version < MIN_LMS_VERSION)) {
					extDb = null;
				}
			}
		}

		fileOutputConfig = OutputConfig.getConfig(dbID, OutputConfig.CONFIG_TYPE_FILE, DEFAULT_CONFIG_NAME);
		if (null == fileOutputConfig) {
			fileOutputConfig = OutputConfig.getLastUsed(dbID, OutputConfig.CONFIG_TYPE_FILE);
		}
		mapOutputConfig = OutputConfig.getConfig(dbID, OutputConfig.CONFIG_TYPE_MAP, DEFAULT_CONFIG_NAME);
		if (null == mapOutputConfig) {
			mapOutputConfig = OutputConfig.getLastUsed(dbID, OutputConfig.CONFIG_TYPE_MAP);
		}

		defaultCellSize = AppCore.getConfig(AppCore.CONFIG_TVIX_DEFAULT_CELL_SIZE);
		defaultProfilePpk = AppCore.getConfig(AppCore.CONFIG_TVIX_DEFAULT_PROFILE_RESOLUTION);
		defaultCellSizeLPTV = AppCore.getConfig(AppCore.CONFIG_TVIX_DEFAULT_CELL_SIZE_LPTV);
		defaultProfilePpkLPTV = AppCore.getConfig(AppCore.CONFIG_TVIX_DEFAULT_PROFILE_RESOLUTION);

		String str = AppCore.getConfig(AppCore.CONFIG_TVIX_AM_SEARCH_DISTANCE_ND);
		if ((null != str) && (str.length() > 0)) {
			amSearchDistanceND = 0.;
			try {
				amSearchDistanceND = Double.parseDouble(str);
			} catch (NumberFormatException nfe) {
			}
		}
		str = AppCore.getConfig(AppCore.CONFIG_TVIX_AM_SEARCH_DISTANCE_DA);
		if ((null != str) && (str.length() > 0)) {
			amSearchDistanceDA = 0.;
			try {
				amSearchDistanceDA = Double.parseDouble(str);
			} catch (NumberFormatException nfe) {
			}
		}

		str = AppCore.getConfig(AppCore.CONFIG_LMS_BASELINE_DATE);
		if ((null != str) && (str.length() > 0)) {
			baselineDate = AppCore.parseDate(str);
		}

		str = AppCore.getConfig(AppCore.CONFIG_TVIX_WINDOW_END_DATE);
		if ((null != str) && (str.length() > 0)) {
			filingWindowEndDate = AppCore.parseDate(str);
		}

		str = AppCore.getPreference(AppCore.CONFIG_TVIX_INCLUDE_FOREIGN_DEFAULT);
		includeForeign = ((null != str) && Boolean.valueOf(str).booleanValue());

		str = AppCore.getPreference(AppCore.PREF_TVIX_DEFAULT_CP_EXCLUDES_BL);
		cpExcludesBaseline = ((null != str) && Boolean.valueOf(str).booleanValue());
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Make an un-initialized copy of this object.  The name must be unique so that is not copied.

	public StudyBuildIxCheck copy() {

		StudyBuildIxCheck newBuild = new StudyBuildIxCheck(dbID);

		newBuild.studyDescription = studyDescription;

		newBuild.source = source;
		newBuild.record = record;

		newBuild.replicate = replicate;
		newBuild.replicationChannel = replicationChannel;

		newBuild.outPath = outPath;
		newBuild.indexFileName = indexFileName;
		newBuild.logFileName = logFileName;

		newBuild.templateKey = templateKey;
		newBuild.extDb = extDb;
		newBuild.fileOutputConfig = fileOutputConfig;
		newBuild.mapOutputConfig = mapOutputConfig;

		newBuild.cellSize = cellSize;
		newBuild.profilePpk = profilePpk;

		newBuild.defaultCellSize = defaultCellSize;
		newBuild.defaultProfilePpk = defaultProfilePpk;
		newBuild.defaultCellSizeLPTV = defaultCellSizeLPTV;
		newBuild.defaultProfilePpkLPTV = defaultProfilePpkLPTV;

		newBuild.amSearchDistanceND = amSearchDistanceND;
		newBuild.amSearchDistanceDA = amSearchDistanceDA;

		newBuild.protectPreBaseline = protectPreBaseline;
		newBuild.protectBaselineFromLPTV = protectBaselineFromLPTV;
		newBuild.protectLPTVFromClassA = protectLPTVFromClassA;

		newBuild.includeForeign = includeForeign;
		if (null != includeUserRecords) {
			newBuild.includeUserRecords = new TreeSet<Integer>(includeUserRecords);
		}
		newBuild.cpExcludesBaseline = cpExcludesBaseline;
		newBuild.excludeApps = excludeApps;
		newBuild.excludePending = excludePending;
		newBuild.excludePostTransition = excludePostTransition;
		if (null != excludeCommands) {
			newBuild.excludeCommands = new TreeSet<String>(excludeCommands);
		}

		newBuild.baselineDate = baselineDate;
		newBuild.filingWindowEndDate = filingWindowEndDate;

		newBuild.filingCutoffDate = filingCutoffDate;

		return newBuild;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Initialize.  This must be called after properties are set but before any of the run or build methods, if this
	// fails all other behavior is undefined and the object should be discarded.  This can only be effective once, any
	// repeat call just immediately returns true.  These are one-shot objects.

	public boolean initialize(ErrorLogger errors) {

		if (initialized) {
			return true;
		}

		if (!super.initialize(errors)) {
			return false;
		}

		// Check configuration settings.

		Template.Info theInfo = null;
		if (templateKey > 0) {
			theInfo = Template.getTemplateInfo(dbID, templateKey);
		}
		if ((null == theInfo) || !theInfo.isLocked || theInfo.isLockedInStudy) {
			if (null != errors) {
				errors.reportError("Cannot build study, missing or invalid template selection.");
			}
			return false;
		}

		if ((null == extDb) || extDb.deleted || ((ExtDb.DB_TYPE_LMS != extDb.type) &&
				(ExtDb.DB_TYPE_LMS_LIVE != extDb.type)) || (extDb.version < MIN_LMS_VERSION)) {
			if (null != errors) {
				errors.reportError("Cannot build study, missing or invalid station data selection.");
			}
			return false;
		}

		// Check parameter values, remove empty strings, set defaults if no input value set.  Clean up format for ID
		// use, see willRunStudy().

		boolean isLPTV;
		if (null != source) {
			isLPTV = source.service.isLPTV();
		} else {
			isLPTV = record.service.isLPTV();
		}

		if (null != cellSize) {
			cellSize = cellSize.trim();
			if (0 == cellSize.length()) {
				cellSize = null;
			}
		}
		if (null == cellSize) {
			if (isLPTV) {
				cellSize = defaultCellSizeLPTV;
			} else {
				cellSize = defaultCellSize;
			}
		}
		if (null != cellSize) {
			try {
				double d = Double.parseDouble(cellSize);
				cellSize = AppCore.formatDecimal(d, 2);
			} catch (NumberFormatException ne) {
				cellSize = null;
			}
		}

		if (null != profilePpk) {
			profilePpk = profilePpk.trim();
			if (0 == profilePpk.length()) {
				profilePpk = null;
			}
		}
		if (null == profilePpk) {
			if (isLPTV) {
				profilePpk = defaultProfilePpkLPTV;
			} else {
				profilePpk = defaultProfilePpk;
			}
		}
		if (null != profilePpk) {
			try {
				double d = Double.parseDouble(profilePpk);
				profilePpk = AppCore.formatDecimal(d, 1);
			} catch (NumberFormatException ne) {
				profilePpk = null;
			}
		}

		// If buildFullStudy is false a new study is being created with just the minimal structure providing the
		// proposal record(s) and coverage scenario.  In that case many initialization steps are skipped; output
		// configuration does not have to be set, and all of the auto-build logic settings are irrelevant.

		if (buildFullStudy) {

			if ((null == fileOutputConfig) || !fileOutputConfig.isValid()) {
				if (null != errors) {
					errors.reportError("Cannot build study, missing or invalid output file settings.");
				}
				return false;
			}
			if ((null == mapOutputConfig) || !mapOutputConfig.isValid()) {
				if (null != errors) {
					errors.reportError("Cannot build study, missing or invalid map output settings.");
				}
				return false;
			}

			// Make sure the file output flag for the pair study custom cell file is not set, and the settings file is.

			fileOutputConfig.flags[OutputConfig.CELL_FILE_PAIRSTUDY] = 0;
			fileOutputConfig.flags[OutputConfig.SETTING_FILE] = 1;

			// If the study record is Class A and it's sequence date is after the filingWindowEndDate, LPTV is always
			// protected, force that flag true.  Otherwise it's a user-selected option.

			if (null != filingWindowEndDate) {
				java.util.Date seqDate;
				boolean isClassA;
				if (null != source) {
					seqDate = AppCore.parseDate(source.getAttribute(Source.ATTR_SEQUENCE_DATE));
					if (null == seqDate) {
						seqDate = new java.util.Date();
					}
					isClassA = source.service.isClassA();
				} else {
					seqDate = record.sequenceDate;
					isClassA = record.service.isClassA();
				}
				if (isClassA && seqDate.after(filingWindowEndDate)) {
					protectLPTVFromClassA = true;
				}
			}

			// If the exclusion command list is empty make it null, otherwise parse it out into separate ARN, facility
			// ID, and call sign exclusion lists.  Originally this was just a list of ARNs so the default is to
			// interpret each command as a literal ARN to match.  Actual commands start with a '$' prefix character,
			// an ARN can also be provided that way with "$arn=X", also facility IDs with "$facilityid=X", or call
			// signs with "$callsign=X".  ARN and call signs are case-insensitive exact matches.

			if (null != excludeCommands) {

				if (excludeCommands.isEmpty()) {
					excludeCommands = null;
				} else {

					String cmd, arg;
					int e, fid;
					boolean bad = false;

					for (String excl : excludeCommands) {

						if (excl.startsWith("$")) {
							e = excl.indexOf('=');
							if (e > 0) {
								cmd = excl.substring(1, e).trim().toLowerCase();
								arg = excl.substring(e + 1).trim();
							} else {
								cmd = excl.trim().toLowerCase();
								arg = "";
							}
						} else {
							cmd = "arn";
							arg = excl.trim();
							if (0 == arg.length()) {
								continue;
							}
						}

						if (cmd.equals("arn")) {
							if (arg.length() > 0) {
								if (null == excludeARNs) {
									excludeARNs = new HashSet<String>();
								}
								excludeARNs.add(arg.toUpperCase());
							} else {
								bad = true;
							}
						} else {
							if (cmd.equals("facilityid")) {
								fid = 0;
								try {
									fid = Integer.parseInt(arg);
								} catch (NumberFormatException ne) {
								}
								if (fid > 0) {
									if (null == excludeFacilityIDs) {
										excludeFacilityIDs = new HashSet<Integer>();
									}
									excludeFacilityIDs.add(Integer.valueOf(fid));
								} else {
									bad = true;
								}
							} else {
								if (cmd.equals("callsign")) {
									if (arg.length() > 0) {
										if (null == excludeCallSigns) {
											excludeCallSigns = new HashSet<String>();
										}
										excludeCallSigns.add(arg.toUpperCase());
									} else {
										bad = true;
									}
								} else {
									bad = true;
								}
							}
						}

						if (bad) {
							if (null != errors) {
								errors.reportError("Cannot build study, bad command '" + excl + "' in exclusion list.");
							}
							return false;
						}
					}
				}
			}

			if ((null != includeUserRecords) && includeUserRecords.isEmpty()) {
				includeUserRecords = null;
			}

			// Other misc. setup.

			if (null != outPath) {
				outPath = outPath.trim();
				if (0 == outPath.length()) {
					outPath = null;
				}
			}
			if (null == outPath) {
				outPath = AppCore.OUT_DIRECTORY_NAME;
			}

			if (null != indexFileName) {
				indexFileName = indexFileName.trim();
				if (0 == indexFileName.length()) {
					indexFileName = null;
				}
			}

			if (null != logFileName) {
				logFileName = logFileName.trim();
				if (0 == logFileName.length()) {
					logFileName = null;
				}
			}
			if (null == logFileName) {
				logFileName = LOG_FILE_NAME;
			}

			outputFiles = new ArrayList<String>();

			baselineExcludedIndex = new HashSet<Integer>();

			searchCache = new HashMap<Integer, ArrayList<ExtDbRecordTV>>();
			baselineCache = new HashMap<Integer, ArrayList<ExtDbRecordTV>>();
		}

		// Create a study description and initial build report.  The report text collects information during the study
		// setup and build process, and later may collect report lines written out by the study engine if runStudy() is
		// used.  All of this also goes to the report output file created by the engine; the initial portion built here
		// is saved in the study database table so the engine can copy it.

		studyReport = new StringBuilder();

		makeDescription(source, record, didSetBefore, beforeSource, beforeRecord, studyReport, this);

		// For servlet requests a name will be generated in runStudy(); since the study database is temporary in that
		// case the name is arbitrary.  In any other situation the name must be set, including when buildFullStudy is
		// false.  But that is not checked here; if it is not set buildStudy() will fail.

		if (null != studyName) {
			studyName = studyName.trim();
			if (0 == studyName.length()) {
				studyName = null;
			}
		}

		if (null != studyName) {
			updateOutDirectoryPath(false);
		}

		if (null != studyDescription) {
			studyDescription = studyDescription.trim();
			if (0 == studyDescription.length()) {
				studyDescription = null;
			}
		}

		initialized = true;

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void abort() {

		super.abort();

		if (null != builder) {
			builder.abort();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update the output directory path once the study name is set.  From a web API context, the database ID is used
	// in the path rather than the host and database names.  That ensures the output file directory, which is a cache
	// for web runs, always has a unique unambiguous name.

	private void updateOutDirectoryPath(boolean useDbID) {

		if ((null != outPath) && (null != studyName)) {
			outDirectoryPath = outPath + File.separator + (useDbID ? dbID : DbCore.getHostDbName(dbID)) +
				File.separator + studyName;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Make description and initial report of the selected record parmeters.  This is static so it can also be used
	// from makeReportPreamble(), used when saving an existing study after edits, see below.

	private static void makeDescription(SourceEditData propSource, ExtDbRecord propRecord, boolean showBefore,
			SourceEditDataTV propBefSource, ExtDbRecordTV propBefRecord, StringBuilder theReport,
			StudyBuildIxCheck theBuild) {

		Record theRecord = ((null != propSource) ? propSource : propRecord);

		StringBuilder recSum = new StringBuilder();
		recSum.append(theRecord.getCallSign());
		recSum.append(' ');
		if (null != theBuild) {
			if (theBuild.replicate) {
				recSum.append('D');
				recSum.append(String.valueOf(theBuild.replicationChannel));
				recSum.append(" (");
				recSum.append(theRecord.getOriginalChannel());
				recSum.append(')');
			} else {
				recSum.append(theRecord.getOriginalChannel());
			}
		} else {
			recSum.append(theRecord.getChannel());
		}
		recSum.append(' ');
		recSum.append(theRecord.getServiceCode());
		recSum.append(' ');
		String str = theRecord.getStatus();
		if (str.length() > 0) {
			recSum.append(str);
			recSum.append(' ');
		}
		recSum.append(theRecord.getCity());
		recSum.append(", ");
		recSum.append(theRecord.getState());

		if (null != theBuild) {

			if (null == theBuild.studyDescription) {
				theBuild.studyDescription = "Interference check study for " + theRecord.getFileNumber() + " " +
					recSum.toString();
			}

			theReport.append("Study created: ");
			theReport.append((new SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.US)).format(new java.util.Date()));
			theReport.append("\n\n");

			theReport.append("Study build station data: ");
			theReport.append(ExtDb.getExtDbDescription(theBuild.dbID, theBuild.extDb.dbKey));
			theReport.append("\n\n");

		} else {

			theReport.append("Study last edited: ");
			theReport.append((new SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.US)).format(new java.util.Date()));
			theReport.append("\n\n");
		}

		theReport.append("    Proposal: ");
		theReport.append(recSum.toString());
		theReport.append('\n');

		str = theRecord.getFileNumber();
		if (str.length() > 0) {
			theReport.append(" File number: ");
			theReport.append(str);
			theReport.append('\n');
		}

		theReport.append(" Facility ID: ");
		theReport.append(theRecord.getFacilityID());
		theReport.append('\n');

		if (theRecord.hasRecordID()) {

			theReport.append("Station data: ");
			theReport.append(theRecord.getStationData());
			theReport.append('\n');

			theReport.append("   Record ID: ");
			theReport.append(theRecord.getRecordID());
			theReport.append('\n');
		}

		theReport.append("     Country: ");
		theReport.append(theRecord.getCountry());
		theReport.append('\n');

		str = theRecord.getZone();
		if (str.length() > 0) {
			theReport.append("        Zone: ");
			theReport.append(str);
			theReport.append('\n');
		}

		if (theRecord.isDTS()) {

			theReport.append("   Ref. lat.: ");
			theReport.append(theRecord.getLatitude());
			theReport.append('\n');

			theReport.append("  Ref. long.: ");
			theReport.append(theRecord.getLongitude());
			theReport.append('\n');

			theReport.append(" # DTS sites: ");
			theReport.append(theRecord.getSiteCount());
			theReport.append('\n');
		}

		theReport.append('\n');

		if (showBefore) {

			theRecord = ((null != propBefSource) ? propBefSource : propBefRecord);

			if (null == theRecord) {

				theReport.append("Proposal \"before\": (none)\n");

			} else {

				theReport.append("Proposal \"before\": ");
				theReport.append(theRecord.getCallSign());
				theReport.append(' ');
				theReport.append(theRecord.getChannel());
				theReport.append(' ');
				theReport.append(theRecord.getServiceCode());
				theReport.append(' ');
				str = theRecord.getStatus();
				if (str.length() > 0) {
					theReport.append(str);
					theReport.append(' ');
				}
				theReport.append(theRecord.getCity());
				theReport.append(", ");
				theReport.append(theRecord.getState());
				theReport.append('\n');

				str = theRecord.getFileNumber();
				if (str.length() > 0) {
					theReport.append("      File number: ");
					theReport.append(str);
					theReport.append('\n');
				}

				theReport.append("      Facility ID: ");
				theReport.append(theRecord.getFacilityID());
				theReport.append('\n');

				if (theRecord.hasRecordID()) {

					theReport.append("     Station data: ");
					theReport.append(theRecord.getStationData());
					theReport.append('\n');

					theReport.append("        Record ID: ");
					theReport.append(theRecord.getRecordID());
					theReport.append('\n');
				}

				theReport.append("          Country: ");
				theReport.append(theRecord.getCountry());
				theReport.append('\n');

				if ((null != propBefSource) && !propBefSource.isLocked) {
					theReport.append("(record may have been modified)\n");
				}
			}

			theReport.append('\n');
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// These methods are used by the web API, results may be undefined unless runStudy() is being used.

	public String getStudyDescription() {

		return studyDescription;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getOutDirectoryPath() {

		return outDirectoryPath;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getIndexURLPath() {

		return makeIndexURLPath(studyName);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public ArrayList<String> getOutputFiles() {

		return outputFiles;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getStudyReport() {

		if (null != studyReport) {
			return studyReport.toString();
		}
		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a list of past runs of this study made through runStudy().  Such runs are tracked in a status table in the
	// database using an ID string based on the study settings, see makeStudyID().  There may be one or more past run
	// outputs kept in the output cache and listed in the status.  Returns null on error, returns an empty list if
	// there are no past runs listed in the status, otherwise the list has past runs in order of date.  Note this also
	// has a side-effect for convenience.  If past runs are found the current study name is set to the name of the most
	// recent study, so the caller may use e.g. getIndexURLPath() to get state related to that study.

	public static class RunIndex {
		public String indexURLPath;
		public java.util.Date runDate;
	}

	public ArrayList<RunIndex> getPastRuns() {
		return getPastRuns(null);
	}

	public ArrayList<RunIndex> getPastRuns(ErrorLogger errors) {

		if (!initialized) {
			if (null != errors) {
				errors.reportError("Study build has not been initialized.");
			}
			return null;
		}

		String studyID = makeStudyID();
		if (null == studyID) {
			if (null != errors) {
				errors.reportError("Cannot run study, unable to form unique study ID.");
			}
			return null;
		}

		ArrayList<RunIndex> result = null;

		DbConnection db = DbCore.connectDb(dbID, errors);
		if (null != db) {
			try {

				result = new ArrayList<RunIndex>();

				String theName = null;
				RunIndex theRun;

				db.query("SELECT study_name, run_date FROM ix_check_status WHERE study_id = '" + db.clean(studyID) +
					"' ORDER BY run_date");

				while (db.next()) {
					theName = db.getString(1);
					theRun = new RunIndex();
					theRun.indexURLPath = makeIndexURLPath(theName);
					theRun.runDate = db.getTimestamp(2);
					result.add(theRun);
				}

				if (null != theName) {
					studyName = theName;
					updateOutDirectoryPath(true);
				}

				DbCore.releaseDb(db);

			} catch (SQLException se) {
				DbCore.releaseDb(db);
				result = null;
				DbConnection.reportError(errors, se);
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Make an index file URL path for a given study name, see getRunIndex().  This is only for servlet runs so the
	// path always used the database ID not the hostname.  Returns null if any strings needed are null.

	private String makeIndexURLPath(String theStudyName) {

		if ((null == outPath) || (null == theStudyName) || (null == indexFileName)) {
			return null;
		}
		return Paths.get(outPath).getFileName() + "/" + dbID + "/" + theStudyName + "/" + indexFileName;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// For study output to be tracked in the cache this method must be called before runStudy(), it sets the study
	// name, creates the output directory, and adds the status entry.  The studies managed in the cache are tracked by
	// the study ID and the study name, each new run has a unique name so that all past run outputs remain distinct.
	// See also getPastRuns().  Note runStudy() would still succeed if the study name were set by another method and
	// this were not called, but if the output is going to the servlet cache directory that output would subsequently
	// be deleted by a future cacheCleanup() since it is not in the index.  Returns false on error.

	public boolean willRunStudy() {
		return willRunStudy(null);
	}

	public boolean willRunStudy(ErrorLogger errors) {

		if (!initialized) {
			if (null != errors) {
				errors.reportError("Study build has not been initialized.");
			}
			return false;
		}

		String studyID = makeStudyID();
		if (null == studyID) {
			if (null != errors) {
				errors.reportError("Cannot run study, unable to form unique study ID.");
			}
			return false;
		}

		boolean error = false;
		String errmsg = "";

		DbConnection db = DbCore.connectDb(dbID, errors);
		if (null != db) {

			try {

				// Generate the new study name.

				db.update("LOCK TABLES ix_check_status WRITE, ix_check_name_sequence WRITE");

				db.update("UPDATE ix_check_name_sequence SET name_key = name_key + 1");
				db.query("SELECT name_key FROM ix_check_name_sequence");
				db.next();
				studyName = STUDY_NAME_PREFIX + db.getString(1);

				// Update the output path and create the output directory.

				updateOutDirectoryPath(true);

				try {
					Files.createDirectories(Paths.get(outDirectoryPath));
				} catch (IOException e) {
					error = true;
					errmsg = "Cannot create output directory: " + e;
				}

				// Insert the new status entry.

				if (!error) {
					db.update("INSERT INTO ix_check_status VALUES ('" + studyName + "', '" + db.clean(studyID) +
						"', NOW())");
				}

			} catch (SQLException se) {
				error = true;
				errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
				db.reportError(se);
			}

			try {
				db.update("UNLOCK TABLES");
			} catch (SQLException se) {
				db.reportError(se);
			}

			DbCore.releaseDb(db);

			if (error && (null != errors)) {
				errors.reportError(errmsg);
			}

		} else {
			error = true;
		}

		return !error;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Build the study ID string used to track servlet-requested runs managed by runStudy().  This is a concatenation
	// of all the identifying information and study settings for the run.  For a data set record, or a source based on
	// a data set record, the station data key and record ID identify the record.  For a source saved as a user record,
	// it is the user record ID.  If the proposal is neither a data set record nor a user record, it cannot be studied
	// by a web request since there is no way to form a persistent, unique identifier for the study output.

	private String makeStudyID() {

		if (!initialized) {
			return null;
		}

		StringBuilder theID = new StringBuilder();

		if (null != source) {
			if (null != source.userRecordID) {
				theID.append("U#I");
				theID.append(String.valueOf(source.userRecordID));
			} else {
				if ((null != source.extDbKey) && (null != source.extRecordID)) {
					theID.append(String.valueOf(source.extDbKey.intValue()));
					theID.append("#I");
					theID.append(source.extRecordID);
				} else {
					return null;
				}
			}
		} else {
			theID.append(String.valueOf(record.extDb.dbKey.intValue()));
			theID.append("#I");
			theID.append(record.extRecordID);
		}

		if (replicate) {
			theID.append("#C");
			theID.append(String.valueOf(replicationChannel));
		}

		theID.append("#T");
		theID.append(String.valueOf(templateKey));

		theID.append("#E");
		theID.append(String.valueOf(extDb.dbKey.intValue()));

		theID.append("#O");
		theID.append(fileOutputConfig.getCodes());

		theID.append("#M");
		theID.append(mapOutputConfig.getCodes());

		if (null != cellSize) {
			theID.append("#S");
			theID.append(cellSize);
		}

		if (null != profilePpk) {
			theID.append("#P");
			theID.append(profilePpk);
		}

		theID.append("#F");
		theID.append(excludeApps ? 'Y' : 'N');
		theID.append(excludePending ? 'Y' : 'N');
		theID.append(includeForeign ? 'Y' : 'N');
		theID.append(protectPreBaseline ? 'Y' : 'N');
		theID.append(protectBaselineFromLPTV ? 'Y' : 'N');
		theID.append(protectLPTVFromClassA ? 'Y' : 'N');
		theID.append(cpExcludesBaseline ? 'Y' : 'N');
		theID.append(excludePostTransition ? 'Y' : 'N');

		if (null != filingCutoffDate) {
			theID.append("#D");
			Calendar cal = Calendar.getInstance();
			cal.setTime(filingCutoffDate);
			theID.append(String.format("%d%02d%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH),
				cal.get(Calendar.DATE)));
		}

		if (null != excludeCommands) {
			theID.append("#X");
			String sep = "";
			for (String cmd : excludeCommands) {
				theID.append(sep);
				theID.append(cmd.toUpperCase());
				sep = ":";
			}
		}

		if (null != includeUserRecords) {
			theID.append("#A");
			String sep = "";
			for (Integer uid : includeUserRecords) {
				theID.append(sep);
				theID.append(String.valueOf(uid));
				sep = ":";
			}
		}

		return theID.toString();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Build and run the study, used for servlet requests.  When a study is built and run here, the study database is
	// deleted to conserve storage resources in a server environment; the status index applies to the existence of
	// output files from past studies, not the study databases.  This will not return until study build and engine run
	// are complete; that can take a while so this should always be called on a secondary thread.  Optionally the
	// caller may provide a status object to be informed of run progress.  Returns false on error.

	public boolean runStudy() {
		return runStudy(null, null);
	}

	public boolean runStudy(ErrorLogger errors) {
		return runStudy(null, errors);
	}

	public boolean runStudy(StatusLogger status) {
		return runStudy(status, null);
	}

	public boolean runStudy(StatusLogger status, ErrorLogger errors) {

		if (!initialized) {
			if (null != errors) {
				errors.reportError("Study build has not been initialized.");
			}
			return false;
		}

		if ((null == studyName) || (null == outDirectoryPath)) {
			if (null != errors) {
				errors.reportError("Study build failed, study name is not set.");
			}
			return false;
		}

		// Use the task queue to manage how many studies may be running simultaneously, using the max process count
		// limited by CPU cores and memory, see AppCore.initialize().  Wait here until the task queue allows this to
		// start, as mentioned this is assumed to be running on a background thread so blocking is not a concern.
		// If the maxEngineProcessCount is less than 1 either there is not enough memory for even one engine process,
		// or the engine executable is non-functional.  In those cases earlier checks should mean this is never even
		// reached, but do a check anyway to be safe.

		if (AppCore.maxEngineProcessCount < 1) {
			if (null != errors) {
				errors.reportError("Study run failed, study engine unavailable.");
			}
			return false;
		}
		AppTask task = new AppTask(1. / (double)(AppCore.maxEngineProcessCount));
		while (!AppTask.canTaskStart(task)) {
			try {Thread.currentThread().sleep(500);} catch (Exception e) {};
		}

		// Create a log file.  Logging of errors and other messages from the study build goes to this.  Later, the full
		// study run engine process will append directly to the file.

		ErrorLogger theLog = null;
		try {
	 		theLog = new ErrorLogger(new PrintStream(new FileOutputStream(outDirectoryPath + File.separator +
				logFileName, true), true), null);
		} catch (IOException e) {
			if (null != errors) {
				errors.reportError("Study build failed, cannot create log file:\n" + e);
			}
			AppTask.taskDone(task);
			return false;
		}

		// Object to dispatch status and log messages from the build method.  Status goes to the caller's status
		// logger (if any), log messages go to the file logger just created, transient log messages are ignored.  The
		// rate of status updates to the caller is limited, assuming some cost there i.e. writing a file.

		final ErrorLogger runLog = theLog;
		final StatusLogger statLog = status;

		StatusLogger buildLog = new StatusLogger() {

			public void reportStatus(String message) {
				if (null != statLog) {
					long now = System.currentTimeMillis();
					if ((now - lastStatusUpdate) > STATUS_UPDATE_INTERVAL) {
						statLog.reportStatus(message);
						lastStatusUpdate = now;
					}
				}
			}

			public void logMessage(String message) {
				runLog.reportMessage(message);
			}

			public void showMessage(String message) {
			}
		};

		// Build the study.  Merge message streams into the main log.

		Study theStudy = buildStudy(buildLog, runLog);

		if (runLog.hasMessages()) {
			runLog.reportMessage(runLog.getMessages());
			runLog.clearMessages();
		}

		runLog.closeStream();

		if (null == theStudy) {
			if (null != errors) {
				errors.reportError("Study build failed, see log file for details.");
			}
			AppTask.taskDone(task);
			return false;
		}

		// Change the study lock from edit to an exclusive run lock.

		boolean error = false;
		String errmsg = "";

		DbConnection db = DbCore.connectDb(dbID, errors);
		if (null != db) {
			try {

				db.update("LOCK TABLES study WRITE");

				db.query("SELECT study_lock, lock_count FROM study WHERE study_key = " + theStudy.key);

				if (db.next()) {

					if ((db.getInt(1) == theStudy.studyLock) && (db.getInt(2) == theStudy.lockCount)) {

						db.update("UPDATE study SET study_lock = " + Study.LOCK_RUN_EXCL +
							", lock_count = lock_count + 1, share_count = 0 WHERE study_key = " + theStudy.key);
						theStudy.studyLock = Study.LOCK_RUN_EXCL;
						theStudy.lockCount++;

					} else {
						error = true;
						errmsg = "Could not update study lock, the lock was modified.";
					}

				} else {
					error = true;
					errmsg = "Could not update study lock, the study was deleted.";
				}

			} catch (SQLException se) {
				error = true;
				errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
				db.reportError(se);
			}

			try {
				db.update("UNLOCK TABLES");
			} catch (SQLException se) {
				db.reportError(se);
			}

			DbCore.releaseDb(db);

			if (error && (null != errors)) {
				errors.reportError(errmsg);
			}

		} else {
			error = true;
		}

		// Build argument list, start the engine process.

		Process process = null;

		if (!error) {

			ArrayList<String> arguments = new ArrayList<String>();

			arguments.add(AppCore.libDirectoryPath + File.separator + AppCore.STUDY_ENGINE_NAME);

			if (AppCore.Debug) {
				arguments.add("-d");
			}

			arguments.add("-w");
			arguments.add(AppCore.workingDirectoryPath);
			arguments.add("-o");
			arguments.add(outPath);
			arguments.add("-s");
			arguments.add("-i");

			arguments.add("-g");
			arguments.add(outDirectoryPath + File.separator + logFileName);
			long zeroTime = getLogStartTime();
			if (zeroTime > 0L) {
				arguments.add("-t");
				arguments.add(String.valueOf(zeroTime));
			}

			arguments.add("-h");
			arguments.add(DbCore.getDbHostname(dbID));
			arguments.add("-b");
			arguments.add(DbCore.getDbName(dbID));
			arguments.add("-u");
			arguments.add(DbCore.getDbUsername(dbID));

			arguments.add("-l");
			arguments.add(String.valueOf(theStudy.lockCount));
			arguments.add("-k");

			arguments.add("-m");
			arguments.add(String.valueOf(AppCore.maxEngineProcessCount));

			arguments.add("-f");
			arguments.add("\""+fileOutputConfig.getCodes()+"\"");
			arguments.add("-e");
			arguments.add("\""+mapOutputConfig.getCodes()+"\"");

			arguments.add("\""+String.valueOf(theStudy.key)+"\"");

			try {
				ProcessBuilder pb = new ProcessBuilder(arguments);
				pb.redirectErrorStream(true);
				process = pb.start();
			} catch (Throwable t) {
				error = true;
				AppCore.log(AppCore.ERROR_MESSAGE, "Could not start process", t);
				if (null != errors) {
					errors.reportError("Could not start process:\n" + t.getMessage());
				}
			}
		}

		// Read from process output until the password prompt appears, then write the password.

		if (!error) {

			try {

				InputStream in = process.getInputStream();
				StringBuilder sbuf = new StringBuilder();
				byte[] buf = new byte[100];
				int nc;
				boolean wait = true;
				do {
					nc = in.read(buf);
					if (nc > 0) {
						sbuf.append(new String(buf, 0, nc));
						wait = !sbuf.toString().toLowerCase().contains("password");
					}
					if (nc < 0) {
						error = true;
						if (null != errors) {
							errors.reportError("Could not start study engine run.");
						}
						process.destroyForcibly();
						break;
					}
				} while (wait);

				if (!error) {
					OutputStream out = process.getOutputStream();
					out.write(DbCore.getDbPassword(dbID).getBytes());
					out.write("\n".getBytes());
					out.flush();
				}

			} catch (IOException ie) {
				error = true;
				if (null != errors) {
					errors.reportError("Could not start study engine run.");
				}
				process.destroyForcibly();
			}
		}

		// Read lines from process output, watch for and process message lines, if they provide output file names add
		// to the file list, if they provide report messages add to the report.  Other lines are sent to the caller's
		// error logger; most log messages from the engine are going directly to the log file, but it may still write
		// to stdout or stderr if Bad Things happen.  Skip the first line in case the password echoes.  Also process
		// run count messages and update the run status, if caller provided a status callback, run that periodically.

		if (!error) {

			BufferedReader processOutput = new BufferedReader(new InputStreamReader(process.getInputStream()));

			String line, key;
			boolean skipLine = true;
			int e, running = 0;
			long now;

			while (process.isAlive()) {

				try {
					line = processOutput.readLine();
				} catch (IOException ie) {
					line = null;
				}

				if (line != null) {

					if (skipLine) {
						skipLine = false;
						continue;
					}

					if (line.startsWith(AppCore.ENGINE_MESSAGE_PREFIX)) {

						e = line.indexOf('=');
						if (e > 0) {
							key = line.substring(AppCore.ENGINE_MESSAGE_PREFIX_LENGTH, e);
							if (key.equals(AppCore.ENGINE_FILE_KEY)) {
								outputFiles.add(line.substring(e + 1));
							} else {
								if (key.equals(AppCore.ENGINE_REPORT_KEY)) {
									studyReport.append(line.substring(e + 1));
									studyReport.append('\n');
								} else {
									if (key.equals(AppCore.ENGINE_RUNCOUNT_KEY)) {
										runStatusDone += running;
										running = 0;
										try {
											running = Integer.parseInt(line.substring(e + 1));
										} catch (NumberFormatException nfe) {
										}
										now = System.currentTimeMillis();
										if ((null != status) && ((now - lastStatusUpdate) > STATUS_UPDATE_INTERVAL)) {
											status.reportStatus("Study running, " + runStatusDone + " of " +
												runStatusTotal + " items done");
											lastStatusUpdate = now;
										}
									}
								}
							}
						}

					} else {
						if (line.trim().length() > 0) {
							error = true;
							if (null != errors) {
								errors.reportError(line);
							}
						}
					}
				}
			}

			if (process.exitValue() != 0) {
				error = true;
			}
		}

		// Delete the study database, done.

		if (null != theStudy) {
			Study.deleteStudy(dbID, theStudy.key, theStudy.lockCount);
		}

		AppTask.taskDone(task);

		return !error;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Build the study.  Returns null on error.  On successful return the study is still locked for edit, the caller
	// can change the lock as needed.  Verify that a study name is provided, createNewStudy() is told to make the name
	// unique if needed, but the base name is assumed otherwise valid.  When this is handling a servlet request the
	// name was generated using a sequential name pattern, otherwise if the request is from the GUI the name was set
	// from user input and should have already been checked for validity with DbCore.checkStudyName().

	public Study buildStudy(StatusLogger status, ErrorLogger errors) {

		if (!initialized) {
			if (null != errors) {
				errors.reportError("Cannot build study, object has not been initialized.");
			}
			return null;
		}

		if (null == studyName) {
			if (null != errors) {
				errors.reportError("Cannot build study, study name not provided.");
			}
			return null;
		}

		// Create the new study and open it.  Start the log timestamp sequence.

		setLogStartTime();

		if (null != status) {
			status.reportStatus("Building study...");
			status.logMessage(timestampMessage("Starting study build"));
		}

		Integer theKey = Study.createNewStudy(dbID, studyName, Study.STUDY_TYPE_TV_IX, templateKey, extDb.dbKey,
			true, errors);
		if (null == theKey) {
			return null;
		}
		int theStudyKey = theKey.intValue();

		Study theStudy = Study.getStudy(dbID, theStudyKey, errors);
		if (null == theStudy) {
			return null;
		}

		// Create the editable study and source objects.  Note this does not check the proposal record for automatic
		// replication.  This assumes the UI will have picked that up and caused it to propagate to the explicit
		// replication properties, unless the user specifically over-rode those properties to either set a different
		// channel, or indicate no replication so an auto-replication record can be studied on it's original channel.

		boolean result = false;

		try {

			study = new StudyEditData(theStudy);
			study.description = studyDescription;
			rules = study.ixRuleData.getRules();

			if (null != source) {
				proposalSource = (SourceEditDataTV)source.deriveSource(study, true, errors);
			} else {
				ExtDbRecordTV theRecord = (ExtDbRecordTV)record;
				proposalSource = SourceEditDataTV.makeSourceTV(theRecord, study, true, errors);
			}

			// The proposal source is identified with an attribute so it can be recognized when the study is opened
			// later, this has to appear on the original of a replication too in case replication is later reverted.

			if (null != proposalSource) {
				proposalSource.setAttribute(Source.ATTR_IS_PROPOSAL);
				if (replicate) {
					study.addOrReplaceSource(proposalSource);
					proposalSource = proposalSource.replicate(replicationChannel, errors);
				}
			}

			// The error logger is periodically checked for messages which are transferred to the status message log
			// so they appear interleaved with other local messages.  This does not attempt to timestamp the messages,
			// there may be multiple lines.

			if ((null != errors) && errors.hasMessages()) {
				if (null != status) {
					status.logMessage(errors.getMessages());
				}
				errors.clearMessages();
			}

			if (null != proposalSource) {

				int minChannel = study.getMinimumChannel(), maxChannel = study.getMaximumChannel();
				if ((proposalSource.channel < minChannel) || (proposalSource.channel > maxChannel)) {
					if (null != errors) {
						errors.reportError("Cannot build study, the channel must be in the range " + minChannel +
							" to " + maxChannel + ".");
					}
				} else {

					study.addOrReplaceSource(proposalSource);

					// Set parameter values as needed.

					ParameterEditData theParam;
					if (null != cellSize) {
						theParam = study.getParameter(Parameter.PARAM_CELL_SIZE);
						if (null != theParam) {
							for (int i = 0; i < theParam.parameter.valueCount; i++) {
								theParam.value[i] = cellSize;
							}
						}
					}
					if (null != profilePpk) {
						theParam = study.getParameter(Parameter.PARAM_PATH_TERR_RES);
						if (null != theParam) {
							for (int i = 0; i < theParam.parameter.valueCount; i++) {
								theParam.value[i] = profilePpk;
							}
						}
					}

					// Generate scenarios.  For a full build also report the results and do AM check on the proposal
					// record.  Then save the study.

					if (buildStudyScenarios(status, errors)) {

						if (buildFullStudy) {

							buildReport();

							if ((amSearchDistanceND > 0.) && (amSearchDistanceDA > 0.)) {
								ExtDb.checkForAMStations(extDb, proposalSource.location, amSearchDistanceND,
									amSearchDistanceDA, study.getKilometersPerDegree(), studyReport);
							} else {
								studyReport.append("Cannot run AM station check, missing configuration values\n\n");
							}
						}

						if (null != status) {
							status.reportStatus("Saving study...");
							status.logMessage(timestampMessage("Build complete, saving study"));
						}

						study.isDataChanged();
						if (study.save(errors, studyReport.toString())) {

							if (null != status) {
								status.logMessage(timestampMessage("Done"));
							}

							if (buildFullStudy) {
								runStatusTotal = proposalScenario.sourceData.getDesiredSourceCount();
								for (int i = 1; i < study.scenarioData.getRowCount(); i++) {
									runStatusTotal += study.scenarioData.get(i).getChildScenarioCount();
								}
							}

							result = true;
						}
					}
				}
			}

		} catch (Throwable t) {
			AppCore.log(AppCore.ERROR_MESSAGE, "Unexpected error", t);
			if (null != errors) {
				errors.reportError("Cannot build study, unexpected error:\n" + t);
			}
			result = false;
		}

		if (null != study) {
			theStudy = study.study;
			study.invalidate();
		}

		// Check for an abort before returning success.

		if (isAborted()) {
			if (null != errors) {
				errors.reportError("Study build aborted.");
			}
			result = false;
		}

		if (result) {
			return theStudy;
		}

		// If any error occurred delete the study.

		Study.deleteStudy(dbID, theStudy.key, theStudy.lockCount, errors);
		study = null;

		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add details of the build to the study report.  Called after the build is completed.

	private void buildReport() {

		String str;

		if (protectPreBaseline || protectBaselineFromLPTV || protectLPTVFromClassA) {
			studyReport.append("Build options:\n");
			if (protectPreBaseline) {
				studyReport.append("Protect pre-transition records not on baseline channel\n");
			}
			if (protectBaselineFromLPTV) {
				studyReport.append("Protect baseline records from LPTV\n");
			}
			if (protectLPTVFromClassA) {
				studyReport.append("Protect LPTV records from Class A\n");
			}
			studyReport.append('\n');
		}

		if (excludeApps || excludePending || (null != filingCutoffDate) || includeForeign || cpExcludesBaseline ||
				excludePostTransition) {
			studyReport.append("Search options:\n");
			if (includeForeign) {
				studyReport.append("Non-U.S. records included\n");
			}
			if (cpExcludesBaseline) {
				studyReport.append("Baseline record excluded if station has CP\n");
			}
			if (excludeApps) {
				studyReport.append("All APP records excluded\n");
			}
			if (excludePending) {
				studyReport.append("All pending records excluded\n");
			}
			if (excludePostTransition) {
				studyReport.append("All post-transition APP, CP, and baseline records excluded\n");
			}
			if (null != filingCutoffDate) {
				if (proposalSource.service.isLPTV()) {
					studyReport.append("LPTV records on or after ");
				} else {
					studyReport.append("All records on or after ");
				}
				studyReport.append(AppCore.formatDate(filingCutoffDate));
				studyReport.append(" excluded\n");
			}
			studyReport.append('\n');
		}

		if (null != includedSources) {
			studyReport.append("User records included:\n");
			for (SourceEditDataTV theSource : includedSources.values()) {
				str = theSource.getRecordID();
				studyReport.append(str);
				for (int i = 0; i < 8 - str.length(); i++) {
					studyReport.append(' ');
				}
				studyReport.append(theSource.getCallSign());
				studyReport.append(' ');
				studyReport.append(theSource.getChannel());
				studyReport.append(' ');
				studyReport.append(theSource.getServiceCode());
				studyReport.append(' ');
				studyReport.append(theSource.getStatus());
				studyReport.append(' ');
				studyReport.append(theSource.getCity());
				studyReport.append(", ");
				studyReport.append(theSource.getState());
				studyReport.append(' ');
				studyReport.append(theSource.getFileNumber());
				studyReport.append('\n');
			}
			studyReport.append('\n');
		}

		if (null != excludedRecords) {
			studyReport.append("Individual records excluded:\n");
			for (ExtDbRecordTV theRecord : excludedRecords.values()) {
				str = theRecord.getARN();
				studyReport.append(str);
				for (int i = 0; i < 14 - str.length(); i++) {
					studyReport.append(' ');
				}
				studyReport.append(theRecord.getCallSign());
				studyReport.append(' ');
				studyReport.append(theRecord.getChannel());
				studyReport.append(' ');
				studyReport.append(theRecord.getServiceCode());
				studyReport.append(' ');
				studyReport.append(theRecord.getStatus());
				studyReport.append(' ');
				studyReport.append(theRecord.getCity());
				studyReport.append(", ");
				studyReport.append(theRecord.getState());
				studyReport.append(' ');
				studyReport.append(theRecord.getFileNumber());
				studyReport.append('\n');
			}
			studyReport.append('\n');
		}

		if (protectedList.isEmpty()) {

			studyReport.append("No protected stations found.\n");

		} else {

			studyReport.append("Stations potentially affected by proposal:\n\n");
			studyReport.append(
				"IX   Call      Chan       Svc Status  City, State               File Number             Distance\n");

			boolean first = true;
			double kmPerDeg = study.getKilometersPerDegree();

			SourceEditDataTV theSource;

			for (Protected theProtected : protectedList) {

				if (theProtected.receivesIX) {
					studyReport.append("Yes  ");
				} else {
					studyReport.append("No   ");
				}

				theSource = theProtected.source;

				reportSource(theSource, proposalSource.location, kmPerDeg, first, studyReport);
				first = false;
			}
		}

		studyReport.append("\n");
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Append a line describing a source to a formatted report table.  Shared by several other methods.

	private static void reportSource(SourceEditDataTV theSource, GeoPoint location, double kmPerDeg, boolean first,
			StringBuilder report) {

		int i;

		String str = theSource.getCallSign();
		report.append(str);
		for (i = 0; i < 10 - str.length(); i++) {
			report.append(' ');
		}

		str = theSource.getChannel();
		report.append(str);
		for (i = 0; i < 11 - str.length(); i++) {
			report.append(' ');
		}

		str = theSource.getServiceCode();
		report.append(str);
		for (i = 0; i < 4 - str.length(); i++) {
			report.append(' ');
		}

		str = theSource.getStatus();
		report.append(str);
		for (i = 0; i < 8 - str.length(); i++) {
			report.append(' ');
		}

		str = theSource.getCity() + ", " + theSource.getState();
		report.append(str);
		for (i = 0; i < 26 - str.length(); i++) {
			report.append(' ');
		}

		str = theSource.getFileNumber();
		report.append(str);
		for (i = 0; i < 24 - str.length(); i++) {
			report.append(' ');
		}

		report.append(String.format(Locale.US, "%5.1f", theSource.location.distanceTo(location, kmPerDeg)));
		if (first) {
			report.append(" km");
		}

		report.append('\n');
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Build all the study scenarios for other stations potentially receiving interference from the proposal.  Return
	// false on any error, study model may be partially modified in that case.

	private boolean buildStudyScenarios(StatusLogger status, ErrorLogger errors) {

		double kmPerDeg = study.getKilometersPerDegree();
		boolean checkDTSDist = study.getCheckIndividualDTSDistance();
		int minChannel = study.getMinimumChannel();
		int maxChannel = study.getMaximumChannel();

		// Set up the "before" record for the proposal if the record or source to use was set explicitly, if not a
		// search will be done automatically during makeDesiredList().  The record may have been explicitly set to
		// null meaning there is no "before".

		if (didSetBefore) {
			if (null != beforeSource) {
				proposalBeforeSource = (SourceEditDataTV)beforeSource.deriveSource(study, true, errors);
				if (null == proposalBeforeSource) {
					return false;
				}
				study.addOrReplaceSource(proposalBeforeSource);
			} else {
				if (null != beforeRecord) {
					proposalBeforeSource = getOrMakeSource(beforeRecord, errors);
					if (null == proposalBeforeSource) {
						return false;
					}
				}
			}
		}

		// Preliminaries when building a full study.  Get the baseline channel index, see doChannelSearch() and
		// makeUndesiredList().  The abort flag is checked after any operation that may have taken some time.

		ArrayList<SourceEditDataTV> desiredSources = null;

		if (buildFullStudy) {

			if (null != baselineDate) {

				baselineIndex = ExtDbRecordTV.getBaselineIndex(extDb, errors);
				if (null == baselineIndex) {
					return false;
				}

				if (isAborted()) {
					if (null != errors) {
						errors.reportError("Study build aborted.");
					}
					return false;
				}
			}

			// Search for a list of user records to be included in the study, if any.

			if (null != includeUserRecords) {

				StringBuilder q = new StringBuilder("user_record_id IN (");
				String sep = "";
				for (Integer uid : includeUserRecords) {
					q.append(sep);
					q.append(String.valueOf(uid));
					sep = ",";
				}
				q.append(')');

				userRecordSources = SourceEditData.findUserRecords(dbID, Source.RECORD_TYPE_TV, q.toString(), errors);
				if (null == userRecordSources) {
					return false;
				}

				if (userRecordSources.isEmpty()) {
					userRecordSources = null;
				}

				if (isAborted()) {
					if (null != errors) {
						errors.reportError("Study build aborted.");
					}
					return false;
				}
			}

			// Create the list of protected desired stations to consider.  As a side-effect makeDesiredList() will
			// find and set an appropriate "before" record for the proposal, if any.

			if (null != status) {
				status.reportStatus("Searching for protected stations...");
				status.logMessage(timestampMessage("Searching for protected stations"));
			}

			desiredSources = makeDesiredList(kmPerDeg, checkDTSDist, minChannel, maxChannel, errors);
			if (null == desiredSources) {
				return false;
			}

			if (isAborted()) {
				if (null != errors) {
					errors.reportError("Study build aborted.");
				}
				return false;
			}

			if ((null != errors) && errors.hasMessages()) {
				if (null != status) {
					status.logMessage(errors.getMessages());
				}
				errors.clearMessages();
			}
		}

		// Create a scenario containing the proposal record and it's "before", if any.  Both are desireds but not
		// undesireds, and there are no other records.  Other code uses this to identify the proposal record(s), this
		// is also the "before" scenario for the later MX check scenario pairs.

		ArrayList<Scenario.SourceListItem> sourceItems = new ArrayList<Scenario.SourceListItem>();
		sourceItems.add(new Scenario.SourceListItem(proposalSource.key.intValue(), true, false, true));
		if (null != proposalBeforeSource) {
			sourceItems.add(new Scenario.SourceListItem(proposalBeforeSource.key.intValue(), true, false, true));
		}
		proposalScenario = new ScenarioEditData(study, "Coverage", "Coverage of proposal",
			Scenario.SCENARIO_TYPE_TVIX_PROPOSAL, true, sourceItems);
		study.scenarioData.addOrReplace(proposalScenario);

		// If not building a full study, that's all.

		if (!buildFullStudy) {
			return true;
		}

		// Create a temporary scenario with all protected records as desireds, and only the proposal and it's "before"
		// as undesireds.  Save and run the study in "probe" mode, see doProbeRun().  Remove the temporary scenario.

		HashSet<Integer> ixSourceKeys = new HashSet<Integer>();

		if (!desiredSources.isEmpty()) {

			if (null != status) {
				status.logMessage(timestampMessage("Found " + String.valueOf(desiredSources.size()) + " records"));
				status.logMessage(timestampMessage("Checking proposal interference"));
			}

			sourceItems = new ArrayList<Scenario.SourceListItem>();
			for (SourceEditDataTV theSource : desiredSources) {
				sourceItems.add(new Scenario.SourceListItem(theSource.key.intValue(), true, false, false));
			}

			sourceItems.add(new Scenario.SourceListItem(proposalSource.key.intValue(), false, true, false));
			if (null != proposalBeforeSource) {
				sourceItems.add(new Scenario.SourceListItem(proposalBeforeSource.key.intValue(), false, true, false));
			}

			study.scenarioData.addOrReplace(new ScenarioEditData(study, "probe", "", false, sourceItems));
			int rowIndex = study.scenarioData.getLastRowChanged();

			ArrayList<IXProbeResult> ixProbes = doProbeRun("Checking proposal interference", desiredSources.size(),
				status, errors);
			if (null == ixProbes) {
				return false;
			}

			study.scenarioData.remove(rowIndex);

			for (IXProbeResult theProbe : ixProbes) {
				if (theProbe.causesIX) {
					ixSourceKeys.add(theProbe.desiredSourceKey);
				}
			}

			if (null != status) {
				status.logMessage(timestampMessage(String.valueOf(ixSourceKeys.size()) +
					" records with interference from proposal"));
			}

		} else {
			if (null != status) {
				status.logMessage(timestampMessage("No protected station records found"));
			}
		}

		if (null != status) {
			status.reportStatus("Searching for undesireds...");
		}

		// Build lists of undesired records for all protected records that receive interference.  Create scenarios for
		// for each with the protected record as desired and all potential interferers as undesireds.  Those will be
		// used for another probe run, they also remain in the study to be the basis for the IX scenario builds.

		protectedList = new ArrayList<Protected>();
		HashMap<Integer, Protected> protectedMap = new HashMap<Integer, Protected>();
		Protected newProtected;
		String theName, theDesc;
		int protectedCount = 0;

		for (SourceEditDataTV theSource : desiredSources) {

			newProtected = new Protected();
			newProtected.source = theSource;
			protectedList.add(newProtected);
			if (!ixSourceKeys.contains(theSource.key)) {
				continue;
			}
			newProtected.receivesIX = true;

			if (null != status) {
				status.logMessage(timestampMessage("Searching for undesireds to " + theSource.getCallSign() + " " +
					theSource.getChannel() + " " + theSource.getStatus()));
			}

			if (!makeUndesiredList(newProtected, kmPerDeg, checkDTSDist, minChannel, maxChannel, errors)) {
				return false;
			}

			if (isAborted()) {
				if (null != errors) {
					errors.reportError("Study build aborted.");
				}
				return false;
			}

			if ((null != errors) && errors.hasMessages()) {
				if (null != status) {
					status.logMessage(errors.getMessages());
				}
				errors.clearMessages();
			}

			sourceItems = new ArrayList<Scenario.SourceListItem>();
			sourceItems.add(new Scenario.SourceListItem(theSource.key.intValue(), true, false, true));

			newProtected.undesiredMap = new HashMap<Integer, Undesired>();

			if (!newProtected.undesiredList.isEmpty()) {
				protectedMap.put(theSource.key, newProtected);
				for (Undesired theUnd : newProtected.undesiredList) {
					newProtected.undesiredMap.put(theUnd.source.key, theUnd);
					sourceItems.add(new Scenario.SourceListItem(theUnd.source.key.intValue(), false, true, false));
				}
			}

			theName = ((theSource.fileNumber.length() > 0) ?
				((theSource.fileNumber.length() > 22) ? theSource.fileNumber.substring(0, 22) : theSource.fileNumber) :
				((theSource.callSign.length() > 0) ? theSource.callSign : "UNKNOWN")) + "_" + theSource.status;
			theDesc = "Potential undesireds to " + theSource.toString();

			newProtected.buildScenario = new ScenarioEditData(study, theName, theDesc, false, sourceItems);
			study.scenarioData.addOrReplace(newProtected.buildScenario);
			protectedCount++;
		}

		// Also set up an undesired list for the proposal record for the received-interference (aka MX) scenarios.

		if (null != status) {
			status.logMessage(timestampMessage("Searching for undesireds to proposal"));
		}

		Protected proposalProtected = new Protected();
		proposalProtected.source = proposalSource;

		if (!makeUndesiredList(proposalProtected, kmPerDeg, checkDTSDist, minChannel, maxChannel, errors)) {
			return false;
		}

		if (isAborted()) {
			if (null != errors) {
				errors.reportError("Study build aborted.");
			}
			return false;
		}

		if ((null != errors) && errors.hasMessages()) {
			if (null != status) {
				status.logMessage(errors.getMessages());
			}
			errors.clearMessages();
		}

		sourceItems = new ArrayList<Scenario.SourceListItem>();
		sourceItems.add(new Scenario.SourceListItem(proposalSource.key.intValue(), true, false, true));

		proposalProtected.undesiredMap = new HashMap<Integer, Undesired>();

		if (!proposalProtected.undesiredList.isEmpty()) {
			protectedMap.put(proposalSource.key, proposalProtected);
			for (Undesired theUnd : proposalProtected.undesiredList) {
				proposalProtected.undesiredMap.put(theUnd.source.key, theUnd);
				sourceItems.add(new Scenario.SourceListItem(theUnd.source.key.intValue(), false, true, false));
			}
		}

		proposalProtected.buildScenario = new ScenarioEditData(study, "Proposal", "Potential undesireds to proposal",
			false, sourceItems);
		study.scenarioData.addOrReplace(proposalProtected.buildScenario);
		protectedCount++;

		// Do another probe run, update the build scenario for each protected to un-flag all undesireds that do not
		// cause interference.

		if (!protectedMap.isEmpty()) {

			if (null != status) {
				status.logMessage(timestampMessage("Checking undesired interference"));
			}

			ArrayList<IXProbeResult> ixProbes = doProbeRun("Checking undesired interference", protectedCount, status,
				errors);
			if (null == ixProbes) {
				return false;
			}

			Protected prot;
			Undesired und;
			for (IXProbeResult theProbe : ixProbes) {
				if (theProbe.causesIX) {
					prot = protectedMap.get(theProbe.desiredSourceKey);
					if (null != prot) {
						und = prot.undesiredMap.get(theProbe.undesiredSourceKey);
						if (null != und) {
							und.causesIX = true;
						}
					}
				}
			}

			SourceListData theSourceList;
			for (Protected theProtected : protectedMap.values()) {
				theSourceList = theProtected.buildScenario.sourceData;
				for (int rowIndex = 0; rowIndex < theSourceList.getRowCount(); rowIndex++) {
					und = theProtected.undesiredMap.get(Integer.valueOf(theSourceList.get(rowIndex).key));
					if ((null != und) && !und.causesIX) {
						theSourceList.setIsUndesired(rowIndex, false);
					}
				}
			}
		}

		// Process the protected records.  Each is studied in scenarios generated by iterating through combinations of
		// other records that interfere, different scenarios occur when there are MX records in the undesired list.
		// The interference impact of the proposal is evaluated in each scenario by comparing the desired record's
		// interference-free coverage with the proposal's "before" record (if any) included, to the interference-free
		// coverage with the proposal itself included, replacing the "before" (if any).  Note the proposal "before"
		// record is not included if the protected is a pre-baseline record and the "before" is a baseline record.

		if (null != status) {
			status.reportStatus("Building interference scenarios...");
		}

		builder = new ScenarioBuilder();
		builder.study = study;
		builder.proposalSource = proposalSource;

		for (Protected theProtected : protectedList) {
			if (theProtected.receivesIX) {
				if (null != status) {
					status.logMessage(timestampMessage("Building IX scenarios for " +
						theProtected.source.getCallSign() + " " + theProtected.source.getChannel() + " " +
						theProtected.source.getStatus()));
				}
				builder.proposalBeforeSource = proposalBeforeSource;
				if ((null != proposalBeforeSource) &&
						(null != theProtected.source.getAttribute(Source.ATTR_IS_PRE_BASELINE)) &&
						(null != proposalBeforeSource.getAttribute(Source.ATTR_IS_BASELINE))) {
					builder.proposalBeforeSource = null;
				}
				builder.desiredSource = theProtected.source;
				builder.undesiredList = theProtected.undesiredList;
				builder.buildScenario = theProtected.buildScenario;
				if (!builder.makeScenarios(null, status, errors)) {
					return false;
				}
			}
		}

		// Now add scenarios analyzing the proposal record as a desired to determine if it receives interference.  The
		// analysis here is considering the absolute amount of interference received in each scenario.  That is
		// determined using the same before-and-after mechanism as the interference scenarios, however the "before" in
		// every case is a scenario that contains only the proposal source as desired with no undesireds at all.

		if (null != status) {
			status.logMessage(timestampMessage("Building MX scenarios"));
		}
		builder.proposalBeforeSource = null;
		builder.desiredSource = proposalSource;
		builder.undesiredList = proposalProtected.undesiredList;
		builder.buildScenario = proposalProtected.buildScenario;
		if (!builder.makeScenarios(proposalScenario, status, errors)) {
			return false;
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Build the list of all records that may need protection from the proposal record per the interference rules.

	private ArrayList<SourceEditDataTV> makeDesiredList(double kmPerDeg, boolean checkDTSDist, int minChannel,
			int maxChannel, ErrorLogger errors) {

		ArrayList<ExtDbRecordTV> desiredRecords = doChannelSearch(proposalSource.channel, false, excludePending,
			includeForeign, minChannel, maxChannel, errors);
		if (null == desiredRecords) {
			return null;
		}

		boolean proposalIsLPTV = proposalSource.service.isLPTV();
		boolean proposalIsSTAorEXP = ((ExtDbRecord.STATUS_TYPE_STA == proposalSource.statusType) ||
			(ExtDbRecord.STATUS_TYPE_EXP == proposalSource.statusType));
		boolean proposalIsClassA = proposalSource.service.isClassA();
		boolean desiredIsLPTV;

		ArrayList<SourceEditDataTV> result = new ArrayList<SourceEditDataTV>();
		SourceEditDataTV theSource;

		Integer basechan, prechan;

		for (ExtDbRecordTV desiredRecord : desiredRecords) {

			desiredIsLPTV = desiredRecord.service.isLPTV();

			// If the proposal is digital LPTV, watch for an analog LPTV license with matching facility ID and channel
			// indicating this is a digital flash-cut.  If so, set the analog license as the baseline "before" case.
			// However the "before" record can now be set explicitly before starting the build, in that case skip this.

			if (!didSetBefore && proposalIsLPTV && proposalSource.service.isDigital() &&
					desiredIsLPTV && !desiredRecord.service.isDigital() &&
					(ExtDbRecord.STATUS_TYPE_LIC == desiredRecord.statusType) &&
					(desiredRecord.facilityID == proposalSource.facilityID) &&
					(desiredRecord.channel == proposalSource.channel)) {
				proposalBeforeSource = getOrMakeSource(desiredRecord, errors);
				if (null == proposalBeforeSource) {
					return null;
				}
				continue;
			}

			// Ignore the desired if it is LPTV and the proposal is not, unless the proposal is an STA or experimental
			// which has to protect everything regardless of service, or the proposal is a Class A and the flag is set
			// to protect LPTV from Class A.

			if (desiredIsLPTV && !proposalIsLPTV && !proposalIsSTAorEXP &&
					(!proposalIsClassA || !protectLPTVFromClassA)) {
				continue;
			}

			// If the baseline date is set, and the protectPreBaseline option is not set, full-service and Class A
			// records dated before the baseline date are not protected if on a different channel than their baseline,
			// or if they have no baseline at all.  This always applies to U.S. records, it also applies to Canadian
			// or Mexican records if the baseline has any such records, see ExtDbRecordTV.getBaselineIndex().

			if (!desiredIsLPTV && (null != baselineDate) && !protectPreBaseline &&
					((Country.US == desiredRecord.country.key) ||
					((Country.CA == desiredRecord.country.key) && baselineIndex.hasCA) ||
					((Country.MX == desiredRecord.country.key) && baselineIndex.hasMX))) {
				basechan = baselineIndex.index.get(Integer.valueOf(desiredRecord.facilityID));
				if ((null == basechan) || (desiredRecord.channel != basechan.intValue())) {
					if (desiredRecord.sequenceDate.before(baselineDate)) {
						continue;
					}

					// Records for channel-sharing host stations are considered pre-transition even after the baseline
					// date if they are not on the baseline channel but they are on the pre-transition channel.  Host
					// stations file on the pre-transition channel after the baseline date to set up sharing prior to
					// actual channel transition, but those are still pre-transition facilities and must be excluded
					// from a post-transition study.  Note this logic could cause a record to be improperly ignored if
					// a host station files a channel-change application returning to it's pre-transition channel, but
					// at the moment there is no way to differentiate that situation.  The chances of that occurring
					// are remote, and excluding all pre-transition records is more important for the moment.

					if (desiredRecord.isSharingHost) {
						prechan = baselineIndex.preIndex.get(Integer.valueOf(desiredRecord.facilityID));
						if ((null != prechan) && (prechan.intValue() == desiredRecord.channel)) {
							continue;
						}
					}
				}
			}

			// Ignore the proposal record itself, anything MX to the proposal, and anything that does not match an
			// interference rule.  Note the MX test used does not apply the co-channel tests of matching city-state
			// or a minimum distance; in this context only facility ID matters, however DRTs have to be considered,
			// see ExtDbRecordTV.areRecordsMX() for details.

			if (desiredRecord.extRecordID.equals(proposalSource.extRecordID) ||
					ExtDbRecordTV.areRecordsMX(desiredRecord, proposalSource, kmPerDeg) ||
					!doRecordsMatchRules(desiredRecord, proposalSource, kmPerDeg, checkDTSDist)) {
				continue;
			}

			// This record will be studied as a protected desired.

			theSource = getOrMakeSource(desiredRecord, errors);
			if (null == theSource) {
				return null;
			}
			result.add(theSource);
		}

		// If any user records are included, check those.  Some of the usual tests are not applied.  User records will
		// not automatically be used as the proposal before record even if they would otherwise match, the user must
		// manually choose the before if that behavior is desired.  Also user records are not excluded based on the
		// excludePostTransition or protectPreBaseline flags.  User records are explicitly included by user input, so
		// that overrides the categorical exclusion rules.  User records may still be treated as pre-baseline in the
		// undesired search.  Also, user records may update the baseline-excluded index here.

		if (null != userRecordSources) {

			for (SourceEditData userSource : userRecordSources) {

				theSource = (SourceEditDataTV)userSource;

				desiredIsLPTV = theSource.service.isLPTV();

				if (!desiredIsLPTV && ((ExtDbRecord.STATUS_TYPE_LIC == theSource.statusType) ||
						(cpExcludesBaseline && (ExtDbRecord.STATUS_TYPE_CP == theSource.statusType)))) {
					baselineExcludedIndex.add(Integer.valueOf((theSource.facilityID * 100) + theSource.channel));
				}

				if (desiredIsLPTV && !proposalIsLPTV && !proposalIsSTAorEXP &&
						(!proposalIsClassA || !protectLPTVFromClassA)) {
					continue;
				}

				if (theSource.userRecordID.equals(proposalSource.userRecordID) ||
						ExtDbRecordTV.areRecordsMX(theSource, proposalSource, kmPerDeg) ||
						!doRecordsMatchRules(theSource, proposalSource, kmPerDeg, checkDTSDist)) {
					continue;
				}

				if (null == includedSources) {
					includedSources = new TreeMap<Integer, SourceEditDataTV>();
				}
				theSource = (SourceEditDataTV)study.findSharedSource(theSource.userRecordID);
				if (null == theSource) {
					theSource = (SourceEditDataTV)userSource.deriveSource(study, true, errors);
					if (null == theSource) {
						return null;
					}
					study.addOrReplaceSource(theSource);
					includedSources.put(theSource.userRecordID, theSource);
				}

				result.add(theSource);
			}
		}

		// Run a second search for baseline records and add any baselines where the station has no license record, and
		// also the baseline matches rules.  Also watch for the proposal's baseline (matching facility ID and channel)
		// and set it as the "before" case, if needed.  Note the channel match has to consider the possibility that a
		// baseline record will replicate to a different channel.  Baselines are not included as protected stations if
		// the proposal is LPTV, unless it is an STA or experimental, or the protectBaselineFromLPTV flag is set.  In
		// general this can do MX tests with just matching facility ID since LPTVs should not appear here (baselines
		// are never LPTV) so the rules for DRTs are not a concern.  However the proposal itself could be a DRT.

		if ((!proposalIsLPTV || proposalIsSTAorEXP || protectBaselineFromLPTV) && !excludePostTransition) {

			desiredRecords = doBaselineSearch(proposalSource.channel, false, includeForeign, minChannel, maxChannel,
				errors);
			if (null == desiredRecords) {
				return null;
			}

			int chan;

			for (ExtDbRecordTV desiredRecord : desiredRecords) {

				if (desiredRecord.replicateToChannel > 0) {
					chan = desiredRecord.replicateToChannel;
				} else {
					chan = desiredRecord.channel;
				}

				if ((desiredRecord.facilityID == proposalSource.facilityID) && !proposalSource.isDRT) {
					if (!didSetBefore && !proposalIsLPTV && (chan == proposalSource.channel) &&
							!desiredRecord.extRecordID.equals(proposalSource.extRecordID)) {
						proposalBeforeSource = getOrMakeSource(desiredRecord, errors);
						if (null == proposalBeforeSource) {
							return null;
						}
					}
					continue;
				}

				if (baselineExcludedIndex.contains(Integer.valueOf((desiredRecord.facilityID * 100) + chan)) ||
						!doRecordsMatchRules(desiredRecord, proposalSource, kmPerDeg, checkDTSDist)) {
					continue;
				}

				theSource = getOrMakeSource(desiredRecord, errors);
				if (null == theSource) {
					return null;
				}
				result.add(theSource);
			}
		}

		// Sort by country, channel, state, city.

		Comparator<SourceEditDataTV> sortComp = new Comparator<SourceEditDataTV>() {
			public int compare(SourceEditDataTV theSource, SourceEditDataTV otherSource) {
				int result = 0;
				if (theSource.country.key < otherSource.country.key) {
					result = -1;
				} else {
					if (theSource.country.key > otherSource.country.key) {
						result = 1;
					} else {
						if (theSource.channel < otherSource.channel) {
							result = -1;
						} else {
							if (theSource.channel > otherSource.channel) {
								result = 1;
							} else {
								result = theSource.state.compareToIgnoreCase(otherSource.state);
								if (0 == result) {
									result = theSource.city.compareToIgnoreCase(otherSource.city);
								}
							}
						}
					}
				}
				return result;
			}
		};

		Collections.sort(result, sortComp);

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Build a list of potential undesired records for the current desired source.  See comments in makeDesiredList()
	// for details of the general logic; in short, ignore LPTV if the desired is non-LPTV, ignore anything MX to the
	// desired or the proposal, and ignore records not matching the rules.  However there are additional rules here,
	// pre-baseline records will be excluded unless the protected record is pre-baseline, and baseline records will be
	// excluded if the protected is pre-baseline.  Note if the desired is the proposal some tests are redundant however
	// this still works correctly.

	private boolean makeUndesiredList(Protected theProtected, double kmPerDeg, boolean checkDTSDist, int minChannel,
			int maxChannel, ErrorLogger errors) {

		SourceEditDataTV desiredSource = theProtected.source;

		ArrayList<ExtDbRecordTV> undesiredRecords = doChannelSearch(desiredSource.channel, true, excludePending,
			includeForeign, minChannel, maxChannel, errors);
		if (null == undesiredRecords) {
			return false;
		}

		boolean desiredIsLPTV = desiredSource.service.isLPTV();

		Integer basechan, prechan;
		boolean desiredIsPreBaseline = false;
		if (!desiredIsLPTV && (null != baselineDate)) {
			basechan = baselineIndex.index.get(Integer.valueOf(desiredSource.facilityID));
			if ((null == basechan) || (desiredSource.channel != basechan.intValue())) {
				java.util.Date seqDate = AppCore.parseDate(desiredSource.getAttribute(Source.ATTR_SEQUENCE_DATE));
				if ((null != seqDate) && seqDate.before(baselineDate)) {
					desiredIsPreBaseline = true;
					desiredSource.setAttribute(Source.ATTR_IS_PRE_BASELINE);
				} else {
					if (null != desiredSource.getAttribute(Source.ATTR_IS_SHARING_HOST)) {
						prechan = baselineIndex.preIndex.get(Integer.valueOf(desiredSource.facilityID));
						if ((null != prechan) && (prechan.intValue() == desiredSource.channel)) {
							desiredIsPreBaseline = true;
							desiredSource.setAttribute(Source.ATTR_IS_PRE_BASELINE);
						}
					}
				}
			}
		}

		ArrayList<Undesired> result = new ArrayList<Undesired>();
		Undesired theUnd;
		boolean undesiredIsLPTV;

		for (ExtDbRecordTV undesiredRecord : undesiredRecords) {

			undesiredIsLPTV = undesiredRecord.service.isLPTV();

			if (!desiredIsLPTV && undesiredIsLPTV) {
				continue;
			}

			if (!undesiredIsLPTV && !desiredIsPreBaseline && (null != baselineDate)) {
				basechan = baselineIndex.index.get(Integer.valueOf(undesiredRecord.facilityID));
				if ((null == basechan) || (undesiredRecord.channel != basechan.intValue())) {
					if (undesiredRecord.sequenceDate.before(baselineDate)) {
						continue;
					}
					if (undesiredRecord.isSharingHost) {
						prechan = baselineIndex.preIndex.get(Integer.valueOf(undesiredRecord.facilityID));
						if ((null != prechan) && (prechan.intValue() == undesiredRecord.channel)) {
							continue;
						}
					}
				}
			}

			if (undesiredRecord.extRecordID.equals(desiredSource.extRecordID) ||
					undesiredRecord.extRecordID.equals(proposalSource.extRecordID) ||
					ExtDbRecordTV.areRecordsMX(undesiredRecord, desiredSource, kmPerDeg) ||
					ExtDbRecordTV.areRecordsMX(undesiredRecord, proposalSource, kmPerDeg) ||
					!doRecordsMatchRules(desiredSource, undesiredRecord, kmPerDeg, checkDTSDist)) {
				continue;
			}

			theUnd = new Undesired();
			theUnd.source = getOrMakeSource(undesiredRecord, errors);
			if (null == theUnd.source) {
				return false;
			}
			result.add(theUnd);
		}

		// Add user records as needed.

		if (null != userRecordSources) {

			SourceEditDataTV theSource;

			for (SourceEditData userSource : userRecordSources) {

				theSource = (SourceEditDataTV)userSource;

				undesiredIsLPTV = theSource.service.isLPTV();

				if (!desiredIsLPTV && undesiredIsLPTV) {
					continue;
				}

				if (!undesiredIsLPTV && !desiredIsPreBaseline && (null != baselineDate)) {
					basechan = baselineIndex.index.get(Integer.valueOf(theSource.facilityID));
					if ((null == basechan) || (theSource.channel != basechan.intValue())) {
						java.util.Date seqDate = AppCore.parseDate(theSource.getAttribute(Source.ATTR_SEQUENCE_DATE));
						if ((null != seqDate) && seqDate.before(baselineDate)) {
							continue;
						}
						if (null != theSource.getAttribute(Source.ATTR_IS_SHARING_HOST)) {
							prechan = baselineIndex.preIndex.get(Integer.valueOf(theSource.facilityID));
							if ((null != prechan) && (prechan.intValue() == theSource.channel)) {
								continue;
							}
						}
					}
				}

				if (theSource.userRecordID.equals(desiredSource.userRecordID) ||
						theSource.userRecordID.equals(proposalSource.userRecordID) ||
						ExtDbRecordTV.areRecordsMX(theSource, desiredSource, kmPerDeg) ||
						ExtDbRecordTV.areRecordsMX(theSource, proposalSource, kmPerDeg) ||
						!doRecordsMatchRules(desiredSource, theSource, kmPerDeg, checkDTSDist)) {
					continue;
				}

				if (null == includedSources) {
					includedSources = new TreeMap<Integer, SourceEditDataTV>();
				}
				theSource = (SourceEditDataTV)study.findSharedSource(theSource.userRecordID);
				if (null == theSource) {
					theSource = (SourceEditDataTV)userSource.deriveSource(study, true, errors);
					if (null == theSource) {
						return false;
					}
					study.addOrReplaceSource(theSource);
					includedSources.put(theSource.userRecordID, theSource);
				}

				theUnd = new Undesired();
				theUnd.source = theSource;
				result.add(theUnd);
			}
		}

		// Add undesired baseline records as needed.

		if ((!desiredIsLPTV && !desiredIsPreBaseline) && !excludePostTransition) {

			undesiredRecords = doBaselineSearch(desiredSource.channel, true, includeForeign, minChannel, maxChannel,
				errors);
			if (null == undesiredRecords) {
				return false;
			}

			int chan;

			for (ExtDbRecordTV undesiredRecord : undesiredRecords) {

				if (undesiredRecord.replicateToChannel > 0) {
					chan = undesiredRecord.replicateToChannel;
				} else {
					chan = undesiredRecord.channel;
				}

				if (baselineExcludedIndex.contains(Integer.valueOf((undesiredRecord.facilityID * 100) + chan)) ||
						(undesiredRecord.facilityID == desiredSource.facilityID) ||
						(undesiredRecord.facilityID == proposalSource.facilityID) ||
						!doRecordsMatchRules(desiredSource, undesiredRecord, kmPerDeg, checkDTSDist)) {
					continue;
				}

				theUnd = new Undesired();
				theUnd.source = getOrMakeSource(undesiredRecord, errors);
				if (null == theUnd.source) {
					return false;
				}
				result.add(theUnd);
			}
		}

		theProtected.undesiredList = result;
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Run the study in probe mode, this runs temporary scenarios in a special engine mode that runs all scenarios
	// (except for the first one), creating no output but logging messages which report tuples of desired source key,
	// undesired source key, and a flag indicating if there is any interference from that undesired in isolation.
	// Those are read in and returned to the caller.  Assume the build process is running on a background thread and
	// was started under an AppTask queue so the engine can use full CPU and memory resources.  First save the study.
	// Note if any errors occur this assumes the study will be deleted so it does not attempt to restore study state.

	ArrayList<IXProbeResult> doProbeRun(String runDesc, int runCount, StatusLogger status, ErrorLogger errors) {

		study.isDataChanged();
		if (!study.save(errors)) {
			return null;
		}

		if (isAborted()) {
			if (null != errors) {
				errors.reportError("Study build aborted.");
			}
			return null;
		}

		Study theStudy = study.study;

		// Change the study lock from edit to an exclusive run lock.

		DbConnection db = DbCore.connectDb(dbID, errors);
		if (null == db) {
			return null;
		}

		String errmsg = null;

		try {

			db.update("LOCK TABLES study WRITE");

			db.query("SELECT study_lock, lock_count FROM study WHERE study_key = " + theStudy.key);

			if (db.next()) {

				if ((db.getInt(1) == theStudy.studyLock) && (db.getInt(2) == theStudy.lockCount)) {

					db.update("UPDATE study SET study_lock = " + Study.LOCK_RUN_EXCL +
						", lock_count = lock_count + 1, share_count = 0 WHERE study_key = " + theStudy.key);
					theStudy.studyLock = Study.LOCK_RUN_EXCL;
					theStudy.lockCount++;

				} else {
					errmsg = "Could not update study lock, the lock was modified.";
				}

			} else {
				errmsg = "Could not update study lock, the study was deleted.";
			}

		} catch (SQLException se) {
			errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
			db.reportError(se);
		}

		try {
			db.update("UNLOCK TABLES");
		} catch (SQLException se) {
			db.reportError(se);
		}

		DbCore.releaseDb(db);

		if (null != errmsg) {
			if (null != errors) {
				errors.reportError(errmsg);
			}
			return null;
		}

		// Build argument list, start the engine process.

		Process process = null;

		ArrayList<String> arguments = new ArrayList<String>();

		arguments.add(AppCore.libDirectoryPath + File.separator + AppCore.STUDY_ENGINE_NAME);

		arguments.add("-w");
		arguments.add(AppCore.workingDirectoryPath);
		arguments.add("-o");
		arguments.add(AppCore.outDirectoryPath);
		arguments.add("-i");

		long zeroTime = getLogStartTime();
		if (zeroTime > 0L) {
			arguments.add("-t");
			arguments.add(String.valueOf(zeroTime));
		}

		arguments.add("-h");
		arguments.add(DbCore.getDbHostname(dbID));
		arguments.add("-b");
		arguments.add(DbCore.getDbName(dbID));
		arguments.add("-u");
		arguments.add(DbCore.getDbUsername(dbID));

		arguments.add("-l");
		arguments.add(String.valueOf(theStudy.lockCount));
		arguments.add("-k");

		// Special option that puts the run in probe mode.

		arguments.add("-p");

		arguments.add(String.valueOf(theStudy.key));

		try {
			ProcessBuilder pb = new ProcessBuilder(arguments);
			pb.redirectErrorStream(true);
			process = pb.start();
		} catch (Throwable t) {
			AppCore.log(AppCore.ERROR_MESSAGE, "Could not start study engine process", t);
			if (null != errors) {
				errors.reportError("Could not start study engine process: " + t.getMessage());
			}
			return null;
		}

		// Read from process output until the password prompt appears, then write the password.

		try {

			InputStream in = process.getInputStream();
			StringBuilder sbuf = new StringBuilder();
			byte[] buf = new byte[100];
			int nc;
			boolean wait = true;
			do {
				nc = in.read(buf);
				if (nc > 0) {
					sbuf.append(new String(buf, 0, nc));
					wait = !sbuf.toString().toLowerCase().contains("password");
				}
				if (nc < 0) {
					if (null != errors) {
						errors.reportError("Could not start study engine process.");
					}
					process.destroyForcibly();
					return null;
				}
			} while (wait);

			OutputStream out = process.getOutputStream();
			out.write(DbCore.getDbPassword(dbID).getBytes());
			out.write("\n".getBytes());
			out.flush();

		} catch (IOException ie) {
			if (null != errors) {
				errors.reportError("Could not start study engine process: " + ie.getMessage());
			}
			process.destroyForcibly();
			return null;
		}

		// Read lines from process output, watch for and process message lines.  Skip the first line in case the
		// password echoes.

		ArrayList<IXProbeResult> result = new ArrayList<IXProbeResult>();
		IXProbeResult theProbe;

		BufferedReader processOutput = new BufferedReader(new InputStreamReader(process.getInputStream()));

		String line, key;
		boolean skipLine = true;
		int e, running = 0, runDone = 0;
		String[] values;

		while (true) {

			try {
				line = processOutput.readLine();
			} catch (IOException ie) {
				line = null;
			}

			if (line == null) {
				if (!process.isAlive()) {
					break;
				}
			} else {

				if (skipLine) {
					skipLine = false;
					continue;
				}

				if (line.startsWith(AppCore.ENGINE_MESSAGE_PREFIX)) {

					e = line.indexOf('=');
					if (e > 0) {
						key = line.substring(AppCore.ENGINE_MESSAGE_PREFIX_LENGTH, e);
						if (key.equals(AppCore.ENGINE_RESULT_KEY)) {
							values = line.substring(e + 1).split(",");
							if (3 == values.length) {
								theProbe = new IXProbeResult();
								try {
									theProbe.desiredSourceKey = Integer.valueOf(values[0]);
									theProbe.undesiredSourceKey = Integer.valueOf(values[1]);
									theProbe.causesIX = (Integer.parseInt(values[2]) != 0);
									result.add(theProbe);
								} catch (NumberFormatException nfe) {
								}
							}
						} else {
							if (key.equals(AppCore.ENGINE_ERROR_KEY)) {
								errmsg = line.substring(e + 1);
							} else {
								if (key.equals(AppCore.ENGINE_PROGRESS_KEY)) {
									if (null != status) {
										status.showMessage(line.substring(e + 1));
									}
								} else {
									if (key.equals(AppCore.ENGINE_RUNCOUNT_KEY)) {
										if (null != status) {
											runDone += running;
											running = 0;
											try {
												running = Integer.parseInt(line.substring(e + 1));
											} catch (NumberFormatException nfe) {
											}
											if (runDone < runCount) {
												status.reportStatus(runDesc + ", " + runDone + " of " + runCount +
													" items done");
											} else {
												status.reportStatus(runDesc + ", run complete");
											}
										}
									}
								}
							}
						}
					}

				} else {
					if (null != status) {
						status.logMessage(line);
					}
				}

				if (isAborted()) {
					errmsg = "Study build aborted.";
					process.destroy();
				}
			}
		}

		if (null != errmsg) {
			if (null != errors) {
				errors.reportError(errmsg);
			}
			return null;
		}

		if (process.exitValue() != 0) {
			if (null != errors) {
				errors.reportError("Study engine process failed, no details available.");
			}
			return null;
		}

		// Change the study lock back to edit.

		db = DbCore.connectDb(dbID, errors);
		if (null == db) {
			return null;
		}

		try {

			db.update("LOCK TABLES study WRITE");

			db.query("SELECT study_lock, lock_count FROM study WHERE study_key = " + theStudy.key);

			if (db.next()) {

				if ((db.getInt(1) == theStudy.studyLock) && (db.getInt(2) == theStudy.lockCount)) {

					db.update("UPDATE study SET study_lock = " + Study.LOCK_EDIT +
						", lock_count = lock_count + 1, share_count = 0 WHERE study_key = " + theStudy.key);
					theStudy.studyLock = Study.LOCK_EDIT;
					theStudy.lockCount++;

				} else {
					errmsg = "Could not update study lock, the lock was modified.";
				}

			} else {
				errmsg = "Could not update study lock, the study was deleted.";
			}

		} catch (SQLException se) {
			errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
			db.reportError(se);
		}

		try {
			db.update("UNLOCK TABLES");
		} catch (SQLException se) {
			db.reportError(se);
		}

		DbCore.releaseDb(db);

		if (null != errmsg) {
			if (null != errors) {
				errors.reportError(errmsg);
			}
			return null;
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Common bit of code, given a search results record, return the source object.  First look in the shared state of
	// the study to see if it is already there, if not make it from the record and add it to the study.  This supports
	// baseline records that are automatically replicated to a different channel.

	private SourceEditDataTV getOrMakeSource(ExtDbRecordTV theRecord, ErrorLogger errors) {

		SourceEditDataTV theSource = (SourceEditDataTV)study.findSharedSource(extDb.dbKey, theRecord.extRecordID);
		if (null == theSource) {
			theSource = SourceEditDataTV.makeSourceTV(theRecord, study, true, errors);
			if (null == theSource) {
				return null;
			}
			study.addOrReplaceSource(theSource);
		}

		if (theRecord.replicateToChannel > 0) {
			SourceEditDataTV origSource = theSource;
			theSource = study.findSharedReplicationSource(origSource.extDbKey, origSource.extRecordID,
				theRecord.replicateToChannel);
			if (null == theSource) {
				theSource = origSource.replicate(theRecord.replicateToChannel, errors);
				if (null == theSource) {
					return null;
				}
				study.addOrReplaceSource(theSource);
			}
		}

		return theSource;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Do a search for all channels related to a specified channel per the interference rules.  The rule distance
	// limits can't be checked by the query so must be checked in code later, the query will return all records
	// regardless of distance.  Since it is likely there will be multiple searches for the same channel, the results
	// are cached.  Caller must specify if the search is for protected or interfering records, the sign of the channel
	// delta must be changed accordingly.  The results are filtered with isExcluded().

	private ArrayList<ExtDbRecordTV> doChannelSearch(int searchChannel, boolean isIxSearch, boolean exclPending,
			boolean inclForeign, int minChannel, int maxChannel, ErrorLogger errors) {

		Integer cacheKey = Integer.valueOf((searchChannel * 10) + (isIxSearch ? 1 : 0));
		ArrayList<ExtDbRecordTV> result = searchCache.get(cacheKey);
		if (null != result) {
			return result;
		}

		// Due to analog-only rules two channel lists are needed, one to match digital records, the other analog
		// records.  The digital list is always a proper subset of the analog, there are no digital-only rules.

		int chan, maxChans = (maxChannel - minChannel) + 1;

		boolean[] searchChansD = new boolean[maxChans];
		boolean[] searchChansA = new boolean[maxChans];

		for (IxRule theRule : rules) {

			if (isIxSearch) {
				chan = searchChannel + theRule.channelDelta.delta;
			} else {
				chan = searchChannel - theRule.channelDelta.delta;
			}
			if ((chan < minChannel) || (chan > maxChannel)) {
				continue;
			}

			// Rules never apply across the channel 4-5, 6-7, and 13-14 gaps.

			if (searchChannel < 5) {
				if (chan > 4) {
					continue;
				}
			} else {
				if (searchChannel < 7) {
					if ((chan < 5) || (chan > 6)) {
						continue;
					}
				} else {
					if (searchChannel < 14) {
						if ((chan < 7) || (chan > 13)) {
							continue;
						}
					} else {
						if (chan < 14) {
							continue;
						}
					}
				}
			}

			if (!theRule.channelDelta.analogOnly) {
				searchChansD[chan - minChannel] = true;
			}
			searchChansA[chan - minChannel] = true;
		}

		// Build the search query.  Usually this is current and pending U.S. records, optionally pending records may be
		// excluded, and/or non-U.S. records included.  However records matching the facility ID of the proposal are
		// included regardless of country restriction, otherwise if the proposal is a foreign record other records that
		// should be considered in "before" scenarios won't appear in the result.

		StringBuilder q = new StringBuilder();

		try {

			ExtDbRecordTV.addRecordTypeQueryTV(extDb.type, extDb.version, !exclPending, false, q, false);

			if (!inclForeign) {
				q.append(" AND (");
				ExtDbRecord.addCountryQuery(extDb.type, extDb.version, Country.US, q, false);
				q.append(" OR ");
				ExtDbRecordTV.addFacilityIDQueryTV(extDb.type, extDb.version, proposalSource.facilityID, q, false);
				q.append(')');
			}

			q.append(" AND ((");
			ExtDbRecordTV.addServiceTypeQueryTV(extDb.type, extDb.version, ExtDbRecord.FLAG_MATCH_SET,
				ExtDbRecord.FLAG_MATCH_SET, q, false);
			StringBuilder chanList = new StringBuilder();
			char sep = '(';
			for (chan = 0; chan < maxChans; chan++) {
				if (searchChansD[chan]) {
					chanList.append(sep);
					chanList.append(String.valueOf(chan + minChannel));
					sep = ',';
				}
			}
			chanList.append(')');
			ExtDbRecordTV.addMultipleChannelQueryTV(extDb.type, extDb.version, chanList.toString(), q, true);

			q.append(") OR (");
			ExtDbRecordTV.addServiceTypeQueryTV(extDb.type, extDb.version, ExtDbRecord.FLAG_MATCH_SET,
				ExtDbRecord.FLAG_MATCH_CLEAR, q, false);
			chanList.setLength(0);
			sep = '(';
			for (chan = 0; chan < maxChans; chan++) {
				if (searchChansA[chan]) {
					chanList.append(sep);
					chanList.append(String.valueOf(chan + minChannel));
					sep = ',';
				}
			}
			chanList.append(')');
			ExtDbRecordTV.addMultipleChannelQueryTV(extDb.type, extDb.version, chanList.toString(), q, true);
			q.append("))");

		} catch (IllegalArgumentException ie) {
			errors.reportError(ie.toString());
			return null;
		}

		// Do the search.

		LinkedList<ExtDbRecordTV> records = ExtDbRecordTV.findRecordsTV(extDb, q.toString(), errors);
		if (null == records) {
			return null;
		}

		// Filter the search results.

		result = new ArrayList<ExtDbRecordTV>();

		boolean proposalIsLPTV = proposalSource.service.isLPTV();
		boolean otherIsLPTV;

		Integer basechan;

		for (ExtDbRecordTV theRecord : records) {

			if (isExcluded(theRecord)) {
				continue;
			}

			otherIsLPTV = theRecord.service.isLPTV();

			// If the filing cut-off date is set, the study is at least partially restricted to records dated before
			// that date.  If the proposal record is full-service or Class A this applies to all records, if the
			// proposal record is LPTV it only applies to other LPTV records.

			if ((null != filingCutoffDate) && (!proposalIsLPTV || otherIsLPTV) &&
					!theRecord.sequenceDate.before(filingCutoffDate)) {
				continue;
			}

			// Update the index of baseline records that are excluded from the study.  Once a full-service or Class A
			// station has a license record on a given channel, any baseline record on that channel is automatically
			// excluded.  Optionally, a CP record can also exclude the baseline.

			if (!otherIsLPTV && ((ExtDbRecord.STATUS_TYPE_LIC == theRecord.statusType) ||
					(cpExcludesBaseline && (ExtDbRecord.STATUS_TYPE_CP == theRecord.statusType)))) {
				baselineExcludedIndex.add(Integer.valueOf((theRecord.facilityID * 100) + theRecord.channel));
			}

			result.add(theRecord);
		}

		searchCache.put(cacheKey, result);

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Do a search for baseline records for a specified channel.  The results are cached as in doChannelSearch().

	private ArrayList<ExtDbRecordTV> doBaselineSearch(int searchChannel, boolean isIxSearch, boolean inclForeign,
			int minChannel, int maxChannel, ErrorLogger errors) {

		Integer cacheKey = Integer.valueOf((searchChannel * 10) + (isIxSearch ? 1 : 0));
		ArrayList<ExtDbRecordTV> result = baselineCache.get(cacheKey);
		if (null != result) {
			return result;
		}

		// Buld the channel list.

		int chan, maxChans = (maxChannel - minChannel) + 1;

		boolean[] searchChans = new boolean[maxChans];

		for (IxRule theRule : rules) {

			if (theRule.channelDelta.analogOnly) {
				continue;
			}

			if (isIxSearch) {
				chan = searchChannel + theRule.channelDelta.delta;
			} else {
				chan = searchChannel - theRule.channelDelta.delta;
			}
			if ((chan < minChannel) || (chan > maxChannel)) {
				continue;
			}

			// Rules never apply across the channel 4-5, 6-7, and 13-14 gaps.

			if (searchChannel < 5) {
				if (chan > 4) {
					continue;
				}
			} else {
				if (searchChannel < 7) {
					if ((chan < 5) || (chan > 6)) {
						continue;
					}
				} else {
					if (searchChannel < 14) {
						if ((chan < 7) || (chan > 13)) {
							continue;
						}
					} else {
						if (chan < 14) {
							continue;
						}
					}
				}
			}

			searchChans[chan - minChannel] = true;
		}

		// Do the search.

		StringBuilder q = new StringBuilder();

		try {

			if (!inclForeign) {
				q.append('(');
				ExtDbRecordTV.addBaselineCountryQuery(extDb.type, extDb.version, Country.US, q, false);
				q.append(" OR ");
				ExtDbRecordTV.addBaselineFacilityIDQuery(extDb.type, extDb.version, proposalSource.facilityID, q,
					false);
				q.append(") AND ");
			}

			StringBuilder chanList = new StringBuilder();
			char sep = '(';
			for (chan = 0; chan < maxChans; chan++) {
				if (searchChans[chan]) {
					chanList.append(sep);
					chanList.append(String.valueOf(chan + minChannel));
					sep = ',';
				}
			}
			chanList.append(')');
			ExtDbRecordTV.addBaselineMultipleChannelQuery(extDb.type, extDb.version, chanList.toString(), q, false);

		} catch (IllegalArgumentException ie) {
			errors.reportError(ie.toString());
			return null;
		}

		LinkedList<ExtDbRecordTV> records = ExtDbRecordTV.findBaselineRecords(extDb, q.toString(), errors);
		if (null == records) {
			return null;
		}

		// Filter the search results, there won't be such things as APP or analog Class A appearing here, however the
		// isExcluded() test also applies the excluded ARN list which may exclude baselines.

		result = new ArrayList<ExtDbRecordTV>();

		for (ExtDbRecordTV theRecord : records) {

			if (isExcluded(theRecord)) {
				continue;
			}

			result.add(theRecord);
		}

		baselineCache.put(cacheKey, result);

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check for record exclusions that apply to all contexts, this is used in the primary search to eliminate records
	// from the cached search results.  The tests are excluding all APP records if the excludeApps flag is set;
	// excluding records specifically by ARN, facility ID, or call sign; excluding all analog Class A records;
	// excluding all STA, experimental, and amendment records; and excluding post-transition records per option.

	private boolean isExcluded(ExtDbRecordTV theRecord) {

		if (excludeApps && (ExtDbRecord.STATUS_TYPE_APP == theRecord.statusType)) {
			return true;
		}

		if (ServiceType.SERVTYPE_NTSC_CLASS_A == theRecord.service.serviceType.key) {
			return true;
		}

		if ((ExtDbRecord.STATUS_TYPE_STA == theRecord.statusType) ||
				(ExtDbRecord.STATUS_TYPE_EXP == theRecord.statusType) ||
				(ExtDbRecord.STATUS_TYPE_AMD == theRecord.statusType)) {
			return true;
		}

		// Post-transition records are proposed facilities for the transition build-out, specifically full-service and
		// Class A non-license records dated after the baseline effective date, on the post-transition baseline channel
		// but with a different or non-existent pre-transition baseline channel.

		if (excludePostTransition && !theRecord.service.isLPTV() &&
				(ExtDbRecord.STATUS_TYPE_LIC != theRecord.statusType) &&
				(null != baselineDate) && !theRecord.sequenceDate.before(baselineDate)) {
			Integer chan = baselineIndex.index.get(Integer.valueOf(theRecord.facilityID));
			if ((null != chan) && (chan.intValue() == theRecord.channel)) {
				chan = baselineIndex.preIndex.get(Integer.valueOf(theRecord.facilityID));
				if ((null == chan) || (chan.intValue() != theRecord.channel)) {
					return true;
				}
			}
		}

		boolean exclude = false;
		if (null != excludeARNs) {
			exclude = excludeARNs.contains(theRecord.appARN.toUpperCase());
		} else {
			if (null != excludeFacilityIDs) {
				exclude = excludeFacilityIDs.contains(Integer.valueOf(theRecord.facilityID));
			} else {
				if (null != excludeCallSigns) {
					exclude = excludeCallSigns.contains(theRecord.callSign.toUpperCase());
				}
			}
		}
		if (exclude) {
			if (null == excludedRecords) {
				excludedRecords = new TreeMap<String, ExtDbRecordTV>();
			}
			excludedRecords.put(theRecord.appARN, theRecord);
			return true;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check a desired source and an undesired record versus the interference rules, check the channel relationship
	// and the distance limit.  Return true if the records are related by any rule.

	private boolean doRecordsMatchRules(SourceEditDataTV desired, ExtDbRecordTV undesired, double kmPerDeg,
			boolean checkDTSDist) {

		int undChan = undesired.channel;
		if (undesired.replicateToChannel > 0) {
			undChan = undesired.replicateToChannel;
		}

		int chanDelt = undChan - desired.channel;
		double checkDist;

		for (IxRule theRule : rules) {

			if ((theRule.channelDelta.delta != chanDelt) ||
					(theRule.channelDelta.analogOnly && desired.service.serviceType.digital)) {
				continue;
			}

			if (undChan < 5) {
				if (desired.channel > 4) {
					continue;
				}
			} else {
				if (undChan < 7) {
					if ((desired.channel < 5) || (desired.channel > 6)) {
						continue;
					}
				} else {
					if (undChan < 14) {
						if ((desired.channel < 7) || (desired.channel > 13)) {
							continue;
						}
					} else {
						if (desired.channel < 14) {
							continue;
						}
					}
				}
			}

			// Checking the distance can get complicated when DTS is involved.  For a desired DTS all the individual
			// DTS transmitters have to be checked, matching if any one is close enough.  Note the rule extra distance
			// may vary for each individual source.  Also the reference facility (site number 0) is not checked, the
			// reference facility contour may limit coverage within the individual contours but it does not extend
			// coverage, so always checking just the individual sources is conservative.  The same is true for the
			// DTS reference point and radius so that is not checked for the desired either.  For the undesired, an
			// option parameter indicates whether just the DTS reference point is checked, or all the DTS transmitters.

			if (desired.isParent) {
				for (SourceEditDataTV dtsSource : desired.getDTSSources()) {
					if (dtsSource.siteNumber > 0) {
						checkDist = theRule.distance + dtsSource.getRuleExtraDistance();
						if (undesired.service.isDTS && checkDTSDist) {
							for (ExtDbRecordTV dtsUndesiredRecord : undesired.dtsRecords) {
								if (dtsUndesiredRecord.location.distanceTo(dtsSource.location, kmPerDeg) <= checkDist) {
									return true;
								}
							}
						} else {
							if (undesired.location.distanceTo(dtsSource.location, kmPerDeg) <= checkDist) {
								return true;
							}
						}
					}
				}
			} else {
				checkDist = theRule.distance + desired.getRuleExtraDistance();
				if (undesired.service.isDTS && checkDTSDist) {
					for (ExtDbRecordTV dtsUndesiredRecord : undesired.dtsRecords) {
						if (dtsUndesiredRecord.location.distanceTo(desired.location, kmPerDeg) <= checkDist) {
							return true;
						}
					}
				} else {
					if (undesired.location.distanceTo(desired.location, kmPerDeg) <= checkDist) {
						return true;
					}
				}
			}
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// As above but desired is a record and undesired is a source.

	private boolean doRecordsMatchRules(ExtDbRecordTV desired, SourceEditDataTV undesired, double kmPerDeg,
			boolean checkDTSDist) {

		int desChan = desired.channel;
		if (desired.replicateToChannel > 0) {
			desChan = desired.replicateToChannel;
		}

		int chanDelt = undesired.channel - desChan;
		double checkDist;

		for (IxRule theRule : rules) {

			if ((theRule.channelDelta.delta != chanDelt) ||
					(theRule.channelDelta.analogOnly && desired.service.serviceType.digital)) {
				continue;
			}

			if (undesired.channel < 5) {
				if (desChan > 4) {
					continue;
				}
			} else {
				if (undesired.channel < 7) {
					if ((desChan < 5) || (desChan > 6)) {
						continue;
					}
				} else {
					if (undesired.channel < 14) {
						if ((desChan < 7) || (desChan > 13)) {
							continue;
						}
					} else {
						if (desChan < 14) {
							continue;
						}
					}
				}
			}

			// Check the distance, see comments above.  Note the reference facility is ignored on the undesired, that
			// has no relevance to the potential for causing interference.  A reference facility does not appear in
			// the record object list, the reference facility is constructed when the source object is created.

			if (desired.service.isDTS) {
				for (ExtDbRecordTV dtsRecord : desired.dtsRecords) {
					checkDist = theRule.distance + dtsRecord.getRuleExtraDistance(study);
					if (undesired.isParent && checkDTSDist) {
						for (SourceEditDataTV dtsUndesiredSource : undesired.getDTSSources()) {
							if (dtsUndesiredSource.siteNumber > 0) {
								if (dtsUndesiredSource.location.distanceTo(dtsRecord.location, kmPerDeg) <= checkDist) {
									return true;
								}
							}
						}
					} else {
						if (undesired.location.distanceTo(dtsRecord.location, kmPerDeg) <= checkDist) {
							return true;
						}
					}
				}
			} else {
				checkDist = theRule.distance + desired.getRuleExtraDistance(study);
				if (undesired.isParent && checkDTSDist) {
					for (SourceEditDataTV dtsUndesiredSource : undesired.getDTSSources()) {
						if (dtsUndesiredSource.siteNumber > 0) {
							if (dtsUndesiredSource.location.distanceTo(desired.location, kmPerDeg) <= checkDist) {
								return true;
							}
						}
					}
				} else {
					if (undesired.location.distanceTo(desired.location, kmPerDeg) <= checkDist) {
						return true;
					}
				}
			}
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// As above but both desired and undesired are sources.

	private boolean doRecordsMatchRules(SourceEditDataTV desired, SourceEditDataTV undesired, double kmPerDeg,
			boolean checkDTSDist) {

		int chanDelt = undesired.channel - desired.channel;
		double checkDist;

		for (IxRule theRule : rules) {

			if ((theRule.channelDelta.delta != chanDelt) ||
					(theRule.channelDelta.analogOnly && desired.service.serviceType.digital)) {
				continue;
			}

			if (undesired.channel < 5) {
				if (desired.channel > 4) {
					continue;
				}
			} else {
				if (undesired.channel < 7) {
					if ((desired.channel < 5) || (desired.channel > 6)) {
						continue;
					}
				} else {
					if (undesired.channel < 14) {
						if ((desired.channel < 7) || (desired.channel > 13)) {
							continue;
						}
					} else {
						if (desired.channel < 14) {
							continue;
						}
					}
				}
			}

			if (desired.isParent) {
				for (SourceEditDataTV dtsSource : desired.getDTSSources()) {
					if (dtsSource.siteNumber > 0) {
						checkDist = theRule.distance + dtsSource.getRuleExtraDistance();
						if (undesired.isParent && checkDTSDist) {
							for (SourceEditDataTV dtsUndesiredSource : undesired.getDTSSources()) {
								if (dtsUndesiredSource.siteNumber > 0) {
									if (dtsUndesiredSource.location.distanceTo(dtsSource.location,
											kmPerDeg) <= checkDist) {
										return true;
									}
								}
							}
						} else {
							if (undesired.location.distanceTo(dtsSource.location, kmPerDeg) <= checkDist) {
								return true;
							}
						}
					}
				}
			} else {
				checkDist = theRule.distance + desired.getRuleExtraDistance();
				if (undesired.isParent && checkDTSDist) {
					for (SourceEditDataTV dtsUndesiredSource : undesired.getDTSSources()) {
						if (dtsUndesiredSource.siteNumber > 0) {
							if (dtsUndesiredSource.location.distanceTo(desired.location, kmPerDeg) <= checkDist) {
								return true;
							}
						}
					}
				} else {
					if (undesired.location.distanceTo(desired.location, kmPerDeg) <= checkDist) {
						return true;
					}
				}
			}
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Make a report preamble for an existing study that has been edited, called from the study editor on save.

	public static String makeReportPreamble(StudyEditData theStudy) {

		if ((Study.STUDY_TYPE_TV_IX != theStudy.study.studyType) || (theStudy.scenarioData.getRowCount() < 1)) {
			return "Cannot write report, invalid study.\n\n";
		}

		ScenarioEditData proposalScenario = theStudy.scenarioData.get(0);
		if (Scenario.SCENARIO_TYPE_TVIX_PROPOSAL != proposalScenario.scenarioType) {
			return "Cannot write report, invalid study.\n\n";
		}

		SourceEditDataTV proposalSource = null, proposalBeforeSource = null;
		for (SourceEditData aSource : proposalScenario.sourceData.getSources(Source.RECORD_TYPE_TV)) {
			if (null != aSource.getAttribute(Source.ATTR_IS_PROPOSAL)) {
				if (null != proposalSource) {
					proposalSource = null;
					break;
				}
				proposalSource = (SourceEditDataTV)aSource;
			} else {
				if (null != proposalBeforeSource) {
					proposalSource = null;
					break;
				}
				proposalBeforeSource = (SourceEditDataTV)aSource;
			}
		}
		if (null == proposalSource) {
			return "Cannot write report, invalid study.\n\n";
		}

		StringBuilder thePreamble = new StringBuilder();

		// Have to assume the "before" was manually selected as there is no way to know if it was or not.  That just
		// causes the "before" record parameters to be printed out in full, or a note "No "before" case" to appear if
		// the "before" is null, so it's harmless even if that record was auto-selected originally.

		makeDescription(proposalSource, null, true, proposalBeforeSource, null, thePreamble, null);

		ScenarioEditData theScenario;
		SourceEditDataTV theSource;
		double kmPerDeg = theStudy.getKilometersPerDegree();
		boolean first = true;
		for (int rowIndex = 1; rowIndex < theStudy.scenarioData.getRowCount(); rowIndex++) {
			theScenario = theStudy.scenarioData.get(rowIndex);
			theSource = (SourceEditDataTV)(theScenario.sourceData.getDesiredSource());
			if ((Scenario.SCENARIO_TYPE_DEFAULT == theScenario.scenarioType) && (null != theSource) &&
					!ExtDbRecordTV.areRecordsMX(theSource, proposalSource, kmPerDeg)) {
				if (first) {
					thePreamble.append("Study has been edited.  Stations studied for impact of proposal:\n\n");
					thePreamble.append(
						"Call      Chan  Svc Status  City, State               File Number             Distance\n");
				}
				reportSource(theSource, proposalSource.location, kmPerDeg, first, thePreamble);
				first = false;
			}
		}
		if (first) {
			thePreamble.append("Study has been edited.  No protected stations found in study.\n");
		}

		thePreamble.append("\n");

		return thePreamble.toString();
	}


	//=================================================================================================================
	// The iterative scenario build process for one protected record is managed by a separate object class, so this
	// functionality can also be accessed directly for use in the study editor, see buildFromScenario().

	public static class ScenarioBuilder {

		private StudyEditData study;

		private SourceEditDataTV proposalSource;
		private SourceEditDataTV proposalBeforeSource;

		private ScenarioEditData buildScenario;

		private SourceEditDataTV desiredSource;
		private ArrayList<Undesired> undesiredList;

		private int scenarioCount;

		private boolean abort;


		//-------------------------------------------------------------------------------------------------------------

		public void abort() {

			abort = true;
		}


		//-------------------------------------------------------------------------------------------------------------

		public boolean isAborted() {

			return abort;
		}


		//-------------------------------------------------------------------------------------------------------------
		// Method to run an iterative scenario build based on a protected-and-undesireds list scenario.  This is used
		// by the study editor to allow a set of IX scenarios to be built, or re-built, using one of the auto-built
		// list scenarios created by a full build, possibly modified, or from a list scenario created manually.  The
		// scenario given as argument must belong to an IX check study, must be a top-level scenario (not a child
		// scenario), must be the default scenario type, and the study must have at least two scenarios.  If this is
		// called from the UI, none of that should ever fail to be true.  Note the outer class full build code does
		// not use this method, it sets properties and calls makeScenarios() directly.

		public boolean buildFromScenario(ScenarioEditData theScenario, StatusLogger status, ErrorLogger errors) {

			if ((null == theScenario.study) || (Study.STUDY_TYPE_TV_IX != theScenario.study.study.studyType) ||
					(null != theScenario.parentScenarioKey) || (theScenario.study.scenarioData.getRowCount() < 2) ||
					(Scenario.SCENARIO_TYPE_DEFAULT != theScenario.scenarioType)) {
				if (null != errors) {
					errors.reportError("Cannot build interference scenarios, invalid study or scenario.");
				}
				return false;
			}

			// Retrieve the study proposal record, and possibly it's "before", from the proposal scenario.  That
			// scenario should always be the first one in the model list, and it should contain exactly one or two
			// sources.  The proposal is flagged with an attribute, if there is another source that is the "before".

			buildScenario = theScenario;
			study = buildScenario.study;

			ScenarioListData theScenarioList = study.scenarioData;
			ScenarioEditData proposalScenario = theScenarioList.get(0);

			SourceListData theSourceList = proposalScenario.sourceData;
			SourceEditDataTV theSource;
			int rowIndex;

			proposalSource = null;
			proposalBeforeSource = null;

			for (rowIndex = 0; rowIndex < theSourceList.getRowCount(); rowIndex++) {
				theSource = (SourceEditDataTV)(theSourceList.getSource(rowIndex));
				if (null != theSource.getAttribute(Source.ATTR_IS_PROPOSAL)) {
					if (null != proposalSource) {
						proposalSource = null;
						break;
					}
					proposalSource = theSource;
				} else {
					if (null != proposalBeforeSource) {
						proposalSource = null;
						break;
					}
					proposalBeforeSource = theSource;
				}
			}

			if ((Scenario.SCENARIO_TYPE_TVIX_PROPOSAL != proposalScenario.scenarioType) || (null == proposalSource)) {
				if (null != errors) {
					errors.reportError("Cannot build interference scenarios, invalid study.");
				}
				return false;
			}

			// Check the argument scenario for validity, it must contain exactly one desired record, the protected,
			// and any number of undesireds (including none).  Along the way extract the desired source and build the
			// undesired list.  All records other than the desired are included in the build as undesireds, whether
			// they are flagged undesired or not.  The undesired flag is used to set the causesIX flag in the undesired
			// object.  If the scenario was auto-built that was determined by a probe study run.  If the scenario is
			// built manually the user must be sure the undesired flags are accurate; if causesIX is false the build
			// assumes that record does not interfere with the desired.  If in doubt, the user should set the undesired
			// flag; the causesIX flag is a performance optimization.  Note even if causesIX is false the undesired
			// must still be in the list.  If another MX undesired has causesIX true there must be IX scenarios both
			// with and without that other interfering undesired, so the list must include all MX records.

			undesiredList = new ArrayList<Undesired>();

			theSourceList = buildScenario.sourceData;
			Scenario.SourceListItem theItem;
			Undesired und;

			desiredSource = null;

			for (rowIndex = 0; rowIndex < theSourceList.getRowCount(); rowIndex++) {
				theItem = theSourceList.get(rowIndex);
				if (theItem.isDesired) {
					if (null != desiredSource) {
						desiredSource = null;
						break;
					}
					desiredSource = (SourceEditDataTV)(theSourceList.getSource(rowIndex));
				} else {
					und = new Undesired();
					und.source = (SourceEditDataTV)(theSourceList.getSource(rowIndex));
					und.causesIX = theItem.isUndesired;
					undesiredList.add(und);
				}
			}

			if (null == desiredSource) {
				if (null != errors) {
					errors.reportError("Cannot build interference scenarios, invalid scenario.");
				}
				return false;
			}

			// The proposal "before" is ignored if that is a baseline and the desired source is a pre-baseline.

			if ((null != proposalBeforeSource) && (null != desiredSource.getAttribute(Source.ATTR_IS_PRE_BASELINE)) &&
					(null != proposalBeforeSource.getAttribute(Source.ATTR_IS_BASELINE))) {
				proposalBeforeSource = null;
			}

			// Do the build.  If the desired source is MX to the proposal this is building MX scenarios.  The build
			// scenario might not contain the identical proposal record if the proposal has been modified.  The build
			// code will use the proposal source, rather than the desired source.

			scenarioCount = 0;

			if (ExtDbRecordTV.areRecordsMX(desiredSource, proposalSource, study.getKilometersPerDegree())) {
				return makeScenarios(proposalScenario, status, errors);
			} else {
				return makeScenarios(null, status, errors);
			}
		}


		//-------------------------------------------------------------------------------------------------------------
		// Generate scenarios for a desired source and undesireds list.  If the scenario argument is null, interference
		// scenarios are created by iterating through the undesired list generating pairs of study scenarios for each
		// distinct combination of non-MX undesireds.  Each consists of a "before" scenario without the proposal, and
		// an "after" scenario with the proposal.  A percentage change is checked between the before and after during
		// the study run.  The "before" may contain a "before" record being replaced by the proposal, or not.

		// When generating coverage scenarios there is only one desired, the proposal.  Each combination of undesireds
		// generates an "after" scenario, but in all cases the "before" is a scenario that contains just the proposal
		// as desired with no undesireds, that is provided by the scenario argument.  Note that is somewhat indirect as
		// the terrain-limited coverage from the "after" scenario also provides the necessary "before" value for the
		// analysis, however that approach would involve additional study-type-specific logic in the engine.  Using the
		// pairing logic keeps things simple and caching makes the extra calculation overhead insignificant.

		// The undesired combinations are generated by traversing a binary tree where each level corresponds to one
		// source in the undesired list.  Each node may have one branch that includes that level's source and excludes
		// one or more other sources MX to that source, and the other branch that excludes the level's source but does
		// not exclude anything else (except what might already be excluded by higher levels).  This approach is needed
		// because MX relationships are not always transitive (A MX to B and B MX to C does not guarantee A MX to C) so
		// sorting the undesired list into static MX groupings may not be possible.  This returns false on error.
		// Start by building the MX exclusions in the undesired list.  This list is traversed in sequence, each index
		// in the list is a level in the tree, so only sources later in the list need be checked for a given source's
		// exclusions.  Any MX groups in which none of the undesireds actually cause interference will be trimmed
		// during the recursion, see doMakeScenarios().

		private boolean makeScenarios(ScenarioEditData proposalScenario, StatusLogger status, ErrorLogger errors) {

			double kmPerDeg = study.getKilometersPerDegree();

			int i, j, undesiredCount = undesiredList.size(), mxCount = 0;
			Undesired und;
			boolean causesIX;

			for (i = 0; i < undesiredCount; i++) {

				und = undesiredList.get(i);
				causesIX = und.causesIX;

				for (j = i + 1; j < undesiredCount; j++) {
					if (ExtDbRecordTV.areRecordsMX(und.source, undesiredList.get(j).source, kmPerDeg)) {
						if (null == und.excludes) {
							und.excludes = new ArrayList<Integer>();
						}
						und.excludes.add(Integer.valueOf(j));
						if (undesiredList.get(j).causesIX) {
							causesIX = true;
						}
					}
				}

				if ((null != und.excludes) && causesIX) {
					mxCount++;
				}
			}

			// Check for a situation that could lead to an impractical number of scenarios.  At 15 MX pairs the study
			// will be large but manageable.  Beyond that it could still be OK so only a warning is issued so the user
			// knows what is going on and can abort if the build seems to get "stuck".  But at 18 MX pairs the scenario
			// count rises to the hundreds of thousands and it becomes very likely the build or the run will fail due
			// to memory or disk space limits, so abort the build.

			if (mxCount > 18) {
				if (null != errors) {
					errors.reportError("MX record count is too high, aborting study build.");
				}
				return false;
			} else {
				if (mxCount > 15) {
					if (null != status) {
						status.logMessage("**High MX record count, build and run times may be long");
					}
				}
			}

			// The new scenarios are added as child scenarios of the build scenario, start by removing any and all
			// existing child scenarios, the new build entirely replaces the old.

			buildScenario.removeAllChildScenarios();

			// Initially set all records to included, start the recursion.

			boolean[] flags = new boolean[undesiredCount];
			for (i = 0; i < undesiredCount; i++) {
				flags[i] = true;
			}

			scenarioCount = 0;
			if (doMakeScenarios(flags, 0, proposalScenario)) {
				return true;
			}

			// If the process failed assume it was an abort, there is no other reason for failure.

			if (null != errors) {
				errors.reportError("Build aborted.");
			}
			return false;
		}


		//-------------------------------------------------------------------------------------------------------------
		// Recursive method to traverse the tree and generate scenarios or scenario pairs.  Returns false on error.
		// First check for an abort flag, that can be set to abort the build when running on a secondary thread.

		private boolean doMakeScenarios(boolean[] flags, int i, ScenarioEditData proposalScenario) {

			if (isAborted()) {
				return false;
			}

			boolean[] nflags = flags.clone();
			int ni = i + 1;

			// If at the bottom, generate the scenarios and the analysis pairing.  Scenario names are generated with a
			// sequential case number for each undesired combination.

			if (ni > nflags.length) {

				int desiredSourceKey;
				ScenarioEditData beforeScenario, afterScenario;
				String theName, theDesc;

				scenarioCount++;

				ArrayList<Scenario.SourceListItem> sourceItems = new ArrayList<Scenario.SourceListItem>();

				// In coverage mode the "before" is a constant scenario with the proposal as desired and no undesireds.
				// Generate the "after" with the flagged undesireds.

				if (null != proposalScenario) {

					desiredSourceKey = proposalSource.key.intValue();

					beforeScenario = proposalScenario;

					sourceItems.add(new Scenario.SourceListItem(desiredSourceKey, true, false, true));

					for (int j = 0; j < flags.length; j++) {
						if (flags[j]) {
							sourceItems.add(new Scenario.SourceListItem(undesiredList.get(j).source.key.intValue(),
								false, true, true));
						}
					}

					theName = "MX_#" + scenarioCount;
					theDesc = "Interference to proposal, scenario " + scenarioCount;

					afterScenario = buildScenario.addChildScenario(theName, theDesc,
						Scenario.SCENARIO_TYPE_TVIX_INTERFERENCE, sourceItems);

				// In interference mode the current desired is being examined for incremental interference from the
				// proposal.  The "before" may include a record related to the proposal, or it may not.  The "after"
				// scenario always has the proposal record itself, replacing the "before" record if that exists.

				} else {

					desiredSourceKey = desiredSource.key.intValue();

					sourceItems.add(new Scenario.SourceListItem(desiredSourceKey, true, false, true));

					if (null != proposalBeforeSource) {
						sourceItems.add(new Scenario.SourceListItem(proposalBeforeSource.key.intValue(), false, true,
							true));
					}

					for (int j = 0; j < flags.length; j++) {
						if (flags[j]) {
							sourceItems.add(new Scenario.SourceListItem(undesiredList.get(j).source.key.intValue(),
								false, true, true));
						}
					}

					theName =  "IX_" + buildScenario.name + "_#" + scenarioCount;
					theDesc = "Interference to " + desiredSource.toString() + ", scenario " + scenarioCount;

					beforeScenario = buildScenario.addChildScenario(theName + "_before", theDesc + ", before",
						Scenario.SCENARIO_TYPE_TVIX_INTERFERENCE, sourceItems);

					if (null != proposalBeforeSource) {
						sourceItems.remove(1);
					}

					sourceItems.add(1, new Scenario.SourceListItem(proposalSource.key.intValue(), false, true, true));

					afterScenario = buildScenario.addChildScenario(theName + "_after", theDesc + ", after",
						Scenario.SCENARIO_TYPE_TVIX_INTERFERENCE, sourceItems);
				}

				if (!study.scenarioData.addScenarioPair(theName, theDesc, beforeScenario.key.intValue(),
						desiredSourceKey, afterScenario.key.intValue(), desiredSourceKey)) {
					return false;
				}

				return true;
			}

			// Descend, branching as needed.  If the current source is included and has an exclusion list there may be
			// two branches, the first includes the current source and excludes all others in it's list, and the second
			// excludes the current source while leaving all others as they were.  However it is possible that all the
			// sources excluded by this one have already been excluded by other sources at higher levels, in that case
			// the second branch can be trimmed.  Also if this source and all of it's exclusions do not actually cause
			// any interference, both branches can be trimmed.

			Undesired theUnd = undesiredList.get(i);
			boolean causesIX = theUnd.causesIX;

			if (nflags[i] && (null != theUnd.excludes)) {

				boolean didExclude = false;
				int j;

				for (Integer exclude : theUnd.excludes) {
					j = exclude.intValue();
					if (nflags[j]) {
						nflags[j] = false;
						didExclude = true;
						if (undesiredList.get(j).causesIX) {
							causesIX = true;
						}
					}
				}

				if (didExclude && causesIX) {
					if (!doMakeScenarios(nflags, ni, proposalScenario)) {
						return false;
					}
					nflags[i] = false;
					for (Integer exclude : theUnd.excludes) {
						j = exclude.intValue();
						nflags[j] = flags[j];
					}
				}

			} else {

				if (!causesIX) {
					nflags[i] = false;
				}
			}

			return doMakeScenarios(nflags, ni, proposalScenario);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Methods to perform various operations on servlet-run study output caches, called by IxCheckAPI.  The results
	// are reported in HTML.  These do not fail on error, error messages are shown in the report.  The report may be
	// null to completely ignore errors.  First is a general report of the cache state, if the report is null here
	// there is nothing to be done as the report is the only result.  The output root directory path is provided as
	// argument, the servlet does not use the default output location.

	public static void cacheReport(String theDbID, String outPath, StringBuilder report) {

		if (null == report) {
			return;
		}

		ErrorLogger errors = new ErrorLogger();

		File cacheDir = new File(outPath + File.separator + theDbID);

		// Get count of studies listed in the status table.

		int statusCount = 0;

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null != db) {
			try {
				db.query("SELECT COUNT(*) FROM ix_check_status");
				if (db.next()) {
					statusCount = db.getInt(1);
				}
			} catch (SQLException se) {
				DbConnection.reportError(errors, se);
			}
			DbCore.releaseDb(db);
		}

		// Get total cache storage size, and a count of output directories in the root.  Ignore regular files, however
		// all directories are assumed to be study outputs.

		long cacheSize = 0L;
		int cacheCount = 0;

		File[] contents = cacheDir.listFiles();
		if (null != contents) {
			for (int i = 0; i < contents.length; i++) {
				if (contents[i].isDirectory()) {
					cacheSize += AppCore.sizeOfDirectoryContents(contents[i]);
					cacheCount++;
				}
			}
		}

		// Write the report.

		if (errors.hasErrors()) {
			report.append("<br><b>" + errors.toString() + "</b><br>\n");
		}

		report.append("<br>Cache status:<br><br>");

		report.append("Study runs listed in cache index: ");
		report.append(String.valueOf(statusCount));
		report.append("<br>\n");

		report.append("Study output directories in cache: ");
		report.append(String.valueOf(cacheCount));
		report.append("<br>\n");

		report.append("Storage used: ");
		if (cacheSize >= 995000000L) {
			report.append(String.format(Locale.US, "%.2f GB", ((double)cacheSize / 1.e9)));
		} else {
			if (cacheSize >= 950000L) {
				report.append(String.format(Locale.US, "%.1f MB", ((double)cacheSize / 1.e6)));
			} else {
				if (cacheSize >= 500L) {
					report.append(String.format(Locale.US, "%d kB", (cacheSize / 1000L)));
				} else {
					report.append(String.format(Locale.US, "%d B", cacheSize));
				}
			}
		}
		report.append("<br><br>\n");
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Perform cache "clean-up" operation, remove status entries for which the output directory does not exist, and
	// remove output directories not listed in the status table.

	public static void cacheCleanup(String theDbID, String outPath, StringBuilder report) {

		ErrorLogger errors = new ErrorLogger();

		File cacheDir = new File(outPath + File.separator + theDbID);

		// Get the output directory names in the cache root.

		HashSet<String> outputNames = new HashSet<String>();

		File[] contents = cacheDir.listFiles();
		if (null != contents) {
			String theName;
			for (int i = 0; i < contents.length; i++) {
				if (contents[i].isDirectory()) {
					outputNames.add(contents[i].getName());
				}
			}
		}

		int deletedStatusCount = 0, deletedOutputCount = 0;

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null != db) {

			try {

				db.update("LOCK TABLES ix_check_status WRITE");

				// Scan the study names in the status table, compare to the output names set compiled earlier.  If a
				// status name has an output directory, the name is removed from the directory names set.  Any output
				// directories remaining in that set will be deleted later.  If a status name does not have an output
				// directory, add it to a list of status names to delete here.

				ArrayList<String> statusNames = new ArrayList<String>();
				String theName;
				db.query("SELECT study_name FROM ix_check_status");
				while (db.next()) {
					theName = db.getString(1);
					if (!outputNames.remove(theName)) {
						statusNames.add(theName);
					}
				}

				// Delete status entries with missing output directories.

				if (!statusNames.isEmpty()) {
					StringBuilder q = new StringBuilder("DELETE FROM ix_check_status WHERE study_name IN");
					int startLen = q.length();
					String sep = " ('";
					for (String statName : statusNames) {
						q.append(sep);
						q.append(statName);
						if (q.length() > DbCore.MAX_QUERY_LENGTH) {
							q.append("')");
							deletedStatusCount += db.update(q.toString());
							q.setLength(startLen);
							sep = " ('";
						} else {
							sep = "','";
						}
					}
					if (q.length() > startLen) {
						q.append("')");
						deletedStatusCount += db.update(q.toString());
					}
				}

			} catch (SQLException se) {
				db.reportError(errors, se);
				outputNames.clear();
			}

			try {
				db.update("UNLOCK TABLES");
			} catch (SQLException se) {
				db.reportError(se);
			}

			DbCore.releaseDb(db);

		} else {
			outputNames.clear();
		}

		// Delete any output directories that were not in the status list.

		if (!outputNames.isEmpty()) {
			for (String dirName : outputNames) {
				AppCore.deleteDirectoryAndContents(new File(cacheDir, dirName));
				deletedOutputCount++;
			}
		}

		// Write the report if needed.

		if (null == report) {
			return;
		}

		if (errors.hasErrors()) {
			report.append("<br><b>" + errors.toString() + "</b><br><br>\n");
		}

		report.append("<br>Cache cleanup results:<br><br>");

		report.append("Index entries removed: ");
		report.append(String.valueOf(deletedStatusCount));
		report.append("<br>\n");

		report.append("Output directories deleted: ");
		report.append(String.valueOf(deletedOutputCount));
		report.append("<br><br>\n");
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Delete status entries and output directories that are older than a number of days based on the run date in the
	// status table.  If the number of days is 0 this will delete everything.  Do this in a manner that also performs
	// one of the clean-up functions.  First delete the old status entries from the table, then get the set of all
	// remaining study names from the table.  Scan the output directories and remove all that do not appear in the
	// status list.  That may remove some directories that were already "orphans".

	public static void cacheDelete(String theDbID, String outPath, int days, StringBuilder report) {

		ErrorLogger errors = new ErrorLogger();

		File cacheDir = new File(outPath + File.separator + theDbID);

		int deletedStatusCount = 0, deletedOutputCount = 0;

		HashSet<String> statusNames = null;

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null != db) {
			try {

				db.update("LOCK TABLES ix_check_status WRITE");

				deletedStatusCount = db.update("DELETE FROM ix_check_status " +
					"WHERE (run_date < (NOW() - INTERVAL " + String.valueOf(days) + " DAY))");

				statusNames = new HashSet<String>();
				db.query("SELECT study_name FROM ix_check_status");
				while (db.next()) {
					statusNames.add(db.getString(1));
				}

			} catch (SQLException se) {
				db.reportError(errors, se);
				statusNames = null;
			}

			try {
				db.update("UNLOCK TABLES");
			} catch (SQLException se) {
				db.reportError(se);
			}

			DbCore.releaseDb(db);
		}

		if (null != statusNames) {

			File[] contents = cacheDir.listFiles();
			String theName;

			if (null != contents) {
				for (int i = 0; i < contents.length; i++) {
					if (contents[i].isDirectory()) {
						theName = contents[i].getName();
						if (!statusNames.contains(theName)) {
							AppCore.deleteDirectoryAndContents(new File(cacheDir, theName));
							deletedOutputCount++;
						}
					}
				}
			}
		}

		// Write the report if needed.

		if (null == report) {
			return;
		}

		if (errors.hasErrors()) {
			report.append("<br><b>" + errors.toString() + "</b><br><br>\n");
		}

		report.append("<br>Cache delete results:<br><br>");

		report.append("Index entries removed: ");
		report.append(String.valueOf(deletedStatusCount));
		report.append("<br>\n");

		report.append("Output directories deleted: ");
		report.append(String.valueOf(deletedOutputCount));
		report.append("<br><br>\n");
	}
}
