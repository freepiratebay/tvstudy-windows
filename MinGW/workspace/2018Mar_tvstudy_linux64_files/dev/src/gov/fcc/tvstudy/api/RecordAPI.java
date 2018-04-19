//
//  RecordAPI.java
//  TVStudy
//
//  Copyright (c) 2015-2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.api;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;
import gov.fcc.tvstudy.core.editdata.*;

import java.util.*;
import java.sql.*;
import java.io.*;


//=====================================================================================================================
// APIOperation subclass for creating custom user records.  The OP_START operation returns an input form for entering
// new record parameters.  That form will primarily be populated with parameters found in the request.  If valid
// record ID parameters exists (see SearchAPI), that record will be loaded and used to further populate the form.  The
// edit form sends an OP_SAVE to validate and save the result as a new user record.  Note even if the form was first
// populated from an existing record, this always saves a new record; the records are not editable once saved.  The
// initial request should always have a NEXT_OP parameter, that operation will be chained after a successful save.

public class RecordAPI extends APIOperation {

	public static final String OP_START = "record";
	public static final String OP_SAVE = "recordsave";

	public static final String KEY_FACILITY_ID = "facility_id";
	public static final String KEY_SERVICE = "service";
	public static final String KEY_STATUS = "status";
	public static final String KEY_CALL_SIGN = "call_sign";
	public static final String KEY_CHANNEL = "channel";
	public static final String KEY_CITY = "city";
	public static final String KEY_STATE = "state";

	public static final String KEY_COUNTRY = "country";
	public static final String KEY_ZONE = "zone";
	public static final String KEY_FREQUENCY_OFFSET = "frequency_offset";
	public static final String KEY_EMISSION_MASK = "emission_mask";
	public static final String KEY_LATITUDE_DIRECTION = "latitude_direction";
	public static final String KEY_LATITUDE_DEGREES = "latitude_degrees";
	public static final String KEY_LATITUDE_MINUTES = "latitude_minutes";
	public static final String KEY_LATITUDE_SECONDS = "latitude_seconds";
	public static final String KEY_LONGITUDE_DIRECTION = "longitude_direction";
	public static final String KEY_LONGITUDE_DEGREES = "longitude_degrees";
	public static final String KEY_LONGITUDE_MINUTES = "longitude_minutes";
	public static final String KEY_LONGITUDE_SECONDS = "longitude_seconds";
	public static final String KEY_HEIGHT_AMSL = "height_amsl";
	public static final String KEY_HEIGHT_AAT = "height_aat";
	public static final String KEY_PEAK_ERP = "peak_erp";
	public static final String KEY_COMMENT = "comment";

	// Error logger.

	private ErrorLogger errors;


	//-----------------------------------------------------------------------------------------------------------------

	public static boolean canHandleOperation(String op) {

		return (OP_START.equals(op) || OP_SAVE.equals(op));
	}


	//-----------------------------------------------------------------------------------------------------------------

	public RecordAPI(String op, HashMap<String, String> theParams, String theError) {

		super(op, theParams, theError);

		errors = new ErrorLogger();

		dispatchOperation(op);
	}


	//-----------------------------------------------------------------------------------------------------------------

	protected void dispatchOperation(String op) {

		if (OP_START.equals(op)) {
			doOpStart();
			return;
		}

		if (OP_SAVE.equals(op)) {
			doOpSave();
			return;
		}

		super.dispatchOperation(op);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Compose the page for input of all parameters for a record.

	private void doOpStart() {

		if (null == nextOp) {
			nextOp = SearchAPI.OP_SHOW;
		}

		// If parameters identify an existing record (see SearchAPI) it will be used to initially populate the new
		// record form fields.  However this is always optional, any errors here are silently ignored and the result
		// is just a blank new-record form.  If the source is DTS it is also ignored.  DTS records can't be entered
		// here due to the compound record structure.  Also pattern data cannot currently be entered here, if the
		// existing record has pattern data it is silently ignored.

		SourceEditDataTV source = null;

		SearchAPI.SearchResult theResult = SearchAPI.getSearchResult(parameters);
		if (null != theResult.source) {
			source = theResult.source;
		} else {
			if (null != theResult.record) {
				source = SourceEditDataTV.makeSourceTV(theResult.record, null, true);
			}
		}
		if ((null != source) && source.service.isDTS) {
			source = null;
		}

		// Compose the edit form page.  Use POST due to larger number of properties and free-text comment field.

		StringBuilder page = new StringBuilder();

		addPageHeader(page, "TVStudy - Create User Record", 0, errorMessage);

		if (null != backOp) {
			addFormStart(page, OP_SAVE, backOp, backOpLabel, nextOp, true);
		} else {
			addFormStart(page, OP_SAVE, OP_START, "Create Another Record", nextOp, true);
		}

		page.append("<table>\n");

		String value = parameters.get(KEY_FACILITY_ID);
		if ((null == value) && (null != source)) {
			value = String.valueOf(source.facilityID);
		}
		page.append("<tr><td>Facility ID</td>\n");
		if (null != value) {
			page.append("<td><input type=\"text\" name=\"" + KEY_FACILITY_ID + "\" value=\"" + value +
				"\"></td>\n");
		} else {
			page.append("<td><input type=\"text\" name=\"" + KEY_FACILITY_ID + "\"></td>\n");
		}

		// DTS services are not included in the list, see comments above.

		value = parameters.get(KEY_SERVICE);
		if ((null == value) && (null != source)) {
			value = source.service.serviceCode;
		}
		page.append("<tr><td>Service</td>");
		page.append("<td><select name=\"" + KEY_SERVICE + "\">\n");
		for (Service theService : Service.getAllServices()) {
			if (theService.isDTS) {
				continue;
			}
			if (theService.serviceCode.equals(value)) {
				page.append("<option value=\"" + theService.serviceCode + "\" selected>" + theService.name +
					"</option>\n");
			} else {
				page.append("<option value=\"" + theService.serviceCode + "\">" + theService.name + "</option>\n");
			}
		}
		page.append("</select></td>\n");

		value = parameters.get(KEY_CALL_SIGN);
		if ((null == value) && (null != source)) {
			value = source.callSign;
		}
		page.append("<tr><td>Call sign</td>\n");
		if (null != value) {
			page.append("<td><input type=\"text\" name=\"" + KEY_CALL_SIGN + "\" value=\"" + value + "\"></td>\n");
		} else {
			page.append("<td><input type=\"text\" name=\"" + KEY_CALL_SIGN + "\"></td>\n");
		}

		value = parameters.get(KEY_CHANNEL);
		if ((null == value) && (null != source)) {
			value = String.valueOf(source.channel);
		}
		page.append("<tr><td>Channel</td>\n");
		if (null != value) {
			page.append("<td><input type=\"text\" name=\"" + KEY_CHANNEL + "\" value=\"" + value + "\"></td>\n");
		} else {
			page.append("<td><input type=\"text\" name=\"" + KEY_CHANNEL + "\"></td>\n");
		}

		value = parameters.get(KEY_STATUS);
		if ((null == value) && (null != source)) {
			value = source.status;
		}
		page.append("<tr><td>Status</td>\n");
		page.append("<td><select name=\"" + KEY_STATUS + "\">\n");
		for (KeyedRecord stat : ExtDbRecord.getStatusList()) {
			if (stat.name.equals(value)) {
				page.append("<option value=\"" + stat.name + "\" selected>" + stat.name + "</option>\n");
				value = null;
			} else {
				page.append("<option value=\"" + stat.name + "\">" + stat.name + "</option>\n");
			}
		}
		if (null != value) {
			page.append("<option value=\"" + value + "\" selected>" + value + "</option>\n");
		}
		page.append("</select></td>\n");

		value = parameters.get(KEY_CITY);
		if ((null == value) && (null != source)) {
			value = source.city;
		}
		page.append("<tr><td>City</td>\n");
		if (null != value) {
			page.append("<td><input type=\"text\" name=\"" + KEY_CITY + "\" value=\"" + value + "\"></td>\n");
		} else {
			page.append("<td><input type=\"text\" name=\"" + KEY_CITY + "\"></td>\n");
		}

		value = parameters.get(KEY_STATE);
		if ((null == value) && (null != source)) {
			value = source.state;
		}
		page.append("<tr><td>State</td>\n");
		if (null != value) {
			page.append("<td><input type=\"text\" name=\"" + KEY_STATE + "\" value=\"" + value + "\"></td>\n");
		} else {
			page.append("<td><input type=\"text\" name=\"" + KEY_STATE + "\"></td>\n");
		}
	
		value = parameters.get(KEY_COUNTRY);
		if ((null == value) && (null != source)) {
			value = source.country.countryCode;
		}
		page.append("<tr><td>Country</td>");
		page.append("<td><select name=\"" + KEY_COUNTRY + "\">\n");
		for (Country theCountry : Country.getAllCountries()) {
			if (theCountry.countryCode.equals(value)) {
				page.append("<option value=\"" + theCountry.countryCode + "\" selected>" + theCountry.name +
					"</option>\n");
			} else {
				page.append("<option value=\"" + theCountry.countryCode + "\">" + theCountry.name + "</option>\n");
			}
		}
		page.append("</select></td>\n");

		value = parameters.get(KEY_ZONE);
		if ((null == value) && (null != source)) {
			value = source.zone.zoneCode;
		}
		page.append("<tr><td>Zone</td>");
		page.append("<td><select name=\"" + KEY_ZONE + "\">\n");
		page.append("<option value=\"\">--</option>\n");
		for (Zone theZone : Zone.getAllZones()) {
			if (theZone.zoneCode.equals(value)) {
				page.append("<option value=\"" + theZone.zoneCode + "\" selected>" + theZone.name + "</option>\n");
			} else {
				page.append("<option value=\"" + theZone.zoneCode + "\">" + theZone.name + "</option>\n");
			}
		}
		page.append("</select></td>\n");

		value = parameters.get(KEY_FREQUENCY_OFFSET);
		if ((null == value) && (null != source)) {
			value = source.frequencyOffset.frequencyOffsetCode;
		}
		page.append("<tr><td>Frequency offset</td>");
		page.append("<td><select name=\"" + KEY_FREQUENCY_OFFSET + "\">\n");
		page.append("<option value=\"\">--</option>\n");
		for (FrequencyOffset theOffset : FrequencyOffset.getAllFrequencyOffsets()) {
			if (theOffset.frequencyOffsetCode.equals(value)) {
				page.append("<option value=\"" + theOffset.frequencyOffsetCode + "\" selected>" + theOffset.name +
					"</option>\n");
			} else {
				page.append("<option value=\"" + theOffset.frequencyOffsetCode + "\">" + theOffset.name +
					"</option>\n");
			}
		}
		page.append("</select></td>\n");

		value = parameters.get(KEY_EMISSION_MASK);
		if ((null == value) && (null != source)) {
			value = source.emissionMask.emissionMaskCode;
		}
		page.append("<tr><td>Emission mask</td>");
		page.append("<td><select name=\"" + KEY_EMISSION_MASK + "\">\n");
		page.append("<option value=\"\">--</option>\n");
		for (EmissionMask theMask : EmissionMask.getAllEmissionMasks()) {
			if (theMask.emissionMaskCode.equals(value)) {
				page.append("<option value=\"" + theMask.emissionMaskCode + "\" selected>" + theMask.name +
					"</option>\n");
			} else {
				page.append("<option value=\"" + theMask.emissionMaskCode + "\">" + theMask.name + "</option>\n");
			}
		}
		page.append("</select></td>\n");

		page.append("<tr><td>Latitude</td>\n");
		value = parameters.get(KEY_LATITUDE_DIRECTION);
		if ((null == value) && (null != source)) {
			if (0 == source.location.latitudeNS) {
				value = "N";
			} else {
				value = "S";
			}
		}
		page.append("<td><select name=\"" + KEY_LATITUDE_DIRECTION + "\">\n");
		if ((null != value) && value.equalsIgnoreCase("N")) {
			page.append("<option value=\"N\" selected>N</option>\n");
		} else {
			page.append("<option value=\"N\">N</option>\n");
		}
		if ((null != value) && value.equalsIgnoreCase("S")) {
			page.append("<option value=\"S\" selected>S</option>\n");
		} else {
			page.append("<option value=\"S\">S</option>\n");
		}
		value = parameters.get(KEY_LATITUDE_DEGREES);
		if ((null == value) && (null != source)) {
			value = String.valueOf(source.location.latitudeDegrees);
		}
		if (null != value) {
			page.append("<input type=\"text\" name=\"" + KEY_LATITUDE_DEGREES + "\" value=\"" + value +
				"\" size=\"6\"> deg\n");
		} else {
			page.append("<input type=\"text\" name=\"" + KEY_LATITUDE_DEGREES + "\" size=\"6\"> deg\n");
		}
		value = parameters.get(KEY_LATITUDE_MINUTES);
		if ((null == value) && (null != source)) {
			value = String.valueOf(source.location.latitudeMinutes);
		}
		if (null != value) {
			page.append("<input type=\"text\" name=\"" + KEY_LATITUDE_MINUTES + "\" value=\"" + value +
				"\" size=\"6\"> min\n");
		} else {
			page.append("<input type=\"text\" name=\"" + KEY_LATITUDE_MINUTES + "\" size=\"6\"> min\n");
		}
		value = parameters.get(KEY_LATITUDE_SECONDS);
		if ((null == value) && (null != source)) {
			value = AppCore.formatSeconds(source.location.latitudeSeconds);
		}
		if (null != value) {
			page.append("<input type=\"text\" name=\"" + KEY_LATITUDE_SECONDS + "\" value=\"" + value +
				"\" size=\"6\"> sec</td>\n");
		} else {
			page.append("<input type=\"text\" name=\"" + KEY_LATITUDE_SECONDS + "\" size=\"6\"> sec</td>\n");
		}

		page.append("<tr><td>Longitude</td>\n");
		value = parameters.get(KEY_LONGITUDE_DIRECTION);
		if ((null == value) && (null != source)) {
			if (0 == source.location.longitudeWE) {
				value = "W";
			} else {
				value = "E";
			}
		}
		page.append("<td><select name=\"" + KEY_LONGITUDE_DIRECTION + "\">\n");
		if ((null != value) && value.equalsIgnoreCase("W")) {
			page.append("<option value=\"W\" selected>W</option>\n");
		} else {
			page.append("<option value = \"W\">W</option>\n");
		}
		if ((null != value) && value.equalsIgnoreCase("E")) {
			page.append("<option value=\"E\" selected>E</option>\n");
		} else {
			page.append("<option value = \"E\">E</option>\n");
		}
		value = parameters.get(KEY_LONGITUDE_DEGREES);
		if ((null == value) && (null != source)) {
			value = String.valueOf(source.location.longitudeDegrees);
		}
		if (null != value) {
			page.append("<input type=\"text\" name=\"" + KEY_LONGITUDE_DEGREES + "\" value=\"" + value +
				"\" size=\"6\"> deg\n");
		} else {
			page.append("<input type=\"text\" name=\"" + KEY_LONGITUDE_DEGREES + "\" size=\"6\"> deg\n");
		}
		value = parameters.get(KEY_LONGITUDE_MINUTES);
		if ((null == value) && (null != source)) {
			value = String.valueOf(source.location.longitudeMinutes);
		}
		if (null != value) {
			page.append("<input type=\"text\" name=\"" + KEY_LONGITUDE_MINUTES + "\" value=\"" + value +
				"\" size=\"6\"> min\n");
		} else {
			page.append("<input type=\"text\" name=\"" + KEY_LONGITUDE_MINUTES + "\" size=\"6\"> min\n");
		}
		value = parameters.get(KEY_LONGITUDE_SECONDS);
		if ((null == value) && (null != source)) {
			value = AppCore.formatSeconds(source.location.longitudeSeconds);
		}
		if (null != value) {
			page.append("<input type=\"text\" name=\"" + KEY_LONGITUDE_SECONDS + "\" value=\"" + value +
				"\" size=\"6\"> sec</td>\n");
		} else {
			page.append("<input type=\"text\" name=\"" + KEY_LONGITUDE_SECONDS + "\" size=\"6\"> sec</td>\n");
		}

		value = parameters.get(KEY_HEIGHT_AMSL);
		if ((null == value) && (null != source)) {
			value = AppCore.formatHeight(source.heightAMSL);
		}
		page.append("<tr><td>Height AMSL</td>\n");
		if (null != value) {
			page.append("<td><input type=\"text\" name=\"" + KEY_HEIGHT_AMSL + "\" value=\"" + value +
				"\"></td>\n");
		} else {
			page.append("<td><input type=\"text\" name=\"" + KEY_HEIGHT_AMSL + "\"></td>\n");
		}

		value = parameters.get(KEY_HEIGHT_AAT);
		if ((null == value) && (null != source)) {
			value = AppCore.formatHeight(source.overallHAAT);
		}
		page.append("<tr><td>HAAT</td>\n");
		if (null != value) {
			page.append("<td><input type=\"text\" name=\"" + KEY_HEIGHT_AAT + "\" value=\"" + value +
				"\"></td>\n");
		} else {
			page.append("<td><input type=\"text\" name=\"" + KEY_HEIGHT_AAT + "\"></td>\n");
		}

		value = parameters.get(KEY_PEAK_ERP);
		if ((null == value) && (null != source)) {
			value = AppCore.formatERP(source.peakERP);
		}
		page.append("<tr><td>Peak ERP</td>\n");
		if (null != value) {
			page.append("<td><input type=\"text\" name=\"" + KEY_PEAK_ERP + "\" value=\"" + value + "\"></td>\n");
		} else {
			page.append("<td><input type=\"text\" name=\"" + KEY_PEAK_ERP + "\"></td>\n");
		}

		page.append("<tr><td>Comment</td>\n");
		page.append("<td><textarea rows=\"5\" cols=\"40\" name=\"" + KEY_COMMENT + "\"></textarea></td>\n");

		page.append("</table>\n");

		addFormEnd(page, "Save Record");

		// Add a back button if an operation is set.

		if (null != backOp) {
			addFormStart(page, backOp);
			SearchAPI.addHiddenFields(page, parameters);
			addFormEnd(page, backOpLabel);
		}

		addPageFooter(page);

		resultPage = page.toString();
		status = STATUS_PAGE;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Set up a new source from request parameters.

	private void doOpSave() {

		if (null == nextOp) {
			nextOp = SearchAPI.OP_SHOW;
		}

		// Facility ID, service, and country must all be present.

		int facilityID = 0;
		String value = parameters.get(KEY_FACILITY_ID);
		if (null != value) {
			try {
				facilityID = Integer.parseInt(value);
			} catch (NumberFormatException ne) {
				handleError("ERROR: Bad facility ID, must be a number", OP_START);
				return;
			}
		} else {
			handleError("ERROR: Missing facility ID", OP_START);
			return;
		}

		Service service = null;
		value = parameters.get(KEY_SERVICE);
		if (null != value) {
			service = Service.getService(value);
			if (null == service) {
				handleError("ERROR: Bad service code", OP_START);
				return;
			}
		} else {
			handleError("ERROR: Missing service code", OP_START);
			return;
		}

		Country country = null;
		value = parameters.get(KEY_COUNTRY);
		if (null != value) {
			country = Country.getCountry(value);
			if (null == country) {
				handleError("ERROR: Bad country code", OP_START);
				return;
			}
		} else {
			handleError("ERROR: Missing country code", OP_START);
			return;
		}

		// Create the new source object.

		SourceEditDataTV source =
			SourceEditDataTV.createSource(null, dbID, facilityID, service, false, country, false, errors);
		if (null == source) {
			handleError(errors.toString(), OP_START);
			return;
		}

		// Check remaining parameters.  Call sign is required, something must be provided but content is arbitrary.

		value = parameters.get(KEY_CALL_SIGN);
		if (null != value) {
			source.callSign = value.toUpperCase();
		} else {
			handleError("ERROR: Missing call sign", OP_START);
			return;
		}

		// Channel is required and must be in valid range.

		value = parameters.get(KEY_CHANNEL);
		if (null != value) {
			source.channel = 0;
			try {
				source.channel = Integer.parseInt(value);
			} catch (NumberFormatException ne) {
			}
			if ((source.channel < SourceTV.CHANNEL_MIN) || (source.channel > SourceTV.CHANNEL_MAX)) {
				handleError("ERROR: Bad channel number", OP_START);
				return;
			}
		} else {
			handleError("ERROR: Missing channel number", OP_START);
			return;
		}

		// Status is optional, when provided set status type if it matches, but content may be arbitrary as well.

		value = parameters.get(KEY_STATUS);
		if (null != value) {
			source.status = value.toUpperCase();
			source.statusType = ExtDbRecord.getStatusType(value);
		}

		// City name is required but arbitrary.

		value = parameters.get(KEY_CITY);
		if (null != value) {
			source.city = value.toUpperCase();
		} else {
			handleError("ERROR: Missing city name", OP_START);
			return;
		}

		// State also required but arbitrary.

		value = parameters.get(KEY_STATE);
		if (null != value) {
			source.state = value.toUpperCase();
		} else {
			handleError("ERROR: Missing state code", OP_START);
			return;
		}

		// Zone is optional, however if present it must be valid.

		value = parameters.get(KEY_ZONE);
		if (null != value) {
			source.zone = Zone.getZone(value);
			if (source.zone.key < 1) {
				handleError("ERROR: Bad zone code", OP_START);
				return;
			}
		}

		// Frequency offset optional but must be valid if present.

		value = parameters.get(KEY_FREQUENCY_OFFSET);
		if (null != value) {
			source.frequencyOffset = FrequencyOffset.getFrequencyOffset(value);
			if (source.frequencyOffset.key < 1) {
				handleError("ERROR: Bad frequency offset code", OP_START);
				return;
			}
		}

		// Emission mask depends on service type, if needed it must be present and valid, if not it is ignored.

		if (source.service.serviceType.needsEmissionMask) {
			value = parameters.get(KEY_EMISSION_MASK);
			if (null != value) {
				source.emissionMask = EmissionMask.getEmissionMask(value);
				if (source.emissionMask.key < 1) {
					handleError("ERROR: Bad emission mask code", OP_START);
					return;
				}
			} else {
				handleError("ERROR: Missing emission mask code", OP_START);
				return;
			}
		}

		// Latitude and longitude are always required.  However the related parameters for latitude, or for longitude,
		// must appear as a set; if the first property exists all others must also exist, new record or not.

		value = parameters.get(KEY_LATITUDE_DIRECTION);
		if (null != value) {

			source.location.latitudeNS = -1;
			if (value.equalsIgnoreCase("N")) {
				source.location.latitudeNS = 0;
			} else {
				if (value.equalsIgnoreCase("S")) {
					source.location.latitudeNS = 1;
				}
			}
			if (source.location.latitudeNS < 0) {
				handleError("ERROR: Bad latitude direction", OP_START);
				return;
			}

			value = parameters.get(KEY_LATITUDE_DEGREES);
			if (null != value) {
				source.location.latitudeDegrees = (int)(Source.LATITUDE_MIN - 1.);
				try {
					source.location.latitudeDegrees = Integer.parseInt(value);
				} catch (NumberFormatException ne) {
				}
				if ((source.location.latitudeDegrees < Source.LATITUDE_MIN) ||
						(source.location.latitudeDegrees > Source.LATITUDE_MAX)) {
					handleError("ERROR: Bad latitude degrees", OP_START);
					return;
				}
			} else {
				handleError("ERROR: Missing latitude degrees", OP_START);
				return;
			}

			value = parameters.get(KEY_LATITUDE_MINUTES);
			if (null != value) {
				source.location.latitudeMinutes = -1;
				try {
					source.location.latitudeMinutes = Integer.parseInt(value);
				} catch (NumberFormatException ne) {
				}
				if ((source.location.latitudeMinutes < 0) || (source.location.latitudeMinutes > 59)) {
					handleError("ERROR: Bad latitude minutes", OP_START);
					return;
				}
			} else {
				handleError("ERROR: Missing latitude minutes", OP_START);
				return;
			}

			value = parameters.get(KEY_LATITUDE_SECONDS);
			if (null != value) {
				source.location.latitudeSeconds = -1.;
				try {
					source.location.latitudeSeconds = Double.parseDouble(value);
				} catch (NumberFormatException ne) {
				}
				if ((source.location.latitudeSeconds < 0.) || (source.location.latitudeSeconds >= 60.)) {
					handleError("ERROR: Bad latitude seconds", OP_START);
					return;
				}
			} else {
				handleError("ERROR: Missing latitude seconds", OP_START);
				return;
			}

		} else {
			handleError("ERROR: Missing latitude", OP_START);
			return;
		}

		value = parameters.get(KEY_LONGITUDE_DIRECTION);
		if (null != value) {

			source.location.longitudeWE = -1;
			if (value.equalsIgnoreCase("W")) {
				source.location.longitudeWE = 0;
			} else {
				if (value.equalsIgnoreCase("E")) {
					source.location.longitudeWE = 1;
				}
			}
			if (source.location.longitudeWE < 0) {
				handleError("ERROR: Bad longitude direction", OP_START);
				return;
			}

			value = parameters.get(KEY_LONGITUDE_DEGREES);
			if (null != value) {
				source.location.longitudeDegrees = (int)(Source.LONGITUDE_MIN - 1.);
				try {
					source.location.longitudeDegrees = Integer.parseInt(value);
				} catch (NumberFormatException ne) {
				}
				if ((source.location.longitudeDegrees < Source.LONGITUDE_MIN) ||
						(source.location.longitudeDegrees > Source.LONGITUDE_MAX)) {
					handleError("ERROR: Bad longitude degrees", OP_START);
					return;
				}
			} else {
				handleError("ERROR: Missing longitude degrees", OP_START);
				return;
			}

			value = parameters.get(KEY_LONGITUDE_MINUTES);
			if (null != value) {
				source.location.longitudeMinutes = -1;
				try {
					source.location.longitudeMinutes = Integer.parseInt(value);
				} catch (NumberFormatException ne) {
				}
				if ((source.location.longitudeMinutes < 0) || (source.location.longitudeMinutes > 59)) {
					handleError("ERROR: Bad longitude minutes", OP_START);
					return;
				}
			} else {
				handleError("ERROR: Missing longitude minutes", OP_START);
				return;
			}

			value = parameters.get(KEY_LONGITUDE_SECONDS);
			if (null != value) {
				source.location.longitudeSeconds = -1.;
				try {
					source.location.longitudeSeconds = Double.parseDouble(value);
				} catch (NumberFormatException ne) {
				}
				if ((source.location.longitudeSeconds < 0.) || (source.location.longitudeSeconds >= 60.)) {
					handleError("ERROR: Bad longitude seconds", OP_START);
					return;
				}
			} else {
				handleError("ERROR: Missing longitude seconds", OP_START);
				return;
			}

		} else {
			handleError("ERROR: Missing longitude", OP_START);
			return;
		}

		source.location.updateLatLon();

		// AMSL height is required, but it may be the HEIGHT_DERIVE value to trigger derivation from the HAAT.  That
		// is not checked for specifically as that value is always in the legal range.

		value = parameters.get(KEY_HEIGHT_AMSL);
		if (null != value) {
			source.heightAMSL = Source.HEIGHT_MIN - 1.;
			try {
				source.heightAMSL = Double.parseDouble(value);
			} catch (NumberFormatException ne) {
			}
			if ((source.heightAMSL < Source.HEIGHT_MIN) || (source.heightAMSL > Source.HEIGHT_MAX)) {
				handleError("ERROR: Bad AMSL height", OP_START);
				return;
			}
		} else {
			handleError("ERROR: Missing AMSL height", OP_START);
			return;
		}

		// HAAT is required, but it may be HEIGHT_DERIVE as for AMSL so it will be computed using the AMSL.  Both can
		// be derived, in which case the AMSL height is set to the minimum transmitter AGL height.

		value = parameters.get(KEY_HEIGHT_AAT);
		if (null != value) {
			source.overallHAAT = Source.HEIGHT_MIN - 1.;
			try {
				source.overallHAAT = Double.parseDouble(value);
			} catch (NumberFormatException ne) {
			}
			if ((source.overallHAAT < Source.HEIGHT_MIN) || (source.overallHAAT > Source.HEIGHT_MAX)) {
				handleError("ERROR: Bad HAAT", OP_START);
				return;
			}
		} else {
			handleError("ERROR: Missing HAAT", OP_START);
			return;
		}

		// Peak ERP is required.

		value = parameters.get(KEY_PEAK_ERP);
		if (null != value) {
			source.peakERP = Source.ERP_MIN - 1.;
			try {
				source.peakERP = Double.parseDouble(value);
			} catch (NumberFormatException ne) {
			}
			if ((source.peakERP < Source.ERP_MIN) || (source.peakERP > Source.ERP_MAX)) {
				handleError("ERROR: Bad peak ERP", OP_START);
				return;
			}
		} else {
			handleError("ERROR: Missing peak ERP", OP_START);
			return;
		}

		// Save the new record data, assigning a new user record ID.  The save will also do a full validity check.

		source = (SourceEditDataTV)source.saveAsUserRecord(errors);
		if (null == source) {
			if (errors.hasErrors()) {
				handleError(errors.toString(), OP_START);
			} else {
				handleError("ERROR: Could not save new user record", OP_START);
			}
			return;
		}

		// Auxiliary comment property, see SourceEditData.

		value = parameters.get(KEY_COMMENT);
		if (null != value) {
			SourceEditData.setSourceComment(source, value);
		}

		// Put the new record ID in the request parameters then chain.

		parameters.put(SearchAPI.KEY_EXT_DB_KEY, "0");
		parameters.put(SearchAPI.KEY_USER_RECORD_ID, String.valueOf(source.userRecordID));
		chainToOperation(nextOp);
	}
}
