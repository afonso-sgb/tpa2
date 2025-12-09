#!/bin/bash

# setup-gluster.sh
# Sets up GlusterFS on 3 nodes (run this on node1)

set -e

echo "=== TPA2 GlusterFS Setup Script ==="

# Configuration
NODE1=${NODE1:-tpa2-node1}
NODE2=${NODE2:-tpa2-node2}
NODE3=${NODE3:-tpa2-node3}
VOLUME_NAME=${VOLUME_NAME:-glustervol}
MOUNT_POINT=${MOUNT_POINT:-/var/sharedfiles}
BRICK_DIR=${BRICK_DIR:-/var/gluster/brick}

# Install GlusterFS server (as per Anexo 3)
echo "Installing GlusterFS server..."
sudo add-apt-repository ppa:gluster/glusterfs-11 -y
sudo apt-get update
sudo apt-get install -y glusterfs-server

# Start GlusterFS daemon
echo "Starting GlusterFS daemon..."
sudo systemctl start glusterd
sudo systemctl enable glusterd

# Create brick directory
echo "Creating brick directory: $BRICK_DIR"
sudo mkdir -p $BRICK_DIR

# Probe peer nodes (run on node1 only)
if [ "$(hostname)" == "$NODE1" ]; then
    echo "Probing peer nodes..."
    sudo gluster peer probe $NODE2
    sudo gluster peer probe $NODE3
    
    # Wait for peers to connect
    sleep 5
    
    # Check peer status
    echo "Peer status:"
    sudo gluster peer status
    
    # Create replicated volume
    echo "Creating replicated volume: $VOLUME_NAME"
    sudo gluster volume create $VOLUME_NAME replica 3 \
        $NODE1:$BRICK_DIR \
        $NODE2:$BRICK_DIR \
        $NODE3:$BRICK_DIR \
        force
    
    # Start the volume
    echo "Starting volume..."
    sudo gluster volume start $VOLUME_NAME
    
    # Display volume info
    echo "Volume info:"
    sudo gluster volume info $VOLUME_NAME
fi

# Create mount point
echo "Creating mount point: $MOUNT_POINT"
sudo mkdir -p $MOUNT_POINT

# Mount the GlusterFS volume
echo "Mounting GlusterFS volume..."
sudo mount -t glusterfs $NODE1:/$VOLUME_NAME $MOUNT_POINT

# Add to /etc/fstab for persistent mounting
echo "Adding to /etc/fstab..."
if ! grep -q "$NODE1:/$VOLUME_NAME" /etc/fstab; then
    echo "$NODE1:/$VOLUME_NAME $MOUNT_POINT glusterfs defaults,_netdev 0 0" | sudo tee -a /etc/fstab
fi

# Set permissions
echo "Setting permissions..."
sudo chmod 777 $MOUNT_POINT

echo "=== GlusterFS setup complete ==="
echo "Volume mounted at: $MOUNT_POINT"
