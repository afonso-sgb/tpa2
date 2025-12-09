#!/bin/bash
# Install and configure Spread Toolkit on Ubuntu 24 LTS
# For GCP VM deployment - TPA2 Project

set -e

echo "================================================"
echo "Installing Spread Toolkit on Ubuntu 24 LTS"
echo "================================================"

# Install build dependencies
echo "[1/5] Installing build dependencies..."
sudo apt update
sudo apt install -y build-essential gcc make wget

# Download Spread Toolkit
echo "[2/5] Downloading Spread Toolkit 5.0.1..."
cd /tmp
wget http://www.spread.org/download/spread-src-5.0.1.tar.gz

# Extract and compile
echo "[3/5] Extracting and compiling Spread..."
tar -xzf spread-src-5.0.1.tar.gz
cd spread-src-5.0.1

./configure
make
sudo make install

# Create runtime directory
echo "[4/5] Creating runtime directories..."
sudo mkdir -p /var/run/spread
sudo mkdir -p /etc/spread
sudo mkdir -p /var/log/spread
sudo chmod 777 /var/run/spread
sudo chmod 777 /var/log/spread

# Verify installation
echo "[5/5] Verifying installation..."
which spread
spread -v

echo "================================================"
echo "Spread Toolkit installed successfully!"
echo "================================================"
echo ""
echo "Next steps:"
echo "1. Copy appropriate spread-nodeX.conf to /etc/spread/spread.conf"
echo "2. Start daemon: sudo spread -c /etc/spread/spread.conf"
echo "3. Test connection: spuser 4803"
echo ""
