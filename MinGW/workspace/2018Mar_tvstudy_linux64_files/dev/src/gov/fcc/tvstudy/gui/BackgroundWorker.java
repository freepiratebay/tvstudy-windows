//
//  BackgroundWorker.java
//  TVStudy
//
//  Copyright (c) 2012-2018 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui;

import gov.fcc.tvstudy.core.*;

import java.util.*;
import java.util.concurrent.*;
import java.sql.*;
import java.awt.*;
import java.awt.event.*;

import javax.swing.*;
import javax.swing.event.*;


//=====================================================================================================================
// SwingWorker subclass to manage running an operation on a background thread, value added is error reporting and
// display of a please-wait dialog with updating message.  The point isn't to get any parallel performance benefit
// from threading, it's just to avoid locking up the event thread during a lengthy operation.  This also can provide
// a cancel operation, if implemented a cancel button is shown in the wait dialog.

public abstract class BackgroundWorker<ReturnType> extends SwingWorker<ReturnType, String> implements StatusLogger {

	private static final int DIALOG_DELAY_TIME = 500;
	private static final int DIALOG_DISPLAY_TIME = 2000;

	private Window parent;
	private String title;

	private ErrorLogger workerErrors;

	private JDialog statusDialog;
	private JLabel statusLabel;
	private boolean canCloseDialog;

	private JButton cancelButton;
	private boolean showCancel;
	private boolean canceled;


	//-----------------------------------------------------------------------------------------------------------------
	// Create an ErrorLogger to accumulate errors from the background thread for deferred reporting.

	public BackgroundWorker(Window theParent, String theTitle) {

		parent = theParent;
		title = theTitle;

		workerErrors = new ErrorLogger(new StringBuilder());
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Run the operation.  This is called on the event thread and blocks until the background thread completes.  It
	// will start the background thread then just wait a short time, if the operation doesn't finish quickly it will
	// show a message dialog, initially with the provided message however the message can be updated by the background
	// process calling publish().  Once the dialog is shown regardless of how much longer the thread takes to complete
	// the dialog will remain visible for a minimum amount of time.  The object returned from doBackgroundWork() is
	// returned, or null if the background thread fails to complete.

	public ReturnType runWork(String message, ErrorReporter errors) {

		execute();

		ReturnType result = null;

		boolean showDialog = false;
		try {
			result = get(DIALOG_DELAY_TIME, TimeUnit.MILLISECONDS);
		} catch (TimeoutException te) {
			showDialog = true;
		} catch (Throwable t) {
			AppCore.log(AppCore.ERROR_MESSAGE, "Unexpected error", t);
			workerErrors.reportError("An unexpected error occurred:\n" + t, AppCore.ERROR_MESSAGE);
		}

		if (showDialog) {

			statusDialog = new JDialog(parent, title, Dialog.ModalityType.APPLICATION_MODAL);
			statusDialog.setResizable(false);
			statusDialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

			statusLabel = new JLabel(message);
			statusLabel.setPreferredSize(AppController.labelSize[40]);

			Box box1 = Box.createVerticalBox();
			box1.add(Box.createVerticalStrut(20));
			box1.add(statusLabel);
			box1.add(Box.createVerticalStrut(20));

			Box box2 = Box.createHorizontalBox();
			box2.add(Box.createHorizontalStrut(20));
			box2.add(box1);
			box2.add(Box.createHorizontalStrut(20));

			Container cp = statusDialog.getContentPane();
			cp.setLayout(new BorderLayout());
			cp.add(box2, BorderLayout.CENTER);

			cancelButton = new JButton("Cancel");
			cancelButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (canceled || !showCancel) {
						return;
					}
					canceled = true;
					cancelButton.setEnabled(false);
					cancel();
				}
			});
			cancelButton.setVisible(showCancel);

			JPanel bp = new JPanel(new FlowLayout(FlowLayout.RIGHT));
			bp.add(cancelButton);

			cp.add(bp, BorderLayout.SOUTH);

			statusDialog.pack();
			statusDialog.setLocationRelativeTo(parent);

			javax.swing.Timer theTimer = new javax.swing.Timer(DIALOG_DISPLAY_TIME, new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (canCloseDialog) {
						statusDialog.setVisible(false);
					} else {
						canCloseDialog = true;
					}
				}
			});
			theTimer.setRepeats(false);
			theTimer.start();

			statusDialog.setVisible(true);

			try {
				result = get();
			} catch (Throwable t) {
				AppCore.log(AppCore.ERROR_MESSAGE, "Unexpected error", t);
				workerErrors.reportError("An unexpected error occurred:\n" + t, AppCore.ERROR_MESSAGE);
			}
		}

		// If the background process reported any errors, re-report those now with the caller's error reporter.  If
		// the process logged any messages, transfer those to the caller's reporter.

		if (workerErrors.hasErrors()) {
			errors.reportError(workerErrors.toString(), workerErrors.getLastErrorType());
		}

		if (workerErrors.hasMessages()) {
			errors.logMessage(workerErrors.getMessages());
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected ReturnType doInBackground() throws Exception {

		return doBackgroundWork(workerErrors);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This must be overridden to perform the actual work.  The return value is simply returned by runQuery(), it is
	// not interpreted in any way.

	protected abstract ReturnType doBackgroundWork(ErrorLogger errors);


	//-----------------------------------------------------------------------------------------------------------------
	// A cancel button may be shown in the wait dialog, showCancel() and hideCancel() may be called at any time from
	// either the background or event threads.  When the cancel button is pressed cancel() is called on the event
	// thread and isCanceled() will subsequently return true.  Subclass may override cancel() or poll isCanceled().

	public synchronized void showCancel() {

		showCancel = true;
		if (null == cancelButton) {
			return;
		}
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				cancelButton.setVisible(true);
			}
		});
	}


	//-----------------------------------------------------------------------------------------------------------------

	public synchronized void hideCancel() {

		showCancel = false;
		if (null == cancelButton) {
			return;
		}
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				cancelButton.setVisible(false);
			}
		});
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void cancel() {
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This is also part of the StatusLogger interface.

	public boolean isCanceled() {

		return canceled;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Public wrapper around publish(), which is being used here just for UI status updates.  This must only be called
	// from the background thread.

	public void setWaitMessage(String theMessage) {

		publish(theMessage);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Methods in StatusLogger; there is no permanent log here so those messages are ignored.

	public void reportStatus(String theMessage) {

		publish(theMessage);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void logMessage(String theMessage) {
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void showMessage(String theMessage) {
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected void process(java.util.List<String> messages) {

		if (null != statusLabel) {
			statusLabel.setText(messages.get(messages.size() - 1));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// If the timer has already fired, close the dialog; else set the flag so it is closed when the timer fires.  In
	// the latter case disable the cancel button (if visible) as it would no longer do anything.

	protected void done() {

		if (canCloseDialog) {
			statusDialog.setVisible(false);
		} else {
			canCloseDialog = true;
			if (null != cancelButton) {
				cancelButton.setEnabled(false);
			}
		}
	}
}
