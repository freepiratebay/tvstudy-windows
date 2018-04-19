//
//  Template.java
//  TVStudy
//
//  Copyright (c) 2015-2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core.data;

import gov.fcc.tvstudy.core.*;

import java.util.*;
import java.sql.*;


//=====================================================================================================================
// Class for study templates.

// The permanent flag prevents the template from being deleted or exported.  It is only set by installation code in
// DbController, it cannot be set or cleared through the UI.  The purpose is to make some templates, particularily the
// default template which must always be present, appear as part of the software installation.

// The locked flag prevents the template from being edited in the template editor.  If the permanent flag is set, the
// locked flag will always be set as well.  However locked may be set without permanent, and locked may be set through
// the UI.  But once set, locked cannot be cleared again.  A locked template represents a fixed starting point for
// future studies, so initial conditions are known.  That allows automatic study runs to cache results.

// The locked_in_study flag prevents parameters and rules from being edited within any study based on the template,
// ensuring any future study using the template always has exactly the same settings.  The locked_in_study flag can
// only be set if locked is set, and like locked, once set it cannot be cleared.  Also it cannot be set if locked is
// already set, they must be set together in the same UI action.

public class Template extends KeyedRecord {

	// Database ID.

	public final String dbID;

	// Database properties:

	// key (super)      Template key, unique, always > 0.
	// name (super)     Template name, never null or empty.
	// isPermanent      True means template cannot be deleted or exported.
	// isLocked         True means template cannot be edited in the template editor.
	// isLockedInStudy  True means parameters and rules set by template cannot be modified in a study.
	// parameters       List of study parameters in the template.
	// ixRules          List of interference rules in the template.

	public final boolean isPermanent;
	public final boolean isLocked;
	public final boolean isLockedInStudy;
	public final ArrayList<Parameter> parameters;
	public final ArrayList<IxRule> ixRules;


	//-----------------------------------------------------------------------------------------------------------------

	public Template(String theDbID, int theKey, String theName, boolean thePermanent, boolean theLocked,
			boolean theLockedInStudy, ArrayList<Parameter> theParameters, ArrayList<IxRule> theIxRules) {

		super(theKey, theName);

		dbID = theDbID;

		isPermanent = thePermanent;
		isLocked = theLocked;
		isLockedInStudy = theLockedInStudy;
		parameters = theParameters;
		ixRules = theIxRules;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Load a template by key.

	public static Template getTemplate(String theDbID, int theKey) {
		return getTemplate(theDbID, theKey, null);
	}

	public static Template getTemplate(String theDbID, int theKey, ErrorLogger errors) {

		String rootName = DbCore.getDbName(theDbID);

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null == db) {
			return null;
		}

		Template result = null;

		boolean error = false, thePermanent = false, theLocked = false, theLockedInStudy = false;
		String errmsg = "", theName = String.valueOf(theKey), theTemplateName = "";
		int errtyp = AppCore.ERROR_MESSAGE;

		try {

			db.query(
			"SELECT " +
				"name, " +
				"permanent, " +
				"locked, " +
				"locked_in_study " +
			"FROM " +
				"template " +
			"WHERE " +
				"template_key = " + theKey);

			if (db.next()) {

				theTemplateName = db.getString(1);
				theName = "'" + theTemplateName + "'";
				thePermanent = db.getBoolean(2);
				theLocked = db.getBoolean(3);
				theLockedInStudy = db.getBoolean(4);

				result = new Template(
					theDbID,
					theKey,
					theTemplateName,
					thePermanent,
					theLocked,
					theLockedInStudy,
					Parameter.getParameters(db, null, rootName, theKey, 0),
					IxRule.getIxRules(db, null, rootName, theKey, 0));

			} else {
				error = true;
				errmsg = "The template does not exist.";
			}

		} catch (SQLException se) {
			error = true;
			errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
			db.reportError(se);
		}

		DbCore.releaseDb(db);

		if (error) {
			if (null != errors) {
				errors.reportError("Could not load template " + theName + ":\n" + errmsg, errtyp);
			}
			return null;
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Find the template key for a template name, return 0 if not found, -1 on error.

	public static int getTemplateKeyForName(String theDbID, String theName) {
		return getTemplateKeyForName(theDbID, theName, null);
	}

	public static int getTemplateKeyForName(String theDbID, String theName, ErrorLogger errors) {

		int result = -1;

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null != db) {
			try {

				db.query("SELECT template_key FROM template WHERE UPPER(name) = '" +
					db.clean(theName.toUpperCase()) + "'");
				if (db.next()) {
					result = db.getInt(1);
				} else {
					result = 0;
				}

				DbCore.releaseDb(db);

			} catch (SQLException se) {
				DbCore.releaseDb(db);
				DbConnection.reportError(errors, se);
			}
		}

		return result;
	}


	//=================================================================================================================
	// Info class provides basic information about a template without having to load the whole model.

	public static class Info extends KeyedRecord {

		public boolean isLocked;
		public boolean isLockedInStudy;


		//-------------------------------------------------------------------------------------------------------------

		public Info(int theKey, String theName) {

			super(theKey, theName);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------

	public static Info getTemplateInfo(String theDbID, int theKey) {
		return getTemplateInfo(theDbID, theKey, null);
	}

	public static Info getTemplateInfo(String theDbID, int theKey, ErrorLogger errors) {

		Info result = null;

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null != db) {
			try {

				db.query("SELECT name, locked, locked_in_study FROM template WHERE template_key = " +
					String.valueOf(theKey));
				if (db.next()) {
					result = new Info(theKey, db.getString(1));
					result.isLocked = db.getBoolean(2);
					result.isLockedInStudy = db.getBoolean(3);
				}

				DbCore.releaseDb(db);

			} catch (SQLException se) {
				DbCore.releaseDb(db);
				result = null;
				DbConnection.reportError(errors, se);
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get a list of Info objects for all templates cast as KeyedRecord, caller can cast back to get info.

	public static ArrayList<KeyedRecord> getTemplateInfoList(String theDbID) {
		return getTemplateInfoList(theDbID, null);
	}

	public static ArrayList<KeyedRecord> getTemplateInfoList(String theDbID, ErrorLogger errors) {

		ArrayList<KeyedRecord> result = null;

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null != db) {
			try {

				result = new ArrayList<KeyedRecord>();
				Info theInfo;

				db.query("SELECT template_key, name, locked, locked_in_study FROM template ORDER BY 1");

				while (db.next()) {

					theInfo = new Info(db.getInt(1), db.getString(2));
					theInfo.isLocked = db.getBoolean(3);
					theInfo.isLockedInStudy = db.getBoolean(4);

					result.add((KeyedRecord)theInfo);
				}

				DbCore.releaseDb(db);

			} catch (SQLException se) {
				DbCore.releaseDb(db);
				result = null;
				DbConnection.reportError(errors, se);
			}
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Duplicate a template.  The locked and locked-in-study flags are always set false on the duplicate.  Those can
	// be set true in the template editor, however once set the template becomes non-editable hence the user needs the
	// chance to edit the template first.  See TemplateEditor.  Of course permanent is always false, only database
	// installation code is allowed to create permanent templates.

	public static Integer duplicateTemplate(String theDbID, int oldKey, String newName) {
		return duplicateTemplate(theDbID, oldKey, newName, null);
	}

	public static Integer duplicateTemplate(String theDbID, int oldKey, String newName, ErrorLogger errors) {

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null == db) {
			return null;
		}

		boolean error = false;
		String errmsg = "";

		int newKey = 0;

		try {

			db.update("LOCK TABLES template WRITE, template_key_sequence WRITE, " +
				"template_parameter_data WRITE, template_parameter_data AS old_param READ, " +
				"template_ix_rule WRITE, template_ix_rule AS old_rule READ");

			db.query("SELECT template_key FROM template WHERE template_key = " + oldKey);

			if (db.next()) {

				db.query("SELECT template_key FROM template WHERE UPPER(name) = '" + db.clean(newName.toUpperCase()) +
					"'");

				if (!db.next()) {

					db.update("UPDATE template_key_sequence SET template_key = template_key + 1");
					db.query("SELECT template_key FROM template_key_sequence");
					db.next();
					newKey = db.getInt(1);

					db.update(
					"INSERT INTO template (" +
						"template_key, " +
						"name, " +
						"permanent, " +
						"locked, " +
						"locked_in_study) " +
					"VALUES (" +
						newKey + ", " +
						"'" + db.clean(newName) + "', " +
						"false, " +
						"false, " +
						"false" +
					")");

					db.update(
					"INSERT INTO template_parameter_data ("+
						"template_key, " +
						"parameter_key, " +
						"value_index, " +
						"value) " +
					"SELECT " +
						newKey + ", " +
						"old_param.parameter_key, " +
						"old_param.value_index, " +
						"old_param.value " +
					"FROM " +
						"template_parameter_data AS old_param " +
					"WHERE " +
						"old_param.template_key = " + oldKey);

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
					"SELECT " +
						newKey + ", " +
						"old_rule.ix_rule_key, " +
						"old_rule.country_key, " +
						"old_rule.service_type_key, " +
						"old_rule.signal_type_key, " +
						"old_rule.undesired_service_type_key, " +
						"old_rule.undesired_signal_type_key, " +
						"old_rule.channel_delta_key, " +
						"old_rule.channel_band_key, " +
						"old_rule.frequency_offset, " +
						"old_rule.emission_mask_key, " +
						"old_rule.distance, " +
						"old_rule.required_du, " +
						"old_rule.undesired_time " +
					"FROM " +
						"template_ix_rule AS old_rule " +
					"WHERE " +
						"old_rule.template_key = " + oldKey);

				} else {
					error = true;
					errmsg = "Template name '" + newName + "' already exists.";
				}

			} else {
				error = true;
				errmsg = "Template key " + oldKey + "does not exist.";
			}

		} catch (SQLException se) {
			error = true;
			errmsg = DbConnection.ERROR_TEXT_PREFIX + se;
			db.reportError(se);
		}

		try {
			if (error && (newKey > 0)) {
				db.update("DELETE FROM template WHERE template_key = " + newKey);
				db.update("DELETE FROM template_parameter_data WHERE template_key = " + newKey);
				db.update("DELETE FROM template_ix_rule WHERE template_key = " + newKey);
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

		return Integer.valueOf(newKey);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Delete a template.  This will re-check the permanent and in-use settings of course.

	public static boolean deleteTemplate(String theDbID, int templateKey) {
		return deleteTemplate(theDbID, templateKey, null);
	}

	public static boolean deleteTemplate(String theDbID, int templateKey, ErrorLogger errors) {

		DbConnection db = DbCore.connectDb(theDbID, errors);
		if (null == db) {
			return false;
		}

		boolean error = false;
		String errmsg = "";
		int refCount = 0;

		try {

			db.update("LOCK TABLES template WRITE, template_parameter_data WRITE, template_ix_rule WRITE, " +
				"study WRITE");

			db.query("SELECT COUNT(*) FROM study WHERE template_key = " + templateKey);
			db.next();
			refCount = db.getInt(1);

			if (0 == refCount) {

				db.query("SELECT permanent FROM template WHERE template_key = " + templateKey);
				if (db.next()) {

					if (!db.getBoolean(1)) {

						db.update("DELETE FROM template WHERE template_key = " + templateKey);
						db.update("DELETE FROM template_parameter_data WHERE template_key = " + templateKey);
						db.update("DELETE FROM template_ix_rule WHERE template_key = " + templateKey);

					} else {
						error = true;
						errmsg = "The template is marked permanent and cannot be deleted.";
					}
				}

			} else {
				error = true;
				errmsg = "The template is still in use and cannot be deleted.";
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
		}

		return !error;
	}
}
