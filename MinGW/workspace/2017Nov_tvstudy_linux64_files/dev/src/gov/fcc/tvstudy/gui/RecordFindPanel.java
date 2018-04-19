//
//  RecordFindPanel.java
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
import java.awt.*;
import java.awt.event.*;
import java.text.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.table.*;
import javax.swing.filechooser.*;


//=====================================================================================================================
// Panel UI for finding and creating records.  Searches can be made on external station data sets or the custom user
// record table.  Search can target a specific record by record ID or file number.  A general search can match any
// combination of facility ID, service, call sign, channel, status, city, and state.  User can choose from multiple
// results displayed in a table, and preview those in a SourceEditor.  New records can also be created, either all-new
// or from duplicating another record.  When a new record is created it may be made into a permanent user record.  A
// default data set can be specified if appropriate, that will be the default in the menu of available data sets.  The
// legal channel range for a search may also be restricted.  See the RecordFind class for typical use.

public class RecordFindPanel extends AppPanel {

	// Configurable properties, see set methods.  These can only be changed when the panel is not visible.

	private int studyType;
	private int recordType;
	private boolean userRecordsOnly;

	private Integer defaultExtDbKey;

	private int minimumTVChannel;
	private int maximumTVChannel;

	// UI components.

	private KeyedRecordMenu extDbMenu;
	private JCheckBox baselineCheckBox;
	private ExtDb extDb;
	private int searchRecordType;
	private boolean useBaseline;

	private JTextField recordIDField;
	private JTextField fileNumberField;
	private JPanel fileNumberPanel;

	private JTextField callSignField;
	private JPanel callSignPanel;
	private JTextField channelField;
	private KeyedRecordMenu statusMenu;
	private KeyedRecordMenu serviceMenu;
	private JTextField cityField;
	private JTextField stateField;
	private KeyedRecordMenu countryMenu;
	private JTextField facilityIDField;
	private JCheckBox includeArchivedCheckBox;

	private String additionalSQL;
	private JButton addSQLButton;

	private RecordListTableModel listModel;
	private JTable listTable;

	private RecordListItem selectedItem;
	private int selectedItemIndex = -1;

	private JLabel noteLabel;
	private JPanel notePanel;

	// Buttons and menu items.

	private boolean canViewEdit;

	private boolean showUserDelete;

	private KeyedRecordMenu newRecordMenu;

	private JButton searchButton;
	private JButton duplicateButton;
	private JButton exportButton;
	private JButton saveButton;
	private JButton deleteButton;
	private JButton openButton;

	private JMenuItem duplicateMenuItem;
	private JMenuItem exportMenuItem;
	private JMenuItem saveMenuItem;
	private JMenuItem deleteMenuItem;
	private JMenuItem openMenuItem;

	// Disambiguation.

	private RecordFindPanel outerThis = this;


	//-----------------------------------------------------------------------------------------------------------------
	// The study type restricts the data set menu to sets providing record types allowed in that type of study, also
	// the options for creating new records are similarily restricted, and records imported from XML files must be of
	// an allowed type.  However the study type may be 0 to show and allow all types.  Alternately a single record
	// type may be specified and only that type is allowed, but that also may be 0 for no restriction.  The record
	// type may also be negated which means only user records of that type may be chosen.  If both type arguments are
	// non-0 the record type takes priority, regardless of whether that record type is compatible with the specified
	// study type.  The callback will be called when the selected record changes.  If canViewEdit is false, all UI
	// related to creating, editing, and viewing records is omitted; the panel is used to search and select one record
	// from the results table, and nothing more.

	public RecordFindPanel(AppEditor theParent, int theStudyType, int theRecordType) {
		super(theParent);
		doSetup(theStudyType, theRecordType, true);
	}

	public RecordFindPanel(AppEditor theParent, Runnable theCallBack, int theStudyType, int theRecordType) {
		super(theParent, theCallBack);
		doSetup(theStudyType, theRecordType, true);
	}

	public RecordFindPanel(AppEditor theParent, Runnable theCallBack, int theRecordType, boolean theCanViewEdit) {
		super(theParent, theCallBack);
		doSetup(0, theRecordType, theCanViewEdit);
	}

	private void doSetup(int theStudyType, int theRecordType, boolean theCanViewEdit) {

		canViewEdit = theCanViewEdit;

		minimumTVChannel = SourceTV.CHANNEL_MIN;
		maximumTVChannel = SourceTV.CHANNEL_MAX;

		ArrayList<KeyedRecord> list = new ArrayList<KeyedRecord>();

		// UI for creating a new record, also set the record and study type restrictions.  If a single record type was
		// specified, or if a study type was specified that only allows a single record type, recordType is set to the
		// type and the new-record UI is a button.  Otherwise studyType is set and the UI is a menu listing the types.
		// If the record type is a negative number, only user records of that type may be selected.

		if (theRecordType > 0) {
			recordType = theRecordType;
		} else {
			if (theRecordType < 0) {
				recordType = -theRecordType;
				userRecordsOnly = true;
			} else {
				list.addAll(Source.getRecordTypes(theStudyType));
				if (1 == list.size()) {
					recordType = list.get(0).key;
				} else {
					studyType = theStudyType;
				}
			}
		}

		JButton newRecordButton = null;

		if (canViewEdit) {

			if (recordType > 0) {

				newRecordButton = new JButton("New");
				newRecordButton.setFocusable(false);
				newRecordButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doNew(recordType);
					}
				});

			} else {

				list.add(0, new KeyedRecord(0, "New"));
				newRecordMenu = new KeyedRecordMenu(list);

				newRecordMenu.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							int theKey = newRecordMenu.getSelectedKey();
							newRecordMenu.setSelectedIndex(0);
							blockActionsEnd();
							if (theKey > 0) {
								doNew(theKey);
							}
						}
					}
				});
			}
		}

		// Create the search fields.  The data set menu will be populated when the dialog is shown.  For TV data sets
		// the alternate baseline record table may be searched.

		extDbMenu = new KeyedRecordMenu();
		extDbMenu.setPrototypeDisplayValue(new KeyedRecord(0, "XyXyXyXyXyXyXyXyXyXy"));
		extDbMenu.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					updateUI(false);
					blockActionsEnd();
				}
			}
		});

		baselineCheckBox = new JCheckBox("Baseline");
		baselineCheckBox.setFocusable(false);
		baselineCheckBox.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					updateUI(false);
					blockActionsEnd();
				}
			}
		});

		JPanel dataPanel = new JPanel();
		dataPanel.setBorder(BorderFactory.createTitledBorder("Search Station Data"));
		dataPanel.add(extDbMenu);
		dataPanel.add(baselineCheckBox);

		// Record ID.

		recordIDField = new JTextField(18);
		AppController.fixKeyBindings(recordIDField);

		JPanel recordIDPanel = new JPanel();
		recordIDPanel.setBorder(BorderFactory.createTitledBorder("Record ID"));
		recordIDPanel.add(recordIDField);

		// File number.

		fileNumberField = new JTextField(18);
		AppController.fixKeyBindings(fileNumberField);

		fileNumberPanel = new JPanel();
		fileNumberPanel.setBorder(BorderFactory.createTitledBorder("File Number"));
		fileNumberPanel.add(fileNumberField);

		// Call sign.

		callSignField = new JTextField(8);
		AppController.fixKeyBindings(callSignField);

		callSignPanel = new JPanel();
		callSignPanel.setBorder(BorderFactory.createTitledBorder("Call Sign"));
		callSignPanel.add(callSignField);

		// Channel.

		channelField = new JTextField(5);
		AppController.fixKeyBindings(channelField);

		JPanel channelPanel = new JPanel();
		channelPanel.setBorder(BorderFactory.createTitledBorder("Channel"));
		channelPanel.add(channelField);

		// Status.

		list.clear();
		list.add(new KeyedRecord(-1, "(any)"));
		list.addAll(ExtDbRecord.getStatusList());
		statusMenu = new KeyedRecordMenu(list);

		JPanel statusPanel = new JPanel();
		statusPanel.setBorder(BorderFactory.createTitledBorder("Status"));
		statusPanel.add(statusMenu);

		// Service, this will be updated per record type when the data set is changed, see updateUI().

		list.clear();
		list.add(new KeyedRecord(0, "(any)"));
		serviceMenu = new KeyedRecordMenu(list);

		JPanel servicePanel = new JPanel();
		servicePanel.setBorder(BorderFactory.createTitledBorder("Service"));
		servicePanel.add(serviceMenu);

		// City.

		cityField = new JTextField(18);
		AppController.fixKeyBindings(cityField);

		JPanel cityPanel = new JPanel();
		cityPanel.setBorder(BorderFactory.createTitledBorder("City"));
		cityPanel.add(cityField);

		// State.

		stateField = new JTextField(4);
		AppController.fixKeyBindings(stateField);

		JPanel statePanel = new JPanel();
		statePanel.setBorder(BorderFactory.createTitledBorder("State"));
		statePanel.add(stateField);

		// Country.

		list.clear();
		list.add(new KeyedRecord(0, "(any)"));
		list.addAll(Country.getCountries());
		countryMenu = new KeyedRecordMenu(list);

		JPanel countryPanel = new JPanel();
		countryPanel.setBorder(BorderFactory.createTitledBorder("Country"));
		countryPanel.add(countryMenu);

		// Facility ID.

		facilityIDField = new JTextField(7);
		AppController.fixKeyBindings(facilityIDField);

		JPanel facilityIDPanel = new JPanel();
		facilityIDPanel.setBorder(BorderFactory.createTitledBorder("Facility ID"));
		facilityIDPanel.add(facilityIDField);

		// External data search is normally just for current and pending records, option to include archived.

		includeArchivedCheckBox = new JCheckBox("Include archived");
		includeArchivedCheckBox.setFocusable(false);

		// Button to prompt for input of additional SQL appended to the WHERE clause.

		additionalSQL = "";

		addSQLButton = new JButton("Add SQL");
		addSQLButton.setFocusable(false);
		addSQLButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doAddSQL();
			}
		});

		// Table to display list of matching records.

		JPanel listTablePanel = new JPanel(new BorderLayout());
		listModel = new RecordListTableModel(listTablePanel);
		listTable = listModel.createTable();

		if (canViewEdit) {
			listTable.addMouseListener(new MouseAdapter() {
				public void mouseClicked(MouseEvent e) {
					if (2 == e.getClickCount()) {
						doOpen();
					}
				}
			});
		}

		listTable.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
			public void valueChanged(ListSelectionEvent theEvent) {
				if (!theEvent.getValueIsAdjusting()) {
					if (1 == listTable.getSelectedRowCount()) {
						selectedItemIndex = listTable.convertRowIndexToModel(listTable.getSelectedRow());
						selectedItem = listModel.get(selectedItemIndex);
					} else {
						selectedItemIndex = -1;
						selectedItem = null;
					}
					updateControls();
					if (null != callBack) {
						callBack.run();
					}
				}
			}
		});

		listTablePanel.add(AppController.createScrollPane(listTable, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
				JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS), BorderLayout.CENTER);
		listTablePanel.add(listModel.filterPanel, BorderLayout.SOUTH);

		// See setNote().

		noteLabel = new JLabel();
		notePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		notePanel.add(noteLabel);
		notePanel.setVisible(false);

		// Property may hide ability to delete user records.

		String str = AppCore.getConfig(AppCore.CONFIG_HIDE_USER_RECORD_DELETE);
		showUserDelete = !((null != str) && Boolean.valueOf(str).booleanValue());

		// Buttons.

		searchButton = new JButton("Search");
		searchButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doSearch();
			}
		});

		JButton clearButton = new JButton("Clear");
		clearButton.setFocusable(false);
		clearButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doClear(true);
			}
		});

		JButton importButton = null;

		if (canViewEdit) {

			duplicateButton = new JButton("Duplicate");
			duplicateButton.setFocusable(false);
			duplicateButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doDuplicate();
				}
			});

			importButton = new JButton("Import");
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

			if (showUserDelete) {
				deleteButton = new JButton("Delete");
				deleteButton.setFocusable(false);
				deleteButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doDelete();
					}
				});
			}

			openButton = new JButton("View");
			openButton.setFocusable(false);
			openButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doOpen();
				}
			});
		}

		// Do the layout.

		JPanel recIDPanel = new JPanel();
		recIDPanel.add(recordIDPanel);

		JPanel fileNumPanel = new JPanel();
		fileNumPanel.add(fileNumberPanel);

		JPanel callFacPanel = new JPanel();
		callFacPanel.add(callSignPanel);
		callFacPanel.add(facilityIDPanel);

		JPanel chanStatPanel = new JPanel();
		chanStatPanel.add(channelPanel);
		chanStatPanel.add(statusPanel);

		JPanel servPanel = new JPanel();
		servPanel.add(servicePanel);

		JPanel ctyPanel = new JPanel();
		ctyPanel.add(cityPanel);

		JPanel stCntPanel = new JPanel();
		stCntPanel.add(statePanel);
		stCntPanel.add(countryPanel);

		JPanel arcSQLPanel = new JPanel();
		arcSQLPanel.add(includeArchivedCheckBox);
		arcSQLPanel.add(addSQLButton);

		Box searchBox = Box.createVerticalBox();
		searchBox.add(callFacPanel);
		searchBox.add(chanStatPanel);
		searchBox.add(fileNumPanel);
		searchBox.add(servPanel);
		searchBox.add(ctyPanel);
		searchBox.add(stCntPanel);
		searchBox.add(arcSQLPanel);
		searchBox.add(recIDPanel);

		JPanel searchButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		searchButtonPanel.add(clearButton);
		searchButtonPanel.add(searchButton);

		JPanel searchPanel = new JPanel(new BorderLayout());
		searchPanel.add(dataPanel, BorderLayout.NORTH);
		searchPanel.add(AppController.createScrollPane(searchBox), BorderLayout.CENTER);
		searchPanel.add(searchButtonPanel, BorderLayout.SOUTH);

		JPanel listPanel = null;

		if (canViewEdit) {

			JPanel listButtonLeftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
			listButtonLeftPanel.add(openButton);
			listButtonLeftPanel.add(duplicateButton);
			listButtonLeftPanel.add(exportButton);
			listButtonLeftPanel.add(saveButton);
			if (showUserDelete) {
				listButtonLeftPanel.add(deleteButton);
			}

			JPanel listButtonRightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
			listButtonRightPanel.add(importButton);
			if (null != newRecordButton) {
				listButtonRightPanel.add(newRecordButton);
			}
			if (null != newRecordMenu) {
				listButtonRightPanel.add(newRecordMenu);
			}

			Box listButtonBox = Box.createHorizontalBox();
			listButtonBox.add(listButtonLeftPanel);
			listButtonBox.add(listButtonRightPanel);

			listPanel = new JPanel(new BorderLayout());
			listPanel.add(listTablePanel, BorderLayout.CENTER);
			listPanel.add(listButtonBox, BorderLayout.SOUTH);

		} else {

			listPanel = listTablePanel;
		}

		setLayout(new BorderLayout());
		add(notePanel, BorderLayout.NORTH);
		add(listPanel, BorderLayout.CENTER);
		add(searchPanel, BorderLayout.EAST);

		// Create persistent menu items, regardless of whether or not they will be used, see addMenuItems().  This is
		// easier than checking for null all the time in the state updater.

		if (canViewEdit) {

			duplicateMenuItem = new JMenuItem("Duplicate");
			duplicateMenuItem.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doDuplicate();
				}
			});

			openMenuItem = new JMenuItem("View");
			openMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, AppController.MENU_SHORTCUT_KEY_MASK));
			openMenuItem.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doOpen();
				}
			});

			exportMenuItem = new JMenuItem("Export...");
			exportMenuItem.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doExport();
				}
			});

			saveMenuItem = new JMenuItem("Save");
			saveMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, AppController.MENU_SHORTCUT_KEY_MASK));
			saveMenuItem.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doSave();
				}
			});

			if (showUserDelete) {
				deleteMenuItem = new JMenuItem("Delete");
				deleteMenuItem.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doDelete();
					}
				});
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Return button for presenting window to set as root pane default.

	public JButton getDefaultButton() {

		return searchButton;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add items to a menu from a presenting window.

	public void addMenuItems(JMenu fileMenu) {

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

		if (!canViewEdit) {
			return;
		}

		// __________________________________

		fileMenu.addSeparator();

		// New [ -> ]

		if (recordType > 0) {

			JMenuItem miNew = new JMenuItem("New");
			miNew.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, AppController.MENU_SHORTCUT_KEY_MASK));
			miNew.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doNew(recordType);
				}
			});
			fileMenu.add(miNew);

		} else {

			JMenu meNew = new JMenu("New");
			JMenuItem miNew;
			for (KeyedRecord theType : Source.getRecordTypes(studyType)) {
				miNew = new JMenuItem(theType.name);
				final int typeKey = theType.key;
				miNew.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doNew(typeKey);
					}
				});
				meNew.add(miNew);
			}
			fileMenu.add(meNew);
		}

		// Import...

		JMenuItem miImport = new JMenuItem("Import...");
		miImport.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doImport();
			}
		});
		fileMenu.add(miImport);

		// Duplicate

		fileMenu.add(duplicateMenuItem);

		// __________________________________

		fileMenu.addSeparator();

		// View/Edit

		fileMenu.add(openMenuItem);

		// Export...

		fileMenu.add(exportMenuItem);

		// Save

		fileMenu.add(saveMenuItem);

		// Delete

		if (showUserDelete) {
			fileMenu.add(deleteMenuItem);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set default station data.  This and all the other configurable properties can only be changed while hidden.

	public void setDefaultExtDbKey(Integer theKey) {

		if (getWindow().isVisible()) {
			return;
		}

		defaultExtDbKey = theKey;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set the TV channel range for record searches (FM searches are never restricted by channel).  If either value is
	// invalid it reverts to the default maximum range, clearing the range may be accomplished by setting 0,0.  Note
	// reversing the arguments is allowed.

	public void setTVChannelRange(int theMinChannel, int theMaxChannel) {

		if (getWindow().isVisible()) {
			return;
		}

		if ((theMinChannel < SourceTV.CHANNEL_MIN) || (theMinChannel > SourceTV.CHANNEL_MAX)) {
			theMinChannel = SourceTV.CHANNEL_MIN;
		}

		if ((theMaxChannel < SourceTV.CHANNEL_MIN) || (theMaxChannel > SourceTV.CHANNEL_MAX)) {
			theMaxChannel = SourceTV.CHANNEL_MAX;
		}

		if (theMinChannel <= theMaxChannel) {
			minimumTVChannel = theMinChannel;
			maximumTVChannel = theMaxChannel;
		} else {
			minimumTVChannel = theMaxChannel;
			maximumTVChannel = theMinChannel;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set note text appearing a label at the top of the layout.  If null or empty string the label is hidden.

	public void setNote(String theNote) {

		if ((null == theNote) || (0 == theNote.length())) {
			noteLabel.setText("");
			notePanel.setVisible(false);
		} else {
			noteLabel.setText(theNote);
			notePanel.setVisible(true);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void updateDocumentName() {

		listModel.updateEditorDocumentNames();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Public methods for presenting window to access the selection.  The "apply" concept means that window tells some
	// outer editing context to actually do something with the record.  That can only occur if the selection is valid,
	// but some contexts will accept a new (unsaved) record and some will not so caller must specify.  Note even if a
	// record is new and new can be applied, it can't be applied if it has an open editor since that may be holding
	// un-committed editor state.  The caller can reply back when a record has been applied, that will affect the
	// behavior on window close, see windowWillClose().

	public Record getSelectedRecord() {

		if (null != selectedItem) {
			return selectedItem.record;
		}
		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean canApplySelection(boolean canApplyNew) {

		if (null != selectedItem) {
			 return (selectedItem.isValid &&
				(!selectedItem.isNew || (canApplyNew && (null == selectedItem.sourceEditor))));
		}
		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void selectionWasApplied() {

		if (null != selectedItem) {
			selectedItem.wasApplied = true;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update the UI when the data set selection is changed, adapting to the new set's record type as needed.  Search
	// criteria that don't apply to the new record type are cleared and disabled, also the service menu is updated.
	// Any content in the record ID or additional SQL fields are also cleared.  Likewise the previous search results
	// are cleared if there is any actual change here.  If the firstUpdate flag is true this ignores existing state and
	// updates as if everything changed.

	private void updateUI(boolean firstUpdate) {

		boolean didChange = false;

		int newRecordType = searchRecordType;

		// The key value on "* user records" menu items is the record type negated.

		int oldDbKey = -searchRecordType;
		if (null != extDb) {
			oldDbKey = extDb.key.intValue();
		}

		int newDbKey = extDbMenu.getSelectedKey();
		ExtDb newExtDb = null;
		if (newDbKey > 0) {
			newExtDb = ExtDb.getExtDb(getDbID(), Integer.valueOf(newDbKey), errorReporter);
			if (null == newExtDb) {
				extDbMenu.setSelectedKey(oldDbKey);
			} else {
				newRecordType = newExtDb.recordType;
			}
		} else {
			if (newDbKey < 0) {
				newRecordType = -newDbKey;
			} else {
				newDbKey = -newRecordType;
			}
		}

		if ((newDbKey != oldDbKey) || firstUpdate) {
			extDb = newExtDb;
			if ((null != extDb) && extDb.hasBaseline()) {
				AppController.setComponentEnabled(baselineCheckBox, true);
			} else {
				baselineCheckBox.setSelected(false);
				AppController.setComponentEnabled(baselineCheckBox, false);
			}
			didChange = true;
		}
		boolean newBaseline = baselineCheckBox.isSelected();

		if ((newRecordType != searchRecordType) || (newBaseline != useBaseline) || firstUpdate) {

			searchRecordType = newRecordType;
			useBaseline = newBaseline;
			didChange = true;

			ArrayList<KeyedRecord> list = new ArrayList<KeyedRecord>();
			list.add(new KeyedRecord(0, "(any)"));
			list.addAll(Service.getServices(searchRecordType));
			serviceMenu.removeAllItems();
			serviceMenu.addAllItems(list);

			switch (searchRecordType) {

				case Source.RECORD_TYPE_TV:
				default: {

					fileNumberPanel.setBorder(BorderFactory.createTitledBorder("File Number"));
					callSignPanel.setBorder(BorderFactory.createTitledBorder("Call Sign"));

					AppController.setComponentEnabled(channelField, true);
					AppController.setComponentEnabled(facilityIDField, true);

					if (useBaseline) {

						fileNumberField.setText("");
						statusMenu.setSelectedIndex(0);
						includeArchivedCheckBox.setSelected(false);

						AppController.setComponentEnabled(fileNumberField, false);
						AppController.setComponentEnabled(statusMenu, false);
						AppController.setComponentEnabled(includeArchivedCheckBox, false);

						if (ExtDb.DB_TYPE_CDBS == extDb.type) {

							serviceMenu.setSelectedIndex(0);
							countryMenu.setSelectedIndex(0);

							AppController.setComponentEnabled(serviceMenu, false);
							AppController.setComponentEnabled(countryMenu, false);
						}

					} else {

						AppController.setComponentEnabled(fileNumberField, true);
						AppController.setComponentEnabled(statusMenu, true);
						AppController.setComponentEnabled(serviceMenu, true);
						AppController.setComponentEnabled(includeArchivedCheckBox, true);
						AppController.setComponentEnabled(countryMenu, true);
					}

					break;
				}

				case Source.RECORD_TYPE_WL: {

					fileNumberPanel.setBorder(BorderFactory.createTitledBorder("Reference Number"));
					callSignPanel.setBorder(BorderFactory.createTitledBorder("Cell Site ID"));

					channelField.setText("");
					statusMenu.setSelectedIndex(0);
					facilityIDField.setText("");
					includeArchivedCheckBox.setSelected(false);

					AppController.setComponentEnabled(fileNumberField, true);
					AppController.setComponentEnabled(channelField, false);
					AppController.setComponentEnabled(statusMenu, false);
					AppController.setComponentEnabled(serviceMenu, true);
					AppController.setComponentEnabled(facilityIDField, false);
					AppController.setComponentEnabled(includeArchivedCheckBox, false);
					AppController.setComponentEnabled(countryMenu, true);

					break;
				}

				case Source.RECORD_TYPE_FM: {

					fileNumberPanel.setBorder(BorderFactory.createTitledBorder("File Number"));
					callSignPanel.setBorder(BorderFactory.createTitledBorder("Call Sign"));

					AppController.setComponentEnabled(fileNumberField, true);
					AppController.setComponentEnabled(channelField, true);
					AppController.setComponentEnabled(statusMenu, true);
					AppController.setComponentEnabled(serviceMenu, true);
					AppController.setComponentEnabled(facilityIDField, true);
					AppController.setComponentEnabled(includeArchivedCheckBox, true);
					AppController.setComponentEnabled(countryMenu, true);

					break;
				}
			}
		}

		if (didChange) {
			recordIDField.setText("");
			additionalSQL = "";
			addSQLButton.setText("Add SQL");
			listModel.setItems(null, true);
		}
	}


	//=================================================================================================================
	// Item class for table list model.  A SourceEditor may be open for any/many records in the list.  The isSource,
	// isUserRecord, and isNew flags are convenience.  The isValid flag is true for most records.  It is only false
	// temporarily on completely new records while the initial SourceEditor is open, there it will be set true in
	// applyEditsFrom(), SourceEditor will not apply edits until data is valid.  If that initial editor is canceled
	// the invalid new record is removed again in editorClosing().  The wasApplied flag is set on any new record when
	// it is applied to the parent, and is cleared again if the record is edited again, see hasUnsavedData().  The
	// comment text for source objects may be set to a user record comment, on data set records it may be set to some
	// useful accessory information from the record.  It appears as a pop-up in the results table.  When records are
	// imported from XML, isNew will be true on a record that is not locked, but in that case isImport is also true.
	// If a new imported record is actually edited isImport is set false.  That is so new imported records that have
	// not been edited do not trigger warning messages about unsaved changes, since there really aren't any.

	private class RecordListItem {

		private Record record;

		private boolean isSource;
		private boolean isUserRecord;
		private boolean isNew;
		private boolean isImport;
		private boolean isValid;
		private boolean wasApplied;

		private String comment;

		private SourceEditor sourceEditor;
	}


	//=================================================================================================================
	// Table model for list of search results.

	private class RecordListTableModel extends AbstractTableModel implements TableFilterModel {

		private static final String RECORD_TYPE_COLUMN = "Type";
		private static final String RECORD_CALLSIGN_COLUMN = "Call Sign";
		private static final String RECORD_CALLSIGN_COLUMN_WL = "Call/ID";
		private static final String RECORD_CHANNEL_COLUMN = "Channel";
		private static final String RECORD_SERVICE_COLUMN = "Svc";
		private static final String RECORD_STATUS_COLUMN = "Status";
		private static final String RECORD_CITY_COLUMN = "City";
		private static final String RECORD_STATE_COLUMN = "State";
		private static final String RECORD_COUNTRY_COLUMN = "Cntry";
		private static final String RECORD_FACILITY_ID_COLUMN = "Facility ID";
		private static final String RECORD_FILE_COLUMN = "File Number";
		private static final String RECORD_FILE_COLUMN_WL = "File/Ref Number";

		private String[] columnNamesNoWL = {
			RECORD_TYPE_COLUMN,
			RECORD_CALLSIGN_COLUMN,
			RECORD_CHANNEL_COLUMN,
			RECORD_SERVICE_COLUMN,
			RECORD_STATUS_COLUMN,
			RECORD_CITY_COLUMN,
			RECORD_STATE_COLUMN,
			RECORD_COUNTRY_COLUMN,
			RECORD_FACILITY_ID_COLUMN,
			RECORD_FILE_COLUMN
		};

		private String[] columnNamesWL = {
			RECORD_TYPE_COLUMN,
			RECORD_CALLSIGN_COLUMN_WL,
			RECORD_CHANNEL_COLUMN,
			RECORD_SERVICE_COLUMN,
			RECORD_STATUS_COLUMN,
			RECORD_CITY_COLUMN,
			RECORD_STATE_COLUMN,
			RECORD_COUNTRY_COLUMN,
			RECORD_FACILITY_ID_COLUMN,
			RECORD_FILE_COLUMN_WL
		};

		private String[] columnNames;

		private static final int RECORD_TYPE_INDEX = 0;
		private static final int RECORD_CALLSIGN_INDEX = 1;
		private static final int RECORD_CHANNEL_INDEX = 2;
		private static final int RECORD_SERVICE_INDEX = 3;
		private static final int RECORD_STATUS_INDEX = 4;
		private static final int RECORD_CITY_INDEX = 5;
		private static final int RECORD_STATE_INDEX = 6;
		private static final int RECORD_COUNTRY_INDEX = 7;
		private static final int RECORD_FACILITY_ID_INDEX = 8;
		private static final int RECORD_FILE_INDEX = 9;

		private JPanel panel;
		private ArrayList<RecordListItem> modelRows;

		private TableFilterPanel filterPanel;


		//-------------------------------------------------------------------------------------------------------------

		private RecordListTableModel(JPanel thePanel) {

			super();

			if (((0 == recordType) || (Source.RECORD_TYPE_WL == recordType)) &&
					((0 == studyType) || Study.isRecordTypeAllowed(studyType, Source.RECORD_TYPE_WL))) {
				columnNames = columnNamesWL;
			} else {
				columnNames = columnNamesNoWL;
			}

			panel = thePanel;
			modelRows = new ArrayList<RecordListItem>();

			filterPanel = new TableFilterPanel(outerThis, this);
		}


		//-------------------------------------------------------------------------------------------------------------
		// Custom sorting similar to the source listing table in the scenario editor.  Sort order is based on multiple
		// columns, some are sorted differently than natural ordering of display values so a custom string converter
		// is used.  Methods in the Record interface provide the formatting, see SourceEditData and ExtDbRecord.

		private JTable createTable() {

			JTable theTable = new JTable(this);
			AppController.configureTable(theTable);

			TableRowSorter<RecordListTableModel> theSorter = new TableRowSorter<RecordListTableModel>(this) {
				public void setSortKeys(java.util.List<? extends RowSorter.SortKey> sortKeys) {

					int columnIndex = RECORD_CALLSIGN_INDEX;
					SortOrder order = SortOrder.ASCENDING;
					if ((null != sortKeys) && (sortKeys.size() > 0)) {
						RowSorter.SortKey theKey = sortKeys.get(0);
						columnIndex = theKey.getColumn();
						order = theKey.getSortOrder();
					}

					ArrayList<RowSorter.SortKey> theKeys = new ArrayList<RowSorter.SortKey>();

					switch (columnIndex) {

						case RECORD_TYPE_INDEX:   // Record type (call sign, status, channel).
							theKeys.add(new RowSorter.SortKey(RECORD_TYPE_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_CALLSIGN_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_STATUS_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_CHANNEL_INDEX, order));
							break;

						case RECORD_CALLSIGN_INDEX:   // Call sign (status, channel).  Default.
						default:
							theKeys.add(new RowSorter.SortKey(RECORD_CALLSIGN_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_STATUS_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_CHANNEL_INDEX, order));
							break;

						case RECORD_CHANNEL_INDEX:   // Channel (country, state, city).
							theKeys.add(new RowSorter.SortKey(RECORD_CHANNEL_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_COUNTRY_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_STATE_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_CITY_INDEX, order));
							break;

						case RECORD_SERVICE_INDEX:   // Service (country, state, city, channel).
							theKeys.add(new RowSorter.SortKey(RECORD_SERVICE_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_COUNTRY_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_STATE_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_CITY_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_CHANNEL_INDEX, order));
							break;

						case RECORD_STATUS_INDEX:   // Status (country, state, city, channel).
							theKeys.add(new RowSorter.SortKey(RECORD_STATUS_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_COUNTRY_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_STATE_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_CITY_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_CHANNEL_INDEX, order));
							break;

						case RECORD_CITY_INDEX:   // City (state, channel).
							theKeys.add(new RowSorter.SortKey(RECORD_CITY_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_STATE_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_CHANNEL_INDEX, order));
							break;

						case RECORD_STATE_INDEX:   // State (city, channel).
							theKeys.add(new RowSorter.SortKey(RECORD_STATE_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_CITY_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_CHANNEL_INDEX, order));
							break;

						case RECORD_COUNTRY_INDEX:   // Country (state, city, channel).
							theKeys.add(new RowSorter.SortKey(RECORD_COUNTRY_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_STATE_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_CITY_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_CHANNEL_INDEX, order));
							break;

						case RECORD_FACILITY_ID_INDEX:   // Facility ID (status, channel).
							theKeys.add(new RowSorter.SortKey(RECORD_FACILITY_ID_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_STATUS_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_CHANNEL_INDEX, order));
							break;

						case RECORD_FILE_INDEX:   // File number, actually ARN (country, state, city, channel).
							theKeys.add(new RowSorter.SortKey(RECORD_FILE_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_COUNTRY_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_STATE_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_CITY_INDEX, order));
							theKeys.add(new RowSorter.SortKey(RECORD_CHANNEL_INDEX, order));
							break;
					}

					super.setSortKeys(theKeys);
				}
			};

			TableStringConverter theConverter = new TableStringConverter() {
				public String toString(TableModel theModel, int rowIndex, int columnIndex) {

					Record theRecord = modelRows.get(filterPanel.forwardIndex[rowIndex]).record;

					switch (columnIndex) {

						case RECORD_TYPE_INDEX:
							return theRecord.getRecordType();

						case RECORD_CALLSIGN_INDEX:
							return theRecord.getCallSign();

						case RECORD_CHANNEL_INDEX:
							return theRecord.getSortChannel();

						case RECORD_SERVICE_INDEX:
							return theRecord.getServiceCode();

						case RECORD_STATUS_INDEX:
							return theRecord.getSortStatus();

						case RECORD_CITY_INDEX:
							return theRecord.getCity();

						case RECORD_STATE_INDEX:
							return theRecord.getState();

						case RECORD_COUNTRY_INDEX:
							return theRecord.getSortCountry();

						case RECORD_FACILITY_ID_INDEX:
							return theRecord.getSortFacilityID();

						case RECORD_FILE_INDEX:
							return theRecord.getARN();
					}

					return "";
				}
			};

			theSorter.setStringConverter(theConverter);
			theTable.setRowSorter(theSorter);
			theSorter.setSortKeys(null);

			// Customize the cell renderer to change color on new/invalid records, and also set any comment text to
			// appear in tool-tip pop-up over the call sign field.

			DefaultTableCellRenderer theRend = new DefaultTableCellRenderer() {
				public Component getTableCellRendererComponent(JTable t, Object o, boolean s, boolean f, int r, int c) {

					JLabel comp = (JLabel)super.getTableCellRendererComponent(t, o, s, f, r, c);

					int row = t.convertRowIndexToModel(r);
					int col = t.convertColumnIndexToModel(c);

					RecordListItem theItem = modelRows.get(filterPanel.forwardIndex[row]);

					if (!s) {
						if (theItem.isNew) {
							if (theItem.isValid) {
								comp.setForeground(Color.GREEN.darker());
							} else {
								comp.setForeground(Color.RED);
							}
						} else {
							comp.setForeground(Color.BLACK);
						}
					}

					if ((RECORD_CALLSIGN_INDEX == col) && (null != theItem.comment) && (theItem.comment.length() > 0)) {
						if (!s) {
							comp.setForeground(Color.BLUE);
						}
						comp.setToolTipText(theItem.comment);
					} else {
						comp.setToolTipText(null);
					}

					return comp;
				}
			};

			TableColumnModel columnModel = theTable.getColumnModel();

			TableColumn theColumn = columnModel.getColumn(RECORD_TYPE_INDEX);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[4]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[4]);

			theColumn = columnModel.getColumn(RECORD_CALLSIGN_INDEX);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[5]);

			theColumn = columnModel.getColumn(RECORD_CHANNEL_INDEX);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[7]);

			theColumn = columnModel.getColumn(RECORD_SERVICE_INDEX);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[2]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[2]);

			theColumn = columnModel.getColumn(RECORD_STATUS_INDEX);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[4]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[4]);

			theColumn = columnModel.getColumn(RECORD_CITY_INDEX);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[8]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[14]);

			theColumn = columnModel.getColumn(RECORD_STATE_INDEX);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[2]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[2]);

			theColumn = columnModel.getColumn(RECORD_COUNTRY_INDEX);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[2]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[2]);

			theColumn = columnModel.getColumn(RECORD_FACILITY_ID_INDEX);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[5]);

			theColumn = columnModel.getColumn(RECORD_FILE_INDEX);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[8]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[16]);

			theTable.setPreferredScrollableViewportSize(new Dimension(theTable.getPreferredSize().width,
				(theTable.getRowHeight() * 5)));

			return theTable;
		}


		//-------------------------------------------------------------------------------------------------------------
		// Set records appearing in the table.  Existing new records may be preserved.  Open SourceEditor dialogs for
		// existing records are canceled, including those for new records if keepNew is false so that should not be
		// false unless the user has confirmed discarding unsaved state.  See windowShouldClose().  This has to be
		// done in two passes, carefully, since canceling dialogs will call editorClosing() which will modify the
		// model here.  Also canceling a dialog theoretically could fail, currently that is impossible but it has to
		// be supported.

		private boolean setItems(ArrayList<RecordListItem> newItems, boolean keepNew) {

			ArrayList<RecordListItem> keepItems = new ArrayList<RecordListItem>();
			for (RecordListItem theItem : new ArrayList<RecordListItem>(modelRows)) {
				if (theItem.isNew && !theItem.isImport && keepNew) {
					keepItems.add(theItem);
				} else {
					if (null != theItem.sourceEditor) {
						if (theItem.sourceEditor.isVisible() && !theItem.sourceEditor.cancel()) {
							AppController.beep();
							theItem.sourceEditor.toFront();
							return false;
						}
						theItem.sourceEditor = null;
					}
				}
			}

			modelRows.clear();

			if (null != newItems) {
				modelRows.addAll(newItems);
			}
			modelRows.addAll(keepItems);

			filterPanel.clearFilter();
			fireTableDataChanged();
			updateBorder();

			return true;
		}


		//-------------------------------------------------------------------------------------------------------------
		// Check if there is any unsaved data, that is new edited records that haven't been applied.  Note wasApplied
		// and isImport are both cleared if a new record is actually edited, even if it was previously applied.

		private boolean hasUnsavedData() {

			for (RecordListItem theItem : modelRows) {
				if (theItem.isNew && !theItem.isImport && !theItem.wasApplied) {
					return true;
				}
			}

			return false;
		}


		//-------------------------------------------------------------------------------------------------------------

		private void updateEditorDocumentNames() {

			for (RecordListItem theItem : modelRows) {
				if (null != theItem.sourceEditor) {
					theItem.sourceEditor.updateDocumentName();
				}
			}
		}


		//-------------------------------------------------------------------------------------------------------------
		// Adding a new record does not clear the filter state since here the only add is from a duplicate, the new
		// record should have the same filter state as the original which must be visible, so the new will be visible.
		// But don't assume that of course.

		private int add(RecordListItem theItem) {

			int modelIndex = modelRows.size();
			modelRows.add(theItem);

			filterPanel.updateFilter();
			int rowIndex = filterPanel.reverseIndex[modelIndex];
			if (rowIndex >= 0) {
				fireTableRowsInserted(rowIndex, rowIndex);
				updateBorder();
			}

			return rowIndex;
		}


		//-------------------------------------------------------------------------------------------------------------

		private RecordListItem get(int rowIndex) {

			return modelRows.get(filterPanel.forwardIndex[rowIndex]);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void remove(int rowIndex) {

			modelRows.remove(filterPanel.forwardIndex[rowIndex]);

			filterPanel.updateFilter();
			fireTableRowsDeleted(rowIndex, rowIndex);
			updateBorder();
		}


		//-------------------------------------------------------------------------------------------------------------
		// A changed item may mean a change to the filtered state so this may appear as an update or a delete.

		private void itemWasChanged(int rowIndex) {

			int modelIndex = filterPanel.forwardIndex[rowIndex];
			filterPanel.updateFilter();
			int newRowIndex = filterPanel.reverseIndex[modelIndex];
			if (newRowIndex >= 0) {
				fireTableRowsUpdated(newRowIndex, newRowIndex);
			} else {
				fireTableRowsDeleted(rowIndex, rowIndex);
				updateBorder();
			}
		}


		//-------------------------------------------------------------------------------------------------------------
		// Methods using unfiltered model index.

		private RecordListItem ufGet(int modelIndex) {

			return modelRows.get(modelIndex);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void ufRemove(int modelIndex) {

			modelRows.remove(modelIndex);

			int rowIndex = filterPanel.reverseIndex[modelIndex];
			filterPanel.updateFilter();
			if (rowIndex >= 0) {
				fireTableRowsDeleted(rowIndex, rowIndex);
				updateBorder();
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		private int ufIndexOfEditor(SourceEditor theEditor) {

			RecordListItem theItem;
			for (int modelIndex = 0; modelIndex < modelRows.size(); modelIndex++) {
				theItem = modelRows.get(modelIndex);
				if (theEditor == theItem.sourceEditor) {
					return modelIndex;
				}
			}

			return -1;
		}


		//-------------------------------------------------------------------------------------------------------------

		private void ufItemWasChanged(int modelIndex) {

			int rowIndex = filterPanel.reverseIndex[modelIndex];
			filterPanel.updateFilter();
			int newRowIndex = filterPanel.reverseIndex[modelIndex];
			if (rowIndex >= 0) {
				if (newRowIndex >= 0) {
					fireTableRowsUpdated(newRowIndex, newRowIndex);
				} else {
					fireTableRowsDeleted(rowIndex, rowIndex);
					updateBorder();
				}
			} else {
				if (newRowIndex >= 0) {
					fireTableRowsInserted(newRowIndex, newRowIndex);
					updateBorder();
				}
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		public void filterDidChange() {

			fireTableDataChanged();
			updateBorder();
		}


		//-------------------------------------------------------------------------------------------------------------

		private void updateBorder() {

			int n = filterPanel.forwardIndex.length;
			if (n > 0) {
				panel.setBorder(BorderFactory.createTitledBorder(String.valueOf(n) + " records"));
			} else {
				panel.setBorder(BorderFactory.createTitledBorder("Records"));
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

		public boolean filterByColumn(int columnIndex) {

			switch (columnIndex) {
				case RECORD_CALLSIGN_INDEX:
				case RECORD_CHANNEL_INDEX:
				case RECORD_SERVICE_INDEX:
				case RECORD_STATUS_INDEX:
				case RECORD_COUNTRY_INDEX: {
					return true;
				}
			}

			return false;
		}


		//-------------------------------------------------------------------------------------------------------------

		public boolean collapseFilterChoices(int columnIndex) {

			switch (columnIndex) {
				case RECORD_CALLSIGN_INDEX:
				case RECORD_CHANNEL_INDEX: {
					return true;
				}
			}

			return false;
		}


		//-------------------------------------------------------------------------------------------------------------

		public int getRowCount() {

			return filterPanel.forwardIndex.length;
		}


		//-------------------------------------------------------------------------------------------------------------

		public int getUnfilteredRowCount() {

			return modelRows.size();
		}


		//-------------------------------------------------------------------------------------------------------------

		public Object getValueAt(int rowIndex, int columnIndex) {

			return getUnfilteredValueAt(filterPanel.forwardIndex[rowIndex], columnIndex);
		}


		//-------------------------------------------------------------------------------------------------------------

		public String getUnfilteredValueAt(int rowIndex, int columnIndex) {

			Record theRecord = modelRows.get(rowIndex).record;

			switch (columnIndex) {

				case RECORD_TYPE_INDEX:
					return theRecord.getRecordType();

				case RECORD_CALLSIGN_INDEX:
					return theRecord.getCallSign();

				case RECORD_CHANNEL_INDEX:
					return theRecord.getChannel() + " " + theRecord.getFrequency();

				case RECORD_SERVICE_INDEX:
					return theRecord.getServiceCode();

				case RECORD_STATUS_INDEX:
					return theRecord.getStatus();

				case RECORD_CITY_INDEX:
					return theRecord.getCity();

				case RECORD_STATE_INDEX:
					return theRecord.getState();

				case RECORD_COUNTRY_INDEX:
					return theRecord.getCountryCode();

				case RECORD_FACILITY_ID_INDEX:
					return theRecord.getFacilityID();

				case RECORD_FILE_INDEX:
					return theRecord.getFileNumber();
			}

			return "";
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Show dialog with a text area for entering additional SQL code for the query, this is added to the WHERE clause
	// after all other criteria using AND.  When text is set the button label changes accordingly.  This used to be a
	// text area in the mainlayout but it took too much space for a rarely-used bit of UI.

	private void doAddSQL() {

		TextInputDialog theDialog = new TextInputDialog(outerThis, "Add SQL", "Additional SQL for WHERE clause");
		theDialog.setInput(additionalSQL);

		AppController.showWindow(theDialog);
		if (theDialog.canceled) {
			return;
		}

		additionalSQL = theDialog.getInput();
		if (0 == additionalSQL.length()) {
			addSQLButton.setText("Add SQL");
		} else {
			addSQLButton.setText("Edit SQL");
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Clear all input fields, except the record type and data set.  Also clear search results, optionally preserve
	// new records.  Theoretically could fail due to dependent dialogs not closing, see setItems() in the list model.

	private boolean doClear(boolean keepNew) {

		blockActionsStart();

		recordIDField.setText("");
		fileNumberField.setText("");

		facilityIDField.setText("");
		serviceMenu.setSelectedIndex(0);
		callSignField.setText("");
		channelField.setText("");
		statusMenu.setSelectedIndex(0);
		cityField.setText("");
		stateField.setText("");
		countryMenu.setSelectedIndex(0);
		includeArchivedCheckBox.setSelected(false);
		additionalSQL = "";
		addSQLButton.setText("Add SQL");

		boolean result = listModel.setItems(null, keepNew);

		updateControls();

		blockActionsEnd();

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Do the search.  First build the search query.  If the record ID or file number are set, all other criteria are
	// ignored as those are expected to match just one specific record.  For a file number search if archived records
	// are included there may be multiple matches, however those are historical versions of the same record and it is
	// assumed they would not vary with respect to other search criteria.  Otherwise a search is performed combining
	// all criteria with AND.  The search is always restricted by record type even for an ID or file number search,
	// also for TV records the search is always restricted by channel.

	private void doSearch() {

		errorReporter.clearTitle();

		int dbType = ExtDb.DB_TYPE_NOT_SET;
		int version = 0;
		boolean isSrc = true;
		if (null != extDb) {
			dbType = extDb.type;
			version = extDb.version;
			isSrc = extDb.isGeneric();
		}

		ArrayList<RecordListItem> searchResults = null;
		StringBuilder query = new StringBuilder();

		if (!useBaseline || (Source.RECORD_TYPE_TV != searchRecordType)) {

			try {

				boolean hasCrit = false, hasChannel = false;

				if (isSrc) {
					if (SourceEditData.addRecordIDQuery(dbType, recordIDField.getText().trim(), query, false)) {
						hasCrit = true;
					}
				} else {
					if (ExtDbRecord.addRecordIDQuery(dbType, version, recordIDField.getText().trim(), query, false)) {
						hasCrit = true;
					}
				}

				if (!hasCrit) {
					if (isSrc) {
						if (SourceEditData.addFileNumberQuery(dbType, fileNumberField.getText().trim(), query,
							false)) {
							hasCrit = true;
						}
					} else {
						if (ExtDbRecord.addFileNumberQuery(dbType, version, fileNumberField.getText().trim(), query,
								false)) {
							hasCrit = true;
						}
					}
				}

				if (!hasCrit) {

					if (isSrc) {
						if (SourceEditData.addFacilityIDQuery(dbType, facilityIDField.getText().trim(), query,
							false)) {
							hasCrit = true;
						}
					} else {
						if (ExtDbRecord.addFacilityIDQuery(dbType, version, facilityIDField.getText().trim(), query,
								false)) {
							hasCrit = true;
						}
					}

					int serviceKey = serviceMenu.getSelectedKey();
					if (serviceKey > 0) {
						if (isSrc) {
							if (SourceEditData.addServiceQuery(dbType, serviceKey, query, hasCrit)) {
								hasCrit = true;
							}
						} else {
							if (ExtDbRecord.addServiceQuery(dbType, version, serviceKey, query, hasCrit)) {
								hasCrit = true;
							}
						}
					}

					if (isSrc) {
						if (SourceEditData.addCallSignQuery(dbType, callSignField.getText().trim(), query, hasCrit)) {
							hasCrit = true;
						}
					} else {
						if (ExtDbRecord.addCallSignQuery(dbType, version, callSignField.getText().trim(), query,
								hasCrit)) {
							hasCrit = true;
						}
					}

					if (Source.RECORD_TYPE_TV == searchRecordType) {
						if (isSrc) {
							if (SourceEditData.addChannelQuery(dbType, channelField.getText().trim(), minimumTVChannel,
									maximumTVChannel, query, hasCrit)) {
								hasChannel = true;
								hasCrit = true;
							}
						} else {
							if (ExtDbRecordTV.addChannelQuery(dbType, version, channelField.getText().trim(),
									minimumTVChannel, maximumTVChannel, query, hasCrit)) {
								hasChannel = true;
								hasCrit = true;
							}
						}
					} else {
						if (Source.RECORD_TYPE_FM == searchRecordType) {
							if (isSrc) {
								if (SourceEditData.addChannelQuery(dbType, channelField.getText().trim(),
										SourceFM.CHANNEL_MIN, SourceFM.CHANNEL_MAX, query, hasCrit)) {
									hasChannel = true;
									hasCrit = true;
								}
							} else {
								if (ExtDbRecordFM.addChannelQuery(dbType, version, channelField.getText().trim(),
										SourceFM.CHANNEL_MIN, SourceFM.CHANNEL_MAX, query, hasCrit)) {
									hasChannel = true;
									hasCrit = true;
								}
							}
						}
					}

					int statusType = statusMenu.getSelectedKey();
					if (statusType >= 0) {
						if (isSrc) {
							if (SourceEditData.addStatusQuery(dbType, statusType, query, hasCrit)) {
								hasCrit = true;
							}
						} else {
							if (ExtDbRecord.addStatusQuery(dbType, version, statusType, query, hasCrit)) {
								hasCrit = true;
							}
						}
					}

					if (isSrc) {
						if (SourceEditData.addCityQuery(dbType, cityField.getText().trim(), query, hasCrit)) {
							hasCrit = true;
						}
					} else {
						if (ExtDbRecord.addCityQuery(dbType, version, cityField.getText().trim(), query, hasCrit)) {
							hasCrit = true;
						}
					}

					if (isSrc) {
						if (SourceEditData.addStateQuery(dbType, stateField.getText().trim(), query, hasCrit)) {
							hasCrit = true;
						}
					} else {
						if (ExtDbRecord.addStateQuery(dbType, version, stateField.getText().trim(), query, hasCrit)) {
							hasCrit = true;
						}
					}

					int countryKey = countryMenu.getSelectedKey();
					if (countryKey > 0) {
						if (isSrc) {
							if (SourceEditData.addCountryQuery(dbType, countryKey, query, hasCrit)) {
								hasCrit = true;
							}
						} else {
							if (ExtDbRecord.addCountryQuery(dbType, version, countryKey, query, hasCrit)) {
								hasCrit = true;
							}
						}
					}

					// Add any additional SQL with AND.

					if (additionalSQL.length() > 0) {
						if (hasCrit) {
							query.append(" AND ");
						}
						query.append('(');
						query.append(additionalSQL);
						query.append(')');
						hasCrit = true;
					}
				}

				// A TV search is always restricted by channel, if a specific one was not entered use full range.

				if ((Source.RECORD_TYPE_TV == searchRecordType) && !hasChannel) {
					if (isSrc) {
						if (SourceEditData.addChannelRangeQuery(dbType, minimumTVChannel, maximumTVChannel, query,
								hasCrit)) {
							hasCrit = true;
						}
					} else {
						if (ExtDbRecordTV.addChannelRangeQuery(dbType, version, minimumTVChannel, maximumTVChannel,
								query, hasCrit)) {
							hasCrit = true;
						}
					}
				}

				// Add record type clause, generally this will include only current and pending records, but
				// optionally may include archived records as well.  There is no record type concept in the user
				// record table or generic import data sets so skip this in those cases.

				if (!isSrc) {
					if (ExtDbRecord.addRecordTypeQuery(dbType, version, true, includeArchivedCheckBox.isSelected(),
							query, hasCrit)) {
						hasCrit = true;
					}
				}

			} catch (IllegalArgumentException ie) {
				errorReporter.reportError(ie.getMessage());
				return;
			}

			// Do the search.

			final String theQuery = query.toString();

			BackgroundWorker<ArrayList<RecordListItem>> theWorker =
					new BackgroundWorker<ArrayList<RecordListItem>>(getWindow(), getTitle()) {
				protected ArrayList<RecordListItem> doBackgroundWork(ErrorLogger errors) {

					ArrayList<RecordListItem> results = new ArrayList<RecordListItem>();
					RecordListItem theItem;

					int dbType = ExtDb.DB_TYPE_NOT_SET;
					boolean isSrc = true;
					if (null != extDb) {
						dbType = extDb.type;
						isSrc = extDb.isGeneric();
					}

					if (isSrc) {

						java.util.List<SourceEditData> sources = null;

						boolean isUsr = false;
						if (ExtDb.DB_TYPE_NOT_SET == dbType) {
							sources = SourceEditData.findUserRecords(getDbID(), searchRecordType, theQuery, errors);
							isUsr = true;
						} else {
							sources = SourceEditData.findImportRecords(extDb, theQuery, errors);
						}

						if (null == sources) {
							return null;
						}

						for (SourceEditData theSource : sources) {

							theItem = new RecordListItem();
							theItem.record = theSource;
							theItem.isSource = true;
							theItem.isUserRecord = isUsr;
							theItem.isValid = true;

							theItem.comment = theSource.makeCommentText();

							results.add(theItem);
						}

					} else {

						LinkedList<ExtDbRecord> records = ExtDbRecord.findRecords(extDb, theQuery, errors);
						if (null == records) {
							return null;
						}

						for (ExtDbRecord theRecord : records) {

							theItem = new RecordListItem();
							theItem.record = theRecord;
							theItem.isValid = true;

							theItem.comment = theRecord.makeCommentText();

							results.add(theItem);
						}
					}

					return results;
				}
			};

			errorReporter.clearMessages();

			searchResults = theWorker.runWork("Searching for records, please wait...", errorReporter);
			if (null == searchResults) {
				return;
			}

		} else {

			// A TV baseline record search is handled by separate query-building methods.  Facility ID here is expected
			// single match, that is the unique identifier in the baseline tables.

			try {

				if (!ExtDbRecordTV.addBaselineRecordIDQuery(dbType, version, recordIDField.getText().trim(), query,
							false) &&
						!ExtDbRecordTV.addBaselineFacilityIDQuery(dbType, version, facilityIDField.getText().trim(),
							query, false)) {

					boolean hasCrit = false;

					int serviceKey = serviceMenu.getSelectedKey();
					if (serviceKey > 0) {
						if (ExtDbRecordTV.addBaselineServiceQuery(dbType, version, serviceKey, query, hasCrit)) {
							hasCrit = true;
						}
					}

					if (ExtDbRecordTV.addBaselineCallSignQuery(dbType, version, callSignField.getText().trim(), query,
							hasCrit)) {
						hasCrit = true;
					}

					boolean hasChannel = false;
					if (ExtDbRecordTV.addBaselineChannelQuery(dbType, version, channelField.getText().trim(),
							minimumTVChannel, maximumTVChannel, query, hasCrit)) {
						hasChannel = true;
						hasCrit = true;
					}

					if (ExtDbRecordTV.addBaselineCityQuery(dbType, version, cityField.getText().trim(), query,
							hasCrit)) {
						hasCrit = true;
					}

					if (ExtDbRecordTV.addBaselineStateQuery(dbType, version, stateField.getText().trim(), query,
							hasCrit)) {
						hasCrit = true;
					}

					int countryKey = countryMenu.getSelectedKey();
					if (countryKey > 0) {
						if (ExtDbRecordTV.addBaselineCountryQuery(dbType, version, countryKey, query, hasCrit)) {
							hasCrit = true;
						}
					}

					if (additionalSQL.length() > 0) {
						if (hasCrit) {
							query.append(" AND ");
						}
						query.append('(');
						query.append(additionalSQL);
						query.append(')');
						hasCrit = true;
					}

					if (!hasChannel) {
						if (ExtDbRecordTV.addBaselineChannelRangeQuery(dbType, version, minimumTVChannel,
								maximumTVChannel, query, hasCrit)) {
							hasCrit = true;
						}
					}
				}

			} catch (IllegalArgumentException ie) {
				errorReporter.reportError(ie.getMessage());
				return;
			}

			final String theQuery = query.toString();

			BackgroundWorker<ArrayList<RecordListItem>> theWorker =
					new BackgroundWorker<ArrayList<RecordListItem>>(getWindow(), getTitle()) {
				protected ArrayList<RecordListItem> doBackgroundWork(ErrorLogger errors) {

					ArrayList<RecordListItem> results = new ArrayList<RecordListItem>();
					RecordListItem theItem;

					LinkedList<ExtDbRecordTV> records = ExtDbRecordTV.findBaselineRecords(extDb, theQuery, errors);
					if (null == records) {
						return null;
					}

					for (ExtDbRecordTV theRecord : records) {

						theItem = new RecordListItem();
						theItem.record = theRecord;
						theItem.isValid = true;

						theItem.comment = theRecord.makeCommentText();

						results.add(theItem);
					}

					return results;
				}
			};

			errorReporter.clearMessages();

			searchResults = theWorker.runWork("Searching for records, please wait...", errorReporter);
			if (null == searchResults) {
				return;
			}
		}

		if (searchResults.size() > 0) {

			if (!listModel.setItems(searchResults, true)) {
				return;
			}

			errorReporter.showMessages();

			listTable.setRowSelectionInterval(0, 0);
			listTable.scrollRectToVisible(listTable.getCellRect(0, 0, true));

		} else {

			if (!listModel.setItems(null, true)) {
				return;
			}

			errorReporter.reportMessage("No matching records found.");
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update the state of various controls after a table selection change.

	private void updateControls() {

		if (!canViewEdit) {
			return;
		}

		if (null == selectedItem) {

			duplicateButton.setEnabled(false);
			duplicateMenuItem.setEnabled(false);
			exportButton.setEnabled(false);
			exportMenuItem.setEnabled(false);
			saveButton.setEnabled(false);
			saveMenuItem.setEnabled(false);
			openButton.setText("View");
			openButton.setEnabled(false);
			openMenuItem.setText("View");
			openMenuItem.setEnabled(false);
			if (showUserDelete) {
				deleteButton.setEnabled(false);
				deleteMenuItem.setEnabled(false);
			}

		} else {

			// A new record is temporarily invalid if it has an open source editor.

			boolean isValid = (selectedItem.isValid && (!selectedItem.isNew || (null == selectedItem.sourceEditor)));

			duplicateButton.setEnabled(isValid);
			duplicateMenuItem.setEnabled(isValid);
			exportButton.setEnabled(isValid);
			exportMenuItem.setEnabled(isValid);

			// Only new records can be saved as user records.  It may or may not be possible to apply a new record.

			if (selectedItem.isNew) {
				saveButton.setEnabled(isValid);
				saveMenuItem.setEnabled(isValid);
				openButton.setText("Edit");
				openMenuItem.setText("Edit");
			} else {
				saveButton.setEnabled(false);
				saveMenuItem.setEnabled(false);
				openButton.setText("View");
				openMenuItem.setText("View");
			}
			openButton.setEnabled(true);
			openMenuItem.setEnabled(true);
			if (showUserDelete) {
				deleteButton.setEnabled(selectedItem.isUserRecord);
				deleteMenuItem.setEnabled(selectedItem.isUserRecord);
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doPrevious() {

		int rowCount = listTable.getRowCount();
		int rowIndex = listTable.getSelectedRow();
		if ((rowCount > 0) && (rowIndex != 0)) {
			if (rowIndex < 0) {
				rowIndex = rowCount - 1;
			} else {
				rowIndex--;
			}
			listTable.setRowSelectionInterval(rowIndex, rowIndex);
			listTable.scrollRectToVisible(listTable.getCellRect(rowIndex, 0, true));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doNext() {

		int rowCount = listTable.getRowCount();
		int rowIndex = listTable.getSelectedRow();
		if ((rowCount > 0) && (rowIndex < (rowCount - 1))) {
			if (rowIndex < 0) {
				rowIndex = 0;
			} else {
				rowIndex++;
			}
			listTable.setRowSelectionInterval(rowIndex, rowIndex);
			listTable.scrollRectToVisible(listTable.getCellRect(rowIndex, 0, true));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// View or edit the selected record.  Even with a new source the editor is told to derive another new record so
	// all properties including facility ID etc. are still editable.  A user record is just viewed, an ExtDbRecord is
	// converted temporarily to a locked source and viewed.

	private void doOpen() {

		if (!canViewEdit || (null == selectedItem)) {
			return;
		}

		if (null != selectedItem.sourceEditor) {
			if (selectedItem.sourceEditor.isVisible()) {
				selectedItem.sourceEditor.toFront();
				return;
			}
			selectedItem.sourceEditor = null;
		}

		if (selectedItem.isNew) {
			errorReporter.setTitle("Edit Record");
		} else {
			errorReporter.setTitle("View Record");
		}
		errorReporter.clearMessages();

		SourceEditData theSource = null;
		String theNote = null;
		if (selectedItem.isSource) {
			theSource = (SourceEditData)(selectedItem.record);
		} else {
			theSource = SourceEditData.makeSource((ExtDbRecord)(selectedItem.record), null, true, errorReporter);
			if (null == theSource) {
				return;
			}
			if (Source.RECORD_TYPE_TV == theSource.recordType) {
				ExtDbRecordTV theRecord = (ExtDbRecordTV)(selectedItem.record);
				if (theRecord.replicateToChannel > 0) {
					theNote = "will replicate to D" + theRecord.replicateToChannel;
				}
			}
		}

		errorReporter.showMessages();

		SourceEditor theEditor = new SourceEditor(this);
		theEditor.setChannelNote(theNote);
		if (!theEditor.setSource(theSource, selectedItem.isNew, errorReporter)) {
			return;
		}

		AppController.showWindow(theEditor);

		selectedItem.sourceEditor = theEditor;

		listModel.itemWasChanged(selectedItemIndex);

		updateControls();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create an entirely new source record, immediately open a SourceEditor for the record.  The blank new record is
	// added to the list, but will be removed again in editorClosing() if still invalid meaning the edit was canceled.

	private void doNew(int theRecordType) {

		if (!canViewEdit) {
			return;
		}

		errorReporter.setTitle("Create New Record");

		SourceEditData newSource = null;
		switch (theRecordType) {
			case Source.RECORD_TYPE_TV: {
				newSource = SourceEditDataTV.createSource(null, getDbID(), 0, Service.getInvalidObject(), false,
					Country.getInvalidObject(), false, errorReporter);
				break;
			}
			case Source.RECORD_TYPE_WL: {
				newSource = SourceEditDataWL.createSource(null, getDbID(), Service.getInvalidObject(),
					Country.getInvalidObject(), false, errorReporter);
				break;
			}
			case Source.RECORD_TYPE_FM: {
				newSource = SourceEditDataFM.createSource(null, getDbID(), 0, Service.getInvalidObject(), 0,
					Country.getInvalidObject(), false, errorReporter);
				break;
			}
		}
		if (null == newSource) {
			return;
		}

		SourceEditor theEditor = new SourceEditor(this);
		if (!theEditor.setSource(newSource, true, errorReporter)) {
			return;
		}

		AppController.showWindow(theEditor);

		RecordListItem newItem = new RecordListItem();
		newItem.record = newSource;
		newItem.isSource = true;
		newItem.isNew = true;
		newItem.sourceEditor = theEditor;

		int rowIndex = listModel.add(newItem);
		if (rowIndex >= 0) {
			rowIndex = listTable.convertRowIndexToView(rowIndex);
			listTable.setRowSelectionInterval(rowIndex, rowIndex);
			listTable.scrollRectToVisible(listTable.getCellRect(rowIndex, 0, true));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create a new record by duplicating the selected record.  The selected record must be valid and if new not have
	// an open source editor.  If a source is selected derive a new one, if an ExtDbRecord make a source from that.
	// Presumably the user wants to make changes to the duplicate so immediately open a source editor.  That also means
	// if the user cancels the editor they will not expect the duplicate to remain in the list, so the duplicate is
	// flagged invalid even though it isn't to trigger that behavior in editorClosing().

	private void doDuplicate() {

		if (!canViewEdit || (null == selectedItem) || !selectedItem.isValid ||
				(selectedItem.isNew && (null != selectedItem.sourceEditor))) {
			return;
		}

		errorReporter.setTitle("Duplicate Record");

		errorReporter.clearMessages();

		SourceEditData newSource = null;
		if (selectedItem.isSource) {
			newSource = ((SourceEditData)(selectedItem.record)).deriveSource(null, false, errorReporter);
		} else {
			newSource = SourceEditData.makeSource((ExtDbRecord)(selectedItem.record), null, false, errorReporter);
		}
		if (null == newSource) {
			return;
		}

		errorReporter.showMessages();

		SourceEditor theEditor = new SourceEditor(this);
		if (!theEditor.setSource(newSource, true, errorReporter)) {
			return;
		}

		AppController.showWindow(theEditor);

		RecordListItem newItem = new RecordListItem();
		newItem.record = newSource;
		newItem.isSource = true;
		newItem.isNew = true;
		newItem.isValid = false;
		newItem.sourceEditor = theEditor;

		int rowIndex = listModel.add(newItem);
		if (rowIndex >= 0) {
			rowIndex = listTable.convertRowIndexToView(rowIndex);
			listTable.setRowSelectionInterval(rowIndex, rowIndex);
			listTable.scrollRectToVisible(listTable.getCellRect(rowIndex, 0, true));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Import a new record from an XML file.  The XML parser fully checks validity so the result is valid.

	private void doImport() {

		if (!canViewEdit) {
			return;
		}

		String title = "Import Records";
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

		// Prompt the user to select a station data set to be used to resolve record references in the imported data.
		// On FM and TV sets can be used, according to the record type and study type restrictions if any; wireless
		// records are never by-reference.

		ArrayList<KeyedRecord> list = new ArrayList<KeyedRecord>(), addList = null;

		if (((0 == recordType) || (Source.RECORD_TYPE_TV == recordType)) &&
				((0 == studyType) || Study.isRecordTypeAllowed(studyType, Source.RECORD_TYPE_TV))) {
			addList = ExtDb.getExtDbList(getDbID(), Source.RECORD_TYPE_TV, errorReporter);
			if (null == addList) {
				return;
			}
			list.addAll(addList);
		}

		if (((0 == recordType) || (Source.RECORD_TYPE_FM == recordType)) &&
				((0 == studyType) || Study.isRecordTypeAllowed(studyType, Source.RECORD_TYPE_FM))) {
			addList = ExtDb.getExtDbList(getDbID(), Source.RECORD_TYPE_FM, errorReporter);
			if (null == addList) {
				return;
			}
			list.addAll(addList);
		}

		PickExtDbDialog promptDialog = new PickExtDbDialog(this, title, list, defaultExtDbKey);
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

		// Open the file, do the import.

		FileReader theReader = null;
		try {
			theReader = new FileReader(theFile);
		} catch (FileNotFoundException fnfe) {
			errorReporter.reportError("Could not open the file:\n" + fnfe.getMessage());
			return;
		}

		final BufferedReader xml = new BufferedReader(theReader);

		BackgroundWorker<ArrayList<SourceEditData>> theWorker =
				new BackgroundWorker<ArrayList<SourceEditData>>(getWindow(), title) {
			protected ArrayList<SourceEditData> doBackgroundWork(ErrorLogger errors) {
				return SourceEditData.readSourcesFromXML(getDbID(), xml, lookupExtDbKey, alternateExtDbKey, recordType,
					studyType, errors);
			}
		};

		errorReporter.clearMessages();

		ArrayList<SourceEditData> newSources = theWorker.runWork("Importing records, please wait...", errorReporter);

		try {xml.close();} catch (IOException ie) {}

		if (null == newSources) {
			return;
		}

		errorReporter.showMessages();

		ArrayList<RecordListItem> newItems = new ArrayList<RecordListItem>();
		RecordListItem newItem;

		for (SourceEditData newSource : newSources) {

			newItem = new RecordListItem();
			newItem.record = newSource;
			newItem.isSource = true;
			newItem.isNew = !newSource.isLocked;
			newItem.isImport = newItem.isNew;
			newItem.isValid = true;

			newItems.add(newItem);
		}

		listModel.setItems(newItems, true);

		listTable.setRowSelectionInterval(0, 0);
		listTable.scrollRectToVisible(listTable.getCellRect(0, 0, true));

		errorReporter.reportMessage(String.valueOf(newSources.size()) + " records imported.");
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Export the selected record to an XML file.  It must be valid, and if new, must not have an open editor.

	private void doExport() {

		if (!canViewEdit || (null == selectedItem) || !selectedItem.isValid ||
				(selectedItem.isNew && (null != selectedItem.sourceEditor))) {
			return;
		}

		String title = "Export Record";
		errorReporter.setTitle(title);

		errorReporter.clearMessages();

		SourceEditData theSource = null;
		if (selectedItem.isSource) {
			theSource = (SourceEditData)(selectedItem.record);
		} else {
			theSource = SourceEditData.makeSource((ExtDbRecord)(selectedItem.record), null, true, errorReporter);
			if (null == theSource) {
				return;
			}
		}

		errorReporter.showMessages();

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

		try {
			theSource.writeToXML(xml, errorReporter);
		} catch (IOException ie) {
			errorReporter.reportError(ie.toString());
		}

		try {xml.close();} catch (IOException ie) {}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Save a new record as a user record, the selected record must be valid, new, and not have an open editor.  The
	// newly-created SourceEditData object representing the user record replaces the existing one in the list.  Also
	// prompt for entry of comment text for the user record.

	private void doSave() {

		if (!canViewEdit || (null == selectedItem) || !selectedItem.isValid || !selectedItem.isNew ||
				(null != selectedItem.sourceEditor)) {
			return;
		}

		String title = "Save User Record";
		errorReporter.setTitle(title);

		SourceEditData theSource = (SourceEditData)selectedItem.record;
		final java.util.Date originalDate = AppCore.parseDate(theSource.getAttribute(Source.ATTR_SEQUENCE_DATE));
		final boolean originalSharedFlag = (null != theSource.getAttribute(Source.ATTR_IS_SHARING_HOST));

		TextInputDialog theDialog = new TextInputDialog(outerThis, title, "Comment");

		final DateSelectionPanel sequenceDatePanel = new DateSelectionPanel(theDialog, "Sequence date", false);
		sequenceDatePanel.setFutureAllowed(true);

		final JCheckBox sharingHostCheckBox = new JCheckBox("Shared channel");

		JPanel topPanel = new JPanel();
		topPanel.add(sequenceDatePanel);
		topPanel.add(sharingHostCheckBox);

		if ((null != originalDate) || originalSharedFlag) {
			JButton useOrigButton = new JButton("Set from original");
			useOrigButton.setFocusable(false);
			useOrigButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					sequenceDatePanel.setDate(originalDate);
					sharingHostCheckBox.setSelected(originalSharedFlag);
				}
			});
			topPanel.add(useOrigButton);
		}

		theDialog.add(topPanel, BorderLayout.NORTH);

		theDialog.pack();

		AppController.showWindow(theDialog);
		if (theDialog.canceled) {
			return;
		}

		String theComment = theDialog.getInput();

		if (sequenceDatePanel.isDateChanged()) {
			java.util.Date theDate = sequenceDatePanel.getDate();
			if (null == theDate) {
				theSource.removeAttribute(Source.ATTR_SEQUENCE_DATE);
			} else {
				theSource.setAttribute(Source.ATTR_SEQUENCE_DATE, AppCore.formatDate(theDate));
			}
		}

		if (sharingHostCheckBox.isSelected()) {
			theSource.setAttribute(Source.ATTR_IS_SHARING_HOST);
		} else {
			theSource.removeAttribute(Source.ATTR_IS_SHARING_HOST);
		}

		SourceEditData newSource = theSource.saveAsUserRecord(errorReporter);
		if (null == newSource) {
			return;
		}

		if (theComment.length() > 0) {
			SourceEditData.setSourceComment(newSource, theComment);
		} else {
			theComment = null;
		}

		selectedItem.record = newSource;
		selectedItem.isUserRecord = true;
		selectedItem.isNew = false;
		selectedItem.isImport = false;

		selectedItem.comment = newSource.makeCommentText();

		listModel.itemWasChanged(selectedItemIndex);

		updateControls();
		if (null != callBack) {
			callBack.run();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Delete a user record.

	private void doDelete() {

		if (!canViewEdit || !showUserDelete || (null == selectedItem) || !selectedItem.isUserRecord) {
			return;
		}

		String title = "Delete User Record";
		errorReporter.setTitle(title);

		if (null != selectedItem.sourceEditor) {
			if (selectedItem.sourceEditor.isVisible() && !selectedItem.sourceEditor.cancel()) {
				AppController.beep();
				selectedItem.sourceEditor.toFront();
				return;
			}
			selectedItem.sourceEditor = null;
		}

		SourceEditData theSource = (SourceEditData)selectedItem.record;
		if (SourceEditData.deleteUserRecord(theSource.dbID, theSource.userRecordID, errorReporter)) {
			listModel.remove(selectedItemIndex);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by source editors when edits are saved, data is assumed valid.

	public boolean applyEditsFrom(AppEditor theEditor) {

		if (theEditor instanceof SourceEditor) {

			SourceEditor sourceEditor = (SourceEditor)theEditor;

			int rowIndex = listModel.ufIndexOfEditor(sourceEditor);
			if (rowIndex < 0) {
				return false;
			}

			RecordListItem theItem = listModel.ufGet(rowIndex);

			theItem.record = sourceEditor.getSource();
			theItem.isImport = false;
			theItem.wasApplied = false;
			theItem.isValid = true;

			listModel.ufItemWasChanged(rowIndex);

			return true;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// When an editor closes, if the record being edited is still invalid that means it was a completely new record
	// and editing was canceled; in that case remove the record from the list.

	public void editorClosing(AppEditor theEditor) {

		if (theEditor instanceof SourceEditor) {

			SourceEditor sourceEditor = (SourceEditor)theEditor;

			int rowIndex = listModel.ufIndexOfEditor(sourceEditor);
			if (rowIndex < 0) {
				return;
			}

			RecordListItem theItem = listModel.ufGet(rowIndex);
			theItem.sourceEditor = null;

			if (!theItem.isValid) {
				listModel.ufRemove(rowIndex);
			} else {
				listModel.ufItemWasChanged(rowIndex);
			}

			updateControls();
			if (null != callBack) {
				callBack.run();
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void clearFields() {

		doClear(true);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Re-populate the record type and data set menu when showing the dialog.

	public void windowWillOpen() {

		extDbMenu.removeAllItems();
		extDb = null;
		if (recordType > 0) {
			searchRecordType = recordType;
		} else {
			if (studyType > 0) {
				searchRecordType = Study.getDefaultRecordType(studyType);
			} else {
				searchRecordType = Source.RECORD_TYPE_TV;
			}
		}

		callSignField.requestFocusInWindow();

		SwingUtilities.invokeLater(new Runnable() {
			public void run() {

				errorReporter.setTitle("Load Station Data List");

				ArrayList<KeyedRecord> list = new ArrayList<KeyedRecord>(), addList = null;

				// The user records item for each type is added before the first actual data set in the list, which
				// puts it just after data sets with keys in the reserved range which represent special functions like
				// most-recent or live servers; getExtDbList() always puts those at the top.

				if (((0 == recordType) || (Source.RECORD_TYPE_TV == recordType)) &&
						((0 == studyType) || Study.isRecordTypeAllowed(studyType, Source.RECORD_TYPE_TV))) {
					boolean doUser = true;
					if (!userRecordsOnly) {
						addList = ExtDb.getExtDbList(getDbID(), Source.RECORD_TYPE_TV, errorReporter);
						if (null != addList) {
							for (KeyedRecord theDb : addList) {
								if (doUser && ((theDb.key < ExtDb.RESERVED_KEY_RANGE_START) ||
										(theDb.key > ExtDb.RESERVED_KEY_RANGE_END))) {
									list.add(new KeyedRecord(-Source.RECORD_TYPE_TV, "TV user records"));
									doUser = false;
								}
								list.add(theDb);
							}
						}
					}
					if (doUser) {
						list.add(new KeyedRecord(-Source.RECORD_TYPE_TV, "TV user records"));
					}
				}

				if (((0 == recordType) || (Source.RECORD_TYPE_WL == recordType)) &&
						((0 == studyType) || Study.isRecordTypeAllowed(studyType, Source.RECORD_TYPE_WL))) {
					boolean doUser = true;
					if (!userRecordsOnly) {
						addList = ExtDb.getExtDbList(getDbID(), Source.RECORD_TYPE_WL, errorReporter);
						if (null != addList) {
							for (KeyedRecord theDb : addList) {
								if (doUser && ((theDb.key < ExtDb.RESERVED_KEY_RANGE_START) ||
										(theDb.key > ExtDb.RESERVED_KEY_RANGE_END))) {
									list.add(new KeyedRecord(-Source.RECORD_TYPE_WL, "Wireless user records"));
									doUser = false;
								}
								list.add(theDb);
							}
						}
					}
					if (doUser) {
						list.add(new KeyedRecord(-Source.RECORD_TYPE_WL, "Wireless user records"));
					}
				}

				if (((0 == recordType) || (Source.RECORD_TYPE_FM == recordType)) &&
						((0 == studyType) || Study.isRecordTypeAllowed(studyType, Source.RECORD_TYPE_FM))) {
					boolean doUser = true;
					if (!userRecordsOnly) {
						addList = ExtDb.getExtDbList(getDbID(), Source.RECORD_TYPE_FM, errorReporter);
						if (null != addList) {
							for (KeyedRecord theDb : addList) {
								if (doUser && ((theDb.key < ExtDb.RESERVED_KEY_RANGE_START) ||
										(theDb.key > ExtDb.RESERVED_KEY_RANGE_END))) {
									list.add(new KeyedRecord(-Source.RECORD_TYPE_FM, "FM user records"));
									doUser = false;
								}
								list.add(theDb);
							}
						}
					}
					if (doUser) {
						list.add(new KeyedRecord(-Source.RECORD_TYPE_FM, "FM user records"));
					}
				}

				blockActionsStart();

				extDbMenu.addAllItems(list);
				if (null != defaultExtDbKey) {
					int defKey = defaultExtDbKey.intValue();
					if (extDbMenu.containsKey(defKey)) {
						extDbMenu.setSelectedKey(defKey);
					}
				}

				updateUI(true);

				doClear(false);

				blockActionsEnd();
			}
		});
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check if there is any unsaved data, that is new records not saved as user records or open editors for new
	// records, if so confirm the user wants to discard those.  Called when the presenting window is being closed.

	public boolean windowShouldClose() {

		if (listModel.hasUnsavedData()) {
			AppController.beep();
			if (JOptionPane.YES_OPTION != JOptionPane.showConfirmDialog(this,
					"New records were edited but have not been saved.  Unsaved\n" +
					"records will be discarded when this window is closed.\n\n" +
					"Are you sure you want to continue?", "Confirm Close",
					JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)) {
				return false;
			}
		}

		if (!doClear(false)) {
			return false;
		}

		return true;
	}
}
