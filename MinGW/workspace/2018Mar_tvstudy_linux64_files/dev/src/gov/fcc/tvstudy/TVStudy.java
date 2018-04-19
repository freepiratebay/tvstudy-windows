//
//  TVStudy.java
//  TVStudy
//
//  Copyright (c) 2015 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy;

import gov.fcc.tvstudy.gui.*;

import javax.swing.*;


//=====================================================================================================================
// Main class for TVStudy desktop application, this wrapper exists just so the class name "TVStudy" appears in the GUI
// environment to identify the application.  See gui.AppController for the actual startup code.

public class TVStudy {
	public static void main(String args[]) throws Exception {
		AppController.main(args);
	}
}
