#!/bin/bash

# provision-base.sh
# Provisions a base VM with required software for TPA2

set -e

echo "=== TPA2 Base VM Provisioning Script ==="

# Update system
echo "Updating system packages..."
sudo apt-get update
sudo apt-get upgrade -y

# Install Java 21
echo "Installing OpenJDK 21..."
sudo apt-get install -y openjdk-21-jdk

# Install Docker
echo "Installing Docker..."
sudo apt-get install -y apt-transport-https ca-certificates curl software-properties-common
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo apt-key add -
sudo add-apt-repository "deb [arch=amd64] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable"
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io

# Add current user to docker group
sudo usermod -aG docker $USER

# Install build tools
echo "Installing build tools..."
sudo apt-get install -y build-essential gcc g++ make python3 python3-pip git

# Install Maven (optional, for local builds)
echo "Installing Maven..."
sudo apt-get install -y maven

echo "=== Base provisioning complete ==="
echo "NOTE: You may need to log out and back in for docker group changes to take effect"
