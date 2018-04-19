//
//  TableFilterPanel.java
//  TVStudy
//
//  Copyright (c) 2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui.editor;

import gov.fcc.tvstudy.gui.*;

import java.util.*;
import java.awt.*;
import java.awt.event.*;
import java.text.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.table.*;


//=====================================================================================================================
// Panel class to implement "drill-down" filtering in a table model.  The model object implements TableFilterModel,
// then creates an instance of this and accesses the filter index arrays directly.  The panel displays a set of pop-up
// menus for table columns that offer choices to filter rows by matching value in each column.  To build an index, this
// scans the model content applying the current filter settings, then updates the menus to show choices based on the
// filtered result to continue narrowing the filter.  A "Clear" button returns to an unfiltered state, also each column
// menu has a fixed "(all ???)" item at the top to clear the filter for just that column.  A menu may show only the
// "(all ???)" item if there would only be one filtering choice, or if there are too many filtering choices for the
// menu to be useful.  However if requested by the model on a column-by-column basis, if the number of menu choices is
// above the limit the choices may be collapsed using an initial substring match.

public class TableFilterPanel extends AppPanel {

	public static final int MAX_FILTER_CHOICES = 30;

	private TableFilterModel tableModel;

	private static class ColumnFilter {

		private boolean doFilter;
		private boolean collapseChoices;

		private JComboBox<String> filterMenu;
		private String allItem;

		private boolean isFiltered;
		private String filterMenuString;
		private String filterString;

		private boolean menuCollapsed;
		private int prefixLength;

		private TreeSet<String> rowValues;
	}

	private int columnCount;
	private ColumnFilter[] columnFilters;

	private JButton clearButton;

	private JPanel layoutPanel;

	// Filter state.  The forward index gives row index in the unfiltered model by row index in the filtered model.
	// The reverse index is the opposite, filtered rows have -1 in the reverse index.

	public int[] forwardIndex;
	public int[] reverseIndex;


	//-----------------------------------------------------------------------------------------------------------------

	public TableFilterPanel(AppEditor theParent, TableFilterModel theModel) {

		super(theParent);

		tableModel = theModel;

		clearButton = new JButton("Clear");
		clearButton.setFocusable(false);
		clearButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent theEvent) {
				clearFilter();
				tableModel.filterDidChange();
			}
		});

		layoutPanel = new JPanel();
		layoutPanel.setLayout(new BoxLayout(layoutPanel, BoxLayout.X_AXIS));

		add(layoutPanel);

		columnsDidChange();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The model must call this method if there are any changes to column order, column count, column names, or the
	// values returned by filterByColumn() or collapseFilterChioces().  Rebuild all the menus and update the layout.
	// This also clears to an unfiltered state.  Note the caller should always call pack() on the displaying window
	// after this else the layout change may not redraw correctly.

	public void columnsDidChange() {

		columnCount = tableModel.getColumnCount();
		columnFilters = new ColumnFilter[columnCount];

		layoutPanel.removeAll();

		ColumnFilter theColumn;
		boolean hasColumns = false;

		for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {

			theColumn = new ColumnFilter();
			columnFilters[columnIndex] = theColumn;

			theColumn.doFilter = tableModel.filterByColumn(columnIndex);

			if (theColumn.doFilter) {

				theColumn.collapseChoices = tableModel.collapseFilterChoices(columnIndex);

				theColumn.filterMenu = new JComboBox<String>();
				theColumn.filterMenu.setFocusable(false);
				theColumn.allItem = "(all " + tableModel.getColumnName(columnIndex) + ")";
				theColumn.filterMenu.addItem(theColumn.allItem);
				layoutPanel.add(theColumn.filterMenu);

				final int theColumnIndex = columnIndex;
				theColumn.filterMenu.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent theEvent) {
						if (blockActions()) {
							updateFilter(theColumnIndex);
							tableModel.filterDidChange();
							blockActionsEnd();
						}
					}
				});
			}
		}

		layoutPanel.add(clearButton);

		updateFilter();
	}

		
	//-----------------------------------------------------------------------------------------------------------------
	// Apply the filter to construct an index mapping the filtered list to the underlying list, including a reverse
	// index, and rebuild the filter choice menus.  Called when local filter settings change, also needs to be called
	// by the model when there are changes to the data.  New index arrays are available in properties.

	public void updateFilter() {
		blockActionsStart();
		updateFilter(-1);
		blockActionsEnd();
	}

	private void updateFilter(int eventColumnIndex) {

		ColumnFilter theColumn;

		// If this came from a menu action, update the filtered state of that column.

		if (eventColumnIndex >= 0) {

			theColumn = columnFilters[eventColumnIndex];

			if (theColumn.doFilter) {

				int newIndex = theColumn.filterMenu.getSelectedIndex();

				if (theColumn.isFiltered && (0 == newIndex)) {

					theColumn.isFiltered = false;
					theColumn.filterMenuString = null;
					theColumn.filterString = null;
					theColumn.menuCollapsed = false;
					theColumn.prefixLength = 0;

				} else {

					if ((!theColumn.isFiltered && (newIndex > 0)) || (newIndex > 1)) {

						theColumn.isFiltered = true;
						theColumn.filterMenuString = (String)theColumn.filterMenu.getSelectedItem();
						if (theColumn.menuCollapsed) {
							theColumn.filterString = theColumn.filterMenuString.substring(0, theColumn.prefixLength);
							theColumn.prefixLength++;
						} else {
							theColumn.filterString = theColumn.filterMenuString;
						}

					} else {
						return;
					}
				}

			} else {
				return;
			}
		}

		// Check if any columns are filtering.  If the model is empty, clear all filtering.  Also create temporary
		// collection objects used to accumulate the new filter menu choices.

		int columnIndex, rowIndex, rowCount = tableModel.getUnfilteredRowCount();
		boolean noFilter = true;

		for (columnIndex = 0; columnIndex < columnCount; columnIndex++) {

			theColumn = columnFilters[columnIndex];

			if (theColumn.doFilter && (rowCount > 0)) {

				if (theColumn.isFiltered) {
					noFilter = false;
				}
				theColumn.rowValues = new TreeSet<String>();

			} else {

				theColumn.isFiltered = false;
				theColumn.filterMenuString = null;
				theColumn.filterString = null;
				theColumn.menuCollapsed = false;
				theColumn.prefixLength = 0;
			}
		}

		// Build the index.  If not filtering this is easy, both index mappings are one-to-one and all columns that
		// are capable of filtering have all row values accumulated as filter choices.  Note cell values that are null
		// are ignored.  Empty values will never match when filtering, but those are accumulated in the choices here.
		// The empty string will not actually appear in the menu but it may cause a single non-empty choice to appear,
		// that is a useful choice because it will filter out the rows with the empty values.

		String value;

		if (noFilter) {

			forwardIndex = new int[rowCount];
			reverseIndex = new int[rowCount];

			for (rowIndex = 0; rowIndex < rowCount; rowIndex++) {

				forwardIndex[rowIndex] = rowIndex;
				reverseIndex[rowIndex] = rowIndex;

				for (columnIndex = 0; columnIndex < columnCount; columnIndex++) {
					theColumn = columnFilters[columnIndex];
					if (theColumn.doFilter) {
						value = tableModel.getUnfilteredValueAt(rowIndex, columnIndex);
						if (null != value) {
							theColumn.rowValues.add(value);
						}
					}
				}
			}

		} else {

			// Building index with filtering, build a temporary forward index during the filtering loop and accumulate
			// new filter choices from only the rows that pass the filter.  Null or empty values exclude the row.

			String[] values = new String[columnCount];
			ArrayList<Integer> matches = new ArrayList<Integer>();
			boolean match;

			for (rowIndex = 0; rowIndex < rowCount; rowIndex++) {

				match = true;

				for (columnIndex = 0; columnIndex < columnCount; columnIndex++) {
					theColumn = columnFilters[columnIndex];
					if (theColumn.doFilter) {
						value = tableModel.getUnfilteredValueAt(rowIndex, columnIndex);
						values[columnIndex] = value;
						if (theColumn.isFiltered && ((null == value) || (0 == value.length()) ||
								(theColumn.menuCollapsed && !value.startsWith(theColumn.filterString)) ||
								(!theColumn.menuCollapsed && !value.equals(theColumn.filterString)))) {
							match = false;
							break;
						}
					}
				}

				if (match) {
					matches.add(new Integer(rowIndex));
					for (columnIndex = 0; columnIndex < columnCount; columnIndex++) {
						theColumn = columnFilters[columnIndex];
						if (theColumn.doFilter && (null != values[columnIndex])) {
							theColumn.rowValues.add(values[columnIndex]);
						}
					}
				}
			}

			reverseIndex = new int[rowCount];
			for (rowIndex = 0; rowIndex < rowCount; rowIndex++) {
				reverseIndex[rowIndex] = -1;
			}

			rowCount = matches.size();
			forwardIndex = new int[rowCount];
			int uRowIndex;
			for (rowIndex = 0; rowIndex < rowCount; rowIndex++) {
				uRowIndex = matches.get(rowIndex).intValue();
				forwardIndex[rowIndex] = uRowIndex;
				reverseIndex[uRowIndex] = rowIndex;
			}
		}

		// Now update the menu choices.  The menus always show the all choice which is selected if the column is not
		// filtering.  If a column is filtering, the second item is the current filter setting which is selected to
		// indicate the column is filtering.  If a column is not filtering, more choices will be shown only if there
		// would be more than 1 and fewer than the max.  If more than the max and collapsing is allowed that will be
		// done.  If the collapsed menu would still be too long the choices aren't shown, but a collapsed menu will
		// show only 1 item to allow continued drill-down.  When a column is filtering and has not been collapsed,
		// there are never any choices beyond all since the filter is an exact match and the choices set will contain
		// only the filter setting in that case.  If a column is filtering and has been collapsed, the menu will show
		// more choices using the next-longer prefix string.

		Collection<String> filterChoices;
		boolean enableClear = false;

		for (columnIndex = 0; columnIndex < columnCount; columnIndex++) {
			theColumn = columnFilters[columnIndex];
			if (!theColumn.doFilter) {
				continue;
			}

			theColumn.filterMenu.removeAllItems();
			theColumn.filterMenu.addItem(theColumn.allItem);

			if (!theColumn.isFiltered) {
				theColumn.menuCollapsed = false;
				theColumn.prefixLength = 0;
			}

			filterChoices = theColumn.rowValues;
			if (null == filterChoices) {
				continue;
			}

			if (theColumn.menuCollapsed ||
					((filterChoices.size() > MAX_FILTER_CHOICES) && theColumn.collapseChoices)) {

				if (!theColumn.menuCollapsed) {
					theColumn.menuCollapsed = true;
					theColumn.prefixLength = 1;
				}

				filterChoices = new ArrayList<String>();

				int prefixLength, valueLength;
				String prefix;
				boolean ellipses, atLongest;
				Iterator<String> it;

				// In case all the values share a long prefix, loop to increment the new prefix length until there is
				// more than 1 choice, or until the longest possible prefix is reached.

				while (true) {

					prefixLength = theColumn.prefixLength;
					prefix = null;
					ellipses = false;
					atLongest = true;

					it = theColumn.rowValues.iterator();
					while (it.hasNext()) {
						value = it.next();
						valueLength = value.length();
						if (valueLength >= prefixLength) {
							if (null == prefix) {
								prefix = value.substring(0, prefixLength);
								ellipses = (valueLength > prefixLength);
							} else {
								if (value.startsWith(prefix)) {
									if (valueLength > prefixLength) {
										ellipses = true;
									}
								} else {
									if (ellipses) {
										filterChoices.add(prefix + "...");
										atLongest = false;
									} else {
										filterChoices.add(prefix);
									}
									prefix = value.substring(0, prefixLength);
									ellipses = (valueLength > prefixLength);
								}
							}
						}
					}
					if (null != prefix) {
						if (ellipses) {
							filterChoices.add(prefix + "...");
							atLongest = false;
						} else {
							filterChoices.add(prefix);
						}
					}

					if ((filterChoices.size() > 1) || atLongest) {
						break;
					}

					theColumn.prefixLength++;
					filterChoices.clear();
				}
			}

			if (theColumn.isFiltered) {

				theColumn.filterMenu.addItem(theColumn.filterMenuString);

				if (theColumn.menuCollapsed) {
					if (filterChoices.size() <= MAX_FILTER_CHOICES) {
						for (String item : filterChoices) {
							theColumn.filterMenu.addItem(item);
						}
					}
				}

				theColumn.filterMenu.setSelectedIndex(1);
				enableClear = true;

			} else {

				// An empty string in the filter choices is not included in the menu, but as mentioned above if present
				// it may cause a single non-empty choice to appear, because that choice will remove empty-value rows.

				if ((theColumn.menuCollapsed || (filterChoices.size() > 1)) &&
						(filterChoices.size() <= MAX_FILTER_CHOICES)) {
					for (String item : filterChoices) {
						if (item.length() > 0) {
							theColumn.filterMenu.addItem(item);
						}
					}
				}

				theColumn.filterMenu.setSelectedIndex(0);
			}

			theColumn.rowValues = null;
		}

		clearButton.setEnabled(enableClear);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Clear the filter state.

	public void clearFilter() {

		for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
			columnFilters[columnIndex].isFiltered = false;
			columnFilters[columnIndex].filterString = null;
			columnFilters[columnIndex].menuCollapsed = false;
			columnFilters[columnIndex].prefixLength = 0;
		}

		updateFilter();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// AppPanel methods.

	public void setEnabled(boolean flag) {

		boolean enableClear = false;
		for (ColumnFilter theColumn : columnFilters) {
			if (theColumn.doFilter) {
				AppController.setComponentEnabled(theColumn.filterMenu, flag);
				if (theColumn.isFiltered) {
					enableClear = flag;
				}
			}
		}
		clearButton.setEnabled(enableClear);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public void clearFields() {

		clearFilter();
	}
}
