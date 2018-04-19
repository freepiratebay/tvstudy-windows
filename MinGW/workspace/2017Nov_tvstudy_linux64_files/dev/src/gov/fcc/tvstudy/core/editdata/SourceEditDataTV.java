//
//  SourceEditDataTV.java
//  TVStudy
//
//  Copyright (c) 2015-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.editdata;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.geo.*;

import java.util.*;
import java.util.regex.*;
import java.io.*;
import java.sql.*;

import javax.xml.parsers.*;

import org.xml.sax.*;
import org.xml.sax.helpers.*;


//=====================================================================================================================
// Mutable model subclass to support editing of TV sources, see SourceEditData.  The immutable properties used for
// identification include service and country (in the superclass), plus facility ID.  Also the properties defining the
// internal structure of a DTS operation (a hierarchy of multiple source objects) are also immutable to protect that
// structure.  See also SourceTV which is the database representation class wrapped by this for an existing record.

public class SourceEditDataTV extends SourceEditData implements Record {

	public final int facilityID;
	public final boolean isDRT;

	public final Integer originalSourceKey;
	public final Integer parentSourceKey;

	public final boolean isParent;

	private SourceTV source;

	public int channel;
	public Zone zone;
	public String status;
	public int statusType;
	public String appARN;
	public SignalType signalType;
	public FrequencyOffset frequencyOffset;
	public EmissionMask emissionMask;
	public double dtsMaximumDistance;
	public String dtsSectors;
	public int siteNumber;

	public int serviceAreaMode;
	public double serviceAreaArg;
	public double serviceAreaCL;
	public int serviceAreaKey;

	public double dtsTimeDelay;

	// The set of sources for a DTS parent has to be managed similar to the way StudyEditData manages the full set of
	// all sources; DTS sources are only stored in their parent and do not appear directly in the full study set.

	private TreeMap<Integer, SourceEditDataTV> dtsSources;
	private HashSet<Integer> addedDTSSourceKeys;
	private HashSet<Integer> deletedDTSSourceKeys;
	private ArrayList<SourceEditDataTV> changedDTSSources;

	// This is an optimization cache, see getDTSSources().

	private ArrayList<SourceEditDataTV> dtsSourceListCache;

	// See getRuleExtraDistance().

	public static final double DEFAULT_RULE_EXTRA_DISTANCE = 163.;


	//-----------------------------------------------------------------------------------------------------------------
	// Create an object for an existing source record from a database.  If from a study database the study argument
	// will be non-null, however that may also be null for source records from other types of databases.

	public SourceEditDataTV(StudyEditData theStudy, SourceTV theSource) {

		super(theStudy, theSource.dbID, Source.RECORD_TYPE_TV, Integer.valueOf(theSource.key), theSource.service,
			theSource.country, theSource.isLocked, theSource.userRecordID, theSource.extDbKey, theSource.extRecordID);

		source = theSource;

		facilityID = source.facilityID;
		isDRT = source.isDRT;

		originalSourceKey = source.originalSourceKey;
		parentSourceKey = source.parentSourceKey;

		isParent = source.isParent;

		callSign = source.callSign;
		channel = source.channel;
		city = source.city;
		state = source.state;
		zone = source.zone;
		status = source.status;
		statusType = ExtDbRecord.getStatusType(source.status);
		fileNumber = source.fileNumber;
		appARN = "";
		for (int i = 0; i < fileNumber.length(); i++) {
			if (Character.isDigit(fileNumber.charAt(i))) {
				appARN = fileNumber.substring(i);
				break;
			}
		}
		signalType = source.signalType;
		frequencyOffset = source.frequencyOffset;
		emissionMask = source.emissionMask;
		location.setLatLon(source.location);
		dtsMaximumDistance = source.dtsMaximumDistance;
		dtsSectors = source.dtsSectors;
		heightAMSL = source.heightAMSL;
		overallHAAT = source.overallHAAT;
		peakERP = source.peakERP;
		antennaID = source.antennaID;
		hasHorizontalPattern = source.hasHorizontalPattern;
		horizontalPattern = null;
		horizontalPatternChanged = false;
		horizontalPatternOrientation = source.horizontalPatternOrientation;
		hasVerticalPattern = source.hasVerticalPattern;
		verticalPattern = null;
		verticalPatternChanged = false;
		verticalPatternElectricalTilt = source.verticalPatternElectricalTilt;
		verticalPatternMechanicalTilt = source.verticalPatternMechanicalTilt;
		verticalPatternMechanicalTiltOrientation = source.verticalPatternMechanicalTiltOrientation;
		hasMatrixPattern = source.hasMatrixPattern;
		matrixPattern = null;
		matrixPatternChanged = false;
		useGenericVerticalPattern = source.useGenericVerticalPattern;
		siteNumber = source.siteNumber;

		serviceAreaMode = source.serviceAreaMode;
		serviceAreaArg = source.serviceAreaArg;
		serviceAreaCL = source.serviceAreaCL;
		serviceAreaKey = source.serviceAreaKey;

		dtsTimeDelay = source.dtsTimeDelay;

		setAllAttributes(source.attributes);

		if (isParent) {
			dtsSources = new TreeMap<Integer, SourceEditDataTV>();
			SourceEditDataTV newDTSSource;
			for (SourceTV dtsSource : source.dtsSources) {
				newDTSSource = new SourceEditDataTV(study, dtsSource);
				dtsSources.put(newDTSSource.key, newDTSSource);
			}
			addedDTSSourceKeys = new HashSet<Integer>();
			deletedDTSSourceKeys = new HashSet<Integer>();
			changedDTSSources = new ArrayList<SourceEditDataTV>();
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Private constructor to support other methods e.g. copy(), createSource(), just sets the final properties.

	private SourceEditDataTV(StudyEditData theStudy, String theDbID, Integer theKey, int theFacilityID,
			Service theService, boolean theIsDRT, Country theCountry, boolean theIsLocked, Integer theUserRecordID,
			Integer theExtDbKey, String theExtRecordID, Integer theOriginalSourceKey, Integer theParentSourceKey,
			boolean theIsParent) {

		super(theStudy, theDbID, Source.RECORD_TYPE_TV, theKey, theService, theCountry, theIsLocked, theUserRecordID,
			theExtDbKey, theExtRecordID);

		facilityID = theFacilityID;
		isDRT = theIsDRT;

		originalSourceKey = theOriginalSourceKey;
		parentSourceKey = theParentSourceKey;

		isParent = theIsParent;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Accessor for the underlying record object.  May be null.

	public Source getSource() {

		return source;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create an identical copy, the purpose is to support multiple transient states of the mutable properties in an
	// editor.  In model contexts the copy and original will be treated as identical and interchangeable since they
	// have the same key and the same Source object.  Patterns are shallow copies as those objects are entirely
	// replaced by editor action, not modified in place.  However the individual sources list for a DTS parent is a
	// deep copy because that list may be modified directly through the parent, see SourceEditor.

	public SourceEditDataTV copy() {

		SourceEditDataTV theCopy = new SourceEditDataTV(study, dbID, key, facilityID, service, isDRT, country,
			isLocked, userRecordID, extDbKey, extRecordID, originalSourceKey, parentSourceKey, isParent);

		theCopy.source = source;

		theCopy.callSign = callSign;
		theCopy.channel = channel;
		theCopy.city = city;
		theCopy.state = state;
		theCopy.zone = zone;
		theCopy.status = status;
		theCopy.statusType = statusType;
		theCopy.fileNumber = fileNumber;
		theCopy.appARN = appARN;
		theCopy.signalType = signalType;
		theCopy.frequencyOffset = frequencyOffset;
		theCopy.emissionMask = emissionMask;
		theCopy.location.setLatLon(location);
		theCopy.dtsMaximumDistance = dtsMaximumDistance;
		theCopy.dtsSectors = dtsSectors;
		theCopy.heightAMSL = heightAMSL;
		theCopy.overallHAAT = overallHAAT;
		theCopy.peakERP = peakERP;
		theCopy.antennaID = antennaID;
		theCopy.hasHorizontalPattern = hasHorizontalPattern;
		theCopy.horizontalPattern = horizontalPattern;
		theCopy.horizontalPatternChanged = horizontalPatternChanged;
		theCopy.horizontalPatternOrientation = horizontalPatternOrientation;
		theCopy.hasVerticalPattern = hasVerticalPattern;
		theCopy.verticalPattern = verticalPattern;
		theCopy.verticalPatternChanged = verticalPatternChanged;
		theCopy.verticalPatternElectricalTilt = verticalPatternElectricalTilt;
		theCopy.verticalPatternMechanicalTilt = verticalPatternMechanicalTilt;
		theCopy.verticalPatternMechanicalTiltOrientation = verticalPatternMechanicalTiltOrientation;
		theCopy.hasMatrixPattern = hasMatrixPattern;
		theCopy.matrixPattern = matrixPattern;
		theCopy.matrixPatternChanged = matrixPatternChanged;
		theCopy.useGenericVerticalPattern = useGenericVerticalPattern;
		theCopy.siteNumber = siteNumber;

		theCopy.serviceAreaMode = serviceAreaMode;
		theCopy.serviceAreaArg = serviceAreaArg;
		theCopy.serviceAreaCL = serviceAreaCL;
		theCopy.serviceAreaKey = serviceAreaKey;

		theCopy.dtsTimeDelay = dtsTimeDelay;

		theCopy.setAllAttributes(attributes);

		if (theCopy.isParent) {
			theCopy.dtsSources = new TreeMap<Integer, SourceEditDataTV>();
			for (SourceEditDataTV dtsSource : dtsSources.values()) {
				theCopy.dtsSources.put(dtsSource.key, dtsSource.copy());
			}
			theCopy.addedDTSSourceKeys = new HashSet<Integer>(addedDTSSourceKeys);
			theCopy.deletedDTSSourceKeys = new HashSet<Integer>(deletedDTSSourceKeys);
			theCopy.changedDTSSources = new ArrayList<SourceEditDataTV>();
		}

		return theCopy;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create a new source object with no association to an existing source record or any underlying primary record.
	// Defaults are set in most properties, caller must subsequently populate the mutable properties as needed and
	// validate before attempting to save.  This is not simply a constructor form because it can fail; a new key is
	// assigned and the key range may be exhausted.  If the study is null a temporary key is assigned.

	public static SourceEditDataTV createSource(StudyEditData newStudy, String newDbID, int newFacilityID,
			Service newService, boolean newIsDRT, Country newCountry, boolean newIsLocked, ErrorLogger errors) {

		return createSource(newStudy, newDbID, newFacilityID, newService, newIsDRT, newCountry, newIsLocked, null,
			null, null, null, errors);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Source objects are also used to represent records from a generic import data set, see ExtDb.  These sources do
	// not have a study context but they do have external data set references and unique keys within the import set.
	// This method creates a new source to be part of such an import, later to be saved directly in the import
	// database using the SourceEditData save() methods.  The ExtDb object provides the source key, the record ID is
	// the source key as a string.  These are always locked.

	public static SourceEditDataTV createExtSource(ExtDb extDb, int newFacilityID, Service newService,
			boolean newIsDRT, Country newCountry, ErrorLogger errors) {

		if ((Source.RECORD_TYPE_TV != extDb.recordType) || !extDb.isGeneric()) {
			if (null != errors) {
				errors.reportError("Cannot create new record, invalid record type or data type.");
			}
			return null;
		}

		Integer newKey = extDb.getNewRecordKey();
		if (null == newKey) {
			if (null != errors) {
				errors.reportError("Cannot create new record, no keys available.");
			}
			return null;
		}

		return createSource(null, extDb.dbID, newKey, newFacilityID, newService, newIsDRT, newCountry, true,
			null, extDb.dbKey, String.valueOf(newKey), null);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Private versions of createSource() allow all properties to be established including DTS parentage, these are
	// only for use by other source-creation methods, e.g. makeSource().  Note the database ID argument is not used if
	// the study is non-null, the study's ID is used.  If study is null the ID should always be non-null.

	private static SourceEditDataTV createSource(StudyEditData newStudy, String newDbID, int newFacilityID,
			Service newService, boolean newIsDRT, Country newCountry, boolean newIsLocked, Integer newUserRecordID,
			Integer newExtDbKey, String newExtRecordID, Integer newParentSourceKey, ErrorLogger errors) {

		Integer newKey;
		if (null != newStudy) {
			newKey = newStudy.getNewSourceKey();
			newDbID = newStudy.dbID;
		} else {
			newKey = getTemporaryKey();
		}
		if (null == newKey) {
			if (null != errors) {
				errors.reportError("Cannot create new record, no keys available.");
			}
			return null;
		}

		return createSource(newStudy, newDbID, newKey, newFacilityID, newService, newIsDRT, newCountry, newIsLocked,
			newUserRecordID, newExtDbKey, newExtRecordID, newParentSourceKey);
	}

	private static SourceEditDataTV createSource(StudyEditData newStudy, String newDbID, Integer newKey,
			int newFacilityID, Service newService, boolean newIsDRT, Country newCountry, boolean newIsLocked,
			Integer newUserRecordID, Integer newExtDbKey, String newExtRecordID, Integer newParentSourceKey) {

		if (null == newExtRecordID) {
			newExtDbKey = null;
		}

		boolean newIsParent = (newService.isDTS & (null == newParentSourceKey));

		SourceEditDataTV newSource = new SourceEditDataTV(newStudy, newDbID, newKey, newFacilityID, newService,
			newIsDRT, newCountry, newIsLocked, newUserRecordID, newExtDbKey, newExtRecordID, null, newParentSourceKey,
			newIsParent);

		newSource.callSign = "";
		newSource.channel = 0;
		newSource.city = "";
		newSource.state = "";
		newSource.zone = Zone.getNullObject();
		newSource.status = "";
		newSource.statusType = ExtDbRecord.STATUS_TYPE_OTHER;
		newSource.fileNumber = "";
		newSource.appARN = "";
		if (newService.serviceType.digital) {
			newSource.signalType = SignalType.getDefaultObject();
		} else {
			newSource.signalType = SignalType.getNullObject();
		}
		newSource.frequencyOffset = FrequencyOffset.getNullObject();
		if (newService.serviceType.needsEmissionMask) {
			newSource.emissionMask = EmissionMask.getInvalidObject();
		} else {
			newSource.emissionMask = EmissionMask.getNullObject();
		}
		newSource.dtsMaximumDistance = 0.;
		newSource.dtsSectors = "";
		newSource.heightAMSL = 0.;
		newSource.overallHAAT = 0.;
		newSource.peakERP = Source.ERP_DEF;
		newSource.antennaID = null;
		newSource.hasHorizontalPattern = false;
		newSource.horizontalPattern = null;
		newSource.horizontalPatternChanged = false;
		newSource.horizontalPatternOrientation = 0.;
		newSource.hasVerticalPattern = false;
		newSource.verticalPattern = null;
		newSource.verticalPatternChanged = false;
		newSource.verticalPatternElectricalTilt = 0.;
		newSource.verticalPatternMechanicalTilt = 0.;
		newSource.verticalPatternMechanicalTiltOrientation = 0.;
		newSource.hasMatrixPattern = false;
		newSource.matrixPattern = null;
		newSource.matrixPatternChanged = false;
		newSource.useGenericVerticalPattern = true;
		newSource.siteNumber = 0;

		newSource.serviceAreaMode = Source.SERVAREA_CONTOUR_DEFAULT;
		newSource.serviceAreaCL = Source.SERVAREA_CL_DEFAULT;

		if (newIsParent) {
			newSource.dtsSources = new TreeMap<Integer, SourceEditDataTV>();
			newSource.addedDTSSourceKeys = new HashSet<Integer>();
			newSource.deletedDTSSourceKeys = new HashSet<Integer>();
			newSource.changedDTSSources = new ArrayList<SourceEditDataTV>();
		}

		return newSource;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create a new source object associated with an underlying primary record.  Most source objects will initially be
	// created by this method.  Most of the logic for this is in ExtDbRecordTV.updateSource(), that will populate
	// all of the mutable source properties.  But the process has to be managed from this class to protect the
	// immutable properties and ensure the association between primary identifiers (data set key and record ID) and
	// the record properties remains valid.  The study object may be null to make a source outside any study.  Note a
	// very important small detail here.  The data set key assigned to a source object is always a key for an actual
	// imported data set, meaning the dbKey property in the ExtDb object, not the key property.  The key may be a
	// generic value representing a "use most recent" behavior on lookup.  But the "use most recent" behavior is only
	// for setting defaults and for transient UI operations.  When a data set record is instantiated into a persistent
	// context by creating a source object, it's reference must become specific.

	public static SourceEditDataTV makeSourceTV(ExtDbRecordTV theRecord, StudyEditData theStudy, boolean theIsLocked) {
		return makeSourceTV(theRecord, theStudy, theIsLocked, null);
	}

	public static SourceEditDataTV makeSourceTV(ExtDbRecordTV theRecord, StudyEditData theStudy, boolean theIsLocked,
			ErrorLogger errors) {

		SourceEditDataTV theSource = createSource(theStudy, theRecord.extDb.dbID, theRecord.facilityID,
			theRecord.service, theRecord.isDRT, theRecord.country, theIsLocked, null, theRecord.extDb.dbKey,
			theRecord.extRecordID, null, errors);
		if (null == theSource) {
			return null;
		}

		// Back to ExtDbRecordTV for the rest, in the case of DTS it will call back to addDTSSource().

		if (!theRecord.updateSource(theSource, errors)) {
			return null;
		}

		return theSource;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create an import data set source from an external data set record (yes this makes sense, it supports the XML
	// parser being used for import into a data set).  See createExtSource() for details on what this is all about.

	public static SourceEditDataTV makeExtSourceTV(ExtDbRecordTV theRecord, ExtDb extDb) {
		return makeExtSourceTV(theRecord, extDb, null);
	}

	public static SourceEditDataTV makeExtSourceTV(ExtDbRecordTV theRecord, ExtDb extDb, ErrorLogger errors) {

		SourceEditDataTV theSource = createExtSource(extDb, theRecord.facilityID, theRecord.service,
			theRecord.isDRT, theRecord.country, errors);
		if (null == theSource) {
			return null;
		}

		if (!theRecord.updateSource(theSource, errors)) {
			return null;
		}

		return theSource;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a DTS source to this parent using a station data record, see updateSource() in ExtDbRecordTV.

	public SourceEditDataTV addDTSSource(ExtDbRecordTV theRecord) {
		return addDTSSource(theRecord, null);
	}

	public SourceEditDataTV addDTSSource(ExtDbRecordTV theRecord, ErrorLogger errors) {

		if (!isParent) {
			if (null != errors) {
				errors.reportError("SourceEditDataTV.addDTSSource() called on non-parent source.");
			}
			return null;
		}

		// If this is the reference facility (site number 0) the service and record ID are taken from the record as
		// they may be different than the parent, otherwise those are copied from the parent.  If somehow the record
		// does not have matching values then updateSource() will fail, as it should.  For the reference facility the
		// service can be any non-DTS service.

		Service theService = service;
		String theExtRecordID = extRecordID;

		if (0 == theRecord.siteNumber) {
			theService = theRecord.service;
			if (theService.isDTS) {
				if (null != errors) {
					errors.reportError("SourceEditDataTV.addDTSSource() cannot use DTS record as reference facility.");
				}
				return null;
			}
			theExtRecordID = theRecord.extRecordID;
		}

		// Facility ID and country are always copied from the parent.

		SourceEditDataTV theSource = createSource(study, dbID, facilityID, theService, false, country, isLocked,
			null, extDbKey, theExtRecordID, key, errors);
		if (null == theSource) {
			return null;
		}

		// Service area mode is always FCC contours for the reference facility, else left at defaults.  The parent
		// does not use the service area mode properties.

		if (0 == theRecord.siteNumber) {
			theSource.serviceAreaMode = Source.SERVAREA_CONTOUR_FCC;
			theSource.serviceAreaCL = Source.SERVAREA_CL_DEFAULT;
		}

		// All the rest is copied from the record.

		if (!theRecord.updateSource(theSource, errors)) {
			return null;
		}

		// Add source to the list.

		dtsSources.put(theSource.key, theSource);
		addedDTSSourceKeys.add(theSource.key);
		dtsSourceListCache = null;

		return theSource;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a "dummy" reference facility source to a DTS parent, used when building from a data set and no reference
	// facility record is found, see updateSource() in ExtDbRecordTV.  This must be a parent record, and there must
	// not already be a reference facility, otherwise there are no restrictions.  The DTS reference point is used as
	// the transmitter location, heights are set so the study engine will derive a AMSL from the minimum AGL, and the
	// ERP is set to a small value that ensures the contour does not extend outside the distance limit circle.

	public boolean addDTSReferenceSource(ErrorLogger errors) {

		if (!isParent) {
			if (null != errors) {
				errors.reportError("SourceEditDataTV.addDTSReferenceSource() called on non-parent source.");
			}
			return false;
		}
		for (SourceEditDataTV dtsSource : dtsSources.values()) {
			if (0 == dtsSource.siteNumber) {
				if (null != errors) {
					errors.reportError("DTS parent source already has a reference facility.");
				}
				return false;
			}
		}

		Service theService = Service.getService("DT");
		if (null == theService) {
			if (null != errors) {
				errors.reportError("SourceEditDataTV.addDTSReferenceSource() could not load service record.");
			}
			return false;
		}

		SourceEditDataTV theSource = createSource(study, dbID, facilityID, theService, false, country, isLocked,
			null, null, null, key, errors);
		if (null == theSource) {
			return false;
		}

		theSource.siteNumber = 0;

		theSource.callSign = callSign;
		theSource.city = city;
		theSource.state = state;
		theSource.channel = channel;
		theSource.zone = zone;

		theSource.location.setLatLon(location);
		theSource.heightAMSL = Source.HEIGHT_DERIVE;
		theSource.overallHAAT = Source.HEIGHT_DERIVE;
		theSource.peakERP = 1.;

		// Add source to the list.

		dtsSources.put(theSource.key, theSource);
		addedDTSSourceKeys.add(theSource.key);
		dtsSourceListCache = null;

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Derive a new source from the current, assigning a new key and allowing various immutable properties to be
	// altered including study, facility ID, service, country, and locked status.  As with createSource(), the caller
	// may need to populate some mutable properties and validate before saving.  The new study may be null to create a
	// source that is not part of any existing study.  To fully derive the new source it may be necessary to load
	// pattern data so this may indirectly use a database connection.  If the facility ID, service, or country are set,
	// even if the new values are identical, the user record ID, station data key, and external record ID are cleared.
	// An association with a primary data record means those properties must always be identical to the underlying
	// record, so changing them or even intending to change them means the association is invalidated.  If this object
	// is associated with a primary data record and a new study is provided that is associated with a different
	// database, again the primary IDs are cleared since those are database-specific.  Also the DRT flag is implicitly
	// paired with particular services so if the service changes, DRT is cleared regardless.  Some derivations don't
	// make sense and are not allowed.  A replication source cannot be used to derive another, the core properties on
	// a replication source must always be the same as the original source being replicated.  Changing the service from
	// DTS to non-DTS on the parent is ambiguous so is not allowed, although that can be done on any of the individual
	// sources under the parent.  If the existing and new services are both DTS, the derivation can only be performed
	// on the parent because the individual sources will be recursively derived as well.  Finally, an unlocked source
	// cannot be locked again.  When a source is locked all properties must be identical to any underlying primary
	// record, so a source that may have been modified cannot safely be locked again.  This could enforce the contract
	// by comparing and reverting properties to allow the lock, but there is currently no practical use for that
	// capability so it is not supported.

	public SourceEditData deriveSource(StudyEditData newStudy, boolean newIsLocked, ErrorLogger errors) {
		return deriveSourceTV(newStudy, facilityID, service, country, newIsLocked, false, errors);
	}

	public SourceEditDataTV deriveSourceTV(int newFacilityID, Service newService, Country newCountry,
			boolean newIsLocked) {
		return deriveSourceTV(study, newFacilityID, newService, newCountry, newIsLocked, true, null);
	}

	public SourceEditDataTV deriveSourceTV(int newFacilityID, Service newService, Country newCountry,
			boolean newIsLocked, ErrorLogger errors) {
		return deriveSourceTV(study, newFacilityID, newService, newCountry, newIsLocked, true, errors);
	}

	private SourceEditDataTV deriveSourceTV(StudyEditData newStudy, int newFacilityID, Service newService,
			Country newCountry, boolean newIsLocked, boolean clearPrimaryIDs, ErrorLogger errors) {

		String newDbID = dbID;
		Integer newUserRecordID = userRecordID;
		Integer newExtDbKey = extDbKey;
		String newExtRecordID = extRecordID;

		if ((null != newStudy) && !newStudy.dbID.equals(dbID)) {
			newDbID = newStudy.dbID;
			clearPrimaryIDs = true;
		}

		if (clearPrimaryIDs) {
			newUserRecordID = null;
			newExtDbKey = null;
			newExtRecordID = null;
		}

		boolean newIsDRT = isDRT;
		if (!newService.equals(service)) {
			newIsDRT = false;
		}

		if ((null != originalSourceKey) || (isParent && !newService.isDTS) ||
				((null != parentSourceKey) && service.isDTS && newService.isDTS)) {
			if (null != errors) {
				errors.reportError("The record derivation cannot be performed.");
			}
			return null;
		}

		if (!isLocked && newIsLocked) {
			if (null != errors) {
				errors.reportError("Unlocked records cannot be locked again.");
			}
			return null;
		}

		return deriveSourceTV(newStudy, newDbID, newFacilityID, newService, newIsDRT, newCountry, newIsLocked,
			newUserRecordID, newExtDbKey, newExtRecordID, null, errors);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Private derivation supports changing primary IDs and recursive derivation of DTS, see discussion for public
	// methods above.  When the service is changed from non-DTS to DTS, the assumption is the current source defines
	// parameters for both the DTS parent and the reference facility, both of those sources will be created and the
	// parent returned with the reference facility already attached.

	private SourceEditDataTV deriveSourceTV(StudyEditData newStudy, String newDbID, int newFacilityID,
			Service newService, boolean newIsDRT, Country newCountry, boolean newIsLocked, Integer newUserRecordID,
			Integer newExtDbKey, String newExtRecordID, Integer newParentSourceKey, ErrorLogger errors) {

		SourceEditDataTV newSource = createSource(newStudy, newDbID, newFacilityID, newService, newIsDRT, newCountry,
			newIsLocked, newUserRecordID, newExtDbKey, newExtRecordID, newParentSourceKey, errors);
		if (null == newSource) {
			return null;
		}

		SourceEditDataTV parentSource = null;

		if (newSource.isParent && !isParent) {

			parentSource = newSource;

			parentSource.callSign = callSign;
			parentSource.channel = channel;
			parentSource.city = city;
			parentSource.state = state;
			parentSource.zone = zone;
			parentSource.status = status;
			parentSource.statusType = statusType;
			parentSource.fileNumber = fileNumber;
			parentSource.appARN = appARN;
			if (service.serviceType.digital) {
				parentSource.signalType = signalType;
			} else {
				parentSource.signalType = SignalType.getDefaultObject();
			}
			parentSource.location.setLatLon(location);

			newSource = parentSource.createDTSSource(0, service, errors);
			if (null == newSource) {
				return null;
			}
			parentSource.addOrReplaceDTSSource(newSource);

			parentSource.setAllAttributes(attributes);

		} else {

			newSource.siteNumber = siteNumber;

			newSource.setAllAttributes(attributes);
		}

		newSource.callSign = callSign;
		newSource.channel = channel;
		newSource.city = city;
		newSource.state = state;
		newSource.zone = zone;
		newSource.status = status;
		newSource.statusType = statusType;
		newSource.fileNumber = fileNumber;
		newSource.appARN = appARN;
		newSource.signalType = signalType;
		newSource.frequencyOffset = frequencyOffset;
		newSource.emissionMask = emissionMask;
		newSource.location.setLatLon(location);
		newSource.dtsMaximumDistance = dtsMaximumDistance;
		newSource.dtsSectors = dtsSectors;
		newSource.heightAMSL = heightAMSL;
		newSource.overallHAAT = overallHAAT;
		newSource.peakERP = peakERP;
		newSource.antennaID = antennaID;
		newSource.hasHorizontalPattern = false;
		newSource.horizontalPattern = null;
		newSource.horizontalPatternChanged = false;
		newSource.horizontalPatternOrientation = horizontalPatternOrientation;
		newSource.hasVerticalPattern = false;
		newSource.verticalPattern = null;
		newSource.verticalPatternChanged = false;
		newSource.verticalPatternElectricalTilt = verticalPatternElectricalTilt;
		newSource.verticalPatternMechanicalTilt = verticalPatternMechanicalTilt;
		newSource.verticalPatternMechanicalTiltOrientation = verticalPatternMechanicalTiltOrientation;
		newSource.hasMatrixPattern = false;
		newSource.matrixPattern = null;
		newSource.matrixPatternChanged = false;
		newSource.useGenericVerticalPattern = useGenericVerticalPattern;

		newSource.serviceAreaMode = serviceAreaMode;
		newSource.serviceAreaArg = serviceAreaArg;
		newSource.serviceAreaCL = serviceAreaCL;
		newSource.serviceAreaKey = serviceAreaKey;

		newSource.dtsTimeDelay = dtsTimeDelay;

		// Load and copy pattern data as needed, see comments above.

		if (hasHorizontalPattern) {
			if ((null == horizontalPattern) && (null != source)) {
				horizontalPattern = source.getHorizontalPattern(errors);
			}
			if (null != horizontalPattern) {
				newSource.hasHorizontalPattern = true;
				newSource.horizontalPattern = horizontalPattern.copy();
				newSource.horizontalPatternChanged = true;
			}
		}

		if (hasVerticalPattern) {
			if ((null == verticalPattern) && (null != source)) {
				verticalPattern = source.getVerticalPattern(errors);
			}
			if (null != verticalPattern) {
				newSource.hasVerticalPattern = true;
				newSource.verticalPattern = verticalPattern.copy();
				newSource.verticalPatternChanged = true;
			}
		}

		if (hasMatrixPattern) {
			if ((null == matrixPattern) && (null != source)) {
				matrixPattern = source.getMatrixPattern(errors);
			}
			if (null != matrixPattern) {
				newSource.hasMatrixPattern = true;
				newSource.matrixPattern = matrixPattern.copy();
				newSource.matrixPatternChanged = true;
			}
		}

		// If deriving a new DTS parent from a non-DTS source, that's all.

		if (null != parentSource) {
			return parentSource;
		}

		// Recursively derive individual sources if deriving one DTS parent to another; however the reference facility
		// source in this case does not get the new service, that remains unchanged.

		if (newSource.isParent && isParent) {
			newSource.dtsSources = new TreeMap<Integer, SourceEditDataTV>();
			newSource.addedDTSSourceKeys = new HashSet<Integer>();
			SourceEditDataTV newDTSSource;
			for (SourceEditDataTV dtsSource : dtsSources.values()) {
				if (0 == dtsSource.siteNumber) {
					newDTSSource = dtsSource.deriveSourceTV(newStudy, newDbID, newFacilityID, dtsSource.service,
						dtsSource.isDRT, newCountry, newIsLocked, newUserRecordID, newExtDbKey, newExtRecordID,
						newSource.key, errors);
				} else {
					newDTSSource = dtsSource.deriveSourceTV(newStudy, newDbID, newFacilityID, newService, newIsDRT,
						newCountry, newIsLocked, newUserRecordID, newExtDbKey, newExtRecordID, newSource.key, errors);
				}
				if (null == newDTSSource) {
					return null;
				}
				newSource.dtsSources.put(newDTSSource.key, newDTSSource);
				newSource.addedDTSSourceKeys.add(newDTSSource.key);
			}
			newSource.deletedDTSSourceKeys = new HashSet<Integer>();
			newSource.changedDTSSources = new ArrayList<SourceEditDataTV>();
		}

		return newSource;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Make a replication source based on this source.  On a replication source, the channel is (usually) changed and
	// ERP and horizontal pattern will later be derived by the study engine based on replicating the original source
	// coverage.  All other properties are identical to the original.  This will initially set a "placeholder" value
	// for the ERP and clear the horizontal pattern.  The vertical pattern is usually generic, but tilt parameters are
	// preserved.  This will recursively replicate DTS sources, however the reference facility of a DTS operation is
	// not replicated, an identical duplicate is made with deriveSource().  The reference facility defines just a
	// bounding contour that is always the same and may already be on a different channel.  The other DTS transmitter
	// sources are replicated individually.  The public version does error checks.  There must be a study object, a
	// source outside any study cannot be replicated since there is no context in which to preserve the original source
	// being replicated.  This source must not already be a replication source, and must be the parent if part of a DTS
	// operation.  Also the new channel must be different than the current unless this is an analog source (the
	// replication source will always be digital).  An unlocked source may be replicated.  All replication sources will
	// be locked, but if based on an unlocked original they will not be shareable, they can only appear in the scenario
	// that contained the original.  Since the replication source replaces the original in that scenario, the original
	// cannot be edited again as long as the replication source exists.  That means there is no need for any mechanism
	// to update the replication source from the original later.  See StudyEditData and ScenarioEditor for details.

	// In a special case, the vertical/matrix pattern may not be set generic.  That is done for baseline records only,
	// recognized by the status string "BL".  The baseline tables cannot directly contain DTS records, so those are
	// pulled in by a reference record ID in a placeholder non-DTS baseline record.  The referenced records are normal
	// records that may have full pattern data.  However those records may not be on the same channel as the baseline
	// placeholder, in that case the channel is changed by triggering the replication code.  To be consistent with DTS
	// records that are not replicated, the vertical/matrix pattern on the replicated record is preserved.

	public SourceEditDataTV replicate(int newChannel) {
		return replicate(newChannel, null);
	}

	public SourceEditDataTV replicate(int newChannel, ErrorLogger errors) {

		if ((null == study) || (null != originalSourceKey) || (null != parentSourceKey)) {
			if (null != errors) {
				errors.reportError("The record replication cannot be performed.");
			}
			return null;
		}

		if (service.serviceType.digital && (newChannel == channel)) {
			if (null != errors) {
				errors.reportError("The replication channel must be different.");
			}
			return null;
		}

		return replicate(newChannel, null, errors);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The private replicate method supports recursive DTS replication.

	private SourceEditDataTV replicate(int newChannel, Integer newParentSourceKey, ErrorLogger errors) {

		Integer newKey = study.getNewSourceKey();
		if (null == newKey) {
			if (null != errors) {
				errors.reportError("Cannot create new record, no keys available.");
			}
			return null;
		}

		// When replicating, the new record must always be digital; if this is currently not a digital service, apply
		// the equivalent digital service type.  If that does not exist, the record cannot be replicated.  This may
		// involve modifying other properties, when changing from analog to digital the frequency offset is set to
		// none, and if an emission mask setting is required by the service type it is set to the default.

		Service newService = service;
		SignalType newSignalType = signalType;
		FrequencyOffset newOffset = frequencyOffset;
		EmissionMask newMask = emissionMask;

		if (!newService.serviceType.digital) {
			newService = service.digitalService;
			if (null == newService) {
				if (null != errors) {
					errors.reportError("Unable to replicate, no equivalent digital service exists.");
				}
				return null;
			}
			newSignalType = SignalType.getDefaultObject();
			newOffset = FrequencyOffset.getNullObject();
			if (newService.serviceType.needsEmissionMask) {
				newMask = EmissionMask.getDefaultObject();
			} else {
				newMask = EmissionMask.getNullObject();
			}
		}

		SourceEditDataTV newSource = new SourceEditDataTV(study, dbID, newKey, facilityID, newService, isDRT, country,
			true, userRecordID, extDbKey, extRecordID, key, newParentSourceKey, isParent);

		// All properties are either copied or set to defaults.  This is correct even on a DTS parent; all of the
		// operating parameters (e.g. pattern data) on a parent record are not used and so can just be cleared.

		newSource.callSign = callSign;
		newSource.channel = newChannel;
		newSource.city = city;
		newSource.state = state;
		newSource.zone = zone;
		newSource.status = status;
		newSource.statusType = statusType;
		newSource.fileNumber = fileNumber;
		newSource.appARN = appARN;
		newSource.signalType = newSignalType;
		newSource.frequencyOffset = newOffset;
		newSource.emissionMask = newMask;
		newSource.location.setLatLon(location);
		newSource.dtsMaximumDistance = dtsMaximumDistance;
		newSource.dtsSectors = dtsSectors;
		newSource.heightAMSL = heightAMSL;
		newSource.overallHAAT = overallHAAT;
		newSource.peakERP = Source.ERP_DEF;
		newSource.antennaID = null;

		newSource.hasHorizontalPattern = false;
		newSource.horizontalPattern = null;
		newSource.horizontalPatternChanged = false;
		newSource.horizontalPatternOrientation = 0.;

		if (null != attributes.get(Source.ATTR_IS_BASELINE)) {
			if (hasVerticalPattern) {
				if ((null == verticalPattern) && (null != source)) {
					verticalPattern = source.getVerticalPattern(errors);
				}
				if (null != verticalPattern) {
					newSource.hasVerticalPattern = true;
					newSource.verticalPattern = verticalPattern.copy();
					newSource.verticalPatternChanged = true;
				}
			}
			if (hasMatrixPattern) {
				if ((null == matrixPattern) && (null != source)) {
					matrixPattern = source.getMatrixPattern(errors);
				}
				if (null != matrixPattern) {
					newSource.hasMatrixPattern = true;
					newSource.matrixPattern = matrixPattern.copy();
					newSource.matrixPatternChanged = true;
				}
			}
		} else {
			newSource.hasVerticalPattern = false;
			newSource.verticalPattern = null;
			newSource.verticalPatternChanged = false;
			newSource.hasMatrixPattern = false;
			newSource.matrixPattern = null;
			newSource.matrixPatternChanged = false;
		}

		newSource.verticalPatternElectricalTilt = verticalPatternElectricalTilt;
		newSource.verticalPatternMechanicalTilt = verticalPatternMechanicalTilt;
		newSource.verticalPatternMechanicalTiltOrientation = verticalPatternMechanicalTiltOrientation;
		newSource.useGenericVerticalPattern = useGenericVerticalPattern;

		newSource.siteNumber = siteNumber;

		newSource.serviceAreaMode = serviceAreaMode;
		newSource.serviceAreaArg = serviceAreaArg;
		newSource.serviceAreaCL = serviceAreaCL;
		newSource.serviceAreaKey = serviceAreaKey;

		newSource.dtsTimeDelay = dtsTimeDelay;

		newSource.setAllAttributes(attributes);

		// For a DTS parent, duplicate the reference facility and recursively replicate the individual DTS sources.

		if (isParent) {
			newSource.dtsSources = new TreeMap<Integer, SourceEditDataTV>();
			newSource.addedDTSSourceKeys = new HashSet<Integer>();
			SourceEditDataTV newDTSSource;
			for (SourceEditDataTV dtsSource : dtsSources.values()) {
				if (0 == dtsSource.siteNumber) {
					newDTSSource = dtsSource.deriveSourceTV(study, study.dbID, dtsSource.facilityID,
						dtsSource.service, dtsSource.isDRT, dtsSource.country, true, dtsSource.userRecordID,
						dtsSource.extDbKey, dtsSource.extRecordID, newKey, errors);
				} else {
					newDTSSource = dtsSource.replicate(newChannel, newKey, errors);
				}
				if (null == newDTSSource) {
					return null;
				}
				newSource.dtsSources.put(newDTSSource.key, newDTSSource);
				newSource.addedDTSSourceKeys.add(newDTSSource.key);
			}
			newSource.deletedDTSSourceKeys = new HashSet<Integer>();
			newSource.changedDTSSources = new ArrayList<SourceEditDataTV>();
		}

		return newSource;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a new source set up to be a DTS transmitter for this parent.  It is not automatically added to the DTS
	// source list.  Properties that must always be the same as the parent are copied.  Caller must provide the site
	// number to identify the new source as either a reference facility (siteNumber == 0) or an individual transmitter
	// site.  A reference facility cannot be created if one already exists.  Note the new source is always unlocked,
	// the parent has to be unlocked for this to even be used.  When creating a reference facility source the caller
	// may optionally provide the service, which can be anything except DTS, otherwise that will default to DT.  Note
	// the attributes do not propagate from the parent, every source object has distinct attributes.

	public SourceEditDataTV createDTSSource(int theSiteNumber) {
		return createDTSSource(theSiteNumber, null, null);
	}

	public SourceEditDataTV createDTSSource(int theSiteNumber, ErrorLogger errors) {
		return createDTSSource(theSiteNumber, null, errors);
	}

	public SourceEditDataTV createDTSSource(int theSiteNumber, Service theService) {
		return createDTSSource(theSiteNumber, theService, null);
	}

	public SourceEditDataTV createDTSSource(int theSiteNumber, Service theService, ErrorLogger errors) {

		if (!isParent) {
			if (null != errors) {
				errors.reportError("SourceEditDataTV.createDTSSource() called on non-parent source.");
			}
			return null;
		}
		if (isLocked) {
			if (null != errors) {
				errors.reportError("SourceEditDataTV.createDTSSource() called on locked source.");
			}
			return null;
		}

		// If this will be a reference facility, make sure there isn't one already.

		if (0 == theSiteNumber) {
			for (SourceEditDataTV dtsSource : dtsSources.values()) {
				if (0 == dtsSource.siteNumber) {
					if (null != errors) {
						errors.reportError("DTS parent source already has a reference facility.");
					}
					return null;
				}
			}
		}

		// For a reference facility a service may have been provided by argument, if not or if the argument is not
		// valid (the reference facility cannot itself be a DTS service) set it to DT.  Always set the primary data
		// keys to null on the reference facility.  For other than the reference facility copy service and keys from
		// the parent.  Facility ID, call sign, city, state, and country are always copied from the parent.

		Integer theUserRecordID = userRecordID;
		Integer theExtDbKey = extDbKey;
		String theExtRecordID = extRecordID;

		if (0 == theSiteNumber) {

			if ((null == theService) || (theService.key < 1) || theService.isDTS) {
				theService = Service.getService("DT");
				if (null == theService) {
					if (null != errors) {
						errors.reportError("SourceEditDataTV.createDTSSource() could not load service record.");
					}
					return null;
				}
			}

			theUserRecordID = null;
			theExtDbKey = null;
			theExtRecordID = null;

		} else {
			theService = service;
		}

		SourceEditDataTV theSource = createSource(study, dbID, facilityID, theService, false, country, false,
			theUserRecordID, theExtDbKey, theExtRecordID, key, errors);
		if (null == theSource) {
			return null;
		}

		theSource.siteNumber = theSiteNumber;

		theSource.callSign = callSign;
		theSource.city = city;
		theSource.state = state;

		// For a DTS site, also copy channel, zone, status, file number, and signal type.  For a reference facility the
		// service area mode is always FCC contour.

		if (theSiteNumber > 0) {

			theSource.channel = channel;
			theSource.zone = zone;
			theSource.status = status;
			theSource.statusType = statusType;
			theSource.fileNumber = fileNumber;
			theSource.appARN = appARN;
			theSource.signalType = signalType;

		} else {

			theSource.serviceAreaMode = Source.SERVAREA_CONTOUR_FCC;
			theSource.serviceAreaCL = Source.SERVAREA_CL_DEFAULT;
		}

		return theSource;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Find the next site number in sequence for a DTS parent.

	public int getNextDTSSiteNumber() {

		if (!isParent || dtsSources.isEmpty()) {
			return 0;
		}

		int maxNum = 0;
		for (SourceEditDataTV theSource : dtsSources.values()) {
			if (theSource.siteNumber > maxNum) {
				maxNum = theSource.siteNumber;
			}
		}

		return maxNum + 1;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Manage the set of DTS sources for a parent.  Don't accept sources not for this parent.

	public synchronized void addOrReplaceDTSSource(SourceEditDataTV theSource) {

		if (!isParent || !key.equals(theSource.parentSourceKey)) {
			return;
		}

		if (null == dtsSources.put(theSource.key, theSource)) {
			if (!deletedDTSSourceKeys.remove(theSource.key)) {
				addedDTSSourceKeys.add(theSource.key);
			}
		}
		dtsSourceListCache = null;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public synchronized void removeDTSSource(SourceEditDataTV theSource) {

		if (null != dtsSources.remove(theSource.key)) {
			if (!addedDTSSourceKeys.remove(theSource.key)) {
				deletedDTSSourceKeys.add(theSource.key);
			}
		}
		dtsSourceListCache = null;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// This may be called repeatedly inside tight loops so the list is cached.  This can't just return the values()
	// collection directly because that would expose the map to untracked modifications.

	public ArrayList<SourceEditDataTV> getDTSSources() {

		if (null == dtsSources) {
			return null;
		}

		if (null == dtsSourceListCache) {
			dtsSourceListCache = new ArrayList<SourceEditDataTV>(dtsSources.values());
		}

		return dtsSourceListCache;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isGeographyInUse(int theGeoKey) {

		return (((Source.SERVAREA_GEOGRAPHY_FIXED == serviceAreaMode) ||
			(Source.SERVAREA_GEOGRAPHY_RELOCATED == serviceAreaMode)) && (theGeoKey == serviceAreaKey));
	}


	//-----------------------------------------------------------------------------------------------------------------

	public int getGeographyKey() {

		if ((Source.SERVAREA_GEOGRAPHY_FIXED == serviceAreaMode) ||
				(Source.SERVAREA_GEOGRAPHY_RELOCATED == serviceAreaMode)) {
			return serviceAreaKey;
		}
		return 0;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Validity and change checks per usual pattern.  Special rule here, if the locked flag is true the source cannot
	// be edited so data is always valid.

	public boolean isDataValid(ErrorLogger errors) {

		if (isLocked) {
			return true;
		}

		// Make sure the editor properly implemented the dependency between service and signal type or emission mask.

		if (service.serviceType.digital) {
			if (0 == signalType.key) {
				signalType = SignalType.getDefaultObject();
			}
		} else {
			if (signalType.key != 0) {
				signalType = SignalType.getNullObject();
			}
		}

		if (service.serviceType.needsEmissionMask) {
			if (0 == emissionMask.key) {
				emissionMask = EmissionMask.getInvalidObject();
			}
		} else {
			if (emissionMask.key != 0) {
				emissionMask = EmissionMask.getNullObject();
			}
		}

		// For a DTS reference facility record, the channel does not have to be in the study channel limits; that
		// source is used only to project the bounding contour for the DTS operation, it is never an operating
		// transmitter's channel, so it just has to be in the range the study engine supports for contour projection.
		// Also if there is no study object use the default channel limits.

		if ((null == study) || ((null != parentSourceKey) && (0 == siteNumber))) {
			if ((channel < SourceTV.CHANNEL_MIN) || (channel > SourceTV.CHANNEL_MAX)) {
				if (null != errors) {
					errors.reportValidationError("Bad channel, must be " + SourceTV.CHANNEL_MIN + " to " +
						SourceTV.CHANNEL_MAX + ".");
				}
				return false;
			}
		} else {
			int minChannel = study.getMinimumChannel(), maxChannel = study.getMaximumChannel();
			if ((channel < minChannel) || (channel > maxChannel)) {
				if (null != errors) {
					errors.reportValidationError("Bad channel, must be " + minChannel + " to " + maxChannel + ".");
				}
				return false;
			}
		}
		if (0 == city.length()) {
			if (null != errors) {
				errors.reportValidationError("A city name must be provided.");
			}
			return false;
		}
		if (0 == state.length()) {
			if (null != errors) {
				errors.reportValidationError("A state code must be provided.");
			}
			return false;
		}
		if (zone.key < 0) {
			if (null != errors) {
				errors.reportValidationError("A zone must be selected.");
			}
			return false;
		}
		if (signalType.key < 0) {
			if (null != errors) {
				errors.reportValidationError("A signal type must be selected.");
			}
			return false;
		}
		if (emissionMask.key < 0) {
			if (null != errors) {
				errors.reportValidationError("An emission mask must be selected.");
			}
			return false;
		}

		// DTS distance is only relevant on a DTS parent.  Zero is valid as that triggers use of a table value, else it
		// must be in valid range.  The alternative sectors definition has priority but both are validated.

		if (isParent) {
			if ((dtsMaximumDistance != 0.) && ((dtsMaximumDistance < Source.DISTANCE_MIN) ||
					(dtsMaximumDistance > Source.DISTANCE_MAX))) {
				if (null != errors) {
					errors.reportValidationError("Bad DTS boundary distance, must be " + Source.DISTANCE_MIN + " to " +
						Source.DISTANCE_MAX + ".");
				}
				return false;
			}
			if (dtsSectors.length() > 0) {
				String errmsg = GeoSectors.validateString(dtsSectors);
				if (null != errmsg) {
					if (null != errors) {
						errors.reportValidationError("Bad DTS boundary: " + errmsg);
					}
					return false;
				}
			}
		}

		// Proper set of site numbers on a DTS parent is checked below, here just make sure it is >= 0.

		if (siteNumber < 0) {
			if (null != errors) {
				errors.reportValidationError("Bad site number, must be >= 0.");
			}
			return false;
		}

		if ((Source.SERVAREA_CONTOUR_LR_PERCENT == serviceAreaMode) ||
				(Source.SERVAREA_CONTOUR_LR_RUN_ABOVE == serviceAreaMode) ||
				(Source.SERVAREA_CONTOUR_LR_RUN_BELOW == serviceAreaMode)) {
			if ((serviceAreaArg < Source.SERVAREA_ARGUMENT_MIN) || (serviceAreaArg > Source.SERVAREA_ARGUMENT_MAX)) {
				errors.reportValidationError("Bad contour mode argument, must be " + Source.SERVAREA_ARGUMENT_MIN +
					" to " + Source.SERVAREA_ARGUMENT_MAX + ".");
				return false;
			}
		}

		if ((Source.SERVAREA_CONTOUR_DEFAULT == serviceAreaMode) ||
				(Source.SERVAREA_CONTOUR_FCC == serviceAreaMode) ||
				(Source.SERVAREA_CONTOUR_LR_PERCENT == serviceAreaMode) ||
				(Source.SERVAREA_CONTOUR_LR_RUN_ABOVE == serviceAreaMode) ||
				(Source.SERVAREA_CONTOUR_LR_RUN_BELOW == serviceAreaMode)) {
			if ((serviceAreaCL != Source.SERVAREA_CL_DEFAULT) && ((serviceAreaCL < Source.SERVAREA_CL_MIN) ||
					(serviceAreaCL > Source.SERVAREA_CL_MAX))) {
				errors.reportValidationError("Bad contour level, must be " + Source.SERVAREA_CL_MIN + " to " +
					Source.SERVAREA_CL_MAX + ".");
				return false;
			}
		}

		if (((Source.SERVAREA_GEOGRAPHY_FIXED == serviceAreaMode) ||
				(Source.SERVAREA_GEOGRAPHY_RELOCATED == serviceAreaMode)) && (serviceAreaKey <= 0)) {
			errors.reportValidationError("Bad or missing service area geography.");
			return false;
		}

		// DTS time delay is only relevant on a DTS transmitter source.

		if ((null != parentSourceKey) && (siteNumber > 0) && ((dtsTimeDelay < Source.TIME_DELAY_MIN) ||
				(dtsTimeDelay > Source.TIME_DELAY_MAX))) {
			if (null != errors) {
				errors.reportValidationError("Bad DTS time delay, must be " + Source.TIME_DELAY_MIN + " to " +
					Source.TIME_DELAY_MAX + ".");
			}
			return false;
		}

		if (!super.isDataValid(errors)) {
			return false;
		}

		// For a DTS parent, check all sources for validity, confirm there is a reference facility (siteNumber == 0)
		// and at least one actual site (siteNumber > 0).  Also the parent itself must always have siteNumber == 0.
		// This could check uniqueness for >0 site numbers, but the study engine won't ever care so not bothering.

		if (isParent) {
			if (0 != siteNumber) {
				if (null != errors) {
					errors.reportValidationError("Bad site number for DTS parent, must be 0.");
				}
				return false;
			}
			boolean hasReference = false, hasSite = false;
			for (SourceEditDataTV dtsSource : dtsSources.values()) {
				if (!dtsSource.isDataValid(errors)) {
					return false;
				}
				if (0 == dtsSource.siteNumber) {
					if (hasReference) {
						if (null != errors) {
							errors.reportValidationError(
								"Multiple reference facilities (site number 0) for DTS parent.");
						}
						return false;
					} else {
						hasReference = true;
					}
				} else {
					hasSite = true;
				}
			}
			if (!hasReference) {
				if (null != errors) {
					errors.reportValidationError("DTS parent must have a reference facility.");
				}
				return false;
			}
			if (!hasSite) {
				if (null != errors) {
					errors.reportValidationError("DTS parent must have at least one transmitter site.");
				}
				return false;
			}
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// A locked record is non-editable and so cannot have changes, but may still report changed if it is a new record
	// never saved to the database (source == null), or due to conditions in the superclass.  For a DTS parent the list
	// of changed secondary sources is built here, for a new or updated source that includes all, for an editable
	// parent the sources are checked for changes, additions, and deletions.

	public boolean isDataChanged() {

		if (null == source) {
			if (isParent) {
				changedDTSSources.clear();
				changedDTSSources.addAll(dtsSources.values());
			}
			return true;
		}

		if (super.isDataChanged()) {
			return true;
		}

		if (isLocked) {
			return false;
		}

		if (isParent) {
			boolean dataChanged = false;
			changedDTSSources.clear();
			for (SourceEditDataTV dtsSource : dtsSources.values()) {
				if (dtsSource.isDataChanged() || addedDTSSourceKeys.contains(dtsSource.key)) {
					changedDTSSources.add(dtsSource);
					dataChanged = true;
				}
			}
			if (dataChanged) {
				return true;
			}
			if (!deletedDTSSourceKeys.isEmpty()) {
				return true;
			}
		}

		// Check for individual changes on an editable source.

		if (facilityID != source.facilityID) {
			return true;
		}
		if (channel != source.channel) {
			return true;
		}
		if (zone.key != source.zone.key) {
			return true;
		}
		if (!status.equals(source.status)) {
			return true;
		}
		if (signalType.key != source.signalType.key) {
			return true;
		}
		if (frequencyOffset.key != source.frequencyOffset.key) {
			return true;
		}
		if (emissionMask.key != source.emissionMask.key) {
			return true;
		}
		if (dtsMaximumDistance != source.dtsMaximumDistance) {
			return true;
		}
		if (!dtsSectors.equals(source.dtsSectors)) {
			return true;
		}
		if (siteNumber != source.siteNumber) {
			return true;
		}

		if (serviceAreaMode != source.serviceAreaMode) {
			return true;
		}
		if (serviceAreaArg != source.serviceAreaArg) {
			return true;
		}
		if (serviceAreaCL != source.serviceAreaCL) {
			return true;
		}
		if (((Source.SERVAREA_GEOGRAPHY_FIXED == serviceAreaMode) ||
				(Source.SERVAREA_GEOGRAPHY_RELOCATED == serviceAreaMode)) &&
				(serviceAreaKey != source.serviceAreaKey)) {
			return true;
		}

		if (dtsTimeDelay != source.dtsTimeDelay) {
			return true;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Save this source, see comments in superclass.  If this is a DTS parent first save the DTS secondary sources,
	// start by deleting any as needed, then save all secondaries, then save the parent.  That order is important, the
	// secondary sources must be saved first, see comments below.

	public void save(DbConnection db) throws SQLException {

		if (isParent) {

			if (!deletedDTSSourceKeys.isEmpty()) {

				String keyList = StudyEditData.makeKeyList(deletedDTSSourceKeys);

				db.update("DELETE FROM source_horizontal_pattern WHERE source_key IN " + keyList);
				db.update("DELETE FROM source_vertical_pattern WHERE source_key IN " + keyList);
				db.update("DELETE FROM source_matrix_pattern WHERE source_key IN " + keyList);
				db.update("DELETE FROM source WHERE source_key IN " + keyList);
			}

			for (SourceEditDataTV dtsSource : changedDTSSources) {
				dtsSource.save(db);
			}
		}

		// Delete any old record if this record was previously saved update the modCount field.  However modCount is
		// irrelevant on DTS secondary sources, the parent value applies to all.  

		db.update("DELETE FROM source WHERE source_key=" + key);

		int newModCount = 0;
		if ((null != source) && (null == parentSourceKey)) {
			newModCount = source.modCount + 1;
		}

		String hpatName = "";
		if (hasHorizontalPattern) {
			if (null != horizontalPattern) {
				hpatName = horizontalPattern.name;
			} else {
				if (null != source) {
					hpatName = source.horizontalPatternName;
				}
			}
		}

		String vpatName = "";
		if (hasVerticalPattern) {
			if (null != verticalPattern) {
				vpatName = verticalPattern.name;
			} else {
				if (null != source) {
					vpatName = source.verticalPatternName;
				}
			}
		}

		String mpatName = "";
		if (hasMatrixPattern) {
			if (null != matrixPattern) {
				mpatName = matrixPattern.name;
			} else {
				if (null != source) {
					mpatName = source.matrixPatternName;
				}
			}
		}

		int theServiceAreaKey = 0;
		if ((Source.SERVAREA_GEOGRAPHY_FIXED == serviceAreaMode) ||
				(Source.SERVAREA_GEOGRAPHY_RELOCATED == serviceAreaMode)) {
			theServiceAreaKey = serviceAreaKey;
		}

		String newAttributes = getAllAttributes();

		db.update(
		"INSERT INTO source (" +
			"source_key," +
			"record_type," +
			"needs_update," +
			"mod_count," +
			"facility_id," +
			"service_key," +
			"is_drt," +
			"is_iboc," +
			"station_class," +
			"call_sign," +
			"sector_id," +
			"channel," +
			"city," +
			"state," +
			"country_key," +
			"zone_key," +
			"status," +
			"file_number," +
			"signal_type_key," +
			"frequency_offset_key," +
			"emission_mask_key," +
			"latitude," +
			"longitude," +
			"dts_maximum_distance," +
			"dts_sectors," +
			"height_amsl," +
			"actual_height_amsl," +
			"height_agl," +
			"overall_haat," +
			"actual_overall_haat," +
			"peak_erp," +
			"contour_erp," +
			"iboc_fraction," +
			"antenna_id," +
			"has_horizontal_pattern," +
			"horizontal_pattern_name," +
			"horizontal_pattern_orientation," +
			"has_vertical_pattern," +
			"vertical_pattern_name," +
			"vertical_pattern_electrical_tilt," +
			"vertical_pattern_mechanical_tilt," +
			"vertical_pattern_mechanical_tilt_orientation," +
			"has_matrix_pattern," +
			"matrix_pattern_name," +
			"use_generic_vertical_pattern," +
			"site_number," +
			"locked," +
			"user_record_id," +
			"ext_db_key," +
			"ext_record_id," +
			"original_source_key," +
			"parent_source_key," +
			"service_area_mode," +
			"service_area_arg," +
			"service_area_cl," +
			"service_area_key," +
			"dts_time_delay," +
			"attributes) " +
		"VALUES (" +
			key + "," +
			recordType + "," +
			"true," +
			newModCount + "," +
			facilityID + "," +
			service.key + "," +
			isDRT + "," +
			"false," +
			"0," +
			"'" + db.clean(callSign) + "'," +
			"''," +
			channel + "," +
			"'" + db.clean(city) + "'," +
			"'" + db.clean(state) + "'," +
			country.key + "," +
			zone.key + "," +
			"'" + db.clean(status) + "'," +
			"'" + db.clean(fileNumber) + "'," +
			signalType.key + "," +
			frequencyOffset.key + "," +
			emissionMask.key + "," +
			location.latitude + "," +
			location.longitude + "," +
			dtsMaximumDistance + "," +
			"'" + db.clean(dtsSectors.trim()) + "'," +
			heightAMSL + "," +
			heightAMSL + "," +
			"0," +
			overallHAAT + "," +
			overallHAAT + "," +
			peakERP + "," +
			(10. * Math.log10(peakERP)) + "," +
			"0.," +
			((null == antennaID) ? "null" : "'" + db.clean(antennaID) + "'") + "," +
			hasHorizontalPattern + "," +
			"'" + db.clean(hpatName) + "'," +
			horizontalPatternOrientation + "," +
			hasVerticalPattern + "," +
			"'" + db.clean(vpatName) + "'," +
			verticalPatternElectricalTilt + "," +
			verticalPatternMechanicalTilt + "," +
			verticalPatternMechanicalTiltOrientation + "," +
			hasMatrixPattern + "," +
			"'" + db.clean(mpatName) + "'," +
			useGenericVerticalPattern + "," +
			siteNumber + "," +
			isLocked + "," +
			((null == userRecordID) ? "0" : userRecordID) + "," +
			((null == extDbKey) ? "0" : extDbKey) + "," +
			((null == extRecordID) ? "null" : "'" + db.clean(extRecordID) + "'") + "," +
			((null == originalSourceKey) ? "0" : originalSourceKey) + "," +
			((null == parentSourceKey) ? "0" : parentSourceKey) + "," +
			serviceAreaMode + "," +
			serviceAreaArg + "," +
			serviceAreaCL + "," +
			theServiceAreaKey + "," +
			dtsTimeDelay + "," +
			"'" + db.clean(newAttributes) + "')");

		savePatterns(db);

		// Create/update the Source object to the as-edited state.  This is why DTS sources were saved first, so when
		// the parent gets here all of the Source objects in the secondary sources have already been updated so those
		// can be added to the parent Source object.

		ArrayList<SourceTV> theDTSSources = null;
		if (isParent) {
			theDTSSources = new ArrayList<SourceTV>();
			for (SourceEditDataTV dtsSource : dtsSources.values()) {
				theDTSSources.add(dtsSource.source);
			}
		}

		source = new SourceTV(dbID, db.getDatabase(), key.intValue(), facilityID, service, isDRT, callSign, channel,
			city, state, country, zone, status, fileNumber, signalType, frequencyOffset, emissionMask,
			location.latitude, location.longitude, dtsMaximumDistance, dtsSectors, heightAMSL, heightAMSL, overallHAAT,
			overallHAAT, peakERP, antennaID, hasHorizontalPattern, hpatName, horizontalPatternOrientation,
			hasVerticalPattern, vpatName, verticalPatternElectricalTilt, verticalPatternMechanicalTilt,
			verticalPatternMechanicalTiltOrientation, hasMatrixPattern, mpatName, useGenericVerticalPattern,
			siteNumber, isLocked, userRecordID, extDbKey, extRecordID, originalSourceKey, parentSourceKey,
			theDTSSources, newModCount, serviceAreaMode, serviceAreaArg, serviceAreaCL, theServiceAreaKey,
			dtsTimeDelay, newAttributes);

		// Clear data-edited state.

		if (isParent) {
			addedDTSSourceKeys.clear();
			deletedDTSSourceKeys.clear();
			changedDTSSources.clear();
		}

		horizontalPatternChanged = false;
		verticalPatternChanged = false;
		matrixPatternChanged = false;
		attributesChanged = false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Method to add sources from a generic import data set to a scenario.  This follows the pattern from addRecords()
	// in ExtDbRecordTV, see comments there for details, also some support methods from that class are emulated here.

	private static class SearchDelta {
		int delta;
		boolean analogOnly;
		double maximumDistance;
	}

	public static int addRecords(ExtDb extDb, ScenarioEditData scenario, int searchType, String query,
			GeoPoint searchCenter, double searchRadius, int minimumChannel, int maximumChannel, boolean disableMX,
			boolean setUndesired, ErrorLogger errors) {

		int studyType = scenario.study.study.studyType;
		if (!extDb.isGeneric() || (Source.RECORD_TYPE_TV != extDb.recordType) ||
				!Study.isRecordTypeAllowed(studyType, Source.RECORD_TYPE_TV) ||
				(((ExtDbSearch.SEARCH_TYPE_DESIREDS == searchType) ||
					(ExtDbSearch.SEARCH_TYPE_PROTECTEDS == searchType)) &&
					(Study.STUDY_TYPE_TV != studyType))) {
			return 0;
		}

		double coChanMX = scenario.study.getCoChannelMxDistance();
		double kmPerDeg = scenario.study.getKilometersPerDegree();
		boolean checkDTSDist = scenario.study.getCheckIndividualDTSDistance();
		int minChannel = scenario.study.getMinimumChannel();
		int maxChannel = scenario.study.getMaximumChannel();

		if (minimumChannel > minChannel) {
			minChannel = minimumChannel;
		}
		if ((maximumChannel > 0) && (maximumChannel < maxChannel)) {
			maxChannel = maximumChannel;
		}
		if (minChannel > maxChannel) {
			return 0;
		}

		StringBuilder q = new StringBuilder();
		boolean hasCrit = false;
		if ((null != query) && (query.length() > 0)) {
			q.append(query);
			hasCrit = true;
		}

		ArrayList<SourceEditData> theSources = null;
		Collection<SearchDelta> deltas = null;

		if (ExtDbSearch.SEARCH_TYPE_DESIREDS == searchType) {

			try {
				addChannelRangeQuery(extDb.type, minChannel, maxChannel, q, hasCrit);
			} catch (IllegalArgumentException ie) {
			}

		} else {

			if (ExtDbSearch.SEARCH_TYPE_PROTECTEDS == searchType) {

				theSources = scenario.sourceData.getUndesiredSources(Source.RECORD_TYPE_TV);
				if (theSources.isEmpty()) {
					if (null != errors) {
						errors.reportError("There are no undesired TV stations in the scenario.");
					}
					return -1;
				}

			} else {

				theSources = scenario.sourceData.getDesiredSources(Source.RECORD_TYPE_TV);
				if (theSources.isEmpty()) {
					if (null != errors) {
						errors.reportError("There are no desired TV stations in the scenario.");
					}
					return -1;
				}
			}

			HashMap<Integer, SearchDelta> searchDeltas = new HashMap<Integer, SearchDelta>();
			SearchDelta searchDelta;

			for (IxRuleEditData theRule : scenario.study.ixRuleData.getActiveRows()) {

				if (Source.RECORD_TYPE_TV != theRule.serviceType.recordType) {
					continue;
				}

				searchDelta = searchDeltas.get(Integer.valueOf(theRule.channelDelta.delta));

				if (null == searchDelta) {

					searchDelta = new SearchDelta();
					searchDelta.delta = theRule.channelDelta.delta;
					searchDelta.analogOnly = theRule.channelDelta.analogOnly;
					searchDelta.maximumDistance = theRule.distance;

					searchDeltas.put(Integer.valueOf(searchDelta.delta), searchDelta);

				} else {

					if (theRule.distance > searchDelta.maximumDistance) {
						searchDelta.maximumDistance = theRule.distance;
					}
				}
			}

			deltas = searchDeltas.values();

			int desChan, undChan, numChans = 0, maxChans = (maxChannel - minChannel) + 1, iChan;
			SourceEditDataTV theSource;

			boolean[] searchChans = new boolean[maxChans];

			for (SourceEditData aSource : theSources) {
				theSource = (SourceEditDataTV)aSource;

				for (SearchDelta theDelta : deltas) {

					if (ExtDbSearch.SEARCH_TYPE_PROTECTEDS == searchType) {

						undChan = theSource.channel;
						desChan = undChan - theDelta.delta;

						if ((desChan < minChannel) || (desChan > maxChannel)) {
							continue;
						}

						iChan = desChan - minChannel;
						if (searchChans[iChan]) {
							continue;
						}

					} else {

						desChan = theSource.channel;
						undChan = desChan + theDelta.delta;

						if ((undChan < minChannel) || (undChan > maxChannel)) {
							continue;
						}

						iChan = undChan - minChannel;
						if (searchChans[iChan]) {
							continue;
						}
					}

					if (desChan < 5) {
						if (undChan > 4) {
							continue;
						}
					} else {
						if (desChan < 7) {
							if ((undChan < 5) || (undChan > 6)) {
								continue;
							}
						} else {
							if (desChan < 14) {
								if ((undChan < 7) || (undChan > 13)) {
									continue;
								}
							} else {
								if (undChan < 14) {
									continue;
								}
							}
						}
					}

					searchChans[iChan] = true;
					numChans++;

					if (numChans == maxChans) {
						break;
					}
				}

				if (numChans == maxChans) {
					break;
				}
			}

			if (0 == numChans) {
				return 0;
			}

			if (numChans < maxChans) {

				StringBuilder chanList = new StringBuilder();
				char sep = '(';
				for (iChan = 0; iChan < maxChans; iChan++) {
					if (searchChans[iChan]) {
						chanList.append(sep);
						chanList.append(String.valueOf(iChan + minChannel));
						sep = ',';
					}
				}
				chanList.append(')');
				try {
					addMultipleChannelQuery(extDb.type, chanList.toString(), q, hasCrit);
				} catch (IllegalArgumentException ie) {
				}

			} else {

				try {
					addChannelRangeQuery(extDb.type, minChannel, maxChannel, q, hasCrit);
				} catch (IllegalArgumentException ie) {
				}
			}
		}

		LinkedList<SourceEditData> sources = findImportRecords(extDb, q.toString(), searchCenter, searchRadius,
			kmPerDeg, errors);

		if (null == sources) {
			return -1;
		}
		if (sources.isEmpty()) {
			return 0;
		}

		removeAllMX(scenario, sources, disableMX, coChanMX, kmPerDeg);

		if (Study.STUDY_TYPE_TV_IX == studyType) {
			SourceEditDataTV protectedSource =
				(SourceEditDataTV)scenario.sourceData.getDesiredSource(Source.RECORD_TYPE_TV);
			SourceEditDataTV proposalSource = null;
			ScenarioEditData proposalScenario = scenario.study.scenarioData.get(0);
			if (Scenario.SCENARIO_TYPE_TVIX_PROPOSAL == proposalScenario.scenarioType) {
				for (SourceEditData aSource : proposalScenario.sourceData.getSources(Source.RECORD_TYPE_TV)) {
					if (null != aSource.getAttribute(Source.ATTR_IS_PROPOSAL)) {
						proposalSource = (SourceEditDataTV)aSource;
						break;
					}
				}
			}
			ListIterator<SourceEditData> lit = sources.listIterator(0);
			SourceEditDataTV theSource;
			while (lit.hasNext()) {
				theSource = (SourceEditDataTV)lit.next();
				if (((null != protectedSource) && ExtDbRecordTV.areRecordsMX(theSource, protectedSource, kmPerDeg)) ||
						((null != proposalSource) && ExtDbRecordTV.areRecordsMX(theSource, proposalSource, kmPerDeg))) {
					lit.remove();
				}
			}
		}

		if (ExtDbSearch.SEARCH_TYPE_DESIREDS != searchType) {

			ListIterator<SourceEditData> lit = sources.listIterator(0);
			SourceEditDataTV theSource, desSource, undSource;
			boolean remove;
			int chanDelt;
			double checkDist;

			while (lit.hasNext()) {

				theSource = (SourceEditDataTV)(lit.next());
				remove = true;

				for (SourceEditData aSource : theSources) {

					if (ExtDbSearch.SEARCH_TYPE_PROTECTEDS == searchType) {
						desSource = theSource;
						undSource = (SourceEditDataTV)aSource;
					} else {
						desSource = (SourceEditDataTV)aSource;
						undSource = theSource;
					}

					chanDelt = undSource.channel - desSource.channel;

					for (SearchDelta theDelta : deltas) {

						if (theDelta.delta != chanDelt) {
							continue;
						}

						if (theDelta.analogOnly && desSource.service.serviceType.digital) {
							continue;
						}

						if (desSource.channel < 5) {
							if (undSource.channel > 4) {
								continue;
							}
						} else {
							if (desSource.channel < 7) {
								if ((undSource.channel < 5) || (undSource.channel > 6)) {
									continue;
								}
							} else {
								if (desSource.channel < 14) {
									if ((undSource.channel < 7) || (undSource.channel > 13)) {
										continue;
									}
								} else {
									if (undSource.channel < 14) {
										continue;
									}
								}
							}
						}

						if (desSource.isParent) {
							for (SourceEditDataTV desDTSSource : desSource.getDTSSources()) {
								if (desDTSSource.siteNumber > 0) {
									checkDist = theDelta.maximumDistance + getRuleExtraDistance(scenario.study,
										desDTSSource.service, false, desDTSSource.country, desDTSSource.channel,
										desDTSSource.peakERP);
									if (undSource.service.isDTS && checkDTSDist) {
										for (SourceEditDataTV undDTSSource : undSource.getDTSSources()) {
											if (undDTSSource.siteNumber > 0) {
												if (desDTSSource.location.distanceTo(undDTSSource.location,
														kmPerDeg) <= checkDist) {
													remove = false;
													break;
												}
											}
										}
										if (!remove) {
											break;
										}
									} else {
										if (desDTSSource.location.distanceTo(undSource.location, kmPerDeg) <=
											checkDist) {
											remove = false;
											break;
										}
									}
								}
							}
						} else {
							checkDist = theDelta.maximumDistance + getRuleExtraDistance(scenario.study,
								desSource.service, false, desSource.country, desSource.channel, desSource.peakERP);
							if (undSource.service.isDTS && checkDTSDist) {
								for (SourceEditDataTV undDTSSource : undSource.getDTSSources()) {
									if (desSource.location.distanceTo(undDTSSource.location, kmPerDeg) <= checkDist) {
										remove = false;
										break;
									}
								}
							} else {
								if (desSource.location.distanceTo(undSource.location, kmPerDeg) <= checkDist) {
									remove = false;
								}
							}
						}
						if (!remove) {
							break;
						}
					}

					if (!remove) {
						break;
					}
				}

				if (remove) {
					lit.remove();
				}
			}
		}

		SourceEditData newSource;
		ArrayList<SourceEditData> newSources = new ArrayList<SourceEditData>();

		for (SourceEditData theSource : sources) {
			newSource = scenario.study.findSharedSource(theSource.extDbKey, theSource.extRecordID);
			if (null == newSource) {
				newSource = theSource.deriveSource(scenario.study, true, errors);
				if (null == newSource) {
					return -1;
				}
			}
			newSources.add(newSource);
		}

		boolean isDesired = true, isUndesired = true;
		if ((ExtDbSearch.SEARCH_TYPE_DESIREDS == searchType) || (ExtDbSearch.SEARCH_TYPE_PROTECTEDS == searchType)) {
			if (disableMX) {
				isUndesired = false;
			} else {
				isUndesired = setUndesired;
			}
		} else {
			isDesired = false;
			if (disableMX && (Study.STUDY_TYPE_TV_IX != studyType)) {
				isUndesired = false;
			}
		}

		for (SourceEditData aSource : newSources) {
			scenario.sourceData.addOrReplace(aSource, isDesired, isUndesired);
		}

		return newSources.size();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Note this and isPreferredRecord() assume the sources are from a generic import data set meaning both extDbKey
	// and extRecordID are non-null; these will almost certainly throw null pointer exceptions if that is not true.
	// Also a cast exception will throw if the objects are not actually SourceEditDataTV.

	private static void removeAllMX(ScenarioEditData scenario, LinkedList<SourceEditData> sources, boolean disableMX,
			double coChanMX, double kmPerDeg) {

		ArrayList<SourceEditData> existSources = scenario.sourceData.getSources(Source.RECORD_TYPE_TV);

		ListIterator<SourceEditData> lit = sources.listIterator(0);
		SourceEditDataTV theSourceTV, existSourceTV;

		while (lit.hasNext()) {
			theSourceTV = (SourceEditDataTV)(lit.next());
			for (SourceEditData existSource : existSources) {
				existSourceTV = (SourceEditDataTV)existSource;
				if ((theSourceTV.extDbKey.equals(existSourceTV.extDbKey) &&
						theSourceTV.extRecordID.equals(existSourceTV.extRecordID)) ||
						(!disableMX && ExtDbRecordTV.areRecordsMX(theSourceTV, existSourceTV, coChanMX, kmPerDeg))) {
					lit.remove();
					break;
				}
			}
		}

		if (disableMX) {
			return;
		}

		Comparator<SourceEditData> prefComp = new Comparator<SourceEditData>() {
			public int compare(SourceEditData theSource, SourceEditData otherSource) {
				if (((SourceEditDataTV)theSource).isPreferredRecord((SourceEditDataTV)otherSource)) {
					return -1;
				}
				return 1;
			}
		};

		Collections.sort(sources, prefComp);

		int recCount = sources.size() - 1;
		for (int recIndex = 0; recIndex < recCount; recIndex++) {
			theSourceTV = (SourceEditDataTV)(sources.get(recIndex));
			lit = sources.listIterator(recIndex + 1);
			while (lit.hasNext()) {
				if (ExtDbRecordTV.areRecordsMX(theSourceTV, (SourceEditDataTV)(lit.next()), coChanMX, kmPerDeg)) {
					lit.remove();
					recCount--;
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	private boolean isPreferredRecord(SourceEditDataTV otherSource) {

		if (service.preferenceRank > otherSource.service.preferenceRank) {
			return true;
		}
		if (service.preferenceRank < otherSource.service.preferenceRank) {
			return false;
		}

		if (statusType < otherSource.statusType) {
			return true;
		}
		if (statusType > otherSource.statusType) {
			return false;
		}

		if (extRecordID.compareTo(otherSource.extRecordID) > 0) {
			return true;
		}
		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Determine the rule extra distance for this source.

	public double getRuleExtraDistance() {

		return getRuleExtraDistance(study, service, isParent, country, channel, peakERP);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Determine the rule extra distance for a service, country, channel, and ERP.  This involves determining the
	// service contour, normalizing the ERP, and looking up the distance from study parameters.  If the study is null
	// parameters are not available for this, just return the default.  In practice that should never happen since this
	// is meant for logic that builds scenarios and that should only occur within an existing study.  On a DTS parent
	// return the default distance; this should actually never be used on a parent.  Also return default if the ERP
	// appears to be invalid.  An option may set the rule-extra to the maximum calculation distance in all cases.

	public static double getRuleExtraDistance(StudyEditData study, Service service, boolean isDTSParent,
			Country country, int channel, double peakERP) {

		if (null == study) {
			return DEFAULT_RULE_EXTRA_DISTANCE;
		}

		if (study.getUseMaxRuleExtraDistance()) {
			return study.getMaximumDistance();
		}

		if (isDTSParent || (peakERP <= 0.)) {
			return DEFAULT_RULE_EXTRA_DISTANCE;
		}

		int countryIndex = country.key - 1;
		double contourLevel;
		int curv;

		if (channel < 7) {

			switch (service.serviceType.key) {

				case ServiceType.SERVTYPE_DTV_FULL:
				default: {
					contourLevel = study.getContourVloDigital(countryIndex);
					curv = study.getCurveSetDigital(countryIndex);
					break;
				}

				case ServiceType.SERVTYPE_NTSC_FULL: {
					contourLevel = study.getContourVloAnalog(countryIndex);
					curv = study.getCurveSetAnalog(countryIndex);
					break;
				}

				case ServiceType.SERVTYPE_DTV_CLASS_A:
				case ServiceType.SERVTYPE_DTV_LPTV: {
					contourLevel = study.getContourVloDigitalLPTV(countryIndex);
					curv = study.getCurveSetDigital(countryIndex);
					break;
				}

				case ServiceType.SERVTYPE_NTSC_CLASS_A:
				case ServiceType.SERVTYPE_NTSC_LPTV: {
					contourLevel = study.getContourVloAnalogLPTV(countryIndex);
					curv = study.getCurveSetAnalog(countryIndex);
					break;
				}
			}

		} else {

			if (channel < 14) {

				switch (service.serviceType.key) {

					case ServiceType.SERVTYPE_DTV_FULL:
					default: {
						contourLevel = study.getContourVhiDigital(countryIndex);
						curv = study.getCurveSetDigital(countryIndex);
						break;
					}

					case ServiceType.SERVTYPE_NTSC_FULL: {
						contourLevel = study.getContourVhiAnalog(countryIndex);
						curv = study.getCurveSetAnalog(countryIndex);
						break;
					}

					case ServiceType.SERVTYPE_DTV_CLASS_A:
					case ServiceType.SERVTYPE_DTV_LPTV: {
						contourLevel = study.getContourVhiDigitalLPTV(countryIndex);
						curv = study.getCurveSetDigital(countryIndex);
						break;
					}

					case ServiceType.SERVTYPE_NTSC_CLASS_A:
					case ServiceType.SERVTYPE_NTSC_LPTV: {
						contourLevel = study.getContourVhiAnalogLPTV(countryIndex);
						curv = study.getCurveSetAnalog(countryIndex);
						break;
					}
				}

			} else {

				double dipoleCont = 0.;
				if (study.getUseDipoleCont(countryIndex)) {
					dipoleCont = 20. * Math.log10((473. + ((double)(channel - 14) * 6.)) /
						study.getDipoleCenterFreqCont(countryIndex));
				}

				switch (service.serviceType.key) {

					case ServiceType.SERVTYPE_DTV_FULL:
					default: {
						contourLevel = study.getContourUhfDigital(countryIndex) + dipoleCont;
						curv = study.getCurveSetDigital(countryIndex);
						break;
					}

					case ServiceType.SERVTYPE_NTSC_FULL: {
						contourLevel = study.getContourUhfAnalog(countryIndex) + dipoleCont;
						curv = study.getCurveSetAnalog(countryIndex);
						break;
					}

					case ServiceType.SERVTYPE_DTV_CLASS_A:
					case ServiceType.SERVTYPE_DTV_LPTV: {
						contourLevel = study.getContourUhfDigitalLPTV(countryIndex) + dipoleCont;
						curv = study.getCurveSetDigital(countryIndex);
						break;
					}

					case ServiceType.SERVTYPE_NTSC_CLASS_A:
					case ServiceType.SERVTYPE_NTSC_LPTV: {
						contourLevel = study.getContourUhfAnalogLPTV(countryIndex) + dipoleCont;
						curv = study.getCurveSetAnalog(countryIndex);
						break;
					}
				}
			}
		}

		// Service at F(50,10) is rare and not fully supported, but it can be selected so handle it just in case.

		if (Parameter.CURVE_FCC_F10 == curv) {
			return 300.;
		}

		double erp = (10. * Math.log10(peakERP)) + (study.getContourUhfDigital(Country.US - 1) - contourLevel);

		int curvref = study.getCurveSetDigital(Country.US - 1);

		if (Parameter.CURVE_FCC_F90 == curvref) {
			if (Parameter.CURVE_FCC_F50 == curv) {
				erp += 8.;
			}
		} else {
			if (Parameter.CURVE_FCC_F50 == curvref) {
				if (Parameter.CURVE_FCC_F90 == curv) {
					erp -= 8.;
				}
			}
		}

		if (erp < study.getRuleExtraDistanceERPLow()) {
			return study.getRuleExtraDistanceLow();
		} else {
			if (erp < study.getRuleExtraDistanceERPMedium()) {
				return study.getRuleExtraDistanceLowMedium();
			} else {
				if (erp < study.getRuleExtraDistanceERPHigh()) {
					return study.getRuleExtraDistanceMediumHigh();
				} else {
					return study.getRuleExtraDistanceHigh();
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Encode source data as an XML description, see comments in the superclass.

	protected boolean writeToXML(Writer xml, boolean standalone, boolean isDesiredFlag, boolean isUndesiredFlag,
			ErrorLogger errors) throws IOException {

		SourceEditDataTV theSource = this;
		if (null != originalSourceKey) {
			if (null != study) {
				SourceEditData aSource = study.getSource(originalSourceKey);
				if (Source.RECORD_TYPE_TV == aSource.recordType) {
					theSource = (SourceEditDataTV)aSource;
				}
			} else {
				theSource = null;
			}
			if (null == theSource) {
				if (null != errors) {
					errors.reportError("An original record needed for replication does not exist.");
				}
				return false;
			}
		}

		// If the source is from a generic data set, locked or otherwise it will be fully exported and the extRecordID
		// will not appear in the attributes.  Those record IDs are not portable, they are arbitrarily assigned at the
		// time the data is imported.  If the data set key does not resolve, assume it is generic.

		String theExtRecordID = theSource.extRecordID;
		if (null != theExtRecordID) {
			ExtDb theDb = ExtDb.getExtDb(dbID, theSource.extDbKey, true);
			if ((null == theDb) || theDb.isGeneric()) {
				theExtRecordID = null;
			}
		}

		// For a normal source or the parent for a DTS operation first do a validity check, nothing is exported if
		// invalid.  Then begin writing the XML.  In standalone mode the locked flag is always false, that is for
		// isolated source exports.  Otherwise include the desired/undesired flags and the actual locked flag.

		boolean isNormal = false, isParent = false, isRef = false, isDTS = false;

		if (null == theSource.parentSourceKey) {

			if (!theSource.isDataValid(errors)) {
				return false;
			}

			isParent = theSource.isParent;
			isNormal = !isParent;

			xml.append("<SOURCE");
			if (standalone) {
				xml.append(" LOCKED=\"false\"");
			} else {
				xml.append(" DESIRED=\"" + isDesiredFlag + "\"");
				xml.append(" UNDESIRED=\"" + isUndesiredFlag + '"');
				xml.append(" LOCKED=\"" + theSource.isLocked + '"');
			}
			xml.append(" SERVICE=\"" + theSource.service.serviceCode + '"');

			// The REPLICATE attribute is always based on "this", everything else comes from "theSource" which is
			// usually "this" except in the case of replication it is the original source "this" is based on.  That
			// original source never also appears in the same scenario since the replication source replaced it there,
			// so duplication in the XML cannot occur.

			if (null != originalSourceKey) {
				xml.append(" REPLICATE=\"" + channel + '"');
			}

		// For a DTS transmitter source or reference facility this should not even be called on a locked source from
		// a data set, unless standalone is true in which case the context is being stripped and all properties will
		// be exported.  Note LOCKED, REPLICATE, etc. appear only on the parent, but those also apply to the individual
		// sources.  However SERVICE needs to appear on the reference facility source.

		} else {

			if (theSource.isLocked && (null != theExtRecordID) && !standalone) {
				return false;
			}

			if (0 == theSource.siteNumber) {
				isRef = true;
			} else {
				isDTS = true;
			}

			xml.append("<DTS_SOURCE");

			if (isRef) {
				xml.append(" SERVICE=\"" + theSource.service.serviceCode + '"');
			}
		}

		// Write properties to the XML as appropriate to the type of record.  External record ID appears on a normal,
		// DTS parent, or DTS reference source, unless standalone is true.  A locked normal or parent source based on
		// an external data record is exported as just a reference to the record ID, see discussion above.

		if ((isNormal || isParent || isRef) && (null != theExtRecordID) && !standalone) {
			xml.append(" RECORD_ID=\"" + theExtRecordID + '"');
			if ((isNormal || isParent) && theSource.isLocked) {
				xml.append("/>\n");
				return true;
			}
		}

		// DTS parent does not have site number as it will always be 0.  It also must be 0 on the reference facility,
		// but that must be explicit because that is how the parser identifies the reference facility element.  On
		// normal sources this is optional, output only if >0.

		if (isRef || isDTS || (isNormal && (theSource.siteNumber > 0))) {
			xml.append(" SITE_NUMBER=\"" + theSource.siteNumber + '"');
		}

		// Normal and DTS parent records get facility ID, call sign, city, state, and country; for secondary DTS
		// records these are all copied down from the parent.

		if (isNormal || isParent) {
			xml.append(" ID=\"" + theSource.facilityID + '"');
			xml.append(" CALL_SIGN=\"" + AppCore.xmlclean(theSource.callSign) + '"');
			xml.append(" CITY=\"" + AppCore.xmlclean(theSource.city) + '"');
			xml.append(" STATE=\"" + AppCore.xmlclean(theSource.state) + '"');
			xml.append(" COUNTRY=\"" + theSource.country.countryCode + '"');
		}

		// On all but a DTS transmitter record write channel, zone, status, and file number.  For DTS sources these
		// are copied down from parent, except for the reference facility which may differ.

		if (isNormal || isParent || isRef) {
			xml.append(" DRT=\"" + theSource.isDRT + '"');
			xml.append(" CHANNEL=\"" + theSource.channel + '"');
			if (theSource.zone.key > 0) {
				xml.append(" ZONE=\"" + theSource.zone.zoneCode + '"');
			}
			xml.append(" STATUS=\"" + theSource.status + '"');
			xml.append(" FILE_NUMBER=\"" + AppCore.xmlclean(theSource.fileNumber) + '"');
			if (theSource.signalType.key > 0) {
				xml.append(" MOD_TYPE=\"" + theSource.signalType.key + '"');
			}
		}

		// A DTS parent gets coordinates, those are the DTS reference point, and the maximum distance if defined.  Then
		// recursively export the individual DTS sources.  All others get full operating parameters, the superclass
		// does most of that including closing the tag and writing out pattern data elements.

		if (isParent) {

			location.writeAttributes(xml);

			if (theSource.dtsMaximumDistance > 0.) {
				xml.append(" DTS_MAXIMUM_DISTANCE=\"" + AppCore.formatDistance(theSource.dtsMaximumDistance) + '"');
			}
			if (theSource.dtsSectors.length() > 0) {
				xml.append(" DTS_SECTORS=\"" + dtsSectors + '"');
			}

			xml.append(">\n");

			// Write all non-transient attributes.

			xml.append(theSource.getAllAttributesNT());

			for (SourceEditDataTV dtsSource : theSource.dtsSources.values()) {
				dtsSource.writeToXML(xml, standalone, isDesiredFlag, isUndesiredFlag, errors);
			}

		} else {

			if (theSource.frequencyOffset.key > 0) {
				xml.append(" OFFSET=\"" + theSource.frequencyOffset.frequencyOffsetCode + '"');
			}
			if (theSource.emissionMask.key > 0) {
				xml.append(" MASK=\"" + theSource.emissionMask.emissionMaskCode + '"');
			}

			if (isDTS) {
				xml.append(" TIME_DELAY=\"" + AppCore.formatDecimal(theSource.dtsTimeDelay, 2) + '"');
			}

			theSource.writeAttributes(xml, errors);
		}

		if (isNormal || isParent) {
			xml.append("</SOURCE>\n");
		} else {
			xml.append("</DTS_SOURCE>\n");
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Make a new source object using values from an XML attributes list.  Here just parse and check the attributes
	// that must be set to create the source object, these appear only on a non-DTS or a DTS parent source, the rest is
	// handled by parseAttributesTV() once the source is created.  Some attributes were parsed by the caller, including
	// DESIRED, UNDESIRED, LOCKED, REPLICATE, and RECORD_ID; if an external record ID was found and LOCKED is true this
	// is not used at all, the source will be pulled directly from the station data.  If this is used, the source is
	// either not locked, not from station data, or both.  The attributes parsed here are ID (facility ID), DRT flag,
	// and SITE_NUMBER.  If this is being used to load a user record the record ID is provided.

	protected static SourceEditDataTV makeSourceWithAttributesTV(String element, Attributes attrs,
			StudyEditData theStudy, String theDbID, Service theService, Country theCountry, boolean theIsLocked,
			Integer theUserRecordID, Integer theExtDbKey, String theExtRecordID, ErrorLogger errors) {

		String str;

		int theFacilityID = 0;
		str = attrs.getValue("ID");
		if (null != str) {
			try {
				theFacilityID = Integer.parseInt(str);
			} catch (NumberFormatException nfe) {
				if (null != errors) {
					errors.reportError("Bad ID attribute in " + element + " tag.");
				}
				return null;
			}
		} else {
			if (null != errors) {
				errors.reportError("Missing ID attribute in " + element + " tag.");
			}
			return null;
		}

		boolean theIsDRT = false;
		str = attrs.getValue("DRT");
		if (null != str) {
			theIsDRT = Boolean.valueOf(str).booleanValue();
		}

		SourceEditDataTV newSource = createSource(theStudy, theDbID, theFacilityID, theService, theIsDRT, theCountry,
			theIsLocked, theUserRecordID, theExtDbKey, theExtRecordID, null, errors);
		if (null == newSource) {
			return null;
		}

		// If this is not a parent source it may optionally have a SITE_NUMBER field; for a DTS parent the site number
		// must and will always be 0 so the attribute is irrelevant.

		if (!newSource.isParent) {
			str = attrs.getValue("SITE_NUMBER");
			if (null != str) {
				newSource.siteNumber = -1;
				try {
					newSource.siteNumber = Integer.parseInt(str);
				} catch (NumberFormatException nfe) {
				}
				if (newSource.siteNumber < 0) {
					if (null != errors) {
						errors.reportError("Bad SITE_NUMBER attribute in " + element + " tag.");
					}
					return null;
				}
			}
		}

		// Parse other attributes for the source.

		if (!newSource.parseAttributesTV(element, attrs, errors)) {
			return null;
		}

		return newSource;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Make a new source object as part of a generic import data set using values from an XML attributes list, see
	// createExtSource() for details.

	protected static SourceEditDataTV makeExtSourceWithAttributesTV(String element, Attributes attrs, ExtDb extDb,
			Service theService, Country theCountry, ErrorLogger errors) {

		String str;

		int theFacilityID = 0;
		str = attrs.getValue("ID");
		if (null != str) {
			try {
				theFacilityID = Integer.parseInt(str);
			} catch (NumberFormatException nfe) {
				if (null != errors) {
					errors.reportError("Bad ID attribute in " + element + " tag.");
				}
				return null;
			}
		} else {
			if (null != errors) {
				errors.reportError("Missing ID attribute in " + element + " tag.");
			}
			return null;
		}

		boolean theIsDRT = false;
		str = attrs.getValue("DRT");
		if (null != str) {
			theIsDRT = Boolean.valueOf(str).booleanValue();
		}

		SourceEditDataTV newSource = createExtSource(extDb, theFacilityID, theService, theIsDRT, theCountry, errors);
		if (null == newSource) {
			return null;
		}

		if (!newSource.isParent) {
			str = attrs.getValue("SITE_NUMBER");
			if (null != str) {
				newSource.siteNumber = -1;
				try {
					newSource.siteNumber = Integer.parseInt(str);
				} catch (NumberFormatException nfe) {
				}
				if (newSource.siteNumber < 0) {
					if (null != errors) {
						errors.reportError("Bad SITE_NUMBER attribute in " + element + " tag.");
					}
					return null;
				}
			}
		}

		// Parse other attributes for the source.

		if (!newSource.parseAttributesTV(element, attrs, errors)) {
			return null;
		}

		return newSource;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Make a new DTS source object using values from an XML attributes list.  This must first determine if this is
	// the reference facility or an individual DTS transmitter site.  That is based on the SITE_NUMBER attribute, the
	// reference facility is 0, actual sites are >0.

	public synchronized SourceEditDataTV addDTSSourceWithAttributes(String element, Attributes attrs) {
		return addDTSSourceWithAttributes(element, attrs, null);
	}

	public synchronized SourceEditDataTV addDTSSourceWithAttributes(String element, Attributes attrs,
			ErrorLogger errors) {

		if (!isParent) {
			if (null != errors) {
				errors.reportError("SourceEditDataTV.addDTSSourceWithAttributes() called on non-parent source.");
			}
			return null;
		}

		String str;

		int theSiteNumber = -1;
		str = attrs.getValue("SITE_NUMBER");
		if (null != str) {
			try {
				theSiteNumber = Integer.parseInt(str);
			} catch (NumberFormatException nfe) {
			}
		}
		if (theSiteNumber < 0) {
			if (null != errors) {
				errors.reportError("Missing or bad SITE_NUMBER attribute in " + element + " tag.");
			}
			return null;
		}

		// If this is a DTS reference facility the service and record ID are different than the parent so look for
		// those attributes, else those are copied from the parent.  SERVICE is required, RECORD_ID is optional, if
		// the record ID is not found clear all the primary data keys.  Also if the primary data keys aren't set on
		// the parent in the first place, don't even look for a record ID, it couldn't be used anyway.

		Service theService = service;
		Integer theUserRecordID = userRecordID;
		Integer theExtDbKey = extDbKey;
		String theExtRecordID = extRecordID;

		if (0 == theSiteNumber) {

			theService = null;
			str = attrs.getValue("SERVICE");
			if (null != str) {
				theService = Service.getService(str);
			}
			if (null == theService) {
				if (null != errors) {
					errors.reportError("Missing or bad SERVICE attribute in " + element + " tag.");
				}
				return null;
			}

			theUserRecordID = null;
			if (null != extDbKey) {
				theExtRecordID = attrs.getValue("RECORD_ID");
				if (null == theExtRecordID) {
					theExtRecordID = attrs.getValue("CDBS_ID");
				}
				if (null == theExtRecordID) {
					theExtDbKey = null;
				}
			}
		}

		// Facility ID, call sign, city, state, and country are always copied from the parent.

		SourceEditDataTV newSource = createSource(study, dbID, facilityID, theService, false, country, isLocked,
			theUserRecordID, theExtDbKey, theExtRecordID, key, errors);
		if (null == newSource) {
			return null;
		}

		newSource.siteNumber = theSiteNumber;

		newSource.callSign = callSign;
		newSource.city = city;
		newSource.state = state;

		// For a DTS site, also copy channel, zone, status, file number, and signal type.  The reference facility
		// always has an FCC contour.

		if (theSiteNumber > 0) {

			newSource.channel = channel;
			newSource.zone = zone;
			newSource.status = status;
			newSource.statusType = statusType;
			newSource.fileNumber = fileNumber;
			newSource.appARN = appARN;
			newSource.signalType = signalType;

		} else {

			newSource.serviceAreaMode = Source.SERVAREA_CONTOUR_FCC;
			newSource.serviceAreaCL = Source.SERVAREA_CL_DEFAULT;
		}

		// Parse other attributes, parseAttributesTV() knows to not look for those attributes copied from the parent.

		if (!newSource.parseAttributesTV(element, attrs, errors)) {
			return null;
		}

		// Add source to the list.

		dtsSources.put(newSource.key, newSource);
		addedDTSSourceKeys.add(newSource.key);
		dtsSourceListCache = null;

		return newSource;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Common part of parsing an XML attributes list when building a new source.  Return false on error.  Applies all
	// value checks as in isDataValid(), as well as ensuring required attributes are always present.  The allowed,
	// expected, and required attributes will vary depending on whether this is a normal source (not part of a DTS
	// operation), a DTS parent, a DTS reference facility, or a DTS site source.

	private boolean parseAttributesTV(String element, Attributes attrs, ErrorLogger errors) {

		String str;

		boolean isNormal = false, isRef = false, isDTS = false;
		if (null != parentSourceKey) {
			if (0 == siteNumber) {
				isRef = true;
			} else {
				isDTS = true;
			}
		} else {
			isNormal = !isParent;
		}

		// Call sign, city, and state are required for normal or parent.

		if (isNormal || isParent) {

			callSign = attrs.getValue("CALL_SIGN");
			if ((null == callSign) || (0 == callSign.length())) {
				if (null != errors) {
					errors.reportError("Missing or bad CALL_SIGN attribute in " + element + " tag.");
				}
				return false;
			}
			if (callSign.length() > Source.MAX_CALL_SIGN_LENGTH) {
				callSign = callSign.substring(0, Source.MAX_CALL_SIGN_LENGTH);
			}

			city = attrs.getValue("CITY");
			if ((null == city) || (0 == city.length())) {
				if (null != errors) {
					errors.reportError("Missing or bad CITY attribute in " + element + " tag.");
				}
				return false;
			}
			if (city.length() > Source.MAX_CITY_LENGTH) {
				city = city.substring(0, Source.MAX_CITY_LENGTH);
			}

			state = attrs.getValue("STATE");
			if ((null == state) || (0 == state.length())) {
				if (null != errors) {
					errors.reportError("Missing or bad STATE attribute in " + element + " tag.");
				}
				return false;
			}
			if (state.length() > Source.MAX_STATE_LENGTH) {
				state = state.substring(0, Source.MAX_STATE_LENGTH);
			}
		}

		// Channel is required for normal, parent, and DTS reference.  Only the widest possible range is checked here.
		// Checking the study channel range is the caller's responsibility as an out-of-range is usually not an error.

		if (isNormal || isParent || isRef) {

			str = attrs.getValue("CHANNEL");
			if (null != str) {
				try {
					channel = Integer.parseInt(str);
				} catch (NumberFormatException nfe) {
				}
			}
			if ((channel < SourceTV.CHANNEL_MIN) || (channel > SourceTV.CHANNEL_MAX)) {
				if (null != errors) {
					errors.reportError("Missing or bad CHANNEL attribute in " + element + " tag.");
				}
				return false;
			}

			// Also for normal, parent, and DTS reference, zone, status, and file number may appear.  However none of
			// these are required, the default null/empty conditions are valid.

			str = attrs.getValue("ZONE");
			if (null != str) {
				zone = Zone.getZone(str);
				if (zone.key < 1) {
					if (null != errors) {
						errors.reportError("Bad ZONE attribute in " + element + " tag.");
					}
					return false;
				}
			}

			str = attrs.getValue("STATUS");
			if (null != str) {
				status = str;
				statusType = ExtDbRecord.getStatusType(str);
				if (ExtDbRecord.STATUS_TYPE_OTHER != statusType) {
					status = ExtDbRecord.STATUS_CODES[statusType];
				}
				if (status.length() > Source.MAX_STATUS_LENGTH) {
					status = status.substring(0, Source.MAX_STATUS_LENGTH);
				}
			}

			str = attrs.getValue("FILE_NUMBER");
			if (null != str) {
				if (str.length() > Source.MAX_FILE_NUMBER_LENGTH) {
					str = str.substring(0, Source.MAX_FILE_NUMBER_LENGTH);
				}
				fileNumber = str;
				appARN = "";
				for (int i = 0; i < fileNumber.length(); i++) {
					if (Character.isDigit(fileNumber.charAt(i))) {
						appARN = fileNumber.substring(i);
						break;
					}
				}
			}

			// Signal type, ignore for analog.  For legacy support, optional for digital; if not present use default.

			if (service.serviceType.digital) {
				str = attrs.getValue("MOD_TYPE");
				if (null == str) {
					signalType = SignalType.getDefaultObject();
				} else {
					int typ = 0;
					try {
						typ = Integer.parseInt(str);
					} catch (NumberFormatException ne) {
					}
					signalType = SignalType.getSignalType(typ);
					if (signalType.key < 1) {
						if (null != errors) {
							errors.reportError("Bad MOD_TYPE attribute in " + element + " tag.");
						}
						return false;
					}
				}
			}
		}

		// Latitude and longitude appear on a DTS parent, but none of the other operating parameters will appear so
		// the superclass parser will not be called in this case and the coordinates have to be parsed here instead.
		// DTS distance limit may also appear for a DTS parent but is not required, default 0 will use a table value.

		if (isParent) {

			if (!location.parseAttributes(element, attrs, recordType, errors)) {
				return false;
			}

			str = attrs.getValue("DTS_MAXIMUM_DISTANCE");
			if (null != str) {
				dtsMaximumDistance = Source.DISTANCE_MIN - 1.;
				try {
					dtsMaximumDistance = Double.parseDouble(str);
				} catch (NumberFormatException nfe) {
				}
				if ((dtsMaximumDistance < Source.DISTANCE_MIN) || (dtsMaximumDistance > Source.DISTANCE_MAX)) {
					if (null != errors) {
						errors.reportError("Bad DTS_MAXIMUM_DISTANCE attribute in " + element + " tag.");
					}
					return false;
				}
			}

			str = attrs.getValue("DTS_SECTORS");
			if (null != str) {
				str = str.trim();
				if (null != GeoSectors.validateString(str)) {
					if (null != errors) {
						errors.reportError("Bad DTS_SECTORS attribute in " + element + " tag.");
					}
					return false;
				}
				dtsSectors = str;
			} else {
				dtsSectors = "";
			}

			return true;
		}

		// Offset is not required, null object is valid.

		str = attrs.getValue("OFFSET");
		if (null != str) {
			frequencyOffset = FrequencyOffset.getFrequencyOffset(str);
			if (frequencyOffset.key < 1) {
				if (null != errors) {
					errors.reportError("Bad OFFSET attribute in " + element + " tag.");
				}
				return false;
			}
		}

		// Emission mask is required if service type requires, else is ignored.

		if (service.serviceType.needsEmissionMask) {
			str = attrs.getValue("MASK");
			if (null != str) {
				emissionMask = EmissionMask.getEmissionMask(str);
			}
			if (emissionMask.key < 1) {
				if (null != errors) {
					errors.reportError("Missing or bad MASK attribute in " + element + " tag.");
				}
				return false;
			}
		}

		// On a DTS transmitter source time delay is optional, default 0.

		if (isDTS) {
			str = attrs.getValue("TIME_DELAY");
			if (null != str) {
				dtsTimeDelay = Source.TIME_DELAY_MIN - 1.;
				try {
					dtsTimeDelay = Double.parseDouble(str);
				} catch (NumberFormatException nfe) {
				}
				if ((dtsTimeDelay < Source.TIME_DELAY_MIN) || (dtsTimeDelay > Source.TIME_DELAY_MAX)) {
					if (null != errors) {
						errors.reportError("Bad TIME_DELAY attribute in " + element + " tag.");
					}
					return false;
				}
			}
		}

		// Superclass handles the remaining properties.

		return parseAttributes(element, attrs, errors);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// See makeCommentText() in superclass.

	protected ArrayList<String> getComments() {

		ArrayList<String> result = null;

		String lic = getAttribute(Source.ATTR_LICENSEE);
		boolean hasLic = ((null != lic) && (lic.length() > 0));
		boolean isSharingHost = (null != getAttribute(Source.ATTR_IS_SHARING_HOST));
		boolean isBaseline = (null != getAttribute(Source.ATTR_IS_BASELINE));

		if (hasLic || isDRT || isSharingHost || isBaseline) {
			result = new ArrayList<String>();
			if (hasLic) {
				result.add("Licensee: " + lic);
			}
			if (isDRT) {
				result.add("Digital replacement translator");
			}
			if (isSharingHost) {
				result.add("Shared channel");
			}
			if (isBaseline) {
				result.add("Baseline record");
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Override methods in the Record interface for properties not defined in the superclass.

	public boolean isReplication() {

		return (null != originalSourceKey);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getFacilityID() {

		return String.valueOf(facilityID);
	}

	public String getSortFacilityID() {

		return String.format(Locale.US, "%07d", facilityID);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getSiteCount() {

		if (service.isDTS) {
			int n = dtsSources.size() - 1;
			if (n < 1) {
				n = 1;
			}
			return String.valueOf(n);
		}
		return "1";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getChannel() {

		if ((null != originalSourceKey) && (null != study)) {
			SourceEditDataTV origSource = (SourceEditDataTV)study.getSource(originalSourceKey);
			if (null != origSource) {
				return (service.serviceType.digital ? "D" : "N") + String.valueOf(channel) +
					frequencyOffset.frequencyOffsetCode + " (" + origSource.getChannel() + ")";
			}
		}
		return (service.serviceType.digital ? "D" : "N") + String.valueOf(channel) +
			frequencyOffset.frequencyOffsetCode;
	}

	public String getSortChannel() {

		return String.format(Locale.US, "%02d%c", channel, (service.serviceType.digital ? 'D' : 'N'));
	}

	public String getOriginalChannel() {

		if ((null != originalSourceKey) && (null != study)) {
			SourceEditDataTV origSource = (SourceEditDataTV)study.getSource(originalSourceKey);
			if (null != origSource) {
				return origSource.getChannel();
			}
		}
		return getChannel();
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getFrequency() {

		return getFrequency(channel, service);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static String getFrequency(int theChannel, Service theService) {

		if (0 == theChannel) {
			return "0 MHz";
		}

		double freq = 0.;
		if (theChannel < 5) {
			freq = 57. + ((double)(theChannel - 2) * 6.);
		} else {
			if (theChannel < 7) {
				freq = 79. + ((double)(theChannel - 5) * 6.);
			} else {
				if (theChannel < 14) {
					freq = 177. + ((double)(theChannel - 7) * 6.);
				} else {
					freq = 473. + ((double)(theChannel - 14) * 6.);
				}
			}
		}

		if (theService.serviceType.digital) {
			return String.format(Locale.US, "%.0f MHz", freq);
		} else {
			freq -= 1.75;
			return String.format(Locale.US, "%.2f MHz", freq);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getZone() {

		if (zone.key > 0) {
			return zone.name;
		}
		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getStatus() {

		return status;
	}

	public String getSortStatus() {

		return String.valueOf(statusType);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getARN() {

		return appARN;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getFrequencyOffset() {

		if (frequencyOffset.key > 0) {
			return frequencyOffset.name;
		}
		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getEmissionMask() {

		if (service.serviceType.needsEmissionMask) {
			if (emissionMask.key > 0) {
				return emissionMask.name;
			}
		}
		return "";
	}
}
