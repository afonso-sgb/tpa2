#!/bin/bash
# Start Spread daemon on GCP VM
# Usage: ./start-spread-daemon.sh <node-number>

set -e

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <node-number>"
    echo "Example: $0 1"
    exit 1
fi

NODE_NUM=$1
CONFIG_FILE="/etc/spread/spread.conf"

if [ ! -f "$CONFIG_FILE" ]; then
    echo "ERROR: Spread configuration not found at ${CONFIG_FILE}"
    echo "Please copy spread-node${NODE_NUM}.conf to ${CONFIG_FILE}"
    exit 1
fi

echo "Starting Spread daemon for tpa2-node${NODE_NUM}..."

# Kill existing spread daemon if running
if pgrep -x "spread" > /dev/null; then
    echo "Stopping existing Spread daemon..."
    sudo pkill spread
    sleep 2
fi

# Start daemon in background
sudo spread -c "$CONFIG_FILE" > /var/log/spread/spread.log 2>&1 &

# Wait for daemon to start
sleep 3

# Verify daemon is running
if pgrep -x "spread" > /dev/null; then
    echo "✓ Spread daemon started successfully"
    echo "  - PID: $(pgrep -x spread)"
    echo "  - Listening on: 4803"
    echo "  - Log file: /var/log/spread/spread.log"
    echo ""
    echo "Test connection with: spuser 4803"
else
    echo "✗ ERROR: Spread daemon failed to start"
    echo "Check logs: tail -f /var/log/spread/spread.log"
    exit 1
fi
