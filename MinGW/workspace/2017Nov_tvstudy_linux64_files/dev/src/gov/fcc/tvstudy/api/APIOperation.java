//
//  APIOperation.java
//  TVStudy
//
//  Copyright (c) 2015-2017 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.api;

import gov.fcc.tvstudy.core.*;
import gov.fcc.tvstudy.core.data.*;

import java.util.*;
import java.io.*;
import java.nio.file.*;
import java.sql.*;


//====================================================================================================================
// Superclass for objects implementing API operations for a servlet.  New concrete instances are obtained from the
// getOperation() factory method.  Generally that will return a subclass instance appropriate to the request, however
// some operations are implemented here including a root menu and errors that occur before subclass identification.

public class APIOperation {

	// Standard file names for results page and log file; use is optional, subclass may customize if desired.

	public static final String INDEX_FILE_NAME = "index.html";
	public static final String LOG_FILE_NAME = "log.txt";

	// Status codes returned from getStatus().

	public static final int STATUS_UNKNOWN = 0;   // Unknown status.
	public static final int STATUS_ERROR = 1;     // Operation failed, use getErrorMessage().
	public static final int STATUS_PAGE = 2;      // Operation running or complete, use getResultPage().
	public static final int STATUS_URL = 3;       // Operation running or complete, use getResultURL().
	public static final int STATUS_CHAIN = 4;     // Operation chained to another, use getChainOperation().

	// In any valid request a known OP parameter must be present, see canHandleOperation().  If OP is null or unknown
	// a root menu page is returned and all other parameters are ignored.  If OP is set, BACK_OP and NEXT_OP may also
	// be present for navigation and chaining.  The back operation is often tied to a form submit button so the button
	// label is provided with BACK_OP_LABEL.  The back operation will also be used to report errors.  Operations used
	// for BACK_OP should return a page that displays the errorMessage property if non-null (see e.g. addPageHeader()),
	// along with other content allowing the user to continue navigating the API.  If a back operation is not specified
	// a STATUS_ERROR result is used to report errors, but that should be a last resort because it is a navigational
	// dead-end.  A NEXT_OP is used to chain directly to another operation when the requested operation completes
	// successfully.

	public static final String KEY_OP = "op";
	public static final String KEY_BACK_OP = "back_op";
	public static final String KEY_BACK_OP_LABEL = "back_op_label";
	public static final String KEY_NEXT_OP = "next_op";

	// The only known op implemented here.

	public static final String OP_MENU = "menu";

	// See servletInit() and servletDestroy().

	private static final HashSet<Object> servlets = new HashSet<Object>();

	protected static String dbID;
	protected static String outRootPath;

	// Instance properties.

	protected HashMap<String, String> parameters;

	protected String backOp;
	protected String backOpLabel;
	protected String nextOp;

	protected String errorMessage;

	protected int status;
	protected String resultPage;
	protected String resultURL;
	protected APIOperation chainOperation;

	// Reload time on in-progress index page.

	protected static final int IN_PROGRESS_RELOAD_TIME = 3;   // seconds


	//-----------------------------------------------------------------------------------------------------------------
	// This must be called when a servlet instance is initialized, there is one-time-only setup done for a new JVM,
	// and some setup that can be repeated if there are no other instances of any servlet in existence.  Return null
	// for success, else an error message; in the case of an error the servlet instance must fail to initialize.  On
	// the first call do one-time JVM setup; load the JDBC driver in case that is in the servlet's lib directory and
	// was not visible when DriverManager initialized.  Then set the working directory for the TVStudy core code, that
	// cannot be changed again in this JVM.

	private static boolean didCoreInit = false;

	public static synchronized String servletInit(Object theServlet, String theWorkPath, String theOutPath) {

		if (servlets.contains(theServlet)) {
			return "ERROR: Servlet instance has already been initialized";
		}

		boolean isNewVM = false;

		if (!didCoreInit) {

			isNewVM = true;

			try {
				Class.forName("com.mysql.jdbc.Driver").newInstance();
				Class.forName("org.postgresql.Driver").newInstance();
			} catch (Throwable t) {
				return "ERROR: Could not load database driver: " + t;
			}

			AppCore.initialize(theWorkPath, false, true);

			didCoreInit = true;

		} else {

			if (!AppCore.workingDirectoryPath.equals(theWorkPath)) {
				return "ERROR: Cannot change working directory";
			}
		}

		if (null != AppCore.fileCheckError) {
			return "ERROR: " + AppCore.fileCheckError;
		}
		if (AppCore.maxEngineProcessCount < 0) {
			return "ERROR: Study engine is not installed or not functioning";
		}
		if (0 == AppCore.maxEngineProcessCount) {
			return "ERROR: Insufficient memory available, study engine cannot run";
		}

		// As long as other servlet instances are still running the output directory and database login cannot be
		// changed, just add to the servlet count.  Once all other instances are destroyed this can re-initialize.

		if (!servlets.isEmpty()) {

			if (!outRootPath.equals(theOutPath)) {
				return "ERROR: Cannot change output directory";
			}

			servlets.add(theServlet);
			return null;
		}

		outRootPath = theOutPath;

		// Get database login credentials from a local properties file.  This is separate from the AppCore properties
		// because the password is stored in cleartext.  There is no UI for these properties, the file must be created
		// manually (with permissions set to protect the password).

		Properties props = new Properties();
		try {
			props.load(new FileInputStream(AppCore.libDirectoryPath + File.separator + AppCore.API_PROPS_FILE_NAME));
		} catch (IOException e) {
			return "ERROR: Could not load database login properties: " + e;
		}
		String theHost = props.getProperty("host");
		String theName = props.getProperty("name");
		String theUser = props.getProperty("user");
		String thePass = props.getProperty("pass");
		if ((null == theHost) || (null == theUser) || (null == thePass)) {
			return "ERROR: Missing database login properties";
		}
		if (null == theName) {
			theName = DbCore.DEFAULT_DB_NAME;
		}

		// Open the database.

		DbCore.DbInfo theInfo = new DbCore.DbInfo(theHost, theName, theUser, thePass);
		ErrorLogger errors = new ErrorLogger();
		if (!DbCore.openDb(theInfo, errors)) {
			return "ERROR: Could not open database: " + errors.toString();
		}

		// If this is a new JVM, run startup operations in subclasses as needed.

		if (isNewVM) {
			IxCheckAPI.servletStartup(theInfo.dbID);
		}

		// Success.

		dbID = theInfo.dbID;

		servlets.add(theServlet);
		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Called when a servlet instance is destroyed, if it is the last one running close the database connection so the
	// next init will re-load database login credentials and open a new connection.

	public static synchronized void servletDestroy(Object theServlet) {

		servlets.remove(theServlet);

		if (servlets.isEmpty() && (null != dbID)) {
			DbCore.closeDb(dbID);
			dbID = null;
		}
	}

 
	//-----------------------------------------------------------------------------------------------------------------
	// Create instance to handle a new request.  Copy parameters to a more convenient map since all parameters are
	// single-valued, ignoring any that have empty values (whitespace is empty).  Get the OP parameter along the way.

	public static APIOperation getOperation(Map<String, String[]> requestParams) {

		HashMap<String, String> theParams = new HashMap<String, String>();
		String op = null;

		if (null == dbID) {
			return new APIOperation(op, theParams, "ERROR: Database connection has not been initialized");
		}

		Iterator<String> it = requestParams.keySet().iterator();
		String name, theValue;
		String[] value;
		while (it.hasNext()) {
			name = it.next();
			value = requestParams.get(name);
			if ((null != value) && (value.length > 0)) {
				theValue = value[0].trim();
				if (theValue.length() > 0) {
					if (KEY_OP.equals(name)) {
						op = theValue;
					} else {
						theParams.put(name, theValue);
					}
				}
			}
		}

		return getOperation(op, theParams, null);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Get an operation subclass object, used directly when chaining, see chainToOperation().  If no op is given
	// use OP_MENU, that is not considered an error, a no-parameters URL is a valid way to load the menu page.  If the
	// operation is unknown that is an error and also shows OP_MENU.

	protected static APIOperation getOperation(String op, HashMap<String, String> theParams, String theError) {

		if (null == op) {
			return new APIOperation(OP_MENU, theParams, theError);
		}

		if (canHandleOperation(op)) {
			return new APIOperation(op, theParams, theError);
		}

		if (SearchAPI.canHandleOperation(op)) {
			return new SearchAPI(op, theParams, theError);
		}

		if (RecordAPI.canHandleOperation(op)) {
			return new RecordAPI(op, theParams, theError);
		}

		if (IxCheckAPI.canHandleOperation(op)) {
			return new IxCheckAPI(op, theParams, theError);
		}

		return new APIOperation(OP_MENU, theParams, "ERROR: Unknown operation '" + op + "'");
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Subclasses must override this to return true for supported operations.

	public static boolean canHandleOperation(String op) {

		return OP_MENU.equals(op);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Subclass constructors must at least accept an operation, parameters map, and error message string, and pass
	// them all to super.  The menu operation is implemented here.

	public APIOperation(String op, HashMap<String, String> theParams, String theError) {

		parameters = theParams;
		errorMessage = theError;

		if (OP_MENU.equals(op)) {

			StringBuilder page = new StringBuilder();

			addPageHeader(page, "TVStudy - Main Menu", 0, errorMessage);

			page.append("TVStudy Main Menu<br><br>\n");

			page.append("<a href=\"/tvstudy/api?op=" + IxCheckAPI.OP_START + "\">Interference check study</a><br>\n");
			page.append("<a href=\"/tvstudy/api?op=" + SearchAPI.OP_START + "\">Station data search</a><br>\n");
			page.append("<a href=\"/tvstudy/api?op=" + RecordAPI.OP_START + "\">Create user record</a><br>\n");

			page.append("<br><a href=\"/tvstudy/api?op=" + IxCheckAPI.OP_CACHE +
				"\">Interference check cache maintenance</a><br>\n");

			addPageFooter(page, false);

			resultPage = page.toString();
			status = STATUS_PAGE;

			return;
		}

		// Extract back and next parameters to be used by subclass.  To prevent navigational loops the back and next
		// cannot be the same as the current op.  If an error message was passed set STATUS_ERROR, the subclass will
		// usually change that to show a page displaying the error.

		backOp = parameters.get(KEY_BACK_OP);
		if (null != backOp) {
			if (backOp.equals(op)) {
				backOp = null;
			} else {
				backOpLabel = parameters.get(KEY_BACK_OP_LABEL);
				if (null == backOpLabel) {
					backOpLabel = "Back";
				}
			}
		}

		nextOp = parameters.get(KEY_NEXT_OP);
		if ((null != nextOp) && nextOp.equals(op)) {
			nextOp = null;
		}

		if (null != errorMessage) {
			status = STATUS_ERROR;
		} else {
			status = STATUS_UNKNOWN;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Subclasses override this to support chaining, see below.  Subclass calls super() if the operation is not
	// recognized, but that should never occur since canHandleOperation() is always used before this.

	protected void dispatchOperation(String op) {

		if (null == errorMessage) {
			if (null != op) {
				errorMessage = "ERROR: Unknown operation '" + op + "'";
			} else {
				errorMessage = "ERROR: No operation specified";
			}
		}
		status = STATUS_ERROR;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Chain to another operation, if in the same class just dispatch, else create a new operation object.

	protected void chainToOperation(String op) {

		if (this.canHandleOperation(op)) {
			this.dispatchOperation(op);
		} else {
			chainOperation = getOperation(op, parameters, errorMessage);
			status = STATUS_CHAIN;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Standard error-handling behavior.  If an operation is provided chain to that, else set local error state.

	protected void handleError(String theError, String errorOp) {

		if (null != theError) {
			errorMessage = theError;
		} else {
			errorMessage = "ERROR: handleError() called with no error message";
		}
		if (null != errorOp) {
			chainToOperation(errorOp);
		} else {
			status = STATUS_ERROR;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Return status of the operation.  If this is STATUS_ERROR, getErrorMessage() must return an explanation which
	// should be sent to the requestor by the servlet in some appropriate manner (i.e. makeErrorPage()).  If this
	// returns STATUS_PAGE, getResultPage() must return the complete HTML response to send to the requestor.  If this
	// is STATUS_URL, getResultURL() must return a URL and the servlet must redirect the requestor there.  If this is
	// STATUS_CHAIN, getChainOperation() must return an APIOperation object taking over the request.  These methods
	// are usually not overridden, subclasses may set instance properties directly.

	public int getStatus() {

		return status;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getErrorMessage() {

		return errorMessage;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getResultPage() {

		return resultPage;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public String getResultURL() {

		return resultURL;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Multiple chained operations are allowed, the servlet must be prepared for any new object to chain to another.

	public APIOperation getChainOperation() {

		return chainOperation;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Convenience methods for composing pages.  Compose an error message page, typically used by the servlet to
	// report the STATUS_ERROR condition.

	public static String makeErrorPage(String theError) {

		StringBuilder page = new StringBuilder();
		addPageHeader(page, "TVStudy - Error", 0, theError);
		addPageFooter(page);
		return page.toString();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Write out a temporary index page to the standard index file name, indicating a study is running.  Optionally
	// may include a status message e.g. "I of N completed".  This may be called repeatedly during a lengthy run.

	protected static void writeInProgressIndex(String outDirectoryPath, String description,
			String message) throws IOException {

		StringBuilder page = new StringBuilder();

		addPageHeader(page, "TVStudy - Study In Progress", IN_PROGRESS_RELOAD_TIME, null);

		page.append("<br>" + description + "<br>\n");
		if (null != message) {
			page.append("<br>" + message + "<br>\n");
		} else {
			page.append("<br>Study running<br>\n");
		}

		addPageFooter(page);

		Files.createDirectories(Paths.get(outDirectoryPath));

		BufferedWriter out = new BufferedWriter(new FileWriter(outDirectoryPath + File.separator + INDEX_FILE_NAME));
		out.write(page.toString());
		out.close();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Write a final results index file with various messages as desired, and a listing of output files including at
	// least a log file with standard name.  All arguments except the path are optional and may be null.

	protected static void writeFileIndex(String outDirectoryPath, String description, String message, String report,
			ArrayList<String> outputFiles) throws IOException {

		StringBuilder page = new StringBuilder();

		addPageHeader(page, "TVStudy - Study Results", 0, null);

		if ((null != description) && (description.length() > 0)) {
			page.append("<br>" + description + "<br>\n");
		}

		if ((null != message) && (message.length() > 0)) {
			page.append("<br>" + message + "<br>\n");
		}

		page.append("<br><a href=\"" + urlclean(LOG_FILE_NAME) + "\">" + LOG_FILE_NAME +
			"</a><br>\n");

		if ((null != report) && (report.length() > 0)) {
			page.append("<br><pre>\n");
			page.append(report);
			page.append("</pre>\n");
		}

		if ((null != outputFiles) && (outputFiles.size() > 0)) {
			page.append("<br>Output files<br><br>\n");
			for (String theFile : outputFiles) {
				page.append("<a href=\"" + urlclean(theFile) + "\">" + theFile + "</a><br>\n");
			}
		}

		addPageFooter(page);

		Files.createDirectories(Paths.get(outDirectoryPath));

		BufferedWriter out = new BufferedWriter(new FileWriter(outDirectoryPath + File.separator + INDEX_FILE_NAME));
		out.write(page.toString());
		out.close();
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Helper methods for building HTML pages.  Add a basic header, may include a reload interval and optionally
	// display an error message.

	protected static void addPageHeader(StringBuilder page, String title, int refresh, String theError) {

		page.append("<html>\n");
		page.append("<head>\n");
		if (refresh > 0) {
			page.append("<meta http-equiv=\"refresh\" content=\"" + refresh + "\"/>\n");
		}
		page.append("<title>" + title + "</title>\n");
		page.append("</head>\n");
		page.append("<body>\n");

		if (null != theError) {
			page.append("<br><b>" + theError + "</b><br><br>\n");
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Start a form.  Most forms are GET, but may use POST as well.  The op is required, back and next are optional.

	protected static void addFormStart(StringBuilder page, String op) {
		addFormStart(page, op, null, null, null, false);
	}

	protected static void addFormStart(StringBuilder page, String op, boolean usePost) {
		addFormStart(page, op, null, null, null, usePost);
	}

	protected static void addFormStart(StringBuilder page, String op, String backOp, String backOpLabel,
			String nextOp) {
		addFormStart(page, op, backOp, backOpLabel, nextOp, false);
	}

	protected static void addFormStart(StringBuilder page, String op, String backOp, String backOpLabel, String nextOp,
			boolean usePost) {

		if (usePost) {
			page.append("<form action=\"api\" method=\"post\">\n");
		} else {
			page.append("<form action=\"api\" method=\"get\">\n");
		}
		page.append("<input type=\"hidden\" name=\"" + KEY_OP + "\" value=\"" + op + "\">\n");
		if (null != backOp) {
			page.append("<input type=\"hidden\" name=\"" + KEY_BACK_OP + "\" value=\"" + backOp + "\">\n");
		}
		if (null != backOpLabel) {
			page.append("<input type=\"hidden\" name=\"" + KEY_BACK_OP_LABEL + "\" value=\"" + backOpLabel + "\">\n");
		}
		if (null != nextOp) {
			page.append("<input type=\"hidden\" name=\"" + KEY_NEXT_OP + "\" value=\"" + nextOp + "\">\n");
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// End a form, label of submit button provided.

	protected static void addFormEnd(StringBuilder page, String label) {

		page.append("<input type=\"submit\" value=\"" + label + "\"><br>\n");
		page.append("</form>\n");
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Add page footer, optionally with a link to the main menu.

	protected static void addPageFooter(StringBuilder page) {
		addPageFooter(page, true);
	}

	protected static void addPageFooter(StringBuilder page, boolean mainLink) {

		if (mainLink) {
			page.append("<br><a href=\"/tvstudy/api?op=menu\">Main menu</a><br>\n");
		}

		page.append("</body>\n");
		page.append("</html>\n");
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Limited URL encoding of a string, only handles characters that are likely to appear in result file path names.
	// Not using URLEncoder because that would encode separator characters in the paths and so return bad URLs.

	public static String urlclean(String theText) {

		StringBuilder result = new StringBuilder();
		char c;

		for (int i = 0; i < theText.length(); i++) {

			c = theText.charAt(i);

			switch (c) {

				case ' ':
					result.append('+');
					break;

				case '#':
					result.append("%23");
					break;

				case '(':
					result.append("%28");
					break;

				case ')':
					result.append("%29");
					break;

				case '+':
					result.append("%2b");
					break;

				case '<':
					result.append("%3c");
					break;

				case '>':
					result.append("%3e");
					break;

				default:
					result.append(c);
					break;
			}
		}

		return result.toString();
	}
}
