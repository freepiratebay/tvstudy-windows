//
//  ServiceType.java
//  TVStudy
//
//  Copyright (c) 2012-2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.data;

import gov.fcc.tvstudy.core.*;

import java.util.*;
import java.sql.*;


//=====================================================================================================================
// Data representation class for a service type table entry.

public class ServiceType extends KeyedRecord {

	// Constants must match database keys.

	public static final int SERVTYPE_DTV_FULL = 1;       // TV full service digital.
	public static final int SERVTYPE_NTSC_FULL = 2;      // TV full service analog.
	public static final int SERVTYPE_DTV_CLASS_A = 3;    // TV Class A digital.
	public static final int SERVTYPE_NTSC_CLASS_A = 4;   // TV Class A analog.
	public static final int SERVTYPE_DTV_LPTV = 5;       // LPTV/translator/booster digital.
	public static final int SERVTYPE_NTSC_LPTV = 6;      // LPTV/translator/booster analog.
	public static final int SERVTYPE_WIRELESS = 11;      // Wireless.
	public static final int SERVTYPE_FM_FULL = 21;       // FM full-service.
	public static final int SERVTYPE_FM_LP = 22;         // FM low power.
	public static final int SERVTYPE_FM_TX = 23;         // FM translator/booster.

	// Database properties:

	// key (super)        Service type key, unique and always > 0.
	// name (super)       Name, never null or empty.
	// recordType         Record type for the service, Source.RECORD_TYPE_*.
	// digital            True if the TV service is digital, else analog.  Not used for wireless or FM.
	// needsEmissionMask  True if the TV service requires an emission mask selection in an interference rule, see
	//                      IxRule.  Not used for wireless or FM.

	public final int recordType;
	public final boolean digital;
	public final boolean needsEmissionMask;


	//-----------------------------------------------------------------------------------------------------------------

	public ServiceType(int theKey, String theName, int theRecordType, boolean theDigital,
			boolean theNeedsEmissionMask) {

		super(theKey, theName);

		recordType = theRecordType;
		digital = theDigital;
		needsEmissionMask = theNeedsEmissionMask;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Retrieve a service type by key.  If the key is not found, return null.

	public static ServiceType getServiceType(int theKey) {

		return recordKeyCache.get(Integer.valueOf(theKey));
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static ServiceType getInvalidObject() {

		return invalidObject;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a list of service types for menus.  May be filtered to a specific record type.

	public static ArrayList<KeyedRecord> getServiceTypes() {
		return getServiceTypes(0);
	}

	public static ArrayList<KeyedRecord> getServiceTypes(int recordType) {

		ArrayList<KeyedRecord> result = new ArrayList<KeyedRecord>();

		for (ServiceType theType : recordCache) {
			if ((0 == recordType) || (theType.recordType == recordType)) {
				result.add((KeyedRecord)theType);
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get full object list.

	public static ArrayList<ServiceType> getAllServiceTypes() {

		return new ArrayList<ServiceType>(recordCache);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Fill the cache of service type objects.

	private static ServiceType invalidObject = new ServiceType(-1, "???", 0, false, false);
	private static ArrayList<ServiceType> recordCache = new ArrayList<ServiceType>();
	private static HashMap<Integer, ServiceType> recordKeyCache = new HashMap<Integer, ServiceType>();

	public static void loadCache(DbConnection db) throws SQLException {

		recordCache.clear();
		recordKeyCache.clear();

		db.query(
		"SELECT " +
			"service_type_key, " +
			"name, " +
			"record_type, " +
			"digital, " +
			"needs_emission_mask " +
		"FROM " +
			"service_type " +
		"ORDER BY 1");

		ServiceType theType;

		while (db.next()) {

			theType = new ServiceType(
				db.getInt(1),
				db.getString(2),
				db.getInt(3),
				db.getBoolean(4),
				db.getBoolean(5));

			recordCache.add(theType);
			recordKeyCache.put(Integer.valueOf(theType.key), theType);
		}
	}
}
