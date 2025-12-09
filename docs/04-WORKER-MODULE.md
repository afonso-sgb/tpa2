# TPA2 - Technical Documentation Part 4: Worker Module

**Course:** Computação Distribuída (Winter 2025-2026)  
**Module:** `worker`  
**Date:** December 2025

---

## Table of Contents

1. [Module Overview](#module-overview)
2. [Worker Class Architecture](#worker-class-architecture)
3. [Request Processing](#request-processing)
4. [File Search Algorithm](#file-search-algorithm)
5. [Statistics Tracking](#statistics-tracking)
6. [Integration with Spread](#integration-with-spread)

---

## 1. Module Overview

### 1.1 Purpose

The `worker` module implements **processing nodes** that:
- Consume requests from RabbitMQ work queue
- Search files in distributed GlusterFS storage
- Participate in leader election for statistics
- Track local request statistics
- Communicate with other workers via Spread

### 1.2 Key Responsibilities

1. **Fair Work Distribution:** Process one request at a time (QoS=1)
2. **File Operations:** Read and search files from GlusterFS
3. **Message Acknowledgment:** Confirm successful processing or requeue on failure
4. **Spread Integration:** Join worker group, announce presence
5. **Election Participation:** Vote in elections, collect stats if coordinator
6. **Statistics Tracking:** Count total, successful, and failed requests

---

## 2. Worker Class Architecture

### 2.1 Class Structure

```java
public class Worker {
    private static final Logger logger = LoggerFactory.getLogger(Worker.class);
    
    // Identity and infrastructure
    private final String workerId;
    private final Connection connection;
    private final Channel channel;
    private final Path sharedFilesPath;
    private final long startTime;
    
    // Spread integration
    private final SpreadSimulator spread;
    private final ElectionManager electionManager;
    
    // Statistics counters
    private final AtomicLong totalRequests;
    private final AtomicLong successfulRequests;
    private final AtomicLong failedRequests;
}
```

### 2.2 Instance Variables Explained

#### **workerId**
```java
private final String workerId;
```

**Purpose:** Unique identifier for this worker instance.

**Format:** `worker-<number>` (e.g., `worker-1`, `worker-2`, `worker-3`)

**Used For:**
- Spread group membership
- Election voting and priority comparison
- Logging and debugging
- Statistics aggregation

**Set From:**
```bash
WORKER_ID=worker-1 java -jar worker.jar
```

#### **sharedFilesPath**
```java
private final Path sharedFilesPath;  // /var/sharedfiles
```

**Purpose:** Root directory of GlusterFS mount point.

**Default:** `/var/sharedfiles`

**Contains:**
```
/var/sharedfiles/
├── email001.txt
├── email002.txt
├── ...
└── email017.txt
```

**Why Path (not String)?**
- Type-safe file operations
- Better API (resolve, walk, etc.)
- Standard Java NIO approach

#### **startTime**
```java
private final long startTime = System.currentTimeMillis();
```

**Purpose:** Track worker uptime for election priority.

**Uptime Calculation:**
```java
long uptime = System.currentTimeMillis() - startTime;
```

**Why It Matters:**
- Longer uptime = higher election priority
- Assumption: Older workers have more stable state
- Prevents frequent coordinator changes

#### **Statistics Counters (AtomicLong)**
```java
private final AtomicLong totalRequests = new AtomicLong(0);
private final AtomicLong successfulRequests = new AtomicLong(0);
private final AtomicLong failedRequests = new AtomicLong(0);
```

**Why AtomicLong?**
- Thread-safe increment without locks
- Multiple threads may update simultaneously
- Lock-free performance

**Updates:**
```java
totalRequests.incrementAndGet();         // Every request
successfulRequests.incrementAndGet();    // On OK response
failedRequests.incrementAndGet();        // On ERROR response
```

---

## 3. Request Processing

### 3.1 Initialization

```java
public Worker(String workerId, String rabbitMqHost, int rabbitMqPort, 
              String sharedFilesDir) throws IOException, TimeoutException {
    this.workerId = workerId;
    this.sharedFilesPath = Paths.get(sharedFilesDir);
    this.startTime = System.currentTimeMillis();
    
    // 1. Connect to RabbitMQ
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(rabbitMqHost);
    factory.setPort(rabbitMqPort);
    connection = factory.newConnection();
    channel = connection.createChannel();
    
    // 2. Declare work queue (ensure it exists)
    channel.queueDeclare(QueueConfig.REQUESTS_QUEUE, true, false, false, null);
    
    // 3. Set QoS for fair dispatch
    channel.basicQos(1);  // Process 1 message at a time
    
    // 4. Initialize Spread simulator
    spread = new SpreadSimulator(workerId, "email_workers", rabbitMqHost, rabbitMqPort);
    spread.joinGroup();
    
    // 5. Initialize election manager
    electionManager = new ElectionManager(workerId, spread, channel);
    electionManager.setStatsProvider(this::getPartialStats);
    
    // 6. Announce presence
    announcePresence();
}
```

**Step 3 - QoS=1 Explained:**

Without QoS:
```
RabbitMQ → Worker1 (fast): 100 messages
RabbitMQ → Worker2 (slow): 5 messages
```

With QoS=1:
```
RabbitMQ → Worker1: 1 message (waits for ACK)
RabbitMQ → Worker2: 1 message (waits for ACK)
RabbitMQ → Worker1: 1 message (after Worker1 ACKs)
...fair distribution
```

### 3.2 Starting the Consumer

```java
public void start() throws IOException {
    logger.info("Worker [{}] starting to consume requests...", workerId);
    
    DeliverCallback deliverCallback = (consumerTag, delivery) -> {
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. Parse request
            RequestMessage request = JsonUtil.parseRequest(delivery.getBody());
            logger.info("Worker [{}] processing request: {} (type: {})", 
                        workerId, request.getRequestId(), request.getType());
            
            // 2. Update statistics
            totalRequests.incrementAndGet();
            
            // 3. Process request
            ResponseMessage response = processRequest(request);
            
            // 4. Send response (if any)
            if (response != null) {
                if (request.getClientQueue() != null && 
                    !request.getClientQueue().isEmpty()) {
                    byte[] responseBytes = JsonUtil.toJsonBytes(response);
                    channel.basicPublish("", request.getClientQueue(), 
                                        null, responseBytes);
                }
                
                // Update stats based on response status
                if (response.getStatus() == ResponseStatus.OK) {
                    successfulRequests.incrementAndGet();
                } else {
                    failedRequests.incrementAndGet();
                }
            }
            
            // 5. Acknowledge message
            channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
            
            long elapsed = System.currentTimeMillis() - startTime;
            logger.info("Worker [{}] completed request {} in {} ms", 
                        workerId, request.getRequestId(), elapsed);
            
        } catch (Exception e) {
            logger.error("Worker [{}] error processing request", workerId, e);
            failedRequests.incrementAndGet();
            
            // Reject and requeue the message
            channel.basicNack(delivery.getEnvelope().getDeliveryTag(), 
                             false, true);
        }
    };
    
    // Start consuming with manual acknowledgment
    channel.basicConsume(QueueConfig.REQUESTS_QUEUE, false, 
                        deliverCallback, consumerTag -> {});
}
```

**Key Points:**

1. **Manual Acknowledgment (`false`):**
   - Worker controls when message is removed from queue
   - Only ACK after successful processing
   - Allows retry on failure

2. **Error Handling:**
   - Try-catch around entire processing
   - On exception: NACK + requeue (`true`)
   - Another worker will retry

3. **Timing:**
   - Log processing duration
   - Helps identify slow operations

### 3.3 Request Router

```java
private ResponseMessage processRequest(RequestMessage request) {
    try {
        switch (request.getType()) {
            case SEARCH:
                return handleSearch(request);
            case GET_FILE:
                return handleGetFile(request);
            case GET_STATS:
                return handleGetStats(request);
            default:
                return new ResponseMessage(
                    request.getRequestId(), 
                    ResponseStatus.ERROR, 
                    null, 
                    "Unknown request type"
                );
        }
    } catch (Exception e) {
        logger.error("Error processing request", e);
        return new ResponseMessage(
            request.getRequestId(), 
            ResponseStatus.ERROR, 
            null, 
            e.getMessage()
        );
    }
}
```

**Pattern:** Simple switch-based routing with fallback error handling.

---

## 4. File Search Algorithm

### 4.1 Search Implementation

```java
private ResponseMessage handleSearch(RequestMessage request) {
    SearchPayload payload = (SearchPayload) request.getPayload();
    List<String> substrings = payload.getSubstrings();
    
    logger.info("Worker [{}] searching for: {}", workerId, substrings);
    
    Map<String, String> matchingEmails = new HashMap<>();
    
    try (Stream<Path> paths = Files.walk(sharedFilesPath)) {
        paths
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".txt"))
            .forEach(path -> {
                try {
                    String emailMessage = Files.readString(path);
                    
                    if (containsAllSubstrings(emailMessage, substrings)) {
                        String filename = path.getFileName().toString();
                        matchingEmails.put(filename, emailMessage);
                    }
                } catch (IOException e) {
                    logger.error("Read error in file: {} - {}", 
                                path, e.getMessage());
                }
            });
        
        logger.info("Worker [{}] found {} matching files", 
                   workerId, matchingEmails.size());
        
        SearchResultPayload resultPayload = new SearchResultPayload(matchingEmails);
        return new ResponseMessage(
            request.getRequestId(),
            ResponseStatus.OK,
            ResponseType.SEARCH_RESULT,
            resultPayload
        );
        
    } catch (IOException e) {
        logger.error("Error searching files", e);
        return new ResponseMessage(
            request.getRequestId(),
            ResponseStatus.ERROR,
            ResponseType.SEARCH_RESULT,
            null
        );
    }
}
```

**Algorithm Breakdown:**

1. **Walk Directory Tree:**
   ```java
   Stream<Path> paths = Files.walk(sharedFilesPath)
   ```
   - Recursively finds all files
   - Returns Stream for functional processing

2. **Filter Regular Files:**
   ```java
   .filter(Files::isRegularFile)
   ```
   - Excludes directories, symlinks, etc.
   - Only process actual files

3. **Filter .txt Files:**
   ```java
   .filter(path -> path.toString().endsWith(".txt"))
   ```
   - Only process email files
   - Ignores other file types

4. **Check Each File:**
   ```java
   .forEach(path -> {
       String content = Files.readString(path);
       if (containsAllSubstrings(content, substrings)) {
           matchingEmails.put(filename, content);
       }
   })
   ```
   - Read entire file content
   - Check if ALL substrings present
   - Store filename → content mapping

### 4.2 Substring Matching (Case-Insensitive)

```java
private boolean containsAllSubstrings(String message, List<String> substrings) {
    String lowerMessage = message.toLowerCase();
    
    for (String substr : substrings) {
        if (!lowerMessage.contains(substr.toLowerCase())) {
            return false;  // Missing substring
        }
    }
    
    return true;  // All substrings found
}
```

**Requirements (Anexo 2):**
- ✅ Case-insensitive search
- ✅ ALL substrings must be present
- ✅ Order doesn't matter
- ✅ Partial matches OK ("Docker" matches "docker-compose")

**Examples:**

```java
// Match
containsAllSubstrings("Deploy em containers Docker", ["Docker"])  → true
containsAllSubstrings("DOCKER AND GCP", ["docker", "gcp"])        → true

// No match
containsAllSubstrings("Docker deployment", ["GCP"])               → false
containsAllSubstrings("Testing", ["Docker", "GCP"])               → false
```

---

## 5. File Retrieval

### 5.1 GET_FILE Implementation

```java
private ResponseMessage handleGetFile(RequestMessage request) {
    FilePayload payload = (FilePayload) request.getPayload();
    String filename = payload.getFilename();
    
    logger.info("Worker [{}] retrieving file: {}", workerId, filename);
    
    try {
        Path filePath;
        
        // Handle absolute vs relative paths
        if (Paths.get(filename).isAbsolute()) {
            filePath = Paths.get(filename);
        } else {
            filePath = sharedFilesPath.resolve(filename);
        }
        
        // Check if file exists
        if (!Files.exists(filePath)) {
            logger.warn("Worker [{}] file not found: {}", workerId, filePath);
            return new ResponseMessage(
                request.getRequestId(),
                ResponseStatus.NOT_FOUND,
                ResponseType.FILE_CONTENT,
                null
            );
        }
        
        // Read file content
        String content = Files.readString(filePath);
        FileContentPayload resultPayload = new FileContentPayload(filename, content);
        
        return new ResponseMessage(
            request.getRequestId(),
            ResponseStatus.OK,
            ResponseType.FILE_CONTENT,
            resultPayload
        );
        
    } catch (IOException e) {
        logger.error("Error reading file: {}", filename, e);
        return new ResponseMessage(
            request.getRequestId(),
            ResponseStatus.ERROR,
            ResponseType.FILE_CONTENT,
            null
        );
    }
}
```

**Error Cases:**

1. **File Not Found:** Return `NOT_FOUND` status
2. **I/O Error:** Return `ERROR` status with exception message
3. **Permission Denied:** Caught as IOException → ERROR

---

## 6. Statistics Tracking

### 6.1 Local Statistics

```java
private PartialStatsPayload getPartialStats() {
    return new PartialStatsPayload(
        workerId,
        totalRequests.get(),
        successfulRequests.get(),
        failedRequests.get()
    );
}
```

**Example:**
```java
{
  "workerId": "worker-2",
  "totalRequests": 5,
  "successfulRequests": 4,
  "failedRequests": 1
}
```

### 6.2 GET_STATS Handler

```java
private ResponseMessage handleGetStats(RequestMessage request) {
    logger.info("Worker [{}] initiating election for statistics aggregation", 
               workerId);
    
    try {
        // Initiate election - winner will respond to client
        electionManager.initiateElection(
            request.getRequestId(), 
            request.getClientQueue()
        );
        
        // Return null = this worker won't send response
        // Election winner will handle it
        return null;
        
    } catch (Exception e) {
        logger.error("Error during statistics election", e);
        
        // Fallback: send local stats only
        StatisticsPayload localStats = new StatisticsPayload(
            totalRequests.get(),
            successfulRequests.get(),
            failedRequests.get(),
            1  // Only this worker
        );
        
        return new ResponseMessage(
            request.getRequestId(),
            ResponseStatus.OK,
            ResponseType.STATISTICS,
            localStats
        );
    }
}
```

**Flow:**

1. Worker receives GET_STATS request
2. Initiates election (multicasts to all workers)
3. Returns `null` (won't send response itself)
4. Election proceeds in background
5. Winner sends aggregated response to client

**Why Return Null?**
- Prevents duplicate responses
- Election winner has full context (requestId, clientQueue)
- Cleaner separation of concerns

---

## 7. Integration with Spread

### 7.1 Worker Presence Announcement

```java
private void announcePresence() {
    try {
        long uptime = System.currentTimeMillis() - startTime;
        
        WorkerPresencePayload presence = new WorkerPresencePayload(
            workerId, uptime, totalRequests.get());
        
        SpreadMessage message = new SpreadMessage(
            SpreadMessageType.WORKER_PRESENCE, workerId, presence);
        
        spread.multicast(message);
        
        logger.info("Worker [{}] announced presence to group", workerId);
    } catch (IOException e) {
        logger.error("Error announcing presence", e);
    }
}
```

**Purpose:**
- Notify other workers of this worker's existence
- Share initial uptime (for election priority)
- Trigger membership updates in other workers

**When Called:**
- Once at startup (in constructor)
- Could be periodic (heartbeat) but not implemented

### 7.2 Cleanup on Shutdown

```java
public void close() throws IOException, TimeoutException {
    if (spread != null) {
        spread.close();  // Leave Spread group, close channels
    }
    if (channel != null && channel.isOpen()) {
        channel.close();
    }
    if (connection != null && connection.isOpen()) {
        connection.close();
    }
    logger.info("Worker [{}] closed", workerId);
}
```

---

## 8. Configuration and Deployment

### 8.1 Environment Variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `WORKER_ID` | `worker-<timestamp>` | Unique worker identifier |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ server hostname |
| `RABBITMQ_PORT` | `5672` | RabbitMQ AMQP port |
| `SHARED_FILES_DIR` | `/var/sharedfiles` | GlusterFS mount point |

### 8.2 Docker Deployment

**Dockerfile:**
```dockerfile
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY target/worker-1.0-SNAPSHOT-shaded.jar worker.jar

ENV WORKER_ID=worker-1
ENV RABBITMQ_HOST=rabbitmq
ENV SHARED_FILES_DIR=/var/sharedfiles

CMD ["java", "-jar", "worker.jar"]
```

**Docker Compose:**
```yaml
services:
  worker1:
    build:
      context: ..
      dockerfile: deploy/Dockerfile.worker
    environment:
      WORKER_ID: worker-1
      RABBITMQ_HOST: rabbitmq
      SHARED_FILES_DIR: /var/sharedfiles
    volumes:
      - gluster-volume:/var/sharedfiles
    depends_on:
      rabbitmq:
        condition: service_healthy
```

### 8.3 Running Manually

```bash
# Build
mvn clean package

# Run worker
WORKER_ID=worker-1 \
RABBITMQ_HOST=localhost \
SHARED_FILES_DIR=/mnt/glusterfs \
  java -jar worker/target/worker-1.0-SNAPSHOT-shaded.jar
```

---

## 9. Performance Considerations

### 9.1 File I/O

**Current:** Read entire file into memory
```java
String content = Files.readString(path);
```

**Scalability Limit:** Large files (>100MB) may cause memory issues

**Alternative (for large files):**
```java
try (BufferedReader reader = Files.newBufferedReader(path)) {
    String line;
    while ((line = reader.readLine()) != null) {
        // Process line by line
    }
}
```

### 9.2 Concurrent Request Handling

**Current:** Single-threaded consumer with QoS=1

**Benefit:**
- Simple, predictable
- Fair distribution across workers

**Limitation:**
- Can't parallelize within a worker
- Slow requests block queue

**Alternative:**
- Thread pool for request processing
- Higher QoS (e.g., 10)
- But more complex error handling

---

**Document Version:** 1.0  
**Last Updated:** December 9, 2025  
**Next:** [Part 5 - Spread Simulation and Election](05-SPREAD-ELECTION.md)
