//
//  ExtDbManager.java
//  TVStudy
//
//  Copyright (c) 2012-2018 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.editdata.*;
import gov.fcc.tvstudy.gui.editor.*;
import gov.fcc.tvstudy.gui.run.*;

import java.util.*;
import java.sql.*;
import java.io.*;
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

public class ExtDbManager extends AppFrame implements ExtDbListener {

	public static final String WINDOW_TITLE = "Station Data Manager";

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
				ExtDb.reloadCache(getDbID());
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
	// Update the list to current database contents, optionally preserving the table selection.  The ExtDb class is
	// responsible for managing the data set table and a cache of it's contents, so this does not do a direct query.
	// The public version is the ExtDbListener call.

	public void updateExtDbList() {
		updateExtDbList(true);
	}

	private void updateExtDbList(boolean preserveSelection) {

		errorReporter.setTitle("Load Data List");
	
		ExtDbListItem selectedItem = null;
		if (preserveSelection) {
			int rowIndex = extDbTable.getSelectedRow();
			if (rowIndex >= 0) {
				selectedItem = extDbModel.get(extDbTable.convertRowIndexToModel(rowIndex));
			}
		}

		ArrayList<ExtDb> extdbs = ExtDb.getExtDbs(getDbID(), errorReporter);
		if (null == extdbs) {
			return;
		}

		ArrayList<ExtDbListItem> list = new ArrayList<ExtDbListItem>();
		ExtDbListItem theItem;
		for (ExtDb theDb : extdbs) {
			theItem = new ExtDbListItem();
			theItem.key = theDb.key;
			theItem.type = theDb.type;
			theItem.id = theDb.id;
			if (theDb.isDownload) {
				theItem.name = ExtDb.DOWNLOAD_SET_NAME;
			} else {
				theItem.name = theDb.name;
			}
			list.add(theItem);
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
	// Re-query the database record for an entry.  This does directly query the database table in case the cache in
	// ExtDb is out of date.  If a change is detected notify that class to update, however it will call back here to
	// reload the entire list so defer that notification.  Return an updated object if anything changed, the existing
	// object if errors occur, or null if the data set is deleted.

	private ExtDbListItem checkExtDb(int rowIndex, ErrorReporter errors) {

		ExtDbListItem theItem = extDbModel.get(rowIndex);

		boolean error = false, notfound = false, didchange = false;
		int newType;
		String newID, newName;

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

					newType = db.getInt(1);
					newID = db.getString(2);
					if (db.getBoolean(3)) {
						newName = ExtDb.DOWNLOAD_SET_NAME;
					} else {
						newName = db.getString(4);
					}

					if ((newType != theItem.type) || !newID.equals(theItem.id) || !newName.equals(theItem.name)) {
						theItem.type = newType;
						theItem.id = newID;
						theItem.name = newName;
						didchange = true;
					}

				} else {
					notfound = true;
					didchange = true;
				}

				DbCore.releaseDb(db);

			} catch (SQLException se) {
				DbCore.releaseDb(db);
				error = true;
				DbConnection.reportError(errors, se);
			}

		} else {
			return theItem;
		}

		if (error) {
			return theItem;
		}

		if (notfound) {
			if (null != errors) {
				errors.reportError("The station data no longer exists.");
			}
			theItem = null;
		}

		if (didchange) {
			SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					ExtDb.reloadCache(getDbID());
				}
			});
		}

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
	// duplication.  An optional descriptive name may be set for UI labelling.  If that is blank the UI label will be
	// an ID string appropriate to the data.  The import is handled by a background thread task in the run manager.

	private void doCreateAndImport(int theType) {

		String title = "Import " + ExtDb.getTypeName(theType) + " station data";
		errorReporter.setTitle(title);

		JFileChooser chooser = new JFileChooser(AppCore.getProperty(AppCore.LAST_FILE_DIRECTORY_KEY));
		chooser.setDialogType(JFileChooser.OPEN_DIALOG);
		chooser.setMultiSelectionEnabled(false);

		JTextField nameField = new JTextField(12);
		AppController.fixKeyBindings(nameField);
		JPanel namePanel = new JPanel();
		namePanel.setBorder(BorderFactory.createTitledBorder("Data set name (optional)"));
		namePanel.add(nameField);

		chooser.setAccessory(namePanel);

		RunPanelThread thePanel = null;

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

				// If a name is entered, check it for length and use of the reserved character for uniqueness.

				File theFile = null;
				String theName = "";
				while (true) {
					if (JFileChooser.APPROVE_OPTION != chooser.showDialog(this, "Choose")) {
						break;
					}
					theName = nameField.getText().trim();
					if (ExtDb.checkExtDbName(getDbID(), theName, errorReporter)) {
						theFile = chooser.getSelectedFile();
						break;
					}
				};
				if (null == theFile) {
					break;
				}

				AppCore.setProperty(AppCore.LAST_FILE_DIRECTORY_KEY, theFile.getParentFile().getAbsolutePath());

				final int extDbType = theType;
				final File sourceFile = theFile;
				final String extDbName = theName;

				thePanel = new RunPanelThread(title, getDbID()) {
					public Object runActivity(StatusLogger status, ErrorLogger errors) {
						return ExtDb.createNewDatabase(getDbID(), extDbType, sourceFile, extDbName, status, errors);
					}
				};
				thePanel.memoryFraction = 0.;

				break;
			}

			// For wireless station data input, user selects individual station data and pattern files.  Those are
			// translated into SQL dump format and then imported normally, see ExtDb.createNewWirelessDatabase().

			case ExtDb.DB_TYPE_WIRELESS: {

				chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
				chooser.setDialogTitle("Choose wireless base station data file");

				File theFile = null;
				String theName = "";
				while (true) {
					if (JFileChooser.APPROVE_OPTION != chooser.showDialog(this, "Choose")) {
						break;
					}
					theName = nameField.getText().trim();
					if (ExtDb.checkExtDbName(getDbID(), theName, errorReporter)) {
						theFile = chooser.getSelectedFile();
						break;
					}
				};
				if (null == theFile) {
					break;
				}
				final File stationFile = theFile;

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
				final File patternFile = chooser.getSelectedFile();

				AppCore.setProperty(AppCore.LAST_FILE_DIRECTORY_KEY, patternFile.getParentFile().getAbsolutePath());

				final String extDbName = theName;

				thePanel = new RunPanelThread(title, getDbID()) {
					public Object runActivity(StatusLogger status, ErrorLogger errors) {
						return ExtDb.createNewWirelessDatabase(getDbID(), stationFile, patternFile, extDbName, status,
							errors);
					}
				};
				thePanel.memoryFraction = 0.;

				break;
			}

			default: {
				errorReporter.reportError("Unknown or unsupported station data type.");
				break;
			}
		}

		if ((null != thePanel) && thePanel.initialize(errorReporter)) {
			RunManager.addRunPanel(thePanel);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Do a download-and-import of CDBS or LMS data.  Also managed by a RunPanel in the run manager.

	private void doDownloadAndImport(int theType) {

		String title = "Download " + ExtDb.getTypeName(theType) + " station data";
		errorReporter.setTitle(title);

		final int extDbType = theType;

		RunPanelThread thePanel = new RunPanelThread(title, getDbID()) {
			public Object runActivity(StatusLogger status, ErrorLogger errors) {
				return ExtDb.downloadDatabase(getDbID(), extDbType, "", status, errors);
			}
		};
		thePanel.memoryFraction = 0.;

		if (thePanel.initialize(errorReporter)) {
			RunManager.addRunPanel(thePanel);
		}
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
		while (true) {
			theName = (String)(JOptionPane.showInputDialog(this, "Enter a name for the data (optional)", title,
				JOptionPane.QUESTION_MESSAGE, null, null, ""));
			if (null == theName) {
				return;
			}
			theName = theName.trim();
			if (ExtDb.checkExtDbName(getDbID(), theName, errorReporter)) {
				break;
			}
		};

		ExtDb.createNewGenericDatabase(getDbID(), theType, theName, errorReporter);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Import into a generic data set.  Right now all this supports is XML, it will have more parsers soon.  This
	// will eventually scale up to something that can be time-consuming but for now it is not expected to be, so don't
	// use the run manager, just an inline background worker.

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

		PickExtDbDialog promptDialog = new PickExtDbDialog(this, title, null) {
			protected ArrayList<KeyedRecord> getExtDbList(ErrorReporter errors) {
				return ExtDb.getExtDbList(getDbID(), extDb.recordType, false, false, errors);
			}
		};

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
	// name is set, even to an empty name, the download flag is also cleared; see ExtDb.downloadDatabase().  For that
	// reason an empty new name will always be saved even if the old name is already empty.

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
		if (oldName.equals(ExtDb.DOWNLOAD_SET_NAME)) {
			oldName = "";
		}
		while (true) {
			newName = (String)(JOptionPane.showInputDialog(this, "Enter a name for the data (optional)", title,
				JOptionPane.QUESTION_MESSAGE, null, null, oldName));
			if (null == newName) {
				return;
			}
			newName = newName.trim();
			if ((oldName.length() > 0) && newName.equals(oldName)) {
				return;
			}
			if (ExtDb.checkExtDbName(getDbID(), newName, oldName, errorReporter)) {
				break;
			}
		};

		ExtDb.renameDatabase(getDbID(), theItem.key, newName, errorReporter);
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
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowWillOpen() {

		ExtDb.addListener(this);

		DbController.restoreColumnWidths(getDbID(), getKeyTitle(), extDbTable);

		blockActionsClear();

		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				updateExtDbList(false);
			}
		});
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowWillClose() {

		DbController.saveColumnWidths(getDbID(), getKeyTitle(), extDbTable);

		blockActionsSet();
	}
}
