//
//  TemplateManager.java
//  TVStudy
//
//  Copyright (c) 2015-2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.editdata.*;
import gov.fcc.tvstudy.gui.editor.*;

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


//=====================================================================================================================
// Editor for the list of templates in a database.  Allows templates to be created, imported, exported, and deleted,
// also opens and manages template editors, see TemplateEditor.  This is not a dialog, however it makes no sense to
// have two of these open for the same database so a unique instance is managed by a parent StudyManager window.

public class TemplateManager extends AppFrame {

	public static final String WINDOW_TITLE = "Template Manager";

	private TemplateListModel templateModel;
	private JTable templateTable;

	private HashMap<Integer, TemplateEditor> templateEditors;

	private JButton duplicateTemplateButton;
	private JButton exportTemplateButton;
	private JButton openTemplateButton;

	private JMenuItem openTemplateMenuItem;
	private JMenuItem renameTemplateMenuItem;
	private JMenuItem duplicateTemplateMenuItem;
	private JMenuItem deleteTemplateMenuItem;
	private JMenuItem exportTemplateMenuItem;


	//-----------------------------------------------------------------------------------------------------------------

	public TemplateManager(AppEditor theParent) {

		super(theParent, WINDOW_TITLE);

		// Table for the template list.

		templateModel = new TemplateListModel();
		templateTable = templateModel.createTable();

		templateTable.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
			public void valueChanged(ListSelectionEvent theEvent) {
				updateControls();
			}
		});

		templateTable.addMouseListener(new MouseAdapter() {
			public void mouseClicked(MouseEvent e) {
				if (2 == e.getClickCount()) {
					doOpenTemplate();
				}
			}
		});

		JPanel templatePanel = new JPanel(new BorderLayout());
		templatePanel.setBorder(BorderFactory.createTitledBorder("Templates"));
		templatePanel.add(AppController.createScrollPane(templateTable), BorderLayout.CENTER);

		templateEditors = new HashMap<Integer, TemplateEditor>();

		// Buttons.

		duplicateTemplateButton = new JButton("Duplicate");
		duplicateTemplateButton.setFocusable(false);
		duplicateTemplateButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doDuplicateTemplate();
			}
		});

		JButton importTemplateButton = new JButton("Import");
		importTemplateButton.setFocusable(false);
		importTemplateButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doImportTemplate();
			}
		});

		exportTemplateButton = new JButton("Export");
		exportTemplateButton.setFocusable(false);
		exportTemplateButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doExportTemplate();
			}
		});

		openTemplateButton = new JButton("Open");
		openTemplateButton.setFocusable(false);
		openTemplateButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doOpenTemplate();
			}
		});

		// Do the layout.

		JPanel butL = new JPanel(new FlowLayout(FlowLayout.LEFT));
		butL.add(duplicateTemplateButton);
		butL.add(importTemplateButton);
		butL.add(exportTemplateButton);

		JPanel butR = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		butR.add(openTemplateButton);

		JPanel buttonPanel = new JPanel();
		buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.X_AXIS));
		buttonPanel.add(butL);
		buttonPanel.add(butR);

		Container cp = getContentPane();
		cp.setLayout(new BorderLayout());
		cp.add(templatePanel, BorderLayout.CENTER);
		cp.add(buttonPanel, BorderLayout.SOUTH);

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
				updateTemplateList(true);
			}
		});
		fileMenu.add(miRefresh);

		// __________________________________

		fileMenu.addSeparator();

		// Import...

		JMenuItem miImport = new JMenuItem("Import...");
		miImport.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_I, AppController.MENU_SHORTCUT_KEY_MASK));
		miImport.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doImportTemplate();
			}
		});
		fileMenu.add(miImport);

		// Export...

		exportTemplateMenuItem = new JMenuItem("Export...");
		exportTemplateMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E,
			AppController.MENU_SHORTCUT_KEY_MASK));
		exportTemplateMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doExportTemplate();
			}
		});
		fileMenu.add(exportTemplateMenuItem);

		// __________________________________

		fileMenu.addSeparator();

		// Open

		openTemplateMenuItem = new JMenuItem("Open");
		openTemplateMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O,
			AppController.MENU_SHORTCUT_KEY_MASK));
		openTemplateMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doOpenTemplate();
			}
		});
		fileMenu.add(openTemplateMenuItem);

		// Rename...

		renameTemplateMenuItem = new JMenuItem("Rename...");
		renameTemplateMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doRenameTemplate();
			}
		});
		fileMenu.add(renameTemplateMenuItem);

		// Duplicate...

		duplicateTemplateMenuItem = new JMenuItem("Duplicate...");
		duplicateTemplateMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doDuplicateTemplate();
			}
		});
		fileMenu.add(duplicateTemplateMenuItem);

		// Delete

		deleteTemplateMenuItem = new JMenuItem("Delete");
		deleteTemplateMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doDeleteTemplate();
			}
		});
		fileMenu.add(deleteTemplateMenuItem);

		// Initial update of UI state.

		updateControls();

		updateDocumentName();
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected String getFileMenuName() {

		return "Template";
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
	// Update controls per table selection.  Note locked templates cannot be exported.  Only the database installation
	// or update code can create locked templates which means those are always present in every database installation
	// so there is no reason to export/import those.  Locked templates can be duplicated (the duplicate will not be
	// locked) so if the user really needs data from a locked template in XML form it can be duplicated then exported.
	// Any template can be opened, the editor has view-only mode for locked and locked-in-study templates.

	private void updateControls() {

		int rowIndex = templateTable.getSelectedRow();
		boolean eOpen = false, eRename = false, eDuplicate = false, eDelete = false, eExport = false;

		if (rowIndex >= 0) {

			eOpen = true;
			eDuplicate = true;

			TemplateListItem theItem = templateModel.get(templateTable.convertRowIndexToModel(rowIndex));
			if (!theItem.isPermanent) {
				if (!templateEditors.containsKey(theItem.key)) {
					eRename = true;
				}
				if (!theItem.inUse) {
					eDelete = true;
				}
				eExport = true;
			}
		}

		duplicateTemplateButton.setEnabled(eDuplicate);
		exportTemplateButton.setEnabled(eExport);

		openTemplateButton.setEnabled(eOpen);

		openTemplateMenuItem.setEnabled(eOpen);
		renameTemplateMenuItem.setEnabled(eRename);
		duplicateTemplateMenuItem.setEnabled(eDuplicate);
		deleteTemplateMenuItem.setEnabled(eDelete);
		exportTemplateMenuItem.setEnabled(eExport);
	}


	//=================================================================================================================
	// Data class for the template list.

	private class TemplateListItem {

		private Integer key;
		private String name;
		private boolean isPermanent;
		private boolean isLocked;
		private boolean isLockedInStudy;
		private boolean inUse;
	}


	//=================================================================================================================

	private class TemplateListModel extends AbstractTableModel {

		private static final String TEMPLATE_NAME_COLUMN = "Name";
		private static final String TEMPLATE_LOCKED_COLUMN = "Locked";
		private static final String TEMPLATE_STUDY_LOCKED_COLUMN = "Study Locked";
		private static final String TEMPLATE_IN_USE_COLUMN = "In Use";

		private String[] columnNames = {
			TEMPLATE_NAME_COLUMN,
			TEMPLATE_LOCKED_COLUMN,
			TEMPLATE_STUDY_LOCKED_COLUMN,
			TEMPLATE_IN_USE_COLUMN
		};

		private static final int TEMPLATE_NAME_INDEX = 0;
		private static final int TEMPLATE_LOCKED_INDEX = 1;
		private static final int TEMPLATE_STUDY_LOCKED_INDEX = 2;
		private static final int TEMPLATE_IN_USE_INDEX = 3;

		private ArrayList<TemplateListItem> modelRows;


		//-------------------------------------------------------------------------------------------------------------

		private TemplateListModel() {

			super();

			modelRows = new ArrayList<TemplateListItem>();
		}


		//-------------------------------------------------------------------------------------------------------------

		private JTable createTable() {

			JTable theTable = new JTable(this);
			AppController.configureTable(theTable);

			TableColumn theColumn = theTable.getColumn(TEMPLATE_NAME_COLUMN);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[20]);

			theColumn = theTable.getColumn(TEMPLATE_LOCKED_COLUMN);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[6]);

			theColumn = theTable.getColumn(TEMPLATE_STUDY_LOCKED_COLUMN);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[6]);

			theColumn = theTable.getColumn(TEMPLATE_IN_USE_COLUMN);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[6]);

			return theTable;
		}


		//-------------------------------------------------------------------------------------------------------------

		private void setItems(ArrayList<TemplateListItem> newItems) {

			modelRows.clear();
			modelRows.addAll(newItems);
			fireTableDataChanged();
		}


		//-------------------------------------------------------------------------------------------------------------

		private TemplateListItem get(int rowIndex) {

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

		private int indexOf(TemplateListItem theItem) {

			return modelRows.indexOf(theItem);
		}


		//-------------------------------------------------------------------------------------------------------------

		private int indexOfKey(Integer theKey) {

			int rowIndex = 0;
			for (TemplateListItem theItem : modelRows) {
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

			TemplateListItem theItem = modelRows.get(rowIndex);

			switch (columnIndex) {

				case TEMPLATE_NAME_INDEX:
					return theItem.name;

				case TEMPLATE_LOCKED_INDEX:
					return (theItem.isLocked ? "Yes" : "No");

				case TEMPLATE_STUDY_LOCKED_INDEX:
					return (theItem.isLockedInStudy ? "Yes" : "No");

				case TEMPLATE_IN_USE_INDEX:
					return (theItem.inUse ? "Yes" : "No");
			}

			return "";
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update the template list to current database contents, optionally preserving the table selection.

	private void updateTemplateList(boolean preserveSelection) {

		errorReporter.setTitle("Reload Template List");

		ArrayList<TemplateListItem> list = getItems(errorReporter);
		if (null == list) {
			return;
		}

		TemplateListItem selectedItem = null;
		if (preserveSelection) {
			int rowIndex = templateTable.getSelectedRow();
			if (rowIndex >= 0) {
				selectedItem = templateModel.get(templateTable.convertRowIndexToModel(rowIndex));
			}
		}

		templateModel.setItems(list);

		if (selectedItem != null) {
			int rowIndex = templateModel.indexOf(selectedItem);
			if (rowIndex >= 0) {
				rowIndex = templateTable.convertRowIndexToView(rowIndex);
				templateTable.setRowSelectionInterval(rowIndex, rowIndex);
				templateTable.scrollRectToVisible(templateTable.getCellRect(rowIndex, 0, true));
			}
		}

		updateControls();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Retrieve a list of all templates in the database.

	private ArrayList<TemplateListItem> getItems(ErrorReporter errors) {

		ArrayList<TemplateListItem> result = new ArrayList<TemplateListItem>();

		TemplateListItem theItem;

		DbConnection db = DbCore.connectDb(getDbID(), errors);
		if (null != db) {
			try {

				db.query(
				"SELECT " +
					"template_key, " +
					"name, " +
					"permanent, " +
					"locked, " +
					"locked_in_study, " +
					"(SELECT COUNT(*) FROM study WHERE template_key = template.template_key) " +
				"FROM " +
					"template " +
				"ORDER BY 1");

				while (db.next()) {

					theItem = new TemplateListItem();

					theItem.key = Integer.valueOf(db.getInt(1));
					theItem.name = db.getString(2);
					theItem.isPermanent = db.getBoolean(3);
					theItem.isLocked = db.getBoolean(4);
					theItem.isLockedInStudy = db.getBoolean(5);
					theItem.inUse = (db.getInt(6) > 0);

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
	// Re-query the database record for a template, update list item properties.  Return the list item as updated, or
	// null if the record no longer exists (the item is removed from the list) or an error occurs.

	private TemplateListItem checkTemplate(int rowIndex, ErrorReporter errors) {

		TemplateListItem theItem = templateModel.get(rowIndex);

		boolean error = false, notfound = false;

		DbConnection db = DbCore.connectDb(getDbID(), errors);
		if (null != db) {
			try {

				db.query(
				"SELECT " +
					"name, " +
					"permanent, " +
					"locked, " +
					"locked_in_study, " +
					"(SELECT COUNT(*) FROM study WHERE template_key = template.template_key) " +
				"FROM " +
					"template " +
				"WHERE " +
					"template_key = " + theItem.key);

				if (db.next()) {

					theItem.name = db.getString(1);
					theItem.isPermanent = db.getBoolean(2);
					theItem.isLocked = db.getBoolean(3);
					theItem.isLockedInStudy = db.getBoolean(4);
					theItem.inUse = (db.getInt(5) > 0);

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
				errors.reportError("The template no longer exists.");
			}
			templateModel.remove(rowIndex);
			return null;
		}

		if (error) {
			return null;
		}

		templateModel.itemWasChanged(rowIndex);

		return theItem;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Move selection up or down in the table.

	private void doPrevious() {

		int size = templateTable.getRowCount();
		int rowIndex = templateTable.getSelectedRow();
		if ((size > 0) && (rowIndex != 0)) {
			if (rowIndex < 0) {
				rowIndex = size - 1;
			} else {
				rowIndex--;
			}
			templateTable.setRowSelectionInterval(rowIndex, rowIndex);
			templateTable.scrollRectToVisible(templateTable.getCellRect(rowIndex, 0, true));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doNext() {

		int size = templateTable.getRowCount();
		int rowIndex = templateTable.getSelectedRow();
		if ((size > 0) && (rowIndex < (size - 1))) {
			if (rowIndex < 0) {
				rowIndex = 0;
			} else {
				rowIndex++;
			}
			templateTable.setRowSelectionInterval(rowIndex, rowIndex);
			templateTable.scrollRectToVisible(templateTable.getCellRect(rowIndex, 0, true));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Import a study template from an XML file, the import method in TemplateEditData creates the template directly
	// in the database.  In fact the import code will support more than one template in the same file, but that rarely
	// if ever occurs since the export function below will only export one template.

	private void doImportTemplate() {

		String title = "Import Template";
		errorReporter.setTitle(title);

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

		FileReader theReader = null;
		try {
			theReader = new FileReader(theFile);
		} catch (FileNotFoundException fnfe) {
			errorReporter.reportError("Could not open the file:\n" + fnfe.getMessage());
			return;
		}

		BufferedReader xml = new BufferedReader(theReader);
		Integer newKey = TemplateEditData.readTemplateFromXML(getDbID(), xml, errorReporter);
		try {xml.close();} catch (IOException ie) {}

		if (null == newKey) {
			return;
		}

		updateTemplateList(false);

		int rowIndex = templateModel.indexOfKey(newKey);
		if (rowIndex >= 0) {
			rowIndex = templateTable.convertRowIndexToView(rowIndex);
			templateTable.setRowSelectionInterval(rowIndex, rowIndex);
			templateTable.scrollRectToVisible(templateTable.getCellRect(rowIndex, 0, true));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Export the selected template to an XML file.

	private void doExportTemplate() {

		int rowIndex = templateTable.getSelectedRow();
		if (rowIndex < 0) {
			return;
		}

		String title = "Export Template";
		errorReporter.setTitle(title);

		TemplateListItem theItem = checkTemplate(templateTable.convertRowIndexToModel(rowIndex), errorReporter);
		if (null == theItem) {
			return;
		}
		if (theItem.isPermanent) {
			return;
		}

		JFileChooser chooser = new JFileChooser(AppCore.getProperty(AppCore.LAST_FILE_DIRECTORY_KEY));
		chooser.setDialogType(JFileChooser.SAVE_DIALOG);
		chooser.setDialogTitle(title);
		chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		chooser.setMultiSelectionEnabled(false);
		chooser.addChoosableFileFilter(new FileNameExtensionFilter("XML (*.xml)", "xml"));
		chooser.setAcceptAllFileFilterUsed(false);

		File theFile = null;
		do {
			if (JFileChooser.APPROVE_OPTION != chooser.showDialog(this, "Export")) {
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

		String theName = theFile.getName();
		if (!theName.toLowerCase().endsWith(".xml")) {
			theFile = new File(theFile.getAbsolutePath() + ".xml");
		}

		FileWriter theWriter = null;
		try {
			theWriter = new FileWriter(theFile);
		} catch (IOException ie) {
			errorReporter.reportError("Could not open the file:\n" + ie.getMessage());
			return;
		}

		BufferedWriter xml = new BufferedWriter(theWriter);

		TemplateEditData.writeTemplateToXML(getDbID(), theItem.key.intValue(), xml, errorReporter);

		try {xml.close();} catch (IOException ie) {}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Open a template editor.

	private void doOpenTemplate() {

		int rowIndex = templateTable.getSelectedRow();
		if (rowIndex < 0) {
			return;
		}

		String title = "Open Template";
		errorReporter.setTitle(title);

		TemplateListItem theItem = checkTemplate(templateTable.convertRowIndexToModel(rowIndex), errorReporter);
		if (null == theItem) {
			return;
		}

		TemplateEditor theEditor = templateEditors.get(theItem.key);
		if (null != theEditor) {
			if (theEditor.isVisible()) {
				theEditor.toFront();
				return;
			}
			templateEditors.remove(theItem.key);
		}

		Template theTemplate = Template.getTemplate(getDbID(), theItem.key.intValue(), errorReporter);
		if (null == theTemplate) {
			return;
		}

		try {
			TemplateEditData theTemplateEditData = new TemplateEditData(getDbID(), theTemplate);
			theEditor = new TemplateEditor(this, theTemplateEditData);
		} catch (Throwable t) {
			AppCore.log(AppCore.ERROR_MESSAGE, "Unexpected error", t);
			errorReporter.reportError("Unexpected error:\n" + t);
			return;
		}

		AppController.showWindow(theEditor);
		templateEditors.put(theItem.key, theEditor);

		updateControls();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Rename an existing template, this can of course also be done by editing the template so if an editor is open
	// this operation is disabled.  However this rename will also work on locked templates.  It will not work on
	// permanent templates.  Prompt for a new name, make sure it is unique, then directly modify in the database.  Do
	// a concurrent-safe name check during the save.

	private void doRenameTemplate() {

		int rowIndex = templateTable.getSelectedRow();
		if (rowIndex < 0) {
			return;
		}

		String title = "Rename Template";
		errorReporter.setTitle(title);

		TemplateListItem theItem = checkTemplate(templateTable.convertRowIndexToModel(rowIndex), errorReporter);
		if ((null == theItem) || theItem.isPermanent || templateEditors.containsKey(theItem.key)) {
			return;
		}

		String theName = "";
		int theKey = 0;

		do {
			theName = (String)(JOptionPane.showInputDialog(this, "Enter a new name for the template", title,
				JOptionPane.QUESTION_MESSAGE, null, null, theItem.name));
			if (null == theName) {
				return;
			}
			theName = theName.trim();
			if (theName.length() > 0) {
				if (theName.equals(theItem.name)) {
					return;
				}
				if (theName.equalsIgnoreCase(theItem.name)) {
					break;
				}
				theKey = Template.getTemplateKeyForName(getDbID(), theName, errorReporter);
				if (theKey < 0) {
					return;
				}
				if (theKey > 0) {
					errorReporter.reportWarning("A template with that name already exists.");
					theName = "";
				}
			}
		} while (0 == theName.length());

		DbConnection db = DbCore.connectDb(getDbID(), errorReporter);
		if (null == db) {
			return;
		}

		String errmsg = null;

		try {

			db.update("LOCK TABLES template WRITE");

			if (!theName.equalsIgnoreCase(theItem.name)) {
				db.query("SELECT template_key FROM template WHERE UPPER(name) = '" + db.clean(theName.toUpperCase()) +
					"' AND template_key <> " + theItem.key);
				if (db.next()) {
					errmsg = "Name change was not saved, a template with the new name already exists.";
				}
			}

			if (null == errmsg) {
				db.update("UPDATE template SET name = '" + db.clean(theName) + "' WHERE template_key = " +
					theItem.key);
			}

		} catch (SQLException se) {
			errmsg = "A database error occurred:\n" + se;
			db.reportError(se);
		}

		try {
			db.update("UNLOCK TABLES");
		} catch (SQLException se) {
			db.reportError(se);
		}

		DbCore.releaseDb(db);

		if (null != errmsg) {
			errorReporter.reportError(errmsg);
		}

		updateTemplateList(true);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Duplicate an existing template.  Prompt for a new name, make sure it is unique.

	private void doDuplicateTemplate() {

		int rowIndex = templateTable.getSelectedRow();
		if (rowIndex < 0) {
			return;
		}

		String title = "Duplicate Template";
		errorReporter.setTitle(title);

		TemplateListItem theItem = checkTemplate(templateTable.convertRowIndexToModel(rowIndex), errorReporter);
		if (null == theItem) {
			return;
		}

		String theName = "";
		int theKey = 0;

		do {
			theName = (String)(JOptionPane.showInputDialog(this, "Enter a name for the new template", title,
				JOptionPane.QUESTION_MESSAGE, null, null, theItem.name));
			if (null == theName) {
				return;
			}
			theName = theName.trim();
			if (theName.length() > 0) {
				theKey = Template.getTemplateKeyForName(getDbID(), theName, errorReporter);
				if (theKey < 0) {
					return;
				}
				if (theKey > 0) {
					errorReporter.reportWarning("A template with that name already exists.");
					theName = "";
				}
			}
		} while (0 == theName.length());

		Integer newKey = Template.duplicateTemplate(getDbID(), theItem.key.intValue(), theName, errorReporter);
		if (null == newKey) {
			return;
		}

		updateTemplateList(false);

		rowIndex = templateModel.indexOfKey(newKey);
		if (rowIndex < 0) {
			return;
		}

		rowIndex = templateTable.convertRowIndexToView(rowIndex);
		templateTable.setRowSelectionInterval(rowIndex, rowIndex);
		templateTable.scrollRectToVisible(templateTable.getCellRect(rowIndex, 0, true));
	}


	//-----------------------------------------------------------------------------------------------------------------
	// A template cannot be deleted if it is permanent or if it is still in use by studies.

	private void doDeleteTemplate() {

		int rowIndex = templateTable.getSelectedRow();
		if (rowIndex < 0) {
			return;
		}

		String title = "Delete Template";
		errorReporter.setTitle(title);

		TemplateListItem theItem = checkTemplate(templateTable.convertRowIndexToModel(rowIndex), errorReporter);
		if (null == theItem) {
			return;
		}
		if (theItem.isPermanent) {
			return;
		}

		// Confirm the operation, if an editor window is open, force it closed.

		AppController.beep();
		int result = JOptionPane.showConfirmDialog(this, "Are you sure you want to delete template '" +
			theItem.name + "'?", title, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (JOptionPane.YES_OPTION != result) {
			return;
		}

		TemplateEditor theEditor = templateEditors.get(theItem.key);
		if (null != theEditor) {
			if (theEditor.isVisible() && !theEditor.closeWithoutSave()) {
				errorReporter.reportWarning("Could not close editor window for the template.\n" +
					"Please close the window manually and try again.");
				theEditor.toFront();
				return;
			}
			templateEditors.remove(theItem.key);
		}

		Template.deleteTemplate(getDbID(), theItem.key.intValue(), errorReporter);

		updateTemplateList(false);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by editors after a save or other operation that might have changed the information being displayed.
	// Not bothering to confirm editor identity, this is only a UI state update so harmless in any case.

	public boolean applyEditsFrom(AppEditor theEditor) {

		if (theEditor instanceof TemplateEditor) {
			int rowIndex = templateModel.indexOfKey(Integer.valueOf(((TemplateEditor)theEditor).getTemplateKey()));
			if (rowIndex >= 0) {
				checkTemplate(rowIndex, null);
				updateControls();
			}
			return true;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Sent by template editors when they close.

	public void editorClosing(AppEditor theEditor) {

		if (theEditor instanceof TemplateEditor) {
			templateEditors.remove(Integer.valueOf(((TemplateEditor)theEditor).getTemplateKey()),
				(TemplateEditor)theEditor);
			updateControls();
			return;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowWillOpen() {

		DbController.restoreColumnWidths(getDbID(), getKeyTitle(), templateTable);

		blockActionsClear();

		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				errorReporter.setTitle("Load Template List");
				templateModel.setItems(getItems(errorReporter));
			}
		});
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean windowShouldClose() {

		if (!templateEditors.isEmpty()) {
			AppController.beep();
			templateEditors.values().iterator().next().toFront();
			return false;
		}

		DbController.saveColumnWidths(getDbID(), getKeyTitle(), templateTable);

		blockActionsSet();

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by the parent StudyManager if it is trying to close.

	public boolean closeIfPossible() {

		if (windowShouldClose()) {
			AppController.hideWindow(this);
			return true;
		}
		return false;
	}
}
