//
//  ProcessController.java
//  TVStudy
//
//  Copyright (c) 2014-2015 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.gui.run;


//=====================================================================================================================
// Interface for objects that can be interactive controllers for processes running in a ProcessPanel.

public interface ProcessController {


	//-----------------------------------------------------------------------------------------------------------------
	// Provide response to a prompt from the process, or null if no response is known (invalid or unexpected prompt)
	// in which case process will be killed.  When returning a response, the implementer must enter a pending state
	// until confirmation or failure.  Only one response per process may be pending at any given time.  In the pending
	// state this should not be called, if it is it may return the pending response string again, or return null.

	public String getProcessResponse(ProcessPanel theProcess, String thePrompt);


	//-----------------------------------------------------------------------------------------------------------------
	// Process confirmed the response, clear pending state.

	public void processResponseConfirmed(ProcessPanel theProcess);


	//-----------------------------------------------------------------------------------------------------------------
	// Process failed for any reason, including but not limited to failure to confirm a response.

	public void processFailed(ProcessPanel theProcess);


	//-----------------------------------------------------------------------------------------------------------------
	// Process exited without error.  However if this happens while a response is pending, it might be an error.

	public void processComplete(ProcessPanel theProcess);


	//-----------------------------------------------------------------------------------------------------------------
	// Inline key=data messages from the process that aren't known to ProcessPanel are sent to the controller.

	public void processStatusMessage(ProcessPanel theProcess, String theKey, String theData);
}
