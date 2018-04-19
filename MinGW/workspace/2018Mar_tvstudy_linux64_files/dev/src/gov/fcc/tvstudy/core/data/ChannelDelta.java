//
//  ChannelDelta.java
//  TVStudy
//
//  Copyright (c) 2012-2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.data;

import gov.fcc.tvstudy.core.*;

import java.util.*;
import java.sql.*;


//=====================================================================================================================
// Represents records from the channel_delta table, providing descriptive names for channel relationships needed in
// the interference rule editor.  The delta indicates the relationship of an undesired signal channel to the desired
// signal channel, it is negative if the undesired is on a lower channel than the desired.

public class ChannelDelta extends KeyedRecord {

	// Database properties:

	// key (super)      Channel delta key, unique, always > 0.
	// name (super)     Delta name, never null or empty.
	// recordType       Record type this delta applies to.
	// delta            Numerical channel delta, undesired minus desired.
	// analogOnly       True if the delta applies only to analog TV desired stations.

	public final int recordType;
	public final int delta;
	public final boolean analogOnly;


	//-----------------------------------------------------------------------------------------------------------------

	public ChannelDelta(int theKey, String theName, int theRecordType, int theDelta, boolean theAnalogOnly) {

		super(theKey, theName);

		recordType = theRecordType;
		delta = theDelta;
		analogOnly = theAnalogOnly;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Accessor for individual records.  There is no null object here, if not found a null is returned.

	public static ChannelDelta getChannelDelta(int theKey) {

		for (ChannelDelta theDelta : recordCache) {
			if (theKey == theDelta.key) {
				return theDelta;
			}
		}
		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static ChannelDelta getChannelDeltaByDelta(int recordType, int delta) {

		for (ChannelDelta theDelta : recordCache) {
			if ((theDelta.recordType == recordType) && (theDelta.delta == delta)) {
				return theDelta;
			}
		}
		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static ChannelDelta getInvalidObject() {

		return invalidObject;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a list of all deltas, cast as KeyedRecord objects for use in menus.  May be restricted by record type.

	public static ArrayList<KeyedRecord> getChannelDeltas() {
		return getChannelDeltas(0);
	}

	public static ArrayList<KeyedRecord> getChannelDeltas(int recordType) {

		ArrayList<KeyedRecord> result = new ArrayList<KeyedRecord>();

		for (ChannelDelta theDelta : recordCache) {
			if ((0 == recordType) || (theDelta.recordType == recordType)) {
				result.add((KeyedRecord)theDelta);
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get the full list of deltas as ChannelDelta objects, optionally filtered by record type.

	public static ArrayList<ChannelDelta> getAllChannelDeltas() {
		return getAllChannelDeltas(0);
	}

	public static ArrayList<ChannelDelta> getAllChannelDeltas(int recordType) {

		ArrayList<ChannelDelta> result = new ArrayList<ChannelDelta>();

		for (ChannelDelta theDelta : recordCache) {
			if ((0 == recordType) || (theDelta.recordType == recordType)) {
				result.add(theDelta);
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Fill the cache of delta records.

	private static ChannelDelta invalidObject = new ChannelDelta(-1, "???", 0, 0, false);
	private static ArrayList<ChannelDelta> recordCache = new ArrayList<ChannelDelta>();

	public static void loadCache(DbConnection db) throws SQLException {

		recordCache.clear();

		db.query(
		"SELECT " +
			"channel_delta_key, " +
			"name, " +
			"record_type, " +
			"delta, " +
			"analog_only " +
		"FROM " +
			"channel_delta " +
		"ORDER BY 1");

		while (db.next()) {
			recordCache.add(new ChannelDelta(
				db.getInt(1),
				db.getString(2),
				db.getInt(3),
				db.getInt(4),
				db.getBoolean(5)));
		}
	}
}
