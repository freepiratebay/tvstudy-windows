//
//  RunPanelProcess.java
//  TVStudy
//
//  Copyright (c) 2017 Hammett & Edison, Inc.  All rights reserved.

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
// RunPanel subclass for a generic command-line process.  Mostly a thin wrapper for ProcessPanel.

public class RunPanelProcess extends RunPanel {

	private static final int RUN_STATE_INIT = 0;
	private static final int RUN_STATE_WAIT = 1;
	private static final int RUN_STATE_RUNNING = 2;
	private static final int RUN_STATE_EXITING = 3;
	private static final int RUN_STATE_EXIT = 4;

	private int runState = RUN_STATE_INIT;
	private boolean runFailed;
	private boolean runCanceled;

	private AppTask task;
	private boolean taskWaiting;

	private ArrayList<String> processArguments;
	private ProcessPanel processPanel;


	//-----------------------------------------------------------------------------------------------------------------
	// This may optionally have a call-back when the process is done.

	public RunPanelProcess(String theName, ArrayList<String> theArgs) {

		super();

		runName = theName;
		processArguments = new ArrayList<String>(theArgs);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public RunPanelProcess(String theName, ArrayList<String> theArgs, Runnable theCallBack) {

		super(theCallBack);

		runName = theName;
		processArguments = new ArrayList<String>(theArgs);
	}


	//-----------------------------------------------------------------------------------------------------------------

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

		task = new AppTask(memoryFraction);

		processPanel = new ProcessPanel(parent, runName, null, null);
		processPanel.setProcessArguments(processArguments);

		setLayout(new BorderLayout());
		add(processPanel, BorderLayout.CENTER);

		runState = RUN_STATE_WAIT;
		runFailed = false;

		initialized = true;

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void poll(long now) {

		if (!initialized) {
			return;
		}

		if (RUN_STATE_EXIT == runState) {
			if (null != task) {
				AppTask.taskDone(task);
				task = null;
			}
			return;
		}

		if (RUN_STATE_WAIT == runState) {

			// The process panel will report running even before starting, but it may have been canceled.

			if (!processPanel.isProcessRunning()) {
				runState = RUN_STATE_EXITING;
				runFailed = processPanel.didProcessFail();
				runCanceled = processPanel.wasProcessCanceled();
			} else {

				if (!AppTask.canTaskStart(task)) {
					if (!taskWaiting) {
						taskWaiting = true;
						processPanel.setStatusMessage("Waiting for other activity to complete...");
					}
					return;
				}
				taskWaiting = false;

				runState = RUN_STATE_RUNNING;
				processPanel.setStatusMessage("Activity running...");
			}
		}

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

			if (isRunning) {
				return;
			}
		}

		runState = RUN_STATE_EXIT;
		if (runFailed) {
			processPanel.stopProcess(false);
		}

		if (null != task) {
			AppTask.taskDone(task);
			task = null;
		}

		if (null != callBack) {
			callBack.run();
		}
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
	// The abort is fully handled by the process panel so just forward to stopProcess().  But for UI labelling,
	// abortWillCancel() will return true only before the process has actually started.

	public void abort() {

		processPanel.stopProcess(true);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean abortWillCancel() {

		if ((RUN_STATE_INIT == runState) || (RUN_STATE_WAIT == runState)) {
			return true;
		}
		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getStatus() {

		switch (runState) {

			case RUN_STATE_INIT:
			case RUN_STATE_WAIT:
				return "Waiting";

			case RUN_STATE_RUNNING:
				return "Running";

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

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void saveReport() {
	}
}
