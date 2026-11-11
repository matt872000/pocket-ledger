#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d "$classes" app/src/main/java/com/pocketledger/BankSmsParser.java tests/ParserChecks.java
java -cp "$classes" ParserChecks
