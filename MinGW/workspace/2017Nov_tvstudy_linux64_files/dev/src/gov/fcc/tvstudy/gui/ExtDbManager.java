//
//  ExtDbManager.java
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.editdata.*;
import gov.fcc.tvstudy.gui.editor.*;

import java.util.*;
import java.sql.*;
import java.io.*;
import java.nio.file.*;
import java.awt.*;
import java.awt.event.*;
import java.text.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.table.*;
import javax.swing.filechooser.*;

import javax.xml.parsers.*;

import org.xml.sax.*;
import org.xml.sax.helpers.*;


//=====================================================================================================================
// Manager UI for the set of external station data sets in a database.  Allows data sets to be created by importing
// downloaded dump files, also data sets can be re-named and deleted.  There is no actual editor for a data set, it is
// a static mirror of a particular download state of the FCC's database, or other external data source.  This is not a
// dialog, but it makes no sense to have two of these open for the same database so a unique instance is managed by
// each StudyManager instance.

// There are now two fundamentally different types of data sets.  The traditional import types, the list returned by
// ExtDb.getImportTypes(), are the dump-file-based "mirror" sets described above.  The data set database is created
// and data imported once, and only once, into that database in one operation, and subsequently the set is read-only.
// There also now "generic" import types, returned by ExtDb.getGenericTypes().  These are more flexible, the data set
// database is created first by separate action, then one more more imports may subsequently be performed drawing from
// a variety of different source data file formats to add records to the set.  The only difference is in setup here,
// throughout the rest of the UI both all types of data sets function the same way.

public class ExtDbManager extends AppFrame {

	public static final String WINDOW_TITLE = "Station Data Manager";

	private static final String DOWNLOAD_SET_NAME = "(download)";

	private ExtDbListModel extDbModel;
	private JTable extDbTable;

	private JMenuItem renameExtDbMenuItem;
	private JMenuItem deleteExtDbMenuItem;

	private KeyedRecordMenu downloadMenu;
	private KeyedRecordMenu importMenu;

	private KeyedRecordMenu createGenericMenu;
	private JButton importGenericButton;
	private JMenuItem importGenericMenuItem;

	// Disambiguation.

	private ExtDbManager outerThis = this;


	//-----------------------------------------------------------------------------------------------------------------

	public ExtDbManager(AppEditor theParent) {

		super(theParent, WINDOW_TITLE);

		// Table for the data list.

		extDbModel = new ExtDbListModel();
		extDbTable = extDbModel.createTable();

		extDbTable.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
			public void valueChanged(ListSelectionEvent theEvent) {
				updateControls();
			}
		});

		JPanel extDbPanel = new JPanel(new BorderLayout());
		extDbPanel.setBorder(BorderFactory.createTitledBorder("Station Data"));
		extDbPanel.add(AppController.createScrollPane(extDbTable), BorderLayout.CENTER);

		// Pop-up menus for various downloading, importing, and creating steps.

		ArrayList<KeyedRecord> dlTypes = ExtDb.getDownloadTypes();
		dlTypes.add(0, new KeyedRecord(0, "Download"));
		downloadMenu = new KeyedRecordMenu(dlTypes);

		downloadMenu.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					int theType = downloadMenu.getSelectedKey();
					downloadMenu.setSelectedIndex(0);
					blockActionsEnd();
					if (theType > 0) {
						doDownloadAndImport(theType);
					}
				}
			}
		});

		// Pop-up menu for the create-and-import one-step data types.

		ArrayList<KeyedRecord> impTypes = ExtDb.getImportTypes();
		impTypes.add(0, new KeyedRecord(0, "Import"));
		importMenu = new KeyedRecordMenu(impTypes);

		importMenu.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					int theType = importMenu.getSelectedKey();
					importMenu.setSelectedIndex(0);
					blockActionsEnd();
					if (theType > 0) {
						doCreateAndImport(theType);
					}
				}
			}
		});

		// Pop-up menu for the create-first, multi-import data types, and a button to import into the selected set.

		ArrayList<KeyedRecord> genTypes = ExtDb.getGenericTypes();
		genTypes.add(0, new KeyedRecord(0, "Create"));
		createGenericMenu = new KeyedRecordMenu(genTypes);

		createGenericMenu.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					int theType = createGenericMenu.getSelectedKey();
					createGenericMenu.setSelectedIndex(0);
					blockActionsEnd();
					if (theType > 0) {
						doCreateGeneric(theType);
					}
				}
			}
		});

		importGenericButton = new JButton("Import");
		importGenericButton.setFocusable(false);
		importGenericButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doImportGeneric();
			}
		});

		// Do the layout.

		JPanel butLeft = new JPanel(new FlowLayout(FlowLayout.LEFT));
		butLeft.add(downloadMenu);
		butLeft.add(importMenu);

		JPanel butRight = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		butRight.add(createGenericMenu);
		butRight.add(importGenericButton);

		Box buttonBox = Box.createHorizontalBox();
		buttonBox.add(butLeft);
		buttonBox.add(butRight);

		Container cp = getContentPane();
		cp.setLayout(new BorderLayout());
		cp.add(extDbPanel, BorderLayout.CENTER);
		cp.add(buttonBox, BorderLayout.SOUTH);

		pack();

		Dimension theSize = new Dimension(500, 400);
		setMinimumSize(theSize);
		setSize(theSize);

		// Build the file menu.

		fileMenu.removeAll();

		// Previous

		JMenuItem miPrevious = new JMenuItem("Previous");
		miPrevious.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_UP, AppController.MENU_SHORTCUT_KEY_MASK));
		miPrevious.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doPrevious();
			}
		});
		fileMenu.add(miPrevious);

		// Next

		JMenuItem miNext = new JMenuItem("Next");
		miNext.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, AppController.MENU_SHORTCUT_KEY_MASK));
		miNext.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doNext();
			}
		});
		fileMenu.add(miNext);

		// __________________________________

		fileMenu.addSeparator();

		// Refresh List

		JMenuItem miRefresh = new JMenuItem("Refresh List");
		miRefresh.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				updateExtDbList(true);
			}
		});
		fileMenu.add(miRefresh);

		// __________________________________

		fileMenu.addSeparator();

		// Download ->

		JMenu meDownload = new JMenu("Download");
		JMenuItem miDownload;
		for (KeyedRecord theType : dlTypes) {
			if (theType.key > 0) {
				miDownload = new JMenuItem(theType.name);
				final int typeKey = theType.key;
				miDownload.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doDownloadAndImport(typeKey);
					}
				});
				meDownload.add(miDownload);
			}
		}
		fileMenu.add(meDownload);

		// Import ->

		JMenu meImport = new JMenu("Import");
		JMenuItem miImport;
		for (KeyedRecord theType : impTypes) {
			if (theType.key > 0) {
				miImport = new JMenuItem(theType.name);
				final int typeKey = theType.key;
				miImport.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doCreateAndImport(typeKey);
					}
				});
				meImport.add(miImport);
			}
		}
		fileMenu.add(meImport);

		// Create ->

		JMenu meCreate = new JMenu("Create");
		JMenuItem miCreate;
		for (KeyedRecord theType : genTypes) {
			if (theType.key > 0) {
				miCreate = new JMenuItem(theType.name);
				final int typeKey = theType.key;
				miCreate.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doCreateGeneric(typeKey);
					}
				});
				meCreate.add(miCreate);
			}
		}
		fileMenu.add(meCreate);

		// __________________________________

		fileMenu.addSeparator();

		// Import...

		importGenericMenuItem = new JMenuItem("Import...");
		importGenericMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doImportGeneric();
			}
		});
		fileMenu.add(importGenericMenuItem);

		// Rename...

		renameExtDbMenuItem = new JMenuItem("Rename...");
		renameExtDbMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doRenameExtDb();
			}
		});
		fileMenu.add(renameExtDbMenuItem);

		// Delete

		deleteExtDbMenuItem = new JMenuItem("Delete");
		deleteExtDbMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doDeleteExtDb();
			}
		});
		fileMenu.add(deleteExtDbMenuItem);

		updateControls();

		updateDocumentName();
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected String getFileMenuName() {

		return "Data";
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected boolean showsEditMenu() {

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void updateDocumentName() {

		setDocumentName(DbCore.getHostDbName(getDbID()));
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void updateControls() {

		boolean eImport = false, eRename = false, eDelete = false;

		int rowIndex = extDbTable.getSelectedRow();
		if (rowIndex >= 0) {
			ExtDbListItem theItem = extDbModel.get(extDbTable.convertRowIndexToModel(rowIndex));
			eImport = ExtDb.isGeneric(theItem.type);
			eRename = true;
			eDelete = true;
		}

		importGenericButton.setEnabled(eImport);
		importGenericMenuItem.setEnabled(eImport);

		renameExtDbMenuItem.setEnabled(eRename);
		deleteExtDbMenuItem.setEnabled(eDelete);
	}


	//=================================================================================================================
	// Data class for the station data list.

	private class ExtDbListItem {

		private Integer key;
		private int type;
		private String id;
		private String name;
	}


	//=================================================================================================================

	private class ExtDbListModel extends AbstractTableModel {

		private static final String EXT_DB_TYPE_COLUMN = "Type";
		private static final String EXT_DB_ID_COLUMN = "Date";
		private static final String EXT_DB_NAME_COLUMN = "Name";

		private String[] columnNames = {
			EXT_DB_TYPE_COLUMN,
			EXT_DB_ID_COLUMN,
			EXT_DB_NAME_COLUMN
		};

		private static final int EXT_DB_TYPE_INDEX = 0;
		private static final int EXT_DB_ID_INDEX = 1;
		private static final int EXT_DB_NAME_INDEX = 2;

		private ArrayList<ExtDbListItem> modelRows;


		//-------------------------------------------------------------------------------------------------------------

		private ExtDbListModel() {

			super();

			modelRows = new ArrayList<ExtDbListItem>();
		}


		//-------------------------------------------------------------------------------------------------------------

		private JTable createTable() {

			JTable theTable = new JTable(this);
			AppController.configureTable(theTable);

			TableColumn theColumn = theTable.getColumn(EXT_DB_TYPE_COLUMN);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[6]);

			theColumn = theTable.getColumn(EXT_DB_ID_COLUMN);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[15]);

			theColumn = theTable.getColumn(EXT_DB_NAME_COLUMN);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[20]);

			return theTable;
		}


		//-------------------------------------------------------------------------------------------------------------

		private void setItems(ArrayList<ExtDbListItem> newItems) {

			modelRows.clear();
			modelRows.addAll(newItems);
			fireTableDataChanged();
		}


		//-------------------------------------------------------------------------------------------------------------

		private ExtDbListItem get(int rowIndex) {

			return modelRows.get(rowIndex);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void remove(int rowIndex) {

			modelRows.remove(rowIndex);
			fireTableRowsDeleted(rowIndex, rowIndex);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void itemWasChanged(int rowIndex) {

			fireTableRowsUpdated(rowIndex, rowIndex);
		}


		//-------------------------------------------------------------------------------------------------------------

		private int indexOf(ExtDbListItem theItem) {

			return modelRows.indexOf(theItem);
		}


		//-------------------------------------------------------------------------------------------------------------

		private int indexOfKey(Integer theKey) {

			int rowIndex = 0;
			for (ExtDbListItem theItem : modelRows) {
				if (theItem.key.equals(theKey)) {
					return rowIndex;
				}
				rowIndex++;
			}

			return -1;
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

			ExtDbListItem theItem = modelRows.get(rowIndex);

			switch (columnIndex) {

				case EXT_DB_TYPE_INDEX:
					return ExtDb.getTypeName(theItem.type);

				case EXT_DB_ID_INDEX:
					return theItem.id;

				case EXT_DB_NAME_INDEX:
					return theItem.name;
			}

			return "";
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update the list to current database contents, optionally preserving the table selection.

	private void updateExtDbList(boolean preserveSelection) {

		errorReporter.setTitle("Refresh Data List");

		ArrayList<ExtDbListItem> list = getItems(errorReporter);
		if (null == list) {
			return;
		}

		ExtDbListItem selectedItem = null;
		if (preserveSelection) {
			int rowIndex = extDbTable.getSelectedRow();
			if (rowIndex >= 0) {
				selectedItem = extDbModel.get(extDbTable.convertRowIndexToModel(rowIndex));
			}
		}

		extDbModel.setItems(list);

		if (selectedItem != null) {
			int rowIndex = extDbModel.indexOf(selectedItem);
			if (rowIndex >= 0) {
				rowIndex = extDbTable.convertRowIndexToView(rowIndex);
				extDbTable.setRowSelectionInterval(rowIndex, rowIndex);
				extDbTable.scrollRectToVisible(extDbTable.getCellRect(rowIndex, 0, true));
			}
		}

		updateControls();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Retrieve a list of all data items.

	private ArrayList<ExtDbListItem> getItems(ErrorReporter errors) {

		ArrayList<ExtDbListItem> result = new ArrayList<ExtDbListItem>();

		ExtDbListItem theItem;

		DbConnection db = DbCore.connectDb(getDbID(), errors);
		if (null != db) {
			try {

				db.query(
				"SELECT " +
					"ext_db_key, " +
					"db_type, " +
					"id, " +
					"is_download, " +
					"name " +
				"FROM " +
					"ext_db " +
				"WHERE " +
					"NOT deleted " +
				"ORDER BY 1 DESC");

				while (db.next()) {

					theItem = new ExtDbListItem();

					theItem.key = Integer.valueOf(db.getInt(1));
					theItem.type = db.getInt(2);
					theItem.id = db.getString(3);
					if (db.getBoolean(4)) {
						theItem.name = DOWNLOAD_SET_NAME;
					} else {
						theItem.name = db.getString(5);
					}

					result.add(theItem);
				}

				DbCore.releaseDb(db);

			} catch (SQLException se) {
				DbCore.releaseDb(db);
				DbConnection.reportError(errors, se);
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Re-query the database record for an entry.  Return the new object, or null if it no longer exists or is now
	// marked deleted.  In the latter case the row is removed from the table.  If an error occurs keep the old object.

	private ExtDbListItem checkExtDb(int rowIndex, ErrorReporter errors) {

		ExtDbListItem theItem = extDbModel.get(rowIndex);

		boolean error = false, notfound = false;

		DbConnection db = DbCore.connectDb(getDbID(), errors);
		if (null != db) {
			try {

				db.query(
				"SELECT " +
					"db_type, " +
					"id, " +
					"is_download, " +
					"name " +
				"FROM " +
					"ext_db " +
				"WHERE " +
					"NOT deleted " +
					"AND ext_db_key = " + theItem.key);

				if (db.next()) {

					theItem.type = db.getInt(1);
					theItem.id = db.getString(2);
					if (db.getBoolean(3)) {
						theItem.name = DOWNLOAD_SET_NAME;
					} else {
						theItem.name = db.getString(4);
					}

				} else {
					notfound = true;
				}

				DbCore.releaseDb(db);

			} catch (SQLException se) {
				DbCore.releaseDb(db);
				error = true;
				DbConnection.reportError(errors, se);
			}

		} else {
			return null;
		}

		if (notfound) {
			if (null != errors) {
				errors.reportError("The station data no longer exists.");
			}
			extDbModel.remove(rowIndex);
			return null;
		}

		if (error) {
			return null;
		}

		extDbModel.itemWasChanged(rowIndex);

		return theItem;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Move selection up or down in the table.

	private void doPrevious() {

		int size = extDbTable.getRowCount();
		int rowIndex = extDbTable.getSelectedRow();
		if ((size > 0) && (rowIndex != 0)) {
			if (rowIndex < 0) {
				rowIndex = size - 1;
			} else {
				rowIndex--;
			}
			extDbTable.setRowSelectionInterval(rowIndex, rowIndex);
			extDbTable.scrollRectToVisible(extDbTable.getCellRect(rowIndex, 0, true));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doNext() {

		int size = extDbTable.getRowCount();
		int rowIndex = extDbTable.getSelectedRow();
		if ((size > 0) && (rowIndex < (size - 1))) {
			if (rowIndex < 0) {
				rowIndex = 0;
			} else {
				rowIndex++;
			}
			extDbTable.setRowSelectionInterval(rowIndex, rowIndex);
			extDbTable.scrollRectToVisible(extDbTable.getCellRect(rowIndex, 0, true));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Do a create-and-import of a static data set.  When adding another data set no attempt is made to check for
	// duplication; so what?  An optional descriptive name may be set for UI labelling.  If that is blank the UI label
	// will be an ID string appropriate to the data.

	private void doCreateAndImport(int theType) {

		String title = "Import " + ExtDb.getTypeName(theType) + " Station Data";
		errorReporter.setTitle(title);

		errorReporter.clearMessages();

		JFileChooser chooser = new JFileChooser(AppCore.getProperty(AppCore.LAST_FILE_DIRECTORY_KEY));
		chooser.setDialogType(JFileChooser.OPEN_DIALOG);
		chooser.setMultiSelectionEnabled(false);

		JTextField nameField = new JTextField(12);
		AppController.fixKeyBindings(nameField);
		JPanel namePanel = new JPanel();
		namePanel.setBorder(BorderFactory.createTitledBorder("Data set name (optional)"));
		namePanel.add(nameField);

		chooser.setAccessory(namePanel);

		Integer newKey = null;

		switch (theType) {

			// For CDBS and LMS databases the station data is SQL dump files with fixed names, user just selects
			// enclosing directory and the rest of the logic is in ExtDb.createNewDatabase().  This now also supports
			// selecting the data dump ZIP file directly, the import code will extract files from that.

			case ExtDb.DB_TYPE_CDBS:
			case ExtDb.DB_TYPE_LMS:
			case ExtDb.DB_TYPE_CDBS_FM: {

				chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
				chooser.addChoosableFileFilter(new FileNameExtensionFilter("ZIP (*.zip)", "zip"));
				chooser.setAcceptAllFileFilterUsed(false);
				chooser.setDialogTitle("Choose " + ExtDb.getTypeName(theType) + " data file directory, or ZIP file");

				if (JFileChooser.APPROVE_OPTION != chooser.showDialog(this, "Choose")) {
					break;
				}
				File theFile = chooser.getSelectedFile();

				AppCore.setProperty(AppCore.LAST_FILE_DIRECTORY_KEY, theFile.getParentFile().getAbsolutePath());

				final int extDbType = theType;
				final File sourceFile = theFile;
				final String extDbName = nameField.getText().trim();

				BackgroundWorker<Integer> theWorker = new BackgroundWorker<Integer>(this, title) {
					protected Integer doBackgroundWork(ErrorLogger errors) {
						return ExtDb.createNewDatabase(getDbID(), extDbType, sourceFile, extDbName, this, errors);
					}
				};
				theWorker.showCancel();

				newKey = theWorker.runWork("Importing data files...", errorReporter);

				break;
			}

			// For wireless station data input, user selects individual station data and pattern files.  Those are
			// translated into temporary files in SQL dump format compatible with ExtDb.createNewDatabase(), see
			// createWirelessTableFiles().

			case ExtDb.DB_TYPE_WIRELESS: {

				chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
				chooser.setDialogTitle("Choose wireless base station data file");

				if (JFileChooser.APPROVE_OPTION != chooser.showDialog(this, "Choose")) {
					break;
				}
				File stationFile = chooser.getSelectedFile();

				// There seems to be no way to clear the selection from an existing chooser object, a different file
				// can be selected but a previously-used chooser cannot be returned to the new-object empty selection
				// state.  If the same chooser were re-used here it would show the file selected from the first
				// appearance, that would be confusing to the user so just have to create a new object.  The name
				// panel doesn't have to appear a second time.

				chooser = new JFileChooser(stationFile.getParentFile().getAbsolutePath());
				chooser.setDialogType(JFileChooser.OPEN_DIALOG);
				chooser.setMultiSelectionEnabled(false);
				chooser.setDialogTitle("Choose wireless pattern data file");

				if (JFileChooser.APPROVE_OPTION != chooser.showDialog(this, "Choose")) {
					break;
				}
				File patternFile = chooser.getSelectedFile();

				AppCore.setProperty(AppCore.LAST_FILE_DIRECTORY_KEY, patternFile.getParentFile().getAbsolutePath());

				File theDir = createWirelessTableFiles(stationFile, patternFile);
				if (null == theDir) {
					break;
				}

				final int extDbType = theType;
				final File fileDirectory = theDir;
				final String extDbName = nameField.getText().trim();

				BackgroundWorker<Integer> theWorker = new BackgroundWorker<Integer>(this, title) {
					protected Integer doBackgroundWork(ErrorLogger errors) {
						return ExtDb.createNewDatabase(getDbID(), extDbType, fileDirectory, extDbName, this, errors);
					}
				};
				theWorker.showCancel();

				newKey = theWorker.runWork("Importing data files...", errorReporter);

				// Delete the temporary import files.

				(new File(theDir, ExtDb.WIRELESS_BASE_FILE)).delete();
				(new File(theDir, ExtDb.WIRELESS_INDEX_FILE)).delete();
				(new File(theDir, ExtDb.WIRELESS_PATTERN_FILE)).delete();
				theDir.delete();

				break;
			}

			default: {
				errorReporter.reportError("Unknown or unsupported station data type.");
				break;
			}
		}

		if (null == newKey) {
			return;
		}

		errorReporter.showMessages();

		updateExtDbList(false);

		int rowIndex = extDbModel.indexOfKey(newKey);
		if (rowIndex >= 0) {
			rowIndex = extDbTable.convertRowIndexToView(rowIndex);
			extDbTable.setRowSelectionInterval(rowIndex, rowIndex);
			extDbTable.scrollRectToVisible(extDbTable.getCellRect(rowIndex, 0, true));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Do a download-and-import of CDBS or LMS data.

	private void doDownloadAndImport(int theType) {

		String title = "Download " + ExtDb.getTypeName(theType) + " Station Data";
		errorReporter.setTitle(title);

		errorReporter.clearMessages();

		final int extDbType = theType;

		BackgroundWorker<Integer> theWorker = new BackgroundWorker<Integer>(this, title) {
			protected Integer doBackgroundWork(ErrorLogger errors) {
				return ExtDb.downloadDatabase(getDbID(), extDbType, "", this, errors);
			}
		};
		theWorker.showCancel();

		Integer newKey = theWorker.runWork("Downloading and importing data...", errorReporter);
		if (null == newKey) {
			return;
		}

		errorReporter.showMessages();

		updateExtDbList(false);

		int rowIndex = extDbModel.indexOfKey(newKey);
		if (rowIndex >= 0) {
			rowIndex = extDbTable.convertRowIndexToView(rowIndex);
			extDbTable.setRowSelectionInterval(rowIndex, rowIndex);
			extDbTable.scrollRectToVisible(extDbTable.getCellRect(rowIndex, 0, true));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create temporary SQL-format data files for a wireless station data import, drawing from CSV files in a more
	// user-friendly format.  The data in the CSV files has to be error-checked and appropriately re-formatted to
	// follow conventions used in the SQL tables.  Return the enclosing temporary directory, or null on error.

	private File createWirelessTableFiles(File stationFile, File patternFile) {

		String errmsg = null;

		BufferedReader stationReader = null, patternReader = null;
		File theDirectory = null, baseFile = null, patIndexFile = null, patFile = null;
		BufferedWriter baseWriter = null, patIndexWriter = null, patWriter = null;

		// Open the CSV input files.

		try {
			stationReader = new BufferedReader(new FileReader(stationFile));
		} catch (FileNotFoundException fe) {
			errmsg = "Data file '" + stationFile.getName() + "' could not be opened";
		}

		if (null == errmsg) {
			try {
				patternReader = new BufferedReader(new FileReader(patternFile));
			} catch (FileNotFoundException fe) {
				errmsg = "Data file '" + patternFile.getName() + "' could not be opened";
			}
		}

		// Create a temporary directory and the output table files.

		if (null == errmsg) {

			try {

				theDirectory = Files.createTempDirectory("wl_import").toFile();

				baseFile = new File(theDirectory, ExtDb.WIRELESS_BASE_FILE);
				baseWriter = new BufferedWriter(new FileWriter(baseFile));

				patIndexFile = new File(theDirectory, ExtDb.WIRELESS_INDEX_FILE);
				patIndexWriter = new BufferedWriter(new FileWriter(patIndexFile));

				patFile = new File(theDirectory, ExtDb.WIRELESS_PATTERN_FILE);
				patWriter = new BufferedWriter(new FileWriter(patFile));

			} catch (IOException ie) {
				errmsg = "Could not create temporary files for data transfer";
			}
		}

		// Copy data from the main station file.  Assign a key to each row, just use the line counter.  In other types
		// of station data the primary key is persistent across different imports but that isn't possible here, so the
		// keys are valid only within a specific import.  The keys will never be exported.

		if (null == errmsg) {

			String fileName = "", line, str;
			AppCore.Counter lineCount = new AppCore.Counter(0);
			String[] fields;

			try {

				fileName = stationFile.getName();

				String cellSiteID, sectorID, referenceNumber, city, state, country;
				double cellLat, cellLon, rcAMSL, haat, erp, orientation, eTilt, mTilt, mTiltOrientation;
				int azAntID, elAntID;

				while (true) {

					line = AppCore.readLineSkipComments(stationReader, lineCount);
					if (null == line) {
						break;
					}
					if (line.contains("|")) {
						errmsg = "Illegal character '|' in '" + fileName + "' at line " + lineCount;
						break;
					}
					fields = line.split(",");

					// The reference number, city, state, and country fields at the end of the line are optional and
					// may all be missing or empty if present.

					if ((fields.length < 13) || (fields.length > 17)) {
						errmsg = "Bad field count in '" + fileName + "' at line " + lineCount;
						break;
					}

					// The cell site ID string must not be empty, the sector ID may be.  The site and sector IDs have
					// limited length, if exceeded truncate and log an informational message.  Neither may contain a
					// '|' character as that is used in the SQL data file as field separator.

					cellSiteID = fields[0].trim();
					if (0 == cellSiteID.length()) {
						errmsg = "Missing cell site ID in '" + fileName + "' at line " + lineCount;
						break;
					}
					if (cellSiteID.length() > Source.MAX_CALL_SIGN_LENGTH) {
						cellSiteID = cellSiteID.substring(0, Source.MAX_CALL_SIGN_LENGTH);
						errorReporter.logMessage("Cell site ID too long, truncated, in '" + fileName + "' at line " +
							lineCount);
					}

					sectorID = fields[1].trim();
					if (sectorID.length() > Source.MAX_SECTOR_ID_LENGTH) {
						sectorID = sectorID.substring(0, Source.MAX_SECTOR_ID_LENGTH);
						errorReporter.logMessage("Sector ID too long, truncated, in '" + fileName + "' at line " +
							lineCount);
					}

					// Check for valid numbers in latitude, longitude, AMSL height, HAAT, and ERP.

					cellLat = Source.LATITUDE_MIN - 1.;
					str = fields[2].trim();
					if (str.length() > 0) {
						try {
							cellLat = Double.parseDouble(str);
						} catch (NumberFormatException ne) {
						}
					}
					if ((cellLat < Source.LATITUDE_MIN) || (cellLat > Source.LATITUDE_MAX)) {
						errmsg = "Missing or bad latitude in '" + fileName + "' at line " + lineCount;
						break;
					}

					// Longitude is negative west in this data, reverse the sign.

					cellLon = Source.LONGITUDE_MIN - 1.;
					str = fields[3].trim();
					if (str.length() > 0) {
						try {
							cellLon = -Double.parseDouble(str);
						} catch (NumberFormatException ne) {
						}
					}
					if ((cellLon < Source.LONGITUDE_MIN) || (cellLon > Source.LONGITUDE_MAX)) {
						errmsg = "Missing or bad longitude in '" + fileName + "' at line " + lineCount;
						break;
					}

					rcAMSL = Source.HEIGHT_MIN - 1.;
					str = fields[4].trim();
					if (str.length() > 0) {
						try {
							rcAMSL = Double.parseDouble(str);
						} catch (NumberFormatException ne) {
						}
					}
					if ((rcAMSL < Source.HEIGHT_MIN) || (rcAMSL > Source.HEIGHT_MAX)) {
						errmsg = "Missing or bad AMSL height in '" + fileName + "' at line " + lineCount;
						break;
					}

					haat = Source.HEIGHT_MIN - 1.;
					str = fields[5].trim();
					if (str.length() > 0) {
						try {
							haat = Double.parseDouble(str);
						} catch (NumberFormatException ne) {
						}
					}
					if ((haat < Source.HEIGHT_MIN) || (haat > Source.HEIGHT_MAX)) {
						errmsg = "Missing or bad HAAT in '" + fileName + "' at line " + lineCount;
						break;
					}

					erp = Source.ERP_MIN - 1.;
					str = fields[6].trim();
					if (str.length() > 0) {
						try {
							erp = Double.parseDouble(str);
						} catch (NumberFormatException ne) {
						}
					}
					if ((erp < Source.ERP_MIN) || (erp > Source.ERP_MAX)) {
						errmsg = "Bad ERP in '" + fileName + "' at line " + lineCount;
						break;
					}

					azAntID = 0;
					str = fields[7].trim();
					if (str.length() > 0) {
						azAntID = -1;
						try {
							azAntID = Integer.parseInt(str);
						} catch (NumberFormatException ne) {
						}
						if (azAntID < 0) {
							errmsg = "Bad azimuth antenna ID in '" + fileName + "' at line " + lineCount;
							break;
						}
					}

					// Orientation and tilt values are always parsed even if pattern ID indicates omni, and all default
					// to 0 if the field is empty, again regardless of omni.

					orientation = 0.;
					str = fields[8].trim();
					if (str.length() > 0) {
						try {
							orientation = Math.IEEEremainder(Double.parseDouble(str), 360.);
							if (orientation < 0.) orientation += 360.;
						} catch (NumberFormatException ne) {
							errmsg = "Bad pattern orientation in '" + fileName + "' at line " + lineCount;
							break;
						}
					}

					elAntID = 0;
					str = fields[9].trim();
					if (str.length() > 0) {
						elAntID = -1;
						try {
							elAntID = Integer.parseInt(str);
						} catch (NumberFormatException ne) {
						}
						if (elAntID < 0) {
							errmsg = "Bad elevation antenna ID in '" + fileName + "' at line " + lineCount;
							break;
						}
					}

					eTilt = 0.;
					str = fields[10].trim();
					if (str.length() > 0) {
						eTilt = AntPattern.TILT_MIN - 1.;
						try {
							eTilt = Double.parseDouble(str);
						} catch (NumberFormatException ne) {
						}
						if ((eTilt < AntPattern.TILT_MIN) || (eTilt > AntPattern.TILT_MAX)) {
							errmsg = "Bad electrical tilt in '" + fileName + "' at line " + lineCount;
							break;
						}
					}

					mTilt = 0.;
					str = fields[11].trim();
					if (str.length() > 0) {
						mTilt = AntPattern.TILT_MIN - 1.;
						try {
							mTilt = Double.parseDouble(str);
						} catch (NumberFormatException ne) {
						}
						if ((mTilt < AntPattern.TILT_MIN) || (mTilt > AntPattern.TILT_MAX)) {
							errmsg = "Bad mechanical tilt in '" + fileName + "' at line " + lineCount;
							break;
						}
					}

					// The tilt orientation defaults to the pattern orientation.

					mTiltOrientation = orientation;
					str = fields[12].trim();
					if (str.length() > 0) {
						try {
							mTiltOrientation = Math.IEEEremainder(Double.parseDouble(str), 360.);
							if (mTiltOrientation < 0.) mTiltOrientation += 360.;
						} catch (NumberFormatException ne) {
							errmsg = "Bad mechanical tilt orientation in '" + fileName + "' at line " + lineCount;
							break;
						}
					}

					// Optional reference number, city, state, and country; if present there are length restrictions
					// however truncation is not logged for these, this is secondary identifying information.

					referenceNumber = "";
					if (fields.length >= 14) {
						referenceNumber = fields[13].trim();
						if (referenceNumber.length() > Source.MAX_FILE_NUMBER_LENGTH) {
							referenceNumber = referenceNumber.substring(0, Source.MAX_FILE_NUMBER_LENGTH);
						}
					}

					city = "";
					if (fields.length >= 15) {
						city = fields[14].trim();
						if (city.length() > Source.MAX_CITY_LENGTH) {
							city = city.substring(0, Source.MAX_CITY_LENGTH);
						}
					}

					state = "";
					if (fields.length >= 16) {
						state = fields[15].trim();
						if (state.length() > Source.MAX_STATE_LENGTH) {
							state = state.substring(0, Source.MAX_STATE_LENGTH);
						}
					}

					country = "";
					if (fields.length >= 17) {
						country = fields[16].trim();
						if (country.length() > 2) {
							country = country.substring(0, 2);
						}
					}

					// Write the row.

					baseWriter.write(String.valueOf(lineCount));          // cell_key
					baseWriter.write('|');
					baseWriter.write(cellSiteID);                         // cell_site_id
					baseWriter.write('|');
					baseWriter.write(sectorID);                           // sector_id
					baseWriter.write('|');
					baseWriter.write(String.valueOf(cellLat));            // cell_lat
					baseWriter.write('|');
					baseWriter.write(String.valueOf(cellLon));            // cell_lon
					baseWriter.write('|');
					baseWriter.write(String.valueOf(rcAMSL));             // rc_amsl
					baseWriter.write('|');
					baseWriter.write(String.valueOf(haat));               // haat
					baseWriter.write('|');
					baseWriter.write(String.valueOf(erp));                // erp
					baseWriter.write('|');
					baseWriter.write(String.valueOf(azAntID));            // az_ant_id
					baseWriter.write('|');
					baseWriter.write(String.valueOf(orientation));        // orientation
					baseWriter.write('|');
					baseWriter.write(String.valueOf(elAntID));            // el_ant_id
					baseWriter.write('|');
					baseWriter.write(String.valueOf(eTilt));              // e_tilt
					baseWriter.write('|');
					baseWriter.write(String.valueOf(mTilt));              // m_tilt
					baseWriter.write('|');
					baseWriter.write(String.valueOf(mTiltOrientation));   // m_tilt_orientation
					baseWriter.write('|');
					baseWriter.write(referenceNumber);                    // reference_number
					baseWriter.write('|');
					baseWriter.write(city);                               // city
					baseWriter.write('|');
					baseWriter.write(state);                              // state
					baseWriter.write('|');
					baseWriter.write(country);                            // country
					baseWriter.write("|^|");
					baseWriter.newLine();
				}

				// If all went well with station data, copy the pattern data.  In the input the full pattern tabulation
				// for each pattern is on a single line, break that out into an index of IDs, types, and names, and a
				// separate data table with one pattern point per row.  Each pattern point has the degree and field
				// values separated by a semicolon, with the points separated by commas. There must be at least 2
				// points in a pattern.  Degree and field values are range-checked, and degree values are checked for
				// correct order and for duplication.  Also check for a 1.0 field value, if not found log a warning
				// message but continue.

				if (null == errmsg) {

					fileName = patternFile.getName();
					lineCount.reset();

					int antID, i;
					char patType;
					boolean isAzPat;
					String patName, theID;
					String[] patFields;
					double degree, field, lastDegree, fieldMax;

					while (true) {

						line = AppCore.readLineSkipComments(patternReader, lineCount);
						if (null == line) {
							break;
						}
						fields = line.split(",");
						if (fields.length < (AntPattern.PATTERN_REQUIRED_POINTS + 3)) {
							errmsg = "Bad field count in '" + fileName + "' at line " + lineCount;
							break;
						}

						// Write to the pattern index, the ID and type are checked, the name must not be empty.

						antID = -1;
						str = fields[0].trim();
						if (str.length() > 0) {
							try {
								antID = Integer.parseInt(str);
							} catch (NumberFormatException ne) {
							}
						}
						if (antID <= 0) {
							errmsg = "Missing or bad antenna ID in '" + fileName + "' at line " + lineCount;
							break;
						}
						theID = String.valueOf(antID);

						patType = ' ';
						str = fields[1].trim();
						if (str.length() > 0) {
							patType = str.toUpperCase().charAt(0);
						}
						if (('A' != patType) && ('E' != patType)) {
							errmsg = "Missing or bad pattern type in '" + fileName + "' at line " + lineCount;
							break;
						}
						isAzPat = ('A' == patType);

						patName = fields[2].trim();
						if (0 == patName.length()) {
							errmsg = "Missing pattern name in '" + fileName + "' at line " + lineCount;
							break;
						}
						if (patName.length() > Source.MAX_PATTERN_NAME_LENGTH) {
							patName = patName.substring(0, Source.MAX_PATTERN_NAME_LENGTH);
							errorReporter.logMessage("Pattern name too long, truncated, in '" + fileName +
								"' at line " + lineCount);
						}
						if (patName.contains("|")) {
							errmsg = "Illegal character '|' in pattern name in '" + fileName + "' at line " +
								lineCount;
							break;
						}

						// Write the index row.

						patIndexWriter.write(theID);     // ant_id
						patIndexWriter.write('|');
						patIndexWriter.write(patType);   // pat_type
						patIndexWriter.write('|');
						patIndexWriter.write(patName);   // name
						patIndexWriter.write("|^|");
						patIndexWriter.newLine();

						// Copy the data points, check values.

						if (isAzPat) {
							lastDegree = AntPattern.AZIMUTH_MIN - 1.;
						} else {
							lastDegree = AntPattern.DEPRESSION_MIN - 1.;
						}
						fieldMax = 0.;

						for (i = 3; i < fields.length; i++) {

							patFields = fields[i].split(";");
							if (patFields.length != 2) {
								errmsg = "Bad pattern point format in '" + fileName + "' at line " + lineCount +
									" point " + (i - 2);
								break;
							}

							str = patFields[0].trim();
							if (isAzPat) {
								degree = AntPattern.AZIMUTH_MIN - 1.;
							} else {
								degree = AntPattern.DEPRESSION_MIN - 1.;
							}
							if (str.length() > 0) {
								try {
									degree = Double.parseDouble(str);
								} catch (NumberFormatException ne) {
								}
							}
							if (isAzPat) {
								if ((degree < AntPattern.AZIMUTH_MIN) || (degree > AntPattern.AZIMUTH_MAX)) {
									errmsg = "Bad azimuth in '" + fileName + "' at line " + lineCount + " point " +
										(i - 2);
									break;
								}
							} else {
								if ((degree < AntPattern.DEPRESSION_MIN) || (degree > AntPattern.DEPRESSION_MAX)) {
									errmsg = "Bad vertical angle in '" + fileName + "' at line " + lineCount +
										" point " + (i - 2);
									break;
								}
							}
							if (degree <= lastDegree) {
								errmsg = "Pattern points out of order or duplicated in '" + fileName + "' at line " +
									lineCount + " point " + (i - 2);
								break;
							}
							lastDegree = degree;

							// This does not use the FIELD_MIN, FIELD_MAX, and FIELD_MAX_CHECK constants as in other
							// code.  Here the field just has to be greater than 0, the FIELD_MIN limit will be applied
							// when the pattern is loaded from the SQL table, see ExtDbRecord.  For the max check an
							// exact 1.0 is expected here, FIELD_MAX_CHECK is a value somewhat less than 1 to reduce
							// the frequency of the warning with CDBS/LMS data.  But a stricter test is appropriate
							// here since this is assumed to be user-generated data not a dump from another database.

							field = -1.;
							str = patFields[1].trim();
							if (str.length() > 0) {
								try {
									field = Double.parseDouble(str);
								} catch (NumberFormatException ne) {
								}
							}
							if ((field <= 0.) || (field > 1.)) {
								errmsg = "Bad relative field in '" + fileName + "' at line " + lineCount + " point " +
									(i - 2);
								break;
							}
							if (field > fieldMax) {
								fieldMax = field;
							}

							// Write the row.

							patWriter.write(theID);                    // ant_id
							patWriter.write('|');
							patWriter.write(String.valueOf(degree));   // degree
							patWriter.write('|');
							patWriter.write(String.valueOf(field));    // relative_field
							patWriter.write("|^|");
							patWriter.newLine();
						}

						if (null != errmsg) {
							break;
						}

						if (fieldMax < 1.) {
							errorReporter.logMessage("Pattern does not contain a 1 for antenna ID " + theID + " in '" +
								fileName + "' at line " + lineCount);
						}
					}
				}

			} catch (IOException ie) {
				errmsg = "An I/O error occurred in '" + fileName + "' at line " + lineCount + ":\n" + ie;

			} catch (Throwable t) {
				errmsg = "An unexpected error occurred in '" + fileName + "' at line " + lineCount + ":\n" + t;
				AppCore.log(AppCore.ERROR_MESSAGE, "Unexpected error", t);
			}
		}

		// Close files.

		if (null != stationReader) {
			try {stationReader.close();} catch (IOException ie) {};
		}
		if (null != patternReader) {
			try {patternReader.close();} catch (IOException ie) {};
		}
		if (null != baseWriter) {
			try {baseWriter.close();} catch (IOException ie) {};
		}
		if (null != patIndexWriter) {
			try {patIndexWriter.close();} catch (IOException ie) {};
		}
		if (null != patWriter) {
			try {patWriter.close();} catch (IOException ie) {};
		}

		// If an error occurred, delete the temporary output files and directory and return the error.

		if (null != errmsg) {
			if (null != baseWriter) {
				baseFile.delete();
			}
			if (null != patIndexWriter) {
				patIndexFile.delete();
			}
			if (null != patWriter) {
				patFile.delete();
			}
			if (null != theDirectory) {
				theDirectory.delete();
			}
			errorReporter.reportError(errmsg);
			return null;
		}

		return theDirectory;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create a generic data set, just provide the optional name, the rest is done by ExtDb.createNewGenericDatabase().

	private void doCreateGeneric(int theType) {

		String title = "Create " + ExtDb.getTypeName(theType) + " Station Data";
		errorReporter.setTitle(title);

		if (!ExtDb.isGeneric(theType)) {
			errorReporter.reportError("Unknown or unsupported station data type.");
			return;
		}

		String theName = null;
		do {
			theName = (String)(JOptionPane.showInputDialog(this, "Enter a name for the data (optional)", title,
				JOptionPane.QUESTION_MESSAGE, null, null, ""));
			if (null == theName) {
				return;
			}
			theName = theName.trim();
			if (theName.length() > 0) {
				if (theName.equals(DOWNLOAD_SET_NAME)) {
					errorReporter.reportWarning("That name cannot be used, please try again.");
					theName = null;
				} else {
					for (int i = 0; i < extDbModel.getRowCount(); i++) {
						if (theName.equalsIgnoreCase(extDbModel.get(i).name)) {
							errorReporter.reportWarning("That name is already in use, please try again.");
							theName = null;
							break;
						}
					}
				}
			}
		} while (null == theName);

		Integer newKey = ExtDb.createNewGenericDatabase(getDbID(), theType, theName, errorReporter);
		if (null == newKey) {
			return;
		}

		updateExtDbList(false);

		int rowIndex = extDbModel.indexOfKey(newKey);
		if (rowIndex >= 0) {
			rowIndex = extDbTable.convertRowIndexToView(rowIndex);
			extDbTable.setRowSelectionInterval(rowIndex, rowIndex);
			extDbTable.scrollRectToVisible(extDbTable.getCellRect(rowIndex, 0, true));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Import into a generic data set.  Right now all this supports is XML, it will have more parsers soon.

	private void doImportGeneric() {

		int rowIndex = extDbTable.getSelectedRow();
		if (rowIndex < 0) {
			return;
		}

		String title = "Import Station Data From XML";
		errorReporter.setTitle(title);

		ExtDbListItem theItem = extDbModel.get(extDbTable.convertRowIndexToModel(rowIndex));
		final ExtDb extDb = ExtDb.getExtDb(getDbID(), theItem.key, errorReporter);
		if (null == extDb) {
			return;
		}

		if (!extDb.isGeneric()) {
			errorReporter.reportError("Unknown or unsupported station data type.");
			return;
		}

		JFileChooser chooser = new JFileChooser(AppCore.getProperty(AppCore.LAST_FILE_DIRECTORY_KEY));
		chooser.setDialogType(JFileChooser.OPEN_DIALOG);
		chooser.setDialogTitle(title);
		chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		chooser.setMultiSelectionEnabled(false);
		chooser.addChoosableFileFilter(new FileNameExtensionFilter("XML (*.xml)", "xml"));
		chooser.setAcceptAllFileFilterUsed(false);

		if (JFileChooser.APPROVE_OPTION != chooser.showDialog(this, "Import")) {
			return;
		}

		File theFile = chooser.getSelectedFile();

		AppCore.setProperty(AppCore.LAST_FILE_DIRECTORY_KEY, theFile.getParentFile().getAbsolutePath());

		ArrayList<KeyedRecord> list = ExtDb.getExtDbList(getDbID(), extDb.recordType, errorReporter);
		if (null == list) {
			return;
		}

		PickExtDbDialog promptDialog = new PickExtDbDialog(this, title, list, null);
		AppController.showWindow(promptDialog);
		if (promptDialog.canceled) {
			return;
		}
		int theKey = promptDialog.lookupExtDbKey;
		final Integer lookupExtDbKey = ((theKey > 0) ? Integer.valueOf(theKey) : null);
		if (theKey > 0) {
			theKey = promptDialog.alternateExtDbKey;
		}
		final Integer alternateExtDbKey = ((theKey > 0) ? Integer.valueOf(theKey) : null);

		FileReader theReader = null;
		try {
			theReader = new FileReader(theFile);
		} catch (FileNotFoundException fnfe) {
			errorReporter.reportError("Could not open the file:\n" + fnfe.getMessage());
			return;
		}

		final BufferedReader xml = new BufferedReader(theReader);

		BackgroundWorker<Integer> theWorker = new BackgroundWorker<Integer>(this, title) {
			protected Integer doBackgroundWork(ErrorLogger errors) {

				DbConnection db = extDb.connectAndLock(errors);
				if (null == db) {
					return null;
				}

				int sourceCount = 0;

				try {

					ParseXML handler = new ParseXML(extDb, lookupExtDbKey, alternateExtDbKey, errors);

					XMLReader xReader = XMLReaderFactory.createXMLReader("org.apache.xerces.parsers.SAXParser");
					xReader.setContentHandler(handler);
					xReader.parse(new InputSource(xml));

					for (SourceEditData theSource : handler.sources) {
						theSource.isDataChanged();
						theSource.save(db);
						sourceCount++;
					}

					if (0 == sourceCount) {
						if (handler.hadStudy) {
							errors.reportWarning("No compatible records found.");
						} else {
							errors.reportWarning("No recognized XML structure found.");
						}
					}

				} catch (SAXException se) {
					String msg = se.getMessage();
					if ((null != msg) && (msg.length() > 0)) {
						errors.reportError("XML error: " + msg);
					}
					sourceCount = -1;
				} catch (SQLException se) {
					db.reportError(errors, se);
					sourceCount = -1;
				} catch (Throwable t) {
					AppCore.log(AppCore.ERROR_MESSAGE, "Unexpected error", t);
					errors.reportError("An unexpected error occurred: " + t);
					sourceCount = -1;
				}

				extDb.releaseDb(db);

				if (sourceCount <= 0) {
					return null;
				}

				return Integer.valueOf(sourceCount);
			}
		};

		errorReporter.clearMessages();

		Integer count = theWorker.runWork("Importing records, please wait...", errorReporter);

		try {xml.close();} catch (IOException ie) {}

		if (null != count) {
			errorReporter.showMessages();
			errorReporter.reportMessage("Imported " + count + " records.");
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The name is really just a description, but for consistency this does enforce uniqueness.  The name is always
	// optional so it can be set to an empty string, in which case the permanent ID string appears in the UI.  When a
	// name is set non-empty, the is_download flag is also cleared; see ExtDb.downloadDatabase() for details.

	private void doRenameExtDb() {

		int rowIndex = extDbTable.getSelectedRow();
		if (rowIndex < 0) {
			return;
		}

		String title = "Rename Station Data";
		errorReporter.setTitle(title);

		rowIndex = extDbTable.convertRowIndexToModel(rowIndex);
		ExtDbListItem theItem = checkExtDb(rowIndex, errorReporter);
		if (null == theItem) {
			return;
		}

		String oldName = theItem.name, newName = null;
		if (oldName.equals(DOWNLOAD_SET_NAME)) oldName = "";
		do {
			newName = (String)(JOptionPane.showInputDialog(this, "Enter a name for the data (optional)", title,
				JOptionPane.QUESTION_MESSAGE, null, null, oldName));
			if (null == newName) {
				return;
			}
			newName = newName.trim();
			if (newName.length() > 0) {
				if (newName.equals(DOWNLOAD_SET_NAME)) {
					errorReporter.reportWarning("That name cannot be used, please try again.");
					newName = null;
				} else {
					for (int i = 0; i < extDbModel.getRowCount(); i++) {
						if ((i != rowIndex) && newName.equalsIgnoreCase(extDbModel.get(i).name)) {
							errorReporter.reportWarning("That name is already in use, please try again.");
							newName = null;
							break;
						}
					}
				}
			}
		} while (null == newName);

		if ((newName.length() > 0) && newName.equals(theItem.name)) {
			return;
		}

		DbConnection db = DbCore.connectDb(getDbID(), errorReporter);
		if (null != db) {

			String errmsg = null;
			int errtyp = AppCore.ERROR_MESSAGE;

			try {

				db.update("LOCK TABLES ext_db WRITE");

				if (newName.length() > 0) {

					String theName = "'" + db.clean(newName) + "'";

					db.query("SELECT ext_db_key FROM ext_db WHERE UPPER(name) = " + theName.toUpperCase() +
						" AND ext_db_key <> " + theItem.key);
					if (!db.next()) {
						db.update("UPDATE ext_db SET name = " + theName + ", is_download = false WHERE ext_db_key = " +
							theItem.key);
					} else {
						errmsg = "That name is already in use.";
						errtyp = AppCore.WARNING_MESSAGE;
					}

				} else {
					db.update("UPDATE ext_db SET name = '', is_download = false WHERE ext_db_key = " + theItem.key);
				}

			} catch (SQLException se) {
				errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
				db.reportError(se);
			}

			try {
				db.update("UNLOCK TABLES");
			} catch (SQLException se) {
				db.reportError(se);
			}

			DbCore.releaseDb(db);

			if (null != errmsg) {
				errorReporter.reportError(errmsg, errtyp);
			}
		}

		updateExtDbList(true);
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doDeleteExtDb() {

		int rowIndex = extDbTable.getSelectedRow();
		if (rowIndex < 0) {
			return;
		}

		errorReporter.setTitle("Delete Station Data");

		ExtDbListItem theItem = checkExtDb(extDbTable.convertRowIndexToModel(rowIndex), errorReporter);
		if (null == theItem) {
			return;
		}

		ExtDb.deleteDatabase(getDbID(), theItem.key.intValue(), errorReporter);

		updateExtDbList(false);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowWillOpen() {

		DbController.restoreColumnWidths(getDbID(), getKeyTitle(), extDbTable);

		blockActionsClear();

		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				errorReporter.setTitle("Load Station Data List");
				extDbModel.setItems(getItems(errorReporter));
			}
		});
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean windowShouldClose() {

		DbController.saveColumnWidths(getDbID(), getKeyTitle(), extDbTable);

		blockActionsSet();
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by the StudyManager if it is trying to close.

	public boolean closeIfPossible() {

		if (windowShouldClose()) {
			AppController.hideWindow(this);
			return true;
		}
		return false;
	}
}
