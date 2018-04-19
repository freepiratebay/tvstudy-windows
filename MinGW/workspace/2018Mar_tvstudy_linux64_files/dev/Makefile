# Build targets.

FC=gfortran

JAR=tvstudy.jar
JARCORE=tvstudy-core.jar
JARGUI=tvstudy-gui.jar
JARAPI=tvstudy-api.jar
JARUTIL=dbutil.jar
EXE=tvstudy.exe
EXEPOST=pair_study_post.exe

# The actual names of the commands for the command-line utilities can be anything, just change the name here,
# the code picks up the name from argv[0] for generating messages and the config file name.  Do not change
# the other names above as the UI has those hard-coded.

EXEUTIL=ptelev.exe
EXENED=convert_ned13.exe
EXENAD=nadcon.exe

.PHONY: all
all: jar exe

.PHONY: jar
jar: codeid $(JARCORE) $(JARGUI) $(JARAPI) $(JAR) $(JARUTIL)

.PHONY: exe
exe: codeid $(EXE) $(EXEPOST) $(EXEUTIL) $(EXENED)

.PHONY: codeid
codeid: make_codeid
	cat `ls -1 src/*.h src/*.c src/model/*.h src/model/*.c | sort -fd` | cksum | ./make_codeid -c; \
	cat `ls -1 src/gov/fcc/tvstudy/*.java src/gov/fcc/tvstudy/*/*.java src/gov/fcc/tvstudy/*/*/*.java | sort -fd` | \
cksum | ./make_codeid -j

make_codeid: src/codeid/make_codeid.c
	gcc -o $@ $^

# Installation.

INSTALLDIR=../lib
INSTALLDIRJAR=..

.PHONY: install
install: all
	cp -pf $(JARCORE) $(JARGUI) $(JARAPI) $(EXE) $(EXEPOST) $(EXENED) $(EXEUTIL) $(JARUTIL) $(INSTALLDIR); \
	cp -pf $(JAR) $(INSTALLDIRJAR)

# Cleanup.

.PHONY: clean
clean:
	rm -rf build/*; \
	rm -f $(JARCORE) $(JARGUI) $(JARAPI) $(EXE) $(EXENED) $(EXEUTIL) $(JARUTIL); \
	mkdir build/model

# Build rules.

CLASSCORE=\
 build/gov/fcc/tvstudy/core/AntPattern.class \
 build/gov/fcc/tvstudy/core/AppCore.class \
 build/gov/fcc/tvstudy/core/AppTask.class \
 build/gov/fcc/tvstudy/core/DbConnection.class \
 build/gov/fcc/tvstudy/core/DbCore.class \
 build/gov/fcc/tvstudy/core/ErrorLogger.class \
 build/gov/fcc/tvstudy/core/ExtDb.class \
 build/gov/fcc/tvstudy/core/ExtDbListener.class \
 build/gov/fcc/tvstudy/core/ExtDbRecord.class \
 build/gov/fcc/tvstudy/core/ExtDbRecordFM.class \
 build/gov/fcc/tvstudy/core/ExtDbRecordTV.class \
 build/gov/fcc/tvstudy/core/ExtDbRecordWL.class \
 build/gov/fcc/tvstudy/core/ExtDbSearch.class \
 build/gov/fcc/tvstudy/core/KeyedRecord.class \
 build/gov/fcc/tvstudy/core/OutputConfig.class \
 build/gov/fcc/tvstudy/core/Record.class \
 build/gov/fcc/tvstudy/core/StatusLogger.class \
 build/gov/fcc/tvstudy/core/StudyBuild.class \
 build/gov/fcc/tvstudy/core/StudyBuildIxCheck.class \
 build/gov/fcc/tvstudy/core/StudyBuildPair.class \
 build/gov/fcc/tvstudy/core/StudyBuildTV6FM.class \
 build/gov/fcc/tvstudy/core/StudyBuildWireless.class \
 build/gov/fcc/tvstudy/core/data/ChannelBand.class \
 build/gov/fcc/tvstudy/core/data/ChannelDelta.class \
 build/gov/fcc/tvstudy/core/data/Country.class \
 build/gov/fcc/tvstudy/core/data/EmissionMask.class \
 build/gov/fcc/tvstudy/core/data/FrequencyOffset.class \
 build/gov/fcc/tvstudy/core/data/IxRule.class \
 build/gov/fcc/tvstudy/core/data/Parameter.class \
 build/gov/fcc/tvstudy/core/data/Scenario.class \
 build/gov/fcc/tvstudy/core/data/Service.class \
 build/gov/fcc/tvstudy/core/data/ServiceType.class \
 build/gov/fcc/tvstudy/core/data/SignalType.class \
 build/gov/fcc/tvstudy/core/data/Source.class \
 build/gov/fcc/tvstudy/core/data/SourceFM.class \
 build/gov/fcc/tvstudy/core/data/SourceTV.class \
 build/gov/fcc/tvstudy/core/data/SourceWL.class \
 build/gov/fcc/tvstudy/core/data/Study.class \
 build/gov/fcc/tvstudy/core/data/Template.class \
 build/gov/fcc/tvstudy/core/data/Zone.class \
 build/gov/fcc/tvstudy/core/editdata/IxRuleEditData.class \
 build/gov/fcc/tvstudy/core/editdata/IxRuleListData.class \
 build/gov/fcc/tvstudy/core/editdata/ListDataChange.class \
 build/gov/fcc/tvstudy/core/editdata/ParameterEditData.class \
 build/gov/fcc/tvstudy/core/editdata/ParseXML.class \
 build/gov/fcc/tvstudy/core/editdata/ScenarioEditData.class \
 build/gov/fcc/tvstudy/core/editdata/ScenarioListData.class \
 build/gov/fcc/tvstudy/core/editdata/SourceEditData.class \
 build/gov/fcc/tvstudy/core/editdata/SourceEditDataFM.class \
 build/gov/fcc/tvstudy/core/editdata/SourceEditDataTV.class \
 build/gov/fcc/tvstudy/core/editdata/SourceEditDataWL.class \
 build/gov/fcc/tvstudy/core/editdata/SourceListData.class \
 build/gov/fcc/tvstudy/core/editdata/StudyEditData.class \
 build/gov/fcc/tvstudy/core/editdata/TemplateEditData.class \
 build/gov/fcc/tvstudy/core/geo/GeoBox.class \
 build/gov/fcc/tvstudy/core/geo/GeoCircle.class \
 build/gov/fcc/tvstudy/core/geo/Geography.class \
 build/gov/fcc/tvstudy/core/geo/GeoPoint.class \
 build/gov/fcc/tvstudy/core/geo/GeoPointSet.class \
 build/gov/fcc/tvstudy/core/geo/GeoPolygon.class \
 build/gov/fcc/tvstudy/core/geo/GeoSectors.class

CLASSGUI=\
 build/gov/fcc/tvstudy/gui/AppController.class \
 build/gov/fcc/tvstudy/gui/AppDialog.class \
 build/gov/fcc/tvstudy/gui/AppEditor.class \
 build/gov/fcc/tvstudy/gui/AppFrame.class \
 build/gov/fcc/tvstudy/gui/AppPanel.class \
 build/gov/fcc/tvstudy/gui/BackgroundWorker.class \
 build/gov/fcc/tvstudy/gui/CalendarButtonIcon.png \
 build/gov/fcc/tvstudy/gui/CalendarDialog.class \
 build/gov/fcc/tvstudy/gui/ColorMapEditor.class \
 build/gov/fcc/tvstudy/gui/DateSelectionPanel.class \
 build/gov/fcc/tvstudy/gui/DbController.class \
 build/gov/fcc/tvstudy/gui/EditMenu.class \
 build/gov/fcc/tvstudy/gui/ErrorReporter.class \
 build/gov/fcc/tvstudy/gui/ExtDbManager.class \
 build/gov/fcc/tvstudy/gui/ExtDbSearchDialog.class \
 build/gov/fcc/tvstudy/gui/KeyedRecordMenu.class \
 build/gov/fcc/tvstudy/gui/OptionsPanel.class \
 build/gov/fcc/tvstudy/gui/OutputConfigDialog.class \
 build/gov/fcc/tvstudy/gui/PickExtDbDialog.class \
 build/gov/fcc/tvstudy/gui/RecordFind.class \
 build/gov/fcc/tvstudy/gui/RecordFindDialog.class \
 build/gov/fcc/tvstudy/gui/RecordFindPanel.class \
 build/gov/fcc/tvstudy/gui/RootEditor.class \
 build/gov/fcc/tvstudy/gui/StudyLockHolder.class \
 build/gov/fcc/tvstudy/gui/StudyManager.class \
 build/gov/fcc/tvstudy/gui/TemplateManager.class \
 build/gov/fcc/tvstudy/gui/TextInputDialog.class \
 build/gov/fcc/tvstudy/gui/editor/CoordinatePanel.class \
 build/gov/fcc/tvstudy/gui/editor/GeographyEditor.class \
 build/gov/fcc/tvstudy/gui/editor/GeoEditPanel.class \
 build/gov/fcc/tvstudy/gui/editor/GeoPlotPanel.class \
 build/gov/fcc/tvstudy/gui/editor/IxRuleEditor.class \
 build/gov/fcc/tvstudy/gui/editor/ParameterEditor.class \
 build/gov/fcc/tvstudy/gui/editor/PatternEditor.class \
 build/gov/fcc/tvstudy/gui/editor/ReceiveAntennaEditor.class \
 build/gov/fcc/tvstudy/gui/editor/ScenarioEditor.class \
 build/gov/fcc/tvstudy/gui/editor/SourceEditor.class \
 build/gov/fcc/tvstudy/gui/editor/StudyEditor.class \
 build/gov/fcc/tvstudy/gui/editor/TableFilterModel.class \
 build/gov/fcc/tvstudy/gui/editor/TableFilterPanel.class \
 build/gov/fcc/tvstudy/gui/editor/TemplateEditor.class \
 build/gov/fcc/tvstudy/gui/run/ProcessController.class \
 build/gov/fcc/tvstudy/gui/run/ProcessPanel.class \
 build/gov/fcc/tvstudy/gui/run/RunPanel.class \
 build/gov/fcc/tvstudy/gui/run/RunPanelStudy.class \
 build/gov/fcc/tvstudy/gui/run/RunPanelPairStudy.class \
 build/gov/fcc/tvstudy/gui/run/RunPanelProcess.class \
 build/gov/fcc/tvstudy/gui/run/RunPanelThread.class \
 build/gov/fcc/tvstudy/gui/run/RunStart.class \
 build/gov/fcc/tvstudy/gui/run/RunStartStudy.class \
 build/gov/fcc/tvstudy/gui/run/RunStartPairStudy.class \
 build/gov/fcc/tvstudy/gui/run/RunManager.class

CLASSAPI=\
 build/gov/fcc/tvstudy/api/APIOperation.class \
 build/gov/fcc/tvstudy/api/IxCheckAPI.class \
 build/gov/fcc/tvstudy/api/RecordAPI.class \
 build/gov/fcc/tvstudy/api/SearchAPI.class

$(JARCORE): $(CLASSCORE)
	jar cf $@ -C build gov/fcc/tvstudy/core

$(JARGUI): $(CLASSGUI) build/codeid/CodeID.class
	jar cf $@ -C build gov/fcc/tvstudy/gui -C build codeid

$(JARAPI): $(CLASSAPI)
	jar cf $@ -C build gov/fcc/tvstudy/api

build/codeid/CodeID.class: src/codeid/CodeID.java
	javac -sourcepath src -encoding UTF8 -classpath build -d build src/codeid/CodeID.java

build/gov/fcc/tvstudy/gui/AppController.class: src/gov/fcc/tvstudy/gui/AppController.java build/codeid/CodeID.class
	javac -sourcepath src -encoding UTF8 -classpath build -d build src/gov/fcc/tvstudy/gui/AppController.java

build/gov/fcc/tvstudy/core/%.class: src/gov/fcc/tvstudy/core/%.java
	javac -sourcepath src -encoding UTF8 -classpath build -d build $<

build/gov/fcc/tvstudy/core/data/%.class: src/gov/fcc/tvstudy/core/data/%.java
	javac -sourcepath src -encoding UTF8 -classpath build -d build $<

build/gov/fcc/tvstudy/core/editdata/%.class: src/gov/fcc/tvstudy/core/editdata/%.java
	javac -sourcepath src -encoding UTF8 -classpath build -d build $<

build/gov/fcc/tvstudy/core/geo/%.class: src/gov/fcc/tvstudy/core/editdata/%.java
	javac -sourcepath src -encoding UTF8 -classpath build -d build $<

build/gov/fcc/tvstudy/gui/%.class: src/gov/fcc/tvstudy/gui/%.java
	javac -sourcepath src -encoding UTF8 -classpath build -d build $<

build/gov/fcc/tvstudy/gui/CalendarButtonIcon.png: src/gov/fcc/tvstudy/gui/CalendarButtonIcon.png
	cp -f $^ $@

build/gov/fcc/tvstudy/gui/editor/%.class: src/gov/fcc/tvstudy/gui/editor/%.java
	javac -sourcepath src -encoding UTF8 -classpath build -d build $<

build/gov/fcc/tvstudy/gui/run/%.class: src/gov/fcc/tvstudy/gui/run/%.java
	javac -sourcepath src -encoding UTF8 -classpath build -d build $<

build/gov/fcc/tvstudy/api/%.class: src/gov/fcc/tvstudy/api/%.java
	javac -sourcepath src -encoding UTF8 -classpath build -d build $<

$(JAR): src/gov/fcc/tvstudy/TVStudy.java Manifest.txt
	javac -sourcepath src -encoding UTF8 -classpath build -d build src/gov/fcc/tvstudy/TVStudy.java; \
	jar cmf Manifest.txt $@ -C build gov/fcc/tvstudy/TVStudy.class

$(JARUTIL): src/gov/fcc/tvstudy/dbutil/DbUtil.java build/codeid/CodeID.class Manifest_dbutil.txt
	javac -sourcepath src -encoding UTF8 -classpath build -d build src/gov/fcc/tvstudy/dbutil/DbUtil.java; \
	jar cmf Manifest_dbutil.txt $@ -C build gov/fcc/tvstudy/dbutil -C build codeid

#++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
# ADDING A NEW MODEL: Add items to the end of both OBJECTLIST_TVSTUDY and OBJECTLIST_UTIL (same for both)
# for the .o files that must be compiled for the new model.  For example if the new model is in a source
# file named "hata.c" in the "src/mode" directory, add "build/model/hata.o" to the end of the lists.  Be
# sure to remember to add \ to the preceding line.  Both C and FORTRAN source are recognized and will be
# built automatically.  If other languages are used, or special compiler flags are needed, additional
# specific build rules must be added below.

OBJECTLIST_TVSTUDY=\
 build/tvstudy.o \
 build/study.o \
 build/parameter.o \
 build/source.o \
 build/report.o \
 build/cell.o \
 build/cache.o \
 build/pattern.o \
 build/fcc_curve_tvstudy.o \
 build/terrain.o \
 build/landcover.o \
 build/map.o \
 build/coordinates.o \
 build/parser.o \
 build/memory.o \
 build/log.o \
 build/model/model_tvstudy.o \
 build/model/longley_rice.o \
 build/model/itsitm.o

OBJECTLIST_UTIL=\
 build/clutil.o \
 build/fcc_curve.o \
 build/terrain.o \
 build/landcover.o \
 build/coordinates.o \
 build/parser.o \
 build/memory.o \
 build/model/model.o \
 build/model/longley_rice.o \
 build/model/itsitm.o

# MacOS build config
#GCC_OPTS=-Wall
#MYSQL_INCLUDE=-I/usr/local/mysql/include
#MYSQL_LIB=/usr/local/mysql/lib/libmysqlclient.a
#LIBM=

# Linux build config
GCC_OPTS=-Wall -D__BUILD_LINUX
MYSQL_INCLUDE=-I../../../include/mysql
MYSQL_LIB=-lmysql
LIBM=-lm

$(EXE): $(OBJECTLIST_TVSTUDY)
	gcc $(GCC_OPTS) $(MYSQL_INCLUDE) -o $@ $^ $(MYSQL_LIB) -lpthread $(LIBM)

$(EXEUTIL): $(OBJECTLIST_UTIL)
	gcc $(GCC_OPTS) -o $@ $^ $(LIBM)

$(EXENAD): src/nadcon.c build/coordinates.o build/memory.o
	gcc $(GCC_OPTS) -o $@ $^ $(LIBM)

$(EXEPOST): src/pair_study_post.c
	gcc $(GCC_OPTS) -o $@ $^ $(LIBM)

$(EXENED): src/convert_ned13.c build/terrain.o build/parser.o build/memory.o src/codeid/codeid.h
	gcc $(GCC_OPTS) -o $@ src/convert_ned13.c build/terrain.o build/parser.o build/memory.o $(LIBM)

# Build rules for all the modules shared by engine and command-line utilities, fcc_curve has different builds.

build/fcc_curve_tvstudy.o: src/fcc_curve.c src/tvstudy.h
	gcc $(GCC_OPTS) -D__BUILD_TVSTUDY $(MYSQL_INCLUDE) -o $@ -c $<

build/fcc_curve.o: src/fcc_curve.c src/global.h src/fcc_curve.h
	gcc $(GCC_OPTS) -o $@ -c $<

build/terrain.o: src/terrain.c src/global.h src/terrain.h src/memory.h
	gcc $(GCC_OPTS) -o $@ -c $<

build/landcover.o: src/landcover.c src/global.h src/landcover.h src/memory.h
	gcc $(GCC_OPTS) -o $@ -c $<

build/coordinates.o: src/coordinates.c src/global.h src/coordinates.h src/memory.h
	gcc $(GCC_OPTS) -o $@ -c $<

build/memory.o: src/memory.c src/global.h src/memory.h
	gcc $(GCC_OPTS) -o $@ -c $<

build/parser.o: src/parser.c src/global.h src/parser.h src/memory.h
	gcc $(GCC_OPTS) -o $@ -c $<

# Build the command-line utility main module.

build/clutil.o: src/clutil.c src/global.h src/model/model.h src/fcc_curve.h src/terrain.h src/landcover.h \
 src/coordinates.h src/parser.h src/memory.h src/codeid/codeid.h
	gcc $(GCC_OPTS) -o $@ -c $<

# Most other modules only used by the engine depend just on tvstudy.h, tvstudy.c and report.c also codeid.h.

build/tvstudy.o: src/tvstudy.c src/tvstudy.h src/codeid/codeid.h
	gcc $(GCC_OPTS) $(MYSQL_INCLUDE) -o $@ -c $<

build/report.o: src/report.c src/tvstudy.h src/codeid/codeid.h
	gcc $(GCC_OPTS) $(MYSQL_INCLUDE) -o $@ -c $<

build/%.o: src/%.c src/tvstudy.h
	gcc $(GCC_OPTS) $(MYSQL_INCLUDE) -o $@ -c $<

# tvstudy.h imports all the individual .h files.

src/tvstudy.h: src/global.h src/model/model.h src/fcc_curve.h src/terrain.h src/landcover.h src/coordinates.h \
 src/parser.h src/memory.h
	touch src/tvstudy.h

# Building model/ modules, model.c different for engine vs. command-line utility.  Any other .c or .f files in
# model/ will be built with .c depending only on model.h.

build/model/model_tvstudy.o: src/model/model.c src/tvstudy.h
	gcc $(GCC_OPTS) -D__BUILD_TVSTUDY $(MYSQL_INCLUDE) -o $@ -c $<

build/model/model.o: src/model/model.c src/global.h src/model/model.h src/fcc_curve.h src/memory.h
	gcc $(GCC_OPTS) $(MYSQL_INCLUDE) -o $@ -c $<

build/model/%.o: src/model/%.c src/model/model.h
	gcc $(GCC_OPTS) -o $@ -c $<

build/model/%.o: src/model/%.f
	gfortran -o $@ -c $<
