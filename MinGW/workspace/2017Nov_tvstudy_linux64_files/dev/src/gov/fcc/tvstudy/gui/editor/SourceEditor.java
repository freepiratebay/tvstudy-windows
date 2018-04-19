//
//  SourceEditor.java
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
import java.awt.*;
import java.awt.event.*;
import java.text.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.table.*;


//=====================================================================================================================
// Source record editor/viewer (most sources are non-editable).

public class SourceEditor extends AppDialog {

	public static final String WINDOW_TITLE = "Station Data";

	// See comments at getSource().

	private Integer originalSourceKey;

	private SourceEditData mainSource;
	private boolean isPermanentInScenario;
	private boolean isTV;
	private boolean isWL;
	private boolean isFM;
	private SourceEditDataTV mainSourceTV;
	private SourceEditDataWL mainSourceWL;
	private SourceEditDataFM mainSourceFM;
	private boolean isNewSource;
	private boolean canEdit;
	private boolean canAddRemoveDTS;

	private int minimumChannel;
	private int maximumChannel;

	// When creating a new source these are edited, otherwise they just mirror the source properties.

	private Service service;
	private Country country;
	private int facilityID;
	private int stationClass;

	// When editing DTS, the main editor panel holds a tab pane with a DTSSourcePanel and multiple SourcePanels for
	// various source objects, else the editor panel just contains a single SourcePanel.  See updateLayout().

	private JPanel editorPanel;

	private JTabbedPane dtsTabPane;

	// List of SourcePanels for updating.

	private HashSet<SourcePanel> sourcePanels;

	// Buttons and menus.

	private KeyedRecordMenu addSiteMenu;
	private JButton removeSiteButton;
	private JPanel addRemovePanel;

	private JButton closeButton;
	private JButton applyButton;

	// Index of pattern editors, see SourcePanel, applyEditsFrom(), etc.

	private HashMap<PatternEditor, SourcePanel> patternEditorIndex;

	// Lists of area modes and geographies for service area selection in editing panels.

	private ArrayList<KeyedRecord> serviceAreaModeList;
	private ArrayList<KeyedRecord> serviceAreaGeographyList;
	private boolean showServiceArea;

	// Disambiguation.

	private SourceEditor outerThis = this;

	// This is a hack.  See setChannelNote().

	private String channelNote;


	//-----------------------------------------------------------------------------------------------------------------
	// The dialog is not functional immediately after construction; it must not be shown until setSource() is called.
	// It always has a null parent window, this would be an AppFrame except the menu features are undesireable here.

	public SourceEditor(AppEditor theParent) {

		super(theParent, null, WINDOW_TITLE, Dialog.ModalityType.MODELESS);

		sourcePanels = new HashSet<SourcePanel>();

		patternEditorIndex = new HashMap<PatternEditor, SourcePanel>();

		serviceAreaModeList = new ArrayList<KeyedRecord>();
		serviceAreaModeList.add(new KeyedRecord(Source.SERVAREA_CONTOUR_DEFAULT, "Default contour"));
		serviceAreaModeList.add(new KeyedRecord(Source.SERVAREA_CONTOUR_FCC, "FCC curves contour"));
		serviceAreaModeList.add(new KeyedRecord(Source.SERVAREA_CONTOUR_FCC_ADD_DIST, "FCC contour plus distance"));
		serviceAreaModeList.add(new KeyedRecord(Source.SERVAREA_CONTOUR_FCC_ADD_PCNT, "FCC contour plus percent"));
		serviceAreaModeList.add(new KeyedRecord(Source.SERVAREA_CONTOUR_LR_PERCENT, "L-R contour percent above"));
		serviceAreaModeList.add(new KeyedRecord(Source.SERVAREA_CONTOUR_LR_RUN_ABOVE, "L-R contour length above"));
		serviceAreaModeList.add(new KeyedRecord(Source.SERVAREA_CONTOUR_LR_RUN_BELOW, "L-R contour length below"));
		serviceAreaModeList.add(new KeyedRecord(Source.SERVAREA_RADIUS, "Constant distance"));
		serviceAreaModeList.add(new KeyedRecord(Source.SERVAREA_GEOGRAPHY_FIXED, "Geography fixed"));
		serviceAreaModeList.add(new KeyedRecord(Source.SERVAREA_GEOGRAPHY_RELOCATED, "Geography relocated"));
		serviceAreaModeList.add(new KeyedRecord(Source.SERVAREA_NO_BOUNDS, "Unrestricted"));

		// The layout is a single SourcePanel for normal sources, or a tab pane with one DTSSourcePanel and multiple
		// SourcePanels when editing DTS.  The panel objects will be created and placed in updateLayout().

		editorPanel = new JPanel();

		dtsTabPane = new JTabbedPane();
		dtsTabPane.getModel().addChangeListener(new ChangeListener() {
			public void stateChanged(ChangeEvent theEvent) {
				removeSiteButton.setEnabled(canAddRemoveDTS && (dtsTabPane.getSelectedIndex() > 1) &&
					(dtsTabPane.getTabCount() > 3));
			}
		});

		// Menu for adding site to DTS.  Will be populated later, see updateAddSiteMenu().

		addSiteMenu = new KeyedRecordMenu();
		addSiteMenu.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					doAddSite();
					blockActionsEnd();
				}
			}
		});

		// Buttons.

		removeSiteButton = new JButton("Remove Site");
		removeSiteButton.setFocusable(false);
		removeSiteButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					doRemoveSite();
					blockActionsEnd();
				}
			}
		});

		closeButton = new JButton("Close");
		closeButton.setFocusable(false);
		closeButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				cancel();
			}
		});

		applyButton = new JButton("OK");
		applyButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doApply();
			}
		});

		// Do the initial layout.  This is incomplete until updateLayout() is called, so that will call pack().

		addRemovePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		addRemovePanel.add(addSiteMenu);
		addRemovePanel.add(removeSiteButton);

		JPanel rightButPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		rightButPanel.add(closeButton);
		rightButPanel.add(applyButton);

		JPanel buttonPanel = new JPanel();
		buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.X_AXIS));
		buttonPanel.add(addRemovePanel);
		buttonPanel.add(rightButPanel);

		Container cp = getContentPane();
		cp.setLayout(new BorderLayout());
		cp.add(editorPanel, BorderLayout.CENTER);
		cp.add(buttonPanel, BorderLayout.SOUTH);

		getRootPane().setDefaultButton(applyButton);

		setResizable(true);
		setLocationSaved(true);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void updateDocumentName() {

		String docName = null;

		if ((null != mainSource) && (mainSource.callSign.length() > 0)) {
			docName = parent.getDocumentName() + "/" + mainSource.callSign;
			if (null != mainSource.study) {
				StudyManager.updateGeographyEditor(getDbID(), mainSource.study.study.key, mainSource.study.name,
					mainSource.key.intValue(), mainSource.callSign);
			}
		} else {
			docName = parent.getDocumentName();
		}

		setDocumentName(docName);

		for (PatternEditor theEditor : patternEditorIndex.keySet()) {
			theEditor.updateDocumentName();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called when geographies are edited, update list and any menus in panels as needed.

	public void updateGeographies() {

		int studyKey = 0, sourceKey = 0;
		if ((null != mainSource) && (null != mainSource.study)) {
			studyKey = mainSource.study.study.key;
			sourceKey = mainSource.key.intValue();
		}

		serviceAreaGeographyList = Geography.getGeographyList(getDbID(), studyKey, sourceKey, Geography.MODE_AREA);
		if (null == serviceAreaGeographyList) {
			serviceAreaGeographyList = new ArrayList<KeyedRecord>();
		}
		serviceAreaGeographyList.add(0, new KeyedRecord(0, "(none)"));

		for (SourcePanel thePanel : sourcePanels) {
			thePanel.updateGeographies();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set the source being edited.  Must be called before display, returns false if errors occur meaning the dialog
	// is not functional and should not be shown.  If deriveNew is true a new source is derived from the argument and
	// is fully editable, including fields for immutable properties service, country, and if relevant, facility ID.
	// When edits are applied another new source will be derived with those edited values.  When deriving, the argument
	// source does not have to be valid.  If deriveNew is false an existing and presumed valid source is being viewed
	// or edited, depending on it's locked flag.  Immutable properties are non-editable regardless of locked status.
	// The argument source is copied so edits cannot affect the original in case editing is canceled.  If the source
	// is locked all editing fields are disabled, however it is still safer to work with a copy.  Note the original
	// object's source key is always copied, see comments at getSource().  When used from the scenario editor the
	// permanent flag for the scenario is passed as that may affected editing behavior.  The UI for editing service
	// area settings may be hidden.  The first form is used by the scenario editor, in that case everything is shown.
	// The second form is used from RecordFind, from that context service area settings are usually not relevant and
	// in some cases would actually be ignored, so they are hidden.

	public boolean setSource(SourceEditData theSource, boolean isPerm) {
		return setSource(theSource, isPerm, false, true, null);
	}

	public boolean setSource(SourceEditData theSource, boolean deriveNew, ErrorReporter errors) {
		return setSource(theSource, false, deriveNew, false, errors);
	}

	private boolean setSource(SourceEditData theSource, boolean isPerm, boolean deriveNew, boolean showServArea,
			ErrorReporter errors) {

		if (isVisible()) {
			return false;
		}

		originalSourceKey = theSource.key;

		if (deriveNew) {

			mainSource = theSource.deriveSource(false, errors);
			if (null == mainSource) {
				return false;
			}
			isNewSource = true;
			canEdit = true;

		} else {

			mainSource = theSource.copy();
			isNewSource = false;
			canEdit = !mainSource.isLocked;
		}

		isPermanentInScenario = isPerm;

		showServiceArea = showServArea;

  		// For a record representing a permanent entry in an OET74 or TV6FM study scenario, there are a number of
		// editing restrictions.  The channel, service, and coordinates must not change because the scenario was built
		// based on interference rule relationships to the permanent record, and changing those relationships would
		// invalidate the scenario.  Service will of course not be editable at all in this situation.  The editor panel
		// classes will restrict channel and coordinates.  Here the ability to add and remove DTS sources must also be
		// disabled.  Note frequency offset and emission mask for TV records also affect rule relationships however
		// those have no impact on the build because those only affect D/U ratios, not search distances.

		canAddRemoveDTS = canEdit;
		if (isPermanentInScenario && (null != mainSource.study) &&
				((Study.STUDY_TYPE_TV_OET74 == mainSource.study.study.studyType) ||
				(Study.STUDY_TYPE_TV6_FM == mainSource.study.study.studyType))) {
			canAddRemoveDTS = false;
		}

		service = mainSource.service;
		country = mainSource.country;

		isTV = false;
		isWL = false;
		isFM = false;
		mainSourceTV = null;
		mainSourceWL = null;
		mainSourceFM = null;

		switch (mainSource.recordType) {

			case Source.RECORD_TYPE_TV: {
				isTV = true;
				mainSourceTV = (SourceEditDataTV)mainSource;
				facilityID = mainSourceTV.facilityID;
				stationClass = 0;
				if (null != mainSourceTV.study) {
					minimumChannel = mainSourceTV.study.getMinimumChannel();
					maximumChannel = mainSourceTV.study.getMaximumChannel();
				} else {
					minimumChannel = SourceTV.CHANNEL_MIN;
					maximumChannel = SourceTV.CHANNEL_MAX;
				}
				break;
			}

			case Source.RECORD_TYPE_WL: {
				isWL = true;
				mainSourceWL = (SourceEditDataWL)mainSource;
				facilityID = 0;
				stationClass = 0;
				minimumChannel = 0;
				maximumChannel = 0;
				break;
			}

			case Source.RECORD_TYPE_FM: {
				isFM = true;
				mainSourceFM = (SourceEditDataFM)mainSource;
				facilityID = mainSourceFM.facilityID;
				stationClass = mainSourceFM.stationClass;
				minimumChannel = SourceFM.CHANNEL_MIN;
				maximumChannel = SourceFM.CHANNEL_MAX;
				break;
			}

			default: {
				return false;
			}
		}

		updateDocumentName();

		updateGeographies();

		updateLayout();

		if (canEdit) {
			closeButton.setText("Cancel");
			applyButton.setVisible(true);
		} else {
			closeButton.setText("Close");
			applyButton.setVisible(false);
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This is a hack.  There are unusual cases where a source being displayed represents a record that will actually
	// be replicated after it is added to a study, but is being viewed outside a study context before replication can
	// be set up.  This allows the caller to explicitly set the replication label text that appears below the channel
	// number field to indicate replication will occur.  Or this could be used for some other purpose, it is entirely
	// cosmetic.  If the note is null, normal behavior is used for the replication label; it will indicate the original
	// channel for a replication source, else it is blank.  See updateFields() in SourcePanel and DTSSourcePanel.

	public void setChannelNote(String theNote) {

		channelNote = theNote;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Make state available to the parent.  The object returned by getSource() will always be a different specific
	// object than the one given to setSource().  When viewing or editing an existing source, the object returned here
	// will be either an identical copy or an as-edited copy with the same sourceKey as the original, making it the
	// same source in the containing model (see SourceListData).  When a new source is being derived, the object will
	// represent a new source with a new sourceKey that does not exist in any containing model (see SourceEditData for
	// details on key uniqueness).  The source key from the original object given to setSource() is available from
	// getOriginalSourceKey(), that may be needed by parents that track open editors by key.

	public SourceEditData getSource() {

		return mainSource;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public Integer getOriginalSourceKey() {

		return originalSourceKey;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isEditing() {

		return canEdit;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isDeriving() {

		return isNewSource;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create all editing panels and establish the layout.  First clear out any previous layout.  Unilaterally close
	// any remaining pattern editor dialogs; this is only called after the mainSource model object has been replaced,
	// so all existing UI is linked to a dead model and can just be discarded.  If a check for pending edits is needed
	// that must be done before this is called.

	private void updateLayout() {

		for (PatternEditor theEditor : patternEditorIndex.keySet()) {
			AppController.hideWindow(theEditor);
		}
		patternEditorIndex.clear();

		dtsTabPane.removeAll();
		editorPanel.removeAll();

		sourcePanels.clear();

		// For DTS create a tabbed layout with a single DTSSourcePanel at index 0 and one more more SourcePanels
		// following, the reference facility is at index 1, the rest in no particular order.  DTS will always be TV,
		// but check to be sure anyway, paranoia is good.

		SourcePanel thePanel;

		if (isTV && service.isDTS) {

			dtsTabPane.addTab("DTS Info", new DTSSourcePanel(mainSourceTV));
			for (SourceEditDataTV theSource : mainSourceTV.getDTSSources()) {
				thePanel = new SourcePanel(theSource);
				if (0 == theSource.siteNumber) {
					dtsTabPane.insertTab("Reference", null, thePanel, null, 1);
				} else {
					dtsTabPane.addTab("Site " + theSource.siteNumber, thePanel);
				}
				sourcePanels.add(thePanel);
			}
			dtsTabPane.setSelectedIndex(0);

			editorPanel.add(dtsTabPane);

			addRemovePanel.setVisible(true);
			addSiteMenu.setEnabled(canAddRemoveDTS);
			removeSiteButton.setEnabled(false);
			updateAddSiteMenu();

		// For non-DTS show a single SourcePanel.

		} else {

			thePanel = new SourcePanel(mainSource);
			editorPanel.add(thePanel);
			sourcePanels.add(thePanel);

			addRemovePanel.setVisible(false);
		}

		// Resize.

		pack();
		setMinimumSize(getSize());
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called when a new source is being created and the service is changed from non-DTS to DTS or vice-versa.  Must
	// derive a new source based on the current one and update the layout.  In the case of changing from non-DTS to
	// DTS, the parameters of the current source will be used to populate both the parent source and the reference
	// facility source in the new DTS operation; see deriveSource() in SourceEditData.  When going from DTS to non-DTS
	// one of the dependent transmitter sources must be picked.  If there are none this will create a new record and
	// copy the core parameters from the parent source; deriveSource() does not support changing from DTS to non-DTS
	// on the parent source.  If there is only one dependent source that is used to derive the new source.  Otherwise
	// the user must pick the source to use for the new record.  Also the new service must be TV.

	private void applyServiceChange(Service newService) {

		if (!isTV || !isNewSource || (service.isDTS == newService.isDTS) ||
				(Source.RECORD_TYPE_TV != newService.serviceType.recordType)) {
			return;
		}

		// User needs to close all open pattern editors first.

		if (!patternEditorIndex.isEmpty()) {
			AppController.beep();
			patternEditorIndex.keySet().iterator().next().toFront();
			return;
		}

		SourceEditDataTV newSource = null;

		errorReporter.setTitle("Change Service");

		if (!service.isDTS && newService.isDTS) {

			newSource = mainSourceTV.deriveSourceTV(facilityID, newService, country, false, errorReporter);

		} else {

			ArrayList<SourceEditDataTV> dtsSources = mainSourceTV.getDTSSources();

			if (dtsSources.isEmpty()) {

				newSource = SourceEditDataTV.createSource(null, getDbID(), facilityID, newService, false, country,
					false, errorReporter);
				if (null != newSource) {
					newSource.callSign = mainSourceTV.callSign;
					newSource.channel = mainSourceTV.channel;
					newSource.city = mainSourceTV.city;
					newSource.state = mainSourceTV.state;
					newSource.zone = mainSourceTV.zone;
					newSource.status = mainSourceTV.status;
					newSource.statusType = mainSourceTV.statusType;
					newSource.fileNumber = mainSourceTV.fileNumber;
					newSource.appARN = mainSourceTV.appARN;
					newSource.location.setLatLon(mainSourceTV.location);
				}

			} else {

				if (1 == dtsSources.size()) {

					newSource =
						dtsSources.get(0).deriveSourceTV(facilityID, newService, country, false, errorReporter);

				} else {

					String[] sites = new String[dtsSources.size()];
					SourceEditDataTV theSource;
					for (int i = 0; i < sites.length; i++) {
						theSource = dtsSources.get(i);
						if (0 == theSource.siteNumber) {
							sites[i] = "Reference";
						} else {
							sites[i] = "Site " + theSource.siteNumber;
						}
					}
					String theSite = (String)(JOptionPane.showInputDialog(null, "Choose site to convert",
						"Convert DTS to Non-DTS", JOptionPane.INFORMATION_MESSAGE, null, sites, sites[0]));
					for (int i = 0; i < sites.length; i++) {
						if (sites[i].equals(theSite)) {
							newSource = dtsSources.get(i).deriveSourceTV(facilityID, newService, country, false,
								errorReporter);
							break;
						}
					}
				}
			}
		}

		if (null != newSource) {
			mainSource = newSource;
			mainSourceTV = newSource;
			service = newService;
			updateLayout();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update fields in all source panels, this is called from the DTSSourcePanel in DTS mode when changes are made to
	// the parent and copied down to the other sources.

	private void updateSourcePanels() {

		for (SourcePanel thePanel : sourcePanels) {
			thePanel.updateFields();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a new DTS site and panel.  If the operation does not have a reference facility add that, otherwise add a
	// normal site.

	private void doAddSite() {

		if (!isTV || !canAddRemoveDTS || !mainSourceTV.isParent) {
			return;
		}

		String title = "Add DTS Site";
		errorReporter.setTitle(title);

		SourceEditDataTV copySource = null;
		int copyKey = addSiteMenu.getSelectedKey();
		addSiteMenu.setSelectedIndex(0);

		boolean doRef = true;
		for (SourceEditDataTV dtsSource : mainSourceTV.getDTSSources()) {
			if (0 == dtsSource.siteNumber) {
				doRef = false;
			}
			if (copyKey == dtsSource.siteNumber) {
				copySource = dtsSource;
			}
		}

		if (-1 == copyKey) {

			RecordFindDialog theDialog = new RecordFindDialog(this, "Choose record to copy for new site",
				Source.RECORD_TYPE_TV);
			if (null != mainSource.study) {
				theDialog.setDefaultExtDbKey(mainSource.study.study.extDbKey);
			}
			AppController.showWindow(theDialog);
			if (theDialog.canceled) {
				return;
			}

			Record theRecord = theDialog.getSelectedRecord();
			if (theRecord.isSource()) {
				copySource = (SourceEditDataTV)theRecord;
			} else {
				copySource = SourceEditDataTV.makeSourceTV((ExtDbRecordTV)theRecord, null, true, errorReporter);
				if (null == copySource) {
					return;
				}
			}
		}

		if ((null != copySource) && copySource.isParent) {
			ArrayList<SourceEditDataTV> theSources = copySource.getDTSSources();
			String[] sites = new String[theSources.size()];
			SourceEditDataTV theSource;
			for (int i = 0; i < sites.length; i++) {
				theSource = theSources.get(i);
				if (0 == theSource.siteNumber) {
					sites[i] = "Reference";
				} else {
					sites[i] = "Site " + theSource.siteNumber;
				}
			}
			String theSite = (String)(JOptionPane.showInputDialog(null, "Choose DTS site to copy", title,
				JOptionPane.INFORMATION_MESSAGE, null, sites, sites[0]));
			for (int i = 0; i < sites.length; i++) {
				if (sites[i].equals(theSite)) {
					copySource = theSources.get(i);
					break;
				}
			}
		}

		SourceEditDataTV newSource;
		if (doRef) {
			newSource = mainSourceTV.createDTSSource(0, errorReporter);
		} else {
			newSource = mainSourceTV.createDTSSource(mainSourceTV.getNextDTSSiteNumber(), errorReporter);
		}
		if (null == newSource) {
			return;
		}

		if (null != copySource) {

			if (doRef) {
				newSource.channel = copySource.channel;
				newSource.zone = copySource.zone;
				newSource.status = copySource.status;
				newSource.statusType = copySource.statusType;
				newSource.fileNumber = copySource.fileNumber;
				newSource.appARN = copySource.appARN;
				newSource.signalType = copySource.signalType;
			}

			newSource.frequencyOffset = copySource.frequencyOffset;
			newSource.location.setLatLon(copySource.location);
			newSource.heightAMSL = copySource.heightAMSL;
			newSource.overallHAAT = copySource.overallHAAT;
			newSource.peakERP = copySource.peakERP;

			if (copySource.hasHorizontalPattern) {
				newSource.antennaID = copySource.antennaID;
				newSource.hasHorizontalPattern = true;
				newSource.horizontalPattern = copySource.horizontalPattern.copy();
				newSource.horizontalPatternChanged = true;
				newSource.horizontalPatternOrientation = copySource.horizontalPatternOrientation;
			}

			if (copySource.hasVerticalPattern) {
				newSource.hasVerticalPattern = true;
				newSource.verticalPattern = copySource.verticalPattern.copy();
				newSource.verticalPatternChanged = true;
			}
			newSource.verticalPatternElectricalTilt = copySource.verticalPatternElectricalTilt;
			newSource.verticalPatternMechanicalTilt = copySource.verticalPatternMechanicalTilt;
			newSource.verticalPatternMechanicalTiltOrientation = copySource.verticalPatternMechanicalTiltOrientation;

			if (copySource.hasMatrixPattern) {
				newSource.hasMatrixPattern = true;
				newSource.matrixPattern = copySource.matrixPattern.copy();
				newSource.matrixPatternChanged = true;
			}

			newSource.dtsTimeDelay = copySource.dtsTimeDelay;
		}

		mainSourceTV.addOrReplaceDTSSource(newSource);

		SourcePanel thePanel = new SourcePanel(newSource);
		if (doRef) {
			dtsTabPane.insertTab("Reference", null, thePanel, null, 1);
			dtsTabPane.setSelectedIndex(1);
		} else {
			dtsTabPane.addTab("Site " + newSource.siteNumber, thePanel);
			dtsTabPane.setSelectedIndex(dtsTabPane.getTabCount() - 1);
		}
		sourcePanels.add(thePanel);

		updateAddSiteMenu();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The reference site and one transmitter site must always remain.

	private void doRemoveSite() {

		if (!isTV || !canAddRemoveDTS || !mainSourceTV.isParent || (dtsTabPane.getTabCount() < 4)) {
			return;
		}

		int theIndex = dtsTabPane.getSelectedIndex();
		Component theComp = dtsTabPane.getComponentAt(theIndex);
		if (!(theComp instanceof SourcePanel)) {
			return;
		}
		SourcePanel thePanel = (SourcePanel)theComp;
		if (0 == thePanel.sourceTV.siteNumber) {
			return;
		}

		// If the panel being removed has open pattern editors just blow them away.

		if (null != thePanel.horizontalPatternEditor) {
			if (thePanel.horizontalPatternEditor.isVisible()) {
				AppController.hideWindow(thePanel.horizontalPatternEditor);
			}
			patternEditorIndex.remove(thePanel.horizontalPatternEditor);
			thePanel.horizontalPatternEditor = null;
		}

		if (null != thePanel.verticalPatternEditor) {
			if (thePanel.verticalPatternEditor.isVisible()) {
				AppController.hideWindow(thePanel.verticalPatternEditor);
			}
			patternEditorIndex.remove(thePanel.verticalPatternEditor);
			thePanel.verticalPatternEditor = null;
		}

		// Remove the panel and it's source.

		sourcePanels.remove(thePanel);

		dtsTabPane.removeTabAt(theIndex);
		mainSourceTV.removeDTSSource(thePanel.sourceTV);

		removeSiteButton.setEnabled((dtsTabPane.getSelectedIndex() > 1) && (dtsTabPane.getTabCount() > 3));

		updateAddSiteMenu();
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void updateAddSiteMenu() {

		addSiteMenu.removeAllItems();

		addSiteMenu.addItem(new KeyedRecord(-3, "Add Site"));
		addSiteMenu.addItem(new KeyedRecord(-2, "Blank"));
		addSiteMenu.addItem(new KeyedRecord(-1, "Search"));

		int n = dtsTabPane.getTabCount();
		if (n > 1) {
			addSiteMenu.addItem(new KeyedRecord(0, "Copy Reference"));
			if (n > 2) {
				Component theComp;
				SourcePanel thePanel;
				for (int i = 2; i < n; i++) {
					theComp = dtsTabPane.getComponentAt(i);
					if (theComp instanceof SourcePanel) {
						thePanel = (SourcePanel)theComp;
						addSiteMenu.addItem(new KeyedRecord(thePanel.sourceTV.siteNumber,
							"Copy Site " + thePanel.sourceTV.siteNumber));
					}
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// If not editable, this just closes.  Otherwise check input, if valid apply to the parent.

	private void doApply() {

		if (!canEdit) {
			cancel();
			return;
		}

		// User needs to close all open pattern editors.

		if (!patternEditorIndex.isEmpty()) {
			AppController.beep();
			patternEditorIndex.keySet().iterator().next().toFront();
			return;
		}

		// Commit any pending edits in text fields, check for errors.

		if (!commitCurrentField()) {
			return;
		}

		// Attempt to close the geography editor as needed.

		if ((null != mainSource) && (null != mainSource.study) && !StudyManager.geographyScopeShouldClose(getDbID(),
				mainSource.study.study.key, mainSource.key.intValue())) {
			return;
		}

		// Check validity.  If a new source is being created, first derive another new source to apply edits to the
		// facility ID, service, and country.

		errorReporter.clearTitle();

		if (isNewSource) {

			SourceEditData newSource = null;
			SourceEditDataTV newSourceTV = null;
			SourceEditDataWL newSourceWL = null;
			SourceEditDataFM newSourceFM = null;
			switch (mainSource.recordType) {
				case Source.RECORD_TYPE_TV: {
					newSourceTV = mainSourceTV.deriveSourceTV(facilityID, service, country, false, errorReporter);
					newSource = newSourceTV;
					break;
				}
				case Source.RECORD_TYPE_WL: {
					newSourceWL = mainSourceWL.deriveSourceWL(service, country, false, errorReporter);
					newSource = newSourceWL;
					break;
				}
				case Source.RECORD_TYPE_FM: {
					newSourceFM = mainSourceFM.deriveSourceFM(facilityID, service, stationClass, country, false,
						errorReporter);
					newSource = newSourceFM;
					break;
				}
			}
			if (null == newSource) {
				return;
			}

			if (!newSource.isDataValid(errorReporter)) {
				return;
			}

			mainSource = newSource;
			mainSourceTV = newSourceTV;
			mainSourceWL = newSourceWL;
			mainSourceFM = newSourceFM;

		} else {

			if (!mainSource.isDataValid(errorReporter)) {
				return;
			}
		}

		// All good, tell parent, close if nothing goes wrong.

		if (parent.applyEditsFrom(this)) {
			blockActionsSet();
			parent.editorClosing(this);
			AppController.hideWindow(this);
			return;
		}

		if (isNewSource) {
			updateLayout();
		}
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
	// Called by pattern editors when edits are saved.

	public boolean applyEditsFrom(AppEditor theEditor) {

		if (theEditor instanceof PatternEditor) {

			PatternEditor patternEditor = (PatternEditor)theEditor;

			SourcePanel thePanel = patternEditorIndex.get(patternEditor);
			if (null != thePanel) {
				return thePanel.applyEditsFrom(patternEditor);
			}

			return false;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called by pattern editors when closing.

	public void editorClosing(AppEditor theEditor) {

		if (theEditor instanceof PatternEditor) {

			PatternEditor patternEditor = (PatternEditor)theEditor;

			SourcePanel thePanel = patternEditorIndex.get(patternEditor);
			if (null != thePanel) {
				thePanel.editorClosing(patternEditor);
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowWillOpen() {

		setLocationRelativeTo(getOwner());

		blockActionsClear();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean windowShouldClose() {

		if (!patternEditorIndex.isEmpty()) {
			HashSet<PatternEditor> theEditors = new HashSet<PatternEditor>(patternEditorIndex.keySet());
			for (PatternEditor theEditor : theEditors) {
				if (!theEditor.cancel()) {
					AppController.beep();
					theEditor.toFront();
					return false;
				}
			}
		}

		// Attempt to close the geography editor as needed.

		if ((null != mainSource) && (null != mainSource.study) && !StudyManager.geographyScopeShouldClose(getDbID(),
				mainSource.study.study.key, mainSource.key.intValue())) {
			return false;
		}

		blockActionsSet();
		parent.editorClosing(this);

		return true;
	}


	//=================================================================================================================
	// Editing of one source object is handled by a panel class, multiple panels are shown for DTS.  The source object
	// being edited, and thus the record type, can never change for an existing panel; new panel objects are created
	// any time updateLayout() is called.  That means the record-type-specific parts of this UI can be set up during
	// construction, there is no need for any dynamic layout capability.

	private class SourcePanel extends JPanel {

		private SourceEditData source;
		private SourceEditDataTV sourceTV;
		private SourceEditDataWL sourceWL;
		private SourceEditDataFM sourceFM;

		// Some fields may have restricted editing, see updateFields().

		private boolean canEditFacServCntry;
		private boolean canEditCallCityState;
		private boolean canEditChan;
		private boolean canEditZoneStatFile;
		private boolean canEditCoords;
		private boolean canEditSite;
		private boolean canEditServiceArea;

		// UI components.

		private JLabel stationDataLabel;
		private JTextField recordIDLabel;
		private JPanel recordIDPanel;

		private JLabel facilityIDLabel;
		private JTextField facilityIDField;

		private JLabel stationClassLabel;
		private KeyedRecordMenu stationClassMenu;

		private JLabel serviceLabel;
		private KeyedRecordMenu serviceMenu;

		private JTextField callSignField;
		private JTextField sectorIDField;

		private JTextField channelField;
		private JLabel replicationLabel;
		private JLabel frequencyLabel;

		private JTextField cityField;

		private JTextField stateField;

		private JLabel countryLabel;
		private KeyedRecordMenu countryMenu;

		private KeyedRecordMenu zoneMenu;

		private KeyedRecordMenu statusMenu;
		private KeyedRecord statusItem;

		private JTextField fileNumberField;

		private KeyedRecordMenu signalTypeMenu;

		private KeyedRecordMenu frequencyOffsetMenu;

		private KeyedRecordMenu emissionMaskMenu;

		private GeoPoint editPoint;
		private CoordinatePanel latitudePanel;
		private CoordinatePanel longitudePanel;

		private JTextField heightAMSLField;
		private JLabel actualHeightAMSLLabel;

		private JTextField overallHAATField;
		private JLabel actualOverallHAATLabel;

		private JTextField peakERPField;
		private JCheckBox ibocCheckBox;
		private JTextField ibocERPField;

		private JLabel horizontalPatternLabel;
		private JButton editHorizontalPatternButton;
		private JLabel horizontalPatternOrientationLabel;

		private JLabel verticalPatternLabel;
		private JButton editVerticalPatternButton;
		private JTextField verticalPatternElectricalTiltField;
		private JTextField verticalPatternMechanicalTiltField;
		private JTextField verticalPatternMechanicalTiltOrientationField;

		private JCheckBox useGenericVerticalPatternCheckBox;

		private JTextField siteNumberField;

		private KeyedRecordMenu serviceAreaModeMenu;
		private JTextField serviceAreaArgumentField;
		private JTextField serviceAreaContourLevelField;
		private KeyedRecordMenu serviceAreaGeographyMenu;
		private JButton editAreaGeoButton;

		private JTextField timeDelayField;

		private JCheckBox baselineCheckBox;

		// Pattern editors.

		private PatternEditor horizontalPatternEditor;
		private PatternEditor verticalPatternEditor;


		//-------------------------------------------------------------------------------------------------------------
		// Set up UI for editing a source.

		private SourcePanel(SourceEditData theSource) {

			source = theSource;
			switch (theSource.recordType) {
				case Source.RECORD_TYPE_TV: {
					sourceTV = (SourceEditDataTV)theSource;
					break;
				}
				case Source.RECORD_TYPE_WL: {
					sourceWL = (SourceEditDataWL)theSource;
					break;
				}
				case Source.RECORD_TYPE_FM: {
					sourceFM = (SourceEditDataFM)theSource;
					break;
				}
			}

			blockActionsStart();

			// Record identification if available, see Record interface methods in SourceEditData.  If no record ID is
			// available the panel is hidden.  The record ID is always display only but shown in a disabled text field
			// so content can be selected and copied.  LMS record IDs are 32-character UUID strings.

			stationDataLabel = new JLabel(" ");
			stationDataLabel.setPreferredSize(AppController.labelSize[32]);

			recordIDLabel = new JTextField(24);
			AppController.fixKeyBindings(recordIDLabel);
			AppController.setComponentEnabled(recordIDLabel, false);

			Box idBox = Box.createVerticalBox();
			idBox.add(stationDataLabel);
			idBox.add(recordIDLabel);

			recordIDPanel = new JPanel();
			recordIDPanel.setBorder(BorderFactory.createTitledBorder("Record ID"));
			recordIDPanel.add(idBox);

			// Facility ID is usually display-only so a label, but may be an editable field when creating a new source.
			// This only applies to TV and FM records.

			JPanel facilityIDPanel = null;

			if (isTV || isFM) {

				facilityIDLabel = new JLabel(" ");
				facilityIDLabel.setPreferredSize(AppController.labelSize[8]);

				facilityIDField = new JTextField(8);
				AppController.fixKeyBindings(facilityIDField);
				facilityIDField.setVisible(false);

				facilityIDField.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							if (canEditFacServCntry) {
								String title = "Edit Facility ID";
								String str = facilityIDField.getText().trim();
								if (str.length() > 0) {
									try {
										facilityID = Integer.parseInt(str);
									} catch (NumberFormatException ne) {
										errorReporter.reportValidationError(title, "The facility ID must be a number.");
									}
								}
							}
							facilityIDField.setText(String.valueOf(facilityID));
							blockActionsEnd();
						}
					}
				});

				facilityIDField.addFocusListener(new FocusAdapter() {
					public void focusGained(FocusEvent theEvent) {
						setCurrentField(facilityIDField);
					}
					public void focusLost(FocusEvent theEvent) {
						if (!theEvent.isTemporary()) {
							facilityIDField.postActionEvent();
						}
					}
				});

				facilityIDPanel = new JPanel();
				facilityIDPanel.setBorder(BorderFactory.createTitledBorder("Facility ID"));
				facilityIDPanel.add(facilityIDLabel);
				facilityIDPanel.add(facilityIDField);
			}

			// Station class applies only to FM records.  It is normally not editable so just a label, but may be a
			// pop-up menu for a new record.

			JPanel stationClassPanel = null;

			if (isFM) {

				stationClassLabel = new JLabel(" ");
				stationClassLabel.setPreferredSize(AppController.labelSize[4]);

				ArrayList<KeyedRecord> list = new ArrayList<KeyedRecord>();
				for (int i = 0; i < ExtDbRecordFM.FM_CLASS_CODES.length; i++) {
					list.add(new KeyedRecord(i, ExtDbRecordFM.FM_CLASS_CODES[i]));
				}
				stationClassMenu = new KeyedRecordMenu(list);
				stationClassMenu.setVisible(false);

				stationClassMenu.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							if (canEditFacServCntry) {
								stationClass = stationClassMenu.getSelectedKey();
							}
							blockActionsEnd();
						}
					}
				});

				stationClassPanel = new JPanel();
				stationClassPanel.setBorder(BorderFactory.createTitledBorder("Class"));
				stationClassPanel.add(stationClassLabel);
				stationClassPanel.add(stationClassMenu);
			}

			// Service, also just a label in most cases, except when creating a new source it may be a menu.  But this
			// is never editable for DTS even when creating a new source.  So if this is editable the current state is
			// non-DTS, meaning if the service is changed to DTS a major transformation must occur, that is handled
			// by the applyServiceChange() method.

			serviceLabel = new JLabel(" ");
			serviceLabel.setPreferredSize(AppController.labelSize[26]);

			serviceMenu = new KeyedRecordMenu(Service.getServices(source.recordType));
			serviceMenu.setVisible(false);

			serviceMenu.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (canEditFacServCntry) {
							final Service newService = (Service)(serviceMenu.getSelectedItem());
							if (isTV) {
								if (newService.isDTS) {
									serviceMenu.setSelectedItem(service);
									SwingUtilities.invokeLater(new Runnable() {
										public void run() {
											applyServiceChange(newService);
										}
									});
								} else {
									if (newService.serviceType.digital) {
										if (0 == sourceTV.signalType.key) {
											sourceTV.signalType = SignalType.getDefaultObject();
										}
									} else {
										if (sourceTV.signalType.key > 0) {
											sourceTV.signalType = SignalType.getNullObject();
										}
									}
									if (null != signalTypeMenu) {
										AppController.setComponentEnabled(signalTypeMenu,
											newService.serviceType.digital);
										signalTypeMenu.setSelectedItem(sourceTV.signalType);
									}
									if (newService.serviceType.needsEmissionMask) {
										AppController.setComponentEnabled(emissionMaskMenu, true);
										if (!service.serviceType.needsEmissionMask) {
											emissionMaskMenu.setSelectedItem(EmissionMask.getInvalidObject());
										}
									} else {
										AppController.setComponentEnabled(emissionMaskMenu, false);
										emissionMaskMenu.setSelectedItem(EmissionMask.getNullObject());
									}
									service = newService;
									frequencyLabel.setText(SourceEditDataTV.getFrequency(sourceTV.channel, service));
								}
							} else {
								service = newService;
							}
						} else {
							serviceMenu.setSelectedItem(service);
						}
						blockActionsEnd();
					}
				}
			});

			JPanel servicePanel = new JPanel();
			servicePanel.setBorder(BorderFactory.createTitledBorder("Service"));
			servicePanel.add(serviceLabel);
			servicePanel.add(serviceMenu);

			// Call sign, text type-in field with listener to check input and update model; only requirement is non-
			// empty.  This and several other fields may be non-editable even when the record is otherwise editable,
			// for individual DTS records where some values are edited indirectly through the DTSSourcePanel UI, see
			// setSource() for details.

			callSignField = new JTextField(10);
			AppController.fixKeyBindings(callSignField);

			callSignField.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (canEditCallCityState) {
							String str = callSignField.getText().trim();
							if (str.length() > 0) {
								if (str.length() > Source.MAX_CALL_SIGN_LENGTH) {
									if (isWL) {
										errorReporter.reportValidationError("Edit Cell Site ID",
											"The cell site ID cannot be longer than " + Source.MAX_CALL_SIGN_LENGTH +
											" characters.");
									} else {
										errorReporter.reportValidationError("Edit Call Sign",
											"The call sign cannot be longer than " + Source.MAX_CALL_SIGN_LENGTH +
											" characters.");
									}
								} else {
									if (!isWL) {
										str = str.toUpperCase();
									}
									source.callSign = str;
								}
							}
						}
						callSignField.setText(source.callSign);
						updateDocumentName();
						blockActionsEnd();
					}
				}
			});

			callSignField.addFocusListener(new FocusAdapter() {
				public void focusGained(FocusEvent theEvent) {
					setCurrentField(callSignField);
				}
				public void focusLost(FocusEvent theEvent) {
					if (!theEvent.isTemporary()) {
						callSignField.postActionEvent();
					}
				}
			});

			JPanel callSignPanel = new JPanel();
			String lbl = "Call Sign";
			if (isWL) {
				lbl = "Cell Site ID";
			}
			callSignPanel.setBorder(BorderFactory.createTitledBorder(lbl));
			callSignPanel.add(callSignField);

			// Sector ID field, only appears for wireless records.

			JPanel sectorIDPanel = null;

			if (isWL) {

				sectorIDField = new JTextField(7);
				AppController.fixKeyBindings(sectorIDField);

				sectorIDField.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							if (canEditCallCityState) {
								String str = sectorIDField.getText().trim();
								if (str.length() > 0) {
									if (str.length() > Source.MAX_SECTOR_ID_LENGTH) {
										errorReporter.reportValidationError("Edit Sector ID",
											"The sector ID cannot be longer than " + Source.MAX_SECTOR_ID_LENGTH +
											" characters.");
									} else {
										sourceWL.sectorID = str;
									}
								}
							}
							sectorIDField.setText(sourceWL.sectorID);
							blockActionsEnd();
						}
					}
				});

				sectorIDField.addFocusListener(new FocusAdapter() {
					public void focusGained(FocusEvent theEvent) {
						setCurrentField(sectorIDField);
					}
					public void focusLost(FocusEvent theEvent) {
						if (!theEvent.isTemporary()) {
							sectorIDField.postActionEvent();
						}
					}
				});

				sectorIDPanel = new JPanel();
				sectorIDPanel.setBorder(BorderFactory.createTitledBorder("Sector ID"));
				sectorIDPanel.add(sectorIDField);
			}

			// Channel, text type-in with listener to parse text and check validity.  Usually the valid range is set by
			// study parameters, however if this is a DTS reference facility record it can have any legal channel.
			// This only applies to TV and FM records.

			JPanel channelPanel = null;

			if (isTV || isFM) {

				channelField = new JTextField(5);
				AppController.fixKeyBindings(channelField);

				channelField.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							int theCh;
							if (isTV) {
								theCh = sourceTV.channel;
							} else {
								theCh = sourceFM.channel;
							}
							if (canEditChan) {
								String title = "Edit Channel";
								String str = channelField.getText().trim();
								if (str.length() > 0) {
									int newCh = 0;
									try {
										newCh = Integer.parseInt(str);
									} catch (NumberFormatException ne) {
										errorReporter.reportValidationError(title, "The channel must be a number.");
									}
									if (newCh != theCh) {
										if (isTV && (null != sourceTV.parentSourceKey) && (0 == sourceTV.siteNumber)) {
											if ((newCh < SourceTV.CHANNEL_MIN) || (newCh > SourceTV.CHANNEL_MAX)) {
												errorReporter.reportValidationError(title,
													"The channel must be in the range " + SourceTV.CHANNEL_MIN +
													" to " + SourceTV.CHANNEL_MAX + ".");
											} else {
												sourceTV.channel = newCh;
												theCh = newCh;
											}
										} else {
											if ((newCh < minimumChannel) || (newCh > maximumChannel)) {
												errorReporter.reportValidationError(title,
													"The channel must be in the range " + minimumChannel + " to " +
													maximumChannel + ".");
											} else {
												if (isTV) {
													sourceTV.channel = newCh;
												} else {
													sourceFM.channel = newCh;
												}
												theCh = newCh;
											}
										}
									}
								}
							}
							channelField.setText(String.valueOf(theCh));
							if (isTV) {
								frequencyLabel.setText(SourceEditDataTV.getFrequency(sourceTV.channel, service));
							} else {
								frequencyLabel.setText(source.getFrequency());
							}
							blockActionsEnd();
						}
					}
				});

				channelField.addFocusListener(new FocusAdapter() {
					public void focusGained(FocusEvent theEvent) {
						setCurrentField(channelField);
					}
					public void focusLost(FocusEvent theEvent) {
						if (!theEvent.isTemporary()) {
							channelField.postActionEvent();
						}
					}
				});

				replicationLabel = new JLabel(" ");

				frequencyLabel = new JLabel(" ");

				JPanel chTopP = new JPanel();
				chTopP.add(channelField);
				chTopP.add(frequencyLabel);
				channelPanel = new JPanel();
				channelPanel.setLayout(new BoxLayout(channelPanel, BoxLayout.Y_AXIS));
				channelPanel.setBorder(BorderFactory.createTitledBorder("Channel"));
				channelPanel.add(chTopP);
				channelPanel.add(replicationLabel);
			}

			// City, text, applies to all record types, may be blank for wireless but not others.  May be non-editable
			// even when others are editable, for DTS sources which inherit this from parent.

			cityField = new JTextField(18);
			AppController.fixKeyBindings(cityField);

			cityField.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (canEditCallCityState) {
							String str = cityField.getText().trim();
							if ((str.length() > 0) || isWL) {
								if (str.length() > Source.MAX_CITY_LENGTH) {
									errorReporter.reportValidationError("Edit City",
										"The city name cannot be longer than " + Source.MAX_CITY_LENGTH +
										" characters.");
								} else {
									source.city = str;
								}
							}
						}
						cityField.setText(source.city);
						blockActionsEnd();
					}
				}
			});

			cityField.addFocusListener(new FocusAdapter() {
				public void focusGained(FocusEvent theEvent) {
					setCurrentField(cityField);
				}
				public void focusLost(FocusEvent theEvent) {
					if (!theEvent.isTemporary()) {
						cityField.postActionEvent();
					}
				}
			});

			JPanel cityPanel = new JPanel();
			cityPanel.setBorder(BorderFactory.createTitledBorder("City"));
			cityPanel.add(cityField);

			// State, text, may be empty for wireless but not others.

			stateField = new JTextField(3);
			AppController.fixKeyBindings(stateField);

			stateField.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (canEditCallCityState) {
							String str = stateField.getText().trim().toUpperCase();
							if ((str.length() > 0) || isWL) {
								if (str.length() > Source.MAX_STATE_LENGTH) {
									errorReporter.reportValidationError("Edit State",
										"The state code cannot be longer than " + Source.MAX_STATE_LENGTH +
										" characters.");
								} else {
									source.state = str;
								}
							}
						}
						stateField.setText(source.state);
						blockActionsEnd();
					}
				}
			});

			stateField.addFocusListener(new FocusAdapter() {
				public void focusGained(FocusEvent theEvent) {
					setCurrentField(stateField);
				}
				public void focusLost(FocusEvent theEvent) {
					if (!theEvent.isTemporary()) {
						stateField.postActionEvent();
					}
				}
			});

			JPanel statePanel = new JPanel();
			statePanel.setBorder(BorderFactory.createTitledBorder("State"));
			statePanel.add(stateField);

			// Country, this is usually non-editable so just a label, but may be a menu for a new source.

			countryLabel = new JLabel(" ");
			countryLabel.setPreferredSize(AppController.labelSize[6]);

			countryMenu = new KeyedRecordMenu(Country.getCountries());
			countryMenu.setVisible(false);

			countryMenu.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (canEditFacServCntry) {
							country = (Country)countryMenu.getSelectedItem();
						} else {
							countryMenu.setSelectedItem(country);
						}
						blockActionsEnd();
					}
				}
			});

			JPanel countryPanel = new JPanel();
			countryPanel.setBorder(BorderFactory.createTitledBorder("Country"));
			countryPanel.add(countryLabel);
			countryPanel.add(countryMenu);

			// Zone, pop-up menu.  Only for TV.

			JPanel zonePanel = null;

			if (isTV) {

				zoneMenu = new KeyedRecordMenu(Zone.getZonesWithNull());

				zoneMenu.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							if (canEditZoneStatFile) {
								sourceTV.zone = (Zone)zoneMenu.getSelectedItem();
							} else {
								zoneMenu.setSelectedItem(sourceTV.zone);
							}
							blockActionsEnd();
						}
					}
				});

				zonePanel = new JPanel();
				zonePanel.setBorder(BorderFactory.createTitledBorder("Zone"));
				zonePanel.add(zoneMenu);
			}

			// Status, pop-up menu.  Only for TV and FM.

			JPanel statusPanel = null;

			if (isTV || isFM) {

				statusMenu = new KeyedRecordMenu(ExtDbRecord.getStatusList());

				statusMenu.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							if (canEditZoneStatFile) {
								statusItem = statusMenu.getSelectedItem();
								if (isTV) {
									sourceTV.status = statusItem.name;
									sourceTV.statusType = statusItem.key;
								} else {
									sourceFM.status = statusItem.name;
									sourceFM.statusType = statusItem.key;
								}
							} else {
								statusMenu.setSelectedItem(statusItem);
							}
							blockActionsEnd();
						}
					}
				});

				statusPanel = new JPanel();
				statusPanel.setBorder(BorderFactory.createTitledBorder("Status"));
				statusPanel.add(statusMenu);
			}

			// File number, text type-in, no restrictions.  Gets special post-processing for TV and FM to split out
			// the ARN portion from the file number.

			fileNumberField = new JTextField(15);
			AppController.fixKeyBindings(fileNumberField);

			fileNumberField.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (canEditZoneStatFile) {
							String str = fileNumberField.getText().trim();
							if (str.length() > Source.MAX_FILE_NUMBER_LENGTH) {
								if (isWL) {
									errorReporter.reportValidationError("Edit Reference Number",
										"The reference number cannot be longer than " + Source.MAX_FILE_NUMBER_LENGTH +
										" characters.");
								} else {
									errorReporter.reportValidationError("Edit File Number",
										"The file number cannot be longer than " + Source.MAX_FILE_NUMBER_LENGTH +
										" characters.");
								}
							} else {
								source.fileNumber = str;
								if (isTV || isFM) {
									char cc;
									String arn = "";
									for (int i = 0; i < str.length(); i++) {
										cc = str.charAt(i);
										if (Character.isDigit(cc)) {
											arn = str.substring(i);
											break;
										}
										if ('-' == cc) {
											arn = str.substring(i + 1);
											break;
										}
									}
									if (isTV) {
										sourceTV.appARN = arn;
									} else {
										sourceFM.appARN = arn;
									}
								}
							}
						}
						fileNumberField.setText(source.fileNumber);
						blockActionsEnd();
					}
				}
			});

			fileNumberField.addFocusListener(new FocusAdapter() {
				public void focusGained(FocusEvent theEvent) {
					setCurrentField(fileNumberField);
				}
				public void focusLost(FocusEvent theEvent) {
					if (!theEvent.isTemporary()) {
						fileNumberField.postActionEvent();
					}
				}
			});

			JPanel fileNumberPanel = new JPanel();
			lbl = "File Number";
			if (isWL) {
				lbl = "Reference Number";
			}
			fileNumberPanel.setBorder(BorderFactory.createTitledBorder(lbl));
			fileNumberPanel.add(fileNumberField);

			// Signal type, pop-up menu.  TV only, except not on a DTS transmitter (for DTS it appears on the parent).
			// This is active only for digital services.  Also it is hidden if there is only one option in the list.

			JPanel signalTypePanel = null;

			if (isTV && !service.isDTS) {

				if (SignalType.hasMultipleOptions()) {

					signalTypeMenu = new KeyedRecordMenu(SignalType.getSignalTypes());

					signalTypeMenu.addActionListener(new ActionListener() {
						public void actionPerformed(ActionEvent theEvent) {
							if (blockActions()) {
								if (canEdit && service.serviceType.digital) {
									sourceTV.signalType = (SignalType)signalTypeMenu.getSelectedItem();
								} else {
									signalTypeMenu.setSelectedItem(sourceTV.signalType);
								}
								blockActionsEnd();
							}
						}
					});

					signalTypePanel = new JPanel();
					signalTypePanel.setBorder(BorderFactory.createTitledBorder("Mod. Type"));
					signalTypePanel.add(signalTypeMenu);
				}
			}

			// Frequency offset, pop-up menu.  TV only.

			JPanel frequencyOffsetPanel = null;

			if (isTV) {

				frequencyOffsetMenu = new KeyedRecordMenu(FrequencyOffset.getFrequencyOffsetsWithNull());

				frequencyOffsetMenu.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							if (canEdit) {
								sourceTV.frequencyOffset = (FrequencyOffset)frequencyOffsetMenu.getSelectedItem();
							} else {
								frequencyOffsetMenu.setSelectedItem(sourceTV.frequencyOffset);
							}
							blockActionsEnd();
						}
					}
				});

				frequencyOffsetPanel = new JPanel();
				frequencyOffsetPanel.setBorder(BorderFactory.createTitledBorder("Freq. Offset"));
				frequencyOffsetPanel.add(frequencyOffsetMenu);
			}

			// Emission mask field, pop-up menu.  This may be disabled depending on the service type.  TV only.

			JPanel emissionMaskPanel = null;

			if (isTV) {

				emissionMaskMenu = new KeyedRecordMenu(EmissionMask.getEmissionMasksWithNull());

				emissionMaskMenu.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							if (canEdit && service.serviceType.needsEmissionMask) {
								sourceTV.emissionMask = (EmissionMask)emissionMaskMenu.getSelectedItem();
							} else {
								emissionMaskMenu.setSelectedItem(sourceTV.emissionMask);
							}
							blockActionsEnd();
						}
					}
				});

				emissionMaskPanel = new JPanel();
				emissionMaskPanel.setBorder(BorderFactory.createTitledBorder("Emission Mask"));
				emissionMaskPanel.add(emissionMaskMenu);
			}

			// Latitude and longitude, separate degrees, minutes, seconds fields, see CoordinatePanel class.  This
			// and most of the remaining fields apply to all record types.

			editPoint = new GeoPoint();

			latitudePanel = new CoordinatePanel(outerThis, editPoint, false, new Runnable() {
				public void run() {
					if (canEditCoords) {
						source.location.latitude = editPoint.latitude;
						source.location.updateDMS();
					}
				}
			});

			longitudePanel = new CoordinatePanel(outerThis, editPoint, true, new Runnable() {
				public void run() {
					if (canEditCoords) {
						source.location.longitude = editPoint.longitude;
						source.location.updateDMS();
					}
				}
			});

			// Height AMSL field, text type-in.  Also a label field below that will display the actual AMSL height used
			// in the most-recent study run, if that is defined and is different than the main value.

			heightAMSLField = new JTextField(7);
			AppController.fixKeyBindings(heightAMSLField);

			heightAMSLField.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (canEdit) {
							String title = "Edit Height AMSL";
							String str = heightAMSLField.getText().trim();
							if (str.length() > 0) {
								if (str.equals(Source.HEIGHT_DERIVE_LABEL)) {
									str = String.valueOf(Source.HEIGHT_DERIVE);
								}
								double d = source.heightAMSL;
								try {
									d = Double.parseDouble(str);
								} catch (NumberFormatException ne) {
									errorReporter.reportValidationError(title, "The height must be a number.");
								}
								if (d != source.heightAMSL) {
									if ((d < Source.HEIGHT_MIN) || (d > Source.HEIGHT_MAX)) {
										errorReporter.reportValidationError(title, "The height must be in the range " +
											Source.HEIGHT_MIN + " to " + Source.HEIGHT_MAX + ".");
									} else {
										source.heightAMSL = d;
									}
								}
							}
						}
						if (Source.HEIGHT_DERIVE == source.heightAMSL) {
							heightAMSLField.setText(Source.HEIGHT_DERIVE_LABEL);
						} else {
							heightAMSLField.setText(AppCore.formatHeight(source.heightAMSL));
						}
						Source theSource = source.getSource();
						if ((null != theSource) && (theSource.actualHeightAMSL != source.heightAMSL) &&
								(Source.HEIGHT_DERIVE != theSource.actualHeightAMSL)) {
							actualHeightAMSLLabel.setText("Last run: " +
								AppCore.formatHeight(theSource.actualHeightAMSL));
						} else {
							actualHeightAMSLLabel.setText(" ");
						}
						blockActionsEnd();
					}
				}
			});

			heightAMSLField.addFocusListener(new FocusAdapter() {
				public void focusGained(FocusEvent theEvent) {
					setCurrentField(heightAMSLField);
				}
				public void focusLost(FocusEvent theEvent) {
					if (!theEvent.isTemporary()) {
						heightAMSLField.postActionEvent();
					}
				}
			});

			actualHeightAMSLLabel = new JLabel("Last run: 9999.9");

			JPanel heightAMSLPanel = new JPanel();
			heightAMSLPanel.setBorder(BorderFactory.createTitledBorder("Height AMSL, m"));
			JPanel hgtP = new JPanel();
			hgtP.setLayout(new BoxLayout(hgtP, BoxLayout.Y_AXIS));
			hgtP.add(heightAMSLField);
			hgtP.add(actualHeightAMSLLabel);
			heightAMSLPanel.add(hgtP);

			// Overall HAAT field, text type-in.  Also has a label displaying the actual value from the last run.

			overallHAATField = new JTextField(7);
			AppController.fixKeyBindings(overallHAATField);

			overallHAATField.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (canEdit) {
							String title = "Edit HAAT";
							String str = overallHAATField.getText().trim();
							if (str.length() > 0) {
								if (str.equals(Source.HEIGHT_DERIVE_LABEL)) {
									str = String.valueOf(Source.HEIGHT_DERIVE);
								}
								double d = source.overallHAAT;
								try {
									d = Double.parseDouble(str);
								} catch (NumberFormatException ne) {
									errorReporter.reportValidationError(title, "The HAAT must be a number.");
								}
								if (d != source.overallHAAT) {
									if ((d < Source.HEIGHT_MIN) || (d > Source.HEIGHT_MAX)) {
										errorReporter.reportValidationError(title, "The HAAT must be in the range " +
											Source.HEIGHT_MIN + " to " + Source.HEIGHT_MAX + ".");
									} else {
										source.overallHAAT = d;
									}
								}
							}
						}
						if (Source.HEIGHT_DERIVE == source.overallHAAT) {
							overallHAATField.setText(Source.HEIGHT_DERIVE_LABEL);
						} else {
							overallHAATField.setText(AppCore.formatHeight(source.overallHAAT));
						}
						Source theSource = source.getSource();
						if ((null != theSource) && (theSource.actualOverallHAAT != source.overallHAAT) &&
								(Source.HEIGHT_DERIVE != theSource.actualOverallHAAT)) {
							actualOverallHAATLabel.setText("Last run: " +
								AppCore.formatHeight(theSource.actualOverallHAAT));
						} else {
							actualOverallHAATLabel.setText(" ");
						}
						blockActionsEnd();
					}
				}
			});

			overallHAATField.addFocusListener(new FocusAdapter() {
				public void focusGained(FocusEvent theEvent) {
					setCurrentField(overallHAATField);
				}
				public void focusLost(FocusEvent theEvent) {
					if (!theEvent.isTemporary()) {
						overallHAATField.postActionEvent();
					}
				}
			});


			actualOverallHAATLabel = new JLabel("Last run: 9999.9");

			JPanel overallHAATPanel = new JPanel();
			overallHAATPanel.setBorder(BorderFactory.createTitledBorder("HAAT, m"));
			hgtP = new JPanel();
			hgtP.setLayout(new BoxLayout(hgtP, BoxLayout.Y_AXIS));
			hgtP.add(overallHAATField);
			hgtP.add(actualOverallHAATLabel);
			overallHAATPanel.add(hgtP);

			// Peak ERP, text type-in.

			peakERPField = new JTextField(7);
			AppController.fixKeyBindings(peakERPField);

			peakERPField.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (canEdit) {
							String title = "Edit Peak ERP";
							String str = peakERPField.getText().trim();
							if (str.length() > 0) {
								double d = source.peakERP;
								try {
									d = Double.parseDouble(str);
								} catch (NumberFormatException ne) {
									errorReporter.reportValidationError(title, "The ERP must be a number.");
								}
								if (d != source.peakERP) {
									if ((d < Source.ERP_MIN) || (d > Source.ERP_MAX)) {
										errorReporter.reportValidationError(title, "The ERP must be in the range " +
											Source.ERP_MIN + " to " + Source.ERP_MAX + ".");
									} else {
										source.peakERP = d;
									}
								}
							}
						}
						peakERPField.setText(AppCore.formatERP(source.peakERP));
						if (isFM && sourceFM.isIBOC) {
							ibocERPField.setText(AppCore.formatERP(sourceFM.peakERP * sourceFM.ibocFraction));
						}
						blockActionsEnd();
					}
				}
			});

			peakERPField.addFocusListener(new FocusAdapter() {
				public void focusGained(FocusEvent theEvent) {
					setCurrentField(peakERPField);
				}
				public void focusLost(FocusEvent theEvent) {
					if (!theEvent.isTemporary()) {
						peakERPField.postActionEvent();
					}
				}
			});

			JPanel peakERPPanel = new JPanel();
			peakERPPanel.setBorder(BorderFactory.createTitledBorder("Peak ERP, kW"));
			peakERPPanel.add(peakERPField);

			// IBOC check-box and ERP field, for FM only.  Note the ERP field displays and accepts kW, however the
			// property in the source object is a fraction, the ratio of the IBOC ERP to the peak ERP.  When the peak
			// ERP is changed the fraction does not change so the IBOC ERP display updates.  When the IBOC ERP is
			// edited the fraction updates, but must be in the range 0.01 to 0.1.

			JPanel ibocPanel = null;

			if (isFM) {

				ibocCheckBox = new JCheckBox("IBOC digital");
				ibocCheckBox.setFocusable(false);

				ibocCheckBox.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							if (canEdit) {
								sourceFM.isIBOC = ibocCheckBox.isSelected();
							}
							if (sourceFM.isIBOC) {
								ibocERPField.setText(AppCore.formatERP(sourceFM.peakERP * sourceFM.ibocFraction));
							} else {
								ibocERPField.setText("");
							}
							blockActionsEnd();
						}
					}
				});

				ibocERPField = new JTextField(7);
				AppController.fixKeyBindings(ibocERPField);

				ibocERPField.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							if (canEdit) {
								String title = "Edit IBOC ERP";
								String str = ibocERPField.getText().trim();
								if (str.length() > 0) {
									double d = sourceFM.ibocFraction;
									try {
										d = Double.parseDouble(str) / sourceFM.peakERP;
									} catch (NumberFormatException ne) {
										errorReporter.reportValidationError(title, "The ERP must be a number.");
									}
									if (d != sourceFM.ibocFraction) {
										if ((d < 0.01) || (d > 0.1)) {
											errorReporter.reportValidationError(title, "The IBOC ERP must be " +
												"between 1% and 10% of the peak ERP.");
										} else {
											sourceFM.ibocFraction = d;
										}
									}
								}
							}
							ibocERPField.setText(AppCore.formatERP(sourceFM.peakERP * sourceFM.ibocFraction));
							blockActionsEnd();
						}
					}
				});

				ibocERPField.addFocusListener(new FocusAdapter() {
					public void focusGained(FocusEvent theEvent) {
						setCurrentField(ibocERPField);
					}
					public void focusLost(FocusEvent theEvent) {
						if (!theEvent.isTemporary()) {
							ibocERPField.postActionEvent();
						}
					}
				});

				JPanel ibocERPPanel = new JPanel();
				ibocERPPanel.setBorder(BorderFactory.createTitledBorder("IBOC ERP, kW"));
				ibocERPPanel.add(ibocERPField);

				JPanel erpPanel = new JPanel();
				erpPanel.setLayout(new BoxLayout(erpPanel, BoxLayout.Y_AXIS));
				erpPanel.add(peakERPPanel);
				erpPanel.add(ibocCheckBox);
				erpPanel.add(ibocERPPanel);

				peakERPPanel = new JPanel();
				peakERPPanel.add(erpPanel);
			}

			// The horizontal pattern name is displayed in a label, editing is handled by a secondary dialog so this
			// has a button to open the dialog.  The pattern orientation is editable in the dialog, here it is just a
			// display-only label in the panel with the name display and edit button.

			horizontalPatternLabel = new JLabel(" ");
			horizontalPatternLabel.setPreferredSize(AppController.labelSize[20]);

			editHorizontalPatternButton = new JButton("Edit");
			editHorizontalPatternButton.setFocusable(false);
			editHorizontalPatternButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doEditHorizontalPattern();
				}
			});

			horizontalPatternOrientationLabel = new JLabel("9999.9");

			JPanel horizontalButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
			horizontalButtonPanel.add(editHorizontalPatternButton);

			JPanel horizontalLabelButtonPanel = new JPanel(new GridLayout(2, 1));
			horizontalLabelButtonPanel.add(horizontalPatternLabel);
			horizontalLabelButtonPanel.add(horizontalButtonPanel);

			JPanel horizontalParamsPanel = new JPanel(new GridLayout(2, 1));
			horizontalParamsPanel.add(new JLabel("Orient."));
			horizontalParamsPanel.add(horizontalPatternOrientationLabel);
		
			JPanel horizontalPatternPanel = new JPanel();
			horizontalPatternPanel.setBorder(BorderFactory.createTitledBorder("Azimuth Pattern"));
			horizontalPatternPanel.add(horizontalLabelButtonPanel);
			horizontalPatternPanel.add(horizontalParamsPanel);

			// The vertical pattern is similar to horizontal, but more extra fields related to tilt.

			verticalPatternLabel = new JLabel(" ");
			verticalPatternLabel.setPreferredSize(AppController.labelSize[20]);

			editVerticalPatternButton = new JButton("Edit");
			editVerticalPatternButton.setFocusable(false);
			editVerticalPatternButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doEditVerticalPattern();
				}
			});

			verticalPatternElectricalTiltField = new JTextField(7);
			AppController.fixKeyBindings(verticalPatternElectricalTiltField);

			verticalPatternElectricalTiltField.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (canEdit) {
							String title = "Edit Electrical Tilt";
							String str = verticalPatternElectricalTiltField.getText().trim();
							if (str.length() > 0) {
								double d = source.verticalPatternElectricalTilt;
								try {
									d = Double.parseDouble(str);
								} catch (NumberFormatException ne) {
									errorReporter.reportValidationError(title, "The tilt must be a number.");
								}
								if (d != source.verticalPatternElectricalTilt) {
									if ((d < AntPattern.TILT_MIN) || (d > AntPattern.TILT_MAX)) {
										errorReporter.reportValidationError(title, "The tilt must be in the range " +
											AntPattern.TILT_MIN + " to " + AntPattern.TILT_MAX + ".");
									} else {
										source.verticalPatternElectricalTilt = d;
									}
								}
							}
						}
						verticalPatternElectricalTiltField.setText(
							AppCore.formatDepression(source.verticalPatternElectricalTilt));
						blockActionsEnd();
					}
				}
			});

			verticalPatternElectricalTiltField.addFocusListener(new FocusAdapter() {
				public void focusGained(FocusEvent theEvent) {
					setCurrentField(verticalPatternElectricalTiltField);
				}
				public void focusLost(FocusEvent theEvent) {
					if (!theEvent.isTemporary()) {
						verticalPatternElectricalTiltField.postActionEvent();
					}
				}
			});

			verticalPatternMechanicalTiltField = new JTextField(7);
			AppController.fixKeyBindings(verticalPatternMechanicalTiltField);

			verticalPatternMechanicalTiltField.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (canEdit) {
							String title = "Edit Mechanical Tilt";
							String str = verticalPatternMechanicalTiltField.getText().trim();
							if (str.length() > 0) {
								double d = source.verticalPatternMechanicalTilt;
								try {
									d = Double.parseDouble(str);
								} catch (NumberFormatException ne) {
									errorReporter.reportValidationError(title, "The tilt must be a number.");
								}
								if (d != source.verticalPatternMechanicalTilt) {
									if ((d < AntPattern.TILT_MIN) || (d > AntPattern.TILT_MAX)) {
										errorReporter.reportValidationError(title, "The tilt must be in the range " +
											AntPattern.TILT_MIN + " to " + AntPattern.TILT_MAX + ".");
									} else {
										source.verticalPatternMechanicalTilt = d;
									}
								}
							}
						}
						verticalPatternMechanicalTiltField.setText(
							AppCore.formatDepression(source.verticalPatternMechanicalTilt));
						blockActionsEnd();
					}
				}
			});

			verticalPatternMechanicalTiltField.addFocusListener(new FocusAdapter() {
				public void focusGained(FocusEvent theEvent) {
					setCurrentField(verticalPatternMechanicalTiltField);
				}
				public void focusLost(FocusEvent theEvent) {
					if (!theEvent.isTemporary()) {
						verticalPatternMechanicalTiltField.postActionEvent();
					}
				}
			});

			verticalPatternMechanicalTiltOrientationField = new JTextField(7);
			AppController.fixKeyBindings(verticalPatternMechanicalTiltOrientationField);

			verticalPatternMechanicalTiltOrientationField.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (canEdit) {
							String title = "Edit Tilt Orientation";
							String str = verticalPatternMechanicalTiltOrientationField.getText().trim();
							if (str.length() > 0) {
								double d = source.verticalPatternMechanicalTiltOrientation;
								try {
									d = Math.IEEEremainder(Double.parseDouble(str), 360.);
									if (d < 0.) d += 360.;
								} catch (NumberFormatException ne) {
									errorReporter.reportValidationError(title, "The orientation must be a number.");
								}
								if (d != source.verticalPatternMechanicalTiltOrientation) {
									source.verticalPatternMechanicalTiltOrientation = d;
								}
							}
						}
						verticalPatternMechanicalTiltOrientationField.setText(
							AppCore.formatAzimuth(source.verticalPatternMechanicalTiltOrientation));
						blockActionsEnd();
					}
				}
			});

			verticalPatternMechanicalTiltOrientationField.addFocusListener(new FocusAdapter() {
				public void focusGained(FocusEvent theEvent) {
					setCurrentField(verticalPatternMechanicalTiltOrientationField);
				}
				public void focusLost(FocusEvent theEvent) {
					if (!theEvent.isTemporary()) {
						verticalPatternMechanicalTiltOrientationField.postActionEvent();
					}
				}
			});

			useGenericVerticalPatternCheckBox = new JCheckBox("May use generic pattern when needed");
			useGenericVerticalPatternCheckBox.setFocusable(false);
			useGenericVerticalPatternCheckBox.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (canEdit) {
							source.useGenericVerticalPattern = useGenericVerticalPatternCheckBox.isSelected();
						} else {
							useGenericVerticalPatternCheckBox.setSelected(source.useGenericVerticalPattern);
						}
						blockActionsEnd();
					}
				}
			});

			JPanel verticalButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
			verticalButtonPanel.add(editVerticalPatternButton);

			JPanel verticalLabelButtonPanel = new JPanel(new GridLayout(2, 1));
			verticalLabelButtonPanel.add(verticalPatternLabel);
			verticalLabelButtonPanel.add(verticalButtonPanel);

			JPanel verticalParamsPanel = new JPanel(new GridLayout(2, 3));
			verticalParamsPanel.add(new JLabel("Elec. Tilt"));
			verticalParamsPanel.add(new JLabel("Mech. Tilt"));
			verticalParamsPanel.add(new JLabel("Tilt Orient."));
			verticalParamsPanel.add(verticalPatternElectricalTiltField);
			verticalParamsPanel.add(verticalPatternMechanicalTiltField);
			verticalParamsPanel.add(verticalPatternMechanicalTiltOrientationField);

			JPanel verticalRightPanel = new JPanel(new GridLayout(2, 1));
			verticalRightPanel.add(verticalParamsPanel);
			verticalRightPanel.add(useGenericVerticalPatternCheckBox);

			JPanel verticalPatternPanel = new JPanel();
			verticalPatternPanel.setBorder(BorderFactory.createTitledBorder("Elevation Pattern"));
			verticalPatternPanel.add(verticalLabelButtonPanel);
			verticalPatternPanel.add(verticalRightPanel);

			// Site number.  For an editable record, the site number is usually editable except on a DTS reference
			// facility record where the value must always be 0.  For other DTS records the value must be >0, for
			// non-DTS records it must be >=0.  Only for TV.

			JPanel siteNumberPanel = null;

			if (isTV) {

				siteNumberField = new JTextField(6);
				AppController.fixKeyBindings(siteNumberField);

				final SourcePanel that = this;
				siteNumberField.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							if (canEditSite) {
								String title = "Edit Site Number";
								String str = siteNumberField.getText().trim();
								if (str.length() > 0) {
									int i = sourceTV.siteNumber;
									try {
										i = Integer.parseInt(str);
									} catch (NumberFormatException ne) {
										errorReporter.reportValidationError(title, "The site number must be a number.");
									}
									if (i != sourceTV.siteNumber) {
										if (service.isDTS) {
											if (i < 1) {
												errorReporter.reportValidationError(title,
													"The site number must be greater than 0.");
											} else {
												sourceTV.siteNumber = i;
												int theIndex = dtsTabPane.indexOfComponent(that);
												if (theIndex > 1) {
													dtsTabPane.setTitleAt(theIndex, "Site " + i);
												}
											}
										} else {
											if (i < 0) {
												errorReporter.reportValidationError(title,
													"The site number must be greater than or equal to 0.");
											} else {
												sourceTV.siteNumber = i;
											}
										}
									}
								}
							}
							siteNumberField.setText(String.valueOf(sourceTV.siteNumber));
							blockActionsEnd();
						}
					}
				});

				siteNumberField.addFocusListener(new FocusAdapter() {
					public void focusGained(FocusEvent theEvent) {
						setCurrentField(siteNumberField);
					}
					public void focusLost(FocusEvent theEvent) {
						if (!theEvent.isTemporary()) {
							siteNumberField.postActionEvent();
						}
					}
				});

				siteNumberPanel = new JPanel();
				siteNumberPanel.setBorder(BorderFactory.createTitledBorder("Site Number"));
				siteNumberPanel.add(siteNumberField);
			}

			// For TV or FM, option to define the studied service area by methods other than default contour method.

			JPanel serviceAreaPanel = null;

			if ((isTV || isFM) && showServiceArea) {

				serviceAreaModeMenu = new KeyedRecordMenu(serviceAreaModeList);

				serviceAreaModeMenu.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							int theMode = serviceAreaModeMenu.getSelectedKey();
							if (isTV) {
								if (canEditServiceArea) {
									sourceTV.serviceAreaMode = theMode;
								} else {
									theMode = sourceTV.serviceAreaMode;
									serviceAreaModeMenu.setSelectedKey(theMode);
								}
							} else {
								if (canEditServiceArea) {
									sourceFM.serviceAreaMode = theMode;
								} else {
									theMode = sourceFM.serviceAreaMode;
									serviceAreaModeMenu.setSelectedKey(theMode);
								}
							}
							if (canEditServiceArea) {
								switch (theMode) {
									case Source.SERVAREA_CONTOUR_DEFAULT:
									case Source.SERVAREA_NO_BOUNDS: {
										AppController.setComponentEnabled(serviceAreaArgumentField, false);
										AppController.setComponentEnabled(serviceAreaContourLevelField, false);
										AppController.setComponentEnabled(serviceAreaGeographyMenu, false);
										editAreaGeoButton.setEnabled(false);
										break;
									}
									case Source.SERVAREA_CONTOUR_FCC: {
										AppController.setComponentEnabled(serviceAreaArgumentField, false);
										AppController.setComponentEnabled(serviceAreaContourLevelField, true);
										AppController.setComponentEnabled(serviceAreaGeographyMenu, false);
										editAreaGeoButton.setEnabled(false);
										break;
									}
									case Source.SERVAREA_CONTOUR_FCC_ADD_DIST:
									case Source.SERVAREA_CONTOUR_FCC_ADD_PCNT:
									case Source.SERVAREA_CONTOUR_LR_PERCENT:
									case Source.SERVAREA_CONTOUR_LR_RUN_ABOVE:
									case Source.SERVAREA_CONTOUR_LR_RUN_BELOW: {
										AppController.setComponentEnabled(serviceAreaArgumentField, true);
										AppController.setComponentEnabled(serviceAreaContourLevelField, true);
										AppController.setComponentEnabled(serviceAreaGeographyMenu, false);
										editAreaGeoButton.setEnabled(false);
										break;
									}
									case Source.SERVAREA_GEOGRAPHY_FIXED:
									case Source.SERVAREA_GEOGRAPHY_RELOCATED: {
										AppController.setComponentEnabled(serviceAreaArgumentField, false);
										AppController.setComponentEnabled(serviceAreaContourLevelField, false);
										AppController.setComponentEnabled(serviceAreaGeographyMenu, true);
										editAreaGeoButton.setEnabled(true);
										break;
									}
									case Source.SERVAREA_RADIUS: {
										AppController.setComponentEnabled(serviceAreaArgumentField, true);
										AppController.setComponentEnabled(serviceAreaContourLevelField, false);
										AppController.setComponentEnabled(serviceAreaGeographyMenu, false);
										editAreaGeoButton.setEnabled(false);
										break;
									}
								}
							} else {
								AppController.setComponentEnabled(serviceAreaArgumentField, false);
								AppController.setComponentEnabled(serviceAreaContourLevelField, false);
								AppController.setComponentEnabled(serviceAreaGeographyMenu, false);
								editAreaGeoButton.setEnabled(false);
							}
							blockActionsEnd();
						}
					}
				});

				serviceAreaArgumentField = new JTextField(6);
				AppController.fixKeyBindings(serviceAreaArgumentField);

				serviceAreaArgumentField.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							double theArg;
							if (isTV) {
								theArg = sourceTV.serviceAreaArg;
							} else {
								theArg = sourceFM.serviceAreaArg;
							}
							if (canEditServiceArea) {
								String title = "Edit Contour Mode Argument";
								String str = serviceAreaArgumentField.getText().trim();
								if (str.length() > 0) {
									double d = theArg;
									try {
										d = Double.parseDouble(str);
									} catch (NumberFormatException ne) {
										errorReporter.reportValidationError(title, "The argument must be a number.");
									}
									if (d != theArg) {
										if ((d < Source.SERVAREA_ARGUMENT_MIN) || (d > Source.SERVAREA_ARGUMENT_MAX)) {
											errorReporter.reportValidationError(title,
												"The argument must be in the range " + Source.SERVAREA_ARGUMENT_MIN +
												" to " + Source.SERVAREA_ARGUMENT_MAX + ".");
										} else {
											if (isTV) {
												sourceTV.serviceAreaArg = d;
											} else {
												sourceFM.serviceAreaArg = d;
											}
											theArg = d;
										}
									}
								}
							}
							serviceAreaArgumentField.setText(AppCore.formatDecimal(theArg, 1));
							blockActionsEnd();
						}
					}
				});

				serviceAreaArgumentField.addFocusListener(new FocusAdapter() {
					public void focusGained(FocusEvent theEvent) {
						setCurrentField(serviceAreaArgumentField);
					}
					public void focusLost(FocusEvent theEvent) {
						if (!theEvent.isTemporary()) {
							serviceAreaArgumentField.postActionEvent();
						}
					}
				});

				serviceAreaContourLevelField = new JTextField(6);
				AppController.fixKeyBindings(serviceAreaContourLevelField);

				serviceAreaContourLevelField.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							double theCL;
							if (isTV) {
								theCL = sourceTV.serviceAreaCL;
							} else {
								theCL = sourceFM.serviceAreaCL;
							}
							if (canEditServiceArea) {
								String title = "Edit Contour Level";
								String str = serviceAreaContourLevelField.getText().trim();
								if (str.length() > 0) {
									if (str.equals(Source.SERVAREA_CL_DEFAULT_LABEL)) {
										str = String.valueOf(Source.SERVAREA_CL_DEFAULT);
									}
									double d = theCL;
									try {
										d = Double.parseDouble(str);
									} catch (NumberFormatException ne) {
										errorReporter.reportValidationError(title,
											"The contour level must be a number.");
									}
									if (d != theCL) {
										if ((d != Source.SERVAREA_CL_DEFAULT) && ((d < Source.SERVAREA_CL_MIN) ||
												(d > Source.SERVAREA_CL_MAX))) {
											errorReporter.reportValidationError(title,
												"The contour level must be in the range " + Source.SERVAREA_CL_MIN +
												" to " + Source.SERVAREA_CL_MAX + ".");
										} else {
											if (isTV) {
												sourceTV.serviceAreaCL = d;
											} else {
												sourceFM.serviceAreaCL = d;
											}
											theCL = d;
										}
									}
								}
							}
							if (Source.SERVAREA_CL_DEFAULT == theCL) {
								serviceAreaContourLevelField.setText(Source.SERVAREA_CL_DEFAULT_LABEL);
							} else {
								serviceAreaContourLevelField.setText(AppCore.formatDecimal(theCL, 1));
							}
							blockActionsEnd();
						}
					}
				});

				serviceAreaContourLevelField.addFocusListener(new FocusAdapter() {
					public void focusGained(FocusEvent theEvent) {
						setCurrentField(serviceAreaContourLevelField);
					}
					public void focusLost(FocusEvent theEvent) {
						if (!theEvent.isTemporary()) {
							serviceAreaContourLevelField.postActionEvent();
						}
					}
				});

				serviceAreaGeographyMenu = new KeyedRecordMenu(serviceAreaGeographyList);

				serviceAreaGeographyMenu.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							if (isTV) {
								if (canEditServiceArea) {
									sourceTV.serviceAreaKey = serviceAreaGeographyMenu.getSelectedKey();
								} else {
									serviceAreaGeographyMenu.setSelectedKey(sourceTV.serviceAreaKey);
								}
							} else {
								if (canEditServiceArea) {
									sourceFM.serviceAreaKey = serviceAreaGeographyMenu.getSelectedKey();
								} else {
									serviceAreaGeographyMenu.setSelectedKey(sourceFM.serviceAreaKey);
								}
							}
							blockActionsEnd();
						}
					}
				});

				JPanel servModePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
				servModePanel.add(serviceAreaModeMenu);

				JPanel contourArgsPanel = new JPanel(new GridLayout(2, 2));
				contourArgsPanel.add(new JLabel("Mode Arg."));
				contourArgsPanel.add(new JLabel("Cont. Level"));
				contourArgsPanel.add(serviceAreaArgumentField);
				contourArgsPanel.add(serviceAreaContourLevelField);

				editAreaGeoButton = new JButton("Edit");
				editAreaGeoButton.setFocusable(false);
				editAreaGeoButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent e) {
						if ((null != mainSource) && (null != mainSource.study)) {
							int theGeoKey = 0;
							if (isTV) {
								theGeoKey = sourceTV.serviceAreaKey;
							} else {
								theGeoKey = sourceFM.serviceAreaKey;
							}
							StudyManager.showGeographyEditor(getDbID(), mainSource.study.study.key,
								mainSource.study.name, mainSource.key.intValue(), mainSource.callSign,
								Geography.MODE_AREA, theGeoKey);
						} else {
							StudyManager.showGeographyEditor(getDbID(), 0, "", 0, "", Geography.MODE_AREA, 0);
						}
					}
				});

				JPanel servMenuPanel = new JPanel();
				servMenuPanel.add(serviceAreaGeographyMenu);
				servMenuPanel.add(editAreaGeoButton);

				JPanel servGeoPanel = new JPanel(new GridLayout(2, 1));
				servGeoPanel.add(new JLabel("Geography"));
				servGeoPanel.add(servMenuPanel);

				Box servAreaBox = Box.createVerticalBox();
				servAreaBox.add(servModePanel);
				servAreaBox.add(contourArgsPanel);
				servAreaBox.add(servGeoPanel);

				serviceAreaPanel = new JPanel();
				serviceAreaPanel.setBorder(BorderFactory.createTitledBorder("Service Area"));
				serviceAreaPanel.add(servAreaBox);
			}

			// Time delay field, text type-in, only for DTS transmitter.

			JPanel timeDelayPanel = null;

			if (isTV && service.isDTS && (sourceTV.siteNumber > 0)) {

				timeDelayField = new JTextField(7);
				AppController.fixKeyBindings(timeDelayField);

				timeDelayField.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							if (canEdit) {
								String title = "Edit Time Delay";
								String str = timeDelayField.getText().trim();
								if (str.length() > 0) {
									double d = sourceTV.dtsTimeDelay;
									try {
										d = Double.parseDouble(str);
									} catch (NumberFormatException ne) {
										errorReporter.reportValidationError(title, "The time delay must be a number.");
									}
									if (d != sourceTV.dtsTimeDelay) {
										if ((d < Source.TIME_DELAY_MIN) || (d > Source.TIME_DELAY_MAX)) {
											errorReporter.reportValidationError(title, "The time delay must be in " +
												"the range " + Source.TIME_DELAY_MIN + " to " + Source.TIME_DELAY_MAX +
												".");
										} else {
											sourceTV.dtsTimeDelay = d;
										}
									}
								}
							}
							timeDelayField.setText(AppCore.formatDecimal(sourceTV.dtsTimeDelay, 2));
							blockActionsEnd();
						}
					}
				});

				timeDelayField.addFocusListener(new FocusAdapter() {
					public void focusGained(FocusEvent theEvent) {
						setCurrentField(timeDelayField);
					}
					public void focusLost(FocusEvent theEvent) {
						if (!theEvent.isTemporary()) {
							timeDelayField.postActionEvent();
						}
					}
				});

				timeDelayPanel = new JPanel();
				timeDelayPanel.setBorder(BorderFactory.createTitledBorder("Time delay, µS"));
				timeDelayPanel.add(timeDelayField);
			}

			// Baseline record check-box, only for TV, and only for non-DTS (flag is on parent, see DTSSourcePanel).

			JPanel baselinePanel = null;

			if (isTV && !service.isDTS) {

				baselineCheckBox = new JCheckBox("Baseline record");
				baselineCheckBox.setFocusable(false);

				baselineCheckBox.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							if (canEdit) {
								if (baselineCheckBox.isSelected()) {
									source.setAttribute(Source.ATTR_IS_BASELINE);
								} else {
									source.removeAttribute(Source.ATTR_IS_BASELINE);
								}
							}
							blockActionsEnd();
						}
					}
				});

				baselinePanel = new JPanel();
				baselinePanel.add(baselineCheckBox);
			}

			// Do the layout.

			JPanel row1Panel = new JPanel();
			row1Panel.add(servicePanel);
			if (null != facilityIDPanel) {
				row1Panel.add(facilityIDPanel);
			}
			row1Panel.add(recordIDPanel);
			row1Panel.add(countryPanel);

			JPanel row2Panel = new JPanel();
			row2Panel.add(callSignPanel);
			if (null != sectorIDPanel) {
				row2Panel.add(sectorIDPanel);
			}
			if (null != channelPanel) {
				row2Panel.add(channelPanel);
			}
			if (null != statusPanel) {
				row2Panel.add(statusPanel);
			}
			row2Panel.add(cityPanel);
			row2Panel.add(statePanel);

			JPanel row3Panel = new JPanel();
			if (null != stationClassPanel) {
				row3Panel.add(stationClassPanel);
			}
			row3Panel.add(fileNumberPanel);
			if (null != zonePanel) {
				row3Panel.add(zonePanel);
			}
			if (null != signalTypePanel) {
				row3Panel.add(signalTypePanel);
			}
			if (null != frequencyOffsetPanel) {
				row3Panel.add(frequencyOffsetPanel);
			}
			if (null != emissionMaskPanel) {
				row3Panel.add(emissionMaskPanel);
			}
			if (null != timeDelayPanel) {
				row3Panel.add(timeDelayPanel);
			}

			JPanel row4Panel = new JPanel();
			row4Panel.add(latitudePanel);
			row4Panel.add(longitudePanel);
			if (null != siteNumberPanel) {
				row4Panel.add(siteNumberPanel);
			}

			JPanel row5Panel = new JPanel();
			row5Panel.add(heightAMSLPanel);
			row5Panel.add(overallHAATPanel);
			row5Panel.add(peakERPPanel);
			row5Panel.add(horizontalPatternPanel);
			if (null != baselinePanel) {
				row5Panel.add(baselinePanel);
			}

			JPanel row6Panel = new JPanel();
			if (null != serviceAreaPanel) {
				row6Panel.add(serviceAreaPanel);
			}
			row6Panel.add(verticalPatternPanel);

			Box mainBox = Box.createVerticalBox();
			mainBox.add(row1Panel);
			mainBox.add(row2Panel);
			mainBox.add(row3Panel);
			mainBox.add(row4Panel);
			mainBox.add(row5Panel);
			mainBox.add(row6Panel);

			add(mainBox);

			// Update all fields.

			updateFields();

			blockActionsEnd();
		}


		//-------------------------------------------------------------------------------------------------------------
		// Update the service area geography menu.  Note there is no check to see if the selected geography no longer
		// appears; that should never happen as the geography editor checks as-edited state before allowing a delete.

		private void updateGeographies() {

			if ((isTV || isFM) && showServiceArea) {

				blockActionsStart();

				serviceAreaGeographyMenu.removeAllItems();
				serviceAreaGeographyMenu.addAllItems(serviceAreaGeographyList);
				if (isTV) {
					serviceAreaGeographyMenu.setSelectedKey(sourceTV.serviceAreaKey);
				} else {
					serviceAreaGeographyMenu.setSelectedKey(sourceFM.serviceAreaKey);
				}

				blockActionsEnd();
			}
		}


		//-------------------------------------------------------------------------------------------------------------
		// Reload UI from the source object, may be called while panel is visible.

		private void updateFields() {

			blockActionsStart();

			Source theSource = source.getSource();

			// The facility ID, service, and country are usually display-only, except when creating a new record.  But
			// the properties are immutable in the source object so the editing controls apply changes to local
			// properties in the outer class, those will be applied by deriving a new source when edits are committed,
			// see doOK().  However one type of change must be applied immediately, that is a service change from
			// non-DTS to DTS or vice-versa.  When that happens the editor has to transform, see applyServiceChange().
			// Once in DTS new-record mode, the facility ID, service, and country become non-editable here regardless;
			// they are editable in the parent source, see DTSSourcePanel.  Also for a DTS source when editable, new
			// record or otherwise, some other fields are always non-editable regardless because those properties are
			// group-edited through the parent source.  That includes call sign, city, and state for all DTS sources,
			// and channel, zone, status, and file number for all DTS sources except the reference facility.  For a
			// reference facility the site number is not editable, that must always be 0.  Usually the service area
			// mode is editable, except on a DTS reference facility which must always use the FCC contour.  Also for a
			// record in a TVIX, OET74, or TV6FM study the channel and coordinates cannot be edited if the record is
			// permanent in the/ scenario.  In those cases the scenario was automatically built based on interference
			// rule relationships to the permanent record so changing the channel or location would invalidate the
			// entire scenario.  Note frequency offset and emission mask for TV records also affect rule relationships
			// but those have no impact on the build logic because they only affect D/U ratios, not search distances.

			canEditFacServCntry = isNewSource;
			canEditCallCityState = canEdit;
			canEditChan = canEdit;
			canEditZoneStatFile = canEdit;
			canEditCoords = canEdit;
			canEditSite = canEdit;
			canEditServiceArea = canEdit;

			if (isTV && (null != sourceTV.parentSourceKey)) {
				canEditFacServCntry = false;
				canEditCallCityState = false;
				if (sourceTV.siteNumber > 0) {
					canEditChan = false;
					canEditZoneStatFile = false;
				} else {
					canEditSite = false;
					canEditServiceArea = false;
				}
			}

			if (isPermanentInScenario && (null != source.study) &&
					((Study.STUDY_TYPE_TV_OET74 == source.study.study.studyType) ||
					(Study.STUDY_TYPE_TV6_FM == source.study.study.studyType))) {
				canEditChan = false;
				canEditCoords = false;
			}

			if (isTV || isFM) {
				if (canEditFacServCntry) {
					facilityIDField.setVisible(true);
					facilityIDField.setText(String.valueOf(facilityID));
					facilityIDLabel.setVisible(false);
					facilityIDLabel.setText(" ");
				} else {
					facilityIDLabel.setVisible(true);
					facilityIDLabel.setText(String.valueOf(facilityID));
					facilityIDField.setVisible(false);
					facilityIDField.setText("");
				}
			}

			if (isFM) {
				if (canEditFacServCntry) {
					stationClassMenu.setVisible(true);
					stationClassMenu.setSelectedKey(stationClass);
					stationClassLabel.setVisible(false);
					stationClassLabel.setText(" ");
				} else {
					stationClassLabel.setVisible(true);
					stationClassLabel.setText(ExtDbRecordFM.FM_CLASS_CODES[stationClass]);
					stationClassMenu.setVisible(false);
				}
			}

			if (source.hasRecordID()) {
				stationDataLabel.setText(source.getStationData());
				recordIDLabel.setText(source.getRecordID());
				recordIDPanel.setVisible(true);
			} else {
				stationDataLabel.setText(" ");
				recordIDLabel.setText(" ");
				recordIDPanel.setVisible(false);
			}

			// Currently a DRT cannot be created through the UI so that can only occur on an existing source.  Note
			// the service on a DTS reference facility is different than the parent.

			if (canEditFacServCntry) {
				serviceMenu.setVisible(true);
				serviceMenu.setSelectedItem(service);
				serviceLabel.setVisible(false);
				serviceLabel.setText(" ");
			} else {
				serviceLabel.setVisible(true);
				if (isTV) {
					if (sourceTV.isDRT) {
						serviceLabel.setText("Digital Replacement Translator");
					} else {
						if ((null != sourceTV.parentSourceKey) && (0 == sourceTV.siteNumber)) {
							serviceLabel.setText(source.service.name);
						} else {
							serviceLabel.setText(service.name);
						}
					}
				} else {
					serviceLabel.setText(service.name);
				}
				serviceMenu.setVisible(false);
			}

			callSignField.setText(source.callSign);
			AppController.setComponentEnabled(callSignField, canEditCallCityState);

			if (isWL) {
				sectorIDField.setText(sourceWL.sectorID);
				AppController.setComponentEnabled(sectorIDField, canEditCallCityState);
			}

			if (isTV) {

				channelField.setText(String.valueOf(sourceTV.channel));
				AppController.setComponentEnabled(channelField, canEditChan);
				String lbl = " ";
				if (null != channelNote) {
					lbl = channelNote;
				} else {
					if ((null != mainSourceTV.originalSourceKey) && (null != mainSource.study)) {
						SourceEditDataTV origSource =
							(SourceEditDataTV)mainSource.study.getSource(mainSourceTV.originalSourceKey);
						if (null != origSource) {
							lbl = "replicated from " + (origSource.service.serviceType.digital ? "D" : "N") +
								origSource.channel;
						}
					}
				}
				replicationLabel.setText(lbl);
				if ((null != sourceTV.parentSourceKey) && (0 == sourceTV.siteNumber)) {
					frequencyLabel.setText(sourceTV.getFrequency());
				} else {
					frequencyLabel.setText(SourceEditDataTV.getFrequency(sourceTV.channel, service));
				}
			}

			if (isFM) {

				channelField.setText(String.valueOf(sourceFM.channel));
				AppController.setComponentEnabled(channelField, canEditChan);
				replicationLabel.setText("");
				frequencyLabel.setText(sourceFM.getFrequency());
			}

			cityField.setText(source.city);
			AppController.setComponentEnabled(cityField, canEditCallCityState);

			stateField.setText(source.state);
			AppController.setComponentEnabled(stateField, canEditCallCityState);

			if (canEditFacServCntry) {
				countryMenu.setVisible(true);
				countryMenu.setSelectedItem(country);
				countryLabel.setVisible(false);
				countryLabel.setText(" ");
			} else {
				countryLabel.setVisible(true);
				countryLabel.setText(country.name);
				countryMenu.setVisible(false);
			}

			if (isTV) {

				zoneMenu.setSelectedItem(sourceTV.zone);
				AppController.setComponentEnabled(zoneMenu, canEditZoneStatFile);

				statusItem = new KeyedRecord(ExtDbRecord.getStatusType(sourceTV.status), sourceTV.status);
				statusMenu.setSelectedItem(statusItem);
				AppController.setComponentEnabled(statusMenu, canEditZoneStatFile);

				siteNumberField.setText(String.valueOf(sourceTV.siteNumber));
				AppController.setComponentEnabled(siteNumberField, canEditSite);

				if (null != signalTypeMenu) {
					signalTypeMenu.setSelectedItem(sourceTV.signalType);
					AppController.setComponentEnabled(signalTypeMenu, (canEdit && service.serviceType.digital));
				}

				frequencyOffsetMenu.setSelectedItem(sourceTV.frequencyOffset);
				AppController.setComponentEnabled(frequencyOffsetMenu, canEdit);

				// The emission mask may be non-editable depending on the service type.

				emissionMaskMenu.setSelectedItem(sourceTV.emissionMask);
				AppController.setComponentEnabled(emissionMaskMenu, (canEdit && service.serviceType.needsEmissionMask));
			}

			if (isFM) {

				statusItem = new KeyedRecord(ExtDbRecord.getStatusType(sourceFM.status), sourceFM.status);
				statusMenu.setSelectedItem(statusItem);
				AppController.setComponentEnabled(statusMenu, canEditZoneStatFile);
			}

			fileNumberField.setText(source.fileNumber);
			AppController.setComponentEnabled(fileNumberField, canEditZoneStatFile);

			editPoint.setLatLon(source.location);
			latitudePanel.update();
			longitudePanel.update();
			latitudePanel.setEnabled(canEditCoords);
			longitudePanel.setEnabled(canEditCoords);

			if (Source.HEIGHT_DERIVE == source.heightAMSL) {
				heightAMSLField.setText(Source.HEIGHT_DERIVE_LABEL);
			} else {
				heightAMSLField.setText(AppCore.formatHeight(source.heightAMSL));
			}
			AppController.setComponentEnabled(heightAMSLField, canEdit);
			if ((null != theSource) && (theSource.actualHeightAMSL != source.heightAMSL) &&
					(Source.HEIGHT_DERIVE != theSource.actualHeightAMSL)) {
				actualHeightAMSLLabel.setText("Last run: " + AppCore.formatHeight(theSource.actualHeightAMSL));
			} else {
				actualHeightAMSLLabel.setText(" ");
			}

			if (Source.HEIGHT_DERIVE == source.overallHAAT) {
				overallHAATField.setText(Source.HEIGHT_DERIVE_LABEL);
			} else {
				overallHAATField.setText(AppCore.formatHeight(source.overallHAAT));
			}
			AppController.setComponentEnabled(overallHAATField, canEdit);
			if ((null != theSource) && (theSource.actualOverallHAAT != source.overallHAAT) &&
					(Source.HEIGHT_DERIVE != theSource.actualOverallHAAT)) {
				actualOverallHAATLabel.setText("Last run: " + AppCore.formatHeight(theSource.actualOverallHAAT));
			} else {
				actualOverallHAATLabel.setText(" ");
			}

			peakERPField.setText(AppCore.formatERP(source.peakERP));
			AppController.setComponentEnabled(peakERPField, canEdit);

			if (isFM) {

				ibocCheckBox.setSelected(sourceFM.isIBOC);
				AppController.setComponentEnabled(ibocCheckBox, canEdit);

				if (sourceFM.isIBOC) {
					ibocERPField.setText(AppCore.formatERP(sourceFM.peakERP * sourceFM.ibocFraction));
				} else {
					ibocERPField.setText("");
				}
				AppController.setComponentEnabled(ibocERPField, canEdit);
			}

			if (source.hasHorizontalPattern) {
				String theName = "(unknown)";
				if (null != source.horizontalPattern) {
					theName = source.horizontalPattern.name;
				} else {
					if (null != theSource) {
						theName = theSource.horizontalPatternName;
					}
				}
				horizontalPatternLabel.setText(theName);
				horizontalPatternLabel.setToolTipText(theName);
			} else {
				horizontalPatternLabel.setText("(none)");
				horizontalPatternLabel.setToolTipText(null);
			}
			if (canEdit) {
				editHorizontalPatternButton.setText("Edit");
				editHorizontalPatternButton.setEnabled(true);
			} else {
				editHorizontalPatternButton.setText("View");
				editHorizontalPatternButton.setEnabled(source.hasHorizontalPattern);
			}
			horizontalPatternOrientationLabel.setText(AppCore.formatAzimuth(source.horizontalPatternOrientation));

			if (source.hasMatrixPattern) {
				String theName = "(unknown matrix)";
				if (null != source.matrixPattern) {
					theName = source.matrixPattern.name + " (matrix)";
				} else {
					if (null != theSource) {
						theName = theSource.matrixPatternName + " (matrix)";
					}
				}
				verticalPatternLabel.setText(theName);
				verticalPatternLabel.setToolTipText(theName);
				if (canEdit) {
					editVerticalPatternButton.setText("Edit");
				} else {
					editVerticalPatternButton.setText("View");
				}
				editVerticalPatternButton.setEnabled(true);
			} else {

				if (source.hasVerticalPattern) {
					String theName = "(unknown)";
					if (null != source.verticalPattern) {
						theName = source.verticalPattern.name;
					} else {
						if (null != theSource) {
							theName = theSource.verticalPatternName;
						}
					}
					verticalPatternLabel.setText(theName);
					verticalPatternLabel.setToolTipText(theName);
				} else {
					verticalPatternLabel.setText("(none)");
					verticalPatternLabel.setToolTipText(null);
				}
				if (canEdit) {
					editVerticalPatternButton.setText("Edit");
					editVerticalPatternButton.setEnabled(true);
				} else {
					editVerticalPatternButton.setText("View");
					editVerticalPatternButton.setEnabled(source.hasVerticalPattern);
				}
			}

			// Vertical pattern tilt fields and the setting to allow a generic pattern are always displayed, and
			// editable if appropriate, regardless of any actual vertical or matrix pattern.  Study parameters may
			// disable use of actual patterns for contour projection, in which case a generic pattern may be used if
			// allowed, else no pattern will be used.  The tilt parameters may apply to the generic pattern.

			verticalPatternElectricalTiltField.setText(AppCore.formatDepression(source.verticalPatternElectricalTilt));
			verticalPatternMechanicalTiltField.setText(AppCore.formatDepression(source.verticalPatternMechanicalTilt));
			verticalPatternMechanicalTiltOrientationField.setText(
				AppCore.formatAzimuth(source.verticalPatternMechanicalTiltOrientation));
			AppController.setComponentEnabled(verticalPatternElectricalTiltField, canEdit);
			AppController.setComponentEnabled(verticalPatternMechanicalTiltField, canEdit);
			AppController.setComponentEnabled(verticalPatternMechanicalTiltOrientationField, canEdit);

			useGenericVerticalPatternCheckBox.setSelected(source.useGenericVerticalPattern);
			AppController.setComponentEnabled(useGenericVerticalPatternCheckBox, canEdit);

			// Service area fields.

			if ((isTV || isFM) && showServiceArea) {

				int theMode, theKey;
				double theArg, theCL;

				if (isTV) {
					theMode = sourceTV.serviceAreaMode;
					theArg = sourceTV.serviceAreaArg;
					theCL = sourceTV.serviceAreaCL;
					theKey = sourceTV.serviceAreaKey;
				} else {
					theMode = sourceFM.serviceAreaMode;
					theArg = sourceFM.serviceAreaArg;
					theCL = sourceFM.serviceAreaCL;
					theKey = sourceFM.serviceAreaKey;
				}

				serviceAreaModeMenu.setSelectedKey(theMode);
				serviceAreaArgumentField.setText(AppCore.formatDecimal(theArg, 2));
				if (Source.SERVAREA_CL_DEFAULT == theCL) {
					serviceAreaContourLevelField.setText(Source.SERVAREA_CL_DEFAULT_LABEL);
				} else {
					serviceAreaContourLevelField.setText(AppCore.formatDecimal(theCL, 1));
				}
				serviceAreaGeographyMenu.setSelectedKey(theKey);

				if (canEditServiceArea) {
					AppController.setComponentEnabled(serviceAreaModeMenu, true);
					switch (theMode) {
						case Source.SERVAREA_CONTOUR_DEFAULT:
						case Source.SERVAREA_NO_BOUNDS: {
							AppController.setComponentEnabled(serviceAreaArgumentField, false);
							AppController.setComponentEnabled(serviceAreaContourLevelField, false);
							AppController.setComponentEnabled(serviceAreaGeographyMenu, false);
							editAreaGeoButton.setEnabled(false);
							break;
						}
						case Source.SERVAREA_CONTOUR_FCC: {
							AppController.setComponentEnabled(serviceAreaArgumentField, false);
							AppController.setComponentEnabled(serviceAreaContourLevelField, true);
							AppController.setComponentEnabled(serviceAreaGeographyMenu, false);
							editAreaGeoButton.setEnabled(false);
							break;
						}
						case Source.SERVAREA_CONTOUR_FCC_ADD_DIST:
						case Source.SERVAREA_CONTOUR_FCC_ADD_PCNT:
						case Source.SERVAREA_CONTOUR_LR_PERCENT:
						case Source.SERVAREA_CONTOUR_LR_RUN_ABOVE:
						case Source.SERVAREA_CONTOUR_LR_RUN_BELOW: {
							AppController.setComponentEnabled(serviceAreaArgumentField, true);
							AppController.setComponentEnabled(serviceAreaContourLevelField, true);
							AppController.setComponentEnabled(serviceAreaGeographyMenu, false);
							editAreaGeoButton.setEnabled(false);
							break;
						}
						case Source.SERVAREA_GEOGRAPHY_FIXED:
						case Source.SERVAREA_GEOGRAPHY_RELOCATED: {
							AppController.setComponentEnabled(serviceAreaArgumentField, false);
							AppController.setComponentEnabled(serviceAreaContourLevelField, false);
							AppController.setComponentEnabled(serviceAreaGeographyMenu, true);
							editAreaGeoButton.setEnabled(true);
							break;
						}
						case Source.SERVAREA_RADIUS: {
							AppController.setComponentEnabled(serviceAreaArgumentField, true);
							AppController.setComponentEnabled(serviceAreaContourLevelField, false);
							AppController.setComponentEnabled(serviceAreaGeographyMenu, false);
							editAreaGeoButton.setEnabled(false);
							break;
						}
					}
				} else {
					AppController.setComponentEnabled(serviceAreaModeMenu, false);
					AppController.setComponentEnabled(serviceAreaArgumentField, false);
					AppController.setComponentEnabled(serviceAreaContourLevelField, false);
					AppController.setComponentEnabled(serviceAreaGeographyMenu, false);
					editAreaGeoButton.setEnabled(false);
				}
			}

			// DTS time delay.

			if (null != timeDelayField) {
				timeDelayField.setText(AppCore.formatDecimal(sourceTV.dtsTimeDelay, 2));
				AppController.setComponentEnabled(timeDelayField, canEdit);
			}

			// Baseline flag.

			if (null != baselineCheckBox) {
				baselineCheckBox.setSelected(null != source.getAttribute(Source.ATTR_IS_BASELINE));
				AppController.setComponentEnabled(baselineCheckBox, canEdit);
			}

			blockActionsEnd();
		}


		//-------------------------------------------------------------------------------------------------------------
		// Pattern editing is handled by a secondary dialog that manipulates the source directly.  If no data and not
		// editable there is no point to opening the dialog, should not have been called, do nothing.

		private void doEditHorizontalPattern() {

			if (null != horizontalPatternEditor) {
				if (horizontalPatternEditor.isVisible()) {
					horizontalPatternEditor.toFront();
					return;
				}
				patternEditorIndex.remove(horizontalPatternEditor);
				horizontalPatternEditor = null;
			}

			if (!canEdit && !source.hasHorizontalPattern) {
				return;
			}

			errorReporter.setTitle("Edit Azimuth Pattern");

			// Load the data if needed; if this fails when editable set no-pattern and open anyway, else don't open.

			if (source.hasHorizontalPattern && (null == source.horizontalPattern)) {
				if (null != source.getSource()) {
					source.horizontalPattern = source.getSource().getHorizontalPattern(errorReporter);
				}
				if (null == source.horizontalPattern) {
					if (!canEdit) {
						return;
					}
					source.hasHorizontalPattern = false;
					source.horizontalPatternChanged = true;
				}
			}

			// Create and show the editor.  When it makes changes it calls applyEditsFrom() on the outer class which
			// will identify the SourcePanel the editor belongs to and call applyEditsFrom() here.

			horizontalPatternEditor = new PatternEditor(outerThis, source.horizontalPattern,
				AntPattern.PATTERN_TYPE_HORIZONTAL, !source.isLocked);
			horizontalPatternEditor.antennaOrientation = source.horizontalPatternOrientation;
			horizontalPatternEditor.antennaID = source.antennaID;
			AppController.showWindow(horizontalPatternEditor);

			patternEditorIndex.put(horizontalPatternEditor, this);
		}


		//-------------------------------------------------------------------------------------------------------------
		// Edit vertical or matrix pattern, similar to above.

		private void doEditVerticalPattern() {

			if (null != verticalPatternEditor) {
				if (verticalPatternEditor.isVisible()) {
					verticalPatternEditor.toFront();
					return;
				}
				patternEditorIndex.remove(verticalPatternEditor);
				verticalPatternEditor = null;
			}

			if (!canEdit && !source.hasMatrixPattern && !source.hasVerticalPattern) {
				return;
			}

			errorReporter.setTitle("Edit Elevation Pattern");

			// If editable, check for case where source has both matrix and vertical patterns; it should only have one
			// or the other, if both, remove one giving preference to the matrix.

			AntPattern thePat = null;

			if (source.hasMatrixPattern) {

				if (canEdit && source.hasVerticalPattern) {
					source.hasVerticalPattern = false;
					source.verticalPattern = null;
					source.verticalPatternChanged = true;
				}

				if (null == source.matrixPattern) {
					if (null != source.getSource()) {
						source.matrixPattern = source.getSource().getMatrixPattern(errorReporter);
					}
					if (null == source.matrixPattern) {
						if (!canEdit) {
							return;
						}
						source.hasMatrixPattern = false;
						source.matrixPatternChanged = true;
					}
				}

				thePat = source.matrixPattern;

			} else {

				if (source.hasVerticalPattern && (null == source.verticalPattern)) {
					if (null != source.getSource()) {
						source.verticalPattern = source.getSource().getVerticalPattern(errorReporter);
					}
					if (null == source.verticalPattern) {
						if (!canEdit) {
							return;
						}
						source.hasVerticalPattern = false;
						source.verticalPatternChanged = true;
					}
				}

				thePat = source.verticalPattern;
			}

			verticalPatternEditor = new PatternEditor(outerThis, thePat, AntPattern.PATTERN_TYPE_VERTICAL,
				!source.isLocked);
			AppController.showWindow(verticalPatternEditor);

			patternEditorIndex.put(verticalPatternEditor, this);
		}


		//-------------------------------------------------------------------------------------------------------------
		// Called by the outer class when pattern editors report edits and closings.

		private boolean applyEditsFrom(PatternEditor theEditor) {

			AntPattern newPattern = theEditor.getPattern();

			if (theEditor == horizontalPatternEditor) {

				if (theEditor.didEdit()) {

					if ((null != newPattern) && !newPattern.isMatrix()) {

						source.hasHorizontalPattern = true;
						source.horizontalPattern = newPattern;
						source.horizontalPatternChanged = true;

						horizontalPatternLabel.setText(newPattern.name);
						horizontalPatternLabel.setToolTipText(newPattern.name);

					} else {

						source.hasHorizontalPattern = false;
						source.horizontalPattern = null;
						source.horizontalPatternChanged = true;

						horizontalPatternLabel.setText("(none)");
						horizontalPatternLabel.setToolTipText(null);
					}
				}

				source.horizontalPatternOrientation = theEditor.antennaOrientation;
				horizontalPatternOrientationLabel.setText(AppCore.formatAzimuth(theEditor.antennaOrientation));

				source.antennaID = theEditor.antennaID;

				return true;
			}

			if (theEditor == verticalPatternEditor) {

				if (theEditor.didEdit()) {

					if (null != newPattern) {

						if (newPattern.isMatrix()) {

							if (source.hasVerticalPattern) {
								source.hasVerticalPattern = false;
								source.verticalPattern = null;
								source.verticalPatternChanged = true;
							}

							source.hasMatrixPattern = true;
							source.matrixPattern = newPattern;
							source.matrixPatternChanged = true;

							verticalPatternLabel.setText(newPattern.name + " (matrix)");
							verticalPatternLabel.setToolTipText(newPattern.name + " (matrix)");

						} else {

							if (source.hasMatrixPattern) {
								source.hasMatrixPattern = false;
								source.matrixPattern = null;
								source.matrixPatternChanged = true;
							}

							source.hasVerticalPattern = true;
							source.verticalPattern = newPattern;
							source.verticalPatternChanged = true;

							verticalPatternLabel.setText(newPattern.name);
							verticalPatternLabel.setToolTipText(newPattern.name);
						}

					} else {

						if (source.hasVerticalPattern) {
							source.hasVerticalPattern = false;
							source.verticalPattern = null;
							source.verticalPatternChanged = true;
						}

						if (source.hasMatrixPattern) {
							source.hasMatrixPattern = false;
							source.matrixPattern = null;
							source.matrixPatternChanged = true;
						}

						verticalPatternLabel.setText("(none)");
						verticalPatternLabel.setToolTipText(null);
					}
				}

				return true;
			}

			return false;
		}


		//-------------------------------------------------------------------------------------------------------------

		private void editorClosing(PatternEditor theEditor) {

			if (theEditor == horizontalPatternEditor) {
				patternEditorIndex.remove(horizontalPatternEditor);
				horizontalPatternEditor = null;
				return;
			}

			if (theEditor == verticalPatternEditor) {
				patternEditorIndex.remove(verticalPatternEditor);
				verticalPatternEditor = null;
				return;
			}
		}
	}


	//=================================================================================================================
	// Separate panel class for editing of the parent source for a DTS operation.  This provides fields for some
	// properties unique to a DTS parent, as well as group-edit fields that are applied to all dependent sources.

	private class DTSSourcePanel extends JPanel {

		private SourceEditDataTV source;

		// Some fields may have restricted editing, see updateFields().

		private boolean canEditChan;
		private boolean canEditCoords;

		private JLabel stationDataLabel;
		private JTextField recordIDLabel;
		private JPanel recordIDPanel;

		private JLabel facilityIDLabel;
		private JTextField facilityIDField;

		private JLabel serviceLabel;
		private KeyedRecordMenu serviceMenu;

		private JTextField callSignField;

		private JTextField channelField;
		private JLabel replicationLabel;
		private JLabel frequencyLabel;

		private JTextField cityField;

		private JTextField stateField;

		private JLabel countryLabel;
		private KeyedRecordMenu countryMenu;

		private KeyedRecordMenu zoneMenu;

		private KeyedRecordMenu signalTypeMenu;

		private KeyedRecordMenu statusMenu;
		private KeyedRecord statusItem;

		private JTextField fileNumberField;

		private GeoPoint editPoint;
		private CoordinatePanel latitudePanel;
		private CoordinatePanel longitudePanel;

		private JTextField distanceField;
		private static final String TABLE_DIST_LABEL = "(table)";

		private JButton editSectorsButton;

		private JCheckBox baselineCheckBox;


		//-------------------------------------------------------------------------------------------------------------
		// Set up UI for a DTS parent source.

		private DTSSourcePanel(SourceEditDataTV theSource) {

			source = theSource;

			blockActionsStart();

			// Data set and record ID.

			stationDataLabel = new JLabel(" ");
			stationDataLabel.setPreferredSize(AppController.labelSize[32]);

			recordIDLabel = new JTextField(24);
			AppController.fixKeyBindings(recordIDLabel);
			AppController.setComponentEnabled(recordIDLabel, false);

			Box idBox = Box.createVerticalBox();
			idBox.add(stationDataLabel);
			idBox.add(recordIDLabel);

			recordIDPanel = new JPanel();
			recordIDPanel.setBorder(BorderFactory.createTitledBorder("Record ID"));
			recordIDPanel.add(idBox);

			// Facility ID is usually a label, but may be an editable field.

			facilityIDLabel = new JLabel(" ");
			facilityIDLabel.setPreferredSize(AppController.labelSize[8]);

			facilityIDField = new JTextField(8);
			AppController.fixKeyBindings(facilityIDField);
			facilityIDField.setVisible(false);

			facilityIDField.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (isNewSource) {
							String title = "Edit Facility ID";
							String str = facilityIDField.getText().trim();
							if (str.length() > 0) {
								int i = facilityID;
								try {
									i = Integer.parseInt(str);
								} catch (NumberFormatException ne) {
									errorReporter.reportValidationError(title, "The facility ID must be a number.");
								}
								if (i != facilityID) {
									facilityID = i;
									updateSourcePanels();
								}
							}
						}
						facilityIDField.setText(String.valueOf(facilityID));
						blockActionsEnd();
					}
				}
			});

			facilityIDField.addFocusListener(new FocusAdapter() {
				public void focusGained(FocusEvent theEvent) {
					setCurrentField(facilityIDField);
				}
				public void focusLost(FocusEvent theEvent) {
					if (!theEvent.isTemporary()) {
						facilityIDField.postActionEvent();
					}
				}
			});

			JPanel facilityIDPanel = new JPanel();
			facilityIDPanel.setBorder(BorderFactory.createTitledBorder("Facility ID"));
			facilityIDPanel.add(facilityIDLabel);
			facilityIDPanel.add(facilityIDField);

			// Service, also just a label in most cases, except it may be a menu.  In this case applyServiceChange()
			// must be called if changing to a non-DTS service, see comments in SourcePanel.

			serviceLabel = new JLabel(" ");
			serviceLabel.setPreferredSize(AppController.labelSize[26]);

			serviceMenu = new KeyedRecordMenu(Service.getServices(Source.RECORD_TYPE_TV));
			serviceMenu.setVisible(false);

			serviceMenu.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (isNewSource) {
							final Service newService = (Service)(serviceMenu.getSelectedItem());
							if (!newService.isDTS) {
								serviceMenu.setSelectedItem(service);
								SwingUtilities.invokeLater(new Runnable() {
									public void run() {
										applyServiceChange(newService);
									}
								});
							} else {
								service = newService;
								updateSourcePanels();
							}
						} else {
							serviceMenu.setSelectedItem(service);
						}
						blockActionsEnd();
					}
				}
			});

			JPanel servicePanel = new JPanel();
			servicePanel.setBorder(BorderFactory.createTitledBorder("Service"));
			servicePanel.add(serviceLabel);
			servicePanel.add(serviceMenu);

			// Call sign field, changes apply to all individual sources including the reference facility.

			callSignField = new JTextField(10);
			AppController.fixKeyBindings(callSignField);

			callSignField.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (canEdit) {
							String str = callSignField.getText().trim().toUpperCase();
							if (str.length() > 0) {
								if (str.length() > Source.MAX_CALL_SIGN_LENGTH) {
									errorReporter.reportValidationError("Edit Call Sign",
										"The call sign cannot be longer than " + Source.MAX_CALL_SIGN_LENGTH +
										" characters.");
								} else {
									source.callSign = str;
									for (SourceEditDataTV dtsSource : source.getDTSSources()) {
										dtsSource.callSign = str;
									}
									updateSourcePanels();
									frequencyLabel.setText(SourceEditDataTV.getFrequency(source.channel, service));
								}
							}
						}
						callSignField.setText(source.callSign);
						updateDocumentName();
						blockActionsEnd();
					}
				}
			});

			callSignField.addFocusListener(new FocusAdapter() {
				public void focusGained(FocusEvent theEvent) {
					setCurrentField(callSignField);
				}
				public void focusLost(FocusEvent theEvent) {
					if (!theEvent.isTemporary()) {
						callSignField.postActionEvent();
					}
				}
			});

			JPanel callSignPanel = new JPanel();
			callSignPanel.setBorder(BorderFactory.createTitledBorder("Call Sign"));
			callSignPanel.add(callSignField);

			// Channel field, this applies to all sources except the reference facility.

			channelField = new JTextField(5);
			AppController.fixKeyBindings(channelField);

			channelField.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (canEditChan) {
							String title = "Edit Channel";
							String str = channelField.getText().trim();
							if (str.length() > 0) {
								int i = source.channel;
								try {
									i = Integer.parseInt(str);
								} catch (NumberFormatException ne) {
									errorReporter.reportValidationError(title, "The channel must be a number.");
								}
								if (i != source.channel) {
									if ((i < minimumChannel) || (i > maximumChannel)) {
										errorReporter.reportValidationError(title,
											"The channel must be in the range " + minimumChannel + " to " +
											maximumChannel + ".");
									} else {
										source.channel = i;
										for (SourceEditDataTV dtsSource : source.getDTSSources()) {
											if (dtsSource.siteNumber > 0) {
												dtsSource.channel = i;
											}
										}
										updateSourcePanels();
									}
								}
							}
						}
						channelField.setText(String.valueOf(source.channel));
						frequencyLabel.setText(SourceEditDataTV.getFrequency(source.channel, service));
						blockActionsEnd();
					}
				}
			});

			channelField.addFocusListener(new FocusAdapter() {
				public void focusGained(FocusEvent theEvent) {
					setCurrentField(channelField);
				}
				public void focusLost(FocusEvent theEvent) {
					if (!theEvent.isTemporary()) {
						channelField.postActionEvent();
					}
				}
			});

			replicationLabel = new JLabel(" ");

			frequencyLabel = new JLabel(" ");

			JPanel chTopP = new JPanel();
			chTopP.add(channelField);
			chTopP.add(frequencyLabel);
			JPanel channelPanel = new JPanel();
			channelPanel.setLayout(new BoxLayout(channelPanel, BoxLayout.Y_AXIS));
			channelPanel.setBorder(BorderFactory.createTitledBorder("Channel"));
			channelPanel.add(chTopP);
			channelPanel.add(replicationLabel);

			// City, text, can't be empty.  This applies to all including the reference facility.

			cityField = new JTextField(18);
			AppController.fixKeyBindings(cityField);

			cityField.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (canEdit) {
							String str = cityField.getText().trim();
							if (str.length() > 0) {
								if (str.length() > Source.MAX_CITY_LENGTH) {
									errorReporter.reportValidationError("Edit City",
										"The city name cannot be longer than " + Source.MAX_CITY_LENGTH +
										" characters.");
								} else {
									source.city = str;
									for (SourceEditDataTV dtsSource : source.getDTSSources()) {
										dtsSource.city = str;
									}
									updateSourcePanels();
								}
							}
						}
						cityField.setText(source.city);
						blockActionsEnd();
					}
				}
			});

			cityField.addFocusListener(new FocusAdapter() {
				public void focusGained(FocusEvent theEvent) {
					setCurrentField(cityField);
				}
				public void focusLost(FocusEvent theEvent) {
					if (!theEvent.isTemporary()) {
						cityField.postActionEvent();
					}
				}
			});

			JPanel cityPanel = new JPanel();
			cityPanel.setBorder(BorderFactory.createTitledBorder("City"));
			cityPanel.add(cityField);

			// State, text, can't be empty.  Also applies to all including reference.

			stateField = new JTextField(3);
			AppController.fixKeyBindings(stateField);

			stateField.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (canEdit) {
							String str = stateField.getText().trim().toUpperCase();
							if (str.length() > 0) {
								if (str.length() > Source.MAX_STATE_LENGTH) {
									errorReporter.reportValidationError("Edit State",
										"The state code cannot be longer than " + Source.MAX_STATE_LENGTH +
										" characters.");
								} else {
									source.state = str;
									for (SourceEditDataTV dtsSource : source.getDTSSources()) {
										dtsSource.state = str;
									}
									updateSourcePanels();
								}
							}
						}
						stateField.setText(source.state);
						blockActionsEnd();
					}
				}
			});

			stateField.addFocusListener(new FocusAdapter() {
				public void focusGained(FocusEvent theEvent) {
					setCurrentField(stateField);
				}
				public void focusLost(FocusEvent theEvent) {
					if (!theEvent.isTemporary()) {
						stateField.postActionEvent();
					}
				}
			});

			JPanel statePanel = new JPanel();
			statePanel.setBorder(BorderFactory.createTitledBorder("State"));
			statePanel.add(stateField);

			// Country, this is usually non-editable so just a label, but may be a menu.

			countryLabel = new JLabel(" ");
			countryLabel.setPreferredSize(AppController.labelSize[6]);

			countryMenu = new KeyedRecordMenu(Country.getCountries());
			countryMenu.setVisible(false);

			countryMenu.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (isNewSource) {
							country = (Country)countryMenu.getSelectedItem();
							updateSourcePanels();
						} else {
							countryMenu.setSelectedItem(country);
						}
						blockActionsEnd();
					}
				}
			});

			JPanel countryPanel = new JPanel();
			countryPanel.setBorder(BorderFactory.createTitledBorder("Country"));
			countryPanel.add(countryLabel);
			countryPanel.add(countryMenu);

			// Zone, pop-up menu, like channel applies to all except reference.

			zoneMenu = new KeyedRecordMenu(Zone.getZonesWithNull());

			zoneMenu.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (canEdit) {
							source.zone = (Zone)zoneMenu.getSelectedItem();
							for (SourceEditDataTV dtsSource : source.getDTSSources()) {
								if (dtsSource.siteNumber > 0) {
									dtsSource.zone = source.zone;
								}
							}
							updateSourcePanels();
						} else {
							zoneMenu.setSelectedItem(source.zone);
						}
						blockActionsEnd();
					}
				}
			});

			JPanel zonePanel = new JPanel();
			zonePanel.setBorder(BorderFactory.createTitledBorder("Zone"));
			zonePanel.add(zoneMenu);

			// Signal type, pop-up menu.  To all except reference.  Hidden if there is only one choice.

			JPanel signalTypePanel = null;

			if (SignalType.hasMultipleOptions()) {

				signalTypeMenu = new KeyedRecordMenu(SignalType.getSignalTypes());

				signalTypeMenu.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							if (canEdit) {
								source.signalType = (SignalType)signalTypeMenu.getSelectedItem();
								for (SourceEditDataTV dtsSource : source.getDTSSources()) {
									if (dtsSource.siteNumber > 0) {
										dtsSource.signalType = source.signalType;
									}
								}
								updateSourcePanels();
							} else {
								signalTypeMenu.setSelectedItem(source.signalType);
							}
							blockActionsEnd();
						}
					}
				});

				signalTypePanel = new JPanel();
				signalTypePanel.setBorder(BorderFactory.createTitledBorder("Mod. Type"));
				signalTypePanel.add(signalTypeMenu);
			}

			// Status, pop-up menu.  To all except reference.

			statusMenu = new KeyedRecordMenu(ExtDbRecord.getStatusList());

			statusMenu.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (canEdit) {
							statusItem = statusMenu.getSelectedItem();
							source.status = statusItem.name;
							source.statusType = statusItem.key;
							for (SourceEditDataTV dtsSource : source.getDTSSources()) {
								if (dtsSource.siteNumber > 0) {
									dtsSource.status = source.status;
									dtsSource.statusType = source.statusType;
								}
							}
							updateSourcePanels();
						} else {
							statusMenu.setSelectedItem(statusItem);
						}
						blockActionsEnd();
					}
				}
			});

			JPanel statusPanel = new JPanel();
			statusPanel.setBorder(BorderFactory.createTitledBorder("Status"));
			statusPanel.add(statusMenu);

			// File number.  All except reference.

			fileNumberField = new JTextField(15);
			AppController.fixKeyBindings(fileNumberField);

			fileNumberField.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (canEdit) {
							String str = fileNumberField.getText().trim();
							if (str.length() > Source.MAX_FILE_NUMBER_LENGTH) {
								errorReporter.reportValidationError("Edit File Number",
									"The file number cannot be longer than " + Source.MAX_FILE_NUMBER_LENGTH +
									" characters.");
							} else {
								source.fileNumber = str;
								char cc;
								String arn = "";
								for (int i = 0; i < str.length(); i++) {
									cc = str.charAt(i);
									if (Character.isDigit(cc)) {
										arn = str.substring(i);
										break;
									}
									if ('-' == cc) {
										arn = str.substring(i + 1);
										break;
									}
								}
								source.appARN = arn;
								for (SourceEditDataTV dtsSource : source.getDTSSources()) {
									if (dtsSource.siteNumber > 0) {
										dtsSource.fileNumber = str;
										dtsSource.appARN = arn;
									}
								}
								updateSourcePanels();
							}
						}
						fileNumberField.setText(source.fileNumber);
						blockActionsEnd();
					}
				}
			});

			fileNumberField.addFocusListener(new FocusAdapter() {
				public void focusGained(FocusEvent theEvent) {
					setCurrentField(fileNumberField);
				}
				public void focusLost(FocusEvent theEvent) {
					if (!theEvent.isTemporary()) {
						fileNumberField.postActionEvent();
					}
				}
			});

			JPanel fileNumberPanel = new JPanel();
			fileNumberPanel.setBorder(BorderFactory.createTitledBorder("File Number"));
			fileNumberPanel.add(fileNumberField);

			// Panels to edit the reference point coordinates.

			editPoint = new GeoPoint();

			latitudePanel = new CoordinatePanel(outerThis, editPoint, false, new Runnable() {
				public void run() {
					if (canEditCoords) {
						source.location.latitude = editPoint.latitude;
						source.location.updateDMS();
					}
				}
			});

			longitudePanel = new CoordinatePanel(outerThis, editPoint, true, new Runnable() {
				public void run() {
					if (canEditCoords) {
						source.location.longitude = editPoint.longitude;
						source.location.updateDMS();
					}
				}
			});

			// Maximum distance field, text type-in.  Special case value of 0 means to use 73.626(c) table.

			distanceField = new JTextField(7);
			AppController.fixKeyBindings(distanceField);

			distanceField.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (canEdit && (0 == source.dtsSectors.length())) {
							String title = "Edit Distance";
							String str = distanceField.getText().trim();
							if (str.length() > 0) {
								if (str.equals(TABLE_DIST_LABEL)) {
									str = "0";
								}
								double d = source.dtsMaximumDistance;
								try {
									d = Double.parseDouble(str);
								} catch (NumberFormatException ne) {
									errorReporter.reportValidationError(title, "The distance must be a number.");
								}
								if (d != source.dtsMaximumDistance) {
									if ((d != 0.) && ((d < Source.DISTANCE_MIN) || (d > Source.DISTANCE_MAX))) {
										errorReporter.reportValidationError(title,
											"The distance must be in the range " + Source.DISTANCE_MIN + " to " +
											Source.DISTANCE_MAX + ".");
									} else {
										source.dtsMaximumDistance = d;
									}
								}
							}
						}
						if (0. == source.dtsMaximumDistance) {
							distanceField.setText(TABLE_DIST_LABEL);
						} else {
							distanceField.setText(AppCore.formatDistance(source.dtsMaximumDistance));
						}
						blockActionsEnd();
					}
				}
			});

			distanceField.addFocusListener(new FocusAdapter() {
				public void focusGained(FocusEvent theEvent) {
					setCurrentField(distanceField);
				}
				public void focusLost(FocusEvent theEvent) {
					if (!theEvent.isTemporary()) {
						distanceField.postActionEvent();
					}
				}
			});

			JPanel distancePanel = new JPanel();
			distancePanel.setBorder(BorderFactory.createTitledBorder("Distance, km"));
			distancePanel.add(distanceField);

			// Alternative to simple radius distance boundary, a sectors geography.  Button to pop up a dialog to view
			// or edit the sectors definition, see GeoEditPanel and GeoPlotPanel.

			editSectorsButton = new JButton("Edit Sectors");
			editSectorsButton.setFocusable(false);
			editSectorsButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doEditSectors();
				}
			});

			JPanel sectorsPanel = new JPanel();
			sectorsPanel.add(editSectorsButton);

			// Baseline record check-box.

			baselineCheckBox = new JCheckBox("Baseline record");
			baselineCheckBox.setFocusable(false);

			baselineCheckBox.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (canEdit) {
							if (baselineCheckBox.isSelected()) {
								source.setAttribute(Source.ATTR_IS_BASELINE);
							} else {
								source.removeAttribute(Source.ATTR_IS_BASELINE);
							}
						}
						blockActionsEnd();
					}
				}
			});

			JPanel baselinePanel = new JPanel();
			baselinePanel.add(baselineCheckBox);

			// Do the layout.

			JPanel row1Panel = new JPanel();
			row1Panel.add(servicePanel);
			row1Panel.add(facilityIDPanel);
			row1Panel.add(recordIDPanel);
			row1Panel.add(countryPanel);

			JPanel row2Panel = new JPanel();
			row2Panel.add(callSignPanel);
			row2Panel.add(channelPanel);
			row2Panel.add(statusPanel);
			row2Panel.add(cityPanel);
			row2Panel.add(statePanel);

			JPanel row3Panel = new JPanel();
			row3Panel.add(fileNumberPanel);
			row3Panel.add(zonePanel);
			if (null != signalTypePanel) {
				row3Panel.add(signalTypePanel);
			}
			row3Panel.add(baselinePanel);

			JPanel latitudeLongitudePanel = new JPanel();
			latitudeLongitudePanel.setLayout(new BoxLayout(latitudeLongitudePanel, BoxLayout.Y_AXIS));
			latitudeLongitudePanel.add(latitudePanel);
			latitudeLongitudePanel.add(longitudePanel);

			JPanel refBoundPanel = new JPanel();
			refBoundPanel.setBorder(BorderFactory.createTitledBorder("Reference point and boundary"));
			refBoundPanel.add(latitudeLongitudePanel);
			refBoundPanel.add(distancePanel);
			refBoundPanel.add(sectorsPanel);

			JPanel row4Panel = new JPanel();
			row4Panel.add(refBoundPanel);

			Box mainBox = Box.createVerticalBox();
			mainBox.add(row1Panel);
			mainBox.add(row2Panel);
			mainBox.add(row3Panel);
			mainBox.add(row4Panel);

			add(mainBox);

			// Update all fields.

			updateFields();

			blockActionsEnd();
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doEditSectors() {

			String title;
			if (canEdit) {
				title = "Edit Sectors";
			} else {
				title = "View Sectors";
			}
			errorReporter.setTitle(title);

			class SectorsDialog extends AppDialog {

				private GeoSectors sectors;

				private GeoEditPanel.SectorsPanel editorPanel;
				private GeoPlotPanel plotPanel;

				private boolean canceled;

				private SectorsDialog() {

					super(outerThis, "DTS Boundary Sectors", Dialog.ModalityType.APPLICATION_MODAL);

					sectors = new GeoSectors(getDbID());
					if (source.dtsSectors.length() > 0) {
						sectors.decodeFromString(source.dtsSectors);
					}

					editorPanel = new GeoEditPanel.SectorsPanel(this, sectors, canEdit, false);
					plotPanel = new GeoPlotPanel();
					plotPanel.setGeography(sectors);

					JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

					if (canEdit) {

						JButton clearButton = new JButton("Clear");
						clearButton.setFocusable(false);
						clearButton.addActionListener(new ActionListener() {
							public void actionPerformed(ActionEvent theEvent) {
								doClear();
							}
						});
						buttonPanel.add(clearButton);

						JButton cancelButton = new JButton("Cancel");
						cancelButton.setFocusable(false);
						cancelButton.addActionListener(new ActionListener() {
							public void actionPerformed(ActionEvent theEvent) {
								doCancel();
							}
						});
						buttonPanel.add(cancelButton);
					}

					JButton okButton = new JButton("OK");
					okButton.setFocusable(false);
					okButton.addActionListener(new ActionListener() {
						public void actionPerformed(ActionEvent theEvent) {
							doOK();
						}
					});
					buttonPanel.add(okButton);

					JTabbedPane tabPane = new JTabbedPane();
					tabPane.addTab("Data", editorPanel);
					tabPane.addTab("Plot", plotPanel);

					Container cp = getContentPane();
					cp.setLayout(new BorderLayout());
					cp.add(tabPane, BorderLayout.CENTER);
					cp.add(buttonPanel, BorderLayout.SOUTH);

					pack();

					Dimension size = new Dimension(700, 400);
					setMinimumSize(size);
					setSize(size);

					setResizable(true);

					setLocationRelativeTo(outerThis);
				}

				private void doClear() {
					source.dtsSectors = "";
					AppController.hideWindow(this);
				}

				private void doCancel() {
					canceled = true;
					AppController.hideWindow(this);
				}

				private void doOK() {
					if (!sectors.areSectorsValid(errorReporter)) {
						return;
					}
					source.dtsSectors = sectors.encodeAsString();
					AppController.hideWindow(this);
				}

				public void setDidEdit() {
					plotPanel.setGeography(sectors);
				}

				public boolean windowShouldClose() {
					canceled = true;
					return true;
				}
			}

			SectorsDialog theDialog = new SectorsDialog();
			AppController.showWindow(theDialog);
			if (!canEdit || theDialog.canceled) {
				return;
			}

			if (source.dtsSectors.length() > 0) {
				editSectorsButton.setText("Edit Sectors");
				AppController.setComponentEnabled(distanceField, false);
			} else {
				editSectorsButton.setText("Add Sectors");
				AppController.setComponentEnabled(distanceField, true);
			}
		}


		//-------------------------------------------------------------------------------------------------------------
  		// Editing fields that are applied here are non-editable in the regular source editing panel, see SourcePanel.
		// On the reference facility source the call sign, city, state, and site number are non-editable (the site
		// number must always be 0, that identifies the reference facility).  On the individual transmitter sources the
		// call sign, channel, city, state, zone, status, and file number are non-editable, and site number must be >0.
		// The facility ID, service, and country are usually non-editable, except when creating a new source.  This may
		// be called while the panel is visible.  Also the channel and coordinates are non-editable for a permanent
		// scenario entry in TVIX, OET74, and TV6FM studies; see comments in setSource().

		private void updateFields() {

			blockActionsStart();

			canEditChan = canEdit;
			canEditCoords = canEdit;

			if (isPermanentInScenario && (null != source.study) &&
					((Study.STUDY_TYPE_TV_OET74 == source.study.study.studyType) ||
					(Study.STUDY_TYPE_TV6_FM == source.study.study.studyType))) {
				canEditChan = false;
				canEditCoords = false;
			}

			if (isNewSource) {
				facilityIDField.setVisible(true);
				facilityIDField.setText(String.valueOf(facilityID));
				facilityIDLabel.setVisible(false);
				facilityIDLabel.setText(" ");
			} else {
				facilityIDLabel.setVisible(true);
				facilityIDLabel.setText(String.valueOf(facilityID));
				facilityIDField.setVisible(false);
				facilityIDField.setText("");
			}

			if (source.hasRecordID()) {
				stationDataLabel.setText(source.getStationData());
				recordIDLabel.setText(source.getRecordID());
				recordIDPanel.setVisible(true);
			} else {
				stationDataLabel.setText(" ");
				recordIDLabel.setText(" ");
				recordIDPanel.setVisible(false);
			}

			// A DTS parent cannot possibly be a DRT so that flag is ignored here.

			if (isNewSource) {
				serviceMenu.setVisible(true);
				serviceMenu.setSelectedItem(service);
				serviceLabel.setVisible(false);
				serviceLabel.setText(" ");
			} else {
				serviceLabel.setVisible(true);
				serviceLabel.setText(service.name);
				serviceMenu.setSelectedIndex(0);
				serviceMenu.setVisible(false);
			}

			callSignField.setText(source.callSign);
			AppController.setComponentEnabled(callSignField, canEdit);

			channelField.setText(String.valueOf(source.channel));
			AppController.setComponentEnabled(channelField, canEditChan);
			String lbl = " ";
			if (null != channelNote) {
				lbl = channelNote;
			} else {
				if ((null != source.originalSourceKey) && (null != source.study)) {
					SourceEditDataTV origSource = (SourceEditDataTV)source.study.getSource(source.originalSourceKey);
					if (null != origSource) {
						lbl = "replicated from " + (origSource.service.serviceType.digital ? "D" : "N") +
							origSource.channel;
					}
				}
			}
			replicationLabel.setText(lbl);
			frequencyLabel.setText(SourceEditDataTV.getFrequency(source.channel, service));

			cityField.setText(source.city);
			AppController.setComponentEnabled(cityField, canEdit);

			stateField.setText(source.state);
			AppController.setComponentEnabled(stateField, canEdit);

			if (isNewSource) {
				countryMenu.setVisible(true);
				countryMenu.setSelectedItem(country);
				countryLabel.setVisible(false);
				countryLabel.setText(" ");
			} else {
				countryLabel.setVisible(true);
				countryLabel.setText(country.name);
				countryMenu.setSelectedIndex(0);
				countryMenu.setVisible(false);
			}

			zoneMenu.setSelectedItem(source.zone);
			AppController.setComponentEnabled(zoneMenu, canEdit);

			if (null != signalTypeMenu) {
				signalTypeMenu.setSelectedItem(source.signalType);
				AppController.setComponentEnabled(signalTypeMenu, canEdit);
			}

			statusItem = new KeyedRecord(ExtDbRecord.getStatusType(source.status), source.status);
			statusMenu.setSelectedItem(statusItem);
			AppController.setComponentEnabled(statusMenu, canEdit);

			fileNumberField.setText(source.fileNumber);
			AppController.setComponentEnabled(fileNumberField, canEdit);

			editPoint.setLatLon(source.location);
			latitudePanel.update();
			longitudePanel.update();
			latitudePanel.setEnabled(canEditCoords);
			longitudePanel.setEnabled(canEditCoords);

			if (0. == source.dtsMaximumDistance) {
				distanceField.setText(TABLE_DIST_LABEL);
			} else {
				distanceField.setText(AppCore.formatDistance(source.dtsMaximumDistance));
			}
			if (canEdit) {
				if (source.dtsSectors.length() > 0) {
					AppController.setComponentEnabled(distanceField, false);
					editSectorsButton.setText("Edit Sectors");
				} else {
					AppController.setComponentEnabled(distanceField, true);
					editSectorsButton.setText("Add Sectors");
				}
				editSectorsButton.setEnabled(true);
			} else {
				AppController.setComponentEnabled(distanceField, false);
				if (source.dtsSectors.length() > 0) {
					editSectorsButton.setText("View Sectors");
					editSectorsButton.setEnabled(true);
				} else {
					editSectorsButton.setText("Add Sectors");
					editSectorsButton.setEnabled(false);
				}
			}

			baselineCheckBox.setSelected(null != source.getAttribute(Source.ATTR_IS_BASELINE));
			AppController.setComponentEnabled(baselineCheckBox, canEdit);

			blockActionsEnd();
		}
	}
}
