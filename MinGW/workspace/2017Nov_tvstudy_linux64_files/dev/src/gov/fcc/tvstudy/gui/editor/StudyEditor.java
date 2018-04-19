//
//  StudyEditor.java
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
import java.io.*;
import java.sql.*;
import java.awt.*;
import java.awt.event.*;
import java.text.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.table.*;
import javax.swing.GroupLayout.*;
import javax.swing.filechooser.*;


//=====================================================================================================================
// Study editor, allows editing parameters and interference rules, browsing scenarios, and opening scenario editors.

public class StudyEditor extends RootEditor implements StudyLockHolder {

	public static final String WINDOW_TITLE = "Study";

	// The study being edited.

	private StudyEditData study;
	private int studyType;

	// UI fields.  First study name and description.

	private JTextField studyNameField;
	private JTextArea studyDescriptionArea;

	// Study mode and points set.

	private KeyedRecordMenu studyModeMenu;
	private KeyedRecordMenu pointSetMenu;
	private JButton editPointGeoButton;

	// Propagation model.

	private KeyedRecordMenu propagationModelMenu;

	// Study area mode and geography.

	private KeyedRecordMenu studyAreaModeMenu;
	private KeyedRecordMenu studyAreaGeographyMenu;
	private JButton editAreaGeoButton;

	// Output configurations.

	private JComboBox<OutputConfig> fileOutputConfigMenu;
	private JComboBox<OutputConfig> mapOutputConfigMenu;

	// Scenario table.

	private ScenarioTableModel scenarioModel;
	private JTable scenarioTable;

	// Parameter editors, and flags set true if there are new parameters, see constructor.

	private ArrayList<ParameterEditor> parameterEditors;
	private boolean hasNewParameters;

	// Interference rules table.

	private IxRuleTableModel ixRuleModel;
	private JTable ixRuleTable;

	// Maps of open editing windows and dialogs.

	private HashMap<Integer, IxRuleEditor> ixRuleEditors;
	private HashMap<Integer, ScenarioEditor> scenarioEditors;

	// Dialog for selecting a target record when creating a new scenario.

	private RecordFind newScenarioFinder;

	// Buttons and menu items.

	private JButton editIxRuleButton;
	private JButton enableIxRuleButton;
	private JButton disableIxRuleButton;

	private JMenuItem editIxRuleMenuItem;
	private JMenuItem deleteIxRuleMenuItem;
	private JMenuItem enableIxRuleMenuItem;
	private JMenuItem disableIxRuleMenuItem;

	private JButton openScenarioButton;
	private JButton exportScenarioButton;

	private JMenuItem duplicateScenarioMenuItem;
	private JMenuItem openScenarioMenuItem;
	private JMenuItem deleteScenarioMenuItem;
	private JMenuItem exportScenarioMenuItem;

	private JMenuItem revertAllParametersMenuItem;

	// Tab pane for editor layout.

	private static final String STUDY_TAB_NAME = "Study";
	private static final String SCENARIOS_TAB_NAME = "Scenarios";
	private static final String PARAMETERS_TAB_NAME = "Parameters";
	private static final String RULES_TAB_NAME = "Rules";

	private JTabbedPane editorTabPane;
	private String lastSelectedTabName;

	// See save().

	private boolean dataChanged;

	// See windowShouldClose().

	private boolean confirmSave = true;

	// Disambiguation.

	private StudyEditor outerThis = this;


	//-----------------------------------------------------------------------------------------------------------------

	public StudyEditor(AppEditor theParent, StudyEditData theStudy) {

		super(theParent, WINDOW_TITLE);

		study = theStudy;
		studyType = study.study.studyType;

		// Use the study key to make the unique key title used to save UI properties.

		setTitleKey(study.study.key);

		// Create UI components, the study name first.

		studyNameField = new JTextField(30);
		AppController.fixKeyBindings(studyNameField);

		studyNameField.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					String newName = studyNameField.getText().trim();
					if ((newName.length() > 0) && !study.name.equals(newName)) {
						boolean changeOK = false;
						if (study.name.equalsIgnoreCase(newName)) {
							changeOK = true;
						} else {
							errorReporter.setTitle("Change Study Name");
							changeOK = DbCore.checkStudyName(newName, study, errorReporter);
						}
						if (changeOK) {
							study.name = newName;
						}
					}
					blockActionsEnd();
				}
				studyNameField.setText(study.name);
			}
		});
		studyNameField.addFocusListener(new FocusAdapter() {
			public void focusGained(FocusEvent theEvent) {
				setCurrentField(studyNameField);
			}
			public void focusLost(FocusEvent theEvent) {
				if (!theEvent.isTemporary()) {
					studyNameField.postActionEvent();
				}
			}
		});

		JPanel namePanel = new JPanel();
		namePanel.setBorder(BorderFactory.createTitledBorder("Study Name"));
		namePanel.add(studyNameField);

		studyNameField.setText(study.name);

		// Study description, a button that pops up a text input dialog.

		JButton descriptionButton = new JButton("Study Description");

		descriptionButton.setFocusable(false);
		descriptionButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doEditDescription();
			}
		});

		// Menus for study mode and selecting point set in points mode, and selecting study area mode and geography in
		// grid mode.  These are not shown for TV interference-check studies or TV6 vs. FM studies, for both of those
		// the study build concept is based on studying individual record service areas in grid mode.

		JPanel modePanel = null;
		JPanel pointPanel = null;
		JPanel areaModePanel = null;
		JPanel areaGeoPanel = null;

		if ((Study.STUDY_TYPE_TV_IX == studyType) || (Study.STUDY_TYPE_TV6_FM == studyType)) {

			study.studyMode = Study.STUDY_MODE_GRID;
			study.pointSetKey = 0;
			study.studyAreaMode = Study.STUDY_AREA_SERVICE;
			study.studyAreaGeoKey = 0;

		} else {

			// Study point, grid or points.

			studyModeMenu = new KeyedRecordMenu();
			studyModeMenu.addItem(new KeyedRecord(Study.STUDY_MODE_GRID, "Grid"));
			studyModeMenu.addItem(new KeyedRecord(Study.STUDY_MODE_POINTS, "Points"));

			studyModeMenu.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (Study.STUDY_MODE_POINTS == studyModeMenu.getSelectedKey()) {
							study.studyMode = Study.STUDY_MODE_POINTS;
							AppController.setComponentEnabled(pointSetMenu, true);
							editPointGeoButton.setEnabled(true);
							AppController.setComponentEnabled(studyAreaModeMenu, false);
							AppController.setComponentEnabled(studyAreaGeographyMenu, false);
							editAreaGeoButton.setEnabled(false);
						} else {
							study.studyMode = Study.STUDY_MODE_GRID;
							AppController.setComponentEnabled(pointSetMenu, false);
							editPointGeoButton.setEnabled(false);
							AppController.setComponentEnabled(studyAreaModeMenu, true);
							if (Study.STUDY_AREA_GEOGRAPHY == study.studyAreaMode) {
								AppController.setComponentEnabled(studyAreaGeographyMenu, true);
								editAreaGeoButton.setEnabled(true);
							} else {
								AppController.setComponentEnabled(studyAreaGeographyMenu, false);
								editAreaGeoButton.setEnabled(false);
							}
						}
						blockActionsEnd();
					}
				}
			});

			modePanel = new JPanel();
			modePanel.setBorder(BorderFactory.createTitledBorder("Study Mode"));
			modePanel.add(studyModeMenu);

			studyModeMenu.setSelectedKey(study.studyMode);

			// Point set geography for points mode.

			pointSetMenu = new KeyedRecordMenu();
			pointSetMenu.setPrototypeDisplayValue(new KeyedRecord(0, "XyXyXyXyXyXyXyXyXyXyXyXy"));

			pointSetMenu.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						study.pointSetKey = pointSetMenu.getSelectedKey();
						blockActionsEnd();
					}
				}
			});

			editPointGeoButton = new JButton("Edit");
			editPointGeoButton.setFocusable(false);
			editPointGeoButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent e) {
					StudyManager.showGeographyEditor(getDbID(), study.study.key, study.name, 0, "",
						Geography.MODE_POINTS, study.pointSetKey);
				}
			});

			pointPanel = new JPanel();
			pointPanel.setBorder(BorderFactory.createTitledBorder("Point Set"));
			pointPanel.add(pointSetMenu);
			pointPanel.add(editPointGeoButton);

			if (Study.STUDY_MODE_POINTS == study.studyMode) {
				AppController.setComponentEnabled(pointSetMenu, true);
				editPointGeoButton.setEnabled(true);
			} else {
				AppController.setComponentEnabled(pointSetMenu, false);
				editPointGeoButton.setEnabled(false);
			}

			// Area mode menu for grid mode.

			studyAreaModeMenu = new KeyedRecordMenu();
			studyAreaModeMenu.addItem(new KeyedRecord(Study.STUDY_AREA_SERVICE, "Individual service areas"));
			studyAreaModeMenu.addItem(new KeyedRecord(Study.STUDY_AREA_GEOGRAPHY, "Fixed geography"));
			studyAreaModeMenu.addItem(new KeyedRecord(Study.STUDY_AREA_NO_BOUNDS, "Unrestricted"));

			studyAreaModeMenu.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						study.studyAreaMode = studyAreaModeMenu.getSelectedKey();
						if (Study.STUDY_AREA_GEOGRAPHY == study.studyAreaMode) {
							AppController.setComponentEnabled(studyAreaGeographyMenu, true);
							editAreaGeoButton.setEnabled(true);
						} else {
							AppController.setComponentEnabled(studyAreaGeographyMenu, false);
							editAreaGeoButton.setEnabled(false);
						}
						blockActionsEnd();
					}
				}
			});

			areaModePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
			areaModePanel.setBorder(BorderFactory.createTitledBorder("Study Area Mode"));
			areaModePanel.add(studyAreaModeMenu);

			studyAreaModeMenu.setSelectedKey(study.studyAreaMode);
			AppController.setComponentEnabled(studyAreaModeMenu, (Study.STUDY_MODE_GRID == study.studyMode));

			// Geography for grid mode in geography study area mode.

			studyAreaGeographyMenu = new KeyedRecordMenu();
			studyAreaGeographyMenu.setPrototypeDisplayValue(new KeyedRecord(0, "XyXyXyXyXyXyXyXyXyXyXyXy"));

			studyAreaGeographyMenu.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						study.studyAreaGeoKey = studyAreaGeographyMenu.getSelectedKey();
						blockActionsEnd();
					}
				}
			});

			editAreaGeoButton = new JButton("Edit");
			editAreaGeoButton.setFocusable(false);
			editAreaGeoButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent e) {
					StudyManager.showGeographyEditor(getDbID(), study.study.key, study.name, 0, "",
						Geography.MODE_AREA, study.studyAreaGeoKey);
				}
			});

			areaGeoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
			areaGeoPanel.setBorder(BorderFactory.createTitledBorder("Area Geography"));
			areaGeoPanel.add(studyAreaGeographyMenu);
			areaGeoPanel.add(editAreaGeoButton);

			if ((Study.STUDY_MODE_GRID == study.studyMode) && (Study.STUDY_AREA_GEOGRAPHY == study.studyAreaMode)) {
				AppController.setComponentEnabled(studyAreaGeographyMenu, true);
				editAreaGeoButton.setEnabled(true);
			} else {
				AppController.setComponentEnabled(studyAreaGeographyMenu, false);
				editAreaGeoButton.setEnabled(false);
			}
		}

		// Propagation model.  The list of available models is obtained by querying the study engine directly, new
		// models can be added to the engine without modifying the UI code.  Hence there is no verification of the
		// model identfier key here, it is simply passed through to the engine code as selected.

		propagationModelMenu = new KeyedRecordMenu(AppCore.propagationModels);

		propagationModelMenu.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					study.propagationModel = propagationModelMenu.getSelectedKey();
					blockActionsEnd();
				}
			}
		});

		propagationModelMenu.setSelectedItem(new KeyedRecord(study.propagationModel, "(unknown)"));

		JPanel modelPanel = new JPanel();
		modelPanel.setBorder(BorderFactory.createTitledBorder("Propagation Model"));
		modelPanel.add(propagationModelMenu);

		// Output configuration UI.

		fileOutputConfigMenu = new JComboBox<OutputConfig>();
		fileOutputConfigMenu.setFocusable(false);
		OutputConfig proto = new OutputConfig(OutputConfig.CONFIG_TYPE_FILE, "");
		proto.name = "XyXyXyXyXyXyXyXyXy";
		fileOutputConfigMenu.setPrototypeDisplayValue(proto);

		fileOutputConfigMenu.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					study.fileOutputConfig = (OutputConfig)fileOutputConfigMenu.getSelectedItem();
					blockActionsEnd();
				}
			}
		});

		mapOutputConfigMenu = new JComboBox<OutputConfig>();
		mapOutputConfigMenu.setFocusable(false);
		proto = new OutputConfig(OutputConfig.CONFIG_TYPE_MAP, "");
		proto.name = "XyXyXyXyXyXyXyXyXy";
		mapOutputConfigMenu.setPrototypeDisplayValue(proto);

		mapOutputConfigMenu.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					study.mapOutputConfig = (OutputConfig)mapOutputConfigMenu.getSelectedItem();
					blockActionsEnd();
				}
			}
		});

		JButton editConfigButton = new JButton("Edit");
		editConfigButton.setFocusable(false);
		editConfigButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doEditOutputConfigs();
			}
		});

		JPanel configPanel = new JPanel();
		configPanel.setBorder(BorderFactory.createTitledBorder("Default Output Settings"));
		configPanel.add(new JLabel("File:"));
		configPanel.add(fileOutputConfigMenu);
		configPanel.add(new JLabel("Map:"));
		configPanel.add(mapOutputConfigMenu);
		configPanel.add(editConfigButton);

		// Create the parameter editor layout.

		parameterEditors = new ArrayList<ParameterEditor>();

		JComponent paramEdit = ParameterEditor.createEditorLayout(this, errorReporter, study.parameters,
			parameterEditors);

		// Set a flag if any parameters had default values set when read from the database.  Those are assumed to be
		// new parameters added since the study was last saved, or if this is a new study, added since the study
		// template was last saved.  This triggers an alert, and new parameters are highlighted (see ParameterEditor).

		for (ParameterEditData theParameter : study.parameters) {
			if (theParameter.parameter.defaultsApplied) {
				hasNewParameters = true;
			}
		}

		JPanel parameterEditPanel = new JPanel(new BorderLayout());
		if (hasNewParameters) {
			parameterEditPanel.setBorder(BorderFactory.createTitledBorder("Study Parameters (new in red)"));
		} else {
			parameterEditPanel.setBorder(BorderFactory.createTitledBorder("Study Parameters"));
		}
		parameterEditPanel.add(paramEdit, BorderLayout.CENTER);

		// Create the interference rule table.

		ixRuleModel = new IxRuleTableModel();
		ixRuleTable = ixRuleModel.createTable(editMenu);

		ixRuleTable.addMouseListener(new MouseAdapter() {
			public void mouseClicked(MouseEvent e) {
				if (2 == e.getClickCount()) {
					doOpenIxRule();
				}
			}
		});

		ixRuleTable.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
			public void valueChanged(ListSelectionEvent theEvent) {
				updateRuleControls();
			}
		});

		JPanel ixRulePanel = new JPanel(new BorderLayout());
		ixRulePanel.setBorder(BorderFactory.createTitledBorder("Interference Rules"));
		ixRulePanel.add(AppController.createScrollPane(ixRuleTable), BorderLayout.CENTER);
		ixRulePanel.add(ixRuleModel.filterPanel, BorderLayout.SOUTH);

		ixRuleEditors = new HashMap<Integer, IxRuleEditor>();

		// Create the scenario table and related.

		scenarioModel = new ScenarioTableModel();
		scenarioTable = scenarioModel.createTable(editMenu);

		scenarioTable.addMouseListener(new MouseAdapter() {
			public void mouseClicked(MouseEvent e) {
				if (2 == e.getClickCount()) {
					doOpenScenario();
				}
			}
		});

		scenarioTable.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
			public void valueChanged(ListSelectionEvent theEvent) {
				updateScenarioControls();
			}
		});

		JPanel scenarioPanel = new JPanel(new BorderLayout());
		scenarioPanel.setBorder(BorderFactory.createTitledBorder("Scenarios"));
		scenarioPanel.add(AppController.createScrollPane(scenarioTable), BorderLayout.CENTER);

		scenarioEditors = new HashMap<Integer, ScenarioEditor>();

		// Create action buttons.

		JButton saveButton = new JButton("Save Study");
		saveButton.setFocusable(false);
		saveButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				saveIfNeeded("Save Study", false);
			}
		});

		JButton saveCloseButton = new JButton("Save & Close");
		saveCloseButton.setFocusable(false);
		saveCloseButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				confirmSave = false;
				if (windowShouldClose()) {
					AppController.hideWindow(outerThis);
				}
			}
		});

		JButton newIxRuleButton = new JButton("New");
		newIxRuleButton.setFocusable(false);
		newIxRuleButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				doNewIxRule();
			}
		});

		if (!study.study.templateLocked) {
			editIxRuleButton = new JButton("Edit");
		} else {
			editIxRuleButton = new JButton("View");
		}
		editIxRuleButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				doOpenIxRule();
			}
		});

		enableIxRuleButton = new JButton("Enable");
		enableIxRuleButton.setFocusable(false);
		enableIxRuleButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				doSetIxRuleActive(true);
			}
		});

		disableIxRuleButton = new JButton("Disable");
		disableIxRuleButton.setFocusable(false);
		disableIxRuleButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				doSetIxRuleActive(false);
			}
		});

		JButton newScenarioButton = new JButton("New");
		newScenarioButton.setFocusable(false);
		newScenarioButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				doNewScenario();
			}
		});

		JButton importScenarioButton = new JButton("Import");
		importScenarioButton.setFocusable(false);
		importScenarioButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				doImportScenarios();
			}
		});

		exportScenarioButton = new JButton("Export");
		exportScenarioButton.setFocusable(false);
		exportScenarioButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				doExportScenarios();
			}
		});

		openScenarioButton = new JButton("Open");
		openScenarioButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				doOpenScenario();
			}
		});

		// Do the layout, major sections for a tab view, first basic study info.

		Box topBox = Box.createVerticalBox();

		JPanel rowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		rowPanel.add(namePanel);
		rowPanel.add(descriptionButton);
		topBox.add(rowPanel);

		if (null != modePanel) {
			rowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
			rowPanel.add(modePanel);
			rowPanel.add(pointPanel);
			topBox.add(rowPanel);
		}

		if (null != areaModePanel) {
			rowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
			rowPanel.add(areaModePanel);
			rowPanel.add(areaGeoPanel);
			topBox.add(rowPanel);
		}

		rowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		rowPanel.add(modelPanel);
		topBox.add(rowPanel);

		rowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		rowPanel.add(configPanel);
		topBox.add(rowPanel);

		JPanel studyEditPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		studyEditPanel.add(topBox);

		// Interference rules.

		JPanel ruleLeftButPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		ruleLeftButPanel.add(newIxRuleButton);

		JPanel ruleRightButPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		ruleRightButPanel.add(enableIxRuleButton);
		ruleRightButPanel.add(disableIxRuleButton);
		ruleRightButPanel.add(editIxRuleButton);

		JPanel ruleButtonPanel = new JPanel();
		ruleButtonPanel.setLayout(new BoxLayout(ruleButtonPanel, BoxLayout.X_AXIS));
		ruleButtonPanel.add(ruleLeftButPanel);
		ruleButtonPanel.add(ruleRightButPanel);

		JPanel ruleEditPanel = new JPanel(new BorderLayout());
		ruleEditPanel.add(ixRulePanel, BorderLayout.CENTER);
		ruleEditPanel.add(ruleButtonPanel, BorderLayout.SOUTH);

		// Scenarios.

		JPanel scenarioLeftButPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		scenarioLeftButPanel.add(newScenarioButton);
		scenarioLeftButPanel.add(importScenarioButton);
		scenarioLeftButPanel.add(exportScenarioButton);

		JPanel scenarioRightButPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		scenarioRightButPanel.add(openScenarioButton);

		JPanel scenarioButtonPanel = new JPanel();
		scenarioButtonPanel.setLayout(new BoxLayout(scenarioButtonPanel, BoxLayout.X_AXIS));
		scenarioButtonPanel.add(scenarioLeftButPanel);
		scenarioButtonPanel.add(scenarioRightButPanel);

		JPanel scenarioEditPanel = new JPanel(new BorderLayout());
		scenarioEditPanel.add(scenarioPanel, BorderLayout.CENTER);
		scenarioEditPanel.add(scenarioButtonPanel, BorderLayout.SOUTH);

		// Put the sections into the tabbed pane, save button always at the bottom.

		editorTabPane = new JTabbedPane();
		editorTabPane.addTab(STUDY_TAB_NAME, studyEditPanel);
		editorTabPane.addTab(SCENARIOS_TAB_NAME, scenarioEditPanel);
		editorTabPane.addTab(PARAMETERS_TAB_NAME, parameterEditPanel);
		editorTabPane.addTab(RULES_TAB_NAME, ruleEditPanel);

		editorTabPane.setSelectedIndex(1);

		// The file menu changes depending on selected tab.

		editorTabPane.getModel().addChangeListener(new ChangeListener() {
			public void stateChanged(ChangeEvent theEvent) {
				updateFileMenu();
			}
		});

		JPanel saveButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		saveButtonPanel.add(saveButton);
		saveButtonPanel.add(saveCloseButton);

		Container cp = getContentPane();
		cp.setLayout(new BorderLayout());
		cp.add(editorTabPane, BorderLayout.CENTER);
		cp.add(saveButtonPanel, BorderLayout.SOUTH);

		pack();

		Dimension theSize = new Dimension(980, 650);
		setMinimumSize(theSize);
		setSize(theSize);

		// Do an initial update of UI state.

		updateGeographies();
		updateOutputConfigMenus();
		updateFileMenu();
		updateDocumentName();
	}


	//=================================================================================================================
	// Table model for the interference rule table.  In-table editing is not supported.

	private class IxRuleTableModel extends AbstractTableModel implements TableFilterModel {

		private static final String IXRULE_COUNTRY_COLUMN = "Ctry D";
		private static final String IXRULE_SERVICE_COLUMN = "Service D";
		private static final String IXRULE_SIGTYPE_COLUMN = "Mod D";
		private static final String IXRULE_BAND_COLUMN = "Band D";
		private static final String IXRULE_USERVICE_COLUMN = "Service U";
		private static final String IXRULE_USIGTYPE_COLUMN = "Mod U";
		private static final String IXRULE_CHANNEL_COLUMN = "Channel U";
		private static final String IXRULE_MASK_COLUMN = "Mask U";
		private static final String IXRULE_OFFSET_COLUMN = "Offset";
		private static final String IXRULE_DISTANCE_COLUMN = "Distance";
		private static final String IXRULE_DU_COLUMN = "D/U";
		private static final String IXRULE_UTIME_COLUMN = "% Time U";

		private String[] columnNamesTVType = {
			IXRULE_COUNTRY_COLUMN,
			IXRULE_SERVICE_COLUMN,
			IXRULE_SIGTYPE_COLUMN,
			IXRULE_BAND_COLUMN,
			IXRULE_USERVICE_COLUMN,
			IXRULE_USIGTYPE_COLUMN,
			IXRULE_CHANNEL_COLUMN,
			IXRULE_MASK_COLUMN,
			IXRULE_OFFSET_COLUMN,
			IXRULE_DISTANCE_COLUMN,
			IXRULE_DU_COLUMN,
			IXRULE_UTIME_COLUMN
		};

		private static final int IXRULE_TVT_COUNTRY_INDEX = 0;
		private static final int IXRULE_TVT_SERVICE_INDEX = 1;
		private static final int IXRULE_TVT_SIGTYPE_INDEX = 2;
		private static final int IXRULE_TVT_BAND_INDEX = 3;
		private static final int IXRULE_TVT_USERVICE_INDEX = 4;
		private static final int IXRULE_TVT_USIGTYPE_INDEX = 5;
		private static final int IXRULE_TVT_CHANNEL_INDEX = 6;
		private static final int IXRULE_TVT_MASK_INDEX = 7;
		private static final int IXRULE_TVT_OFFSET_INDEX = 8;
		private static final int IXRULE_TVT_DISTANCE_INDEX = 9;
		private static final int IXRULE_TVT_DU_INDEX = 10;
		private static final int IXRULE_TVT_UTIME_INDEX = 11;

		private String[] columnNamesTV = {
			IXRULE_COUNTRY_COLUMN,
			IXRULE_SERVICE_COLUMN,
			IXRULE_BAND_COLUMN,
			IXRULE_USERVICE_COLUMN,
			IXRULE_CHANNEL_COLUMN,
			IXRULE_MASK_COLUMN,
			IXRULE_OFFSET_COLUMN,
			IXRULE_DISTANCE_COLUMN,
			IXRULE_DU_COLUMN,
			IXRULE_UTIME_COLUMN
		};

		private static final int IXRULE_TV_COUNTRY_INDEX = 0;
		private static final int IXRULE_TV_SERVICE_INDEX = 1;
		private static final int IXRULE_TV_BAND_INDEX = 2;
		private static final int IXRULE_TV_USERVICE_INDEX = 3;
		private static final int IXRULE_TV_CHANNEL_INDEX = 4;
		private static final int IXRULE_TV_MASK_INDEX = 5;
		private static final int IXRULE_TV_OFFSET_INDEX = 6;
		private static final int IXRULE_TV_DISTANCE_INDEX = 7;
		private static final int IXRULE_TV_DU_INDEX = 8;
		private static final int IXRULE_TV_UTIME_INDEX = 9;

		private String[] columnNamesFM = {
			IXRULE_COUNTRY_COLUMN,
			IXRULE_SERVICE_COLUMN,
			IXRULE_USERVICE_COLUMN,
			IXRULE_CHANNEL_COLUMN,
			IXRULE_DISTANCE_COLUMN,
			IXRULE_DU_COLUMN,
			IXRULE_UTIME_COLUMN
		};

		private static final int IXRULE_FM_COUNTRY_INDEX = 0;
		private static final int IXRULE_FM_SERVICE_INDEX = 1;
		private static final int IXRULE_FM_USERVICE_INDEX = 2;
		private static final int IXRULE_FM_CHANNEL_INDEX = 3;
		private static final int IXRULE_FM_DISTANCE_INDEX = 4;
		private static final int IXRULE_FM_DU_INDEX = 5;
		private static final int IXRULE_FM_UTIME_INDEX = 6;

		private boolean hasTV;
		private boolean showType;

		private NumberFormat doubleFormatter;

		private TableFilterPanel filterPanel;


		//-------------------------------------------------------------------------------------------------------------

		private IxRuleTableModel() {

			super();

			hasTV = (Study.STUDY_TYPE_FM != studyType);
			if (hasTV) {
				showType = SignalType.hasMultipleOptions();
			}

			doubleFormatter = NumberFormat.getInstance(Locale.US);
			doubleFormatter.setMinimumFractionDigits(2);
			doubleFormatter.setMaximumFractionDigits(2);
			doubleFormatter.setMinimumIntegerDigits(1);

			filterPanel = new TableFilterPanel(outerThis, this);
		}


		//-------------------------------------------------------------------------------------------------------------
		// Create and configure a JTable to present the model.  Set appropriate column widths.  Also this gets a custom
		// renderer so inactive rules have a different appearance, and some columns are right-aligned.

		private JTable createTable(EditMenu theEditMenu) {

			JTable theTable = new JTable(this);
			AppController.configureTable(theTable, theEditMenu);
			theTable.setAutoCreateRowSorter(true);

			DefaultTableCellRenderer theRend = new DefaultTableCellRenderer() {
				public Component getTableCellRendererComponent(JTable t, Object o, boolean s, boolean f, int r, int c) {

					JLabel comp = (JLabel)super.getTableCellRendererComponent(t, o, s, f, r, c);

					if (!s) {
						if (get(t.convertRowIndexToModel(r)).isActive) {
							comp.setForeground(Color.BLACK);
						} else {
							comp.setForeground(Color.GRAY.brighter());
						}
					}

					if ((hasTV && showType && ((IXRULE_TVT_DISTANCE_INDEX == c) || (IXRULE_TVT_DU_INDEX == c) ||
								(IXRULE_TVT_UTIME_INDEX == c))) ||
							(hasTV && !showType && ((IXRULE_TV_DISTANCE_INDEX == c) || (IXRULE_TV_DU_INDEX == c) ||
								(IXRULE_TV_UTIME_INDEX == c))) ||
							(!hasTV && ((IXRULE_FM_DISTANCE_INDEX == c) || (IXRULE_FM_DU_INDEX == c) ||
								(IXRULE_FM_UTIME_INDEX == c)))) {
						comp.setHorizontalAlignment(SwingConstants.RIGHT);
					} else {
						comp.setHorizontalAlignment(SwingConstants.LEFT);
					}

					return comp;
				}
			};

			DefaultTableCellRenderer rHeadRend = (DefaultTableCellRenderer)((new JTableHeader()).getDefaultRenderer());
			rHeadRend.setHorizontalAlignment(SwingConstants.RIGHT);

			TableColumn theColumn = theTable.getColumn(IXRULE_COUNTRY_COLUMN);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[3]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[4]);

			theColumn = theTable.getColumn(IXRULE_SERVICE_COLUMN);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[10]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[15]);

			if (hasTV && showType) {
				theColumn = theTable.getColumn(IXRULE_SIGTYPE_COLUMN);
				theColumn.setCellRenderer(theRend);
				theColumn.setMinWidth(AppController.textFieldWidth[3]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[4]);
			}

			if (hasTV) {
				theColumn = theTable.getColumn(IXRULE_BAND_COLUMN);
				theColumn.setCellRenderer(theRend);
				theColumn.setMinWidth(AppController.textFieldWidth[3]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[4]);
			}

			theColumn = theTable.getColumn(IXRULE_USERVICE_COLUMN);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[10]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[15]);

			if (hasTV && showType) {
				theColumn = theTable.getColumn(IXRULE_USIGTYPE_COLUMN);
				theColumn.setCellRenderer(theRend);
				theColumn.setMinWidth(AppController.textFieldWidth[3]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[4]);
			}

			theColumn = theTable.getColumn(IXRULE_CHANNEL_COLUMN);
			theColumn.setCellRenderer(theRend);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[10]);

			if (hasTV) {
				theColumn = theTable.getColumn(IXRULE_MASK_COLUMN);
				theColumn.setCellRenderer(theRend);
				theColumn.setMinWidth(AppController.textFieldWidth[5]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[10]);
			}

			if (hasTV) {
				theColumn = theTable.getColumn(IXRULE_OFFSET_COLUMN);
				theColumn.setCellRenderer(theRend);
				theColumn.setMinWidth(AppController.textFieldWidth[5]);
				theColumn.setPreferredWidth(AppController.textFieldWidth[8]);
			}

			theColumn = theTable.getColumn(IXRULE_DISTANCE_COLUMN);
			theColumn.setCellRenderer(theRend);
			theColumn.setHeaderRenderer(rHeadRend);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[8]);

			theColumn = theTable.getColumn(IXRULE_DU_COLUMN);
			theColumn.setCellRenderer(theRend);
			theColumn.setHeaderRenderer(rHeadRend);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[8]);

			theColumn = theTable.getColumn(IXRULE_UTIME_COLUMN);
			theColumn.setCellRenderer(theRend);
			theColumn.setHeaderRenderer(rHeadRend);
			theColumn.setMinWidth(AppController.textFieldWidth[5]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[8]);

			return theTable;
		}


		//-------------------------------------------------------------------------------------------------------------
		// Methods that change rows are just wrappers around IxRuleListData methods, but also call postChange().  Also
		// have to apply the filter to convert row references from filtered model (here) to/from real model.

		private int addOrReplace(IxRuleEditData theRule) {

			postChange(study.ixRuleData.addOrReplace(theRule));

			int modelIndex = study.ixRuleData.getLastRowChanged();
			if (modelIndex >= 0) {
				return filterPanel.reverseIndex[modelIndex];
			}
			return modelIndex;
		}


		//-------------------------------------------------------------------------------------------------------------

		private IxRuleEditData get(int rowIndex) {

			return study.ixRuleData.get(filterPanel.forwardIndex[rowIndex]);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void remove(int rowIndex) {

			postChange(study.ixRuleData.remove(filterPanel.forwardIndex[rowIndex]));
		}


		//-------------------------------------------------------------------------------------------------------------

		private void remove(int[] rows) {

			int[] modRows = new int[rows.length];
			for (int i = 0; i < rows.length; i++) {
				modRows[i] = filterPanel.forwardIndex[rows[i]];
			}
			postChange(study.ixRuleData.remove(modRows));
		}


		//-------------------------------------------------------------------------------------------------------------

		private void setActive(int rowIndex, boolean theFlag) {

			postChange(study.ixRuleData.setActive(filterPanel.forwardIndex[rowIndex], theFlag));
		}


		//-------------------------------------------------------------------------------------------------------------
		// Called after table data may have changed, fire appropriate change events.  Gets complicated due to the
		// filter, the filter is updated and the filtered model row determined both before and after that update.
		// Which filtered model index matters depends on the type of change.  The model row affected may not be in the
		// filtered model before, after, neither, or both; which means an update may have to be notified as a delete or
		// an insert if the change caused the row to leave or enter the filtered model.

		private void postChange(boolean didChange) {

			if (didChange) {

				int modelIndex = study.ixRuleData.getLastRowChanged();
				int rowBefore = -1, rowAfter = -1;
				if ((modelIndex >= 0) && (modelIndex < filterPanel.reverseIndex.length)) {
					rowBefore = filterPanel.reverseIndex[modelIndex];
				}
				filterPanel.updateFilter();
				if ((modelIndex >= 0) && (modelIndex < filterPanel.reverseIndex.length)) {
					rowAfter = filterPanel.reverseIndex[modelIndex];
				}

				switch (study.ixRuleData.getLastChange()) {

					case ListDataChange.NO_CHANGE:
					default:
						break;

					case ListDataChange.ALL_CHANGE:
						fireTableDataChanged();
						break;

					case ListDataChange.INSERT:
						if (rowAfter >= 0) {
							fireTableRowsInserted(rowAfter, rowAfter);
						}
						break;

					case ListDataChange.UPDATE:
						if (rowBefore >= 0) {
							if (rowAfter >= 0) {
								fireTableRowsUpdated(rowBefore, rowAfter);
							} else {
								fireTableRowsDeleted(rowBefore, rowBefore);
							}
						} else {
							if (rowAfter >= 0) {
								fireTableRowsInserted(rowAfter, rowAfter);
							}
						}
						break;

					case ListDataChange.DELETE:
						if (rowBefore >= 0) {
							fireTableRowsDeleted(rowBefore, rowBefore);
						}
						break;
				}
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		private void dataWasChanged() {

			filterPanel.updateFilter();
			fireTableDataChanged();
		}


		//-------------------------------------------------------------------------------------------------------------

		public void filterDidChange() {

			fireTableDataChanged();
		}


		//-------------------------------------------------------------------------------------------------------------
		// Table model method implementations.

		public int getColumnCount() {

			if (hasTV) {
				if (showType) {
					return columnNamesTVType.length;
				} else {
					return columnNamesTV.length;
				}
			} else {
				return columnNamesFM.length;
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		public String getColumnName(int columnIndex) {

			if (hasTV) {
				if (showType) {
					return columnNamesTVType[columnIndex];
				} else {
					return columnNamesTV[columnIndex];
				}
			} else {
				return columnNamesFM[columnIndex];
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		public boolean filterByColumn(int columnIndex) {

			if (hasTV) {

				if (showType) {

					switch (columnIndex) {
						case IXRULE_TVT_COUNTRY_INDEX:
						case IXRULE_TVT_SERVICE_INDEX:
						case IXRULE_TVT_BAND_INDEX:
						case IXRULE_TVT_USERVICE_INDEX:
						case IXRULE_TVT_CHANNEL_INDEX: {
							return true;
						}
					}

				} else {

					switch (columnIndex) {
						case IXRULE_TV_COUNTRY_INDEX:
						case IXRULE_TV_SERVICE_INDEX:
						case IXRULE_TV_BAND_INDEX:
						case IXRULE_TV_USERVICE_INDEX:
						case IXRULE_TV_CHANNEL_INDEX: {
							return true;
						}
					}
				}

			} else {

				switch (columnIndex) {
					case IXRULE_FM_COUNTRY_INDEX:
					case IXRULE_FM_SERVICE_INDEX:
					case IXRULE_FM_USERVICE_INDEX:
					case IXRULE_FM_CHANNEL_INDEX: {
						return true;
					}
				}
			}

			return false;
		}


		//-------------------------------------------------------------------------------------------------------------

		public boolean collapseFilterChoices(int columnIndex) {

			return false;
		}


		//-------------------------------------------------------------------------------------------------------------

		public int getRowCount() {

			return filterPanel.forwardIndex.length;
		}


		//-------------------------------------------------------------------------------------------------------------

		public int getUnfilteredRowCount() {

			return study.ixRuleData.getRowCount();
		}


		//-------------------------------------------------------------------------------------------------------------

		public Object getValueAt(int rowIndex, int columnIndex) {

			return getUnfilteredValueAt(filterPanel.forwardIndex[rowIndex], columnIndex);
		}


		//-------------------------------------------------------------------------------------------------------------

		public String getUnfilteredValueAt(int rowIndex, int columnIndex) {

			IxRuleEditData theRule = study.ixRuleData.get(rowIndex);

			if (hasTV) {

				if (!showType) {
					switch (columnIndex) {
						case IXRULE_TV_COUNTRY_INDEX: {
							columnIndex = IXRULE_TVT_COUNTRY_INDEX;
							break;
						}
						case IXRULE_TV_SERVICE_INDEX: {
							columnIndex = IXRULE_TVT_SERVICE_INDEX;
							break;
						}
						case IXRULE_TV_BAND_INDEX: {
							columnIndex = IXRULE_TVT_BAND_INDEX;
							break;
						}
						case IXRULE_TV_USERVICE_INDEX: {
							columnIndex = IXRULE_TVT_USERVICE_INDEX;
							break;
						}
						case IXRULE_TV_CHANNEL_INDEX: {
							columnIndex = IXRULE_TVT_CHANNEL_INDEX;
							break;
						}
						case IXRULE_TV_MASK_INDEX: {
							columnIndex = IXRULE_TVT_MASK_INDEX;
							break;
						}
						case IXRULE_TV_OFFSET_INDEX: {
							columnIndex = IXRULE_TVT_OFFSET_INDEX;
							break;
						}
						case IXRULE_TV_DISTANCE_INDEX: {
							columnIndex = IXRULE_TVT_DISTANCE_INDEX;
							break;
						}
						case IXRULE_TV_DU_INDEX: {
							columnIndex = IXRULE_TVT_DU_INDEX;
							break;
						}
						case IXRULE_TV_UTIME_INDEX: {
							columnIndex = IXRULE_TVT_UTIME_INDEX;
							break;
						}
					}
				}

				switch (columnIndex) {

					case IXRULE_TVT_COUNTRY_INDEX: {
						return theRule.country.countryCode;
					}

					case IXRULE_TVT_SERVICE_INDEX: {
						return theRule.serviceType.name;
					}

					case IXRULE_TVT_SIGTYPE_INDEX: {
						if (Source.RECORD_TYPE_TV == theRule.serviceType.recordType) {
							return theRule.signalType.name;
						} else {
							return "";
						}
					}

					case IXRULE_TVT_BAND_INDEX: {
						if (Source.RECORD_TYPE_TV == theRule.serviceType.recordType) {
							return theRule.channelBand.name;
						} else {
							return "";
						}
					}

					case IXRULE_TVT_USERVICE_INDEX: {
						return theRule.undesiredServiceType.name;
					}

					case IXRULE_TVT_USIGTYPE_INDEX: {
						if (Source.RECORD_TYPE_TV == theRule.serviceType.recordType) {
							return theRule.undesiredSignalType.name;
						} else {
							return "";
						}
					}

					case IXRULE_TVT_CHANNEL_INDEX: {
						return theRule.channelDelta.name;
					}

					case IXRULE_TVT_MASK_INDEX: {
						if (Source.RECORD_TYPE_TV == theRule.serviceType.recordType) {
							return theRule.emissionMask.name;
						} else {
							return "";
						}
					}

					case IXRULE_TVT_OFFSET_INDEX: {
						if (Source.RECORD_TYPE_TV == theRule.serviceType.recordType) {
							switch (theRule.frequencyOffset) {
								case 0:
								default: {
									return "(any)";
								}
								case IxRule.FREQUENCY_OFFSET_WITHOUT: {
									return "Without";
								}
								case IxRule.FREQUENCY_OFFSET_WITH: {
									return "With";
								}
							}
						} else {
							return "";
						}
					}

					case IXRULE_TVT_DISTANCE_INDEX: {
						return doubleFormatter.format(theRule.distance);
					}

					case IXRULE_TVT_DU_INDEX: {
						return doubleFormatter.format(theRule.requiredDU);
					}

					case IXRULE_TVT_UTIME_INDEX: {
						return doubleFormatter.format(theRule.undesiredTime);
					}
				}

			} else {

				switch (columnIndex) {

					case IXRULE_FM_COUNTRY_INDEX: {
						return theRule.country.name;
					}

					case IXRULE_FM_SERVICE_INDEX: {
						return theRule.serviceType.name;
					}

					case IXRULE_FM_USERVICE_INDEX: {
						return theRule.undesiredServiceType.name;
					}

					case IXRULE_FM_CHANNEL_INDEX: {
						return theRule.channelDelta.name;
					}

					case IXRULE_FM_DISTANCE_INDEX: {
						return doubleFormatter.format(theRule.distance);
					}

					case IXRULE_FM_DU_INDEX: {
						return doubleFormatter.format(theRule.requiredDU);
					}

					case IXRULE_FM_UTIME_INDEX: {
						return doubleFormatter.format(theRule.undesiredTime);
					}
				}
			}

			return "";
		}
	}


	//=====================================================================================================================
	// Table model for the scenario list.

	private class ScenarioTableModel extends AbstractTableModel {

		private static final String SCENARIO_NAME_COLUMN = "Name";
		private static final String SCENARIO_DESCRIPTION_COLUMN = "Description";

		private String[] columnNames = {
			SCENARIO_NAME_COLUMN,
			SCENARIO_DESCRIPTION_COLUMN
		};

		private static final int SCENARIO_NAME_INDEX = 0;
		private static final int SCENARIO_DESCRIPTION_INDEX = 1;


		//-------------------------------------------------------------------------------------------------------------
		// Create a table to present the model.

		private JTable createTable(EditMenu theEditMenu) {

			JTable theTable = new JTable(this);
			AppController.configureTable(theTable, theEditMenu);
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

		private int addOrReplace(ScenarioEditData theScenario) {

			postChange(study.scenarioData.addOrReplace(theScenario));

			return study.scenarioData.getLastRowChanged();
		}


		//-------------------------------------------------------------------------------------------------------------

		private void remove(int rowIndex) {

			postChange(study.scenarioData.remove(rowIndex));
		}


		//-------------------------------------------------------------------------------------------------------------

		private void remove(int[] rows) {

			postChange(study.scenarioData.remove(rows));
		}


		//-------------------------------------------------------------------------------------------------------------

		private void postChange(boolean didChange) {

			if (didChange) {

				int lastRow = study.scenarioData.getLastRowChanged();

				switch (study.scenarioData.getLastChange()) {

					case ListDataChange.NO_CHANGE:
					default:
						break;

					case ListDataChange.ALL_CHANGE:
						fireTableDataChanged();
						break;

					case ListDataChange.INSERT:
						fireTableRowsInserted(lastRow, lastRow);
						break;

					case ListDataChange.UPDATE:
						fireTableRowsUpdated(lastRow, lastRow);
						break;

					case ListDataChange.DELETE:
						fireTableRowsDeleted(lastRow, lastRow);
						break;
				}
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		private void scenarioWasEdited(ScenarioEditData theScenario) {

			int rowIndex = study.scenarioData.indexOf(theScenario);
			if (rowIndex >= 0) {
				fireTableRowsUpdated(rowIndex, rowIndex);
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		private void dataWasChanged() {

			fireTableDataChanged();
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

			return study.scenarioData.getRowCount();
		}


		//-------------------------------------------------------------------------------------------------------------

		public Object getValueAt(int rowIndex, int columnIndex) {

			ScenarioEditData theScenario = study.scenarioData.get(rowIndex);

			switch (columnIndex) {

				case SCENARIO_NAME_INDEX:
					return theScenario.name;

				case SCENARIO_DESCRIPTION_INDEX:
					return theScenario.description;
			}

			return "";
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create the file menu according to the selected tab in the layout.  Do nothing if selection has not changed.

	private void updateFileMenu() {

		String selectedTabName = editorTabPane.getTitleAt(editorTabPane.getSelectedIndex());
		if (selectedTabName.equals(lastSelectedTabName)) {
			return;
		}
		lastSelectedTabName = selectedTabName;

		fileMenu.removeAll();

		// Save goes in all menus so shortcut will always work.

		JMenuItem miSave = new JMenuItem("Save Study");
		miSave.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, AppController.MENU_SHORTCUT_KEY_MASK));
		miSave.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				saveIfNeeded("Save Study", false);
			}
		});

		if (selectedTabName.equals(STUDY_TAB_NAME)) {

			getRootPane().setDefaultButton(null);

			fileMenu.setText("Study");

			// Save New Template...

			JMenuItem miSaveAsTmpl = new JMenuItem("Save New Template...");
			miSaveAsTmpl.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doSaveNewTemplate();
				}
			});
			fileMenu.add(miSaveAsTmpl);

			// __________________________________

			fileMenu.addSeparator();

			// Geography Editor

			JMenuItem miGeo = new JMenuItem("Geography Editor");
			miGeo.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					StudyManager.showGeographyEditor(getDbID(), study.study.key, study.name, 0, "",
						Geography.MODE_ALL, 0);
				}
			});
			fileMenu.add(miGeo);

			// __________________________________

			fileMenu.addSeparator();

			// Save Study

			fileMenu.add(miSave);

			return;
		}

		// The menu for the Parameters tab.

		if (selectedTabName.equals(PARAMETERS_TAB_NAME)) {

			getRootPane().setDefaultButton(null);

			fileMenu.setText("Parameter");

			// Revert All Parameters

			revertAllParametersMenuItem = new JMenuItem("Revert All Parameters");
			if (study.study.templateLocked) {
				revertAllParametersMenuItem.setEnabled(false);
			} else {
				revertAllParametersMenuItem.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doRevertAllParameters();
					}
				});
			}
			fileMenu.add(revertAllParametersMenuItem);

			// __________________________________

			fileMenu.addSeparator();

			// Save Study

			fileMenu.add(miSave);

			return;
		}

		// The menu for the Rules tab.

		if (selectedTabName.equals(RULES_TAB_NAME)) {

			getRootPane().setDefaultButton(editIxRuleButton);

			fileMenu.setText("Rule");

			// Previous

			JMenuItem miPrevious = new JMenuItem("Previous");
			miPrevious.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_UP, AppController.MENU_SHORTCUT_KEY_MASK));
			miPrevious.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doPreviousRule();
				}
			});
			fileMenu.add(miPrevious);

			// Next

			JMenuItem miNext = new JMenuItem("Next");
			miNext.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, AppController.MENU_SHORTCUT_KEY_MASK));
			miNext.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doNextRule();
				}
			});
			fileMenu.add(miNext);

			// __________________________________

			fileMenu.addSeparator();

			// New...

			JMenuItem miNewRule = new JMenuItem("New...");
			miNewRule.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, AppController.MENU_SHORTCUT_KEY_MASK));
			if (!study.study.templateLocked) {
				miNewRule.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doNewIxRule();
					}
				});
			} else {
				miNewRule.setEnabled(false);
			}
			fileMenu.add(miNewRule);

			// __________________________________

			fileMenu.addSeparator();

			// Edit/View

			if (!study.study.templateLocked) {
				editIxRuleMenuItem = new JMenuItem("Edit");
			} else {
				editIxRuleMenuItem = new JMenuItem("View");
			}
			editIxRuleMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O,
				AppController.MENU_SHORTCUT_KEY_MASK));
			editIxRuleMenuItem.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doOpenIxRule();
				}
			});
			fileMenu.add(editIxRuleMenuItem);

			// Delete

			deleteIxRuleMenuItem = new JMenuItem("Delete");
			if (!study.study.templateLocked) {
				deleteIxRuleMenuItem.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doDeleteIxRule();
					}
				});
			} else {
				deleteIxRuleMenuItem.setEnabled(false);
			}
			fileMenu.add(deleteIxRuleMenuItem);

			// __________________________________

			fileMenu.addSeparator();

			// Enable

			enableIxRuleMenuItem = new JMenuItem("Enable");
			enableIxRuleMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E,
				AppController.MENU_SHORTCUT_KEY_MASK));
			enableIxRuleMenuItem.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doSetIxRuleActive(true);
				}
			});
			fileMenu.add(enableIxRuleMenuItem);

			// Disable

			disableIxRuleMenuItem = new JMenuItem("Disable");
			disableIxRuleMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D,
				AppController.MENU_SHORTCUT_KEY_MASK));
			disableIxRuleMenuItem.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doSetIxRuleActive(false);
				}
			});
			fileMenu.add(disableIxRuleMenuItem);

			// Enable/Disable by Type...

			JMenuItem miEnableDisable = new JMenuItem("Enable/Disable by Type...");
			miEnableDisable.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doEnableDisableRules();
				}
			});
			fileMenu.add(miEnableDisable);

			// __________________________________

			fileMenu.addSeparator();

			// Save Study

			fileMenu.add(miSave);

			updateRuleControls();

			return;
		}

		// The menu for the Scenarios tab.

		if (selectedTabName.equals(SCENARIOS_TAB_NAME)) {

			getRootPane().setDefaultButton(openScenarioButton);

			fileMenu.setText("Scenario");

			// Previous

			JMenuItem miPrevious = new JMenuItem("Previous");
			miPrevious.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_UP, AppController.MENU_SHORTCUT_KEY_MASK));
			miPrevious.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doPreviousScenario();
				}
			});
			fileMenu.add(miPrevious);

			// Next

			JMenuItem miNext = new JMenuItem("Next");
			miNext.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, AppController.MENU_SHORTCUT_KEY_MASK));
			miNext.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doNextScenario();
				}
			});
			fileMenu.add(miNext);

			// __________________________________

			fileMenu.addSeparator();

			// New...

			JMenuItem miNewScenario = new JMenuItem("New...");
			miNewScenario.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N,
				AppController.MENU_SHORTCUT_KEY_MASK));
			miNewScenario.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doNewScenario();
				}
			});
			fileMenu.add(miNewScenario);

			// Duplicate...

			duplicateScenarioMenuItem = new JMenuItem("Duplicate...");
			duplicateScenarioMenuItem.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doDuplicateScenario();
				}
			});
			fileMenu.add(duplicateScenarioMenuItem);

			// __________________________________

			fileMenu.addSeparator();

			// Import...

			JMenuItem miImport = new JMenuItem("Import...");
			miImport.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doImportScenarios();
				}
			});
			fileMenu.add(miImport);

			// Export...

			exportScenarioMenuItem = new JMenuItem("Export...");
			exportScenarioMenuItem.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doExportScenarios();
				}
			});
			fileMenu.add(exportScenarioMenuItem);

			// __________________________________

			fileMenu.addSeparator();

			// Open

			openScenarioMenuItem = new JMenuItem("Open");
			openScenarioMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O,
				AppController.MENU_SHORTCUT_KEY_MASK));
			openScenarioMenuItem.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doOpenScenario();
				}
			});
			fileMenu.add(openScenarioMenuItem);

			// Delete

			deleteScenarioMenuItem = new JMenuItem("Delete");
			deleteScenarioMenuItem.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doDeleteScenarios();
				}
			});
			fileMenu.add(deleteScenarioMenuItem);

			// __________________________________

			fileMenu.addSeparator();

			// Delete Unused Records

			JMenuItem miDeleteUnusedSources = new JMenuItem("Delete Unused Records");
			miDeleteUnusedSources.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doDeleteUnusedSources();
				}
			});
			fileMenu.add(miDeleteUnusedSources);

			// __________________________________

			fileMenu.addSeparator();

			// Geography Editor

			JMenuItem miGeo = new JMenuItem("Geography Editor");
			miGeo.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					StudyManager.showGeographyEditor(getDbID(), study.study.key, study.name, 0, "",
						Geography.MODE_ALL, 0);
				}
			});
			fileMenu.add(miGeo);

			// __________________________________

			fileMenu.addSeparator();

			// Save Study

			fileMenu.add(miSave);

			updateScenarioControls();

			return;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update buttons and menu items related to the interference rule UI based on current table selection.

	private void updateRuleControls() {

		IxRuleEditData theRule;
		int rowCount = ixRuleTable.getSelectedRowCount();
		boolean eEdit = false, eDelete = false, eEnable = false, eDisable = false;

		if (1 == rowCount) {

			eEdit = true;
			eDelete = !study.study.templateLocked;

			theRule = ixRuleModel.get(ixRuleTable.convertRowIndexToModel(ixRuleTable.getSelectedRow()));
			if (theRule.isActive) {
				eDisable = true;
			} else {
				eEnable = true;
			}

		} else {

			if (rowCount > 1) {

				eDelete = !study.study.templateLocked;

				int[] rows = ixRuleTable.getSelectedRows();
				for (int i = 0; i < rows.length; i++) {
					theRule = ixRuleModel.get(ixRuleTable.convertRowIndexToModel(rows[i]));
					if (theRule.isActive) {
						eDisable = true;
						if (eEnable) {
							break;
						}
					} else {
						eEnable = true;
						if (eDisable) {
							break;
						}
					}
				}
			}
		}

		editIxRuleButton.setEnabled(eEdit);
		editIxRuleMenuItem.setEnabled(eEdit);
		deleteIxRuleMenuItem.setEnabled(eDelete);
		enableIxRuleButton.setEnabled(eEnable);
		enableIxRuleMenuItem.setEnabled(eEnable);
		disableIxRuleButton.setEnabled(eDisable);
		disableIxRuleMenuItem.setEnabled(eDisable);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update buttons and menu items related to the scenario UI based on current table selection.

	private void updateScenarioControls() {

		int rowCount = scenarioTable.getSelectedRowCount();
		boolean eOpen = false, eDelete = false, eDuplicate = false, eExport = false;

		if (1 == rowCount) {

			ScenarioEditData theScenario =
				study.scenarioData.get(scenarioTable.convertRowIndexToModel(scenarioTable.getSelectedRow()));

			eOpen = true;
			eDelete = !theScenario.isPermanent;
			eDuplicate = true;
			eExport = true;

			// Scenarios in TV interference-check studies have UI behavior controlled by the scenario type property.
			// Types other than default can't be duplicated, the proposal scenario can't be deleted.

			if (Study.STUDY_TYPE_TV_IX == studyType) {
				if (Scenario.SCENARIO_TYPE_DEFAULT != theScenario.scenarioType) {
					eDuplicate = false;
					if (Scenario.SCENARIO_TYPE_TVIX_PROPOSAL == theScenario.scenarioType) {
						eDelete = false;
					}
				}
			}

		} else {

			if (rowCount > 1) {
				eDelete = true;
				eExport = true;
			}
		}

		openScenarioButton.setEnabled(eOpen);
		openScenarioMenuItem.setEnabled(eOpen);
		deleteScenarioMenuItem.setEnabled(eDelete);
		duplicateScenarioMenuItem.setEnabled(eDuplicate);
		exportScenarioButton.setEnabled(eExport);
		exportScenarioMenuItem.setEnabled(eExport);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Determine if a geography is in use by the as-edited study.

	public boolean isGeographyInUse(int theGeoKey) {

		return study.isGeographyInUse(theGeoKey);
	};


	//-----------------------------------------------------------------------------------------------------------------
	// Update geography lists, called from GeographyEditor.

	public void updateGeographies() {

		blockActionsStart();

		if (null != pointSetMenu) {

			pointSetMenu.removeAllItems();

			ArrayList<KeyedRecord> list = Geography.getGeographyList(getDbID(), study.study.key, 0,
				Geography.MODE_POINTS);
			if (null != list) {
				pointSetMenu.addAllItems(list);
				if ((study.pointSetKey > 0) && pointSetMenu.containsKey(study.pointSetKey)) {
					pointSetMenu.setSelectedKey(study.pointSetKey);
				} else {
					study.pointSetKey = pointSetMenu.getSelectedKey();
				}
			}
		}

		if (null != studyAreaGeographyMenu) {

			studyAreaGeographyMenu.removeAllItems();

			ArrayList<KeyedRecord> list = Geography.getGeographyList(getDbID(), study.study.key, 0,
				Geography.MODE_AREA);
			if (null != list) {
				studyAreaGeographyMenu.addAllItems(list);
				if ((study.studyAreaGeoKey > 0) && studyAreaGeographyMenu.containsKey(study.studyAreaGeoKey)) {
					studyAreaGeographyMenu.setSelectedKey(study.studyAreaGeoKey);
				} else {
					study.studyAreaGeoKey = studyAreaGeographyMenu.getSelectedKey();
				}
			}
		}

		blockActionsEnd();

		for (ScenarioEditor theEditor : scenarioEditors.values()) {
			theEditor.updateGeographies();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This object is the top of an editing hierarchy, the database ID comes from the model object not the parent.

	public String getDbID() {

		return study.dbID;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public RootEditor getRootEditor() {

		return this;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void updateDocumentName() {

		setDocumentName(DbCore.getHostDbName(getDbID()) + "/" + study.name);

		StudyManager.updateGeographyEditor(getDbID(), study.study.key, study.name, 0, "");

		for (IxRuleEditor theEditor : ixRuleEditors.values()) {
			theEditor.updateDocumentName();
		}
		for (ScenarioEditor theEditor : scenarioEditors.values()) {
			theEditor.updateDocumentName();
		}
		if (null != newScenarioFinder) {
			newScenarioFinder.updateDocumentName();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Methods in the StudyLockHolder interface.

	public int getStudyKey() {

		return study.study.key;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int getStudyLock() {

		return study.study.studyLock;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int getLockCount() {

		return study.study.lockCount;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void toFront() {

		super.toFront();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check validity in the model and editor state, see comments in the superclass.  Check for focus in a text field
	// in this window, if found fire the action so the listener commits edits and checks validity.  Next do a similar
	// edit-commit check in all open scenario editors.  If all that passes, finally do validity checks in the model.

	protected boolean isDataValid(String title) {

		if (!super.isDataValid(title)) {
			return false;
		}

		if (!commitCurrentField()) {
			return false;
		}

		for (ScenarioEditor theEditor : scenarioEditors.values()) {
			if (theEditor.isVisible() && !theEditor.isDataValid(title)) {
				return false;
			}
		}

		errorReporter.setTitle(title);

		return study.isDataValid(errorReporter);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check for data changes in preparation for a save.  This must be called prior to calling save(), see below.

	protected boolean isDataChanged() {

		dataChanged = study.isDataChanged();

		return dataChanged;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This method saves as-edited data.  The design of the model classes requires that isDataChanged() methods be
	// called prior to saving data, as those methods have side-effects that set up transient state for the save; so
	// this will fail unless isDataChanged() is called immediately prior and returned true.

	protected boolean save(String title) {

		if (!super.save(title)) {
			dataChanged = false;
			return false;
		}

		if (!dataChanged) {
			return false;
		}
		dataChanged = false;

		errorReporter.setTitle(title);
		boolean ok = false;

		// For TV interference-check study, before saving need to check for particular changes that trigger rebuilds
		// of the actual interference scenarios, and use a portion of the IX check build code to do those rebuilds as
		// needed.  First confirm the proposal scenario exists.  If it does not this is an invalid IX check study,
		// however the study model is otherwise valid so in that case just do a normal save.  If doing the rebuild,
		// first remove all child scenarios from the proposal scenario.  The proposal scenario normally does not have
		// any child scenarios, however IX check studies created before the parent-child structure that were updated
		// in place had all existing IX scenarios set as children of the proposal scenario.

		if ((Study.STUDY_TYPE_TV_IX == studyType) && (study.scenarioData.getRowCount() > 0) &&
				(Scenario.SCENARIO_TYPE_TVIX_PROPOSAL == study.scenarioData.get(0).scenarioType)) {

			ScenarioEditData proposalScenario = study.scenarioData.get(0);
			proposalScenario.removeAllChildScenarios();

			// If the proposal scenario has changed, all others need to be rebuilt.  However changes in that scenario
			// are limited to replacing the source objects, so the test asks the source list object directly (the
			// scenario object would always return true).  Then build list of all scenarios needing rebuild.

			boolean addAll = proposalScenario.sourceData.isScenarioChanged();

			final ArrayList<ScenarioEditData> buildList = new ArrayList<ScenarioEditData>();

			for (ScenarioEditData theScenario : study.scenarioData.getRows()) {
				if ((Scenario.SCENARIO_TYPE_DEFAULT == theScenario.scenarioType) &&
						(addAll || theScenario.isScenarioChanged())) {
					buildList.add(theScenario);
				}
			}

			// Do the rebuilds as needed, that may sometimes take a while so do it on the background thread, also that
			// can be aborted.  Once rebuild is done save the study, that can't be aborted.  Also rebuild the report
			// preamble.  See StudyBuildIxCheck for details.

			BackgroundWorker<Boolean> theWorker = new BackgroundWorker<Boolean>(this, title) {

				private StudyBuildIxCheck.ScenarioBuilder builder;

				protected Boolean doBackgroundWork(ErrorLogger errors) {

					builder = new StudyBuildIxCheck.ScenarioBuilder();
					SourceEditDataTV theSource;
					for (ScenarioEditData theScenario : buildList) {
						if (isCanceled()) {
							return Boolean.FALSE;
						}
						theSource = (SourceEditDataTV)(theScenario.sourceData.getDesiredSource());
						if (null == theSource) {
							errors.reportError("Cannot identify protected record in '" + theScenario.name + "'.");
							return Boolean.FALSE;
						}
						setWaitMessage("Building scenarios for '" + theScenario.name + "'...");
						if (!builder.buildFromScenario(theScenario, null, errors)) {
							return Boolean.FALSE;
						}
					}

					// Re-run validity and data-change checks on the study model to be sure the results of the auto-
					// build are correctly saved (and to catch bugs in the build code that produce invalid data).

					hideCancel();

					if (!study.isDataValid()) {
						return Boolean.FALSE;
					}
					study.isDataChanged();

					setWaitMessage("Saving study, please wait...");
					return Boolean.valueOf(study.save(errors, StudyBuildIxCheck.makeReportPreamble(study)));
				}

				public void cancel() {
					if (null != builder) {
						builder.abort();
					}
				}
			};

			theWorker.showCancel();

			errorReporter.clearMessages();

			Boolean result = theWorker.runWork("Building scenarios, please wait...                  ",
				errorReporter).booleanValue();
			if (null != result) {
				ok = result.booleanValue();
			}

		// Normal save for all other study types.

		} else {

			BackgroundWorker<Boolean> theWorker = new BackgroundWorker<Boolean>(this, title) {
				protected Boolean doBackgroundWork(ErrorLogger errors) {
					return Boolean.valueOf(study.save(errors));
				}
			};

			errorReporter.clearMessages();

			Boolean result = theWorker.runWork("Saving study, please wait...", errorReporter);
			if (null != result) {
				ok = result.booleanValue();
			}
		}

		// If the save succeeded update the document name, inform the parent editor of the change.  There is a chance
		// a name change was not saved because the new name was not unique, that will cause the name to revert to it's
		// previous value, so update the UI name field too just in case.  Also show any non-fatal messages.

		if (ok) {

			updateDocumentName();
			studyNameField.setText(study.name);

			parent.applyEditsFrom(this);

			errorReporter.showMessages();

			return true;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// At the start of any save operation, check to be sure the study record still exists and is locked, and the lock
	// state has not been changed.  If any of that is not true the save cannot occur, changes must be discarded.  But
	// the user can keep the editor open to inspect data, or to save as a template.

	public boolean saveIfNeeded(String title, boolean confirm) {

		String errmsg = null;

		DbConnection db = DbCore.connectDb(getDbID());
		if (null != db) {
			try {

				db.query("SELECT study_lock, lock_count FROM study WHERE study_key = " + study.study.key);
				if (db.next()) {
					if ((Study.LOCK_EDIT != db.getInt(1)) || (study.study.lockCount != db.getInt(2))) {
						errmsg = "The study database lock was modified.";
					}
				} else {
					errmsg = "The study does not exist.";
				}

			} catch (SQLException se) {
				errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
				db.reportError(se);
			}

			DbCore.releaseDb(db);

		} else {
			errmsg = "A connection to the database server cannot be established.";
		}

		if (null != errmsg) {
			AppController.beep();
			if (JOptionPane.YES_OPTION == JOptionPane.showConfirmDialog(this, errmsg +
					"\n\nThe study cannot be saved.  You may keep the window open to view data\n" +
					"or save the study as a template.  Do you want to keep the window open?", title,
					JOptionPane.YES_NO_OPTION, JOptionPane.ERROR_MESSAGE)) {
				return false;
			}
			return true;
		}

		return super.saveIfNeeded(title, confirm);
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doPreviousRule() {

		int rowCount = ixRuleTable.getRowCount();
		int rowIndex = ixRuleTable.getSelectedRow();
		if ((rowCount > 0) && (rowIndex != 0)) {
			if (rowIndex < 0) {
				rowIndex = rowCount - 1;
			} else {
				rowIndex--;
			}
			ixRuleTable.setRowSelectionInterval(rowIndex, rowIndex);
			ixRuleTable.scrollRectToVisible(ixRuleTable.getCellRect(rowIndex, 0, true));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doNextRule() {

		int rowCount = ixRuleTable.getRowCount();
		int rowIndex = ixRuleTable.getSelectedRow();
		if ((rowCount > 0) && (rowIndex < (rowCount - 1))) {
			if (rowIndex < 0) {
				rowIndex = 0;
			} else {
				rowIndex++;
			}
			ixRuleTable.setRowSelectionInterval(rowIndex, rowIndex);
			ixRuleTable.scrollRectToVisible(ixRuleTable.getCellRect(rowIndex, 0, true));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doNewIxRule() {

		if (study.study.templateLocked) {
			return;
		}

		int recordType = Source.RECORD_TYPE_TV;
		if (Study.STUDY_TYPE_FM == studyType) {
			recordType = Source.RECORD_TYPE_FM;
		} else {
			if (Study.STUDY_TYPE_TV6_FM == studyType) {
				recordType = 0;
			}
		}

		Integer theKey = study.getNewIxRuleKey();
		IxRuleEditor theEditor = new IxRuleEditor(this, recordType, new IxRuleEditData(theKey));
		AppController.showWindow(theEditor);
		ixRuleEditors.put(theKey, theEditor);
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doOpenIxRule() {

		if (ixRuleTable.getSelectedRowCount() != 1) {
			return;
		}

		int rowIndex = ixRuleTable.convertRowIndexToModel(ixRuleTable.getSelectedRow());
		IxRuleEditData theRule = ixRuleModel.get(rowIndex);

		IxRuleEditor theEditor = ixRuleEditors.get(theRule.key);
		if (null != theEditor) {
			if (theEditor.isVisible()) {
				theEditor.toFront();
				return;
			}
			ixRuleEditors.remove(theRule.key);
		}

		theEditor = new IxRuleEditor(this, theRule.serviceType.recordType, theRule);
		AppController.showWindow(theEditor);
		ixRuleEditors.put(theRule.key, theEditor);
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doDeleteIxRule() {

		if (study.study.templateLocked) {
			return;
		}

		if (0 == ixRuleTable.getSelectedRowCount()) {
			return;
		}

		int[] selRows = ixRuleTable.getSelectedRows();
		int[] rows = new int[selRows.length];
		IxRuleEditData theRule;
		IxRuleEditor theEditor;

		for (int i = 0; i < selRows.length; i++) {

			rows[i] = ixRuleTable.convertRowIndexToModel(selRows[i]);

			theRule = ixRuleModel.get(rows[i]);
			theEditor = ixRuleEditors.get(theRule.key);
			if (null != theEditor) {
				if (theEditor.isVisible() && !theEditor.cancel()) {
					AppController.beep();
					theEditor.toFront();
					return;
				}
				ixRuleEditors.remove(theRule.key);
			}
		}

		ixRuleModel.remove(rows);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Rules may be enabled and disabled even if templateLocked is true for the study.  Although the point of that flag
	// is to ensure all studies using the same template behave the same, in this case the effect of disabling rules
	// could just be achieved by removing records from the study scenarios.  Even with a locked template different
	// studies can have different scenarios and so different results, so there is no point to blocking this action.

	private void doSetIxRuleActive(boolean theFlag) {

		if (0 == ixRuleTable.getSelectedRowCount()) {
			return;
		}

		int[] selRows = ixRuleTable.getSelectedRows();
		int rowIndex;
		IxRuleEditor theEditor;

		for (int i = 0; i < selRows.length; i++) {

			rowIndex = ixRuleTable.convertRowIndexToModel(selRows[i]);
			ixRuleModel.setActive(rowIndex, theFlag);

			theEditor = ixRuleEditors.get(ixRuleModel.get(rowIndex).key);
			if (null != theEditor) {
				theEditor.updateDocumentName();
			}
		}

		updateRuleControls();
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doEnableDisableRules() {

		IxRuleEnableDisable theDialog = new IxRuleEnableDisable();
		AppController.showWindow(theDialog);
		if (theDialog.canceled) {
			return;
		}

		ixRuleModel.dataWasChanged();

		updateRuleControls();
	}


	//=================================================================================================================
	// Dialog to enter criteria for enabling/disabling interference rule by various category types.

	private class IxRuleEnableDisable extends AppDialog {

		private JComboBox<String> enableDisableMenu;

		private KeyedRecordMenu countryMenu;
		private KeyedRecordMenu serviceTypeMenu;
		private KeyedRecordMenu undesiredServiceTypeMenu;
		private KeyedRecordMenu channelDeltaMenu;
		private KeyedRecordMenu channelBandMenu;

		private JButton okButton;
		private JButton cancelButton;

		private boolean canceled;


		//-------------------------------------------------------------------------------------------------------------

		private IxRuleEnableDisable() {

			super(outerThis, "Enable/Disable Interference Rules", Dialog.ModalityType.APPLICATION_MODAL);

			boolean hasTV = (Study.STUDY_TYPE_FM != studyType);

			int recordType = Source.RECORD_TYPE_TV;
			if (Study.STUDY_TYPE_FM == studyType) {
				recordType = Source.RECORD_TYPE_FM;
			} else {
				if (Study.STUDY_TYPE_TV6_FM == studyType) {
					recordType = 0;
				}
			}

			// Menu to determine if matched rules are enabled or disabled.

			enableDisableMenu = new JComboBox<String>();
			enableDisableMenu.setFocusable(false);
			enableDisableMenu.addItem("Disable");
			enableDisableMenu.addItem("Enable");

			JPanel enDisPanel = new JPanel();
			enDisPanel.add(new JLabel("Action"));
			enDisPanel.add(enableDisableMenu);

			// Create the UI components for matching rules.  Country first.  All menus have one additional initial item
			// meaning "match all", label is just blank.

			ArrayList<KeyedRecord> menuItems = new ArrayList<KeyedRecord>();
			menuItems.add(new KeyedRecord(-1, ""));
			menuItems.addAll(Country.getCountries());
			countryMenu = new KeyedRecordMenu(menuItems);

			JPanel countryPanel = new JPanel();
			countryPanel.setBorder(BorderFactory.createTitledBorder("Country"));
			countryPanel.add(countryMenu);

			// Desired service type.  Note in the actual rule editor there may be interactions between the settings but
			// those are not implemented here; this is just a search template, if an invalid pattern is provided it
			// won't match anything, so what.

			menuItems.clear();
			menuItems.add(new KeyedRecord(-1, ""));
			menuItems.addAll(ServiceType.getServiceTypes(recordType));
			serviceTypeMenu = new KeyedRecordMenu(menuItems);

			JPanel serviceTypePanel = new JPanel();
			serviceTypePanel.setBorder(BorderFactory.createTitledBorder("Service"));
			serviceTypePanel.add(serviceTypeMenu);

			// Undesired service type (items are the same as previous).

			undesiredServiceTypeMenu = new KeyedRecordMenu(menuItems);

			JPanel undesiredServiceTypePanel = new JPanel();
			undesiredServiceTypePanel.setBorder(BorderFactory.createTitledBorder("Service"));
			undesiredServiceTypePanel.add(undesiredServiceTypeMenu);

			// Channel delta.

			menuItems.clear();
			menuItems.add(new KeyedRecord(-1, ""));
			menuItems.addAll(ChannelDelta.getChannelDeltas(recordType));
			channelDeltaMenu = new KeyedRecordMenu(menuItems);

			JPanel channelDeltaPanel = new JPanel();
			channelDeltaPanel.setBorder(BorderFactory.createTitledBorder("Channel"));
			channelDeltaPanel.add(channelDeltaMenu);

			// Channel band, only for TV.

			JPanel channelBandPanel = null;

			if (hasTV) {

				menuItems.clear();
				menuItems.add(new KeyedRecord(-1, ""));
				menuItems.addAll(ChannelBand.getChannelBandsWithNull());
				channelBandMenu = new KeyedRecordMenu(menuItems);

				channelBandPanel = new JPanel();
				channelBandPanel.setBorder(BorderFactory.createTitledBorder("Band"));
				channelBandPanel.add(channelBandMenu);
			}

			// Buttons.

			okButton = new JButton("OK");
			okButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doOK();
				}
			});

			cancelButton = new JButton("Cancel");
			cancelButton.setFocusable(false);
			cancelButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doCancel();
				}
			});

			// Do the layout.

			JPanel row1Panel = new JPanel();
			row1Panel.add(countryPanel);
			row1Panel.add(serviceTypePanel);
			if (hasTV) {
				row1Panel.add(channelBandPanel);
			}
			row1Panel.setBorder(BorderFactory.createTitledBorder("Desired Station"));

			JPanel row2Panel = new JPanel();
			row2Panel.add(undesiredServiceTypePanel);
			row2Panel.add(channelDeltaPanel);
			row2Panel.setBorder(BorderFactory.createTitledBorder("Undesired Station"));

			JPanel editorPanel = new JPanel();
			editorPanel.setLayout(new BoxLayout(editorPanel, BoxLayout.Y_AXIS));
			editorPanel.add(enDisPanel);
			editorPanel.add(row1Panel);
			editorPanel.add(row2Panel);

			JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
			buttonPanel.add(cancelButton);
			buttonPanel.add(okButton);

			Container cp = getContentPane();
			cp.setLayout(new BorderLayout());
			cp.add(editorPanel, BorderLayout.CENTER);
			cp.add(buttonPanel, BorderLayout.SOUTH);

			getRootPane().setDefaultButton(okButton);

			pack();

			setMinimumSize(getSize());

			setResizable(true);
			setLocationSaved(true);
		}


		//-------------------------------------------------------------------------------------------------------------

		public void updateDocumentName() {

			setDocumentName(outerThis.getDocumentName());
		}


		//-------------------------------------------------------------------------------------------------------------
		// Note this enables/disables all matching rules in the underlying data model (study.ixRuleData) regardless of
		// the current filtered state of the local table model (see IxRuleTableModel).

		private void doOK() {

			boolean theFlag = false;
			if (enableDisableMenu.getSelectedIndex() > 0) {
				theFlag = true;
			}

			int countryKey = countryMenu.getSelectedKey();
			int serviceTypeKey = serviceTypeMenu.getSelectedKey();
			int undesiredServiceTypeKey = undesiredServiceTypeMenu.getSelectedKey();
			int channelDeltaKey = channelDeltaMenu.getSelectedKey();
			int channelBandKey = -1;
			if (null != channelBandMenu) {
				channelBandMenu.getSelectedKey();
			}

			int i, n = study.ixRuleData.getRowCount();
			IxRuleEditData theRule;

			for (i = 0; i < n; i++) {

				theRule = study.ixRuleData.get(i);

				if ((countryKey >= 0) && (theRule.country.key != countryKey)) {
					continue;
				}
				if ((serviceTypeKey >= 0) && (theRule.serviceType.key != serviceTypeKey)) {
					continue;
				}
				if ((undesiredServiceTypeKey >= 0) && (theRule.undesiredServiceType.key != undesiredServiceTypeKey)) {
					continue;
				}
				if ((channelDeltaKey >= 0) && (theRule.channelDelta.key != channelDeltaKey)) {
					continue;
				}
				if ((channelBandKey >= 0) && (theRule.channelBand.key != channelBandKey)) {
					continue;
				}

				study.ixRuleData.setActive(i, theFlag);
			}

			canceled = false;

			blockActionsSet();
			AppController.hideWindow(this);
		}


		//-------------------------------------------------------------------------------------------------------------

		private boolean cancel() {

			doCancel();
			return canceled;
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doCancel() {

			canceled = true;

			blockActionsSet();
			AppController.hideWindow(this);
		}


		//-------------------------------------------------------------------------------------------------------------

		public void windowWillOpen() {

			enableDisableMenu.setSelectedIndex(0);

			countryMenu.setSelectedIndex(0);
			serviceTypeMenu.setSelectedIndex(0);
			undesiredServiceTypeMenu.setSelectedIndex(0);
			channelDeltaMenu.setSelectedIndex(0);
			if (null != channelBandMenu) {
				channelBandMenu.setSelectedIndex(0);
			}

			setLocationRelativeTo(outerThis);

			canceled = false;

			blockActionsClear();
		}


		//-------------------------------------------------------------------------------------------------------------

		public boolean windowShouldClose() {

			canceled = true;

			blockActionsSet();
			return true;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doPreviousScenario() {

		int rowCount = scenarioTable.getRowCount();
		int rowIndex = scenarioTable.getSelectedRow();
		if ((rowCount > 0) && (rowIndex != 0)) {
			if (rowIndex < 0) {
				rowIndex = rowCount - 1;
			} else {
				rowIndex--;
			}
			scenarioTable.setRowSelectionInterval(rowIndex, rowIndex);
			scenarioTable.scrollRectToVisible(scenarioTable.getCellRect(rowIndex, 0, true));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doNextScenario() {

		int rowCount = scenarioTable.getRowCount();
		int rowIndex = scenarioTable.getSelectedRow();
		if ((rowCount > 0) && (rowIndex < (rowCount - 1))) {
			if (rowIndex < 0) {
				rowIndex = 0;
			} else {
				rowIndex++;
			}
			scenarioTable.setRowSelectionInterval(rowIndex, rowIndex);
			scenarioTable.scrollRectToVisible(scenarioTable.getCellRect(rowIndex, 0, true));
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create a new scenario.  Depending on study type this may show a dialog to obtain the scenario name (must always
	// be provided), or a RecordFind window to select a target TV record for the scenario along with the scenario name
	// in an accessory panel.  If a RecordFind window is shown, the scenario creation happens in applyEditsFrom().

	private void doNewScenario() {

		if ((null != newScenarioFinder) && newScenarioFinder.isVisible()) {
			newScenarioFinder.toFront();
			return;
		}

		String title = "New Scenario";
		errorReporter.setTitle(title);

		if ((Study.STUDY_TYPE_TV_IX == studyType) || (Study.STUDY_TYPE_TV_OET74 == studyType) ||
				(Study.STUDY_TYPE_TV6_FM == studyType)) {

			newScenarioFinder = new RecordFind(this, title, 0, Source.RECORD_TYPE_TV);
			newScenarioFinder.setDefaultExtDbKey(study.study.extDbKey);
			if (Study.STUDY_TYPE_TV6_FM == studyType) {
				newScenarioFinder.setNote("Select desired TV channel 6 station for the new scenario");
			} else {
				newScenarioFinder.setNote("Select desired TV station for the new scenario");
			}
			newScenarioFinder.setAccessoryPanel(new OptionsPanel.NewScenario(this));
			newScenarioFinder.setApply(new String[] {"Create"}, true, true);

			AppController.showWindow(newScenarioFinder);
	
			return;
		}

		String theName = "";
		do {
			theName = JOptionPane.showInputDialog(this, "Enter a name for the new scenario", title,
				JOptionPane.QUESTION_MESSAGE);
			if (null == theName) {
				return;
			}
			theName = theName.trim();
		} while (!DbCore.checkScenarioName(theName, study, errorReporter));

		ScenarioEditData theScenario = new ScenarioEditData(study);
		theScenario.name = theName;

		int rowIndex = scenarioModel.addOrReplace(theScenario);

		if (rowIndex >= 0) {

			rowIndex = scenarioTable.convertRowIndexToView(rowIndex);
			scenarioTable.setRowSelectionInterval(rowIndex, rowIndex);
			scenarioTable.scrollRectToVisible(scenarioTable.getCellRect(rowIndex, 0, true));

			doOpenScenario();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Duplicate a scenario, prompt for a new name, make a copy with a new key, open it in an editor.

	private void doDuplicateScenario() {

		if (scenarioTable.getSelectedRowCount() != 1) {
			return;
		}

		int rowIndex = scenarioTable.convertRowIndexToModel(scenarioTable.getSelectedRow());
		ScenarioEditData theScenario = study.scenarioData.get(rowIndex);

		String title = "Duplicate Scenario";
		errorReporter.setTitle(title);

		String theName = "";
		do {
			theName = (String)(JOptionPane.showInputDialog(this, "Enter a name for the new scenario", title,
				JOptionPane.QUESTION_MESSAGE, null, null, theScenario.name));
			if (null == theName) {
				return;
			}
			theName = theName.trim();
		} while (!DbCore.checkScenarioName(theName, study, errorReporter));

		theScenario = theScenario.duplicate(theName, errorReporter);
		if (null == theScenario) {
			return;
		}

		rowIndex = scenarioModel.addOrReplace(theScenario);

		if (rowIndex >= 0) {

			rowIndex = scenarioTable.convertRowIndexToView(rowIndex);
			scenarioTable.setRowSelectionInterval(rowIndex, rowIndex);
			scenarioTable.scrollRectToVisible(scenarioTable.getCellRect(rowIndex, 0, true));

			doOpenScenario();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Import scenarios from XML file.  Because the import may involved database queries to look up external data
	// records, it runs on a secondary thread to avoid locking up the UI.

	private void doImportScenarios() {

		String title = "Import Scenarios";
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

		// Prompt the user to select station data sets to be used to resolve record references in the imported data.
		// Only TV and FM data sets may be selected, and only if the record type is allowed in the study.  Wireless
		// records can never be by-reference in XML because the record IDs in wireless data sets are unique only for
		// one specific wireless data import, making cross-set references impossible.  A primary and an alternate set
		// may be selected, the main purpose for that is to allow both a CDBS and LMS set to be selected for TV so
		// either type of record ID can be resolved.  The primary set is always checked first.  See PickExtDbDialog.

		ArrayList<KeyedRecord> list = new ArrayList<KeyedRecord>(), addList = null;

		if (Study.isRecordTypeAllowed(studyType, Source.RECORD_TYPE_TV)) {
			addList = ExtDb.getExtDbList(getDbID(), Source.RECORD_TYPE_TV, errorReporter);
			if (null == addList) {
				return;
			}
			list.addAll(addList);
		}

		if (Study.isRecordTypeAllowed(studyType, Source.RECORD_TYPE_FM)) {
			addList = ExtDb.getExtDbList(getDbID(), Source.RECORD_TYPE_FM, errorReporter);
			if (null == addList) {
				return;
			}
			list.addAll(addList);
		}

		PickExtDbDialog promptDialog = new PickExtDbDialog(outerThis, title, list, study.study.extDbKey);
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

		BackgroundWorker<Integer> theWorker = new BackgroundWorker<Integer>(this, title) {
			protected Integer doBackgroundWork(ErrorLogger errors) {
				return study.readScenariosFromXML(lookupExtDbKey, alternateExtDbKey, xml, errors);
			}
		};

		errorReporter.clearMessages();

		Integer count = theWorker.runWork("Importing scenarios, please wait...", errorReporter);

		try {xml.close();} catch (IOException ie) {}

		if (null != count) {
			scenarioModel.dataWasChanged();
			errorReporter.showMessages();
			errorReporter.reportMessage(String.valueOf(count) + " scenarios imported.");
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Export selected scenario(s) to an XML file.  If the file already exists, prompt before replacing.

	private void doExportScenarios() {

		if (0 == scenarioTable.getSelectedRowCount()) {
			return;
		}

		String title = "Export Scenarios";
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

		String theName = theFile.getName().toLowerCase();
		if (!theName.endsWith(".xml")) {
			theFile = new File(theFile.getAbsolutePath() + ".xml");
		}

		FileWriter theWriter = null;
		try {
			theWriter = new FileWriter(theFile);
		} catch (IOException ie) {
			errorReporter.reportError("Could not open the file:\n" + ie.getMessage());
			return;
		}

		final BufferedWriter xml = new BufferedWriter(theWriter);

		final ArrayList<ScenarioEditData> theScenarios = new ArrayList<ScenarioEditData>();
		int[] rows = scenarioTable.getSelectedRows();
		for (int i = 0; i < rows.length; i++) {
			theScenarios.add(study.scenarioData.get(scenarioTable.convertRowIndexToModel(rows[i])));
		}

		BackgroundWorker<Object> theWorker = new BackgroundWorker<Object>(this, title) {
			protected Object doBackgroundWork(ErrorLogger errors) {
				study.writeScenariosToXML(xml, theScenarios, errors);
				return null;
			}
		};

		theWorker.runWork("Exporting scenarios, please wait...", errorReporter);

		try {xml.close();} catch (IOException ie) {}
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doOpenScenario() {

		if (scenarioTable.getSelectedRowCount() != 1) {
			return;
		}

		int rowIndex = scenarioTable.convertRowIndexToModel(scenarioTable.getSelectedRow());
		ScenarioEditData theScenario = study.scenarioData.get(rowIndex);

		ScenarioEditor theEditor = scenarioEditors.get(theScenario.key);
		if (null != theEditor) {
			if (theEditor.isVisible()) {
				theEditor.toFront();
				return;
			}
			scenarioEditors.remove(theScenario.key);
		}

		theEditor = new ScenarioEditor(this, theScenario);
		AppController.showWindow(theEditor);
		scenarioEditors.put(theScenario.key, theEditor);
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doDeleteScenarios() {

		if (0 == scenarioTable.getSelectedRowCount()) {
			return;
		}

		String title = "Delete Scenarios";
		errorReporter.setTitle(title);

		int[] selRows = scenarioTable.getSelectedRows();
		if (selRows.length > 1) {
			AppController.beep();
			if (JOptionPane.YES_OPTION != JOptionPane.showConfirmDialog(this,
					"Are you sure you want to delete multiple scenarios?", title, JOptionPane.YES_NO_OPTION,
					JOptionPane.WARNING_MESSAGE)) {
				return;
			}
		}

		ArrayList<Integer> toDelete = new ArrayList<Integer>();

		int i, rowIndex;
		ScenarioEditData theScenario;
		boolean hasPerm = false;
		ScenarioEditor theEditor;

		for (i = 0; i < selRows.length; i++) {
			rowIndex = scenarioTable.convertRowIndexToModel(selRows[i]);
			theScenario = study.scenarioData.get(rowIndex);
			if (theScenario.isPermanent || (Scenario.SCENARIO_TYPE_TVIX_PROPOSAL == theScenario.scenarioType)) {
				hasPerm = true;
			} else {
				theEditor = scenarioEditors.get(theScenario.key);
				if (null != theEditor) {
					if (theEditor.isVisible() && !theEditor.closeWithoutSave()) {
						AppController.beep();
						theEditor.toFront();
						return;
					}
					scenarioEditors.remove(theScenario.key);
				}
				toDelete.add(Integer.valueOf(rowIndex));
			}
		}

		if (hasPerm) {
			errorReporter.reportWarning("Some scenarios cannot be deleted.");
		}

		if (toDelete.isEmpty()) {
			return;
		}

		int[] rows = new int[toDelete.size()];
		for (i = 0; i < toDelete.size(); i++) {
			rows[i] = toDelete.get(i).intValue();
		}

		scenarioModel.remove(rows);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Delete sources that are not part of any current scenario, see StudyEditData.removeAllUnusedSources().

	private void doDeleteUnusedSources() {

		String title = "Delete Unused Records";
		errorReporter.setTitle(title);

		int count = study.getUnusedSourceCount();
		if (0 == count) {
			errorReporter.reportMessage("There are no unused records in the study.");
			return;
		}

		AppController.beep();
		if (JOptionPane.YES_OPTION != JOptionPane.showConfirmDialog(this,
				"Station records in the study that do not appear in any existing scenario will be\n" +
				"deleted.  The records will be re-created if needed again, however any calculation\n" +
				"results for those records currently in cache files will have to be re-computed.\n\n" +
				"Are you sure you want to delete unused records?", title, JOptionPane.YES_NO_OPTION,
				JOptionPane.QUESTION_MESSAGE)) {
			return;
		}

		study.removeAllUnusedSources();

		errorReporter.reportMessage("Deleted " + count + " records from the study.");
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Revert all parameters to template values.  Since an inadvertent use of this might be rather upsetting to the
	// user given that there is no general "undo", get confirmation.

	private void doRevertAllParameters() {

		if (study.study.templateLocked) {
			return;
		}

		AppController.beep();
		if (JOptionPane.YES_OPTION != JOptionPane.showConfirmDialog(this,
				"All unlocked study parameters will be set to default values\n" +
				"from the study template.  Are you sure you want to do this?", "Revert All Parameters",
				JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE)) {
			return;
		}

		for (ParameterEditor theEditor : parameterEditors) {
			theEditor.setDefaultValue(true);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Save the current parameters and rules as a new template for creating new studies.  Prompt for a template name,
	// the name must be unique.

	private void doSaveNewTemplate() {

		String title = "Save New Template";
		errorReporter.setTitle(title);

		String theName = "";
		int theKey = 0;

		do {
			theName = JOptionPane.showInputDialog(this, "Enter a name for the new template", title,
				JOptionPane.QUESTION_MESSAGE);
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

		TemplateEditData.createNewTemplateFromStudy(theName, study, errorReporter);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Show a pop-up text dialog to edit/input description text.

	private void doEditDescription() {

		TextInputDialog theDialog = new TextInputDialog(this, "Study Description", "Study Description");
		theDialog.setInput(study.description);

		AppController.showWindow(theDialog);
		if (theDialog.canceled) {
			return;
		}

		study.description = theDialog.getInput();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Open an OutputConfigDialog to edit the output file and map output configurations.

	private void doEditOutputConfigs() {

		ArrayList<OutputConfig> theList = new ArrayList<OutputConfig>();
		theList.add(study.fileOutputConfig);
		theList.add(study.mapOutputConfig);

		OutputConfigDialog theDialog = new OutputConfigDialog(this, theList, true);
		AppController.showWindow(theDialog);

		// New state is applied even if dialog is cancelled, see discussion in OutputConfigDialog.

		for (OutputConfig theConfig : theDialog.getConfigs()) {
			switch (theConfig.type) {
				case OutputConfig.CONFIG_TYPE_FILE: {
					study.fileOutputConfig = theConfig;
					break;
				}
				case OutputConfig.CONFIG_TYPE_MAP: {
					study.mapOutputConfig = theConfig;
					break;
				}
			}
		}

		updateOutputConfigMenus();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Re-populate the output file and map output menus and select objects, which may or may not be in the saved list.

	private void updateOutputConfigMenus() {

		blockActionsStart();

		ArrayList<OutputConfig> theList = OutputConfig.getConfigs(getDbID(), OutputConfig.CONFIG_TYPE_FILE);
		if (!theList.contains(study.fileOutputConfig)) {
			theList.add(0, study.fileOutputConfig);
		}
		if (!study.fileOutputConfig.isNull()) {
			theList.add(0, OutputConfig.getNullObject(OutputConfig.CONFIG_TYPE_FILE));
		}

		fileOutputConfigMenu.removeAllItems();
		for (OutputConfig config : theList) {
			fileOutputConfigMenu.addItem(config);
		}
		fileOutputConfigMenu.setSelectedItem(study.fileOutputConfig);

		theList = OutputConfig.getConfigs(getDbID(), OutputConfig.CONFIG_TYPE_MAP);
		if (!theList.contains(study.mapOutputConfig)) {
			theList.add(0, study.mapOutputConfig);
		}
		if (!study.mapOutputConfig.isNull()) {
			theList.add(0, OutputConfig.getNullObject(OutputConfig.CONFIG_TYPE_MAP));
		}

		mapOutputConfigMenu.removeAllItems();
		for (OutputConfig config : theList) {
			mapOutputConfigMenu.addItem(config);
		}
		mapOutputConfigMenu.setSelectedItem(study.mapOutputConfig);

		blockActionsEnd();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by dependent windows, that is rule and scenario editors, when editing action is applied by user.  For an
	// interference rule edit, it may be creating a new rule or editing an existing one, always check first for rule
	// uniqueness in the model.

	public boolean applyEditsFrom(AppEditor theEditor) {

		if (theEditor instanceof IxRuleEditor) {

			if (study.study.templateLocked) {
				return false;
			}

			IxRuleEditor ruleEditor = (IxRuleEditor)theEditor;
			IxRuleEditData theRule = ruleEditor.getIxRule();
			if (ruleEditor != ixRuleEditors.get(theRule.key)) {
				return false;
			}

			if (study.ixRuleData.isIxRuleUnique(theRule, ruleEditor.getErrorReporter())) {

				int rowIndex = ixRuleModel.addOrReplace(theRule);

				if (rowIndex >= 0) {
					rowIndex = ixRuleTable.convertRowIndexToView(rowIndex);
					ixRuleTable.setRowSelectionInterval(rowIndex, rowIndex);
					ixRuleTable.scrollRectToVisible(ixRuleTable.getCellRect(rowIndex, 0, true));
				}

				return true;
			}

			return false;
		}

		// Scenario editors make changes directly to the model object, there is no "cancel" concept in those editors
		// so this is not really an apply since that has already happened, this just refreshes the local display.

		if (theEditor instanceof ScenarioEditor) {

			ScenarioEditor scenarioEditor = (ScenarioEditor)theEditor;
			if (scenarioEditor != scenarioEditors.get(scenarioEditor.getScenario().key)) {
				return false;
			}

			scenarioModel.scenarioWasEdited(scenarioEditor.getScenario());
			return true;
		}

		// If the new-scenario start dialog sent this, retrieve the selected record from the dialog and finish the
		// process of creating the new scenario.  Check the options panel inputs for validity.

		if (theEditor instanceof RecordFind) {

			if ((RecordFind)theEditor != newScenarioFinder) {
				return false;
			}

			ErrorReporter theReporter = newScenarioFinder.getErrorReporter();
			OptionsPanel theOptions = (OptionsPanel)(newScenarioFinder.getAccessoryPanel());

			if (!DbCore.checkScenarioName(theOptions.name, study, theReporter)) {
				return false;
			}

			SourceEditData theSource = newScenarioFinder.source;
			ExtDbRecord theRecord = newScenarioFinder.record;

			int theType = 0;
			if (null != theSource) {
				theType = theSource.recordType;
			} else {
				if (null != theRecord) {
					theType = theRecord.recordType;
				}
			}

			if (Source.RECORD_TYPE_TV != theType) {
				theReporter.reportError("That record type is not allowed here.");
				return false;
			}

			// Check the channel against the legal range for the study.  The search dialog does not restrict the
			// channel range for search because an out-of-range record may be replicated to an in-range channel.  If
			// the record will be replicated set up for that, and check the replication channel not the original.  This
			// supports automatic replication, see ExtDbRecordTV for details.

			int replicateToChannel = 0;

			int minChannel = study.getMinimumChannel(), maxChannel = study.getMaximumChannel();

			if (theOptions.replicate) {

				replicateToChannel = theOptions.replicationChannel;
				if ((replicateToChannel < minChannel) || (replicateToChannel > maxChannel)) {
					theReporter.reportWarning("The replication channel must be in the range " + minChannel + " to " +
						maxChannel + ".");
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
					theReporter.reportWarning("The record must have a channel in the range " + minChannel + " to " +
						maxChannel + ", or be replicated to a channel in range.");
					return false;
				}
			}

			// Create the scenario, prepare to create and add the source.

			ScenarioEditData theScenario = new ScenarioEditData(study);
			theScenario.name = theOptions.name;

			SourceEditData newSource = null;
			SourceEditDataTV originalSource = null;

			theReporter.clearErrors();
			theReporter.clearMessages();

			// A source object from the find may be a new source or one loaded from the user record table.  If a user
			// record it is shareable (user records are always locked), else for a new record derive a new source to
			// set the study and leave the source unlocked.  For an external record, if not already in the study,
			// create a new locked source.  Replicate as needed.

			if (null != theSource) {

				if (null != theSource.userRecordID) {
					newSource = study.findSharedSource(theSource.userRecordID);
				}
				if (null == newSource) {
					newSource = theSource.deriveSource(study, theSource.isLocked, theReporter);
				}
				if ((null != newSource) && (replicateToChannel > 0)) {
					originalSource = (SourceEditDataTV)newSource;
					newSource = null;
					if (null != theSource.userRecordID) {
						newSource = study.findSharedReplicationSource(theSource.userRecordID, replicateToChannel);
					}
					if (null == newSource) {
						newSource = originalSource.replicate(replicateToChannel, theReporter);
					}
				}

			} else {

				if (null != theRecord) {

					newSource = study.findSharedSource(theRecord.extDb.key, theRecord.extRecordID);
					if (null == newSource) {
						newSource = SourceEditData.makeSource(theRecord, study, true, theReporter);
					}
					if ((null != newSource) && (replicateToChannel > 0)) {
						originalSource = (SourceEditDataTV)newSource;
						newSource = study.findSharedReplicationSource(theRecord.extDb.key, theRecord.extRecordID,
							replicateToChannel);
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

			// For an FM to TV 6 study the target TV record must be on, or replicated to, channel 6.

			if ((Study.STUDY_TYPE_TV6_FM == studyType) && (6 != ((SourceEditDataTV)newSource).channel)) {
				theReporter.reportWarning("The target TV record must be on, or replicated to, channel 6.");
				return false;
			}

			// For a TVIX study, must determine if the desired source is a "pre-baseline" record, meaning it represents
			// a facility that will cease to exist after the transition to the new baseline channel plan.  That alters
			// the behavior of interference scenario builds, see StudyBuildIxCheck.  In a full study build done by that
			// class this condition is determined automatically.  However the information to do that is not readily
			// avaialble now, and there may also be cases in which the user wants the pre-baseline behavior regardless
			// of actual conditions.  So just ask the user.

			if (Study.STUDY_TYPE_TV_IX == studyType) {
				AppController.beep();
				int prompt = JOptionPane.showConfirmDialog(this,
					"Should this be considered a pre-baseline facility record?", theReporter.getTitle(),
					JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
				if (JOptionPane.CANCEL_OPTION == prompt) {
					return false;
				}
				if (JOptionPane.YES_OPTION == prompt) {
					newSource.setAttribute(Source.ATTR_IS_PRE_BASELINE);
					if (null != originalSource) {
						originalSource.setAttribute(Source.ATTR_IS_PRE_BASELINE);
					}
				}
			}

			// Show any log messages from the record conversion process.

			theReporter.showMessages();

			// Add the target record as a desired but not an undesired, flagged permanent.  In these scenarios there
			// is always exactly one desired TV, since there can be no others the undesired flag is meaningless.

			if (null != originalSource) {
				study.addOrReplaceSource(originalSource);
			}
			theScenario.sourceData.addOrReplace(newSource, true, false, true);

			// Add the scenario, select it in the list and open it.

			int rowIndex = scenarioModel.addOrReplace(theScenario);

			if (rowIndex >= 0) {

				rowIndex = scenarioTable.convertRowIndexToView(rowIndex);
				scenarioTable.setRowSelectionInterval(rowIndex, rowIndex);
				scenarioTable.scrollRectToVisible(scenarioTable.getCellRect(rowIndex, 0, true));

				doOpenScenario();
			}

			return true;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by dependent windows when closing.

	public void editorClosing(AppEditor theEditor) {

		if (theEditor instanceof IxRuleEditor) {
			ixRuleEditors.remove(((IxRuleEditor)theEditor).getIxRule().key, (IxRuleEditor)theEditor);
			return;
		}

		if (theEditor instanceof ScenarioEditor) {
			scenarioEditors.remove(((ScenarioEditor)theEditor).getScenario().key, (ScenarioEditor)theEditor);
			return;
		}

		if (theEditor instanceof RecordFind) {
			if ((RecordFind)theEditor == newScenarioFinder) {
				newScenarioFinder = null;
				return;
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This will try to close the editor without saving changes or prompting the user; but it might fail if there are
	// editors or dialogs showing that don't want to close.

	public boolean closeWithoutSave() {

		for (IxRuleEditor theEditor : new ArrayList<IxRuleEditor>(ixRuleEditors.values())) {
			if (theEditor.isVisible() && !theEditor.cancel()) {
				AppController.beep();
				theEditor.toFront();
				return false;
			}
			ixRuleEditors.remove(theEditor.getIxRule().key);
		}

		for (ScenarioEditor theEditor : new ArrayList<ScenarioEditor>(scenarioEditors.values())) {
			if (theEditor.isVisible() && !theEditor.closeWithoutSave()) {
				AppController.beep();
				theEditor.toFront();
				return false;
			}
			scenarioEditors.remove(theEditor.getScenario().key);
		}

		study.invalidate();

		blockActionsSet();
		parent.editorClosing(this);
		AppController.hideWindow(this);

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Show a message if there are new study parameters, see comments in constructor.

	public void windowWillOpen() {

		DbController.restoreColumnWidths(getDbID(), getKeyTitle() + ".Rules", ixRuleTable);
		DbController.restoreColumnWidths(getDbID(), getKeyTitle() + ".Scenarios", scenarioTable);

		blockActionsClear();

		if (hasNewParameters) {
			SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					errorReporter.setTitle("New Parameters");
					AppController.beep();
					errorReporter.reportMessage("New parameters have been added to the study.");
				}
			});
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// When the study editor is closed, start by closing all open scenario editors, if any refuse to close abort.  The
	// windowShouldClose() in ScenarioEditor calls back to editorClosing() which removes it from the editor list.  Then
	// prompt to save changes as needed, and finally inform the parent list editor of the close.  Note the parent is
	// responsible for releasing the study lock.  Open interference rule editors will block the close if the rule is
	// editable (the template isn't study-locked), otherwise are just canceled.

	public boolean windowShouldClose() {

		boolean doConfirmSave = confirmSave;
		confirmSave = true;

		boolean rulesLocked = study.study.templateLocked;
		for (IxRuleEditor theEditor : new ArrayList<IxRuleEditor>(ixRuleEditors.values())) {
			if (theEditor.isVisible() && (!rulesLocked || !theEditor.cancel())) {
				AppController.beep();
				theEditor.toFront();
				return false;
			}
			ixRuleEditors.remove(theEditor.getIxRule().key);
		}

		for (ScenarioEditor theEditor : new ArrayList<ScenarioEditor>(scenarioEditors.values())) {
			if (theEditor.isVisible()) {
				if (!theEditor.windowShouldClose()) {
					AppController.beep();
					return false;
				}
				AppController.hideWindow(theEditor);
			}
			scenarioEditors.remove(theEditor.getScenario().key);
		}

		if (null != newScenarioFinder) {
			if (newScenarioFinder.isVisible() && !newScenarioFinder.closeIfPossible()) {
				AppController.beep();
				newScenarioFinder.toFront();
				return false;
			}
			newScenarioFinder = null;
		}

		if (!StudyManager.geographyScopeShouldClose(getDbID(), study.study.key, 0)) {
			return false;
		}

		if (!saveIfNeeded("Close Study", doConfirmSave)) {
			toFront();
			return false;
		}

		study.invalidate();

		DbController.saveColumnWidths(getDbID(), getKeyTitle() + ".Rules", ixRuleTable);
		DbController.saveColumnWidths(getDbID(), getKeyTitle() + ".Scenarios", scenarioTable);

		blockActionsSet();
		parent.editorClosing(this);

		return true;
	}
}
