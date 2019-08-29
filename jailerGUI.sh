#!/bin/sh

instdir=`dirname $0`
cd $instdir
 
LIB=lib

# JDBC-driver
# CP=$CP:<driver-jar>

# configuration files in the config directory
CP=$CP:config

# the libraries
CP=$CP:$LIB/junit.jar
CP=$CP:$LIB/log4j.jar
CP=$CP:$LIB/args4j.jar
CP=$CP:$LIB/prefuse.jar
CP=$CP:$LIB/sdoc-0.5.0-beta.jar
CP=$CP:$LIB/activation-1.0.2.jar
CP=$CP:$LIB/jaxb-core-2.3.0-b170127.1453.jar
CP=$CP:$LIB/jaxb-impl-2.3.0-b170127.1453.jar
CP=$CP:$LIB/jaxb-api-2.3.0-b170201.1204.jar
CP=$CP:$LIB/jsqlparser-1.3.jar
CP=$CP:$LIB/tablefilter-swing-5.3.1.jar
CP=$CP:jailer.jar

# echo $CP

java -Xmx1200M -cp $CP net.sf.jailer.ui.ExtractionModelFrame "$@"

