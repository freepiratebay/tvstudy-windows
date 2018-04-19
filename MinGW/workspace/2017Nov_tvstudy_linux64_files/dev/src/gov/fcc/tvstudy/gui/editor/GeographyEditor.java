//
//  GeographyEditor.java
//  TVStudy
//
//  Copyright (c) 2016-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui.editor;

import gov.fcc.tvstudy.gui.*;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.geo.*;

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
import javax.swing.border.*;
import javax.swing.filechooser.*;


//=====================================================================================================================
// Editor UI for geography data sets.  This is a master-detail UI with save automaticaly triggered when selection is
// changed in the master list.  The detail editor is one of a set of panel classes, one for each geography type, see
// GeoEditPanel.  The study manager for each open database manages a singleton instance of this class.  Also the study
// manager keeps a master lock on editable root data, including geographies and receive antennas, and acquires that
// lock prior to opening this editor or ReceiveAntennaEditor.  Thus both can assume that no other active editor can
// exist so fewer concurrency checks/protections are needed.  The two editors may both be open together in this
// application instance, they cooperate with each other to protect as-edited state using isReceiveAntennaInUse() and
// updateReceiveAntennas().  Likewise the geography editor cooperates with open study editors that may have as-edited
// use of geographies.  See StudyManager.

// Geography objects are visible in various scopes including global, study-specific, and study-source-specific.  Study
// and source keys in the index table determine scope.  Global scope shows only objects with both study and source keys
// zero.  Study scope shows study key zero or matching a specified key, and source key zero.  Source scope shows study
// key zero or matching a specified key, and source key zero or matching a specified key.  The visible scope can be
// changed while the editor is showing.  Scope of an object is defined by the current scope when the object is created.
// There are also different geography type modes, see Geography.MODE_* for details.

public class GeographyEditor extends RootEditor {

	public static final String WINDOW_TITLE = "Geography Editor";

	private KeyedRecordMenu geoTypeMenu;
	private int geoType;

	private int studyKey;
	private String studyName;
	private int sourceKey;
	private String sourceName;
	private int typeMode;

	private GeoListModel geoModel;
	private JList<String> geoList;

	private Geography currentGeo;
	private int currentIndex = -1;
	private boolean didEdit;
	private boolean ignoreSelectionChange;

	private JTextField nameField;
	private JPanel namePanel;

	private GeoEditPanel currentPanel;

	private JPanel editorPanel;
	private GeoPlotPanel plotPanel;
	private JTabbedPane tabPane;

	private JButton exportButton;
	private JButton duplicateButton;
	private JButton importDataButton;
	private JButton exportDataButton;
	private JButton saveButton;

	private JComboBox<String> scopeMenu;

	private JMenuItem exportMenuItem;
	private JMenuItem duplicateMenuItem;
	private JMenuItem deleteMenuItem;
	private JMenuItem importDataMenuItem;
	private JMenuItem exportDataMenuItem;
	private JMenuItem saveMenuItem;

	// Key will be selected if possible when window opens, see setScope().

	private int showGeoKey;

	private GeographyEditor outerThis = this;


	//-----------------------------------------------------------------------------------------------------------------

	public GeographyEditor(AppEditor theParent) {

		super(theParent, WINDOW_TITLE);

		studyKey = 0;
		sourceKey = 0;
		typeMode = Geography.MODE_ALL;

		// Set up UI, menu to select geography type being edited, list of geographies of that type.

		geoTypeMenu = new KeyedRecordMenu(Geography.getTypes(typeMode));

		geoTypeMenu.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					doSelectionChange();
					blockActionsEnd();
				}
			}
		});

		geoType = geoTypeMenu.getSelectedKey();

		geoModel = new GeoListModel();
		geoList = geoModel.createList();

		geoList.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
			public void valueChanged(ListSelectionEvent theEvent) {
				if (!theEvent.getValueIsAdjusting()) {
					if (blockActions()) {
						doSelectionChange();
						blockActionsEnd();
					}
				}
			}
		});

		JPanel listPanel = new JPanel(new BorderLayout());
		listPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
		listPanel.add(AppController.createScrollPane(geoList), BorderLayout.CENTER);

		// Current geography name field.

		nameField = new JTextField(30);
		AppController.fixKeyBindings(nameField);

		nameField.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (null == currentGeo) {
					return;
				}
				if (blockActions()) {
					String newName = nameField.getText().trim();
					if ((newName.length() > 0) && !currentGeo.name.equals(newName)) {
						boolean changeOK = false;
						if (currentGeo.name.equalsIgnoreCase(newName)) {
							changeOK = true;
						} else {
							errorReporter.setTitle("Change Geography Name");
							if (0 == Geography.getKeyForName(getDbID(), newName, errorReporter)) {
								changeOK = true;
							} else {
								errorReporter.reportValidationError("A geography with that name already exists.");
							}
						}
						if (changeOK) {
							currentGeo.name = newName;
							setDidEdit();
							geoModel.update(currentIndex);
						}
					}
					blockActionsEnd();
				}
				nameField.setText(currentGeo.name);
			}
		});

		nameField.addFocusListener(new FocusAdapter() {
			public void focusGained(FocusEvent theEvent) {
				Component comp = theEvent.getComponent();
				if (comp instanceof JTextField) {
					setCurrentField((JTextField)comp);
				}
			}
			public void focusLost(FocusEvent theEvent) {
				if (!theEvent.isTemporary()) {
					Component comp = theEvent.getComponent();
					if (comp instanceof JTextField) {
						((JTextField)comp).postActionEvent();
					}
				}
			}
		});

		JPanel nameP = new JPanel();
		nameP.setBorder(BorderFactory.createTitledBorder("Geography Name"));
		nameP.add(nameField);
		namePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		namePanel.add(nameP);

		// Panel to contain the type-specific UI, see inner panel classes below.

		editorPanel = new JPanel();
		editorPanel.setLayout(new BorderLayout());

		// Panel to plot the current geography.

		plotPanel = new GeoPlotPanel();

		// Tab pane to hold editor panel and plot panel.

		tabPane = new JTabbedPane();
		tabPane.addTab("Data", editorPanel);
		tabPane.addTab("Plot", plotPanel);

		// Buttons.

		JButton newGeoButton = new JButton("New");
		newGeoButton.setFocusable(false);
		newGeoButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doCreateNew();
			}
		});

		duplicateButton = new JButton("Duplicate");
		duplicateButton.setFocusable(false);
		duplicateButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doDuplicate();
			}
		});

		JButton importButton = new JButton("Import");
		importButton.setFocusable(false);
		importButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doImport();
			}
		});

		exportButton = new JButton("Export");
		exportButton.setFocusable(false);
		exportButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doExport();
			}
		});

		saveButton = new JButton("Save");
		saveButton.setFocusable(false);
		saveButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doSave();
			}
		});

		importDataButton = new JButton("Import Data");
		importDataButton.setFocusable(false);
		importDataButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doImportData();
			}
		});

		exportDataButton = new JButton("Export Data");
		exportDataButton.setFocusable(false);
		exportDataButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doExportData();
			}
		});

		// Scope menu can be used to broaden scope but not narrow, will be populated when the geography is loaded.

		scopeMenu = new JComboBox<String>();
		scopeMenu.setFocusable(false);
		scopeMenu.setPrototypeDisplayValue("XyXyXyXyXy");

		scopeMenu.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					if (null != currentGeo) {
						switch (scopeMenu.getSelectedIndex()) {
							case 0: {
								currentGeo.studyKey = 0;
								currentGeo.sourceKey = 0;
								break;
							}
							case 1: {
								currentGeo.studyKey = studyKey;
								currentGeo.sourceKey = 0;
								break;
							}
							case 2: {
								currentGeo.studyKey = studyKey;
								currentGeo.sourceKey = sourceKey;
								break;
							}
						}
						setDidEdit();
					}
					blockActionsEnd();
				}
			}
		});


		// Do the layout.

		JPanel topP = new JPanel(new FlowLayout(FlowLayout.LEFT));
		topP.add(geoTypeMenu);

		JPanel butP1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
		butP1.add(newGeoButton);
		butP1.add(importButton);

		JPanel butP2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
		butP2.add(duplicateButton);
		butP2.add(exportButton);

		Box butB = Box.createVerticalBox();
		butB.add(butP1);
		butB.add(butP2);

		JPanel leftPanel = new JPanel(new BorderLayout());
		leftPanel.add(topP, BorderLayout.NORTH);
		leftPanel.add(listPanel, BorderLayout.CENTER);
		leftPanel.add(butB, BorderLayout.SOUTH);

		JPanel butL = new JPanel(new FlowLayout(FlowLayout.LEFT));
		butL.add(importDataButton);
		butL.add(exportDataButton);

		JPanel butM = new JPanel();
		butM.add(new JLabel("Scope:"));
		butM.add(scopeMenu);

		JPanel butR = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		butR.add(saveButton);

		butB = Box.createHorizontalBox();
		butB.add(butL);
		butB.add(butM);
		butB.add(butR);

		JPanel rightPanel = new JPanel(new BorderLayout());
		rightPanel.add(tabPane, BorderLayout.CENTER);
		rightPanel.add(butB, BorderLayout.SOUTH);

		Container cp = getContentPane();
		cp.setLayout(new BorderLayout());
		cp.add(leftPanel, BorderLayout.WEST);
		cp.add(rightPanel, BorderLayout.CENTER);

		pack();

		Dimension theSize = new Dimension(1070, 600);
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
				doCreateNew();
			}
		});
		fileMenu.add(miNew);

		// Import...

		JMenuItem importMenuItem = new JMenuItem("Import...");
		importMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_I, AppController.MENU_SHORTCUT_KEY_MASK));
		importMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doImport();
			}
		});
		fileMenu.add(importMenuItem);

		// __________________________________

		fileMenu.addSeparator();

		// Duplicate

		duplicateMenuItem = new JMenuItem("Duplicate");
		duplicateMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doDuplicate();
			}
		});
		fileMenu.add(duplicateMenuItem);

		// Export...

		exportMenuItem = new JMenuItem("Export...");
		exportMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E, AppController.MENU_SHORTCUT_KEY_MASK));
		exportMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doExport();
			}
		});
		fileMenu.add(exportMenuItem);

		// Delete

		deleteMenuItem = new JMenuItem("Delete");
		deleteMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doDelete();
			}
		});
		fileMenu.add(deleteMenuItem);

		// __________________________________

		fileMenu.addSeparator();

		// Import Data...

		importDataMenuItem = new JMenuItem("Import Data...");
		importDataMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doImportData();
			}
		});
		fileMenu.add(importDataMenuItem);

		// Export Data...

		exportDataMenuItem = new JMenuItem("Export Data...");
		exportDataMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doExportData();
			}
		});
		fileMenu.add(exportDataMenuItem);

		// __________________________________

		fileMenu.addSeparator();

		// Save

		saveMenuItem = new JMenuItem("Save");
		saveMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, AppController.MENU_SHORTCUT_KEY_MASK));
		saveMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doSave();
			}
		});
		fileMenu.add(saveMenuItem);

		updateDocumentName();

		updateControls();
	}


	//=================================================================================================================
	// List model for geographies.

	private class GeoListModel extends AbstractListModel<String> {

		private ArrayList<Geography> modelRows;


		//-------------------------------------------------------------------------------------------------------------

		private GeoListModel() {

			modelRows = new ArrayList<Geography>();
		}


		//-------------------------------------------------------------------------------------------------------------

		private JList<String> createList() {

			JList<String> theList = new JList<String>(this);
			theList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

			return theList;
		}


		//-------------------------------------------------------------------------------------------------------------

		private void setRows(ArrayList<Geography> theRows) {

			if (!modelRows.isEmpty()) {
				int lastIndex = modelRows.size() - 1;
				modelRows.clear();
				fireIntervalRemoved(this, 0, lastIndex);
			}

			if ((null != theRows) && !theRows.isEmpty()) {
				modelRows.addAll(theRows);
				fireIntervalAdded(this, 0, (modelRows.size() - 1));
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		private void set(int rowIndex, Geography theGeo) {

			modelRows.set(rowIndex, theGeo);
			fireContentsChanged(this, rowIndex, rowIndex);
		}


		//-------------------------------------------------------------------------------------------------------------

		private int add(Geography theGeo) {

			modelRows.add(0, theGeo);
			fireIntervalAdded(this, 0, 0);

			return 0;
		}


		//-------------------------------------------------------------------------------------------------------------

		private Geography get(int rowIndex) {

			return modelRows.get(rowIndex);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void remove(int rowIndex) {

			modelRows.remove(rowIndex);
			fireIntervalRemoved(this, rowIndex, rowIndex);
		}


		//-------------------------------------------------------------------------------------------------------------

		private int indexOf(Geography theGeo) {

			return modelRows.indexOf(theGeo);
		}


		//-------------------------------------------------------------------------------------------------------------

		private int indexOfKey(int theGeoKey) {

			Geography theGeo;
			for (int i = 0; i < modelRows.size(); i++) {
				theGeo = modelRows.get(i);
				if ((null != theGeo.key) && (theGeoKey == theGeo.key.intValue())) {
					return i;
				}
			}
			return -1;
		}


		//-------------------------------------------------------------------------------------------------------------

		private void update(int rowIndex) {

			fireContentsChanged(this, rowIndex, rowIndex);
		}


		//-------------------------------------------------------------------------------------------------------------

		public int getSize() {

			return modelRows.size();
		}


		//-------------------------------------------------------------------------------------------------------------

		public String getElementAt(int rowIndex) {

			String str = modelRows.get(rowIndex).name;
			if (null == str) {
				str = "";
			}
			str = str.trim();
			if (0 == str.length()) {
				str = "(new)";
			}
			return str;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected String getFileMenuName() {

		return "Geography";
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected boolean showsEditMenu() {

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void updateDocumentName() {

		String theName = DbCore.getHostDbName(getDbID());
		if (0 != studyKey) {
			theName = theName + "/" + studyName;
			if (0 != sourceKey) {
				theName = theName + "/" + sourceName;
			}
		}
		setDocumentName(theName);
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void updateControls() {

		boolean enable = (null != currentGeo);

		boolean enableDuplicate = (enable && (null != currentGeo.key));
		duplicateButton.setEnabled(enableDuplicate);
		duplicateMenuItem.setEnabled(enableDuplicate);

		exportButton.setEnabled(enable);
		exportMenuItem.setEnabled(enable);

		boolean enableImExData = (enable && ((Geography.GEO_TYPE_POINT_SET == geoType) ||
			(Geography.GEO_TYPE_SECTORS == geoType) || (Geography.GEO_TYPE_POLYGON == geoType)));
		importDataButton.setEnabled(enableImExData);
		importDataMenuItem.setEnabled(enableImExData);
		exportDataButton.setEnabled(enableImExData);
		exportDataMenuItem.setEnabled(enableImExData);

		deleteMenuItem.setEnabled(enable);

		scopeMenu.setEnabled(enable);

		saveButton.setEnabled(enable && didEdit);
		saveMenuItem.setEnabled(enable && didEdit);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Determine if a receive antenna is in use by as-edited state, forward to panel if a point set is being edited.

	public boolean isReceiveAntennaInUse(int theAntKey) {

		if ((null != currentPanel) && (currentPanel instanceof GeoEditPanel.PointSetPanel)) {
			return ((GeoEditPanel.PointSetPanel)currentPanel).isReceiveAntennaInUse(theAntKey);
		}
		return false;
	};


	//-----------------------------------------------------------------------------------------------------------------
	// Update receive antenna list, forward to panel if a point set is being edited.

	public void updateReceiveAntennas() {

		if ((null != currentPanel) && (currentPanel instanceof GeoEditPanel.PointSetPanel)) {
			((GeoEditPanel.PointSetPanel)currentPanel).updateReceiveAntennas();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set the scope of geography objects that may be edited.  If the scope is actually changing, save the current
	// object as needed; it's possible that object would still be in scope after the change but it's not worth the
	// trouble to try to figure that out.  Return is true if the change is successful, false if a save was needed but
	// failed or was cancelled by the user in which case the scope is unchanged.  This may be called when the editor
	// is not visible, in which case the object list is not updated now, that happens in windowWillOpen().  If a
	// geography key is provided, try to select that if possible regardless of whether scope is changing.

	public boolean setScope(int theStudyKey, String theStudyName, int theSourceKey, String theSourceName,
			int theMode, int theGeoKey) {

		if ((theStudyKey == studyKey) && (theSourceKey == sourceKey) && (theMode == typeMode) && ((0 == theGeoKey) ||
				((null != currentGeo) && (null != currentGeo.key) && (theGeoKey == currentGeo.key.intValue())))) {
			studyName = theStudyName;
			sourceName = theSourceName;
			updateDocumentName();
			return true;
		}

		ignoreSelectionChange = true;

		if (null != currentGeo) {
			if (saveIfNeeded("Save Geography", true)) {
				if (null == currentGeo.key) {
					geoModel.remove(currentIndex);
				}
				clearCurrentGeo();
			} else {
				geoTypeMenu.setSelectedKey(geoType);
				geoList.scrollRectToVisible(geoList.getCellBounds(currentIndex, currentIndex));
				geoList.setSelectedIndex(currentIndex);
				ignoreSelectionChange = false;
				return false;
			}
		}

		studyKey = theStudyKey;
		studyName = theStudyName;
		sourceKey = theSourceKey;
		sourceName = theSourceName;
		typeMode = theMode;
		updateDocumentName();

		// If an object needs to be selected, start by selecting the type; if the key is undefined clear it.  The
		// object will be found and selected when the list is loaded below or when the window becomes visible.

		if (theGeoKey > 0) {
			int theType = Geography.getTypeForKey(getDbID(), theGeoKey);
			if (theType > 0) {
				geoType = theType;
			} else {
				theGeoKey = 0;
			}
		}

		geoTypeMenu.removeAllItems();
		geoTypeMenu.addAllItems(Geography.getTypes(typeMode));
		if (geoTypeMenu.containsKey(geoType)) {
			geoTypeMenu.setSelectedKey(geoType);
		} else {
			geoType = geoTypeMenu.getSelectedKey();
		}

		if (isVisible()) {
			geoModel.setRows(Geography.getGeographies(getDbID(), geoType, studyKey, sourceKey, errorReporter));
			if (theGeoKey > 0) {
				int rowIndex = geoModel.indexOfKey(theGeoKey);
				if (rowIndex >= 0) {
					geoList.scrollRectToVisible(geoList.getCellBounds(rowIndex, rowIndex));
					geoList.setSelectedIndex(rowIndex);
					loadCurrentGeo();
					tabPane.setSelectedIndex(0);
				} else {
					updateControls();
				}
			} else {
				updateControls();
			}
		} else {
			showGeoKey = theGeoKey;
		}

		ignoreSelectionChange = false;
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called to update names associated with scope keys, ignore if no longer visible or scope has changed.

	public void updateScope(int theStudyKey, String theStudyName, int theSourceKey, String theSourceName) {

		if (isVisible() && (theStudyKey == studyKey)) {
			studyName = theStudyName;
			if (theSourceKey == sourceKey) {
				sourceName = theSourceName;
			}
			updateDocumentName();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called when an editor that may have opened this one with a specified scope wants to close, if this is still in
	// the same scope attempt to close this editor first and return success.  If false is returned the calling editor
	// should not close.  If scope does not match just return true.

	public boolean scopeShouldClose(int theStudyKey, int theSourceKey) {

		if ((theStudyKey == studyKey) && (theSourceKey == sourceKey)) {
			return closeIfPossible();
		}
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doSelectionChange() {

		if (ignoreSelectionChange) {
			return;
		}
		ignoreSelectionChange = true;

		if (null != currentGeo) {
			if (saveIfNeeded("Save Geography", true)) {
				if (null == currentGeo.key) {
					geoModel.remove(currentIndex);
				}
				clearCurrentGeo();
			} else {
				geoTypeMenu.setSelectedKey(geoType);
				geoList.scrollRectToVisible(geoList.getCellBounds(currentIndex, currentIndex));
				geoList.setSelectedIndex(currentIndex);
				ignoreSelectionChange = false;
				return;
			}
		}

		int newType = geoTypeMenu.getSelectedKey();

		if (newType != geoType) {
			geoType = newType;
			geoModel.setRows(Geography.getGeographies(getDbID(), geoType, studyKey, sourceKey, errorReporter));
			updateControls();
		} else {
			loadCurrentGeo();
		}

		ignoreSelectionChange = false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void clearCurrentGeo() {

		currentGeo = null;
		currentIndex = -1;
		didEdit = false;

		currentPanel = null;
		editorPanel.removeAll();
		editorPanel.repaint();
		plotPanel.setGeography(currentGeo);

		updateControls();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Load the selected geography into editor state.  Any previous current state must be cleared before calling this.

	private void loadCurrentGeo() {

		if (null != currentGeo) {
			return;
		}

		int rowIndex = geoList.getSelectedIndex();
		if (rowIndex < 0) {
			return;
		}

		errorReporter.setTitle("Load Geography Data");

		currentIndex = rowIndex;
		currentGeo = geoModel.get(currentIndex);

		if ((null != currentGeo.key) && !currentGeo.loadData(errorReporter)) {
			setDidEdit();
		}

		try {

			GeoEditPanel newPanel;

			switch (currentGeo.type) {

				case Geography.GEO_TYPE_POINT_SET:
					newPanel = new GeoEditPanel.PointSetPanel(outerThis, currentGeo, true);
					break;

				case Geography.GEO_TYPE_BOX:
					newPanel = new GeoEditPanel.BoxPanel(outerThis, currentGeo, true);
					break;

				case Geography.GEO_TYPE_CIRCLE:
					newPanel = new GeoEditPanel.CirclePanel(outerThis, currentGeo, true);
					break;

				case Geography.GEO_TYPE_SECTORS:
					newPanel = new GeoEditPanel.SectorsPanel(outerThis, currentGeo, true);
					break;

				case Geography.GEO_TYPE_POLYGON:
					newPanel = new GeoEditPanel.PolygonPanel(outerThis, currentGeo, true);
					break;

				default:
					throw new Exception("Unknown geography type.");
			}

			editorPanel.add(namePanel, BorderLayout.NORTH);
			nameField.setText(currentGeo.name);

			editorPanel.add(newPanel, BorderLayout.CENTER);

			currentPanel = newPanel;

			currentPanel.revalidate();
			editorPanel.repaint();

			updateControls();

			blockActionsStart();
			scopeMenu.removeAllItems();
			scopeMenu.addItem("Global");
			int select = 0;
			if (currentGeo.studyKey > 0) {
				scopeMenu.addItem("Study");
				select = 1;
				if (currentGeo.sourceKey > 0) {
					scopeMenu.addItem("Record");
					select = 2;
				}
			}
			scopeMenu.setSelectedIndex(select);
			blockActionsEnd();

			if (null == currentGeo.key) {
				setDidEdit();
			} else {
				plotPanel.setGeography(currentGeo);
			}

		} catch (Throwable t) {
			AppCore.log(AppCore.ERROR_MESSAGE, "Unexpected error", t);
			errorReporter.reportError("Unexpected error: " + t);
			setDidEdit();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void setDidEdit() {

		if (null == currentGeo) {
			return;
		}

		didEdit = true;

		saveButton.setEnabled(true);
		saveMenuItem.setEnabled(true);

		plotPanel.setGeography(currentGeo);
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected boolean isDataValid(String title) {

		if (!super.isDataValid(title)) {
			return false;
		}

		if (null == currentGeo) {
			return true;
		}

		if (!commitCurrentField() || !currentPanel.commitTableEdits()) {
			return false;
		}

		errorReporter.setTitle(title);

		if (!currentGeo.isDataValid(errorReporter)) {
			return false;
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected boolean isDataChanged() {

		if (null == currentGeo) {
			return false;
		}

		return didEdit;
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected boolean save(String title) {

		if (!super.save(title)) {
			return false;
		}

		if (null == currentGeo) {
			return true;
		}

		errorReporter.setTitle(title);

		boolean result = currentGeo.save(errorReporter);

		if (result) {
			didEdit = false;
			updateControls();
			StudyManager.updateGeographies(getDbID());
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doPrevious() {

		int size = geoModel.getSize();
		int rowIndex = geoList.getSelectedIndex();
		if ((size > 0) && (rowIndex != 0)) {
			if (rowIndex < 0) {
				rowIndex = size - 1;
			} else {
				rowIndex--;
			}
			geoList.scrollRectToVisible(geoList.getCellBounds(rowIndex, rowIndex));
			geoList.setSelectedIndex(rowIndex);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doNext() {

		int size = geoModel.getSize();
		int rowIndex = geoList.getSelectedIndex();
		if ((size > 0) && (rowIndex < (size - 1))) {
			if (rowIndex < 0) {
				rowIndex = 0;
			} else {
				rowIndex++;
			}
			geoList.scrollRectToVisible(geoList.getCellBounds(rowIndex, rowIndex));
			geoList.setSelectedIndex(rowIndex);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The save may change the name due to a final concurrent-safe uniqueness check, so update just in case.

	private void doSave() {

		if (null == currentGeo) {
			return;
		}

		if (saveIfNeeded("Save Geography", false)) {
			nameField.setText(currentGeo.name);
			geoModel.update(currentIndex);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doCreateNew() {

		Geography newGeo = null;

		switch (geoType) {
			case Geography.GEO_TYPE_POINT_SET:
				newGeo = new GeoPointSet(getDbID());
				break;
			case Geography.GEO_TYPE_BOX:
				newGeo = new GeoBox(getDbID());
				break;
			case Geography.GEO_TYPE_CIRCLE:
				newGeo = new GeoCircle(getDbID());
				break;
			case Geography.GEO_TYPE_SECTORS:
				newGeo = new GeoSectors(getDbID());
				break;
			case Geography.GEO_TYPE_POLYGON:
				newGeo = new GeoPolygon(getDbID());
				break;
			default:
				return;
		}

		newGeo.studyKey = studyKey;
		newGeo.sourceKey = sourceKey;

		addNewGeography(newGeo);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Import a geography from XML.

	private void doImport() {

		String title = "Import Geography";
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

		// Open the file, do the import.

		FileReader theReader = null;
		try {
			theReader = new FileReader(theFile);
		} catch (FileNotFoundException fnfe) {
			errorReporter.reportError("Could not open the file:\n" + fnfe.getMessage());
			return;
		}

		final BufferedReader xml = new BufferedReader(theReader);

		BackgroundWorker<Geography> theWorker = new BackgroundWorker<Geography>(this, title) {
			protected Geography doBackgroundWork(ErrorLogger errors) {
				return Geography.readGeographyFromXML(getDbID(), xml, errors);
			}
		};

		errorReporter.clearMessages();

		Geography newGeo = theWorker.runWork("Importing geography, please wait...", errorReporter);

		try {xml.close();} catch (IOException ie) {}

		if (null == newGeo) {
			return;
		}

		errorReporter.showMessages();

		newGeo.studyKey = studyKey;
		newGeo.sourceKey = sourceKey;

		addNewGeography(newGeo);
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doDuplicate() {

		if ((null == currentGeo) || (null == currentGeo.key)) {
			return;
		}

		addNewGeography(currentGeo.duplicate());
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Shared by create, import, and duplicate.

	private void addNewGeography(Geography newGeo) {

		ignoreSelectionChange = true;

		if (null != currentGeo) {

			if (saveIfNeeded("Save Geography", true)) {

				if (null == currentGeo.key) {
					geoModel.remove(currentIndex);
				}

				clearCurrentGeo();

			} else {

				geoList.scrollRectToVisible(geoList.getCellBounds(currentIndex, currentIndex));
				geoList.setSelectedIndex(currentIndex);

				ignoreSelectionChange = false;

				return;
			}
		}

		int rowIndex = geoModel.add(newGeo);
		geoList.scrollRectToVisible(geoList.getCellBounds(rowIndex, rowIndex));
		geoList.setSelectedIndex(rowIndex);

		loadCurrentGeo();

		tabPane.setSelectedIndex(0);

		ignoreSelectionChange = false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Export a geography to XML.

	private void doExport() {

		if (null == currentGeo) {
			return;
		}

		String title = "Export " + Geography.getTypeName(currentGeo.type);
		errorReporter.setTitle(title);
		if (!currentGeo.isDataValid(errorReporter)) {
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

		String theName = theFile.getName().toLowerCase();
		if (!theName.endsWith(".xml")) {
			theFile = new File(theFile.getAbsolutePath() + ".xml");
		}

		BufferedWriter theWriter = null;
		try {
			theWriter = new BufferedWriter(new FileWriter(theFile));
		} catch (IOException ie) {
			errorReporter.reportError("Could not open the file:\n" + ie.getMessage());
			return;
		}

		final BufferedWriter xml = new BufferedWriter(theWriter);
		BackgroundWorker<Object> theWorker = new BackgroundWorker<Object>(this, title) {
			protected Object doBackgroundWork(ErrorLogger errors) {
				currentGeo.writeToXML(xml, errors);
				return null;
			}
		};

		theWorker.runWork("Exporting geography, please wait...", errorReporter);

		try {xml.close();} catch (IOException ie) {}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Delete.  Check if any open study editors are using the geography.  References from saved study data will be
	// checked in Geography.deleteGeography().  Also confirm the delete.

	private void doDelete() {

		if (null == currentGeo) {
			return;
		}

		String title = "Delete Geography";
		errorReporter.setTitle(title);

		// Confirm the operation.

		AppController.beep();
		int result = JOptionPane.showConfirmDialog(this, "Are you sure you want to delete geography '" +
			currentGeo.name + "'?", title, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (JOptionPane.YES_OPTION != result) {
			return;
		}

		if ((null != currentGeo.key) && StudyManager.isGeographyInUse(getDbID(), currentGeo.key.intValue())) {
			errorReporter.reportWarning("The geography is in use and cannot be deleted.");
			return;
		}

		if ((null != currentGeo.key) && !Geography.deleteGeography(getDbID(), currentGeo.key.intValue(),
				errorReporter)) {
			return;
		}

		ignoreSelectionChange = true;

		int rowIndex = currentIndex;

		clearCurrentGeo();

		if (rowIndex >= 0) {
			geoModel.remove(rowIndex);
			if (rowIndex >= geoModel.getSize()) {
				rowIndex--;
			}
		} else {
			rowIndex = 0;
		}

		if ((rowIndex >= 0) && (rowIndex < geoModel.getSize())) {

			geoList.scrollRectToVisible(geoList.getCellBounds(rowIndex, rowIndex));
			geoList.setSelectedIndex(rowIndex);

			loadCurrentGeo();
		}

		ignoreSelectionChange = false;

		StudyManager.updateGeographies(getDbID());
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Import data for a point set, sectors, or polygon.  All three have a CSV text format, import from ESRI shapefile
	// format is also supported for polygon.  For a point set the imported points are appended to the existing data,
	// for sectors or polygon the imported data replaces existing.

	private void doImportData() {

		if ((null == currentGeo) || ((Geography.GEO_TYPE_POINT_SET != geoType) &&
				(Geography.GEO_TYPE_SECTORS != geoType) && (Geography.GEO_TYPE_POLYGON != geoType))) {
			return;
		}

		String title = "Import " + Geography.getTypeName(geoType) + " Data";
		errorReporter.setTitle(title);

		JFileChooser chooser = new JFileChooser(AppCore.getProperty(AppCore.LAST_FILE_DIRECTORY_KEY));
		chooser.setDialogType(JFileChooser.OPEN_DIALOG);
		chooser.setDialogTitle(title);
		chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		chooser.setMultiSelectionEnabled(false);
		switch (geoType) {
			case Geography.GEO_TYPE_POINT_SET: {
				chooser.addChoosableFileFilter(new FileNameExtensionFilter("Points (*.csv)", "csv"));
				break;
			}
			case Geography.GEO_TYPE_SECTORS: {
				chooser.addChoosableFileFilter(new FileNameExtensionFilter("Sectors (*.csv)", "csv"));
				break;
			}
			case Geography.GEO_TYPE_POLYGON: {
				chooser.addChoosableFileFilter(new FileNameExtensionFilter("Polygon (*.csv, *.shp)", "csv", "shp"));
				break;
			}
		}
		chooser.setAcceptAllFileFilterUsed(false);

		if (JFileChooser.APPROVE_OPTION != chooser.showDialog(this, "Import")) {
			return;
		}
		File theFile = chooser.getSelectedFile();

		AppCore.setProperty(AppCore.LAST_FILE_DIRECTORY_KEY, theFile.getParentFile().getAbsolutePath());

		// Convenient to have the parsing method return a temporary geography object to pass back data.

		Geography newGeo = null;
		if (theFile.getName().toLowerCase().endsWith("shp") && (Geography.GEO_TYPE_POLYGON == geoType)) {
			newGeo = importShapefile(title, theFile);
		} else {
			newGeo = importCSV(title, theFile);
		}

		if (null != newGeo) {

			switch (geoType) {

				case Geography.GEO_TYPE_POINT_SET: {

					for (GeoPointSet.StudyPoint thePoint : ((GeoPointSet)newGeo).points) {
						((GeoPointSet)currentGeo).points.add(thePoint);
					}

					currentPanel.dataChanged();
					setDidEdit();

					break;
				}

				case Geography.GEO_TYPE_SECTORS: {

					((GeoSectors)currentGeo).sectors = ((GeoSectors)newGeo).sectors;

					currentPanel.dataChanged();
					setDidEdit();

					break;
				}

				case Geography.GEO_TYPE_POLYGON: {

					((GeoPolygon)currentGeo).points = ((GeoPolygon)newGeo).points;
					((GeoPolygon)currentGeo).reference.setLatLon(((GeoPolygon)newGeo).reference);

					currentPanel.dataChanged();
					setDidEdit();

					break;
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	private Geography importCSV(String title, File theFile) {

		BufferedReader theReader = null;
		try {
			theReader = new BufferedReader(new FileReader(theFile));
		} catch (IOException ie) {
			errorReporter.reportError("Could not open the file:\n" + ie.getMessage());
			return null;
		}

		Geography newGeo = null;

		String fileName = theFile.getName(), errmsg = null, line;
		AppCore.Counter lineCount = new AppCore.Counter(0);
		String[] fields;

		try {

			switch (geoType) {

				case Geography.GEO_TYPE_SECTORS: {

					GeoSectors theGeo = new GeoSectors(getDbID());
					newGeo = theGeo;
					newGeo.studyKey = studyKey;
					newGeo.sourceKey = sourceKey;

					while (true) {

						line = AppCore.readLineSkipComments(theReader, lineCount);
						if (null == line) {
							break;
						}
						if (0 == line.trim().length()) {
							continue;
						}

						// Sectors is a one-line format, also used to serialize in other contexts, see GeoSectors.

						errmsg = theGeo.decodeFromString(line);

						break;
					}

					break;
				}

				case Geography.GEO_TYPE_POINT_SET: {

					GeoPointSet theGeo = new GeoPointSet(getDbID());
					newGeo = theGeo;
					newGeo.studyKey = studyKey;
					newGeo.sourceKey = sourceKey;

					int ofs, i;
					StringBuilder sb;
					String ptname, str;
					double lat, lon, rhgt;
					GeoPointSet.StudyPoint thePoint = null;

					while (true) {

						line = AppCore.readLineSkipComments(theReader, lineCount);
						if (null == line) {
							break;
						}
						if (0 == line.trim().length()) {
							continue;
						}

						fields = line.split(",");

						// The name field may be quoted with enclosed commas.  Reassemble and strip quotes as needed.

						if (fields.length > 4) {
							ofs = fields.length - 4;
							sb = new StringBuilder();
							for (i = 0; i <= ofs; i++) {
								sb.append(fields[i]);
								if (i < ofs) {
									sb.append(',');
								}
							}
							ptname = sb.toString().trim();
						} else {
							ofs = 0;
							ptname = fields[0].trim();
						}
						if (ptname.startsWith("\"") && ptname.endsWith("\"")) {
							ptname = ptname.substring(1, (ptname.length() - 1)).trim();
						} else {
							ofs = 0;
						}

						if ((fields.length < 4) || ((fields.length - ofs) > 4)) {
							errmsg = "Bad field count in '" + fileName + "' at line " + lineCount;
							break;
						}

						if (ptname.length() > Geography.MAX_NAME_LENGTH) {
							ptname = ptname.substring(0, Geography.MAX_NAME_LENGTH);
						}

						lat = Source.LATITUDE_MIN - 1.;
						str = fields[ofs + 1].trim();
						if (str.length() > 0) {
							try {
								lat = Double.parseDouble(str);
							} catch (NumberFormatException ne) {
							}
						}
						if ((lat < Source.LATITUDE_MIN) || (lat > Source.LATITUDE_MAX)) {
							errmsg = "Missing or bad latitude in '" + fileName + "' at line " + lineCount;
							break;
						}

						lon = Source.LONGITUDE_MIN - 1.;
						str = fields[ofs + 2].trim();
						if (str.length() > 0) {
							try {
								lon = -Double.parseDouble(str);
							} catch (NumberFormatException ne) {
							}
						}
						if ((lon < Source.LONGITUDE_MIN) || (lon > Source.LONGITUDE_MAX)) {
							errmsg = "Missing or bad longitude in '" + fileName + "' at line " + lineCount;
							break;
						}

						rhgt = Geography.MIN_RECEIVE_HEIGHT - 1.;
						str = fields[ofs + 3].trim();
						if (str.length() > 0) {
							try {
								rhgt = Double.parseDouble(str);
							} catch (NumberFormatException ne) {
							}
						}
						if ((rhgt < Geography.MIN_RECEIVE_HEIGHT) || (rhgt > Geography.MAX_RECEIVE_HEIGHT)) {
							errmsg = "Missing or bad receiver height in '" + fileName + "' at line " + lineCount;
							break;
						}

						thePoint = new GeoPointSet.StudyPoint();

						thePoint.name = ptname;
						thePoint.setLatLon(lat, lon);
						thePoint.receiveHeight = rhgt;
						thePoint.antenna = GeoPointSet.GENERIC_ANTENNA;

						theGeo.points.add(thePoint);
					}

					break;
				}

				case Geography.GEO_TYPE_POLYGON: {

					GeoPolygon theGeo = new GeoPolygon(getDbID());
					newGeo = theGeo;
					newGeo.studyKey = studyKey;
					newGeo.sourceKey = sourceKey;

					String str;
					double lat, lon, slat = 999., nlat = -999, elon = 999., wlon = -999.;
					GeoPolygon.VertexPoint thePoint = null;

					while (true) {

						line = AppCore.readLineSkipComments(theReader, lineCount);
						if (null == line) {
							break;
						}
						if (0 == line.trim().length()) {
							continue;
						}

						fields = line.split(",");

						if (2 != fields.length) {
							errmsg = "Bad field count in '" + fileName + "' at line " + lineCount;
							break;
						}

						lat = Source.LATITUDE_MIN - 1.;
						str = fields[0].trim();
						if (str.length() > 0) {
							try {
								lat = Double.parseDouble(str);
							} catch (NumberFormatException ne) {
							}
						}
						if ((lat < Source.LATITUDE_MIN) || (lat > Source.LATITUDE_MAX)) {
							errmsg = "Missing or bad latitude in '" + fileName + "' at line " + lineCount;
							break;
						}

						lon = Source.LONGITUDE_MIN - 1.;
						str = fields[1].trim();
						if (str.length() > 0) {
							try {
								lon = -Double.parseDouble(str);
							} catch (NumberFormatException ne) {
							}
						}
						if ((lon < Source.LONGITUDE_MIN) || (lon > Source.LONGITUDE_MAX)) {
							errmsg = "Missing or bad longitude in '" + fileName + "' at line " + lineCount;
							break;
						}

						thePoint = new GeoPolygon.VertexPoint();

						thePoint.setLatLon(lat, lon);

						theGeo.points.add(thePoint);

						if (lat < slat) {
							slat = lat;
						}
						if (lat > nlat) {
							nlat = lat;
						}
						if (lon < elon) {
							elon = lon;
						}
						if (lon > wlon) {
							wlon = lon;
						}
					}

					if ((null == errmsg) && (theGeo.points.size() > 0)) {
						theGeo.reference.setLatLon(((slat + nlat) / 2.), ((elon + wlon) / 2.));
					}

					break;
				}
			}

		} catch (IOException ie) {
			errmsg = "An I/O error occurred in '" + fileName + "' at line " + lineCount + ":\n" + ie;

		} catch (Throwable t) {
			errmsg = "An unexpected error occurred in '" + fileName + "' at line " + lineCount + ":\n" + t;
			AppCore.log(AppCore.ERROR_MESSAGE, "Unexpected error", t);
		}

		try {theReader.close();} catch (IOException ie) {}

		if (null != errmsg) {
			errorReporter.reportError(errmsg);
			return null;
		}
		return newGeo;
	}


	//=================================================================================================================
	// Picker dialog for shapefile polygon import, shows list of polys along with a preview plot.

	private class PickPolyDialog extends AppDialog {

		private ArrayList<ArrayList<GeoPolygon.VertexPoint>> polys;

		private JList<String> polyList;
		private int index = -1;

		private GeoPlotPanel previewPlot;

		private JButton okButton;

		private boolean canceled;


		//-------------------------------------------------------------------------------------------------------------
		// The selection list has an extra entry, a header line, at the top.

		private PickPolyDialog(String title, Vector<String> theList,
				ArrayList<ArrayList<GeoPolygon.VertexPoint>> thePolys) {

			super(outerThis, title, Dialog.ModalityType.APPLICATION_MODAL);

			polys = thePolys;

			polyList = new JList<String>(theList);
			polyList.setFont(new Font("Monospaced", Font.PLAIN, 12));
			polyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

			polyList.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
				public void valueChanged(ListSelectionEvent theEvent) {
					if (!theEvent.getValueIsAdjusting()) {
						index = polyList.getSelectedIndex() - 1;
						if (index >= 0) {
							okButton.setEnabled(true);
							previewPlot.setVertexPoints(polys.get(index));
						} else {
							okButton.setEnabled(false);
							previewPlot.setVertexPoints(null);
						}
					}
				}
			});

			previewPlot = new GeoPlotPanel();

			okButton = new JButton("OK");
			okButton.setFocusable(false);
			okButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doOK();
				}
			});
			okButton.setEnabled(false);

			JButton cancelButton = new JButton("Cancel");
			cancelButton.setFocusable(false);
			cancelButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doCancel();
				}
			});

			JPanel leftPanel = new JPanel(new BorderLayout());
			leftPanel.add(AppController.createScrollPane(polyList, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
				JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED), BorderLayout.CENTER);

			Dimension size = new Dimension(200, 0);
			leftPanel.setMinimumSize(size);

			JPanel rightPanel = new JPanel(new BorderLayout());
			rightPanel.add(previewPlot, BorderLayout.CENTER);

			size = new Dimension(400, 0);
			rightPanel.setMinimumSize(size);

			JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
			buttonPanel.add(cancelButton);
			buttonPanel.add(okButton);

			Container cp = getContentPane();
			cp.setLayout(new BorderLayout());
			cp.add(new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, false, leftPanel, rightPanel), BorderLayout.CENTER);
			cp.add(buttonPanel, BorderLayout.SOUTH);

			pack();

			size = new Dimension(800, 500);
			setMinimumSize(size);
			setSize(size);
			setResizable(true);

			setLocationRelativeTo(outerThis);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doOK() {

			if (index < 0) {
				return;
			}

			AppController.hideWindow(this);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doCancel() {

			canceled = true;
			AppController.hideWindow(this);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This first reads all polygon parts into a temporary array, then shows a picker dialog if more than one found.
	// Opening the attributes (DBF) file does not have to succeed, if it fails the attributes aren't read.  Those are
	// only listed in the picker for identification purposes.

	private Geography importShapefile(String title, File theFile) {

		BufferedInputStream theStream = null;
		try {
			theStream = new BufferedInputStream(new FileInputStream(theFile));
		} catch (IOException ie) {
			errorReporter.reportError("Could not open the file:\n" + ie.getMessage());
			return null;
		}
		final BufferedInputStream shpStream = theStream;

		String dbfPath = theFile.getAbsolutePath();
		dbfPath = dbfPath.substring(0, (dbfPath.length() - 4)) + ".dbf";
		theStream = null;
		try {
			theStream = new BufferedInputStream(new FileInputStream(new File(dbfPath)));
		} catch (IOException ie) {
		}
		final BufferedInputStream dbfStream = theStream;

		final Vector<String> descriptions = new Vector<String>();
		final ArrayList<ArrayList<GeoPolygon.VertexPoint>> polys = new ArrayList<ArrayList<GeoPolygon.VertexPoint>>();

		final String fileName = theFile.getName();

		BackgroundWorker<String> theWorker = new BackgroundWorker<String>(this, title) {
			protected String doBackgroundWork(ErrorLogger errors) {

				ArrayList<GeoPolygon.VertexPoint> poly = null;

				String attrs = null;
				byte[] buf = new byte[65536];
				int i, j, k, len, nj, shptyp, recnum, nParts = 0, nPoints = 0, maxParts = 0, nAttr = 0, reclen = 0;
				int[] parts = null, attrLen = null, attrPad = null;
				StringBuilder s;
				long l;
				GeoPolygon.VertexPoint point = null;

				boolean readDbf = (null != dbfStream);

				try {

					if (100 != shpStream.read(buf, 0, 100)) {
						return "Unexpected end of file reading '" + fileName + "'";
					}

					i = (buf[3] & 0xFF) | (buf[2] & 0xFF) << 8 | (buf[1] & 0xFF) << 16 | (buf[0] & 0xFF) << 24;
					if (9994 != i) {
						return "File '" + fileName + "' is not a shapefile";
					}

					shptyp = (buf[32] & 0xFF) | (buf[33] & 0xFF) << 8 | (buf[34] & 0xFF) << 16 |
						(buf[35] & 0xFF) << 24;
					if ((3 != shptyp) && (5 != shptyp)) {
						return "File '" + fileName + "' does not contain polygon shapes";
					}

					if (readDbf) {
						if (32 != dbfStream.read(buf, 0, 32)) {
							readDbf = false;
						}
					}

					if (readDbf) {
						nAttr = (((buf[8] & 0xFF) | (buf[9] & 0xFF) << 8) - 33) / 32;
						if ((nAttr <= 0) || (nAttr > 255)) {
							readDbf = false;
						}
					}

					if (readDbf) {
						String hdr = "Poly  Part   # pts  ";
						s = new StringBuilder(hdr);
						attrLen = new int[nAttr];
						attrPad = new int[nAttr];
						reclen = 0;
						for (i = 0; i < nAttr; i++) {
							if (32 != dbfStream.read(buf, 0, 32)) break;
							attrLen[i] = (buf[16] & 0xFF);
							reclen += attrLen[i];
							for (j = 0; j < 11; j++) {
								if (0 == buf[j]) break;
								s.append((char)buf[j]);
							}
							len = ((attrLen[i] > j) ? attrLen[i] : j) + 2;
							for (; j < len; j++) {
								s.append(' ');
							}
							attrPad[i] = len - attrLen[i];
						}
						if (i < nAttr) {
							readDbf = false;
							descriptions.add(hdr);
						} else {
							descriptions.add(s.toString());
							dbfStream.read(buf, 0, 1);
							reclen++;
						}
					}

					while (true) {

						if (12 != shpStream.read(buf, 0, 12)) {
							break;
						}

						recnum = (buf[3] & 0xFF) | (buf[2] & 0xFF) << 8 | (buf[1] & 0xFF) << 16 |
							(buf[0] & 0xFF) << 24;
						shptyp = (buf[8] & 0xFF) | (buf[9] & 0xFF) << 8 | (buf[10] & 0xFF) << 16 |
							(buf[11] & 0xFF) << 24;

						// Ignore null shapes, skip the related DBF record.

						if (0 == shptyp) {
							if (readDbf) {
								dbfStream.read(buf, 0, reclen);
							}
							continue;
						}

						if ((3 != shptyp) && (5 != shptyp)) {
							return "File '" + fileName + "' does not contain polygon shapes";
						}

						if (40 != shpStream.read(buf, 0, 40)) {
							return "Unexpected end of file reading '" + fileName + "'";
						}

						nParts = (buf[32] & 0xFF) | (buf[33] & 0xFF) << 8 | (buf[34] & 0xFF) << 16 |
							(buf[35] & 0xFF) << 24;
						nPoints = (buf[36] & 0xFF) | (buf[37] & 0xFF) << 8 | (buf[38] & 0xFF) << 16 |
							(buf[39] & 0xFF) << 24;

						if (nParts > maxParts) {
							maxParts = nParts + 10;
							parts = new int[maxParts];
						}

						for (i = 0; i < nParts; i++) {
							if (4 != shpStream.read(buf, 0, 4)) break;
							parts[i] = (buf[0] & 0xFF) | (buf[1] & 0xFF) << 8 | (buf[2] & 0xFF) << 16 |
								(buf[3] & 0xFF) << 24;
						}
						if (i < nParts) {
							return "Unexpected end of file reading '" + fileName + "'";
						}

						attrs = "";
						if (readDbf) {
							if (reclen != dbfStream.read(buf, 0, reclen)) {
								readDbf = false;
							} else {
								s = new StringBuilder();
								k = 1;
								for (i = 0; i < nAttr; i++) {
									for (j = 0; j < attrLen[i]; j++) {
										s.append((char)buf[k++]);
									}
									for (j = 0; j < attrPad[i]; j++) {
										s.append(' ');
									}
								}
								attrs = s.toString();
							}
						}

						for (i = 0; i < nParts; i++) {

							poly = new ArrayList<GeoPolygon.VertexPoint>();
							polys.add(poly);

							if (i < (nParts - 1)) {
								nj = parts[i + 1];
							} else {
								nj = nPoints;
							}

							descriptions.add(String.format(Locale.US, "%4d  %4d  %6d  %s", recnum, (i + 1),
								(nj - parts[i]), attrs));

							for (j = parts[i]; j < nj; j++) {

								point = new GeoPolygon.VertexPoint();
								poly.add(point);

								if (8 != shpStream.read(buf, 0, 8)) break;
								l = (long)(buf[0] & 0xFF) | (long)(buf[1] & 0xFF) << 8 | (long)(buf[2] & 0xFF) << 16 |
									(long)(buf[3] & 0xFF) << 24 | (long)(buf[4] & 0xFF) << 32 |
									(long)(buf[5] & 0xFF) << 40 | (long)(buf[6] & 0xFF) << 48 |
									(long)(buf[7] & 0xFF) << 56;
								point.longitude = -Double.longBitsToDouble(l);

								if (8 != shpStream.read(buf, 0, 8)) break;
								l = (long)(buf[0] & 0xFF) | (long)(buf[1] & 0xFF) << 8 | (long)(buf[2] & 0xFF) << 16 |
									(long)(buf[3] & 0xFF) << 24 | (long)(buf[4] & 0xFF) << 32 |
									(long)(buf[5] & 0xFF) << 40 | (long)(buf[6] & 0xFF) << 48 |
									(long)(buf[7] & 0xFF) << 56;
								point.latitude = Double.longBitsToDouble(l);

								point.updateDMS();
							}

							if (j < nj) {
								return "Unexpected end of file reading '" + fileName + "'";
							}
						}

						if (i < nParts) {
							return "Unexpected end of file reading '" + fileName + "'";
						}
					}

				} catch (IOException ie) {
					return "An I/O error occurred reading '" + fileName + "':\n" + ie;

				} catch (Throwable t) {
					AppCore.log(AppCore.ERROR_MESSAGE, "Unexpected error", t);
					return "An unexpected error occurred reading '" + fileName + "':\n" + t;
				}

				return null;
			}
		};

		String errmsg = theWorker.runWork("Reading shapefile, please wait...", errorReporter);

		try {shpStream.close();} catch (IOException ie) {}

		if (null != dbfStream) {
			try {dbfStream.close();} catch (IOException ie) {}
		}

		if (null != errmsg) {
			errorReporter.reportError(errmsg);
			return null;
		}

		if (polys.isEmpty()) {
			errorReporter.reportWarning("File '" + fileName + "' does not contain any non-null shapes");
			return null;
		}

		GeoPolygon newGeo = new GeoPolygon(getDbID());
		newGeo.studyKey = studyKey;
		newGeo.sourceKey = sourceKey;

		if (polys.size() > 1) {

			PickPolyDialog theDialog = new PickPolyDialog("Choose Polygon", descriptions, polys);
			AppController.showWindow(theDialog);
			if (theDialog.canceled) {
				return null;
			}

			newGeo.points = polys.get(theDialog.index);

		} else {
			newGeo.points = polys.get(0);
		}

		double slat = 999., nlat = -999, elon = 999., wlon = -999.;
		for (GeoPolygon.VertexPoint thePoint : newGeo.points) {
			if (thePoint.latitude < slat) {
				slat = thePoint.latitude;
			}
			if (thePoint.latitude > nlat) {
				nlat = thePoint.latitude;
			}
			if (thePoint.longitude < elon) {
				elon = thePoint.longitude;
			}
			if (thePoint.longitude > wlon) {
				wlon = thePoint.longitude;
			}
		}

		newGeo.reference.setLatLon(((slat + nlat) / 2.), ((elon + wlon) / 2.));

		return newGeo;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Export data to a CSV file for point set, sectors, or polygon.

	private void doExportData() {

		if ((null == currentGeo) || ((Geography.GEO_TYPE_POINT_SET != currentGeo.type) &&
				(Geography.GEO_TYPE_SECTORS != currentGeo.type) && (Geography.GEO_TYPE_POLYGON != currentGeo.type))) {
			return;
		}

		String title = "Export " + Geography.getTypeName(currentGeo.type) + " Data";
		errorReporter.setTitle(title);
		if (!currentGeo.isDataValid(errorReporter)) {
			return;
		}

		JFileChooser chooser = new JFileChooser(AppCore.getProperty(AppCore.LAST_FILE_DIRECTORY_KEY));
		chooser.setDialogType(JFileChooser.SAVE_DIALOG);
		chooser.setDialogTitle(title);
		chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		chooser.setMultiSelectionEnabled(false);
		switch (currentGeo.type) {
			case Geography.GEO_TYPE_POINT_SET: {
				chooser.addChoosableFileFilter(new FileNameExtensionFilter("Points (*.csv)", "csv"));
				break;
			}
			case Geography.GEO_TYPE_SECTORS: {
				chooser.addChoosableFileFilter(new FileNameExtensionFilter("Sectors (*.csv)", "csv"));
				break;
			}
			case Geography.GEO_TYPE_POLYGON: {
				chooser.addChoosableFileFilter(new FileNameExtensionFilter("Polygon (*.csv)", "csv"));
				break;
			}
		}
		chooser.setAcceptAllFileFilterUsed(false);

		File theFile = null;
		do {
			if (JFileChooser.APPROVE_OPTION != chooser.showDialog(this, "Export Data")) {
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

		String theName = theFile.getName().toLowerCase();
		if (!theName.endsWith(".csv")) {
			theFile = new File(theFile.getAbsolutePath() + ".csv");
		}

		BufferedWriter theWriter = null;
		try {
			theWriter = new BufferedWriter(new FileWriter(theFile));
		} catch (IOException ie) {
			errorReporter.reportError("Could not open the file:\n" + ie.getMessage());
			return;
		}

		String fileName = theFile.getName(), errmsg = null;

		try {

			switch (currentGeo.type) {

				case Geography.GEO_TYPE_POINT_SET: {
					for (GeoPointSet.StudyPoint point : ((GeoPointSet)currentGeo).points) {
						theWriter.write(String.format(Locale.US, "\"%s\",%f,%f,%f\n", point.name, point.latitude,
							-point.longitude, point.receiveHeight));
					}
					break;
				}

				case Geography.GEO_TYPE_SECTORS: {
					theWriter.write(((GeoSectors)currentGeo).encodeAsString());
					theWriter.write("\n");
					break;
				}

				case Geography.GEO_TYPE_POLYGON: {
					for (GeoPolygon.VertexPoint point : ((GeoPolygon)currentGeo).points) {
						theWriter.write(String.format(Locale.US, "%f,%f\n", point.latitude, -point.longitude));
					}
					break;
				}
			}

		} catch (IOException ie) {
			errmsg = "An I/O error occurred writing to '" + fileName + "':\n" + ie;

		} catch (Throwable t) {
			errmsg = "An unexpected error occurred writing to '" + fileName + "':\n" + t;
			AppCore.log(AppCore.ERROR_MESSAGE, "Unexpected error", t);
		}

		try {theWriter.close();} catch (IOException ie) {}

		if (null != errmsg) {
			errorReporter.reportError(errmsg);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowWillOpen() {

		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				geoType = geoTypeMenu.getSelectedKey();
				geoModel.setRows(Geography.getGeographies(getDbID(), geoType, studyKey, sourceKey, errorReporter));
				if (showGeoKey > 0) {
					int rowIndex = geoModel.indexOfKey(showGeoKey);
					if (rowIndex >= 0) {
						geoList.scrollRectToVisible(geoList.getCellBounds(rowIndex, rowIndex));
						geoList.setSelectedIndex(rowIndex);
						loadCurrentGeo();
					}
					showGeoKey = 0;
				}
			}
		});

		blockActionsClear();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean windowShouldClose() {

		if (!saveIfNeeded("Close Geography Editor", true)) {
			toFront();
			return false;
		}

		clearCurrentGeo();

		geoModel.setRows(null);

		blockActionsSet();
		parent.editorClosing(this);
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean closeIfPossible() {

		if (!isVisible()) {
			return true;
		}
		if (windowShouldClose()) {
			AppController.hideWindow(this);
			return true;
		}
		return false;
	}
}
