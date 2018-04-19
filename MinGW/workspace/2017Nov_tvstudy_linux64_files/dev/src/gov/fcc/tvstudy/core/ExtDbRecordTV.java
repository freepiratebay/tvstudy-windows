//
//  ExtDbRecordTV.java
//  TVStudy
//
//  Copyright (c) 2015-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core;

import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.editdata.*;
import gov.fcc.tvstudy.core.geo.*;

import java.util.*;
import java.text.*;
import java.sql.*;


//=====================================================================================================================
// Concrete subclass of ExtDbRecord for TV records from external station data sets.  See the superclass for details.

public class ExtDbRecordTV extends ExtDbRecord implements Record {

	// CDBS and LMS mappings for status codes.

	private static final String[] CDBS_STATUS_CODES = {
		"APP", "CP", "CP MOD", "LIC"
	};
	private static final int[] CDBS_STATUS_TYPES = {
		STATUS_TYPE_APP, STATUS_TYPE_CP, STATUS_TYPE_CP, STATUS_TYPE_LIC
	};
	private static final String[] LMS_APP_STATUS_CODES = {
		"SAV", "SUB", "PEN", "DIS", "WIT", "DEN", "RET", "CAN", "REC"
	};

	// Originally the STATUS_TYPE_* values (see superclass) directly determined preference order for status codes in
	// isPreferredRecord().  But those values are also stored in database records, which made it difficult to insert
	// new codes into the sequence because existing codes in the database had to be re-written.  Hence the ordering is
	// now indirect, STATUS_TYPE_* are index values into this array to obtain a preference ordering value.

	public static final int[] STATUS_TYPE_RANK = {
	// STA  CP LIC APP OTH EXP AMD
		30, 10, 20, 40, 70, 60, 50
	};

	// Co-channel MX distance for DRTs, see areRecordsMX().

	public static final double DRT_MX_DISTANCE = 30.;

	// Information for baseline records.

	public static final String BASELINE_STATUS = "BL";
	public static final String BASELINE_ID_PREFIX = "DTVBL";

	public int siteNumber;
	public int facilityID;
	public boolean isDRT;
	public String callSign;
	public int channel;
	public String zoneCode;
	public String status;
	public int statusType;
	public String filePrefix;
	public String appARN;
	public String frequencyOffsetCode;
	public String emissionMaskCode;
	public double dtsMaximumDistance;
	public double alternateERP;
	public boolean daIndicated;
	public boolean isSharingHost;
	public String licensee;

	// List of transmitter records for a DTS parent.

	public LinkedList<ExtDbRecordTV> dtsRecords;

	// See findBaselineRecords().

	public boolean isBaseline;
	public int replicateToChannel;

	// Flag used for the search radius check on a DTS, see findRecords().

	private boolean inSearchRadius;

	// See isOperating().

	private boolean isOperatingFacility;
	private boolean operatingStatusSet;


	//-----------------------------------------------------------------------------------------------------------------
	// Search a CDBS or LMS database for TV records.  For CDBS the query does an inner join on tables tv_eng_data,
	// application, and facility; for LMS it is tables app_location, app_antenna, app_antenna_frequency, application,
	// license_filing_version, and application_facility (however app_antenna and app_antenna_frequency are left joins
	// so fields may be null).  Records that have an unknown service or country, bad facility IDs, or bad channel are
	// ignored.  This has different forms to deal with DTS.  A call with dtsParent null will only find DTS records with
	// the CDBS site number 0 or LMS reference location flag true, those are the "parent" placeholder sources in a DTS
	// record set.  During that search recursive calls are made with a non-null dtsParent to get the DTS transmitter
	// records matching a particular parent.  If the database type does not support TV records return an empty list.

	public static LinkedList<ExtDbRecord> findRecordsImpl(ExtDb extDb, String query, GeoPoint searchCenter,
			double searchRadius, double kmPerDegree, ErrorLogger errors) {
		LinkedList<ExtDbRecordTV> records = findRecordsTV(extDb, query, searchCenter, searchRadius, kmPerDegree, null,
			errors);
		if (null != records) {
			return new LinkedList<ExtDbRecord>(records);
		}
		return null;
	}

	public static LinkedList<ExtDbRecordTV> findRecordsTV(ExtDb extDb, String query) {
		return findRecordsTV(extDb, query, null, 0., 0., null, null);
	}

	public static LinkedList<ExtDbRecordTV> findRecordsTV(ExtDb extDb, String query, ErrorLogger errors) {
		return findRecordsTV(extDb, query, null, 0., 0., null, errors);
	}

	private static LinkedList<ExtDbRecordTV> findRecordsTV(ExtDb extDb, String query, GeoPoint searchCenter,
			double searchRadius, double kmPerDegree, ExtDbRecordTV dtsParent, ErrorLogger errors) {

		boolean isCDBS = (ExtDb.DB_TYPE_CDBS == extDb.type);
		if (!isCDBS && (ExtDb.DB_TYPE_LMS != extDb.type) && (ExtDb.DB_TYPE_LMS_LIVE != extDb.type)) {
			return new LinkedList<ExtDbRecordTV>();
		}

		// Compose the query, first the field and table lists that are the same regardless of import version or DTS
		// parent/dependent query.

		String fldStr = "", frmStr = "", whrStr = "", ordStr = "";

		if (isCDBS) {

			fldStr =
			"SELECT " +
				"(CASE WHEN (facility.fac_callsign <> '') " +
					"THEN facility.fac_callsign ELSE application.fac_callsign END), " +
				"(CASE WHEN (facility.comm_city <> '') " +
					"THEN facility.comm_city ELSE application.comm_city END), " +
				"(CASE WHEN (facility.comm_state <> '') " +
					"THEN facility.comm_state ELSE application.comm_state END), " +
				"tv_eng_data.fac_zone, " +
				"tv_eng_data.tv_dom_status, " +
				"application.file_prefix, " +
				"application.app_arn, " +
				"tv_eng_data.freq_offset, " +
				"tv_eng_data.dt_emission_mask, " +
				"tv_eng_data.lat_dir, " +
				"tv_eng_data.lat_deg, " +
				"tv_eng_data.lat_min, " +
				"tv_eng_data.lat_sec, " +
				"tv_eng_data.lon_dir, " +
				"tv_eng_data.lon_deg, " +
				"tv_eng_data.lon_min, " +
				"tv_eng_data.lon_sec, " +
				"tv_eng_data.rcamsl_horiz_mtr, " +
				"tv_eng_data.haat_rc_mtr, " +
				"tv_eng_data.effective_erp, " +
				"tv_eng_data.max_erp_any_angle, " +
				"tv_eng_data.antenna_id, " +
				"tv_eng_data.ant_rotation, " +
				"tv_eng_data.elevation_antenna_id, " +
				"tv_eng_data.electrical_deg, " +
				"tv_eng_data.mechanical_deg, " +
				"tv_eng_data.true_deg, " +
				"tv_eng_data.eng_record_type, " +
				"tv_app_indicators.da_ind, " +
				"tv_eng_data.site_number, " +
				"tv_eng_data.vsd_service," +
				"facility.fac_country," +
				"tv_eng_data.application_id," +
				"tv_eng_data.facility_id," +
				"tv_eng_data.station_channel," +
				"tv_eng_data.predict_coverage_area AS dts_max_dist, " +
				"facility.fac_service, " +
				"application.app_type, " +
				"tv_eng_data.antenna_id, " +
				"tv_eng_data.electrical_deg";

			frmStr =
			"FROM " +
				"tv_eng_data " +
				"JOIN facility USING (facility_id) " +
				"JOIN application USING (application_id) " +
				"LEFT JOIN tv_app_indicators USING (application_id, site_number) ";

			if (extDb.version > 0) {
				frmStr = frmStr +
					"LEFT JOIN app_tracking USING (application_id) ";
			}

		} else {

			if (ExtDb.DB_TYPE_LMS_LIVE == extDb.type) {

				fldStr =
				"SELECT " +
					"(CASE WHEN (facility.callsign <> '') " +
						"THEN facility.callsign ELSE application.aapp_callsign END), " +
					"(CASE WHEN (facility.community_served_city <> '') " +
						"THEN facility.community_served_city " +
						"ELSE application_facility.afac_community_city END), " +
					"(CASE WHEN (facility.community_served_state <> '') " +
						"THEN facility.community_served_state " +
						"ELSE application_facility.afac_community_state_code END), " +
					"application_facility.afac_facility_zone_code, " +
					"license_filing_version.current_status_code, " +
					"'BLANK' AS file_prefix, " +
					"application.aapp_file_num, " +
					"app_antenna_frequency.aafq_offset, " +
					"app_antenna.emission_mask_code, " +
					"app_location.aloc_lat_dir, " +
					"(CASE WHEN app_location.aloc_lat_deg = '' THEN 0::INT " +
						"ELSE app_location.aloc_lat_deg::INT END), " +
					"(CASE WHEN app_location.aloc_lat_mm = '' THEN 0::INT " +
						"ELSE app_location.aloc_lat_mm::INT END), " +
					"(CASE WHEN app_location.aloc_lat_ss = '' THEN 0::FLOAT " +
						"ELSE app_location.aloc_lat_ss::FLOAT END), " +
					"app_location.aloc_long_dir, " +
					"(CASE WHEN app_location.aloc_long_deg = '' THEN 0::INT " +
						"ELSE app_location.aloc_long_deg::INT END), " +
					"(CASE WHEN app_location.aloc_long_mm = '' THEN 0::INT " +
						"ELSE app_location.aloc_long_mm::INT END), " +
					"(CASE WHEN app_location.aloc_long_ss = '' THEN 0::FLOAT " +
						"ELSE app_location.aloc_long_ss::FLOAT END), " +
					"(CASE WHEN app_antenna.aant_rc_amsl = '' THEN 0::FLOAT " +
						"ELSE app_antenna.aant_rc_amsl::FLOAT END), " +
					"(CASE WHEN app_antenna.aant_rc_haat = '' THEN 0::FLOAT " +
						"ELSE app_antenna.aant_rc_haat::FLOAT END), " +
					"(CASE WHEN app_antenna_frequency.aafq_power_erp_kw = '' THEN 0::FLOAT " +
						"ELSE app_antenna_frequency.aafq_power_erp_kw::FLOAT END), " +
					"0::FLOAT AS max_erp_any_angle, " +
					"app_antenna.aant_antenna_record_id, " +
					"(CASE WHEN app_antenna.aant_rotation_deg = '' THEN 0::FLOAT " +
						"ELSE app_antenna.aant_rotation_deg::FLOAT END), " +
					"app_antenna.aant_antenna_record_id, " +
					"(CASE WHEN app_antenna.aant_electrical_deg = '' THEN 0::FLOAT " +
						"ELSE app_antenna.aant_electrical_deg::FLOAT END), " +
					"(CASE WHEN app_antenna.aant_mechanical_deg = '' THEN 0::FLOAT " +
						"ELSE app_antenna.aant_mechanical_deg::FLOAT END), " +
					"(CASE WHEN app_antenna.aant_true_deg = '' THEN 0::FLOAT " +
						"ELSE app_antenna.aant_true_deg::FLOAT END), " +
					"(CASE WHEN (application.dts_reference_ind = 'R') THEN 'N' " +
						"ELSE license_filing_version.active_ind END), " +
					"app_antenna.aant_antenna_type_code, " +
					"app_location.aloc_loc_seq_id, " +
					"license_filing_version.service_code," +
					"application_facility.country_code," +
					"app_location.aloc_aapp_application_id," +
					"application_facility.afac_facility_id," +
					"(CASE WHEN application_facility.afac_channel = '' THEN 0::INT " +
						"ELSE application_facility.afac_channel::INT END)," +
					"application.dts_waiver_distance, " +
					"license_filing_version.service_code, " +
					"license_filing_version.auth_type_code, " +
					"app_antenna.aant_antenna_id, " +
					"(CASE WHEN app_antenna.foreign_station_beam_tilt = '' THEN 0::FLOAT " +
						"ELSE app_antenna.foreign_station_beam_tilt::FLOAT END)";

				frmStr =
				"FROM " +
					"mass_media.app_location " +
					"LEFT JOIN mass_media.app_antenna ON (app_antenna.aant_aloc_loc_record_id = " +
						"app_location.aloc_loc_record_id) " +
					"LEFT JOIN mass_media.app_antenna_frequency ON " +
						"(app_antenna_frequency.aafq_aant_antenna_record_id = app_antenna.aant_antenna_record_id) " +
					"JOIN common_schema.application ON (application.aapp_application_id = " +
						"app_location.aloc_aapp_application_id) " +
					"JOIN common_schema.license_filing_version ON (license_filing_version.filing_version_id = " +
						"app_location.aloc_aapp_application_id) " +
					"JOIN common_schema.application_facility ON (application_facility.afac_application_id = " +
						"app_location.aloc_aapp_application_id) " +
					"JOIN common_schema.facility ON (facility.facility_id = application_facility.afac_facility_id) " +
					"LEFT JOIN mass_media.shared_channel ON ((shared_channel.application_id = " +
						"app_location.aloc_aapp_application_id) AND (shared_channel.facility_id = " +
						"application_facility.afac_facility_id)) ";

			} else {

				// The LMS export files intermittently have duplicated rows in some tables that result in duplicate
				// tuples in the query result.  Assuming those are always exact duplicates, SELECT DISTINCT filters
				// them out.  There's no down side because for some reason it seems to slightly improve query speed.

				if (extDb.version > 1) {
					fldStr =
					"SELECT DISTINCT " +
						"(CASE WHEN (facility.callsign <> '') " +
							"THEN facility.callsign ELSE application.aapp_callsign END), " +
						"(CASE WHEN (facility.community_served_city <> '') " +
							"THEN facility.community_served_city " +
							"ELSE application_facility.afac_community_city END), " +
						"(CASE WHEN (facility.community_served_state <> '') " +
							"THEN facility.community_served_state " +
							"ELSE application_facility.afac_community_state_code END), ";
				} else {
					fldStr =
					"SELECT DISTINCT " +
						"application.aapp_callsign, " +
						"application_facility.afac_community_city, " +
						"application_facility.afac_community_state_code, ";
				}

				fldStr = fldStr +
					"application_facility.afac_facility_zone_code, " +
					"license_filing_version.current_status_code, " +
					"'BLANK' AS file_prefix, " +
					"application.aapp_file_num, " +
					"app_antenna_frequency.aafq_offset, " +
					"app_antenna.emission_mask_code, " +
					"app_location.aloc_lat_dir, " +
					"app_location.aloc_lat_deg, " +
					"app_location.aloc_lat_mm, " +
					"app_location.aloc_lat_ss, " +
					"app_location.aloc_long_dir, " +
					"app_location.aloc_long_deg, " +
					"app_location.aloc_long_mm, " +
					"app_location.aloc_long_ss, " +
					"app_antenna.aant_rc_amsl, " +
					"app_antenna.aant_rc_haat, " +
					"app_antenna_frequency.aafq_power_erp_kw, " +
					"0. AS max_erp_any_angle, " +
					"app_antenna.aant_antenna_record_id, " +
					"app_antenna.aant_rotation_deg, " +
					"app_antenna.aant_antenna_record_id, " +
					"app_antenna.aant_electrical_deg, " +
					"app_antenna.aant_mechanical_deg, " +
					"app_antenna.aant_true_deg, " +
					"(CASE WHEN (application.dts_reference_ind = 'R') THEN 'N' " +
						"ELSE license_filing_version.active_ind END), " +
					"app_antenna.aant_antenna_type_code, " +
					"app_location.aloc_loc_seq_id, " +
					"license_filing_version.service_code," +
					"application_facility.country_code," +
					"app_location.aloc_aapp_application_id," +
					"application_facility.afac_facility_id," +
					"application_facility.afac_channel," +
					"application.dts_waiver_distance, " +
					"license_filing_version.service_code, " +
					"license_filing_version.auth_type_code, " +
					"app_antenna.aant_antenna_id";

				if (extDb.version > 4) {
					fldStr = fldStr + ", " +
						"app_antenna.foreign_station_beam_tilt";
				} else {
					fldStr = fldStr + ", " +
						"app_antenna.aant_electrical_deg";
				}

				frmStr =
				"FROM " +
					"app_location " +
					"LEFT JOIN app_antenna ON (app_antenna.aant_aloc_loc_record_id = " +
						"app_location.aloc_loc_record_id) " +
					"LEFT JOIN app_antenna_frequency ON (app_antenna_frequency.aafq_aant_antenna_record_id = " +
						"app_antenna.aant_antenna_record_id) " +
					"JOIN application ON (application.aapp_application_id = app_location.aloc_aapp_application_id) " +
					"JOIN license_filing_version ON (license_filing_version.filing_version_id = " +
						"app_location.aloc_aapp_application_id) " +
					"JOIN application_facility ON (application_facility.afac_application_id = " +
						"app_location.aloc_aapp_application_id) ";

				if (extDb.version > 1) {
					frmStr = frmStr +
						"JOIN facility ON (facility.facility_id = application_facility.afac_facility_id) ";
				}

				if (extDb.version > 5) {
					frmStr = frmStr +
						"LEFT JOIN shared_channel ON ((shared_channel.application_id = " +
							"app_location.aloc_aapp_application_id) AND (shared_channel.facility_id = " +
							"application_facility.afac_facility_id)) ";
				}
			}
		}

		// Queries for finding DTS transmitters for a given parent.  These do not apply any conditions beyond matching
		// primary record ID; if that matches it is assumed other fields must also be appropriate.

		if (null != dtsParent) {

			fldStr = fldStr + " ";

			if (isCDBS) {

				whrStr =
				"WHERE " +
					"(tv_eng_data.application_id = " + dtsParent.extRecordID + ") AND " +
					"(tv_eng_data.site_number > 0) ";

				ordStr = "ORDER BY tv_eng_data.site_number";

			} else {

				whrStr =
				"WHERE " +
					"(app_location.aloc_aapp_application_id = '" + dtsParent.extRecordID + "') AND " +
					"(app_location.aloc_dts_reference_location_ind = 'N') ";

				ordStr = "ORDER BY app_location.aloc_loc_seq_id";
			}

		// Set up a normal query, finding DTS parents and all non-DTS records, and applying the conditions in the
		// query argument, if any.  This allows the argument to be null/empty to match all records.

		} else {

			if ((null != query) && (query.length() > 0)) {
				whrStr = "WHERE (" + query + ") AND ";
			} else {
				whrStr = "WHERE ";
			}

			if (isCDBS) {

				// CDBS import version 1 added the app_tracking table to get accepted_date for preference sequencing.
				// For version 0 substitute tv_eng_data.last_change_date, not quite the same but the only thing
				// available that is even close.  Also app_tracking.accepted_date is sometimes blank, again substitute
				// tv_eng_data.last_change_date as needed.

				if (extDb.version < 1) {

					fldStr = fldStr + ", " +
						"facility.fac_status, " +
						"tv_eng_data.last_change_date AS sequence_date, " +
						"'N' AS host_ind, " +
						"'' AS licensee ";

				} else {

					fldStr = fldStr + ", " +
						"facility.fac_status, " +
						"(CASE WHEN (app_tracking.accepted_date IS NOT NULL " +
								"AND (app_tracking.accepted_date <> '')) " +
							"THEN app_tracking.accepted_date " +
							"ELSE tv_eng_data.last_change_date END) AS sequence_date, " +
						"'N' AS host_ind, " +
						"'' AS licensee ";
				}

				whrStr = whrStr + "((tv_eng_data.site_number = 0) OR (tv_eng_data.vsd_service <> 'DD')) ";

			} else {

				// LMS import version 0 is no longer supported.  Import version 1 added fields dts_waiver_distance and
				// dts_reference_ind, those are now in the common portion above.  Version 2 adds the facility table to
				// get facility_status.  Note the live server is always the current version so is only in that case.

				if (extDb.version < 1) {

					if (null != errors) {
						errors.reportError("Unsupported data set import version.");
					}
					return null;

				} else {

					if (extDb.version < 2) {

						fldStr = fldStr + ", " +
							"'' AS facility_status, " +
							"application.aapp_receipt_date AS sequence_date";

					} else {

						fldStr = fldStr + ", " +
							"facility.facility_status, " +
							"application.aapp_receipt_date AS sequence_date";
					}

					if (extDb.version > 5) {
						fldStr = fldStr + ", " +
							"shared_channel.host_ind, " +
							"application_facility.licensee_name ";
					} else {
						fldStr = fldStr + ", " +
							"'N' AS host_ind, " +
							"application_facility.licensee_name ";
					}
				}

				// LMS queries are always restricted to a set of purpose codes identifying records that are relevant to
				// engineering studies; other codes are for administrative purposes.

				whrStr = whrStr +
					"((CASE WHEN (license_filing_version.purpose_code = 'AMD') " +
						"THEN license_filing_version.original_purpose_code " +
						"ELSE license_filing_version.purpose_code END) IN ('CP','L2C','MOD','RUL','STA')) AND " +
					"((license_filing_version.service_code <> 'DTS') OR " +
						"(app_location.aloc_dts_reference_location_ind = 'Y')) ";

				// Channel-sharing station records are ignored unless they are for the sharing host.  For older
				// imports that can't identify the host, all channel-sharing records are ignored.

				if (extDb.version < 6) {
					whrStr = whrStr +
						"AND (application.channel_sharing_ind <> 'Y') ";
				} else {
					whrStr = whrStr +
						"AND ((application.channel_sharing_ind <> 'Y') OR (shared_channel.host_ind = 'Y')) ";
				}
			}
		}

		// Connect and run the query.

		LinkedList<ExtDbRecordTV> result = null;
		boolean hasDTS = false;

		DbConnection db = extDb.connectDb(errors);
		if (null != db) {
			try {

				result = new LinkedList<ExtDbRecordTV>();
				ExtDbRecordTV theRecord;

				Service theService;
				Country theCountry;
				int cdbsAppID, facID, chan, i, antID, statType;
				double dtsDist;
				boolean drtFlag, isArch, isPend;
				String str, recID, dir, stat, pfx, arn;
				GeoPoint thePoint = new GeoPoint();

				SimpleDateFormat dateFormat;
				if (isCDBS) {
					dateFormat = new SimpleDateFormat("MM/dd/yyyy", Locale.US);
				} else {
					dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
				}
				java.util.Date seqDate = null;

				db.query(fldStr + frmStr + whrStr + ordStr);

				while (db.next()) {

					// For DTS records other than the parent, service, country, record ID, facility ID, channel, and
					// status all come from the parent.  For all others including DTS parents extract and check all of
					// those, if any are bad ignore the record.  Note the constants CHANNEL_MIN and CHANNEL_MAX are
					// used to check channel range, those always define the maximum possible range.  If a narrower
					// range is desired i.e. due to study parameter settings, that has to be included explicitly in the
					// query.  For a DTS parent from LMS the operating parameter fields will be null, the DTS reference
					// location does not have data in the app_antenna or app_antenna_frequency tables.  However that
					// means those are LEFT JOIN tables so the fields could somehow be null for a record that is not a
					// DTS parent; such a record could not be used since it is incomplete, check for that and ignore.

					if (null != dtsParent) {

						if (!isCDBS && (null == db.getString(22))) {
							continue;
						}

						theService = dtsParent.service;
						theCountry = dtsParent.country;
						recID = dtsParent.extRecordID;
						facID = dtsParent.facilityID;
						chan = dtsParent.channel;
						dtsDist = 0.;
						seqDate = new java.util.Date(0);
						drtFlag = false;
						stat = dtsParent.status;
						statType = dtsParent.statusType;
						isArch = dtsParent.isArchived;
						isPend = dtsParent.isPending;

					} else {

						if (isCDBS) {
							theService = cdbsCodeCache.get(db.getString(31));
						} else {
							theService = lmsCodeCache.get(db.getString(31));
						}
						if (null == theService) {
							continue;
						}

						if (!isCDBS && !theService.isDTS && (null == db.getString(22))) {
							continue;
						}

						theCountry = Country.getCountry(db.getString(32));
						if (null == theCountry) {
							continue;
						}

						if (isCDBS) {
							cdbsAppID = db.getInt(33);
							if (cdbsAppID <= 0) {
								continue;
							}
							recID = String.valueOf(cdbsAppID);
						} else {
							recID = db.getString(33);
							if ((null == recID) || (0 == recID.length())) {
								continue;
							}
						}

						facID = db.getInt(34);

						chan = db.getInt(35);
						if ((chan < SourceTV.CHANNEL_MIN) || (chan > SourceTV.CHANNEL_MAX)) {
							continue;
						}

						// Text sometimes appears in the DTS waiver distance field in LMS so that is not typed as a
						// number, parse it here so the bad data can be ignored (just use table distance).

						dtsDist = 0.;
						if (theService.isDTS) {
							try {
								str = db.getString(36);
								if (null != str) {
									dtsDist = Double.parseDouble(str);
								}
							} catch (NumberFormatException e) {
							}
						}

						// Extract sequence date, see isPreferredRecord().  In CDBS and imported LMS this is stored as
						// a string because MySQL won't directly convert the date formats from the import files, so in
						// all cases this is read as a string and parsed.

						seqDate = null;
						try {
							str = db.getString(42);
							if (null != str) {
								seqDate = dateFormat.parse(str);
							}
						} catch (ParseException pe) {
						}
						if (null == seqDate) {
							seqDate = new java.util.Date(0);
						}

						// Check for a DRT (Digital Replacement Translator).  These are low-power digital facilities
						// that are essentially boosters for full-service parent stations, usually on a different
						// channel.  DRT records have the same facility ID as the parent, and can be identified by
						// checking service codes in the engineering record vs. the facility record in CDBS.  In LMS
						// these are specifically identified by a service code.

						str = db.getString(37);
						if (isCDBS) {
							drtFlag = ((null != str) && str.equals("DT") &&
								(ServiceType.SERVTYPE_DTV_LPTV == theService.serviceType.key));
						} else {
							drtFlag = ((null != str) && str.equals("DRT"));
						}

						// Facility status, this modifies logic for some other properties.

						String facStat = db.getString(41);
						if (null == facStat) {
							facStat = "";
						}

						// Determine the record status.  If the facility status is "EXPER" the status is EXP regardless
						// of anything else.  Otherwise, this involves both a status code and a type code.  In either
						// CDBS or LMS a type indicating the record is an STA will force that status regardless of the
						// actual status code.  In CDBS an STA has "STA" or "STAX" in the application.app_type field.
						// In LMS it is "S" in the license_filing_version.auth_type_code field.  Otherwise for CDBS the
						// code from tv_eng_data.tv_dom_status is matched to a list mapping to a status type code.  For
						// LMS if the code "REV" appears in license_filing_version.current_status_code, the status is
						// AMD (for amendment).  If anything in the LMS_APP_STATUS_CODES list appears in that field the
						// status is APP.  Otherwise "C" in the auth_type_code field means CP, "L" means LIC.

						stat = db.getString(5);
						if (null == stat) {
							stat = "";
						}
						statType = STATUS_TYPE_OTHER;

						if (facStat.equals("EXPER")) {
							statType = STATUS_TYPE_EXP;
						} else {

							String type = db.getString(38);
							if (null == type) {
								type = "";
							}
							if (isCDBS) {
								if (type.equalsIgnoreCase("STA") || type.equalsIgnoreCase("STAX")) {
									statType = STATUS_TYPE_STA;
								} else {
									for (i = 0; i < CDBS_STATUS_CODES.length; i++) {
										if (CDBS_STATUS_CODES[i].equalsIgnoreCase(stat)) {
											statType = CDBS_STATUS_TYPES[i];
											break;
										}
									}
								}
							} else {
								if (type.equalsIgnoreCase("S")) {
									statType = STATUS_TYPE_STA;
								} else {
									if (stat.equalsIgnoreCase("REV")) {
										statType = STATUS_TYPE_AMD;
									} else {
										for (i = 0; i < LMS_APP_STATUS_CODES.length; i++) {
											if (LMS_APP_STATUS_CODES[i].equalsIgnoreCase(stat)) {
												statType = STATUS_TYPE_APP;
												break;
											}
										}
										if (STATUS_TYPE_OTHER == statType) {
											if (type.equalsIgnoreCase("C")) {
												statType = STATUS_TYPE_CP;
											} else {
												if (type.equalsIgnoreCase("L")) {
													statType = STATUS_TYPE_LIC;
												}
											}
										}
									}
								}
							}
						}

						if (STATUS_TYPE_OTHER != statType) {
							stat = STATUS_CODES[statType];
						}

						// Get archived and pending flags.  If the facility status is FVOID treat this as an archived
						// record regardless of other fields.  Otherwise in CDBS check tv_eng_data.eng_record_type, 'P'
						// means pending, 'A' and 'R' are archived.  In LMS if license_filing_version.active_ind is 'N'
						// it is archived, if license_filing_version.current_status_code is 'PEN' it is pending.

						isArch = false;
						isPend = false;

						if (facStat.equals("FVOID")) {
							isArch = true;
						} else {

							str = db.getString(28);
							if (null != str) {
								if (isCDBS) {
									if (str.equalsIgnoreCase("P")) {
										isPend = true;
									} else {
										if (str.equalsIgnoreCase("A") || str.equalsIgnoreCase("R")) {
											isArch = true;
										}
									}
								} else {
									if (str.equalsIgnoreCase("N")) {
										isArch = true;
									} else {
										str = db.getString(5);
										if ((null != str) && str.equalsIgnoreCase("PEN")) {
											isPend = true;
										}
									}
								}
							}
						}
					}

					// Extract coordinates first, if a search radius is set, check that.

					thePoint.latitudeNS = 0;
					dir = db.getString(10);
					if ((null != dir) && dir.equalsIgnoreCase("S")) {
						thePoint.latitudeNS = 1;
					}
					thePoint.latitudeDegrees = db.getInt(11);
					thePoint.latitudeMinutes = db.getInt(12);
					thePoint.latitudeSeconds = db.getDouble(13);

					thePoint.longitudeWE = 0;
					dir = db.getString(14);
					if ((null != dir) && dir.equalsIgnoreCase("E")) {
						thePoint.longitudeWE = 1;
					}
					thePoint.longitudeDegrees = db.getInt(15);
					thePoint.longitudeMinutes = db.getInt(16);
					thePoint.longitudeSeconds = db.getDouble(17);

					thePoint.updateLatLon();
					if (isCDBS) {
						thePoint.convertFromNAD27();
					}

					// Apply the search radius check, if the record is outside the distance ignore it, except for DTS.
					// For DTS if this is the top-level search, skip the distance check on DTS parent records and keep
					// all in the results for now.  When this is a secondary (recursive) search to load individual
					// transmitter records for a given parent, all are checked but rather than ignoring those outside,
					// set a flag in the parent if any are inside; if all are outside (flag is not set) then the parent
					// will be removed from the top-level results.  See below.

					if ((null != searchCenter) && (searchRadius > 0.)) {
						if (null != dtsParent) {
							if (searchCenter.distanceTo(thePoint, kmPerDegree) <= searchRadius) {
								dtsParent.inSearchRadius = true;
							}
						} else {
							if (!theService.isDTS && (searchCenter.distanceTo(thePoint, kmPerDegree) > searchRadius)) {
								continue;
							}
						}
					}

					// Save the record in the results.

					theRecord = new ExtDbRecordTV(extDb);

					theRecord.extRecordID = recID;
					if (theService.isDTS && (null == dtsParent)) {
						theRecord.siteNumber = 0;
					} else {
						theRecord.siteNumber = db.getInt(30);
					}
					theRecord.facilityID = facID;

					theRecord.service = theService;
					theRecord.isDRT = drtFlag;

					str = db.getString(1);
					if (null == str) {
						str = "";
					} else {
						if (str.length() > Source.MAX_CALL_SIGN_LENGTH) {
							str = str.substring(0, Source.MAX_CALL_SIGN_LENGTH);
						}
					}
					theRecord.callSign = str;

					theRecord.channel = chan;

					str = db.getString(2);
					if (null == str) {
						str = "";
					} else {
						if (str.length() > Source.MAX_CITY_LENGTH) {
							str = str.substring(0, Source.MAX_CITY_LENGTH);
						}
					}
					theRecord.city = str;
					str = db.getString(3);
					if (null == str) {
						str = "";
					} else {
						if (str.length() > Source.MAX_STATE_LENGTH) {
							str = str.substring(0, Source.MAX_STATE_LENGTH);
						}
					}
					theRecord.state = str;
					theRecord.country = theCountry;

					theRecord.zoneCode = db.getString(4);
					if (null == theRecord.zoneCode) {
						theRecord.zoneCode = "";
					}

					if (stat.length() > Source.MAX_STATUS_LENGTH) {
						stat = stat.substring(0, Source.MAX_STATUS_LENGTH);
					}
					theRecord.status = stat;
					theRecord.statusType = statType;

					theRecord.isArchived = isArch;
					theRecord.isPending = isPend;

					pfx = db.getString(6);
					arn = db.getString(7);
					if (!isCDBS && (null != arn)) {
						String[] parts = arn.split("-");
						if (2 == parts.length) {
							pfx = parts[0];
							arn = parts[1];
						}
					}
					if (null == arn) {
						arn = "";
					} else {
						if (arn.length() > Source.MAX_FILE_NUMBER_LENGTH) {
							arn = arn.substring(0, Source.MAX_FILE_NUMBER_LENGTH);
						}
					}
					if (null == pfx) {
						pfx = "";
					} else {
						if ((pfx.length() + arn.length()) > Source.MAX_FILE_NUMBER_LENGTH) {
							pfx = pfx.substring(0, Source.MAX_FILE_NUMBER_LENGTH - arn.length());
						}
					}
					theRecord.filePrefix = pfx;
					theRecord.appARN = arn;

					theRecord.location.setLatLon(thePoint);

					theRecord.dtsMaximumDistance = dtsDist;

					if (null == dtsParent) {

						str = db.getString(43);
						if ((null != str) && str.equalsIgnoreCase("Y")) {
							theRecord.isSharingHost = true;
						}

						theRecord.licensee = db.getString(44);
					}

					// Operating parameter fields are null for DTS parent from LMS, see discussion above.

					if (!isCDBS && theService.isDTS && (null == dtsParent)) {

						theRecord.frequencyOffsetCode = "";
						theRecord.emissionMaskCode = "";

					} else {

						theRecord.frequencyOffsetCode = db.getString(8);
						if (null == theRecord.frequencyOffsetCode) {
							theRecord.frequencyOffsetCode = "";
						}

						theRecord.emissionMaskCode = db.getString(9);
						if (null == theRecord.emissionMaskCode) {
							theRecord.emissionMaskCode = "";
						}

						theRecord.heightAMSL = db.getDouble(18);
						theRecord.overallHAAT = db.getDouble(19);

						theRecord.peakERP = db.getDouble(20);
						theRecord.alternateERP = db.getDouble(21);

						if (isCDBS) {
							antID = db.getInt(22);
							if (antID > 0) {
								theRecord.antennaRecordID = String.valueOf(antID);
								theRecord.antennaID = db.getString(39);
							}
						} else {
							str = db.getString(22);
							if (null != str) {
								str = str.trim();
								if (0 == str.length()) {
									str = null;
								}
							}
							theRecord.antennaRecordID = str;
							str = db.getString(39);
							if (null != str) {
								str = str.trim();
								if (0 == str.length()) {
									str = null;
								}
							}
							theRecord.antennaID = str;
						}
						theRecord.horizontalPatternOrientation = Math.IEEEremainder(db.getDouble(23), 360.);
						if (theRecord.horizontalPatternOrientation < 0.)
							theRecord.horizontalPatternOrientation += 360.;

						if (isCDBS) {
							antID = db.getInt(24);
							if (antID > 0) {
								theRecord.elevationAntennaRecordID = String.valueOf(antID);
							}
						} else {
							theRecord.elevationAntennaRecordID = db.getString(24);
						}
						theRecord.verticalPatternElectricalTilt = db.getDouble(25);
						if ((Country.US != theCountry.key) && (0. == theRecord.verticalPatternElectricalTilt)) {
							theRecord.verticalPatternElectricalTilt = db.getDouble(40);
						}
						theRecord.verticalPatternMechanicalTilt = db.getDouble(26);
						theRecord.verticalPatternMechanicalTiltOrientation =
							Math.IEEEremainder(db.getDouble(27), 360.);
						if (theRecord.verticalPatternMechanicalTiltOrientation < 0.)
							theRecord.verticalPatternMechanicalTiltOrientation += 360.;

						str = db.getString(29);
						if (null != str) {
							if (isCDBS) {
								theRecord.daIndicated = str.equalsIgnoreCase("Y");
							} else {
								theRecord.daIndicated = str.equalsIgnoreCase("DIR");
							}
						}
					}

					theRecord.sequenceDate = seqDate;

					result.add(theRecord);

					if (theService.isDTS) {
						hasDTS = true;
					}
				}

				extDb.releaseDb(db);

			} catch (SQLException se) {
				extDb.releaseDb(db);
				result = null;
				DbConnection.reportError(errors, se);
			}
		}

		// If this was a top-level search and any DTS parent records were found, do lookups of DTS transmitter records
		// for each parent.  If the secondary search fails, the top-level search fails too (the only complete failure
		// here is an SQL exception which should abort the entire operation).  If the secondary search returns no
		// records, log it and remove the parent from the top-level results.  If a radius search is in effect the
		// parent records may have been included regardless of the radius, if the distance to individual transmitters
		// needs to be checked.  In that case if all are outside the parent is removed.  See above.

		if ((null != result) && (null == dtsParent) && hasDTS) {

			ListIterator<ExtDbRecordTV> lit = result.listIterator(0);
			ExtDbRecordTV theRecord;

			while (lit.hasNext()) {
				theRecord = lit.next();
				if (!theRecord.service.isDTS) {
					continue;
				}

				theRecord.dtsRecords = findRecordsTV(extDb, query, searchCenter, searchRadius, kmPerDegree, theRecord,
					errors);

				if (null == theRecord.dtsRecords) {
					return null;
				} else {

					if (theRecord.dtsRecords.isEmpty()) {
						if (null != errors) {
							errors.logMessage(
								makeMessage(theRecord, "Ignored bad DTS, no records with site_number > 0."));
						}
						lit.remove();
					} else {

						if ((null != searchCenter) && (searchRadius > 0.) && !theRecord.inSearchRadius) {
							lit.remove();
						}
					}
				}
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Do a search of the DTV baseline facility record table.  For LMS this requires import version 2 or later.  The
	// dtsRefSearch flag is true if this search is looking for DTS reference facility records, which must be non-DTS
	// records.  That has no effect for a CDBS search because the CDBS baseline table only provides non-DTS records.
	// In LMS the baseline table itself can also only provide non-DTS records, but a baseline record may also refer to
	// a normal DTS record that supersedes the non-DTS facility in the baseline role but not the reference facility
	// role.  In that case when searching for a DTS reference facility the baseline record is returned, otherwise the
	// referenced DTS record is found and returned instead.  However in the latter case the referenced DTS record may
	// be on a different channel that the non-DTS baseline; in that case the DTS must be replicated to the baseline
	// channel, properties are set so that will be done by code that adds the record to a study.

	public static LinkedList<ExtDbRecordTV> findBaselineRecords(ExtDb extDb, String query) {
		return findBaselineRecords(extDb, query, null, 0., 0., false, null);
	}

	public static LinkedList<ExtDbRecordTV> findBaselineRecords(ExtDb extDb, String query, ErrorLogger errors) {
		return findBaselineRecords(extDb, query, null, 0., 0., false, errors);
	}

	public static LinkedList<ExtDbRecordTV> findBaselineRecords(ExtDb extDb, String query, GeoPoint searchCenter,
			double searchRadius, double kmPerDegree) {
		return findBaselineRecords(extDb, query, searchCenter, searchRadius, kmPerDegree, false, null);
	}

	public static LinkedList<ExtDbRecordTV> findBaselineRecords(ExtDb extDb, String query, GeoPoint searchCenter,
			double searchRadius, double kmPerDegree, ErrorLogger errors) {
		return findBaselineRecords(extDb, query, searchCenter, searchRadius, kmPerDegree, false, errors);
	}

	private static LinkedList<ExtDbRecordTV> findBaselineRecords(ExtDb extDb, String query, GeoPoint searchCenter,
			double searchRadius, double kmPerDegree, boolean dtsRefSearch, ErrorLogger errors) {

		boolean isCDBS = (ExtDb.DB_TYPE_CDBS == extDb.type);
		if (!isCDBS && (((ExtDb.DB_TYPE_LMS != extDb.type) && (ExtDb.DB_TYPE_LMS_LIVE != extDb.type)) ||
				(extDb.version < 2))) {
			return new LinkedList<ExtDbRecordTV>();
		}

		// These are usually assumed U.S. DTV records, except LMS version 3 adds explicit service and country fields.

		Service theService = null;
		Country theCountry = null;

		if (isCDBS || (extDb.version < 3)) {

			theService = Service.getService("DT");
			if (null == theService) {
				if (null != errors) {
					errors.reportError("ExtDbRecordTV.findBaselineRecords(): could not find service.");
				}
				return null;
			}

			theCountry = Country.getCountry(Country.US);
			if (null == theCountry) {
				if (null != errors) {
					errors.reportError("ExtDbRecordTV.findBaselineRecords(): could not find country.");
				}
				return null;
			}
		}

		// The sequence date for LMS is set to the baseline effective date from configuration, if somehow that is not
		// defined use "now".  For CDBS, use the distant past.

		String str;

		java.util.Date seqDate = null;

		if (isCDBS) {
			seqDate = new java.util.Date(0);
		} else {
			str = AppCore.getConfig(AppCore.CONFIG_LMS_BASELINE_DATE);
			if ((null != str) && (str.length() > 0)) {
				seqDate = AppCore.parseDate(str);
			}
			if (null == seqDate) {
				seqDate = new java.util.Date();
			}
		}

		String fldStr = "", frmStr = "", whrStr = "";

		if ((null != query) && (query.length() > 0)) {
			whrStr = " WHERE (" + query + ")";
		}

		if (isCDBS) {

			fldStr =
			"SELECT " +
				"dtv_channel_assignments.facility_id, " +
				"dtv_channel_assignments.post_dtv_channel, " +
				"(CASE WHEN (facility.fac_callsign <> '') " +
					"THEN facility.fac_callsign ELSE dtv_channel_assignments.callsign END), " +
				"(CASE WHEN (facility.comm_city <> '') " +
					"THEN facility.comm_city ELSE dtv_channel_assignments.city END), " +
				"(CASE WHEN (facility.comm_state <> '') " +
					"THEN facility.comm_state ELSE dtv_channel_assignments.state END), " +
				"dtv_channel_assignments.latitude, " +
				"dtv_channel_assignments.longitude, " +
				"dtv_channel_assignments.rcamsl, " +
				"dtv_channel_assignments.haat, " +
				"dtv_channel_assignments.erp, " +
				"dtv_channel_assignments.da_ind, " +
				"dtv_channel_assignments.antenna_id, " +
				"dtv_channel_assignments.ref_azimuth";

			frmStr =
			" FROM " +
				"dtv_channel_assignments " +
				"JOIN facility USING (facility_id)";

		} else {

			if (ExtDb.DB_TYPE_LMS_LIVE == extDb.type) {

				fldStr =
				"SELECT " +
					"(CASE WHEN app_dtv_channel_assignment.adca_facility_record_id = '' THEN 0::INT " +
						"ELSE app_dtv_channel_assignment.adca_facility_record_id::INT END), " +
					"lkp_dtv_allotment.rdta_digital_channel, " +
					"(CASE WHEN (facility.callsign <> '') " +
						"THEN facility.callsign ELSE app_dtv_channel_assignment.callsign END), " +
					"(CASE WHEN (facility.community_served_city <> '' ) " +
						"THEN facility.community_served_city ELSE lkp_dtv_allotment.rdta_city END), " +
					"(CASE WHEN (facility.community_served_state <> '' ) " +
						"THEN facility.community_served_state ELSE lkp_dtv_allotment.rdta_state END), " +
					"lkp_dtv_allotment.rdta_lat_dir, " +
					"lkp_dtv_allotment.rdta_lat_deg, " +
					"lkp_dtv_allotment.rdta_lat_min, " +
					"lkp_dtv_allotment.rdta_lat_sec, " +
					"lkp_dtv_allotment.rdta_lon_dir, " +
					"lkp_dtv_allotment.rdta_lon_deg, " +
					"lkp_dtv_allotment.rdta_lon_min, " +
					"lkp_dtv_allotment.rdta_lon_sec, " +
					"app_dtv_channel_assignment.rcamsl, " +
					"lkp_dtv_allotment.rdta_haat, " +
					"lkp_dtv_allotment.rdta_erp, " +
					"app_dtv_channel_assignment.directional_antenna_ind, " +
					"app_dtv_channel_assignment.antenna_id, " +
					"app_dtv_channel_assignment.antenna_rotation, " +
					"lkp_dtv_allotment.dts_ref_application_id";

				frmStr =
				" FROM " +
					"mass_media.app_dtv_channel_assignment " +
					"JOIN mass_media.lkp_dtv_allotment ON (lkp_dtv_allotment.rdta_dtv_allotment_id = " +
						"app_dtv_channel_assignment.dtv_allotment_id) " +
					"JOIN common_schema.facility ON " +
						"(CASE WHEN app_dtv_channel_assignment.adca_facility_record_id = '' THEN false " +
						"ELSE (facility.facility_id = app_dtv_channel_assignment.adca_facility_record_id::INT) END)";

			} else {

				fldStr =
				"SELECT " +
					"app_dtv_channel_assignment.adca_facility_record_id, " +
					"lkp_dtv_allotment.rdta_digital_channel, " +
					"(CASE WHEN (facility.callsign <> '') " +
						"THEN facility.callsign ELSE app_dtv_channel_assignment.callsign END), " +
					"(CASE WHEN (facility.community_served_city <> '' ) " +
						"THEN facility.community_served_city ELSE lkp_dtv_allotment.rdta_city END), " +
					"(CASE WHEN (facility.community_served_state <> '' ) " +
						"THEN facility.community_served_state ELSE lkp_dtv_allotment.rdta_state END), " +
					"lkp_dtv_allotment.rdta_lat_dir, " +
					"lkp_dtv_allotment.rdta_lat_deg, " +
					"lkp_dtv_allotment.rdta_lat_min, " +
					"lkp_dtv_allotment.rdta_lat_sec, " +
					"lkp_dtv_allotment.rdta_lon_dir, " +
					"lkp_dtv_allotment.rdta_lon_deg, " +
					"lkp_dtv_allotment.rdta_lon_min, " +
					"lkp_dtv_allotment.rdta_lon_sec, " +
					"app_dtv_channel_assignment.rcamsl, " +
					"lkp_dtv_allotment.rdta_haat, " +
					"lkp_dtv_allotment.rdta_erp, " +
					"app_dtv_channel_assignment.directional_antenna_ind, " +
					"app_dtv_channel_assignment.antenna_id, " +
					"app_dtv_channel_assignment.antenna_rotation, " +
					"lkp_dtv_allotment.dts_ref_application_id";

				frmStr =
				" FROM " +
					"app_dtv_channel_assignment " +
					"JOIN lkp_dtv_allotment ON (lkp_dtv_allotment.rdta_dtv_allotment_id = " +
						"app_dtv_channel_assignment.dtv_allotment_id) " +
					"JOIN facility ON (facility.facility_id = app_dtv_channel_assignment.adca_facility_record_id)";
			}

			if (extDb.version > 2) {
				fldStr = fldStr + ", " +
					"lkp_dtv_allotment.rdta_service_code, " +
					"lkp_dtv_allotment.rdta_country_code";
			}

			if (extDb.version > 3) {
				fldStr = fldStr + ", " +
					"app_dtv_channel_assignment.emission_mask_code";
			}

			if (extDb.version > 4) {
				fldStr = fldStr + ", " +
					"app_dtv_channel_assignment.electrical_deg";
			}
		}

		HashMap<String, ExtDbRecordTV> dtsAddMap = null;

		LinkedList<ExtDbRecordTV> result = null;
		ExtDbRecordTV theRecord;

		DbConnection db = extDb.connectDb(errors);
		if (null != db) {
			try {

				db.query(fldStr + frmStr + whrStr);

				int facID, chan;
				GeoPoint thePoint = new GeoPoint();
				result = new LinkedList<ExtDbRecordTV>();

				if (isCDBS) {

					String latstr, lonstr;
					int lati, loni, londl, antID;

					while (db.next()) {

						facID = db.getInt(1);
						if (facID <= 0) {
							continue;
						}

						chan = db.getInt(2);
						if ((chan < SourceTV.CHANNEL_MIN) || (chan > SourceTV.CHANNEL_MAX)) {
							continue;
						}

						// In the CDBS baseline records, latitude and longitude are stored degrees-minutes-seconds in a
						// fixed-width, zero-padded, un-delimited string with sign character for S/E.

						latstr = db.getString(6);
						if (null == latstr) {
							continue;
						}
						lati = 0;
						if ('-' == latstr.charAt(0)) {
							lati = 1;
						}

						lonstr = db.getString(7);
						if (null == lonstr) {
							continue;
						}
						loni = 0;
						if ('-' == lonstr.charAt(0)) {
							loni = 1;
						}
						londl = 2;
						if ((lonstr.length() - loni) > 6) {
							londl = 3;
						}

						try {

							thePoint.latitudeNS = lati;
							thePoint.latitudeDegrees = Integer.parseInt(latstr.substring(lati, lati + 2));
							thePoint.latitudeMinutes = Integer.parseInt(latstr.substring(lati + 2, lati + 4));
							thePoint.latitudeSeconds = Double.parseDouble(latstr.substring(lati + 4));

							thePoint.longitudeWE = loni;
							thePoint.longitudeDegrees = Integer.parseInt(lonstr.substring(loni, loni + londl));
							thePoint.longitudeMinutes = Integer.parseInt(lonstr.substring(loni + londl,
								loni + londl + 2));
							thePoint.longitudeSeconds = Double.parseDouble(lonstr.substring(loni + londl + 2));

						} catch (NumberFormatException ne) {
							continue;
						}

						thePoint.updateLatLon();
						thePoint.convertFromNAD27();

						if ((null != searchCenter) && (searchRadius > 0.)) {
							if (searchCenter.distanceTo(thePoint, kmPerDegree) > searchRadius) {
								continue;
							}
						}

						theRecord = new ExtDbRecordTV(extDb);

						theRecord.extRecordID = BASELINE_ID_PREFIX + String.valueOf(facID);
						theRecord.isBaseline = true;

						theRecord.service = theService;

						str = db.getString(3);
						if (null == str) {
							str = "";
						} else {
							if (str.length() > Source.MAX_CALL_SIGN_LENGTH) {
								str = str.substring(0, Source.MAX_CALL_SIGN_LENGTH);
							}
						}
						theRecord.callSign = str;

						str = db.getString(4);
						if (null == str) {
							str = "";
						} else {
							if (str.length() > Source.MAX_CITY_LENGTH) {
								str = str.substring(0, Source.MAX_CITY_LENGTH);
							}
						}
						theRecord.city = str;
						str = db.getString(5);
						if (null == str) {
							str = "";
						} else {
							if (str.length() > Source.MAX_STATE_LENGTH) {
								str = str.substring(0, Source.MAX_STATE_LENGTH);
							}
						}
						theRecord.state = str;
						theRecord.country = theCountry;

						theRecord.facilityID = facID;

						theRecord.channel = chan;

						theRecord.zoneCode = "";

						theRecord.status = BASELINE_STATUS;
						theRecord.statusType = STATUS_TYPE_OTHER;

						theRecord.filePrefix = BASELINE_ID_PREFIX;
						theRecord.appARN = String.valueOf(facID);

						theRecord.frequencyOffsetCode = "";
						theRecord.emissionMaskCode = "";

						theRecord.location.setLatLon(thePoint);

						theRecord.heightAMSL = db.getDouble(8);
						theRecord.overallHAAT = db.getDouble(9);

						theRecord.peakERP = db.getDouble(10);

						str = db.getString(11);
						if (null != str) {
							theRecord.daIndicated = str.equalsIgnoreCase("Y");
						}
						antID = db.getInt(12);
						if (antID > 0) {
							theRecord.antennaRecordID = String.valueOf(antID);
							theRecord.antennaID = theRecord.antennaRecordID;
						}
						theRecord.horizontalPatternOrientation = Math.IEEEremainder(db.getDouble(13), 360.);
						if (theRecord.horizontalPatternOrientation < 0.)
							theRecord.horizontalPatternOrientation += 360.;

						theRecord.sequenceDate = seqDate;

						result.add(theRecord);
					}

				} else {

					// If this is not a DTS reference facility search, baseline records that reference a normal DTS
					// record are set aside during the initial query, using the DTS record ID as key.  Later there will
					// be a search to find those records and morph them into baseline records to use in place of the
					// original baseline record (that may include replication).  However if the referenced record is
					// not found, or is not DTS, the original baseline record will be added to the result.

					if (!dtsRefSearch) {
						dtsAddMap = new HashMap<String, ExtDbRecordTV>();
					}

					String dir;

					while (db.next()) {

						if (extDb.version > 2) {

							theService = lmsCodeCache.get(db.getString(21));
							if (null == theService) {
								continue;
							}

							theCountry = Country.getCountry(db.getString(22));
							if (null == theCountry) {
								continue;
							}
						}

						facID = db.getInt(1);
						if (facID <= 0) {
							continue;
						}

						chan = db.getInt(2);
						if ((chan < SourceTV.CHANNEL_MIN) || (chan > SourceTV.CHANNEL_MAX)) {
							continue;
						}

						thePoint.latitudeNS = 0;
						dir = db.getString(6);
						if ((null != dir) && dir.equalsIgnoreCase("S")) {
							thePoint.latitudeNS = 1;
						}
						thePoint.latitudeDegrees = db.getInt(7);
						thePoint.latitudeMinutes = db.getInt(8);
						thePoint.latitudeSeconds = db.getDouble(9);

						thePoint.longitudeWE = 0;
						dir = db.getString(10);
						if ((null != dir) && dir.equalsIgnoreCase("E")) {
							thePoint.longitudeWE = 1;
						}
						thePoint.longitudeDegrees = db.getInt(11);
						thePoint.longitudeMinutes = db.getInt(12);
						thePoint.longitudeSeconds = db.getDouble(13);

						thePoint.updateLatLon();

						if ((null != searchCenter) && (searchRadius > 0.)) {
							if (searchCenter.distanceTo(thePoint, kmPerDegree) > searchRadius) {
								continue;
							}
						}

						theRecord = new ExtDbRecordTV(extDb);

						theRecord.extRecordID = BASELINE_ID_PREFIX + String.valueOf(facID);
						theRecord.isBaseline = true;

						theRecord.service = theService;

						str = db.getString(3);
						if (null == str) {
							str = "";
						} else {
							if (str.length() > Source.MAX_CALL_SIGN_LENGTH) {
								str = str.substring(0, Source.MAX_CALL_SIGN_LENGTH);
							}
						}
						theRecord.callSign = str;

						str = db.getString(4);
						if (null == str) {
							str = "";
						} else {
							if (str.length() > Source.MAX_CITY_LENGTH) {
								str = str.substring(0, Source.MAX_CITY_LENGTH);
							}
						}
						theRecord.city = str;
						str = db.getString(5);
						if (null == str) {
							str = "";
						} else {
							if (str.length() > Source.MAX_STATE_LENGTH) {
								str = str.substring(0, Source.MAX_STATE_LENGTH);
							}
						}
						theRecord.state = str;
						theRecord.country = theCountry;

						theRecord.facilityID = facID;

						theRecord.channel = chan;

						theRecord.zoneCode = "";

						theRecord.status = BASELINE_STATUS;
						theRecord.statusType = STATUS_TYPE_OTHER;

						theRecord.filePrefix = BASELINE_ID_PREFIX;
						theRecord.appARN = String.valueOf(facID);

						theRecord.frequencyOffsetCode = "";

						if (extDb.version > 3) {
							theRecord.emissionMaskCode = db.getString(23);
						} else {
							theRecord.emissionMaskCode = "";
						}

						theRecord.location.setLatLon(thePoint);

						theRecord.heightAMSL = db.getDouble(14);
						theRecord.overallHAAT = db.getDouble(15);

						theRecord.peakERP = db.getDouble(16);

						str = db.getString(17);
						if (null != str) {
							theRecord.daIndicated = str.equalsIgnoreCase("Y");
						}
						str = db.getString(18);
						if (null != str) {
							str = str.trim();
							if (0 == str.length()) {
								str = null;
							}
						}
						theRecord.antennaRecordID = str;
						theRecord.antennaID = theRecord.antennaRecordID;
						theRecord.horizontalPatternOrientation = Math.IEEEremainder(db.getDouble(19), 360.);
						if (theRecord.horizontalPatternOrientation < 0.)
							theRecord.horizontalPatternOrientation += 360.;

						if (extDb.version > 4) {
							theRecord.verticalPatternElectricalTilt = db.getDouble(24);
						}

						theRecord.sequenceDate = seqDate;

						// If this record has a DTS reference save it for later.

						str = db.getString(20);
						if (!dtsRefSearch && (null != str) && (str.length() > 0)) {
							dtsAddMap.put(str, theRecord);
						} else {
							result.add(theRecord);
						}
					}
				}

				extDb.releaseDb(db);

			} catch (SQLException se) {
				extDb.releaseDb(db);
				result = null;
				DbConnection.reportError(errors, se);
			}
		}

		// Add normal DTS records if needed.  Properties are altered to make these into true baseline records, however
		// the channel is not changed here.  The baseline channel from the non-DTS record is set in replicateToChannel
		// if that is different from the actual record channel, causing the record to be replicated later.  Note the
		// records found here must be DTS, if they are not, or if the referenced record ID is not found, the original
		// baseline record from the main query above is added to the result instead.

		if ((null != result) && (null != dtsAddMap) && !dtsAddMap.isEmpty()) {

			StringBuilder dtsAddList = new StringBuilder("app_location.aloc_aapp_application_id IN ");
			char sep = '(';
			for (String id : dtsAddMap.keySet()) {
				dtsAddList.append(sep);
				dtsAddList.append('\'');
				dtsAddList.append(id);
				dtsAddList.append('\'');
				sep = ',';
			}
			dtsAddList.append(')');

			LinkedList<ExtDbRecordTV> dtsAdd = findRecordsTV(extDb, dtsAddList.toString(), null, 0., 0., null, errors);
			if (null == dtsAdd) {
				result = null;
			} else {

				ExtDbRecordTV blRecord;

				// Scan records returned from the DTS search, ignore any that are not DTS.  Otherwise get the original
				// baseline record that referred to this DTS, removing the original from the map in the process.  Alter
				// the DTS record to become the baseline record, copying from the original as needed.

				for (ExtDbRecordTV addRecord : dtsAdd) {

					if (!addRecord.service.isDTS) {
						continue;
					}

					blRecord = dtsAddMap.remove(addRecord.extRecordID);
					if (null == blRecord) {
						continue;
					}

					addRecord.extRecordID = blRecord.extRecordID;
					addRecord.isBaseline = blRecord.isBaseline;
					addRecord.facilityID = blRecord.facilityID;
					if (blRecord.channel != addRecord.channel) {
						addRecord.replicateToChannel = blRecord.channel;
					}
					addRecord.status = blRecord.status;
					addRecord.statusType = blRecord.statusType;
					addRecord.filePrefix = blRecord.filePrefix;
					addRecord.appARN = blRecord.appARN;
					addRecord.isArchived = blRecord.isArchived;
					addRecord.isPending = blRecord.isPending;

					// Copy from the parent down to the individual DTS transmitter records.

					for (ExtDbRecordTV dtsRecord : addRecord.dtsRecords) {
						dtsRecord.extRecordID = addRecord.extRecordID;
						dtsRecord.isBaseline = addRecord.isBaseline;
						dtsRecord.facilityID = addRecord.facilityID;
						dtsRecord.replicateToChannel = addRecord.replicateToChannel;
						dtsRecord.status = addRecord.status;
						dtsRecord.statusType = addRecord.statusType;
						dtsRecord.filePrefix = addRecord.filePrefix;
						dtsRecord.appARN = addRecord.appARN;
						dtsRecord.isArchived = addRecord.isArchived;
						dtsRecord.isPending = addRecord.isPending;
					}

					result.add(addRecord);
				}

				// If any records remain in the lookup map the referenced DTS was not found, or the ID retrieved a
				// record that was not DTS and was ignored.  Just add the original baseline records for all of those.

				result.addAll(dtsAddMap.values());
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get an index of baseline channels, return a map of channel number by facility ID, also flags indicating if any
	// records in Canada or Mexico are found in the baseline (that may occur only in LMS, CDBS does not support non-US
	// baseline record).  This assumes the baseline table only contains one record per facility ID (or at least, only
	// one unique channel number per facility ID) which should always be the case.  Returns an empty index if called
	// on a database with no baseline table.  This now also builds a separate index of pre-baseline channels, that is
	// the pre-auction channel from LMS, or the analog channel from CDBS.  However the hasCA and hasMX flags apply only
	// to the baseline index, the pre-baseline may or may not (probably not) contain foreign stations.

	public static class BaselineIndex {
		HashMap<Integer, Integer> index;
		HashMap<Integer, Integer> preIndex;
		boolean hasCA;
		boolean hasMX;
	}

	public static BaselineIndex getBaselineIndex(ExtDb extDb) {
		return getBaselineIndex(extDb, null);
	}

	public static BaselineIndex getBaselineIndex(ExtDb extDb, ErrorLogger errors) {

		BaselineIndex result = new BaselineIndex();
		result.index = new HashMap<Integer, Integer>();
		result.preIndex = new HashMap<Integer, Integer>();

		if (!extDb.hasBaseline()) {
			return result;
		}
		boolean isCDBS = (ExtDb.DB_TYPE_CDBS == extDb.type);

		DbConnection db = extDb.connectDb(errors);
		if (null == db) {
			return null;
		}

		try {

			if (isCDBS) {

				db.query(
				"SELECT " +
					"facility_id, " +
					"post_dtv_channel, " +
					"ntsc_channel " +
				"FROM " +
					"dtv_channel_assignments");

			} else {

				if (ExtDb.DB_TYPE_LMS_LIVE == extDb.type) {

					db.query(
					"SELECT " +
						"(CASE WHEN app_dtv_channel_assignment.adca_facility_record_id = '' THEN 0::INT " +
							"ELSE app_dtv_channel_assignment.adca_facility_record_id::INT END), " +
						"lkp_dtv_allotment.rdta_digital_channel, " +
						"app_dtv_channel_assignment.pre_auction_channel, " +
						"lkp_dtv_allotment.rdta_country_code " +
					"FROM " +
						"mass_media.app_dtv_channel_assignment " +
						"JOIN mass_media.lkp_dtv_allotment ON (lkp_dtv_allotment.rdta_dtv_allotment_id = " +
							"app_dtv_channel_assignment.dtv_allotment_id)");

				} else {

					db.query(
					"SELECT " +
						"app_dtv_channel_assignment.adca_facility_record_id, " +
						"lkp_dtv_allotment.rdta_digital_channel, " +
						"app_dtv_channel_assignment.pre_auction_channel, " +
						"lkp_dtv_allotment.rdta_country_code " +
					"FROM " +
						"app_dtv_channel_assignment " +
						"JOIN lkp_dtv_allotment ON (lkp_dtv_allotment.rdta_dtv_allotment_id = " +
							"app_dtv_channel_assignment.dtv_allotment_id)");
				}
			}

			int facID, chan;
			Country theCountry;

			while (db.next()) {

				facID = db.getInt(1);
				if (facID <= 0) {
					continue;
				}

				chan = db.getInt(2);
				if ((chan >= SourceTV.CHANNEL_MIN) && (chan <= SourceTV.CHANNEL_MAX)) {
					result.index.put(Integer.valueOf(facID), Integer.valueOf(chan));
					if (!isCDBS) {
						theCountry = Country.getCountry(db.getString(4));
						if (null != theCountry) {
							if (Country.CA == theCountry.key) {
								result.hasCA = true;
							}
							if (Country.MX == theCountry.key) {
								result.hasMX = true;
							}
						}
					}
				}

				chan = db.getInt(3);
				if ((chan >= SourceTV.CHANNEL_MIN) && (chan <= SourceTV.CHANNEL_MAX)) {
					result.preIndex.put(Integer.valueOf(facID), Integer.valueOf(chan));
				}
			}

			extDb.releaseDb(db);

		} catch (SQLException se) {
			extDb.releaseDb(db);
			result = null;
			DbConnection.reportError(errors, se);
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Convenience to do a search for a specific record by record ID.  Note a not-found condition is not considered an
	// error here; it is just logged as a message.  The caller can tell if a null return means error or not-found by
	// checking errors.hasErrors().  This recognizes baseline record IDs and uses findBaselineRecords() as needed.

	public static ExtDbRecordTV findRecordTV(String theDbID, Integer extDbKey, String theExtRecordID) {
		return findRecordTV(theDbID, extDbKey, theExtRecordID, null);
	}

	public static ExtDbRecordTV findRecordTV(String theDbID, Integer extDbKey, String theExtRecordID,
			ErrorLogger errors) {
		ExtDb extDb = ExtDb.getExtDb(theDbID, extDbKey, errors);
		if (null == extDb) {
			return null;
		}
		return findRecordTV(extDb, theExtRecordID, errors);
	}

	public static ExtDbRecordTV findRecordTV(ExtDb extDb, String theExtRecordID) {
		return findRecordTV(extDb, theExtRecordID, null);
	}

	public static ExtDbRecordTV findRecordTV(ExtDb extDb, String theExtRecordID, ErrorLogger errors) {

		if (Source.RECORD_TYPE_TV != extDb.recordType) {
			return null;
		}

		boolean isBaseline = false;
		int facilityID = 0;

		if (theExtRecordID.toUpperCase().startsWith(BASELINE_ID_PREFIX)) {
			try {
				facilityID = Integer.parseInt(theExtRecordID.substring(BASELINE_ID_PREFIX.length()));
			} catch (NumberFormatException ne) {
			}
			if (facilityID > 0) {
				isBaseline = true;
			}
		}

		StringBuilder query = new StringBuilder();
		try {
			if (isBaseline) {
				addBaselineFacilityIDQuery(extDb.type, extDb.version, facilityID, query, false);
			} else {
				addRecordIDQueryTV(extDb.type, extDb.version, theExtRecordID, query, false);
			}
		} catch (IllegalArgumentException ie) {
			if (null != errors) {
				errors.logMessage(ie.getMessage());
			}
			return null;
		}

		LinkedList<ExtDbRecordTV> theRecs;
		if (isBaseline) {
			theRecs = findBaselineRecords(extDb, query.toString(), null, 0., 0., false, errors);
		} else {
			theRecs = findRecordsTV(extDb, query.toString(), null, 0., 0., null, errors);
		}
		if (null == theRecs) {
			return null;
		}

		if (theRecs.isEmpty()) {
			if (null != errors) {
				errors.logMessage("Record not found for record ID '" + theExtRecordID + "'.");
			}
			return null;
		}

		return theRecs.getFirst();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Batch search for individual records by ID, used to efficiently find multiple records since individual use of
	// findRecordTV() may have very poor performance.  The IDs may have a mix of normal and baseline IDs so this may
	// involve separate normal and baseline queries.  Combined results are returned in a map keyed by ID.

	public static HashMap<String, ExtDbRecordTV> batchFindRecordTV(ExtDb extDb, HashSet<String> theExtRecordIDs) {
		return batchFindRecordTV(extDb, theExtRecordIDs, null);
	}

	public static HashMap<String, ExtDbRecordTV> batchFindRecordTV(ExtDb extDb, HashSet<String> theExtRecordIDs,
			ErrorLogger errors) {

		HashMap<String, ExtDbRecordTV> result = new HashMap<String, ExtDbRecordTV>();

		if (Source.RECORD_TYPE_TV != extDb.recordType) {
			return result;
		}

		boolean doSearch = false, quote = false;
		StringBuilder query = new StringBuilder();
		String sep = "(";
		if (ExtDb.DB_TYPE_CDBS == extDb.type) {
			query.append("tv_eng_data.application_id IN ");
		} else {
			query.append("UPPER(app_location.aloc_aapp_application_id) IN ");
			quote = true;
			sep = "('";
		}

		boolean doBlSearch = false, blQuote = false;
		StringBuilder blQuery = new StringBuilder();
		String blSep = "(";
		if (ExtDb.DB_TYPE_CDBS == extDb.type) {
			blQuery.append("dtv_channel_assignments.facility_id IN ");
		} else {
			blQuery.append("app_dtv_channel_assignment.adca_facility_record_id IN ");
			if (ExtDb.DB_TYPE_LMS_LIVE == extDb.type) {
				blQuote = true;
				blSep = "('";
			}
		}

		int facilityID, applicationID;

		for (String theID : theExtRecordIDs) {

			theID = theID.toUpperCase();

			facilityID = 0;
			if (theID.startsWith(BASELINE_ID_PREFIX)) {
				try {
					facilityID = Integer.parseInt(theID.substring(BASELINE_ID_PREFIX.length()));
				} catch (NumberFormatException ne) {
				}
			}

			if (facilityID <= 0) {

				if (!quote) {
					applicationID = 0;
					try {
						applicationID = Integer.parseInt(theID);
					} catch (NumberFormatException ne) {
					}
					if (applicationID <= 0) {
						continue;
					}
				}

				query.append(sep);
				query.append(DbConnection.clean(theID));
				if (quote) {
					sep = "','";
				} else {
					sep = ",";
				}

				doSearch = true;

			} else {

				blQuery.append(sep);
				blQuery.append(String.valueOf(facilityID));
				if (blQuote) {
					blSep = "','";
				} else {
					blSep = ",";
				}

				doBlSearch = true;
			}
		}

		if (doSearch) {

			if (quote) {
				query.append("')");
			} else {
				query.append(")");
			}

			LinkedList<ExtDbRecordTV> theRecs = findRecordsTV(extDb, query.toString(), null, 0., 0., null, errors);
			if (null == theRecs) {
				return null;
			}

			for (ExtDbRecordTV theRec : theRecs) {
				result.put(theRec.extRecordID, theRec);
			}
		}

		if (doBlSearch) {

			if (blQuote) {
				blQuery.append("')");
			} else {
				blQuery.append(')');
			}

			LinkedList<ExtDbRecordTV> theRecs = findBaselineRecords(extDb, blQuery.toString(), null, 0., 0., false,
				errors);
			if (null == theRecs) {
				return null;
			}

			for (ExtDbRecordTV theRec : theRecs) {
				result.put(theRec.extRecordID, theRec);
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check if a particular external record ID exists in a data set.  Also recognizes baseline IDs as above.

	public static boolean doesRecordIDExistTV(ExtDb extDb, String theExtRecordID) {

		if (Source.RECORD_TYPE_TV != extDb.recordType) {
			return false;
		}

		boolean isBaseline = false;
		int facilityID = 0;

		if (theExtRecordID.toUpperCase().startsWith(BASELINE_ID_PREFIX)) {
			try {
				facilityID = Integer.parseInt(theExtRecordID.substring(BASELINE_ID_PREFIX.length()));
			} catch (NumberFormatException ne) {
			}
			if (facilityID > 0) {
				isBaseline = true;
			}
		}

		StringBuilder query = new StringBuilder();
		try {
			if (isBaseline) {
				addBaselineFacilityIDQuery(extDb.type, extDb.version, facilityID, query, false);
			} else {
				addRecordIDQueryTV(extDb.type, extDb.version, theExtRecordID, query, false);
			}
		} catch (IllegalArgumentException ie) {
			return false;
		}

		DbConnection db = extDb.connectDb();
		if (null == db) {
			return false;
		}

		boolean result = false;

		try {

			if (isBaseline) {

				if (ExtDb.DB_TYPE_CDBS == extDb.type) {
					db.query("SELECT COUNT(*) FROM dtv_channel_assignments WHERE " + query.toString());
				} else {
					if (ExtDb.DB_TYPE_LMS_LIVE == extDb.type) {
						db.query("SELECT COUNT(*) FROM mass_media.app_dtv_channel_assignment WHERE " +
							query.toString());
					} else {
						db.query("SELECT COUNT(*) FROM app_dtv_channel_assignment WHERE " + query.toString());
					}
				}

			} else {

				if (ExtDb.DB_TYPE_CDBS == extDb.type) {
					db.query("SELECT COUNT(*) FROM tv_eng_data WHERE " + query.toString());
				} else {
					if (ExtDb.DB_TYPE_LMS_LIVE == extDb.type) {
						db.query("SELECT COUNT(*) FROM mass_media.app_location WHERE " + query.toString());
					} else {
						db.query("SELECT COUNT(*) FROM app_location WHERE " + query.toString());
					}
				}
			}

			if (db.next()) {
				result = (db.getInt(1) > 0);
			}

		} catch (SQLException se) {
			db.reportError(se);
		}

		extDb.releaseDb(db);

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Methods to support composing query clauses for findRecords() searching, see superclass for details.  The first
	// method adds a record ID search.  In CDBS or user records the argument is validated as a number > 0 for
	// application ID or user record ID match.

	public static boolean addRecordIDQueryTV(int dbType, int version, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if ((ExtDb.DB_TYPE_CDBS == dbType) || (ExtDb.DB_TYPE_LMS == dbType) || (ExtDb.DB_TYPE_LMS_LIVE == dbType)) {

			if ((null == str) || (0 == str.length())) {
				return false;
			}

			if (ExtDb.DB_TYPE_CDBS == dbType) {
				int theID = 0;
				try {
					theID = Integer.parseInt(str);
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException("The record ID must be a number.");
				}
				if (theID <= 0) {
					throw new IllegalArgumentException("The record ID must be greater than 0.");
				}
			}

			if (combine) {
				query.append(" AND ");
			}

			if (ExtDb.DB_TYPE_CDBS == dbType) {

				query.append("(tv_eng_data.application_id = ");
				query.append(str);
				query.append(')');

			} else {

				query.append("(UPPER(app_location.aloc_aapp_application_id) = '");
				query.append(DbConnection.clean(str.toUpperCase()));
				query.append("')");
			}

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Search by file number.  In CDBS the file prefix and ARN comprising a file number are in separate fields, in LMS
	// legacy CDBS file numbers are in a single field with '-' separating prefix and ARN.  However in user records and
	// all UI, prefix and ARN are combined with no separator.  New LMS numbers are just an ARN with no prefix, those
	// are recognizable because they always start with multiple leading zeros, CDBS ARNs never start with zero (at
	// least not those involved with TV services).  However when new LMS numbers are copied back to CDBS they are
	// assigned a prefix of "BLANK", and the UI here will show that and add it for LMS-derived records as well.  Hence
	// the search input may be a file number string in any valid format, with or without separator, with or without
	// "BLANK" prefix, and is appropriately translated depending on the context.  However since ARNs are actually
	// unique and so sufficient to match in all cases, prefix is always optional in the search input.  Note this is
	// usually called via the superclass method which parses out the search string into prefix and ARN.

	public static boolean addFileNumberQueryTV(int dbType, int version, String prefix, String arn, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if ((ExtDb.DB_TYPE_CDBS == dbType) || (ExtDb.DB_TYPE_LMS == dbType) || (ExtDb.DB_TYPE_LMS_LIVE == dbType)) {

			if (combine) {
				query.append(" AND ");
			}

			if (ExtDb.DB_TYPE_CDBS == dbType) {

				if (prefix.length() > 0) {
					query.append("((UPPER(application.file_prefix) = '");
					query.append(prefix);
					query.append("') AND (UPPER(application.app_arn) = '");
					query.append(arn);
					query.append("'))");
				} else {
					query.append("(UPPER(application.app_arn) = '");
					query.append(arn);
					query.append("')");
				}

			} else {

				if (prefix.length() > 0) {
					query.append("(UPPER(application.aapp_file_num) = '");
					query.append(prefix);
					query.append('-');
					query.append(arn);
				} else {
					if (arn.startsWith("00")) {
						query.append("(UPPER(application.aapp_file_num) = '");
					} else {
						query.append("(UPPER(application.aapp_file_num) LIKE '%-");
					}
					query.append(arn);
				}
				query.append("')");
			}

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query clause to search by facility ID.  The version that takes a string argument is in the superclass.

	public static boolean addFacilityIDQueryTV(int dbType, int version, int facilityID, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if ((ExtDb.DB_TYPE_CDBS == dbType) || (ExtDb.DB_TYPE_LMS == dbType) || (ExtDb.DB_TYPE_LMS_LIVE == dbType)) {

			if (combine) {
				query.append(" AND ");
			}

			if (ExtDb.DB_TYPE_CDBS == dbType) {

				query.append("(tv_eng_data.facility_id = ");
				query.append(String.valueOf(facilityID));
				query.append(')');

			} else {

				query.append("(application_facility.afac_facility_id = ");
				query.append(String.valueOf(facilityID));
				query.append(')');
			}

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query clause to search by service.  For CDBS and LMS, uses code maps loaded from the database at startup,
	// see loadCache() in the superclass.  Note in both cases a given service may map to multiple codes.

	public static boolean addServiceQueryTV(int dbType, int version, int serviceKey, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if ((ExtDb.DB_TYPE_CDBS == dbType) || (ExtDb.DB_TYPE_LMS == dbType) || (ExtDb.DB_TYPE_LMS_LIVE == dbType)) {

			if (combine) {
				query.append(" AND ");
			}

			if (ExtDb.DB_TYPE_CDBS == dbType) {

				query.append("(tv_eng_data.vsd_service IN ");
				String s = "('";

				for (Map.Entry<String, Service> e : cdbsCodeCache.entrySet()) {
					if (e.getValue().key == serviceKey) {
						query.append(s);
						query.append(e.getKey());
						s = "','";
					}
				}

				query.append("'))");

			} else {

				query.append("(license_filing_version.service_code IN ");
				String s = "('";

				for (Map.Entry<String, Service> e : lmsCodeCache.entrySet()) {
					if (e.getValue().key == serviceKey) {
						query.append(s);
						query.append(e.getKey());
						s = "','";
					}
				}

				query.append("'))");
			}

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query clause to search by services matching isOperating and digital flags.

	public static boolean addServiceTypeQueryTV(int dbType, int version, int operatingMatch, int digitalMatch,
			StringBuilder query, boolean combine) throws IllegalArgumentException {

		if ((ExtDb.DB_TYPE_CDBS == dbType) || (ExtDb.DB_TYPE_LMS == dbType) || (ExtDb.DB_TYPE_LMS_LIVE == dbType)) {

			if (combine) {
				query.append(" AND ");
			}

			Service serv;

			if (ExtDb.DB_TYPE_CDBS == dbType) {

				query.append("(tv_eng_data.vsd_service IN ");
				String s = "('";

				for (Map.Entry<String, Service> e : cdbsCodeCache.entrySet()) {
					serv = e.getValue();
					if ((Source.RECORD_TYPE_TV == serv.serviceType.recordType) &&
							((FLAG_MATCH_SET != operatingMatch) || serv.isOperating) &&
							((FLAG_MATCH_CLEAR != operatingMatch) || !serv.isOperating) &&
							((FLAG_MATCH_SET != digitalMatch) || serv.serviceType.digital) &&
							((FLAG_MATCH_CLEAR != digitalMatch) || !serv.serviceType.digital)) {
						query.append(s);
						query.append(e.getKey());
						s = "','";
					}
				}

				query.append("'))");

			} else {

				query.append("(license_filing_version.service_code IN ");
				String s = "('";

				for (Map.Entry<String, Service> e : lmsCodeCache.entrySet()) {
					serv = e.getValue();
					if ((Source.RECORD_TYPE_TV == serv.serviceType.recordType) &&
							((FLAG_MATCH_SET != operatingMatch) || serv.isOperating) &&
							((FLAG_MATCH_CLEAR != operatingMatch) || !serv.isOperating) &&
							((FLAG_MATCH_SET != digitalMatch) || serv.serviceType.digital) &&
							((FLAG_MATCH_CLEAR != digitalMatch) || !serv.serviceType.digital)) {
						query.append(s);
						query.append(e.getKey());
						s = "','";
					}
				}

				query.append("'))");
			}

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query search clause for call sign, case-insensitive head-anchored match.

	public static boolean addCallSignQueryTV(int dbType, int version, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if ((ExtDb.DB_TYPE_CDBS == dbType) || (ExtDb.DB_TYPE_LMS == dbType) || (ExtDb.DB_TYPE_LMS_LIVE == dbType)) {

			if ((null == str) || (0 == str.length())) {
				return false;
			}

			if (combine) {
				query.append(" AND ");
			}

			if (ExtDb.DB_TYPE_CDBS == dbType) {

				query.append("(UPPER(CASE WHEN (facility.fac_callsign <> '') " +
					"THEN facility.fac_callsign ELSE application.fac_callsign END) REGEXP '^D*");
				query.append(DbConnection.clean(str.toUpperCase()));
				query.append(".*')");

			} else {

				if (ExtDb.DB_TYPE_LMS_LIVE == dbType) {
					query.append("(UPPER(CASE WHEN (facility.callsign <> '') " +
						"THEN facility.callsign ELSE application.aapp_callsign END) ~ '^D*");
				} else {
					if (version > 1) {
						query.append("(UPPER(CASE WHEN (facility.callsign <> '') " +
							"THEN facility.callsign ELSE application.aapp_callsign END) REGEXP '^D*");
					} else {
						query.append("(UPPER(application.aapp_callsign) REGEXP '^D*");
					}
				}
				query.append(DbConnection.clean(str.toUpperCase()));
				query.append(".*')");
			}

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query search clause for a specific channel search.  See superclass for argument-checking forms.

	public static boolean addChannelQueryTV(int dbType, int version, int channel, int minimumChannel,
			int maximumChannel, StringBuilder query, boolean combine) throws IllegalArgumentException {

		if ((ExtDb.DB_TYPE_CDBS == dbType) || (ExtDb.DB_TYPE_LMS == dbType) || (ExtDb.DB_TYPE_LMS_LIVE == dbType)) {

			if ((minimumChannel > 0) && (maximumChannel > 0) &&
					((channel < minimumChannel) || (channel > maximumChannel))) {
				throw new IllegalArgumentException("The channel must be in the range " + minimumChannel + " to " +
					maximumChannel + ".");
			}

			if (combine) {
				query.append(" AND ");
			}

			if (ExtDb.DB_TYPE_CDBS == dbType) {

				query.append("(tv_eng_data.station_channel = ");
				query.append(String.valueOf(channel));
				query.append(')');

			} else {

				if (ExtDb.DB_TYPE_LMS_LIVE == dbType) {
					query.append("(CASE WHEN application_facility.afac_channel = '' THEN false " +
						"ELSE (application_facility.afac_channel::INT = ");
					query.append(String.valueOf(channel));
					query.append(") END)");
				} else {
					query.append("(application_facility.afac_channel = ");
					query.append(String.valueOf(channel));
					query.append(')');
				}
			}

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query search clause for a channel range search.  The range arguments are assumed valid.

	public static boolean addChannelRangeQueryTV(int dbType, int version, int minimumChannel, int maximumChannel,
			StringBuilder query, boolean combine) throws IllegalArgumentException {

		if ((ExtDb.DB_TYPE_CDBS == dbType) || (ExtDb.DB_TYPE_LMS == dbType) || (ExtDb.DB_TYPE_LMS_LIVE == dbType)) {

			if (combine) {
				query.append(" AND ");
			}

			if (ExtDb.DB_TYPE_CDBS == dbType) {

				query.append("(tv_eng_data.station_channel BETWEEN ");
				query.append(String.valueOf(minimumChannel));
				query.append(" AND ");
				query.append(String.valueOf(maximumChannel));
				query.append(')');

			} else {

				if (ExtDb.DB_TYPE_LMS_LIVE == dbType) {
					query.append("(CASE WHEN application_facility.afac_channel = '' THEN false " +
						"ELSE (application_facility.afac_channel::INT BETWEEN ");
					query.append(String.valueOf(minimumChannel));
					query.append(" AND ");
					query.append(String.valueOf(maximumChannel));
					query.append(") END)");
				} else {
					query.append("(application_facility.afac_channel BETWEEN ");
					query.append(String.valueOf(minimumChannel));
					query.append(" AND ");
					query.append(String.valueOf(maximumChannel));
					query.append(')');
				}
			}

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query search clause for matching a list of channels.  This is not meant to process direct user input, the
	// argument string is assumed to be a valid SQL value list composed by other code e.g. "(7,8,12,33)", containing
	// valid channel numbers.

	public static boolean addMultipleChannelQueryTV(int dbType, int version, String str, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if ((ExtDb.DB_TYPE_CDBS == dbType) || (ExtDb.DB_TYPE_LMS == dbType) || (ExtDb.DB_TYPE_LMS_LIVE == dbType)) {

			if (combine) {
				query.append(" AND ");
			}

			if (ExtDb.DB_TYPE_CDBS == dbType) {

				query.append("(tv_eng_data.station_channel IN ");
				query.append(str);
				query.append(')');

			} else {

				if (ExtDb.DB_TYPE_LMS_LIVE == dbType) {
					query.append("(CASE WHEN application_facility.afac_channel = '' THEN false " +
						"ELSE (application_facility.afac_channel::INT IN ");
					query.append(str);
					query.append(") END)");
				} else {
					query.append("(application_facility.afac_channel IN ");
					query.append(str);
					query.append(')');
				}
			}

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add a query search clause for status.

	public static boolean addStatusQueryTV(int dbType, int version, int statusType, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if ((ExtDb.DB_TYPE_CDBS == dbType) || (ExtDb.DB_TYPE_LMS == dbType) || (ExtDb.DB_TYPE_LMS_LIVE == dbType)) {

			switch (statusType) {

				case STATUS_TYPE_STA:
				case STATUS_TYPE_CP:
				case STATUS_TYPE_LIC:
				case STATUS_TYPE_APP:
				case STATUS_TYPE_EXP: {
					break;
				}

				default: {
					throw new IllegalArgumentException("Unknown status code.");
				}
			}

			if (combine) {
				query.append(" AND ");
			}

			if (ExtDb.DB_TYPE_CDBS == dbType) {

				if (STATUS_TYPE_EXP == statusType) {

					query.append("(facility.fac_status = 'EXPER')");

				} else {

					if (STATUS_TYPE_STA == statusType) {

						query.append("(UPPER(application.app_type) IN ('STA', 'STAX'))");

					} else {

						query.append("(UPPER(tv_eng_data.tv_dom_status) IN ");
						String s = "('";
						for (int i = 0; i < CDBS_STATUS_TYPES.length; i++) {
							if (CDBS_STATUS_TYPES[i] == statusType) {
								query.append(s);
								query.append(CDBS_STATUS_CODES[i]);
								s = "','";
							}
						}
						query.append("'))");
					}
				}

			} else {

				if (STATUS_TYPE_EXP == statusType) {

					if (version > 1) {
						query.append("(facility.facility_status = 'EXPER')");
					} else {
						query.append("(false)");
					}

				} else {

					if (STATUS_TYPE_STA == statusType) {

						query.append("(UPPER(license_filing_version.auth_type_code) = 'S')");

					} else {

						if (STATUS_TYPE_APP == statusType) {
							query.append("((UPPER(license_filing_version.current_status_code) IN ");
						} else {
							query.append("((UPPER(license_filing_version.current_status_code) NOT IN ");
						}
						String s = "('";
						for (int i = 0; i < LMS_APP_STATUS_CODES.length; i++) {
							query.append(s);
							query.append(LMS_APP_STATUS_CODES[i]);
							s = "','";
						}
						query.append("'))");

						if (STATUS_TYPE_APP == statusType) {
							query.append(')');
						} else {
							if (STATUS_TYPE_CP == statusType) {
								query.append(" AND (UPPER(license_filing_version.auth_type_code) = 'C'))");
							} else {
								if (STATUS_TYPE_LIC == statusType) {
									query.append(" AND (UPPER(license_filing_version.auth_type_code) = 'L'))");
								}
							}
						}
					}
				}
			}

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add query clause for city name search, case-insensitive unanchored match, '*' wildcards are allowed.

	public static boolean addCityQueryTV(int dbType, int version, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if ((ExtDb.DB_TYPE_CDBS == dbType) || (ExtDb.DB_TYPE_LMS == dbType) || (ExtDb.DB_TYPE_LMS_LIVE == dbType)) {

			if ((null == str) || (0 == str.length())) {
				return false;
			}

			if (combine) {
				query.append(" AND ");
			}

			if (ExtDb.DB_TYPE_CDBS == dbType) {

				query.append("(UPPER(CASE WHEN (facility.comm_city <> '') " +
					"THEN facility.comm_city ELSE application.comm_city END) LIKE '%");
				query.append(DbConnection.clean(str.toUpperCase()).replace('*', '%'));
				query.append("%')");

			} else {

				if (version > 1) {
					query.append("(UPPER(CASE WHEN (facility.community_served_city <> '') " +
						"THEN facility.community_served_city " +
						"ELSE application_facility.afac_community_city END) LIKE '%");
				} else {
					query.append("(UPPER(application_facility.afac_community_city) LIKE '%");
				}
				query.append(DbConnection.clean(str.toUpperCase()).replace('*', '%'));
				query.append("%')");
			}

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add query clause for state code search, this is an exact string match but still case-insensitive.

	public static boolean addStateQueryTV(int dbType, int version, String str, StringBuilder query, boolean combine)
			throws IllegalArgumentException {

		if ((ExtDb.DB_TYPE_CDBS == dbType) || (ExtDb.DB_TYPE_LMS == dbType) || (ExtDb.DB_TYPE_LMS_LIVE == dbType)) {

			if ((null == str) || (0 == str.length())) {
				return false;
			}

			if (combine) {
				query.append(" AND ");
			}

			if (ExtDb.DB_TYPE_CDBS == dbType) {

				query.append("(UPPER(CASE WHEN (facility.comm_state <> '') " +
					"THEN facility.comm_state ELSE application.comm_state END) = '");
				query.append(DbConnection.clean(str.toUpperCase()));
				query.append("')");

			} else {

				if (version > 1) {
					query.append("(UPPER(CASE WHEN (facility.community_served_state <> '') " +
						"THEN facility.community_served_state " +
						"ELSE application_facility.afac_community_state_code END) = '");
				} else {
					query.append("(UPPER(application_facility.afac_community_state_code) = '");
				}
				query.append(DbConnection.clean(str.toUpperCase()));
				query.append("')");
			}

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add clause for country search.  See superclass for forms that resolve string and key arguments.

	public static boolean addCountryQueryTV(int dbType, int version, Country country, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if ((ExtDb.DB_TYPE_CDBS == dbType) || (ExtDb.DB_TYPE_LMS == dbType) || (ExtDb.DB_TYPE_LMS_LIVE == dbType)) {

			if (combine) {
				query.append(" AND ");
			}

			if (ExtDb.DB_TYPE_CDBS == dbType) {

				query.append("(UPPER(facility.fac_country) = '");
				query.append(country.countryCode);
				query.append("')");

			} else {

				query.append("(UPPER(application_facility.country_code) = '");
				query.append(country.countryCode);
				query.append("')");
			}

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add record type search query clause.  Current records are always included, pending and archived records may
	// optionally be included.  Any unknown record types are treated as current, so this excludes types not desired
	// rather than including those desired.

	public static boolean addRecordTypeQueryTV(int dbType, int version, boolean includePending,
			boolean includeArchived, StringBuilder query, boolean combine) {

		if ((ExtDb.DB_TYPE_CDBS == dbType) || (ExtDb.DB_TYPE_LMS == dbType) || (ExtDb.DB_TYPE_LMS_LIVE == dbType)) {

			if (combine) {
				query.append(" AND ");
			}

			if (ExtDb.DB_TYPE_CDBS == dbType) {

				if (!includePending) {
					if (!includeArchived) {
						query.append("((tv_eng_data.eng_record_type NOT IN ('P','A','R')) AND ");
						query.append("(facility.fac_status <> 'FVOID'))");
					} else {
						query.append("(tv_eng_data.eng_record_type <> 'P')");
					}
				} else {
					if (!includeArchived) {
						query.append("((tv_eng_data.eng_record_type NOT IN ('A','R')) AND ");
						query.append("(facility.fac_status <> 'FVOID'))");
					} else {
						query.append("(true)");
					}
				}

			} else {

				if (!includePending) {
					if (!includeArchived) {
						query.append("((license_filing_version.active_ind = 'Y') AND ");
						query.append("(application.dts_reference_ind <> 'R') AND ");
						query.append("(license_filing_version.current_status_code <> 'PEN')");
						if (version > 1) {
							query.append(" AND (facility.facility_status <> 'FVOID')");
						}
						query.append(')');
					} else {
						query.append("(license_filing_version.current_status_code <> 'PEN')");
					}
				} else {
					if (!includeArchived) {
						query.append("((license_filing_version.active_ind = 'Y') AND ");
						query.append("(application.dts_reference_ind <> 'R')");
						if (version > 1) {
							query.append(" AND (facility.facility_status <> 'FVOID')");
						}
						query.append(')');
					} else {
						query.append("(true)");
					}
				}
			}

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set of query-building methods for searching the baseline tables.  Record ID is just a facility ID with prefix.

	public static boolean addBaselineRecordIDQuery(int dbType, int version, String str, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if ((ExtDb.DB_TYPE_CDBS == dbType) || (ExtDb.DB_TYPE_LMS == dbType) || (ExtDb.DB_TYPE_LMS_LIVE == dbType)) {

			if ((null == str) || (0 == str.length())) {
				return false;
			}

			if (str.toUpperCase().startsWith(BASELINE_ID_PREFIX)) {
				str = str.substring(BASELINE_ID_PREFIX.length());
			}
			int facilityID = 0;
			try {
				facilityID = Integer.parseInt(str);
			} catch (NumberFormatException ne) {
			}
			if (facilityID <= 0) {
				throw new IllegalArgumentException("Invalid baseline record ID.");
			}

			return addBaselineFacilityIDQuery(dbType, version, facilityID, query, combine);
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static boolean addBaselineFacilityIDQuery(int dbType, int version, String str, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if ((null == str) || (0 == str.length())) {
			return false;
		}

		int facilityID = 0;
		try {
			facilityID = Integer.parseInt(str);
		} catch (NumberFormatException ne) {
			throw new IllegalArgumentException("The facility ID must be a number.");
		}

		return addBaselineFacilityIDQuery(dbType, version, facilityID, query, combine);
	}

	public static boolean addBaselineFacilityIDQuery(int dbType, int version, int facilityID, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if ((ExtDb.DB_TYPE_CDBS == dbType) || (ExtDb.DB_TYPE_LMS == dbType) || (ExtDb.DB_TYPE_LMS_LIVE == dbType)) {

			if (combine) {
				query.append(" AND ");
			}

			// Baseline records cannot have a facility ID less than 1, even if such a record appears in a data set it
			// must be ignored.  But since zero and negative IDs are legal in other contexts, this does not return an
			// error, it just causes the query to always return no result.

			if (facilityID <= 0) {
				query.append("(false)");
				return true;
			}

			if (ExtDb.DB_TYPE_CDBS == dbType) {

				query.append("(dtv_channel_assignments.facility_id = ");
				query.append(String.valueOf(facilityID));
				query.append(')');

			} else {

				if (ExtDb.DB_TYPE_LMS_LIVE == dbType) {
					query.append("(CASE WHEN app_dtv_channel_assignment.adca_facility_record_id = '' THEN false " +
						"ELSE (app_dtv_channel_assignment.adca_facility_record_id::INT = ");
					query.append(String.valueOf(facilityID));
					query.append(") END)");
				} else {
					query.append("(app_dtv_channel_assignment.adca_facility_record_id = ");
					query.append(String.valueOf(facilityID));
					query.append(')');
				}
			}

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Prior to LMS version 3 baseline tables had no service field, all records were full-service DTV.  After that,
	// DTV and Class A may both appear in baseline, but some DTV records are actually DTS; the baseline table cannot
	// directly contain a DTS record, so it is done by referencing a DTS record from the main tables by ID.

	public static boolean addBaselineServiceQuery(int dbType, int version, int serviceKey, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if ((ExtDb.DB_TYPE_CDBS == dbType) || (ExtDb.DB_TYPE_LMS == dbType) || (ExtDb.DB_TYPE_LMS_LIVE == dbType)) {

			if (combine) {
				query.append(" AND ");
			}

			Service theServ = Service.getService(serviceKey);

			if ((ExtDb.DB_TYPE_CDBS == dbType) || (version < 3)) {

				if ((null != theServ) && theServ.serviceCode.equals("DT")) {
					query.append("(true)");
				} else {
					query.append("(false)");
				}

			} else {

				if ((null != theServ) && theServ.isDTS) {

					query.append("(lkp_dtv_allotment.dts_ref_application_id <> '')");

				} else {

					query.append("((lkp_dtv_allotment.rdta_service_code IN ");
					String s = "('";

					for (Map.Entry<String, Service> e : lmsCodeCache.entrySet()) {
						if (e.getValue().key == serviceKey) {
							query.append(s);
							query.append(e.getKey());
							s = "','";
						}
					}

					query.append("')) AND (lkp_dtv_allotment.dts_ref_application_id = ''))");
				}
			}

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static boolean addBaselineCallSignQuery(int dbType, int version, String str, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if ((ExtDb.DB_TYPE_CDBS == dbType) || (ExtDb.DB_TYPE_LMS == dbType) || (ExtDb.DB_TYPE_LMS_LIVE == dbType)) {

			if ((null == str) || (0 == str.length())) {
				return false;
			}

			if (combine) {
				query.append(" AND ");
			}

			if (ExtDb.DB_TYPE_CDBS == dbType) {

				query.append("(UPPER(CASE WHEN (facility.fac_callsign <> '') " +
					"THEN facility.fac_callsign ELSE dtv_channel_assignments.callsign END) REGEXP '^D*");
				query.append(DbConnection.clean(str.toUpperCase()));
				query.append(".*')");

			} else {

				if (ExtDb.DB_TYPE_LMS_LIVE == dbType) {
					query.append("(UPPER(CASE WHEN (facility.callsign <> '') " +
						"THEN facility.callsign ELSE app_dtv_channel_assignment.callsign END) ~ '^D*");
				} else {
					query.append("(UPPER(CASE WHEN (facility.callsign <> '') " +
						"THEN facility.callsign ELSE app_dtv_channel_assignment.callsign END) REGEXP '^D*");
				}
				query.append(DbConnection.clean(str.toUpperCase()));
				query.append(".*')");
			}

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static boolean addBaselineChannelQuery(int dbType, int version, String str, int minimumChannel,
			int maximumChannel, StringBuilder query, boolean combine) throws IllegalArgumentException {

		if ((ExtDb.DB_TYPE_CDBS == dbType) || (ExtDb.DB_TYPE_LMS == dbType) || (ExtDb.DB_TYPE_LMS_LIVE == dbType)) {

			if ((null == str) || (0 == str.length())) {
				return false;
			}

			int channel = 0;
			try {
				channel = Integer.parseInt(str);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("The channel must be a number.");
			}
			if ((minimumChannel > 0) && (maximumChannel > 0) &&
					((channel < minimumChannel) || (channel > maximumChannel))) {
				throw new IllegalArgumentException("The channel must be in the range " + minimumChannel + " to " +
					maximumChannel + ".");
			}

			return addBaselineChannelQuery(dbType, version, channel, query, combine);
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static boolean addBaselineChannelQuery(int dbType, int version, int channel, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if ((ExtDb.DB_TYPE_CDBS == dbType) || (ExtDb.DB_TYPE_LMS == dbType) || (ExtDb.DB_TYPE_LMS_LIVE == dbType)) {

			if (combine) {
				query.append(" AND ");
			}

			if (ExtDb.DB_TYPE_CDBS == dbType) {

				query.append("(dtv_channel_assignments.post_dtv_channel = ");
				query.append(String.valueOf(channel));
				query.append(')');

			} else {

				query.append("(lkp_dtv_allotment.rdta_digital_channel = ");
				query.append(String.valueOf(channel));
				query.append(')');
			}

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static boolean addBaselineChannelRangeQuery(int dbType, int version, int minimumChannel, int maximumChannel,
			StringBuilder query, boolean combine) throws IllegalArgumentException {

		if ((ExtDb.DB_TYPE_CDBS == dbType) || (ExtDb.DB_TYPE_LMS == dbType) || (ExtDb.DB_TYPE_LMS_LIVE == dbType)) {

			if (combine) {
				query.append(" AND ");
			}

			if (ExtDb.DB_TYPE_CDBS == dbType) {

				query.append("(dtv_channel_assignments.post_dtv_channel BETWEEN ");
				query.append(String.valueOf(minimumChannel));
				query.append(" AND ");
				query.append(String.valueOf(maximumChannel));
				query.append(')');

			} else {

				query.append("(lkp_dtv_allotment.rdta_digital_channel BETWEEN ");
				query.append(String.valueOf(minimumChannel));
				query.append(" AND ");
				query.append(String.valueOf(maximumChannel));
				query.append(')');
			}

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static boolean addBaselineMultipleChannelQuery(int dbType, int version, String str, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if ((ExtDb.DB_TYPE_CDBS == dbType) || (ExtDb.DB_TYPE_LMS == dbType) || (ExtDb.DB_TYPE_LMS_LIVE == dbType)) {

			if (combine) {
				query.append(" AND ");
			}

			if (ExtDb.DB_TYPE_CDBS == dbType) {

				query.append("(dtv_channel_assignments.post_dtv_channel IN ");
				query.append(str);
				query.append(')');

			} else {

				query.append("(lkp_dtv_allotment.rdta_digital_channel IN ");
				query.append(str);
				query.append(')');
			}

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static boolean addBaselineCityQuery(int dbType, int version, String str, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if ((ExtDb.DB_TYPE_CDBS == dbType) || (ExtDb.DB_TYPE_LMS == dbType) || (ExtDb.DB_TYPE_LMS_LIVE == dbType)) {

			if ((null == str) || (0 == str.length())) {
				return false;
			}

			if (combine) {
				query.append(" AND ");
			}

			if (ExtDb.DB_TYPE_CDBS == dbType) {

				query.append("(UPPER(CASE WHEN (facility.comm_city <> '') " +
					"THEN facility.comm_city ELSE dtv_channel_assignments.city END) LIKE '%");
				query.append(DbConnection.clean(str.toUpperCase()).replace('*', '%'));
				query.append("%')");

			} else {

				query.append("(UPPER(CASE WHEN (facility.community_served_city <> '' ) " +
					"THEN facility.community_served_city ELSE lkp_dtv_allotment.rdta_city END) LIKE '%");
				query.append(DbConnection.clean(str.toUpperCase()).replace('*', '%'));
				query.append("%')");
			}

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static boolean addBaselineStateQuery(int dbType, int version, String str, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if ((ExtDb.DB_TYPE_CDBS == dbType) || (ExtDb.DB_TYPE_LMS == dbType) || (ExtDb.DB_TYPE_LMS_LIVE == dbType)) {

			if ((null == str) || (0 == str.length())) {
				return false;
			}

			if (combine) {
				query.append(" AND ");
			}

			if (ExtDb.DB_TYPE_CDBS == dbType) {

				query.append("(UPPER(CASE WHEN (facility.comm_state <> '') " +
					"THEN facility.comm_state ELSE dtv_channel_assignments.state END) = '");
				query.append(DbConnection.clean(str.toUpperCase()));
				query.append("')");

			} else {

				query.append("(UPPER(CASE WHEN (facility.community_served_state <> '' ) " +
					"THEN facility.community_served_state ELSE lkp_dtv_allotment.rdta_state END) = '");
				query.append(DbConnection.clean(str.toUpperCase()));
				query.append("')");
			}

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Prior to LMS version 3 baseline tables had no country field, all records were US.

	public static boolean addBaselineCountryQuery(int dbType, int version, int countryKey, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		Country country = Country.getCountry(countryKey);
		if (null == country) {
			throw new IllegalArgumentException("Unknown country key.");
		}

		return addBaselineCountryQuery(dbType, version, country, query, combine);
	}

	public static boolean addBaselineCountryQuery(int dbType, int version, Country country, StringBuilder query,
			boolean combine) throws IllegalArgumentException {

		if ((ExtDb.DB_TYPE_CDBS == dbType) || (ExtDb.DB_TYPE_LMS == dbType) || (ExtDb.DB_TYPE_LMS_LIVE == dbType)) {

			if (combine) {
				query.append(" AND ");
			}

			if ((ExtDb.DB_TYPE_CDBS == dbType) || (version < 3)) {

				if (Country.US == country.key) {
					query.append("(true)");
				} else {
					query.append("(false)");
				}

			} else {

				query.append("(UPPER(lkp_dtv_allotment.rdta_country_code) = '");
				query.append(country.countryCode);
				query.append("')");
			}

			return true;
		}

		throw new IllegalArgumentException(BAD_TYPE_MESSAGE);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add records to a scenario using a station data search, this may add desireds, protecteds, or undesireds.  The
	// search criteria are passed on to findRecords(), those typically come from a search UI.  Not all records found
	// in the search will necessarily be added.  This will perform checks for mutually-exclusive records, both among
	// the records in the search result and in that list versus existing sources in the scenario.  When MX records are
	// found, preferred-record logic is used to select just one of the records.  When new records are MX to existing
	// sources the existing source is always preferred.  However if disableMX is true, MX checks are disabled and the
	// check excludes only exact duplicates.  If MX checks are not disabled the preferOperating flag applies to the MX
	// selection logic, if true operating facilities are preferred in the MX logic, see isPreferredRecord().  For a
	// desireds or protecteds search all records are flagged as desired; for an undesireds search the new records are
	// never desireds.  For desireds or protecteds searches the undesired flag is usually set based on the setUndesired
	// argument, for an undesireds search the undesired flag is always set.  However in any search type if MX checks
	// are disabled undesired is usually not set because the user must manually select undesired records in that case
	// (but some study types may still have undesired set for MX records, see below).  For protecteds and undesireds
	// searches, the results are also filtered using channel and maximum distance relationships from interference rules
	// applied to undesired or desired sources already in the scenario; there must be some or an error occurs.  Returns
	// the count of records added, which may be 0, or -1 on error.

	private static class SearchDelta {
		int delta;
		boolean analogOnly;
		double maximumDistance;
	}

	public static int addRecords(ExtDb extDb, boolean useBaseline, ScenarioEditData scenario, int searchType,
			String query, GeoPoint searchCenter, double searchRadius, int minimumChannel, int maximumChannel,
			boolean disableMX, boolean preferOperating, boolean setUndesired, ErrorLogger errors) {

		// Check data set, record, study, and search type for a valid combination.  Desired and protected searches are
		// only allowed in general-purpose TV studies; other study types have only one desired TV record per scenario
		// which is added when the scenario is created.  These are not errors, just return that no records were added.

		int studyType = scenario.study.study.studyType;
		if (extDb.isGeneric() || (Source.RECORD_TYPE_TV != extDb.recordType) ||
				!Study.isRecordTypeAllowed(studyType, Source.RECORD_TYPE_TV) ||
				(((ExtDbSearch.SEARCH_TYPE_DESIREDS == searchType) ||
					(ExtDbSearch.SEARCH_TYPE_PROTECTEDS == searchType)) &&
					(Study.STUDY_TYPE_TV != studyType))) {
			return 0;
		}

		// Get study parameters.

		double coChanMX = scenario.study.getCoChannelMxDistance();
		double kmPerDeg = scenario.study.getKilometersPerDegree();
		boolean checkDTSDist = scenario.study.getCheckIndividualDTSDistance();

		int minChannel = scenario.study.getMinimumChannel();
		int maxChannel = scenario.study.getMaximumChannel();

		// Adjust channel range per arguments.  If the result is a degenerate range, return 0.

		if (minimumChannel > minChannel) {
			minChannel = minimumChannel;
		}
		if ((maximumChannel > 0) && (maximumChannel < maxChannel)) {
			maxChannel = maximumChannel;
		}
		if (minChannel > maxChannel) {
			return 0;
		}

		// Begin building the query string.

		StringBuilder q = new StringBuilder();
		boolean hasCrit = false;
		if ((null != query) && (query.length() > 0)) {
			q.append(query);
			hasCrit = true;
		}

		ArrayList<SourceEditData> theSources = null;
		Collection<SearchDelta> deltas = null;

		// For a desireds search, just apply the channel range restriction.

		if (ExtDbSearch.SEARCH_TYPE_DESIREDS == searchType) {

			try {
				if (useBaseline) {
					addBaselineChannelRangeQuery(extDb.type, extDb.version, minChannel, maxChannel, q, hasCrit);
				} else {
					addChannelRangeQueryTV(extDb.type, extDb.version, minChannel, maxChannel, q, hasCrit);
				}
			} catch (IllegalArgumentException ie) {
			}

		// For a protecteds or undesireds search, get the list of existing undesireds or desireds in the scenario,
		// make sure it's not empty.  That's an error, caller should pre-check that.

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

			// Scan the interference rules and build a list of channel deltas and maximum distances.  The search will
			// just use these deltas and distances to cull results.  Applying the actual rules is generally a waste of
			// effort since the rule distances usually don't vary between different rules using the same channel delta.
			// But if they do this simplification would just include a few extra records that don't actually match a
			// rule, which is harmless since the study engine always strictly applies the rules and ignores records
			// that don't match.  Also the distance check here is a worst-case anyway because it uses an estimate of
			// the service area extent for each desired, the engine will use the actual service area extent which
			// should always be smaller than the worst-case estimate.

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

			// Build a list of all channels to be considered in the search; the distance limit has to be checked in the
			// code here, the query can't handle that, so this gets all records on each channel.  Stop looking once all
			// channels are selected.  Note channel 37 is not specifically excluded; there should never be any records
			// on that channel, but if there are for some reason, they will appear in the search.  Note also this does
			// not consider the analog-only flag for the channel deltas; doing so would complicate the query because a
			// protecteds search would need separate channel lists for digital services vs. analog.  So the query will
			// return all records on all channels, then the analog-only restriction is checked in code below.

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

					// Rules don't apply across the channel 4-5, 6-7, and 13-14 gaps; the logic for this is embedded
					// in the study engine code, not reflected in the rule definitions themselves.  Note this does not
					// exclude channel deltas greater than one for channels 2-13; the rule editor and study engine
					// allow rules to be defined for those deltas as long as other conditions are met.

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

			// If no channels were selected the rule list must have been empty; not considered an error.

			if (0 == numChans) {
				return 0;
			}

			// Add the channel list to the WHERE clause for the query, if all channels selected used the full range.

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
					if (useBaseline) {
						addBaselineMultipleChannelQuery(extDb.type, extDb.version, chanList.toString(), q, hasCrit);
					} else {
						addMultipleChannelQueryTV(extDb.type, extDb.version, chanList.toString(), q, hasCrit);
					}
				} catch (IllegalArgumentException ie) {
				}

			} else {

				try {
					if (useBaseline) {
						addBaselineChannelRangeQuery(extDb.type, extDb.version, minChannel, maxChannel, q, hasCrit);
					} else {
						addChannelRangeQueryTV(extDb.type, extDb.version, minChannel, maxChannel, q, hasCrit);
					}
				} catch (IllegalArgumentException ie) {
				}
			}
		}

		// Run the query.

		LinkedList<ExtDbRecordTV> records = null;
		if (useBaseline) {
			records = findBaselineRecords(extDb, q.toString(), searchCenter, searchRadius, kmPerDeg, false, errors);
		} else {
			records = findRecordsTV(extDb, q.toString(), searchCenter, searchRadius, kmPerDeg, null, errors);
		}

		// An empty result is not an error.

		if (null == records) {
			return -1;
		}
		if (records.isEmpty()) {
			return 0;
		}

		// Scan the records for mutually-exclusive pairs of records and pick one over the other, see comments in
		// removeAllMX().

		removeAllMX(scenario, records, disableMX, preferOperating, coChanMX, kmPerDeg);

		// Special case for TVIX studies (must be an undesireds search due to earlier checks), records MX to the
		// protected record being studied by this scenario, or MX to the proposal record being evaluated by the study,
		// are never added to a scenario regardless of other flags.

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
			ListIterator<ExtDbRecordTV> lit = records.listIterator(0);
			ExtDbRecordTV theRecord;
			while (lit.hasNext()) {
				theRecord = lit.next();
				if (((null != protectedSource) && areRecordsMX(theRecord, protectedSource, kmPerDeg)) ||
						((null != proposalSource) && areRecordsMX(theRecord, proposalSource, kmPerDeg))) {
					lit.remove();
				}
			}
		}

		// For a protecteds or undesireds search now that all potential new records have been found applying the MX
		// logic, make another pass to eliminate any new records that are outside the maximum distance limits from
		// the rules.  Records that request automatic replication are considered on their replicated channel.

		if (ExtDbSearch.SEARCH_TYPE_DESIREDS != searchType) {

			ListIterator<ExtDbRecordTV> lit = records.listIterator(0);
			ExtDbRecordTV theRecord;
			SourceEditDataTV theSource;
			boolean remove, isDigital;
			int desChan, undChan, chanDelt;
			double checkDist;

			while (lit.hasNext()) {

				theRecord = lit.next();
				remove = true;

				for (SourceEditData aSource : theSources) {
					theSource = (SourceEditDataTV)aSource;

					if (ExtDbSearch.SEARCH_TYPE_PROTECTEDS == searchType) {
						desChan = (theRecord.replicateToChannel > 0) ? theRecord.replicateToChannel :
							theRecord.channel;
						undChan = theSource.channel;
						isDigital = theRecord.service.serviceType.digital;
					} else {
						desChan = theSource.channel;
						undChan = (theRecord.replicateToChannel > 0) ? theRecord.replicateToChannel :
							theRecord.channel;
						isDigital = theSource.service.serviceType.digital;
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

					chanDelt = undChan - desChan;

					for (SearchDelta theDelta : deltas) {

						if (theDelta.delta != chanDelt) {
							continue;
						}

						if (theDelta.analogOnly && isDigital) {
							continue;
						}

						// When the undesired is a DTS, the distance checked is normally to the DTS reference point
						// (the parent source coordinates).  However a parameter option may select an alternate method
						// where each individual undesired DTS transmitter will be checked against the station-to-cell
						// distance limits in the study engine.  In that case, the rough-cut check being done here has
						// to check each undesired DTS transmitter and make the decision on the shortest distance from
						// the desired.  When the desired is a DTS, all of its individual transmitters are always
						// checked, with the decision based on the shortest distance found.  However neither the
						// reference facility (site number 0) nor the DTS reference point and radius are checked for
						// the desired.  A parameter will determine whether coverage extends to all individual contours
						// or truncates at the union of the reference facility contour and the reference point and
						// radius.  However whether that option is on or off, coverage still can never exist outside
						// the individual transmitter contours, hence always checking the individual transmitters means
						// undesireds will be included sufficient for either setting of the truncate option; at worst
						// there are a few extra undesireds not really needed, which is harmless.  In all cases the
						// check distance is the rule maximum for the channel delta plus the individual rule extra
						// distance for the desired source being checked.  Due to the different DTS structure in
						// ExtDbRecordTV vs. SourceEditDataTV, this has to branch first on a protecteds vs. an
						// undesireds search type since the object classes are opposite.

						if (ExtDbSearch.SEARCH_TYPE_PROTECTEDS == searchType) {

							if (theRecord.service.isDTS) {
								for (ExtDbRecordTV dtsRecord : theRecord.dtsRecords) {
									checkDist = theDelta.maximumDistance +
										dtsRecord.getRuleExtraDistance(scenario.study);
									if (theSource.isParent && checkDTSDist) {
										for (SourceEditDataTV dtsSource : theSource.getDTSSources()) {
											if (dtsSource.siteNumber > 0) {
												if (dtsRecord.location.distanceTo(dtsSource.location, kmPerDeg) <=
														checkDist) {
													remove = false;
													break;
												}
											}
										}
										if (!remove) {
											break;
										}
									} else {
										if (dtsRecord.location.distanceTo(theSource.location, kmPerDeg) <= checkDist) {
											remove = false;
											break;
										}
									}
								}
							} else {
								checkDist = theDelta.maximumDistance +
									theRecord.getRuleExtraDistance(scenario.study);
								if (theSource.isParent && checkDTSDist) {
									for (SourceEditDataTV dtsSource : theSource.getDTSSources()) {
										if (theRecord.location.distanceTo(dtsSource.location, kmPerDeg) <= checkDist) {
											remove = false;
											break;
										}
									}
								} else {
									if (theRecord.location.distanceTo(theSource.location, kmPerDeg) <= checkDist) {
										remove = false;
									}
								}
							}

						} else {

							if (theSource.isParent) {
								for (SourceEditDataTV dtsSource : theSource.getDTSSources()) {
									if (dtsSource.siteNumber > 0) {
										checkDist = theDelta.maximumDistance + dtsSource.getRuleExtraDistance();
										if (theRecord.service.isDTS && checkDTSDist) {
											for (ExtDbRecordTV dtsRecord : theRecord.dtsRecords) {
												if (dtsSource.location.distanceTo(dtsRecord.location, kmPerDeg) <=
														checkDist) {
													remove = false;
													break;
												}
											}
											if (!remove) {
												break;
											}
										} else {
											if (dtsSource.location.distanceTo(theRecord.location, kmPerDeg) <=
													checkDist) {
												remove = false;
												break;
											}
										}
									}
								}
							} else {
								checkDist = theDelta.maximumDistance + theSource.getRuleExtraDistance();
								if (theRecord.service.isDTS && checkDTSDist) {
									for (ExtDbRecordTV dtsRecord : theRecord.dtsRecords) {
										if (theSource.location.distanceTo(dtsRecord.location, kmPerDeg) <= checkDist) {
											remove = false;
											break;
										}
									}
								} else {
									if (theSource.location.distanceTo(theRecord.location, kmPerDeg) <= checkDist) {
										remove = false;
									}
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

		// Add the new records to the scenario.  If some fail to load properly in makeSource() but no error is reported
		// just ignore and continue; if an error is reported, immediately abort.  Make sure if an error does occur,
		// none of the new records are added.  See makeSource() for details of error reporting vs. message logging.
		// A baseline search may return records flagged for automatic replication, that has to be supported here.

		SourceEditData newSource;
		SourceEditDataTV originalSource;
		ArrayList<SourceEditData> newSources = new ArrayList<SourceEditData>();

		if (null != errors) {
			errors.clearErrors();
		} else {
			errors = new ErrorLogger(null, null);
		}

		for (ExtDbRecordTV theRecord : records) {
			newSource = scenario.study.findSharedSource(theRecord.extDb.key, theRecord.extRecordID);
			if (null == newSource) {
				newSource = SourceEditData.makeSource(theRecord, scenario.study, true, errors);
				if (null == newSource) {
					if (errors.hasErrors()) {
						return -1;
					}
					continue;
				}
			}
			if (theRecord.replicateToChannel > 0) {
				originalSource = (SourceEditDataTV)newSource;
				newSource = scenario.study.findSharedReplicationSource(theRecord.extDb.key, theRecord.extRecordID,
					theRecord.replicateToChannel);
				if (null == newSource) {
					newSource = originalSource.replicate(theRecord.replicateToChannel, errors);
				}
				if (null == newSource) {
					if (errors.hasErrors()) {
						return -1;
					}
					continue;
				}
				scenario.study.addOrReplaceSource(originalSource);
			}
			newSources.add(newSource);
		}

		// For a desireds or protecteds search, the desired flag is always set and the undesired flag will follow the
		// setUndesired argument.  For an undesireds search, desired is not set and undesired is set regardless of the
		// argument.  Except in either case if MX tests are disabled, usually undesired is not set so the records are
		// inactive until the user manually chooses which ones to study.  However the exception to the exception is
		// an undesireds search in an interference-check study, the scenarios are "templates" that are used to build
		// other scenarios and MX groups of flagged undesireds are expected.

		boolean isDesired = true, isUndesired = true;
		if ((ExtDbSearch.SEARCH_TYPE_DESIREDS == searchType) ||	(ExtDbSearch.SEARCH_TYPE_PROTECTEDS == searchType)) {
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
	// Check a list of new records from a search for MX relationships and remove records so only one remains from any
	// pair that are MX.  First, if a record from the search is MX to any source already in the scenario, the new
	// record is always ignored and the existing is not changed.  If disableMX is true this only removes identical
	// records already in the scenario.

	private static void removeAllMX(ScenarioEditData scenario, LinkedList<ExtDbRecordTV> records, boolean disableMX,
			boolean preferOperating, double coChanMX, double kmPerDeg) {

		ArrayList<SourceEditData> theSources = scenario.sourceData.getSources(Source.RECORD_TYPE_TV);

		ListIterator<ExtDbRecordTV> lit = records.listIterator(0);
		ExtDbRecordTV theRecord;
		SourceEditDataTV theSource;

		while (lit.hasNext()) {
			theRecord = lit.next();
			for (SourceEditData aSource : theSources) {
				theSource = (SourceEditDataTV)aSource;
				if (theRecord.extRecordID.equals(theSource.extRecordID) ||
						(!disableMX && areRecordsMX(theRecord, theSource, coChanMX, kmPerDeg))) {
					lit.remove();
					break;
				}
			}
		}

		if (disableMX) {
			return;
		}

		// Check remaining records for MX pairs and pick one using isPreferredRecord().  Some of the MX tests are not
		// transitive, so to ensure deterministic results the list is sorted using ExtDbRecord.isPreferredRecord(),
		// moving higher-priority records to the top regardless of MX relationships.  Then each record is compared to
		// all those later in the list, and when MX relationships are found the lower-priority records are removed.

		final boolean prefOp = preferOperating;
		Comparator<ExtDbRecordTV> prefComp = new Comparator<ExtDbRecordTV>() {
			public int compare(ExtDbRecordTV theRecord, ExtDbRecordTV otherRecord) {
				if (theRecord.isPreferredRecord(otherRecord, prefOp)) {
					return -1;
				}
				return 1;
			}
		};

		Collections.sort(records, prefComp);

		int recCount = records.size() - 1;
		for (int recIndex = 0; recIndex < recCount; recIndex++) {
			theRecord = records.get(recIndex);
			lit = records.listIterator(recIndex + 1);
			while (lit.hasNext()) {
				if (areRecordsMX(theRecord, lit.next(), coChanMX, kmPerDeg)) {
					lit.remove();
					recCount--;
				}
			}
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	private ExtDbRecordTV(ExtDb theExtDb) {

		super(theExtDb, Source.RECORD_TYPE_TV);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Update a source record object from this object's properties, this is called by makeSource() in SourceEditDataTV
	// when creating a new source encapsulating this record.  Return is false on failure, but the caller should check
	// hasErrors() on the error logger as in some cases a message is logged rather than an error reported.  A non-error
	// failure may not necessarily abort an overall process but merely cause a particular record to be ignored.  Also
	// even with a successful return messages may have been logged e.g. when defaults are applied.  The caller should
	// check for and appropriately report the message log.  First verify that the source being updated is associated
	// with this record; facility ID, service, and country must all match.  This cannot be used to arbitrarily copy
	// record properties to other source objects.  (Data set key and record ID are not checked anymore because there
	// are now legitimate cases where those won't match; this is really paranoia anyway.)  Note the source does not
	// have to be part of an existing study, if the study object on the source is null appropriate defaults are used.

	public boolean updateSource(SourceEditDataTV theSource) {
		return updateSource(theSource, null);
	}

	public boolean updateSource(SourceEditDataTV theSource, ErrorLogger errors) {

		if (!extDb.dbID.equals(theSource.dbID) || (facilityID != theSource.facilityID) ||
				!service.equals(theSource.service) || !country.equals(theSource.country)) {
			if (null != errors) {
				errors.reportError("ExtDbRecordTV.updateSource(): non-matching source object.");
			}
			return false;
		}

		boolean isCDBS = (ExtDb.DB_TYPE_CDBS == extDb.type);
		if (!isCDBS && (ExtDb.DB_TYPE_LMS != extDb.type) && (ExtDb.DB_TYPE_LMS_LIVE != extDb.type)) {
			if (null != errors) {
				errors.reportError("ExtDbRecordTV.updateSource(): unsupported external record type.");
			}
			return false;
		}

		// Some of the logic below depends on checking for reported errors from other methods.  If the caller did not
		// provide an error logger, create a temporary one.  Also saves having to check for null everywhere.

		if (null != errors) {
			errors.clearErrors();
		} else {
			errors = new ErrorLogger(null, null);
		}
		boolean error = false;

		// Begin copying properties common to normal and DTS parent records.

		theSource.siteNumber = siteNumber;
		theSource.callSign = callSign;
		theSource.channel = channel;
		theSource.city = city;
		theSource.state = state;
		theSource.zone = Zone.getZone(zoneCode);
		theSource.status = status;
		theSource.statusType = statusType;
		theSource.fileNumber = filePrefix + appARN;
		theSource.appARN = appARN;
		theSource.frequencyOffset = FrequencyOffset.getFrequencyOffset(frequencyOffsetCode);

		// Special handling of the emission mask, make sure it is set when it should be and not set when it shouldn't.

		if (service.serviceType.needsEmissionMask) {
			theSource.emissionMask = EmissionMask.getEmissionMask(emissionMaskCode);
			if (0 == theSource.emissionMask.key) {
				theSource.emissionMask = EmissionMask.getDefaultObject();
			}
		} else {
			theSource.emissionMask = EmissionMask.getNullObject();
		}

		theSource.location.setLatLon(location);

		theSource.dtsMaximumDistance = dtsMaximumDistance;

		// Some properties are saved in attributes.  Adding new direct properties to the source model is a lot of
		// effort, for infrequently-used properties this makes more sense.

		theSource.setAttribute(Source.ATTR_SEQUENCE_DATE, AppCore.formatDate(sequenceDate));
		if ((null != licensee) && (licensee.length() > 0)) {
			theSource.setAttribute(Source.ATTR_LICENSEE, licensee);
		}
		if (isSharingHost) {
			theSource.setAttribute(Source.ATTR_IS_SHARING_HOST);
		}
		if (isBaseline) {
			theSource.setAttribute(Source.ATTR_IS_BASELINE);
		}


		// For a DTS parent source the rest of the fields remain at defaults; now process the secondary records.  That
		// includes the individual DTS transmitter records, as well as the reference facility that defines a bounding
		// contour for the service area (in union with the distance radius around the reference point).  The reference
		// facility record has to be retrieved here.  For CDBS, it is usually a separate record with matching facility
		// ID but service type DT and is either a current record with type code C, or an archived record with code R.
		// For LMS the reference facility is usually the DTV record with matching facility ID and the DTS reference
		// facility indicator 'R'.  However in case that does not exist, e.g. for a new DTS application where the DTV
		// is still current, also look for DTV records with license or CP status.  In either case only active records
		// are considered; unlike CDBS, flagged LMS reference facilities are still active, but are treated as archived
		// by other queries, see addRecordTypeQuery().  If multiple matches use the most recent.

		if (null != dtsRecords) {

			ExtDbRecordTV refRecord = null;
			LinkedList<ExtDbRecordTV> theRecs = null;

			if (isCDBS) {

				theRecs = findRecordsTV(extDb, "(tv_eng_data.vsd_service = 'DT') AND " +
					"(tv_eng_data.eng_record_type IN ('C', 'R')) AND (tv_eng_data.facility_id = " + facilityID + ")",
					null, 0., 0., null, errors);
				if (null == theRecs) {
					return false;
				}

			} else {

				if (extDb.version < 1) {
					if (null != errors) {
						errors.reportError("Unsupported data set import version.");
					}
					return false;
				}
				theRecs = findRecordsTV(extDb, "(application_facility.afac_facility_id = " + facilityID + ") AND " +
					"(license_filing_version.service_code = 'DTV') AND " +
					"(license_filing_version.active_ind = 'Y') AND " +
					"((application.dts_reference_ind = 'R') OR " +
						"((license_filing_version.current_status_code <> 'PEN') AND " +
						"(license_filing_version.auth_type_code IN ('C', 'L'))))", null, 0., 0., null, errors);
				if (null == theRecs) {
					return false;
				}
			}

			if (!theRecs.isEmpty()) {

				for (ExtDbRecordTV theRecord : theRecs) {
					if ((null == refRecord) || theRecord.sequenceDate.after(refRecord.sequenceDate)) {
						refRecord = theRecord;
					}
				}

				refRecord.siteNumber = 0;
			}

			// No reference record found, fall back to the baseline facility record for the facility ID.

			if (null == refRecord) {

				StringBuilder query = new StringBuilder();
				addBaselineFacilityIDQuery(extDb.type, extDb.version, facilityID, query, false);

				theRecs = findBaselineRecords(extDb, query.toString(), null, 0., 0., true, errors);
				if (null == theRecs) {
					return false;
				}

				if (!theRecs.isEmpty()) {
					refRecord = theRecs.getFirst();
				}
			}

			// If still no reference record, log a message and create a "dummy" reference facility record, see
			// SourceEditDataTV for details.  All other code expects a reference facility record to always exist, so
			// the only other way to react to this would be to reject the parent record as invalid.  However valid
			// experimental DTS facility records can exist with no reference facility; this approach allows those
			// records to be used without having to modify other code to handle a missing reference facility.

			SourceEditDataTV dtsSource;

			if (null == refRecord) {

				errors.logMessage(makeMessage(this, "No DTS reference facility record found."));

				if (!theSource.addDTSReferenceSource(errors)) {
					return false;
				}

			} else {

				// Convert the reference record.  The site number is always set to 0, that is how the reference
				// facility is identified; all other DTS transmitter records have site number >0.

				refRecord.siteNumber = 0;

				dtsSource = theSource.addDTSSource(refRecord, errors);
				if (null == dtsSource) {
					return false;
				}
			}

			// Convert the individual sources.

			for (ExtDbRecordTV dtsRecord : dtsRecords) {
				dtsSource = theSource.addDTSSource(dtsRecord, errors);
				if (null == dtsSource) {
					return false;
				}
			}

			// All done for a DTS parent.

			return true;
		}

		// Continue with updating a source that is not a DTS parent.  Copy a few more values.

		theSource.heightAMSL = heightAMSL;
		theSource.overallHAAT = overallHAAT;
		theSource.peakERP = peakERP;

		// Get horizontal pattern data if needed.  If pattern data does not exist set pattern to omni.  A pattern with
		// too few points or bad data values will also revert to omni, see pattern-load methods in the superclass for
		// details.  If the study parameter says to trust the DA indicator and that is not set don't even look for the
		// pattern just use omni.  If there is no study context, default to ignoring the DA indicator.

		if ((null != antennaRecordID) && (daIndicated || (null == theSource.study) ||
				!theSource.study.getTrustPatternFlag())) {

			String theName = null, make, model;

			DbConnection db = extDb.connectDb(errors);
			if (null != db) {
				try {

					if (isCDBS) {

						db.query(
						"SELECT " +
							"ant_make, " +
							"ant_model_num " +
						"FROM " +
							"ant_make " +
						"WHERE " +
							"antenna_id = " + antennaRecordID);

					} else {

						if (ExtDb.DB_TYPE_LMS_LIVE == extDb.type) {

							if (isBaseline && !service.isDTS) {

								db.query(
								"SELECT " +
									"rant_make, " +
									"rant_model " +
								"FROM " +
									"mass_media.lkp_antenna " +
								"WHERE " +
									"rant_antenna_id = '" + antennaRecordID + "'");

							} else {

								db.query(
								"SELECT " +
									"aant_make, " +
									"aant_model " +
								"FROM " +
									"mass_media.app_antenna " +
								"WHERE " +
									"aant_antenna_record_id = '" + antennaRecordID + "'");
							}

						} else {

							if (isBaseline && !service.isDTS) {

								db.query(
								"SELECT " +
									"rant_make, " +
									"rant_model " +
								"FROM " +
									"lkp_antenna " +
								"WHERE " +
									"rant_antenna_id = '" + antennaRecordID + "'");

							} else {

								db.query(
								"SELECT " +
									"aant_make, " +
									"aant_model " +
								"FROM " +
									"app_antenna " +
								"WHERE " +
									"aant_antenna_record_id = '" + antennaRecordID + "'");
							}
						}
					}

					if (db.next()) {

						make = db.getString(1);
						if (null == make) {
							make = "";
						}
						model = db.getString(2);
						if (null == model) {
							model = "";
						}
						theName = make + "-" + model;
						if (theName.length() > Source.MAX_PATTERN_NAME_LENGTH) {
							theName = theName.substring(0, Source.MAX_PATTERN_NAME_LENGTH);
						}

					} else {

						errors.logMessage(makeMessage(this, "Antenna record ID " + antennaRecordID + " not found."));
					}

					extDb.releaseDb(db);

				} catch (SQLException se) {
					extDb.releaseDb(db);
					error = true;
					DbConnection.reportError(errors, se);
				}

			} else {
				error = true;
			}

			if (error) {
				return false;
			}

			// If horizontal pattern retrieval returns an empty array treat that as omni, it means the antenna ID is
			// defined just to provide type information, it is an omni antenna.  Otherwise if the retrieval returns
			// null but did not report an error, the pattern was not usable and omni should be substituted.  In that
			// case a message will have been logged describing the problem.

			if (null != theName) {

				ArrayList<AntPattern.AntPoint> thePoints = getAntennaPattern(this, errors);

				if ((null != thePoints) && thePoints.isEmpty()) {
					thePoints = null;
				}

				if (null != thePoints) {

					theSource.antennaID = antennaID;
					theSource.hasHorizontalPattern = true;
					theSource.horizontalPattern = new AntPattern(theSource.dbID, AntPattern.PATTERN_TYPE_HORIZONTAL,
						theName, thePoints);
					theSource.horizontalPatternChanged = true;

				} else {

					if (errors.hasErrors()) {
						return false;
					}
				}
			}
		}

		// The horizontal pattern orientation value is set and preserved even if no actual pattern is set.  If the
		// pattern was not set due to an error, the orientation may still be correct and so relevant later if the user
		// manually sets the pattern data.

		theSource.horizontalPatternOrientation = horizontalPatternOrientation;

		// Now the vertical pattern, similar logic to the horizontal.  However this is complicated by the fact that the
		// pattern may be a conventional single vertical pattern, or a multi-slice "matrix" pattern.

		if (null != elevationAntennaRecordID) {

			String theName = null, make, model;
			boolean isMatrix = false;

			DbConnection db = extDb.connectDb(errors);
			if (null != db) {
				try {

					if (isCDBS) {

						db.query(
						"SELECT " +
							"ant_make, " +
							"ant_model_num " +
						"FROM " +
							"elevation_ant_make " +
						"WHERE " +
							"elevation_antenna_id = " + elevationAntennaRecordID);

					} else {

						if (ExtDb.DB_TYPE_LMS_LIVE == extDb.type) {

							db.query(
							"SELECT " +
								"aant_make, " +
								"aant_model " +
							"FROM " +
								"mass_media.app_antenna " +
							"WHERE " +
								"aant_antenna_record_id = '" + elevationAntennaRecordID + "'");

						} else {

							db.query(
							"SELECT " +
								"aant_make, " +
								"aant_model " +
							"FROM " +
								"app_antenna " +
							"WHERE " +
								"aant_antenna_record_id = '" + elevationAntennaRecordID + "'");
						}
					}

					if (db.next()) {

						make = db.getString(1);
						if (null == make) {
							make = "";
						}
						model = db.getString(2);
						if (null == model) {
							model = "";
						}
						theName = make + "-" + model;
						if (theName.length() > Source.MAX_PATTERN_NAME_LENGTH) {
							theName = theName.substring(0, Source.MAX_PATTERN_NAME_LENGTH);
						}

						if (isCDBS) {

							db.query(
							"SELECT " +
								"COUNT(*) " +
							"FROM " +
								"elevation_pattern " +
							"WHERE " +
								"elevation_antenna_id = " + elevationAntennaRecordID + " " +
								"AND field_value0 > 0.");

							if (db.next() && (db.getInt(1) > 0)) {
								isMatrix = true;
							}

						} else {

							if (ExtDb.DB_TYPE_LMS_LIVE == extDb.type) {

								db.query(
								"SELECT " +
									"MAX(aaep_azimuth) " +
								"FROM " +
									"mass_media.app_antenna_elevation_pattern " +
								"WHERE " +
									"aaep_antenna_record_id = '" + elevationAntennaRecordID + "'");

							} else {

								db.query(
								"SELECT " +
									"MAX(aaep_azimuth) " +
								"FROM " +
									"app_antenna_elevation_pattern " +
								"WHERE " +
									"aaep_antenna_record_id = '" + elevationAntennaRecordID + "'");
							}

							if (db.next() && (db.getDouble(1) > 0.)) {
								isMatrix = true;
							}
						}

					} else {

						errors.logMessage(makeMessage(this, "Elevation antenna ID " + elevationAntennaRecordID +
							" not found."));
					}

					extDb.releaseDb(db);

				} catch (SQLException se) {
					extDb.releaseDb(db);
					error = true;
					DbConnection.reportError(errors, se);
				}

			} else {
				error = true;
			}

			if (error) {
				return false;
			}

			// See horizontal case above for details of the error/message handling.

			if (null != theName) {

				if (isMatrix) {

					ArrayList<AntPattern.AntSlice> theSlices = getMatrixPattern(this, errors);

					if (null != theSlices) {

						theSource.hasMatrixPattern = true;
						theSource.matrixPattern = new AntPattern(theSource.dbID, theName,
							AntPattern.PATTERN_TYPE_VERTICAL, theSlices);
						theSource.matrixPatternChanged = true;

					} else {

						if (errors.hasErrors()) {
							return false;
						}
					}

				} else {

					ArrayList<AntPattern.AntPoint> thePoints = getElevationPattern(this, errors);

					if ((null != thePoints) && thePoints.isEmpty()) {
						thePoints = null;
					}

					if (null != thePoints) {

						theSource.hasVerticalPattern = true;
						theSource.verticalPattern = new AntPattern(theSource.dbID, AntPattern.PATTERN_TYPE_VERTICAL,
							theName, thePoints);
						theSource.verticalPatternChanged = true;

					} else {

						if (errors.hasErrors()) {
							return false;
						}
					}
				}
			}
		}

		// As with horizontal pattern orientation, vertical pattern tilts are always preserved even with no pattern.
		// Note these are not used with a matrix pattern.

		theSource.verticalPatternElectricalTilt = verticalPatternElectricalTilt;
		theSource.verticalPatternMechanicalTilt = verticalPatternMechanicalTilt;
		theSource.verticalPatternMechanicalTiltOrientation = verticalPatternMechanicalTiltOrientation;

		// Special processing for a matrix pattern.  If the peak values of the individual pattern slices are not all
		// 1.0, derive a pseudo-horizontal pattern from the individual slice peak values, and then normalize each
		// slice to a 1.0 peak.  If there was also a horizontal pattern in the database, that is discarded.  In most
		// cases a matrix pattern will have no horizontal pattern, or a pattern consisting entirely of 1.0 values, but
		// even if not the assumption is it would be the same as the pattern implied by the peak variations in the
		// matrix slices, and so that pattern should not be applied as it would be "double-dipping".  Note the pattern
		// determined here is neither a horizontal-plane pattern nor a main-beam pattern; the values come from the peak
		// of each slice regardless of the depression angle.

		// This is necessary because contour projection may use just the horizontal pattern combined with the generic
		// vertical patterns, so some reasonable horizontal pattern must always be present.  But this pre-processing
		// will cancel out in situations where the full matrix pattern should be used (as is always the case for
		// terrain-sensitive signal projections); when normal lookups combine the pseudo-horizontal pattern and the
		// normalized matrix slices, that will re-construct the original matrix pattern.

		if (theSource.hasMatrixPattern) {

			ArrayList<AntPattern.AntPoint> newHpat = new ArrayList<AntPattern.AntPoint>();
			AntPattern.AntPoint maxPoint;
			boolean usePat = false;

			for (AntPattern.AntSlice theSlice : theSource.matrixPattern.getSlices()) {
				maxPoint = null;
				for (AntPattern.AntPoint thePoint : theSlice.points) {
					if ((null == maxPoint) || (thePoint.relativeField > maxPoint.relativeField)) {
						maxPoint = thePoint;
					}
				}
				newHpat.add(new AntPattern.AntPoint(theSlice.value, maxPoint.relativeField));
				if (maxPoint.relativeField < 1.) {
					usePat = true;
				}
			}

			if (usePat) {

				for (AntPattern.AntPoint hPoint : newHpat) {
					for (AntPattern.AntPoint vPoint :
							theSource.matrixPattern.getSlicePoints(Double.valueOf(hPoint.angle))) {
						vPoint.relativeField /= hPoint.relativeField;
					}
				}

				theSource.antennaID = null;
				theSource.hasHorizontalPattern = true;
				theSource.horizontalPattern = new AntPattern(theSource.dbID, AntPattern.PATTERN_TYPE_HORIZONTAL,
					"(matrix derived)", newHpat);
				theSource.horizontalPatternChanged = true;
				theSource.horizontalPatternOrientation = 0.;
			}
		}

		// A parameter determines the initial setting for the record flag that allows generic vertical patterns to be
		// used when there is no real vertical pattern data.  This can be changed later for individual records, also
		// other parameters may disable generic patterns for all records in some situations i.e. contour projection.
		// With no study, this defaults to true.

		if (null == theSource.study) {
			theSource.useGenericVerticalPattern = true;
		} else {
			theSource.useGenericVerticalPattern = theSource.study.getUseGenericVpat(theSource.country.key - 1);
		}

		// Make some automatic corrections for known issues in the data.  First, many full-service Mexican records
		// represent allotments and provide little more than channel and coordinates; ERP and heights are often zero.
		// Apply defaults set by study parameter, the HAAT is set here only if the AMSL is also missing because the
		// HAAT is needed to derive the AMSL, see below, but otherwise HAAT is not used.  There must of course be a
		// study context to provide the defaults, if not skip this.

		if ((Country.MX == country.key) && ((ServiceType.SERVTYPE_DTV_FULL == service.serviceType.key) ||
				(ServiceType.SERVTYPE_NTSC_FULL == service.serviceType.key)) && (null != theSource.study)) {

			if (0. == peakERP) {

				if (ServiceType.SERVTYPE_DTV_FULL == service.serviceType.key) {

					if (channel < 7) {
						theSource.peakERP = theSource.study.getDefaultMexicanDigitalVloERP();
					} else {
						if (channel < 14) {
							theSource.peakERP = theSource.study.getDefaultMexicanDigitalVhiERP();
						} else {
							theSource.peakERP = theSource.study.getDefaultMexicanDigitalUhfERP();
						}
					}

				} else {

					if (channel < 7) {
						theSource.peakERP = theSource.study.getDefaultMexicanAnalogVloERP();
					} else {
						if (channel < 14) {
							theSource.peakERP = theSource.study.getDefaultMexicanAnalogVhiERP();
						} else {
							theSource.peakERP = theSource.study.getDefaultMexicanAnalogUhfERP();
						}
					}
				}

				errors.logMessage(makeMessage(this, "Used default for missing ERP."));
			}

			if ((0. == heightAMSL) && (0. == overallHAAT)) {

				if (ServiceType.SERVTYPE_DTV_FULL == service.serviceType.key) {

					if (channel < 7) {
						theSource.overallHAAT = theSource.study.getDefaultMexicanDigitalVloHAAT();
					} else {
						if (channel < 14) {
							theSource.overallHAAT = theSource.study.getDefaultMexicanDigitalVhiHAAT();
						} else {
							theSource.overallHAAT = theSource.study.getDefaultMexicanDigitalUhfHAAT();
						}
					}

				} else {

					if (channel < 7) {
						theSource.overallHAAT = theSource.study.getDefaultMexicanAnalogVloHAAT();
					} else {
						if (channel < 14) {
							theSource.overallHAAT = theSource.study.getDefaultMexicanAnalogVhiHAAT();
						} else {
							theSource.overallHAAT = theSource.study.getDefaultMexicanAnalogUhfHAAT();
						}
					}
				}

				errors.logMessage(makeMessage(this, "Used default for missing HAAT."));
			}
		}

		// Some Canadian and Mexican records do not have an AMSL height; flag those so the study engine will derive
		// AMSL from HAAT.

		if ((Country.US != country.key) && (0. == heightAMSL) && (0. != overallHAAT)) {
			theSource.heightAMSL = Source.HEIGHT_DERIVE;
			errors.logMessage(makeMessage(this, "Derived missing AMSL from HAAT."));
		}

		// If the peak ERP value is zero or negative, assume dBk was erroneously entered in the kW field.

		if (peakERP <= 0.) {
			theSource.peakERP = Math.pow(10., (peakERP / 10.));
			errors.logMessage(makeMessage(this, "Converted ERP from dBk to kilowatts."));
		}

		// Occasionally mechanical tilt and mechanical tilt orientation are transposed.

		if ((verticalPatternMechanicalTilt > AntPattern.TILT_MAX) &&
				(verticalPatternMechanicalTiltOrientation <= AntPattern.TILT_MAX)) {
			theSource.verticalPatternMechanicalTilt = verticalPatternMechanicalTiltOrientation;
			theSource.verticalPatternMechanicalTiltOrientation = verticalPatternMechanicalTilt;
			errors.logMessage(makeMessage(this, "Transposed mechanical tilt parameters."));
		}

		// Mechanical or electrical tilt out of legal range are ignored.

		if ((verticalPatternElectricalTilt < AntPattern.TILT_MIN) ||
				(verticalPatternElectricalTilt > AntPattern.TILT_MAX)) {
			theSource.verticalPatternElectricalTilt = 0.;
			errors.logMessage(makeMessage(this, "Ignored out-of-range electrical tilt."));
		}
		if ((verticalPatternMechanicalTilt < AntPattern.TILT_MIN) ||
				(verticalPatternMechanicalTilt > AntPattern.TILT_MAX)) {
			theSource.verticalPatternMechanicalTilt = 0.;
			errors.logMessage(makeMessage(this, "Ignored out-of-range mechanical tilt."));
		}

		// Mechanical tilt orientation field may be missing, if mechanical tilt is non-zero warn that 0 was assumed.
		// Note this may not always be right, this is a field that may legitimately be 0.  However currently there is
		// no way to tell a legitimate 0 from one substituted for missing data at insert.

		if ((verticalPatternMechanicalTilt != 0.) && (0. == verticalPatternMechanicalTiltOrientation)) {
			errors.logMessage(makeMessage(this, "Used 0 for missing mechanical tilt orientation."));
		}

		// For U.S. non-full-service (i.e. Class A or LPTV) records, check an alternate CDBS field for the peak ERP.
		// Normally the value from tv_eng_data.effective_erp is used, but if tv_eng_data.max_erp_any_angle has a larger
		// value, use that instead.  This only affects a small number of records, in general the alternate field does
		// not have any value in it at all.  Which means it appears to have value 0 so a check for <= 0 and conversion
		// from assumed dBk to kW cannot be done for the alternate field; if it is zero or negative due to mis-entry of
		// dBk it just won't used because the main field at this point will always be > 0.  This does not apply to LMS,
		// there is no equivalent to the alternate ERP field.

		if (isCDBS && (Country.US == country.key) && (ServiceType.SERVTYPE_DTV_FULL != service.serviceType.key) &&
				(ServiceType.SERVTYPE_NTSC_FULL != service.serviceType.key)) {
			if (alternateERP > peakERP) {
				theSource.peakERP = alternateERP;
				errors.logMessage(makeMessage(this, "Using ERP value from max_erp_any_angle."));
			}
		}

		// Done.

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Determine if two records (contained in ExtDbRecordTV or SourceEditDataTV objects) are mutually-exclusive.  The
	// primary test is facility ID; any two records with the same facility ID are MX.  Beyond that are back-up checks
	// to detect co-channel MX cases with differing facility IDs.  Those apply only for matching channel and country.
	// Most often those checks are needed to detect MX applications for a new facility, but also to catch cases such as
	// DTV rule-making records that have a facility ID that does not match the actual station.  Note for the distance
	// check here when a DTS is involved, only the DTS reference point coordinates are ever checked.  Also note special
	// handling of DRT records.  A parent full-service and all of it's DRTs have the same facility ID however those
	// records are not MX to each other, unless co-channel and less than a fixed distance apart.  Also alternate forms
	// of this may not apply the back-up matching city-state and distance checks.

	public static boolean areRecordsMX(ExtDbRecordTV a, ExtDbRecordTV b, double mxDist, double kmPerDeg) {
		int a_channel = (a.replicateToChannel > 0) ? a.replicateToChannel : a.channel;
		int b_channel = (b.replicateToChannel > 0) ? b.replicateToChannel : b.channel;
		return areRecordsMX(
			a.facilityID, a.isDRT, a_channel, a.country.key, a.state, a.city, a.location,
			b.facilityID, b.isDRT, b_channel, b.country.key, b.state, b.city, b.location,
			true, mxDist, kmPerDeg);
	}

	public static boolean areRecordsMX(ExtDbRecordTV a, SourceEditDataTV b, double mxDist, double kmPerDeg) {
		int a_channel = (a.replicateToChannel > 0) ? a.replicateToChannel : a.channel;
		return areRecordsMX(
			a.facilityID, a.isDRT, a_channel, a.country.key, a.state, a.city, a.location,
			b.facilityID, b.isDRT, b.channel, b.country.key, b.state, b.city, b.location,
			true, mxDist, kmPerDeg);
	}

	public static boolean areRecordsMX(SourceEditDataTV a, SourceEditDataTV b, double mxDist, double kmPerDeg) {
		return areRecordsMX(
			a.facilityID, a.isDRT, a.channel, a.country.key, a.state, a.city, a.location,
			b.facilityID, b.isDRT, b.channel, b.country.key, b.state, b.city, b.location,
			true, mxDist, kmPerDeg);
	}

	public static boolean areRecordsMX(ExtDbRecordTV a, ExtDbRecordTV b, double kmPerDeg) {
		int a_channel = (a.replicateToChannel > 0) ? a.replicateToChannel : a.channel;
		int b_channel = (b.replicateToChannel > 0) ? b.replicateToChannel : b.channel;
		return areRecordsMX(
			a.facilityID, a.isDRT, a_channel, a.country.key, a.state, a.city, a.location,
			b.facilityID, b.isDRT, b_channel, b.country.key, b.state, b.city, b.location,
			false, 0., kmPerDeg);
	}

	public static boolean areRecordsMX(ExtDbRecordTV a, SourceEditDataTV b, double kmPerDeg) {
		int a_channel = (a.replicateToChannel > 0) ? a.replicateToChannel : a.channel;
		return areRecordsMX(
			a.facilityID, a.isDRT, a_channel, a.country.key, a.state, a.city, a.location,
			b.facilityID, b.isDRT, b.channel, b.country.key, b.state, b.city, b.location,
			false, 0., kmPerDeg);
	}

	public static boolean areRecordsMX(SourceEditDataTV a, SourceEditDataTV b, double kmPerDeg) {
		return areRecordsMX(
			a.facilityID, a.isDRT, a.channel, a.country.key, a.state, a.city, a.location,
			b.facilityID, b.isDRT, b.channel, b.country.key, b.state, b.city, b.location,
			false, 0., kmPerDeg);
	}

	private static boolean areRecordsMX(int a_facilityID, boolean a_is_drt, int a_channel, int a_country_key,
			String a_state, String a_city, GeoPoint a_location, int b_facilityID, boolean b_is_drt, int b_channel,
			int b_country_key, String b_state, String b_city, GeoPoint b_location, boolean doBackupTests,
			double mxDist, double kmPerDeg) {

		if (a_facilityID == b_facilityID) {

			if (a_is_drt || b_is_drt) {

				if (a_channel != b_channel) {
					return false;
				}

				if (doBackupTests && (a_location.distanceTo(b_location, kmPerDeg) < DRT_MX_DISTANCE)) {
					return true;
				}

				return false;
			}

			return true;
		}

		if (!doBackupTests) {
			return false;
		}

		if (a_channel != b_channel) {
			return false;
		}

		if (a_country_key != b_country_key) {
			return false;
		}

		if (a_state.equalsIgnoreCase(b_state) && a_city.equalsIgnoreCase(b_city)) {
			return true;
		}

		if ((mxDist > 0.) && (a_location.distanceTo(b_location, kmPerDeg) < mxDist)) {
			return true;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Check another record and determine if this record is preferred over the other, presumably mutually-exclusive.
	// Returns true if this record is preferred, false otherwise.

	public boolean isPreferredRecord(ExtDbRecordTV otherRecord, boolean preferOperating) {

		// The first test is to prefer an "operating facility" record, see isOperating() for details.  This test can
		// be disabled by argument.

		if (preferOperating) {
			if (isOperating()) {
				if (!otherRecord.isOperating()) {
					return true;
				}
			} else {
				if (otherRecord.isOperating()) {
					return false;
				}
			}
		}

		// Next test the ranking of the record service, in case services are not the same.  This means for example a
		// digital Class A record for a flash-cut is preferred over the associated analog record, and a full-service
		// record is preferred over any associated auxiliary service record, both regardless of the record status.

		if (service.preferenceRank > otherRecord.service.preferenceRank) {
			return true;
		}
		if (service.preferenceRank < otherRecord.service.preferenceRank) {
			return false;
		}

		// Next check the record status, this is most often the test that will resolve the comparison.  This has an
		// extra rule when the prefer-operating test is active, in that case the STA status is always preferred.

		if (preferOperating) {
			if ((STATUS_TYPE_STA == statusType) && (STATUS_TYPE_STA != otherRecord.statusType)) {
				return true;
			}
			if ((STATUS_TYPE_STA != statusType) && (STATUS_TYPE_STA == otherRecord.statusType)) {
				return false;
			}
		}
		if (STATUS_TYPE_RANK[statusType] < STATUS_TYPE_RANK[otherRecord.statusType]) {
			return true;
		}
		if (STATUS_TYPE_RANK[statusType] > STATUS_TYPE_RANK[otherRecord.statusType]) {
			return false;
		}

		// If nothing else resolved the comparison, prefer the record with the more-recent sequence date.

		if (sequenceDate.after(otherRecord.sequenceDate)) {
			return true;
		}
		if (sequenceDate.before(otherRecord.sequenceDate)) {
			return false;
		}

		// This must return a consistent result for sorting (if A != B then A.isPreferred(B) != B.isPreferred(A)), so
		// in the very unlikely event that all else was equal, compare record IDs.  The ID sequence is arbitrary but
		// since IDs are unique this is guaranteed to never see the records as equal.

		if (extRecordID.compareTo(otherRecord.extRecordID) > 0) {
			return true;
		}
		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Determine if this record represents an actual operating facility.  The test result is cached.

	public boolean isOperating() {

		if (operatingStatusSet) {
			return isOperatingFacility;
		}

		// If the record service is non-operating (auxiliaries, rule-makings, etc.), the record cannot be operating.
		// Archived records are never operating.  Status codes LIC and STA are always operating.  CP is operating for
		// a non-U.S. record, for U.S. operating only if there is a pending license application.  OTHER is operating
		// for non-U.S. but not for U.S.  All others are never operating.

		if (!service.isOperating || isArchived) {
			isOperatingFacility = false;
		} else {
			switch (statusType) {
				case STATUS_TYPE_LIC:
				case STATUS_TYPE_STA: {
					isOperatingFacility = true;
					break;
				}
				case STATUS_TYPE_CP: {
					if (Country.US == country.key) {
						isOperatingFacility = hasLicenseApp(extDb, facilityID);
					} else {
						isOperatingFacility = true;
					}
					break;
				}
				case STATUS_TYPE_OTHER: {
					if (Country.US == country.key) {
						isOperatingFacility = false;
					} else {
						isOperatingFacility = true;
					}
					break;
				}
				default: {
					isOperatingFacility = false;
					break;
				}
			}
		}

		operatingStatusSet = true;

		return isOperatingFacility;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get the rule extra distance, see details in SourceEditDataTV.

	public double getRuleExtraDistance(StudyEditData study) {

		return SourceEditDataTV.getRuleExtraDistance(study, service, (null != dtsRecords), country,
			(replicateToChannel > 0) ? replicateToChannel : channel, peakERP);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Determine if there is a pending license application for a given facility ID, used to determine if a CP for the
	// facility should be considered operating.  This queries for all pending license-to-cover records in the specified
	// database and caches the results.  Note this does not report database errors interactively or to the caller, and
	// if a query fails it will not be repeated.  Database errors simply return false for this and all later tests for
	// the same database context.

	private static HashMap<String, HashSet<Integer>> licenseAppLists = new HashMap<String, HashSet<Integer>>();

	private static synchronized boolean hasLicenseApp(ExtDb extDb, int facilityID) {

		boolean isCDBS = (ExtDb.DB_TYPE_CDBS == extDb.type);
		if (!isCDBS && (ExtDb.DB_TYPE_LMS != extDb.type) && (ExtDb.DB_TYPE_LMS_LIVE != extDb.type)) {
			return false;
		}

		String cacheKey = extDb.dbID + "_" + extDb.key;

		HashSet<Integer> licenseApps = licenseAppLists.get(cacheKey);

		if (null == licenseApps) {

			licenseApps = new HashSet<Integer>();

			DbConnection db = extDb.connectDb();
			if (null != db) {
				try {

					if (isCDBS) {

						db.query(
						"SELECT " +
							"facility_id " +
						"FROM " +
							"tv_eng_data " +
						"WHERE " +
							"(tv_dom_status = 'LIC') " +
							"AND (eng_record_type = 'P')");

					} else {

						if (ExtDb.DB_TYPE_LMS_LIVE == extDb.type) {

							db.query(
							"SELECT " +
								"application_facility.afac_facility_id " +
							"FROM " +
								"common_schema.license_filing_version " +
								"JOIN common_schema.application_facility ON " +
									"(application_facility.afac_application_id = " +
										"license_filing_version.filing_version_id) " +
							"WHERE " +
								"(license_filing_version.active_ind = 'Y') " +
								"AND (license_filing_version.auth_type_code = 'L') " +
								"AND (license_filing_version.current_status_code = 'PEN')");

						} else {

							db.query(
							"SELECT " +
								"application_facility.afac_facility_id " +
							"FROM " +
								"license_filing_version " +
								"JOIN application_facility ON (application_facility.afac_application_id = " +
									"license_filing_version.filing_version_id) " +
							"WHERE " +
								"(license_filing_version.active_ind = 'Y') " +
								"AND (license_filing_version.auth_type_code = 'L') " +
								"AND (license_filing_version.current_status_code = 'PEN')");
						}
					}

					while (db.next()) {
						licenseApps.add(Integer.valueOf(db.getInt(1)));
					}

				} catch (SQLException se) {
					db.reportError(se);
				}

				extDb.releaseDb(db);
			}

			licenseAppLists.put(cacheKey, licenseApps);
		}

		return licenseApps.contains(Integer.valueOf(facilityID));
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Remove cache state when closing a database.

	public static synchronized void closeDb(String theDbID) {

		licenseAppLists.remove(theDbID);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// See makeCommentText() in superclass.

	protected ArrayList<String> getComments() {

		ArrayList<String> result = null;

		boolean hasLic = ((null != licensee) && (licensee.length() > 0));

		if (hasLic || isDRT || isSharingHost || isBaseline) {
			result = new ArrayList<String>();
			if (hasLic) {
				result.add("Licensee: " + licensee);
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
	// Methods in the Record interface.  Some are in the superclass.

	public String getFacilityID() {

		return String.valueOf(facilityID);
	}

	public String getSortFacilityID() {

		return String.format(Locale.US, "%07d", facilityID);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getSiteCount() {

		if (service.isDTS) {
			int n = dtsRecords.size();
			if (n < 1) {
				n = 1;
			}
			return String.valueOf(n);
		}
		return "1";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getCallSign() {

		return callSign;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getChannel() {

		if (replicateToChannel > 0) {
			return "D" + String.valueOf(replicateToChannel) + " (" + getOriginalChannel() + ")";
		}
		return getOriginalChannel();
	}

	public String getSortChannel() {

		int chan = channel;
		if (replicateToChannel > 0) {
			chan = replicateToChannel;
		}
		return String.format(Locale.US, "%02d%c", chan, (service.serviceType.digital ? 'D' : 'N'));
	}

	public String getOriginalChannel() {

		return (service.serviceType.digital ? "D" : "N") + String.valueOf(channel) +
			FrequencyOffset.getFrequencyOffset(frequencyOffsetCode).frequencyOffsetCode;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getFrequency() {

		int chan = channel;
		if (replicateToChannel > 0) {
			chan = replicateToChannel;
		}

		double freq = 0.;
		if (chan < 5) {
			freq = 57. + ((double)(chan - 2) * 6.);
		} else {
			if (chan < 7) {
				freq = 79. + ((double)(chan - 5) * 6.);
			} else {
				if (chan < 14) {
					freq = 177. + ((double)(chan - 7) * 6.);
				} else {
					freq = 473. + ((double)(chan - 14) * 6.);
				}
			}
		}

		if (service.serviceType.digital) {
			return String.format(Locale.US, "%.0f MHz", freq);
		} else {
			freq -= 1.75;
			return String.format(Locale.US, "%.2f MHz", freq);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getZone() {

		Zone theZone = Zone.getZone(zoneCode);
		if (theZone.key > 0) {
			return theZone.name;
		}
		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getStatus() {

		return status + (isPending ? " *P" : (isArchived ? " *A" : ""));
	}

	public String getSortStatus() {

		return String.valueOf(statusType);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getFileNumber() {

		return filePrefix + appARN;
	}

	public String getARN() {

		return appARN;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getFrequencyOffset() {

		FrequencyOffset theOff = FrequencyOffset.getFrequencyOffset(frequencyOffsetCode);
		if (theOff.key > 0) {
			return theOff.name;
		}
		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getEmissionMask() {

		if (service.serviceType.needsEmissionMask) {
			EmissionMask theMask = EmissionMask.getEmissionMask(emissionMaskCode);
			if (theMask.key > 0) {
				return theMask.name;
			}
		}
		return "";
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getPeakERP() {

		return AppCore.formatERP(((alternateERP > peakERP) ? alternateERP : peakERP)) + " kW";
	}
}
