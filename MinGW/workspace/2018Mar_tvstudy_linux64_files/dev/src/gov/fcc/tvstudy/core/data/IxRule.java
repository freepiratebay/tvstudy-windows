//
//  IxRule.java
//  TVStudy
//
//  Copyright (c) 2012-2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.data;

import gov.fcc.tvstudy.core.*;

import java.util.*;
import java.sql.*;


//=====================================================================================================================
// Representation class for an interference rule table record.

public class IxRule extends KeyedRecord {

	// Constants used in the rule editor UI.

	public static final int FREQUENCY_OFFSET_WITHOUT = 1;
	public static final int FREQUENCY_OFFSET_WITH = 2;

	public static final double DISTANCE_MIN = 1.;
	public static final double DISTANCE_MAX = 500.;

	public static final double DU_MIN = -60.;
	public static final double DU_MAX = 60.;

	public static final double PERCENT_TIME_MIN = 0.01;
	public static final double PERCENT_TIME_MAX = 99.99;

	// Database properties:

	// key (super)           Rule key, unique only within a study, always > 0.
	// name (super)          Composed from other properties.
	// country               Country for the desired source.
	// serviceType           Service type for the desired source.
	// signalType            TV digital signal type for the desired source, null object for analog or non-TV.
	// undesiredServiceType  Service type for the undesired source.
	// undesiredSignalType   TV digital signal type for the undesired source, null object for analog or non-TV.
	// channelDelta          Channel relationship.
	// channelBand           Channel band restriction, the null object means the rule applies to all bands.
	// frequencyOffset       Indicates type of offset required to match per constants above, or 0 for N/A.
	// emissionMask          Undesired emission mask, null object if serviceUndesired.needsEmissionMask is false.
	// distance              Limiting (culling) distance in kilometers.
	// requiredDU            Minimum D/U in dB for no interference.
	// undesiredTime         Percent-time value for undesired signal predictions.
	// isActive              If true the rule is applied, if false it is ignored by the study engine.

	public final Country country;
	public final ServiceType serviceType;
	public final SignalType signalType;
	public final ServiceType undesiredServiceType;
	public final SignalType undesiredSignalType;
	public final ChannelDelta channelDelta;
	public final ChannelBand channelBand;
	public final int frequencyOffset;
	public final EmissionMask emissionMask;
	public final double distance;
	public final double requiredDU;
	public final double undesiredTime;
	public final boolean isActive;


	//-----------------------------------------------------------------------------------------------------------------

	public IxRule(int theKey, Country theCountry, ServiceType theServiceType, SignalType theSignalType,
			ServiceType theUndesiredServiceType, SignalType theUndesiredSignalType, ChannelDelta theChannelDelta,
			ChannelBand theChannelBand, int theFrequencyOffset, EmissionMask theEmissionMask, double theDistance,
			double theRequiredDU, double theUndesiredTime, boolean theIsActive) {

		super(theKey, theCountry.name + " " + theServiceType.name + " from " + theUndesiredServiceType.name + " " +
			theChannelDelta.name);

		country = theCountry;
		serviceType = theServiceType;
		signalType = theSignalType;
		undesiredServiceType = theUndesiredServiceType;
		undesiredSignalType = theUndesiredSignalType;
		channelDelta = theChannelDelta;
		channelBand = theChannelBand;
		frequencyOffset = theFrequencyOffset;
		emissionMask = theEmissionMask;
		distance = theDistance;
		requiredDU = theRequiredDU;
		undesiredTime = theUndesiredTime;
		isActive = theIsActive;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Retrieve the interference rules from a database.  Caller must provide an open database connection and handle
	// exceptions.  To support the template editor, this takes both a database name, and a root name and template key.
	// If the database name is set the others are ignored and rules are loaded from the named database.  Otherwise,
	// rules are loaded from the indicated template in the root database.  A filter argument may be given to restrict
	// the rule query based on record type of the desired service type.  When loading a database, the filter value is
	// a study type and only record types for that study type are loaded.  When loading a template, the filter argument
	// is a record type and only that type is loaded.  In either case if the filter is 0 all types are loaded.  Usually
	// when loading a named database e.g. a study the rules are filtered but when loading a template they are not,
	// however this may also be used to load a type-specific set of rules from a template to apply in an editor.

	public static ArrayList<IxRule> getIxRules(DbConnection db, String theDbName, String rootName, int templateKey,
			int filter) throws SQLException {

		ArrayList<IxRule> result = new ArrayList<IxRule>();

		StringBuilder typeList = new StringBuilder("(");
		if ((0 == filter) || (null != theDbName)) {
			String sep = "";
			for (KeyedRecord theType : Source.getRecordTypes(filter)) {
				typeList.append(sep);
				typeList.append(String.valueOf(theType.key));
				sep = ",";
			}
		} else {
			typeList.append(String.valueOf(filter));
		}
		typeList.append(")");

		if (null != theDbName) {

			db.setDatabase(theDbName);

			db.query(
			"SELECT " +
				"ix_rule.ix_rule_key, " +
				"ix_rule.country_key, " +
				"ix_rule.service_type_key, " +
				"ix_rule.signal_type_key, " +
				"ix_rule.undesired_service_type_key, " +
				"ix_rule.undesired_signal_type_key, " +
				"ix_rule.channel_delta_key, " +
				"ix_rule.channel_band_key, " +
				"ix_rule.frequency_offset, " +
				"ix_rule.emission_mask_key, " +
				"ix_rule.distance, " +
				"ix_rule.required_du, " +
				"ix_rule.undesired_time, " +
				"ix_rule.is_active " +
			"FROM " +
				"ix_rule " +
				"JOIN " + rootName + ".service_type USING (service_type_key) " +
			"WHERE " +
				"service_type.record_type IN " + typeList + " " +
			"ORDER BY 2, 3, 4, 5, 6, 7, 10");

		} else {

			db.setDatabase(rootName);

			db.query(
			"SELECT " +
				"ix_rule_key, " +
				"country_key, " +
				"service_type_key, " +
				"signal_type_key, " +
				"undesired_service_type_key, " +
				"undesired_signal_type_key, " +
				"channel_delta_key, " +
				"channel_band_key, " +
				"frequency_offset, " +
				"emission_mask_key, " +
				"distance, " +
				"required_du, " +
				"undesired_time, " +
				"true AS is_active " +
			"FROM " +
				"template_ix_rule " +
				"JOIN service_type USING (service_type_key) " +
			"WHERE " +
				"template_key = " + templateKey + " " +
				"AND service_type.record_type IN " + typeList + " " +
			"ORDER BY 2, 3, 4, 5, 6, 7, 10");
		}

		Country theCountry;
		ServiceType theDesiredServiceType, theUndesiredServiceType;
		ChannelDelta theChannelDelta;

		while (db.next()) {

			theCountry = Country.getCountry(db.getInt(2));
			if (null == theCountry) {
				continue;
			}

			theDesiredServiceType = ServiceType.getServiceType(db.getInt(3));
			if (null == theDesiredServiceType) {
				continue;
			}

			theUndesiredServiceType = ServiceType.getServiceType(db.getInt(5));
			if (null == theUndesiredServiceType) {
				continue;
			}

			theChannelDelta = ChannelDelta.getChannelDelta(db.getInt(7));
			if (null == theChannelDelta) {
				continue;
			}

			result.add(new IxRule(
				db.getInt(1),
				theCountry,
				theDesiredServiceType,
				SignalType.getSignalType(db.getInt(4)),
				theUndesiredServiceType,
				SignalType.getSignalType(db.getInt(6)),
				theChannelDelta,
				ChannelBand.getChannelBand(db.getInt(8)),
				db.getInt(9),
				EmissionMask.getEmissionMask(db.getInt(10)),
				db.getDouble(11),
				db.getDouble(12),
				db.getDouble(13),
				db.getBoolean(14)));
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Turn a list of rule objects into a formatted text summary.  This is stored in the study table record so the
	// study engine can include it in text reports.  This saves the study engine having to load all the secondary
	// tables with the descriptive texts for the various key values.

	public static String makeIxRuleSummary(ArrayList<IxRule> rules) {

		boolean hasTV = false;
		for (IxRule theRule : rules) {
			if (Source.RECORD_TYPE_TV == theRule.serviceType.recordType) {
				hasTV = true;
				break;
			}
		}
		boolean showType = false;
		if (hasTV) {
			showType = SignalType.hasMultipleOptions();
		}

		StringBuilder sum = new StringBuilder();
		if (hasTV) {
			if (showType) {
				sum.append("Ctry D  Service D                Mod D   Bnd D  " +
					"Service U                Mod U   Channel U   Mask U        Offset  Dist km   D/U dB     % U\n\n");
			} else {
				sum.append("Ctry D  Service D                Bnd D  " +
					"Service U                Channel U   Mask U        Offset  Dist km   D/U dB     % U\n\n");
			}
		} else {
			sum.append("Ctry D  Service D                " +
				"Service U                Channel U  Dist km   D/U dB     % U\n\n");
		}

		String dtyp, bnd, utyp, msk, ofs;

		for (IxRule theRule : rules) {

			if (!theRule.isActive) {
				continue;
			}

			if (Source.RECORD_TYPE_TV == theRule.serviceType.recordType) {
				dtyp = theRule.signalType.name;
				bnd = theRule.channelBand.name;
				utyp = theRule.undesiredSignalType.name;
				msk = theRule.undesiredSignalType.name;
				switch (theRule.frequencyOffset) {
					case FREQUENCY_OFFSET_WITH:
						ofs = "With";
						break;
					case FREQUENCY_OFFSET_WITHOUT:
						ofs = "Without";
						break;
					default:
						ofs = "(any)";
						break;
				}
			} else {
				dtyp = "";
				bnd = "";
				utyp = "";
				msk = "";
				ofs = "";
			}

			if (hasTV) {
				if (showType) {
					sum.append(String.format(Locale.US,
						"%-6s  %-23s  %-6s  %-5s  %-23s  %-6s  %-10s  %-12s  %-7s  %6.1f  %7.2f  %6.2f\n",
						theRule.country.name, theRule.serviceType.name, dtyp, bnd, theRule.undesiredServiceType.name,
						utyp, theRule.channelDelta.name, msk, ofs, theRule.distance, theRule.requiredDU,
						theRule.undesiredTime));
				} else {
					sum.append(String.format(Locale.US,
						"%-6s  %-23s  %-5s  %-23s  %-10s  %-12s  %-7s  %6.1f  %7.2f  %6.2f\n",
						theRule.country.name, theRule.serviceType.name, bnd, theRule.undesiredServiceType.name,
						theRule.channelDelta.name, msk, ofs, theRule.distance, theRule.requiredDU,
						theRule.undesiredTime));
				}
			} else {
				sum.append(String.format(Locale.US,
					"%-6s  %-23s  %-23s  %-10s  %6.1f  %7.2f  %6.2f\n",
					theRule.country.name, theRule.serviceType.name, theRule.undesiredServiceType.name,
					theRule.channelDelta.name, theRule.distance, theRule.requiredDU, theRule.undesiredTime));
			}
		}

		return sum.toString();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create table in a new database, optionally copy initial rules from a template in the named root database.  If
	// templateKey and rootName are provided a study type may also be provided so rules can be filtered appropriately
	// by record type, if that is 0 all types are copied.  Also if the specified template does not contain any rules
	// for a record type that may exist according to the study type, rules for that record type are copied from the
	// default template.  However some types may not even have rules in the default template.

	public static void createTables(DbConnection db, String theDbName, String rootName, int templateKey, int studyType)
			throws SQLException {

		db.setDatabase(theDbName);

		db.update(
		"CREATE TABLE ix_rule (" +
			"ix_rule_key INT NOT NULL PRIMARY KEY," +
			"country_key INT NOT NULL," +
			"service_type_key INT NOT NULL," +
			"signal_type_key INT NOT NULL," +
			"undesired_service_type_key INT NOT NULL," +
			"undesired_signal_type_key INT NOT NULL," +
			"channel_delta_key INT NOT NULL," +
			"channel_band_key INT NOT NULL," +
			"frequency_offset INT NOT NULL," +
			"emission_mask_key INT NOT NULL," +
			"distance FLOAT NOT NULL," +
			"required_du FLOAT NOT NULL," +
			"undesired_time FLOAT NOT NULL," +
			"is_active BOOLEAN NOT NULL" +
		")");

		if ((null != rootName) && (templateKey > 0)) {

			int theKey, rowCount;

			for (KeyedRecord theType : Source.getRecordTypes(studyType)) {

				theKey = templateKey;

				while (true) {

					rowCount = db.update(
					"INSERT INTO ix_rule (" +
						"ix_rule_key," +
						"country_key," +
						"service_type_key," +
						"signal_type_key," +
						"undesired_service_type_key," +
						"undesired_signal_type_key," +
						"channel_delta_key," +
						"channel_band_key," +
						"frequency_offset," +
						"emission_mask_key," +
						"distance," +
						"required_du," +
						"undesired_time," +
						"is_active) " +
					"SELECT " +
						"ix_rule_key," +
						"country_key," +
						"service_type_key," +
						"signal_type_key," +
						"undesired_service_type_key," +
						"undesired_signal_type_key," +
						"channel_delta_key," +
						"channel_band_key," +
						"frequency_offset," +
						"emission_mask_key," +
						"distance," +
						"required_du," +
						"undesired_time," +
						"true AS is_active " +
					"FROM " +
						rootName + ".template_ix_rule " +
						"JOIN " + rootName + ".service_type USING (service_type_key) " +
					"WHERE " +
						"template_key = " + theKey + " " +
						"AND service_type.record_type = " + theType.key);

					if ((rowCount > 0) || (1 == theKey)) {
						break;
					}
					theKey = 1;
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create all tables and copy contents from another database.

	public static void copyTables(DbConnection db, String theDbName, String fromDbName) throws SQLException {

		createTables(db, theDbName, null, 0, 0);

		db.update(
		"INSERT INTO ix_rule (" +
			"ix_rule_key," +
			"country_key," +
			"service_type_key," +
			"signal_type_key," +
			"undesired_service_type_key," +
			"undesired_signal_type_key," +
			"channel_delta_key," +
			"channel_band_key," +
			"frequency_offset," +
			"emission_mask_key," +
			"distance," +
			"required_du," +
			"undesired_time," +
			"is_active) " +
		"SELECT " +
			"ix_rule_key," +
			"country_key," +
			"service_type_key," +
			"signal_type_key," +
			"undesired_service_type_key," +
			"undesired_signal_type_key," +
			"channel_delta_key," +
			"channel_band_key," +
			"frequency_offset," +
			"emission_mask_key," +
			"distance," +
			"required_du," +
			"undesired_time," +
			"is_active " +
		"FROM " +
			fromDbName + ".ix_rule");
	}
}
