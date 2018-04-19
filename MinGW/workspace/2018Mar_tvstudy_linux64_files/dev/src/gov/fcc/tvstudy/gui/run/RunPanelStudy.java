//
//  RunPanelStudy.java
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui.run;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.gui.*;
import gov.fcc.tvstudy.gui.editor.*;

import java.util.*;
import java.sql.*;
import java.io.*;
import java.awt.*;
import java.awt.event.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;


//=====================================================================================================================
// Panel to manage an individual study run, including a possible initial study building step via a StudyBuild object.
// These are usually created by a StudyManager, this implements StudyLockHolder for that to track open studies.  But
// the parent of these panels is always the run manager, so the study manager reference is optional; if set that will
// be notified at various stages in the run.  Once initialized, this will display a ProcessPanel to handle the study
// engine, and optionally a report area in a separate tab showing report text.  The run manager will call poll()
// frequently to keep things moving along.  This implements ProcessController, there is no interaction with the engine
// process but messages are monitored to provide time-to-completion estimates.

public class RunPanelStudy extends RunPanel implements StudyLockHolder, ProcessController, StatusLogger {

	private StudyManager studyManager;
	private String dbID;

	// The StudyBuild object is set if a new study needs to be built first, or is null to run an existing study.  In
	// the latter case the superclass lock holder properties and other properties here must be set directly, otherwise
	// those will all be set after the new study is created, see buildStudy().  If the build is non-null, the study
	// may or may not run after the build depending on the runAfterBuild flag.  If build is null that is ignored.

	public StudyBuild studyBuild;
	public boolean runAfterBuild;

	// The study lock state, these may be set immediately after construction, or later if a study build has to be done
	// before the study and lock exist.

	public int studyKey;
	public String studyName;
	public int studyType;
	public int studyLock;
	public int lockCount;

	// These must be set, initialize() will validate.

	public OutputConfig fileOutputConfig;
	public OutputConfig mapOutputConfig;

	public String reportPreamble;

	public ArrayList<Integer> scenarioKeys;
	public int totalSourceCount;

	// The process panel to manage the study engine run.

	private ProcessPanel processPanel;

	// Study report messages displayed here.

	private JTextArea reportArea;
	private JScrollPane reportPane;
	private boolean reportPaneAdded;

	// May be a pane for the run log and one for the report.

	private JTabbedPane tabPane;

	// State.

	private static final int RUN_STATE_INIT = 0;
	private static final int RUN_STATE_WAIT = 1;
	private static final int RUN_STATE_BUILD = 2;
	private static final int RUN_STATE_START = 3;
	private static final int RUN_STATE_RUNNING = 4;
	private static final int RUN_STATE_EXITING = 6;
	private static final int RUN_STATE_EXIT = 7;

	private int runState;
	private boolean runFailed;
	private boolean runCanceled;

	private AppTask task;
	private boolean taskWaiting;

	// Build runs on a separate thread.

	private Thread buildThread;
	private boolean buildFailed;
	private ErrorLogger buildErrors;
	private String buildStatus;
	private StringBuilder buildMessageLog;

	// Time-to-completion estimate info, see processLogMessage().

	private long runStatusStartTime;
	private int runStatusRunningCount;
	private int runStatusDoneCount;
	private boolean updateRunStatus;


	//-----------------------------------------------------------------------------------------------------------------

	public RunPanelStudy(StudyManager theManager, String theDbID) {

		super();

		studyManager = theManager;
		dbID = theDbID;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This will be called before passing the panel to the run manager for display.  Errors here are not shown in the
	// panel display, this assumes the panel is not visible yet, so errors are only sent to the caller's reporter.

	public boolean initialize(ErrorReporter errors) {

		if (initialized) {
			return true;
		}

		if (RUN_STATE_INIT != runState) {
			runState = RUN_STATE_EXIT;
			runFailed = true;
			return false;
		}

		runState = RUN_STATE_EXIT;
		runFailed = true;

		if (!super.initialize(errors)) {
			return false;
		}

		// Silently adjust a memory fraction that is too small.  The memory limit argument to the study engine is an
		// integer which is the reciprocal of the memory fraction and that should not be greater that the maximum,
		// see AppCore.initialize().  If maxEngineProcessCount is <=0 the run is going to fail in any case.

		if (AppCore.maxEngineProcessCount > 0) {
			double minFrac = 1. / (double)AppCore.maxEngineProcessCount;
			if (memoryFraction < minFrac) {
				memoryFraction = minFrac;
			}
		}

		// Make sure the output configurations are set and valid.  If the config has the log file option, set the flag
		// so the run manager automatically saves run output when done, but don't clear it if not.

		if ((null == fileOutputConfig) || fileOutputConfig.isNull() || !fileOutputConfig.isValid()) {
			if (null != errors) {
				errors.reportError("Cannot run study, missing or invalid output file settings.");
			}
			return false;
		}
		if ((null == mapOutputConfig) || mapOutputConfig.isNull() || !mapOutputConfig.isValid()) {
			if (null != errors) {
				errors.reportError("Cannot run study, missing or invalid map output settings.");
			}
			return false;
		}

		autoSaveOutput = (fileOutputConfig.flags[OutputConfig.LOG_FILE] > 0);

		// Make sure the file output flag for the pair study custom cell file is not set, and settings file is set.

		fileOutputConfig.flags[OutputConfig.CELL_FILE_PAIRSTUDY] = 0;
		fileOutputConfig.flags[OutputConfig.SETTING_FILE] = 1;

		// If a StudyBuild is set, it's initializer does all the validation work.  Else make sure lock state is set.

		String activity = "";

		if (null != studyBuild) {

			if (!studyBuild.initialize(errors)) {
				return false;
			}

			studyLock = Study.LOCK_NONE;
			studyName = studyBuild.studyName;

			activity = "Build";

		} else {

			if ((studyKey <= 0) || (Study.LOCK_NONE == studyLock) || (lockCount <= 0)) {
				errors.reportError("Cannot run study, missing or invalid lock state.");
				return false;
			}

			if (null != studyName) {
				studyName = studyName.trim();
				if (0 == studyName.length()) {
					studyName = null;
				}
			}

			activity = "Run";
		}

		if (null != studyName) {
			runName = activity + " study " + studyName;
		} else {
			runName = activity + " new study";
		}


		// The parent study manager window which created this may close before this actually runs, so must directly
		// hold the database state open to be sure connections can be made later.

		if (!DbCore.openDb(dbID, this)) {
			errors.reportError("Invalid database connection ID");
			return false;
		}

		task = new AppTask(memoryFraction);

		// Create the process display panel, arguments will be set in startRun() to start the process once AppTask
		// approves.  Messages may still be displayed in the panel, see buildStudy().

		processPanel = new ProcessPanel(parent, "Study", DbCore.getDbPassword(dbID), null);
		processPanel.setProcessController(this);

		// Text area for report display.

		reportArea = new JTextArea(ProcessPanel.TEXT_AREA_ROWS, ProcessPanel.TEXT_AREA_COLUMNS);
		reportArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
		AppController.fixKeyBindings(reportArea);
		reportArea.setEditable(false);
		reportArea.setLineWrap(true);

		reportPane = AppController.createScrollPane(reportArea);

		// Layout.

		tabPane = new JTabbedPane();
		tabPane.addTab("Run", processPanel);

		setLayout(new BorderLayout());
		add(tabPane, BorderLayout.CENTER);

		// If the study report preamble is non-empty add the report tab immediately.  Otherwise this will be added when
		// it is needed, e.g. after the study is built if a preamble is generated, or during the run the first time
		// the engine outputs a report message (which may be never).

		if ((null != reportPreamble) && (reportPreamble.length() > 0)) {
			reportArea.append(reportPreamble);
			tabPane.add("Report", reportPane);
			reportPaneAdded = true;
		}

		runState = RUN_STATE_WAIT;
		runFailed = false;

		initialized = true;

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getDbID() {

		return dbID;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// StudyLockHolder methods.

	public int getStudyKey() {

		return studyKey;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getStudyName() {

		if (null == studyName) {
			return "New study";
		}
		return studyName;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int getStudyLock() {

		return studyLock;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int getLockCount() {

		return lockCount;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void toFront() {

		if (null != parent) {
			parent.getWindow().toFront();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean closeWithoutSave() {

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean studyManagerClosing() {

		studyManager = null;
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Do a check to see if disk space needed for the run is available.  Assume output is trivial here, just check for
	// cache space for the total number of desired sources to be studied.

	public boolean isDiskSpaceAvailable() {

		return AppCore.isFreeSpaceAvailable(dbID, totalSourceCount, 0L);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by the manager window on a timer.  Should not be called before initialization.

	public void poll(long now) {

		if (!initialized) {
			return;
		}

		// In the exit state, make sure AppTask knows this is done.

		if (RUN_STATE_EXIT == runState) {

			if (null != task) {

				AppTask.taskDone(task);
				task = null;

				DbCore.closeDb(dbID, this);
			}

			return;
		}

		// Waiting state.  Check to see if the process panel was stopped.  Otherwise check with AppTask to see if this
		// can start, if not, wait.

		if (RUN_STATE_WAIT == runState) {

			if (!processPanel.isProcessRunning()) {
				runState = RUN_STATE_EXITING;
				runFailed = processPanel.didProcessFail();
				runCanceled = processPanel.wasProcessCanceled();
			} else {

				if (!AppTask.canTaskStart(task)) {
					if (!taskWaiting) {
						taskWaiting = true;
						processPanel.setStatusMessage("Waiting for other runs to complete...");
					}
					return;
				}
				taskWaiting = false;

				if (null == studyBuild) {
					runState = RUN_STATE_START;
				} else {
					runState = RUN_STATE_BUILD;
				}
			}
		}
			
		// The first time here in the build state, start a background thread to build the study.  Later polls in the
		// build state just return until the thread exits.

		if (RUN_STATE_BUILD == runState) {

			if (null == buildThread) {

				buildErrors = new ErrorLogger();
				buildStatus = null;
				buildMessageLog = new StringBuilder();
				if (studyBuild.abortWillCancel()) {
					processPanel.setAbortButtonLabel("Cancel");
				} else {
					processPanel.setAbortButtonLabel("Abort");
				}

				buildThread = new Thread() {
					public void run() {
						try {
							buildStudy();
						} catch (Throwable t) {
							buildFailed = true;
						}
					}
				};
				buildThread.start();

				processPanel.setStatusMessage("Building study...");

				return;

			} else {

				updateBuildStatus();

				if (buildThread.isAlive()) {
					return;
				}

				if (buildErrors.hasMessages()) {
					processPanel.displayLogMessage(buildErrors.getMessages() + "\n");
				}
				if (buildErrors.hasErrors()) {
					processPanel.displayLogMessage(buildErrors.toString() + "\n");
				}

				// If the build failed, set exiting state.  Otherwise if the study is to be run now, notify the study
				// manager about the new study lock state, then proceed to start.  If this was just a build process,
				// cancel the pending run state in the process panel and proceed to exit.

				buildThread = null;

				if (buildFailed) {
					runState = RUN_STATE_EXITING;
					if (studyBuild.isAborted()) {
						runCanceled = true;
						processPanel.setStatusMessage("Study build canceled");
					} else {
						runFailed = true;
						processPanel.setStatusMessage("Study build failed");
					}
				} else {
					if (null != studyManager) {
						studyManager.applyEditsFrom(this);
					}
					if (runAfterBuild) {
						runState = RUN_STATE_START;
					} else {
						runState = RUN_STATE_EXITING;
						processPanel.stopProcess(false);
						processPanel.setStatusMessage("Study build complete");
					}
				}

				// Regardless of state if a report was set show it in the report tab, create that tab if needed.

				if ((null != reportPreamble) && (reportPreamble.length() > 0)) {
					reportArea.append(reportPreamble);
					if (!reportPaneAdded) {
						tabPane.add("Report", reportPane);
						reportPaneAdded = true;
					}
				}
			}
		}

		// In the start state, possibly falling through from build.  Set the argument list in the process panel, the
		// process will start when the panel is polled below.  Again check for a cancel from the process panel.

		if (RUN_STATE_START == runState) {

			if (!processPanel.isProcessRunning()) {
				runState = RUN_STATE_EXITING;
				runFailed = processPanel.didProcessFail();
				runCanceled = processPanel.wasProcessCanceled();
			} else {

				runState = RUN_STATE_RUNNING;
				updateRunStatus = true;

				startRun();
			}
		}

		// In the running state, possibly falling through from start.  Poll the process panel.  That will start or
		// update the process.  If the poll returns false the process is no longer running, check for failure.

		if (RUN_STATE_RUNNING == runState) {

			boolean isRunning = false;

			try {
				isRunning = processPanel.pollProcess(now);
				if (!isRunning && processPanel.didProcessFail()) {
					runState = RUN_STATE_EXITING;
					runFailed = true;
				}
			} catch (Throwable t) {
				AppCore.log(AppCore.ERROR_MESSAGE, "pollProcess() failed", t);
				isRunning = false;
				runState = RUN_STATE_EXITING;
				runFailed = true;
			}

			// Update time-to-completion display if needed.

			if (isRunning && updateRunStatus) {
				if (totalSourceCount > 0) {
					String status;
					String progress = AppCore.formatCount(runStatusDoneCount) + " of " +
						AppCore.formatCount(totalSourceCount) + " items done";
					if (runStatusDoneCount == totalSourceCount) {
						status = progress + ", study complete";
					} else {
						if (0L == runStatusStartTime) {
							status = progress + ", waiting for start...";
						} else {
							double fractionDone = (double)runStatusDoneCount / (double)totalSourceCount;
							double minutesElapsed = (double)(now - runStatusStartTime) / 60000.;
							if ((fractionDone < 0.1) && (minutesElapsed < 2.)) {
								status = progress + ", estimating time...";
							} else {
								double minutesRemaining = minutesElapsed * ((1. / fractionDone) - 1.);
								if (fractionDone < 0.5) {
									minutesRemaining *= 1.5 - fractionDone;
								}
								if (minutesRemaining < 1.) {
									status = progress + ", less than 1 minute remaining";
								} else {
									if (minutesRemaining < 60.) {
										int minutes = (int)Math.rint(minutesRemaining);
										status = progress + ", about " + minutes +
											((1 == minutes) ? " minute" : " minutes") + " remaining";
									} else {
										int hours = (int)Math.rint(minutesRemaining / 60.);
										status = progress + ", about " + hours +
											((1 == hours) ? " hour" : " hours") + " remaining";
									}
								}
							}
						}
					}
					processPanel.setStatusMessage(status);
				}
				updateRunStatus = false;
			}

			// If still running, that's all for this tick.

			if (isRunning) {
				return;
			}
		}

		// Final fall-through, must be in exiting.  If the run failed send the process an extra kill to be safe.

		runState = RUN_STATE_EXIT;
		if (runFailed) {
			processPanel.stopProcess(false);
		}

		// Process is done, release the study lock (if it exists; an early failure can occur before the lock is set).
		// This has to be done directly here rather than letting the study manager do it, because the lock may be in
		// one of two possible states.  If the initial lock was exclusive, the engine downgrades to a shared lock and
		// increments the lock count.  However this can't assume the lock will be in that state, because the engine may
		// have aborted before it got around to downgrading the lock.  First look for an exact match to the lock as
		// originally set, if that does not match and the original lock was exclusive, then check for a shared lock
		// with a lock count one higher.  Note errors do not cause the failed state to be set; the run is already done
		// and it's success or failure state should not be altered.

		if (Study.LOCK_NONE != studyLock) {

			boolean error = false;
			String errorMessage = "";

			DbConnection db = DbCore.connectDb(dbID);
			if (null != db) {
				try {

					db.update("LOCK TABLES study WRITE");

					db.query("SELECT study_lock, lock_count, share_count FROM study WHERE study_key=" + studyKey);

					if (db.next()) {

						int theLock = db.getInt(1);
						int theLockCount = db.getInt(2);
						int theShareCount = db.getInt(3);

						if (((theLock == studyLock) && (theLockCount == lockCount)) ||
								((Study.LOCK_RUN_EXCL == studyLock) && (Study.LOCK_RUN_SHARE == theLock) &&
								(theLockCount == (lockCount + 1)))) {

							if ((Study.LOCK_RUN_EXCL == theLock) || (--theShareCount <= 0)) {
								db.update("UPDATE study SET study_lock = " + Study.LOCK_NONE +
									", lock_count = lock_count + 1, share_count = 0 WHERE study_key = " + studyKey);
								studyLock = Study.LOCK_NONE;
								lockCount = theLockCount + 1;
							} else {
								db.update("UPDATE study SET share_count = share_count - 1 WHERE study_key = " +
									studyKey);
								studyLock = Study.LOCK_RUN_SHARE;
								lockCount = theLockCount;
							}

						} else {
							error = true;
							errorMessage = "*** The study lock was modified. ***";
						}

					} else {
						error = true;
						errorMessage = "*** The study was deleted. ***";
					}

				} catch (SQLException se) {
					error = true;
					errorMessage = DbConnection.ERROR_TEXT_PREFIX + se;
					db.reportError(se);
				}

				try {
					db.update("UNLOCK TABLES");
				} catch (SQLException se) {
					db.reportError(se);
				}

				DbCore.releaseDb(db);

			} else {
				error = true;
				errorMessage = "*** Could not open database connection. ***";
			}

			if (error) {
				processPanel.setStatusMessage(errorMessage);
			}
		}

		// Notify the task queue this task is done, and inform the study manager that the lock is released.  Also
		// close the database.

		if (null != studyManager) {
			studyManager.editorClosing(this);
		}

		if (null != task) {

			AppTask.taskDone(task);
			task = null;

			DbCore.closeDb(dbID, this);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Build the study.  This is assumed to be called on a secondary thread, it may block for a considerable time.
	// Any errors or messages from the build process are displayed in the process panel.  Use a private database
	// connection, accumulate errors and other messages for display in the process panel, caller does that.

	private void buildStudy() {

		String errmsg = "";

		Study theStudy = studyBuild.buildStudy(this, buildErrors);
		if (null == theStudy) {
			buildFailed = true;
		} else {

			// Study build succeeded, copy study state.  Then change the study lock from edit to run.

			studyKey = theStudy.key;
			studyName = theStudy.name;
			studyType = theStudy.studyType;
			studyLock = theStudy.studyLock;
			lockCount = theStudy.lockCount;

			runName = "Run study " + studyName;

			reportPreamble = theStudy.reportPreamble;

			String rootName = DbCore.getDbName(dbID);

			DbConnection db = DbCore.connectDb(dbID, buildErrors);
			if (null != db) {
				try {

					db.update("LOCK TABLES study WRITE");

					db.query("SELECT study_lock, lock_count FROM study WHERE study_key = " + theStudy.key);

					if (db.next()) {

						if ((db.getInt(1) == studyLock) && (db.getInt(2) == lockCount)) {

							db.update("UPDATE study SET study_lock = " + Study.LOCK_RUN_EXCL +
								", lock_count = lock_count + 1, share_count = 0 WHERE study_key = " + studyKey);
							studyLock = Study.LOCK_RUN_EXCL;
							lockCount++;

							db.update("UNLOCK TABLES");

							// Get scenario keys and count of desired sources.  Different query for IX check study,
							// based on the number of child scenarios.  Also in that case scenarios are auto-run based
							// on scenario pairings, so there is no need to pass a scenario key list.

							totalSourceCount = 0;

							if (Study.STUDY_TYPE_TV_IX == studyType) {

								db.query("SELECT COUNT(*) FROM " + rootName + "_" + studyKey +
									".scenario_source WHERE scenario_key = 1 AND is_desired");
								if (db.next()) {
									totalSourceCount = db.getInt(1);
								}

								db.query(
								"SELECT " +
									"parent.scenario_key, " +
									"COUNT(*) " +
								"FROM " +
									rootName + "_" + studyKey + ".scenario AS parent " +
									"JOIN " + rootName + "_" + studyKey + ".scenario AS child ON " +
										"(child.parent_scenario_key = parent.scenario_key) " +
									"JOIN " + rootName + "_" + studyKey + ".scenario_source ON " +
										"(scenario_source.scenario_key = child.scenario_key) " +
								"WHERE " +
									"scenario_source.is_desired " +
								"GROUP BY 1 " +
								"ORDER BY 1");

								while (db.next()) {
									totalSourceCount += db.getInt(2);
								}

							} else {

								db.query(
								"SELECT " +
									"scenario_key, " +
									"COUNT(*) " +
								"FROM " +
									rootName + "_" + studyKey + ".scenario " +
									"JOIN " + rootName + "_" + studyKey + ".scenario_source USING (scenario_key) " +
								"WHERE " +
									"scenario_source.is_desired " +
								"GROUP BY 1 " +
								"ORDER BY 1");

								scenarioKeys = new ArrayList<Integer>();

								while (db.next()) {
									scenarioKeys.add(Integer.valueOf(db.getInt(1)));
									totalSourceCount += db.getInt(2);
								}
							}

						} else {
							buildFailed = true;
							errmsg = "Could not update study lock, the lock was modified.";
						}

					} else {
						buildFailed = true;
						errmsg = "Could not update study lock, the study was deleted.";
					}

				} catch (SQLException se) {
					buildFailed = true;
					errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
					db.reportError(se);
				}

				try {
					db.update("UNLOCK TABLES");
				} catch (SQLException se) {
					db.reportError(se);
				}

				DbCore.releaseDb(db);

				if (buildFailed) {
					buildErrors.reportError(errmsg);
				}

			} else {
				buildFailed = true;
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Status and log messages from the study build process.  The StatusLogger methods are called on the background
	// thread from the build methods.  The update method is called from poll().

	public synchronized void reportStatus(String message) {

		buildStatus = message;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public synchronized void logMessage(String message) {

		buildMessageLog.append(message + "\n");
	}


	//-----------------------------------------------------------------------------------------------------------------

	public synchronized void showMessage(String message) {

		buildMessageLog.append(message + "\r");
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isCanceled() {

		return runCanceled;
	}


	//-----------------------------------------------------------------------------------------------------------------

	private synchronized void updateBuildStatus() {

		if (studyBuild.abortWillCancel()) {
			processPanel.setAbortButtonLabel("Cancel");
		} else {
			processPanel.setAbortButtonLabel("Abort");
		}

		if (null != buildStatus) {
			processPanel.setStatusMessage(buildStatus);
			buildStatus = null;
		}

		if (buildMessageLog.length() > 0) {
			processPanel.displayLogMessage(buildMessageLog.toString());
			buildMessageLog.setLength(0);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Start the run; build the argument list and give it to the process panel, the rest happens on the next poll.

	private void startRun() {

		ArrayList<String> arguments = new ArrayList<String>();

		arguments.add(AppCore.libDirectoryPath + File.separator + AppCore.STUDY_ENGINE_NAME);

		if (AppCore.Debug) {
			arguments.add("-d");
		}

		arguments.add("-w");
		arguments.add(AppCore.workingDirectoryPath);
		arguments.add("-o");
		arguments.add(AppCore.outDirectoryPath);
		arguments.add("-i");

		if (null != studyBuild) {
			long zeroTime = studyBuild.getLogStartTime();
			if (zeroTime > 0L) {
				arguments.add("-t");
				arguments.add(String.valueOf(zeroTime));
			}
		}

		arguments.add("-h");
		arguments.add(DbCore.getDbHostname(dbID));
		arguments.add("-b");
		arguments.add(DbCore.getDbName(dbID));
		arguments.add("-u");
		arguments.add(DbCore.getDbUsername(dbID));

		arguments.add("-l");
		arguments.add("\""+String.valueOf(lockCount)+"\"");

		arguments.add("-m");
		arguments.add(String.valueOf((int)Math.rint(1. / memoryFraction)));

		arguments.add("-f");
		arguments.add("\""+fileOutputConfig.getCodes()+"\"");
		arguments.add("-e");
		arguments.add("\""+mapOutputConfig.getCodes()+"\"");

		if ((null != runComment) && (runComment.length() > 0)) {
			arguments.add("-c");
			arguments.add("\""+runComment+"\"");
		}

		arguments.add("\""+String.valueOf(studyKey)+"\"");

		// An empty or null scenario list is allowed, a run that does not study any scenarios is potentially useful
		// because it still does updates of derived source properties, replications, etc.  Also some special study
		// types will auto-run scenarios regardless of the command-line, which would be ignored if defined.

		if (null != scenarioKeys) {
			for (Integer theKey : scenarioKeys) {
				arguments.add("\""+String.valueOf(theKey)+"\"");
			}
		}

		processPanel.setProcessArguments(arguments);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// ProcessController methods, see comments above.

	public String getProcessResponse(ProcessPanel theProcess, String thePrompt) {

		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void processResponseConfirmed(ProcessPanel theProcess) {

		return;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void processFailed(ProcessPanel theProcess) {

		return;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void processComplete(ProcessPanel theProcess) {

		return;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Watch for report messages and append to the report text, add the tab if needed.  Watch for messages reporting
	// the source run counts, update the time-to-complete status as needed, see poll() below.

	public void processStatusMessage(ProcessPanel theProcess, String theKey, String theData) {

		if (theKey.equals(AppCore.ENGINE_REPORT_KEY)) {
			reportArea.append(theData + "\n");
			if (!reportPaneAdded) {
				tabPane.add("Report", reportPane);
				reportPaneAdded = true;
			}
			return;
		}

		if (theKey.equals(AppCore.ENGINE_RUNCOUNT_KEY)) {
			if (0L == runStatusStartTime) {
				runStatusStartTime = System.currentTimeMillis();
				updateRunStatus = true;
			}
			if (runStatusRunningCount > 0) {
				runStatusDoneCount += runStatusRunningCount;
				runStatusRunningCount = 0;
				updateRunStatus = true;
			}
			try {
				runStatusRunningCount = Integer.parseInt(theData);
			} catch (NumberFormatException e) {
			}
			return;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by a ProcessPanel when the cancel/abort button is pressed, also forwarded from abort().  In the build
	// phase, this has to look at abortWillCancel() in the build object and decide whether this is a graceful cancel
	// or an abort and behave accordingly.  Otherwise just send this back to the panel.

	public boolean stopProcess(ProcessPanel thePanel) {

		if (RUN_STATE_BUILD != runState) {
			return thePanel.stopProcess(true);
		}

		if (studyBuild.isAborted()) {
			return true;
		}

		if (studyBuild.abortWillCancel()) {

			studyBuild.abort();

		} else {

			AppController.beep();
			String[] opts = {"No", "Yes"};
			if (0 == JOptionPane.showOptionDialog(this,
					"This will forcibly terminate a running study.  Output\n" +
					"files may be incomplete and databases may be left in an\n" +
					"invalid state.  Are you sure you want to do this?",
					"Abort " + runName, 0, JOptionPane.WARNING_MESSAGE, null, opts, opts[0])) {
				return false;
			}

			// Conditions may have changed while the dialog was up, re-check.

			if (!studyBuild.isAborted()) {
				studyBuild.abort();
				if (!studyBuild.abortWillCancel()) {
					buildThread.interrupt();
				}
			}
		}

		thePanel.stopProcess(false);

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Status information, and methods to write the output and report to files.

	public String getStatus() {

		switch (runState) {

			case RUN_STATE_INIT:
			case RUN_STATE_WAIT:
				return "Waiting";

			case RUN_STATE_BUILD:
				return "Building study";

			case RUN_STATE_START:
				return "Starting";

			case RUN_STATE_RUNNING:
				if (totalSourceCount > 0) {
					return "Running " + AppCore.formatCount(runStatusDoneCount) + " of " +
						AppCore.formatCount(totalSourceCount);
				} else {
					return "Running";
				}

			case RUN_STATE_EXITING:
				return "Exiting";

			case RUN_STATE_EXIT:
				if (runFailed) {
					return "Failed";
				} else {
					if (runCanceled) {
						return "Canceled";
					} else {
						return "Complete";
					}
				}
		}

		return "Unknown";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isRunning() {

		if (RUN_STATE_EXIT == runState) {
			return false;
		}
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isWaiting() {

		if (RUN_STATE_WAIT == runState) {
			return true;
		}
		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void bumpTask() {

		if ((RUN_STATE_WAIT == runState) && (null != task)) {
			AppTask.bumpTask(task);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean runFailed() {

		return runFailed;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Abort forwards to stopProcess().

	public void abort() {

		stopProcess(processPanel);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean abortWillCancel() {

		if ((RUN_STATE_INIT == runState) || (RUN_STATE_WAIT == runState)) {
			return true;
		}
		if (RUN_STATE_BUILD == runState) {
			return studyBuild.abortWillCancel();
		}
		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean hasOutput() {

		if ((null == processPanel) || isRunning()) {
			return false;
		}
		return processPanel.hasOutput();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void writeOutputTo(Writer theWriter) throws IOException {

		if ((null == processPanel) || isRunning()) {
			return;
		}
		processPanel.writeOutputTo(theWriter);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void saveOutput() {

		if ((null == processPanel) || isRunning()) {
			return;
		}
		processPanel.saveOutput();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean hasReport() {

		if (isRunning()) {
			return false;
		}
		return reportPaneAdded;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Save report to a file.  Can only happen once all activity is done.

	public void saveReport() {

		if (isRunning() || !reportPaneAdded) {
			return;
		}

		String theReport = reportArea.getText().trim();

		String title = "Save Study Report";
		errorReporter.setTitle(title);

		JFileChooser chooser = new JFileChooser(AppCore.getProperty(AppCore.LAST_FILE_DIRECTORY_KEY));
		chooser.setDialogType(JFileChooser.SAVE_DIALOG);
		chooser.setDialogTitle(title);
		chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		chooser.setMultiSelectionEnabled(false);

		File theFile = null;
		do {
			if (JFileChooser.APPROVE_OPTION != chooser.showDialog(this, "Save")) {
				return;
			}
			theFile = chooser.getSelectedFile();
			if (theFile.exists()) {
				AppController.beep();
				if (JOptionPane.YES_OPTION != JOptionPane.showConfirmDialog(this,
						"The file exists, do you want to replace it?", title, JOptionPane.YES_NO_OPTION,
						JOptionPane.WARNING_MESSAGE)) {
					theFile = null;
				}
			}
		} while (null == theFile);

		AppCore.setProperty(AppCore.LAST_FILE_DIRECTORY_KEY, theFile.getParentFile().getAbsolutePath());

		FileWriter theWriter = null;
		try {
			theWriter = new FileWriter(theFile);
		} catch (IOException ie) {
			errorReporter.reportError("Could not open the file:\n" + ie.getMessage());
			return;
		}

		try {
			theWriter.write(theReport);
		} catch (IOException ie) {
			errorReporter.reportError("Could not write to the file:\n" + ie.getMessage());
		}

		try {
			theWriter.close();
		} catch (IOException ie) {
		}
	}
}
