//
//  OptionsPanel.java
//  TVStudy
//
//  Copyright (c) 2016-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui;

import gov.fcc.tvstudy.gui.run.*;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.editdata.*;

import java.util.*;
import java.sql.*;
import java.awt.*;
import java.awt.event.*;
import java.text.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;


//=====================================================================================================================
// Accessory panel class used to provide a variety of common UIs needed in contexts such as adding new records to
// scenarios, creating new scenarios, setting up build-and-run studies, and running studies.  Often used as an
// accessory panel in a RecordFind dialog.  The abstract superclass provides a core set of UIs that are individually
// activated by constructor argument.  That constructor is used only by concrete subclasses.  The UIs include a field
// to enter a study or scenario name, button and pop-up text dialog to enter a study or scenario description, option to
// make the selected record (e.g. in a RecordFind context) editable, option to replicate the selected record if it is
// TV, UI to select/edit a run output configuration, and UI for run settings including memory limit and a button and
// pop-up dialog for entering a comment.  The constructor creates components then calls createLayout() with this as
// argument, subclasses may override to substitute a different panel and call super, or lay out superclass components
// directly as desired.   A set of concrete inner subclasses is provided for most common uses, some of those are just
// constructor argument patterns, others provide additional UI e.g. for the build-and-run study types.

public abstract class OptionsPanel extends AppPanel {

	protected boolean showName;
	protected String nameLabel;
	protected JTextField nameField;
	protected JPanel namePanel;

	protected boolean showDescription;
	protected String descriptionLabel;
	protected JButton descriptionButton;
	protected JPanel descriptionPanel;

	protected boolean showEdit;
	protected JCheckBox editableCheckBox;
	protected JPanel editPanel;

	protected boolean showReplicate;
	protected boolean allowMultiChannelReplicate;
	protected JCheckBox replicateCheckBox;
	protected JTextField replicationChannelField;
	protected boolean isDigital;
	protected boolean isLPTV;
	protected boolean isClassA;
	protected int channel;
	protected java.util.Date sequenceDate;
	protected JPanel replicatePanel;

	protected boolean showOutput;
	protected boolean showOutputMenus;
	protected JComboBox<OutputConfig> fileOutputConfigMenu;
	protected JComboBox<OutputConfig> mapOutputConfigMenu;
	protected JButton editConfigButton;
	protected JLabel fileOutputConfigLabel;
	protected JLabel mapOutputConfigLabel;
	protected JPanel outputPanel;

	protected boolean showRun;
	protected JComboBox<String> memoryFractionMenu;
	protected JButton commentButton;
	protected JPanel runPanel;

	protected boolean enabled = true;

	// Defaults for UI may be set by subclass or other, applied in clearFields().

	public String defaultName;

	public String defaultDescription;

	public OutputConfig defaultFileOutputConfig;
	public OutputConfig defaultMapOutputConfig;

	// Inputs available in these properties, if enabled, once validateInput() returns true.

	public String name;

	public String description;

	public boolean isLocked;

	public boolean replicate;
	public int replicationChannel;
	public int[] replicationChannels;
	public boolean includeNonReplicatedStudy;

	public OutputConfig fileOutputConfig;
	public OutputConfig mapOutputConfig;

	public double memoryFraction;
	public String comment;


	//-----------------------------------------------------------------------------------------------------------------

	protected OptionsPanel(AppEditor theParent, boolean theShowName, String theNameLabel, boolean theShowDescrip,
			String theDescripLabel, boolean theShowEdit, boolean theShowRep, boolean theMultiRep, boolean theShowOut,
			boolean theShowOutMenus, boolean theShowRun) {

		super(theParent);

		showName = theShowName;
		nameLabel = theNameLabel;
		showDescription = theShowDescrip;
		descriptionLabel = theDescripLabel;
		showEdit = theShowEdit;
		showReplicate = theShowRep;
		allowMultiChannelReplicate = theMultiRep;
		showOutput = theShowOut;
		if (showOutput) {
			showOutputMenus = theShowOutMenus;
		}
		showRun = theShowRun;

		// Name field.  This can actually be any arbitrary text input since caller provides the label.

		if (showName) {

			nameField = new JTextField(20);
			AppController.fixKeyBindings(nameField);

			namePanel = new JPanel();
			namePanel.add(nameField);
			namePanel.setBorder(BorderFactory.createTitledBorder(nameLabel));
		}

		// Description pop-up, again could actually be anything since caller provides the label.

		if (showDescription) {

			descriptionButton = new JButton(descriptionLabel);

			descriptionButton.setFocusable(false);
			descriptionButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doEditDescription();
				}
			});

			descriptionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
			descriptionPanel.add(descriptionButton);

			description = "";
		}

		// Editable-record option.  If editable, replication is not allowed; the combination doesn't make sense here.
		// The scenario model supports it, but it wouldn't make sense to the user in context.  The editable request
		// would apply to the source before replication, but the replication source is all the user initially sees and
		// that is never editable.  So it would appear the editable request was ignored.  Later if the user reverts
		// the replicated record, the editable original would unexpectedly appear.  Having these options is just a
		// convenience anyway, the user can always do the steps manually, including replicating an editable record.

		if (showEdit) {

			editableCheckBox = new JCheckBox("Allow editing");
			editableCheckBox.setFocusable(false);
			editableCheckBox.setToolTipText(
				"<HTML>Allow the station record to be edited.  Changes apply only in the current scenario.<BR>" +
				"Use only if necessary, editable records can significantly increase study run times.</HTML>");
			AppController.setComponentEnabled(editableCheckBox, false);

			if (showReplicate) {
				editableCheckBox.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							if (editableCheckBox.isSelected()) {
								AppController.setComponentEnabled(replicateCheckBox, false);
								replicateCheckBox.setSelected(false);
								AppController.setComponentEnabled(replicationChannelField, false);
							} else {
								if (channel > 0) {
									AppController.setComponentEnabled(replicateCheckBox, true);
									AppController.setComponentEnabled(replicationChannelField,
										replicateCheckBox.isSelected());
								}
							}
							blockActionsEnd();
						}
					}
				});
			}

			editPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
			editPanel.add(editableCheckBox);
		}

		// Replication option.  Replication sources have to be locked.

		if (showReplicate) {

			replicateCheckBox = new JCheckBox("Replicate");
			replicateCheckBox.setFocusable(false);
			replicateCheckBox.setToolTipText(
				"<HTML>Study a TV station on a different channel using a derived<BR>" +
				"ERP and azimuth pattern replicating the original coverage.</HTML>");
			AppController.setComponentEnabled(replicateCheckBox, false);

			replicateCheckBox.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (replicateCheckBox.isSelected()) {
							if (showEdit) {
								AppController.setComponentEnabled(editableCheckBox, false);
								editableCheckBox.setSelected(false);
							}
							AppController.setComponentEnabled(replicationChannelField, true);
						} else {
							if (showEdit) {
								AppController.setComponentEnabled(editableCheckBox, true);
							}
							AppController.setComponentEnabled(replicationChannelField, false);
						}
						blockActionsEnd();
					}
				}
			});

			// Replication channel field, activates if option is selected.

			replicationChannelField = new JTextField(8);
			AppController.fixKeyBindings(replicationChannelField);
			AppController.setComponentEnabled(replicationChannelField, false);

			// Layout.

			JPanel chkBoxP = new JPanel(new FlowLayout(FlowLayout.LEFT));
			chkBoxP.add(replicateCheckBox);

			JPanel chanP = new JPanel();
			if (allowMultiChannelReplicate) {
				chanP.setBorder(BorderFactory.createTitledBorder("Rep. Channel(s)"));
			} else {
				chanP.setBorder(BorderFactory.createTitledBorder("Rep. Channel"));
			}
			chanP.add(replicationChannelField);

			replicatePanel = new JPanel();
			replicatePanel.add(chkBoxP);
			replicatePanel.add(chanP);
		}

		// Output configuration UI.  See OutputConfigDialog.  Menus will be populated in clearFields().

		if (showOutput) {

			outputPanel = new JPanel();

			if (showOutputMenus) {

				fileOutputConfigMenu = new JComboBox<OutputConfig>();
				fileOutputConfigMenu.setFocusable(false);
				OutputConfig proto = new OutputConfig(OutputConfig.CONFIG_TYPE_FILE, "");
				proto.name = "XyXyXyXyXyXyXyXyXy";
				fileOutputConfigMenu.setPrototypeDisplayValue(proto);

				fileOutputConfigMenu.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							fileOutputConfig = (OutputConfig)fileOutputConfigMenu.getSelectedItem();
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
							mapOutputConfig = (OutputConfig)mapOutputConfigMenu.getSelectedItem();
							blockActionsEnd();
						}
					}
				});

				outputPanel.setBorder(BorderFactory.createTitledBorder("Output Settings"));
				outputPanel.add(new JLabel("File:"));
				outputPanel.add(fileOutputConfigMenu);
				outputPanel.add(new JLabel("Map:"));
				outputPanel.add(mapOutputConfigMenu);

				editConfigButton = new JButton("Edit");

				outputPanel.add(editConfigButton);

			} else {

				editConfigButton = new JButton("Output Settings");

				fileOutputConfigLabel = new JLabel();
				mapOutputConfigLabel = new JLabel();

				JPanel butP = new JPanel();
				butP.add(editConfigButton);
				JPanel fileP = new JPanel();
				fileP.add(fileOutputConfigLabel);
				JPanel mapP = new JPanel();
				mapP.add(mapOutputConfigLabel);

				outputPanel.setLayout(new BoxLayout(outputPanel, BoxLayout.Y_AXIS));
				outputPanel.add(butP);
				outputPanel.add(fileP);
				outputPanel.add(mapP);
			}

			editConfigButton.setFocusable(false);
			editConfigButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doEditOutputConfigs();
				}
			});
		}

		// Memory fraction and comment for study run.  The comment is actually generically labeled "Comment" so it is
		// not necessarily a run comment, but currently that's the only application.  Note the maxEngineProcessCount
		// can be <=0 in which case the engine will not run at all (see AppCore.initialize()), but in that case other
		// code needs to pre-check and not even show this UI.  This will always show at least an "All" option.

		if (showRun) {

			memoryFractionMenu = new JComboBox<String>();
			memoryFractionMenu.setFocusable(false);
			memoryFractionMenu.addItem("All");
			for (int frac = 2; frac <= AppCore.maxEngineProcessCount; frac++) {
				memoryFractionMenu.addItem("1/" + String.valueOf(frac));
			}

			commentButton = new JButton("Comment");

			commentButton.setFocusable(false);
			commentButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doEditComment();
				}
			});

			JPanel memP = new JPanel();
			memP.setBorder(BorderFactory.createTitledBorder("Memory Use"));
			memP.add(memoryFractionMenu);

			JPanel comP = new JPanel(new FlowLayout(FlowLayout.LEFT));
			comP.add(commentButton);

			runPanel = new JPanel();
			runPanel.add(memP);
			runPanel.add(comP);

			comment = "";
		}

		// Default layout, just add everything to the default FlowLayout.

		createLayout(this);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This just adds the enabled UIs in sequence to the argument panel without interacting with the layout manager
	// directly.  That usually works, if it is too limited the subclass can override this to do something else.
	// 

	protected void createLayout(JPanel thePanel) {

		if (showName) {
			thePanel.add(namePanel);
		}

		if (showDescription) {
			thePanel.add(descriptionPanel);
		}

		if (showEdit) {
			thePanel.add(editPanel);
		}

		if (showReplicate) {
			thePanel.add(replicatePanel);
		}

		if (showOutput) {
			thePanel.add(outputPanel);
		}

		if (showRun) {
			thePanel.add(runPanel);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Show a pop-up text dialog to edit/input description text.

	private void doEditDescription() {

		TextInputDialog theDialog = new TextInputDialog(parent, descriptionLabel, descriptionLabel);
		theDialog.setInput(description);

		AppController.showWindow(theDialog);
		if (theDialog.canceled) {
			return;
		}

		description = theDialog.getInput();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Open an OutputConfigDialog to edit the output file configurations.

	private void doEditOutputConfigs() {

		if (!showOutput) {
			return;
		}

		ArrayList<OutputConfig> theList = new ArrayList<OutputConfig>();
		theList.add(fileOutputConfig);
		theList.add(mapOutputConfig);

		OutputConfigDialog theDialog = new OutputConfigDialog(this, theList, false);
		AppController.showWindow(theDialog);

		for (OutputConfig theConfig : theDialog.getConfigs()) {
			switch (theConfig.type) {
				case OutputConfig.CONFIG_TYPE_FILE: {
					fileOutputConfig = theConfig;
					break;
				}
				case OutputConfig.CONFIG_TYPE_MAP: {
					mapOutputConfig = theConfig;
					break;
				}
			}
		}

		updateOutputConfigUI();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Re-populate the output config menus and select current objects, which may or may not be in the saved list.  Or
	// if menus are not showing, just put current config names in the labels.

	private void updateOutputConfigUI() {

		if (showOutputMenus) {

			blockActionsStart();

			ArrayList<OutputConfig> theList = OutputConfig.getConfigs(getDbID(), OutputConfig.CONFIG_TYPE_FILE);
			fileOutputConfigMenu.removeAllItems();
			for (OutputConfig config : theList) {
				fileOutputConfigMenu.addItem(config);
			}

			if (!theList.contains(fileOutputConfig)) {
				fileOutputConfigMenu.addItem(fileOutputConfig);
			}
			fileOutputConfigMenu.setSelectedItem(fileOutputConfig);

			theList = OutputConfig.getConfigs(getDbID(), OutputConfig.CONFIG_TYPE_MAP);
			mapOutputConfigMenu.removeAllItems();
			for (OutputConfig config : theList) {
				mapOutputConfigMenu.addItem(config);
			}

			if (!theList.contains(mapOutputConfig)) {
				mapOutputConfigMenu.addItem(mapOutputConfig);
			}
			mapOutputConfigMenu.setSelectedItem(mapOutputConfig);

			blockActionsEnd();

		} else {

			fileOutputConfigLabel.setText("File: " + fileOutputConfig.name);
			mapOutputConfigLabel.setText("Map: " + mapOutputConfig.name);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Pop-up for comment text.

	private void doEditComment() {

		TextInputDialog theDialog = new TextInputDialog(parent, "Comment", "Comment");
		theDialog.setInput(comment);

		AppController.showWindow(theDialog);
		if (theDialog.canceled) {
			return;
		}

		comment = theDialog.getInput();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void setEnabled(boolean flag) {

		if (showName) {
			AppController.setComponentEnabled(nameField, flag);
		}

		if (showDescription) {
			descriptionButton.setEnabled(flag);
		}

		if (showEdit) {
			AppController.setComponentEnabled(editableCheckBox, flag);
		}

		if (showReplicate) {
			boolean eRep = flag;
			if (0 == channel) {
				eRep = false;
			}
			AppController.setComponentEnabled(replicateCheckBox, eRep);
			if (replicateCheckBox.isSelected()) {
				AppController.setComponentEnabled(replicationChannelField, eRep);
			} else {
				AppController.setComponentEnabled(replicationChannelField, false);
			}
		}

		if (showOutput) {
			if (showOutputMenus) {
				AppController.setComponentEnabled(fileOutputConfigMenu, flag);
				AppController.setComponentEnabled(mapOutputConfigMenu, flag);
			}
			editConfigButton.setEnabled(flag);
		}

		if (showRun) {
			AppController.setComponentEnabled(memoryFractionMenu, flag);
			commentButton.setEnabled(flag);
		}

		enabled = flag;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void clearFields() {

		blockActionsStart();

		if (showName) {
			if (null == defaultName) {
				defaultName = "";
			}
			nameField.setText(defaultName);
		}

		if (showDescription) {
			if (null == defaultDescription) {
				defaultDescription = "";
			}
			description = defaultDescription;
		}

		if (showEdit) {
			editableCheckBox.setSelected(false);
		}

		if (showReplicate) {
			replicateCheckBox.setSelected(false);
			replicationChannelField.setText("");
			AppController.setComponentEnabled(replicationChannelField, false);
		}

		if (showOutput) {
			if ((null == defaultFileOutputConfig) || defaultFileOutputConfig.isNull()) {
				defaultFileOutputConfig = OutputConfig.getLastUsed(getDbID(), OutputConfig.CONFIG_TYPE_FILE);
			}
			fileOutputConfig = defaultFileOutputConfig;
			if ((null == defaultMapOutputConfig) || defaultMapOutputConfig.isNull()) {
				defaultMapOutputConfig = OutputConfig.getLastUsed(getDbID(), OutputConfig.CONFIG_TYPE_MAP);
			}
			mapOutputConfig = defaultMapOutputConfig;
			updateOutputConfigUI();
		}

		if (showRun) {
			String str = AppCore.getPreference(AppCore.PREF_DEFAULT_ENGINE_MEMORY_LIMIT);
			if (null != str) {
				int lim = 0;
				try {
					lim = Integer.parseInt(str);
				} catch (NumberFormatException ne) {
				}
				if (lim > AppCore.maxEngineProcessCount) {
					lim = AppCore.maxEngineProcessCount;
				}
				if (lim < 0) {
					lim = 0;
				}
				memoryFractionMenu.setSelectedIndex(lim);
			} else {
				memoryFractionMenu.setSelectedIndex(0);
			}
			comment = "";
		}

		blockActionsEnd();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// An unlocked SourceEditData must be a new record just created by either doCreateNew() or doDuplicate().  New
	// records are implicitly editable and cannot be replicated.  Non-new records of any type can be made editable or
	// replicated, but as discussed earlier both options can't be selected together.

	public void selectionChanged(Object newSelection) {

		if (null == newSelection) {
			isDigital = false;
			isLPTV = false;
			isClassA = false;
			channel = 0;
			sequenceDate = null;
			if (showReplicate) {
				replicateCheckBox.setSelected(false);
				AppController.setComponentEnabled(replicateCheckBox, false);
				replicationChannelField.setText("");
				AppController.setComponentEnabled(replicationChannelField, false);
			}
			return;
		}

		if (newSelection instanceof SourceEditData) {

			SourceEditData theSource = (SourceEditData)newSelection;
			isDigital = theSource.service.isDigital();
			isLPTV = theSource.service.isLPTV();
			isClassA = theSource.service.isClassA();
			if (Source.RECORD_TYPE_TV == theSource.recordType) {
				channel = ((SourceEditDataTV)theSource).channel;
			} else {
				channel = 0;
			}
			sequenceDate = AppCore.parseDate(theSource.getAttribute(Source.ATTR_SEQUENCE_DATE));
			if (null == sequenceDate) {
				sequenceDate = new java.util.Date();
			}

			if (!theSource.isLocked) {
				channel = 0;
				if (showEdit) {
					editableCheckBox.setSelected(true);
				}
				if (showReplicate) {
					replicateCheckBox.setSelected(false);
					AppController.setComponentEnabled(replicateCheckBox, false);
					replicationChannelField.setText("");
					AppController.setComponentEnabled(replicationChannelField, false);
				}
				return;
			}

		} else {

			if (newSelection instanceof ExtDbRecord) {

				ExtDbRecord theRecord = (ExtDbRecord)newSelection;
				isDigital = theRecord.service.isDigital();
				isLPTV = theRecord.service.isLPTV();
				isClassA = theRecord.service.isClassA();
				if (Source.RECORD_TYPE_TV == theRecord.recordType) {
					ExtDbRecordTV theRecordTV = (ExtDbRecordTV)theRecord;
					channel = theRecordTV.channel;
					if ((theRecordTV.replicateToChannel > 0) && showReplicate && !replicateCheckBox.isSelected()) {
						replicateCheckBox.setSelected(true);
						replicationChannelField.setText(String.valueOf(theRecordTV.replicateToChannel));
					}
				} else {
					channel = 0;
				}
				sequenceDate = theRecord.sequenceDate;

			} else {

				isDigital = false;
				isLPTV = false;
				isClassA = false;
				channel = 0;
				sequenceDate = null;
			}
		}

		if (showReplicate) {
			if (0 == channel) {
				replicateCheckBox.setSelected(false);
				AppController.setComponentEnabled(replicateCheckBox, false);
				replicationChannelField.setText("");
				AppController.setComponentEnabled(replicationChannelField, false);
			} else {
				AppController.setComponentEnabled(replicateCheckBox, true);
				AppController.setComponentEnabled(replicationChannelField, replicateCheckBox.isSelected());
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Description 

	public boolean validateInput() {

		name = "";

		if (showName) {

			String theName = nameField.getText().trim();
			if (0 == theName.length()) {
				errorReporter.reportWarning("Please provide a name.");
				return false;
			}

			name = theName;
		}

		isLocked = true;

		if (showEdit) {
			isLocked = !editableCheckBox.isSelected();
		}

		// Optionally this can allow multiple channels to be entered for replication, in that case multiple studies
		// will be built all with the same settings except for the replication channel.

		replicate = false;
		replicationChannel = 0;
		replicationChannels = null;
		includeNonReplicatedStudy = false;

		if (showReplicate) {

			replicate = replicateCheckBox.isSelected();

			if (replicate) {

				boolean error = false;
				TreeSet<Integer> theChannels = new TreeSet<Integer>();

				String inputString = replicationChannelField.getText().trim();
				if (inputString.length() > 0) {

					String[] parts = inputString.split(","), chans = null;

					int chan = 0, chan1 = 0, chan2 = 0;

					for (String part : parts) {

						if (part.contains("-")) {
							chans = part.split("-");
							if (2 == chans.length) {
								try {
									chan1 = Integer.parseInt(chans[0].trim());
								} catch (NumberFormatException ne) {
									error = true;
								}
								if (error) {
									break;
								}
								if ((chan1 < SourceTV.CHANNEL_MIN) || (chan1 > SourceTV.CHANNEL_MAX)) {
									error = true;
									break;
								}
								try {
									chan2 = Integer.parseInt(chans[1].trim());
								} catch (NumberFormatException ne) {
									error = true;
								}
								if (error) {
									break;
								}
								if ((chan2 < SourceTV.CHANNEL_MIN) || (chan2 > SourceTV.CHANNEL_MAX)) {
									error = true;
									break;
								}
								if (chan1 <= chan2) {
									for (chan = chan1; chan <= chan2; chan++) {
										theChannels.add(Integer.valueOf(chan));
									}
								} else {
									for (chan = chan2; chan <= chan1; chan++) {
										theChannels.add(Integer.valueOf(chan));
									}
								}
							} else {
								error = true;
								break;
							}

						} else {

							try {
								chan = Integer.parseInt(part.trim());
							} catch (NumberFormatException ne) {
								error = true;
							}
							if (error) {
								break;
							}
							if ((chan < SourceTV.CHANNEL_MIN) || (chan > SourceTV.CHANNEL_MAX)) {
								error = true;
								break;
							}
							theChannels.add(Integer.valueOf(chan));
						}
					}

				} else {
					error = true;
				}

				// Usually entering the same channel for replication of a digital record is an error (it is not an
				// error for analog, that sets up on-channel replication to digital).  However as a convenience, if
				// multiple replication channels are entered for a digital record and the same channel appears in the
				// list it is not reported as an error, instead set a separate flag so a normal non-replicated study
				// is also configured and included in the study build list.

				if (allowMultiChannelReplicate) {

					if (error || theChannels.isEmpty()) {
						errorReporter.reportWarning("Please enter valid replication channels.\n" +
							"Use commas and dashes for multiple channels and ranges.");
						return false;
					}

					if (isDigital && theChannels.remove(Integer.valueOf(channel))) {
						if (theChannels.isEmpty()) {
							errorReporter.reportWarning(
								"The replication channel must be different than the original channel.");
							return false;
						} else {
							includeNonReplicatedStudy = true;
						}
					}

					replicationChannels = new int[theChannels.size()];
					int i = 0;
					for (Integer theChan : theChannels) {
						replicationChannels[i++] = theChan.intValue();
					}

					replicationChannel = replicationChannels[0];

				} else {

					if (error || theChannels.isEmpty()) {
						errorReporter.reportWarning("Please enter a valid replication channel.");
						return false;
					}

					if (theChannels.size() > 1) {
						errorReporter.reportWarning("Please enter a single replication channel.");
						return false;
					}

					replicationChannel = theChannels.first().intValue();
					if (isDigital && (replicationChannel == channel)) {
						errorReporter.reportWarning(
							"The replication channel must be different than the original channel.");
						replicationChannel = 0;
						return false;
					}
				}
			}
		}

		if (showOutput) {

			if ((null == fileOutputConfig) || fileOutputConfig.isNull() || !fileOutputConfig.isValid()) {
				errorReporter.reportWarning("Please provide valid output file settings.");
				return false;
			}

			if ((null == mapOutputConfig) || mapOutputConfig.isNull() || !mapOutputConfig.isValid()) {
				errorReporter.reportWarning("Please provide valid map output settings.");
				return false;
			}

			fileOutputConfig.saveAsLastUsed(getDbID());
			mapOutputConfig.saveAsLastUsed(getDbID());

		} else {

			fileOutputConfig = null;
			mapOutputConfig = null;
		}

		memoryFraction = 1.;

		if (showRun) {

			memoryFraction = 1. / (double)(memoryFractionMenu.getSelectedIndex() + 1);
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// For use by subclasses that use a StudyBuild subclass to manage the study creation process.  Some may cause
	// multiple studies to be created.

	public ArrayList<StudyBuild> getStudyBuilds() {

		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowWillOpen() {

		clearFields();
	}


	//=================================================================================================================
	// Subclass for a basic add-new-record case, just the superclass UI for record edit and replicate.  The superclass
	// doesn't really need to be abstract, it's fully functional so trivial subclasses like this can exist to protect
	// the world from having to use that superclass contructor with the long cryptic argument list.

	public static class AddRecord extends OptionsPanel {


		//-------------------------------------------------------------------------------------------------------------

		public AddRecord(AppEditor theParent) {

			super(theParent, false, null, false, null, true, true, false, false, false, false);

			setBorder(BorderFactory.createTitledBorder("Record Options"));
		}
	}
		

	//=================================================================================================================
	// Subclass for a new-scenario case, show scenario name and replicate options.

	public static class NewScenario extends OptionsPanel {


		//-------------------------------------------------------------------------------------------------------------

		public NewScenario(AppEditor theParent) {

			super(theParent, true, "Scenario Name", false, null, false, true, false, false, false, false);

			setBorder(BorderFactory.createTitledBorder("Scenario Settings"));
		}
	}
		

	//=================================================================================================================
	// Subclass used when creating a new interference check study.

	public static class IxCheckStudy extends OptionsPanel {

		private KeyedRecordMenu templateMenu;

		private KeyedRecordMenu extDbMenu;

		private JComboBox<String> cellSizeMenu;
		private JTextField profilePtIncField;

		private JButton setBeforeButton;
		private JButton clearBeforeButton;
		private JLabel beforeLabel;
		private JCheckBox useDefaultBeforeCheckBox;
		private JPanel beforePanel;

		private JCheckBox protectPreBaselineCheckBox;
		private JCheckBox protectBaselineFromLPTVCheckBox;
		private JCheckBox protectLPTVFromClassACheckBox;

		private JCheckBox includeForeignCheckBox;
		private JTextArea includeUserRecordsTextArea;
		private JButton includeUserRecordButton;
		private JCheckBox cpExcludesBaselineCheckBox;
		private JCheckBox excludeAppsCheckBox;
		private JCheckBox excludePendingCheckBox;
		private JCheckBox excludePostTransitionCheckBox;
		private JTextArea excludeCommandsTextArea;
		private JButton excludeARNButton;

		private JPanel moreOptionsMainPanel;
		private JPanel moreOptionsButtonPanel;
		private JDialog moreOptionsDialog;
		private JButton moreOptionsButton;

		private DateSelectionPanel filingCutoffDatePanel;

		private boolean isPostWindow;

		// Values are set directly in a StudyBuildIxCheck object provided to the constructor.

		private StudyBuildIxCheck studyBuild;

		// This may cause multiple studies to be built on multiple replication channels so the return is an array.

		private ArrayList<StudyBuild> studyBuilds;


		//-------------------------------------------------------------------------------------------------------------
		// Show the name, description, replication, output, and run options from the superclass.  Except, multiple
		// replication, output, and run are not shown if this is not a full study build; see StudyBuildIxCheck.

		public IxCheckStudy(AppEditor theParent, StudyBuildIxCheck theStudy) {

			super(theParent, true, "Study Name", true, "Study Description", false, true, theStudy.buildFullStudy,
				theStudy.buildFullStudy, false, theStudy.buildFullStudy);

			if (theStudy.buildFullStudy) {
				setBorder(BorderFactory.createTitledBorder("Study Build Settings"));
			} else {
				setBorder(BorderFactory.createTitledBorder("Study Settings"));
			}

			studyBuild = theStudy;

			defaultName = theStudy.studyName;
			defaultDescription = theStudy.studyDescription;
			defaultFileOutputConfig = theStudy.fileOutputConfig;
			defaultMapOutputConfig = theStudy.mapOutputConfig;

			templateMenu = new KeyedRecordMenu();
			templateMenu.setPrototypeDisplayValue(new KeyedRecord(0, "XyXyXyXyXyXyXyXyXy"));

			JPanel templatePanel = new JPanel();
			templatePanel.setBorder(BorderFactory.createTitledBorder("Template"));
			templatePanel.add(templateMenu);

			extDbMenu = new KeyedRecordMenu();
			extDbMenu.setPrototypeDisplayValue(new KeyedRecord(0, "XyXyXyXyXyXyXyXyXy"));

			JPanel extDbPanel = new JPanel();
			extDbPanel.setBorder(BorderFactory.createTitledBorder("Station Data"));
			extDbPanel.add(extDbMenu);

			cellSizeMenu = new JComboBox<String>();
			cellSizeMenu.setFocusable(false);
			for (double siz : StudyBuildIxCheck.CELL_SIZES) {
				cellSizeMenu.addItem(AppCore.formatDecimal(siz, 1));
			}

			JPanel cellSizePanel = new JPanel();
			cellSizePanel.setBorder(BorderFactory.createTitledBorder("Cell size, km"));
			cellSizePanel.add(cellSizeMenu);

			profilePtIncField = new JTextField(8);
			AppController.fixKeyBindings(profilePtIncField);
			profilePtIncField.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					String str = profilePtIncField.getText().trim();
					if (str.length() > 0) {
						try {
							double d = Double.parseDouble(str);
							if (d > 1.) {
								profilePtIncField.setText(AppCore.formatDecimal((1. / d), 3));
								profilePtIncField.selectAll();
							}
						} catch (NumberFormatException ne) {
						}
					}
				}
			});
			profilePtIncField.addFocusListener(new FocusAdapter() {
				public void focusLost(FocusEvent theEvent) {
					if (!theEvent.isTemporary()) {
						profilePtIncField.postActionEvent();
					}
				}
			});

			JPanel profilePtIncPanel = new JPanel();
			profilePtIncPanel.setBorder(BorderFactory.createTitledBorder("Profile inc., km"));
			profilePtIncPanel.add(profilePtIncField);

			// Options to choose the "before" record for the study.  Location in layout will vary depending on whether
			// this is a full build or not, see below.

			setBeforeButton = new JButton("Set");
			setBeforeButton.setFocusable(false);
			setBeforeButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					RecordFindDialog theDialog = new RecordFindDialog(parent, "Choose proposal \"before\" record",
						Source.RECORD_TYPE_TV);
					AppController.showWindow(theDialog);
					if (!theDialog.canceled) {
						Record theRecord = theDialog.getSelectedRecord();
						if (theRecord instanceof SourceEditDataTV) {
							studyBuild.didSetBefore = true;
							studyBuild.beforeSource = (SourceEditDataTV)theRecord;
							beforeLabel.setText(studyBuild.beforeSource.toString());
							studyBuild.beforeRecord = null;
						} else {
							if (theRecord instanceof ExtDbRecordTV) {
								studyBuild.didSetBefore = true;
								studyBuild.beforeSource = null;
								studyBuild.beforeRecord = (ExtDbRecordTV)theRecord;
								beforeLabel.setText(studyBuild.beforeRecord.toString());
							} else {
								errorReporter.reportError("That record type is not allowed here.");
							}
						}
					}
				}
			});

			clearBeforeButton = new JButton("Clear");
			clearBeforeButton.setFocusable(false);
			clearBeforeButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					studyBuild.didSetBefore = true;
					studyBuild.beforeSource = null;
					studyBuild.beforeRecord = null;
					beforeLabel.setText("(none)");
				}
			});

			// If this is a full study build, show all UI; the "before" record UI shows option to use default record
			// found during the build, and that UI goes in the secondary dialog with other less-used options.  If this
			// is not a full build, various build options and that secondary dialog will not be shown.  This is just
			// going to create a "blank slate" new study with only a proposal record and scenario, to be opened in an
			// editor.  In that case the "before" record UI is in the main panel and there is no default, the record
			// must be set manually or no record will be used.

			if (theStudy.buildFullStudy) {

				useDefaultBeforeCheckBox = new JCheckBox("Default");
				useDefaultBeforeCheckBox.setFocusable(false);
				useDefaultBeforeCheckBox.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (useDefaultBeforeCheckBox.isSelected()) {
							studyBuild.didSetBefore = false;
							setBeforeButton.setEnabled(false);
							clearBeforeButton.setEnabled(false);
							beforeLabel.setText("(default)");
						} else {
							studyBuild.didSetBefore = true;
							setBeforeButton.setEnabled(true);
							clearBeforeButton.setEnabled(true);
							if (null != studyBuild.beforeSource) {
								beforeLabel.setText(studyBuild.beforeSource.toString());
							} else {
								if (null != studyBuild.beforeRecord) {
									beforeLabel.setText(studyBuild.beforeRecord.toString());
								} else {
									beforeLabel.setText("(none)");
								}
							}
						}
					}
				});

				beforeLabel = new JLabel("(default)");

				// Build options.

				protectPreBaselineCheckBox = new JCheckBox("Protect records not on baseline channel");
				protectPreBaselineCheckBox.setFocusable(false);
				protectBaselineFromLPTVCheckBox = new JCheckBox("Protect baseline records from LPTV");
				protectBaselineFromLPTVCheckBox.setFocusable(false);
				protectLPTVFromClassACheckBox = new JCheckBox("Protect LPTV records from Class A");
				protectLPTVFromClassACheckBox.setFocusable(false);

				// Options for including and excluding records during searches appear in a separate dialog.

				includeForeignCheckBox = new JCheckBox("Include non-U.S. records");
				includeForeignCheckBox.setFocusable(false);

				includeUserRecordsTextArea = new JTextArea(4, 16);
				AppController.fixKeyBindings(includeUserRecordsTextArea);
				includeUserRecordsTextArea.setLineWrap(true);
				includeUserRecordsTextArea.setWrapStyleWord(true);

				includeUserRecordButton = new JButton("Add");
				includeUserRecordButton.setFocusable(false);
				includeUserRecordButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						RecordFindDialog theDialog = new RecordFindDialog(parent, "Choose user record to include",
							-Source.RECORD_TYPE_TV);
						AppController.showWindow(theDialog);
						if (!theDialog.canceled) {
							String theID = theDialog.getSelectedRecord().getRecordID();
							if ((null != theID) && (theID.length() > 0)) {
								includeUserRecordsTextArea.append(theID + "\n");
							}
						}
					}
				});

				JPanel incUserButP = new JPanel(new FlowLayout(FlowLayout.LEFT));
				incUserButP.add(includeUserRecordButton);

				JPanel incUserPanel = new JPanel(new BorderLayout());
				incUserPanel.setBorder(BorderFactory.createTitledBorder("User records to include"));
				incUserPanel.add(AppController.createScrollPane(includeUserRecordsTextArea), BorderLayout.CENTER);
				incUserPanel.add(incUserButP, BorderLayout.SOUTH);

				cpExcludesBaselineCheckBox = new JCheckBox("CP excludes station's baseline");
				cpExcludesBaselineCheckBox.setFocusable(false);

				excludeAppsCheckBox = new JCheckBox("Exclude all APP records");
				excludeAppsCheckBox.setFocusable(false);

				excludePendingCheckBox = new JCheckBox("Exclude all pending records");
				excludePendingCheckBox.setFocusable(false);

				// Checking the excludePostTransition option will automatically check the protectPreBaseline option too
				// because that is almost always what the user will want, however this is just advisory and one-way,
				// the user can still un-check protectPreBaseline and that does not affect the exclude option.

				excludePostTransitionCheckBox = new JCheckBox("Exclude all post-transition CP, APP, and BL");
				excludePostTransitionCheckBox.setFocusable(false);
				excludePostTransitionCheckBox.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (excludePostTransitionCheckBox.isSelected()) {
							protectPreBaselineCheckBox.setSelected(true);
						}
					}
				});

				excludeCommandsTextArea = new JTextArea(4, 16);
				AppController.fixKeyBindings(excludeCommandsTextArea);
				excludeCommandsTextArea.setLineWrap(true);
				excludeCommandsTextArea.setWrapStyleWord(true);

				excludeARNButton = new JButton("Add");
				excludeARNButton.setFocusable(false);
				excludeARNButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						RecordFindDialog theDialog = new RecordFindDialog(parent, "Choose record to exclude",
							Source.RECORD_TYPE_TV);
						AppController.showWindow(theDialog);
						if (!theDialog.canceled) {
							String theARN = theDialog.getSelectedRecord().getARN();
							if ((null != theARN) && (theARN.length() > 0)) {
								excludeCommandsTextArea.append(theARN + "\n");
							}
						}
					}
				});

				JPanel excARNButP = new JPanel(new FlowLayout(FlowLayout.LEFT));
				excARNButP.add(excludeARNButton);

				JPanel excARNsPanel = new JPanel(new BorderLayout());
				excARNsPanel.setBorder(BorderFactory.createTitledBorder("Records to exclude (ARNs)"));
				excARNsPanel.add(AppController.createScrollPane(excludeCommandsTextArea), BorderLayout.CENTER);
				excARNsPanel.add(excARNButP, BorderLayout.SOUTH);

				filingCutoffDatePanel = new DateSelectionPanel(this, "Cutoff Date", false);
				filingCutoffDatePanel.setFutureAllowed(true);

				Box moreOptsBox = Box.createVerticalBox();
				moreOptsBox.add(cpExcludesBaselineCheckBox);
				moreOptsBox.add(excludeAppsCheckBox);
				moreOptsBox.add(excludePendingCheckBox);
				moreOptsBox.add(excludePostTransitionCheckBox);

				JPanel moreOptsP = new JPanel(new FlowLayout(FlowLayout.LEFT));
				moreOptsP.add(moreOptsBox);

				moreOptionsMainPanel = new JPanel();
				moreOptionsMainPanel.setLayout(new BoxLayout(moreOptionsMainPanel, BoxLayout.Y_AXIS));
				moreOptionsMainPanel.add(moreOptsP);
				moreOptionsMainPanel.add(excARNsPanel);
				moreOptionsMainPanel.add(incUserPanel);
				moreOptionsMainPanel.add(filingCutoffDatePanel);

				JButton optsOKBut = new JButton("OK");
				optsOKBut.setFocusable(false);
				optsOKBut.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if ((null != moreOptionsDialog) && moreOptionsDialog.isVisible()) {
							AppController.hideWindow(moreOptionsDialog);
						}
					}
				});

				moreOptionsButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
				moreOptionsButtonPanel.add(optsOKBut);

				moreOptionsButton = new JButton("More");
				moreOptionsButton.setFocusable(false);
				moreOptionsButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (null == moreOptionsDialog) {
							moreOptionsDialog = new JDialog(getWindow(), "Additional Options",
								Dialog.ModalityType.APPLICATION_MODAL);
							moreOptionsDialog.add(moreOptionsMainPanel, BorderLayout.CENTER);
							moreOptionsDialog.add(moreOptionsButtonPanel, BorderLayout.SOUTH);
							moreOptionsDialog.pack();
							moreOptionsDialog.setMinimumSize(moreOptionsDialog.getSize());
							moreOptionsDialog.setLocationRelativeTo(moreOptionsButton);
						}
						if (!moreOptionsDialog.isVisible()) {
							AppController.showWindow(moreOptionsDialog);
						}
					}
				});

			} else {

				beforeLabel = new JLabel("(none)");
			}

			// Layout.

			Box col1Box = Box.createVerticalBox();
			col1Box.add(replicatePanel);
			col1Box.add(templatePanel);

			add(col1Box);

			Box col2Box = Box.createVerticalBox();
			col2Box.add(extDbPanel);
			col2Box.add(namePanel);
			col2Box.add(descriptionPanel);

			add(col2Box);

			Box col3Box = Box.createVerticalBox();
			col3Box.add(cellSizePanel);
			col3Box.add(profilePtIncPanel);

			add(col3Box);

			JPanel befTopP = new JPanel(new FlowLayout(FlowLayout.LEFT));
			befTopP.add(beforeLabel);

			JPanel befBotP = new JPanel(new FlowLayout(FlowLayout.LEFT));
			befBotP.add(setBeforeButton);
			befBotP.add(clearBeforeButton);

			beforePanel = new JPanel();
			beforePanel.setBorder(BorderFactory.createTitledBorder("Proposal \"before\" case"));
			beforePanel.setLayout(new BoxLayout(beforePanel, BoxLayout.Y_AXIS));
			beforePanel.add(befTopP);
			beforePanel.add(befBotP);

			if (theStudy.buildFullStudy) {

				moreOptionsMainPanel.add(beforePanel);

				runPanel.remove(commentButton);
				descriptionPanel.add(commentButton);

				befBotP.add(useDefaultBeforeCheckBox);

				Box optsBox = Box.createVerticalBox();
				optsBox.add(protectPreBaselineCheckBox);
				optsBox.add(protectBaselineFromLPTVCheckBox);
				optsBox.add(protectLPTVFromClassACheckBox);
				optsBox.add(includeForeignCheckBox);

				JPanel topRowP = new JPanel();
				topRowP.add(optsBox);
				topRowP.add(moreOptionsButton);

				JPanel botRowP = new JPanel();
				botRowP.add(outputPanel);
				botRowP.add(runPanel);

				Box col4Box = Box.createVerticalBox();
				col4Box.add(topRowP);
				col4Box.add(botRowP);

				add(col4Box);

			} else {

				add(beforePanel);
			}
		}


		//-------------------------------------------------------------------------------------------------------------
		// The layout is done directly above.

		protected void createLayout(JPanel thePanel) {

			return;
		}


		//-------------------------------------------------------------------------------------------------------------

		public void setEnabled(boolean flag) {

			super.setEnabled(flag);

			AppController.setComponentEnabled(templateMenu, flag);

			AppController.setComponentEnabled(extDbMenu, flag);

			AppController.setComponentEnabled(cellSizeMenu, flag);
			AppController.setComponentEnabled(profilePtIncField, flag);

			if (studyBuild.buildFullStudy) {

				AppController.setComponentEnabled(useDefaultBeforeCheckBox, flag);
				if (useDefaultBeforeCheckBox.isSelected()) {
					setBeforeButton.setEnabled(false);
					clearBeforeButton.setEnabled(false);
				} else {
					setBeforeButton.setEnabled(flag);
					clearBeforeButton.setEnabled(flag);
				}

				AppController.setComponentEnabled(protectPreBaselineCheckBox,
					(flag && (null != studyBuild.baselineDate)));
				AppController.setComponentEnabled(protectBaselineFromLPTVCheckBox, (flag && isLPTV));
				AppController.setComponentEnabled(protectLPTVFromClassACheckBox, (flag && isClassA && !isPostWindow));

				AppController.setComponentEnabled(includeForeignCheckBox, flag);
				AppController.setComponentEnabled(includeUserRecordsTextArea, flag);
				includeUserRecordButton.setEnabled(flag);

				AppController.setComponentEnabled(cpExcludesBaselineCheckBox, flag);
				AppController.setComponentEnabled(excludeAppsCheckBox, flag);
				AppController.setComponentEnabled(excludePendingCheckBox, flag);
				AppController.setComponentEnabled(excludePostTransitionCheckBox, flag);
				AppController.setComponentEnabled(excludeCommandsTextArea, flag);
				excludeARNButton.setEnabled(flag);

				moreOptionsButton.setEnabled(flag);

				filingCutoffDatePanel.setEnabled(flag);

			} else {

				setBeforeButton.setEnabled(flag);
				clearBeforeButton.setEnabled(flag);
			}
		}


		//-------------------------------------------------------------------------------------------------------------
		// Populate menus when the containing window is shown.

		public void windowWillOpen() {

			templateMenu.removeAllItems();
			extDbMenu.removeAllItems();

			SwingUtilities.invokeLater(new Runnable() {
				public void run() {

					blockActionsStart();

					errorReporter.setTitle("Load Template List");

					ArrayList<KeyedRecord> list = Template.getTemplateInfoList(getDbID(), errorReporter);
					if (null != list) {
						Template.Info theInfo;
						for (KeyedRecord theItem : list) {
							theInfo = (Template.Info)theItem;
							if (theInfo.isLocked && !theInfo.isLockedInStudy) {
								templateMenu.addItem(theItem);
							}
						}
					}

					errorReporter.setTitle("Load Station Data List");

					list = ExtDb.getExtDbList(getDbID(), ExtDb.DB_TYPE_LMS, StudyBuildIxCheck.MIN_LMS_VERSION,
						errorReporter);
					if (null != list) {
						if (list.isEmpty()) {
							errorReporter.reportError(
								"No compatible LMS TV station data found, use Station Data Manager to add data.");
						} else {
							extDbMenu.addAllItems(list);
						}
					}

					clearFields();

					blockActionsEnd();
				}
			});
		}


		//-------------------------------------------------------------------------------------------------------------
		// The IxCheckStudy object provided at construction may provide defaults for some or all fields.

		public void clearFields() {

			super.clearFields();

			blockActionsStart();

			if ((studyBuild.templateKey > 0) && templateMenu.containsKey(studyBuild.templateKey)) {
				templateMenu.setSelectedKey(studyBuild.templateKey);
			} else {
				templateMenu.setSelectedIndex(0);
			}

			applyDefaults();

			if (null != studyBuild.extDb) {
				int theKey = studyBuild.extDb.key.intValue();
				if (extDbMenu.containsKey(theKey)) {
					extDbMenu.setSelectedKey(theKey);
				} else {
					extDbMenu.setSelectedIndex(0);
				}
			} else {
				extDbMenu.setSelectedIndex(0);
			}

			studyBuild.beforeSource = null;
			studyBuild.beforeRecord = null;
			setBeforeButton.setEnabled(false);
			clearBeforeButton.setEnabled(false);

			if (studyBuild.buildFullStudy) {

				studyBuild.didSetBefore = false;
				beforeLabel.setText("(default)");
				useDefaultBeforeCheckBox.setSelected(true);

				protectPreBaselineCheckBox.setSelected(studyBuild.protectPreBaseline || isLPTV);
				protectBaselineFromLPTVCheckBox.setSelected(studyBuild.protectBaselineFromLPTV && isLPTV);
				protectLPTVFromClassACheckBox.setSelected(isClassA && isPostWindow);

				includeForeignCheckBox.setSelected(studyBuild.includeForeign);
				if (null != studyBuild.includeUserRecords) {
					StringBuilder e = new StringBuilder();
					for (Integer uid : studyBuild.includeUserRecords) {
						e.append(String.valueOf(uid));
						e.append("\n");
					}
					includeUserRecordsTextArea.setText(e.toString());
				} else {
					includeUserRecordsTextArea.setText("");
				}

				cpExcludesBaselineCheckBox.setSelected(studyBuild.cpExcludesBaseline);
				excludeAppsCheckBox.setSelected(studyBuild.excludeApps);
				excludePendingCheckBox.setSelected(studyBuild.excludePending);
				excludePostTransitionCheckBox.setSelected(studyBuild.excludePostTransition);
				if (null != studyBuild.excludeCommands) {
					StringBuilder e = new StringBuilder();
					for (String cmd : studyBuild.excludeCommands) {
						e.append(cmd);
						e.append("\n");
					}
					excludeCommandsTextArea.setText(e.toString());
				} else {
					excludeCommandsTextArea.setText("");
				}

				filingCutoffDatePanel.setDate(studyBuild.filingCutoffDate);

			} else {

				studyBuild.didSetBefore = true;
				beforeLabel.setText("(none)");
			}

			blockActionsEnd();
		}


		//-------------------------------------------------------------------------------------------------------------
		// Set UI fields to defaults from the study build object.

		private void applyDefaults() {

			String str;
			if (isLPTV || (isClassA && isPostWindow)) {
				str = studyBuild.defaultCellSizeLPTV;
			} else {
				str = studyBuild.defaultCellSize;
			}
			if ((null != str) && (str.length() > 0)) {
				try {
					double d = Double.parseDouble(str);
					str = AppCore.formatDecimal(d, 1);
					cellSizeMenu.setSelectedItem(str);
				} catch (NumberFormatException nfe) {
				}
			}

			if (isLPTV || (isClassA && isPostWindow)) {
				str = studyBuild.defaultProfilePpkLPTV;
			} else {
				str = studyBuild.defaultProfilePpk;
			}
			if ((null != str) && (str.length() > 0)) {
				try {
					double d = Double.parseDouble(str);
					if (d > 0.) {
						d = 1. / d;
						profilePtIncField.setText(String.valueOf(d));
					}
				} catch (NumberFormatException nfe) {
				}
			}

			if (studyBuild.buildFullStudy) {

				if (null != studyBuild.baselineDate) {
					AppController.setComponentEnabled(protectPreBaselineCheckBox, enabled);
				} else {
					protectPreBaselineCheckBox.setSelected(false);
					AppController.setComponentEnabled(protectPreBaselineCheckBox, false);
				}

				if (isClassA) {
					if (!isPostWindow) {
						AppController.setComponentEnabled(protectLPTVFromClassACheckBox, enabled);
					} else {
						protectLPTVFromClassACheckBox.setSelected(true);
						AppController.setComponentEnabled(protectLPTVFromClassACheckBox, false);
					}
				} else {
					protectLPTVFromClassACheckBox.setSelected(false);
					AppController.setComponentEnabled(protectLPTVFromClassACheckBox, false);
				}
			}
		}


		//-------------------------------------------------------------------------------------------------------------
		// When a record from a data set is selected, set default values for cell size and profile resolution, also
		// set the file number as the study name, and select the station data set.

		public void selectionChanged(Object newSelection) {

			super.selectionChanged(newSelection);

			isPostWindow = ((null != studyBuild.filingWindowEndDate) && (null != sequenceDate) &&
				sequenceDate.after(studyBuild.filingWindowEndDate));

			if (null == newSelection) {
				return;
			}

			String str;
			if (isLPTV || (isClassA && isPostWindow)) {
				str = studyBuild.defaultCellSizeLPTV;
			} else {
				str = studyBuild.defaultCellSize;
			}
			if ((null != str) && (str.length() > 0)) {
				try {
					double d = Double.parseDouble(str);
					str = AppCore.formatDecimal(d, 1);
					cellSizeMenu.setSelectedItem(str);
				} catch (NumberFormatException nfe) {
				}
			}

			if (isLPTV || (isClassA && isPostWindow)) {
				str = studyBuild.defaultProfilePpkLPTV;
			} else {
				str = studyBuild.defaultProfilePpk;
			}
			if ((null != str) && (str.length() > 0)) {
				try {
					double d = Double.parseDouble(str);
					if (d > 0.) {
						d = 1. / d;
						profilePtIncField.setText(String.valueOf(d));
					}
				} catch (NumberFormatException nfe) {
				}
			}

			if (newSelection instanceof Record) {
				String theName = ((Record)newSelection).getFileNumber();
				if ((null != theName) && (theName.length() > 0)) {
					nameField.setText(theName);
				}
			}

			if (newSelection instanceof SourceEditData) {

				SourceEditData theSource = (SourceEditData)newSelection;
				if ((Source.RECORD_TYPE_TV == theSource.recordType) && (null != theSource.extDbKey)) {
					int theKey = theSource.extDbKey.intValue();
					if (extDbMenu.containsKey(theKey)) {
						extDbMenu.setSelectedKey(theKey);
					}
				}

			} else {

				if (newSelection instanceof ExtDbRecord) {

					ExtDbRecord theRecord = (ExtDbRecord)newSelection;
					if (Source.RECORD_TYPE_TV == theRecord.recordType) {
						int theKey = theRecord.extDb.dbKey.intValue();
						if (extDbMenu.containsKey(theKey)) {
							extDbMenu.setSelectedKey(theKey);
						}
					}
				}
			}

			if (studyBuild.buildFullStudy) {

				protectPreBaselineCheckBox.setSelected(isLPTV);

				if (isLPTV) {
					AppController.setComponentEnabled(protectBaselineFromLPTVCheckBox, enabled);
				} else {
					AppController.setComponentEnabled(protectBaselineFromLPTVCheckBox, false);
				}

				if (isClassA) {
					if (!isPostWindow) {
						protectLPTVFromClassACheckBox.setSelected(false);
						AppController.setComponentEnabled(protectLPTVFromClassACheckBox, enabled);
					} else {
						protectLPTVFromClassACheckBox.setSelected(true);
						AppController.setComponentEnabled(protectLPTVFromClassACheckBox, false);
					}
				} else {
					protectLPTVFromClassACheckBox.setSelected(false);
					AppController.setComponentEnabled(protectLPTVFromClassACheckBox, false);
				}
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		public boolean validateInput() {

			errorReporter.clearTitle();

			if (!super.validateInput()) {
				return false;
			}

			if (!DbCore.checkStudyName(name, getDbID(), false, errorReporter)) {
				return false;
			}

			int templateKey = templateMenu.getSelectedKey();
			if (templateKey <= 0) {
				errorReporter.reportWarning("Please select a study template.");
				return false;
			}

			ExtDb extDb = null;
			int theKey = extDbMenu.getSelectedKey();
			if (theKey > 0) {
				extDb = ExtDb.getExtDb(getDbID(), Integer.valueOf(theKey));
			} else {
				errorReporter.reportWarning("Please select a station data set.");
				return false;
			}
			if ((null == extDb) || extDb.deleted) {
				errorReporter.reportWarning("That station data set has been deleted, please select another.");
				return false;
			}

			String cellSize = (String)cellSizeMenu.getSelectedItem();

			String str = profilePtIncField.getText().trim();
			if (0 == str.length()) {
				errorReporter.reportWarning("Please enter a profile point spacing.");
				return false;
			}
			double d = Parameter.MIN_PATH_TERR_RES - 1.;
			try {
				d = Double.parseDouble(str);
				if (d > 0.) {
					if (d <= 1.) {
						d = 1. / d;
					} else {
						profilePtIncField.setText(AppCore.formatDecimal((1. / d), 3));
						profilePtIncField.selectAll();
					}
				}
			} catch (NumberFormatException ne) {
				errorReporter.reportWarning("The profile point spacing must be a number.");
				return false;
			}
			if ((d < Parameter.MIN_PATH_TERR_RES) || (d > Parameter.MAX_PATH_TERR_RES)) {
				errorReporter.reportWarning("The profile point spacing must be in the range " +
					(1. / Parameter.MAX_PATH_TERR_RES) + " to " + (1. / Parameter.MIN_PATH_TERR_RES) + ".");
				return false;
			}
			String profilePpk = String.valueOf(d);

			TreeSet<String> newExcludeCommands = null;
			TreeSet<Integer> newIncludeUserRecords = null;

			if (studyBuild.buildFullStudy) {

				str = excludeCommandsTextArea.getText().trim();
				if (str.length() > 0) {
					newExcludeCommands = new TreeSet<String>();
					for (String cmd : str.split("\\s+")) {
						newExcludeCommands.add(cmd);
					}
				}

				str = includeUserRecordsTextArea.getText().trim();
				if (str.length() > 0) {
					newIncludeUserRecords = new TreeSet<Integer>();
					int id;
					for (String uid : str.split("\\s+")) {
						id = 0;
						try {
							id = Integer.parseInt(uid);
						} catch (NumberFormatException nfe) {
						}
						if (id <= 0) {
							errorReporter.reportWarning("Only user record IDs may appear in the include list.");
							return false;
						}
						newIncludeUserRecords.add(Integer.valueOf(id));
					}
				}
			}

			// All good, save values.

			studyBuild.templateKey = templateKey;

			studyBuild.extDb = extDb;

			studyBuild.studyName = name;

			studyBuild.studyDescription = null;
			if (description.length() > 0) {
				studyBuild.studyDescription = description;
			}

			studyBuild.cellSize = cellSize;
			studyBuild.profilePpk = profilePpk;

			// If this is to be a full study build (and possible auto-run), all UI was active, update everything then
			// build the list of study build objects the main UI will use to add to the run manager.

			if (studyBuild.buildFullStudy) {

				studyBuild.fileOutputConfig = fileOutputConfig;
				studyBuild.mapOutputConfig = mapOutputConfig;

				studyBuild.protectPreBaseline = protectPreBaselineCheckBox.isSelected();
				studyBuild.protectBaselineFromLPTV = protectBaselineFromLPTVCheckBox.isSelected();
				studyBuild.protectLPTVFromClassA = protectLPTVFromClassACheckBox.isSelected();

				studyBuild.includeForeign = includeForeignCheckBox.isSelected();
				studyBuild.includeUserRecords = newIncludeUserRecords;

				studyBuild.cpExcludesBaseline = cpExcludesBaselineCheckBox.isSelected();
				studyBuild.excludeApps = excludeAppsCheckBox.isSelected();
				studyBuild.excludePending = excludePendingCheckBox.isSelected();
				studyBuild.excludePostTransition = excludePostTransitionCheckBox.isSelected();
				studyBuild.excludeCommands = newExcludeCommands;

				studyBuild.filingCutoffDate = filingCutoffDatePanel.getDate();

				// If not replicating, add the normal study, done.  Otherwise if the normal study is also included (see
				// super.validateInput()) add that, then add duplicates for each replication channel entered.

				studyBuilds = new ArrayList<StudyBuild>();

				if (!replicate) {

					studyBuilds.add(studyBuild);

				} else {

					if (includeNonReplicatedStudy) {
						studyBuilds.add(studyBuild);
					}

					for (int theChan : replicationChannels) {

						studyBuild = studyBuild.copy();

						studyBuild.replicate = true;
						studyBuild.replicationChannel = theChan;

						studyBuild.studyName = name + " (on " + String.valueOf(theChan) + ")";

						studyBuilds.add(studyBuild);
					}
				}

			// If this is not a full build, the main study build object will be retrieved and used in a different
			// context by the study manager.

			} else {

				if (replicate) {

					studyBuild.replicate = true;
					studyBuild.replicationChannel = replicationChannels[0];

					studyBuild.studyName = name + " (on " + String.valueOf(replicationChannels[0]) + ")";
				}
			}

			return true;
		}


		//-------------------------------------------------------------------------------------------------------------

		public StudyBuildIxCheck getStudyBuild() {

			return studyBuild;
		}


		//-------------------------------------------------------------------------------------------------------------

		public ArrayList<StudyBuild> getStudyBuilds() {

			return studyBuilds;
		}
	}


	//=================================================================================================================
	// Subclass used when creating a wireless interference study.  User inputs study name, selects template, and
	// selects the wireless station data set to use for the initial scenario creation.  In the containing RecordFind
	// dialog the user picks the TV data set and the desired TV record.

	public static class WirelessStudy extends OptionsPanel {

		private KeyedRecordMenu templateMenu;

		private KeyedRecordMenu wirelessExtDbMenu;
		private JCheckBox maskingCheckBox;
		private KeyedRecordMenu maskingExtDbMenu;

		private JTextField frequencyField;
		private JTextField bandwidthField;

		// Values are set directly in the study creation object.

		public StudyBuildWireless studyBuild;


		//-------------------------------------------------------------------------------------------------------------
		// Show name, description, replication, output configuration, and run options from superclass.

		public WirelessStudy(AppEditor theParent, StudyBuildWireless theStudy) {

			super(theParent, true, "Study Name", true, "Study Description", false, true, false, true, false, true);

			setBorder(BorderFactory.createTitledBorder("Study Build Settings"));

			studyBuild = theStudy;

			defaultName = theStudy.studyName;
			defaultDescription = theStudy.studyDescription;

			templateMenu = new KeyedRecordMenu();
			templateMenu.setPrototypeDisplayValue(new KeyedRecord(0, "XyXyXyXyXyXyXyXyXy"));

			templateMenu.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						updateParams();
						blockActionsEnd();
					}
				}
			});

			JPanel templatePanel = new JPanel();
			templatePanel.setBorder(BorderFactory.createTitledBorder("Template"));
			templatePanel.add(templateMenu);

			wirelessExtDbMenu = new KeyedRecordMenu();
			wirelessExtDbMenu.setPrototypeDisplayValue(new KeyedRecord(0, "XyXyXyXyXyXyXyXyXy"));

			JPanel wlMenuPanel = new JPanel();
			wlMenuPanel.setBorder(BorderFactory.createTitledBorder("Wireless Station Data"));
			wlMenuPanel.add(wirelessExtDbMenu);

			maskingCheckBox = new JCheckBox("Include masking interference");
			maskingCheckBox.setFocusable(false);
			maskingCheckBox.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					AppController.setComponentEnabled(maskingExtDbMenu, maskingCheckBox.isSelected());
				}
			});

			maskingExtDbMenu = new KeyedRecordMenu();
			maskingExtDbMenu.setPrototypeDisplayValue(new KeyedRecord(0, "XyXyXyXyXyXyXyXyXy"));
			AppController.setComponentEnabled(maskingExtDbMenu, false);

			JPanel tvMenuPanel = new JPanel();
			tvMenuPanel.setBorder(BorderFactory.createTitledBorder("TV Station Data"));
			tvMenuPanel.add(maskingExtDbMenu);

			frequencyField = new JTextField(7);
			AppController.fixKeyBindings(frequencyField);

			JPanel freqPanel = new JPanel();
			freqPanel.setBorder(BorderFactory.createTitledBorder("WL Freq., MHz"));
			freqPanel.add(frequencyField);

			bandwidthField = new JTextField(7);
			AppController.fixKeyBindings(bandwidthField);

			JPanel bandPanel = new JPanel();
			bandPanel.setBorder(BorderFactory.createTitledBorder("WL B/W, MHz"));
			bandPanel.add(bandwidthField);

			// Layout.

			Box col1Box = Box.createVerticalBox();
			col1Box.add(replicatePanel);
			col1Box.add(templatePanel);

			JPanel chkP = new JPanel(new FlowLayout(FlowLayout.LEFT));
			chkP.add(maskingCheckBox);

			Box col2Box = Box.createVerticalBox();
			col2Box.add(wlMenuPanel);
			col2Box.add(chkP);
			col2Box.add(tvMenuPanel);

			Box col3Box = Box.createVerticalBox();
			col3Box.add(freqPanel);
			col3Box.add(bandPanel);

			JPanel nameDescP = new JPanel();
			nameDescP.add(namePanel);
			nameDescP.add(descriptionPanel);

			JPanel outRunP = new JPanel();
			outRunP.add(outputPanel);
			outRunP.add(runPanel);

			Box col4Box = Box.createVerticalBox();
			col4Box.add(nameDescP);
			col4Box.add(outRunP);

			add(col1Box);
			add(col2Box);
			add(col3Box);
			add(col4Box);
		}


		//-------------------------------------------------------------------------------------------------------------
		// The layout is done directly.

		protected void createLayout(JPanel thePanel) {

			return;
		}


		//-------------------------------------------------------------------------------------------------------------

		private void updateParams() {

			int tempKey = templateMenu.getSelectedKey();
			if (tempKey > 0) {
				String str = Parameter.getTemplateParameterValue(getDbID(), tempKey, Parameter.PARAM_SCEN_WL_FREQ, 0);
				if (null != str) {
					frequencyField.setText(str);
				}
				str = Parameter.getTemplateParameterValue(getDbID(), tempKey, Parameter.PARAM_SCEN_WL_BW, 0);
				if (null != str) {
					bandwidthField.setText(str);
				}
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		public void setEnabled(boolean flag) {

			super.setEnabled(flag);

			AppController.setComponentEnabled(templateMenu, flag);

			AppController.setComponentEnabled(wirelessExtDbMenu, flag);
			AppController.setComponentEnabled(maskingCheckBox, flag);
			if (maskingCheckBox.isSelected()) {
				AppController.setComponentEnabled(maskingExtDbMenu, flag);
			} else {
				AppController.setComponentEnabled(maskingExtDbMenu, false);
			}

			AppController.setComponentEnabled(frequencyField, flag);
			AppController.setComponentEnabled(bandwidthField, flag);
		}


		//-------------------------------------------------------------------------------------------------------------
		// Populate menus when the containing window is shown.

		public void windowWillOpen() {

			templateMenu.removeAllItems();
			wirelessExtDbMenu.removeAllItems();
			maskingExtDbMenu.removeAllItems();

			SwingUtilities.invokeLater(new Runnable() {
				public void run() {

					blockActionsStart();

					errorReporter.setTitle("Load Template List");

					ArrayList<KeyedRecord> list = Template.getTemplateInfoList(getDbID(), errorReporter);
					if (null != list) {
						templateMenu.addAllItems(list);
					}

					errorReporter.setTitle("Load Station Data List");

					list = ExtDb.getExtDbList(getDbID(), Source.RECORD_TYPE_WL, false, errorReporter);
					if (null != list) {
						if (list.isEmpty()) {
							errorReporter.reportError(
								"No wireless station data found, use Station Data Manager to add data.");
						} else {
							wirelessExtDbMenu.addAllItems(list);
						}
					}

					list = ExtDb.getExtDbList(getDbID(), Source.RECORD_TYPE_TV, false, errorReporter);
					if (null != list) {
						maskingExtDbMenu.addAllItems(list);
					}

					clearFields();

					blockActionsEnd();
				}
			});
		}


		//-------------------------------------------------------------------------------------------------------------
		// The StudyBuild object may provide defaults.

		public void clearFields() {

			super.clearFields();

			blockActionsStart();

			if ((studyBuild.templateKey > 0) && templateMenu.containsKey(studyBuild.templateKey)) {
				templateMenu.setSelectedKey(studyBuild.templateKey);
			} else {
				templateMenu.setSelectedIndex(0);
			}

			updateParams();

			if (null != studyBuild.wirelessExtDb) {
				int theKey = studyBuild.wirelessExtDb.key.intValue();
				if (wirelessExtDbMenu.containsKey(theKey)) {
					wirelessExtDbMenu.setSelectedKey(theKey);
				} else {
					wirelessExtDbMenu.setSelectedIndex(0);
				}
			} else {
				wirelessExtDbMenu.setSelectedIndex(0);
			}
			if (null != studyBuild.maskingExtDb) {
				maskingCheckBox.setSelected(true);
				int theKey = studyBuild.maskingExtDb.key.intValue();
				if (maskingExtDbMenu.containsKey(theKey)) {
					maskingExtDbMenu.setSelectedKey(theKey);
				} else {
					maskingExtDbMenu.setSelectedIndex(0);
				}
			} else {
				maskingCheckBox.setSelected(false);
				maskingExtDbMenu.setSelectedIndex(0);
				AppController.setComponentEnabled(maskingExtDbMenu, false);
			}

			blockActionsEnd();
		}


		//-------------------------------------------------------------------------------------------------------------
		// When a record from a data set is selected, set the selection for the masking TV interference search to use
		// the same data set.  If the new record does not have a data set reference the menu selection is not changed.
		// The record should always be TV, just ignore if it is not.  Also set the study name to the file number.

		public void selectionChanged(Object newSelection) {

			super.selectionChanged(newSelection);

			if (null == newSelection) {
				return;
			}

			if (newSelection instanceof Record) {
				String theName = ((Record)newSelection).getFileNumber();
				if ((null != theName) && (theName.length() > 0)) {
					nameField.setText(theName);
				}
			}

			if (newSelection instanceof SourceEditData) {

				SourceEditData theSource = (SourceEditData)newSelection;
				if ((Source.RECORD_TYPE_TV == theSource.recordType) && (null != theSource.extDbKey)) {
					int theKey = theSource.extDbKey.intValue();
					if (maskingExtDbMenu.containsKey(theKey)) {
						maskingExtDbMenu.setSelectedKey(theKey);
					}
				}

			} else {

				if (newSelection instanceof ExtDbRecord) {

					ExtDbRecord theRecord = (ExtDbRecord)newSelection;
					if (Source.RECORD_TYPE_TV == theRecord.recordType) {
						int theKey = theRecord.extDb.dbKey.intValue();
						if (maskingExtDbMenu.containsKey(theKey)) {
							maskingExtDbMenu.setSelectedKey(theKey);
						}
					}
				}
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		public boolean validateInput() {

			errorReporter.clearTitle();

			if (!super.validateInput()) {
				return false;
			}

			if (!DbCore.checkStudyName(name, getDbID(), false, errorReporter)) {
				return false;
			}

			int templateKey = templateMenu.getSelectedKey();
			if (templateKey <= 0) {
				errorReporter.reportWarning("Please choose a study template.");
				return false;
			}

			ExtDb wirelessExtDb = null;
			int theKey = wirelessExtDbMenu.getSelectedKey();
			if (theKey > 0) {
				wirelessExtDb = ExtDb.getExtDb(getDbID(), Integer.valueOf(theKey));
			}
			if ((null == wirelessExtDb) || wirelessExtDb.deleted) {
				errorReporter.reportWarning("That wireless station data set has been deleted, please select another.");
				return false;
			}

			ExtDb maskingExtDb = null;
			if (maskingCheckBox.isSelected()) {
				theKey = maskingExtDbMenu.getSelectedKey();
				if (theKey > 0) {
					maskingExtDb = ExtDb.getExtDb(getDbID(), Integer.valueOf(theKey));
				}
				if ((null == maskingExtDb) || maskingExtDb.deleted) {
					errorReporter.reportWarning("That TV station data set has been deleted, please select another.");
					return false;
				}
			}

			String str = frequencyField.getText().trim();
			if (0 == str.length()) {
				errorReporter.reportWarning("Please enter a wireless frequency.");
				return false;
			}
			double d = 0.;
			try {
				d = Double.parseDouble(str);
			} catch (NumberFormatException ne) {
				errorReporter.reportWarning("The wireless frequency must be a number.");
				return false;
			}
			if ((d < 50.) || (d > 800.)) {
				errorReporter.reportWarning("The wireless frequency must be in the range 50 to 800.");
				return false;
			}
			String frequency = str;

			str = bandwidthField.getText().trim();
			if (0 == str.length()) {
				errorReporter.reportWarning("Please enter a wireless bandwidth.");
				return false;
			}
			d = 0.;
			try {
				d = Double.parseDouble(str);
			} catch (NumberFormatException ne) {
				errorReporter.reportWarning("The wireless bandwidth must be a number.");
				return false;
			}
			if ((d < 0.1) || (d > 20.)) {
				errorReporter.reportWarning("The wireless bandwidth must be in the range 0.1 to 20.");
				return false;
			}
			String bandwidth = str;

			// All good, save values.

			studyBuild.replicate = replicate;
			studyBuild.replicationChannel = replicationChannel;

			studyBuild.studyName = name;

			studyBuild.studyDescription = null;
			if (description.length() > 0) {
				studyBuild.studyDescription = description;
			}

			studyBuild.templateKey = templateKey;

			studyBuild.wirelessExtDb = wirelessExtDb;
			studyBuild.maskingExtDb = maskingExtDb;

			studyBuild.frequency = frequency;
			studyBuild.bandwidth = bandwidth;

			return true;
		}


		//-------------------------------------------------------------------------------------------------------------
		// To support other subclasses that might cause multiple studies to be created this returns an array, but here
		// there will only ever be one.

		public ArrayList<StudyBuild> getStudyBuilds() {

			ArrayList<StudyBuild> theBuilds = new ArrayList<StudyBuild>();
			theBuilds.add(studyBuild);
			return theBuilds;
		}
	}


	//=================================================================================================================
	// Subclass used when creating an FM to TV channel 6 interference study.  Input study name, select template, and
	// select the FM station data set to use for the initial scenario creation.  In the containing RecordFind dialog
	// the user picks the TV data set and the target TV record.

	public static class TV6FMStudy extends OptionsPanel {

		private KeyedRecordMenu templateMenu;

		private KeyedRecordMenu fmExtDbMenu;
		private JCheckBox maskingCheckBox;
		private KeyedRecordMenu tvExtDbMenu;

		// Values are set directly in the study creation object.

		public StudyBuildTV6FM studyBuild;


		//-------------------------------------------------------------------------------------------------------------
		// Show name, description, replicate, output, and run options from superclass.

		public TV6FMStudy(AppEditor theParent, StudyBuildTV6FM theStudy) {

			super(theParent, true, "Study Name", true, "Study Description", false, true, false, true, false, true);

			setBorder(BorderFactory.createTitledBorder("Study Build Settings"));

			studyBuild = theStudy;

			defaultName = theStudy.studyName;
			defaultDescription = theStudy.studyDescription;

			templateMenu = new KeyedRecordMenu();
			templateMenu.setPrototypeDisplayValue(new KeyedRecord(0, "XyXyXyXyXyXyXyXyXy"));

			JPanel templatePanel = new JPanel();
			templatePanel.setBorder(BorderFactory.createTitledBorder("Template"));
			templatePanel.add(templateMenu);

			fmExtDbMenu = new KeyedRecordMenu();
			fmExtDbMenu.setPrototypeDisplayValue(new KeyedRecord(0, "XyXyXyXyXyXyXyXyXy"));

			JPanel fmMenuPanel = new JPanel();
			fmMenuPanel.setBorder(BorderFactory.createTitledBorder("FM Station Data"));
			fmMenuPanel.add(fmExtDbMenu);

			maskingCheckBox = new JCheckBox("Include TV masking interference");
			maskingCheckBox.setFocusable(false);
			maskingCheckBox.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					AppController.setComponentEnabled(tvExtDbMenu, maskingCheckBox.isSelected());
				}
			});

			tvExtDbMenu = new KeyedRecordMenu();
			tvExtDbMenu.setPrototypeDisplayValue(new KeyedRecord(0, "XyXyXyXyXyXyXyXyXy"));
			AppController.setComponentEnabled(tvExtDbMenu, false);

			JPanel tvMenuPanel = new JPanel();
			tvMenuPanel.setBorder(BorderFactory.createTitledBorder("TV Station Data"));
			tvMenuPanel.add(tvExtDbMenu);

			// Layout.

			Box col1Box = Box.createVerticalBox();
			col1Box.add(replicatePanel);
			col1Box.add(templatePanel);

			JPanel chkP = new JPanel(new FlowLayout(FlowLayout.LEFT));
			chkP.add(maskingCheckBox);

			Box col2Box = Box.createVerticalBox();
			col2Box.add(fmMenuPanel);
			col2Box.add(chkP);
			col2Box.add(tvMenuPanel);

			JPanel nameDescP = new JPanel();
			nameDescP.add(namePanel);
			nameDescP.add(descriptionPanel);

			JPanel outRunP = new JPanel();
			outRunP.add(outputPanel);
			outRunP.add(runPanel);

			Box col3Box = Box.createVerticalBox();
			col3Box.add(nameDescP);
			col3Box.add(outRunP);

			add(col1Box);
			add(col2Box);
			add(col3Box);
		}


		//-------------------------------------------------------------------------------------------------------------
		// Layout done directly.

		protected void createLayout(JPanel thePanel) {

			return;
		}


		//-------------------------------------------------------------------------------------------------------------

		public void setEnabled(boolean flag) {

			super.setEnabled(flag);

			AppController.setComponentEnabled(templateMenu, flag);

			AppController.setComponentEnabled(fmExtDbMenu, flag);
			AppController.setComponentEnabled(maskingCheckBox, flag);
			if (maskingCheckBox.isSelected()) {
				AppController.setComponentEnabled(tvExtDbMenu, flag);
			} else {
				AppController.setComponentEnabled(tvExtDbMenu, false);
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		public void windowWillOpen() {

			templateMenu.removeAllItems();
			fmExtDbMenu.removeAllItems();
			tvExtDbMenu.removeAllItems();

			SwingUtilities.invokeLater(new Runnable() {
				public void run() {

					blockActionsStart();

					errorReporter.setTitle("Load Template List");

					ArrayList<KeyedRecord> list = Template.getTemplateInfoList(getDbID(), errorReporter);
					if (null != list) {
						templateMenu.addAllItems(list);
					}

					errorReporter.setTitle("Load Station Data List");

					list = ExtDb.getExtDbList(getDbID(), Source.RECORD_TYPE_FM, false, errorReporter);
					if (null != list) {
						if (list.isEmpty()) {
							errorReporter.reportError(
								"No FM station data found, use Station Data Manager to add data.");
						} else {
							fmExtDbMenu.addAllItems(list);
						}
					}

					list = ExtDb.getExtDbList(getDbID(), Source.RECORD_TYPE_TV, false, errorReporter);
					if (null != list) {
						tvExtDbMenu.addAllItems(list);
					}

					clearFields();

					blockActionsEnd();
				}
			});
		}


		//-------------------------------------------------------------------------------------------------------------

		public void clearFields() {

			super.clearFields();

			blockActionsStart();

			if ((studyBuild.templateKey > 0) && templateMenu.containsKey(studyBuild.templateKey)) {
				templateMenu.setSelectedKey(studyBuild.templateKey);
			} else {
				templateMenu.setSelectedIndex(0);
			}

			if (null != studyBuild.fmExtDb) {
				int theKey = studyBuild.fmExtDb.key.intValue();
				if (fmExtDbMenu.containsKey(theKey)) {
					fmExtDbMenu.setSelectedKey(theKey);
				} else {
					fmExtDbMenu.setSelectedIndex(0);
				}
			} else {
				fmExtDbMenu.setSelectedIndex(0);
			}
			if (null != studyBuild.tvExtDb) {
				maskingCheckBox.setSelected(true);
				int theKey = studyBuild.tvExtDb.key.intValue();
				if (tvExtDbMenu.containsKey(theKey)) {
					tvExtDbMenu.setSelectedKey(theKey);
				} else {
					tvExtDbMenu.setSelectedIndex(0);
				}
			} else {
				maskingCheckBox.setSelected(false);
				tvExtDbMenu.setSelectedIndex(0);
				AppController.setComponentEnabled(tvExtDbMenu, false);
			}

			blockActionsEnd();
		}


		//-------------------------------------------------------------------------------------------------------------
		// When a record from a data set is selected, set the selection for the TV interference search to use the same
		// data set.  If the new record does not have a data set reference the menu selection is not changed.  The
		// record should always be TV, just ignore if it is not.  Also set the study name to the record file number.

		public void selectionChanged(Object newSelection) {

			super.selectionChanged(newSelection);

			if (null == newSelection) {
				return;
			}

			if (newSelection instanceof Record) {
				String theName = ((Record)newSelection).getFileNumber();
				if ((null != theName) && (theName.length() > 0)) {
					nameField.setText(theName);
				}
			}

			if (newSelection instanceof SourceEditData) {

				SourceEditData theSource = (SourceEditData)newSelection;
				if ((Source.RECORD_TYPE_TV == theSource.recordType) && (null != theSource.extDbKey)) {
					int theKey = theSource.extDbKey.intValue();
					if (tvExtDbMenu.containsKey(theKey)) {
						tvExtDbMenu.setSelectedKey(theKey);
					}
				}

			} else {

				if (newSelection instanceof ExtDbRecord) {

					ExtDbRecord theRecord = (ExtDbRecord)newSelection;
					if (Source.RECORD_TYPE_TV == theRecord.recordType) {
						int theKey = theRecord.extDb.dbKey.intValue();
						if (tvExtDbMenu.containsKey(theKey)) {
							tvExtDbMenu.setSelectedKey(theKey);
						}
					}
				}
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		public boolean validateInput() {

			errorReporter.clearTitle();

			if (!super.validateInput()) {
				return false;
			}

			if (!DbCore.checkStudyName(name, getDbID(), false, errorReporter)) {
				return false;
			}

			int templateKey = templateMenu.getSelectedKey();
			if (templateKey <= 0) {
				errorReporter.reportWarning("Please choose a study template.");
				return false;
			}

			ExtDb fmExtDb = null;
			int theKey = fmExtDbMenu.getSelectedKey();
			if (theKey > 0) {
				fmExtDb = ExtDb.getExtDb(getDbID(), Integer.valueOf(theKey));
			}
			if ((null == fmExtDb) || fmExtDb.deleted) {
				errorReporter.reportWarning("That FM station data set has been deleted, please select another.");
				return false;
			}

			ExtDb tvExtDb = null;
			if (maskingCheckBox.isSelected()) {
				theKey = tvExtDbMenu.getSelectedKey();
				if (theKey > 0) {
					tvExtDb = ExtDb.getExtDb(getDbID(), Integer.valueOf(theKey));
				}
				if ((null == tvExtDb) || tvExtDb.deleted) {
					errorReporter.reportWarning("That TV station data set has been deleted, please select another.");
					return false;
				}
			}

			// All good, save values.

			studyBuild.replicate = replicate;
			studyBuild.replicationChannel = replicationChannel;

			studyBuild.studyName = name;

			studyBuild.studyDescription = null;
			if (description.length() > 0) {
				studyBuild.studyDescription = description;
			}

			studyBuild.templateKey = templateKey;

			studyBuild.fmExtDb = fmExtDb;
			studyBuild.tvExtDb = tvExtDb;

			return true;
		}


		//-------------------------------------------------------------------------------------------------------------
		// To support other subclasses that might cause multiple studies to be created this returns an array, but here
		// there will only ever be one.

		public ArrayList<StudyBuild> getStudyBuilds() {

			ArrayList<StudyBuild> theBuilds = new ArrayList<StudyBuild>();
			theBuilds.add(studyBuild);
			return theBuilds;
		}
	}


	//=================================================================================================================
	// Subclass for a run-study dialog, show output config including menus, and run options.

	public static class RunStart extends OptionsPanel {


		//-------------------------------------------------------------------------------------------------------------

		public RunStart(AppEditor theParent) {

			super(theParent, false, null, false, null, false, false, false, true, true, true);
		}
	}
}
