# TPA2 - Technical Documentation Part 6: Deployment and Testing

**Course:** Computação Distribuída (Winter 2025-2026)  
**Date:** December 2025

---

## Table of Contents

1. [System Requirements](#system-requirements)
2. [Local Docker Deployment](#local-docker-deployment)
3. [GlusterFS Setup](#glusterfs-setup)
4. [Google Cloud Platform Deployment](#google-cloud-platform-deployment)
5. [Testing Procedures](#testing-procedures)
6. [Troubleshooting](#troubleshooting)
7. [Performance Tuning](#performance-tuning)

---

## 1. System Requirements

### 1.1 Software Dependencies

| Component | Version | Purpose |
|-----------|---------|---------|
| **Java** | 21 | Application runtime |
| **Maven** | 3.9+ | Build tool |
| **Docker** | 28.3.3+ | Container platform |
| **Docker Compose** | 2.24+ | Multi-container orchestration |
| **RabbitMQ** | 3-management | Message broker |
| **GlusterFS** | 11+ | Distributed file system |

### 1.2 Hardware Requirements

**Development (local):**
- CPU: 4 cores minimum
- RAM: 8 GB minimum
- Disk: 20 GB free space

**Production (GCP):**
- 3 VM instances minimum
- CPU: 2 vCPUs per VM
- RAM: 4 GB per VM
- Disk: 20 GB per VM

---

## 2. Local Docker Deployment

### 2.1 Build Process

#### **Step 1: Build Maven Modules**

```powershell
# Navigate to project root
cd C:\Users\asben\Code\TPA2

# Build all modules (skipping tests for speed)
mvn clean package -DskipTests

# Expected output:
# [INFO] BUILD SUCCESS
# [INFO] Total time: ~30s
```

**Artifacts created:**
- `userapp/target/userapp-1.0-shaded.jar`
- `worker/target/worker-1.0-shaded.jar`

#### **Step 2: Verify JARs**

```powershell
# Check UserApp
java -jar userapp\target\userapp-1.0-shaded.jar help

# Check Worker
java -jar worker\target\worker-1.0-shaded.jar --help
# (Should show usage or start worker)
```

### 2.2 Docker Compose Configuration

**File:** `deploy/docker-compose.yml`

```yaml
version: '3.8'

services:
  rabbitmq:
    image: rabbitmq:3-management
    container_name: tpa2-rabbitmq
    ports:
      - "5672:5672"   # AMQP
      - "15672:15672" # Management UI
    environment:
      - RABBITMQ_DEFAULT_USER=guest
      - RABBITMQ_DEFAULT_PASS=guest
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - tpa2-network

  worker1:
    build:
      context: ..
      dockerfile: deploy/Dockerfile.worker
    container_name: tpa2-worker1
    environment:
      - WORKER_ID=worker-1
      - RABBIT_HOST=rabbitmq
      - RABBIT_PORT=5672
      - FILE_DIR=/mnt/glusterfs
    volumes:
      - glusterfs-volume:/mnt/glusterfs
    depends_on:
      rabbitmq:
        condition: service_healthy
    networks:
      - tpa2-network
    restart: unless-stopped

  worker2:
    build:
      context: ..
      dockerfile: deploy/Dockerfile.worker
    container_name: tpa2-worker2
    environment:
      - WORKER_ID=worker-2
      - RABBIT_HOST=rabbitmq
      - RABBIT_PORT=5672
      - FILE_DIR=/mnt/glusterfs
    volumes:
      - glusterfs-volume:/mnt/glusterfs
    depends_on:
      rabbitmq:
        condition: service_healthy
    networks:
      - tpa2-network
    restart: unless-stopped

  worker3:
    build:
      context: ..
      dockerfile: deploy/Dockerfile.worker
    container_name: tpa2-worker3
    environment:
      - WORKER_ID=worker-3
      - RABBIT_HOST=rabbitmq
      - RABBIT_PORT=5672
      - FILE_DIR=/mnt/glusterfs
    volumes:
      - glusterfs-volume:/mnt/glusterfs
    depends_on:
      rabbitmq:
        condition: service_healthy
    networks:
      - tpa2-network
    restart: unless-stopped

networks:
  tpa2-network:
    driver: bridge

volumes:
  glusterfs-volume:
    driver: local
```

### 2.3 Dockerfiles

#### **Dockerfile.worker**

```dockerfile
FROM eclipse-temurin:21-jre-alpine

# Install required packages
RUN apk add --no-cache bash

# Create working directory
WORKDIR /app

# Copy JAR file
COPY worker/target/worker-1.0-shaded.jar /app/worker.jar

# Environment variables (overridden by docker-compose)
ENV WORKER_ID=worker-default
ENV RABBIT_HOST=localhost
ENV RABBIT_PORT=5672
ENV FILE_DIR=/mnt/glusterfs

# Expose nothing (connects to RabbitMQ)

# Run worker
CMD ["java", "-jar", "worker.jar"]
```

#### **Dockerfile.userapp**

```dockerfile
FROM eclipse-temurin:21-jre-alpine

# Install bash
RUN apk add --no-cache bash

# Create working directory
WORKDIR /app

# Copy JAR
COPY userapp/target/userapp-1.0-shaded.jar /app/userapp.jar

# Environment
ENV RABBIT_HOST=rabbitmq
ENV RABBIT_PORT=5672

# Entry point
ENTRYPOINT ["java", "-jar", "userapp.jar"]
```

### 2.4 Starting the System

```powershell
# Navigate to deployment directory
cd deploy

# Start all services
docker compose up -d

# Verify containers
docker compose ps

# Expected output:
# NAME              STATUS
# tpa2-rabbitmq     Up 10 seconds (healthy)
# tpa2-worker1      Up 5 seconds
# tpa2-worker2      Up 5 seconds
# tpa2-worker3      Up 5 seconds
```

### 2.5 Populating Files

```powershell
# Copy email files to GlusterFS volume
docker compose exec worker1 bash -c "mkdir -p /mnt/glusterfs/emails"
docker compose cp ../EmailFiles/email017.txt worker1:/mnt/glusterfs/emails/

# Verify
docker compose exec worker1 ls -lh /mnt/glusterfs/emails/
```

**Alternative:** Use script

```powershell
# Run sample email creation script
bash create-sample-emails.sh
```

### 2.6 Testing Local Deployment

```powershell
# Search for keyword
java -jar ..\userapp\target\userapp-1.0-shaded.jar search "Docker" rabbitmq 5672

# Get file
java -jar ..\userapp\target\userapp-1.0-shaded.jar get-file email017.txt rabbitmq 5672

# Get statistics
java -jar ..\userapp\target\userapp-1.0-shaded.jar get-stats rabbitmq 5672
```

---

## 3. GlusterFS Setup

### 3.1 Why GlusterFS?

**Benefits:**
- **Replication:** Files replicated across all nodes
- **Fault tolerance:** System survives node failures
- **Scalability:** Add nodes dynamically
- **POSIX compliance:** Works like regular filesystem

### 3.2 GlusterFS Architecture

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│   VM-1       │     │   VM-2       │     │   VM-3       │
│              │     │              │     │              │
│ GlusterFS    │◄───►│ GlusterFS    │◄───►│ GlusterFS    │
│ Brick        │     │ Brick        │     │ Brick        │
│ /mnt/brick1  │     │ /mnt/brick2  │     │ /mnt/brick3  │
└──────┬───────┘     └──────┬───────┘     └──────┬───────┘
       │                    │                    │
       └────────────────────┼────────────────────┘
                            │
                    ┌───────▼───────┐
                    │ Gluster Volume│
                    │ (replicated)  │
                    └───────────────┘
                            │
                    Mounted at: /mnt/glusterfs
```

### 3.3 Installation (Ubuntu 24.04)

**On each VM:**

```bash
# Add GlusterFS repository
sudo add-apt-repository ppa:gluster/glusterfs-11
sudo apt update

# Install GlusterFS server
sudo apt install -y glusterfs-server

# Start service
sudo systemctl start glusterd
sudo systemctl enable glusterd

# Verify
sudo systemctl status glusterd
```

### 3.4 Creating GlusterFS Volume

**On VM-1 (primary node):**

```bash
# Peer with other nodes
sudo gluster peer probe vm-2
sudo gluster peer probe vm-3

# Verify peers
sudo gluster peer status

# Create brick directories
sudo mkdir -p /mnt/brick1/gv0

# Create replicated volume
sudo gluster volume create gv0 replica 3 \
  vm-1:/mnt/brick1/gv0 \
  vm-2:/mnt/brick1/gv0 \
  vm-3:/mnt/brick1/gv0 \
  force

# Start volume
sudo gluster volume start gv0

# Verify
sudo gluster volume info gv0
```

**Expected output:**
```
Volume Name: gv0
Type: Replicate
Volume ID: <uuid>
Status: Started
Number of Bricks: 1 x 3 = 3
Transport-type: tcp
Bricks:
Brick1: vm-1:/mnt/brick1/gv0
Brick2: vm-2:/mnt/brick1/gv0
Brick3: vm-3:/mnt/brick1/gv0
```

### 3.5 Mounting GlusterFS

**On each VM:**

```bash
# Create mount point
sudo mkdir -p /mnt/glusterfs

# Mount volume
sudo mount -t glusterfs localhost:/gv0 /mnt/glusterfs

# Verify
df -h | grep glusterfs

# Make persistent (add to /etc/fstab)
echo "localhost:/gv0 /mnt/glusterfs glusterfs defaults,_netdev 0 0" | sudo tee -a /etc/fstab
```

### 3.6 Testing Replication

```bash
# On VM-1: Create test file
echo "Hello from VM-1" | sudo tee /mnt/glusterfs/test.txt

# On VM-2: Verify file exists
cat /mnt/glusterfs/test.txt
# Output: Hello from VM-1

# On VM-3: Also verify
cat /mnt/glusterfs/test.txt
# Output: Hello from VM-1
```

---

## 4. Google Cloud Platform Deployment

### 4.1 VM Creation

```bash
# Create 3 VMs with static IPs
gcloud compute instances create tpa2-node1 \
  --zone=europe-west1-b \
  --machine-type=e2-medium \
  --image-family=ubuntu-2404-lts-amd64 \
  --image-project=ubuntu-os-cloud \
  --boot-disk-size=20GB \
  --tags=tpa2-cluster

gcloud compute instances create tpa2-node2 \
  --zone=europe-west1-b \
  --machine-type=e2-medium \
  --image-family=ubuntu-2404-lts-amd64 \
  --image-project=ubuntu-os-cloud \
  --boot-disk-size=20GB \
  --tags=tpa2-cluster

gcloud compute instances create tpa2-node3 \
  --zone=europe-west1-b \
  --machine-type=e2-medium \
  --image-family=ubuntu-2404-lts-amd64 \
  --image-project=ubuntu-os-cloud \
  --boot-disk-size=20GB \
  --tags=tpa2-cluster
```

### 4.2 Firewall Rules

```bash
# Allow RabbitMQ (5672, 15672)
gcloud compute firewall-rules create tpa2-rabbitmq \
  --allow tcp:5672,tcp:15672 \
  --target-tags=tpa2-cluster \
  --source-ranges=0.0.0.0/0

# Allow GlusterFS (24007-24008, 49152-49251)
gcloud compute firewall-rules create tpa2-glusterfs \
  --allow tcp:24007-24008,tcp:49152-49251 \
  --target-tags=tpa2-cluster \
  --source-ranges=<internal-network-range>

# Allow SSH (already exists usually)
# gcloud compute firewall-rules create tpa2-ssh --allow tcp:22
```

### 4.3 Installing Docker on GCP VMs

**On each VM:**

```bash
# SSH into VM
gcloud compute ssh tpa2-node1

# Install Docker
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh

# Add user to docker group
sudo usermod -aG docker $USER

# Log out and back in
exit
gcloud compute ssh tpa2-node1

# Verify
docker --version
```

### 4.4 Installing Docker Compose

```bash
# On each VM
sudo curl -L "https://github.com/docker/compose/releases/download/v2.24.0/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose

# Verify
docker-compose --version
```

### 4.5 Deploying Application

**On each VM:**

```bash
# Clone repository (or copy files)
git clone <repository-url> ~/tpa2
cd ~/tpa2/deploy

# Build and start
docker compose up -d

# Verify
docker compose ps
docker compose logs -f
```

### 4.6 Load Balancing (Optional)

```bash
# Create load balancer for UserApp access
gcloud compute backend-services create tpa2-backend \
  --protocol=TCP \
  --port-name=rabbitmq \
  --global

# Add VMs to backend
gcloud compute backend-services add-backend tpa2-backend \
  --instance-group=tpa2-cluster \
  --global
```

---

## 5. Testing Procedures

### 5.1 Basic Functionality Tests

#### **Test 1: File Search**

```powershell
# Search for keyword in all files
java -jar userapp-1.0-shaded.jar search "Docker" localhost 5672

# Expected output:
# Searching for keyword: Docker
# Files found:
# - email017.txt
```

#### **Test 2: File Retrieval**

```powershell
# Get specific file
java -jar userapp-1.0-shaded.jar get-file email017.txt localhost 5672

# Expected: File contents displayed
```

#### **Test 3: Statistics**

```powershell
# Get system statistics
java -jar userapp-1.0-shaded.jar get-stats localhost 5672

# Expected output:
# Statistics:
#   Total Requests: 3
#   Successful: 3
#   Failed: 0
#   Active Workers: 3
```

### 5.2 Distributed Statistics Test

**Scenario:** Verify all workers contribute to statistics

```powershell
# Generate load on all workers
for ($i=1; $i -le 10; $i++) {
    java -jar userapp-1.0-shaded.jar search "test$i" localhost 5672
    Start-Sleep -Milliseconds 100
}

# Request stats
java -jar userapp-1.0-shaded.jar get-stats localhost 5672

# Verify: Total requests should be ~10
# Check logs to see which worker was coordinator
docker logs tpa2-worker1 | Select-String "is the coordinator"
docker logs tpa2-worker2 | Select-String "is the coordinator"
docker logs tpa2-worker3 | Select-String "is the coordinator"
```

### 5.3 Leader Election Test

**Scenario:** Verify correct coordinator selection based on uptime

```powershell
# Start workers sequentially with delays
docker compose up -d worker1
Start-Sleep -Seconds 30

docker compose up -d worker2
Start-Sleep -Seconds 30

docker compose up -d worker3
Start-Sleep -Seconds 10

# Request stats (worker-1 should be coordinator - highest uptime)
java -jar userapp-1.0-shaded.jar get-stats localhost 5672

# Verify in logs
docker logs tpa2-worker1 | Select-String "is the coordinator"
# Should show: "Worker [worker-1] is the coordinator"
```

### 5.4 Fault Tolerance Test

**Scenario:** System continues working with failed nodes

```powershell
# Stop one worker
docker compose stop worker2

# Verify remaining workers handle requests
java -jar userapp-1.0-shaded.jar search "Docker" localhost 5672
# Should still work (worker1 or worker3 processes)

# Request stats
java -jar userapp-1.0-shaded.jar get-stats localhost 5672
# Should show 2 workers instead of 3

# Restart worker
docker compose start worker2

# Verify it rejoins
java -jar userapp-1.0-shaded.jar get-stats localhost 5672
# Should show 3 workers again
```

### 5.5 Concurrent Requests Test

**Scenario:** Multiple clients making simultaneous requests

```powershell
# Run 5 concurrent searches
$jobs = 1..5 | ForEach-Object {
    Start-Job -ScriptBlock {
        param($keyword)
        java -jar userapp-1.0-shaded.jar search $keyword localhost 5672
    } -ArgumentList "concurrent$_"
}

# Wait for all to complete
$jobs | Wait-Job | Receive-Job

# Check stats
java -jar userapp-1.0-shaded.jar get-stats localhost 5672
# Total requests should be >= 5
```

### 5.6 GlusterFS Replication Test

**On GCP VMs:**

```bash
# VM-1: Create file
echo "Test data" | sudo tee /mnt/glusterfs/testfile.txt

# VM-2: Verify replication
cat /mnt/glusterfs/testfile.txt
# Should show: Test data

# VM-3: Also verify
cat /mnt/glusterfs/testfile.txt
# Should show: Test data

# VM-1: Stop GlusterFS
sudo systemctl stop glusterd

# VM-2: Still accessible
cat /mnt/glusterfs/testfile.txt
# Should still work (replica on VM-2 and VM-3)
```

---

## 6. Troubleshooting

### 6.1 RabbitMQ Connection Errors

**Error:** `Connection refused`

**Solution:**
```powershell
# Check RabbitMQ status
docker compose logs rabbitmq

# Restart if needed
docker compose restart rabbitmq

# Verify port binding
docker compose ps
# Should show: 0.0.0.0:5672->5672/tcp
```

### 6.2 Workers Not Processing Requests

**Error:** Requests timeout

**Symptoms:**
```
Request timeout after 10000ms
```

**Debug:**
```powershell
# Check worker logs
docker compose logs worker1 | Select-String "ERROR"

# Verify RabbitMQ queue
# Open http://localhost:15672
# Navigate to Queues → request_queue
# Check: Ready messages, Consumers
```

**Common causes:**
1. Worker crashed → `docker compose restart worker1`
2. No consumers → Check worker logs for startup errors
3. Queue misconfigured → Restart all workers

### 6.3 Election Never Completes

**Error:** Stats request hangs forever

**Debug:**
```powershell
# Check election messages
docker logs tpa2-worker1 | Select-String "Election"

# Look for:
# - "initiating election" ✓
# - "received election" ✓
# - "voted for" ✓
# - "Election ... complete" ✓
# - "Multicasted coordinator announcement" ✓
# - "is the coordinator" ✓
```

**Common causes:**
1. Workers on different Spread groups → Check `SPREAD_GROUP` env var
2. Network partition → Verify `docker network inspect tpa2_tpa2-network`
3. Timeout too short → Increase `ELECTION_TIMEOUT_MS`

### 6.4 Stats Collection Timeout

**Error:** `Stats collection timeout. Collected 1/3 responses`

**Debug:**
```powershell
# Check coordinator logs
docker logs tpa2-worker<coordinator-id> | Select-String -Context 5,5 "timeout"

# Verify other workers received request
docker logs tpa2-worker1 | Select-String "STATS_REQUEST"
docker logs tpa2-worker2 | Select-String "STATS_REQUEST"
docker logs tpa2-worker3 | Select-String "STATS_REQUEST"
```

**Common causes:**
1. Worker offline → `docker compose ps`
2. Timeout too short → Increase `STATS_COLLECTION_TIMEOUT_MS`
3. Thread blocking (fixed) → Verify using latest code

### 6.5 GlusterFS Mount Fails

**Error:** `mount: wrong fs type, bad option`

**Debug:**
```bash
# Check GlusterFS service
sudo systemctl status glusterd

# Verify volume
sudo gluster volume info gv0

# Check network connectivity
sudo gluster peer status

# Test mount manually
sudo mount -t glusterfs -o log-level=DEBUG localhost:/gv0 /mnt/glusterfs
```

**Common causes:**
1. glusterfs-client not installed → `sudo apt install glusterfs-client`
2. Volume not started → `sudo gluster volume start gv0`
3. Firewall blocking → Check ports 24007-24008, 49152-49251

### 6.6 Performance Issues

**Symptom:** Slow responses, high CPU

**Monitor:**
```powershell
# Check container stats
docker stats

# Look for:
# - High CPU% → May need more resources
# - High MEM% → Possible memory leak
# - High NET I/O → Network bottleneck
```

**Optimize:**
1. Increase worker count
2. Add QoS prefetch limit
3. Use connection pooling
4. Enable RabbitMQ lazy queues

---

## 7. Performance Tuning

### 7.1 RabbitMQ Configuration

**Enable lazy queues (for high throughput):**

```java
Map<String, Object> args = new HashMap<>();
args.put("x-queue-mode", "lazy");
channel.queueDeclare("request_queue", true, false, false, args);
```

### 7.2 Worker QoS

**Adjust prefetch count:**

```java
// Process 5 messages concurrently per worker
channel.basicQos(5);  // Default: 1
```

**Trade-off:**
- Higher value → Better throughput
- Lower value → Fairer distribution

### 7.3 JVM Tuning

**Set heap size:**

```dockerfile
CMD ["java", "-Xms512m", "-Xmx1g", "-jar", "worker.jar"]
```

**Enable G1GC (better for low-latency):**

```dockerfile
CMD ["java", "-XX:+UseG1GC", "-jar", "worker.jar"]
```

### 7.4 Docker Resource Limits

**Limit container resources:**

```yaml
services:
  worker1:
    deploy:
      resources:
        limits:
          cpus: '1.0'
          memory: 1G
        reservations:
          cpus: '0.5'
          memory: 512M
```

---

## 8. Monitoring

### 8.1 RabbitMQ Management UI

**Access:** `http://localhost:15672`

**Credentials:** guest/guest

**Monitor:**
- Queue depths
- Message rates
- Consumer counts
- Connection status

### 8.2 Application Logs

```powershell
# Follow all logs
docker compose logs -f

# Specific worker
docker compose logs -f worker1

# Filter errors
docker compose logs | Select-String "ERROR"

# Export logs
docker compose logs > logs.txt
```

### 8.3 Health Checks

**Add to docker-compose.yml:**

```yaml
services:
  worker1:
    healthcheck:
      test: ["CMD", "pgrep", "-f", "worker.jar"]
      interval: 30s
      timeout: 10s
      retries: 3
```

---

## 9. Cleanup

### 9.1 Stop System

```powershell
# Stop all containers
docker compose down

# Stop and remove volumes
docker compose down -v

# Remove images
docker compose down --rmi all
```

### 9.2 GCP Cleanup

```bash
# Delete VMs
gcloud compute instances delete tpa2-node1 tpa2-node2 tpa2-node3 --zone=europe-west1-b

# Delete firewall rules
gcloud compute firewall-rules delete tpa2-rabbitmq tpa2-glusterfs

# Delete load balancer
gcloud compute backend-services delete tpa2-backend --global
```

---

## 10. Delivery Checklist

- [x] Source code in Git repository
- [x] Maven build succeeds
- [x] Docker images build successfully
- [x] Local deployment works (3 workers)
- [ ] GCP deployment tested
- [ ] GlusterFS replication verified
- [x] All tests pass (search, get-file, get-stats)
- [x] Distributed statistics working
- [x] Leader election functioning correctly
- [ ] Technical report PDF created
- [ ] Documentation complete

---

**Document Version:** 1.0  
**Last Updated:** December 9, 2025  
**End of Deployment Documentation**
