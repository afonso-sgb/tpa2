#!/bin/bash

# run-tests.sh
# Runs integration tests against the deployed system

set -e

echo "=== TPA2 Integration Tests ==="

# Configuration
RABBITMQ_HOST=${RABBITMQ_HOST:-localhost}
RABBITMQ_PORT=${RABBITMQ_PORT:-5672}
USERAPP_JAR="userapp/target/userapp.jar"

echo "Testing against RabbitMQ at $RABBITMQ_HOST:$RABBITMQ_PORT"

# Build if not already built
if [ ! -f "$USERAPP_JAR" ]; then
    echo "Building project..."
    mvn clean package -DskipTests
fi

# Test 1: Search for files
echo ""
echo "Test 1: Search for files containing 'meeting' and 'schedule'"
java -jar $USERAPP_JAR search meeting schedule

# Test 2: Get file content
echo ""
echo "Test 2: Get file content"
# This assumes a file was found in test 1
# In a real test, we'd parse the output and use an actual filename
# java -jar $USERAPP_JAR get-file /var/sharedfiles/email001.txt

# Test 3: Get statistics
echo ""
echo "Test 3: Get statistics"
java -jar $USERAPP_JAR get-stats

echo ""
echo "=== Tests complete ==="
