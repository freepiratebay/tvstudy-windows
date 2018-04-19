//
//  DbConnection.java
//  TVStudy
//
//  Copyright (c) 2012-2016 Hammett & Edison, Inc.  All rights reserved.

package gov.fcc.tvstudy.core;

import java.util.*;
import java.sql.*;


//=====================================================================================================================
// Class for managing a persistent database connection via JDBC, with a simplified and unified API for a sequential
// query-response usage pattern where this object manages the result set.  A key feature here is the ability to hold a
// connection in the open state across connect()/close() contexts, to avoid connection delays in high-latency network
// environments.  A call to close() does not usually close the connection immediately, it starts a timer that will
// close it some time later if there are no intervening calls to connect().

public class DbConnection {

	public static final String ERROR_TEXT_PREFIX = "An operation cannot be completed due to a database error:\n";

	private static final int IDLE_CLOSE_TIME = 30000;   // milliseconds

	public final String driver;
	public final String hostname;
	public final String username;
	public final String password;

	// Internal state properties.

	private Connection connection;
	private String dbName;
	private Statement statement;

	private boolean connected;

	private ResultSet resultSet;

	private Thread inTransactionForThread;

	private boolean canLinger;
	private TimerTask closeTask;

	// All delayed close events are handled by one timer thread.  Use a daemon thread, lingering connections do not
	// need to delay application exit.

	private static Timer closeTimer = new Timer(true);


	//-----------------------------------------------------------------------------------------------------------------

	public DbConnection(String theDriver, String theHost, String theUser, String thePass) {

		driver = theDriver;
		hostname = theHost;
		username = theUser;
		password = thePass;
	}


	//-----------------------------------------------------------------------------------------------------------------

	public DbConnection copy() {

		return new DbConnection(driver, hostname, username, password);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Open a connection, creating or re-creating a Connection object as needed.  The name is optional, setDatabase()
	// can be used to change database later by calling setCatalog() on the connection.  However if the driver does not
	// support that failures will occur.  Caller must behave accordingly, if the driver does not support setCatalog()
	// the database name must be provided to connect().  This does not report specific failures, if any failure occurs
	// it reports only a generic connection-failed message, however it will log a specific message.

	public boolean connect() {
		return connect(null, null);
	}

	public boolean connect(String theName) {
		return connect(theName, null);
	}

	public boolean connect(ErrorLogger errors) {
		return connect(null, errors);
	}

	public synchronized boolean connect(String theName, ErrorLogger errors) {

		if (connected) {
			if (null != errors) {
				errors.reportError(
					"An operation cannot be completed because the\n" +
					"database server connection is already in use.");
			}
			return false;
		}

		// Cancel any pending close.

		if (null != closeTask) {
			closeTask.cancel();
			closeTask = null;
		}

		// Clean up the name argument, it is either null or a non-empty string.

		if (null != theName) {
			theName = theName.trim();
			if (0 == theName.length()) {
				theName = null;
			}
		}

		// If a connection is lingering and the name is changing, close the connection.  The initial connection has to
		// always set the requested name in case the driver does not support using setCatalog() later.

		if ((null != connection) && (null != theName) && !theName.equals(dbName)) {
			close(false);
		}

		// Create a new connection if needed.

		if (null == connection) {
			try {

				String url = driver + "//" + hostname;
				if (null != theName) {
					url = url + "/" + theName;
				}
				connection = DriverManager.getConnection(url, username, password);
				dbName = theName;
				canLinger = true;

			} catch (SQLException se) {
				AppCore.log(AppCore.ERROR_MESSAGE, "Database connection failed", se);
			}
		}

		if (null != connection) {
			connected = true;
		} else {
			if (null != errors) {
				errors.reportError(
					"An operation cannot be completed because a connection to\n" +
					"the database server cannot be established.  The server may\n" +
					"be off-line or there may be a problem with the network.");
			}
		}

		return connected;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// The connected flag is the logical state of this manager object, not necessarily the actual connection state.
	// False means the connection is available for use but does not mean the underlying connection is closed.

	public boolean isConnected() {

		return connected;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Change the database on an open connection, if not open this does nothing, otherwise it calls setCatalog() after
	// closing any existing statement, a new one has to be created to apply the new name.  If setCatalog() is a no-op
	// in the driver this does nothing.  Setting a null or empty name, or the same name already set, does nothing.

	public void setDatabase(String theName) throws SQLException {

		if (!connected) {
			return;
		}

		if (null == theName) {
			return;
		} else {
			theName = theName.trim();
			if (0 == theName.length()) {
				return;
			}
		}
		if (theName.equals(dbName)) {
			return;
		}

		try {

			if (null != statement) {
				resultSet = null;
				Statement s = statement;
				statement = null;
				s.close();
			}

			connection.setCatalog(theName);
			dbName = theName;

		} catch (SQLException se) {
			canLinger = false;
			throw se;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Always a chance this will return null, although whatever it returns can be used with setDatabase().

	public String getDatabase() {

		return dbName;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Execute a query that returns a result set.  When there is an exception, the canLinger flag is cleared so the
	// next close() really closes regardless of the caller's request, in case the problem is connection rot.

	public synchronized void query(String theQuery) throws SQLException {

		if (!connected) {
			throw new SQLException("DbConnection.query(): connection is not open");
		}

		if ((null != inTransactionForThread) && (Thread.currentThread() != inTransactionForThread)) {
			throw new SQLException("DbConnection.query(): transaction was opened on a different thread");
		}

		try {

			if (null == statement) {
				statement = connection.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_READ_ONLY);
			}
			resultSet = statement.executeQuery(theQuery);

		} catch (SQLException se) {
			canLinger = false;
			throw se;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Execute a query with no result set, return number of rows affected.

	public synchronized int update(String theQuery) throws SQLException {

		if (!connected) {
			throw new SQLException("DbConnection.update(): connection is not open");
		}

		if ((null != inTransactionForThread) && (Thread.currentThread() != inTransactionForThread)) {
			throw new SQLException("DbConnection.update(): transaction was opened on a different thread");
		}

		int result = 0;

		try {

			if (null == statement) {
				statement = connection.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_READ_ONLY);
			}
			result = statement.executeUpdate(theQuery);

		} catch (SQLException se) {
			canLinger = false;
			throw se;
		}

		return result;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Wrappers around methods in the result set object.  In general if there is no result set these will throw a null
	// pointer exception, however next() checks first since that should always be called before any others.

	public boolean next() throws SQLException {
		if (null == resultSet) {
			return false;
		}
		return resultSet.next();
	}

	public boolean wasNull() throws SQLException {
		return resultSet.wasNull();
	}

	public String getString(int fieldIndex) throws SQLException {
		return resultSet.getString(fieldIndex);
	}

	public String getString(String fieldName) throws SQLException {
		return resultSet.getString(fieldName);
	}

	public boolean getBoolean(int fieldIndex) throws SQLException {
		return resultSet.getBoolean(fieldIndex);
	}

	public boolean getBoolean(String fieldName) throws SQLException {
		return resultSet.getBoolean(fieldName);
	}

	public int getInt(int fieldIndex) throws SQLException {
		return resultSet.getInt(fieldIndex);
	}

	public int getInt(String fieldName) throws SQLException {
		return resultSet.getInt(fieldName);
	}

	public long getLong(int fieldIndex) throws SQLException {
		return resultSet.getLong(fieldIndex);
	}

	public long getLong(String fieldName) throws SQLException {
		return resultSet.getLong(fieldName);
	}

	public float getFloat(int fieldIndex) throws SQLException {
		return resultSet.getFloat(fieldIndex);
	}

	public float getFloat(String fieldName) throws SQLException {
		return resultSet.getFloat(fieldName);
	}

	public double getDouble(int fieldIndex) throws SQLException {
		return resultSet.getDouble(fieldIndex);
	}

	public double getDouble(String fieldName) throws SQLException {
		return resultSet.getDouble(fieldName);
	}

	public java.sql.Date getDate(int fieldIndex) throws SQLException {
		return resultSet.getDate(fieldIndex);
	}

	public java.sql.Date getDate(String fieldName) throws SQLException {
		return resultSet.getDate(fieldName);
	}

	public java.sql.Timestamp getTimestamp(int fieldIndex) throws SQLException {
		return resultSet.getTimestamp(fieldIndex);
	}

	public java.sql.Timestamp getTimestamp(String fieldName) throws SQLException {
		return resultSet.getTimestamp(fieldName);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Begin a transaction, which means, turn off autocommit in the connection.  Only one thread may be using the
	// connection this way at any given time, so track the thread that initiates this.

	public synchronized void begin() throws SQLException {

		if (!connected) {
			throw new SQLException("DbConnection.begin(): connection is not open");
		}

		if (null != inTransactionForThread) {
			throw new SQLException("DbConnection.begin(): a transaction is already open");
		}

		try {

			inTransactionForThread = Thread.currentThread();
			connection.setAutoCommit(false);

		} catch (SQLException se) {
			canLinger = false;
			throw se;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Commit the open transaction and turn autocommit back on, check to be sure this is the same thread.

	public synchronized void commit() throws SQLException {

		if (!connected) {
			throw new SQLException("DbConnection.commit(): connection is not open");
		}

		if (null == inTransactionForThread) {
			throw new SQLException("DbConnection.commit(): no transaction is open");
		}

		if (Thread.currentThread() != inTransactionForThread) {
			throw new SQLException("DbConnection.commit(): transaction was opened on a different thread");
		}

		try {

			connection.commit();
			connection.setAutoCommit(true);
			inTransactionForThread = null;

		} catch (SQLException se) {
			canLinger = false;
			throw se;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Roll back the open transaction.

	public synchronized void rollback() throws SQLException {

		if (!connected) {
			throw new SQLException("DbConnection.rollback(): connection is not open");
		}

		if (null == inTransactionForThread) {
			throw new SQLException("DbConnection.rollback(): no transaction is open");
		}

		if (Thread.currentThread() != inTransactionForThread) {
			throw new SQLException("DbConnection.rollback(): transaction was opened on a different thread");
		}

		try {

			connection.rollback();
			connection.setAutoCommit(true);
			inTransactionForThread = null;

		} catch (SQLException se) {
			canLinger = false;
			throw se;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Roll back open transaction, discard result set and statement, catch and discard exceptions.  Meant to be called
	// from other exception handlers, also from close() below, so it deliberately does not check connected state.  The
	// rollback is done regardless of the calling thread so an error-handling thread can clean up after another died.

	public synchronized void abort() {

		if (null != inTransactionForThread) {
			try {
				connection.rollback();
				connection.setAutoCommit(true);
			} catch (SQLException se) {
				canLinger = false;
			}
			inTransactionForThread = null;
		}

		if (null != statement) {
			resultSet = null;
			try {
				statement.close();
			} catch (SQLException se) {
				canLinger = false;
			}
			statement = null;
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Close the connection to the database, which always means calling abort() to clean up all active state, and may
	// also mean actually closing the connection; however that does not always occur.  To avoid poor performance in
	// conditions of high network latency the connection may stay open to be re-used.  If so, a timer is started that
	// will actually close the connection after an idle time.  This makes no difference in calling code, all database
	// operations are still closely bracketed by connect()/close(); but the usual close() with no argument will cause
	// the connection to stay open.  Calling code should use close(false) if it is known that the connection will not
	// be needed again.  Also since close() does not fail if the connection is not actually open, close(false) can be
	// used as a clean-up operation.  Note also the canLinger flag can override the stay-open request.

	public void close() {
		close(true);
	}

	public synchronized void close(boolean linger) {

		if ((null == connection) || (!connected && linger)) {
			return;
		}

		abort();

		if (null != closeTask) {
			closeTask.cancel();
			closeTask = null;
		}

		if (linger && canLinger) {

			closeTask = new TimerTask() {
				public void run() {
					close(false);
				}
			};
			closeTimer.schedule(closeTask, IDLE_CLOSE_TIME);

		} else {

			try {
				connection.close();
			} catch (SQLException se) {
			}
			connection = null;
			dbName = null;
			canLinger = false;
		}

		connected = false;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Convenience to report any current warning messages.  If there are no warnings normally the report will not be
	// made, if the alwaysReport argument is true it will report a generic message instead.

	public void reportWarnings(ErrorLogger errors, boolean alwaysReport) {

		if (!connected) {
			return;
		}

		if (null == errors) {
			return;
		}

		String theWarnings = getWarnings();

		if (null == theWarnings) {
			if (!alwaysReport) {
				return;
			}
			theWarnings = "A database operation may have failed, but no details are available.";
		} else {
			theWarnings = "A database operation returned the following message:" + theWarnings;
		}

		errors.reportWarning(theWarnings);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Retrieve and clear all warning messages from the result set, statement, and connection objects.  Returns all
	// messages concatenated in a string, or null if there are none.

	public synchronized String getWarnings() {

		if (!connected) {
			return null;
		}

		StringBuilder theWarnings = new StringBuilder();

		try {

			SQLWarning theWarning;

	 		if (null != resultSet) {
				theWarning = resultSet.getWarnings();
				resultSet.clearWarnings();
				while (null != theWarning) {
					theWarnings.append("\n");
					theWarnings.append(theWarning.getMessage());
					theWarning = theWarning.getNextWarning();
				}
			}

			if (null != statement) {
				theWarning = statement.getWarnings();
				statement.clearWarnings();
				while (null != theWarning) {
					theWarnings.append("\n");
					theWarnings.append(theWarning.getMessage());
					theWarning = theWarning.getNextWarning();
				}
			}

			if (null != connection) {
				theWarning = connection.getWarnings();
				connection.clearWarnings();
				while (null != theWarning) {
					theWarnings.append("\n");
					theWarnings.append(theWarning.getMessage());
					theWarning = theWarning.getNextWarning();
				}
			}

		} catch (SQLException se) {
		}

		if (theWarnings.length() > 0) {
			return theWarnings.toString();
		}

		return null;
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Convenience method for handling a caught SQL exception, log it and optionally report to the user.

	public static void reportError(ErrorLogger errors, SQLException theException) {

		reportError(theException);

		if (null != errors) {
			errors.reportError(ERROR_TEXT_PREFIX + theException);
		}
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Log an SQL error.

	public static void reportError(SQLException theException) {

		AppCore.log(AppCore.ERROR_MESSAGE, "Database error", theException);
	}


	//-----------------------------------------------------------------------------------------------------------------
	// Escape characters in text so it is safe in an SQL query.

	public static String clean(String theText) {

		StringBuilder result = new StringBuilder();
		char c;

		for (int i = 0; i < theText.length(); i++) {

			c = theText.charAt(i);

			switch (c) {

				case '\'':
					result.append("''");
					break;

				case '\\':
					result.append("\\\\");
 					break;

				default:
					result.append(c);
					break;
			}
		}

		return result.toString();
	}
}
