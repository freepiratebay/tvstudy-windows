//
//  OutputConfigDialog.java
//  TVStudy
//
//  Copyright (c) 2015-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;

import java.util.*;
import java.sql.*;
import java.awt.*;
import java.awt.event.*;

import javax.swing.*;
import javax.swing.event.*;


//=====================================================================================================================
// Dialog to select and edit output configurations.  May shows multiple panels in a tabbed layout

public class OutputConfigDialog extends AppDialog {

	public static final String WINDOW_TITLE = "Output Settings";

	private ArrayList<OutputConfig> configs;
	private boolean showNullObject;

	private ArrayList<ConfigPanel> configPanels;

	private OutputConfigDialog outerThis = this;


	//-----------------------------------------------------------------------------------------------------------------
	// In this form, the layout will show one panel for each possible type of configuration.  It is assumed the dialog
	// is being shown just to allow saved configurations to be managed, the caller will not actually be using the
	// final selections when the dialog closes, so a cancel button is not shown.  The initial configs in each of the
	// panels will be whatever is returned by OutputConfig.getLastUsed().

	public OutputConfigDialog(AppEditor theParent) {

		super(theParent, WINDOW_TITLE, Dialog.ModalityType.APPLICATION_MODAL);

		doSetup(null, false, false);
	}	

	
	//-----------------------------------------------------------------------------------------------------------------
	// In this form, an existing set of config selections is being edited.  One panel is created for each config in
	// the argument list (however note the design assumes there will be at most one config of each possible type).
	// The caller will use getConfigs() to get the as-edited state when the dialog closes.  A cancel button is shown.
	// Optionally the selection menus can include the null object.

	public OutputConfigDialog(AppEditor theParent, ArrayList<OutputConfig> theConfigs, boolean allowNull) {

		super(theParent, WINDOW_TITLE, Dialog.ModalityType.APPLICATION_MODAL);

		doSetup(theConfigs, true, allowNull);
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doSetup(ArrayList<OutputConfig> theConfigs, boolean showCancel, boolean allowNull) {

		configs = new ArrayList<OutputConfig>();

		if (null != theConfigs) {
			for (OutputConfig theConfig : theConfigs) {
				if (null != theConfig) {
					configs.add(theConfig);
				}
			}
		}

		if (configs.isEmpty()) {
			configs.add(OutputConfig.getLastUsed(getDbID(), OutputConfig.CONFIG_TYPE_FILE));
			configs.add(OutputConfig.getLastUsed(getDbID(), OutputConfig.CONFIG_TYPE_MAP));
		}

		configPanels = new ArrayList<ConfigPanel>();

		for (OutputConfig theConfig : configs) {
			configPanels.add(new ConfigPanel(theConfig.type, theConfig));
		}

		showNullObject = allowNull;

		// Buttons.

		JButton okButton = new JButton("OK");
		okButton.setFocusable(false);
		okButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doOK();
			}
		});

		JButton cancelButton = null;
		if (showCancel) {
			cancelButton = new JButton("Cancel");
			cancelButton.setFocusable(false);
			cancelButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doCancel(true);
				}
			});
		}

		// Do the layout.

		Container theLayout = null;
		if (configPanels.size() == 1) {
			theLayout = configPanels.get(0);
		} else {
			JTabbedPane theTabPane = new JTabbedPane();
			for (ConfigPanel thePanel : configPanels) {
				theTabPane.addTab(OutputConfig.getTypeName(thePanel.type), thePanel);
			}
			theLayout = theTabPane;
		}
			
		JPanel butPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		if (showCancel) {
			butPanel.add(cancelButton);
		}
		butPanel.add(okButton);

		Container cp = getContentPane();
		cp.setLayout(new BorderLayout());
		cp.add(theLayout, BorderLayout.CENTER);
		cp.add(butPanel, BorderLayout.SOUTH);

		getRootPane().setDefaultButton(okButton);

		pack();

		setMinimumSize(getSize());

		setResizable(true);

		// Initial update.

		updateMenus();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void updateDocumentName() {

		setDocumentName(parent.getDocumentName());
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void updateMenus() {

		for (ConfigPanel thePanel : configPanels) {
			thePanel.updateMenu();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	private boolean validateInputs() {

		for (ConfigPanel thePanel : configPanels) {
			if (!thePanel.validateInput()) {
				return false;
			}
		}
		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doOK() {

		if (!validateInputs()) {
			return;
		}

		configs = new ArrayList<OutputConfig>();
		for (ConfigPanel thePanel : configPanels) {
			configs.add(thePanel.config);
		}

		blockActionsSet();
		AppController.hideWindow(this);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This is a very different cancel because of the possibility that an original configuration actually changed due
	// to a save, in which case the state in the caller's context has to reflect the as-saved object even if the user
	// says cancel.  Look up the old configs by name and return new objects.  If the name no longer exists due to a
	// delete just return the original object; that can still be used by the caller as it is now standalone.  The
	// caller must apply the new objects regardless, so there is no cancelled property.  Null or unsaved objects in
	// the original state are always just returned.

	private void doCancel(boolean doHide) {

		ArrayList<OutputConfig> oldConfigs = configs;

		configs = new ArrayList<OutputConfig>();
		OutputConfig newConfig;

		for (OutputConfig oldConfig : oldConfigs) {

			if (oldConfig.isUnsaved() || oldConfig.isNull()) {
				newConfig = oldConfig;
			} else {

				newConfig = OutputConfig.getConfig(getDbID(), oldConfig.type, oldConfig.name);
				if (null == newConfig) {
					newConfig = oldConfig;
				}
			}

			configs.add(newConfig);
		}

		blockActionsSet();

		if (doHide) {
			AppController.hideWindow(this);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public ArrayList<OutputConfig> getConfigs() {

		return configs;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowWillOpen() {

		setLocationRelativeTo(getOwner());

		blockActionsClear();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean windowShouldClose() {

		doCancel(false);

		return true;
	}


	//=================================================================================================================
	// Panel to edit one configuration, along with a menu showing all saved configurations of the same type which can
	// be selected to change the current config, also with a delete button to delete the selected saved config, and a
	// name field and save button to enter a new name and save the current config under that name.

	private class ConfigPanel extends JPanel {

		private int type;

		private JComboBox<OutputConfig> configMenu;
		private JButton deleteButton;

		private ArrayList<FlagPanel> flagPanels;

		private JTextField nameField;
		private JButton saveButton;

		private OutputConfig config;


		//-------------------------------------------------------------------------------------------------------------

		private ConfigPanel(int theType, OutputConfig theConfig) {

			super();

			type = theType;
			config = theConfig;

			// Menu will be populated in updateMenu().

			configMenu = new JComboBox<OutputConfig>();
			configMenu.setFocusable(false);

			configMenu.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						OutputConfig newConfig = (OutputConfig)configMenu.getSelectedItem();
						if (null != newConfig) {
							config = newConfig;
							updateConfig();
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

			JPanel menuPanel = new JPanel();
			menuPanel.add(configMenu);
			menuPanel.add(deleteButton);

			// Build UI for active flags.  If a flag has multiple names, a check box is shown with the first name, and
			// a pop-up menu beside that with the additional names.  If single name, just a check box.  See FlagPanel.

			flagPanels = new ArrayList<FlagPanel>();

			String[][] theNames = OutputConfig.getFlagNames(type);
			for (int flagIndex : OutputConfig.getFlagList(type)) {
				flagPanels.add(new FlagPanel(flagIndex, theNames[flagIndex]));
			}

			JPanel flagPanel = new JPanel();
			flagPanel.setLayout(new BoxLayout(flagPanel, BoxLayout.Y_AXIS));
			for (FlagPanel thePanel : flagPanels) {
				flagPanel.add(thePanel);
			}

			// Field for a new configuration name and a save button.

			nameField = new JTextField(20);
			AppController.fixKeyBindings(nameField);

			saveButton = new JButton("Save");
			saveButton.setFocusable(false);
			saveButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doSave();
				}
			});

			JPanel savePanel = new JPanel();
			savePanel.add(nameField);
			savePanel.add(saveButton);

			// Do the layout.

			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			add(flagPanel);
			add(menuPanel);
			add(savePanel);
		}


		//=============================================================================================================
		// UI for one flag in the config, check-box and possible menu.

		private class FlagPanel extends JPanel {

			private int flagIndex;

			private JCheckBox checkBox;
			private JComboBox<String> menu;


			//---------------------------------------------------------------------------------------------------------

			private FlagPanel(int theIndex, String[] theNames) {

				flagIndex = theIndex;

				setLayout(new FlowLayout(FlowLayout.LEFT));

				checkBox = new JCheckBox(theNames[0]);
				checkBox.setFocusable(false);
				add(checkBox);

				if (theNames.length > 1) {

					checkBox.addActionListener(new ActionListener() {
						public void actionPerformed(ActionEvent theEvent) {
							AppController.setComponentEnabled(menu, checkBox.isSelected());
						}
					});

					menu = new JComboBox<String>();
					menu.setFocusable(false);
					for (int i = 1; i < theNames.length; i++) {
						menu.addItem(theNames[i]);
					}
					AppController.setComponentEnabled(menu, false);
					add(menu);
				}
			}


			//---------------------------------------------------------------------------------------------------------

			private int getFlag() {

				if (checkBox.isSelected()) {
					if (null != menu) {
						return menu.getSelectedIndex() + 1;
					} else {
						return 1;
					}
				}
				return 0;
			}


			//---------------------------------------------------------------------------------------------------------

			private void setFlag(int theFlag) {

				if (theFlag < 1) {
					checkBox.setSelected(false);
					if (null != menu) {
						AppController.setComponentEnabled(menu, false);
					}
				} else {
					checkBox.setSelected(true);
					if (null != menu) {
						AppController.setComponentEnabled(menu, true);
						if (theFlag <= menu.getItemCount()) {
							menu.setSelectedIndex(theFlag - 1);
						} else {
							menu.setSelectedIndex(0);
						}
					}
				}
			}


			//---------------------------------------------------------------------------------------------------------

			public void setEnabled(boolean theFlag) {

				if (theFlag) {
					AppController.setComponentEnabled(checkBox, true);
					if (null != menu) {
						AppController.setComponentEnabled(menu, checkBox.isSelected());
					}
				} else {
					AppController.setComponentEnabled(checkBox, false);
					if (null != menu) {
						AppController.setComponentEnabled(menu, false);
					}
				}
			}
		}


		//-------------------------------------------------------------------------------------------------------------
		// The showNullObject option ensures the null object always appears in the menu; but regardless of that option
		// if the initial config happens to be a null object it will still appear in the menu initially.  But it will
		// disappear as soon as something else is selected.  A default object is also always shown in the list.

		private void updateMenu() {

			blockActionsStart();

			ArrayList<OutputConfig> theConfigs = OutputConfig.getConfigs(getDbID(), type);
			theConfigs.add(0, OutputConfig.getDefaultObject(type));
			if (!theConfigs.contains(config)) {
				theConfigs.add(0, config);
			}
			if (showNullObject && !config.isNull()) {
				theConfigs.add(0, OutputConfig.getNullObject(type));
			}

			configMenu.removeAllItems();
			for (OutputConfig theConfig : theConfigs) {
				configMenu.addItem(theConfig);
			}
			configMenu.setSelectedItem(config);

			updateConfig();

			blockActionsEnd();
		}


		//-------------------------------------------------------------------------------------------------------------

		private void updateConfig() {

			if (config.isNull()) {
				for (FlagPanel thePanel : flagPanels) {
					thePanel.setFlag(0);
					thePanel.setEnabled(false);
				}
				deleteButton.setEnabled(false);
				nameField.setText("");
				AppController.setComponentEnabled(nameField, false);
				saveButton.setEnabled(false);
				return;
			}

			for (FlagPanel thePanel : flagPanels) {
				thePanel.setFlag(config.flags[thePanel.flagIndex]);
				thePanel.setEnabled(true);
			}
			if (!config.isUnsaved()) {
				deleteButton.setEnabled(true);
				nameField.setText(config.name);
			} else {
				deleteButton.setEnabled(false);
				nameField.setText("");
			}
			AppController.setComponentEnabled(nameField, true);
			saveButton.setEnabled(true);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doSave() {

			if (!validateInput()) {
				return;
			}

			String newName = nameField.getText().trim();
			if (newName.equalsIgnoreCase(OutputConfig.UNSAVED_CONFIG_NAME) ||
					newName.equalsIgnoreCase(OutputConfig.NULL_CONFIG_NAME)) {
				nameField.setText("");
				newName = "";
			}
			if (0 == newName.length()) {
				errorReporter.reportWarning("Please provide a valid name for the saved settings.");
				return;
			}

			config.name = newName;
			if (!config.save(getDbID(), errorReporter)) {
				config.name = OutputConfig.UNSAVED_CONFIG_NAME;
				nameField.setText("");
			}

			updateMenu();
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doDelete() {

			if (config.isUnsaved() || config.isNull()) {
				return;
			}

			if (OutputConfig.deleteConfig(getDbID(), config.type, config.name)) {
				config.name = OutputConfig.UNSAVED_CONFIG_NAME;
			}

			updateMenu();
		}


		//-------------------------------------------------------------------------------------------------------------
		// Get properties from UI and build new as-edited object.  Somewhat misnamed at the moment since this cannot
		// fail, any state is valid including with nothing checked.  Do nothing if the selection is a null object.  If
		// the new object would have identical flag state do not replace the existing object, so if it has a saved
		// name that remains.  Otherwise the new object has the unsaved name, if this is preceding a save it will get
		// a new name after the save, otherwise this is returning an unsaved state to the calling context.

		public boolean validateInput() {

			if (config.isNull()) {
				return true;
			}

			OutputConfig newConfig = new OutputConfig(type, "");
			for (FlagPanel thePanel : flagPanels) {
				newConfig.flags[thePanel.flagIndex] = thePanel.getFlag();
				if (newConfig.flags[thePanel.flagIndex] != config.flags[thePanel.flagIndex]) {
					config = newConfig;
				}
			}

			return true;
		}
	}
}
