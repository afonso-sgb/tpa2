# TPA2 - Complete GCP Deployment Guide

**Project:** Distributed Email Search System  
**Course:** Computação Distribuída (CD-2526)  
**Date:** December 9, 2025  
**Deadline:** December 13, 2025

---

## 📋 Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Prerequisites](#prerequisites)
3. [Phase 1: GCP VM Creation](#phase-1-gcp-vm-creation)
4. [Phase 2: Base System Configuration](#phase-2-base-system-configuration)
5. [Phase 3: Spread Toolkit Installation](#phase-3-spread-toolkit-installation)
6. [Phase 4: GlusterFS Configuration](#phase-4-glusterfs-configuration)
7. [Phase 5: Application Deployment](#phase-5-application-deployment)
8. [Phase 6: Testing & Validation](#phase-6-testing--validation)
9. [Troubleshooting](#troubleshooting)

---

## Architecture Overview

### System Components (3 VMs)

```
┌─────────────────────────────────────────────────────────────────┐
│                         GCP Network                              │
│  Subnet: 10.128.0.0/20 (us-central1)                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐        │
│  │   VM Node 1  │   │   VM Node 2  │   │   VM Node 3  │        │
│  │ 10.128.0.2   │   │ 10.128.0.3   │   │ 10.128.0.4   │        │
│  ├──────────────┤   ├──────────────┤   ├──────────────┤        │
│  │ RabbitMQ     │   │ Worker 2     │   │ Worker 3     │        │
│  │ (Docker)     │   │ (Java 21)    │   │ (Java 21)    │        │
│  │              │   │              │   │              │        │
│  │ Worker 1     │   │ Spread       │   │ Spread       │        │
│  │ (Java 21)    │   │ Daemon       │   │ Daemon       │        │
│  │              │   │              │   │              │        │
│  │ Spread       │   │ GlusterFS    │   │ GlusterFS    │        │
│  │ Daemon       │   │ Brick        │   │ Brick        │        │
│  │              │   │              │   │              │        │
│  │ GlusterFS    │   │              │   │              │        │
│  │ Brick        │   │              │   │              │        │
│  └──────────────┘   └──────────────┘   └──────────────┘        │
│         │                   │                   │                │
│         └───────────────────┴───────────────────┘                │
│                   GlusterFS Volume                               │
│              /var/sharedfiles/emails                             │
│          (20 email .txt files replicated)                        │
│                                                                   │
│  Spread Group: tpa2_workers                                      │
│  RabbitMQ Queue: email_requests                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Communication Patterns

**RabbitMQ (Work Queue):**
- UserApp → RabbitMQ → Workers (request distribution)
- Workers → RabbitMQ → UserApp (async responses)

**Spread Toolkit (Multicast Group):**
- Worker membership tracking
- Stats request election (multicast)
- Stats collection (P2P messaging)
- Leader coordination

**GlusterFS:**
- Replicated file system across all 3 VMs
- Email files accessible at `/var/sharedfiles/emails`

---

## Prerequisites

### Local Machine Requirements

1. **Google Cloud SDK installed**
   ```bash
   # Verify installation
   gcloud --version
   ```

2. **GCP Project configured**
   ```bash
   # Login to GCP
   gcloud auth login
   
   # Set project
   gcloud config set project YOUR_PROJECT_ID
   
   # Set default region
   gcloud config set compute/region us-central1
   gcloud config set compute/zone us-central1-a
   ```

3. **Project JARs built**
   ```bash
   # On your local machine
   cd C:\Users\asben\Code\TPA2
   mvn clean package -DskipTests
   
   # Verify JARs exist:
   # - userapp/target/userapp.jar
   # - worker/target/worker.jar
   ```

4. **Email files prepared**
   ```bash
   # Ensure EmailFiles/ directory contains 20 .txt files
   ls EmailFiles/*.txt
   ```

---

## Phase 1: GCP VM Creation

### Step 1.1: Create VMs

```bash
# Create VM instances with Ubuntu 24.04 LTS
for i in 1 2 3; do
  gcloud compute instances create tpa2-node${i} \
    --zone=us-central1-a \
    --machine-type=e2-medium \
    --image-family=ubuntu-2404-lts-amd64 \
    --image-project=ubuntu-os-cloud \
    --boot-disk-size=20GB \
    --boot-disk-type=pd-standard \
    --tags=tpa2-node \
    --metadata=enable-oslogin=FALSE
done
```

### Step 1.2: Configure Firewall Rules

```bash
# Allow RabbitMQ ports (5672 - AMQP, 15672 - Management UI)
gcloud compute firewall-rules create tpa2-rabbitmq \
  --allow=tcp:5672,tcp:15672 \
  --source-ranges=0.0.0.0/0 \
  --target-tags=tpa2-node \
  --description="RabbitMQ AMQP and Management"

# Allow Spread daemon port (4803)
gcloud compute firewall-rules create tpa2-spread \
  --allow=tcp:4803 \
  --source-ranges=10.128.0.0/20 \
  --target-tags=tpa2-node \
  --description="Spread Toolkit daemon"

# Allow GlusterFS ports (24007-24008, 49152-49251)
gcloud compute firewall-rules create tpa2-gluster \
  --allow=tcp:24007-24008,tcp:49152-49251 \
  --source-ranges=10.128.0.0/20 \
  --target-tags=tpa2-node \
  --description="GlusterFS replication"

# Allow SSH
gcloud compute firewall-rules create tpa2-ssh \
  --allow=tcp:22 \
  --source-ranges=0.0.0.0/0 \
  --target-tags=tpa2-node \
  --description="SSH access"
```

### Step 1.3: Get VM IP Addresses

```bash
# List VMs and their IPs
gcloud compute instances list --filter="name:tpa2-node"

# Expected output (your IPs may vary):
# NAME         ZONE           MACHINE_TYPE  INTERNAL_IP  EXTERNAL_IP
# tpa2-node1   us-central1-a  e2-medium     10.128.0.2   34.xxx.xxx.xxx
# tpa2-node2   us-central1-a  e2-medium     10.128.0.3   34.xxx.xxx.xxx
# tpa2-node3   us-central1-a  e2-medium     10.128.0.4   34.xxx.xxx.xxx
```

**📝 Note:** Save the INTERNAL_IP addresses - you'll need them for configuration!

---

## Phase 2: Base System Configuration

### Step 2.1: Upload Setup Scripts

```bash
# Upload scripts to all VMs
for i in 1 2 3; do
  gcloud compute scp deploy/scripts/setup-gcp-vm.sh tpa2-node${i}:~ --zone=us-central1-a
  gcloud compute scp deploy/scripts/install-spread.sh tpa2-node${i}:~ --zone=us-central1-a
done
```

### Step 2.2: Run Base Setup on All VMs

**Connect to each VM and run:**

```bash
# SSH to Node 1
gcloud compute ssh tpa2-node1 --zone=us-central1-a

# Run setup script
chmod +x setup-gcp-vm.sh
sudo ./setup-gcp-vm.sh

# Verify installations
java -version        # Should show openjdk 21
docker --version     # Should show Docker version
gcc --version        # Should show GCC compiler

# Exit VM
exit
```

**Repeat for Node 2 and Node 3:**

```bash
gcloud compute ssh tpa2-node2 --zone=us-central1-a
sudo ./setup-gcp-vm.sh
exit

gcloud compute ssh tpa2-node3 --zone=us-central1-a
sudo ./setup-gcp-vm.sh
exit
```

---

## Phase 3: Spread Toolkit Installation

### Step 3.1: Install Spread on All Nodes

**On each VM:**

```bash
# SSH to node
gcloud compute ssh tpa2-node1 --zone=us-central1-a

# Run Spread installation
chmod +x install-spread.sh
sudo ./install-spread.sh

# Verify installation
which spread
ls -la /usr/local/bin/spread

exit
```

Repeat for nodes 2 and 3.

### Step 3.2: Configure Spread Daemons

**Create configuration files on each node:**

**Node 1 - /etc/spread/spread.conf:**

```bash
gcloud compute ssh tpa2-node1 --zone=us-central1-a

sudo mkdir -p /etc/spread
sudo tee /etc/spread/spread.conf > /dev/null <<'EOF'
# Spread Configuration for TPA2 - Node 1
Spread_Segment 10.128.0.255:4803 {
    node1   10.128.0.2
    node2   10.128.0.3
    node3   10.128.0.4
}

EventLogFile = /var/log/spread.log
EventTimeStamp

DangerousMonitor = false
MaxSessionMessages = 10000

RuntimeDir = /var/run/spread
EOF

# Create runtime directory
sudo mkdir -p /var/run/spread
sudo mkdir -p /var/log

exit
```

**Node 2 - /etc/spread/spread.conf:**

```bash
gcloud compute ssh tpa2-node2 --zone=us-central1-a

sudo mkdir -p /etc/spread
sudo tee /etc/spread/spread.conf > /dev/null <<'EOF'
# Spread Configuration for TPA2 - Node 2
Spread_Segment 10.128.0.255:4803 {
    node1   10.128.0.2
    node2   10.128.0.3
    node3   10.128.0.4
}

EventLogFile = /var/log/spread.log
EventTimeStamp

DangerousMonitor = false
MaxSessionMessages = 10000

RuntimeDir = /var/run/spread
EOF

sudo mkdir -p /var/run/spread
sudo mkdir -p /var/log

exit
```

**Node 3 - Similar configuration**

```bash
gcloud compute ssh tpa2-node3 --zone=us-central1-a

sudo mkdir -p /etc/spread
sudo tee /etc/spread/spread.conf > /dev/null <<'EOF'
# Spread Configuration for TPA2 - Node 3
Spread_Segment 10.128.0.255:4803 {
    node1   10.128.0.2
    node2   10.128.0.3
    node3   10.128.0.4
}

EventLogFile = /var/log/spread.log
EventTimeStamp

DangerousMonitor = false
MaxSessionMessages = 10000

RuntimeDir = /var/run/spread
EOF

sudo mkdir -p /var/run/spread
sudo mkdir -p /var/log

exit
```

### Step 3.3: Start Spread Daemons

**On each node:**

```bash
# Node 1
gcloud compute ssh tpa2-node1 --zone=us-central1-a
sudo nohup spread -n node1 -c /etc/spread/spread.conf &
exit

# Node 2
gcloud compute ssh tpa2-node2 --zone=us-central1-a
sudo nohup spread -n node2 -c /etc/spread/spread.conf &
exit

# Node 3
gcloud compute ssh tpa2-node3 --zone=us-central1-a
sudo nohup spread -n node3 -c /etc/spread/spread.conf &
exit
```

### Step 3.4: Verify Spread Cluster

```bash
gcloud compute ssh tpa2-node1 --zone=us-central1-a

# Check Spread is running
ps aux | grep spread

# Check logs
sudo tail -f /var/log/spread.log
# Should see: "Configuration at node1 is:"
# Should see all 3 nodes in the segment

exit
```

---

## Phase 4: GlusterFS Configuration

### Step 4.1: Install GlusterFS on All Nodes

```bash
# On each VM
for i in 1 2 3; do
  gcloud compute ssh tpa2-node${i} --zone=us-central1-a << 'ENDSSH'
    # Install GlusterFS server
    sudo apt-get update
    sudo apt-get install -y glusterfs-server
    
    # Start GlusterFS service
    sudo systemctl start glusterd
    sudo systemctl enable glusterd
    
    # Create brick directory
    sudo mkdir -p /data/gluster/emails
    
    exit
ENDSSH
done
```

### Step 4.2: Create Gluster Cluster

**Run ONLY on Node 1:**

```bash
gcloud compute ssh tpa2-node1 --zone=us-central1-a

# Probe other nodes
sudo gluster peer probe 10.128.0.3
sudo gluster peer probe 10.128.0.4

# Verify peer status
sudo gluster peer status
# Should show 2 peers connected

# Create replicated volume (3-way replication)
sudo gluster volume create emailfiles replica 3 \
  10.128.0.2:/data/gluster/emails \
  10.128.0.3:/data/gluster/emails \
  10.128.0.4:/data/gluster/emails \
  force

# Start volume
sudo gluster volume start emailfiles

# Verify volume
sudo gluster volume info emailfiles

exit
```

### Step 4.3: Mount GlusterFS Volume on All Nodes

```bash
for i in 1 2 3; do
  gcloud compute ssh tpa2-node${i} --zone=us-central1-a << 'ENDSSH'
    # Create mount point
    sudo mkdir -p /var/sharedfiles/emails
    
    # Mount GlusterFS volume
    sudo mount -t glusterfs localhost:/emailfiles /var/sharedfiles/emails
    
    # Add to fstab for auto-mount on reboot
    echo "localhost:/emailfiles /var/sharedfiles/emails glusterfs defaults,_netdev 0 0" | sudo tee -a /etc/fstab
    
    # Verify mount
    df -h | grep emailfiles
    
    exit
ENDSSH
done
```

### Step 4.4: Upload Email Files

**Upload from local machine to Node 1:**

```bash
# Create tar of email files
cd C:\Users\asben\Code\TPA2
tar -czf EmailFiles.tar.gz EmailFiles/

# Upload to Node 1
gcloud compute scp EmailFiles.tar.gz tpa2-node1:~ --zone=us-central1-a

# Extract on Node 1 (will replicate to all nodes via GlusterFS)
gcloud compute ssh tpa2-node1 --zone=us-central1-a << 'ENDSSH'
  tar -xzf EmailFiles.tar.gz
  sudo cp EmailFiles/*.txt /var/sharedfiles/emails/
  sudo chmod 644 /var/sharedfiles/emails/*.txt
  ls -la /var/sharedfiles/emails/
  exit
ENDSSH
```

**Verify replication on other nodes:**

```bash
# Check Node 2
gcloud compute ssh tpa2-node2 --zone=us-central1-a
ls -la /var/sharedfiles/emails/
# Should show 20 .txt files
exit

# Check Node 3
gcloud compute ssh tpa2-node3 --zone=us-central1-a
ls -la /var/sharedfiles/emails/
# Should show 20 .txt files
exit
```

---

## Phase 5: Application Deployment

### Step 5.1: Start RabbitMQ on Node 1

**Upload Docker Compose file:**

```bash
# Upload compose file
gcloud compute scp deploy/docker-compose.yml tpa2-node1:~ --zone=us-central1-a

# SSH to Node 1
gcloud compute ssh tpa2-node1 --zone=us-central1-a

# Start RabbitMQ
docker-compose up -d rabbitmq

# Verify RabbitMQ is running
docker ps
docker logs rabbitmq

# Wait for RabbitMQ to be ready (about 30 seconds)
sleep 30

exit
```

**Access RabbitMQ Management UI:**
```
http://<NODE1_EXTERNAL_IP>:15672
Username: guest
Password: guest
```

### Step 5.2: Deploy Worker JAR to All Nodes

```bash
# Upload worker JAR to all nodes
for i in 1 2 3; do
  gcloud compute scp worker/target/worker.jar tpa2-node${i}:~/worker.jar --zone=us-central1-a
done
```

### Step 5.3: Start Workers on All Nodes

**Node 1 - Worker 1:**

```bash
gcloud compute ssh tpa2-node1 --zone=us-central1-a

# Start worker in background
nohup java -jar worker.jar \
  --worker-id worker-001 \
  --rabbitmq-host 10.128.0.2 \
  --rabbitmq-port 5672 \
  --spread-host "4803@10.128.0.2" \
  --spread-group tpa2_workers \
  --file-dir /var/sharedfiles/emails \
  > worker.log 2>&1 &

# Check worker is running
tail -f worker.log
# Press Ctrl+C to stop tailing

exit
```

**Node 2 - Worker 2:**

```bash
gcloud compute ssh tpa2-node2 --zone=us-central1-a

nohup java -jar worker.jar \
  --worker-id worker-002 \
  --rabbitmq-host 10.128.0.2 \
  --rabbitmq-port 5672 \
  --spread-host "4803@10.128.0.3" \
  --spread-group tpa2_workers \
  --file-dir /var/sharedfiles/emails \
  > worker.log 2>&1 &

tail -f worker.log

exit
```

**Node 3 - Worker 3:**

```bash
gcloud compute ssh tpa2-node3 --zone=us-central1-a

nohup java -jar worker.jar \
  --worker-id worker-003 \
  --rabbitmq-host 10.128.0.2 \
  --rabbitmq-port 5672 \
  --spread-host "4803@10.128.0.4" \
  --spread-group tpa2_workers \
  --file-dir /var/sharedfiles/emails \
  > worker.log 2>&1 &

tail -f worker.log

exit
```

### Step 5.4: Verify Worker Cluster

**Check Spread group membership:**

```bash
gcloud compute ssh tpa2-node1 --zone=us-central1-a

# Check worker log for membership messages
grep "Member joined" worker.log
# Should see messages about all 3 workers joining tpa2_workers group

exit
```

---

## Phase 6: Testing & Validation

### Step 6.1: Deploy UserApp

**Upload UserApp JAR:**

```bash
# Upload to Node 1 (or run from local machine)
gcloud compute scp userapp/target/userapp.jar tpa2-node1:~/userapp.jar --zone=us-central1-a
```

### Step 6.2: Run UserApp

```bash
# SSH to Node 1
gcloud compute ssh tpa2-node1 --zone=us-central1-a

# Start UserApp
java -jar userapp.jar --rabbitmq-host 10.128.0.2 --rabbitmq-port 5672
```

### Step 6.3: Test Search Functionality

**In UserApp console:**

```
> search body meeting
Searching for keywords in body: [meeting]
Request ID: req-1234567890
Waiting for response...

Response received:
Found 5 files:
- email001.txt
- email007.txt
- email012.txt
- email015.txt
- email018.txt
```

**Test with multiple keywords:**

```
> search body Java GCP Docker
Searching for keywords in body: [Java, GCP, Docker]
Request ID: req-1234567891
Waiting for response...

Response received:
Found 2 files:
- email003.txt
- email017.txt
```

### Step 6.4: Test Get-File Functionality

```
> get-file email017.txt
Requesting file: email017.txt
Request ID: req-1234567892
Waiting for response...

Response received:
File: email017.txt
Content:
From: developer@example.com
To: team@example.com
Subject: gRPC em Java 21 implementation

We need to deploy our gRPC service on GCP using Docker containers...
[full file content displayed]
```

### Step 6.5: Test Statistics (MOST IMPORTANT!)

**This tests the Spread-based leader election!**

```
> get-stats
Requesting global statistics...
Request ID: req-1234567893
Waiting for response...

[On worker logs, you'll see:]
- Election initiated
- Workers voting via Spread multicast
- Coordinator elected (worker with longest uptime)
- Stats collected from all workers via Spread P2P
- Aggregated response sent

Response received:
Global Statistics:
- Total requests: 15
- Successful requests: 12
- Failed requests: 3
- Active workers: 3
```

### Step 6.6: Verify Load Distribution

**Check worker logs on all nodes:**

```bash
# Node 1
gcloud compute ssh tpa2-node1 --zone=us-central1-a
grep "Processing request" worker.log | wc -l
exit

# Node 2
gcloud compute ssh tpa2-node2 --zone=us-central1-a
grep "Processing request" worker.log | wc -l
exit

# Node 3
gcloud compute ssh tpa2-node3 --zone=us-central1-a
grep "Processing request" worker.log | wc -l
exit
```

All workers should show approximately equal request counts (RabbitMQ round-robin).

### Step 6.7: Test GlusterFS Replication

**Add a new file on Node 1:**

```bash
gcloud compute ssh tpa2-node1 --zone=us-central1-a

# Create new email file
sudo tee /var/sharedfiles/emails/email021.txt > /dev/null <<'EOF'
From: test@example.com
To: admin@example.com
Subject: Test replication

This file tests GlusterFS replication across nodes.
Keywords: Java 21, GCP, Docker, Spread
EOF

exit
```

**Verify on Node 2 and Node 3:**

```bash
# Should appear immediately on all nodes
gcloud compute ssh tpa2-node2 --zone=us-central1-a
ls -la /var/sharedfiles/emails/email021.txt
cat /var/sharedfiles/emails/email021.txt
exit
```

**Search for the new file:**

```
> search body replication
Searching for keywords in body: [replication]

Response received:
Found 1 files:
- email021.txt
```

---

## Troubleshooting

### Spread Issues

**Problem:** Workers can't connect to Spread daemon

```bash
# Check Spread is running
gcloud compute ssh tpa2-node1 --zone=us-central1-a
ps aux | grep spread
sudo tail /var/log/spread.log

# Restart Spread
sudo pkill spread
sudo nohup spread -n node1 -c /etc/spread/spread.conf &
```

**Problem:** Spread cluster not forming

```bash
# Verify firewall
gcloud compute firewall-rules list | grep tpa2

# Check spread.conf has correct IPs
cat /etc/spread/spread.conf

# Verify network connectivity
ping 10.128.0.3
telnet 10.128.0.3 4803
```

### RabbitMQ Issues

**Problem:** Workers can't connect to RabbitMQ

```bash
# Check RabbitMQ is running
gcloud compute ssh tpa2-node1 --zone=us-central1-a
docker ps | grep rabbitmq
docker logs rabbitmq

# Restart RabbitMQ
docker restart rabbitmq
```

**Problem:** Messages stuck in queue

```bash
# Check RabbitMQ Management UI
# http://<NODE1_EXTERNAL_IP>:15672

# Purge queue if needed
docker exec rabbitmq rabbitmqctl purge_queue email_requests
```

### GlusterFS Issues

**Problem:** Files not replicating

```bash
# Check volume status
sudo gluster volume status emailfiles

# Check mount
df -h | grep emailfiles

# Remount if needed
sudo umount /var/sharedfiles/emails
sudo mount -t glusterfs localhost:/emailfiles /var/sharedfiles/emails
```

**Problem:** Volume not accessible

```bash
# Check peers
sudo gluster peer status

# Restart glusterd
sudo systemctl restart glusterd
sudo gluster volume start emailfiles
```

### Worker Issues

**Problem:** Worker crashes or exits

```bash
# Check worker logs
gcloud compute ssh tpa2-node1 --zone=us-central1-a
tail -100 worker.log

# Check for Java errors
grep Exception worker.log
grep Error worker.log

# Restart worker
pkill -f worker.jar
nohup java -jar worker.jar [parameters] > worker.log 2>&1 &
```

### Statistics Election Issues

**Problem:** Stats request times out

```bash
# Check all workers are in Spread group
grep "Member joined" worker.log

# Check election logs
grep "Election" worker.log
grep "STATS_ELECTION" worker.log

# Verify Spread multicast is working
grep "Multicasted" worker.log
```

---

## System Monitoring

### Check All Services Status

```bash
# Create monitoring script
cat > check-status.sh <<'EOF'
#!/bin/bash
echo "=== Spread Status ==="
ps aux | grep spread | grep -v grep

echo ""
echo "=== RabbitMQ Status ==="
docker ps | grep rabbitmq

echo ""
echo "=== Worker Status ==="
ps aux | grep worker.jar | grep -v grep

echo ""
echo "=== GlusterFS Status ==="
df -h | grep emailfiles

echo ""
echo "=== Spread Group Members ==="
grep "Member joined" worker.log | tail -5
EOF

chmod +x check-status.sh

# Run on each node
for i in 1 2 3; do
  echo "=== Node $i ==="
  gcloud compute ssh tpa2-node${i} --zone=us-central1-a < check-status.sh
  echo ""
done
```

---

## Performance Testing

### Load Test Script

```bash
# Create load test script for UserApp
cat > load-test.sh <<'EOF'
#!/bin/bash
# Send 100 search requests
for i in {1..100}; do
  echo "search body meeting"
  sleep 0.5
done | java -jar userapp.jar --rabbitmq-host 10.128.0.2
EOF

chmod +x load-test.sh
./load-test.sh
```

**Expected Results:**
- All requests distributed across workers
- Approximately 33 requests per worker
- All requests complete successfully
- Stats show correct totals

---

## Cleanup / Shutdown

### Stop Services

```bash
# On each node
for i in 1 2 3; do
  gcloud compute ssh tpa2-node${i} --zone=us-central1-a << 'ENDSSH'
    # Stop worker
    pkill -f worker.jar
    
    # Stop Spread (if running as root)
    sudo pkill spread
    
    # Unmount GlusterFS
    sudo umount /var/sharedfiles/emails
    
    exit
ENDSSH
done

# On Node 1 - Stop RabbitMQ
gcloud compute ssh tpa2-node1 --zone=us-central1-a
docker-compose down
exit

# Stop GlusterFS volume (Node 1 only)
gcloud compute ssh tpa2-node1 --zone=us-central1-a
sudo gluster volume stop emailfiles
exit
```

### Delete VMs (Optional)

```bash
# Delete all VMs
gcloud compute instances delete tpa2-node1 tpa2-node2 tpa2-node3 \
  --zone=us-central1-a --quiet

# Delete firewall rules
gcloud compute firewall-rules delete tpa2-rabbitmq tpa2-spread tpa2-gluster tpa2-ssh --quiet
```

---

## Appendix: Command Reference

### Quick Start Commands

```bash
# Build locally
mvn clean package -DskipTests

# Create VMs
for i in 1 2 3; do
  gcloud compute instances create tpa2-node${i} --zone=us-central1-a \
    --machine-type=e2-medium --image-family=ubuntu-2404-lts-amd64 \
    --image-project=ubuntu-os-cloud --tags=tpa2-node
done

# Setup all nodes
for i in 1 2 3; do
  gcloud compute scp deploy/scripts/*.sh tpa2-node${i}:~ --zone=us-central1-a
  gcloud compute ssh tpa2-node${i} --zone=us-central1-a --command="sudo ./setup-gcp-vm.sh && sudo ./install-spread.sh"
done

# Start RabbitMQ (Node 1)
gcloud compute scp deploy/docker-compose.yml tpa2-node1:~ --zone=us-central1-a
gcloud compute ssh tpa2-node1 --zone=us-central1-a --command="docker-compose up -d rabbitmq"

# Deploy workers
for i in 1 2 3; do
  gcloud compute scp worker/target/worker.jar tpa2-node${i}:~ --zone=us-central1-a
done

# Start workers (adjust IPs!)
gcloud compute ssh tpa2-node1 --zone=us-central1-a --command="nohup java -jar worker.jar --worker-id worker-001 --rabbitmq-host 10.128.0.2 --spread-host 4803@10.128.0.2 --spread-group tpa2_workers &"
```

### Useful Commands

```bash
# List VMs
gcloud compute instances list

# SSH to VM
gcloud compute ssh tpa2-node1 --zone=us-central1-a

# Copy file to VM
gcloud compute scp local-file.txt tpa2-node1:~/remote-file.txt --zone=us-central1-a

# Copy file from VM
gcloud compute scp tpa2-node1:~/remote-file.txt ./local-file.txt --zone=us-central1-a

# Run command on VM
gcloud compute ssh tpa2-node1 --zone=us-central1-a --command="ls -la"

# View serial console output
gcloud compute instances get-serial-port-output tpa2-node1 --zone=us-central1-a
```

---

## Success Criteria Checklist

- [ ] 3 VMs created with Ubuntu 24 LTS
- [ ] Java 21 installed on all nodes
- [ ] Docker installed on all nodes
- [ ] Spread Toolkit installed on all nodes
- [ ] Spread cluster formed (3 nodes)
- [ ] GlusterFS volume created with 3-way replication
- [ ] 20 email files accessible on all nodes
- [ ] RabbitMQ running on Node 1
- [ ] 3 Workers running and connected to RabbitMQ
- [ ] 3 Workers joined Spread group `tpa2_workers`
- [ ] Search requests work correctly
- [ ] Get-file requests work correctly
- [ ] Get-stats triggers leader election via Spread
- [ ] Stats aggregation works correctly
- [ ] Load is distributed across workers
- [ ] New files replicate via GlusterFS
- [ ] System handles worker failures gracefully

---

**Deployment complete! System ready for demonstration.**

**For questions or issues, refer to:**
- `REQUIREMENTS_VERIFICATION.md` - Requirements compliance
- `LOCAL_SPREAD_TESTING.md` - Local testing guide
- Worker logs: `~/worker.log` on each node
- Spread logs: `/var/log/spread.log` on each node
- RabbitMQ UI: `http://<NODE1_IP>:15672`
