//
//  TemplateEditData.java
//  TVStudy
//
//  Copyright (c) 2012-2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.editdata;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;

import java.util.*;
import java.util.regex.*;
import java.sql.*;
import java.io.*;

import javax.xml.parsers.*;

import org.xml.sax.*;
import org.xml.sax.helpers.*;


//=====================================================================================================================
// Model class for editing study templates, see TemplateEditor.  This is patterned on a study editor model, stripped
// down to provide only parameters and rules.  See StudyEditData.  As with a study, the template object will never be
// null; this does not have a state to hold a new template never saved to the database.  The only way new templates
// come into existence is by duplicating an existing template directly in the database, or importing a template from
// XML, again directly into the database.  This only has to support editing an existing database object.

public class TemplateEditData {

	public final String dbID;

	public Template template;

	private int newIxRuleKey;

	public String name;
	public final ArrayList<ParameterEditData> parameters;
	public final IxRuleListData ixRuleData;


	//-----------------------------------------------------------------------------------------------------------------

	public TemplateEditData(String theID, Template theTemplate) {

		dbID = theID;

		template = theTemplate;

		newIxRuleKey = 0;
		for (IxRule theRule : template.ixRules) {
			if (theRule.key > newIxRuleKey) {
				newIxRuleKey = theRule.key;
			}
		}

		name = template.name;

		parameters = new ArrayList<ParameterEditData>();
		for (Parameter theParameter : template.parameters) {
			parameters.add(new ParameterEditData(theParameter, template.isLocked));
		}

		ixRuleData = new IxRuleListData(template.ixRules, template.isLocked);
	}


	//-----------------------------------------------------------------------------------------------------------------

	public Integer getNewIxRuleKey() {

		if (++newIxRuleKey <= 0) {
			newIxRuleKey = -1;
			throw new RuntimeException("Interference rule key range exhausted");
		}
		return Integer.valueOf(newIxRuleKey);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// A locked template cannot be edited, so it is always valid and can never have any changes.

	public boolean isDataValid() {
		return isDataValid(null);
	}

	public boolean isDataValid(ErrorLogger errors) {

		if (template.isLocked) {
			return true;
		}

		if (!ixRuleData.isDataValid(errors)) {
			return false;
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public boolean isDataChanged() {

		if (template.isLocked) {
			return false;
		}

		if (ixRuleData.isDataChanged()) {
			return true;
		}

		for (ParameterEditData theParam : parameters) {
			if (theParam.isDataChanged()) {
				return true;
			}
		}

		if (!name.equals(template.name)) {
			return true;
		}

		return false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Save template data.

	public synchronized boolean save() {
		return save(null);
	}

	public synchronized boolean save(ErrorLogger errors) {

		DbConnection db = DbCore.connectDb(dbID, errors);
		if (null != db) {

			boolean error = false;
			String errmsg = "";

			try {

				// Lock the template tables, confirm the template still exists.

				db.update("LOCK TABLES template WRITE, template_parameter_data WRITE, template_ix_rule WRITE");

				db.query("SELECT template_key FROM template WHERE template_key = " + template.key);
				if (!db.next()) {
					error = true;
					errmsg = "Template save failed, template does not exist.";
				}

				// Do the save.  Parameter and rule data are saved by deleting all existing records and inserting new
				// ones.  There is no persistent lock so no protection against concurrent edits, this has to be an
				// atomic last-save-wins protocol.  The interference rule keys are generated sequentially during the
				// save.  Those are use only when copying template rules to a new study after which there is no link
				// between rules in that study and the template.

				if (!error) {

					db.update("DELETE FROM template_parameter_data WHERE template_key = " + template.key);
					int valueIndex;
					for (ParameterEditData theParameter : parameters) {
						for (valueIndex = 0; valueIndex < theParameter.parameter.valueCount; valueIndex++) {
							db.update(
							"INSERT INTO template_parameter_data (" +
								"template_key," +
								"parameter_key," +
								"value_index," +
								"value) " +
							"VALUES (" +
								template.key + "," +
								theParameter.parameter.key + "," +
								valueIndex + "," +
								"'" + db.clean(theParameter.value[valueIndex]) + "')");
						}
						theParameter.didSave();
					}

					db.update("DELETE FROM template_ix_rule WHERE template_key = " + template.key);
					int nextRuleKey = 0;
					for (IxRuleEditData theRule : ixRuleData.getRows()) {
						db.update(
						"INSERT INTO template_ix_rule (" +
							"template_key, " +
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
							"undesired_time) " +
						"VALUES (" +
							template.key + "," +
							(++nextRuleKey) + "," +
							theRule.country.key + "," +
							theRule.serviceType.key + "," +
							theRule.signalType.key + "," +
							theRule.undesiredServiceType.key + "," +
							theRule.undesiredSignalType.key + "," +
							theRule.channelDelta.key + "," +
							theRule.channelBand.key + "," +
							theRule.frequencyOffset + "," +
							theRule.emissionMask.key + "," +
							theRule.distance + "," +
							theRule.requiredDU + "," +
							theRule.undesiredTime + ")");
						theRule.didSave();
					}
					ixRuleData.didSave();

					if (!name.equals(template.name)) {
						db.query("SELECT template_key FROM template WHERE UPPER(name) = '" +
							db.clean(name.toUpperCase()) + "' AND template_key <> " + template.key);
						if (db.next()) {
							errors.logMessage(
								"Name change was not saved, a template with the new name already exists.");
							name = template.name;
						} else {
							db.update("UPDATE template SET name = '" + db.clean(name) + "' WHERE template_key = " +
								template.key);
						}
					}

					ArrayList<Parameter> theParameters = new ArrayList<Parameter>();
					for (ParameterEditData theParameter : parameters) {
						theParameters.add(theParameter.parameter);
					}

					template = new Template(dbID, template.key, name, template.isPermanent, template.isLocked,
						template.isLockedInStudy, theParameters, ixRuleData.getRules());
				}

			} catch (SQLException se) {
				error = true;
				errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
				db.reportError(se);
			}

			try {
				db.update("UNLOCK TABLES");
			} catch (SQLException se) {
				db.reportError(se);
			}

			DbCore.releaseDb(db);

			if (error) {
				if (null != errors) {
					errors.reportError(errmsg);
				}
				return false;
			}

		} else {
			return false;
		}

		return true;
	}


	//=================================================================================================================
	// Private data classes for holding just the information for parameters and rules that are part of a template,
	// these minimal data-only classes are used to streamline import and export of template data, and creating a new
	// template from an existing study.

	private static class TemplateParameter {

		private int key;
		private String[] value;


		//-------------------------------------------------------------------------------------------------------------

		private TemplateParameter(int theKey, ArrayList<String> theValues) {

			key = theKey;
			value = new String[theValues.size()];
			for (int valueIndex = 0; valueIndex < value.length; valueIndex++) {
				value[valueIndex] = theValues.get(valueIndex);
			}
		}


		//-------------------------------------------------------------------------------------------------------------

		private TemplateParameter(ParameterEditData theParam) {

			key = theParam.parameter.key;
			value = new String[theParam.parameter.valueCount];
			for (int valueIndex = 0; valueIndex < value.length; valueIndex++) {
				value[valueIndex] = theParam.value[valueIndex];
			}
		}
	}


	//=================================================================================================================
	// These do not have a key; the rule keys in a template are abritrary and so are assigned sequentially at save.

	private static class TemplateIxRule {

		private Country country;
		private ServiceType serviceType;
		private SignalType signalType;
		private ServiceType undesiredServiceType;
		private SignalType undesiredSignalType;
		private ChannelDelta channelDelta;
		private ChannelBand channelBand;
		private int frequencyOffset;
		private EmissionMask emissionMask;
		private double distance;
		private double requiredDU;
		private double undesiredTime;


		//-------------------------------------------------------------------------------------------------------------

		private TemplateIxRule() {
		}


		//-------------------------------------------------------------------------------------------------------------

		private TemplateIxRule(IxRuleEditData theIxRule) {

			country = theIxRule.country;
			serviceType = theIxRule.serviceType;
			signalType = theIxRule.signalType;
			undesiredServiceType = theIxRule.undesiredServiceType;
			undesiredSignalType = theIxRule.undesiredSignalType;
			channelDelta = theIxRule.channelDelta;
			channelBand = theIxRule.channelBand;
			frequencyOffset = theIxRule.frequencyOffset;
			emissionMask = theIxRule.emissionMask;
			distance = theIxRule.distance;
			requiredDU = theIxRule.requiredDU;
			undesiredTime = theIxRule.undesiredTime;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Save a new template from an existing study editor model.

	public static Integer createNewTemplateFromStudy(String theName, StudyEditData theStudy) {
		return createNewTemplateFromStudy(theName, theStudy, null);
	}

	public static Integer createNewTemplateFromStudy(String theName, StudyEditData theStudy, ErrorLogger errors) {

		ArrayList<TemplateParameter> theParameters = new ArrayList<TemplateParameter>(theStudy.parameters.size());
		for (ParameterEditData theParameter : theStudy.parameters) {
			theParameters.add(new TemplateParameter(theParameter));
		}

		ArrayList<IxRuleEditData> theStudyRules = theStudy.ixRuleData.getRows();
		ArrayList<TemplateIxRule> theRules = new ArrayList<TemplateIxRule>(theStudyRules.size());
		for (IxRuleEditData theIxRule : theStudyRules) {
			theRules.add(new TemplateIxRule(theIxRule));
		}

		return createNewTemplate(theStudy.dbID, theName, false, false, theParameters, theRules, errors);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Create a new template.  This checks to be sure the name is unique, if not this will change the name of the
	// existing template by adding the key.  That is done for correct behavior on import of templates with names that
	// are recognized for automatic study builds, i.e. interference-check studies, so those special templates can be
	// updated without requiring manual renaming.  This can save templates with locked and locked_in_study set, but
	// that will occur only when importing from XML.  See TemplateEditor for details.  The permanent flag will always
	// be false, only installation code can create permanent templates.  Return is the new key, or null on error.

	private static Integer createNewTemplate(String theDbID, String theName, boolean theLocked, boolean theStudyLocked,
			ArrayList<TemplateParameter> theParameters, ArrayList<TemplateIxRule> theRules, ErrorLogger errors) {

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null == db) {
			return null;
		}

		boolean error = false;
		String errmsg = "";

		int theKey = 0, valueIndex;

		try {

			db.update("LOCK TABLES template WRITE, template_key_sequence WRITE, template_parameter_data WRITE, " +
				"template_ix_rule WRITE");

			db.query("SELECT template_key FROM template WHERE UPPER(name) = '" + db.clean(theName.toUpperCase()) +
				"'");
			if (db.next()) {
				theKey = db.getInt(1);
				db.update("UPDATE template SET name = '" + db.clean(theName) + " (" + String.valueOf(theKey) + ")' " +
					"WHERE template_key = " + String.valueOf(theKey));
			}

			db.update("UPDATE template_key_sequence SET template_key = template_key + 1");
			db.query("SELECT template_key FROM template_key_sequence");
			db.next();
			theKey = db.getInt(1);

			db.update(
			"INSERT INTO template (" +
				"template_key, " +
				"name, " +
				"permanent, " +
				"locked, " +
				"locked_in_study) " +
			"VALUES (" +
				theKey + ", " +
				"'" + db.clean(theName) + "', " +
				"false, " +
				theLocked + ", " +
				theStudyLocked +
			")");

			for (TemplateParameter theParameter : theParameters) {
				for (valueIndex = 0; valueIndex < theParameter.value.length; valueIndex++) {
					if (null != theParameter.value[valueIndex]) {
						db.update(
						"INSERT INTO template_parameter_data (" +
							"template_key," +
							"parameter_key," +
							"value_index," +
							"value) " +
						"VALUES (" +
							theKey + "," +
							theParameter.key + "," +
							valueIndex + "," +
							"'" + db.clean(theParameter.value[valueIndex]) + "')");
					}
				}
			}

			int nextRuleKey = 0;
			for (TemplateIxRule theIxRule : theRules) {
				db.update(
				"INSERT INTO template_ix_rule (" +
					"template_key, " +
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
					"undesired_time) " +
				"VALUES (" +
					theKey + "," +
					(++nextRuleKey) + "," +
					theIxRule.country.key + "," +
					theIxRule.serviceType.key + "," +
					theIxRule.signalType.key + "," +
					theIxRule.undesiredServiceType.key + "," +
					theIxRule.undesiredSignalType.key + "," +
					theIxRule.channelDelta.key + "," +
					theIxRule.channelBand.key + "," +
					theIxRule.frequencyOffset + "," +
					theIxRule.emissionMask.key + "," +
					theIxRule.distance + "," +
					theIxRule.requiredDU + "," +
					theIxRule.undesiredTime + ")");
			}

		} catch (SQLException se) {
			error = true;
			errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
			db.reportError(se);
		}

		try {
			if (error && (theKey > 0)) {
				db.update("DELETE FROM template WHERE template_key = " + theKey);
				db.update("DELETE FROM template_parameter_data WHERE template_key = " + theKey);
				db.update("DELETE FROM template_ix_rule WHERE template_key = " + theKey);
			}
			db.update("UNLOCK TABLES");
		} catch (SQLException se) {
			db.reportError(se);
		}

		DbCore.releaseDb(db);

		if (error) {
			if (null != errors) {
				errors.reportError(errmsg);
			}
			return null;
		}

		return Integer.valueOf(theKey);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Write a template to an XML file, first load the parameters and rules from the database.  This will not export a
	// permanent template, but it will export locked templates.  See comments in Template.

	public static boolean writeTemplateToXML(String theDbID, int templateKey, Writer xml) {
		return writeTemplateToXML(theDbID, templateKey, xml, null);
	}

	public static boolean writeTemplateToXML(String theDbID, int templateKey, Writer xml, ErrorLogger errors) {

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null == db) {
			return false;
		}

		boolean error = false;
		String errmsg = "";

		String name = "";
		boolean isLocked = false, isStudyLocked = false;
		ArrayList<TemplateParameter> parameters = new ArrayList<TemplateParameter>();
		ArrayList<TemplateIxRule> ixRules = new ArrayList<TemplateIxRule>();

		int paramKey, lastParamKey = 0, valueIndex;
		ArrayList<String> paramValues = new ArrayList<String>();
		TemplateIxRule theIxRule;

		try {

			db.update("LOCK TABLES template READ, template_parameter_data READ, template_ix_rule READ");

			db.query("SELECT permanent, name, locked, locked_in_study FROM template WHERE template_key = " +
				templateKey);
			if (db.next()) {

				if (!db.getBoolean(1)) {

					name = db.getString(2);
					isLocked = db.getBoolean(3);
					isStudyLocked = db.getBoolean(4);

					db.query(
					"SELECT " +
						"parameter_key," +
						"value_index," +
						"value " +
					"FROM " +
						"template_parameter_data " +
					"WHERE " +
						"template_key = " + templateKey + " " +
					"ORDER BY 1, 2");
	
					while (db.next()) {
						paramKey = db.getInt(1);
						if (paramKey > lastParamKey) {
							if (lastParamKey > 0) {
								parameters.add(new TemplateParameter(lastParamKey, paramValues));
							}
							lastParamKey = paramKey;
							paramValues.clear();
						}
						valueIndex = db.getInt(2);
						if (valueIndex >= paramValues.size()) {
							for (int i = paramValues.size(); i <= valueIndex; i++) {
								paramValues.add(null);
							}
						}
						paramValues.set(valueIndex, db.getString(3));
					}
					if (lastParamKey > 0) {
						parameters.add(new TemplateParameter(lastParamKey, paramValues));
					}

					db.query(
					"SELECT " +
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
						"undesired_time " +
					"FROM " +
						"template_ix_rule " +
					"WHERE " +
						"template_key = " + templateKey);

					while (db.next()) {
						theIxRule = new TemplateIxRule();
						theIxRule.country = Country.getCountry(db.getInt(1));
						theIxRule.serviceType = ServiceType.getServiceType(db.getInt(2));
						theIxRule.signalType = SignalType.getSignalType(db.getInt(3));
						theIxRule.undesiredServiceType = ServiceType.getServiceType(db.getInt(4));
						theIxRule.undesiredSignalType = SignalType.getSignalType(db.getInt(5));
						theIxRule.channelDelta = ChannelDelta.getChannelDelta(db.getInt(6));
						if ((null == theIxRule.country) || (null == theIxRule.serviceType) ||
								(null == theIxRule.signalType) || (null == theIxRule.undesiredServiceType) ||
								(null == theIxRule.undesiredSignalType) || (null == theIxRule.channelDelta)) {
							continue;
						}
						theIxRule.channelBand = ChannelBand.getChannelBand(db.getInt(7));
						theIxRule.frequencyOffset = db.getInt(8);
						theIxRule.emissionMask = EmissionMask.getEmissionMask(db.getInt(9));
						theIxRule.distance = db.getDouble(10);
						theIxRule.requiredDU = db.getDouble(11);
						theIxRule.undesiredTime = db.getDouble(12);
						ixRules.add(theIxRule);
					}

				} else {
					error = true;
					errmsg = "The template cannot be exported.";
				}

			} else {
				error = true;
				errmsg = "The template does not exist.";
			}

		} catch (SQLException se) {
			error = true;
			errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
			db.reportError(se);
		}

		try {
			db.update("UNLOCK TABLES");
		} catch (SQLException se) {
			db.reportError(se);
		}

		DbCore.releaseDb(db);

		if (error) {
			if (null != errors) {
				errors.reportError(errmsg);
			}
			return false;
		}

		// Write the data.

		try {

			xml.append("<TEMPLATE NAME=\"" + name + "\" LOCKED=\"" + isLocked + "\" STUDYLOCKED=\"" + isStudyLocked +
				"\">\n");

			for (TemplateParameter theParam : parameters) {
				xml.append("<PARAMETER KEY=\"" + theParam.key + "\">\n");
				for (valueIndex = 0; valueIndex < theParam.value.length; valueIndex++) {
					if (null != theParam.value[valueIndex]) {
						xml.append("<VALUE INDEX=\"" + valueIndex + "\">" +
							AppCore.xmlclean(theParam.value[valueIndex]) + "</VALUE>\n");
					}
				}
				xml.append("</PARAMETER>\n");
			}

			for (TemplateIxRule theRule : ixRules) {
				xml.append("<RULE COUNTRY=\"" + theRule.country.key + '"');
				xml.append(" DSERVICE=\"" + theRule.serviceType.key + '"');
				if (theRule.signalType.key > 0) {
					xml.append(" DMOD_TYPE=\"" + theRule.signalType.key + '"');
				}
				xml.append(" USERVICE=\"" + theRule.undesiredServiceType.key + '"');
				if (theRule.undesiredSignalType.key > 0) {
					xml.append(" UMOD_TYPE=\"" + theRule.undesiredSignalType.key + '"');
				}
				xml.append(" CHANNEL=\"" + theRule.channelDelta.delta + '"');
				if (theRule.channelBand.key > 0) {
					xml.append(" BAND=\"" + theRule.channelBand.key + '"');
				}
				if (theRule.frequencyOffset > 0) {
					xml.append(" OFFSET=\"" + theRule.frequencyOffset + '"');
				}
				if (theRule.emissionMask.key > 0) {
					xml.append(" MASK=\"" + theRule.emissionMask.key + '"');
				}
				xml.append(" DISTANCE=\"" + AppCore.formatDistance(theRule.distance) + '"');
				xml.append(" DU=\"" + AppCore.formatDU(theRule.requiredDU) + '"');
				xml.append(" UTIME=\"" + AppCore.formatPercent(theRule.undesiredTime) + "\"/>\n");
			}

			xml.append("</TEMPLATE>\n");

		} catch (IOException ie) {
			if (null != errors) {
				errors.reportError("Could not write to the file:\n" + ie.getMessage());
			}
			return false;
		}

		return true;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Parse an XML source to load a template (or templates, multiple TEMPLATE elements are allowed but are not
	// expected hence the method name is singular) and save directly to the database.  All the work is done in the
	// parsing handler.  Return is the key of the last template imported, null on error.

	public static Integer readTemplateFromXML(String theDbID, Reader xml) {
		return readTemplateFromXML(theDbID, xml, null);
	}

	public static Integer readTemplateFromXML(String theDbID, Reader xml, ErrorLogger errors) {

		if (null != errors) {
			errors.clearErrors();
		} else {
			errors = new ErrorLogger(null, null);
		}

		TemplateXMLHandler handler = new TemplateXMLHandler(theDbID, errors);
		try {
			XMLReader xReader = XMLReaderFactory.createXMLReader("org.apache.xerces.parsers.SAXParser");
			xReader.setContentHandler(handler);
			xReader.parse(new InputSource(xml));
		} catch (SAXException se) {
			String msg = se.getMessage();
			if ((null != msg) && (msg.length() > 0)) {
				errors.reportError("XML error: " + msg);
			}
		} catch (Throwable t) {
			AppCore.log(AppCore.ERROR_MESSAGE, "Unexpected error", t);
			errors.reportError("An unexpected error occurred:\n" + t);
		}

		return handler.templateKey;
	}


	//=================================================================================================================
	// XML parsing handler.  This will parse until one complete TEMPLATE element has been processed, commit that
	// directly to the database using createNewTemplate(), then continue for additional templates.

	private static class TemplateXMLHandler extends DefaultHandler {

		private String dbID;
		private ErrorLogger errors;

		// Accumulated state for the template being parsed.

		private String templateName;
		private boolean isLocked;
		private boolean isLockedInStudy;
		private ArrayList<TemplateParameter> parameters;
		private ArrayList<TemplateIxRule> ixRules;

		// Temporary properties used during parsing.

		private int parameterKey = 0;
		private int valueIndex = -1;
		private ArrayList<String> parameterValues = new ArrayList<String>();

		private TemplateIxRule ixRule;

		// Stack of element names and content buffers for nested elements.

		private ArrayDeque<String> elements;
		private ArrayDeque<StringWriter> buffers;

		// Key for the last template successfully imported, or null.

		private Integer templateKey;


		//-------------------------------------------------------------------------------------------------------------
		// All errors are send to the ErrorLogger using reportError().  After reporting an error the parsing methods
		// will throw an exception to abort parsing, but the exception itself does not have a specific error message.

		private TemplateXMLHandler(String theDbID, ErrorLogger theErrors) {

			super();

			dbID = theDbID;
			errors = theErrors;

			parameters = new ArrayList<TemplateParameter>();
			ixRules = new ArrayList<TemplateIxRule>();

			elements = new ArrayDeque<String>();
			buffers = new ArrayDeque<StringWriter>();
		}


		//-------------------------------------------------------------------------------------------------------------
		// Start of element.  Push the element name and a new content buffer on to the stacks.

		public void startElement(String nameSpc, String locName, String qName, Attributes attrs) throws SAXException {

			elements.push(qName);
			buffers.push(new StringWriter());

			// Start of a new template.  Error if parsing is already inside a TEMPLATE element.  The NAME attribute is
			// required, it will be checked for uniqueness later and made unique if needed.  The LOCKED attribute is
			// is optional, isLocked defaults to false.  If LOCKED is absent or it is false, the STUDYLOCKED attribute
			// is ignored, isLockedInStudy is also false.  If LOCKED is present and true, isLockedInStudy defaults to
			// true, for that to become false STUDYLOCKED must explicitly set it false.  This logic is because older
			// exports actually had LOCKED representing the isLockedInStudy state since templates with isLocked could
			// never be exported.  That no-export condition is now indicated by the template isPermanent flag, and the
			// former behavior of isLockedInStudy splits into the current isLocked and isLockedInStudy.  See Template.

			if (qName.equals("TEMPLATE")) {

				if (templateName != null) {
					errors.reportError("TEMPLATE elements may not be nested");
					throw new SAXException();
				}

				templateName = attrs.getValue("NAME");
				if (null == templateName) {
					errors.reportError("Missing NAME attribute in TEMPLATE tag");
					throw new SAXException();
				}

				String str = attrs.getValue("LOCKED");
				isLocked = ((null != str) && Boolean.valueOf(str).booleanValue());
				if (isLocked) {
					str = attrs.getValue("STUDYLOCKED");
					isLockedInStudy = ((null == str) || Boolean.valueOf(str).booleanValue());
				} else {
					isLockedInStudy = false;
				}

				return;
			}

			// A PARAMETER element must be inside a TEMPLATE, and cannot be nested.  The only attribute is the key,
			// that must be present, and it is assumed that will match a parameter in the database parameter table.
			// However that is not checked.  The keys are only checked for general validity (> 0) and for uniqueness
			// within this template.  If the template contains parameter keys that do not exist in the database those
			// will be included, however those are ignored when the template is actually used to create a study (not
			// worth the trouble to identify and ignore them here).

			if (qName.equals("PARAMETER")) {

				if (null == templateName) {
					errors.reportError("PARAMETER element must be inside a TEMPLATE element");
					throw new SAXException();
				}
				if (parameterKey > 0) {
					errors.reportError("PARAMETER elements cannot be nested");
					throw new SAXException();
				}

				String str = attrs.getValue("KEY");
				if (null != str) {
					try {
						parameterKey = Integer.parseInt(str);
					} catch (NumberFormatException nfe) {
					}
				}
				if (parameterKey <= 0) {
					errors.reportError("Missing or bad KEY attribute in PARAMETER tag");
					throw new SAXException();
				}

				for (TemplateParameter theParameter : parameters) {
					if (parameterKey == theParameter.key) {
						errors.reportError("Duplicate parameter key in template");
						throw new SAXException();
					}
				}

				parameterValues.clear();

				return;
			}

			// A VALUE element provides one value for a parameter, identified by 0..N index, these must only be inside
			// a PARAMETER and cannot be nested.  Indices may be out of sequence and not all represented, the value
			// list will be expanded by adding nulls as needed.  Also this does not complain if multiple values appear
			// for the same index, later values replace previous ones.  A parameter may not have any VALUE elements,
			// a single value may be enclosed directly as the PARAMETER element content, see endElement().

			if (qName.equals("VALUE")) {

				if (parameterKey <= 0) {
					errors.reportError("VALUE element must be inside a PARAMETER element");
					throw new SAXException();
				}
				if (valueIndex >= 0) {
					errors.reportError("VALUE elements cannot be nested");
					throw new SAXException();
				}

				String str = attrs.getValue("INDEX");
				if (null != str) {
					try {
						valueIndex = Integer.parseInt(str);
					} catch (NumberFormatException nfe) {
					}
				}
				if ((valueIndex < 0) || (valueIndex >= Parameter.MAX_VALUE_COUNT)) {
					errors.reportError("Missing or bad INDEX attribute in VALUE tag");
					throw new SAXException();
				}

				if (valueIndex >= parameterValues.size()) {
					for (int i = parameterValues.size(); i <= valueIndex; i++) {
						parameterValues.add(null);
					}
				}

				return;
			}

			// A RULE element has all properties in the attributes.  Like PARAMETER this must be inside TEMPLATE and
			// cannot be nested.  Here the key values are arbitrary and are assigned sequentially.  Some attributes
			// are required, others have defaults.

			if (qName.equals("RULE")) {

				if (null == templateName) {
					errors.reportError("RULE element must be inside a TEMPLATE element");
					throw new SAXException();
				}
				if (null != ixRule) {
					errors.reportError("RULE elements cannot be nested");
					throw new SAXException();
				}

				ixRule = new TemplateIxRule();

				String str = attrs.getValue("COUNTRY");
				if (null != str) {
					try {
						ixRule.country = Country.getCountry(Integer.parseInt(str));
					} catch (NumberFormatException nfe) {
					}
				}
				if (null == ixRule.country) {
					errors.reportError("Missing or bad COUNTRY attribute in RULE tag");
					throw new SAXException();
				}

				str = attrs.getValue("DSERVICE");
				if (null != str) {
					try {
						ixRule.serviceType = ServiceType.getServiceType(Integer.parseInt(str));
					} catch (NumberFormatException nfe) {
					}
				}
				if (null == ixRule.serviceType) {
					errors.reportError("Missing or bad DSERVICE attribute in RULE tag");
					throw new SAXException();
				}

				if (ixRule.serviceType.digital) {
					str = attrs.getValue("DMOD_TYPE");
					if (null == str) {
						ixRule.signalType = SignalType.getDefaultObject();
					} else {
						int typ = 0;
						try {
							typ = Integer.parseInt(str);
						} catch (NumberFormatException ne) {
						}
						ixRule.signalType = SignalType.getSignalType(typ);
						if (ixRule.signalType.key < 1) {
							errors.reportError("Bad DMOD_TYPE attribute in RULE tag.");
							throw new SAXException();
						}
					}
				} else {
					ixRule.signalType = SignalType.getNullObject();
				}

				str = attrs.getValue("USERVICE");
				if (null != str) {
					try {
						ixRule.undesiredServiceType = ServiceType.getServiceType(Integer.parseInt(str));
					} catch (NumberFormatException nfe) {
					}
				}
				if (null == ixRule.undesiredServiceType) {
					errors.reportError("Missing or bad USERVICE attribute in RULE tag");
					throw new SAXException();
				}

				if (ixRule.serviceType.recordType != ixRule.undesiredServiceType.recordType) {
					errors.reportError("Invalid RULE tag, DSERVICE and USERVICE record types do not match");
					throw new SAXException();
				}

				if (ixRule.undesiredServiceType.digital) {
					str = attrs.getValue("UMOD_TYPE");
					if (null == str) {
						ixRule.undesiredSignalType = SignalType.getDefaultObject();
					} else {
						int typ = 0;
						try {
							typ = Integer.parseInt(str);
						} catch (NumberFormatException ne) {
						}
						ixRule.undesiredSignalType = SignalType.getSignalType(typ);
						if (ixRule.undesiredSignalType.key < 1) {
							errors.reportError("Bad UMOD_TYPE attribute in RULE tag.");
							throw new SAXException();
						}
					}
				} else {
					ixRule.undesiredSignalType = SignalType.getNullObject();
				}

				str = attrs.getValue("CHANNEL");
				if (null != str) {
					try {
						ixRule.channelDelta = ChannelDelta.getChannelDeltaByDelta(ixRule.serviceType.recordType,
							Integer.parseInt(str));
					} catch (NumberFormatException nfe) {
					}
				}
				if (null == ixRule.channelDelta) {
					errors.reportError("Missing or bad CHANNEL attribute in RULE tag");
					throw new SAXException();
				}

				str = attrs.getValue("BAND");
				if ((null != str) && (Source.RECORD_TYPE_TV == ixRule.serviceType.recordType)) {
					try {
						ixRule.channelBand = ChannelBand.getChannelBand(Integer.parseInt(str));
					} catch (NumberFormatException nfe) {
					}
					if (null == ixRule.channelBand) {
						errors.reportError("Bad BAND attribute in RULE tag");
						throw new SAXException();
					}
				} else {
					ixRule.channelBand = ChannelBand.getNullObject();
				}

				str = attrs.getValue("OFFSET");
				if ((null != str) && (Source.RECORD_TYPE_TV == ixRule.serviceType.recordType)) {
					try {
						ixRule.frequencyOffset = Integer.parseInt(str);
					} catch (NumberFormatException nfe) {
					}
					if ((IxRule.FREQUENCY_OFFSET_WITHOUT != ixRule.frequencyOffset) &&
							(IxRule.FREQUENCY_OFFSET_WITH != ixRule.frequencyOffset)) {
						errors.reportError("Bad OFFSET attribute in RULE tag");
						throw new SAXException();
					}
				}

				str = attrs.getValue("MASK");
				if ((null != str) && (Source.RECORD_TYPE_TV == ixRule.serviceType.recordType)) {
					try {
						ixRule.emissionMask = EmissionMask.getEmissionMask(Integer.parseInt(str));
					} catch (NumberFormatException nfe) {
					}
					if (null == ixRule.emissionMask) {
						errors.reportError("Bad BAND attribute in RULE tag");
						throw new SAXException();
					}
				} else {
					ixRule.emissionMask = EmissionMask.getNullObject();
				}

				ixRule.distance = IxRule.DISTANCE_MIN - 1.;
				str = attrs.getValue("DISTANCE");
				if (null != str) {
					try {
						ixRule.distance = Double.parseDouble(str);
					} catch (NumberFormatException nfe) {
					}
				}
				if ((ixRule.distance < IxRule.DISTANCE_MIN) || (ixRule.distance > IxRule.DISTANCE_MAX)) {
					errors.reportError("Missing or bad DISTANCE attribute in RULE tag");
					throw new SAXException();
				}

				ixRule.requiredDU = IxRule.DU_MIN - 1.;
				str = attrs.getValue("DU");
				if (null != str) {
					try {
						ixRule.requiredDU = Double.parseDouble(str);
					} catch (NumberFormatException nfe) {
					}
				}
				if ((ixRule.requiredDU < IxRule.DU_MIN) || (ixRule.requiredDU > IxRule.DU_MAX)) {
					errors.reportError("Missing or bad DU attribute in RULE tag");
					throw new SAXException();
				}

				ixRule.undesiredTime = IxRule.PERCENT_TIME_MIN - 1.;
				str = attrs.getValue("UTIME");
				if (null != str) {
					try {
						ixRule.undesiredTime = Double.parseDouble(str);
					} catch (NumberFormatException nfe) {
					}
				}
				if ((ixRule.undesiredTime < IxRule.PERCENT_TIME_MIN) ||
						(ixRule.undesiredTime > IxRule.PERCENT_TIME_MAX)) {
					errors.reportError("Missing or bad UTIME attribute in RULE tag");
					throw new SAXException();
				}

				return;
			}

			// Any other element type is an error.

			errors.reportError("Unknown element '" + qName + "'");
			throw new SAXException();
		}


		//-------------------------------------------------------------------------------------------------------------
		// Add content characters to the buffer for the current element.

		public void characters(char[] ch, int start, int length) {

			if (!buffers.isEmpty()) {
				buffers.peek().write(ch, start, length);
			}
		}


		//-------------------------------------------------------------------------------------------------------------
		// End of element, pop the element name and content off the stacks, check for overlap (the superclass may do
		// that, I'm not sure, but it doesn't hurt to check again).

		public void endElement(String nameSpc, String locName, String qName) throws SAXException {

			String element = elements.pop();
			String content = buffers.pop().toString().trim();

			if (!element.equals(qName)) {
				errors.reportError("Overlapping elements not allowed");
				throw new SAXException();
			}

			// The end of a TEMPLATE element.  Must contain at least one parameter and one rule.  Content ignored.  If
			// all is well, create the new template in the database.

			if (element.equals("TEMPLATE")) {

				if (parameters.isEmpty() || ixRules.isEmpty()) {
					errors.reportError("TEMPLATE element must contain parameters and rules");
					throw new SAXException();
				}

				templateKey = createNewTemplate(dbID, templateName, isLocked, isLockedInStudy, parameters, ixRules,
					errors);
				if (null == templateKey) {
					throw new SAXException();
				}

				// Clear all state for the next TEMPLATE element.

				templateName = null;
				isLocked = false;
				isLockedInStudy = false;
				parameters.clear();
				ixRules.clear();

				parameterKey = 0;
				valueIndex = -1;
				parameterValues.clear();

				ixRule = null;

				return;
			}

			// If the parameter value list is empty, there were no VALUE elements enclosed.  That means this is an
			// older export made before multi-value parameters existed.  In that case, all parameters are single-value
			// and the value is the PARMETER element content itself.  However the content must be non-empty, if it is
			// empty assume the newer syntax and report it as a missing VALUE element.

			if (element.equals("PARAMETER")) {

				if (parameterValues.isEmpty()) {
					if (0 == content.length()) {
						errors.reportError("PARAMETER element must have at least one VALUE");
						throw new SAXException();
					}
					parameterValues.add(content);
				}

				parameters.add(new TemplateParameter(parameterKey, parameterValues));

				parameterKey = 0;
				valueIndex = -1;
				parameterValues.clear();

				return;
			}

			// Parameter value is just the VALUE element content.

			if (element.equals("VALUE")) {

				parameterValues.set(valueIndex, content);

				valueIndex = -1;

				return;
			}

			// Rule was fully processed at the element start.  Content ignored.

			if (element.equals("RULE")) {

				ixRules.add(ixRule);

				ixRule = null;

				return;
			}
		}
	}
}
