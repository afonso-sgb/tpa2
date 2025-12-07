# TPA2 Quick Start Guide

Get the distributed email search system running in under 5 minutes!

## Prerequisites

- Java 21
- Maven 3.8+
- Docker & Docker Compose

## Quick Start (Local Development)

### 1. Clone and Build

```bash
cd c:\Users\asben\Code\TPA2
mvn clean package -DskipTests
```

### 2. Create Sample Email Files

```bash
bash create-sample-emails.sh EmailFiles
```

### 3. Start Docker Services

```bash
cd deploy
docker-compose up -d
```

Wait about 10 seconds for services to start.

### 4. Copy Sample Files to Shared Volume

```bash
docker exec tpa2-worker1 mkdir -p /var/sharedfiles
docker cp ../EmailFiles/email001.txt tpa2-worker1:/var/sharedfiles/
docker cp ../EmailFiles/email002.txt tpa2-worker1:/var/sharedfiles/
docker cp ../EmailFiles/email003.txt tpa2-worker1:/var/sharedfiles/
docker cp ../EmailFiles/email004.txt tpa2-worker1:/var/sharedfiles/
docker cp ../EmailFiles/email005.txt tpa2-worker1:/var/sharedfiles/
```

### 5. Run Searches

```bash
# Go back to project root
cd ..

# Search for emails about meetings
java -jar userapp/target/userapp.jar search meeting

# Search for multiple terms
java -jar userapp/target/userapp.jar search meeting tomorrow

# Get file content
java -jar userapp/target/userapp.jar get-file email001.txt

# Get statistics
java -jar userapp/target/userapp.jar get-stats
```

### 6. Monitor

**RabbitMQ Management UI:** http://localhost:15672 (guest/guest)

**Worker Logs:**
```bash
docker logs tpa2-worker1
docker logs tpa2-worker2
docker logs tpa2-worker3
```

### 7. Stop Services

```bash
cd deploy
docker-compose down
```

## Troubleshooting

### Services not starting

```bash
# Check service status
docker-compose ps

# View logs
docker-compose logs
```

### No search results

```bash
# Verify files are in shared volume
docker exec tpa2-worker1 ls -la /var/sharedfiles

# Check worker logs
docker logs tpa2-worker1
```

### Connection refused

```bash
# Ensure RabbitMQ is running
docker ps | grep rabbitmq

# Check if port 5672 is available
netstat -an | grep 5672
```

## What's Happening?

1. **RabbitMQ** receives search requests from UserApp
2. **Workers** (3 instances) compete for requests using work queue pattern
3. Each worker searches files in `/var/sharedfiles`
4. Results are sent back to UserApp via client-specific response queue
5. UserApp displays results

## Next Steps

- Read the full [README.md](README.md) for detailed documentation
- Review [structure.md](structure.md) for architecture details
- Explore GCP deployment with GlusterFS replication
- Implement Spread-based consensus for distributed statistics

## Common Commands

```bash
# Rebuild after code changes
mvn clean package -DskipTests

# Restart workers only
cd deploy
docker-compose restart worker1 worker2 worker3

# View real-time logs
docker-compose logs -f

# Clean up everything
docker-compose down -v
```

Enjoy exploring the TPA2 distributed system! 🚀
