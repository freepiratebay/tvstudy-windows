//
//  IxRuleEditor.java
//  TVStudy
//
//  Copyright (c) 2012-2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui.editor;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.editdata.*;
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
// Interference rule editor dialog.  This can be a non-editable viewer.

public class IxRuleEditor extends AppDialog {

	public static final String WINDOW_TITLE = "Interference Rule";

	private IxRuleEditData ixRule;
	private boolean canEdit;

	// Editing components.

	private KeyedRecordMenu countryMenu;
	private KeyedRecordMenu serviceTypeMenu;
	private KeyedRecordMenu signalTypeMenu;
	private KeyedRecordMenu undesiredServiceTypeMenu;
	private KeyedRecordMenu undesiredSignalTypeMenu;
	private KeyedRecordMenu channelDeltaMenu;
	private KeyedRecordMenu channelBandMenu;
	private KeyedRecordMenu frequencyOffsetMenu;
	private KeyedRecordMenu emissionMaskMenu;
	private JTextField distanceField;
	private JTextField requiredDUField;
	private JTextField undesiredTimeField;

	// Buttons.

	private JButton closeButton;
	private JButton applyButton;


	//-----------------------------------------------------------------------------------------------------------------
	// The editor adjusts to the record type, service types are filtered and TV has more properties than FM.  If the
	// record type is 0 nothing is filtered, that is used by the template editor.

	public IxRuleEditor(AppEditor theParent, int recordType, IxRuleEditData theRule) {

		super(theParent, WINDOW_TITLE, Dialog.ModalityType.MODELESS);

		// Copy the model object so changes are isolated in case of a cancel.  The as-edited object is retrieved by
		// getIxRule().  If the rule is in a template-locked study all UI is disabled, this is a non-editable viewer.

		ixRule = theRule.copy();
		canEdit = !ixRule.isLocked;

		boolean isTV = ((0 == recordType) || (Source.RECORD_TYPE_TV == recordType));

		// Create the UI components.  Country first, a pop-up menu backed by the list of all countries.

		countryMenu = new KeyedRecordMenu(Country.getCountries());
		countryMenu.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					ixRule.country = (Country)countryMenu.getSelectedItem();
					blockActionsEnd();
				} else {
					countryMenu.setSelectedItem(ixRule.country);
				}
			}
		});
		JPanel countryPanel = new JPanel();
		countryPanel.setBorder(BorderFactory.createTitledBorder("Country"));
		countryPanel.add(countryMenu);

		// Desired service type, pop-up menu.  This interacts with the channel selection, this cannot be changed to a
		// digital type if an analog-only channel is selected.

		serviceTypeMenu = new KeyedRecordMenu(ServiceType.getServiceTypes(recordType));
		serviceTypeMenu.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					ServiceType newType = (ServiceType)serviceTypeMenu.getSelectedItem();
					if (newType.digital && (ixRule.channelDelta.key > 0) && ixRule.channelDelta.analogOnly) {
						errorReporter.reportValidationError("Edit Service Type",
							"That service type cannot be used with the selected channel.");
						serviceTypeMenu.setSelectedItem(ixRule.serviceType);
					} else {
						ixRule.serviceType = newType;
						if (newType.digital) {
							if (0 == ixRule.signalType.key) {
								ixRule.signalType = SignalType.getDefaultObject();
							}
						} else {
							if (ixRule.signalType.key > 0) {
								ixRule.signalType = SignalType.getNullObject();
							}
						}
						if (null != signalTypeMenu) {
							AppController.setComponentEnabled(signalTypeMenu, newType.digital);
							signalTypeMenu.setSelectedItem(ixRule.signalType);
						}
					}
					blockActionsEnd();
				} else {
					serviceTypeMenu.setSelectedItem(ixRule.serviceType);
				}
			}
		});
		JPanel serviceTypePanel = new JPanel();
		serviceTypePanel.setBorder(BorderFactory.createTitledBorder("Service"));
		serviceTypePanel.add(serviceTypeMenu);

		// Desired signal type, pop-up menu.  TV only.  This interacts with service selection, it is disabled and
		// forced to the null object for analog services, enables and defaults to the default object for digital.
		// When enabled the menu does _not_ include the null object.  If the signal types list contains only one
		// choice the UI is hidden, however the property is still updated per service type.

		JPanel signalTypePanel = null;
		if (isTV) {
			if (SignalType.hasMultipleOptions()) {
				signalTypeMenu = new KeyedRecordMenu(SignalType.getSignalTypes());
				signalTypeMenu.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							if (ixRule.serviceType.digital) {
								ixRule.signalType = (SignalType)signalTypeMenu.getSelectedItem();
							} else {
								signalTypeMenu.setSelectedItem(ixRule.signalType);
							}
							blockActionsEnd();
						} else {
							signalTypeMenu.setSelectedItem(ixRule.signalType);
						}
					}
				});
				signalTypePanel = new JPanel();
				signalTypePanel.setBorder(BorderFactory.createTitledBorder("Mod. Type"));
				signalTypePanel.add(signalTypeMenu);
			}
		} else {
			if (canEdit) {
				ixRule.signalType = SignalType.getNullObject();
			}
		}

		// Undesired service type, pop-up menu.  For TV records, the selection here and in channel delta affects state
		// of the emission mask field, if the service type requires an emission mask and the delta is adjacent-channel,
		// the mask must be selected; otherwise it is not applicable.

		undesiredServiceTypeMenu = new KeyedRecordMenu(ServiceType.getServiceTypes(recordType));
		undesiredServiceTypeMenu.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					ixRule.undesiredServiceType = (ServiceType)undesiredServiceTypeMenu.getSelectedItem();
					if (null != emissionMaskMenu) {
						if (ixRule.undesiredServiceType.needsEmissionMask &&
								(1 == Math.abs(ixRule.channelDelta.delta))) {
							AppController.setComponentEnabled(emissionMaskMenu, true);
							if (0 == ixRule.emissionMask.key) {
								emissionMaskMenu.setSelectedItem(EmissionMask.getInvalidObject());
							}
						} else {
							AppController.setComponentEnabled(emissionMaskMenu, false);
							if (ixRule.emissionMask.key != 0) {
								emissionMaskMenu.setSelectedItem(EmissionMask.getNullObject());
							}
						}
					}
					if (ixRule.undesiredServiceType.digital) {
						if (0 == ixRule.undesiredSignalType.key) {
							ixRule.undesiredSignalType = SignalType.getDefaultObject();
						}
					} else {
						if (ixRule.undesiredSignalType.key > 0) {
							ixRule.undesiredSignalType = SignalType.getNullObject();
						}
					}
					if (null != undesiredSignalTypeMenu) {
						AppController.setComponentEnabled(undesiredSignalTypeMenu,
							ixRule.undesiredServiceType.digital);
						undesiredSignalTypeMenu.setSelectedItem(ixRule.undesiredSignalType);
					}
					blockActionsEnd();
				} else {
					undesiredServiceTypeMenu.setSelectedItem(ixRule.undesiredServiceType);
				}
			}
		});
		JPanel undesiredServiceTypePanel = new JPanel();
		undesiredServiceTypePanel.setBorder(BorderFactory.createTitledBorder("Service"));
		undesiredServiceTypePanel.add(undesiredServiceTypeMenu);

		// Undesired signal type, pop-up menu, as above.

		JPanel undesiredSignalTypePanel = null;
		if (isTV) {
			if (SignalType.hasMultipleOptions()) {
				undesiredSignalTypeMenu = new KeyedRecordMenu(SignalType.getSignalTypes());
				undesiredSignalTypeMenu.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							if (ixRule.undesiredServiceType.digital) {
								ixRule.undesiredSignalType = (SignalType)undesiredSignalTypeMenu.getSelectedItem();
							} else {
								undesiredSignalTypeMenu.setSelectedItem(ixRule.undesiredSignalType);
							}
							blockActionsEnd();
						} else {
							undesiredSignalTypeMenu.setSelectedItem(ixRule.undesiredSignalType);
						}
					}
				});
				undesiredSignalTypePanel = new JPanel();
				undesiredSignalTypePanel.setBorder(BorderFactory.createTitledBorder("Mod. Type"));
				undesiredSignalTypePanel.add(undesiredSignalTypeMenu);
			}
		} else {
			if (canEdit) {
				ixRule.undesiredSignalType = SignalType.getNullObject();
			}
		}

		// Channel delta, pop-up menu.  Interacts with service types, distance, and emission mask.  If the desired
		// service type is digital an analog-only channel cannot be selected.  When the channel is changed, check the
		// distance field; if it is disabled (because it is initially disabled when no channel is selected), set the
		// distance to the channel's maximum.  Otherwise check the distance against the maximum and update as needed.
		// Finally, for TV records this may change the state of the emission mask selection, which is enabled or
		// disabled per the undesired service type and whether or not a first-adjacent channel is selected.

		channelDeltaMenu = new KeyedRecordMenu(ChannelDelta.getChannelDeltas(recordType));
		channelDeltaMenu.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					ChannelDelta newDelta = (ChannelDelta)channelDeltaMenu.getSelectedItem();
					if (newDelta.analogOnly && (ixRule.serviceType.key > 0) && ixRule.serviceType.digital) {
						errorReporter.reportValidationError("Edit Channel",
							"That channel cannot be used with the selected desired service type.");
						channelDeltaMenu.setSelectedItem(ixRule.channelDelta);
					} else {
						ixRule.channelDelta = newDelta;
						if (null != emissionMaskMenu) {
							if (ixRule.undesiredServiceType.needsEmissionMask && (1 == Math.abs(newDelta.delta))) {
								AppController.setComponentEnabled(emissionMaskMenu, true);
								if (0 == ixRule.emissionMask.key) {
									emissionMaskMenu.setSelectedItem(EmissionMask.getInvalidObject());
								}
							} else {
								AppController.setComponentEnabled(emissionMaskMenu, false);
								if (ixRule.emissionMask.key != 0) {
									emissionMaskMenu.setSelectedItem(EmissionMask.getNullObject());
								}
							}
						}
					}
					blockActionsEnd();
				} else {
					channelDeltaMenu.setSelectedItem(ixRule.channelDelta);
				}
			}
		});
		JPanel channelDeltaPanel = new JPanel();
		channelDeltaPanel.setBorder(BorderFactory.createTitledBorder("Channel"));
		channelDeltaPanel.add(channelDeltaMenu);

		// Channel band, pop-up menu.  Only for TV.

		JPanel channelBandPanel = null;
		if (isTV) {
			channelBandMenu = new KeyedRecordMenu(ChannelBand.getChannelBandsWithNull());
			channelBandMenu.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						ixRule.channelBand = (ChannelBand)channelBandMenu.getSelectedItem();
						blockActionsEnd();
					} else {
						channelBandMenu.setSelectedItem(ixRule.channelBand);
					}
				}
			});
			channelBandPanel = new JPanel();
			channelBandPanel.setBorder(BorderFactory.createTitledBorder("Band"));
			channelBandPanel.add(channelBandMenu);
		} else {
			if (canEdit) {
				ixRule.channelBand = ChannelBand.getNullObject();
			}
		}

		// Frequency offset, pop-up menu.  Also TV only.  Doesn't have a separate enumeration class since it's really
		// just a glorified boolean, make a menu list including the three possible choices: without, with, or whatever.

		JPanel frequencyOffsetPanel = null;
		if (isTV) {
			ArrayList<KeyedRecord> items = new ArrayList<KeyedRecord>();
			items.add(new KeyedRecord(0, "(any)"));
			items.add(new KeyedRecord(IxRule.FREQUENCY_OFFSET_WITH, "With"));
			items.add(new KeyedRecord(IxRule.FREQUENCY_OFFSET_WITHOUT, "Without"));
			frequencyOffsetMenu = new KeyedRecordMenu(items);
			frequencyOffsetMenu.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						ixRule.frequencyOffset = frequencyOffsetMenu.getSelectedKey();
						blockActionsEnd();
					} else {
						frequencyOffsetMenu.setSelectedKey(ixRule.frequencyOffset);
					}
				}
			});
			frequencyOffsetPanel = new JPanel();
			frequencyOffsetPanel.setBorder(BorderFactory.createTitledBorder("Frequency Offset"));
			frequencyOffsetPanel.add(frequencyOffsetMenu);
		} else {
			if (canEdit) {
				ixRule.frequencyOffset = 0;
			}
		}

		// Emission mask field, pop-up menu.  Also TV only.  This may be disabled based on undesired service type and
		// channel delta.

		JPanel emissionMaskPanel = null;
		if (isTV) {
			emissionMaskMenu = new KeyedRecordMenu(EmissionMask.getEmissionMasksWithNull());
			emissionMaskMenu.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						ixRule.emissionMask = (EmissionMask)emissionMaskMenu.getSelectedItem();
						blockActionsEnd();
					} else {
						emissionMaskMenu.setSelectedItem(ixRule.emissionMask);
					}
				}
			});
			emissionMaskPanel = new JPanel();
			emissionMaskPanel.setBorder(BorderFactory.createTitledBorder("Emission Mask"));
			emissionMaskPanel.add(emissionMaskMenu);
			AppController.setComponentEnabled(emissionMaskMenu, false);
		} else {
			if (canEdit) {
				ixRule.emissionMask = EmissionMask.getNullObject();
			}
		}

		// Limit distance field, text type-in.

		distanceField = new JTextField(10);
		AppController.fixKeyBindings(distanceField);
		distanceField.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					String title = "Edit Distance Limit";
					String str = distanceField.getText().trim();
					if (str.length() > 0) {
						try {
							double d = Double.parseDouble(str);
							if ((d < IxRule.DISTANCE_MIN) || (d > IxRule.DISTANCE_MAX)) {
								errorReporter.reportValidationError(title, "The value must be in the range " +
									IxRule.DISTANCE_MIN + " to " + IxRule.DISTANCE_MAX + ".");
							} else {
								ixRule.distance = d;
							}
						} catch (NumberFormatException ne) {
							errorReporter.reportValidationError(title, "The value must be a number.");
						}
					}
					blockActionsEnd();
				}
				distanceField.setText(AppCore.formatDistance(ixRule.distance));
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
		distancePanel.setBorder(BorderFactory.createTitledBorder("Distance Limit"));
		distancePanel.add(distanceField);

		// Required D/U field, text type-in.

		requiredDUField = new JTextField(10);
		AppController.fixKeyBindings(requiredDUField);
		requiredDUField.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					String title = "Edit Required D/U";
					String str = requiredDUField.getText().trim();
					if (str.length() > 0) {
						try {
							double d = Double.parseDouble(str);
							if ((d < IxRule.DU_MIN) || (d > IxRule.DU_MAX)) {
								errorReporter.reportValidationError(title, "The value must be in the range " +
									IxRule.DU_MIN + "  to " + IxRule.DU_MAX + ".");
							} else {
								ixRule.requiredDU = d;
							}
						} catch (NumberFormatException ne) {
							errorReporter.reportValidationError(title, "The value must be a number.");
						}
					}
					blockActionsEnd();
				}
				requiredDUField.setText(AppCore.formatDU(ixRule.requiredDU));
			}
		});
		requiredDUField.addFocusListener(new FocusAdapter() {
			public void focusGained(FocusEvent theEvent) {
				setCurrentField(requiredDUField);
			}
			public void focusLost(FocusEvent theEvent) {
				if (!theEvent.isTemporary()) {
					requiredDUField.postActionEvent();
				}
			}
		});
		JPanel requiredDUPanel = new JPanel();
		requiredDUPanel.setBorder(BorderFactory.createTitledBorder("Required D/U"));
		requiredDUPanel.add(requiredDUField);

		// Undesired % time field, text type-in.

		undesiredTimeField = new JTextField(10);
		AppController.fixKeyBindings(undesiredTimeField);
		undesiredTimeField.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				if (blockActions()) {
					String title = "Edit Undesired % Time";
					String str = undesiredTimeField.getText().trim();
					if (str.length() > 0) {
						try {
							double d = Double.parseDouble(str);
							if ((d < IxRule.PERCENT_TIME_MIN) || (d > IxRule.PERCENT_TIME_MAX)) {
								errorReporter.reportValidationError(title, "The value must be in the range " +
									IxRule.PERCENT_TIME_MIN + " to " + IxRule.PERCENT_TIME_MAX + ".");
							} else {
								ixRule.undesiredTime = d;
							}
						} catch (NumberFormatException ne) {
							errorReporter.reportValidationError(title, "The value must be a number.");
						}
					}
					blockActionsEnd();
				}
				undesiredTimeField.setText(AppCore.formatPercent(ixRule.undesiredTime));
			}
		});
		undesiredTimeField.addFocusListener(new FocusAdapter() {
			public void focusGained(FocusEvent theEvent) {
				setCurrentField(undesiredTimeField);
			}
			public void focusLost(FocusEvent theEvent) {
				if (!theEvent.isTemporary()) {
					undesiredTimeField.postActionEvent();
				}
			}
		});
		JPanel undesiredTimePanel = new JPanel();
		undesiredTimePanel.setBorder(BorderFactory.createTitledBorder("Undesired % Time"));
		undesiredTimePanel.add(undesiredTimeField);

		// Buttons.

		closeButton = new JButton("Cancel");
		closeButton.setFocusable(false);
		closeButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				cancel();
			}
		});

		applyButton = new JButton("Save");
		applyButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doApply();
			}
		});

		// Do the layout.

		JPanel row1Panel = new JPanel();
		row1Panel.setBorder(BorderFactory.createTitledBorder("Desired Station"));
		row1Panel.add(countryPanel);
		row1Panel.add(serviceTypePanel);
		if (null != signalTypePanel) {
			row1Panel.add(signalTypePanel);
		}
		if (null != channelBandPanel) {
			row1Panel.add(channelBandPanel);
		}

		JPanel row2Panel = new JPanel();
		row2Panel.setBorder(BorderFactory.createTitledBorder("Undesired Station"));
		row2Panel.add(undesiredServiceTypePanel);
		if (null != undesiredSignalTypePanel) {
			row2Panel.add(undesiredSignalTypePanel);
		}
		row2Panel.add(channelDeltaPanel);
		if (null != emissionMaskPanel) {
			row2Panel.add(emissionMaskPanel);
		}

		JPanel row3Panel = new JPanel();
		row3Panel.setBorder(BorderFactory.createTitledBorder("Conditions"));
		if (null != frequencyOffsetPanel) {
			row3Panel.add(frequencyOffsetPanel);
		}
		row3Panel.add(distancePanel);
		row3Panel.add(requiredDUPanel);
		row3Panel.add(undesiredTimePanel);

		JPanel editorPanel = new JPanel();
		editorPanel.setLayout(new BoxLayout(editorPanel, BoxLayout.Y_AXIS));
		editorPanel.add(row1Panel);
		editorPanel.add(row2Panel);
		editorPanel.add(row3Panel);

		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttonPanel.add(closeButton);
		buttonPanel.add(applyButton);

		Container cp = getContentPane();
		cp.setLayout(new BorderLayout());
		cp.add(editorPanel, BorderLayout.CENTER);
		cp.add(buttonPanel, BorderLayout.SOUTH);

		getRootPane().setDefaultButton(applyButton);

		pack();

		setMinimumSize(getSize());

		setResizable(true);
		setLocationSaved(true);

		// Initialize the UI.

		countryMenu.setSelectedItem(ixRule.country);
		AppController.setComponentEnabled(countryMenu, canEdit);

		serviceTypeMenu.setSelectedItem(ixRule.serviceType);
		AppController.setComponentEnabled(serviceTypeMenu, canEdit);

		if (null != signalTypeMenu) {
			signalTypeMenu.setSelectedItem(ixRule.signalType);
			if (ixRule.serviceType.digital) {
				AppController.setComponentEnabled(signalTypeMenu, canEdit);
			} else {
				AppController.setComponentEnabled(signalTypeMenu, false);
			}
		}

		if (null != channelBandMenu) {
			channelBandMenu.setSelectedItem(ixRule.channelBand);
			AppController.setComponentEnabled(channelBandMenu, canEdit);
		}

		undesiredServiceTypeMenu.setSelectedItem(ixRule.undesiredServiceType);
		AppController.setComponentEnabled(undesiredServiceTypeMenu, canEdit);

		if (null != undesiredSignalTypeMenu) {
			undesiredSignalTypeMenu.setSelectedItem(ixRule.undesiredSignalType);
			if (ixRule.undesiredServiceType.digital) {
				AppController.setComponentEnabled(undesiredSignalTypeMenu, canEdit);
			} else {
				AppController.setComponentEnabled(undesiredSignalTypeMenu, false);
			}
		}

		channelDeltaMenu.setSelectedItem(ixRule.channelDelta);
		AppController.setComponentEnabled(channelDeltaMenu, canEdit);

		if (null != emissionMaskMenu) {
			emissionMaskMenu.setSelectedItem(ixRule.emissionMask);
			if (ixRule.undesiredServiceType.needsEmissionMask && (1 == Math.abs(ixRule.channelDelta.delta))) {
				AppController.setComponentEnabled(emissionMaskMenu, canEdit);
			} else {
				AppController.setComponentEnabled(emissionMaskMenu, false);
			}
		}

		if (null != frequencyOffsetMenu) {
			frequencyOffsetMenu.setSelectedKey(ixRule.frequencyOffset);
			AppController.setComponentEnabled(frequencyOffsetMenu, canEdit);
		}

		distanceField.setText(AppCore.formatDistance(ixRule.distance));

		requiredDUField.setText(AppCore.formatDU(ixRule.requiredDU));
		AppController.setComponentEnabled(requiredDUField, canEdit);

		undesiredTimeField.setText(AppCore.formatPercent(ixRule.undesiredTime));
		AppController.setComponentEnabled(undesiredTimeField, canEdit);

		updateDocumentName();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void updateDocumentName() {

		String docName;

		if ((null != ixRule) && !ixRule.isActive) {
			docName = parent.getDocumentName() + "/disabled";
		} else {
			docName = parent.getDocumentName();
		}

		setDocumentName(docName);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Do nothing if not editable.

	private void doApply() {

		if (!canEdit) {
			cancel();
			return;
		}

		// Commit any pending edits in text fields, check if the listeners threw errors.

		if (!commitCurrentField()) {
			return;
		}

		// Do a full validity check just to be safe.

		errorReporter.clearTitle();

		if (!ixRule.isDataValid(errorReporter)) {
			return;
		}

		// Apply to the parent, close if successful.

		if (parent.applyEditsFrom(this)) {
			blockActionsSet();
			parent.editorClosing(this);
			AppController.hideWindow(this);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public IxRuleEditData getIxRule() {

		return ixRule;
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

		setLocationRelativeTo(getOwner());

		blockActionsClear();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean windowShouldClose() {

		blockActionsSet();
		parent.editorClosing(this);
		return true;
	}
}
