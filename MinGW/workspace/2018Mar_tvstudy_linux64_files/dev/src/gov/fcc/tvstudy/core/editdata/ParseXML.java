//
//  ParseXML.java
//  TVStudy
//
//  Copyright (c) 2016-2018 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.editdata;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;

import java.util.*;
import java.util.regex.*;
import java.io.*;

import javax.xml.parsers.*;

import org.xml.sax.*;
import org.xml.sax.helpers.*;


//=====================================================================================================================
// XML parser.  This can work both with and without a study context.  If a study is provided this can build scenarios
// and will add them directly to the study model, else it will just parse source objects and make those available in
// the sources public array.  This may also be used to parse a single isolated user record which is stored as XML, if
// a user record ID is provided this will read until one full source record is parsed, assign that the ID, and then
// ignore all subsequent data in the XML file.  The single record is available in the source public property.  A third
// mode here is used to import into a generic data set, in that mode multiple sources will be parsed and assigned an
// external data set key and new record ID obtained from the ExtDb object provided, see the createExtSource() methods
// in SourceEditData*.

public class ParseXML extends DefaultHandler {

	// Current XML export version.

	public static final int XML_VERSION = 105000;

	// This returns all unique sources loaded or created when those aren't being added directly to a study.

	public ArrayList<SourceEditData> sources;

	// This returns a single source in user record parsing mode, else it is null.

	public SourceEditData source;

	// Number of scenarios added to the study in scenario mode.

	public int scenarioCount;

	// Indicate if TVSTUDY element was seen, so caller can report a reason for a non-error but empty result.

	public boolean hadStudy;

	// Functional state.

	private StudyEditData study;
	private String dbID;
	private Integer userRecordID;
	private int parseRecordType;
	private int parseStudyType;
	private ExtDb importExtDb;
	private ErrorLogger errors;

	private boolean userRecordMode;
	private boolean importMode;
	private boolean scenarioMode;

	// This is the data set used to attempt to resolve by-reference sources in any mode but user-record.  For by-
	// reference records found during parsing, search is deferred to the end of the enclosing element so a batch
	// search can be run.  This is performance optimization, record-by-record searches can be really slow.  There may
	// be more than one lookup data set used, a primary and alternate.

	private LookupIndex lookupIndex;
	private LookupIndex alternateIndex;

	private HashSet<String> searchIDs;
	private ArrayList<SearchItem> searchItems;

	// Parsing state.

	private boolean ignoreAll;

	private boolean inStudy;
	private int xmlVersion;
	private ArrayList<SourceEditData> studyNewSources;
	private ArrayList<ScenarioItem> scenarioItems;

	private boolean inScenario;
	private String scenarioName;
	private String scenarioDescription;
	private ArrayList<Scenario.SourceListItem> scenarioSourceItems;
	private HashMap<Integer, ArrayList<String>> scenarioParameters;
	private boolean hasDesiredTV;

	private boolean inParameter;
	private int parameterKey;
	private ArrayList<String> parameterValues;

	private boolean inValue;
	private int valueIndex;

	private boolean inSource;
	private boolean isDesired;
	private boolean isUndesired;
	private SourceEditData newSource;
	private SourceEditDataTV newSourceTV;
	private SourceEditDataWL newSourceWL;
	private SourceEditDataFM newSourceFM;
	private boolean didMakeSource;
	private int replicateToChannel;

	private boolean inDTSSource;
	private SourceEditDataTV newDTSSource;

	private String horizontalPatternName;
	private String verticalPatternName;
	private String matrixPatternName;

	private boolean inPat;

	// Stack of element names and content buffers for nested elements.

	private ArrayDeque<String> elements;
	private ArrayDeque<StringWriter> buffers;

	// For parsing APAT, EPAT, and MPAT data in XML.

	private Pattern patternParser;


	//-----------------------------------------------------------------------------------------------------------------
	// In the first form, scenarios and sources are being imported into a study.  Objects will be added directly to
	// the study as parsing proceeds.  The study argument must not be null here.  A data set key may be provided for
	// resolving by-reference sources.  If that argument is null the study default data set is used if that exists,
	// else by-reference sources are ignored.  The parsed records are filtered according to the study type, ignoring
	// any record types not allowed for the study.  If the lookup data set key is provided, an alternate lookup key
	// may also be provided, if a record is not found in the primary the alternate is checked.  The main use of that
	// capability is for TV import, so both CDBS and LMS sets can be provided to resolve all possible record IDs.

	public ParseXML(StudyEditData theStudy, Integer theLookupExtDbKey, Integer theAlternateExtDbKey,
			ErrorLogger theErrors) {
		super();
		doSetup(theStudy, theStudy.dbID, theLookupExtDbKey, theAlternateExtDbKey, null, null, 0,
			theStudy.study.studyType, theErrors);
	}

	// The second form is used to import a user record, this is only used internally from SourceEditData to parse data
	// out of the user record table, it will not be used on data from other sources.  This mode may or may not have a
	// study context, if study is null the dbID must be passed explicitly.  This never has a by-reference search
	// context as user records will always be standalone XML blocks.  This will parse until one valid source is found
	// then ignore the rest of the input; XML from the user record table should only ever contain one source.

	public ParseXML(StudyEditData theStudy, String theDbID, Integer theUserRecordID, ErrorLogger theErrors) {
		super();
		doSetup(theStudy, theDbID, null, null, theUserRecordID, null, 0, 0, theErrors);
	}

	// The next form is used to parse sources outside any particular context, the resulting objects are transient and
	// presumably will be shown to the user in a selection UI then selected sources transferred to a study context
	// via derivation, see deriveSource() in SourceEditData.  This may have a by-reference context, or not, and the
	// records may be filtered by record type, study type, or neither.  If the recordType argument is >0 only that
	// type will be accepted, all others will be ignored, else if the studyType argument is >0 only record types that
	// are allowed for that study type will be accepted.

	public ParseXML(String theDbID, Integer theLookupExtDbKey, Integer theAlternateExtDbKey, int theRecordType,
			int theStudyType, ErrorLogger theErrors) {
		super();
		doSetup(null, theDbID, theLookupExtDbKey, theAlternateExtDbKey, null, null, theRecordType, theStudyType,
			theErrors);
	}

	// The last form is used to parse XML into a generic import data set context, all the needed information is in
	// the ExtDb object provided.  This may also have a by-reference context, that is provided separately of course.
	// This never has a study context.  Records will be filtered according to the record type for the import set.
	// Also the ExtDb object must be configured to hand out new record IDs, see ExtDb.

	public ParseXML(ExtDb theImportExtDb, Integer theLookupExtDbKey, Integer theAlternateExtDbKey,
			ErrorLogger theErrors) {
		super();
		doSetup(null, theImportExtDb.dbID, theLookupExtDbKey, theAlternateExtDbKey, null, theImportExtDb,
			theImportExtDb.recordType, 0, theErrors);
	}

	// The common part of construction, see comments above.

	private void doSetup(StudyEditData theStudy, String theDbID, Integer theLookupExtDbKey,
			Integer theAlternateExtDbKey, Integer theUserRecordID, ExtDb theImportExtDb, int theRecordType,
			int theStudyType, ErrorLogger theErrors) {

		study = theStudy;
		dbID = theDbID;
		userRecordID = theUserRecordID;
		parseRecordType = theRecordType;
		parseStudyType = theStudyType;
		importExtDb = theImportExtDb;
		errors = theErrors;

		// Set flags controlling the major branchings of the logic.  In import mode a study context is irrelevant so
		// the argument is cleared.  In user record mode the study may be used but only for ownership of the source
		// object, so the scenarioMode flag is not set in that case.  Note if all three of these are false this is in
		// "generic read mode", all unique sources found are returned in the public list and all are transient.

		userRecordMode = (null != userRecordID);
		importMode = (null != importExtDb);
		if (importMode) {
			study = null;
		}
		if (!userRecordMode) {
			scenarioMode = (null != study);
		}

		// Get an ExtDb context to use for resolving by-reference sources.  This is always optional, but if no key is
		// provided and a study context is present, try using the default data set key from the study.  Also do some
		// sanity checks.  If a lookup set is found but it provides records that are not allowed per filter settings,
		// ignore it.  Also in import set mode if the lookup set is the same as the destination for import, ignore it.
		// This is irrelevant in user-record mode, there can never be a by-reference record in that case.  A second
		// alternate key may also be provided, it is used only if the primary key is set, subject to the same checks.
		// Note generic data sets cannot be used for this since the record IDs are not portable, those should not even
		// be offerred by any UI leading to this so if somehow selected just ignore.

		if (!userRecordMode) {

			if (null == theLookupExtDbKey) {
				theAlternateExtDbKey = null;
			}

			ExtDb theExtDb = null;

			if ((null == theLookupExtDbKey) && scenarioMode) {
				theLookupExtDbKey = study.extDbKey;
			}
			if (null != theLookupExtDbKey) {
				theExtDb = ExtDb.getExtDb(dbID, theLookupExtDbKey);
				if ((null != theExtDb) && theExtDb.isGeneric()) {
					theExtDb = null;
				}
			}

			if (null != theExtDb) {
				if (parseRecordType > 0) {
					if (parseRecordType != theExtDb.recordType) {
						theExtDb = null;
					}
				} else {
					if (parseStudyType > 0) {
						if (!Study.isRecordTypeAllowed(parseStudyType, theExtDb.recordType)) {
							theExtDb = null;
						}
					}
				}
				if (importMode && importExtDb.key.equals(theExtDb.key)) {
					theExtDb = null;
				}
			}

			// If a lookup data set was found, parsed shareable sources are indexed to resolve later references.  If a
			// study is available the index is seeded with sources from the lookup set that are already in the study.
			// In that case replications can also occur, and may also be shared.  See doScenarioAdd().

			if (null != theExtDb) {

				lookupIndex = new LookupIndex();
				lookupIndex.lookupExtDb = theExtDb;
				lookupIndex.sharedSources = new HashMap<String, SourceEditData>();

				searchIDs = new HashSet<String>();

				if (scenarioMode) {

					lookupIndex.sharedReplicationSources = new ArrayList<HashMap<String, SourceEditDataTV>>();
					study.loadSharedSourceIndex(lookupIndex.lookupExtDb.key, lookupIndex.sharedSources,
						lookupIndex.sharedReplicationSources);

					searchItems = new ArrayList<SearchItem>();
				}

				// Set up the alternate the same way as needed.

				theExtDb = null;

				if (null != theAlternateExtDbKey) {
					theExtDb = ExtDb.getExtDb(dbID, theAlternateExtDbKey);
					if ((null != theExtDb) && theExtDb.isGeneric()) {
						theExtDb = null;
					}
				}

				if (null != theExtDb) {
					if (parseRecordType > 0) {
						if (parseRecordType != theExtDb.recordType) {
							theExtDb = null;
						}
					} else {
						if (parseStudyType > 0) {
							if (!Study.isRecordTypeAllowed(parseStudyType, theExtDb.recordType)) {
								theExtDb = null;
							}
						}
					}
					if (importMode && importExtDb.key.equals(theExtDb.key)) {
						theExtDb = null;
					}
				}

				if (null != theExtDb) {

					alternateIndex = new LookupIndex();
					alternateIndex.lookupExtDb = theExtDb;
					alternateIndex.sharedSources = new HashMap<String, SourceEditData>();

					if (scenarioMode) {

						alternateIndex.sharedReplicationSources = new ArrayList<HashMap<String, SourceEditDataTV>>();
						study.loadSharedSourceIndex(alternateIndex.lookupExtDb.key, alternateIndex.sharedSources,
							alternateIndex.sharedReplicationSources);
					}
				}
			}
		}

		// If not in scenario mode or user record mode, all unique sources found will be returned in the list property.

		if (!scenarioMode && !userRecordMode) {
			sources = new ArrayList<SourceEditData>();
		}

		// Setup needed for all modes.

		valueIndex = -1;

		elements = new ArrayDeque<String>();
		buffers = new ArrayDeque<StringWriter>();

		patternParser = Pattern.compile("[,\\n]");
	}


	//=================================================================================================================
	// Index for a data set used for resolving record IDs, more than one set may be checked.

	private class LookupIndex {
		private ExtDb lookupExtDb;
		private HashMap<String, SourceEditData> sharedSources;
		private ArrayList<HashMap<String, SourceEditDataTV>> sharedReplicationSources;
	}


	//=================================================================================================================
	// Data for a record in the deferred search list.

	private class SearchItem {
		private String extRecordID;
		private boolean isDesired;
		private boolean isUndesired;
		private int replicateToChannel;
	}


	//=================================================================================================================
	// Data for a new scenario, deferred until all parsed with no errors.

	private class ScenarioItem {
		private String name;
		private String description;
		private ArrayList<Scenario.SourceListItem> sourceItems;
		private HashMap<Integer, ArrayList<String>> parameters;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Start of element.  Push the element name and a new content buffer on to the stacks.

	public void startElement(String nameSpc, String locName, String qName, Attributes attrs) throws SAXException {

		String str;

		elements.push(qName);
		buffers.push(new StringWriter());

		// The TVSTUDY element must be present and include a version number, and nothing before 1.3 is supported.
		// Only the first study element is processed, all later are ignored.

		if (qName.equals("TVSTUDY")) {

			if (hadStudy || ignoreAll) {
				return;
			}

			if (inStudy) {
				errors.reportError("TVSTUDY elements may not be nested.");
				throw new SAXException();
			}
			inStudy = true;
			hadStudy = true;

			str = attrs.getValue("VERSION");
			if (null != str) {
				try {
					xmlVersion = Integer.parseInt(str);
				} catch (NumberFormatException nfe) {
				}
			}
			if (xmlVersion <= 0) {
				errors.reportError("Missing or bad VERSION attribute in TVSTUDY tag.");
				throw new SAXException();
			}

			if ((xmlVersion < 103000) || (xmlVersion > XML_VERSION)) {
				errors.reportError("Format version is not supported.");
				throw new SAXException();
			}

			if (scenarioMode) {
				studyNewSources = new ArrayList<SourceEditData>();
				scenarioItems = new ArrayList<ScenarioItem>();
			}

			return;
		}

		// Start of a new scenario.  Ignore this outside of a TVSTUDY element, if not in scenario parsing mode, or if
		// ignoreAll is set.  Else error if parsing already inside a SCENARIO element.  Note SOURCE elements within
		// the scenario may still be parsed even if this element is ignored.

		if (qName.equals("SCENARIO")) {

			if (!inStudy || !scenarioMode || ignoreAll) {
				return;
			}

			if (inScenario) {
				errors.reportError("SCENARIO elements may not be nested.");
				throw new SAXException();
			}
			inScenario = true;

			// If the name is not provided or the value doesn't pass checks assign a generic default, however the name
			// does not have to be unique, the ScenarioEditData constructor will make the name unique if needed.

			scenarioName = attrs.getValue("NAME");
			if ((null == scenarioName) || !DbCore.checkScenarioName(scenarioName, study, false)) {
				scenarioName = "Import";
			}

			scenarioDescription = "";
			scenarioSourceItems = new ArrayList<Scenario.SourceListItem>();
			scenarioParameters = new HashMap<Integer, ArrayList<String>>();

			return;
		}

		// A DESCRIPTION element is handled at the end of the element.  This is a trivial element so not worth the
		// trouble to do nesting or context checks here.

		if (qName.equals("DESCRIPTION")) {
			return;
		}

		// A PARAMETER element is ignored if not in a SCENARIO, which will always be true if SCENARIO is being ignored.
		// Otherwise the only check is for nesting, it's not worth enforcing structure any more strictly for these.
		// The only attribute is the key, that must be present, but is just checked for general validity (> 0).  If a
		// key does not exist in the scenario it will be ignored later; this is just providing values for existing
		// parameters and is always optional.  Duplicate keys are not an error, the last values found will be used.  

		if (qName.equals("PARAMETER")) {

			if (!inScenario || ignoreAll) {
				return;
			}

			if (inParameter) {
				errors.reportError("PARAMETER elements may not be nested.");
				throw new SAXException();
			}
			inParameter = true;

			str = attrs.getValue("KEY");
			if (null != str) {
				try {
					parameterKey = Integer.parseInt(str);
				} catch (NumberFormatException nfe) {
				}
			}
			if (parameterKey <= 0) {
				errors.reportError("Missing or bad KEY attribute in PARAMETER tag.");
				throw new SAXException();
			}

			parameterValues = new ArrayList<String>();

			return;
		}

		// A VALUE element provides one value for a parameter, identified by 0..N index, these are ignored outside a
		// PARAMETER and cannot be nested.  Indices may be out of sequence and not all represented, the value list
		// will be expanded by adding nulls as needed.  Also duplicate indices are not an error, the last is used.

		if (qName.equals("VALUE")) {

			if (!inParameter || ignoreAll) {
				return;
			}

			if (inValue) {
				errors.reportError("VALUE elements cannot be nested.");
				throw new SAXException();
			}
			inValue = true;

			str = attrs.getValue("INDEX");
			if (null != str) {
				try {
					valueIndex = Integer.parseInt(str);
				} catch (NumberFormatException nfe) {
				}
			}
			if ((valueIndex < 0) || (valueIndex >= Parameter.MAX_VALUE_COUNT)) {
				errors.reportError("Missing or bad INDEX attribute in VALUE tag.");
				throw new SAXException();
			}

			if (valueIndex >= parameterValues.size()) {
				for (int i = parameterValues.size(); i <= valueIndex; i++) {
					parameterValues.add(null);
				}
			}

			return;
		}

		// Start of a SOURCE element.  This may be in a SCENARIO element or not, that will be handled at the end of
		// the element.  However when parsing scenarios, sources outside a scenario are ignored.  Outside of a TVSTUDY
		// element this is ignored.  Usual check for illegal nesting.

		if (qName.equals("SOURCE")) {

			if (!inStudy || (scenarioMode && !inScenario) || ignoreAll) {
				return;
			}

			if (inSource) {
				errors.reportError("SOURCE elements may not be nested.");
				throw new SAXException();
			}
			inSource = true;

			// The service attribute is extracted first to identify the record type.  However in format versions prior
			// to 1.5 the service was not included on by-reference records, only TV records could exist then so the
			// type was implicit.  In that case assume TV and do not report the missing attribute here.

			Service service = null;
			str = attrs.getValue("SERVICE");
			if (null != str) {
				service = Service.getService(str);
				if (null == service) {
					errors.reportError("Bad SERVICE attribute in SOURCE tag.");
					throw new SAXException();
				}
			}

			int recordType = Source.RECORD_TYPE_TV;
			if (xmlVersion >= 105000) {
				if (null == service) {
					errors.reportError("Missing SERVICE attribute in SOURCE tag.");
					throw new SAXException();
				}
				recordType = service.serviceType.recordType;
			}

			// Make sure the record type is allowed, if not ignore it.

			if (parseRecordType > 0) {
				if (recordType != parseRecordType) {
					inSource = false;
					return;
				}
			} else {
				if (parseStudyType > 0) {
					if (!Study.isRecordTypeAllowed(parseStudyType, recordType)) {
						inSource = false;
						return;
					}
				}
			}

			// If a scenario is being loaded, extract the study flags.  The flags are optional, the default is true.
			// However the flags are not always honored, they may be altered in special cases, see endElement().  For
			// legacy support the attribute STUDY is a synonym for DESIRED.

			if (inScenario) {

				str = attrs.getValue("DESIRED");
				if (null == str) {
					str = attrs.getValue("STUDY");
				}
				isDesired = ((null == str) || Boolean.valueOf(str).booleanValue());

				str = attrs.getValue("UNDESIRED");
				isUndesired = ((null == str) || Boolean.valueOf(str).booleanValue());
			}

			// Extract the locked flag, also optional, default is true.

			str = attrs.getValue("LOCKED");
			boolean isLocked = ((null == str) || Boolean.valueOf(str).booleanValue());

			// If a study is available, check for a REPLICATE attribute on a TV record.  Replication cannot be done
			// without a study context to retain the original source.  The source defined by the SOURCE element is the
			// original, REPLICATE means to create another source replicating that original, that will be done at the
			// end of the SOURCE element.  Skip this in user record and import modes, those do not allow replication.

			if (inScenario && (Source.RECORD_TYPE_TV == recordType)) {

				str = attrs.getValue("REPLICATE");
				if (null != str) {

					try {
						replicateToChannel = Integer.parseInt(str);
					} catch (NumberFormatException nfe) {
					}

					if ((replicateToChannel < SourceTV.CHANNEL_MIN) || (replicateToChannel > SourceTV.CHANNEL_MAX)) {
						errors.reportError("Bad REPLICATE attribute in SOURCE tag.");
						throw new SAXException();
					}
				}
			}

			// A source based on a record from a data set with persistent, portable IDs is indicated by the presence
			// of a RECORD_ID attribute.  This does not apply and the attribute is ignored for wireless records, and
			// in user-record mode.  IDs for wireless records are valid only for a specific data set so should never
			// be exported.  User records should never be by-reference.  In either case assume all attributes will be
			// present, if not an error should and will occur.  CDBS_ID is a legacy synonym for RECORD_ID.

			String extRecordID = null;
			if (!userRecordMode && (Source.RECORD_TYPE_WL != recordType)) {
				extRecordID = attrs.getValue("RECORD_ID");
				if (null == extRecordID) {
					extRecordID = attrs.getValue("CDBS_ID");
				}
			}

			// A locked data set record is a reference only, no further properties are present in attributes or nested
			// elements.  If a data set is available these records may be loaded from that, otherwise the element is
			// ignored.  It is not an error if a record does not exist in the lookup data set, again the element is
			// just ignored.  Sources loaded this way are kept in a temporary sharing index so later references to the
			// same record don't have to be re-loaded.  If a study is available the index was seeded with sources from
			// the data set already in the study so those also do not have to be loaded.  Otherwise a search query is
			// run to find the record.  For performance the search is not done immediately, the record is added to a
			// search list which will be run as a batch at the end of the scenario or study element, see endElement().

			if (isLocked && (null != extRecordID)) {

				if (null == lookupIndex) {
					inSource = false;
				} else {

					newSource = lookupIndex.sharedSources.get(extRecordID);
					if ((null == newSource) && (null != alternateIndex)) {
						newSource = alternateIndex.sharedSources.get(extRecordID);
					}

					if (null == newSource) {

						searchIDs.add(extRecordID);

						if (inScenario) {

							SearchItem theItem = new SearchItem();
							theItem.extRecordID = extRecordID;
							theItem.isDesired = isDesired;
							theItem.isUndesired = isUndesired;
							theItem.replicateToChannel = replicateToChannel;

							searchItems.add(theItem);
						}

						inSource = false;

					} else {

						// In a scenario the same source may be seen more than once because it was used in a previous
						// scenario, or even in the same scenario if there are multiple replications.  In any other
						// mode sources should never be duplicated so ignore. 

						if (inScenario) {

							switch (recordType) {

								case Source.RECORD_TYPE_TV: {
									newSourceTV = (SourceEditDataTV)newSource;
									break;
								}

								case Source.RECORD_TYPE_FM: {
									newSourceFM = (SourceEditDataFM)newSource;
									break;
								}
							}

						} else {
							inSource = false;
						}
					}
				}

				return;
			}

			// This is an editable source or one not based on a portable data set record.  In either case the source
			// will never be shared so this will always create a new source object from attributes.  At this point if
			// the SERVICE attribute was not found it is reported as an error.  Also extract the COUNTRY attribute
			// which must always be present.

			if (null == service) {
				errors.reportError("Missing SERVICE attribute in SOURCE tag.");
				throw new SAXException();
			}

			Country country = null;
			str = attrs.getValue("COUNTRY");
			if (null != str) {
				country = Country.getCountry(str);
			}
			if (null == country) {
				errors.reportError("Missing or bad COUNTRY attribute in SOURCE tag.");
				throw new SAXException();
			}

			// Create the new source.  If a RECORD_ID attribute is present and that ID exists in the lookup data set
			// (if any) the data set key and ID are included on the new source, but that is advisory only.  In any
			// case all source properties must be present in attributes or element content so the source is never
			// loaded from the data set here.  However in user record mode, the first source seen in this context is
			// saved as the user record, in that case the record is always locked and any record ID from the tag is
			// ignored.  In import mode, again the source is always locked, and the source being created is actually
			// the external data set record itself so the data set key and a new record ID are assigned, that is
			// handled by separate makeExtSourceWithAttributes*() methods because those also have to use a method on
			// the ExtDb object to obtain the new source key for the source object.

			Integer extDbKey = null;

			if (userRecordMode || importMode) {

				isLocked = true;
				extRecordID = null;

			} else {

				if ((null != lookupIndex) && (null != extRecordID) &&
						ExtDbRecord.doesRecordIDExist(lookupIndex.lookupExtDb, extRecordID)) {
					extDbKey = lookupIndex.lookupExtDb.key;
				} else {
					extRecordID = null;
				}
			}

			switch (recordType) {

				case Source.RECORD_TYPE_TV: {

					if (importMode) {
						newSourceTV = SourceEditDataTV.makeExtSourceWithAttributesTV(qName, attrs, importExtDb,
							service, country, errors);
					} else {
						newSourceTV = SourceEditDataTV.makeSourceWithAttributesTV(qName, attrs, study, dbID, service,
							country, isLocked, userRecordID, extDbKey, extRecordID, errors);
					}
					if (null == newSourceTV) {
						throw new SAXException();
					}
					newSource = newSourceTV;

					// If a study is available check channel for TV records, if out of range log it and ignore the
					// element.  The channel was already checked for validity by the attributes parser so this is not
					// an error, the record is valid but it is just not allowed in this particular study.  If a
					// replication attribute was found this checks the replication channel not the original; the
					// original can be out of range as long as the replication is in range.

					if (inScenario) {
						if (0 == replicateToChannel) {
							if ((newSourceTV.channel < study.getMinimumChannel()) ||
									(newSourceTV.channel > study.getMaximumChannel())) {
								if (null != errors) {
									errors.logMessage(ExtDbRecord.makeMessage(newSource,
										"Ignored, channel is out of range for study."));
								}
								inSource = false;
								return;
							}
						} else {
							if ((replicateToChannel < study.getMinimumChannel()) ||
									(replicateToChannel > study.getMaximumChannel())) {
								if (null != errors) {
									errors.logMessage(ExtDbRecord.makeMessage(newSource,
										"Ignored, replication channel " + String.valueOf(replicateToChannel) +
										" is out of range for study."));
								}
								inSource = false;
								return;
							}
						}
					}

					break;
				}

				// Wireless records never have a data set reference, see comments above.

				case Source.RECORD_TYPE_WL: {

					if (importMode) {
						newSourceWL = SourceEditDataWL.makeExtSourceWithAttributesWL(qName, attrs, importExtDb,
							service, country, errors);
					} else {
						newSourceWL = SourceEditDataWL.makeSourceWithAttributesWL(qName, attrs, study, dbID, service,
							country, isLocked, userRecordID, null, null, errors);
					}
					if (null == newSourceWL) {
						throw new SAXException();
					}
					newSource = newSourceWL;

					break;
				}

				case Source.RECORD_TYPE_FM: {

					if (importMode) {
						newSourceFM = SourceEditDataFM.makeExtSourceWithAttributesFM(qName, attrs, importExtDb,
							service, country, errors);
					} else {
						newSourceFM = SourceEditDataFM.makeSourceWithAttributesFM(qName, attrs, study, dbID, service,
							country, isLocked, userRecordID, extDbKey, extRecordID, errors);
					}
					if (null == newSourceFM) {
						throw new SAXException();
					}
					newSource = newSourceFM;

					break;
				}
			}

			didMakeSource = true;

			// Get pattern names from attributes and store until pattern data is parsed.

			str = attrs.getValue("APAT_NAME");
			if (null != str) {
				if (str.length() > Source.MAX_PATTERN_NAME_LENGTH) {
					str = str.substring(0, Source.MAX_PATTERN_NAME_LENGTH);
				}
				horizontalPatternName = str;
			} else {
				horizontalPatternName = "";
			}

			str = attrs.getValue("EPAT_NAME");
			if (null != str) {
				if (str.length() > Source.MAX_PATTERN_NAME_LENGTH) {
					str = str.substring(0, Source.MAX_PATTERN_NAME_LENGTH);
				}
				verticalPatternName = str;
			} else {
				verticalPatternName = "";
			}

			str = attrs.getValue("MPAT_NAME");
			if (null != str) {
				if (str.length() > Source.MAX_PATTERN_NAME_LENGTH) {
					str = str.substring(0, Source.MAX_PATTERN_NAME_LENGTH);
				}
				matrixPatternName = str;
			} else {
				matrixPatternName = "";
			}

			return;
		}

		// Start a DTS_SOURCE element, as usual no nesting.  If this occurs outside a standalone SOURCE element it is
		// ignored.  Otherwise the containing SOURCE element must be a DTS TV record (the parent flag is true).

		if (qName.equals("DTS_SOURCE")) {

			if (!inSource || !didMakeSource || ignoreAll) {
				return;
			}

			if (inDTSSource) {
				errors.reportError("DTS_SOURCE elements may not be nested.");
				throw new SAXException();
			}
			inDTSSource = true;

			if ((null == newSourceTV) || !newSourceTV.isParent) {
				errors.reportError("DTS_SOURCE not allowed in non-parent SOURCE element.");
				throw new SAXException();
			}

			newDTSSource = newSourceTV.addDTSSourceWithAttributes(qName, attrs, errors);
			if (null == newDTSSource) {
				throw new SAXException();
			}

			// Get pattern names from attributes and store until pattern data is parsed.  Note this is not overwriting
			// anything from the enclosing parent SOURCE since a DTS parent never has patterns.

			str = attrs.getValue("APAT_NAME");
			if (null != str) {
				if (str.length() > Source.MAX_PATTERN_NAME_LENGTH) {
					str = str.substring(0, Source.MAX_PATTERN_NAME_LENGTH);
				}
				horizontalPatternName = str;
			} else {
				horizontalPatternName = "";
			}

			str = attrs.getValue("EPAT_NAME");
			if (null != str) {
				if (str.length() > Source.MAX_PATTERN_NAME_LENGTH) {
					str = str.substring(0, Source.MAX_PATTERN_NAME_LENGTH);
				}
				verticalPatternName = str;
			} else {
				verticalPatternName = "";
			}

			str = attrs.getValue("MPAT_NAME");
			if (null != str) {
				if (str.length() > Source.MAX_PATTERN_NAME_LENGTH) {
					str = str.substring(0, Source.MAX_PATTERN_NAME_LENGTH);
				}
				matrixPatternName = str;
			} else {
				matrixPatternName = "";
			}

			return;
		}

		// APAT/EPAT/MPAT elements may occur inside SOURCE or DTS_SOURCE.  These are ignored outside a containing
		// standalone source.  Also if the related HAS_?PAT flag is false ignore the element.

		boolean isHpat = qName.equals("APAT");
		boolean isVpat = qName.equals("EPAT");
		boolean isMpat = qName.equals("MPAT");

		if (isHpat || isVpat || isMpat) {

			if (!inSource || !didMakeSource || ignoreAll) {
				return;
			}

			SourceEditData theSource = newSource;
			if (null != newDTSSource) {
				theSource = newDTSSource;
			}

			if ((isHpat && !theSource.hasHorizontalPattern) || (isVpat && !theSource.hasVerticalPattern) ||
					(isMpat && !theSource.hasMatrixPattern)) {
				return;
			}

			if (inPat) {
				errors.reportError("APAT/EPAT/MPAT elements may not be nested.");
				throw new SAXException();
			}
			inPat = true;

			return;
		}

		// Any other element type is an error.

		errors.reportError("Unknown element '" + qName + "'.");
		throw new SAXException();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add content characters to the buffer for the current element.

	public void characters(char[] ch, int start, int length) {

		if (!buffers.isEmpty()) {
			buffers.peek().write(ch, start, length);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// End of element, pop the element name and content off the stacks.  If the element name does not match it means
	// either elements overlap or nested elements were not explicitly closed.

	public void endElement(String nameSpc, String locName, String qName) throws SAXException {

		String element = elements.pop();
		String content = buffers.pop().toString().trim();

		if (!element.equals(qName)) {
			errors.reportError("Overlapping or un-terminated elements.");
			throw new SAXException();
		}

		// End of the TVSTUDY element.  If the deferred search list is not empty, run the search.  That will occur if
		// scenarios were not being processed so this is either an import or a generic load of all sources found.
		// Otherwise in scenario mode, if any scenarios were parsed add them to the study.  Add all new sources to the
		// study first, then create the new scenarios, apply parameter values, and add to the study.

		if (element.equals("TVSTUDY")) {

			if (inStudy) {

				if ((null != lookupIndex) && !searchIDs.isEmpty()) {
					doSearch(lookupIndex);
					if (!searchIDs.isEmpty() && (null != alternateIndex)) {
						doSearch(alternateIndex);
					}
					if (!searchIDs.isEmpty()) {
						errors.logMessage("Record IDs not found in data sets:");
						for (String theID : searchIDs) {
							errors.logMessage(theID);
						}
					}
					searchIDs.clear();
				}

				if (scenarioMode && !scenarioItems.isEmpty()) {

					for (SourceEditData theSource : studyNewSources) {
						study.addOrReplaceSource(theSource);
					}

					ScenarioEditData newScenario;
					ParameterEditData theParam;
					ArrayList<String> theValues;
					String value;
					int nValues, vIndex;

					for (ScenarioItem theItem : scenarioItems) {

						newScenario = new ScenarioEditData(study, theItem.name, theItem.description,
							theItem.sourceItems);

						if (!theItem.parameters.isEmpty()) {
							for (Integer theKey : theItem.parameters.keySet()) {
								theParam = newScenario.getParameter(theKey.intValue());
								if (null != theParam) {
									theValues = theItem.parameters.get(theKey);
									nValues = theValues.size();
									if (nValues > theParam.parameter.valueCount) {
										nValues = theParam.parameter.valueCount;
									}
									for (vIndex = 0; vIndex < nValues; vIndex++) {
										value = theValues.get(vIndex);
										if (null != value) {
											theParam.value[vIndex] = value;
										}
									}
								}
							}
						}

						study.scenarioData.addOrReplace(newScenario);
						scenarioCount++;
					}
				}

				inStudy = false;
			}

			xmlVersion = 0;
			studyNewSources = null;
			scenarioItems = null;

			return;
		}

		// The end of a SCENARIO element.  Ignore if not in a scenario.  If the deferred search list is not empty, run
		// the search.  Ignore empty scenarios.  For some study types the scenario must have a desired TV, if one is
		// not present ignore the scenario.  See doScenarioAdd().  The scenario information is stored for later, see
		// above, they are created and added all-or-nothing in case of error.

		if (element.equals("SCENARIO")) {

			if (inScenario) {

				if ((null != lookupIndex) && !searchIDs.isEmpty()) {
					doSearch(lookupIndex);
					if (!searchIDs.isEmpty() && (null != alternateIndex)) {
						doSearch(alternateIndex);
					}
					if (!searchIDs.isEmpty()) {
						errors.logMessage("Record IDs not found in data sets:");
						for (String theID : searchIDs) {
							errors.logMessage(theID);
						}
					}
					searchIDs.clear();
					searchItems.clear();
				}

				if (!scenarioSourceItems.isEmpty() && (((Study.STUDY_TYPE_TV_IX != study.study.studyType) &&
						(Study.STUDY_TYPE_TV_OET74 != study.study.studyType) &&
						(Study.STUDY_TYPE_TV6_FM != study.study.studyType)) || hasDesiredTV)) {

					ScenarioItem theItem = new ScenarioItem();

					theItem.name = scenarioName;
					theItem.description = scenarioDescription;
					theItem.sourceItems = scenarioSourceItems;
					theItem.parameters = scenarioParameters;

					scenarioItems.add(theItem);
				}

				inScenario = false;
			}

			// Clear state for the next SCENARIO element.

			scenarioName = null;
			scenarioDescription = null;
			scenarioSourceItems = null;
			scenarioParameters = null;
			hasDesiredTV = false;

			return;
		}

		// Save description if in a scenario, no check for multiple occurrences or nesting, just use the last one.

		if (element.equals("DESCRIPTION")) {

			if (inScenario) {
				scenarioDescription = content;
			}

			return;
		}

		// If the parameter value list is empty, just ignore the parameter.

		if (element.equals("PARAMETER")) {

			if (inParameter) {

				if (!parameterValues.isEmpty()) {
					scenarioParameters.put(Integer.valueOf(parameterKey), parameterValues);
				}

				inParameter = false;
			}

			parameterKey = 0;
			parameterValues = null;

			return;
		}

		// Parameter value is just the VALUE element content, ignore if empty.

		if (element.equals("VALUE")) {

			if (inValue) {

				if (content.length() > 0) {
					parameterValues.set(valueIndex, content);
				}

				inValue = false;
			}

			valueIndex = -1;

			return;
		}

		// If the source was constructed from attributes and nested elements, do additional checks for completeness.
		// For DTS, make sure a valid set of DTS_SOURCE elements was found.

		if (element.equals("SOURCE")) {

			if (inSource) {

				if (didMakeSource) {

					if ((null != newSourceTV) && newSourceTV.isParent) {
						boolean hasRef = false, hasSite = false, err = false;
						for (SourceEditDataTV dtsSource : newSourceTV.getDTSSources()) {
							if (0 == dtsSource.siteNumber) {
								if (hasRef) {
									err = true;
									break;
								}
								hasRef = true;
							} else {
								hasSite = true;
							}
						}
						if (!hasRef || !hasSite) {
							err = true;
						}
						if (err) {
							errors.reportError(
								"Incomplete or invalid set of DTS_SOURCE elements within SOURCE element.");
							throw new SAXException();
						}
					}

					// Make sure pattern data elements were found if the attributes indicated they should be present.

					if (newSource.hasHorizontalPattern && (null == newSource.horizontalPattern)) {
						errors.reportError("Missing APAT element in SOURCE element.");
						throw new SAXException();
					}

					if (newSource.hasVerticalPattern && (null == newSource.verticalPattern)) {
						errors.reportError("Missing EPAT element in SOURCE element.");
						throw new SAXException();
					}

					if (newSource.hasMatrixPattern && (null == newSource.matrixPattern)) {
						errors.reportError("Missing MPAT element in SOURCE element.");
						throw new SAXException();
					}

					// Source attributes may occur in the element content, apply those.

					newSource.setAllAttributesNT(content);

					// Add the source to the results, in scenario mode it goes in the new scenario sources, in user
					// record mode it goes in the single source public property and all else is ignored, else it goes
					// in the sources list property.  There is no concern about duplicates here because sources seen
					// in this context are always unique by definition.  Below, outside of this context, the source is
					// definitely not unique, it is a by-reference source already retrieved and added while parsing a
					// previous scenario, so it does not need to be added again.

					if (scenarioMode) {

						if (inScenario) {
							studyNewSources.add(newSource);
						}

					} else {

						if (userRecordMode) {

							source = newSource;
							ignoreAll = true;

						} else {

							sources.add(newSource);
						}
					}
				}

				// Do scenario-related processing e.g. replication, see doScenarioAdd().

				if (inScenario) {
					doScenarioAdd(newSource, isDesired, isUndesired, newSourceTV, replicateToChannel);
				}

				inSource = false;
			}

			// Clear state for the next SOURCE element.

			isDesired = false;
			isUndesired = false;
			newSource = null;
			newSourceTV = null;
			newSourceWL = null;
			newSourceFM = null;
			didMakeSource = false;
			replicateToChannel = 0;

			horizontalPatternName = null;
			verticalPatternName = null;
			matrixPatternName = null;

			return;
		}

		// For DTS_SOURCE, check that patterns were found, apply content, then clear state and continue.

		if (element.equals("DTS_SOURCE")) {

			if (inDTSSource) {

				if (didMakeSource) {

					if (newDTSSource.hasHorizontalPattern && (null == newDTSSource.horizontalPattern)) {
						errors.reportError("Missing APAT element in DTS_SOURCE element.");
						throw new SAXException();
					}

					if (newDTSSource.hasVerticalPattern && (null == newDTSSource.verticalPattern)) {
						errors.reportError("Missing EPAT element in DTS_SOURCE element.");
						throw new SAXException();
					}

					if (newDTSSource.hasMatrixPattern && (null == newDTSSource.matrixPattern)) {
						errors.reportError("Missing MPAT element in DTS_SOURCE element.");
						throw new SAXException();
					}

					newDTSSource.setAllAttributesNT(content);
				}

				inDTSSource = false;
			}

			newDTSSource = null;

			return;
		}

		// For APAT/EPAT/MPAT, parse the content data, add to the source or DTS source as needed.  Checks done in
		// startElement() ensure a valid state; if there is a DTS transmitter source being parsed the pattern data
		// goes there, else it goes to the regular (non-DTS) source.

		boolean isHpat = element.equals("APAT");
		boolean isVpat = element.equals("EPAT");
		boolean isMpat = element.equals("MPAT");

		if (isHpat || isVpat || isMpat) {

			if (inPat) {

				boolean bad = false;

				SourceEditData theSource = newSource;
				if (null != newDTSSource) {
					theSource = newDTSSource;
				}

				String[] tokens = patternParser.split(content);

				if (isHpat || isVpat) {

					ArrayList<AntPattern.AntPoint> thePoints = new ArrayList<AntPattern.AntPoint>();

					double azdep, rf, minazdep, maxazdep, lazdep;
					if (isHpat) {
						minazdep = AntPattern.AZIMUTH_MIN;
						maxazdep = AntPattern.AZIMUTH_MAX;
						lazdep = AntPattern.AZIMUTH_MIN - 1.;
					} else {
						minazdep = AntPattern.DEPRESSION_MIN;
						maxazdep = AntPattern.DEPRESSION_MAX;
						lazdep = AntPattern.DEPRESSION_MIN - 1.;
					}

					for (int i = 1; i < tokens.length; i += 2) {
						try {
							azdep = Double.parseDouble(tokens[i - 1]);
							rf = Double.parseDouble(tokens[i]);
						} catch (NumberFormatException nfe) {
							bad = true;
							break;
						}
						if ((azdep < minazdep) || (azdep > maxazdep)) {
							bad = true;
							break;
						}
						if (azdep <= lazdep) {
							bad = true;
							break;
						}
						lazdep = azdep;
						if ((rf < AntPattern.FIELD_MIN) || (rf > AntPattern.FIELD_MAX)) {
							bad = true;
							break;
						}
						thePoints.add(new AntPattern.AntPoint(azdep, rf));
					}

					if (!bad && (thePoints.size() < AntPattern.PATTERN_REQUIRED_POINTS)) {
						bad = true;
					}

					if (!bad) {
						if (isHpat) {
							theSource.hasHorizontalPattern = true;
							theSource.horizontalPattern = new AntPattern(dbID, AntPattern.PATTERN_TYPE_HORIZONTAL,
								horizontalPatternName, thePoints);
							theSource.horizontalPatternChanged = true;
						} else {
							theSource.hasVerticalPattern = true;
							theSource.verticalPattern = new AntPattern(dbID, AntPattern.PATTERN_TYPE_VERTICAL,
								verticalPatternName, thePoints);
							theSource.verticalPatternChanged = true;
						}
					}

				} else {

					ArrayList<AntPattern.AntSlice> theSlices = new ArrayList<AntPattern.AntSlice>();
					ArrayList<AntPattern.AntPoint> thePoints = null;
					double az, dep, rf, laz = AntPattern.AZIMUTH_MIN - 1., ldep = AntPattern.DEPRESSION_MIN - 1.;

					for (int i = 2; i < tokens.length; i += 3) {
						try {
							az = Double.parseDouble(tokens[i - 2]);
							dep = Double.parseDouble(tokens[i - 1]);
							rf = Double.parseDouble(tokens[i]);
						} catch (NumberFormatException nfe) {
							bad = true;
							break;
						}
						if ((az < AntPattern.AZIMUTH_MIN) || (az > AntPattern.AZIMUTH_MAX)) {
							bad = true;
							break;
						}
						if ((dep < AntPattern.DEPRESSION_MIN) || (dep > AntPattern.DEPRESSION_MAX)) {
							bad = true;
							break;
						}
						if (az != laz) {
							if (az <= laz) {
								bad = true;
								break;
							}
							laz = az;
							if ((null != thePoints) && (thePoints.size() < AntPattern.PATTERN_REQUIRED_POINTS)) {
								bad = true;
								break;
							}
							thePoints = new ArrayList<AntPattern.AntPoint>();
							theSlices.add(new AntPattern.AntSlice(az, thePoints));
							ldep = AntPattern.DEPRESSION_MIN - 1.;
						}
						if (dep <= ldep) {
							bad = true;
							break;
						}
						ldep = dep;
						if ((rf < AntPattern.FIELD_MIN) || (rf > AntPattern.FIELD_MAX)) {
							bad = true;
							break;
						}
						thePoints.add(new AntPattern.AntPoint(dep, rf));
					}

					if (!bad && (null != thePoints) && (thePoints.size() < AntPattern.PATTERN_REQUIRED_POINTS)) {
						bad = true;
					}

					if (!bad && (theSlices.size() < AntPattern.PATTERN_REQUIRED_POINTS)) {
						bad = true;
					}

					if (!bad) {
						theSource.hasMatrixPattern = true;
						theSource.matrixPattern = new AntPattern(dbID, matrixPatternName,
							AntPattern.PATTERN_TYPE_VERTICAL, theSlices);
						theSource.matrixPatternChanged = true;
					}
				}

				if (bad) {
					errors.reportError("Bad data in APAT/EPAT/MPAT element.");
					throw new SAXException();
				}

				inPat = false;
			}

			return;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Do a search for all by-reference records not previously loaded, called at the end of either scenario or study
	// context as needed.  May be multiple calls for the primary and alternate lookup sets.

	private void doSearch(LookupIndex theIndex) throws SAXException {

		switch (theIndex.lookupExtDb.recordType) {

			case Source.RECORD_TYPE_TV: {

				HashMap<String, ExtDbRecordTV> theRecords = ExtDbRecordTV.batchFindRecordTV(theIndex.lookupExtDb,
					searchIDs, errors);
				if (null == theRecords) {
					throw new SAXException();
				}

				SourceEditData theSource;
				SourceEditDataTV theSourceTV;

				// Adding to a scenario, run through the search list and retrieve individual records.

				if (inScenario) {

					ExtDbRecordTV theRecord;

					for (SearchItem theItem : searchItems) {

						theRecord = theRecords.get(theItem.extRecordID);
						if (null == theRecord) {
							continue;
						}

						searchIDs.remove(theItem.extRecordID);

						// If a REPLICATE attribute was not found, check if this record requests automatic replication,
						// see ExtDbRecordTV for details.  Then check the ultimate channel for range, the original
						// channel may be out of range if the replication channel is not.  If out of range just ignore
						// the element, that is not an error, the source is valid it just can't be used in this study.

						if (0 == theItem.replicateToChannel) {
							theItem.replicateToChannel = theRecord.replicateToChannel;
						}
						if (0 == theItem.replicateToChannel) {
							if ((theRecord.channel < study.getMinimumChannel()) ||
									(theRecord.channel > study.getMaximumChannel())) {
								if (null != errors) {
									errors.logMessage(ExtDbRecord.makeMessage(theRecord,
										"Ignored, channel is out of range for study."));
								}
								continue;
							}
						} else {
							if ((theItem.replicateToChannel < study.getMinimumChannel()) ||
									(theItem.replicateToChannel > study.getMaximumChannel())) {
								if (null != errors) {
									errors.logMessage(ExtDbRecord.makeMessage(theRecord,
										"Ignored, replication channel " + String.valueOf(theItem.replicateToChannel) +
										" is out of range for study."));
								}
								continue;
							}
						}

						// Check to see if the source is already converted.  This is not entirely paranoia; it is
						// possible for the same record to be used more than once within the same scenario.

						theSource = theIndex.sharedSources.get(theItem.extRecordID);
						if (null == theSource) {
							theSourceTV = SourceEditDataTV.makeSourceTV(theRecord, study, true, errors);
							if (null == theSourceTV) {
								throw new SAXException();
							}
							theSource = theSourceTV;
							theIndex.sharedSources.put(theItem.extRecordID, theSource);
							studyNewSources.add(theSource);
						} else {
							theSourceTV = (SourceEditDataTV)theSource;
						}

						doScenarioAdd(theSource, theItem.isDesired, theItem.isUndesired, theSourceTV,
							theItem.replicateToChannel);
					}

					break;
				}

				// For a data set import or a generic read-all run, just put all records found in the results.  Ignore
				// records that need replication.  Note this assumes there can be no duplicates, in this case there
				// will only be one search so there is no existing state to merge with (although two data sets may be
				// searched, the IDs set is used to be sure there cannot be any duplication between those searchs).
				// In import mode a different method is used in the SourceEditData subclass, see there for details.

				if (!scenarioMode) {

					for (ExtDbRecordTV theRecord : theRecords.values()) {

						searchIDs.remove(theRecord.extRecordID);

						if (theRecord.replicateToChannel > 0) {
							continue;
						}

						if (importMode) {
							theSourceTV = SourceEditDataTV.makeExtSourceTV(theRecord, importExtDb, errors);
						} else {
							theSourceTV = SourceEditDataTV.makeSourceTV(theRecord, null, true, errors);
						}
						if (null == theSourceTV) {
							throw new SAXException();
						}

						sources.add(theSourceTV);
					}
				}

				break;
			}

			case Source.RECORD_TYPE_FM: {

				HashMap<String, ExtDbRecordFM> theRecords = ExtDbRecordFM.batchFindRecordFM(theIndex.lookupExtDb,
					searchIDs, errors);
				if (null == theRecords) {
					throw new SAXException();
				}

				SourceEditData theSource;
				SourceEditDataFM theSourceFM;

				if (inScenario) {

					ExtDbRecordFM theRecord;

					for (SearchItem theItem : searchItems) {

						theRecord = theRecords.get(theItem.extRecordID);
						if (null == theRecord) {
							continue;
						}

						searchIDs.remove(theItem.extRecordID);

						theSource = theIndex.sharedSources.get(theItem.extRecordID);
						if (null == theSource) {
							theSourceFM = SourceEditDataFM.makeSourceFM(theRecord, study, true, errors);
							if (null == theSourceFM) {
								throw new SAXException();
							}
							theSource = theSourceFM;
							theIndex.sharedSources.put(theRecord.extRecordID, theSource);
							studyNewSources.add(theSource);
						}

						doScenarioAdd(theSource, theItem.isDesired, theItem.isUndesired, null, 0);
					}

					break;
				}

				if (!scenarioMode) {

					for (ExtDbRecordFM theRecord : theRecords.values()) {

						searchIDs.remove(theRecord.extRecordID);

						if (importMode) {
							theSourceFM = SourceEditDataFM.makeExtSourceFM(theRecord, importExtDb, errors);
						} else {
							theSourceFM = SourceEditDataFM.makeSourceFM(theRecord, null, true, errors);
						}
						if (null == theSourceFM) {
							throw new SAXException();
						}

						sources.add(theSourceFM);
					}
				}

				break;
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a source to the scenario, first do replication if needed.  If the replication channel matches the original
	// and the original is digital, the replication would do nothing so don't bother.  Only analog sources can be
	// replicated on-channel, which converts them to digital.  For a locked source from external data, check shared
	// sources in case the replication source already exists, if not do the replication and add it.

	private void doScenarioAdd(SourceEditData theSource, boolean isDesired, boolean isUndesired,
			SourceEditDataTV theSourceTV, int replicateToChannel) throws SAXException {

		if ((null != theSourceTV) && (replicateToChannel > 0) &&
				((replicateToChannel != theSourceTV.channel) || !theSourceTV.service.serviceType.digital)) {

			SourceEditDataTV origSource = theSourceTV;
			theSourceTV = null;

			LookupIndex theIndex = null;
			if ((null != origSource.extDbKey) && (null != origSource.extRecordID) && (null != lookupIndex)) {
				if (origSource.extDbKey.equals(lookupIndex.lookupExtDb)) {
					theIndex = lookupIndex;
				} else {
					if ((null != alternateIndex) && origSource.extDbKey.equals(alternateIndex.lookupExtDb)) {
						theIndex = alternateIndex;
					}
				}
			}

			if ((null != theIndex) && origSource.isLocked) {
				theSourceTV = theIndex.sharedReplicationSources.get(replicateToChannel - SourceTV.CHANNEL_MIN).
					get(origSource.extRecordID);
			}

			if (null == theSourceTV) {

				theSourceTV = origSource.replicate(replicateToChannel, errors);
				if (null == theSourceTV) {
					throw new SAXException();
				}

				if ((null != theIndex) && origSource.isLocked) {
					theIndex.sharedReplicationSources.get(replicateToChannel - SourceTV.CHANNEL_MIN).
						put(origSource.extRecordID, theSourceTV);
				}

				studyNewSources.add(theSourceTV);
			}

			theSource = theSourceTV;
		}

		// Check for duplication, this is a paranoia check that just guards against the same source key appearing more
		// than once in the scenario because that would cause a key-uniqueness exception in SQL.  Such duplicates are
		// silently ignored.  Other rules such as checking for MX records are not implemented here.  The scenario is
		// assumed valid because it must have been valid when exported, so those tests are unnecessary.  Manual editing
		// of the XML file may of course make that assumption wrong, hence the paranoia check for duplicates.  But if a
		// user manually edits the XML making the scenario invalid in other ways that corrupt study results but do not
		// cause fatal errors, tough luck, they dug their own hole.

		for (Scenario.SourceListItem theItem : scenarioSourceItems) {
			if (theSource.key.intValue() == theItem.key) {
				return;
			}
		}

		// Add the source key to the list for the scenario.  For TV interference-check and wireless to TV interference
		// studies, a scenario can only have one desired TV source (it also must have one, that is checked at the end
		// of the scenario element, see above).  In those cases the first desired found is added as a permanent entry
		// and the undesired flag is cleared.  After that all TV sources have desired cleared and permanent false.
		// All non-TV sources also have desired cleared and are not permanent.  One further rule, for a TV channel 6
		// and FM study only a TV channel 6 record can be desired, all other TVs must be undesired-only.  The channel
		// 6 must also be undesired in that case as it may cause interference to the FM records in the scenario, which
		// may also be desireds in that study type.

		boolean isPermanent = false;
		if ((Study.STUDY_TYPE_TV_IX == study.study.studyType) ||
				(Study.STUDY_TYPE_TV_OET74 == study.study.studyType) ||
				(Study.STUDY_TYPE_TV6_FM == study.study.studyType)) {
			if (null != theSourceTV) {
				if (hasDesiredTV) {
					isDesired = false;
				} else {
					if (isDesired) {
						if (Study.STUDY_TYPE_TV6_FM == study.study.studyType) {
							if (6 == theSourceTV.channel) {
								hasDesiredTV = true;
								isUndesired = true;
								isPermanent = true;
							} else {
								isDesired = false;
							}
						} else {
							hasDesiredTV = true;
							isUndesired = false;
							isPermanent = true;
						}
					}
				}
			} else {
				if (Study.STUDY_TYPE_TV6_FM != study.study.studyType) {
					isDesired = false;
				}
			}
		}

		scenarioSourceItems.add(new Scenario.SourceListItem(theSource.key.intValue(), isDesired, isUndesired,
			isPermanent));
	}
}
