//
//  ToolRun.java
//  TVStudy
//
//  Copyright (c) 2012-2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui.run;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.gui.*;

import java.util.*;
import java.awt.*;
import java.awt.event.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;


//=====================================================================================================================
// Window to display a ProcessPanel to handle running an arbitrary external command-line tool and provide management
// UI, including a timer to keep panel UI and status updated.  The run starts as soon as the window is made visible.

public class ToolRun extends AppFrame {

	private ProcessPanel processPanel;

	// Buttons and menu items.

	private JButton saveButton;
	private JButton closeButton;

	private JMenuItem abortMenuItem;
	private JMenuItem saveMenuItem;

	// Timer to keep the process UI and status updated.

	private static final int TIMER_INTERVAL = 200;   // milliseconds
	private javax.swing.Timer checkTimer;


	//-----------------------------------------------------------------------------------------------------------------

	public ToolRun(String theName, ArrayList<String> theArgs) {

		super(null, "Run " + theName);

		processPanel = new ProcessPanel(this, theName, null, null);
		processPanel.setProcessArguments(theArgs);

		// Buttons.

		saveButton = new JButton("Save Output");
		saveButton.setFocusable(false);
		saveButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				processPanel.saveOutput();
			}
		});
		saveButton.setEnabled(false);

		closeButton = new JButton("Close");
		closeButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doClose();
			}
		});
		closeButton.setEnabled(false);

		// Layout.

		JPanel butPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		butPanel.add(saveButton);
		butPanel.add(closeButton);

		Container cp = getContentPane();
		cp.setLayout(new BorderLayout());
		cp.add(processPanel, BorderLayout.CENTER);
		cp.add(butPanel, BorderLayout.SOUTH);

		getRootPane().setDefaultButton(closeButton);

		pack();

		Dimension theSize = new Dimension(600, 400);
		setMinimumSize(theSize);
		setSize(theSize);

		// Build file menu.

		fileMenu.removeAll();

		// Abort

		abortMenuItem = new JMenuItem("Abort");
		abortMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				processPanel.killProcess(true);
			}
		});
		fileMenu.add(abortMenuItem);

		// __________________________________

		fileMenu.addSeparator();

		// Save Output...

		saveMenuItem = new JMenuItem("Save Output...");
		saveMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, AppController.MENU_SHORTCUT_KEY_MASK));
		saveMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				processPanel.saveOutput();
			}
		});
		fileMenu.add(saveMenuItem);

		// Set initial UI state.

		updateControls();
	
		// Timer to keep the UI and status updated.

		checkTimer = new javax.swing.Timer(TIMER_INTERVAL, new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doUpdate();
			}
		});
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getDbID() {

		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getFileMenuName() {

		return "Run";
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update state of buttons and menu items per process status.

	private void updateControls() {

		if (processPanel.isProcessRunning()) {

			abortMenuItem.setEnabled(true);
			saveButton.setEnabled(false);
			saveMenuItem.setEnabled(false);
			closeButton.setEnabled(false);

		} else {

			abortMenuItem.setEnabled(false);
			if (processPanel.hasOutput()) {
				saveButton.setEnabled(true);
				saveMenuItem.setEnabled(true);
			} else {
				saveButton.setEnabled(false);
				saveMenuItem.setEnabled(false);
			}
			closeButton.setEnabled(true);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by the timer.  Poll the process, if still running that's all, else stop the timer and update.

	private void doUpdate() {

		boolean isRunning = false;
		try {
			isRunning = processPanel.pollProcess(System.currentTimeMillis());
		} catch (Throwable t) {
			AppCore.log(AppCore.ERROR_MESSAGE, "pollProcess() failed", t);
			processPanel.killProcess(false);
		}
		if (isRunning) {
			return;
		}

		checkTimer.stop();

		updateControls();
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doClose() {

		if (!processPanel.isProcessRunning()) {
			blockActionsSet();
			AppController.hideWindow(this);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Just start the poll timer, the process will start on the first pollProcess() call to the ProcessPanel.

	public void windowWillOpen() {

		if (!checkTimer.isRunning()) {
			checkTimer.start();
		}

		blockActionsClear();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// If the process is still running the window cannot close.

	public boolean windowShouldClose() {

		if (processPanel.isProcessRunning()) {
			toFront();
			errorReporter.reportMessage("This window can't be closed while the process is running.");
			return false;
		}

		blockActionsSet();
		return true;
	}
}
