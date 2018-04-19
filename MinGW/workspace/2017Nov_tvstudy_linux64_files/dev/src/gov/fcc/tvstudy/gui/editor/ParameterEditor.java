//
//  ParameterEditor.java
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.

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
// Object to support editing of a parameter in an editor UI.  Provides a label for the property name, component(s) to
// edit the value(s), a units label, and a button to set default value(s).  The edit component class depends on the
// parameter type; strings and numbers get a text field, pick-from values get a pop-up menu (non-editable combo box),
// option values get a check-box, date values get a panel with label display and a button that opens a date-picker
// dialog, see DateSelectionPanel.  The component has action and focus listeners to handle validation and convert the
// new value to a string.  For a multi-value parameter, an additional pop-up menu selects the value index being edited
// in the component.  For a table parameter, the component is a button that opens a modal dialog containing a table
// for editing the parameter values.  Generally these are not used directly, they are self-contained editors backed by
// the ParameterEditData model so it is usually not necessary to interact with them individually.  A containing editor
// will usually use createEditorLayout() to get a complete, self-contained UI for a set of parameters.

public class ParameterEditor {

	private AppEditor parent;
	private ErrorReporter errorReporter;

	private ParameterEditData parameter;

	public final JLabel label;
	public final JComponent editComponent;
	public final JLabel unitsLabel;
	public final JButton setDefaultButton;

	public final JComboBox<String> valueMenu;
	private int valueIndex;

	private NumberFormat doubleFormatter;

	private boolean canEdit;

	private TableParameterEditor tableEditor;

	// An option parameter editor can enable/disable a set of other editors, see createEditorLayout().

	private ArrayList<ParameterEditor> enablesEditors;


	//-----------------------------------------------------------------------------------------------------------------
	// Since this is not a standalone UI, the error reporting object is provided by the caller.

	public ParameterEditor(AppEditor theParent, ErrorReporter theErrorReporter, ParameterEditData theParameter) {

		super();

		parent = theParent;
		errorReporter = theErrorReporter;

		parent.blockActionsStart();

		parameter = theParameter;

		doubleFormatter = NumberFormat.getInstance(Locale.US);
		doubleFormatter.setMinimumFractionDigits(0);
		doubleFormatter.setMaximumFractionDigits(6);
		doubleFormatter.setMinimumIntegerDigits(1);
		doubleFormatter.setGroupingUsed(false);

		canEdit = !parameter.isLocked;

		JComponent theComponent;
		JTextField theField;

		// A table parameter is handled by a secondary modal dialog, the edit component is a button.

		if (parameter.parameter.isTable) {

			JButton openTableButton;
			if (canEdit) {
				openTableButton = new JButton("Edit");
			} else {
				openTableButton = new JButton("View");
			}
			theComponent = openTableButton;

			tableEditor = new TableParameterEditor(parent);

			openTableButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					AppController.showWindow(tableEditor);
				}
			});

		// Set up editing component for a non-table parameter.  The validity checking done by the listeners is primary,
		// there is no isDataValid() in the edit data class; existing values from the database are presumed valid.

		} else {

			switch (parameter.parameter.type) {

				// A string is edited in a text field.  The only special processing is to trim whitespace.

				case Parameter.TYPE_STRING:
				default: {

					theField = new JTextField(10);
					theComponent = theField;
					AppController.fixKeyBindings(theField);
					theField.setText(parameter.value[valueIndex]);

					theField.addActionListener(new ActionListener() {
						public void actionPerformed(ActionEvent theEvent) {
							JTextField field = (JTextField)editComponent;
							if (parent.blockActions()) {
								if (canEdit) {
									parameter.value[valueIndex] = field.getText().trim();
									setDefaultButton.setEnabled(!parameter.value[valueIndex].equals(
										parameter.parameter.defaultValue[valueIndex]));
								}
								parent.blockActionsEnd();
							}
							field.setText(parameter.value[valueIndex]);
						}
					});
					theField.addFocusListener(new FocusAdapter() {
						public void focusGained(FocusEvent theEvent) {
							parent.setCurrentField((JTextField)editComponent);
						}
						public void focusLost(FocusEvent theEvent) {
							if (!theEvent.isTemporary()) {
								((JTextField)editComponent).postActionEvent();
							}
						}
					});

					break;
				}

				// Integer also edited in a text field, but action listener confirms a valid number and checks range.

				case Parameter.TYPE_INTEGER: {

					theField = new JTextField(10);
					theComponent = theField;
					AppController.fixKeyBindings(theField);
					theField.setText(parameter.value[valueIndex]);

					theField.addActionListener(new ActionListener() {
						public void actionPerformed(ActionEvent theEvent) {
							JTextField field = (JTextField)editComponent;
							if (parent.blockActions()) {
								String title = "Edit Parameter";
								if (canEdit) {
									String str = field.getText().trim();
									if (str.length() > 0) {
										try {
											int i = Integer.parseInt(str);
											if ((i < parameter.parameter.minIntegerValue) ||
													(i > parameter.parameter.maxIntegerValue)) {
												errorReporter.reportValidationError(title,
													"The value must be in the range " +
													parameter.parameter.minIntegerValue + " to " +
													parameter.parameter.maxIntegerValue + ".");
											} else {
												parameter.integerValue[valueIndex] = i;
												parameter.value[valueIndex] = String.valueOf(i);
											}
										} catch (NumberFormatException ne) {
											errorReporter.reportValidationError(title, "The value must be a number.");
										}
									}
									setDefaultButton.setEnabled(!parameter.value[valueIndex].equals(
										parameter.parameter.defaultValue[valueIndex]));
								}
								parent.blockActionsEnd();
							}
							field.setText(parameter.value[valueIndex]);
						}
					});
					theField.addFocusListener(new FocusAdapter() {
						public void focusGained(FocusEvent theEvent) {
							parent.setCurrentField((JTextField)editComponent);
						}
						public void focusLost(FocusEvent theEvent) {
							if (!theEvent.isTemporary()) {
								((JTextField)editComponent).postActionEvent();
							}
						}
					});

					break;
				}

				// Decimal value just like integer.

				case Parameter.TYPE_DECIMAL: {

					theField = new JTextField(10);
					theComponent = theField;
					AppController.fixKeyBindings(theField);
					theField.setText(parameter.value[valueIndex]);

					theField.addActionListener(new ActionListener() {
						public void actionPerformed(ActionEvent theEvent) {
							JTextField field = (JTextField)editComponent;
							if (parent.blockActions()) {
								String title = "Edit Parameter";
								if (canEdit) {
									String str = field.getText().trim();
									if (str.length() > 0) {
										try {
											double d = Double.parseDouble(str);
											if ((d < parameter.parameter.minDecimalValue) ||
													(d > parameter.parameter.maxDecimalValue)) {
												errorReporter.reportValidationError(title,
													"The value must be in the range " +
													parameter.parameter.minDecimalValue + " to " +
													parameter.parameter.maxDecimalValue + ".");
											} else {
												parameter.decimalValue[valueIndex] = d;
												parameter.value[valueIndex] = doubleFormatter.format(d);
											}
										} catch (NumberFormatException ne) {
											errorReporter.reportValidationError(title, "The value must be a number.");
										}
									}
									setDefaultButton.setEnabled(!parameter.value[valueIndex].equals(
										parameter.parameter.defaultValue[valueIndex]));
								}
								parent.blockActionsEnd();
							}
							field.setText(parameter.value[valueIndex]);
						}
					});
					theField.addFocusListener(new FocusAdapter() {
						public void focusGained(FocusEvent theEvent) {
							parent.setCurrentField((JTextField)editComponent);
						}
						public void focusLost(FocusEvent theEvent) {
							if (!theEvent.isTemporary()) {
								((JTextField)editComponent).postActionEvent();
							}
						}
					});

					break;
				}

				// Option value is a checkbox, however the label is still separate for layout purposes so the check box
				// itself does not have any label text.  String storage of this value type is a "0" or "1".  This can
				// enable/disable a set of other parameter editors, see createEditorLayout().

				case Parameter.TYPE_OPTION: {

					JCheckBox theCheckBox = new JCheckBox();
					theCheckBox.setFocusable(false);
					theComponent = theCheckBox;
					theCheckBox.setSelected(parameter.optionValue[valueIndex]);

					theCheckBox.addActionListener(new ActionListener() {
						public void actionPerformed(ActionEvent theEvent) {
							if (parent.blockActions()) {
								if (canEdit) {
									parameter.optionValue[valueIndex] = ((JCheckBox)editComponent).isSelected();
									if (parameter.optionValue[valueIndex]) {
										parameter.value[valueIndex] = "1";
									} else {
										parameter.value[valueIndex] = "0";
									}
									setDefaultButton.setEnabled(!parameter.value[valueIndex].equals(
										parameter.parameter.defaultValue[valueIndex]));
									if (null != enablesEditors) {
										for (ParameterEditor theEditor : enablesEditors) {
											theEditor.setEnabled(parameter.optionValue[valueIndex]);
										}
									}
								} else {
									((JCheckBox)editComponent).setSelected(parameter.optionValue[valueIndex]);
								}
								parent.blockActionsEnd();
							} else {
								((JCheckBox)editComponent).setSelected(parameter.optionValue[valueIndex]);
							}
						}
					});

					break;
				}

				// Pick-from value is a pop-up menu.  Note this also sets the integerValue property; the value picked
				// is always an integer, stored as the key in a KeyedRecord item picked from the menu.

				case Parameter.TYPE_PICKFROM: {

					KeyedRecordMenu theMenu = new KeyedRecordMenu(parameter.parameter.pickfromItems);
					theComponent = theMenu;
					theMenu.setSelectedIndex(parameter.pickfromIndex[valueIndex]);

					theMenu.addActionListener(new ActionListener() {
						public void actionPerformed(ActionEvent theEvent) {
							if (parent.blockActions()) {
								if (canEdit) {
									parameter.pickfromIndex[valueIndex] =
										((KeyedRecordMenu)editComponent).getSelectedIndex();
									parameter.integerValue[valueIndex] =
										((KeyedRecordMenu)editComponent).getSelectedKey();
									parameter.value[valueIndex] = String.valueOf(parameter.integerValue[valueIndex]);
									setDefaultButton.setEnabled(!parameter.value[valueIndex].equals(
										parameter.parameter.defaultValue[valueIndex]));
								} else {
									((KeyedRecordMenu)editComponent).setSelectedIndex(
										parameter.pickfromIndex[valueIndex]);
								}
								parent.blockActionsEnd();
							} else {
								((KeyedRecordMenu)editComponent).setSelectedIndex(parameter.pickfromIndex[valueIndex]);
							}
						}
					});

					break;
				}

				// Date is handled by DateSelectionPanel, see that for details.

				case Parameter.TYPE_DATE: {

					DateSelectionPanel thePanel = new DateSelectionPanel(parent, false, false, new Runnable() {
						public void run() {
							if (parent.blockActions()) {
								if (canEdit) {
									parameter.dateValue[valueIndex] = ((DateSelectionPanel)editComponent).getDate();
									if (null != parameter.dateValue[valueIndex]) {
										parameter.value[valueIndex] =
											AppCore.formatDate(parameter.dateValue[valueIndex]);
									} else {
										parameter.value[valueIndex] = "";
									}
									setDefaultButton.setEnabled(!parameter.value[valueIndex].equals(
										parameter.parameter.defaultValue[valueIndex]));
								}
								parent.blockActionsEnd();
							} else {
								((DateSelectionPanel)editComponent).setDate(parameter.dateValue[valueIndex]);
							}
						}
					});
					theComponent = thePanel;
					thePanel.setFutureAllowed(true);
					thePanel.setDate(parameter.dateValue[valueIndex]);

					break;
				}
			}
		}

		// Set the description as the tool-tip pop-up text.

		theComponent.setToolTipText(parameter.parameter.description);

		// Finalize the components.  The label is highlighted if the parameter had defaults applied when loaded, that
		// is assumed to mean this is a new parameter added to the root database since this study (or it's template)
		// was last saved.  Those are emphasized so the user can look to see if the default is appropriate.  However
		// don't do this for a scenario parameter; all of those are always "new" from the point of view of a newly-
		// created scenario, as the user would expect them to be in any case, so the highlighting serves no purpose.

		label = new JLabel(parameter.parameter.name);
		if (parameter.parameter.defaultsApplied && !parameter.parameter.isScenario) {
			label.setForeground(Color.RED.darker());
		}

		editComponent = theComponent;

		unitsLabel = new JLabel(parameter.parameter.units);

		setDefaultButton = new JButton("Revert");
		setDefaultButton.setFocusable(false);
		setDefaultButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				setDefaultValue(false);
			}
		});

		// If the parameter has more than one value, create a menu to select the value being edited.  All values are
		// always the same type.  The list of value names describes to the user what each value represents.  Note there
		// is no enabling/disabling check for an option parameter; an enabling parameter can only be single-valued.

		if (parameter.parameter.valueCount > 1) {
			valueMenu = new JComboBox<String>(parameter.parameter.valueName);
			valueMenu.setFocusable(false);
			valueMenu.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (parent.blockActions()) {
						int newIndex = valueMenu.getSelectedIndex();
						if (newIndex != valueIndex) {
							if (canEdit && (editComponent instanceof JTextField)) {
								errorReporter.clearErrors();
								((JTextField)editComponent).postActionEvent();
								if (errorReporter.hasErrors()) {
									valueMenu.setSelectedIndex(valueIndex);
									parent.blockActionsEnd();
									return;
								}
							}
							valueIndex = newIndex;
							if (!parameter.parameter.isTable) {
								switch (parameter.parameter.type) {
									case Parameter.TYPE_STRING:
									case Parameter.TYPE_INTEGER:
									case Parameter.TYPE_DECIMAL:
									default: {
										((JTextField)editComponent).setText(parameter.value[valueIndex]);
										break;
									}
									case Parameter.TYPE_OPTION: {
										((JCheckBox)editComponent).setSelected(parameter.optionValue[valueIndex]);
										break;
									}
									case Parameter.TYPE_PICKFROM: {
										((KeyedRecordMenu)editComponent).
											setSelectedIndex(parameter.pickfromIndex[valueIndex]);
										break;
									}
								}
							}
							if (canEdit) {
								setDefaultButton.setEnabled(!parameter.value[valueIndex].equals(
									parameter.parameter.defaultValue[valueIndex]));
							} else {
								setDefaultButton.setEnabled(false);
							}
						}
						parent.blockActionsEnd();
					}
				}
			});
		} else {
			valueMenu = null;
		}

		setEnabled(true);

		parent.blockActionsEnd();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// There is a distinction between disabled and non-editable, see AppController.setComponentEnabled(), so it is not
	// redundant to enable/disable the edit component both directly and with that method.

	public void setEnabled(boolean enable) {

		if (enable) {

			label.setEnabled(true);
			editComponent.setEnabled(true);
			unitsLabel.setEnabled(true);
			if (canEdit) {
				if (!parameter.parameter.isTable) {
					AppController.setComponentEnabled(editComponent, true);
				}
				setDefaultButton.setEnabled(!parameter.value[valueIndex].equals(
					parameter.parameter.defaultValue[valueIndex]));
			} else {
				if (!parameter.parameter.isTable) {
					AppController.setComponentEnabled(editComponent, false);
				}
				setDefaultButton.setEnabled(false);
			}
			if (null != valueMenu) {
				valueMenu.setEnabled(true);
			}

		} else {

			label.setEnabled(false);
			editComponent.setEnabled(false);
			unitsLabel.setEnabled(false);
			setDefaultButton.setEnabled(false);
			if (null != valueMenu) {
				valueMenu.setEnabled(false);
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Reset the value to default.  If argument is false reset only the current value index, if true reset all.

	public void setDefaultValue(boolean resetAll) {

		if (!canEdit) {
			return;
		}

		parent.blockActionsStart();

		if (resetAll) {
			for (int theIndex = 0; theIndex < parameter.parameter.valueCount; theIndex++) {
				parameter.setDefaultValue(theIndex);
			}
		} else {
			parameter.setDefaultValue(valueIndex);
		}

		if (!parameter.parameter.isTable) {

			switch (parameter.parameter.type) {

				case Parameter.TYPE_STRING:
				case Parameter.TYPE_INTEGER:
				case Parameter.TYPE_DECIMAL: {
					((JTextField)editComponent).setText(parameter.value[valueIndex]);
					break;
				}

				case Parameter.TYPE_OPTION: {
					((JCheckBox)editComponent).setSelected(parameter.optionValue[valueIndex]);
					if (null != enablesEditors) {
						for (ParameterEditor theEditor : enablesEditors) {
							theEditor.setEnabled(parameter.optionValue[valueIndex]);
						}
					}
					break;
				}

				case Parameter.TYPE_PICKFROM: {
					((KeyedRecordMenu)editComponent).setSelectedIndex(parameter.pickfromIndex[valueIndex]);
					break;
				}

				case Parameter.TYPE_DATE: {
					((DateSelectionPanel)editComponent).setDate(parameter.dateValue[valueIndex]);
				}
			}
		}

		setDefaultButton.setEnabled(false);

		parent.blockActionsEnd();
	}


	//=================================================================================================================
	// Dialog to edit/view a table parameter.  Most of the work is in the table model class.

	private class TableParameterEditor extends AppDialog {

		private TableParameterModel model;
		private JTable table;

		private JButton okButton;
		private JButton cancelButton;


		//-------------------------------------------------------------------------------------------------------------

		private TableParameterEditor(AppEditor theParent) {

			super(theParent, parameter.parameter.name, Dialog.ModalityType.APPLICATION_MODAL);

			setDisposeOnClose(false);

			// Create the model and table.

			model = new TableParameterModel();
			table = model.createTable();

			// Buttons.

			if (canEdit) {

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

			} else {

				okButton = new JButton("Close");
				okButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doCancel();
					}
				});
			}

			// Do the layout.

			JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
			if (canEdit) {
				buttonPanel.add(cancelButton);
			}
			buttonPanel.add(okButton);

			Container cp = getContentPane();
			cp.setLayout(new BorderLayout());
			cp.add(AppController.createScrollPane(table, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
				JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED), BorderLayout.CENTER);
			cp.add(buttonPanel, BorderLayout.SOUTH);

			getRootPane().setDefaultButton(okButton);

			pack();
			setMinimumSize(getSize());
			setResizable(true);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doOK() {

			if (table.isEditing()) {
				if (!table.getCellEditor().stopCellEditing()) {
					return;
				}
			}
			model.save();

			blockActionsSet();
			AppController.hideWindow(this);
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doCancel() {

			if (windowShouldClose()) {
				AppController.hideWindow(this);
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		public void windowWillOpen() {

			model.load();
			setDocumentName(parameter.parameter.valueName[valueIndex]);
			setLocationRelativeTo(editComponent);
			blockActionsClear();
		}


		//-------------------------------------------------------------------------------------------------------------

		public boolean windowShouldClose() {

			if (table.isEditing()) {
				table.getCellEditor().cancelCellEditing();
			}

			blockActionsSet();
			return true;
		}
	}


	//=================================================================================================================
	// Table model for a table parameter editor.

	private class TableParameterModel extends AbstractTableModel {

		private int rowCount;
		private int columnCount;

		private boolean isDecimal;

		private int[][] integerValues;
		private double[][] decimalValues;

		private boolean didEdit;


		//-------------------------------------------------------------------------------------------------------------

		private TableParameterModel() {

			rowCount = parameter.parameter.tableRowLabels.length;
			columnCount = parameter.parameter.tableColumnLabels.length;

			isDecimal = (Parameter.TYPE_DECIMAL == parameter.parameter.type);

			if (isDecimal) {
				decimalValues = new double[rowCount][columnCount];
			} else {
				integerValues = new int[rowCount][columnCount];
			}
		}


		//-------------------------------------------------------------------------------------------------------------
		// Set up the table.  Row labels are shown in the first column.

		private JTable createTable() {

			JTable theTable = new JTable(this);
			AppController.configureTable(theTable);

			theTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

			DefaultTableCellRenderer theRend = new DefaultTableCellRenderer() {
				public Component getTableCellRendererComponent(JTable t, Object o, boolean s, boolean f, int r, int c) {
					JLabel comp = (JLabel)super.getTableCellRendererComponent(t, o, s, f, r, c);
					comp.setHorizontalAlignment(SwingConstants.RIGHT);
					return comp;
				}
			};

			TableColumnModel theColumns = theTable.getColumnModel();
			TableColumn theColumn;
			for (int colIndex = 0; colIndex < theColumns.getColumnCount(); colIndex++) {
				theColumn = theColumns.getColumn(colIndex);
				theColumn.setHeaderRenderer(theRend);
				theColumn.setPreferredWidth(AppController.textFieldWidth[4]);
			}

			int width = theTable.getPreferredSize().width;
			if (width > 500) {
				width = 500;
			}
			int height = theTable.getRowHeight() * rowCount;
			if (height > 400) {
				height = 400;
			}
			theTable.setPreferredScrollableViewportSize(new Dimension(width, height));

			return theTable;
		}


		//-------------------------------------------------------------------------------------------------------------
		// Load values into local array to start editing.

		private void load() {

			int row, column, tableIndex;
			for (row = 0, tableIndex = 0; row < rowCount; row++) {
				for (column = 0; column < columnCount; column++, tableIndex++) {
					if (isDecimal) {
						decimalValues[row][column] = parameter.decimalTableValue[valueIndex][tableIndex];
					} else {
						integerValues[row][column] = parameter.integerTableValue[valueIndex][tableIndex];
					}
				}
			}

			fireTableDataChanged();

			didEdit = false;
		}


		//-------------------------------------------------------------------------------------------------------------
		// Save edited values back to the model object.  Validity was checked in setValueAt().

		private void save() {

			if (!canEdit || !didEdit) {
				return;
			}

			StringBuilder newValue = new StringBuilder();
			String sep = "";

			int row, column, tableIndex;
			for (row = 0, tableIndex = 0; row < rowCount; row++) {
				for (column = 0; column < columnCount; column++, tableIndex++) {
					newValue.append(sep);
					sep = ",";
					if (isDecimal) {
						parameter.decimalTableValue[valueIndex][tableIndex] = decimalValues[row][column];
						newValue.append(doubleFormatter.format(decimalValues[row][column]));
					} else {
						parameter.integerTableValue[valueIndex][tableIndex] = integerValues[row][column];
						newValue.append(String.valueOf(integerValues[row][column]));
					}
				}
			}

			parameter.value[valueIndex] = newValue.toString();
			setDefaultButton.setEnabled(!parameter.value[valueIndex].equals(
				parameter.parameter.defaultValue[valueIndex]));

			didEdit = false;
		}


		//-------------------------------------------------------------------------------------------------------------

		public int getColumnCount() {

			return columnCount + 1;
		}


		//-------------------------------------------------------------------------------------------------------------

		public String getColumnName(int columnIndex) {

			if (0 == columnIndex) {
				return "";
			}
			return parameter.parameter.tableColumnLabels[columnIndex - 1];
		}


		//-------------------------------------------------------------------------------------------------------------

		public Class getColumnClass(int columnIndex) {

			if (0 == columnIndex) {
				return String.class;
			}
			if (isDecimal) {
				return Double.class;
			} else {
				return Integer.class;
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		public int getRowCount() {

			return rowCount;
		}


		//-------------------------------------------------------------------------------------------------------------

		public boolean isCellEditable(int rowIndex, int columnIndex) {

			if (!canEdit) {
				return false;
			}

			if (0 == columnIndex) {
				return false;
			}
			return true;
		}


		//-------------------------------------------------------------------------------------------------------------

		public Object getValueAt(int rowIndex, int columnIndex) {

			if (0 == columnIndex) {
				return parameter.parameter.tableRowLabels[rowIndex];
			}
			if (isDecimal) {
				return Double.valueOf(decimalValues[rowIndex][columnIndex - 1]);
			} else {
				return Integer.valueOf(integerValues[rowIndex][columnIndex - 1]);
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		public void setValueAt(Object value, int rowIndex, int columnIndex) {

			if (!canEdit || (0 == columnIndex)) {
				return;
			}

			if (isDecimal) {

				double d = ((Double)value).doubleValue();
				if ((d < parameter.parameter.minDecimalValue) || (d > parameter.parameter.maxDecimalValue)) {
					errorReporter.reportValidationError("Edit Parameter", "The value must be in the range " +
						parameter.parameter.minDecimalValue + " to " + parameter.parameter.maxDecimalValue + ".");
				} else {
					if (d != decimalValues[rowIndex][columnIndex - 1]) {
						decimalValues[rowIndex][columnIndex - 1] = d;
						didEdit = true;
					}
				}

			} else {

				int i = ((Integer)value).intValue();
				if ((i < parameter.parameter.minIntegerValue) || (i > parameter.parameter.maxIntegerValue)) {
					errorReporter.reportValidationError("Edit Parameter", "The value must be in the range " +
						parameter.parameter.minIntegerValue + " to " + parameter.parameter.maxIntegerValue + ".");
				} else {
					if (i != integerValues[rowIndex][columnIndex - 1]) {
						integerValues[rowIndex][columnIndex - 1] = i;
						didEdit = true;
					}
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create editors for a list of parameters and set up in a UI.  All parameters in the same parameter group are in
	// the same panel wrapped in a scroll pane.  If there is only one group that pane is returned, otherwise the group
	// layouts are placed in a custom panel using a pop-up menu to change the pane being displayed, see ParameterPanel
	// (the number of groups got too large to handle with a tabbed pane).  This does not assume all parameters in the
	// same group will occur in sequence, but it ensures the group selection menu will be ordered according to the
	// sequence in which each group is first encountered.  If the input list is empty this will return an empty panel.
	// The ErrorReporter is for editors to use from action handlers once the UI is live; no errors will be reported
	// under this call.  If the returnEditors argument is non-null the editors are also added to that list.

	public static JComponent createEditorLayout(AppEditor theParent, ErrorReporter theErrorReporter,
			ArrayList<ParameterEditData> theParameters, ArrayList<ParameterEditor> returnEditors) {

		if (theParameters.isEmpty()) {
			return new JPanel();
		}

		ArrayList<String> names = new ArrayList<String>();
		ArrayList<ParameterEditorGroup> groups = new ArrayList<ParameterEditorGroup>();

		int theIndex;
		ParameterEditorGroup theGroup;
		ParameterEditor theEditor;

		for (ParameterEditData theParameter : theParameters) {

			theIndex = names.indexOf(theParameter.parameter.groupName);
			if (theIndex < 0) {
				theGroup = new ParameterEditorGroup();
				names.add(theParameter.parameter.groupName);
				groups.add(theGroup);
			} else {
				theGroup = groups.get(theIndex);
			}

			theEditor = new ParameterEditor(theParent, theErrorReporter, theParameter);
			theGroup.addEditor(theEditor);

			if (null != returnEditors) {
				returnEditors.add(theEditor);
			}
		}

		if (1 == names.size()) {
			return AppController.createScrollPane(groups.get(0).panel);
		}

		return new ParameterPanel(names, groups);
	}


	//=================================================================================================================
	// Class to manage parameter editor layout, see createEditorLayout().  This is a four-column layout built with
	// GroupLayout, labels right-aligned in the first column, value menus (as they exist) in the second column, edit
	// components in the third column, and default buttons in the last column (again as they exist, scenario parameters
	// do not show that button).  To keep the layout and components from stretching unpleasantly, the panel with the
	// GroupLayout is wrapped in another panel with a default FlowLayout.

	private static class ParameterEditorGroup {

		private ParameterEditor enablingEditor;

		private JPanel panel;

		private GroupLayout layout;

		private GroupLayout.ParallelGroup labelGroup;
		private GroupLayout.ParallelGroup menuGroup;
		private GroupLayout.ParallelGroup componentGroup;
		private GroupLayout.ParallelGroup unitsLabelGroup;
		private GroupLayout.ParallelGroup buttonGroup;

		private GroupLayout.SequentialGroup verticalGroup;

		private boolean hasNewParameters;


		//-------------------------------------------------------------------------------------------------------------

		private ParameterEditorGroup() {

			JPanel innerP = new JPanel();
			layout = new GroupLayout(innerP);
			innerP.setLayout(layout);

			layout.setAutoCreateGaps(true);
			layout.setAutoCreateContainerGaps(true);

			labelGroup = layout.createParallelGroup(GroupLayout.Alignment.TRAILING);
			menuGroup = layout.createParallelGroup();
			componentGroup = layout.createParallelGroup();
			unitsLabelGroup = layout.createParallelGroup();
			buttonGroup = layout.createParallelGroup();
			layout.setHorizontalGroup(layout.createSequentialGroup().
				addGroup(labelGroup).
				addGroup(menuGroup).
				addGroup(componentGroup).
				addGroup(unitsLabelGroup).
				addGroup(buttonGroup));

			verticalGroup = layout.createSequentialGroup();
			layout.setVerticalGroup(verticalGroup);

			panel = new JPanel();
			panel.add(innerP);
		}


		//-------------------------------------------------------------------------------------------------------------
		// Add a row of components from one parameter editor.  If an option parameter is seen with the enablesGroup
		// flag set, that enables/disables all subsequent parameters in the group.

		private void addEditor(ParameterEditor theEditor) {

			if ((Parameter.TYPE_OPTION == theEditor.parameter.parameter.type) &&
					theEditor.parameter.parameter.enablesGroup) {
				enablingEditor = theEditor;
				theEditor.enablesEditors = new ArrayList<ParameterEditor>();
			} else {
				if (null != enablingEditor) {
					enablingEditor.enablesEditors.add(theEditor);
					theEditor.setEnabled(enablingEditor.parameter.optionValue[0]);
				}
			}

			labelGroup.addComponent(theEditor.label);
			componentGroup.addComponent(theEditor.editComponent);
			unitsLabelGroup.addComponent(theEditor.unitsLabel);

			if (theEditor.parameter.parameter.isScenario) {

				if (null != theEditor.valueMenu) {

					menuGroup.addComponent(theEditor.valueMenu);

					verticalGroup.addGroup(layout.createParallelGroup(GroupLayout.Alignment.BASELINE).
						addComponent(theEditor.label).
						addComponent(theEditor.valueMenu).
						addComponent(theEditor.editComponent).
						addComponent(theEditor.unitsLabel));

				} else {

					verticalGroup.addGroup(layout.createParallelGroup(GroupLayout.Alignment.BASELINE).
						addComponent(theEditor.label).
						addComponent(theEditor.editComponent).
						addComponent(theEditor.unitsLabel));
				}

			} else {

				buttonGroup.addComponent(theEditor.setDefaultButton);

				if (null != theEditor.valueMenu) {

					menuGroup.addComponent(theEditor.valueMenu);

					verticalGroup.addGroup(layout.createParallelGroup(GroupLayout.Alignment.BASELINE).
						addComponent(theEditor.label).
						addComponent(theEditor.valueMenu).
						addComponent(theEditor.editComponent).
						addComponent(theEditor.unitsLabel).
						addComponent(theEditor.setDefaultButton));

				} else {

					verticalGroup.addGroup(layout.createParallelGroup(GroupLayout.Alignment.BASELINE).
						addComponent(theEditor.label).
						addComponent(theEditor.editComponent).
						addComponent(theEditor.unitsLabel).
						addComponent(theEditor.setDefaultButton));
				}

				if (theEditor.parameter.parameter.defaultsApplied) {
					hasNewParameters = true;
				}
			}
		}
	}


	//=================================================================================================================
	// Panel subclass to hold the multi-group parameter layout using a pop-up menu to select group.

	private static class ParameterPanel extends JPanel {

		private JComboBox<String> groupMenu;
		private ArrayList<Container> groupPanes;


		//-------------------------------------------------------------------------------------------------------------

		private ParameterPanel(ArrayList<String> names, ArrayList<ParameterEditorGroup> groups) {

			groupMenu = new JComboBox<String>();
			groupMenu.setFocusable(false);
			groupMenu.setMaximumRowCount(names.size());

			JPanel menuPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
			menuPanel.add(groupMenu);

			groupPanes = new ArrayList<Container>();

			for (int theIndex = 0; theIndex < names.size(); theIndex++) {
				groupMenu.addItem(names.get(theIndex));
				groupPanes.add(AppController.createScrollPane(groups.get(theIndex).panel));
			}

			groupMenu.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					remove(1);
					add(groupPanes.get(groupMenu.getSelectedIndex()), BorderLayout.CENTER);
					revalidate();
					repaint();
				}
			});

			setLayout(new BorderLayout());
			add(menuPanel, BorderLayout.NORTH);
			add(groupPanes.get(0), BorderLayout.CENTER);
		}
	}
}
