#!/bin/sh
PROJECT_DIR="$(dirname "$0")"

$PROJECT_DIR/gradlew clean extract-to-bin shadowJar