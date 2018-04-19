//
//  SignalType.java
//  TVStudy
//
//  Copyright (c) 2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.data;

import gov.fcc.tvstudy.core.*;

import java.util.*;
import java.sql.*;


//=====================================================================================================================
// Represents records from the signal_type table, and provides caching data factory methods.

public class SignalType extends KeyedRecord {

	// Database properties:

	// key (super)   Signal type key, unique.
	// name (super)  Type name, never null or empty.


	//-----------------------------------------------------------------------------------------------------------------

	public SignalType(int theKey, String theName) {

		super(theKey, theName);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Accessor for individual type records.

	public static SignalType getSignalType(int theKey) {

		if (0 == theKey) {
			return nullObject;
		}

		for (SignalType theType : recordCache) {
			if (theKey == theType.key) {
				return theType;
			}
		}
		return nullObject;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The "null object" is a conceptual null, a real object with a key value of 0.

	public static SignalType getNullObject() {

		return nullObject;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The signal type property always defaults to a valid selection rather than an invalid to force UI selection.

	public static SignalType getDefaultObject() {

		return defaultObject;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a list of all types.

	public static ArrayList<KeyedRecord> getSignalTypes() {

		ArrayList<KeyedRecord> result = new ArrayList<KeyedRecord>();

		for (SignalType theType : recordCache) {
			result.add((KeyedRecord)theType);
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a copy of the full native object list.

	public static ArrayList<SignalType> getAllSignalTypes() {

		return new ArrayList<SignalType>(recordCache);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Returns true if there is more than one type.  Used to hide UI when there is only one.

	public static boolean hasMultipleOptions() {

		return (recordCache.size() > 1);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Fill the cache of type records.  Called when the first database connection is opened, see DbCore.

	private static SignalType nullObject = new SignalType(0, "(n/a)");
	private static SignalType invalidObject = new SignalType(-1, "???");
	private static SignalType defaultObject = nullObject;
	private static ArrayList<SignalType> recordCache = new ArrayList<SignalType>();

	public static void loadCache(DbConnection db) throws SQLException {

		recordCache.clear();

		db.query(
		"SELECT " +
			"signal_type_key, " +
			"name, " +
			"is_default " +
		"FROM " +
			"signal_type " +
		"ORDER BY 1");

		SignalType theType;

		while (db.next()) {

			theType = new SignalType(
				db.getInt(1),
				db.getString(2));

			if (db.getBoolean(3)) {
				defaultObject = theType;
			}

			recordCache.add(theType);
		}
	}
}
