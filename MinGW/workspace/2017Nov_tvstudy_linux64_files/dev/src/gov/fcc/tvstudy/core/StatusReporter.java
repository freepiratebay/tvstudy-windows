//
//  StatusReporter.java
//  TVStudy
//
//  Copyright (c) 2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core;


//=====================================================================================================================
// Interface for objects that report status to some type of UI during lengthy blocking methods on secondary threads.
// Allows UI classes to get updates from core methods.  Also supports a cancel-operation feature, if possible code
// should check the isCanceled() state and abort if it returns true.  However that behavior is not mandatory.

public interface StatusReporter {


	//-----------------------------------------------------------------------------------------------------------------

	public void setWaitMessage(String theMessage);


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isCanceled();
}
