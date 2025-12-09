#!/bin/bash

# test-email017.sh
# Tests that email017.txt can be found with the specified substrings from Anexo 1

set -e

echo "=== Testing email017.txt Search (Anexo 1) ==="

USERAPP_JAR="userapp/target/userapp.jar"

if [ ! -f "$USERAPP_JAR" ]; then
    echo "Building project..."
    mvn clean package -DskipTests
fi

echo ""
echo "Test 1: Search with 'gRPC em Java 21'"
java -jar $USERAPP_JAR search "gRPC em Java 21"

echo ""
echo "Test 2: Search with 'GCP'"
java -jar $USERAPP_JAR search "GCP"

echo ""
echo "Test 3: Search with 'Docker'"
java -jar $USERAPP_JAR search "Docker"

echo ""
echo "Test 4: Search with all three: 'gRPC em Java 21', 'GCP', 'Docker'"
java -jar $USERAPP_JAR search "gRPC em Java 21" "GCP" "Docker"

echo ""
echo "=== All tests complete ==="
echo "Email017.txt should appear in all search results above"
