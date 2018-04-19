//
//  RunManager.java
//  TVStudy
//
//  Copyright (c) 2016-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui.run;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.gui.*;

import java.util.*;
import java.sql.*;
import java.io.*;
import java.awt.*;
import java.awt.event.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.table.*;


//=====================================================================================================================
// Class to manage study runs, builds, data downloads, etc.  The public interface is the static method addRunPanel(),
// see RunPanel for details.  A private singleton is used to display and manage RunPanel subclasses.

public class RunManager extends AppFrame {

	private static RunManager runManager;

	private RunListTableModel runListModel;
	private JTable runListTable;

	private RunPanel currentRunPanel;
	private JPanel wrapperPanel;

	private JSplitPane splitPane;

	// Buttons and menu items.

	private JButton saveLogButton;
	private JButton saveReportButton;
	private JButton removeButton;
	private JButton runNextButton;

	private JMenuItem abortMenuItem;
	private JMenuItem saveLogMenuItem;
	private JMenuItem saveReportMenuItem;

	// Polling timer.

	private static final int TIMER_INTERVAL = 200;   // milliseconds
	private javax.swing.Timer checkTimer;

	// Ignore table selection changes during shenanigans.

	private boolean ignoreSelectionChange;


	//-----------------------------------------------------------------------------------------------------------------
	// This will create the singleton if it does not yet exist, but it does not show the window.

	public static synchronized RunManager getRunManager() {

		if (null == runManager) {
			new RunManager();
		}
		return runManager;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Constructor is private, this is an application-wide singleton.

	private RunManager() {

		super(null, "Activity Queue");

		if (null != runManager) {
			throw new RuntimeException("Run manager already exists");
		}
		runManager = this;

		runListModel = new RunListTableModel();
		runListTable = runListModel.createTable();

		runListTable.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
			public void valueChanged(ListSelectionEvent theEvent) {
				if (!theEvent.getValueIsAdjusting() && !ignoreSelectionChange) {
					doChangePanel();
				}
			}
		});

		JPanel listPanel = new JPanel(new BorderLayout());
		listPanel.add(AppController.createScrollPane(runListTable), BorderLayout.CENTER);
		listPanel.setMinimumSize(new Dimension(0, (runListTable.getRowHeight() * 8)));

		wrapperPanel = new JPanel();
		wrapperPanel.setLayout(new BoxLayout(wrapperPanel, BoxLayout.X_AXIS));
		wrapperPanel.setMinimumSize(new Dimension(0, 200));

		splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, false, listPanel, wrapperPanel);

		// Buttons.

		saveLogButton = new JButton("Save Log...");
		saveLogButton.setFocusable(false);
		saveLogButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doSaveOutput();
			}
		});

		saveReportButton = new JButton("Save Report...");
		saveReportButton.setFocusable(false);
		saveReportButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doSaveReport();
			}
		});

		removeButton = new JButton("Remove");
		removeButton.setFocusable(false);
		removeButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (runListModel.isEmpty()) {
					if (windowShouldClose()) {
						AppController.hideWindow(runManager);
					}
				} else {
					doRemove();
				}
			}
		});

		runNextButton = new JButton("Move To Top");
		runNextButton.setFocusable(false);
		runNextButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doRunNext();
			}
		});

		// Do layout.

		JPanel butLeft = new JPanel(new FlowLayout(FlowLayout.LEFT));
		butLeft.add(runNextButton);

		JPanel butRight = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		butRight.add(saveLogButton);
		butRight.add(saveReportButton);
		butRight.add(removeButton);

		Box butBox = Box.createHorizontalBox();
		butBox.add(butLeft);
		butBox.add(butRight);

		Container cp = getContentPane();
		cp.setLayout(new BorderLayout());
		cp.add(splitPane, BorderLayout.CENTER);
		cp.add(butBox, BorderLayout.SOUTH);

		pack();

		Dimension theSize = getSize();
		theSize.width = 600;
		setSize(theSize);
		setMinimumSize(theSize);

		// Build file menu.

		fileMenu.removeAll();

		// Abort

		abortMenuItem = new JMenuItem("Abort");
		abortMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doAbort();
			}
		});
		fileMenu.add(abortMenuItem);

		// __________________________________

		fileMenu.addSeparator();

		// Save Log...

		saveLogMenuItem = new JMenuItem("Save Log...");
		saveLogMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, AppController.MENU_SHORTCUT_KEY_MASK));
		saveLogMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doSaveOutput();
			}
		});
		fileMenu.add(saveLogMenuItem);

		// Save Report...

		saveReportMenuItem = new JMenuItem("Save Report...");
		saveReportMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, AppController.MENU_SHORTCUT_KEY_MASK));
		saveReportMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doSaveReport();
			}
		});

		// Timer to keep the UI and status updated in all the panels.

		checkTimer = new javax.swing.Timer(TIMER_INTERVAL, new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doUpdate();
			}
		});

		updateDocumentName();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getFileMenuName() {

		return "Activity";
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected boolean showsEditMenu() {

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getDbID() {

		return null;
	}


	//=================================================================================================================
	// Table model class for the run panel list.  The auto-remove flag column is editable.

	private class RunListTableModel extends AbstractTableModel {

		private static final String RUN_COLUMN = "Activity";
		private static final String STATUS_COLUMN = "Status";
		private static final String AUTO_REMOVE_COLUMN = "Remove when complete";

		private String[] columnNames = {
			RUN_COLUMN,
			STATUS_COLUMN,
			AUTO_REMOVE_COLUMN
		};

		private static final int RUN_INDEX = 0;
		private static final int STATUS_INDEX = 1;
		private static final int AUTO_REMOVE_INDEX = 2;

		private ArrayList<RunPanel> modelRows;


		//-------------------------------------------------------------------------------------------------------------

		private RunListTableModel() {

			super();

			modelRows = new ArrayList<RunPanel>();
		}


		//-------------------------------------------------------------------------------------------------------------

		private JTable createTable() {

			JTable theTable = new JTable(this);
			AppController.configureTable(theTable);
			theTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

			TableColumn theColumn = theTable.getColumn(RUN_COLUMN);
			theColumn.setMinWidth(AppController.textFieldWidth[8]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[20]);

			theColumn = theTable.getColumn(STATUS_COLUMN);
			theColumn.setMinWidth(AppController.textFieldWidth[3]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[6]);

			theColumn = theTable.getColumn(AUTO_REMOVE_COLUMN);
			theColumn.setMinWidth(AppController.textFieldWidth[3]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[3]);

			return theTable;
		}


		//-------------------------------------------------------------------------------------------------------------

		private int add(RunPanel thePanel) {

			int rowIndex = modelRows.size();
			modelRows.add(thePanel);

			fireTableRowsInserted(rowIndex, rowIndex);

			return rowIndex;
		}


		//-------------------------------------------------------------------------------------------------------------

		private int add(int rowIndex, RunPanel thePanel) {

			modelRows.add(rowIndex, thePanel);

			fireTableRowsInserted(rowIndex, rowIndex);

			return rowIndex;
		}


		//-------------------------------------------------------------------------------------------------------------

		private RunPanel get(int rowIndex) {

			return modelRows.get(rowIndex);
		}


		//-------------------------------------------------------------------------------------------------------------

		private ArrayList<RunPanel> getAll() {

			return new ArrayList<RunPanel>(modelRows);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void remove(RunPanel thePanel) {

			int rowIndex = modelRows.indexOf(thePanel);
			if (rowIndex >= 0) {
				modelRows.remove(rowIndex);
				fireTableRowsDeleted(rowIndex, rowIndex);
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		private void removeAll() {

			modelRows.clear();
			fireTableDataChanged();
		}


		//-------------------------------------------------------------------------------------------------------------

		private boolean isEmpty() {

			return modelRows.isEmpty();
		}


		//-------------------------------------------------------------------------------------------------------------

		private void update() {

			if (!modelRows.isEmpty()) {
				fireTableChanged(new TableModelEvent(this, 0, (modelRows.size() - 1)));
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		public int getColumnCount() {

			return columnNames.length;
		}


		//-------------------------------------------------------------------------------------------------------------

		public String getColumnName(int columnIndex) {

			return columnNames[columnIndex];
		}


		//-------------------------------------------------------------------------------------------------------------

		public Class getColumnClass(int columnIndex) {

			if (AUTO_REMOVE_INDEX == columnIndex) {
				return Boolean.class;
			}
			return String.class;
		}


		//-------------------------------------------------------------------------------------------------------------

		public int getRowCount() {

			return modelRows.size();
		}


		//-------------------------------------------------------------------------------------------------------------

		public boolean isCellEditable(int rowIndex, int columnIndex) {

			if (AUTO_REMOVE_INDEX == columnIndex) {
				return true;
			}
			return false;
		}


		//-------------------------------------------------------------------------------------------------------------

		public Object getValueAt(int rowIndex, int columnIndex) {

			RunPanel thePanel = modelRows.get(rowIndex);

			switch (columnIndex) {

				case RUN_INDEX:
					return thePanel.getRunName();

				case STATUS_INDEX:
					return thePanel.getStatus();

				case AUTO_REMOVE_INDEX:
					return Boolean.valueOf(thePanel.autoRemove);
			}

			return "";
		}


		//-------------------------------------------------------------------------------------------------------------

		public void setValueAt(Object value, int rowIndex, int columnIndex) {

			if (AUTO_REMOVE_INDEX != columnIndex) {
				return;
			}

			RunPanel thePanel = modelRows.get(rowIndex);

			thePanel.autoRemove = ((Boolean)value).booleanValue();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update state of buttons and menu items per current panel status.

	private void updateControls() {

		if (null == currentRunPanel) {

			abortMenuItem.setEnabled(false);
			abortMenuItem.setText("Abort");
			saveLogButton.setEnabled(false);
			saveLogMenuItem.setEnabled(false);
			saveReportButton.setEnabled(false);
			saveReportMenuItem.setEnabled(false);
			removeButton.setEnabled(runListModel.isEmpty());
			runNextButton.setEnabled(false);

		} else {

			if (currentRunPanel.isRunning()) {

				abortMenuItem.setEnabled(true);
				if (currentRunPanel.abortWillCancel()) {
					abortMenuItem.setText("Cancel");
				} else {
					abortMenuItem.setText("Abort");
				}
				saveLogButton.setEnabled(false);
				saveLogMenuItem.setEnabled(false);
				saveReportButton.setEnabled(false);
				saveReportMenuItem.setEnabled(false);
				removeButton.setEnabled(false);
				runNextButton.setEnabled(currentRunPanel.isWaiting() && (runListTable.getSelectedRow() > 0));

			} else {

				abortMenuItem.setEnabled(false);
				abortMenuItem.setText("Abort");
				if (currentRunPanel.hasOutput()) {
					saveLogButton.setEnabled(true);
					saveLogMenuItem.setEnabled(true);
				} else {
					saveLogButton.setEnabled(false);
					saveLogMenuItem.setEnabled(false);
				}
				if (currentRunPanel.hasReport()) {
					saveReportButton.setEnabled(true);
					saveReportMenuItem.setEnabled(true);
				} else {
					saveReportButton.setEnabled(false);
					saveReportMenuItem.setEnabled(false);
				}
				removeButton.setEnabled(true);
				runNextButton.setEnabled(false);
			}
		}

		if (runListModel.isEmpty()) {
			removeButton.setText("Close");
		} else {
			removeButton.setText("Remove");
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Table selection changed, display selected panel.

	private void doChangePanel() {

		if (runListTable.getSelectedRowCount() != 1) {
			return;
		}

		if (null != currentRunPanel) {
			wrapperPanel.remove(currentRunPanel);
		}
		currentRunPanel = runListModel.get(runListTable.getSelectedRow());
		wrapperPanel.add(currentRunPanel);
		wrapperPanel.revalidate();
		wrapperPanel.repaint();

		updateControls();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Abort the run.

	private void doAbort() {

		if ((null == currentRunPanel) || !currentRunPanel.isRunning()) {
			return;
		}

		currentRunPanel.abort();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Save log output from the current panel to a file.

	private void doSaveOutput() {

		if ((null == currentRunPanel) || !currentRunPanel.hasOutput()) {
			return;
		}

		currentRunPanel.saveOutput();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Save report from the current panel to a file.

	private void doSaveReport() {

		if ((null == currentRunPanel) || !currentRunPanel.hasReport()) {
			return;
		}

		currentRunPanel.saveReport();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Remove the current panel.

	private void doRemove() {

		if ((null == currentRunPanel) || currentRunPanel.isRunning()) {
			return;
		}

		wrapperPanel.remove(currentRunPanel);

		runListModel.remove(currentRunPanel);
		currentRunPanel.setParent(null);
		currentRunPanel = null;

		if (!runListModel.isEmpty()) {
			runListTable.scrollRectToVisible(runListTable.getCellRect(0, 0, true));
			runListTable.setRowSelectionInterval(0, 0);
		} else {
			wrapperPanel.revalidate();
			wrapperPanel.repaint();
			updateControls();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Move the selected waiting run to the top of the list so it runs next.

	private void doRunNext() {

		if ((null == currentRunPanel) || !currentRunPanel.isWaiting() || (runListTable.getSelectedRow() <= 0)) {
			return;
		}

		currentRunPanel.bumpTask();

		ignoreSelectionChange = true;

		runListModel.remove(currentRunPanel);
		runListModel.add(0, currentRunPanel);

		runListTable.scrollRectToVisible(runListTable.getCellRect(0, 0, true));
		runListTable.setRowSelectionInterval(0, 0);

		ignoreSelectionChange = false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a new panel.  Create and show the manager as needed.

	public static void addRunPanel(RunPanel thePanel) {

		if (!thePanel.isInitialized()) {
			return;
		}

		if (null == runManager) {
			getRunManager();
		}
		if (runManager.isVisible()) {
			runManager.toFront();
		} else {
			AppController.showWindow(runManager);
		}

		int rowIndex = runManager.runListModel.add(thePanel);
		thePanel.setParent(runManager);

		if (rowIndex >= 0) {
			runManager.runListTable.setRowSelectionInterval(rowIndex, rowIndex);
			runManager.runListTable.scrollRectToVisible(runManager.runListTable.getCellRect(rowIndex, 0, true));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by the timer, poll the panels.  If one is done and has auto-save and/or auto-remove, do those things.

	private void doUpdate() {

		long now = System.currentTimeMillis();

		boolean didRemoveCurrent = false;

		for (RunPanel thePanel : runListModel.getAll()) {

			thePanel.poll(now);

			if (!thePanel.isRunning()) {

				if (thePanel.autoSaveOutput && thePanel.hasOutput()) {

					thePanel.autoSaveOutput = false;

					thePanel.saveOutputToLogFile();
				}

				if (thePanel.autoRemove && !thePanel.runFailed()) {

					thePanel.autoRemove = false;

					runListModel.remove(thePanel);
					thePanel.setParent(null);

					if (thePanel == currentRunPanel) {
						wrapperPanel.remove(currentRunPanel);
						currentRunPanel = null;
						didRemoveCurrent = true;
					}
				}
			}
		}

		if (didRemoveCurrent) {
			if (!runListModel.isEmpty()) {
				runListTable.scrollRectToVisible(runListTable.getCellRect(0, 0, true));
				runListTable.setRowSelectionInterval(0, 0);
			} else {
				wrapperPanel.revalidate();
				wrapperPanel.repaint();
			}
		}

		runListModel.update();
		updateControls();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Start the poll timer.

	public void windowWillOpen() {

		Integer i = DbCore.getIntegerProperty(getDbID(), getKeyTitle() + ".dividerLocation");
		if (null != i) {
			splitPane.setDividerLocation(i.intValue());
		}

		DbController.restoreColumnWidths(getDbID(), getKeyTitle(), runListTable);

		if (!checkTimer.isRunning()) {
			checkTimer.start();
		}

		blockActionsClear();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// If any process is still running the window cannot close.  Otherwise remove all panels.

	public boolean windowShouldClose() {

		errorReporter.clearTitle();

		if (!runListModel.isEmpty()) {

			ArrayList<RunPanel> thePanels = runListModel.getAll();
			for (RunPanel thePanel : thePanels) {
				if (thePanel.isRunning()) {
					toFront();
					errorReporter.reportMessage("This window can't be closed until all activities are complete.");
					return false;
				}
			}

			for (RunPanel thePanel : thePanels) {
				thePanel.setParent(null);
			}
			runListModel.removeAll();

			if (null != currentRunPanel) {
				wrapperPanel.remove(currentRunPanel);
				currentRunPanel.setParent(null);
				currentRunPanel = null;
			}
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowWillClose() {

		DbCore.setIntegerProperty(getDbID(), getKeyTitle() + ".dividerLocation",
			Integer.valueOf(splitPane.getDividerLocation()));

		DbController.saveColumnWidths(getDbID(), getKeyTitle(), runListTable);

		blockActionsSet();

		checkTimer.stop();
	}
}
