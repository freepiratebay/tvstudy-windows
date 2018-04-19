//
//  ScenarioEditor.java
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui.editor;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.editdata.*;
import gov.fcc.tvstudy.core.geo.*;
import gov.fcc.tvstudy.gui.*;

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
// Scenario editor, allows adding and removing sources in the scenario, and editing sources when permitted.  This is
// a secondary UI window that always belongs to a parent StudyEditor, which is responsible for loading and saving data.
// This is constructed with a specific ScenarioEditData object and can only edit that object.  There is no "editing
// canceled" behavior here.

// UI labelling has been changed vs. the code and database design, "Source" is now labeled "Station" or "Record".

public class ScenarioEditor extends AppFrame {

	public static final String WINDOW_TITLE = "Scenario";

	private ScenarioEditData scenario;
	private int studyType;

	// UI fields.

	private JTextField scenarioNameField;
	private JTextArea scenarioDescriptionArea;

	private ArrayList<ParameterEditor> parameterEditors;

	private SourceTableModel sourceModel;
	private JTable sourceTable;

	// Buttons and menu items.

	private JButton addSourceButton;
	private JButton openSourceButton;
	private JButton removeSourceButton;

	private JMenuItem addSourceMenuItem;
	private JMenuItem addSourcesMenuItem;
	private JMenuItem openSourceMenuItem;
	private JMenuItem removeSourceMenuItem;

	private JMenuItem replicateSourceMenuItem;
	private JMenuItem unlockSourceMenuItem;
	private JMenuItem revertSourceMenuItem;
	private JMenuItem exportSourceMenuItem;

	private JMenuItem setDesiredMenuItem;
	private JMenuItem clearDesiredMenuItem;
	private JMenuItem setUndesiredMenuItem;
	private JMenuItem clearUndesiredMenuItem;

	private JComboBox<ExtDbSearch> addSourcesMenu;

	// Dependent dialogs.  May be SourceEditors open for any/many sources in the list, indexed by sourceKey.

	private HashMap<Integer, SourceEditor> sourceEditors;

	private RecordFind addSourceFinder;
	private ExtDbSearchDialog addSourcesDialog;

	// Disambiguation.

	private ScenarioEditor outerThis = this;


	//-----------------------------------------------------------------------------------------------------------------

	public ScenarioEditor(AppEditor theParent, ScenarioEditData theScenario) {

		super(theParent, WINDOW_TITLE);

		scenario = theScenario;
		studyType = scenario.study.study.studyType;

		// See also the override of getKeyTitle().

		setTitleKey(scenario.key.intValue());

		// Create UI components, the scenario name first.

		scenarioNameField = new JTextField(30);
		AppController.fixKeyBindings(scenarioNameField);

		scenarioNameField.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					String newName = scenarioNameField.getText().trim();
					if ((newName.length() > 0) && !scenario.name.equals(newName)) {
						boolean changeOK = false;
						if (scenario.name.equalsIgnoreCase(newName)) {
							changeOK = true;
						} else {
							errorReporter.setTitle("Change Scenario Name");
							changeOK = DbCore.checkScenarioName(newName, scenario.study, errorReporter);
						}
						if (changeOK) {
							scenario.name = newName;
							updateDocumentName();
							parent.applyEditsFrom(outerThis);
						}
					}
					blockActionsEnd();
				}
				scenarioNameField.setText(scenario.name);
			}
		});
		scenarioNameField.addFocusListener(new FocusAdapter() {
			public void focusGained(FocusEvent theEvent) {
				setCurrentField(scenarioNameField);
			}
			public void focusLost(FocusEvent theEvent) {
				if (!theEvent.isTemporary()) {
					scenarioNameField.postActionEvent();
				}
			}
		});

		JPanel innerNamePanel = new JPanel();
		innerNamePanel.setBorder(BorderFactory.createTitledBorder("Scenario Name"));
		innerNamePanel.add(scenarioNameField);

		JPanel namePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		namePanel.add(innerNamePanel);

		scenarioNameField.setText(scenario.name);

		// Scenario description.

		scenarioDescriptionArea = new JTextArea(6, 40);
		AppController.fixKeyBindings(scenarioDescriptionArea);

		scenarioDescriptionArea.setLineWrap(true);
		scenarioDescriptionArea.setWrapStyleWord(true);
		scenarioDescriptionArea.getDocument().addDocumentListener(new DocumentListener() {
			public void insertUpdate(DocumentEvent theEvent) {
				scenario.description = scenarioDescriptionArea.getText().trim();
			}
			public void removeUpdate(DocumentEvent theEvent) {
				scenario.description = scenarioDescriptionArea.getText().trim();
			}
			public void changedUpdate(DocumentEvent theEvent) {
			}
		});
		scenarioDescriptionArea.addFocusListener(new FocusAdapter() {
			public void focusGained(FocusEvent theEvent) {
			}
			public void focusLost(FocusEvent theEvent) {
				if (!theEvent.isTemporary()) {
					parent.applyEditsFrom(outerThis);
				}
			}
		});

		JPanel descriptionPanel = new JPanel(new BorderLayout());
		descriptionPanel.setBorder(BorderFactory.createTitledBorder("Scenario Description"));
		descriptionPanel.add(AppController.createScrollPane(scenarioDescriptionArea), BorderLayout.CENTER);

		scenarioDescriptionArea.setText(scenario.description);
		scenario.description = scenarioDescriptionArea.getText().trim();

		// Create the parameter editor layout if needed.

		JPanel parameterEditPanel = null;

		if (null != scenario.parameters) {

			parameterEditors = new ArrayList<ParameterEditor>();
			JComponent paramEdit = ParameterEditor.createEditorLayout(this, errorReporter, scenario.parameters,
				parameterEditors);

			parameterEditPanel = new JPanel(new BorderLayout());
			parameterEditPanel.setBorder(BorderFactory.createTitledBorder("Scenario Parameters"));
			parameterEditPanel.add(paramEdit, BorderLayout.CENTER);
		}

		// Set up the source list table.

		JPanel sourcePanel = new JPanel(new BorderLayout());
		sourceModel = new SourceTableModel(sourcePanel);
		sourceTable = sourceModel.createTable(editMenu);

		sourceTable.addMouseListener(new MouseAdapter() {
			public void mouseClicked(MouseEvent e) {
				if (2 == e.getClickCount()) {
					doOpenSource();
				}
			}
		});

		sourceTable.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
			public void valueChanged(ListSelectionEvent theEvent) {
				updateControls();
			}
		});

		sourceModel.updateBorder();
		sourcePanel.add(AppController.createScrollPane(sourceTable), BorderLayout.CENTER);
		sourcePanel.add(sourceModel.filterPanel, BorderLayout.SOUTH);

		sourceEditors = new HashMap<Integer, SourceEditor>();

		// Menu of saved searches, this is populated later by the updateSearchMenu() method.

		addSourcesMenu = new JComboBox<ExtDbSearch>();
		addSourcesMenu.setFocusable(false);

		addSourcesMenu.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					ExtDbSearch newSearch = (ExtDbSearch)(addSourcesMenu.getSelectedItem());
					if ((null != newSearch) && (newSearch.name.length() > 0)) {
						doAddSources(newSearch);
					}
					addSourcesMenu.setSelectedIndex(0);
					blockActionsEnd();
				}
			}
		});

		// Create action buttons.

		addSourceButton = new JButton("Add One");
		addSourceButton.setFocusable(false);
		addSourceButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doAddSource();
			}
		});

		openSourceButton = new JButton("View");
		openSourceButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doOpenSource();
			}
		});

		removeSourceButton = new JButton("Remove");
		removeSourceButton.setFocusable(false);
		removeSourceButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doRemoveSource();
			}
		});

		JButton closeButton = new JButton("Close");
		closeButton.setFocusable(false);
		closeButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (windowShouldClose()) {
					AppController.hideWindow(outerThis);
				}
			}
		});

		// Do the layout.

		JPanel nameDescPanel = new JPanel(new BorderLayout());
		nameDescPanel.add(namePanel, BorderLayout.NORTH);
		nameDescPanel.add(descriptionPanel, BorderLayout.CENTER);

		JPanel topPanel = nameDescPanel;
		if (null != parameterEditPanel) {
			topPanel = new JPanel(new BorderLayout());
			topPanel.add(nameDescPanel, BorderLayout.NORTH);
			topPanel.add(parameterEditPanel, BorderLayout.CENTER);
		}

		JPanel leftButPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		leftButPanel.add(addSourceButton);
		leftButPanel.add(addSourcesMenu);

		JPanel midButPanel = new JPanel();
		midButPanel.add(removeSourceButton);
		midButPanel.add(openSourceButton);

		JPanel rightButPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		rightButPanel.add(closeButton);

		JPanel buttonPanel = new JPanel();
		buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.X_AXIS));
		buttonPanel.add(leftButPanel);
		buttonPanel.add(midButPanel);
		buttonPanel.add(rightButPanel);

		Container cp = getContentPane();
		cp.setLayout(new BorderLayout());
		cp.add(topPanel, BorderLayout.NORTH);
		cp.add(sourcePanel, BorderLayout.CENTER);
		cp.add(buttonPanel, BorderLayout.SOUTH);

		getRootPane().setDefaultButton(openSourceButton);

		pack();

		Dimension theSize = new Dimension(860, 650);
		setSize(theSize);
		setMinimumSize(theSize);

		// Build the Source menu.

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

		// Add One...

		addSourceMenuItem = new JMenuItem("Add One...");
		addSourceMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, AppController.MENU_SHORTCUT_KEY_MASK));
		addSourceMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doAddSource();
			}
		});
		fileMenu.add(addSourceMenuItem);

		// Add Many...

		addSourcesMenuItem = new JMenuItem("Add Many...");
		addSourcesMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doAddSources(null);
			}
		});
		fileMenu.add(addSourcesMenuItem);

		// __________________________________

		fileMenu.addSeparator();

		// View/Edit

		openSourceMenuItem = new JMenuItem("View");
		openSourceMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, AppController.MENU_SHORTCUT_KEY_MASK));
		openSourceMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doOpenSource();
			}
		});
		fileMenu.add(openSourceMenuItem);

		// Remove

		removeSourceMenuItem = new JMenuItem("Remove");
		removeSourceMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doRemoveSource();
			}
		});
		fileMenu.add(removeSourceMenuItem);

		// __________________________________

		fileMenu.addSeparator();

		// Replicate...

		replicateSourceMenuItem = new JMenuItem("Replicate...");
		replicateSourceMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doReplicateSource();
			}
		});
		fileMenu.add(replicateSourceMenuItem);

		// Allow Editing

		unlockSourceMenuItem = new JMenuItem("Allow Editing");
		unlockSourceMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doUnlockSource();
			}
		});
		fileMenu.add(unlockSourceMenuItem);

		// Revert

		revertSourceMenuItem = new JMenuItem("Revert");
		revertSourceMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doRevertSource();
			}
		});
		fileMenu.add(revertSourceMenuItem);

		// __________________________________

		fileMenu.addSeparator();

		// Export...

		exportSourceMenuItem = new JMenuItem("Export...");
		exportSourceMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doExportSource();
			}
		});
		fileMenu.add(exportSourceMenuItem);

		// Add to the Edit menu, items to set/clear desired and undesired flags on a multiple selection.

		editMenu.addSeparator();

		setDesiredMenuItem = new JMenuItem("Set Desired");
		setDesiredMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doSetFlags(true, true);
			}
		});
		editMenu.add(setDesiredMenuItem);

		clearDesiredMenuItem = new JMenuItem("Clear Desired");
		clearDesiredMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doSetFlags(true, false);
			}
		});
		editMenu.add(clearDesiredMenuItem);

		setUndesiredMenuItem = new JMenuItem("Set Undesired");
		setUndesiredMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doSetFlags(false, true);
			}
		});
		editMenu.add(setUndesiredMenuItem);

		clearUndesiredMenuItem = new JMenuItem("Clear Undesired");
		clearUndesiredMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doSetFlags(false, false);
			}
		});
		editMenu.add(clearUndesiredMenuItem);

		// Initial update of UI state.

		updateControls();
		updateDocumentName();
	}


	//=================================================================================================================
	// Table model for the source list in a scenario.  This is a wrapper around the SourceListData model object which
	// is actually managing the list.

	private class SourceTableModel extends AbstractTableModel implements TableFilterModel {

		private static final String SOURCE_DESIRED_COLUMN = "Des";
		private static final String SOURCE_UNDESIRED_COLUMN = "Und";
		private static final String SOURCE_CALLSIGN_COLUMN = "Call Sign";
		private static final String SOURCE_CALLSIGN_COLUMN_WL = "Call/ID";
		private static final String SOURCE_CHANNEL_COLUMN = "Channel";
		private static final String SOURCE_SERVICE_COLUMN = "Svc";
		private static final String SOURCE_STATUS_COLUMN = "Status";
		private static final String SOURCE_CITY_COLUMN = "City";
		private static final String SOURCE_STATE_COLUMN = "State";
		private static final String SOURCE_FACILITY_ID_COLUMN = "Facility ID";
		private static final String SOURCE_FILE_COLUMN = "File Number";
		private static final String SOURCE_FILE_COLUMN_WL = "File/Ref Number";
		private static final String SOURCE_COUNTRY_COLUMN = "Cntry";

		private String[] columnNamesNoWL = {
			SOURCE_DESIRED_COLUMN,
			SOURCE_UNDESIRED_COLUMN,
			SOURCE_CALLSIGN_COLUMN,
			SOURCE_CHANNEL_COLUMN,
			SOURCE_SERVICE_COLUMN,
			SOURCE_STATUS_COLUMN,
			SOURCE_CITY_COLUMN,
			SOURCE_STATE_COLUMN,
			SOURCE_FACILITY_ID_COLUMN,
			SOURCE_FILE_COLUMN,
			SOURCE_COUNTRY_COLUMN
		};

		private String[] columnNamesWL = {
			SOURCE_DESIRED_COLUMN,
			SOURCE_UNDESIRED_COLUMN,
			SOURCE_CALLSIGN_COLUMN_WL,
			SOURCE_CHANNEL_COLUMN,
			SOURCE_SERVICE_COLUMN,
			SOURCE_STATUS_COLUMN,
			SOURCE_CITY_COLUMN,
			SOURCE_STATE_COLUMN,
			SOURCE_FACILITY_ID_COLUMN,
			SOURCE_FILE_COLUMN_WL,
			SOURCE_COUNTRY_COLUMN
		};

		private String[] columnNames;

		private static final int SOURCE_DESIRED_INDEX = 0;
		private static final int SOURCE_UNDESIRED_INDEX = 1;
		private static final int SOURCE_CALLSIGN_INDEX = 2;
		private static final int SOURCE_CHANNEL_INDEX = 3;
		private static final int SOURCE_SERVICE_INDEX = 4;
		private static final int SOURCE_STATUS_INDEX = 5;
		private static final int SOURCE_CITY_INDEX = 6;
		private static final int SOURCE_STATE_INDEX = 7;
		private static final int SOURCE_FACILITY_ID_INDEX = 8;
		private static final int SOURCE_FILE_INDEX = 9;
		private static final int SOURCE_COUNTRY_INDEX = 10;

		private JPanel panel;

		private TableFilterPanel filterPanel;


		//-------------------------------------------------------------------------------------------------------------

		private SourceTableModel(JPanel thePanel) {

			if (Study.isRecordTypeAllowed(studyType, Source.RECORD_TYPE_WL)) {
				columnNames = columnNamesWL;
			} else {
				columnNames = columnNamesNoWL;
			}

			panel = thePanel;

			filterPanel = new TableFilterPanel(outerThis, this);
		}


		//-------------------------------------------------------------------------------------------------------------
		// Set up a table to display this model.  Custom renderer to apply color per locked state.  Also the table
		// gets custom sorting.

		private JTable createTable(EditMenu theEditMenu) {

			JTable theTable = new JTable(this);
			AppController.configureTable(theTable, theEditMenu);

			// Subclass TableRowSorter so that any call to sortKeys() substitutes a fixed list of keys based on the
			// first column in the argument list.  Also if the argument is null or empty rather than no sort, use a
			// default sort.

			TableRowSorter<SourceTableModel> theSorter = new TableRowSorter<SourceTableModel>(this) {
				public void setSortKeys(java.util.List<? extends RowSorter.SortKey> sortKeys) {

					int columnIndex = SOURCE_DESIRED_INDEX;
					SortOrder normal = SortOrder.ASCENDING;
					SortOrder reverse = SortOrder.DESCENDING;

					if ((null != sortKeys) && (sortKeys.size() > 0)) {
						RowSorter.SortKey theKey = sortKeys.get(0);
						columnIndex = theKey.getColumn();
						if ((SOURCE_DESIRED_INDEX == columnIndex) || (SOURCE_UNDESIRED_INDEX == columnIndex)) {
							if (theKey.getSortOrder().equals(SortOrder.ASCENDING)) {
								normal = SortOrder.DESCENDING;
								reverse = SortOrder.ASCENDING;
							}
						} else {
							if (theKey.getSortOrder().equals(SortOrder.DESCENDING)) {
								normal = SortOrder.DESCENDING;
								reverse = SortOrder.ASCENDING;
							}
						}
					}

					ArrayList<RowSorter.SortKey> theKeys = new ArrayList<RowSorter.SortKey>();

					switch (columnIndex) {

						case SOURCE_DESIRED_INDEX:   // Desired (country, state, city, channel).  Default.
						default:
							theKeys.add(new RowSorter.SortKey(SOURCE_DESIRED_INDEX, reverse));
							theKeys.add(new RowSorter.SortKey(SOURCE_COUNTRY_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_STATE_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_CITY_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_CHANNEL_INDEX, normal));
							break;

						case SOURCE_UNDESIRED_INDEX:   // Undesired (country, state, city, channel).
							theKeys.add(new RowSorter.SortKey(SOURCE_UNDESIRED_INDEX, reverse));
							theKeys.add(new RowSorter.SortKey(SOURCE_COUNTRY_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_STATE_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_CITY_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_CHANNEL_INDEX, normal));
							break;

						case SOURCE_CALLSIGN_INDEX:   // Call sign (status, channel).
							theKeys.add(new RowSorter.SortKey(SOURCE_CALLSIGN_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_STATUS_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_CHANNEL_INDEX, normal));
							break;

						case SOURCE_CHANNEL_INDEX:   // Channel (country, state, city).
							theKeys.add(new RowSorter.SortKey(SOURCE_CHANNEL_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_COUNTRY_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_STATE_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_CITY_INDEX, normal));
							break;

						case SOURCE_SERVICE_INDEX:   // Service (country, state, city, channel).
							theKeys.add(new RowSorter.SortKey(SOURCE_SERVICE_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_COUNTRY_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_STATE_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_CITY_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_CHANNEL_INDEX, normal));
							break;

						case SOURCE_STATUS_INDEX:   // Status (country, state, city, channel).
							theKeys.add(new RowSorter.SortKey(SOURCE_STATUS_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_COUNTRY_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_STATE_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_CITY_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_CHANNEL_INDEX, normal));
							break;

						case SOURCE_CITY_INDEX:   // City (country, state, channel).
							theKeys.add(new RowSorter.SortKey(SOURCE_CITY_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_COUNTRY_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_STATE_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_CHANNEL_INDEX, normal));
							break;

						case SOURCE_STATE_INDEX:   // State (country, city, channel).
							theKeys.add(new RowSorter.SortKey(SOURCE_STATE_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_COUNTRY_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_CITY_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_CHANNEL_INDEX, normal));
							break;

						case SOURCE_FACILITY_ID_INDEX:   // Facility ID (status, channel).
							theKeys.add(new RowSorter.SortKey(SOURCE_FACILITY_ID_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_STATUS_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_CHANNEL_INDEX, normal));
							break;

						case SOURCE_FILE_INDEX:   // File number, actually ARN (country, state, city, channel).
							theKeys.add(new RowSorter.SortKey(SOURCE_FILE_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_COUNTRY_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_STATE_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_CITY_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_CHANNEL_INDEX, normal));
							break;

						case SOURCE_COUNTRY_INDEX:   // Country (state, city, channel).
							theKeys.add(new RowSorter.SortKey(SOURCE_COUNTRY_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_STATE_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_CITY_INDEX, normal));
							theKeys.add(new RowSorter.SortKey(SOURCE_CHANNEL_INDEX, normal));
							break;
					}

					super.setSortKeys(theKeys);
				}
			};

			// The string converter may provide different properties for sorting that are displayed, see implementation
			// of the Record interface in SourceEditData.

			TableStringConverter theConverter = new TableStringConverter() {
				public String toString(TableModel theModel, int rowIndex, int columnIndex) {

					Scenario.SourceListItem theItem = scenario.sourceData.get(filterPanel.forwardIndex[rowIndex]);
					SourceEditData theSource = scenario.sourceData.getSource(filterPanel.forwardIndex[rowIndex]);

					switch (columnIndex) {

						case SOURCE_DESIRED_INDEX:
							return (theItem.isDesired ? "0" : "1");

						case SOURCE_UNDESIRED_INDEX:
							return (theItem.isUndesired ? "0" : "1");

						case SOURCE_CALLSIGN_INDEX:
							return theSource.getCallSign();

						case SOURCE_CHANNEL_INDEX:
							return theSource.getSortChannel();

						case SOURCE_SERVICE_INDEX:
							return theSource.getServiceCode();

						case SOURCE_STATUS_INDEX:
							return theSource.getSortStatus();

						case SOURCE_CITY_INDEX:
							return theSource.getCity();

						case SOURCE_STATE_INDEX:
							return theSource.getState();

						case SOURCE_FACILITY_ID_INDEX:
							return theSource.getSortFacilityID();

						case SOURCE_FILE_INDEX:
							return theSource.getARN();

						case SOURCE_COUNTRY_INDEX:
							return theSource.getSortCountry();
					}

					return "";
				}
			};

			theSorter.setStringConverter(theConverter);
			theTable.setRowSorter(theSorter);
			theSorter.setSortKeys(null);

			DefaultTableCellRenderer theRend = new DefaultTableCellRenderer() {
				public Component getTableCellRendererComponent(JTable t, Object o, boolean s, boolean f, int r, int c) {

					JLabel comp = (JLabel)super.getTableCellRendererComponent(t, o, s, f, r, c);

					SourceEditData theSource =
						scenario.sourceData.getSource(filterPanel.forwardIndex[t.convertRowIndexToModel(r)]);

					if (!s) {
						if (theSource.isLocked) {
							comp.setForeground(Color.BLACK);
						} else {
							comp.setForeground(Color.GREEN.darker());
						}
					}

					if (SOURCE_CALLSIGN_INDEX == c) {
						String cmnt = theSource.makeCommentText();
						if ((null != cmnt) && (cmnt.length() > 0)) {
							if (!s) {
								comp.setForeground(Color.BLUE);
							}
							comp.setToolTipText(cmnt);
						} else {
							comp.setToolTipText(null);
						}
					} else {
						comp.setToolTipText(null);
					}

					return comp;
				}
			};

			TableColumnModel columnModel = theTable.getColumnModel();

			TableColumn theColumn = columnModel.getColumn(SOURCE_DESIRED_INDEX);
			theColumn.setMinWidth(AppController.textFieldWidth[3]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[3]);

			theColumn = columnModel.getColumn(SOURCE_UNDESIRED_INDEX);
			theColumn.setMinWidth(AppController.textFieldWidth[3]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[3]);

			theColumn = columnModel.getColumn(SOURCE_CALLSIGN_INDEX);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[7]);

			theColumn = columnModel.getColumn(SOURCE_CHANNEL_INDEX);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[7]);

			theColumn = columnModel.getColumn(SOURCE_SERVICE_INDEX);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[2]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[2]);

			theColumn = columnModel.getColumn(SOURCE_STATUS_INDEX);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[4]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[4]);

			theColumn = columnModel.getColumn(SOURCE_CITY_INDEX);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[20]);

			theColumn = columnModel.getColumn(SOURCE_STATE_INDEX);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[3]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[3]);

			theColumn = columnModel.getColumn(SOURCE_FACILITY_ID_INDEX);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[5]);

			theColumn = columnModel.getColumn(SOURCE_FILE_INDEX);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[23]);

			theColumn = columnModel.getColumn(SOURCE_COUNTRY_INDEX);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[3]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[3]);

			return theTable;
		}


		//-------------------------------------------------------------------------------------------------------------

		private int addOrReplace(SourceEditData theSource, boolean theDesFlag, boolean theUndFlag) {

			postChange(scenario.sourceData.addOrReplace(theSource, theDesFlag, theUndFlag));

			int modelIndex = scenario.sourceData.getLastRowChanged();
			if (modelIndex >= 0) {
				return filterPanel.reverseIndex[modelIndex];
			}
			return modelIndex;
		}


		//--------------------------------------------------------------------------------------------------------------

		private void remove(int rowIndex) {

			postChange(scenario.sourceData.remove(filterPanel.forwardIndex[rowIndex]));
		}


		//-------------------------------------------------------------------------------------------------------------

		private void remove(int[] rows) {

			int[] modRows = new int[rows.length];
			for (int i = 0; i < rows.length; i++) {
				modRows[i] = filterPanel.forwardIndex[rows[i]];
			}
			postChange(scenario.sourceData.remove(modRows));
		}


		//-------------------------------------------------------------------------------------------------------------

		private void set(int rowIndex, SourceEditData newSource) {

			postChange(scenario.sourceData.set(filterPanel.forwardIndex[rowIndex], newSource));
		}


		//-------------------------------------------------------------------------------------------------------------

		private void setUnfiltered(int rowIndex, SourceEditData newSource) {

			postChange(scenario.sourceData.set(rowIndex, newSource));
		}


		//-------------------------------------------------------------------------------------------------------------

		private Scenario.SourceListItem get(int rowIndex) {

			return scenario.sourceData.get(filterPanel.forwardIndex[rowIndex]);
		}


		//-------------------------------------------------------------------------------------------------------------

		private SourceEditData getSource(int rowIndex) {

			return scenario.sourceData.getSource(filterPanel.forwardIndex[rowIndex]);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void setIsDesired(int rowIndex, boolean flag) {

			postChange(scenario.sourceData.setIsDesired(filterPanel.forwardIndex[rowIndex], flag));
		}


		//-------------------------------------------------------------------------------------------------------------

		private void setIsUndesired(int rowIndex, boolean flag) {

			postChange(scenario.sourceData.setIsUndesired(filterPanel.forwardIndex[rowIndex], flag));
		}


		//-------------------------------------------------------------------------------------------------------------
		// Called after table data may have changed, fire appropriate change events.  Gets complicated due to the
		// filter, the filter is updated and the filtered model row determined both before and after that update.
		// Which filtered model index matters depends on the type of change.  The model row affected may not be in the
		// filtered model before, after, neither, or both; which means an update may have to be notified as a delete or
		// an insert if the change caused the row to leave or enter the filtered model.

		private void postChange(boolean didChange) {

			if (didChange) {

				int modelIndex = scenario.sourceData.getLastRowChanged();
				int rowBefore = -1, rowAfter = -1;
				if ((modelIndex >= 0) && (modelIndex < filterPanel.reverseIndex.length)) {
					rowBefore = filterPanel.reverseIndex[modelIndex];
				}
				filterPanel.updateFilter();
				if ((modelIndex >= 0) && (modelIndex < filterPanel.reverseIndex.length)) {
					rowAfter = filterPanel.reverseIndex[modelIndex];
				}

				switch (scenario.sourceData.getLastChange()) {

					case ListDataChange.NO_CHANGE:
					default:
						break;

					case ListDataChange.ALL_CHANGE:
						fireTableDataChanged();
						updateBorder();
						break;

					case ListDataChange.INSERT:
						if (rowAfter >= 0) {
							fireTableRowsInserted(rowAfter, rowAfter);
							updateBorder();
						}
						break;

					case ListDataChange.UPDATE:
						if (rowBefore >= 0) {
							if (rowAfter >= 0) {
								fireTableRowsUpdated(rowBefore, rowAfter);
							} else {
								fireTableRowsDeleted(rowBefore, rowBefore);
								updateBorder();
							}
						} else {
							if (rowAfter >= 0) {
								fireTableRowsInserted(rowAfter, rowAfter);
								updateBorder();
							}
						}
						break;

					case ListDataChange.DELETE:
						if (rowBefore >= 0) {
							fireTableRowsDeleted(rowBefore, rowBefore);
							updateBorder();
						}
						break;
				}
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		private void dataWasChanged() {

			filterPanel.updateFilter();
			fireTableDataChanged();
			updateBorder();
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
				panel.setBorder(BorderFactory.createTitledBorder(String.valueOf(n) + " stations"));
			} else {
				panel.setBorder(BorderFactory.createTitledBorder("Stations"));
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
				case SOURCE_CALLSIGN_INDEX:
				case SOURCE_CHANNEL_INDEX:
				case SOURCE_SERVICE_INDEX:
				case SOURCE_CITY_INDEX:
				case SOURCE_STATE_INDEX:
				case SOURCE_COUNTRY_INDEX: {
					return true;
				}
			}

			return false;
		}


		//-------------------------------------------------------------------------------------------------------------

		public boolean collapseFilterChoices(int columnIndex) {

			switch (columnIndex) {
				case SOURCE_CALLSIGN_INDEX:
				case SOURCE_CHANNEL_INDEX:
				case SOURCE_CITY_INDEX:
				case SOURCE_STATE_INDEX: {
					return true;
				}
			}

			return false;
		}


		//-------------------------------------------------------------------------------------------------------------

		public Class getColumnClass(int columnIndex) {

			if ((SOURCE_DESIRED_INDEX == columnIndex) || (SOURCE_UNDESIRED_INDEX == columnIndex)) {
				return Boolean.class;
			}

			return Object.class;
		}


		//-------------------------------------------------------------------------------------------------------------

		public int getRowCount() {

			return filterPanel.forwardIndex.length;
		}


		//-------------------------------------------------------------------------------------------------------------

		public int getUnfilteredRowCount() {

			return scenario.sourceData.getRowCount();
		}


		//-------------------------------------------------------------------------------------------------------------
		// Permanent items cannot have desired/undesired state changed.  In various study types including TV IX check
		// and wireless IX, there can be only one desired station per scenario which was added programmatically when
		// the scenario was created, so the desired column can never be changed.  Also wireless records can never be
		// desireds, those should only appear in OET-74 studies, but check the record type too just in case.  In an FM
		// and TV channel 6 study there can only be one desired TV, like TV and wireless IX that is a permanent entry
		// added when the scenario was created.  But FMs in that study type can be both desired and undesired, so in
		// that case the desired lockout only applies to TV records.  Also in TVIX studies, some scenario types cannot
		// have desired or undesired flags changed at all.

		public boolean isCellEditable(int rowIndex, int columnIndex) {

			if ((Scenario.SCENARIO_TYPE_TVIX_PROPOSAL == scenario.scenarioType) ||
					(Scenario.SCENARIO_TYPE_TVIX_INTERFERENCE == scenario.scenarioType)) {
				return false;
			}

			rowIndex = filterPanel.forwardIndex[rowIndex];

			if (SOURCE_DESIRED_INDEX == columnIndex) {
				Scenario.SourceListItem theItem = scenario.sourceData.get(rowIndex);
				if (theItem.isPermanent) {
					return false;
				}
				if ((Study.STUDY_TYPE_TV_IX == studyType) || (Study.STUDY_TYPE_TV_OET74 == studyType)) {
					return false;
				}
				SourceEditData theSource = scenario.sourceData.getSource(rowIndex);
				if (Source.RECORD_TYPE_WL == theSource.recordType) {
					return false;
				}
				if ((Study.STUDY_TYPE_TV6_FM == studyType) && (Source.RECORD_TYPE_TV == theSource.recordType)) {
					return false;
				}
				return true;
			}

			if (SOURCE_UNDESIRED_INDEX == columnIndex) {
				Scenario.SourceListItem theItem = scenario.sourceData.get(rowIndex);
				if (theItem.isPermanent) {
					return false;
				}
				return true;
			}

			return false;
		}


		//-------------------------------------------------------------------------------------------------------------

		public Object getValueAt(int rowIndex, int columnIndex) {

			return getCellValue(filterPanel.forwardIndex[rowIndex], columnIndex);
		}


		//-------------------------------------------------------------------------------------------------------------

		public String getUnfilteredValueAt(int rowIndex, int columnIndex) {

			return getCellValue(rowIndex, columnIndex).toString();
		}


		//-------------------------------------------------------------------------------------------------------------

		private Object getCellValue(int rowIndex, int columnIndex) {

			Scenario.SourceListItem theItem = scenario.sourceData.get(rowIndex);
			SourceEditData theSource = scenario.sourceData.getSource(rowIndex);

			switch (columnIndex) {

				case SOURCE_DESIRED_INDEX:
					return Boolean.valueOf(theItem.isDesired);

				case SOURCE_UNDESIRED_INDEX:
					return Boolean.valueOf(theItem.isUndesired);

				case SOURCE_CALLSIGN_INDEX:
					return theSource.getCallSign();

				case SOURCE_CHANNEL_INDEX:
					return theSource.getChannel() + " " + theSource.getFrequency();

				case SOURCE_SERVICE_INDEX:
					return theSource.getServiceCode();

				case SOURCE_STATUS_INDEX:
					return theSource.getStatus();

				case SOURCE_CITY_INDEX:
					return theSource.getCity();

				case SOURCE_STATE_INDEX:
					return theSource.getState();

				case SOURCE_FACILITY_ID_INDEX:
					return theSource.getFacilityID();

				case SOURCE_FILE_INDEX:
					return theSource.getFileNumber();

				case SOURCE_COUNTRY_INDEX:
					return theSource.getCountryCode();
			}

			return "";
		}


		//-------------------------------------------------------------------------------------------------------------
		// Note this does not apply the filter because the setter methods will.

		public void setValueAt(Object value, int rowIndex, int columnIndex) {

			switch (columnIndex) {

				case SOURCE_DESIRED_INDEX:
					setIsDesired(rowIndex, ((Boolean)value).booleanValue());
					break;

				case SOURCE_UNDESIRED_INDEX:
					setIsUndesired(rowIndex, ((Boolean)value).booleanValue());
					break;
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update the state of buttons and menu items per current table selection.

	private void updateControls() {

		int rowCount = sourceTable.getSelectedRowCount();
		boolean isEditing = false, viewOnly = false, eAdd = false, eOpen = false, eRemove = false, eReplicate = false,
			eUnlock = false, eRevert = false, eExport = false, eFlags = false;

		if (1 == rowCount) {

			int rowIndex = sourceTable.convertRowIndexToModel(sourceTable.getSelectedRow());
			Scenario.SourceListItem theItem = sourceModel.get(rowIndex);
			SourceEditData theSource = sourceModel.getSource(rowIndex);
			SourceEditor theEditor = sourceEditors.get(theSource.key);
			if (null != theEditor) {
				if (theEditor.isVisible()) {
					isEditing = !theSource.isLocked;
				} else {
					sourceEditors.remove(theSource.key);
				}
			}

			viewOnly = theSource.isLocked;
			eAdd = true;
			eOpen = true;
			eRemove = !theItem.isPermanent;
			eReplicate = (Source.RECORD_TYPE_TV == theSource.recordType) && !isEditing;
			eUnlock = theSource.isLocked && !theSource.isReplication();
			eRevert = theSource.isReplication() || (!theSource.isLocked && theSource.hasRecordID() && !isEditing);
			eExport = !isEditing;
			eFlags = true;

			// For a permanent entry in an OET74 or TV6FM study, replication is not allowed, and revert is not allowed
			// if the entry is already a replication.  In those studies, the permanent entry in a scenario is a target
			// record that was used to automatically build the rest of the scenario based on interference rules, so
			// channel, service, and location must not change else the entire scenario is invalidated.  Replication
			// will always change either channel or service, or both.  An initial replication may have been performed
			// on the record before the scenario was built, that must not be reverted.  However the record can be made
			// editable, so reverting an editable record is allowed.  The source editor will not allow channel or
			// coordinates to be edited in this case, so the original will always match.

			if (theItem.isPermanent && ((Study.STUDY_TYPE_TV_OET74 == studyType) ||
					(Study.STUDY_TYPE_TV6_FM == studyType))) {
				eReplicate = false;
				if (theSource.isReplication()) {
					eRevert = false;
				}
			}

		} else {

			viewOnly = true;
			eAdd = true;
			if (rowCount > 1) {
				eRemove = true;
				eFlags = true;
			}
		}

		// In a TV IX check study, special scenario types identify scenarios that are part of the auto-build.  The
		// inteference scenarios are entirely non-editable.  The proposal scenario can't have records added or removed,
		// but the existing record(s) can be edited or replicated.

		if (Scenario.SCENARIO_TYPE_TVIX_INTERFERENCE == scenario.scenarioType) {
			eAdd = false;
			eRemove = false;
			eReplicate = false;
			eUnlock = false;
			eRevert = false;
			eFlags = false;
		} else {
			if (Scenario.SCENARIO_TYPE_TVIX_PROPOSAL == scenario.scenarioType) {
				eAdd = false;
				eRemove = false;
				eFlags = false;
			}
		}

		addSourceButton.setEnabled(eAdd);
		addSourcesMenu.setEnabled(eAdd);

		addSourceMenuItem.setEnabled(eAdd);
		addSourcesMenuItem.setEnabled(eAdd);

		if (viewOnly) {
			openSourceButton.setText("View");
			openSourceMenuItem.setText("View");
		} else {
			openSourceButton.setText("Edit");
			openSourceMenuItem.setText("Edit");
		}

		openSourceButton.setEnabled(eOpen);
		openSourceMenuItem.setEnabled(eOpen);

		removeSourceButton.setEnabled(eRemove);
		removeSourceMenuItem.setEnabled(eRemove);

		replicateSourceMenuItem.setEnabled(eReplicate);
		unlockSourceMenuItem.setEnabled(eUnlock);
		revertSourceMenuItem.setEnabled(eRevert);
		exportSourceMenuItem.setEnabled(eExport);

		setDesiredMenuItem.setEnabled(eFlags);
		clearDesiredMenuItem.setEnabled(eFlags);
		setUndesiredMenuItem.setEnabled(eFlags);
		clearUndesiredMenuItem.setEnabled(eFlags);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update geography lists.

	public void updateGeographies() {

		for (SourceEditor theEditor : sourceEditors.values()) {
			theEditor.updateGeographies();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getKeyTitle() {

		Window theWin = parent.getWindow();
		if (theWin instanceof AppFrame) {
			return ((AppFrame)theWin).getKeyTitle() + "." + super.getKeyTitle();
		}

		return super.getKeyTitle();
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected String getFileMenuName() {

		return "Station";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void updateDocumentName() {

		setDocumentName(parent.getDocumentName() + "/" + scenario.name);

		if (null != addSourcesDialog) {
			addSourcesDialog.updateDocumentName();
		}
		if (null != addSourceFinder) {
			addSourceFinder.updateDocumentName();
		}
		for (SourceEditor theEditor : sourceEditors.values()) {
			theEditor.updateDocumentName();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public ScenarioEditData getScenario() {

		return scenario;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by the parent when performing a validity check.  This only checks pending edit state, it does not call
	// isDataValid() in the model objects, the parent will do that.  See StudyEditor.isDataValid().

	public boolean isDataValid(String title) {

		for (SourceEditor theEditor : sourceEditors.values()) {
			if (theEditor.isVisible() && theEditor.isEditing()) {
				AppController.beep();
				theEditor.toFront();
				return false;
			}
		}

		return commitCurrentField();
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doPrevious() {

		int rowCount = sourceTable.getRowCount();
		int rowIndex = sourceTable.getSelectedRow();
		if ((rowCount > 0) && (rowIndex != 0)) {
			if (rowIndex < 0) {
				rowIndex = rowCount - 1;
			} else {
				rowIndex--;
			}
			sourceTable.setRowSelectionInterval(rowIndex, rowIndex);
			sourceTable.scrollRectToVisible(sourceTable.getCellRect(rowIndex, 0, true));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doNext() {

		int rowCount = sourceTable.getRowCount();
		int rowIndex = sourceTable.getSelectedRow();
		if ((rowCount > 0) && (rowIndex < (rowCount - 1))) {
			if (rowIndex < 0) {
				rowIndex = 0;
			} else {
				rowIndex++;
			}
			sourceTable.setRowSelectionInterval(rowIndex, rowIndex);
			sourceTable.scrollRectToVisible(sourceTable.getCellRect(rowIndex, 0, true));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Show a RecordFind window configured to allow individual records to be added to the scenario.  Note the window
	// stays open to allow multiple adds, it must be explicitly closed when no longer needed.  The actual add process
	// happens in applyEditsFrom(), called by the find window when user clicks the apply button.

	private void doAddSource() {

		if ((Scenario.SCENARIO_TYPE_TVIX_PROPOSAL == scenario.scenarioType) ||
				(Scenario.SCENARIO_TYPE_TVIX_INTERFERENCE == scenario.scenarioType)) {
			return;
		}

		if (null != addSourceFinder) {
			if (addSourceFinder.isVisible()) {
				addSourceFinder.toFront();
				return;
			}
			addSourceFinder = null;
		}

		RecordFind theFinder = new RecordFind(this, "Add Station", studyType, 0);
		theFinder.setDefaultExtDbKey(scenario.study.study.extDbKey);
		theFinder.setAccessoryPanel(new OptionsPanel.AddRecord(this));
		theFinder.setApply(new String[] {"Add"}, true, false);

		AppController.showWindow(theFinder);
		addSourceFinder = theFinder;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Populate the menu of saved searchs.  This always contains an initial "null" object just to identify the menu,
	// also two default selections for desired and undesired searches.

	private void updateSearchMenu() {

		blockActionsStart();

		ArrayList<ExtDbSearch> theSearches = ExtDbSearch.getSearches(getDbID(), studyType);

		addSourcesMenu.removeAllItems();

		ExtDbSearch defSearch = new ExtDbSearch(0);
		defSearch.name = "Add Many...";
		addSourcesMenu.addItem(defSearch);

		// Some study types do not allow a desired or protected search.

		if ((Study.STUDY_TYPE_TV == studyType) || (Study.STUDY_TYPE_FM == studyType) ||
				(Study.STUDY_TYPE_TV6_FM == studyType)) {

			defSearch = new ExtDbSearch(studyType);
			defSearch.name = ExtDbSearch.DEFAULT_DESIREDS_SEARCH_NAME;
			defSearch.searchType = ExtDbSearch.SEARCH_TYPE_DESIREDS;
			addSourcesMenu.addItem(defSearch);

			defSearch = new ExtDbSearch(studyType);
			defSearch.name = ExtDbSearch.DEFAULT_PROTECTEDS_SEARCH_NAME;
			defSearch.searchType = ExtDbSearch.SEARCH_TYPE_PROTECTEDS;
			addSourcesMenu.addItem(defSearch);
		}

		defSearch = new ExtDbSearch(studyType);
		defSearch.name = ExtDbSearch.DEFAULT_UNDESIREDS_SEARCH_NAME;
		defSearch.searchType = ExtDbSearch.SEARCH_TYPE_UNDESIREDS;
		defSearch.preferOperating = true;
		addSourcesMenu.addItem(defSearch);

		for (ExtDbSearch theSearch : theSearches) {
			addSourcesMenu.addItem(theSearch);
		}

		blockActionsEnd();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add multiple records from an external data search.  If a search object is provided, it has the auto-run flag
	// set, and there is a valid default data set, immediately run the search.  Otherwise, open the search editing
	// dialog or if already showing update with the new object.

	private void doAddSources(ExtDbSearch search) {

		if ((Scenario.SCENARIO_TYPE_TVIX_PROPOSAL == scenario.scenarioType) ||
				(Scenario.SCENARIO_TYPE_TVIX_INTERFERENCE == scenario.scenarioType)) {
			return;
		}

		if ((null != search) && search.autoRun && (null != scenario.study.study.extDbKey)) {
			ExtDb theDb = ExtDb.getExtDb(getDbID(), scenario.study.study.extDbKey, errorReporter);
			if (null != theDb) {
				runExtDbSearch(theDb, false, search, errorReporter);
				return;
			}
		}

		if (null != addSourcesDialog) {
			if (addSourcesDialog.isVisible()) {
				if (null != search) {
					addSourcesDialog.setSearch(search);
				}
				addSourcesDialog.toFront();
				return;
			}
			addSourcesDialog = null;
		}

		ExtDbSearchDialog theDialog = new ExtDbSearchDialog(this, scenario.study.study.extDbKey, studyType);
		if (null != search) {
			theDialog.setSearch(search);
		}
		AppController.showWindow(theDialog);
		addSourcesDialog = theDialog;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Run an add-many search defined by an ExtDbSearch object.  First make sure this is a valid search, the study
	// type must match, the record type must be allowed, and some study types do not allow desired or protected
	// searches.  The search input dialog shouldn't allow invalid combinations, but check anyway to be safe.

	private boolean runExtDbSearch(ExtDb extDb, boolean useBaseline, ExtDbSearch search, ErrorReporter errors) {

		if ((search.studyType != studyType) || !Study.isRecordTypeAllowed(studyType, extDb.recordType) ||
				(((ExtDbSearch.SEARCH_TYPE_DESIREDS == search.searchType) ||
						(ExtDbSearch.SEARCH_TYPE_PROTECTEDS == search.searchType)) &&
					(Study.STUDY_TYPE_TV != studyType) && (Study.STUDY_TYPE_FM != studyType) &&
					((Study.STUDY_TYPE_TV6_FM != studyType) || (Source.RECORD_TYPE_FM != extDb.recordType))) ||
				((Study.STUDY_TYPE_TV_IX == studyType) && !search.disableMX)) {
			errors.reportWarning("Search is not valid in this study, no stations were added.");
			return false;
		}

		// If this is a protecteds or undesireds search, there must be undesired or desired records already in the
		// scenario of an appropriate type to apply rules during the search.  For a protecteds search, undesireds of
		// the same record type as the search must exist.  For an undesireds search, if the search type is TV or FM
		// desireds of the same type must exist, but if the undesired is WL a TV desired must exist.

		if (ExtDbSearch.SEARCH_TYPE_PROTECTEDS == search.searchType) {
			if (!scenario.sourceData.hasUndesiredSources(extDb.recordType)) {
				errors.reportWarning(
					"The scenario must have at least one undesired station\n" +
					"before a search for protecteds can be performed.");
				return false;
			}

		} else {

			if (ExtDbSearch.SEARCH_TYPE_UNDESIREDS == search.searchType) {
				int checkType = extDb.recordType;
				if (Source.RECORD_TYPE_WL == checkType) {
					checkType = Source.RECORD_TYPE_TV;
				}
				if (!scenario.sourceData.hasDesiredSources(checkType)) {
					errors.reportWarning(
						"The scenario must have at least one desired station\n" +
						"before a search for undesireds can be performed.");
					return false;
				}
			}
		}

		// If MX checks are disabled, remind the user that records will not be flagged as undesireds, so individual
		// records that should cause interference must be selected manually.  Skip this for an auto-run search.  Also
		// skip this for an IX check study, in which manually-edited scenarios are expected to contain MX undesireds.

		if (search.disableMX && !search.autoRun && (Study.STUDY_TYPE_TV_IX != studyType)) {
			errors.reportWarning(
				"MX checks are disabled, new records will not be marked as undesireds.\n" +
				"Records that should be undesireds must be identified manually.");
		}

		// Run it.

		final ExtDb theDb = extDb;
		final boolean theBaseline = useBaseline;
		final ExtDbSearch theSearch = search;

		BackgroundWorker<Integer> theWorker = new BackgroundWorker<Integer>(errors.getWindow(), errors.getTitle()) {
			public Integer doBackgroundWork(ErrorLogger errors) {
				int count = scenario.addRecords(theDb, theBaseline, theSearch, errors);
				if (count < 0) {
					return null;
				}
				return Integer.valueOf(count);
			}
		};

		errors.clearMessages();

		Integer result = theWorker.runWork("Searching for stations, please wait...", errors);
		if (null == result) {
			return false;
		}

		errors.showMessages();

		sourceModel.dataWasChanged();

		errors.reportMessage(result.toString() + " stations were added.");

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// View or edit a source in a SourceEditor window.  If editing, the change is made in applyEditsFrom().

	private void doOpenSource() {

		if (sourceTable.getSelectedRowCount() != 1) {
			return;
		}

		int rowIndex = sourceTable.convertRowIndexToModel(sourceTable.getSelectedRow());
		SourceEditData theSource = sourceModel.getSource(rowIndex);

		SourceEditor theEditor = sourceEditors.get(theSource.key);
		if (null != theEditor) {
			if (theEditor.isVisible()) {
				theEditor.toFront();
				return;
			} else {
				sourceEditors.remove(theSource.key);
			}
		}

		Scenario.SourceListItem theItem = sourceModel.get(rowIndex);

		theEditor = new SourceEditor(this);
		theEditor.setSource(theSource, theItem.isPermanent);

		AppController.showWindow(theEditor);
		sourceEditors.put(theSource.key, theEditor);

		updateControls();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Remove records from the scenario.  If any have open source editors those are canceled.

	private void doRemoveSource() {

		if ((Scenario.SCENARIO_TYPE_TVIX_PROPOSAL == scenario.scenarioType) ||
				(Scenario.SCENARIO_TYPE_TVIX_INTERFERENCE == scenario.scenarioType)) {
			return;
		}

		if (0 == sourceTable.getSelectedRowCount()) {
			return;
		}

		int[] selRows = sourceTable.getSelectedRows();
		ArrayList<Integer> toDelete = new ArrayList<Integer>();

		int i, rowIndex;
		Scenario.SourceListItem theItem;
		boolean hasPerm = false;
		SourceEditor theEditor;

		for (i = 0; i < selRows.length; i++) {
			rowIndex = sourceTable.convertRowIndexToModel(selRows[i]);
			theItem = sourceModel.get(rowIndex);
			if (theItem.isPermanent) {
				hasPerm = true;
			} else {
				theEditor = sourceEditors.get(Integer.valueOf(theItem.key));
				if (null != theEditor) {
					if (theEditor.isVisible()) {
						if (!theEditor.cancel()) {
							AppController.beep();
							theEditor.toFront();
							return;
						}
					} else {
						sourceEditors.remove(Integer.valueOf(theItem.key));
					}
				}
				toDelete.add(Integer.valueOf(rowIndex));
			}
		}

		if (hasPerm) {
			errorReporter.reportMessage("Remove Stations", "Some records are permanent and cannot be removed.");
		}

		if (toDelete.isEmpty()) {
			return;
		}

		int[] rows = new int[toDelete.size()];
		for (i = 0; i < toDelete.size(); i++) {
			rows[i] = toDelete.get(i).intValue();
		}

		sourceModel.remove(rows);

		updateControls();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Replace a locked source with an editable copy, and open a source editor.  May need to cancel an existing source
	// editor viewing the record first.

	private void doUnlockSource() {

		if (Scenario.SCENARIO_TYPE_TVIX_INTERFERENCE == scenario.scenarioType) {
			return;
		}

		if (sourceTable.getSelectedRowCount() != 1) {
			return;
		}

		int rowIndex = sourceTable.convertRowIndexToModel(sourceTable.getSelectedRow());
		SourceEditData theSource = sourceModel.getSource(rowIndex);
		if (!theSource.isLocked || theSource.isReplication()) {
			return;
		}

		SourceEditor theEditor = sourceEditors.get(theSource.key);
		if (null != theEditor) {
			if (theEditor.isVisible()) {
				if (!theEditor.cancel()) {
					AppController.beep();
					theEditor.toFront();
					return;
				}
			} else {
				sourceEditors.remove(theSource.key);
			}
		}

		errorReporter.setTitle("Allow Editing");

		SourceEditData newSource = theSource.deriveSource(false, errorReporter);
		if (null == newSource) {
			return;
		}

		sourceModel.set(rowIndex, newSource);

		doOpenSource();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Replicate a TV source, or change the channel of replication.  If there is an open editor, this is not allowed
	// if the source is editable otherwise the open editor is cancelled.

	private void doReplicateSource() {

		if (Scenario.SCENARIO_TYPE_TVIX_INTERFERENCE == scenario.scenarioType) {
			return;
		}

		if (sourceTable.getSelectedRowCount() != 1) {
			return;
		}

		int rowIndex = sourceTable.convertRowIndexToModel(sourceTable.getSelectedRow());
		SourceEditData theSource = sourceModel.getSource(rowIndex);
		if (Source.RECORD_TYPE_TV != theSource.recordType) {
			return;
		}
		SourceEditDataTV theSourceTV = (SourceEditDataTV)theSource;

		SourceEditor theEditor = sourceEditors.get(theSource.key);
		if (null != theEditor) {
			if (theEditor.isVisible()) {
				if (!theSource.isLocked || !theEditor.cancel()) {
					AppController.beep();
					theEditor.toFront();
					return;
				}
			} else {
				sourceEditors.remove(theSource.key);
			}
		}

		// This is not allowed on a permanent entry in an OET74 or TV6FM study; see comments in updateControls().

		Scenario.SourceListItem theItem = sourceModel.get(rowIndex);

		if (theItem.isPermanent && ((Study.STUDY_TYPE_TV_OET74 == studyType) ||
				(Study.STUDY_TYPE_TV6_FM == studyType))) {
			return;
		}

		String title = "Replicate";
		errorReporter.setTitle(title);

		// Retrieve the original source for replication, that may be the current source if it is not already a
		// replication source.

		SourceEditDataTV origSource = theSourceTV;
		if (null != theSourceTV.originalSourceKey) {
			SourceEditData aSource = scenario.study.getSource(theSourceTV.originalSourceKey);
			if ((null == aSource) || (Source.RECORD_TYPE_TV != aSource.recordType)) {
				errorReporter.reportError("The original record for replication does not exist.");
				return;
			}
			origSource = (SourceEditDataTV)aSource;
		}

		// Get the new channel.  The channel entered must be different than the original, unless the original is
		// analog.  All replications are digital; analog sources can replicate to digital on the same channel.

		int repChan = 0, minChannel = scenario.study.getMinimumChannel(),
			maxChannel = scenario.study.getMaximumChannel();
		String str;

		do {

			str = JOptionPane.showInputDialog(this, "Enter the replication channel", title,
				JOptionPane.QUESTION_MESSAGE);
			if (null == str) {
				return;
			}
			str = str.trim();

			if (str.length() > 0) {
				try {
					repChan = Integer.parseInt(str);
					if ((repChan < minChannel) || (repChan > maxChannel)) {
						errorReporter.reportWarning("The channel must be in the range " + minChannel + " to " +
							maxChannel + ".");
						repChan = 0;
					} else {
						if (origSource.service.serviceType.digital && (repChan == origSource.channel)) {
							errorReporter.reportWarning("The channel must be different than the original.");
							repChan = 0;
						}
					}
				} catch (NumberFormatException ne) {
					errorReporter.reportWarning("The channel must be a number.");
					repChan = 0;
				}
			}

		} while (0 == repChan);

		// If the current source is already a replication and the current channel was re-entered, nothing to do!

		if ((null != theSourceTV.originalSourceKey) && (repChan == theSourceTV.channel)) {
			return;
		}

		// Find or create the replication source, set it in the scenario, replacing the current.

		errorReporter.clearMessages();

		SourceEditData newSource = null;
		if (origSource.isLocked) {
			if (null != origSource.userRecordID) {
				newSource = scenario.study.findSharedReplicationSource(origSource.userRecordID, repChan);
			} else {
				if ((null != origSource.extDbKey) && (null != origSource.extRecordID)) {
					newSource = scenario.study.findSharedReplicationSource(origSource.extDbKey,
						origSource.extRecordID, repChan);
				}
			}
		}
		if (null == newSource) {
			newSource = origSource.replicate(repChan, errorReporter);
			if (null == newSource) {
				return;
			}
		}

		// Check if this replication is already in the scenario, if so abort.  See comments in doRevertSource().

		String newChan = newSource.getSortChannel();

		for (SourceEditData otherSource : scenario.sourceData.getSources()) {
			if ((((null != newSource.userRecordID) && (newSource.userRecordID.equals(otherSource.userRecordID))) ||
					((null != newSource.extDbKey) && newSource.extDbKey.equals(otherSource.extDbKey) &&
					(null != newSource.extRecordID) && newSource.extRecordID.equals(otherSource.extRecordID))) &&
					newChan.equals(otherSource.getSortChannel())) {
				errorReporter.reportWarning(
					"The original record is already replicated on that channel in the scenario.");
				return;
			}
		}

		errorReporter.showMessages();

		sourceModel.set(rowIndex, newSource);

		updateControls();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Revert a source.  If the selected source is a replication, revert to the original.  If it is an editable source
	// based on a user or external record, revert to the non-editable record directly from the user record table or
	// external data (that may have to be retrieved, there is no guarantee it is currently part of the shared source
	// model).  A non-replication locked source, or an editable source that is not based on a user or external record,
	// can't be reverted because there is nothing to revert to (note an editable source can never be locked again).

	private void doRevertSource() {

		if (Scenario.SCENARIO_TYPE_TVIX_INTERFERENCE == scenario.scenarioType) {
			return;
		}

		if (sourceTable.getSelectedRowCount() != 1) {
			return;
		}

		int rowIndex = sourceTable.convertRowIndexToModel(sourceTable.getSelectedRow());
		Scenario.SourceListItem theItem = sourceModel.get(rowIndex);
		SourceEditData theSource = sourceModel.getSource(rowIndex);

		SourceEditor theEditor = sourceEditors.get(theSource.key);
		if (null != theEditor) {
			if (theEditor.isVisible()) {
				if (!theEditor.cancel()) {
					AppController.beep();
					theEditor.toFront();
					return;
				}
			} else {
				sourceEditors.remove(theSource.key);
			}
		}

		String title = "Revert";
		errorReporter.setTitle(title);

		SourceEditData newSource = null;

		errorReporter.clearErrors();

		if (theSource.isReplication()) {

			// In an OET74 or TV6FM study if the entry is permanent the record cannot be reverted, because the channel
			// and service cannot be allowed to change.  See comments in updateControls().

			if (theItem.isPermanent && ((Study.STUDY_TYPE_TV_OET74 == studyType) ||
					(Study.STUDY_TYPE_TV6_FM == studyType))) {
				return;
			}

			newSource = scenario.study.getSource(((SourceEditDataTV)theSource).originalSourceKey);
			if (null == newSource) {
				errorReporter.reportError("The original pre-replication record does not exist.");
				return;
			}

			// A replication cannot be reverted if the original channel is illegal for the study.  This can happen
			// because most appearances of the RecordFind window offer the chance to replicate the selected record
			// before it is actually added to a scenario, in which case the original channel is not restricted by the
			// study's range, only the replication channel is restricted.

			int origChan = ((SourceEditDataTV)newSource).channel;
			if ((origChan < scenario.study.getMinimumChannel()) || (origChan > scenario.study.getMaximumChannel())) {
				errorReporter.reportWarning(
					"Replication cannot be reverted, the original channel is out of range for the study.");
				return;
			}

			// A replication cannot be reverted if the original record already appears in the scenario.  The test is
			// the same identical-record test used when adding new entries, matching primary record IDs and channel.
			// Note the replication record will never match it's original by this test because the getSortChannel()
			// result includes a D/N suffix so even on-channel replication from analog to digital will have non-
			// matching channel strings from that method.

			String newChan = newSource.getSortChannel();

			for (SourceEditData otherSource : scenario.sourceData.getSources()) {
				if ((((null != newSource.userRecordID) && (newSource.userRecordID.equals(otherSource.userRecordID))) ||
						((null != newSource.extDbKey) && newSource.extDbKey.equals(otherSource.extDbKey) &&
						(null != newSource.extRecordID) && newSource.extRecordID.equals(otherSource.extRecordID))) &&
						newChan.equals(otherSource.getSortChannel())) {
					errorReporter.reportWarning(
						"Replication cannot be reverted, the original record is already in the scenario.");
					return;
				}
			}

		} else {

			if (theSource.isLocked || !theSource.hasRecordID()) {
				return;
			}

			// If the record has to be re-loaded (that can happen if unused records were deleted since the record was
			// made editable), warning messages are not reported to the user.  The assumption is those messages were
			// already seen the first time the record was added to the scenario, before it was replaced by the editable
			// duplicate.  Also if the record requests automatic replication that is ignored here.  That replication
			// was done when the record was first added so the user had to deliberately revert that before making the
			// source editable.

			if (null != theSource.userRecordID) {

				newSource = scenario.study.findSharedSource(theSource.userRecordID);
				if (null == newSource) {
					newSource = SourceEditData.findUserRecord(getDbID(), scenario.study, theSource.userRecordID,
						errorReporter);
					if (null == newSource) {
						if (!errorReporter.hasErrors()) {
							errorReporter.reportError("The original user record does not exist.");
						}
						return;
					}
				}

			} else {

				newSource = scenario.study.findSharedSource(theSource.extDbKey, theSource.extRecordID);
				if (null == newSource) {
					ExtDb theExtDb = ExtDb.getExtDb(getDbID(), theSource.extDbKey, errorReporter);
					if (null == theExtDb) {
						return;
					}
					if (theExtDb.isGeneric()) {
						newSource = SourceEditData.findImportRecord(theExtDb, scenario.study, theSource.extRecordID,
							errorReporter);
						if (null == newSource) {
							if (!errorReporter.hasErrors()) {
								errorReporter.reportError("The original station data record does not exist.");
							}
							return;
						}
					} else {
						ExtDbRecord theRecord = ExtDbRecord.findRecord(theExtDb, theSource.extRecordID, errorReporter);
						if (null == theRecord) {
							if (!errorReporter.hasErrors()) {
								errorReporter.reportError("The original station data record does not exist.");
							}
							return;
						}
						newSource = SourceEditData.makeSource(theRecord, scenario.study, true, errorReporter);
					}
				}
			}
		}

		sourceModel.set(rowIndex, newSource);

		updateControls();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Export the selected record to an XML file.  If editable, it must not have an open editor.

	private void doExportSource() {

		if (sourceTable.getSelectedRowCount() != 1) {
			return;
		}

		int rowIndex = sourceTable.convertRowIndexToModel(sourceTable.getSelectedRow());
		SourceEditData theSource = sourceModel.getSource(rowIndex);

		if (!theSource.isLocked) {
			SourceEditor theEditor = sourceEditors.get(theSource.key);
			if (null != theEditor) {
				if (theEditor.isVisible()) {
					AppController.beep();
					theEditor.toFront();
					return;
				} else {
					sourceEditors.remove(theSource.key);
					updateControls();
				}
			}
		}

		String title = "Export";
		errorReporter.setTitle(title);

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
	// Change desired/undesired flags on all selected rows.

	private void doSetFlags(boolean doDes, boolean newFlag) {

		if ((Scenario.SCENARIO_TYPE_TVIX_PROPOSAL == scenario.scenarioType) ||
				(Scenario.SCENARIO_TYPE_TVIX_INTERFERENCE == scenario.scenarioType)) {
			return;
		}

		if (0 == sourceTable.getSelectedRowCount()) {
			return;
		}

		for (int row : sourceTable.getSelectedRows()) {
			if (doDes) {
				sourceModel.setIsDesired(sourceTable.convertRowIndexToModel(row), newFlag);
			} else {
				sourceModel.setIsUndesired(sourceTable.convertRowIndexToModel(row), newFlag);
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by dependent dialogs when user commits edits or requests action.  First process request from the add-
	// multiple-records dialog, doing a search to add all matching records for user-entered criteria.

	public boolean applyEditsFrom(AppEditor theEditor) {

		if (theEditor instanceof ExtDbSearchDialog) {

			if ((ExtDbSearchDialog)theEditor != addSourcesDialog) {
				return false;
			}

			return runExtDbSearch(addSourcesDialog.extDb, addSourcesDialog.useBaseline, addSourcesDialog.search,
				addSourcesDialog.getErrorReporter());
		}

		// If the single-station add dialog sends an apply a single record has been selected in the dialog, retrieve
		// it and process the selection and options input.

		if (theEditor instanceof RecordFind) {

			if ((RecordFind)theEditor != addSourceFinder) {
				return false;
			}

			ErrorReporter theReporter = addSourceFinder.getErrorReporter();
			OptionsPanel theOptions = (OptionsPanel)(addSourceFinder.getAccessoryPanel());

			SourceEditData theSource = addSourceFinder.source;
			ExtDbRecord theRecord = addSourceFinder.record;

			int theType = 0;
			if (null != theSource) {
				theType = theSource.recordType;
			} else {
				if (null != theRecord) {
					theType = theRecord.recordType;
				}
			}

			// Do a paranoia check of the record type; the dialog should only allow compatible types to be chosen.

			if (!Study.isRecordTypeAllowed(studyType, theType)) {
				theReporter.reportError("That record type is not allowed here.");
				return false;
			}

			// If a TV record is selected, check the channel against the legal range for the study.  The search dialog
			// does not restrict the channel range for search because an out-of-range record may be replicated to an
			// in-range channel.  If the record will be replicated set up for that, and check the replication channel
			// not the original.  This supports automatic replication, see ExtDbRecordTV for details.

			int replicateToChannel = 0;

			if (Source.RECORD_TYPE_TV == theType) {

				int minChannel = scenario.study.getMinimumChannel(), maxChannel = scenario.study.getMaximumChannel();

				if (theOptions.replicate) {

					replicateToChannel = theOptions.replicationChannel;
					if ((replicateToChannel < minChannel) || (replicateToChannel > maxChannel)) {
						theReporter.reportWarning("The replication channel must be in the range " + minChannel +
							" to " + maxChannel + ".");
						return false;
					}

				} else {

					int theChan = 0;
					if (null != theSource) {
						theChan = ((SourceEditDataTV)theSource).channel;
					} else {
						theChan = ((ExtDbRecordTV)theRecord).replicateToChannel;
						if (0 == theChan) {
							theChan = ((ExtDbRecordTV)theRecord).channel;
						} else {
							replicateToChannel = theChan;
						}
					}

					if ((theChan < minChannel) || (theChan > maxChannel)) {
						theReporter.reportWarning("A TV record must have a channel in the range " + minChannel +
							" to " + maxChannel + ", or be replicated to a channel in range.");
						return false;
					}
				}
			}

			// Prepare to create the source.

			SourceEditData newSource = null;
			SourceEditDataTV originalSource = null;
			boolean wasLocked = true;

			theReporter.clearErrors();
			theReporter.clearMessages();

			// A source object from the find may be a new source or one loaded from the user record table.  If a user
			// record it is shareable if it will be locked.  In any case a new source is derived to set the study.

			if (null != theSource) {

				wasLocked = theSource.isLocked;

				if ((null != theSource.userRecordID) && theOptions.isLocked) {
					newSource = scenario.study.findSharedSource(theSource.userRecordID);
				}
				if (null == newSource) {
					newSource = theSource.deriveSource(scenario.study, theOptions.isLocked, theReporter);
				}
				if ((null != newSource) && (replicateToChannel > 0)) {
					originalSource = (SourceEditDataTV)newSource;
					newSource = null;
					if ((null != theSource.userRecordID) && theOptions.isLocked) {
						newSource = scenario.study.findSharedReplicationSource(theSource.userRecordID,
							replicateToChannel);
					}
					if (null == newSource) {
						newSource = originalSource.replicate(replicateToChannel, theReporter);
					}
				}

			} else {

				// For an external record, a source must be created.  It is shareable if it will be non-editable.

				if (null != theRecord) {

					if (theOptions.isLocked) {
						newSource = scenario.study.findSharedSource(theRecord.extDb.key, theRecord.extRecordID);
					}
					if (null == newSource) {
						newSource = SourceEditData.makeSource(theRecord, scenario.study, theOptions.isLocked,
							theReporter);
					}
					if ((null != newSource) && (replicateToChannel > 0)) {
						originalSource = (SourceEditDataTV)newSource;
						newSource = null;
						if (theOptions.isLocked) {
							newSource = scenario.study.findSharedReplicationSource(theRecord.extDb.key,
								theRecord.extRecordID, replicateToChannel);
						}
						if (null == newSource) {
							newSource = originalSource.replicate(replicateToChannel, theReporter);
						}
					}
				}
			}

			// If a source object was not created successfully, fail.  In some cases makeSource() will fail to create a
			// source but only log a message, report the message as an error if needed.

			if (null == newSource) {
				if (!theReporter.hasErrors()) {
					if (theReporter.hasMessages()) {
						theReporter.reportError(theReporter.getMessages());
					} else {
						theReporter.reportError("The record could not be added due to an unknown error.");
					}
				}
				return false;
			}

			// Check to be sure an identical source (matching user record ID or external data key and record ID) is not
			// already in the scenario, fail in that case.  Also check if the new source appears to be MX to any source
			// already in the scenario.  If MX the new source probably should not be added since the study engine does
			// not apply any MX logic, all active undesireds potentially interfere with all desireds based only on
			// matching an interference rule.  That means inappropriate self-interference could easily be predicted if
			// MX records are included.  However the user can manually manage MX records in a scenario by selecting
			// which are active as undesireds; so an MX record can still be added, but the addition must be confirmed.
			// Also this now requires a channel match for the identical-record test, to allow a record and one or more
			// replications of that same record to appear in the same scenario.  In an IX check study the scenarios
			// being edited are expected to contain many MX undesireds, because they are used to build interference
			// scenarios by iterating through non-MX undesired combinations.  In that case the MX test only looks at
			// desired records, and if MX is found the warning message is different.

			boolean isMX = false;
			double coChanMX = scenario.study.getCoChannelMxDistance();
			double kmPerDeg = scenario.study.getKilometersPerDegree();

			String newChan = newSource.getSortChannel();

			SourceEditData otherSource;
			Scenario.SourceListItem theItem;
			for (int rowIndex = 0; rowIndex < scenario.sourceData.getRowCount(); rowIndex++) {
				otherSource = scenario.sourceData.getSource(rowIndex);
				if ((((null != newSource.userRecordID) && (newSource.userRecordID.equals(otherSource.userRecordID))) ||
						((null != newSource.extDbKey) && newSource.extDbKey.equals(otherSource.extDbKey) &&
						(null != newSource.extRecordID) && newSource.extRecordID.equals(otherSource.extRecordID))) &&
						newChan.equals(otherSource.getSortChannel())) {
					theReporter.reportWarning("That record is already in the scenario.");
					return false;
				}
				if (!isMX) {
					if (newSource.recordType == otherSource.recordType) {
						if ((Source.RECORD_TYPE_TV == newSource.recordType) &&
								ExtDbRecordTV.areRecordsMX((SourceEditDataTV)newSource, (SourceEditDataTV)otherSource,
									coChanMX, kmPerDeg)) {
							if (Study.STUDY_TYPE_TV_IX == studyType) {
								theItem = scenario.sourceData.get(rowIndex);
								if (theItem.isDesired) {
									isMX = true;
								}
							} else {
								isMX = true;
							}
						}
						if ((Source.RECORD_TYPE_FM == newSource.recordType) &&
								ExtDbRecordFM.areRecordsMX((SourceEditDataFM)newSource, (SourceEditDataFM)otherSource,
									coChanMX, kmPerDeg)) {
							isMX = true;
						}
					}
				}
			}

			// Extra test for a TV IX check study, records that are MX to the proposal record being evaluated by the
			// study should not be added, because the proposal record will always be added to the interference
			// scenarios automatically by the auto-build code later.

			if (!isMX && (Study.STUDY_TYPE_TV_IX == studyType)) {
				ScenarioEditData propScenario = scenario.study.scenarioData.get(0);
				if (Scenario.SCENARIO_TYPE_TVIX_PROPOSAL == propScenario.scenarioType) {
					SourceEditDataTV propSource = null;
					for (SourceEditData aSource : propScenario.sourceData.getSources(Source.RECORD_TYPE_TV)) {
						if (null != aSource.getAttribute(Source.ATTR_IS_PROPOSAL)) {
							propSource = (SourceEditDataTV)aSource;
							break;
						}
					}
					if ((null != propSource) && ExtDbRecordTV.areRecordsMX((SourceEditDataTV)newSource, propSource,
							kmPerDeg)) {
						isMX = true;
					}
				}
			}

			if (isMX) {
				AppController.beep();
				String[] opts = {"No", "Yes"};
				if (Study.STUDY_TYPE_TV_IX == studyType) {
					if (1 != JOptionPane.showOptionDialog(addSourceFinder,
							"The new record appears to be mutually-exclusive with the desired record in\n" +
							"this scenario, or with the proposal record for the study.  All records in\n" +
							"the scenario will be included as undesireds in the interference scenarios,\n" +
							"regardless of the undesired flag.  Are you sure you want to add this record?",
							addSourceFinder.getBaseTitle(), 0, JOptionPane.WARNING_MESSAGE, null, opts, opts[0])) {
						return false;
					}
				} else {
					if (1 != JOptionPane.showOptionDialog(addSourceFinder,
							"The new record appears to be mutually-exclusive with one already in the\n" +
							"scenario.  The study will always consider all active undesired records as\n" +
							"potential interference sources to all desireds, regardless of apparent MX\n" +
							"relationships.  Are you sure you want to add this record?",
							addSourceFinder.getBaseTitle(), 0, JOptionPane.WARNING_MESSAGE, null, opts, opts[0])) {
						return false;
					}
					theReporter.reportWarning("The new record will not be marked as an undesired.");
				}
			}

			// Add the new source.  If this was a replication add the original first.  For general-purpose study types
			// the new source is always a desired, for most others it never is (for those types there is always one
			// desired station that was added to the scenario when it was created, with the permanent flag set), except
			// in a FM and TV channel 6 study where FM records may also be desireds.  For any type, the new station is
			// an undesired, unless an MX relationship was detected, except in a TV IX check study where MX is ignored.

			theReporter.showMessages();

			if (null != originalSource) {
				scenario.study.addOrReplaceSource(originalSource);
			}
			boolean isDes = ((Study.STUDY_TYPE_TV == studyType) || (Study.STUDY_TYPE_FM == studyType) ||
				((Study.STUDY_TYPE_TV6_FM == studyType) && (Source.RECORD_TYPE_FM == newSource.recordType)));
			boolean isUnd = (!isMX || (Study.STUDY_TYPE_TV_IX == studyType));
			int rowIndex = sourceModel.addOrReplace(newSource, isDes, isUnd);
			if (rowIndex < 0) {
				return false;
			}

			rowIndex = sourceTable.convertRowIndexToView(rowIndex);
			sourceTable.setRowSelectionInterval(rowIndex, rowIndex);
			sourceTable.scrollRectToVisible(sourceTable.getCellRect(rowIndex, 0, true));

			// If the new source is editable but the selected record was not, immediately show an edit dialog.  If the
			// record selected in the find dialog was editable it must have been a newly-created or derived source
			// meaning the user has just seen it in the source editor off the find dialog, no need to show that again.

			if (!theOptions.isLocked && wasLocked) {
				doOpenSource();
			}

			return true;
		}

		// An apply from a source editor means editing is complete on an editable record, update the model.  But be
		// paranoid and make sure the source really is in the model and is editable first.

		if (theEditor instanceof SourceEditor) {

			SourceEditor sourceEditor = (SourceEditor)theEditor;
			Integer theKey = sourceEditor.getOriginalSourceKey();
			if (sourceEditor != sourceEditors.get(theKey)) {
				return false;
			}

			int rowIndex = scenario.sourceData.indexOfSourceKey(theKey);
			if ((rowIndex < 0) || scenario.sourceData.getSource(rowIndex).isLocked) {
				return false;
			}

			sourceModel.setUnfiltered(rowIndex, sourceEditor.getSource());

			return true;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by dependent dialogs when closing.

	public void editorClosing(AppEditor theEditor) {

		if (theEditor instanceof ExtDbSearchDialog) {
			if ((ExtDbSearchDialog)theEditor == addSourcesDialog) {
				addSourcesDialog = null;
				updateSearchMenu();
			}
			return;
		}

		if (theEditor instanceof RecordFind) {
			if ((RecordFind)theEditor == addSourceFinder) {
				addSourceFinder = null;
			}
			return;
		}

		if (theEditor instanceof SourceEditor) {
			sourceEditors.remove(((SourceEditor)theEditor).getOriginalSourceKey(), (SourceEditor)theEditor);
			updateControls();
			return;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Force the editor to go away without preserving or saving any state; cancel showing dialogs and hide.

	public boolean closeWithoutSave() {

		if (!sourceEditors.isEmpty()) {
			for (SourceEditor theEditor : new ArrayList<SourceEditor>(sourceEditors.values())) {
				if (theEditor.isVisible()) {
					if (!theEditor.cancel()) {
						AppController.beep();
						theEditor.toFront();
						return false;
					}
				} else {
					sourceEditors.remove(theEditor.getOriginalSourceKey());
				}
			}
		}

		if (null != addSourceFinder) {
			if (addSourceFinder.isVisible() && !addSourceFinder.closeIfPossible()) {
				AppController.beep();
				addSourceFinder.toFront();
				return false;
			}
			addSourceFinder = null;
		}

		if (null != addSourcesDialog) {
			if (addSourcesDialog.isVisible() && !addSourcesDialog.cancel()) {
				AppController.beep();
				addSourcesDialog.toFront();
				return false;
			}
			addSourcesDialog = null;
		}

		blockActionsSet();
		parent.editorClosing(this);
		AppController.hideWindow(this);

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowWillOpen() {

		DbController.restoreColumnWidths(getDbID(), getKeyTitle(), sourceTable);

		updateSearchMenu();

		blockActionsClear();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// When closing, the dialogs for adding sources are just canceled, as are any source editors for non-editable
	// records.  However source editors for editable records will block the close and must be closed manually first.

	public boolean windowShouldClose() {

		if (!sourceEditors.isEmpty()) {
			for (SourceEditor theEditor : new ArrayList<SourceEditor>(sourceEditors.values())) {
				if (theEditor.isVisible()) {
					if (theEditor.isEditing() || !theEditor.cancel()) {
						AppController.beep();
						theEditor.toFront();
						return false;
					}
				} else {
					sourceEditors.remove(theEditor.getOriginalSourceKey());
				}
			}
		}

		if (null != addSourceFinder) {
			if (addSourceFinder.isVisible() && !addSourceFinder.closeIfPossible()) {
				AppController.beep();
				addSourceFinder.toFront();
				return false;
			}
			addSourceFinder = null;
		}

		if (null != addSourcesDialog) {
			if (addSourcesDialog.isVisible() && !addSourcesDialog.cancel()) {
				AppController.beep();
				addSourcesDialog.toFront();
				return false;
			}
			addSourcesDialog = null;
		}

		if (!commitCurrentField()) {
			toFront();
			return false;
		}

		DbController.saveColumnWidths(getDbID(), getKeyTitle(), sourceTable);

		blockActionsSet();
		parent.editorClosing(this);

		return true;
	}
}
