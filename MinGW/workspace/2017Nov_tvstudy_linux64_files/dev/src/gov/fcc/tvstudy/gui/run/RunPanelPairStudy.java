//
//  RunPanelPairStudy.java
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui.run;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.gui.*;

import java.util.*;
import java.io.*;
import java.sql.*;
import java.awt.*;
import java.awt.event.*;
import java.text.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;


//=====================================================================================================================
// RunPanel subclass for managing a pair-wise study.  This study type consists of a series of intermediate study
// engine runs generating temporary data files which are finally post-processed by a separate utility.  This panel
// manages all of those runs in a tabbed layout, in turn this is managed by StudyRunManager in the usual way.  Although
// this works with an existing study, there are study build and post-run study restore phases, see StudyBuildPair.
// The studyBuild object always exists here, it is created by the constructor.

public class RunPanelPairStudy extends RunPanel implements ProcessController {

	public StudyBuildPair studyBuild;

	// Multiple ProcessPanels are displayed.  Status label and abort button shown outside the tab pane, applying to
	// all panels when multiple processes are running.

	private JTabbedPane tabPane;

	private JLabel statusLabel;
	private JButton abortButton;

	// State.

	private static final int RUN_STATE_INIT = 0;
	private static final int RUN_STATE_WAIT = 1;
	private static final int RUN_STATE_BUILD = 2;
	private static final int RUN_STATE_PRERUN = 3;
	private static final int RUN_STATE_RUNNING = 4;
	private static final int RUN_STATE_POSTRUN = 5;
	private static final int RUN_STATE_RESTORE = 6;
	private static final int RUN_STATE_EXITING = 7;
	private static final int RUN_STATE_EXIT = 8;

	private int runState;

	private boolean runFailed;
	private String runFailedMessage;

	private AppTask task;
	private boolean taskWaiting;

	// The build process runs on a separate thread.

	private Thread buildThread;
	private boolean buildFailed;

	// Run list from the build.

	private ArrayDeque<StudyBuildPair.ScenarioSortItem> scenarioRunList;

	// There may be multiple running ProcessPanels.  See the ProcessController methods for details of other state.

	private ArrayList<ProcessPanel> studyRuns;

	private HashMap<ProcessPanel, StudyBuildPair.ScenarioSortItem> studyRunsPending;
	private HashSet<ProcessPanel> ignoreFailedRuns;

	private boolean hasOutput;

	// The restore process also runs on a separate thread.

	private Thread restoreThread;

	// Temporary output files will be deleted on exit.

	private String filePath;
	private ArrayList<File> outFiles;

	// This always exists as a place to show messages if needed, but this property is not used for state management.
	// When appropriate, this panel will also be in the studyRuns list.

	private ProcessPanel baselinePanel;

	// State for progress reporting and time estimate.

	private long runStatusStartTime;
	private int runStatusTotalCount;
	private int runStatusRunningCount;
	private int runStatusDoneCount;
	private boolean updateRunStatus;

	// Estimate of disk space used per desired station per channel studied.  Both temporary and final output files are
	// on disk together during post-processing so this is nearly double the expected size of the final output.

	private static final long SOURCE_OUTPUT_SPACE_NEEDED = 3000000L;


	//-----------------------------------------------------------------------------------------------------------------

	public RunPanelPairStudy(AppEditor theParent) {

		super(theParent);

		studyBuild = new StudyBuildPair(getDbID());
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The StudyBuildPair initializer does most of the work.

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

		// Make sure the file output flag for the pair study custom cell file is set, also the settings file.

		fileOutputConfig.flags[OutputConfig.CELL_FILE_PAIRSTUDY] = 1;
		fileOutputConfig.flags[OutputConfig.SETTING_FILE] = 1;

		if (!studyBuild.initialize(errors)) {
			return false;
		}

		runState = RUN_STATE_WAIT;
		runFailed = false;

		// During the main running phase multiple processes may be started but total memory use will be scaled by the
		// fraction selected here.  That may result in fewer processes than requested in studyBuild.runCount.

		task = new AppTask(memoryFraction);

		// Copy lock state.

		studyKey = studyBuild.baseStudy.key;
		studyType = studyBuild.baseStudy.studyType;
		studyLock = studyBuild.baseStudy.studyLock;
		lockCount = studyBuild.baseStudy.lockCount;

		studyName = studyBuild.baseStudy.name;

		// Other setup.

		filePath =
			AppCore.outDirectoryPath + File.separator + DbCore.getHostDbName(getDbID()) + File.separator + studyName;
		outFiles = new ArrayList<File>();

		studyRuns = new ArrayList<ProcessPanel>();
		studyRunsPending = new HashMap<ProcessPanel, StudyBuildPair.ScenarioSortItem>();
		ignoreFailedRuns = new HashSet<ProcessPanel>();

		// A status label tracking progress of the study.

		statusLabel = new JLabel();
		statusLabel.setPreferredSize(AppController.labelSize[60]);

		// Buttons.

		abortButton = new JButton("Cancel");
		abortButton.setFocusable(false);
		abortButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doAbort();
			}
		});

		// Layout.  Always create the baseline process panel to have something to show, and a place to show messages.

		baselinePanel = new ProcessPanel(this, "Study", DbCore.getDbPassword(getDbID()), mergeLineStrings);
		baselinePanel.setProcessController(this);
		baselinePanel.setStatusPanelVisible(false);

		tabPane = new JTabbedPane();
		tabPane.addTab("Baseline", baselinePanel);

		JPanel statPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		statPanel.add(abortButton);
		statPanel.add(statusLabel);

		setLayout(new BorderLayout());
		add(tabPane, BorderLayout.CENTER);
		add(statPanel, BorderLayout.SOUTH);

		// Success.

		updateControls();

		initialized = true;

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update UI controls per current state.

	private void updateControls() {

		if ((RUN_STATE_WAIT == runState) || (RUN_STATE_BUILD == runState) || (RUN_STATE_PRERUN == runState) ||
				(RUN_STATE_RUNNING == runState) || (RUN_STATE_POSTRUN == runState)) {
			abortButton.setEnabled(true);
		} else {
			abortButton.setEnabled(false);
		}

		if (RUN_STATE_RUNNING == runState) {
			abortButton.setText("Abort All");
		} else {
			abortButton.setText("Abort");
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// UI abort action, if in a running state get confirmation.  See abort().

	private void doAbort() {

		if ((RUN_STATE_PRERUN == runState) || (RUN_STATE_RUNNING == runState) ||
				(RUN_STATE_POSTRUN == runState)) {

			AppController.beep();

			String[] opts = {"No", "Yes"};
			if (1 != JOptionPane.showOptionDialog(this,
					"This will terminate all study runs and cancel all\n" +
					"data processing, no output files will be created.\n\n" +
					"Are you sure you want to abort the study?",
					"Abort Study", 0, JOptionPane.WARNING_MESSAGE, null, opts, opts[0])) {
				return;
			}
		}

		abort();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check free disk space.  Check both cache space, based on the baseline scenario source count, and also an
	// estimate of the output file size.  For a pair study, the output files can easily be much bigger than cache.

	public boolean isDiskSpaceAvailable() {

		long outSize =
			(long)(studyBuild.baselineSourceCount * studyBuild.studyChannels.length) * SOURCE_OUTPUT_SPACE_NEEDED;

		return AppCore.isFreeSpaceAvailable(getDbID(), studyBuild.baselineSourceCount, outSize);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The state runner, called by the displaying run manager.  Handles progression through states, in running states
	// polls running processes to keep UI updated.  Note when a process exits with failure that may be ignored if it is
	// in the ignoreFailedRuns list, see processFailed().

	public void poll(long now) {

		if (!initialized) {
			return;
		}

		// In the exit state, make sure AppTask knows this is done.

		if (RUN_STATE_EXIT == runState) {
			if (null != task) {
				AppTask.taskDone(task);
				task = null;
			}
			return;
		}

		// Waiting state.  Check with AppTask to see if this can start.

		if (RUN_STATE_WAIT == runState) {

			if (!AppTask.canTaskStart(task)) {
				if (!taskWaiting) {
					taskWaiting = true;
					statusLabel.setText("Waiting for other runs to complete...");
				}
				return;
			}
			taskWaiting = false;

			runState = RUN_STATE_BUILD;
		}
			
		// Build state.  Start the thread first time through, poll it later.

		if (RUN_STATE_BUILD == runState) {

			if (null == buildThread) {

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

				statusLabel.setText("Building pair study scenarios...");

				return;

			} else {

				if (buildThread.isAlive()) {
					return;
				}

				// When the thread exits, if it failed go straight to exiting state, no point in attempting restore.
				// Otherwise inform the study manager about the new study lock state and start the prerun state.

				buildThread = null;

				if (buildFailed) {

					runState = RUN_STATE_EXITING;
					runFailed = true;
					runFailedMessage = "*** Study setup failed ***";
					updateControls();

				} else {

					runState = RUN_STATE_PRERUN;
					statusLabel.setText("Running baseline scenario");
					updateControls();

					startPrerun();

					originalParent.applyEditsFrom(this);
				}
			}
		}

		// As long as studyRuns contains one or more active runs, poll all of those, removing any that are no longer
		// running, until all are done.  This continues even if state has been set to restore or exiting; those states
		// do not execute until all runs are finished.  See abort().

		if (!studyRuns.isEmpty()) {

			Iterator<ProcessPanel> it = studyRuns.iterator();
			ProcessPanel theRun;
			boolean isRunning;

			while (it.hasNext()) {

				theRun = it.next();

				isRunning = false;
				try {
					isRunning = theRun.pollProcess(now);
				} catch (Throwable t) {
					AppCore.log(AppCore.ERROR_MESSAGE, "pollProcess() failed", t);
					theRun.killProcess(false);
					isRunning = false;
				}

				if (!isRunning) {

					it.remove();

					if (theRun.hasOutput()) {
						hasOutput = true;
					}

					if (theRun.didProcessFail() && !ignoreFailedRuns.contains(theRun)) {
						runState = RUN_STATE_RESTORE;
						runFailed = true;
						runFailedMessage = "*** Study process failed ***";
						updateControls();
					}
				}
			}

			// If needed, update a count of sources or scenarios studied along with an estimated time to complete.  The
			// update flag is set whenever an engine process gives feedback indicating it has completed an item, the
			// exact mechanism varies between the baseline study and full pair study but the concept is the same, see
			// details in processResponseConfirmed() and processLogMessage().  However in the pair study phase the
			// count of items (scenarios) is scaled according to the number of sources for the time estimate.

			if (!studyRuns.isEmpty() && updateRunStatus) {
				int totalCount = 0, doneCount = 0;
				if (RUN_STATE_PRERUN == runState) {
					totalCount = runStatusTotalCount;
					doneCount = runStatusDoneCount;
				} else {
					if (RUN_STATE_RUNNING == runState) {
						totalCount = studyBuild.scenarioCount;
						doneCount = studyBuild.scenarioCount - scenarioRunList.size();
					}
				}
				if (totalCount > 0) {
					String status;
					String progress = AppCore.formatCount(doneCount) + " of " +
						AppCore.formatCount(totalCount) + " items done";
					if (doneCount == totalCount) {
						status = progress + ", runs finishing...";
					} else {
						if (0L == runStatusStartTime) {
							status = progress + ", waiting for start...";
						} else {
							double fractionDone = (double)runStatusDoneCount / (double)runStatusTotalCount;
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
					statusLabel.setText(status);
				}
				updateRunStatus = false;
			}

			// If any still running, that's all for now.

			if (!studyRuns.isEmpty()) {
				return;
			}

			// All processes done.  In the running state do a fail-safe check to be sure all scenarios were run, in
			// case processes exited but didn't report errors.

			if ((RUN_STATE_RUNNING == runState) && !runFailed && !scenarioRunList.isEmpty()) {

				runState = RUN_STATE_RESTORE;
				runFailed = true;
				runFailedMessage = "*** Study failed, did not process all scenarios ***";
				updateControls();

			} else {

				// Proceed to the next state as needed.

				if (RUN_STATE_PRERUN == runState) {
					runState = RUN_STATE_RUNNING;
					statusLabel.setText("Running pair study scenarios");
					startRunning();
					updateControls();
					return;
				}

				if (RUN_STATE_RUNNING == runState) {
					runState = RUN_STATE_POSTRUN;
					statusLabel.setText("Post-processing study results");
					startPostrun();
					updateControls();
					return;
				}

				if (RUN_STATE_POSTRUN == runState) {
					runState = RUN_STATE_RESTORE;
					statusLabel.setText("Restoring study database");
					updateControls();
				}
			}
		}

		// Restore state runs on a secondary thread, similar to build state above.  Errors during the restore do not
		// cause the overall state to change to failed.  When the thread exits, inform the study manager of another
		// lock state change.  See StudyBuildPair.restoreStudy().

		if (RUN_STATE_RESTORE == runState) {

			if (null == restoreThread) {

				restoreThread = new Thread() {
					public void run() {
						try {
							restoreStudy();
						} catch (Throwable t) {
						}
					}
				};
				restoreThread.start();

				return;

			} else {

				if (restoreThread.isAlive()) {
					return;
				}

				restoreThread = null;

				runState = RUN_STATE_EXITING;
				statusLabel.setText("Study complete");
				updateControls();

				// Tell the study manager this object is done with the study, something else can use it now.

				originalParent.editorClosing(this);
			}
		}

		// Final fall through, must be in exiting state.  Set exit, inform the task manager this is done, and delete
		// the temporary output files.

		runState = RUN_STATE_EXIT;
		if (runFailed) {
			statusLabel.setText(runFailedMessage);
		}

		if (null != task) {
			AppTask.taskDone(task);
			task = null;
		}

		for (File theFile : outFiles) {
			theFile.delete();
		}
		outFiles.clear();

		updateControls();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Build the study.  This is assumed to be called on a secondary thread, it may block for a considerable time.
	// Any errors or messages from the build process are displayed in the baseline process panel.

	private void buildStudy() {

		if (RUN_STATE_BUILD != runState) {
			buildFailed = true;
			return;
		}

		String errmsg = "";

		// Logger to accumulate errors and other messages for display in the baseline panel.

		ErrorLogger errors = new ErrorLogger();

		Study theStudy = studyBuild.buildStudy(errors);
		if (null == theStudy) {
			buildFailed = true;
		} else {

			// Study build succeeded, change the study lock from edit to run.

			if (errors.hasMessages()) {
				errors.reportMessage(errors.getMessages());
				errors.clearMessages();
			}

			DbConnection db = DbCore.connectDb(getDbID(), errors);
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
					errors.reportError(errmsg);
				}

			} else {
				buildFailed = true;
			}
		}

		// Show errors and messages in the baseline process panel display area, has to be done on the event thread.

		if (errors.hasErrors()) {
			final String message = "\n" + errors.toString() + "\n";
			SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					baselinePanel.displayLogMessage(message);
				}
			});
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Start the baseline run state.  This studies just the baseline scenario.  The run creates a temporary data file
	// that will be post-processed along with the pair runs, but it also has an important side-effect of pre-processing
	// all sources in the database, i.e. completing all replications, so the later pair runs can all run with just the
	// shared run lock.  The user may also select output files from this run.

	private void startPrerun() {

		ArrayList<String> arguments = new ArrayList<String>();

		String lockID = String.valueOf(lockCount);

		File outFile = new File(filePath, "outtemp_" + lockID + "_0");

		arguments.add(AppCore.libDirectoryPath + File.separator + AppCore.STUDY_ENGINE_NAME);

		if (AppCore.Debug) {
			arguments.add("-d");
		}

		arguments.add("-w");
		arguments.add(AppCore.workingDirectoryPath);
		arguments.add("-o");
		arguments.add(AppCore.outDirectoryPath);
		arguments.add("-i");

		arguments.add("-h");
		arguments.add(DbCore.getDbHostname(getDbID()));
		arguments.add("-b");
		arguments.add(DbCore.getDbName(getDbID()));
		arguments.add("-u");
		arguments.add(DbCore.getDbUsername(getDbID()));

		arguments.add("-l");
		arguments.add(lockID);
		arguments.add("-k");

		arguments.add("-n");
		arguments.add(lockID);

		arguments.add("-f");
		arguments.add("\""+fileOutputConfig.getCodes()+"\"");
		arguments.add("-e");
		arguments.add("\""+mapOutputConfig.getCodes()+"\"");

		// Include some run information in the comment field, followed by any user-provided comment.

		StringBuilder com = new StringBuilder();
		com.append("Pair study baseline run\n");
		com.append("Study country: ");
		if (null == studyBuild.studyCountry) {
			com.append("All");
		} else {
			com.append(studyBuild.studyCountry.name);
		}
		com.append('\n');
		com.append("Study channels:");
		for (int chan : studyBuild.studyChannels) {
			com.append(' ');
			com.append(String.valueOf(chan));
		}
		com.append('\n');
		com.append("\""+runComment+"\"");
		arguments.add("-c");
		arguments.add("\""+com.toString()+"\"");

		arguments.add(String.valueOf(studyKey));

		arguments.add(String.valueOf(studyBuild.baselineScenarioKey));

		// The baseline panel already exists and is in the display.

		baselinePanel.setProcessArguments(arguments);

		studyRuns.add(baselinePanel);

		outFiles.add(outFile);

		runStatusStartTime = 0L;
		runStatusTotalCount = studyBuild.baselineSourceCount;
		runStatusRunningCount = 0;
		runStatusDoneCount = 0;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Start the running state, queue up all the actual study runs.

	private void startRunning() {

		// Get the run list from the build.

		scenarioRunList = studyBuild.getScenarioRunList();

		// Change the study lock to run shared.

		boolean error = false;
		String errorMessage = "";

		DbConnection db = DbCore.connectDb(getDbID());
		if (null != db) {
			try {

				db.update("LOCK TABLES study WRITE");

				db.query("SELECT study_lock, lock_count FROM study WHERE study_key = " + studyKey);

				if (db.next()) {

					if ((db.getInt(1) == studyLock) && (db.getInt(2) == lockCount)) {

						db.update("UPDATE study SET study_lock = " + Study.LOCK_RUN_SHARE +
							", lock_count = lock_count + 1, share_count = 1 WHERE study_key = " + studyKey);
						studyLock = Study.LOCK_RUN_SHARE;
						lockCount++;

					} else {
						error = true;
						errorMessage = "*** The study lock was modified ***";
					}

				} else {
					error = true;
					errorMessage = "*** The study was deleted ***";
				}

			} catch (SQLException se) {
				error = true;
				errorMessage = "*** Error - " + se + " ***";
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
			errorMessage = "*** Could not open database connection ***";
		}

		// If no error, set up to start the runs.  Multiple processes may run simultaneously, first determine the
		// run count.  That value started as user input setting a maximum.  Logic in StudyBuildPair may have reduced
		// that count based on the number of scenarios.  Here, it may be further reduced based on the memory fraction.
		// Note maxEngineProcessCount may be <= 0 indicating there is either not enough memory to run the engine, or
		// the engine executable isn't properly installed.  In that case this should never be reached at all, but
		// regardless runCount is never less than 1 here.  If there is an engine problem an error will happen later.

		if (!error) {

			int runCount = studyBuild.runCount;
			if ((runCount > 1) && (memoryFraction < 1.)) {
				int maxCount = (int)(memoryFraction * (double)AppCore.maxEngineProcessCount);
				if (maxCount < 1) {
					maxCount = 1;
				}
				if (runCount > maxCount) {
					runCount = maxCount;
				}
			}

			// The memory limit argument actually given to the engine is the reciprocal of the fraction as an integer,
			// in other words it is the total number of engine processes expected to run concurrently.

			String nProc = String.valueOf((int)Math.rint((double)runCount / memoryFraction));

			// A run ID number is given to each process to use for temporary file names, based on combining the current
			// lock count (which will always be unique for the study) with the run number.  This must be in sync with
			// code in the study engine for naming of the files.  It assumes the directory path was established by the
			// engine in the baseline run so all directories will already exist.  The scenario keys run by each process
			// will be provided dynamically, see the ProcessController methods, the trigger for that behavior in the
			// engine is to pass a '*' as the first scenario key argument.

			String runID;
			File outFile;
			ArrayList<String> arguments;
			ProcessPanel theRun;

			String theHost = DbCore.getDbHostname(getDbID());
			String theName = DbCore.getDbName(getDbID());
			String theUser = DbCore.getDbUsername(getDbID());

			String lockID = String.valueOf(lockCount);

			// These runs generate only the pair study cell file, all other output is off.

			OutputConfig conf = new OutputConfig(OutputConfig.CONFIG_TYPE_FILE, "");
			conf.flags[OutputConfig.CELL_FILE_PAIRSTUDY] = 1;
			String outCodes = conf.getCodes();

			for (int runNumber = 0; runNumber < runCount; runNumber++) {

				runID = String.valueOf((lockCount * runCount) + runNumber);

				outFile = new File(filePath, "outtemp_" + runID + "_0");

				arguments = new ArrayList<String>();

				arguments.add(AppCore.libDirectoryPath + File.separator + AppCore.STUDY_ENGINE_NAME);

				if (AppCore.Debug) {
					arguments.add("-d");
				}

				arguments.add("-w");
				arguments.add(AppCore.workingDirectoryPath);
				arguments.add("-o");
				arguments.add(AppCore.outDirectoryPath);
				arguments.add("-i");

				arguments.add("-h");
				arguments.add(theHost);
				arguments.add("-b");
				arguments.add(theName);
				arguments.add("-u");
				arguments.add(theUser);

				arguments.add("-l");
				arguments.add(lockID);
				arguments.add("-k");

				arguments.add("-n");
				arguments.add(runID);

				arguments.add("-x");

				arguments.add("-m");
				arguments.add(nProc);

				arguments.add("-f");
				arguments.add("\""+outCodes+"\"");
				arguments.add("-e");
				arguments.add("\""+OutputConfig.NO_OUTPUT_CODE+"\"");

				arguments.add("\""+String.valueOf(studyKey)+"\"");

				arguments.add("*");

				theRun = new ProcessPanel(this, "Study", DbCore.getDbPassword(getDbID()), mergeLineStrings);
				theRun.setProcessController(this);

				theRun.setProcessArguments(arguments);

				studyRuns.add(theRun);

				outFiles.add(outFile);
			}
		}

		// If any error occurred put message in the UI, set failure flag, and go to restore.  Just to be paranoid,
		// kill any processes that might have been set up before the error, so they can never start.

		if (error) {

			runState = RUN_STATE_RESTORE;
			runFailed = true;
			runFailedMessage = errorMessage;
			updateControls();

			for (ProcessPanel theRun : studyRuns) {
				theRun.killProcess(false);
			}
			studyRuns.clear();

			return;
		}

		// Display the run UIs, the poll method does the rest, the processes will start the first time they are polled.

		int runNumber = 1;
		for (ProcessPanel theRun : studyRuns) {
			tabPane.addTab("Run " + runNumber++, theRun);
		}

		runStatusStartTime = 0L;
		runStatusTotalCount = studyBuild.scenarioSourceCount;
		runStatusRunningCount = 0;
		runStatusDoneCount = 0;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Start the post-run state, do the data post-processing.  A bit of that is local, but most of it is handled by a
	// separate external utility for performance reasons.  First the local part, write the stations list output file,
	// along the way determine the maximum facility ID value.  For historical reasons this is done by re-querying the
	// source records.  That used to be necessary to get the NAD83 coordinates; those are in the Source model now so
	// this could draw from the in-memory state, but this is working fine so why mess with it.

	private void startPostrun() {

		boolean error = false;
		String errorMessage = null;

		int maxFacilityID = 0;

		BufferedWriter stationsWriter = null;

		String rootName = DbCore.getDbName(getDbID());

		DbConnection db = DbCore.connectDb(getDbID());
		if (null != db) {
			try {

				stationsWriter = new BufferedWriter(new FileWriter(new File(filePath, "stations.csv")));

				db.setDatabase(rootName + "_" + studyKey);

				db.query(
				"SELECT " +
					"source.facility_id, " +
					"source.channel, " +
					"scenario_source.is_desired, " +
					"scenario_source.is_undesired, " +
					"service.service_code, " +
					"source.call_sign, " +
					"source.city, " +
					"source.state, " +
					"country.country_code, " +
					"source.status, " +
					"source.file_number, " +
					"source.latitude, " +
					"source.longitude " +
				"FROM " +
					"scenario_source " +
					"JOIN source USING (source_key) " +
					"JOIN " + rootName + ".service USING (service_key) " +
					"JOIN " + rootName + ".country USING (country_key) " +
				"WHERE " +
					"scenario_source.scenario_key = " + studyBuild.baselineScenarioKey + " " +
					"AND (scenario_source.is_desired OR scenario_source.is_undesired) " +
				"ORDER BY " +
					"source.country_key, source.state, source.city, source.channel");

				int facID;

				while (db.next()) {

					facID = db.getInt(1);
					if (facID > maxFacilityID) {
						maxFacilityID = facID;
					}

					stationsWriter.write(
						String.format(Locale.US, "%d,%d,%d,%d,%s,\"%s\",\"%s\",%s,%s,%s,\"%.22s\",%f,%f\n",
							facID, db.getInt(2), db.getInt(3), db.getInt(4), db.getString(5), db.getString(6),
							db.getString(7), db.getString(8), db.getString(9), db.getString(10), db.getString(11),
							db.getDouble(12), db.getDouble(13)));
				}

			} catch (SQLException se) {
				error = true;
				errorMessage = "*** Error - " + se + " ***";
				db.reportError(se);

			} catch (IOException ie) {
				error = true;
				errorMessage = "*** Error - " + ie + " ***";
			}

			DbCore.releaseDb(db);

			if (null != stationsWriter) {
				try {stationsWriter.close();} catch (IOException ie) {}
			}

		} else {
			error = true;
			errorMessage = "*** Could not open database connection ***";
		}

		// If an error occurred, straight to restore.

		if (error) {

			runState = RUN_STATE_RESTORE;
			runFailed = true;
			runFailedMessage = errorMessage;
			updateControls();

			return;
		}

		// Start the post-processing run.

		ArrayList<String> arguments = new ArrayList<String>();

		arguments.add(AppCore.libDirectoryPath + File.separator + "pair_study_post.exe");

		arguments.add(filePath);

		arguments.add(String.valueOf(maxFacilityID));

		for (File theFile : outFiles) {
			arguments.add(theFile.getName());
		}

		ArrayList<String> mergeLines = new ArrayList<String>();
		mergeLines.add("Completed ");

		ProcessPanel theRun = new ProcessPanel(this, "Process", null, mergeLines);
		theRun.setProcessController(this);
		theRun.setStatusPanelVisible(false);

		theRun.setProcessArguments(arguments);

		studyRuns.add(theRun);

		tabPane.addTab("Post", theRun);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Restore the study, called on a secondary thread.  Errors here are essentially ignored, the state will proceed
	// to exiting in any case, and a failure here does not invalidate any of the study results so it should not alter
	// a successful result to this point.  However error messages will be displayed in the baseline log.

	private void restoreStudy() {

		if (RUN_STATE_RESTORE != runState) {
			return;
		}

		// Copy the current lock state to and from the study build object.

		studyBuild.baseStudy.studyLock = studyLock;
		studyBuild.baseStudy.lockCount = lockCount;

		ErrorLogger errors = new ErrorLogger();

		boolean error = !studyBuild.restoreStudy(errors);

		studyLock = studyBuild.baseStudy.studyLock;
		lockCount = studyBuild.baseStudy.lockCount;

		// If no error, unlock the study.

		if (!error) {

			String errorMessage = "";

			DbConnection db = DbCore.connectDb(getDbID());
			if (null != db) {
				try {

					db.update("LOCK TABLES study WRITE");

					db.query("SELECT study_lock, lock_count FROM study WHERE study_key=" + studyKey);

					if (db.next()) {

						if ((db.getInt(1) == studyLock) && (db.getInt(2) == lockCount)) {

							db.update("UPDATE study SET study_lock = " + Study.LOCK_NONE +
								", lock_count = lock_count + 1, share_count = 0 WHERE study_key = " + studyKey);
							studyLock = Study.LOCK_NONE;
							lockCount++;

						} else {
							error = true;
							errorMessage = "Could not unlock study, the lock was modified.";
						}

					} else {
						error = true;
						errorMessage = "Could not unlock study, the study was deleted.";
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
				errorMessage = "Could not open database connection.";
			}

			if (error) {
				errors.reportError(errorMessage);
			}
		}

		if (error) {
			final String message = "\n" + errors.toString() + "\n";
			SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					baselinePanel.displayLogMessage(message);
				}
			});
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// ProcessController interface methods.  When this method is called, a prompt was issued by one of the running
	// engine processes.  Currently this can only be a prompt for another scenario key, just pass them out in queued
	// sequence.  However the response must be confirmed by the process, so this moves each key into a pending state
	// per process until that is either confirmed or the process fails.  If there are no more scenarios, respond just
	// with the prompting prefix which the process will interpret to mean clean up and exit, however even that has to
	// be confirmed by the process.  See ProcessPanel.  Return null on invalid calls, calling process will be killed.

	public String getProcessResponse(ProcessPanel thePanel, String thePrompt) {

		if (RUN_STATE_RUNNING != runState) {
			return null;
		}

		if (!studyRuns.contains(thePanel) || studyRunsPending.containsKey(thePanel)) {
			return null;
		}

		if (0L == runStatusStartTime) {
			runStatusStartTime = System.currentTimeMillis();
		}

		StudyBuildPair.ScenarioSortItem theItem = scenarioRunList.poll();
		studyRunsPending.put(thePanel, theItem);

		if (null == theItem) {
			return AppCore.ENGINE_PROMPT_PREFIX;
		} else {
			return String.valueOf(theItem.key);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Reports confirmation of a response sent to a process, consider the scenario as successfully running.

	public void processResponseConfirmed(ProcessPanel thePanel) {

		if (RUN_STATE_RUNNING != runState) {
			return;
		}

		StudyBuildPair.ScenarioSortItem theItem = studyRunsPending.remove(thePanel);
		if (null != theItem) {
			runStatusDoneCount += theItem.sourceCount;
			updateRunStatus = true;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Reports failure of a process being controlled.

	// If the process failed while not in the prompt-response pending state this is a true failure that must abort the
	// entire study.  That will be detected and handled by checkRuns() automatically.  But in that case there is no
	// point to continuing with other scenario runs since the overall study has failed, so this empties the scenario
	// queue causing all other processes to exit at the next prompt.

	// If the process failed while in the pending state, this may attempt to recover and finish the overall study.  The
	// failed process was in a "safe" state where any previous scenario runs were completed without error, most likely
	// the failure had to do with the interactive prompt-response protocol.  In that case as long as at least one other
	// process is still able to continue running scenarios, the pending scenario can just be re-queued and the overall
	// study may still finish.  In that case this process is placed in the ignoreFailedRuns list so checkRuns() does
	// not count the failure as aborting the study.  If there are no other processes, as above the queue is cleared.

	// If the process failed in the pending state but the pending response is the exit command, the process can just
	// immediately be placed in the ignoreFailedRuns list regardless of whether or not any other processes are still
	// running, since there is no pending scenario to re-queue.  However when checking for other processes that could
	// process a re-queued scenario, any that have the exit command pending can't be counted.

	public void processFailed(ProcessPanel thePanel) {

		if (RUN_STATE_RUNNING != runState) {
			return;
		}

		if (!studyRuns.contains(thePanel)) {
			return;
		}

		if (studyRunsPending.containsKey(thePanel)) {

			StudyBuildPair.ScenarioSortItem theItem = studyRunsPending.remove(thePanel);

			if (null == theItem) {
				ignoreFailedRuns.add(thePanel);
			} else {

				boolean hasOther = false;
				for (ProcessPanel otherRun : studyRuns) {
					if (otherRun.isProcessRunning()) {
						if (studyRunsPending.containsKey(otherRun)) {
							if (null != studyRunsPending.get(otherRun)) {
								hasOther = true;
								break;
							}
						} else {
							hasOther = true;
							break;
						}
					}
				}
				if (hasOther) {
					scenarioRunList.push(theItem);
					ignoreFailedRuns.add(thePanel);
				} else {
					scenarioRunList.clear();
				}
			}

		} else {
			scenarioRunList.clear();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Reports a process exited cleanly.  If not in the prompt-response pending state do nothing, that means all is
	// well.  If in the pending state, this shouldn't have happened.  If the pending response was the exit command
	// ignore it, if not simply re-queue the pending scenario.  If there are other processes still active they will
	// pick up the scenario at next prompt.  If not, a fail-safe check in checkRuns() will consider the overall study
	// as failed if all processes have exited but the scenario queue is not empty.

	public void processComplete(ProcessPanel thePanel) {

		if (RUN_STATE_RUNNING != runState) {
			return;
		}

		StudyBuildPair.ScenarioSortItem theItem = studyRunsPending.remove(thePanel);
		if (null != theItem) {
			scenarioRunList.push(theItem);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// In the baseline study phase, status messages are used to follow the study state and update run status and
	// remaining-time estimate.  In the pair study phase, that is handled through the prompt-and-response methods
	// above.  In either case, update of the status happens in checkRuns().

	public void processStatusMessage(ProcessPanel thePanel, String theKey, String theData) {

		if (RUN_STATE_PRERUN != runState) {
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
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isRunning() {

		if (RUN_STATE_EXIT != runState) {
			return true;
		}
		return false;
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
	// An abort action is not available in init, restore, exiting, or exit.  In wait or in build before the build
	// thread has been created, just set exiting.  In build if the thread is running, set the abort flag in the build
	// object, if it can abort it will and poll() will detect that.  In prerun, running, or postrun, send a kill to all
	// processes, again poll() will see the runs die and advance.

	public void abort() {

		if ((RUN_STATE_WAIT == runState) || ((RUN_STATE_BUILD == runState) && (null == buildThread))) {
			runState = RUN_STATE_EXITING;
			runFailed = true;
			runFailedMessage = "*** Study aborted ***";
			updateControls();
			return;
		}
		if (RUN_STATE_BUILD == runState) {
			studyBuild.abort();
			return;
		}
		if ((RUN_STATE_PRERUN == runState) || (RUN_STATE_RUNNING == runState) ||
				(RUN_STATE_POSTRUN == runState)) {
			for (ProcessPanel theRun : studyRuns) {
				theRun.killProcess(false);
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getStatus() {

		switch (runState) {

			case RUN_STATE_INIT:
			case RUN_STATE_WAIT:
				return "Waiting";

			case RUN_STATE_BUILD:
				return "Building study";

			case RUN_STATE_PRERUN:
				if (runStatusTotalCount > 0) {
					return "Baseline " + AppCore.formatCount(runStatusDoneCount) + " of " +
						AppCore.formatCount(runStatusTotalCount);
				} else {
					return "Baseline";
				}

			case RUN_STATE_RUNNING:
				if (studyBuild.scenarioCount > 0) {
					return "Running " +
						AppCore.formatCount(studyBuild.scenarioCount - scenarioRunList.size()) + " of " +
						AppCore.formatCount(studyBuild.scenarioCount);
				} else {
					return "Running";
				}

			case RUN_STATE_POSTRUN:
			case RUN_STATE_RESTORE:
				return "Post-process";

			case RUN_STATE_EXITING:
				return "Exiting";

			case RUN_STATE_EXIT:
				if (runFailed) {
					return "Failed";
				} else {
					return "Complete";
				}
		}

		return "Unknown";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean hasOutput() {

		if (RUN_STATE_EXIT == runState) {
			return hasOutput;
		}
		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This concatenates output from all the run panels showing in the tab pane.  At least one panel actually has some
	// output to save otherwise the hasOutput flag would not be set, see poll().  However this is a one-shot operation
	// since the panels delete their temporary log files once saved, so the flag is cleared after.

	public void writeOutputTo(Writer theWriter) throws IOException {

		if ((RUN_STATE_EXIT != runState) || !hasOutput) {
			return;
		}

		Component c;
		ProcessPanel p;
		for (int i = 0; i < tabPane.getTabCount(); i++) {
			c = tabPane.getComponentAt(i);
			if (c instanceof ProcessPanel) {
				p = (ProcessPanel)c;
				if (p.hasOutput()) {
					theWriter.write("\n\n------------------------ Output from " + tabPane.getTitleAt(i) +
						" ------------------------\n\n");
					p.writeOutputTo(theWriter);
				}
			}
		}

		hasOutput = false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void saveOutput() {

		if ((RUN_STATE_EXIT != runState) || !hasOutput) {
			return;
		}

		String title = "Save Run Outputs";
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
			writeOutputTo(theWriter);
		} catch (IOException ie) {
			errorReporter.reportError("Could not write to the file:\n" + ie.getMessage());
		}

		try {
			theWriter.close();
		} catch (IOException ie) {
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Currently no report information from this type of run.

	public boolean hasReport() {

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void saveReport() {

		return;
	}
}
