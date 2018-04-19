// make_codeid.c
// 12Jun2017 rsj

// Create a "code ID" by using cksum on concatenated source files and converting the result to a character string,
// which is written to a C header and a Java class to be compiled in to the applications.  Reads the cksum output from
// stdin, the rest is scripted in the Makefile.  Option -c outputs only C header, -j only Java class, else both.

#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>

#define MAX_LINE 1000
#define MAX_ID_LEN 8

int main(int argc, char **argv) {

	int doC = 1, doJava = 1;

	if ((argc > 1) && ('-' == argv[1][0])) {
		if ('c' == argv[1][1]) {
			doJava = 0;
		} else {
			if ('j' == argv[1][1]) {
				doC = 0;
			}
		}
	}

	char line[MAX_LINE];
	unsigned int codeID = 0;

	if (fgets(line, MAX_LINE, stdin)) {
		codeID = (unsigned int)strtol(line, NULL, 10);
	}

	int digit, i = 0;
	char id[MAX_ID_LEN + 1];

	do {
		digit = codeID % 62;
		codeID /= 62;
		if (digit < 10) {
			id[i] = '0' + digit;
		} else {
			digit -= 10;
			if (digit < 26) {
				id[i] = 'A' + digit;
			} else {
				digit -= 26;
				id[i] = 'a' + digit;
			}
		}
		i++;
	} while ((codeID > 0) && (i < MAX_ID_LEN));
	id[i] = '\0';

	FILE *out;

	if (doC) {
		out = fopen("src/codeid/codeid.h", "w");
		if (out) {
			fprintf(out, "#define CODE_ID \"%s\"\n", id);
			fclose(out);
		}
	}

	if (doJava) {
		out = fopen("src/codeid/CodeID.java", "w");
		if (out) {
			fputs("package codeid;\n", out);
			fputs("public class CodeID {\n", out);
			fprintf(out, "\tpublic static final String ID = \"%s\";\n", id);
			fputs("}\n", out);
			fclose(out);
		}
	}

	exit(0);
}
