//
//  RunPanelThread.java
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
// RunPanel subclass to call any arbitrary code on a background thread and use the StatusLogger interface to display
// messages from that in a text area.  This is abstract, runActivity() must be overridden to do the desired activity,
// that will be called on the background thread.

public abstract class RunPanelThread extends RunPanel implements StatusLogger {

	public static final int TEXT_AREA_ROWS = 15;
	public static final int TEXT_AREA_COLUMNS = 80;

	private String dbID;

	private JTextArea outputArea;

	private boolean lastLineWasTransient;
	private int lastOutputLineStart;

	private JViewport outputViewport;
	private int autoScrollState;
	private boolean autoScrollLock;

	private JLabel statusLabel;
	private JButton abortButton;
	private JPanel statusPanel;

	private static final int RUN_STATE_INIT = 0;
	private static final int RUN_STATE_WAIT = 1;
	private static final int RUN_STATE_RUNNING = 2;
	private static final int RUN_STATE_CANCELING = 3;
	private static final int RUN_STATE_EXITING = 4;
	private static final int RUN_STATE_EXIT = 5;

	private int runState = RUN_STATE_INIT;
	private boolean runFailed;
	private boolean runCanceled;
	private String runStatus;

	private AppTask task;
	private boolean taskWaiting;

	private ErrorLogger runErrors;
	private String runStatusPending;
	private StringBuilder runMessageLog;
	private Thread runThread;

	private Object runResult;

	private RunPanelThread outerThis = this;


	//-----------------------------------------------------------------------------------------------------------------
	// This may optionally be given a dbID if the runActivity() code will need to use database connections.

	public RunPanelThread(String theName) {

		super();

		runName = theName;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public RunPanelThread(String theName, String theDbID) {

		super();

		runName = theName;
		dbID = theDbID;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public RunPanelThread(String theName, Runnable theCallBack) {

		super(theCallBack);

		runName = theName;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public RunPanelThread(String theName, Runnable theCallBack, String theDbID) {

		super(theCallBack);

		runName = theName;
		dbID = theDbID;
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

		// If a database ID was provided, make sure it remains available.

		if (null != dbID) {
			if (!DbCore.openDb(dbID, this)) {
				errors.reportError("Invalid database connection ID");
				return false;
			}
		}

		task = new AppTask(memoryFraction);

		// Set up UI.  This is a simplified version of what is in ProcessPanel; output is expected to be relatively
		// short so the entire log is kept in the text area with no backing file and line limit as in ProcessPanel.
		// However the transient message over-write and scrollback-lock behaviors from ProcessPanel are implemented.

		outputArea = new JTextArea(TEXT_AREA_ROWS, TEXT_AREA_COLUMNS);
		outputArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
		AppController.fixKeyBindings(outputArea);
		outputArea.setEditable(false);
		outputArea.setLineWrap(true);

		((DefaultCaret)outputArea.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);

		JScrollPane outPane = AppController.createScrollPane(outputArea);
		outputViewport = outPane.getViewport();

		outPane.getVerticalScrollBar().addAdjustmentListener(new AdjustmentListener() {
			public void adjustmentValueChanged(AdjustmentEvent theEvent) {
				if (autoScrollLock) {
					return;
				}
				if (outputViewport.getViewSize().height <= outputViewport.getExtentSize().height) {
					autoScrollState = 0;
				}
				if (0 == autoScrollState) {
					return;
				}
				Adjustable theAdj = theEvent.getAdjustable();
				boolean atBot = ((theAdj.getMaximum() - theAdj.getVisibleAmount() - theEvent.getValue()) < 10);
				if (1 == autoScrollState) {
					if (!atBot) {
						autoScrollState = 2;
					}
				} else {
					if (atBot) {
						autoScrollState = 1;
					}
				}
			}
		});

		statusLabel = new JLabel("Starting...");
		statusLabel.setPreferredSize(AppController.labelSize[60]);

		abortButton = new JButton("Cancel");
		abortButton.setFocusable(false);
		abortButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				abort();
			}
		});

		statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		statusPanel.add(abortButton);
		statusPanel.add(statusLabel);

		setLayout(new BorderLayout());

		add(outPane, BorderLayout.CENTER);
		add(statusPanel, BorderLayout.SOUTH);

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
	// Return null in case of error, returned object is available from getResult().

	public abstract Object runActivity(StatusLogger status, ErrorLogger errors);


	//-----------------------------------------------------------------------------------------------------------------

	public Object getResult() {

		return runResult;
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

				if (null != dbID) {
					DbCore.closeDb(dbID, this);
				}
			}

			return;
		}

		if (RUN_STATE_WAIT == runState) {

			if (runCanceled) {
				runState = RUN_STATE_EXITING;
			} else {

				if (!AppTask.canTaskStart(task)) {
					if (!taskWaiting) {
						taskWaiting = true;
						statusLabel.setText("Waiting for other runs to complete...");
					}
					return;
				}
				taskWaiting = false;

				runErrors = new ErrorLogger();
				runStatusPending = null;
				runMessageLog = new StringBuilder();

				runThread = new Thread() {
					public void run() {
						runResult = runActivity(outerThis, runErrors);
						if (null == runResult) {
							runFailed = !runCanceled;
						}
						if (runErrors.hasMessages()) {
							logMessage(runErrors.getMessages());
							runErrors.clearMessages();
						}
						if (runErrors.hasErrors()) {
							logMessage(runErrors.toString());
						}
					}
				};

				runThread.start();

				runState = RUN_STATE_RUNNING;
				statusLabel.setText("Starting...");
				runStatus = "Starting";
			}
		}

		doUpdate();

		if (RUN_STATE_RUNNING == runState) {
			if (runCanceled) {
				statusLabel.setText("Canceling...");
				runState = RUN_STATE_CANCELING;
			}
		}

		if ((null != runThread) && runThread.isAlive()) {
			return;
		}

		runState = RUN_STATE_EXIT;
		abortButton.setEnabled(false);

		doUpdate();

		if (runFailed) {
			statusLabel.setText("Failed");
		} else {
			if (runCanceled) {
				statusLabel.setText("Canceled");
			} else {
				statusLabel.setText("Complete");
			}
		}

		if (null != task) {

			AppTask.taskDone(task);
			task = null;

			if (null != callBack) {
				callBack.run();
			}

			if (null != dbID) {
				DbCore.closeDb(dbID, this);
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// StatusLogger methods called on background thread, buffer the messages for UI updating by doUpdate().

	public synchronized void reportStatus(String message) {

		runStatusPending = message;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public synchronized void logMessage(String message) {

		runMessageLog.append(message + "\n");
	}


	//-----------------------------------------------------------------------------------------------------------------

	public synchronized void showMessage(String message) {

		runMessageLog.append(message + "\r");
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doUpdate() {

		if (null != runStatusPending) {
			runStatus = runStatusPending;
			runStatusPending = null;
			if (!runCanceled) {
				statusLabel.setText(runStatus + "...");
			}
		}

		if (runMessageLog.length() > 0) {
			displayMessage(runMessageLog.toString());
			runMessageLog.setLength(0);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Show text in the display area.  Lines ending in carriage return are over-written by the next line.

	private void displayMessage(String theMessage) {

		int position = 0, nextPosition = 0, length = theMessage.length(), outputEnd;
		char nextChar;
		String line = null;

		while (position < length) {

			do {
				nextChar = theMessage.charAt(nextPosition);
			} while (('\n' != nextChar) && ('\r' != nextChar) && (++nextPosition < length));

			if (nextPosition > position) {
				if (nextPosition < length) {
					line = theMessage.substring(position, ++nextPosition);
				} else {
					line = theMessage.substring(position, nextPosition) + "\n";
				}
				position = nextPosition;
			} else {
				position = ++nextPosition;
				continue;
			}

			outputEnd = outputArea.getDocument().getLength();

			if (lastLineWasTransient) {
				outputArea.replaceRange(line, lastOutputLineStart, outputEnd);
			} else {
				lastOutputLineStart = outputEnd;
				outputArea.append(line);
			}

			lastLineWasTransient = ('\r' == nextChar);
		}

		// Do the auto-scroll behavior as needed.

		autoScrollLock = true;

		outputViewport.validate();
		if (outputViewport.getViewSize().height <= outputViewport.getExtentSize().height) {
			autoScrollState = 0;
		} else {
			if (0 == autoScrollState) {
				autoScrollState = 1;
			}
			if (1 == autoScrollState) {
				outputViewport.scrollRectToVisible(
					new Rectangle(0, (outputViewport.getViewSize().height - 1), 1, 1));
			}
		}

		autoScrollLock = false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isCanceled() {

		return runCanceled;
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
	// On the first attempt this just sets the canceled flag, which the thread should pick up through isCanceled().
	// A second attempt will interrupt the thread.

	public void abort() {

		if (!runCanceled) {
			runCanceled = true;
			abortButton.setText("Abort");
		} else {
			abortButton.setEnabled(false);
			if (null != runThread) {
				runThread.interrupt();
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean abortWillCancel() {

		return !runCanceled;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getStatus() {

		switch (runState) {

			case RUN_STATE_INIT:
			case RUN_STATE_WAIT:
				return "Waiting";

			case RUN_STATE_RUNNING:
				return runStatus;

			case RUN_STATE_CANCELING:
				return "Canceling";

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

		if (isRunning()) {
			return false;
		}
		return (outputArea.getDocument().getLength() > 0);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void writeOutputTo(Writer theWriter) throws IOException {

		if (isRunning() || (0 == outputArea.getDocument().getLength())) {
			return;
		}

		theWriter.write(outputArea.getText());
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void saveOutput() {

		if (isRunning() || (0 == outputArea.getDocument().getLength())) {
			return;
		}

		String title = "Save " + runName + " Log";
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
			theWriter.write(outputArea.getText());
		} catch (IOException ie) {
			errorReporter.reportError("Could not write to the file:\n" + ie.getMessage());
		}

		try {
			theWriter.close();
		} catch (IOException ie) {
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean hasReport() {

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void saveReport() {
	}
}
