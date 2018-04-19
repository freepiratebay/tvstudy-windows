//
//  ExtDbListener.java
//  TVStudy
//
//  Copyright (c) 2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core;


//=====================================================================================================================
// Interface for objects that can register with ExtDb to be notified when the data set list changes.

public interface ExtDbListener {


	//-----------------------------------------------------------------------------------------------------------------

	public String getDbID();


	//-----------------------------------------------------------------------------------------------------------------

	public void updateExtDbList();
}
