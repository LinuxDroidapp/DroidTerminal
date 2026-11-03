#!/bin/sh
##
## Gradle start up script for UN*X
##
set -e

APP_HOME="$(cd "$(dirname "$0")" && pwd)"

if [ -z "$JAVA_HOME" ]; then
    JAVA_EXE=$(which java)
else
    JAVA_EXE="$JAVA_HOME/bin/java"
fi

exec "$JAVA_EXE" \
    -Xmx2g \
    -Dfile.encoding=UTF-8 \
    -Dorg.gradle.appname="$0" \
    -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
