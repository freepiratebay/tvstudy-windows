//
//  Service.java
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.data;

import gov.fcc.tvstudy.core.*;

import java.util.*;
import java.sql.*;


//=====================================================================================================================
// Data representation class for a service table entry.

public class Service extends KeyedRecord {

	// Database properties:

	// key (super)     Service key, unique and always > 0.
	// name (super)    Name, never null or empty.
	// serviceCode     Two-character service code, never null or empty, unique.
	// serviceType     Service type, never null.
	// isDTS           True for DTS service.
	// isOperating     True if the service means an actual real-world operation; rule-makings and auxiliaries are not
	//                  considered operating services, an operating-service record is always preferred.
	// preferenceRank  Numerical ranking to determine preferred records in an MX group.  Higher values are preferred.
	// digitalService  For analog services the equivalent digital service for analog->digital replication, else null.

	public final String serviceCode;
	public final ServiceType serviceType;
	public final boolean isDTS;
	public final boolean isOperating;
	public final int preferenceRank;
	public final Service digitalService;


	//-----------------------------------------------------------------------------------------------------------------

	public Service(int theKey, String theName, String theServiceCode, ServiceType theServiceType, boolean theIsDTS,
			boolean theIsOp, int thePrefRank, Service theDigServ) {

		super(theKey, theName);

		serviceCode = theServiceCode;
		serviceType = theServiceType;
		isDTS = theIsDTS;
		isOperating = theIsOp;
		preferenceRank = thePrefRank;
		digitalService = theDigServ;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Conveniences.

	public boolean isDigital() {

		return serviceType.digital;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isLPTV() {

		return ((ServiceType.SERVTYPE_DTV_LPTV == serviceType.key) ||
			(ServiceType.SERVTYPE_NTSC_LPTV == serviceType.key));
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isClassA() {

		return ((ServiceType.SERVTYPE_DTV_CLASS_A == serviceType.key) ||
			(ServiceType.SERVTYPE_NTSC_CLASS_A == serviceType.key));
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Retrieve a service by key.  If the key is not found, return null.

	public static Service getService(int theKey) {

		return serviceKeyCache.get(Integer.valueOf(theKey));
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Retrieve a service by service code, return null if not found.

	public static Service getService(String theServiceCode) {

		return serviceCodeCache.get(theServiceCode);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static Service getInvalidObject() {

		return invalidObject;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a list of all services cast as KeyedRecord objects, optionally filtered for record type.

	public static ArrayList<KeyedRecord> getServices() {
		return getServices(0);
	}

	public static ArrayList<KeyedRecord> getServices(int recordType) {

		ArrayList<KeyedRecord> result = new ArrayList<KeyedRecord>();

		for (Service theService : serviceCache) {
			if ((0 == recordType) || (theService.serviceType.recordType == recordType)) {
				result.add((KeyedRecord)theService);
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get the full list as Service objects.

	public static ArrayList<Service> getAllServices() {

		return new ArrayList<Service>(serviceCache);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Fill the cache of service objects.  First load the ServiceType cache so references can be resolved.  Query is
	// ordered to put digital services first so the back-references from analog services will resolve.

	private static Service invalidObject =
		new Service(-1, "???", "??", ServiceType.getInvalidObject(), false, false, 0, null);
	private static ArrayList<Service> serviceCache = new ArrayList<Service>();
	private static HashMap<Integer, Service> serviceKeyCache = new HashMap<Integer, Service>();
	private static HashMap<String, Service> serviceCodeCache = new HashMap<String, Service>();

	public static void loadCache(DbConnection db) throws SQLException {

		ServiceType.loadCache(db);

		serviceCache.clear();
		serviceKeyCache.clear();
		serviceCodeCache.clear();

		db.query(
		"SELECT " +
			"service.service_key, " +
			"service.name, " +
			"service.service_code, " +
			"service.service_type_key, " +
			"service.is_dts, " +
			"service.is_operating, " +
			"service.preference_rank, " +
			"service.digital_service_key " +
		"FROM " +
			"service " +
			"JOIN service_type USING (service_type_key) " +
		"ORDER BY " +
			"service_type.digital DESC, " +
			"service.service_key");

		Service theService;

		while (db.next()) {

			theService = new Service(
				db.getInt(1),
				db.getString(2),
				db.getString(3),
				ServiceType.getServiceType(db.getInt(4)),
				db.getBoolean(5),
				db.getBoolean(6),
				db.getInt(7),
				serviceKeyCache.get(Integer.valueOf(db.getInt(8))));

			serviceCache.add(theService);
			serviceKeyCache.put(Integer.valueOf(theService.key), theService);
			serviceCodeCache.put(theService.serviceCode, theService);
		}
	}
}
