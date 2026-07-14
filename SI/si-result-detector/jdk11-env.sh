#!/usr/bin/env bash
# Optional: force JDK 11 for this shell before running ./gradlew (matches PolySI build.gradle).
# Does not change build.gradle or wrapper.
#
#   source ./jdk11-env.sh
#   ./gradlew compileJava
#
# If your distro uses a different path, set JAVA_HOME manually after sourcing, or edit the line below.

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-11-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$PATH"
