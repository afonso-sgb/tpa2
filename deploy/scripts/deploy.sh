#!/bin/bash

# deploy.sh
# Deploys the TPA2 application to GCP VMs

set -e

echo "=== TPA2 Deployment Script ==="

# Configuration
NODES=(tpa2-node1 tpa2-node2 tpa2-node3)
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Build the project
echo "Building Maven project..."
cd "$PROJECT_DIR"
mvn clean package -DskipTests

# Function to copy files to a node
deploy_to_node() {
    local node=$1
    echo "Deploying to $node..."
    
    # Copy JARs
    gcloud compute scp worker/target/worker.jar $node:/tmp/worker.jar
    
    # SSH and set up
    gcloud compute ssh $node -- bash << 'EOF'
        # Move JAR to application directory
        sudo mkdir -p /opt/tpa2
        sudo mv /tmp/worker.jar /opt/tpa2/
        sudo chmod +x /opt/tpa2/worker.jar
        
        # Create systemd service
        sudo tee /etc/systemd/system/tpa2-worker.service > /dev/null << 'SERVICE'
[Unit]
Description=TPA2 Worker Service
After=network.target glusterd.service

[Service]
Type=simple
User=nobody
Environment="WORKER_ID=$(hostname)"
Environment="RABBITMQ_HOST=tpa2-node1"
Environment="RABBITMQ_PORT=5672"
Environment="SHARED_FILES_DIR=/var/sharedfiles"
ExecStart=/usr/bin/java -jar /opt/tpa2/worker.jar
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
SERVICE
        
        # Reload systemd and start service
        sudo systemctl daemon-reload
        sudo systemctl enable tpa2-worker
        sudo systemctl restart tpa2-worker
        
        echo "Worker service deployed and started on $(hostname)"
EOF
}

# Deploy RabbitMQ on node1
echo "Deploying RabbitMQ on ${NODES[0]}..."
gcloud compute ssh ${NODES[0]} -- bash << 'EOF'
    # Stop any existing RabbitMQ container
    docker stop rabbitmq 2>/dev/null || true
    docker rm rabbitmq 2>/dev/null || true
    
    # Run RabbitMQ
    docker run -d \
        --name rabbitmq \
        --hostname rabbitmq \
        -p 5672:5672 \
        -p 15672:15672 \
        rabbitmq:3-management
    
    echo "RabbitMQ started on $(hostname)"
EOF

# Deploy workers to all nodes
for node in "${NODES[@]}"; do
    deploy_to_node $node &
done

wait

echo "=== Deployment complete ==="
echo "RabbitMQ Management UI: http://$(gcloud compute instances describe ${NODES[0]} --format='get(networkInterfaces[0].accessConfigs[0].natIP)'):15672"
echo "Default credentials: guest/guest"
