//
//  PatternEditor.java
//  TVStudy
//
//  Copyright (c) 2012-2018 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui.editor;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.editdata.*;
import gov.fcc.tvstudy.core.geo.*;
import gov.fcc.tvstudy.gui.*;

import java.util.*;
import java.util.regex.*;
import java.sql.*;
import java.io.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.text.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.table.*;
import javax.swing.filechooser.*;


//=====================================================================================================================
// Pattern editor dialog UI for all types of pattern data.  Antenna orientation and ID are used only in horizontal
// antenna mode, they are set and read directly by the parent.  If the pattern is edited in any way antenna ID is set
// null.  When a pattern is loaded from external station data (see SearchPanel), the new ID is set.

public class PatternEditor extends AppDialog implements ExtDbListener {

	public static final String WINDOW_TITLE = "Antenna Pattern";

	private AntPattern pattern;
	private int patternType;

	public double antennaOrientation;
	public String antennaID;

	// UI fields.

	private JTextField patternNameField;

	private JTextField antennaOrientationField;
	private JCheckBox rotateHorizontalPlotCheckBox;

	private JTextField antennaGainField;

	private JComboBox<Double> sliceMenu;
	private boolean ignoreSliceChange;
	private Double currentSliceValue;

	private PatternTableModel patternModel;

	private PatternPlotPanel patternPlotPanel;

	private SearchPanel patternSearchPanel;

	private JTabbedPane tabPane;

	// Buttons.

	private JButton changeSliceValueButton;
	private JButton removeSliceButton;

	private JButton insertPointButton;
	private JButton deletePointButton;
	private JButton clearPatternButton;
	private JButton exportButton;

	// Flags.

	private boolean canEdit;
	private boolean didEdit;

	// Disambiguation.

	private PatternEditor outerThis = this;


	//-----------------------------------------------------------------------------------------------------------------
	// The AntPattern model object will be modified directly if editing is allowed, so always make a copy.  If there
	// is no existing pattern and the editor is being opened to create or add one, the model object is null and the
	// pattern type is set by the argument; otherwise the pattern type comes from the model object.

	public PatternEditor(AppEditor theParent, AntPattern thePat, int thePatType, boolean theCanEdit) {

		super(theParent, WINDOW_TITLE, Dialog.ModalityType.MODELESS);

		if (null != thePat) {
			pattern = thePat.copy();
			patternType = pattern.type;
		} else {
			patternType = thePatType;
			pattern = new AntPattern(getDbID(), patternType, "");
		}

		canEdit = theCanEdit;
		
		// Create the UI components.  Field for the name.

		patternNameField = new JTextField(30);
		AppController.fixKeyBindings(patternNameField);

		JPanel namePanel = new JPanel();
		namePanel.setBorder(BorderFactory.createTitledBorder("Antenna Name"));
		namePanel.add(patternNameField);

		AppController.setComponentEnabled(patternNameField, canEdit);

		// Field for editing the horizontal pattern orientation when in horizontal mode.  Also a check-box here will
		// rotate the pattern plot according to the orientation.

		JPanel horizontalPanel = null;
		JPanel gainPanel = null;
		JPanel matrixPanel = null;

		if (AntPattern.PATTERN_TYPE_HORIZONTAL == patternType) {

			antennaOrientationField = new JTextField(7);
			AppController.fixKeyBindings(antennaOrientationField);

			AppController.setComponentEnabled(antennaOrientationField, canEdit);

			antennaOrientationField.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					if (blockActions()) {
						if (canEdit) {
							String title = "Edit Antenna Orientation";
							String str = antennaOrientationField.getText().trim();
							if (str.length() > 0) {
								double d = antennaOrientation;
								try {
									d = Math.IEEEremainder(Double.parseDouble(str), 360.);
									if (d < 0.) d += 360.;
								} catch (NumberFormatException ne) {
									errorReporter.reportValidationError(title, "The orientation must be a number.");
								}
								if (d != antennaOrientation) {
									antennaOrientation = d;
									if (rotateHorizontalPlotCheckBox.isSelected()) {
										patternPlotPanel.repaint();
									}
								}
							}
						}
						antennaOrientationField.setText(AppCore.formatAzimuth(antennaOrientation));
						blockActionsEnd();
					}
				}
			});

			antennaOrientationField.addFocusListener(new FocusAdapter() {
				public void focusGained(FocusEvent theEvent) {
					setCurrentField(antennaOrientationField);
				}
				public void focusLost(FocusEvent theEvent) {
					if (!theEvent.isTemporary()) {
						antennaOrientationField.postActionEvent();
					}
				}
			});

			rotateHorizontalPlotCheckBox = new JCheckBox("Rotate pattern plot");
			rotateHorizontalPlotCheckBox.setFocusable(false);

			rotateHorizontalPlotCheckBox.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					patternPlotPanel.repaint();
				}
			});

			horizontalPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
			horizontalPanel.setBorder(BorderFactory.createTitledBorder("Antenna Orientation"));
			horizontalPanel.add(antennaOrientationField);
			horizontalPanel.add(rotateHorizontalPlotCheckBox);

		} else {

			// Gain field appears in receive mode only.

			if (AntPattern.PATTERN_TYPE_RECEIVE == patternType) {

				antennaGainField = new JTextField(7);
				AppController.fixKeyBindings(antennaGainField);

				AppController.setComponentEnabled(antennaGainField, canEdit);

				antennaGainField.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							if (canEdit) {
								String title = "Edit Antenna Gain";
								String str = antennaGainField.getText().trim();
								if (str.length() > 0) {
									double d = pattern.gain;
									try {
										d = Double.parseDouble(str);
									} catch (NumberFormatException ne) {
										errorReporter.reportValidationError(title, "The gain must be a number.");
									}
									if (d != pattern.gain) {
										if ((d < AntPattern.GAIN_MIN) || (d > AntPattern.GAIN_MAX)) {
											errorReporter.reportValidationError(title,
												"The gain must be in the range " + AntPattern.GAIN_MIN + " to " +
													AntPattern.GAIN_MAX + ".");
										} else {
											pattern.gain = d;
											didEdit = true;
										}
									}
								}
							}
							antennaGainField.setText(AppCore.formatDecimal(pattern.gain, 2));
							blockActionsEnd();
						}
					}
				});

				antennaGainField.addFocusListener(new FocusAdapter() {
					public void focusGained(FocusEvent theEvent) {
						setCurrentField(antennaGainField);
					}
					public void focusLost(FocusEvent theEvent) {
						if (!theEvent.isTemporary()) {
							antennaGainField.postActionEvent();
						}
					}
				});

				gainPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
				gainPanel.setBorder(BorderFactory.createTitledBorder("Antenna Gain, dBd"));
				gainPanel.add(antennaGainField);
			}

			// Matrix-related controls appear in vertical or receive pattern modes.
	
			sliceMenu = new JComboBox<Double>();
			sliceMenu.setFocusable(false);
			sliceMenu.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doChangeSlice();
				}
			});

			matrixPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
			if (AntPattern.PATTERN_TYPE_VERTICAL != patternType) {
				matrixPanel.setBorder(BorderFactory.createTitledBorder("Frequency, MHz"));
			} else {
				matrixPanel.setBorder(BorderFactory.createTitledBorder("Azimuth"));
			}
			matrixPanel.add(sliceMenu);

			if (canEdit) {

				changeSliceValueButton = new JButton("Change");
				changeSliceValueButton.setFocusable(false);
				changeSliceValueButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doChangeSliceValue();
					}
				});

				JButton addSliceButton = new JButton("Add");
				addSliceButton.setFocusable(false);
				addSliceButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doAddSlice();
					}
				});

				removeSliceButton = new JButton("Remove");
				removeSliceButton.setFocusable(false);
				removeSliceButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doRemoveSlice();
					}
				});

				matrixPanel.add(changeSliceValueButton);
				matrixPanel.add(addSliceButton);
				matrixPanel.add(removeSliceButton);
			}
		}

		// Table for the points.

		patternModel = new PatternTableModel();

		patternModel.table.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
			public void valueChanged(ListSelectionEvent theEvent) {
				if (canEdit) {
					if (patternModel.getSelectedRow() >= 0) {
						insertPointButton.setEnabled(true);
						deletePointButton.setEnabled(true);
					} else {
						insertPointButton.setEnabled(false);
						deletePointButton.setEnabled(false);
					}
				}
			}
		});

		JPanel patternEditPanel = new JPanel(new BorderLayout());
		patternEditPanel.add(AppController.createScrollPane(patternModel.table), BorderLayout.CENTER);

		// Don't move this line!  Table must be in a container before being enabled/disabled.

		AppController.setComponentEnabled(patternModel.table, canEdit);

		// Plot panel.

		patternPlotPanel = new PatternPlotPanel();

		// Search panel appears only if pattern is editable.

		if (canEdit) {
			patternSearchPanel = new SearchPanel();
		}

		// Buttons.

		JButton addPointButton = null;
		JButton importButton = null;
		JButton applyButton = null;

		if (canEdit) {

			addPointButton = new JButton("Add");
			addPointButton.setFocusable(false);
			addPointButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doAddPoint();
				}
			});

			insertPointButton = new JButton("Insert");
			insertPointButton.setFocusable(false);
			insertPointButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doInsertPoint();
				}
			});

			deletePointButton = new JButton("Delete");
			deletePointButton.setFocusable(false);
			deletePointButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doDeletePoint();
				}
			});

			if (AntPattern.PATTERN_TYPE_RECEIVE != patternType) {
				clearPatternButton = new JButton("Clear");
				clearPatternButton.setFocusable(false);
				clearPatternButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						doClearPattern();
					}
				});
			}

			importButton = new JButton("Import");
			importButton.setFocusable(false);
			importButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doImport();
				}
			});

			if (AntPattern.PATTERN_TYPE_RECEIVE == patternType) {
				applyButton = new JButton("Save");
			} else {
				applyButton = new JButton("OK");
			}
			applyButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doApply();
				}
			});
		}

		exportButton = new JButton("Export");
		exportButton.setFocusable(false);
		exportButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				doExport();
			}
		});

		JButton closeButton = null;
		if (canEdit) {
			closeButton = new JButton("Cancel");
		} else {
			closeButton = new JButton("Close");
		}
		closeButton.setFocusable(false);
		closeButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				cancel();
			}
		});

		// Do the layout.  Note even if editable the search tab won't be added until windowWillOpen(), it may not
		// appear due to errors loading the data set list.

		JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		topPanel.add(namePanel);

		if (canEdit) {
			JPanel patButPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
			patButPanel.add(addPointButton);
			patButPanel.add(insertPointButton);
			patButPanel.add(deletePointButton);
			patternEditPanel.add(patButPanel, BorderLayout.SOUTH);
		}

		tabPane = new JTabbedPane();
		tabPane.addTab("Data", patternEditPanel);
		tabPane.addTab("Plot", patternPlotPanel);

		Box extrasBox = Box.createVerticalBox();
		if (null != horizontalPanel) {
			extrasBox.add(horizontalPanel);
		}
		if (null != gainPanel) {
			extrasBox.add(gainPanel);
		}
		if (null != matrixPanel) {
			extrasBox.add(matrixPanel);
		}

		JPanel midTopPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		midTopPanel.add(extrasBox);

		JPanel midPanel = new JPanel(new BorderLayout());
		midPanel.add(midTopPanel, BorderLayout.NORTH);
		midPanel.add(tabPane, BorderLayout.CENTER);

		JPanel butLeftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		if (null != clearPatternButton) {
			butLeftPanel.add(clearPatternButton);
		}
		if (null != importButton) {
			butLeftPanel.add(importButton);
		}
		butLeftPanel.add(exportButton);

		JPanel butRightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		butRightPanel.add(closeButton);
		if (canEdit) {
			butRightPanel.add(applyButton);
		}

		JPanel bottomPanel = new JPanel();
		bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.X_AXIS));
		bottomPanel.add(butLeftPanel);
		bottomPanel.add(butRightPanel);

		Container cp = getContentPane();
		cp.setLayout(new BorderLayout());
		cp.add(topPanel, BorderLayout.NORTH);
		cp.add(midPanel, BorderLayout.CENTER);
		cp.add(bottomPanel, BorderLayout.SOUTH);

		if (canEdit) {
			getRootPane().setDefaultButton(applyButton);
		}

		pack();

		Dimension theSize = getSize();
		theSize.height = 650;
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
	// An empty pattern state is valid, that returns null meaning no pattern (omnidirectional, generic, etc.).

	public AntPattern getPattern() {

		if (pattern.isSimple() && patternModel.points.isEmpty()) {
			return null;
		}

		return pattern;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean didEdit() {

		return didEdit;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Clear the pattern to use omni/generic.  Not allowed for a receive pattern.

	private void doClearPattern() {

		if (!canEdit || (AntPattern.PATTERN_TYPE_RECEIVE == patternType)) {
			return;
		}

		pattern = new AntPattern(getDbID(), patternType, "");
		patternNameField.setText("");
		antennaID = null;

		updateState(null);
		didEdit = true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Change the slice being edited in a matrix pattern.  Not allowed to change until the current slice is valid.

	private void doChangeSlice() {

		if ((null == sliceMenu) || (null == currentSliceValue) || ignoreSliceChange || pattern.isSimple()) {
			return;
		}

		if (!patternModel.checkPattern()) {
			ignoreSliceChange = true;
			sliceMenu.setSelectedItem(currentSliceValue);
			ignoreSliceChange = false;
			return;
		}

		updateState((Double)sliceMenu.getSelectedItem());
	}

		
	//-----------------------------------------------------------------------------------------------------------------
	// Prompts for a new value for the current slice, it must be valid and not already exist in the pattern.

	private void doChangeSliceValue() {

		if (!canEdit || (null == currentSliceValue) || pattern.isSimple()) {
			return;
		}

		String uclbl, lclbl;
		double minVal, maxVal;
		if (AntPattern.PATTERN_TYPE_VERTICAL == patternType) {
			uclbl = "Azimuth";
			lclbl = "azimuth";
			minVal = AntPattern.AZIMUTH_MIN;
			maxVal = AntPattern.AZIMUTH_MAX;
		} else {
			uclbl = "Frequency";
			lclbl = "frequency";
			minVal = AntPattern.FREQUENCY_MIN;
			maxVal = AntPattern.FREQUENCY_MAX;
		}

		String str;
		double val;
		Double newValue = null;

		do {

			str = (String)JOptionPane.showInputDialog(this, "New " + lclbl, "Change " + uclbl,
				JOptionPane.QUESTION_MESSAGE, null, null, AppCore.formatDecimal(currentSliceValue.doubleValue(), 2));
			if (null == str) {
				return;
			}
			str = str.trim();

			if (str.length() > 0) {
				try {
					val = Double.parseDouble(str);
					if ((val < minVal) || (val > maxVal)) {
						errorReporter.reportWarning("The " + lclbl + " must be in the range " + minVal + " to " +
							maxVal + ".");
					} else {
						newValue = Double.valueOf(val);
						if (newValue.equals(currentSliceValue)) {
							return;
						}
						if (pattern.containsSlice(newValue)) {
							errorReporter.reportWarning("A pattern with that " + lclbl + " already exists.");
							newValue = null;
						}
					}
				} catch (NumberFormatException ne) {
					errorReporter.reportWarning("The " + lclbl + " must be a number.");
				}
			}

		} while (null == newValue);

		if (!pattern.changeSliceValue(currentSliceValue, newValue)) {
			return;
		}

		updateState(newValue);
		didEdit = true;
	}

		
	//-----------------------------------------------------------------------------------------------------------------
	// If the pattern is currently simple, this prompts for two new slice values, one for the current simple pattern
	// when it converts to a slice, and a second for the new slice.  Those must both be valid and different.  If the
	// pattern is already a matrix prompt for a new slice value, that must be valid and not already exist.  This is
	// not allowed unless the current pattern is valid.  This is never allowed in horizontal mode.

	private void doAddSlice() {

		if (!canEdit || (AntPattern.PATTERN_TYPE_HORIZONTAL == patternType) || !patternModel.checkPattern()) {
			return;
		}

		String uclbl, lclbl;
		double minVal, maxVal;
		if (AntPattern.PATTERN_TYPE_VERTICAL != patternType) {
			uclbl = "Frequency";
			lclbl = "frequency";
			minVal = AntPattern.FREQUENCY_MIN;
			maxVal = AntPattern.FREQUENCY_MAX;
		} else {
			uclbl = "Azimuth";
			lclbl = "azimuth";
			minVal = AntPattern.AZIMUTH_MIN;
			maxVal = AntPattern.AZIMUTH_MAX;
		}

		String str;
		double val;
		Double firstValue = null, newValue = null;

		if (pattern.isSimple()) {

			do {

				str = JOptionPane.showInputDialog(this, uclbl + " for existing pattern", "Convert To Matrix",
					JOptionPane.QUESTION_MESSAGE);
				if (null == str) {
					return;
				}
				str = str.trim();

				if (str.length() > 0) {
					try {
						val = Double.parseDouble(str);
						if ((val < minVal) || (val > maxVal)) {
							errorReporter.reportWarning("The " + lclbl + " must be in the range " + minVal + " to " +
								maxVal + ".");
						} else {
							firstValue = Double.valueOf(val);
						}
					} catch (NumberFormatException ne) {
						errorReporter.reportWarning("The " + lclbl + " must be a number.");
					}
				}

			} while (null == firstValue);
		}

		do {

			str = JOptionPane.showInputDialog(this, "New pattern " + lclbl, "Add Pattern",
				JOptionPane.QUESTION_MESSAGE);
			if (null == str) {
				return;
			}
			str = str.trim();

			if (str.length() > 0) {
				try {
					val = Double.parseDouble(str);
					if ((val < minVal) || (val > maxVal)) {
						errorReporter.reportWarning("The " + lclbl + " must be in the range " + minVal + " to " +
							maxVal + ".");
					} else {
						newValue = Double.valueOf(val);
						if (pattern.containsSlice(newValue) || ((null != firstValue) && newValue.equals(firstValue))) {
							errorReporter.reportWarning("A pattern with that " + lclbl + " already exists.");
							newValue = null;
						}
					}
				} catch (NumberFormatException ne) {
					errorReporter.reportWarning("The " + lclbl + " must be a number.");
				}
			}

		} while (null == newValue);

		if (pattern.isSimple()) {
			if (!pattern.convertToMatrix(firstValue, newValue)) {
				return;
			}
		} else {
			if (pattern.addSlice(newValue)) {
				return;
			}
		}

		updateState(newValue);
		didEdit = true;
	}

		
	//-----------------------------------------------------------------------------------------------------------------
	// Removing the next-to-last slice turns the pattern back into a simple pattern.

	private void doRemoveSlice() {

		if (!canEdit || (null == currentSliceValue) || pattern.isSimple()) {
			return;
		}

		if (!pattern.removeSlice(currentSliceValue)) {
			return;
		}

		updateState(null);
		didEdit = true;
	}

		
	//-----------------------------------------------------------------------------------------------------------------

	private void updateState(Double loadValue) {

		if (AntPattern.PATTERN_TYPE_HORIZONTAL == patternType) {

			if (pattern.isSimple()) {
				patternModel.setPoints(pattern.getPoints());
			}
			currentSliceValue = null;

			return;
		}

		ignoreSliceChange = true;

		sliceMenu.removeAllItems();

		if (pattern.isSimple()) {

			sliceMenu.setEnabled(false);
			if (canEdit) {
				changeSliceValueButton.setEnabled(false);
				removeSliceButton.setEnabled(false);
			}

			patternModel.setPoints(pattern.getPoints());
			currentSliceValue = null;

		} else {

			for (Double theValue : pattern.getSliceValues()) {
				sliceMenu.addItem(theValue);
			}

			if ((null != loadValue) && pattern.containsSlice(loadValue)) {
				sliceMenu.setSelectedItem(loadValue);
			} else {
				loadValue = (Double)sliceMenu.getSelectedItem();
			}

			sliceMenu.setEnabled(true);
			if (canEdit) {
				changeSliceValueButton.setEnabled(true);
				removeSliceButton.setEnabled(true);
			}

			patternModel.setPoints(pattern.getSlicePoints(loadValue));
			currentSliceValue = loadValue;
		}

		ignoreSliceChange = false;
	}


	//=================================================================================================================
	// Table model for the pattern editing table.  This object also creates and manages the table itself.

	private class PatternTableModel extends AbstractTableModel {

		private ArrayList<AntPattern.AntPoint> points;

		private JTable table;


		//-------------------------------------------------------------------------------------------------------------

		private PatternTableModel() {

			super();

			points = new ArrayList<AntPattern.AntPoint>();

			table = new JTable(this);
			AppController.configureTable(table);

			TableColumn theColumn = table.getColumn(getColumnName(0));
			theColumn.setMinWidth(AppController.textFieldWidth[4]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[6]);

			theColumn = table.getColumn(getColumnName(1));
			theColumn.setMinWidth(AppController.textFieldWidth[4]);
			theColumn.setPreferredWidth(AppController.textFieldWidth[6]);

			table.setPreferredScrollableViewportSize(new Dimension(table.getPreferredSize().width,
				(table.getRowHeight() * 30)));
		}


		//-------------------------------------------------------------------------------------------------------------
		// The points array in the AntPattern object is modified directly, as are the point objects.

		private void setPoints(ArrayList<AntPattern.AntPoint> thePoints) {

			points = thePoints;
			fireTableDataChanged();

			updateButtons();

			patternPlotPanel.repaint();
		}


		//-------------------------------------------------------------------------------------------------------------

		private void replacePoints(ArrayList<AntPattern.AntPoint> thePoints) {

			points.clear();
			points.addAll(thePoints);
			fireTableDataChanged();

			updateButtons();

			patternPlotPanel.repaint();
		}


		//-------------------------------------------------------------------------------------------------------------
		// Points are always kept in order by azimuth/depression, this returns the row at which the point is inserted,
		// and also automatically selects that row.  It will fail if the azimuth/depression already exists in the data,
		// returning -1 in that case.

		private int addPoint(AntPattern.AntPoint newPoint) {

			int rowIndex = 0;
			AntPattern.AntPoint thePoint;

			for (rowIndex = 0; rowIndex < points.size(); rowIndex++) {
				thePoint = points.get(rowIndex);
				if (newPoint.angle <= thePoint.angle) {
					if (newPoint.angle == thePoint.angle) {
						return -1;
					}
					break;
				}
			}

			points.add(rowIndex, newPoint);
			fireTableRowsInserted(rowIndex, rowIndex);

			final int moveToIndex = rowIndex;
			SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					table.setRowSelectionInterval(moveToIndex, moveToIndex);
					table.scrollRectToVisible(table.getCellRect(moveToIndex, 0, true));
				}
			});

			updateButtons();

			return rowIndex;
		}


		//-------------------------------------------------------------------------------------------------------------

		private void remove(int rowIndex) {

			points.remove(rowIndex);
			fireTableRowsDeleted(rowIndex, rowIndex);

			updateButtons();
		}


		//-------------------------------------------------------------------------------------------------------------

		private void updateButtons() {

			boolean hasPat = !points.isEmpty();
			if (null != clearPatternButton) {
				clearPatternButton.setEnabled(hasPat);
			}
			exportButton.setEnabled(hasPat);
		}


		//-------------------------------------------------------------------------------------------------------------
		// Convenience.

		private int getSelectedRow() {

			return table.getSelectedRow();
		}


		//-------------------------------------------------------------------------------------------------------------
		// Checks on pattern validity, must have a minimum number of points.  The pattern must have a 1.0 point for
		// horizontal or vertical patterns.  A receive pattern must also have a 1.0 but that will be checked by
		// isDataValid(), for a matrix receive pattern there only has to be a 1.0 in some slice not necessarily all.
		// The validity check does not enforce a 1.0 for horizontal or vertical pattern types because patterns from
		// source databases may not always pass that test, so it is enforced only when the pattern is edited.

		private boolean checkPattern() {

			errorReporter.setTitle("Verify Pattern");

			if (points.size() < AntPattern.PATTERN_REQUIRED_POINTS) {
				errorReporter.reportValidationError("Pattern must have " + AntPattern.PATTERN_REQUIRED_POINTS +
					" or more points.");
				return false;
			}

			if (AntPattern.PATTERN_TYPE_RECEIVE != patternType) {

				double patmax = 0.;
				for (AntPattern.AntPoint thePoint : points) {
					if (thePoint.relativeField > patmax) {
						patmax = thePoint.relativeField;
					}
				}

				if (patmax < 1.) {
					errorReporter.reportValidationError("Pattern must have a 1.");
					return false;
				}
			}

			return true;
		}


		//-------------------------------------------------------------------------------------------------------------

		public int getColumnCount() {

			return 2;
		}


		//-------------------------------------------------------------------------------------------------------------

		public String getColumnName(int columnIndex) {

			if (0 == columnIndex) {
				if (AntPattern.PATTERN_TYPE_VERTICAL != patternType) {
					return "Azimuth";
				} else {
					return "Vertical Angle";
				}
			} else {
				return "Relative Field";
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		public int getRowCount() {

			return points.size();
		}


		//-------------------------------------------------------------------------------------------------------------

		public boolean isCellEditable(int rowIndex, int columnIndex) {

			return canEdit;
		}


		//-------------------------------------------------------------------------------------------------------------

		public Object getValueAt(int rowIndex, int columnIndex) {

			AntPattern.AntPoint thePoint = points.get(rowIndex);

			switch (columnIndex) {

				case 0:
					if (patternType != AntPattern.PATTERN_TYPE_VERTICAL) {
						return AppCore.formatAzimuth(thePoint.angle);
					} else {
						return AppCore.formatDepression(thePoint.angle);
					}

				case 1:
					return AppCore.formatRelativeField(thePoint.relativeField);
			}

			return "";
		}


		//-------------------------------------------------------------------------------------------------------------
		// As points are edited, they are rounded appropriately and error-checked.  Edits that are no-change after
		// rounding are simply ignored.  Otherwise if azimuth/depression is edited, make sure the change does not
		// create a duplicate, and re-order the points if needed.

		public void setValueAt(Object value, int rowIndex, int columnIndex) {

			if (!canEdit) {
				return;
			}

			errorReporter.setTitle("Edit Pattern Point");

			double newVal = 0.;
			try {
				newVal = Double.parseDouble(value.toString());
			} catch (NumberFormatException ne) {
				errorReporter.reportValidationError("The value must be a number.");
				return;
			}

			AntPattern.AntPoint thePoint = points.get(rowIndex);

			switch (columnIndex) {

				case 0: {

					String lbl = "";

					if (AntPattern.PATTERN_TYPE_VERTICAL != patternType) {

						newVal = Math.rint(newVal * AntPattern.AZIMUTH_ROUND) / AntPattern.AZIMUTH_ROUND;
						if (newVal == thePoint.angle) {
							return;
						}
						if ((newVal < AntPattern.AZIMUTH_MIN) || (newVal > AntPattern.AZIMUTH_MAX)) {
							errorReporter.reportValidationError("Azimuth must be from " + AntPattern.AZIMUTH_MIN +
								" to " + AntPattern.AZIMUTH_MAX + ".");
							return;
						}
						lbl = "azimuth";

					} else {

						newVal = Math.rint(newVal * AntPattern.DEPRESSION_ROUND) / AntPattern.DEPRESSION_ROUND;
						if (newVal == thePoint.angle) {
							return;
						}
						if ((newVal < AntPattern.DEPRESSION_MIN) || (newVal > AntPattern.DEPRESSION_MAX)) {
							errorReporter.reportValidationError("Vertical angle must be from " +
								AntPattern.DEPRESSION_MIN + " to " + AntPattern.DEPRESSION_MAX + ".");
							return;
						}
						lbl = "vertical angle";
					}

					AntPattern.AntPoint oldPoint;
					int newRowIndex = 0;

					for (newRowIndex = 0; newRowIndex < points.size(); newRowIndex++) {
						if (newRowIndex == rowIndex) {
							continue;
						}
						oldPoint = points.get(newRowIndex);
						if (newVal <= oldPoint.angle) {
							if (newVal == oldPoint.angle) {
								errorReporter.reportValidationError("A point at that " + lbl + " already exists.");
								return;
							}
							break;
						}
					}
					if (newRowIndex > rowIndex) {
						newRowIndex--;
					}

					thePoint.angle = newVal;
					didEdit = true;
					antennaID = null;

					if (newRowIndex == rowIndex) {

						fireTableRowsUpdated(rowIndex, rowIndex);

					} else {

						points.remove(rowIndex);
						fireTableRowsDeleted(rowIndex, rowIndex);

						points.add(newRowIndex, thePoint);
						fireTableRowsInserted(newRowIndex, newRowIndex);

						final int moveToIndex = newRowIndex;
						SwingUtilities.invokeLater(new Runnable() {
							public void run() {
								table.setRowSelectionInterval(moveToIndex, moveToIndex);
								table.scrollRectToVisible(table.getCellRect(moveToIndex, 0, true));
							}
						});
					}

					return;
				}

				case 1: {

					if ((newVal < 0.) || (newVal > AntPattern.FIELD_MAX)) {
						errorReporter.reportValidationError("Relative field must be from " + AntPattern.FIELD_MIN +
							" to " + AntPattern.FIELD_MAX + ".");
						return;
					}

					newVal = Math.rint(newVal * AntPattern.FIELD_ROUND) / AntPattern.FIELD_ROUND;
					if (newVal < AntPattern.FIELD_MIN) {
						newVal = AntPattern.FIELD_MIN;
					}

					if (newVal == thePoint.relativeField) {
						return;
					}

					thePoint.relativeField = newVal;
					didEdit = true;
					antennaID = null;

					fireTableRowsUpdated(rowIndex, rowIndex);

					return;
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Import and export patterns using CSV files.  Note these don't support matrix patterns in the files; in matrix
	// mode what is imported/exported is just the single slice currently being viewed/edited.

	private void doImport() {

		if (!canEdit) {
			return;
		}

		String title = "Import Pattern";
		errorReporter.setTitle(title);

		JFileChooser chooser = new JFileChooser(AppCore.getProperty(AppCore.LAST_FILE_DIRECTORY_KEY));
		chooser.setDialogType(JFileChooser.OPEN_DIALOG);
		chooser.setDialogTitle(title);
		chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		chooser.setMultiSelectionEnabled(false);
		chooser.addChoosableFileFilter(new FileNameExtensionFilter("CSV (*.csv)", "csv"));
		chooser.setAcceptAllFileFilterUsed(false);

		if (JFileChooser.APPROVE_OPTION != chooser.showDialog(this, "Import")) {
			return;
		}

		File theFile = chooser.getSelectedFile();

		AppCore.setProperty(AppCore.LAST_FILE_DIRECTORY_KEY, theFile.getParentFile().getAbsolutePath());

		FileReader theReader = null;
		try {
			theReader = new FileReader(theFile);
		} catch (FileNotFoundException fnfe) {
			errorReporter.reportError("Could not open the file:\n" + fnfe.getMessage());
			return;
		}

		BufferedReader csv = new BufferedReader(theReader);

		ArrayList<AntPattern.AntPoint> newPoints = new ArrayList<AntPattern.AntPoint>();

		boolean error = false;
		String errmsg = "";

		String theLine;
		AppCore.Counter lineNumber = new AppCore.Counter(0);
		Pattern theParser = Pattern.compile(",");
		String[] tokens;

		double azdep, rf, minazdep, maxazdep, lazdep;
		if (AntPattern.PATTERN_TYPE_VERTICAL != patternType) {
			minazdep = AntPattern.AZIMUTH_MIN;
			maxazdep = AntPattern.AZIMUTH_MAX;
			lazdep = AntPattern.AZIMUTH_MIN - 1.;
		} else {
			minazdep = AntPattern.DEPRESSION_MIN;
			maxazdep = AntPattern.DEPRESSION_MAX;
			lazdep = AntPattern.DEPRESSION_MIN - 1.;
		}

		try {

			while (true) {

				theLine = AppCore.readLineSkipComments(csv, lineNumber);
				if (null == theLine) {
					break;
				}

				tokens = theParser.split(theLine);
				if (2 != tokens.length) {
					error = true;
					errmsg = "Bad data format";
					break;
				}

				try {
					azdep = Double.parseDouble(tokens[0]);
					rf = Double.parseDouble(tokens[1]);
				} catch (NumberFormatException nfe) {
					error = true;
					errmsg = "Bad data format";
					break;
				}

				if ((azdep < minazdep) || (azdep > maxazdep)) {
					error = true;
					if (AntPattern.PATTERN_TYPE_VERTICAL != patternType) {
						errmsg = "Azimuth out of range";
					} else {
						errmsg = "Vertical angle out of range";
					}
					break;
				}

				if (azdep <= lazdep) {
					error = true;
					errmsg = "Points out of sequence";
					break;
				}
				lazdep = azdep;

				if ((rf < 0.) || (rf > AntPattern.FIELD_MAX)) {
					error = true;
					errmsg = "Field out of range";
					break;
				}

				rf = Math.rint(rf * AntPattern.FIELD_ROUND) / AntPattern.FIELD_ROUND;
				if (rf < AntPattern.FIELD_MIN) {
					rf = AntPattern.FIELD_MIN;
				}

				newPoints.add(new AntPattern.AntPoint(azdep, rf));
			}

			if (!error && newPoints.isEmpty()) {
				error = true;
				errmsg = "The file was empty.";
			}

		} catch (IOException ie) {
			error = true;
			errmsg = "Could not read from the file:\n" + ie.getMessage();
			lineNumber.reset();
		}

		try {csv.close();} catch (IOException ie) {}

		if (error) {

			if (lineNumber.get() > 0) {
				errmsg = errmsg + " at line " + lineNumber;
			}
			errorReporter.reportError(errmsg);

		} else {

			patternModel.replacePoints(newPoints);
			didEdit = true;
			antennaID = null;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// For vertical pattern, this now offers either CSV or an LMS-compatible XML format.

	private void doExport() {

		if (patternModel.points.isEmpty()) {
			return;
		}

		String title = "Export Pattern";
		errorReporter.setTitle(title);

		JFileChooser chooser = new JFileChooser(AppCore.getProperty(AppCore.LAST_FILE_DIRECTORY_KEY));
		chooser.setDialogType(JFileChooser.SAVE_DIALOG);
		chooser.setDialogTitle(title);
		chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		chooser.setMultiSelectionEnabled(false);
		FileNameExtensionFilter csvFilter = new FileNameExtensionFilter("CSV (*.csv)", "csv");
		FileNameExtensionFilter xmlFilter = new FileNameExtensionFilter("XML (*.xml)", "xml");
		chooser.addChoosableFileFilter(csvFilter);
		if (AntPattern.PATTERN_TYPE_VERTICAL == patternType) {
			chooser.addChoosableFileFilter(xmlFilter);
		}
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

		boolean xmlFormat = xmlFilter.equals(chooser.getFileFilter());
		String theName = theFile.getName().toLowerCase();
		if (xmlFormat) {
			if (!theName.endsWith(".xml")) {
				theFile = new File(theFile.getAbsolutePath() + ".xml");
			}
		} else {
			if (!theName.endsWith(".csv")) {
				theFile = new File(theFile.getAbsolutePath() + ".csv");
			}
		}

		// If XML format is chosen, verify the pattern data complies with LMS requirements, confirm export if not.

		if (xmlFormat) {
			String errmsg = null;
			boolean outRange = false, hasMin = false, hasMax = false, badStep = false, badFineStep = false;
			float lastAngle = -999.f, delta;
			for (AntPattern.AntPoint thePoint : patternModel.points) {
				if ((thePoint.angle < -10.) || (thePoint.angle > 90.)) {
					outRange = true;
				}
				if (-10. == thePoint.angle) {
					hasMin = true;
				}
				if (90. == thePoint.angle) {
					hasMax = true;
				}
				if (lastAngle >= -10.) {
					delta = (float)thePoint.angle - lastAngle;
					if ((lastAngle < -5.) || (lastAngle >= 10.)) {
						if (delta > 5.) {
							badStep = true;
						}
					} else {
						if (delta > 0.5) {
							badFineStep = true;
						}
					}
				}
				lastAngle = (float)thePoint.angle;
			}
			if (outRange) {
				errmsg = "Points are outside the range of -10 to 90 degrees\n";
			} else {
				if (!hasMin || !hasMax) {
					errmsg = "Points do not cover the range of -10 to 90 degrees\n";
				}
			}
			if (badStep) {
				if (null == errmsg) {
					errmsg = "";
				}
				errmsg = errmsg + "Points are more than 5 degrees apart\n";
			}
			if (badFineStep) {
				if (null == errmsg) {
					errmsg = "";
				}
				errmsg = errmsg + "Points are more than 0.5 degrees apart between -5 and 10 degrees\n";
			}
			if ((null != errmsg) && JOptionPane.YES_OPTION != JOptionPane.showConfirmDialog(this,
					"The pattern data may not comply with LMS requirements:\n" + errmsg +
					"Are you sure you want to continue?", title, JOptionPane.YES_NO_OPTION,
					JOptionPane.WARNING_MESSAGE)) {
				return;
			}
		}

		FileWriter theWriter = null;
		try {
			theWriter = new FileWriter(theFile);
		} catch (IOException ie) {
			errorReporter.reportError("Could not open the file:\n" + ie.getMessage());
			return;
		}

		BufferedWriter writer = new BufferedWriter(theWriter);

		try {
			if (xmlFormat) {
				writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
					"<elevationPattern xmlns=\"http://mb.fcc.gov/elevationPattern\">\n");
			}
			for (AntPattern.AntPoint thePoint : patternModel.points) {
				if (xmlFormat) {

					// Below is not a typo, LMS format mis-spells angle as "Agle".

					writer.write(String.format(Locale.US, "<elevationData>\n<depressionAgle>%.3f</depressionAgle>\n" +
						"<fieldValue>%.4f</fieldValue>\n</elevationData>\n", thePoint.angle, thePoint.relativeField));
				} else {
					writer.write(String.format(Locale.US, "%.3f,%.4f\n", thePoint.angle, thePoint.relativeField));
				}
			}
			if (xmlFormat) {
				writer.write("</elevationPattern>\n");
			}
		} catch (IOException ie) {
			errorReporter.reportError("Could not write to the file:\n" + ie.getMessage());
		}

		try {writer.close();} catch (IOException ie) {}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The pattern point model automatically keeps points in order, an add or insert uses appropriate default values
	// for the new point.  For add, try to extend an existing sequence by stepping along at the same increment of
	// azimuth/depression, defaulting to 10 degrees for azimuth or 1 degree for depression.  This will fail if the last
	// point in the pattern is already at the maximum value for azimuth/depression.

	private void doAddPoint() {

		if (!canEdit) {
			return;
		}

		double newAzDep = 0., newRel = 1.;

		int lastIndex = patternModel.points.size() - 1;
		if (lastIndex >= 0) {

			double azDepInc, maxAzDep;
			if (AntPattern.PATTERN_TYPE_VERTICAL != patternType) {
				azDepInc = 10.;
				maxAzDep = AntPattern.AZIMUTH_MAX - 0.1;
			} else {
				azDepInc = 1.;
				maxAzDep = AntPattern.DEPRESSION_MAX - 0.1;
			}

			AntPattern.AntPoint lastPoint = patternModel.points.get(lastIndex);

			if (lastIndex > 0) {
				AntPattern.AntPoint prevPoint = patternModel.points.get(lastIndex - 1);
				azDepInc = lastPoint.angle - prevPoint.angle;
			}

			newAzDep = lastPoint.angle + azDepInc;
			if (newAzDep > maxAzDep) {
				newAzDep = maxAzDep;
			}
			if (newAzDep <= lastPoint.angle) {
				AppController.beep();
				return;
			}

			newRel = lastPoint.relativeField;
		}

		if (patternModel.addPoint(new AntPattern.AntPoint(newAzDep, newRel)) >= 0) {
			didEdit = true;
			antennaID = null;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Insert is similar to add, in this case the usual default is to interpolate between bracketing points.  If the
	// first point is selected, this offsets downward by an increment similar to the add case above.  This will fail
	// if the bracketing points are separated by just the minimum increment, or if the first point is selected and is
	// already at the minimum azimuth/depression.

	private void doInsertPoint() {

		if (!canEdit) {
			return;
		}

		int rowIndex = patternModel.getSelectedRow();
		if (rowIndex < 0) {
			return;
		}

		double newAzDep = 0., newRel = 1.;

		AntPattern.AntPoint thisPoint = patternModel.points.get(rowIndex);

		double azDepInc, minAzDep, azDepRnd;
		if (AntPattern.PATTERN_TYPE_VERTICAL != patternType) {
			azDepInc = 10.;
			minAzDep = AntPattern.AZIMUTH_MIN;
			azDepRnd = AntPattern.AZIMUTH_ROUND;
		} else {
			azDepInc = 1.;
			minAzDep = AntPattern.DEPRESSION_MIN;
			azDepRnd = AntPattern.DEPRESSION_ROUND;
		}

		if (rowIndex > 0) {

			AntPattern.AntPoint prevPoint = patternModel.points.get(rowIndex - 1);

			if ((thisPoint.angle - prevPoint.angle) > (2. / azDepRnd)) {

				newAzDep = (thisPoint.angle + prevPoint.angle) / 2.;
				newAzDep = Math.rint(newAzDep * azDepRnd) / azDepRnd;

				newRel = (thisPoint.relativeField + prevPoint.relativeField) / 2.;
				newRel = Math.rint(newRel * AntPattern.FIELD_ROUND) / AntPattern.FIELD_ROUND;

			} else {

				AppController.beep();
				return;
			}

		} else {

			if (rowIndex < (patternModel.getRowCount() - 1)) {
				AntPattern.AntPoint nextPoint = patternModel.points.get(rowIndex + 1);
				azDepInc = nextPoint.angle - thisPoint.angle;
			}

			newAzDep = thisPoint.angle - azDepInc;
			if (newAzDep < minAzDep) {
				newAzDep = minAzDep;
			}
			if (newAzDep >= thisPoint.angle) {
				AppController.beep();
				return;
			}

			newRel = thisPoint.relativeField;
		}

		if (patternModel.addPoint(new AntPattern.AntPoint(newAzDep, newRel)) >= 0) {
			didEdit = true;
			antennaID = null;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doDeletePoint() {

		if (!canEdit) {
			return;
		}

		int rowIndex = patternModel.getSelectedRow();
		if (rowIndex >= 0) {
			patternModel.remove(rowIndex);
			didEdit = true;
			antennaID = null;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	private void doApply() {

		if (!canEdit) {
			cancel();
			return;
		}

		// Commit any pending edits, check for errors.

		errorReporter.clearTitle();

		if (!commitCurrentField()) {
			return;
		}

		errorReporter.clearErrors();

		if (patternModel.table.isEditing()) {
			patternModel.table.getCellEditor().stopCellEditing();
		}
		if (errorReporter.hasErrors()) {
			return;
		}

		// Except in receive mode, a simple pattern with no points is valid, that indicates a "no pattern" condition
		// that will cause getPattern() to return null.  Otherwise call the local checkPattern() and isDataValid(),
		// checkPattern() does more checks than are required for object validity.

		if ((AntPattern.PATTERN_TYPE_RECEIVE == patternType) || !pattern.isSimple() || !patternModel.points.isEmpty()) {
			if (!patternModel.checkPattern()) {
				return;
			}
			if (!pattern.isDataValid(errorReporter)) {
				return;
			}
		}

		// A name must be provided for a receive antenna pattern, it is optional for all others.  Certain names are
		// reserved by other UIs, treat those as blank.

		String theName = patternNameField.getText().trim();
		if (theName.equals(AntPattern.NEW_ANTENNA_NAME) || theName.equals(AntPattern.GENERIC_ANTENNA_NAME)) {
			theName = "";
		}
		if (theName.length() > Source.MAX_PATTERN_NAME_LENGTH) {
			theName = theName.substring(0, Source.MAX_PATTERN_NAME_LENGTH);
		}
		if ((AntPattern.PATTERN_TYPE_RECEIVE == patternType) && (0 == theName.length())) {
			errorReporter.reportWarning("Please provide a name for the antenna.");
			return;
		}

		if (!theName.equals(pattern.name)) {
			pattern.name = theName;
			didEdit = true;
		}

		// Inform the parent of the change, close if successful.

		if (parent.applyEditsFrom(this)) {
			AppController.hideWindow(this);
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
	// Update station data list for changes.

	public void updateExtDbList() {

		if (null == patternSearchPanel) {
			return;
		}

		SwingUtilities.invokeLater(new Runnable() {
			public void run() {

				ArrayList<KeyedRecord> list = ExtDb.getExtDbList(getDbID(), 0);
				if (null == list) {
					return;
				}

				int selectKey = patternSearchPanel.extDbMenu.getSelectedKey();
				patternSearchPanel.extDbMenu.removeAllItems();
				if (!list.isEmpty()) {
					patternSearchPanel.extDbMenu.addAllItems(list);
					if (patternSearchPanel.extDbMenu.containsKey(selectKey)) {
						patternSearchPanel.extDbMenu.setSelectedKey(selectKey);
					}
				}
			}
		});
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set initial state.  If editable load the station data list and show the search panel.  If the list fails to
	// load or is empty no error, just don't show the search panel.

	public void windowWillOpen() {

		patternNameField.setText(pattern.name);

		updateState(null);

		if (null != antennaOrientationField) {
			antennaOrientationField.setText(AppCore.formatAzimuth(antennaOrientation));
		}

		if (null != antennaGainField) {
			antennaGainField.setText(AppCore.formatDecimal(pattern.gain, 2));
		}

		if (null != patternSearchPanel) {
			patternSearchPanel.extDbMenu.removeAllItems();
			ArrayList<KeyedRecord> list = ExtDb.getExtDbList(getDbID(), 0);
			if ((null != list) && !list.isEmpty()) {
				patternSearchPanel.extDbMenu.addAllItems(list);
				tabPane.addTab("Search", patternSearchPanel);
			} else {
				patternSearchPanel = null;
			}
		}

		setLocationRelativeTo(getOwner());

		blockActionsClear();

		if (null != patternSearchPanel) {
			ExtDb.addListener(this);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void windowWillClose() {

		if (null != patternSearchPanel) {
			ExtDb.removeListener(this);
		}

		blockActionsSet();
		parent.editorClosing(this);
	}


	//=================================================================================================================
	// Plot the pattern.  Nothing fancy, but it works.

	private class PatternPlotPanel extends Canvas {


		//-------------------------------------------------------------------------------------------------------------

		public void paint(Graphics og) {

			ArrayList<AntPattern.AntPoint> thePoints = patternModel.points;

			int rowCount = thePoints.size();
			if (rowCount < 2) {
				return;
			}

			Graphics2D g = (Graphics2D)og;
			g.setColor(Color.GRAY);
			g.setStroke(new BasicStroke((float)1.));

			Path2D.Double thePath = new Path2D.Double();

			int row1, row2;
			AntPattern.AntPoint point1, point2;
			double topy = (double)getHeight();
			double xscl = 1., yscl = 1., scl = 1., x0 = 0., y0 = 0., xl = 0., yl = 0.;
			double xo, yo, adi, ad1, ad2, rf1, rf2, ad, rf, x, y, dx, dy;
			int xi1, yi1, xi2, yi2;

			if (AntPattern.PATTERN_TYPE_VERTICAL != patternType) {

				double rot = 0.;
				if ((null != rotateHorizontalPlotCheckBox) && rotateHorizontalPlotCheckBox.isSelected()) {
					rot = antennaOrientation;
				}

				xo = (double)getWidth() / 2.;
				yo = topy / 2.;

				xscl = xo * 0.95;
				yscl = yo * 0.95;
				if (xscl < yscl) {
					scl = xscl;
				} else {
					scl = yscl;
				}

				adi = 0.02;

				for (rf = 0.2; rf < 1.01; rf += 0.2) {
					x = rf * scl * 2.;
					xi1 = (int)(xo - (x / 2.));
					yi1 = (int)(topy - (yo + (x / 2.)));
					xi2 = (int)x;
					yi2 = (int)x;
					g.drawOval(xi1, yi1, xi2, yi2);
				}
				for (ad = 0.; ad < 359.99; ad += 10.) {
					xi1 = (int)(xo + (0.2 * scl * Math.sin(ad * GeoPoint.DEGREES_TO_RADIANS)));
					yi1 = (int)(topy - (yo + (0.2 * scl * Math.cos(ad * GeoPoint.DEGREES_TO_RADIANS))));
					xi2 = (int)(xo + (scl * Math.sin(ad * GeoPoint.DEGREES_TO_RADIANS)));
					yi2 = (int)(topy - (yo + (scl * Math.cos(ad * GeoPoint.DEGREES_TO_RADIANS))));
					g.drawLine(xi1, yi1, xi2, yi2);
				}

				for (row1 = 0; row1 < rowCount; row1++) {

					point1 = thePoints.get(row1);
					ad1 = (point1.angle + rot) * GeoPoint.DEGREES_TO_RADIANS;
					rf1 = point1.relativeField;

					row2 = row1 + 1;
					if (row2 == rowCount) {
						row2 = 0;
					}
					point2 = thePoints.get(row2);
					ad2 = (point2.angle + rot) * GeoPoint.DEGREES_TO_RADIANS;
					if (ad2 < ad1) {
						ad2 += GeoPoint.TWO_PI;
					}
					rf2 = point2.relativeField;

					for (ad = ad1; ad < ad2; ad += adi) {

						rf = rf1 + ((rf2 - rf1) * ((ad - ad1) / (ad2 - ad1)));
						x = xo + (rf * scl * Math.sin(ad));
						y = topy - (yo + (rf * scl * Math.cos(ad)));

						if (0 == row1) {

							thePath.moveTo(x, y);
							xl = x;
							yl = y;
							x0 = x;
							y0 = y;

						} else {

							dx = x - xl;
							dy = y - yl;
							if (Math.sqrt((dx * dx) + (dy * dy)) > 2.) {
								thePath.lineTo(x, y);
								xl = x;
								yl = y;
							}
						}
					}
				}

				thePath.lineTo(x0, y0);

			} else {

				boolean first = true;

				x = (double)getWidth() / 100.;
				xscl = x * 0.95;
				xo = ((x - xscl) / 2.) * 100.;

				yscl = topy * 0.95;
				yo = (topy - yscl) / 2.;

				adi = 0.25;

				xi1 = (int)xo;
				xi2 = (int)(xo + (100. * xscl));
				for (rf = 0.; rf < 1.01; rf += 0.1) {
					yi1 = (int)(topy - (yo + (rf * yscl)));
					yi2 = yi1;
					g.drawLine(xi1, yi1, xi2, yi2);
				}
				yi1 = (int)(topy - yo);
				yi2 = (int)(topy - (yo + yscl));
				for (ad = 0.; ad < 100.01; ad += 5.) {
					xi1 = (int)(xo + (ad * xscl));
					xi2 = xi1;
					g.drawLine(xi1, yi1, xi2, yi2);
				}
				xi1 = (int)(xo + (10. * xscl));
				xi2 = xi1;
				g.setStroke(new BasicStroke((float)2.));
				g.drawLine(xi1, yi1, xi2, yi2);

				for (row1 = 0; row1 < (rowCount - 1); row1++) {

					point1 = thePoints.get(row1);
					ad1 = point1.angle + 10.;
					rf1 = point1.relativeField;

					point2 = thePoints.get(row1 + 1);
					ad2 = point2.angle + 10.;
					rf2 = point2.relativeField;

					for (ad = ad1; ad < ad2; ad += adi) {

						if (ad < 0.) {
							continue;
						}

						rf = rf1 + ((rf2 - rf1) * ((ad - ad1) / (ad2 - ad1)));
						x = xo + (ad * xscl);
						y = topy - (yo + (rf * yscl));

						if (first) {

							thePath.moveTo(x, y);
							xl = x;
							yl = y;
							first = false;

						} else {

							dx = x - xl;
							dy = y - yl;
							if (Math.sqrt((dx * dx) + (dy * dy)) > 2.) {
								thePath.lineTo(x, y);
								xl = x;
								yl = y;
							}
						}
					}
				}
			}

			g.setColor(Color.BLACK);
			g.setStroke(new BasicStroke((float)3., BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(thePath);
		}
	}


	//=================================================================================================================
	// Search UI, appears in a tab when pattern data is editable.

	private class SearchPanel extends JPanel {

		private KeyedRecordMenu extDbMenu;

		private JTextField searchField;

		private SearchListModel searchModel;

		// Buttons.

		private JButton searchButton;
		private JButton loadButton;


		//-------------------------------------------------------------------------------------------------------------
		// Create the UI components.  The data set menu will be re-populated as needed, see windowWillOpen().

		private SearchPanel() {

			extDbMenu = new KeyedRecordMenu(new ArrayList<KeyedRecord>());

			JPanel extDbPanel = new JPanel();
			extDbPanel.setBorder(BorderFactory.createTitledBorder("Station Data"));
			extDbPanel.add(extDbMenu);

			searchField = new JTextField(15);
			AppController.fixKeyBindings(searchField);
			searchField.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doSearch();
				}
			});

			searchModel = new SearchListModel();

			searchModel.list.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
				public void valueChanged(ListSelectionEvent theEvent) {
					loadButton.setEnabled(searchModel.list.getSelectedIndex() >= 0);
				}
			});

			// Buttons.

			searchButton = new JButton("Search");
			searchButton.setFocusable(false);
			searchButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doSearch();
				}
			});

			loadButton = new JButton("Load");
			loadButton.setFocusable(false);
			loadButton.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent theEvent) {
					doLoad();
				}
			});
			loadButton.setEnabled(false);

			// Do the layout.

			JPanel row1Panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
			row1Panel.add(extDbPanel);

			JPanel row2Panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
			row2Panel.add(searchField);
			row2Panel.add(searchButton);

			JPanel topPanel = new JPanel();
			topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
			topPanel.add(row1Panel);
			topPanel.add(row2Panel);

			JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
			buttonPanel.add(loadButton);

			setLayout(new BorderLayout());
			add(topPanel, BorderLayout.NORTH);
			add(AppController.createScrollPane(searchModel.list), BorderLayout.CENTER);
			add(buttonPanel, BorderLayout.SOUTH);
		}


		//=============================================================================================================
		// List model for the search results list.

		private class SearchListModel extends AbstractListModel<String> {

			private ArrayList<ExtDb.AntennaID> modelRows;

			private JList<String> list;


			//---------------------------------------------------------------------------------------------------------

			private SearchListModel() {

				modelRows = new ArrayList<ExtDb.AntennaID>();

				list = new JList<String>(this);
				list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
			}


			//---------------------------------------------------------------------------------------------------------

			private void setItems(ArrayList<ExtDb.AntennaID> newList) {

				int lastIndex = modelRows.size() - 1;
				if (lastIndex >= 0) {
					modelRows.clear();
					fireIntervalRemoved(this, 0, lastIndex);
				}

				if (null != newList) {
					modelRows.addAll(newList);
				}

				lastIndex = modelRows.size() - 1;
				if (lastIndex >= 0) {
					fireIntervalAdded(this, 0, lastIndex);
				}
			}


			//---------------------------------------------------------------------------------------------------------

			private ExtDb.AntennaID getSelectedItem() {

				int rowIndex = list.getSelectedIndex();
				if (rowIndex >= 0) {
					return modelRows.get(rowIndex);
				}
				return null;
			}


			//---------------------------------------------------------------------------------------------------------

			public int getSize() {

				return modelRows.size();
			}


			//---------------------------------------------------------------------------------------------------------

			public String getElementAt(int index) {

				return modelRows.get(index).name;
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		private void doReset() {

			searchField.setText("");
			searchModel.setItems(null);
			loadButton.setEnabled(false);
		}


		//-------------------------------------------------------------------------------------------------------------
		// Do the search.

		private void doSearch() {

			final Integer extDbKey = Integer.valueOf(extDbMenu.getSelectedKey());

			final String str = searchField.getText().trim();
			if (0 == str.length()) {
				return;
			}

			BackgroundWorker<ArrayList<ExtDb.AntennaID>> theWorker =
					new BackgroundWorker<ArrayList<ExtDb.AntennaID>>(outerThis, WINDOW_TITLE) {
				protected ArrayList<ExtDb.AntennaID> doBackgroundWork(ErrorLogger errors) {
					return ExtDb.findAntennas(getDbID(), extDbKey, str,
						(AntPattern.PATTERN_TYPE_VERTICAL == patternType), errors);
				}
			};

			errorReporter.setTitle("Antenna Search");

			ArrayList<ExtDb.AntennaID> theItems =
				theWorker.runWork("Searching for antennas, please wait...", errorReporter);
			if (null == theItems) {
				return;
			}

			searchModel.setItems(theItems);
		}


		//-------------------------------------------------------------------------------------------------------------
		// Try to load pattern data for the selected antenna into the pattern editor tab.

		private void doLoad() {

			ExtDb.AntennaID theAnt = searchModel.getSelectedItem();
			if (null == theAnt) {
				return;
			}

			String title = "Load Pattern Data";
			errorReporter.setTitle(title);

			errorReporter.clearMessages();

			ArrayList<AntPattern.AntPoint> newPoints = null;
			String theID = null;

			if (AntPattern.PATTERN_TYPE_VERTICAL != patternType) {

				newPoints = ExtDbRecord.getAntennaPattern(theAnt.dbID, theAnt.extDbKey, theAnt.antennaRecordID,
					errorReporter);
				if ((null != newPoints) && newPoints.isEmpty()) {
					errorReporter.reportWarning("No pattern data found for antenna.");
					newPoints = null;
				} else {
					theID = theAnt.antennaID;
				}

			} else {

				newPoints = ExtDbRecord.getElevationPattern(theAnt.dbID, theAnt.extDbKey, theAnt.antennaRecordID,
					errorReporter);
				if ((null != newPoints) && newPoints.isEmpty()) {
					errorReporter.reportWarning("No pattern data found for antenna.");
					newPoints = null;
				}
			}

			if (null == newPoints) {
				return;
			}

			errorReporter.showMessages();

			if (pattern.isSimple()) {
				patternNameField.setText(theAnt.name);
			}
			patternModel.replacePoints(newPoints);
			didEdit = true;
			antennaID = theID;

			tabPane.setSelectedIndex(0);
		}
	}
}
