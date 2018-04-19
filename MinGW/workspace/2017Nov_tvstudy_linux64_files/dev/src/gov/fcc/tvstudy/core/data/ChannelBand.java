//
//  ChannelBand.java
//  TVStudy
//
//  Copyright (c) 2012-2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.data;

import gov.fcc.tvstudy.core.*;

import java.util.*;
import java.sql.*;


//=====================================================================================================================
// Represents records from the channel_band table, and provides caching data factory methods.

public class ChannelBand extends KeyedRecord {

	// Database properties:

	// key (super)   Channel band key, unique, always > 0.
	// name (super)  Band name, never null or empty.
	// firstChannel  First and last channel numbers in the band.
	// lastChannel

	public final int firstChannel;
	public final int lastChannel;


	//-----------------------------------------------------------------------------------------------------------------

	public ChannelBand(int theKey, String theName, int theFirstChannel, int theLastChannel) {

		super(theKey, theName);

		firstChannel = theFirstChannel;
		lastChannel = theLastChannel;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Accessor for individual band records.

	public static ChannelBand getChannelBand(int theKey) {

		if (0 == theKey) {
			return nullObject;
		}

		for (ChannelBand theBand : recordCache) {
			if (theKey == theBand.key) {
				return theBand;
			}
		}
		return nullObject;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Actually using nulls in the database and null object references in the code for enumerated properties was
	// causing too many headaches.  The "null object" is a conceptual null, a real object with a key value of 0.  It
	// does not exist in the enumeration tables in the database, it is always obtained from accessors like this.  Not
	// all types will have a null object; some are always conceptually non-null.

	public static ChannelBand getNullObject() {

		return nullObject;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This returns an object with key < 0 to represent an undefined/invalid state in the UI.  See comments above.

	public static ChannelBand getInvalidObject() {

		return invalidObject;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a list of all bands.

	public static ArrayList<KeyedRecord> getChannelBands() {

		ArrayList<KeyedRecord> result = new ArrayList<KeyedRecord>();

		for (ChannelBand theBand : recordCache) {
			result.add((KeyedRecord)theBand);
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a list of all bands including the null object.

	public static ArrayList<KeyedRecord> getChannelBandsWithNull() {

		ArrayList<KeyedRecord> result = new ArrayList<KeyedRecord>();

		result.add(nullObject);

		for (ChannelBand theBand : recordCache) {
			result.add((KeyedRecord)theBand);
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a copy of the full native object list.

	public static ArrayList<ChannelBand> getAllChannelBands() {

		return new ArrayList<ChannelBand>(recordCache);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Fill the cache of band records.  Called when the first database connection is opened, see DbCore.  Note the
	// cache does not need to be reloaded after the first open.  Content of the root database tables is constant for a
	// given database version regardless of the specific server, and the application can only open databases of a
	// specific version.  However this can be called more than once, in case errors occur.  Returns false on error.

	private static ChannelBand nullObject = new ChannelBand(0, "(any)", 0, 9999);
	private static ChannelBand invalidObject = new ChannelBand(-1, "???", 0, 0);
	private static ArrayList<ChannelBand> recordCache = new ArrayList<ChannelBand>();

	public static void loadCache(DbConnection db) throws SQLException {

		recordCache.clear();

		db.query(
		"SELECT " +
			"channel_band_key, " +
			"name, " +
			"first_channel, " +
			"last_channel " +
		"FROM " +
			"channel_band " +
		"ORDER BY 1");

		while (db.next()) {
			recordCache.add(new ChannelBand(
				db.getInt(1),
				db.getString(2),
				db.getInt(3),
				db.getInt(4)));
		}
	}
}
