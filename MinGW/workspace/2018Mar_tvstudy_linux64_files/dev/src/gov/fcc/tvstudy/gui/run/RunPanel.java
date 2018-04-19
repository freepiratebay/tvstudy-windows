//
//  RunPanel.java
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui.run;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.gui.*;

import java.util.*;
import java.io.*;
import java.awt.*;
import java.awt.event.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;


//=====================================================================================================================
// Abstract panel superclass used by RunManager to manage running processes.  Properties are set directly, then
// initialize() does setup.  Memory fraction and comment are optional, defaults will be used if not set.

public abstract class RunPanel extends AppPanel {

	public static final String LOG_FILE_NAME = "log.txt";

	public double memoryFraction = 1.;
	public String runComment;

	public String runName;

	// If set, the run manager will automatically save the run output when done, regardless of errors.

	public boolean autoSaveOutput;

	// If set, the run manager will remove the panel from display when the run completes without error.

	public boolean autoRemove;

	// Set by subclass initialize() implementation on successful validation and setup.

	protected boolean initialized;


	//-----------------------------------------------------------------------------------------------------------------
	// Run panels are only displayed in the run manager so that is always the parent.

	public RunPanel() {

		super(RunManager.getRunManager());
	}


	//-----------------------------------------------------------------------------------------------------------------

	public RunPanel(Runnable theCallBack) {

		super(RunManager.getRunManager(), theCallBack);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Runs are not necessarily associated with a database, if they are subclass must override to provide ID.

	public String getDbID() {

		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Subclass must always override, call super if desired but always set initialized flag directly.

	public boolean initialize(ErrorReporter errors) {

		if (initialized) {
			return true;
		}

		// Silently fix an invalid memory fraction.

		if (memoryFraction < 0.) {
			memoryFraction = 0.;
		}
		if (memoryFraction > 1.) {
			memoryFraction = 1.;
		}

		// Comment should not be null, set an empty string if needed.

		if (null != runComment) {
			runComment = runComment.trim();
		} else {
			runComment = "";
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isInitialized() {

		return initialized;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getRunName() {

		if (null == runName) {
			return "(unknown)";
		}
		return runName;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Do a check to see if disk space needed for the run is available, other UI might want to warn the user if this
	// returns false, e.g. see RunStart.  Typically overridden, but not required as this is advisory only.

	public boolean isDiskSpaceAvailable() {

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This is called regularily by the run manager to monitor and progress run state.  Argument is system clock time.
	// Run state is defined by the subclass however desired.

	public abstract void poll(long now);


	//-----------------------------------------------------------------------------------------------------------------

	public abstract boolean isRunning();


	//-----------------------------------------------------------------------------------------------------------------

	public abstract boolean isWaiting();


	//-----------------------------------------------------------------------------------------------------------------

	public abstract void bumpTask();


	//-----------------------------------------------------------------------------------------------------------------

	public abstract boolean runFailed();


	//-----------------------------------------------------------------------------------------------------------------
	// An abort() is a request only, it is not guaranteed to stop activity immediately but implementation should make
	// all effort to react promptly.  When activity can be stopped gracefully with no undesired side-effects,
	// abortWillCancel() should return true; in that case the UI will label the action as cancel, not abort.

	public abstract void abort();


	//-----------------------------------------------------------------------------------------------------------------

	public boolean abortWillCancel() {

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public abstract String getStatus();


	//-----------------------------------------------------------------------------------------------------------------
	// These are typically wrappers for methods in ProcessPanel, but some subclasses may have multiple such panels.

	public abstract boolean hasOutput();


	//-----------------------------------------------------------------------------------------------------------------

	public abstract void writeOutputTo(Writer theWriter) throws IOException;


	//-----------------------------------------------------------------------------------------------------------------

	public abstract void saveOutput();


	//-----------------------------------------------------------------------------------------------------------------
	// Used by the run manager when auto-saving the output.

	public void saveOutputToLogFile() {

		if (null == runName) {
			return;
		}

		String thePath, theDbID = getDbID();
		if (null == theDbID) {
			thePath = AppCore.workingDirectoryPath;
		} else {
			thePath = AppCore.outDirectoryPath + File.separator + DbCore.getHostDbName(theDbID) + File.separator +
				runName;
		}
		File theFile = new File(thePath, LOG_FILE_NAME);

		FileWriter theWriter = null;
		try {
			theWriter = new FileWriter(theFile);
		} catch (IOException ie) {
			return;
		}

		try {
			writeOutputTo(theWriter);
		} catch (IOException ie) {
		}

		try {
			theWriter.close();
		} catch (IOException ie) {
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public abstract boolean hasReport();


	//-----------------------------------------------------------------------------------------------------------------

	public abstract void saveReport();
}
