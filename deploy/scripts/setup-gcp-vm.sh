#!/bin/bash
# Complete setup script for GCP VM - TPA2 Project
# Installs: Java 21, Docker, Spread Toolkit, GlusterFS
# Usage: ./setup-gcp-vm.sh <node-number> <node1-ip> <node2-ip> <node3-ip>

set -e

if [ "$#" -ne 4 ]; then
    echo "Usage: $0 <node-number> <node1-ip> <node2-ip> <node3-ip>"
    echo "Example: $0 1 10.128.0.8 10.128.0.10 10.128.0.11"
    exit 1
fi

NODE_NUM=$1
NODE1_IP=$2
NODE2_IP=$3
NODE3_IP=$4
HOSTNAME="tpa2-node${NODE_NUM}"

echo "================================================"
echo "Setting up ${HOSTNAME}"
echo "Node IPs: ${NODE1_IP}, ${NODE2_IP}, ${NODE3_IP}"
echo "================================================"

# Update system
echo "[1/6] Updating system..."
sudo apt update
sudo apt upgrade -y

# Install Java 21
echo "[2/6] Installing Java 21..."
sudo apt install -y openjdk-21-jdk
java -version

# Install Docker
echo "[3/6] Installing Docker..."
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh
sudo usermod -aG docker $USER
docker --version

# Install Docker Compose
echo "[4/6] Installing Docker Compose..."
sudo curl -L "https://github.com/docker/compose/releases/download/v2.24.0/docker-compose-$(uname -s)-$(uname -m)" \
    -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose
docker-compose --version

# Install GlusterFS
echo "[5/6] Installing GlusterFS..."
sudo add-apt-repository -y ppa:gluster/glusterfs-11
sudo apt update
sudo apt install -y glusterfs-server
sudo service glusterd start
sudo mkdir -p /var/gluster/brick
sudo chmod 777 /var/gluster/brick
sudo mkdir -p /var/sharedfiles
sudo chmod 777 /var/sharedfiles

# Configure /etc/hosts
echo "[6/6] Configuring /etc/hosts..."
sudo bash -c "cat >> /etc/hosts << EOF

# TPA2 Cluster Nodes
${NODE1_IP} tpa2-node1
${NODE2_IP} tpa2-node2
${NODE3_IP} tpa2-node3
EOF"

# Install Spread Toolkit
echo "[7/7] Installing Spread Toolkit..."
cd /tmp
wget http://www.spread.org/download/spread-src-5.0.1.tar.gz
tar -xzf spread-src-5.0.1.tar.gz
cd spread-src-5.0.1
sudo apt install -y build-essential gcc make
./configure
make
sudo make install
sudo mkdir -p /var/run/spread /etc/spread /var/log/spread
sudo chmod 777 /var/run/spread /var/log/spread

echo "================================================"
echo "VM Setup Complete!"
echo "================================================"
echo ""
echo "Installation Summary:"
echo "  - Java:          $(java -version 2>&1 | head -n1)"
echo "  - Docker:        $(docker --version)"
echo "  - Docker Compose: $(docker-compose --version)"
echo "  - GlusterFS:     $(glusterd --version | head -n1)"
echo "  - Spread:        $(spread -v 2>&1 | head -n1)"
echo ""
echo "Next steps:"
echo "  1. Reboot VM: sudo reboot"
echo "  2. Copy Spread config: scp spread-node${NODE_NUM}.conf ${HOSTNAME}:/etc/spread/spread.conf"
echo "  3. Start Spread daemon: sudo spread -c /etc/spread/spread.conf &"
echo "  4. Configure GlusterFS (see setup-gluster.sh)"
echo ""
