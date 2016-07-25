#!/bin/sh
export JAVA_HOME=/usr/lib/jvm/jdk1.8.0_31
export PATH=$JAVA_HOME/bin:$PATH

java -Xmx1000m -Xms1000m -Dfootprint.cache=512 -cp "lib/*" it.geosolutions.footprint.FootprintExtractionTool $@
