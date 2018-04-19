#ifndef _sysctlheader_
#define _sysctlheader_

#define CTL_HW 1
#define HW_MEMSIZE 2

#include <windows.h>

static void sysctl(int* mib, int mibsize, DWORDLONG* totalMemory, size_t *len, char* reserved, int reserved2)
{
	MEMORYSTATUSEX status;
	status.dwLength = sizeof(status);
	GlobalMemoryStatusEx( &status );
	*totalMemory = status.ullTotalPhys;
}

#endif