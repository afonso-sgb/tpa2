# TPA2 - Distributed Email Search System

A distributed system for searching email files using RabbitMQ for message brokering, GlusterFS for replicated file storage, and Spread toolkit for group communication and consensus.

## Project Structure

```
TPA2/
├── common/                 # Shared models and utilities
│   └── src/main/java/
│       └── pt/isel/cd/common/
│           ├── model/     # Message POJOs
│           ├── util/      # JSON utilities
│           └── config/    # Configuration constants
├── userapp/               # Client CLI application
│   └── src/main/java/
│       └── pt/isel/cd/userapp/
├── worker/                # Worker service
│   └── src/main/java/
│       └── pt/isel/cd/worker/
├── deploy/                # Deployment files
│   ├── Dockerfile.worker
│   ├── Dockerfile.userapp
│   ├── docker-compose.yml
│   └── scripts/
│       ├── provision-base.sh
│       ├── setup-gluster.sh
│       ├── deploy.sh
│       └── run-tests.sh
└── pom.xml               # Parent Maven POM
```

## Architecture

### Components

1. **UserApp** - CLI client that publishes requests to RabbitMQ and consumes responses
2. **Worker** - Service that consumes requests, searches files, and returns responses
3. **RabbitMQ** - Message broker for request/response communication (work queue pattern)
4. **GlusterFS** - Replicated distributed file system (mounted at `/var/sharedfiles`)
5. **Spread** - Group communication toolkit (for consensus/election - to be fully implemented)

### Message Flow

```
UserApp                RabbitMQ                Worker(s)               GlusterFS
   |                      |                       |                        |
   |--Request------------>|                       |                        |
   |   (search/get-file)  |                       |                        |
   |                      |<--Consume-------------|                        |
   |                      |                       |--Read Files----------->|
   |                      |                       |<-File Content----------|
   |                      |<--Response------------|                        |
   |<--Response-----------|                       |                        |
```

### Request Types

1. **SEARCH** - Search for files containing all specified substrings
2. **GET_FILE** - Retrieve the content of a specific file
3. **GET_STATS** - Get aggregated statistics (total, successful, failed requests)

## Prerequisites

- Java 21
- Maven 3.8+
- Docker (for local testing)
- GCP account with 3 Ubuntu 24 LTS VMs (for production deployment)

## Building the Project

```bash
# Build all modules
mvn clean package

# Skip tests
mvn clean package -DskipTests
```

This generates:
- `userapp/target/userapp.jar` - Standalone CLI application
- `worker/target/worker.jar` - Standalone worker service

## Local Development with Docker Compose

### 1. Build the project

```bash
mvn clean package -DskipTests
```

### 2. Start services

```bash
cd deploy
docker-compose up -d
```

This starts:
- RabbitMQ (ports 5672, 15672)
- 3 Worker instances
- Shared volume for files

### 3. Access RabbitMQ Management UI

Open http://localhost:15672 (guest/guest)

### 4. Populate test files

```bash
# Create some test email files in the shared volume
docker exec tpa2-worker1 sh -c 'echo "Meeting scheduled for tomorrow at 10am" > /var/sharedfiles/email001.txt'
docker exec tpa2-worker1 sh -c 'echo "Project deadline is next week" > /var/sharedfiles/email002.txt'
docker exec tpa2-worker1 sh -c 'echo "Meeting rescheduled to afternoon" > /var/sharedfiles/email003.txt'
```

### 5. Run client commands

```bash
# Search for files
java -jar userapp/target/userapp.jar search meeting

# Search with multiple substrings (case-insensitive)
java -jar userapp/target/userapp.jar search meeting tomorrow

# Test email017.txt (Anexo 1) - should find with these substrings:
java -jar userapp/target/userapp.jar search "gRPC em Java 21" "GCP" "Docker"

# Get file content
java -jar userapp/target/userapp.jar get-file email017.txt

# Get statistics
java -jar userapp/target/userapp.jar get-stats
```

### 6. Stop services

```bash
docker-compose down
```

## GCP Production Deployment

### 1. Provision VMs

Create 3 Ubuntu 24 LTS VMs named `tpa2-node1`, `tpa2-node2`, `tpa2-node3`.

### 2. Run base provisioning on all nodes

```bash
# Copy and run on each VM
gcloud compute scp deploy/scripts/provision-base.sh tpa2-node1:~
gcloud compute ssh tpa2-node1 -- 'bash ~/provision-base.sh'

# Repeat for node2 and node3
```

### 3. Set up GlusterFS

```bash
# Run on node1 only
gcloud compute scp deploy/scripts/setup-gluster.sh tpa2-node1:~
gcloud compute ssh tpa2-node1 -- 'bash ~/setup-gluster.sh'
```

### 4. Deploy application

```bash
# From your local machine
cd deploy/scripts
./deploy.sh
```

This will:
- Build the project
- Deploy RabbitMQ on node1
- Deploy worker services to all nodes
- Start workers as systemd services

### 5. Populate email files

```bash
# SSH to node1 and add test files to /var/sharedfiles
gcloud compute ssh tpa2-node1
sudo cp /path/to/EmailFiles/*.txt /var/sharedfiles/
```

### 6. Run client from local machine

```bash
# Get node1 external IP
NODE1_IP=$(gcloud compute instances describe tpa2-node1 --format='get(networkInterfaces[0].accessConfigs[0].natIP)')

# Run userapp
export RABBITMQ_HOST=$NODE1_IP
java -jar userapp/target/userapp.jar search meeting schedule
```

## Configuration

### Environment Variables

**UserApp:**
- `RABBITMQ_HOST` - RabbitMQ host (default: localhost)
- `RABBITMQ_PORT` - RabbitMQ port (default: 5672)

**Worker:**
- `WORKER_ID` - Unique worker identifier
- `RABBITMQ_HOST` - RabbitMQ host (default: localhost)
- `RABBITMQ_PORT` - RabbitMQ port (default: 5672)
- `SHARED_FILES_DIR` - Path to shared files (default: /var/sharedfiles)

## Testing

### Integration Tests

```bash
cd deploy/scripts
./run-tests.sh
```

### Manual Testing

```bash
# Start local environment
cd deploy
docker-compose up -d

# Wait for services to be healthy
sleep 10

# Run searches
java -jar ../userapp/target/userapp.jar search test
java -jar ../userapp/target/userapp.jar get-stats
```

## Message Formats (JSON)

### Request Message

```json
{
  "requestId": "uuid",
  "type": "SEARCH",
  "clientQueue": "client-uuid-resp",
  "payload": {
    "substrings": ["meeting", "schedule"]
  }
}
```

### Response Message

```json
{
  "requestId": "uuid",
  "status": "OK",
  "type": "SEARCH_RESULT",
  "payload": {
    "filenames": ["/var/sharedfiles/email001.txt"]
  }
}
```

## Design Decisions

### RabbitMQ Work Queue Pattern

- Workers compete for messages from `requests` queue
- Fair dispatch with `basicQos(1)` ensures even distribution
- Client-specific response queues with TTL for ephemeral connections

### File Search Strategy

- Stream-based file walking for efficiency
- Content-based substring matching (case-sensitive)
- Returns absolute paths for unambiguous file identification

### Statistics Collection

- Each worker maintains local counters (atomic operations)
- In full implementation, Spread-based consensus would aggregate stats
- Current version returns local worker stats only

## Known Limitations

1. **Spread Integration** - Consensus/election algorithm not fully implemented
   - Would use Spread multicast for worker coordination
   - Would implement leader election for statistics aggregation
   - Current version returns local stats only

2. **File Caching** - No file list caching implemented
   - Every search walks entire directory tree
   - Could optimize with timestamp-based caching

3. **Error Handling** - Basic error handling
   - Could add retry logic for transient failures
   - Could implement circuit breakers for fault tolerance

## Future Enhancements

1. Implement full Spread-based consensus for GET_STATS
2. Add file list caching with modification time tracking
3. Implement worker health monitoring
4. Add metrics and monitoring (Prometheus/Grafana)
5. Support for regex-based searches
6. Distributed tracing with correlation IDs

## Troubleshooting

### Workers not receiving messages

Check RabbitMQ queue: http://localhost:15672

```bash
# Check worker logs
docker logs tpa2-worker1
```

### GlusterFS mount issues

```bash
# Check GlusterFS status on node1
sudo gluster volume status
sudo gluster peer status

# Remount if needed
sudo umount /var/sharedfiles
sudo mount -t glusterfs tpa2-node1:/glustervol /var/sharedfiles
```

### Connection refused errors

Ensure RabbitMQ is running and accessible:

```bash
# Check RabbitMQ
docker ps | grep rabbitmq
netstat -an | grep 5672
```

## References

- [RabbitMQ Work Queues Tutorial](https://www.rabbitmq.com/tutorials/tutorial-two-java.html)
- [GlusterFS Quick Start Guide](https://docs.gluster.org/en/latest/Quick-Start-Guide/Quickstart/)
- [Spread Toolkit Documentation](http://www.spread.org/docs.html)

## License

This project is for educational purposes (ISEL CD course - TPA2).

## Authors

TPA2 Development Team
