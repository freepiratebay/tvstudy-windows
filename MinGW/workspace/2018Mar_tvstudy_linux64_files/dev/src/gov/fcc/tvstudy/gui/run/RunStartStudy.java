//
//  RunStartStudy.java
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
import javax.swing.table.*;


//=====================================================================================================================
// RunStart subclass for setting up a normal run of an existing study.  Shows a list of all scenarios in the study to
// allow user to select which ones to run (some study types always run all scenarios).  Creates a RunPanelStudy to set
// up and manage the run.  See superclass for further details.

public class RunStartStudy extends RunStart {

	public static final String WINDOW_TITLE = "Run Study";

	private RunPanelStudy runPanel;

	private ScenarioListTableModel scenarioListModel;
	private JTable scenarioListTable;

	private int runAllCount;


	//-----------------------------------------------------------------------------------------------------------------

	public RunStartStudy(StudyManager theParent) {

		super(theParent, WINDOW_TITLE);

		runPanel = new RunPanelStudy(theParent, theParent.getDbID());

		// Create the list table.

		scenarioListModel = new ScenarioListTableModel();
		scenarioListTable = scenarioListModel.createTable();

		JPanel listPanel = new JPanel(new BorderLayout());
		listPanel.setBorder(BorderFactory.createTitledBorder("Select scenario(s) to study"));
		listPanel.add(AppController.createScrollPane(scenarioListTable), BorderLayout.CENTER);

		// Do the layout.

		JPanel mainPanel = new JPanel(new BorderLayout());
		mainPanel.add(listPanel, BorderLayout.CENTER);
		mainPanel.add(optionsPanel, BorderLayout.SOUTH);

		Container cp = getContentPane();
		cp.setLayout(new BorderLayout());
		cp.add(mainPanel, BorderLayout.CENTER);
		cp.add(buttonPanel, BorderLayout.SOUTH);

		pack();

		Dimension theSize = getSize();
		theSize.height = 400;
		setSize(theSize);
		setMinimumSize(theSize);
	}


	//=================================================================================================================
	// Data class for the list of scenarios.

	private class ScenarioListItem {

		private int key;

		private String name;
		private String description;
		private int sourceCount;
	}


	//=================================================================================================================
	// Table model class for the scenario list.

	private class ScenarioListTableModel extends AbstractTableModel {

		private static final String SCENARIO_NAME_COLUMN = "Name";
		private static final String SCENARIO_DESCRIPTION_COLUMN = "Description";

		private String[] columnNames = {
			SCENARIO_NAME_COLUMN,
			SCENARIO_DESCRIPTION_COLUMN
		};

		private static final int SCENARIO_NAME_INDEX = 0;
		private static final int SCENARIO_DESCRIPTION_INDEX = 1;

		private ArrayList<ScenarioListItem> modelRows;


		//-------------------------------------------------------------------------------------------------------------

		private ScenarioListTableModel() {

			super();

			modelRows = new ArrayList<ScenarioListItem>();
		}


		//-------------------------------------------------------------------------------------------------------------

		private JTable createTable() {

			JTable theTable = new JTable(this);
			AppController.configureTable(theTable);
			theTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
			theTable.setAutoCreateRowSorter(true);

			TableColumn theColumn = theTable.getColumn(SCENARIO_NAME_COLUMN);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[15]);

			theColumn = theTable.getColumn(SCENARIO_DESCRIPTION_COLUMN);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[30]);

			return theTable;
		}


		//-------------------------------------------------------------------------------------------------------------

		private void setItems(ArrayList<ScenarioListItem> newItems) {

			modelRows.clear();

			if (null != newItems) {
				modelRows.addAll(newItems);
			}

			fireTableDataChanged();
		}


		//-------------------------------------------------------------------------------------------------------------

		private ScenarioListItem get(int rowIndex) {

			return modelRows.get(rowIndex);
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

		public int getRowCount() {

			return modelRows.size();
		}


		//-------------------------------------------------------------------------------------------------------------

		public Object getValueAt(int rowIndex, int columnIndex) {

			ScenarioListItem theItem = modelRows.get(rowIndex);

			switch (columnIndex) {

				case SCENARIO_NAME_INDEX:
					return theItem.name;

				case SCENARIO_DESCRIPTION_INDEX:
					return theItem.description;
			}

			return "";
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set the study key, load the list of scenarios.  Must be called before showing the dialog; also assumes the
	// dialog WILL be shown after this is called.  This checks and sets the study lock.  The study must be unlocked or
	// in shared run lock.  If unlocked an exclusive run lock is set, if in shared run lock the share count is
	// incremented.  The study engine will inherit the lock state.  Also verifies the database version.

	public boolean setStudy(int theStudyKey, ErrorReporter errors) {

		if (isVisible() || (runPanel.studyKey > 0)) {
			return false;
		}

		DbConnection db = DbCore.connectDb(getDbID(), errors);
		if (null == db) {
			return false;
		}

		String rootName = DbCore.getDbName(getDbID());

		boolean error = false, lockSet = false;
		String errorMessage = "", theName = String.valueOf(theStudyKey), theStudyName = "", theFileConfName = "",
			theFileConfCodes = "", theMapConfName = "", theMapConfCodes = "", thePreamble = null;
		OutputConfig theConfig = null;
		int theLock = Study.LOCK_NONE, theLockCount = 0, theShareCount = 0, theStudyType = 0,
			errorType = AppCore.ERROR_MESSAGE;
		ArrayList<ScenarioListItem> theItems = null;

		try {

			db.update("LOCK TABLES study WRITE, version WRITE");

			db.query(
			"SELECT " +
				"study.name, " +
				"version.version, " +
				"study.study_lock, " +
				"study.lock_count, " +
				"study.share_count, " +
				"study.study_type, " +
				"study.output_config_file_name, " +
				"study.output_config_file_codes, " +
				"study.output_config_map_name, " +
				"study.output_config_map_codes, " +
				"study.report_preamble " +
			"FROM " +
				"study " +
				"JOIN version " +
			"WHERE " +
				"study_key = " + theStudyKey);

			if (db.next()) {

				theStudyName = db.getString(1);
				theName = "'" + theStudyName + "'";

				if (DbCore.DATABASE_VERSION == db.getInt(2)) {

					theLock = db.getInt(3);
					theLockCount = db.getInt(4);

					if ((Study.LOCK_NONE == theLock) || (Study.LOCK_RUN_SHARE == theLock)) {

						theShareCount = db.getInt(5);
						theStudyType = db.getInt(6);
						theFileConfName = db.getString(7);
						theFileConfCodes = db.getString(8);
						theMapConfName = db.getString(9);
						theMapConfCodes = db.getString(10);
						thePreamble = db.getString(11);

						if (Study.LOCK_NONE == theLock) {

							db.update("UPDATE study SET study_lock = " + Study.LOCK_RUN_EXCL +
								", lock_count = lock_count + 1, share_count = 0 WHERE study_key = " + theStudyKey);
							lockSet = true;
							theLock = Study.LOCK_RUN_EXCL;
							theLockCount++;

						} else {

							db.update("UPDATE study SET share_count = share_count + 1 WHERE study_key = " +
								theStudyKey);
							lockSet = true;
							theShareCount++;
						}

						db.update("UNLOCK TABLES");

						// Load scenarios.  There must be at least one scenario and one desired source.  The editor
						// tries to be sure of that but an empty/null study can still happen.  TV IX check studies
						// have a different scenario structure involving child scenarios, use a different query.

						runAllCount = 0;

						if (Study.STUDY_TYPE_TV_IX == theStudyType) {

							db.query("SELECT COUNT(*) FROM " + rootName + "_" + theStudyKey +
								".scenario_source WHERE scenario_key = 1 AND is_desired");
							if (db.next()) {
								runAllCount = db.getInt(1);
							}

							db.query(
							"SELECT " +
								"parent.scenario_key, " +
								"parent.name, " +
								"parent.description, " +
								"COUNT(*) " +
							"FROM " +
								rootName + "_" + theStudyKey + ".scenario AS parent " +
								"JOIN " + rootName + "_" + theStudyKey + ".scenario AS child ON " +
									"(child.parent_scenario_key = parent.scenario_key) " +
								"JOIN " + rootName + "_" + theStudyKey + ".scenario_source ON " +
									"(scenario_source.scenario_key = child.scenario_key) " +
							"WHERE " +
								"scenario_source.is_desired " +
							"GROUP BY 1, 2, 3 " +
							"ORDER BY 1");

						} else {

							db.query(
							"SELECT " +
								"scenario_key, " +
								"name, " +
								"description, " +
								"COUNT(*) " +
							"FROM " +
								rootName + "_" + theStudyKey + ".scenario " +
								"JOIN " + rootName + "_" + theStudyKey + ".scenario_source USING (scenario_key) " +
							"WHERE " +
								"scenario_source.is_desired " +
								"AND (scenario.parent_scenario_key = 0) " +
							"GROUP BY 1, 2, 3 " +
							"ORDER BY 1");
						}

						theItems = new ArrayList<ScenarioListItem>();
						ScenarioListItem theItem;

						while (db.next()) {
							theItem = new ScenarioListItem();
							theItem.key = db.getInt(1);
							theItem.name = db.getString(2);
							theItem.description = db.getString(3);
							theItem.sourceCount = db.getInt(4);
							theItems.add(theItem);
							runAllCount += theItem.sourceCount;
						}

						if (0 == runAllCount) {
							error = true;
							errorMessage = "There are no desired stations in the study.";
							errorType = AppCore.WARNING_MESSAGE;
						}

					} else {
						error = true;
						errorMessage = "The study is in use by another application.";
						errorType = AppCore.WARNING_MESSAGE;
					}

				} else {
					error = true;
					errorMessage = "The database version is incorrect.";
				}

			} else {
				error = true;
				errorMessage = "The study does not exist.";
			}

		} catch (SQLException se) {
			error = true;
			errorMessage = DbConnection.ERROR_TEXT_PREFIX + se;
			db.reportError(se);
		}

		// Clean up the lock state.

		try {
			db.update("UNLOCK TABLES");
			if (error && lockSet) {
				if ((Study.LOCK_RUN_EXCL == theLock) || (--theShareCount <= 0)) {
					db.update("UPDATE study SET study_lock = " + Study.LOCK_NONE +
						", lock_count = lock_count + 1, share_count = 0 WHERE study_key = " + theStudyKey);
				} else {
					db.update("UPDATE study SET share_count = share_count - 1 WHERE study_key = " + theStudyKey);
				}
			}
		} catch (SQLException se) {
			db.reportError(se);
		}

		DbCore.releaseDb(db);

		// If an error occurred, report and return failure.

		if (error) {
			if (null != errors) {
				errors.reportError("Cannot run study " + theName + ":\n" + errorMessage, errorType);
			}
			return false;
		}

		// All set, save state.

		runPanel.studyKey = theStudyKey;
		runPanel.studyName = theStudyName;
		runPanel.studyType = theStudyType;
		runPanel.studyLock = theLock;
		runPanel.lockCount = theLockCount;

		runPanel.runName = theStudyName;

		optionsPanel.defaultFileOutputConfig = OutputConfig.getOrMakeConfig(getDbID(), OutputConfig.CONFIG_TYPE_FILE,
			theFileConfName, theFileConfCodes);
		optionsPanel.defaultMapOutputConfig = OutputConfig.getOrMakeConfig(getDbID(), OutputConfig.CONFIG_TYPE_MAP,
			theMapConfName, theMapConfCodes);

		runPanel.reportPreamble = thePreamble;

		scenarioListModel.setItems(theItems);

		// Generally all scenarios are run, so initially all are selected.  For a TV interference-check study, all
		// scenarios will be run by the engine regardless of UI so disable the table so the selection cannot change.
		// Also in the IX check case, the table may legitimately be empty because the proposal scenario is not shown.

		if (scenarioListModel.getRowCount() > 0) {
			scenarioListTable.setRowSelectionInterval(0, (scenarioListModel.getRowCount() - 1));
		}
		if (Study.STUDY_TYPE_TV_IX == runPanel.studyType) {
			AppController.setComponentEnabled(scenarioListTable, false);
		} else {
			AppController.setComponentEnabled(scenarioListTable, true);
		}

		updateDocumentName();

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public RunPanel getRunPanel() {

		return runPanel;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public StudyLockHolder getStudyLockHolder() {

		return runPanel;
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected void doReset() {

		super.doReset();

		scenarioListTable.clearSelection();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Validate superclass input, then build scenario key list and total source count.  No error possible there, an
	// empty scenario list is a legal input state, a run with no scenarios does have useful effects.  For TV IX study
	// the selection and key list are irrelevant, all scenarios are auto-run regardless of the UI.

	protected boolean validateInput() {

		if (!super.validateInput()) {
			return false;
		}

		runPanel.fileOutputConfig = optionsPanel.fileOutputConfig;
		runPanel.mapOutputConfig = optionsPanel.mapOutputConfig;

		if (Study.STUDY_TYPE_TV_IX == runPanel.studyType) {

			runPanel.totalSourceCount = runAllCount;

		} else {

			runPanel.scenarioKeys = new ArrayList<Integer>();
			runPanel.totalSourceCount = 0;
			ScenarioListItem theScenario;
			for (int rowIndex : scenarioListTable.getSelectedRows()) {
				theScenario = scenarioListModel.get(scenarioListTable.convertRowIndexToModel(rowIndex));
				runPanel.scenarioKeys.add(Integer.valueOf(theScenario.key));
				runPanel.totalSourceCount += theScenario.sourceCount;
			}
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowWillClose() {

		super.windowWillClose();

		runPanel = null;
		scenarioListModel.setItems(null);
	}
}
