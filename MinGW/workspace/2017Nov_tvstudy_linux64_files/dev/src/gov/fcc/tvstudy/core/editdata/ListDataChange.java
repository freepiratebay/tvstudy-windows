//
//  ListDataChange.java
//  TVStudy
//
//  Copyright (c) 2015 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.editdata;


//=====================================================================================================================
// Interface for list data model classes to inform controllers (i.e. TableModel subclasses) of the last model change.

public interface ListDataChange {

	// Change types.

	public static final int NO_CHANGE = 0;
	public static final int ALL_CHANGE = 1;
	public static final int INSERT = 2;
	public static final int UPDATE = 3;
	public static final int DELETE = 4;


	//-----------------------------------------------------------------------------------------------------------------
	// Return last change.

	public int getLastChange();


	//-----------------------------------------------------------------------------------------------------------------
	// Return last model row changed, should return -1 for NO_CHANGE and 0 for ALL_CHANGE.

	public int getLastRowChanged();
}
