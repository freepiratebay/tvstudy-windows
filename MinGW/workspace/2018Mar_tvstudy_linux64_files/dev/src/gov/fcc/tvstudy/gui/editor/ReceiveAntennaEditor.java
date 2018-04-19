//
//  ReceiveAntennaEditor.java
//  TVStudy
//
//  Copyright (c) 2016-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui.editor;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.gui.*;

import java.util.*;
import java.util.regex.*;
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
// Editor for the list of receive antennas in a database.  This is managed as a singleton per open database by a study
// manager, which also maintains a master lock to ensure no two receive antenna editors can be open on the same
// database at the same time; see StudyManager, also comments in GeographyEditor.

public class ReceiveAntennaEditor extends AppFrame {

	public static final String WINDOW_TITLE = "Receive Antenna Editor";

	private AntennaListModel antennaModel;
	private JTable antennaTable;

	private JButton duplicateAntennaButton;
	private JButton exportAntennaButton;
	private JButton openAntennaButton;

	private JMenuItem openAntennaMenuItem;
	private JMenuItem duplicateAntennaMenuItem;
	private JMenuItem deleteAntennaMenuItem;
	private JMenuItem exportAntennaMenuItem;

	private boolean ignoreSelectionChange;


	//-----------------------------------------------------------------------------------------------------------------

	public ReceiveAntennaEditor(AppEditor theParent) {

		super(theParent, WINDOW_TITLE);

		// Table for the antenna list.

		antennaModel = new AntennaListModel();
		antennaTable = antennaModel.createTable();

		antennaTable.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
			public void valueChanged(ListSelectionEvent theEvent) {
				if (ignoreSelectionChange) {
					return;
				}
				updateControls();
			}
		});

		antennaTable.addMouseListener(new MouseAdapter() {
			public void mouseClicked(MouseEvent e) {
				if (2 == e.getClickCount()) {
					doOpenAntenna();
				}
			}
		});

		JPanel antennaPanel = new JPanel(new BorderLayout());
		antennaPanel.setBorder(BorderFactory.createTitledBorder("Receive Antennas"));
		antennaPanel.add(AppController.createScrollPane(antennaTable), BorderLayout.CENTER);

		// Buttons.

		JButton newAntennaButton = new JButton("New");
		newAntennaButton.setFocusable(false);
		newAntennaButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doNewAntenna();
			}
		});

		duplicateAntennaButton = new JButton("Duplicate");
		duplicateAntennaButton.setFocusable(false);
		duplicateAntennaButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doDuplicateAntenna();
			}
		});

		JButton importAntennaButton = new JButton("Import");
		importAntennaButton.setFocusable(false);
		importAntennaButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doImportAntenna();
			}
		});

		exportAntennaButton = new JButton("Export");
		exportAntennaButton.setFocusable(false);
		exportAntennaButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doExportAntenna();
			}
		});

		openAntennaButton = new JButton("Open");
		openAntennaButton.setFocusable(false);
		openAntennaButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doOpenAntenna();
			}
		});

		// Do the layout.

		JPanel butL = new JPanel(new FlowLayout(FlowLayout.LEFT));
		butL.add(newAntennaButton);
		butL.add(duplicateAntennaButton);
		butL.add(importAntennaButton);
		butL.add(exportAntennaButton);

		JPanel butR = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		butR.add(openAntennaButton);

		JPanel buttonPanel = new JPanel();
		buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.X_AXIS));
		buttonPanel.add(butL);
		buttonPanel.add(butR);

		Container cp = getContentPane();
		cp.setLayout(new BorderLayout());
		cp.add(antennaPanel, BorderLayout.CENTER);
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

		// New

		JMenuItem miNew = new JMenuItem("New");
		miNew.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, AppController.MENU_SHORTCUT_KEY_MASK));
		miNew.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doNewAntenna();
			}
		});
		fileMenu.add(miNew);

		// Import...

		JMenuItem miImport = new JMenuItem("Import...");
		miImport.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_I, AppController.MENU_SHORTCUT_KEY_MASK));
		miImport.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doImportAntenna();
			}
		});
		fileMenu.add(miImport);

		// Export...

		exportAntennaMenuItem = new JMenuItem("Export...");
		exportAntennaMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E,
			AppController.MENU_SHORTCUT_KEY_MASK));
		exportAntennaMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doExportAntenna();
			}
		});
		fileMenu.add(exportAntennaMenuItem);

		// __________________________________

		fileMenu.addSeparator();

		// Open

		openAntennaMenuItem = new JMenuItem("Open");
		openAntennaMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O,
			AppController.MENU_SHORTCUT_KEY_MASK));
		openAntennaMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doOpenAntenna();
			}
		});
		fileMenu.add(openAntennaMenuItem);

		// Duplicate...

		duplicateAntennaMenuItem = new JMenuItem("Duplicate...");
		duplicateAntennaMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doDuplicateAntenna();
			}
		});
		fileMenu.add(duplicateAntennaMenuItem);

		// Delete

		deleteAntennaMenuItem = new JMenuItem("Delete");
		deleteAntennaMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doDeleteAntenna();
			}
		});
		fileMenu.add(deleteAntennaMenuItem);

		// Initial update of UI state.

		updateControls();

		updateDocumentName();
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected String getFileMenuName() {

		return "Antenna";
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
	// Update controls per table selection.  A new antenna (never saved) can't be duplicated or exported.

	private void updateControls() {

		boolean eOpen = false, eDuplicate = false, eExport = false, eDelete = false;

		int rowIndex = antennaTable.getSelectedRow();
		if (rowIndex >= 0) {
			eOpen = true;
			AntennaListItem theItem = antennaModel.get(antennaTable.convertRowIndexToModel(rowIndex));
			if ((null != theItem.key) && (null == theItem.patternEditor)) {
				eDuplicate = true;
				eExport = true;
			}
			eDelete = true;
		}

		openAntennaButton.setEnabled(eOpen);
		duplicateAntennaButton.setEnabled(eDuplicate);
		exportAntennaButton.setEnabled(eExport);

		openAntennaMenuItem.setEnabled(eOpen);
		duplicateAntennaMenuItem.setEnabled(eDuplicate);
		exportAntennaMenuItem.setEnabled(eExport);
		deleteAntennaMenuItem.setEnabled(eDelete);
	}


	//=================================================================================================================
	// Data class for the antenna list.

	private class AntennaListItem {

		private Integer key;
		private String name;
		private boolean isMatrix;
		private double minFrequency;
		private double maxFrequency;

		private PatternEditor patternEditor;
	}


	//=================================================================================================================

	private class AntennaListModel extends AbstractTableModel {

		private static final String ANTENNA_NAME_COLUMN = "Name";
		private static final String ANTENNA_TYPE_COLUMN = "Type";
		private static final String ANTENNA_MIN_FREQ_COLUMN = "Min Freq.";
		private static final String ANTENNA_MAX_FREQ_COLUMN = "Max Freq.";

		private String[] columnNames = {
			ANTENNA_NAME_COLUMN,
			ANTENNA_TYPE_COLUMN,
			ANTENNA_MIN_FREQ_COLUMN,
			ANTENNA_MAX_FREQ_COLUMN
		};

		private static final int ANTENNA_NAME_INDEX = 0;
		private static final int ANTENNA_TYPE_INDEX = 1;
		private static final int ANTENNA_MIN_FREQ_INDEX = 2;
		private static final int ANTENNA_MAX_FREQ_INDEX = 3;

		private ArrayList<AntennaListItem> modelRows;


		//-------------------------------------------------------------------------------------------------------------

		private AntennaListModel() {

			super();

			modelRows = new ArrayList<AntennaListItem>();
		}


		//-------------------------------------------------------------------------------------------------------------

		private JTable createTable() {

			JTable theTable = new JTable(this);
			AppController.configureTable(theTable);

			TableColumn theColumn = theTable.getColumn(ANTENNA_NAME_COLUMN);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[20]);

			theColumn = theTable.getColumn(ANTENNA_TYPE_COLUMN);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[6]);

			theColumn = theTable.getColumn(ANTENNA_MIN_FREQ_COLUMN);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[6]);

			theColumn = theTable.getColumn(ANTENNA_MAX_FREQ_COLUMN);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[6]);

			return theTable;
		}


		//-------------------------------------------------------------------------------------------------------------
		// This has to merge the existing and new lists, sometimes copying properties, to preserve open editor state.
		// All open editors are editable and so must be assumed to have unsaved edits.

		private void setItems(ArrayList<AntennaListItem> newItems) {

			HashMap<Integer, AntennaListItem> oldItems = new HashMap<Integer, AntennaListItem>();
			ArrayList<AntennaListItem> keepItems = new ArrayList<AntennaListItem>();
			for (AntennaListItem theItem : new ArrayList<AntennaListItem>(modelRows)) {
				if (null != theItem.key) {
					oldItems.put(theItem.key, theItem);
				} else {
					keepItems.add(theItem);
				}
			}

			modelRows.clear();

			if (null != newItems) {
				AntennaListItem theItem;
				for (AntennaListItem newItem : newItems) {
					theItem = oldItems.remove(newItem.key);
					if (null != theItem) {
						theItem.name = newItem.name;
						theItem.isMatrix = newItem.isMatrix;
						theItem.minFrequency = newItem.minFrequency;
						theItem.maxFrequency = newItem.maxFrequency;
						modelRows.add(theItem);
					} else {
						modelRows.add(newItem);
					}
				}
			}

			for (AntennaListItem theItem : oldItems.values()) {
				if (null != theItem.patternEditor) {
					modelRows.add(theItem);
				}
			}

			modelRows.addAll(keepItems);

			fireTableDataChanged();
		}


		//-------------------------------------------------------------------------------------------------------------

		private int add(AntennaListItem newItem) {

			int rowIndex = modelRows.size();
			modelRows.add(newItem);
			fireTableRowsInserted(rowIndex, rowIndex);
			return rowIndex;
		}


		//-------------------------------------------------------------------------------------------------------------

		private AntennaListItem get(int rowIndex) {

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

		private int indexOf(AntennaListItem theItem) {

			return modelRows.indexOf(theItem);
		}


		//-------------------------------------------------------------------------------------------------------------

		private AntennaListItem getItemForEditor(PatternEditor theEditor) {

			for (AntennaListItem theItem : modelRows) {
				if (theEditor == theItem.patternEditor) {
					return theItem;
				}
			}
			return null;
		}


		//-------------------------------------------------------------------------------------------------------------

		private ArrayList<PatternEditor> getEditors() {

			ArrayList<PatternEditor> result = new ArrayList<PatternEditor>();

			for (AntennaListItem theItem : modelRows) {
				if (null != theItem.patternEditor) {
					result.add(theItem.patternEditor);
				}
			}

			return result;
		}


		//-------------------------------------------------------------------------------------------------------------

		private AntennaListItem getItemForName(String theName) {

			for (AntennaListItem theItem : modelRows) {
				if (theItem.name.equalsIgnoreCase(theName)) {
					return theItem;
				}
			}
			return null;
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

			AntennaListItem theItem = modelRows.get(rowIndex);

			switch (columnIndex) {

				case ANTENNA_NAME_INDEX:
					return theItem.name;

				case ANTENNA_TYPE_INDEX:
					return (theItem.isMatrix ? "Matrix" : "Simple");

				case ANTENNA_MIN_FREQ_INDEX:
					return (theItem.isMatrix ? AppCore.formatDecimal(theItem.minFrequency, 2) : "");

				case ANTENNA_MAX_FREQ_INDEX:
					return (theItem.isMatrix ? AppCore.formatDecimal(theItem.maxFrequency, 2) : "");
			}

			return "";
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Retrieve a list of all antennas in the database.

	private ArrayList<AntennaListItem> getItems(ErrorReporter errors) {

		ArrayList<AntennaListItem> result = new ArrayList<AntennaListItem>();

		AntennaListItem theItem;

		DbConnection db = DbCore.connectDb(getDbID(), errors);
		if (null != db) {
			try {

				db.query(
				"SELECT " +
					"receive_antenna_index.antenna_key, " +
					"receive_antenna_index.name, " +
					"MIN(receive_pattern.frequency), " +
					"MAX(receive_pattern.frequency) " +
				"FROM " +
					"receive_antenna_index " +
					"JOIN receive_pattern USING (antenna_key) " +
				"GROUP BY 1,2 " +
				"ORDER BY 1");

				while (db.next()) {

					theItem = new AntennaListItem();

					theItem.key = Integer.valueOf(db.getInt(1));
					theItem.name = db.getString(2);
					theItem.minFrequency = db.getDouble(3);
					theItem.maxFrequency = db.getDouble(4);
					theItem.isMatrix = (theItem.minFrequency != theItem.maxFrequency);

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
	// Move selection up or down in the table.

	private void doPrevious() {

		int size = antennaTable.getRowCount();
		int rowIndex = antennaTable.getSelectedRow();
		if ((size > 0) && (rowIndex != 0)) {
			if (rowIndex < 0) {
				rowIndex = size - 1;
			} else {
				rowIndex--;
			}
			antennaTable.setRowSelectionInterval(rowIndex, rowIndex);
			antennaTable.scrollRectToVisible(antennaTable.getCellRect(rowIndex, 0, true));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doNext() {

		int size = antennaTable.getRowCount();
		int rowIndex = antennaTable.getSelectedRow();
		if ((size > 0) && (rowIndex < (size - 1))) {
			if (rowIndex < 0) {
				rowIndex = 0;
			} else {
				rowIndex++;
			}
			antennaTable.setRowSelectionInterval(rowIndex, rowIndex);
			antennaTable.scrollRectToVisible(antennaTable.getCellRect(rowIndex, 0, true));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Open a pattern editor.

	private void doOpenAntenna() {

		int rowIndex = antennaTable.getSelectedRow();
		if (rowIndex < 0) {
			return;
		}

		String title = "Open Antenna";
		errorReporter.setTitle(title);

		AntennaListItem theItem = antennaModel.get(antennaTable.convertRowIndexToModel(rowIndex));

		if (null != theItem.patternEditor) {
			if (theItem.patternEditor.isVisible()) {
				theItem.patternEditor.toFront();
				return;
			}
			theItem.patternEditor = null;
		}

		AntPattern thePattern = AntPattern.getReceiveAntenna(getDbID(), theItem.key.intValue(), errorReporter);
		if (null == thePattern) {
			return;
		}

		theItem.patternEditor = new PatternEditor(this, thePattern, thePattern.type, true);
		AppController.showWindow(theItem.patternEditor);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Import an antenna pattern from a CSV file.  There is also an import/export within the pattern editor but that
	// reads only a single slice into a matrix pattern and it only accepts relative field.  This will auto-detect a
	// simple vs. matrix pattern and recognize units in dB or relative field.

	private void doImportAntenna() {

		String title = "Import Antenna";
		errorReporter.setTitle(title);

		JFileChooser chooser = new JFileChooser(AppCore.getProperty(AppCore.LAST_FILE_DIRECTORY_KEY));
		chooser.setDialogType(JFileChooser.OPEN_DIALOG);
		chooser.setDialogTitle(title);
		chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		chooser.setMultiSelectionEnabled(false);
		chooser.addChoosableFileFilter(new FileNameExtensionFilter("CSV (*.csv)", "csv"));
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

		BufferedReader csv = new BufferedReader(theReader);

		// This will read in all data, on the first line it determines whether this is a single slice or a matrix by
		// counting the number of comma-separated fields, either 2 or 3.  For a matrix it checks the frequency values
		// for valid range and sequence, matrix or simple it checks pattern azimuths for range and sequence.  However
		// individual pattern values are not error-checked yet, this just keeps track of the minimum and maximum so the
		// units can be auto-detected later and dB converted to relative field if needed.

		ArrayList<AntPattern.AntSlice> theSlices = new ArrayList<AntPattern.AntSlice>();
		ArrayList<AntPattern.AntPoint> thePoints = null;
		boolean isMatrix = false;

		String theLine;
		AppCore.Counter lineNumber = new AppCore.Counter(0);
		int nToken = 0;
		Pattern theParser = Pattern.compile(",");
		String[] tokens;

		double freq = AntPattern.FREQUENCY_MIN, lfreq = AntPattern.FREQUENCY_MIN - 1., azm,
			lazm = AntPattern.AZIMUTH_MIN - 1., rf, patmin = 999., patmax = -999.;

		String errmsg = null;

		try {

			while (true) {

				theLine = AppCore.readLineSkipComments(csv, lineNumber);
				if (null == theLine) {
					break;
				}

				tokens = theParser.split(theLine);
				if (0 == nToken) {
					nToken = tokens.length;
					if ((nToken < 2) || (nToken > 3)) {
						errmsg = "Bad data format";
						break;
					}
					isMatrix = (3 == nToken);
				} else {
					if (tokens.length != nToken) {
						errmsg = "Bad data format";
						break;
					}
				}

				try {
					if (isMatrix) {
						freq = Double.parseDouble(tokens[0]);
						azm = Double.parseDouble(tokens[1]);
						rf = Double.parseDouble(tokens[2]);
					} else {
						azm = Double.parseDouble(tokens[0]);
						rf = Double.parseDouble(tokens[1]);
					}
				} catch (NumberFormatException nfe) {
					errmsg = "Bad data format";
					break;
				}

				if ((freq < AntPattern.FREQUENCY_MIN) || (freq > AntPattern.FREQUENCY_MAX)) {
					errmsg = "Frequency out of range";
					break;
				}
				if (freq < lfreq) {
					errmsg = "Frequencies out of sequence";
					break;
				}
				if (freq > lfreq) {
					AntPattern.AntSlice theSlice = new AntPattern.AntSlice(freq);
					theSlices.add(theSlice);
					thePoints = theSlice.points;
					lfreq = freq;
					lazm = AntPattern.AZIMUTH_MIN - 1.;
				}

				azm = Math.rint(azm * AntPattern.AZIMUTH_ROUND) / AntPattern.AZIMUTH_ROUND;
				if ((azm < AntPattern.AZIMUTH_MIN) || (azm > AntPattern.AZIMUTH_MAX)) {
					errmsg = "Azimuth out of range";
					break;
				}
				if (azm <= lazm) {
					errmsg = "Azimuths duplicated or out of sequence";
					break;
				}
				lazm = azm;

				if (rf < patmin) {
					patmin = rf;
				}
				if (rf > patmax) {
					patmax = rf;
				}

				thePoints.add(new AntPattern.AntPoint(azm, rf));
			}

			if ((null == errmsg) && (null == thePoints)) {
				errmsg = "The file was empty";
			}

		} catch (IOException ie) {
			errmsg = "Could not read from the file:\n" + ie.getMessage();
			lineNumber.reset();
		}

		try {csv.close();} catch (IOException ie) {}

		if (null != errmsg) {
			if (lineNumber.get() > 0) {
				errmsg = errmsg + " at line " + lineNumber;
			}
			errorReporter.reportError(errmsg);
			return;
		}

		// If the min/max range is between 0 and 1 units are relative field, otherwise convert from dB.  Also round
		// the relative field values and snap to the minimum value.  This does not check for a 1.0 maximum, the new
		// pattern will immediately be opened in an editor and the editor will enforce that requirement.

		boolean isDb = ((patmin < 0.) || (patmax > 1.));

		for (AntPattern.AntSlice theSlice : theSlices) {
			for (AntPattern.AntPoint thePoint : theSlice.points) {
				rf = thePoint.relativeField;
				if (isDb) {
					rf = Math.pow(10., ((rf - patmax) / 20.));
				}
				rf = Math.rint(rf * AntPattern.FIELD_ROUND) / AntPattern.FIELD_ROUND;
				if (rf < AntPattern.FIELD_MIN) {
					rf = AntPattern.FIELD_MIN;
				}
				thePoint.relativeField = rf;
			}
		}

		// Finally, build the new pattern object.

		AntPattern newPattern;
		if (isMatrix) {
			newPattern = new AntPattern(getDbID(), "", AntPattern.PATTERN_TYPE_RECEIVE, theSlices);
		} else {
			newPattern = new AntPattern(getDbID(), AntPattern.PATTERN_TYPE_RECEIVE, "", thePoints);
		}
		if (isDb && (patmax > 0.)) {
			newPattern.gain = patmax;
		}

		AntennaListItem newItem = new AntennaListItem();
		newItem.name = "(new)";
		newItem.isMatrix = isMatrix;
		newItem.minFrequency = newPattern.minimumValue();
		newItem.maxFrequency = newPattern.maximumValue();

		int rowIndex = antennaTable.convertRowIndexToView(antennaModel.add(newItem));
		ignoreSelectionChange = true;
		antennaTable.setRowSelectionInterval(rowIndex, rowIndex);
		ignoreSelectionChange = false;
		antennaTable.scrollRectToVisible(antennaTable.getCellRect(rowIndex, 0, true));

		newItem.patternEditor = new PatternEditor(this, newPattern, newPattern.type, true);
		AppController.showWindow(newItem.patternEditor);

		updateControls();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Export the selected antenna to a CSV file.

	private void doExportAntenna() {

		int rowIndex = antennaTable.getSelectedRow();
		if (rowIndex < 0) {
			return;
		}

		String title = "Export Antenna";
		errorReporter.setTitle(title);

		AntennaListItem theItem = antennaModel.get(antennaTable.convertRowIndexToModel(rowIndex));
		if ((null == theItem.key) || (null != theItem.patternEditor)) {
			return;
		}

		AntPattern thePattern = AntPattern.getReceiveAntenna(getDbID(), theItem.key.intValue(), errorReporter);
		if (null == thePattern) {
			return;
		}

		JFileChooser chooser = new JFileChooser(AppCore.getProperty(AppCore.LAST_FILE_DIRECTORY_KEY));
		chooser.setDialogType(JFileChooser.SAVE_DIALOG);
		chooser.setDialogTitle(title);
		chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		chooser.setMultiSelectionEnabled(false);
		chooser.addChoosableFileFilter(new FileNameExtensionFilter("CSV (*.csv)", "csv"));
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
		if (!theName.toLowerCase().endsWith(".csv")) {
			theFile = new File(theFile.getAbsolutePath() + ".csv");
		}

		FileWriter theWriter = null;
		try {
			theWriter = new FileWriter(theFile);
		} catch (IOException ie) {
			errorReporter.reportError("Could not open the file:\n" + ie.getMessage());
			return;
		}

		BufferedWriter csv = new BufferedWriter(theWriter);

		try {
			if (thePattern.isMatrix()) {
				for (AntPattern.AntSlice theSlice : thePattern.getSlices()) {
					for (AntPattern.AntPoint thePoint : theSlice.points) {
						csv.write(String.format(Locale.US, "%.3f,%.3f,%.4f\n", theSlice.value, thePoint.angle,
							thePoint.relativeField));
					}
				}
			} else {
				for (AntPattern.AntPoint thePoint : thePattern.getPoints()) {
					csv.write(String.format(Locale.US, "%.3f,%.4f\n", thePoint.angle, thePoint.relativeField));
				}
			}
		} catch (IOException ie) {
			errorReporter.reportError("Could not write to the file:\n" + ie.getMessage());
		}

		try {csv.close();} catch (IOException ie) {}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create a new antenna with no name and opens an editor.  If the editor is cancelled the new item will be removed.

	private void doNewAntenna() {

		String title = "New Antenna";
		errorReporter.setTitle(title);

		AntPattern newPattern = new AntPattern(getDbID(), AntPattern.PATTERN_TYPE_RECEIVE, "");

		AntennaListItem newItem = new AntennaListItem();
		newItem.name = "(new)";
		newItem.isMatrix = newPattern.isMatrix();
		newItem.minFrequency = newPattern.minimumValue();
		newItem.maxFrequency = newPattern.maximumValue();

		int rowIndex = antennaTable.convertRowIndexToView(antennaModel.add(newItem));
		ignoreSelectionChange = true;
		antennaTable.setRowSelectionInterval(rowIndex, rowIndex);
		ignoreSelectionChange = false;
		antennaTable.scrollRectToVisible(antennaTable.getCellRect(rowIndex, 0, true));

		newItem.patternEditor = new PatternEditor(this, newPattern, newPattern.type, true);
		AppController.showWindow(newItem.patternEditor);

		updateControls();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Duplicate an existing antenna, this copies the pattern, clears the name, and opens an editor.

	private void doDuplicateAntenna() {

		int rowIndex = antennaTable.getSelectedRow();
		if (rowIndex < 0) {
			return;
		}

		String title = "Duplicate Antenna";
		errorReporter.setTitle(title);

		AntennaListItem theItem = antennaModel.get(antennaTable.convertRowIndexToModel(rowIndex));
		if (null == theItem.key) {
			return;
		}

		AntPattern thePattern = AntPattern.getReceiveAntenna(getDbID(), theItem.key.intValue(), errorReporter);
		if (null == thePattern) {
			return;
		}

		AntPattern newPattern = thePattern.copy();
		newPattern.name = "";

		AntennaListItem newItem = new AntennaListItem();
		newItem.name = "(new)";
		newItem.isMatrix = newPattern.isMatrix();
		newItem.minFrequency = newPattern.minimumValue();
		newItem.maxFrequency = newPattern.maximumValue();

		rowIndex = antennaTable.convertRowIndexToView(antennaModel.add(newItem));
		ignoreSelectionChange = true;
		antennaTable.setRowSelectionInterval(rowIndex, rowIndex);
		ignoreSelectionChange = false;
		antennaTable.scrollRectToVisible(antennaTable.getCellRect(rowIndex, 0, true));

		newItem.patternEditor = new PatternEditor(this, newPattern, newPattern.type, true);
		AppController.showWindow(newItem.patternEditor);

		updateControls();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Delete.  If this is a new antenna (import or duplicate), it will always have an open editor; just cancel that,
	// which will remove the item from the list in editorClosing().

	private void doDeleteAntenna() {

		int rowIndex = antennaTable.getSelectedRow();
		if (rowIndex < 0) {
			return;
		}

		String title = "Delete Antenna";
		errorReporter.setTitle(title);

		rowIndex = antennaTable.convertRowIndexToModel(rowIndex);
		AntennaListItem theItem = antennaModel.get(rowIndex);

		if (null == theItem.key) {
			theItem.patternEditor.cancel();
			return;
		}

		// After confirming the delete, check if the geography editor is currently using the geography in as-
		// edited state.  References from saved geographies will be checked in AntPattern.deleteReceiveAntenna().

		AppController.beep();
		int result = JOptionPane.showConfirmDialog(this, "Are you sure you want to delete antenna '" +
			theItem.name + "'?", title, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (JOptionPane.YES_OPTION != result) {
			return;
		}

		if (StudyManager.isReceiveAntennaInUse(getDbID(), theItem.key.intValue())) {
			errorReporter.reportWarning("The antenna is in use and cannot be deleted.");
			return;
		}

		if (!AntPattern.deleteReceiveAntenna(getDbID(), theItem.key.intValue(), errorReporter)) {
			return;
		}

		if ((null != theItem.patternEditor) && !theItem.patternEditor.cancel()) {
			return;
		}

		antennaModel.remove(rowIndex);

		StudyManager.updateReceiveAntennas(getDbID());
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by pattern editors when edit state is to be committed, perform a save of the as-edited state if needed.

	public boolean applyEditsFrom(AppEditor theEditor) {

		if (theEditor instanceof PatternEditor) {

			PatternEditor patEditor = (PatternEditor)theEditor;
			if (patEditor.didEdit()) {

				AntennaListItem theItem = antennaModel.getItemForEditor(patEditor);
				if (null == theItem) {
					return true;
				}

				ErrorReporter theReporter = patEditor.getErrorReporter();

				AntPattern thePattern = patEditor.getPattern();
				AntennaListItem otherItem = antennaModel.getItemForName(thePattern.name);
				if ((null != otherItem) && (otherItem != theItem)) {
					theReporter.reportWarning("That antenna name already exists.");
					return false;
				}

				theReporter.setTitle("Save Antenna");

				if (thePattern.save(theReporter)) {

					theItem.key = thePattern.key;
					theItem.name = thePattern.name;
					theItem.isMatrix = thePattern.isMatrix();
					theItem.minFrequency = thePattern.minimumValue();
					theItem.maxFrequency = thePattern.maximumValue();

					antennaModel.itemWasChanged(antennaModel.indexOf(theItem));

					StudyManager.updateReceiveAntennas(getDbID());

					return true;
				}

				return false;
			}

			return true;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Sent by pattern editors when they close.  If the list item does not have a key this was a new antenna (created
	// by import or duplicate); in that case it is the original action being cancelled, remove it from the list.

	public void editorClosing(AppEditor theEditor) {

		if (theEditor instanceof PatternEditor) {
			AntennaListItem theItem = antennaModel.getItemForEditor((PatternEditor)theEditor);
			if (null != theItem) {
				theItem.patternEditor = null;
				int rowIndex = antennaModel.indexOf(theItem);
				if (null == theItem.key) {
					antennaModel.remove(rowIndex);
				} else {
					antennaModel.itemWasChanged(rowIndex);
					updateControls();
				}
			}
			return;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowWillOpen() {

		DbController.restoreColumnWidths(getDbID(), getKeyTitle(), antennaTable);

		blockActionsClear();

		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				errorReporter.setTitle("Load Antenna List");
				antennaModel.setItems(getItems(errorReporter));
			}
		});
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean windowShouldClose() {

		for (PatternEditor theEditor : antennaModel.getEditors()) {
			if (theEditor.didEdit() || !theEditor.cancel()) {
				AppController.beep();
				theEditor.toFront();
				return false;
			}
		}
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowWillClose() {

		DbController.saveColumnWidths(getDbID(), getKeyTitle(), antennaTable);

		blockActionsSet();
		parent.editorClosing(this);
	}
}
