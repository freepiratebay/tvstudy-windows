//
//  ExtDbSearchDialog.java
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui;

import gov.fcc.tvstudy.gui.editor.*;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.editdata.*;
import gov.fcc.tvstudy.core.geo.*;

import java.util.*;
import java.sql.*;
import java.awt.*;
import java.awt.event.*;
import java.text.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.table.*;


//=====================================================================================================================
// Dialog for providing search criteria for a multiple record search of an external station data set.  The search is
// not performed here; this dialog merely allows the user to build the search query, the search itself is done by the
// parent when the user applies the input from this dialog.  This is an editor for the ExtDbSearch model, which is a
// persistent model for a search state that is translated to a query string by code in the ExtDbSearch class.  Searches
// are saved in a root database table using the name as the only identifier, any save simply overwrites any existing
// record with matching name, case-insensitive.  This editor and the model are patterned after the OutputConfig model
// and OutputConfigDialog UI, see there for details.

public class ExtDbSearchDialog extends AppDialog {

	public static final String WINDOW_TITLE = "Add Stations";

	private int studyType;
	private Integer defaultExtDbKey;
	private KeyedRecordMenu extDbMenu;
	private JCheckBox baselineCheckBox;

	private KeyedRecordMenu searchTypeMenu;

	private JCheckBox disableMXCheckBox;
	private JCheckBox preferOperatingCheckBox;
	private JCheckBox desiredOnlyCheckBox;

	private JCheckBox serviceSearchCheckBox;
	private ArrayList<Service> servicesTV;
	private ArrayList<JCheckBox> serviceCheckBoxesTV;
	private JPanel servicePanelTV;
	private ArrayList<Service> servicesFM;
	private ArrayList<JCheckBox> serviceCheckBoxesFM;
	private JPanel servicePanelFM;

	private JCheckBox countrySearchCheckBox;
	private ArrayList<Country> countries;
	private ArrayList<JCheckBox> countryCheckBoxes;

	private JCheckBox statusSearchCheckBox;
	private ArrayList<JCheckBox> statusCheckBoxes;

	private JCheckBox radiusSearchCheckBox;
	private CoordinatePanel latitudePanel;
	private CoordinatePanel longitudePanel;
	private JTextField radiusField;

	private JTextField minimumChannelField;
	private JTextField maximumChannelField;

	private JButton addSQLButton;

	private JButton applyButton;

	private JComboBox<ExtDbSearch> searchMenu;
	private JButton deleteButton;

	private JTextField nameField;
	private JCheckBox autoRunCheckBox;

	// Properties defining the search.

	public ExtDb extDb;
	public boolean useBaseline;

	public ExtDbSearch search;


	//-----------------------------------------------------------------------------------------------------------------
	// The external data set key is a default selection, the usual menu is shown for choosing the data set to search.
	// The dialog always has a null parent, it's meant to stay open beside a parent scenario editor for multiple
	// search operations in sequence so shouldn't block interaction with it's parent.  This probably makes more sense
	// as a frame however all the menu-related features in the AppFrame superclass are not wanted here.

	public ExtDbSearchDialog(AppEditor theParent, Integer theExtDbKey, int theStudyType) {

		super(theParent, null, WINDOW_TITLE, Dialog.ModalityType.MODELESS);

		studyType = theStudyType;
		defaultExtDbKey = theExtDbKey;

		search = new ExtDbSearch(studyType);

		// Most of the controls share a state updater since they interact.  Also an enable updater is shared to avoid
		// duplicating code in the state updater.

		ActionListener doStateUpdate = new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					updateUI();
					blockActionsEnd();
				}
			}
		};

		ActionListener doEnableUpdate = new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					enableUI();
					blockActionsEnd();
				}
			}
		};

		// Set up the search UI.  Menu to choose data to search.  This will be populated when window is shown.  When
		// the data set is changed, the UI may reconfigure based on the data set type.

		ArrayList<KeyedRecord> list = new ArrayList<KeyedRecord>();

		extDbMenu = new KeyedRecordMenu(list);
		extDbMenu.addActionListener(doStateUpdate);
		extDbMenu.setPrototypeDisplayValue(new KeyedRecord(0, "XyXyXyXyXyXyXyXyXy"));

		baselineCheckBox = new JCheckBox("Baseline");
		baselineCheckBox.setFocusable(false);
		baselineCheckBox.addActionListener(doEnableUpdate);

		JPanel extDbPanel = new JPanel();
		extDbPanel.setBorder(BorderFactory.createTitledBorder("Search Station Data"));
		extDbPanel.add(extDbMenu);
		extDbPanel.add(baselineCheckBox);

		// Logic configuration options.  Type of search, desireds, protecteds, or undesireds.  Desireds is just a
		// straightforward search using criteria selected in the dialog.  Protected and undesired searches also use
		// criteria, but in addition both also apply interference rules to select only records that have a rule-based
		// relationship to other records already in the scenario (in a protecteds search existing undesired records are
		// used, in an undesireds search existing desired records are used).

		list.add(new KeyedRecord(ExtDbSearch.SEARCH_TYPE_DESIREDS, "Desired stations"));
		list.add(new KeyedRecord(ExtDbSearch.SEARCH_TYPE_PROTECTEDS, "Protected stations"));
		list.add(new KeyedRecord(ExtDbSearch.SEARCH_TYPE_UNDESIREDS, "Undesired stations"));

		searchTypeMenu = new KeyedRecordMenu(list);
		searchTypeMenu.addActionListener(doStateUpdate);

		JPanel typePanel = new JPanel();
		typePanel.setBorder(BorderFactory.createTitledBorder("Search Type"));
		typePanel.add(searchTypeMenu);

		// Option to disable all MX checks and add all records regardless, normally records MX to others already in the
		// scenario, or MX to others in the new search, are not included (among new records in the search a preference
		// logic is used to pick one record from an MX set).  Option to prefer operating facilities among MX records,
		// see ExtDbRecord, which is irrelevant if MX checks are disabled so is disabled in that case.  Otherwise for
		// undesired search the prefer-operating option is usually on so that is selected by default in that case.
		// Last is option to add records as desired-only, that is relevant only for a desired or protected search since
		// desired is never set on results of an undesired search, and also desired-only is irrelevant if MX checks are
		// disabled as in that case the undesired flag is never set (meaning an undesireds search with MX disabled
		// results in records added with both scenario involvement options disabled meaning they are not used at all
		// unless selected manually, but that's the intended result).

		disableMXCheckBox = new JCheckBox("Disable all MX checks");
		disableMXCheckBox.setFocusable(false);
		disableMXCheckBox.addActionListener(doStateUpdate);

		preferOperatingCheckBox = new JCheckBox("Prefer operating facilities");
		preferOperatingCheckBox.setFocusable(false);

		desiredOnlyCheckBox = new JCheckBox("Add stations as desired-only");
		desiredOnlyCheckBox.setFocusable(false);

		// Search by service.  Separate service lists and thus UI panels for TV and FM, one is always hidden.

		serviceSearchCheckBox = new JCheckBox("Search by service");
		serviceSearchCheckBox.setFocusable(false);
		serviceSearchCheckBox.addActionListener(doEnableUpdate);

		ArrayList<Service> services = Service.getAllServices();
		servicesTV = new ArrayList<Service>();
		serviceCheckBoxesTV = new ArrayList<JCheckBox>();
		servicesFM = new ArrayList<Service>();
		serviceCheckBoxesFM = new ArrayList<JCheckBox>();
		JCheckBox theCheckBox;
		for (Service service : services) {
			theCheckBox = new JCheckBox(service.name);
			theCheckBox.setFocusable(false);
			if (Source.RECORD_TYPE_TV == service.serviceType.recordType) {
				servicesTV.add(service);
				serviceCheckBoxesTV.add(theCheckBox);
			} else {
				if (Source.RECORD_TYPE_FM == service.serviceType.recordType) {
					servicesFM.add(service);
					serviceCheckBoxesFM.add(theCheckBox);
				}
			}
		}

		servicePanelTV = new JPanel();
		servicePanelTV.setLayout(new BoxLayout(servicePanelTV, BoxLayout.Y_AXIS));
		for (JCheckBox theBox : serviceCheckBoxesTV) {
			servicePanelTV.add(theBox);
		}

		servicePanelFM = new JPanel();
		servicePanelFM.setLayout(new BoxLayout(servicePanelFM, BoxLayout.Y_AXIS));
		for (JCheckBox theBox : serviceCheckBoxesFM) {
			servicePanelFM.add(theBox);
		}
		servicePanelFM.setVisible(false);

		JPanel serviceSearchPanel = new JPanel();
		serviceSearchPanel.setLayout(new BoxLayout(serviceSearchPanel, BoxLayout.Y_AXIS));
		serviceSearchPanel.setBorder(BorderFactory.createEtchedBorder());
		serviceSearchPanel.add(serviceSearchCheckBox);
		serviceSearchPanel.add(servicePanelTV);
		serviceSearchPanel.add(servicePanelFM);

		// Search by country.

		countrySearchCheckBox = new JCheckBox("Search by country");
		countrySearchCheckBox.setFocusable(false);
		countrySearchCheckBox.addActionListener(doEnableUpdate);

		countries = Country.getAllCountries();
		countryCheckBoxes = new ArrayList<JCheckBox>();
		for (Country country : countries) {
			theCheckBox = new JCheckBox(country.name);
			theCheckBox.setFocusable(false);
			countryCheckBoxes.add(theCheckBox);
		}

		JPanel countrySearchPanel = new JPanel();
		countrySearchPanel.setLayout(new BoxLayout(countrySearchPanel, BoxLayout.Y_AXIS));
		countrySearchPanel.setBorder(BorderFactory.createEtchedBorder());
		countrySearchPanel.add(countrySearchCheckBox);
		for (JCheckBox theBox : countryCheckBoxes) {
			countrySearchPanel.add(theBox);
		}

		// Search by record status.

		statusSearchCheckBox = new JCheckBox("Search by status");
		statusSearchCheckBox.setFocusable(false);
		statusSearchCheckBox.addActionListener(doEnableUpdate);

		statusCheckBoxes = new ArrayList<JCheckBox>();
		for (KeyedRecord stat : ExtDbRecord.getStatusList()) {
			theCheckBox = new JCheckBox(stat.name);
			theCheckBox.setFocusable(false);
			statusCheckBoxes.add(theCheckBox);
		}

		JPanel statusSearchPanel = new JPanel();
		statusSearchPanel.setLayout(new BoxLayout(statusSearchPanel, BoxLayout.Y_AXIS));
		statusSearchPanel.setBorder(BorderFactory.createEtchedBorder());
		statusSearchPanel.add(statusSearchCheckBox);
		for (JCheckBox theBox : statusCheckBoxes) {
			statusSearchPanel.add(theBox);
		}

		// Search by center point and radius.

		radiusSearchCheckBox = new JCheckBox("Search by center point and radius");
		radiusSearchCheckBox.setFocusable(false);
		radiusSearchCheckBox.addActionListener(doEnableUpdate);

		latitudePanel = new CoordinatePanel(this, search.center, false, null);

		longitudePanel = new CoordinatePanel(this, search.center, true, null);

		radiusField = new JTextField(7);
		AppController.fixKeyBindings(radiusField);

		JPanel radiusPanel = new JPanel();
		radiusPanel.setBorder(BorderFactory.createTitledBorder("Radius, km"));
		radiusPanel.add(radiusField);

		JPanel radiusSearchPanel = new JPanel();
		radiusSearchPanel.setLayout(new BoxLayout(radiusSearchPanel, BoxLayout.Y_AXIS));
		radiusSearchPanel.setBorder(BorderFactory.createEtchedBorder());

		JPanel thePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		thePanel.add(radiusSearchCheckBox);
		radiusSearchPanel.add(thePanel);

		thePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		thePanel.add(latitudePanel);
		radiusSearchPanel.add(thePanel);

		thePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		thePanel.add(longitudePanel);
		radiusSearchPanel.add(thePanel);

		thePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		thePanel.add(radiusPanel);
		radiusSearchPanel.add(thePanel);

		// Minimum and maximum channel fields.

		minimumChannelField = new JTextField(6);
		AppController.fixKeyBindings(minimumChannelField);

		maximumChannelField = new JTextField(6);
		AppController.fixKeyBindings(maximumChannelField);

		JPanel minChanPanel = new JPanel();
		minChanPanel.setBorder(BorderFactory.createTitledBorder("Min. Channel"));
		minChanPanel.add(minimumChannelField);

		JPanel maxChanPanel = new JPanel();
		maxChanPanel.setBorder(BorderFactory.createTitledBorder("Max. Channel"));
		maxChanPanel.add(maximumChannelField);

		JPanel channelRangePanel = new JPanel();
		channelRangePanel.add(minChanPanel);
		channelRangePanel.add(maxChanPanel);

		// Button to prompt for user to directly enter SQL for the WHERE clause.

		addSQLButton = new JButton("Add SQL");
		addSQLButton.setFocusable(false);
		addSQLButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doAddSQL();
			}
		});

		// UI for selecting and deleting saved searches.  Menu will be populated in updateMenu().  Field for a new
		// search name and a save button.  The auto-run option is used by displaying context, if set when the saved
		// search is selected from a menu it runs immediately, otherwise it opens in this dialog.

		searchMenu = new JComboBox<ExtDbSearch>();
		searchMenu.setFocusable(false);
		ExtDbSearch proto = new ExtDbSearch(0);
		proto.name = "XyXyXyXyXyXyXyXyXyXyXyXyXyXyXy";
		searchMenu.setPrototypeDisplayValue(proto);

		searchMenu.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					ExtDbSearch newSearch = (ExtDbSearch)(searchMenu.getSelectedItem());
					if ((null != newSearch) && (newSearch.name.length() > 0)) {
						setSearch(newSearch);
						deleteButton.setEnabled(true);
					} else {
						deleteButton.setEnabled(false);
					}
					blockActionsEnd();
				}
			}
		});

		deleteButton = new JButton("Delete");
		deleteButton.setFocusable(false);
		deleteButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doDelete();
			}
		});

		nameField = new JTextField(20);
		AppController.fixKeyBindings(nameField);

		autoRunCheckBox = new JCheckBox("Auto-run");
		autoRunCheckBox.setFocusable(false);

		JButton saveButton = new JButton("Save");
		saveButton.setFocusable(false);
		saveButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doSave();
			}
		});

		// Buttons.

		JButton clearButton = new JButton("Clear");
		clearButton.setFocusable(false);
		clearButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doClear();
			}
		});

		JButton closeButton = new JButton("Close");
		closeButton.setFocusable(false);
		closeButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				cancel();
			}
		});

		applyButton = new JButton("Add");
		applyButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doApply();
			}
		});

		// Do the layout.
 
		JPanel dbP = new JPanel();
		dbP.add(extDbPanel);

		JPanel typeP = new JPanel();
		typeP.add(typePanel);

		Box optsB = Box.createVerticalBox();
		optsB.add(disableMXCheckBox);
		optsB.add(preferOperatingCheckBox);
		optsB.add(desiredOnlyCheckBox);

		JPanel optsP = new JPanel();
		optsP.add(optsB);

		JPanel svcP = new JPanel();
		svcP.add(serviceSearchPanel);

		Box leftBox = Box.createVerticalBox();
		leftBox.add(dbP);
		leftBox.add(typeP);
		leftBox.add(optsP);
		leftBox.add(svcP);

		JPanel rTop = new JPanel();
		rTop.add(countrySearchPanel);
		rTop.add(statusSearchPanel);

		JPanel rMid = new JPanel();
		rMid.add(radiusSearchPanel);

		JPanel rBotL = new JPanel(new FlowLayout(FlowLayout.LEFT));
		rBotL.add(addSQLButton);

		JPanel rBotR = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		rBotR.add(clearButton);

		Box rBot = Box.createHorizontalBox();
		rBot.add(rBotL);
		rBot.add(rBotR);

		Box rightBox = Box.createVerticalBox();
		rightBox.add(rTop);
		rightBox.add(rMid);
		rightBox.add(channelRangePanel);
		rightBox.add(rBot);

		Box mainBox = Box.createHorizontalBox();
		mainBox.add(leftBox);
		mainBox.add(rightBox);

		JPanel menuPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		menuPanel.add(searchMenu);
		menuPanel.add(deleteButton);

		JPanel savePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		savePanel.add(nameField);
		savePanel.add(autoRunCheckBox);
		savePanel.add(saveButton);

		Box bottomBox = Box.createVerticalBox();
		bottomBox.add(menuPanel);
		bottomBox.add(savePanel);

		JPanel mainPanel = new JPanel(new BorderLayout());
		mainPanel.add(mainBox, BorderLayout.CENTER);
		mainPanel.add(bottomBox, BorderLayout.SOUTH);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttons.add(closeButton);
		buttons.add(applyButton);

		Container cp = getContentPane();
		cp.setLayout(new BorderLayout());
		cp.add(mainPanel, BorderLayout.CENTER);
		cp.add(buttons, BorderLayout.SOUTH);

		getRootPane().setDefaultButton(applyButton);

		pack();

		Dimension theSize = getSize();
		theSize.width = 670;
		setSize(theSize);
		setMinimumSize(theSize);

		setResizable(true);
		setLocationSaved(true);

		updateDocumentName();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void updateDocumentName() {

		setDocumentName(parent.getDocumentName());
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Apply properties from another search object to the UI state and model.  Ignore if the wrong study type.

	public void setSearch(ExtDbSearch theSearch) {

		if (theSearch.studyType != studyType) {
			return;
		}

		blockActionsStart();

		if (searchTypeMenu.containsKey(theSearch.searchType)) {
			searchTypeMenu.setSelectedKey(theSearch.searchType);
		} else {
			searchTypeMenu.setSelectedKey(ExtDbSearch.SEARCH_TYPE_DESIREDS);
		}

		disableMXCheckBox.setSelected(theSearch.disableMX);

		preferOperatingCheckBox.setSelected(theSearch.preferOperating);

		desiredOnlyCheckBox.setSelected(theSearch.desiredOnly);

		boolean searchState = false;
		if (!theSearch.serviceKeys.isEmpty()) {
			Service theService;
			JCheckBox theBox;
			for (int i = 0; i < servicesTV.size(); i++) {
				theService = servicesTV.get(i);
				theBox = serviceCheckBoxesTV.get(i);
				if (theSearch.serviceKeys.contains(Integer.valueOf(theService.key))) {
					theBox.setSelected(true);
					searchState = true;
				} else {
					theBox.setSelected(false);
				}
			}
			for (int i = 0; i < servicesFM.size(); i++) {
				theService = servicesFM.get(i);
				theBox = serviceCheckBoxesFM.get(i);
				if (theSearch.serviceKeys.contains(Integer.valueOf(theService.key))) {
					theBox.setSelected(true);
					searchState = true;
				} else {
					theBox.setSelected(false);
				}
			}
		}
		serviceSearchCheckBox.setSelected(searchState);

		searchState = false;
		if (!theSearch.countryKeys.isEmpty()) {
			Country theCountry;
			JCheckBox theBox;
			for (int i = 0; i < countries.size(); i++) {
				theCountry = countries.get(i);
				theBox = countryCheckBoxes.get(i);
				if (theSearch.countryKeys.contains(Integer.valueOf(theCountry.key))) {
					theBox.setSelected(true);
					searchState = true;
				} else {
					theBox.setSelected(false);
				}
			}
		}
		countrySearchCheckBox.setSelected(searchState);

		searchState = false;
		if (!theSearch.statusTypes.isEmpty()) {
			int i = 0;
			JCheckBox theBox;
			for (KeyedRecord stat : ExtDbRecord.getStatusList()) {
				theBox = statusCheckBoxes.get(i++);
				if (theSearch.statusTypes.contains(Integer.valueOf(stat.key))) {
					theBox.setSelected(true);
					searchState = true;
				} else {
					theBox.setSelected(false);
				}
			}
		}
		statusSearchCheckBox.setSelected(searchState);

		searchState = false;
		if (theSearch.radius > 0.) {
			search.center.setLatLon(theSearch.center);
			radiusField.setText(AppCore.formatDistance(theSearch.radius));
			searchState = true;
		} else {
			search.center.setLatLon(0., 0.);
			radiusField.setText("");
		}
		latitudePanel.update();
		longitudePanel.update();
		radiusSearchCheckBox.setSelected(searchState);

		if (0 == theSearch.minimumChannel) {
			minimumChannelField.setText("");
		} else {
			minimumChannelField.setText(String.valueOf(theSearch.minimumChannel));
		}
		if (0 == theSearch.maximumChannel) {
			maximumChannelField.setText("");
		} else {
			maximumChannelField.setText(String.valueOf(theSearch.maximumChannel));
		}

		search.additionalSQL = theSearch.additionalSQL.trim();
		if (0 == search.additionalSQL.length()) {
			addSQLButton.setText("Add SQL");
		} else {
			addSQLButton.setText("Edit SQL");
		}

		if (theSearch.name.equalsIgnoreCase(ExtDbSearch.DEFAULT_DESIREDS_SEARCH_NAME) ||
				theSearch.name.equalsIgnoreCase(ExtDbSearch.DEFAULT_UNDESIREDS_SEARCH_NAME) ||
				theSearch.name.equalsIgnoreCase(ExtDbSearch.DEFAULT_PROTECTEDS_SEARCH_NAME)) {
			nameField.setText("");
		} else {
			nameField.setText(theSearch.name);
		}
		autoRunCheckBox.setSelected(theSearch.autoRun);

		updateUI();

		blockActionsEnd();

		updateMenu();
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doClear() {

		blockActionsStart();

		disableMXCheckBox.setSelected(false);
		preferOperatingCheckBox.setSelected(false);
		desiredOnlyCheckBox.setSelected(false);

		serviceSearchCheckBox.setSelected(false);
		for (JCheckBox theBox : serviceCheckBoxesTV) {
			theBox.setSelected(false);
		}
		for (JCheckBox theBox : serviceCheckBoxesFM) {
			theBox.setSelected(false);
		}

		countrySearchCheckBox.setSelected(false);
		for (JCheckBox theBox : countryCheckBoxes) {
			theBox.setSelected(false);
		}

		statusSearchCheckBox.setSelected(false);
		for (JCheckBox theBox : statusCheckBoxes) {
			theBox.setSelected(false);
		}

		radiusSearchCheckBox.setSelected(false);
		search.center.setLatLon(0., 0.);
		latitudePanel.update();
		longitudePanel.update();
		radiusField.setText("");

		search.additionalSQL = "";
		addSQLButton.setText("Add SQL");

		updateUI();

		blockActionsEnd();

		updateMenu();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update the state of the UI per various interactions.  A primary one is the data set selection which indicates
	// the record type, TV and FM are similar but have different service lists, wireless has almost none of the search
	// options and is always undesired-only.  Other interactions involve the search type and MX options for TV and FM.

	private void updateUI() {

		int theType = Study.getDefaultRecordType(studyType);

		int theKey = extDbMenu.getSelectedKey();
		ExtDb theDb = null;
		if (theKey > 0) {
			theDb = ExtDb.getExtDb(getDbID(), Integer.valueOf(theKey), true);
			if (null != theDb) {
				theType = theDb.recordType;
			}
		}
		if ((null != theDb) && theDb.hasBaseline()) {
			AppController.setComponentEnabled(baselineCheckBox, true);
		} else {
			baselineCheckBox.setSelected(false);
			AppController.setComponentEnabled(baselineCheckBox, false);
		}

		// Note some study and record type combinations allow only an undesired search.

		switch (theType) {

			case Source.RECORD_TYPE_TV:
			case Source.RECORD_TYPE_FM:
			default: {
				if ((Study.STUDY_TYPE_TV == studyType) || (Study.STUDY_TYPE_FM == studyType) ||
						((Study.STUDY_TYPE_TV6_FM == studyType) && (Source.RECORD_TYPE_FM == theType))) {
					AppController.setComponentEnabled(searchTypeMenu, true);
				} else {
					AppController.setComponentEnabled(searchTypeMenu, false);
					searchTypeMenu.setSelectedKey(ExtDbSearch.SEARCH_TYPE_UNDESIREDS);
				}
				if (Study.STUDY_TYPE_TV_IX == studyType) {
					AppController.setComponentEnabled(disableMXCheckBox, false);
					disableMXCheckBox.setSelected(true);
				} else {
					AppController.setComponentEnabled(disableMXCheckBox, true);
				}
				if (disableMXCheckBox.isSelected()) {
					AppController.setComponentEnabled(preferOperatingCheckBox, false);
					preferOperatingCheckBox.setSelected(false);
					AppController.setComponentEnabled(desiredOnlyCheckBox, false);
					desiredOnlyCheckBox.setSelected(false);
				} else {
					AppController.setComponentEnabled(preferOperatingCheckBox, true);
					preferOperatingCheckBox.setSelected(true);
					switch (searchTypeMenu.getSelectedKey()) {
						case ExtDbSearch.SEARCH_TYPE_DESIREDS:
						default: {
							AppController.setComponentEnabled(desiredOnlyCheckBox, true);
							break;
						}
						case ExtDbSearch.SEARCH_TYPE_PROTECTEDS: {
							AppController.setComponentEnabled(desiredOnlyCheckBox, true);
							break;
						}
						case ExtDbSearch.SEARCH_TYPE_UNDESIREDS: {
							AppController.setComponentEnabled(desiredOnlyCheckBox, false);
							desiredOnlyCheckBox.setSelected(false);
							break;
						}
					}
				}
				if (Source.RECORD_TYPE_FM == theType) {
					servicePanelTV.setVisible(false);
					servicePanelFM.setVisible(true);
				} else {
					servicePanelFM.setVisible(false);
					servicePanelTV.setVisible(true);
				}
				AppController.setComponentEnabled(serviceSearchCheckBox, true);
				AppController.setComponentEnabled(countrySearchCheckBox, true);
				AppController.setComponentEnabled(statusSearchCheckBox, true);
				AppController.setComponentEnabled(radiusSearchCheckBox, true);
				break;
			}

			case Source.RECORD_TYPE_WL: {
				AppController.setComponentEnabled(searchTypeMenu, false);
				searchTypeMenu.setSelectedKey(ExtDbSearch.SEARCH_TYPE_UNDESIREDS);
				AppController.setComponentEnabled(disableMXCheckBox, false);
				disableMXCheckBox.setSelected(false);
				AppController.setComponentEnabled(preferOperatingCheckBox, false);
				preferOperatingCheckBox.setSelected(false);
				AppController.setComponentEnabled(desiredOnlyCheckBox, false);
				desiredOnlyCheckBox.setSelected(false);
				AppController.setComponentEnabled(serviceSearchCheckBox, false);
				serviceSearchCheckBox.setSelected(false);
				AppController.setComponentEnabled(countrySearchCheckBox, false);
				countrySearchCheckBox.setSelected(false);
				AppController.setComponentEnabled(statusSearchCheckBox, false);
				statusSearchCheckBox.setSelected(false);
				AppController.setComponentEnabled(radiusSearchCheckBox, true);
				break;
			}
		}

		boolean enable = (Source.RECORD_TYPE_WL != theType);
		AppController.setComponentEnabled(minimumChannelField, enable);
		AppController.setComponentEnabled(maximumChannelField, enable);

		enableUI();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update the state of the UI for enable/disable changes with no state interactions.

	private void enableUI() {

		boolean enable = serviceSearchCheckBox.isSelected();
		for (JCheckBox theBox : serviceCheckBoxesTV) {
			AppController.setComponentEnabled(theBox, enable);
		}
		for (JCheckBox theBox : serviceCheckBoxesFM) {
			AppController.setComponentEnabled(theBox, enable);
		}

		enable = countrySearchCheckBox.isSelected();
		for (JCheckBox theBox : countryCheckBoxes) {
			AppController.setComponentEnabled(theBox, enable);
		}

		if (baselineCheckBox.isSelected()) {
			AppController.setComponentEnabled(statusSearchCheckBox, false);
			enable = false;
		} else {
			AppController.setComponentEnabled(statusSearchCheckBox, true);
			enable = statusSearchCheckBox.isSelected();
		}
		for (JCheckBox theBox : statusCheckBoxes) {
			AppController.setComponentEnabled(theBox, enable);
		}

		enable = radiusSearchCheckBox.isSelected();
		latitudePanel.setEnabled(enable);
		longitudePanel.setEnabled(enable);
		AppController.setComponentEnabled(radiusField, enable);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Show dialog with a text area for entering additional SQL code for the query.

	private void doAddSQL() {

		TextInputDialog theDialog = new TextInputDialog(this, "Add SQL", "Additional SQL for WHERE clause");
		theDialog.setInput(search.additionalSQL);

		AppController.showWindow(theDialog);
		if (theDialog.canceled) {
			return;
		}

		search.additionalSQL = theDialog.getInput();
		if (0 == search.additionalSQL.length()) {
			addSQLButton.setText("Add SQL");
		} else {
			addSQLButton.setText("Edit SQL");
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Always add one "blank" search to the top of the menu, selected when the active name doesn't match anything.

	private void updateMenu() {

		blockActionsStart();

		ArrayList<ExtDbSearch> theSearches = ExtDbSearch.getSearches(getDbID(), studyType);

		searchMenu.removeAllItems();

		ExtDbSearch selectSearch = new ExtDbSearch(studyType);
		searchMenu.addItem(selectSearch);

		String curName = nameField.getText().trim();

		for (ExtDbSearch theSearch : theSearches) {
			searchMenu.addItem(theSearch);
			if (curName.equals(theSearch.name)) {
				selectSearch = theSearch;
			}
		}

		searchMenu.setSelectedItem(selectSearch);
		deleteButton.setEnabled(selectSearch.name.length() > 0);

		blockActionsEnd();
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doSave() {

		if (!validateInput(false)) {
			return;
		}

		String newName = nameField.getText().trim();
		if (newName.equalsIgnoreCase(ExtDbSearch.DEFAULT_DESIREDS_SEARCH_NAME) ||
				newName.equalsIgnoreCase(ExtDbSearch.DEFAULT_UNDESIREDS_SEARCH_NAME) ||
				newName.equalsIgnoreCase(ExtDbSearch.DEFAULT_PROTECTEDS_SEARCH_NAME)) {
			nameField.setText("");
			newName = "";
		}
		if (0 == newName.length()) {
			errorReporter.reportWarning("Please provide a valid name for the saved search.");
			return;
		}
		search.name = newName;

		// The auto-run flag is updated per the UI during the save but then cleared again, so a displaying context
		// running a search using this dialog's model object directly (i.e. under doAppy()) can rely on that flag
		// never being set.  It will only be set in objects obtained directly from ExtDbSearch retrieval methods.

		search.autoRun = autoRunCheckBox.isSelected();

		if (!search.save(getDbID(), errorReporter)) {
			search.name = "";
			nameField.setText("");
		}

		search.autoRun = false;

		updateMenu();
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doDelete() {

		ExtDbSearch theSearch = (ExtDbSearch)(searchMenu.getSelectedItem());
		if ((null != theSearch) && (null != theSearch.name) && (theSearch.name.length() > 0)) {
			ExtDbSearch.deleteSearch(getDbID(), theSearch.studyType, theSearch.name);
		}

		updateMenu();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check input, apply to parent.  This never closes automatically, it must be closed explicitly.

	private void doApply() {

		if (!validateInput(true)) {
			return;
		}

		parent.applyEditsFrom(this);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check to verify state and apply to the model object.

	private boolean validateInput(boolean isApply) {

		if (!commitCurrentField()) {
			return false;
		}

		errorReporter.clearTitle();

		// Get data set selection, confirm it's still valid.

		int theKey = extDbMenu.getSelectedKey();
		if (0 == theKey) {
			return false;
		}
		extDb = ExtDb.getExtDb(getDbID(), Integer.valueOf(theKey), errorReporter);
		if (null == extDb) {
			return false;
		}
		useBaseline = (extDb.hasBaseline() && baselineCheckBox.isSelected());

		// Commit various UI state to the search object.

		search.searchType = searchTypeMenu.getSelectedKey();

		search.disableMX = disableMXCheckBox.isSelected();
		search.preferOperating = preferOperatingCheckBox.isSelected();
		search.desiredOnly = desiredOnlyCheckBox.isSelected();

		// Parse the radius field, check for validity.  A radius value of 0 in the search object will disable this
		// check in the actual search code.  The coordinate entry panels update the search object state directly but
		// that will have no consequence as long as this correctly sets or clears the radius.

		String str;

		if (radiusSearchCheckBox.isSelected()) {

			str = radiusField.getText().trim();
			if (str.length() > 0) {
				try {
					double d = Double.parseDouble(str);
					if ((d < 1.) || (d > 1000.)) {
						errorReporter.reportValidationError("The radius value must be in the range 1 to 1000.");
					} else {
						search.radius = d;
					}
				} catch (NumberFormatException ne) {
					errorReporter.reportValidationError("The radius value must be a number.");
				}
			}

		} else {
			search.radius = 0.;
		}

		// Parse the channel range fields and check for validity.  A blank field is valid, as is an entry of 0.  If
		// both are 0 the range limit is disabled.  One 0 and one non-0 means the 0 side of the range is unrestricted
		// (except by other implicit restrictions, see actual search methods for details).  Otherwise values must be
		// in valid range for the record type, and minimum must be less than or equal to maximum.

		if (Source.RECORD_TYPE_WL == extDb.recordType) {
			search.minimumChannel = 0;
			search.maximumChannel = 0;
		} else {

			int chanMin = 0, chanMax = 0;
			if (Source.RECORD_TYPE_FM == extDb.recordType) {
				chanMin = SourceFM.CHANNEL_MIN;
				chanMax = SourceFM.CHANNEL_MAX;
			} else {
				chanMin = SourceTV.CHANNEL_MIN;
				chanMax = SourceTV.CHANNEL_MAX;
			}

			str = minimumChannelField.getText().trim();
			if (str.length() > 0) {
				try {
					int i = Integer.parseInt(str);
					if (i < chanMin) {
						errorReporter.reportValidationError("The minimum channel must be greater than or equal to " +
							chanMin + ".");
						return false;
					} else {
						search.minimumChannel = i;
					}
				} catch (NumberFormatException ne) {
					errorReporter.reportValidationError("The minimum channel must be a number.");
					return false;
				}
			} else {
				search.minimumChannel = 0;
			}
				
			str = maximumChannelField.getText().trim();
			if (str.length() > 0) {
				try {
					int i = Integer.parseInt(str);
					if (i > chanMax) {
						errorReporter.reportValidationError("The maximum channel must be less than or equal to " +
							chanMax + ".");
						return false;
					} else {
						search.maximumChannel = i;
					}
				} catch (NumberFormatException ne) {
					errorReporter.reportValidationError("The maximum channel must be a number.");
					return false;
				}
			} else {
				search.maximumChannel = 0;
			}

			if ((search.minimumChannel > 0) && (search.maximumChannel > 0) &&
					(search.minimumChannel > search.maximumChannel)) {
				errorReporter.reportValidationError(
					"The minimum channel must be less than or equal to the maximum channel.");
				return false;
			}
		}

		// It's not necessary for any specific criteria to be selected meaning all active records in the data set can
		// be matched, but that state is confirmed.  But if one of the search-by-category boxes is checked, at least
		// one item in that category must also be checked.

		boolean noCrit = true;

		search.serviceKeys.clear();
		if (serviceSearchCheckBox.isSelected()) {
			ArrayList<Service> services = servicesTV;
			ArrayList<JCheckBox> serviceCheckBoxes = serviceCheckBoxesTV;
			if (Source.RECORD_TYPE_FM == extDb.recordType) {
				services = servicesFM;
				serviceCheckBoxes = serviceCheckBoxesFM;
			}
			Service theService;
			JCheckBox theBox;
			for (int i = 0; i < services.size(); i++) {
				theService = services.get(i);
				theBox = serviceCheckBoxes.get(i);
				if (theBox.isSelected()) {
					search.serviceKeys.add(Integer.valueOf(theService.key));
					noCrit = false;
				}
			}
			if (noCrit) {
				errorReporter.reportWarning("Please select at least one service.");
				return false;
			}
		}

		search.countryKeys.clear();
		if (countrySearchCheckBox.isSelected()) {
			Country theCountry;
			JCheckBox theBox;
			for (int i = 0; i < countries.size(); i++) {
				theCountry = countries.get(i);
				theBox = countryCheckBoxes.get(i);
				if (theBox.isSelected()) {
					search.countryKeys.add(Integer.valueOf(theCountry.key));
					noCrit = false;
				}
			}
			if (noCrit) {
				errorReporter.reportWarning("Please select at least one country.");
				return false;
			}
		}

		search.statusTypes.clear();
		if (statusSearchCheckBox.isSelected() && !useBaseline) {
			int i = 0;
			JCheckBox theBox;
			for (KeyedRecord stat : ExtDbRecord.getStatusList()) {
				theBox = statusCheckBoxes.get(i++);
				if (theBox.isSelected()) {
					search.statusTypes.add(Integer.valueOf(stat.key));
					noCrit = false;
				}
			}
			if (noCrit) {
				errorReporter.reportWarning("Please select at least one status.");
				return false;
			}
		}

		if (radiusSearchCheckBox.isSelected()) {
			if ((0. == search.center.latitude) || (0. == search.center.longitude) || (0. == search.radius)) {
				errorReporter.reportWarning("Please enter search radius and center coordinates.");
				return false;
			}
			noCrit = false;
		}

		if ((search.minimumChannel > 0) || (search.maximumChannel > 0)) {
			noCrit = false;
		}

		if (search.additionalSQL.length() > 0) {
			noCrit = false;
		}

		// If no search criteria entered for a desireds search, confirm the user really wants to load all records.
		// For an undesireds or protecteds search, not entering any restrictive criteria is the expected case since
		// those searches are implicitly restricted to only records with a potential interference relationship to some
		// desired or undesired station record already in the scenario; usually the user will want all such records.
		// Also skip this if input is being validated for a save rather than an apply.

		if (noCrit && isApply && (ExtDbSearch.SEARCH_TYPE_DESIREDS == search.searchType)) {
			AppController.beep();
			if (JOptionPane.YES_OPTION != JOptionPane.showConfirmDialog(this,
					"<HTML>No search criteria are selected.  This will<BR>" +
					"match <B>all records</B> in the data set.<BR><BR>" +
					"Are you sure you want to continue?</HTML>", "Confirm Big Search",
					JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)) {
				return false;
			}
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean cancel() {

		if (windowShouldClose()) {
			AppController.hideWindow(this);
			return true;
		}
		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowWillOpen() {

		extDbMenu.removeAllItems();

		setLocationRelativeTo(getOwner());

		blockActionsClear();

		SwingUtilities.invokeLater(new Runnable() {
			public void run() {

				errorReporter.setTitle("Load Station Data List");

				String dbID = getDbID();
				ArrayList<KeyedRecord> list = new ArrayList<KeyedRecord>(), addList = null;

				if (Study.isRecordTypeAllowed(studyType, Source.RECORD_TYPE_TV)) {
					addList = ExtDb.getExtDbList(dbID, Source.RECORD_TYPE_TV, errorReporter);
					if (null == addList) {
						cancel();
					}
					list.addAll(addList);
				}

				if (Study.isRecordTypeAllowed(studyType, Source.RECORD_TYPE_WL)) {
					addList = ExtDb.getExtDbList(dbID, Source.RECORD_TYPE_WL, errorReporter);
					if (null == addList) {
						cancel();
					}
					list.addAll(addList);
				}

				if (Study.isRecordTypeAllowed(studyType, Source.RECORD_TYPE_FM)) {
					addList = ExtDb.getExtDbList(dbID, Source.RECORD_TYPE_FM, errorReporter);
					if (null == addList) {
						cancel();
					}
					list.addAll(addList);
				}

				blockActionsStart();

				extDbMenu.addAllItems(list);
				if (null != defaultExtDbKey) {
					int defKey = defaultExtDbKey.intValue();
					if (extDbMenu.containsKey(defKey)) {
						extDbMenu.setSelectedKey(defKey);
					}
				}

				updateUI();

				blockActionsEnd();

				updateMenu();

				if (list.isEmpty()) {
					errorReporter.reportError("No station data found, please use Station Data Manager to add data.");
				}
			}
		});
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean windowShouldClose() {

		blockActionsSet();
		parent.editorClosing(this);
		return true;
	}
}
