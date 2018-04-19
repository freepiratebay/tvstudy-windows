//
//  TableFilterModel.java
//  TVStudy
//
//  Copyright (c) 2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui.editor;


//=====================================================================================================================
// Interface for table model objects that can work with TableFilterPanel, see that for details.

public interface TableFilterModel {


	//-----------------------------------------------------------------------------------------------------------------
	// Basic column-related TableModel methods must be available.

	public int getColumnCount();


	//-----------------------------------------------------------------------------------------------------------------

	public String getColumnName(int columnIndex);


	//-----------------------------------------------------------------------------------------------------------------
	// Return true if a column should be included in the filter.

	public boolean filterByColumn(int columnIndex);


	//-----------------------------------------------------------------------------------------------------------------
	// Return true if the menu choices for a column should be collapsed by combining row values with matching initial
	// substrings, increasing the substring length each time a selection is made.

	public boolean collapseFilterChoices(int columnIndex);


	//-----------------------------------------------------------------------------------------------------------------
	// Implementing class is presumably filtering in the normal row and cell accessors.

	public int getUnfilteredRowCount();


	//-----------------------------------------------------------------------------------------------------------------
	// The filter only works on strings, other classes must be converted as appropriate in this method.

	public String getUnfilteredValueAt(int rowIndex, int columnIndex);


	//-----------------------------------------------------------------------------------------------------------------
	// Called by the filter panel when filtering state changes due to menu action.

	public void filterDidChange();
}
