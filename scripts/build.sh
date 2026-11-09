#!/usr/bin/env bash
# Compile and package without a build tool download (JDK 21 + one JDBC jar).
set -euo pipefail
cd "$(dirname "$0")/.."

PG_JAR=lib/postgresql-42.7.4.jar
if [ ! -f "$PG_JAR" ]; then
  mkdir -p lib
  curl -fsSL -o "$PG_JAR" https://github.com/pgjdbc/pgjdbc/releases/download/REL42.7.4/postgresql-42.7.4.jar
fi

rm -rf build && mkdir -p build/classes build/test-classes
javac --release 21 -Xlint:all -Werror -d build/classes $(find src/main/java -name '*.java')
javac --release 21 -cp build/classes -d build/test-classes $(find src/test/java bench -name '*.java')

cat > build/MANIFEST.MF <<EOF
Main-Class: dev.nishant.tally.App
Class-Path: ../$PG_JAR
EOF
jar --create --file build/tally.jar --manifest build/MANIFEST.MF -C build/classes .
echo "built build/tally.jar"
