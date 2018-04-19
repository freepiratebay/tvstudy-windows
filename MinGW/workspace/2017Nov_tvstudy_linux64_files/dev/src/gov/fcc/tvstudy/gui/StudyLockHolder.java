//
//  StudyLockHolder.java
//  TVStudy
//
//  Copyright (c) 2012-2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui;


//=====================================================================================================================
// Interface for objects that have an "open" study, meaning they hold and manage a study lock state.  Implementing
// objects are assumed to be some type of UI view currently displayed in a window, but may not necessarily be Window
// subclasses.  However they must implement several methods mirroring the Window and RootEditor APIs, forwarding those
// to an appropriate parent or containing object as needed.

public interface StudyLockHolder {


	//-----------------------------------------------------------------------------------------------------------------
	// Return the study key.

	public int getStudyKey();


	//-----------------------------------------------------------------------------------------------------------------
	// Return the current lock information.

	public int getStudyLock();


	//-----------------------------------------------------------------------------------------------------------------

	public int getLockCount();


	//-----------------------------------------------------------------------------------------------------------------
	// Methods typically just forward to a containing Window.

	public boolean isVisible();


	//-----------------------------------------------------------------------------------------------------------------

	public void toFront();


	//-----------------------------------------------------------------------------------------------------------------
	// Discard changes and close with no further UI; the object may refuse by returning false.

	public boolean closeWithoutSave();
}
