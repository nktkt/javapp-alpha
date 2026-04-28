#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
CLASSES_DIR="$ROOT_DIR/build/test-classes"
SOURCES_FILE="$ROOT_DIR/build/test-sources.list"

mkdir -p "$CLASSES_DIR" "$ROOT_DIR/build"

find "$ROOT_DIR/src/main/java" "$ROOT_DIR/src/test/java" -name '*.java' > "$SOURCES_FILE"
javac --release 25 -d "$CLASSES_DIR" @"$SOURCES_FILE"
java -cp "$CLASSES_DIR" dev.javapp.compiler.TranspilerSmokeTest

