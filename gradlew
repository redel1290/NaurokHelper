#!/bin/sh

##############################################################################
# Gradle startup script for UN*X
##############################################################################

# Attempt to set APP_HOME
APP_HOME=$( cd "${0%/*}" 2>/dev/null && pwd )
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

exec java $DEFAULT_JVM_OPTS $JAVA_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
