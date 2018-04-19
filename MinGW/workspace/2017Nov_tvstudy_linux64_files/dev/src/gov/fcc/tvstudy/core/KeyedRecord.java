//
//  KeyedRecord.java
//  TVStudy
//
//  Copyright (c) 2012-2015 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core;

import java.util.*;


//=====================================================================================================================
// Data class that pairs an integer key with a name string.  This is the superclass for all immutable data classes that
// represent records from database tables with a primary key and a descriptive name.  It defines equality as matching
// class and key.

public class KeyedRecord {

	public final int key;
	public final String name;


	//-----------------------------------------------------------------------------------------------------------------

	public KeyedRecord(int theKey, String theName) {

		super();

		key = theKey;
		name = theName;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String toString() {

		return name;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int hashCode() {

		return key;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean equals(Object other) {

		return (null != other) && (((KeyedRecord)other).key == key);
	}
}
