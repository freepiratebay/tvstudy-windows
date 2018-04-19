//
//  RunPanel.java
//  TVStudy
//
//  Copyright (c) 2012-2016 Hammett & Edison, Inc.  All rights reserved.

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
// Abstract panel superclass used by StudyRunManager to manage study building and running operations.  This implements
// StudyLockHolder for StudyManager to track open studies.  Properties are set directly, then initialize() does setup.
// The output configuration must be set, memory fraction and comment are optional, defaults will be used if not set.

public abstract class RunPanel extends AppPanel implements StudyLockHolder {

	public static final String LOG_FILE_NAME = "log.txt";

	public OutputConfig fileOutputConfig;
	public OutputConfig mapOutputConfig;

	public double memoryFraction;
	public String runComment;

	// The lock state, these may be set immediately after construction, or sometimes later if a new study has to be
	// built before the study and lock exist; initialize() does not validate these, subclass must if needed.

	public int studyKey;
	public int studyType;
	public int studyLock;
	public int lockCount;

	public String studyName;

	// If set, the run manager will automatically save the run output when done, regardless of errors.  This will be
	// set from the LOG_FILE output file flag in initialize(), but not cleared by that, so UI may also set it directly.

	public boolean autoSaveOutput;

	// If set, the run manager will remove the panel from display when the run completes without error.

	public boolean autoRemove;

	// Process output messages merged together, used for ProcessPanel construction.

	protected static ArrayList<String> mergeLineStrings;
	static {
		mergeLineStrings = new ArrayList<String>();
		mergeLineStrings.add("Grid and cell setup for sourceKey");
		mergeLineStrings.add("Cell setup for sourceKey");
	};

	// Set by subclass initialize() implementation on successful validation and setup.

	protected boolean initialized;


	//-----------------------------------------------------------------------------------------------------------------

	public RunPanel(AppEditor theParent) {

		super(theParent);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Subclass must always override, call super if desired but always set initialized flag directly.

	public boolean initialize(ErrorReporter errors) {

		if (initialized) {
			return true;
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

		// Silently fix an invalid memory fraction.  Note if maxEngineProcessCount is <=0 the run is going to fail in
		// any case so the fraction is irrelevant.  In that case pre-checks should mean this is never even reached.

		if ((memoryFraction <= 0.) || (memoryFraction > 1.) || (AppCore.maxEngineProcessCount <= 0)) {
			memoryFraction = 1.;
		} else {
			double minFrac = 1. / (double)AppCore.maxEngineProcessCount;
			if (memoryFraction < minFrac) {
				memoryFraction = minFrac;
			}
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
	// StudyLockHolder methods.  Usually don't have to be overridden.

	public int getStudyKey() {

		return studyKey;
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
	// This is testing whether the enclosing window is visible, meaning the run manager, not the panel itself.  The
	// run manager typically displays only one panel at a time, but non-visible panels are still active.  The study
	// manager uses this to determine if toFront() can be called to bring the top-level window representing whatever
	// is holding the lock to front.  The assumption here is that the original AppPanel parent is the study manager,
	// and the current parent, if different, is the run manager.

	public boolean isVisible() {

		if (parent != originalParent) {
			return parent.getWindow().isVisible();
		}
		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void toFront() {

		if (parent != originalParent) {
			parent.getWindow().toFront();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This means go away so the lock can be released.  If still running that can't happen.  Otherwise notify both the
	// study manager and the run manager (see comment above), the study manager will release the lock and the run
	// manager will remove this from display.

	public boolean closeWithoutSave() {

		if (isRunning()) {
			return false;
		}

		originalParent.editorClosing(this);
		if (parent != originalParent) {
			parent.editorClosing(this);
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Not in StudyLockHolder, but closely related so usually does not have to be overridden.

	public String getStudyName() {

		if (null == studyName) {
			return "New Study";
		}
		return studyName;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Do a check to see if disk space needed for the run is available, other UI might want to warn the user if this
	// returns false, e.g. see RunStart.  Typically overridden, but not required as this is advisory only.

	public boolean isDiskSpaceAvailable() {

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This is called regularily by the run manager to monitor and progress run state.  Argument is system clock time.
	// Run state is defined by the subclass however desired.  The abort() call is a request only, it may be ignored.

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

	public abstract void abort();


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

		if (null == studyName) {
			return;
		}

		String filePath = AppCore.outDirectoryPath + File.separator + DbCore.getHostDbName(getDbID()) +
			File.separator + studyName;
		File theFile = new File(filePath, LOG_FILE_NAME);

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
