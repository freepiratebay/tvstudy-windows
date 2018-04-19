//
//  log.c
//  TVStudy
//
//  Copyright (c) 2012-2017 Hammett & Edison, Inc.  All rights reserved.


// Functions to handle message and error logging.


#include "tvstudy.h"

#include <stdarg.h>
#include <time.h>
#include <sys/time.h>


//---------------------------------------------------------------------------------------------------------------------

static char *format_time(time_t *t);
static time_t write_log(FILE *log, char *str);

static char *LogFile = NULL;

static FILE *MessageLog = NULL;
static FILE *ErrorLog = NULL;

static int LogsOpen = 0;

static time_t StartTime = 0;
static int StartTimeMillis = 0;

static time_t LastMessageTime = 0;

static int StatusEnabled = 0;

#define HB_INTERVAL 3
#define HB_MESSAGE "...working"
#define HB_BAR_SIZE 30
#define HB_BAR_ON '#'
#define HB_BAR_OFF '-'
#define HB_BAR_ALT '='

static int HbLogCount = 0;
static int HbLogTick = 0;
static int HbLogNeedEnd = 0;


//---------------------------------------------------------------------------------------------------------------------
// Set file for logging, both messages and errors will go to this file if set.  If logs are open, first close them.

void set_log_file(char *theFile) {

	if (LogsOpen) {
		log_close();
	}

	int l = strlen(theFile) + 2;
	if (LogFile) {
		if (strlen(LogFile) < l) {
			LogFile = (char *)mem_realloc(LogFile, l);
		}
	} else {
		LogFile = (char *)mem_alloc(l);
	}
	lcpystr(LogFile, theFile, l);
}


//---------------------------------------------------------------------------------------------------------------------
// The zero-time for the log may be set by command-line option to merge this run's log with an existing sequence.  The
// argument value is in milliseconds.  This can only be done once, before the first log open.

void set_log_start_time(long theTime) {

	if (0 == StartTime) {
		StartTime = (int)(theTime / 1000L);
		StartTimeMillis = (int)(theTime % 1000L);
	}
}


//---------------------------------------------------------------------------------------------------------------------
// Open logging, set the output streams for messages and errors, set the start time for relative timestamps (unless
// that time is already set and this is a "re-open").  This is optional, if not called directly it will be called on
// the first use of any other log function.  If no file has been set or there is an error opening it, messages go to
// stdout and errors to stderr.

void log_open() {

	if (LogsOpen) {
		log_close();
	}

	if (LogFile) {
		MessageLog = fopen(LogFile, "a");
	}
	if (!MessageLog) {
		MessageLog = stdout;
		ErrorLog = stderr;
	} else {
		ErrorLog = MessageLog;
	}

	LogsOpen = 1;

	struct timeval t;
	struct timezone tz;
	gettimeofday(&t, &tz);

	char *reop = "";
	if (0 == StartTime) {
		StartTime = t.tv_sec;
		StartTimeMillis = (int)(t.tv_usec / 1000L);
	} else {
		reop = "re-";
	}

	char str[MAX_STRING];
	struct tm *p = localtime(&(t.tv_sec));
	snprintf(str, MAX_STRING, "Log %sopened %04d.%02d.%02d %02d:%02d:%02d.%03d", reop, (p->tm_year + 1900),
		(p->tm_mon + 1), p->tm_mday, p->tm_hour, p->tm_min, p->tm_sec, (int)(t.tv_usec / 1000L));

	LastMessageTime = write_log(MessageLog, str);

	if (Debug) {
		report_terrain_stats(MessageLog);
	}
}


//---------------------------------------------------------------------------------------------------------------------
// Close logging.

void log_close() {

	if (!LogsOpen) {
		return;
	}

	write_log(MessageLog, "Log closed");

	if (LogFile) {
		fclose(MessageLog);
	}
	MessageLog = NULL;
	ErrorLog = NULL;

	LogsOpen = 0;

	LastMessageTime = 0;

	if (Debug) {
		report_terrain_stats(NULL);
	}
}


//---------------------------------------------------------------------------------------------------------------------
// Get log opened time as a string, if logs aren't open get current time.

char *log_open_time() {

	if (LogsOpen) {
		return format_time(&StartTime);
	}
	return current_time();
}


//---------------------------------------------------------------------------------------------------------------------
// Get current time as a string.

char *current_time() {

	time_t now;
	time(&now);
	return format_time(&now);
}


//---------------------------------------------------------------------------------------------------------------------
// Format a time as a string.

static char *format_time(time_t *t) {

	static char str[MAX_STRING];

	struct tm *p = localtime(t);
	snprintf(str, MAX_STRING, "%04d.%02d.%02d %02d:%02d:%02d", (p->tm_year + 1900), (p->tm_mon + 1), p->tm_mday,
		p->tm_hour, p->tm_min, p->tm_sec);
	return str;
}


//---------------------------------------------------------------------------------------------------------------------
// All functions take a printf-style format string followed by corresponding variable argument list.

// Arguments:

//   See printf().

void log_message(const char *fmt, ...) {

	if (!LogsOpen) {
		log_open();
	}

	static char str[MAX_STRING];
	static va_list ap;

	va_start(ap, fmt);
	vsnprintf(str, MAX_STRING, fmt, ap);
	va_end(ap);

	LastMessageTime = write_log(MessageLog, str);
}


//---------------------------------------------------------------------------------------------------------------------
// Arguments:

//   See printf().

void log_error(const char *fmt, ...) {

	if (!LogsOpen) {
		log_open();
	}

	static char str[MAX_STRING];
	static va_list ap;

	va_start(ap, fmt);
	vsnprintf(str, MAX_STRING, fmt, ap);
	va_end(ap);

	write_log(ErrorLog, str);
}


//---------------------------------------------------------------------------------------------------------------------
// This call checks mysql_error() on the global connection and appends anything from that to the message.

// Arguments:

//   See printf().

void log_db_error(const char *fmt, ...) {

	if (!LogsOpen) {
		log_open();
	}

	static char str[MAX_STRING];
	static va_list ap;

	va_start(ap, fmt);
	vsnprintf(str, MAX_STRING, fmt, ap);
	va_end(ap);

	const char *myError = mysql_error(MyConnection);
	if (*myError) {
		lcatstr(str, ": ", MAX_STRING);
		lcatstr(str, myError, MAX_STRING);
	}

	write_log(ErrorLog, str);
}


//---------------------------------------------------------------------------------------------------------------------
// Add timestamp to message, write to log.

// Arguments:

//   log  Output stream.
//   str  Message to log.

// Return the current time.

static time_t write_log(FILE *log, char *str) {

	struct timeval t;
	struct timezone tz;
	gettimeofday(&t, &tz);

	int seconds = (int)(t.tv_sec - StartTime);
	int millis = (int)(t.tv_usec / 1000) - StartTimeMillis;
	if (millis < 0) {
		seconds--;
		millis += 1000;
	}
	int hours = seconds / 3600;
	int minutes = (seconds % 3600) / 60;
	seconds = seconds % 60;

	fprintf(log, "%3d:%02d:%02d.%03d - %s\n", hours, minutes, seconds, millis, str);
	fflush(log);

	return t.tv_sec;
}


//---------------------------------------------------------------------------------------------------------------------
// Enable or disable status messages.

void set_status_enabled(int enable) {

	StatusEnabled = enable;
}


//---------------------------------------------------------------------------------------------------------------------
// Write a status message, consisting of a key identifying the message type and the message.

void status_message(char *key, char *mesg) {

	if (StatusEnabled) {
		printf("%s%s=%s\n", STATUS_MESSAGE_PREFIX, key, mesg);
		fflush(stdout);
	}
}


//---------------------------------------------------------------------------------------------------------------------
// Function to write out "heartbeat" status messages to confirm the process is still alive when a UI front-end
// application is managing the process.  The messages do not have any vital information, but do show a "completion
// bar" indicator that may track an actual iteration count or just alternate different patterns.  Any lengthy iterative
// sequence should at least call hb_log() often enough to ensure a message is written every HB_INTERVAL seconds.  The
// interval is measured from the last message written by log_message() or from here.  For fast-running studies,
// log_message() may be called often enough that no heartbeat messages are written at all.

void hb_log() {

	if ((time(NULL) - LastMessageTime) < HB_INTERVAL) {
		return;
	}

	static char str[MAX_STRING];
	static int barPhase = 1, lastDone = 0;

	char bar[HB_BAR_SIZE + 1];
	int i;

	if (HbLogCount > 0) {

		int done = (HbLogTick * HB_BAR_SIZE) / HbLogCount;
		for (i = 0; i < HB_BAR_SIZE; i++) {
			if (i < done) {
				bar[i] = HB_BAR_ON;
			} else {
				bar[i] = HB_BAR_OFF;
			}
		}
		bar[i] = '\0';

		if (HbLogTick < HbLogCount) {

			if (done == lastDone) {
				if (barPhase) {
					bar[done] = HB_BAR_ALT;
				}
				barPhase = !barPhase;
			} else {
				lastDone = done;
				barPhase = 1;
			}

			HbLogNeedEnd = 1;

		} else {
			lastDone = 0;
			barPhase = 1;
			HbLogNeedEnd = 0;
		}

	} else {

		for (i = 0; i < HB_BAR_SIZE; i++) {
			if (barPhase) {
				bar[i] = HB_BAR_ON;
			} else {
				bar[i] = HB_BAR_OFF;
			}
			barPhase = !barPhase;
		}
		bar[i] = '\0';

		barPhase = !barPhase;
	}

	snprintf(str, MAX_STRING, "%s [%s]", HB_MESSAGE, bar);

	status_message(STATUS_KEY_PROGRESS, str);
	LastMessageTime = time(NULL);
}


//---------------------------------------------------------------------------------------------------------------------
// Functions to set up heartbeat messages to show completion status of an iterative sequence, hb_log_begin() starts a
// new sequence and sets the maximum count value, hb_log_tick() is called to increment the counter, and hb_log_end()
// ends the sequence.  Those functions all call hb_log() to write a message if needed.  Direct calls to hb_log() may
// also be made if hb_log_tick() might not be called often enough.  Note in hb_log_end() if any previous call in the
// sequence wrote a message a final full-completion message is written regardless of the elapsed time.

void hb_log_begin(int count) {
	if (count > 0) {
		HbLogCount = count;
	} else {
		HbLogCount = 0;
	}
	HbLogTick = 0;
	HbLogNeedEnd = 0;
	hb_log();
}

void hb_log_tick() {
	if (HbLogTick < HbLogCount) {
		HbLogTick++;
	}
	hb_log();
}

void hb_log_end() {
	HbLogTick = HbLogCount;
	if (HbLogNeedEnd) {
		LastMessageTime = 0;
	}
	hb_log();
	HbLogCount = 0;
	HbLogTick = 0;
	HbLogNeedEnd = 0;
}
