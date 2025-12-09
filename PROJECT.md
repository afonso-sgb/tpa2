# TPA2 - Distributed Email Search System

## Description and Main Objective

The **TPA2 (Trabalho Prático de Avaliação 2)** project is a distributed email search system designed to demonstrate key concepts in distributed computing, specifically message-oriented architectures, distributed file systems, and fault-tolerant service design.

### Main Objective

Build a scalable, distributed system that allows users to search through email files stored in a replicated distributed file system. The system processes search requests asynchronously using a work queue pattern, with multiple worker nodes collaborating to provide fast, reliable search capabilities across a shared email corpus.

### Key Features

- **Distributed File Storage**: Emails stored in GlusterFS with 3-way replication for high availability
- **Asynchronous Processing**: RabbitMQ-based work queue distributes search requests across multiple workers
- **Case-Insensitive Search**: Find emails containing all specified substrings (matching Anexo 2 requirements)
- **Fault Tolerance**: Worker failures don't affect system availability; unprocessed messages are requeued
- **Horizontal Scalability**: Add more workers to increase throughput without code changes
- **Statistics Tracking**: Monitor system usage and performance across all workers
- **Docker Deployment**: Containerized architecture for easy deployment and testing

---

## Module Architecture

### 1. UserApp Module

**Purpose**: Command-line client application for interacting with the distributed email search system.

#### Responsibilities

- Parse user commands and arguments from the command line
- Create request messages (search, get-file, get-stats) with unique request IDs
- Serialize requests to JSON and publish to RabbitMQ work queues
- Create ephemeral response queues with auto-delete and TTL (60 seconds)
- Wait for and deserialize responses from workers
- Display results to the user in a formatted manner

#### Key Components

**`UserApp.java`** - Main class with three primary operations:

1. **search(substrings...)**: 
   - Creates a `SearchRequest` payload with list of substrings
   - Publishes to `email_search_queue`
   - Waits for `SearchResponse` containing matching filenames and content
   - Displays results: `##:filename` followed by email content

2. **getFile(filename)**:
   - Creates a `GetFileRequest` payload with target filename
   - Publishes to `email_search_queue`
   - Waits for `GetFileResponse` with file content
   - Displays the complete email text

3. **getStats()**:
   - Creates a `GetStatsRequest` payload
   - Publishes to `email_search_queue`
   - Waits for `GetStatsResponse` with aggregated statistics
   - Displays total requests, successful/failed counts, worker count

#### Technical Details

- **Request/Response Pattern**: Uses unique `requestId` (UUID) and dedicated response queue per request
- **Timeout Handling**: 30-second timeout with `CompletableFuture` for async response handling
- **Connection Management**: Establishes RabbitMQ connection, creates channels for publishing/consuming
- **Message Serialization**: Uses `JsonUtil` (Gson-based) to convert objects ↔ JSON ↔ byte arrays (Anexo 4)

#### Usage Examples

```bash
# Search for emails containing all substrings (case-insensitive)
java -jar userapp.jar search "gRPC em Java 21" "GCP" "Docker"

# Retrieve specific email content
java -jar userapp.jar get-file email017.txt

# Get system statistics
java -jar userapp.jar get-stats
```

---

### 2. Worker Module

**Purpose**: Stateless worker processes that consume search requests from RabbitMQ and search the distributed file system.

#### Responsibilities

- Connect to RabbitMQ and consume messages from `email_search_queue`
- Deserialize incoming request messages from JSON
- Process requests based on type (search/get-file/get-stats)
- Search email files in `/var/sharedfiles` (GlusterFS mount point)
- Track local statistics (total requests, successful/failed counts)
- Serialize response messages and publish to client-specific response queues
- Acknowledge messages after successful processing

#### Key Components

**`Worker.java`** - Main worker process with request dispatcher:

1. **handleSearch(SearchRequest)**:
   - Uses `Files.walk()` to traverse `/var/sharedfiles` directory
   - Filters for `.txt` files only
   - Reads each file and checks if content contains **all** substrings (case-insensitive)
   - Implementation matches **Anexo 2** specification exactly
   - Returns `Map<filename, content>` for matching emails
   - Creates `SearchResponse` with results

2. **handleGetFile(GetFileRequest)**:
   - Constructs file path from request filename
   - Validates file exists and is within allowed directory
   - Reads complete file content using `Files.readString()`
   - Creates `GetFileResponse` with content or error message

3. **handleGetStats(GetStatsRequest)**:
   - Returns local worker statistics (not aggregated across all workers)
   - Includes: `totalRequests`, `successfulRequests`, `failedRequests`
   - Worker count always returns 1 (each worker reports own stats)

#### Technical Details

- **Fair Dispatch**: Sets QoS prefetch count to 1, ensuring workers process one message at a time
- **Manual Acknowledgment**: Only ACKs messages after successful processing to prevent message loss
- **Error Handling**: Catches exceptions, logs errors, sends error responses, increments `failedRequests`
- **File Search Algorithm** (from Anexo 2):
  ```java
  private boolean containsAllSubstrings(Path file, List<String> substrings) {
      String content = Files.readString(file);
      String lowerContent = content.toLowerCase();
      for (String substring : substrings) {
          if (!lowerContent.contains(substring.toLowerCase())) {
              return false;
          }
      }
      return true;
  }
  ```
- **Thread Safety**: Uses `AtomicInteger` for concurrent statistic updates
- **Logging**: SLF4J with Logback for structured logging

#### Worker Lifecycle

1. Start → Connect to RabbitMQ
2. Declare work queue (`email_search_queue`, durable)
3. Set QoS prefetch count
4. Register consumer callback
5. Process messages indefinitely
6. On shutdown → Close channel and connection

---

### 3. GlusterFS Module

**Purpose**: Distributed replicated file system providing shared storage for email files across all worker nodes.

#### Architecture

GlusterFS creates a distributed volume with 3-way replication across three nodes, ensuring:
- **High Availability**: Email files remain accessible even if 1-2 nodes fail
- **Data Consistency**: All replicas are automatically synchronized
- **Transparent Access**: Workers see a single unified filesystem at `/var/sharedfiles`

#### Key Concepts

**Brick**: Physical storage directory on each node (`/var/gluster/brick`)
**Volume**: Logical distributed filesystem combining all bricks (`glustervol`)
**Mount Point**: Directory where volume is mounted (`/var/sharedfiles`)
**Replica Count**: Number of copies of each file (3 in this project)

#### Installation and Setup (Anexo 3)

**On Base VM (Ubuntu 24 LTS):**
```bash
# Add GlusterFS PPA repository
sudo add-apt-repository ppa:gluster/glusterfs-11 -y
sudo apt update
sudo apt install glusterfs-server

# Start GlusterFS daemon
sudo service glusterd start
sudo service glusterd status

# Create brick directory (storage location)
sudo mkdir -p /var/gluster/brick
sudo chmod 777 /var/gluster/brick

# Create mount point directory (application access point)
sudo mkdir /var/sharedfiles
sudo chmod 777 /var/sharedfiles
```

**Create Machine Image and Deploy 3 VMs**: `tpa2-node1`, `tpa2-node2`, `tpa2-node3`

**On All 3 Nodes:**
```bash
# Start GlusterFS daemon
sudo service glusterd start

# Configure /etc/hosts with internal IPs
echo "10.128.0.8 tpa2-node1" | sudo tee -a /etc/hosts
echo "10.128.0.10 tpa2-node2" | sudo tee -a /etc/hosts
echo "10.128.0.11 tpa2-node3" | sudo tee -a /etc/hosts
```

**On tpa2-node1 ONLY (Cluster Setup):**
```bash
# Establish peer relationships
sudo gluster peer probe tpa2-node2
sudo gluster peer probe tpa2-node3

# Verify peers
sudo gluster peer status

# Create replicated volume
sudo gluster volume create glustervol replica 3 \
    tpa2-node1:/var/gluster/brick \
    tpa2-node2:/var/gluster/brick \
    tpa2-node3:/var/gluster/brick \
    force

# Start volume
sudo gluster volume start glustervol
```

**On All 3 Nodes (Mount Volume):**
```bash
# Mount GlusterFS volume to shared directory
sudo mount -t glusterfs tpa2-node1:/glustervol /var/sharedfiles

# Verify replication
date > /var/sharedfiles/test.txt
# Check file appears on all nodes in /var/sharedfiles
```

#### How Workers Use GlusterFS

1. Each worker has `/var/sharedfiles` mounted as a GlusterFS client
2. Worker reads files from `/var/sharedfiles/*.txt`
3. GlusterFS transparently:
   - Routes read requests to available replica
   - Load balances across healthy nodes
   - Handles node failures automatically
4. All workers see identical file content (strong consistency)

#### Replication Benefits

- **Fault Tolerance**: System continues functioning with 1-2 node failures
- **Load Distribution**: Reads can be served from any replica
- **Data Durability**: 3 copies prevent data loss from disk failures
- **Geographic Distribution**: Replicas can span availability zones (in production)

---

### 4. RabbitMQ Module

**Purpose**: Message broker providing asynchronous communication between UserApp clients and Worker processes using the work queue pattern.

#### Architecture Pattern: Work Queue (Task Queue)

RabbitMQ implements a **competing consumers** pattern where:
1. Multiple workers consume from a single queue
2. Each message is delivered to exactly one worker (fair dispatch)
3. Workers acknowledge messages after processing
4. Unacknowledged messages are requeued if worker fails

#### Key Queues

**1. `email_search_queue` (Work Queue)**
- **Type**: Durable (survives broker restarts)
- **Producers**: UserApp instances
- **Consumers**: Multiple Worker instances
- **Message Types**: `SearchRequest`, `GetFileRequest`, `GetStatsRequest`
- **Routing**: Round-robin distribution with QoS prefetch=1

**2. Response Queues (Dynamic, Per-Request)**
- **Naming**: `response_queue_<UUID>` (unique per request)
- **Type**: Exclusive, auto-delete with TTL (60 seconds)
- **Producers**: Worker that processes the request
- **Consumers**: Originating UserApp instance
- **Message Types**: `SearchResponse`, `GetFileResponse`, `GetStatsResponse`
- **Lifecycle**: Created by UserApp → Worker publishes response → Auto-deleted after consumption

#### Message Flow (Search Example)

```
┌─────────┐                  ┌──────────┐                  ┌─────────┐
│ UserApp │                  │ RabbitMQ │                  │ Worker  │
└────┬────┘                  └────┬─────┘                  └────┬────┘
     │                            │                             │
     │ 1. Create response queue   │                             │
     ├───────────────────────────>│                             │
     │   "response_queue_abc123"  │                             │
     │                            │                             │
     │ 2. Publish SearchRequest   │                             │
     ├───────────────────────────>│                             │
     │   to: email_search_queue   │                             │
     │   replyTo: response_queue_abc123                         │
     │   requestId: "abc123"      │                             │
     │                            │                             │
     │ 3. Start consuming from    │                             │
     │    response_queue_abc123   │                             │
     ├───────────────────────────>│                             │
     │                            │                             │
     │                            │ 4. Deliver message (QoS=1) │
     │                            ├────────────────────────────>│
     │                            │                             │
     │                            │         5. Process search   │
     │                            │            (read files)     │
     │                            │                             │
     │                            │ 6. Publish SearchResponse   │
     │                            │<────────────────────────────┤
     │                            │   to: response_queue_abc123 │
     │                            │   requestId: "abc123"       │
     │                            │                             │
     │ 7. Receive response        │                             │
     │<───────────────────────────┤                             │
     │                            │                             │
     │ 8. Display results         │                             │
     │                            │                             │
     │                            │ 9. ACK message              │
     │                            │<────────────────────────────┤
     │                            │                             │
     │ 10. Close response queue   │                             │
     │    (auto-delete)           │                             │
     └────────────────────────────┴─────────────────────────────┘
```

#### RabbitMQ Configuration

**Connection Settings** (from `QueueConfig.java`):
```java
HOST = "localhost" (or RabbitMQ container hostname in Docker)
PORT = 5672
WORK_QUEUE_NAME = "email_search_queue"
```

**Work Queue Properties**:
- `durable = true`: Queue survives broker restart
- `exclusive = false`: Multiple consumers can connect
- `autoDelete = false`: Queue persists when no consumers

**Response Queue Properties**:
- `exclusive = true`: Only creating connection can consume
- `autoDelete = true`: Deleted when connection closes
- `TTL = 60000ms`: Automatically deleted after 60 seconds
- Arguments: `{"x-message-ttl": 60000, "x-expires": 60000}`

**Message Properties**:
- `contentType = "application/json"`
- `deliveryMode = 2`: Persistent messages (survive broker restart)
- `replyTo = <response_queue_name>`: Return address for responses

#### Quality of Service (QoS)

**Worker QoS Settings**:
```java
channel.basicQos(1); // Prefetch count = 1
```

**Benefits**:
- **Fair Dispatch**: Each worker gets one message at a time
- **Load Balancing**: Busy workers don't get overwhelmed
- **Fault Tolerance**: Unacknowledged messages return to queue if worker crashes

#### Message Acknowledgment Strategy

**Manual Acknowledgment** (Worker side):
```java
channel.basicConsume(WORK_QUEUE_NAME, false, // autoAck = false
    (consumerTag, delivery) -> {
        try {
            processRequest(delivery);
            channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
        } catch (Exception e) {
            channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
        }
    }
);
```

**Why Manual ACK?**
- Messages only removed from queue after successful processing
- Worker crashes → message returns to queue → picked up by another worker
- Prevents message loss during failures

#### Docker Deployment

**RabbitMQ Container** (from `docker-compose.yml`):
```yaml
rabbitmq:
  image: rabbitmq:3-management
  ports:
    - "5672:5672"   # AMQP protocol
    - "15672:15672" # Management UI
  environment:
    RABBITMQ_DEFAULT_USER: guest
    RABBITMQ_DEFAULT_PASS: guest
```

**Management UI**: http://localhost:15672
- Monitor queues, connections, channels
- View message rates and queue depths
- Debug message routing

---

## How All Modules Work Together

### Complete System Workflow

```
┌──────────────────────────────────────────────────────────────────┐
│                     DISTRIBUTED EMAIL SEARCH SYSTEM               │
└──────────────────────────────────────────────────────────────────┘

┌─────────────┐         ┌──────────────┐         ┌─────────────────┐
│   UserApp   │────────>│   RabbitMQ   │────────>│  Worker Node 1  │
│   (Client)  │<────────│ (Message Bus)│<────────│  + GlusterFS    │
└─────────────┘         └──────────────┘         └─────────────────┘
                              │   △                        │
                              │   │                        │
                              │   │               ┌────────▼────────┐
                              │   │               │   GlusterFS     │
                              │   │               │    Volume       │
                              │   │               │  (3x replicas)  │
                              │   │               └────────┬────────┘
                              │   │                        │
                              v   │                        │
                        ┌─────────────────┐       ┌────────▼────────┐
                        │  Worker Node 2  │       │  Worker Node 3  │
                        │  + GlusterFS    │       │  + GlusterFS    │
                        └─────────────────┘       └─────────────────┘
```

### Step-by-Step Execution Flow

#### Scenario: User Searches for Emails Containing "gRPC em Java 21", "GCP", "Docker"

**Step 1: UserApp Creates Request**
```java
// UserApp.main() parses command line
args = ["search", "gRPC em Java 21", "GCP", "Docker"]

// Create unique request
String requestId = UUID.randomUUID().toString(); // "abc-123-def"
SearchRequest payload = new SearchRequest(Arrays.asList("gRPC em Java 21", "GCP", "Docker"));
RequestMessage request = new RequestMessage(requestId, MessageType.SEARCH, "response_queue_abc-123-def", payload);
```

**Step 2: UserApp Sets Up Response Queue**
```java
// Create ephemeral response queue
String responseQueue = "response_queue_" + requestId;
channel.queueDeclare(responseQueue, false, true, true, ttlArgs);

// Start consuming responses (async)
CompletableFuture<ResponseMessage> future = new CompletableFuture<>();
channel.basicConsume(responseQueue, true, (tag, delivery) -> {
    ResponseMessage response = JsonUtil.fromJsonBytes(delivery.getBody(), ResponseMessage.class);
    future.complete(response);
}, tag -> {});
```

**Step 3: UserApp Publishes to Work Queue**
```java
// Serialize and publish
byte[] messageBytes = JsonUtil.toJsonBytes(request);
channel.basicPublish("", "email_search_queue", 
    new AMQP.BasicProperties.Builder()
        .contentType("application/json")
        .deliveryMode(2) // persistent
        .build(),
    messageBytes);
```

**Step 4: RabbitMQ Routes Message to Available Worker**
- Worker 1: Processing a previous request (busy)
- Worker 2: Idle → **Receives message** (QoS prefetch=1)
- Worker 3: Idle (not selected this time)

**Step 5: Worker 2 Processes Request**
```java
// Deserialize message
RequestMessage request = JsonUtil.parseRequest(delivery.getBody());

// Dispatch based on type
SearchRequest searchPayload = (SearchRequest) request.getPayload();

// Search files in GlusterFS mount
Map<String, String> results = new HashMap<>();
Files.walk(Paths.get("/var/sharedfiles"))
    .filter(Files::isRegularFile)
    .filter(path -> path.toString().endsWith(".txt"))
    .forEach(path -> {
        if (containsAllSubstrings(path, searchPayload.getSubstrings())) {
            String content = Files.readString(path);
            results.put(path.getFileName().toString(), content);
        }
    });
```

**Step 6: Worker 2 Reads from GlusterFS**
```
Worker 2 reads /var/sharedfiles/email017.txt
    ↓
GlusterFS client routes request to replica
    ↓
Node 1 brick: /var/gluster/brick/email017.txt ← Selected
Node 2 brick: /var/gluster/brick/email017.txt
Node 3 brick: /var/gluster/brick/email017.txt
    ↓
Content returned to Worker 2
```

**Step 7: Worker 2 Checks Substrings (Case-Insensitive)**
```java
String content = "...gRPC em Java 21...Docker...GCP...";
String lowerContent = content.toLowerCase();

// Check "gRPC em Java 21"
lowerContent.contains("grpc em java 21") → true

// Check "GCP"
lowerContent.contains("gcp") → true

// Check "Docker"
lowerContent.contains("docker") → true

// All substrings match → Add to results
results.put("email017.txt", content);
```

**Step 8: Worker 2 Sends Response**
```java
// Create response
SearchResponse responsePayload = new SearchResponse(results);
ResponseMessage response = new ResponseMessage(
    request.getRequestId(), // "abc-123-def"
    MessageType.SEARCH,
    responsePayload
);

// Publish to client's response queue
byte[] responseBytes = JsonUtil.toJsonBytes(response);
channel.basicPublish("", request.getClientQueue(), // "response_queue_abc-123-def"
    new AMQP.BasicProperties.Builder()
        .contentType("application/json")
        .build(),
    responseBytes);

// Acknowledge message
channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
```

**Step 9: UserApp Receives Response**
```java
// CompletableFuture completes with response
ResponseMessage response = future.get(30, TimeUnit.SECONDS);
SearchResponse payload = (SearchResponse) response.getPayload();

// Display results
System.out.println("Found " + payload.getResults().size() + " email(s)");
for (String filename : payload.getResults().keySet()) {
    System.out.println("##:" + filename);
    System.out.println(payload.getResults().get(filename));
}
```

**Output:**
```
Found 1 email(s) containing all substrings

##:email017.txt
De: rodrigo.santiago@techteam.pt
Para: manuela.afonso@techteam.pt, antonio.silva@techteam.pt
Assunto: Protótipo gRPC em Java concluído
Data: 2025-11-12

Manuela e António,

Boas notícias! Terminei o protótipo de serviço gRPC em Java 21.
...
Próximos passos:
1. Integrar com RabbitMQ para mensageria assíncrona
2. Deploy em containers Docker
3. Testes nas VMs da plataforma GCP
...
```

### Fault Tolerance Scenarios

#### Scenario 1: Worker Crashes During Processing

1. UserApp publishes request → Worker A receives message
2. Worker A starts processing (does NOT acknowledge yet)
3. Worker A crashes (JVM killed, server reboot, etc.)
4. RabbitMQ detects connection loss
5. Message returns to `email_search_queue` (not acknowledged)
6. Worker B picks up message and processes it
7. UserApp receives response (transparent recovery)

#### Scenario 2: GlusterFS Node Failure

1. Worker attempts to read from `/var/sharedfiles/email001.txt`
2. GlusterFS routes read to Node 1 replica
3. Node 1 is down/unreachable
4. GlusterFS automatically fails over to Node 2 replica
5. Worker receives file content (transparent failover)
6. No error visible to worker or user

#### Scenario 3: RabbitMQ Connection Timeout

1. UserApp publishes request and waits for response
2. No worker available or all workers busy
3. 30-second timeout expires in `future.get(30, SECONDS)`
4. UserApp catches `TimeoutException`
5. Displays error message: "Request timed out"
6. Message remains in queue for eventual processing

---

## Development Strategy for Each Module

### Module 1: Common Module (Foundation)

**Strategy**: Design reusable message contracts and utilities first to establish communication protocol.

#### Step 1: Define Message POJOs
- Created `RequestMessage` and `ResponseMessage` base classes
- Designed payload hierarchy: `SearchRequest`, `GetFileRequest`, `GetStatsRequest`
- Implemented corresponding response payloads
- Added `MessageType` enum for type discrimination
- **Rationale**: Type-safe message handling prevents runtime errors; clear contracts enable parallel development

#### Step 2: Implement JSON Serialization (Anexo 4)
```java
// JsonUtil.java - Gson-based serialization
public static byte[] toJsonBytes(Object obj) {
    String json = gson.toJson(obj);
    return json.getBytes(StandardCharsets.UTF_8);
}

public static <T> T fromJsonBytes(byte[] bytes, Class<T> clazz) {
    String json = new String(bytes, StandardCharsets.UTF_8);
    return gson.fromJson(json, clazz);
}
```
- **Rationale**: Matches Anexo 4 specification; Gson provides automatic serialization; UTF-8 ensures compatibility

#### Step 3: Create Queue Configuration
- Centralized `QueueConfig` class with constants
- **Rationale**: Single source of truth for queue names; easy to modify for testing/deployment

#### Testing Approach
- Unit tests for JSON serialization round-trips
- Verified all message types serialize/deserialize correctly
- Tested edge cases (null values, empty lists, special characters)

---

### Module 2: Worker Module (Core Logic)

**Strategy**: Implement file search logic first (testable in isolation), then integrate with RabbitMQ.

#### Step 1: Implement File Search Algorithm (Anexo 2)
```java
// Standalone search method - testable without RabbitMQ
public static Map<String, String> searchInsideEmails(String directoryPath, 
                                                      List<String> substringsList) {
    Map<String, String> matchingEmails = new HashMap<>();
    Files.list(Paths.get(directoryPath))
        .filter(Files::isRegularFile)
        .filter(path -> path.toString().endsWith(".txt"))
        .forEach(path -> {
            String emailMessage = Files.readString(path);
            if (containsAllSubstrings(emailMessage, substringsList)) {
                matchingEmails.put(path.toString(), emailMessage);
            }
        });
    return matchingEmails;
}
```
- **Rationale**: Direct implementation from Anexo 2; validates core functionality before adding messaging complexity

#### Step 2: Implement Case-Insensitive Matching
```java
public static boolean containsAllSubstrings(String message, List<String> substringsList) {
    String lowerMessage = message.toLowerCase();
    for (String substr : substringsList) {
        if (!lowerMessage.contains(substr.toLowerCase())) {
            return false;
        }
    }
    return true;
}
```
- **Rationale**: Anexo 1 requires finding "gRPC em Java 21" (mixed case); toLowerCase() ensures robust matching

#### Step 3: Add Statistics Tracking
```java
private final AtomicInteger totalRequests = new AtomicInteger(0);
private final AtomicInteger successfulRequests = new AtomicInteger(0);
private final AtomicInteger failedRequests = new AtomicInteger(0);
```
- **Rationale**: AtomicInteger ensures thread-safe updates; supports concurrent message processing

#### Step 4: Integrate RabbitMQ Consumer
- Created connection and channel
- Declared work queue (durable)
- Set QoS prefetch count to 1
- Implemented message consumer with manual ACK
- **Rationale**: QoS=1 ensures fair dispatch; manual ACK prevents message loss on failure

#### Step 5: Implement Request Dispatcher
```java
private void processRequest(Delivery delivery) {
    RequestMessage request = JsonUtil.parseRequest(delivery.getBody());
    ResponseMessage response;
    
    switch (request.getType()) {
        case SEARCH -> response = handleSearch(request);
        case GET_FILE -> response = handleGetFile(request);
        case GET_STATS -> response = handleGetStats(request);
        default -> response = createErrorResponse(request);
    }
    
    // Send response to client queue
    channel.basicPublish("", request.getClientQueue(), props, 
                        JsonUtil.toJsonBytes(response));
    
    // Acknowledge after successful processing
    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
}
```
- **Rationale**: Centralized error handling; response sent before ACK ensures message reprocessing on send failure

#### Testing Approach
- **Local Testing**: Created sample .txt files in `/tmp/emails/`
- **Search Validation**: Verified case-insensitive matching with various substring combinations
- **RabbitMQ Integration**: Tested with local RabbitMQ instance before Docker deployment
- **Error Scenarios**: Tested file not found, permission denied, invalid requests

---

### Module 3: UserApp Module (Client Interface)

**Strategy**: Build CLI parser first, then implement RabbitMQ client with request/response correlation.

#### Step 1: Implement Command Line Parsing
```java
public static void main(String[] args) {
    if (args.length < 1) {
        printUsage();
        return;
    }
    
    String command = args[0].toLowerCase();
    switch (command) {
        case "search" -> {
            List<String> substrings = Arrays.asList(args).subList(1, args.length);
            search(substrings);
        }
        case "get-file" -> getFile(args[1]);
        case "get-stats" -> getStats();
        default -> printUsage();
    }
}
```
- **Rationale**: Simple string-based commands; subList for variable-length search arguments

#### Step 2: Implement Request/Response Correlation
```java
private static ResponseMessage sendRequestAndWait(RequestMessage request) {
    String requestId = request.getRequestId();
    String responseQueue = "response_queue_" + requestId;
    
    // Create exclusive, auto-delete response queue
    Map<String, Object> args = new HashMap<>();
    args.put("x-message-ttl", 60000); // 60 seconds
    args.put("x-expires", 60000);
    channel.queueDeclare(responseQueue, false, true, true, args);
    
    // Set up async response consumer
    CompletableFuture<ResponseMessage> future = new CompletableFuture<>();
    channel.basicConsume(responseQueue, true, (tag, delivery) -> {
        ResponseMessage response = JsonUtil.fromJsonBytes(delivery.getBody(), 
                                                          ResponseMessage.class);
        if (response.getRequestId().equals(requestId)) {
            future.complete(response);
        }
    }, tag -> {});
    
    // Publish request
    channel.basicPublish("", WORK_QUEUE_NAME, props, 
                        JsonUtil.toJsonBytes(request));
    
    // Wait for response with timeout
    return future.get(30, TimeUnit.SECONDS);
}
```
- **Rationale**: UUID-based correlation prevents response mismatch; CompletableFuture enables async handling with timeout

#### Step 3: Implement Response Display
```java
private static void displaySearchResults(SearchResponse response) {
    Map<String, String> results = response.getResults();
    System.out.println("Found " + results.size() + " email(s) containing all substrings\n");
    
    for (String filename : results.keySet()) {
        System.out.println("##:" + filename);  // Anexo 2 format
        System.out.println(results.get(filename));
        System.out.println();
    }
}
```
- **Rationale**: Matches Anexo 2 output format exactly (`##:filename`)

#### Step 4: Add Error Handling
- Timeout handling with descriptive messages
- Connection failure recovery
- Invalid argument validation
- **Rationale**: User-friendly error messages improve debugging experience

#### Testing Approach
- **Unit Testing**: Tested command parsing with various argument combinations
- **Integration Testing**: Ran against local RabbitMQ + single worker
- **Timeout Testing**: Verified 30-second timeout with no workers running
- **Concurrency Testing**: Multiple concurrent UserApp instances → verified response correlation

---

### Module 4: Docker Deployment (Local Testing)

**Strategy**: Containerize workers and RabbitMQ for reproducible local testing environment.

#### Step 1: Create Worker Dockerfile
```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY worker/target/worker.jar /app/worker.jar
ENV RABBITMQ_HOST=rabbitmq
ENV WORKER_ID=worker1
CMD ["java", "-jar", "worker.jar"]
```
- **Rationale**: Alpine Linux for minimal image size; eclipse-temurin for Java 21 support; environment variables for configuration

#### Step 2: Create Docker Compose Configuration
```yaml
services:
  rabbitmq:
    image: rabbitmq:3-management
    ports:
      - "5672:5672"
      - "15672:15672"
  
  worker1:
    build: .
    depends_on:
      - rabbitmq
    environment:
      - WORKER_ID=worker1
    volumes:
      - shared-files:/var/sharedfiles
  
  worker2:
    build: .
    depends_on:
      - rabbitmq
    environment:
      - WORKER_ID=worker2
    volumes:
      - shared-files:/var/sharedfiles
  
  worker3:
    build: .
    depends_on:
      - rabbitmq
    environment:
      - WORKER_ID=worker3
    volumes:
      - shared-files:/var/sharedfiles

volumes:
  shared-files:
```
- **Rationale**: Shared volume simulates GlusterFS; depends_on ensures RabbitMQ starts first; 3 workers demonstrate load balancing

#### Step 3: Create Deployment Scripts
- `build.sh`: Compile Maven project + build Docker images
- `start.sh`: Launch docker-compose stack
- `stop.sh`: Graceful shutdown
- **Rationale**: Single-command deployment simplifies testing workflow

#### Testing Approach
- **Build Verification**: `mvn clean package` → verify JARs created
- **Container Health**: Check RabbitMQ management UI (http://localhost:15672)
- **Volume Sharing**: Copy sample emails → verify visible in all worker containers
- **Load Balancing**: Send multiple requests → verify distributed across workers (RabbitMQ UI)

---

### Module 5: GlusterFS Deployment (Production)

**Strategy**: Automate GlusterFS cluster setup with scripts following Anexo 3 specification.

#### Step 1: Create Base VM Provisioning Script
```bash
# provision-base.sh
sudo add-apt-repository ppa:gluster/glusterfs-11 -y
sudo apt-get update
sudo apt-get install -y glusterfs-server default-jdk maven docker.io

# Create brick and mount point directories
sudo mkdir -p /var/gluster/brick
sudo chmod 777 /var/gluster/brick
sudo mkdir /var/sharedfiles
sudo chmod 777 /var/sharedfiles

# Enable GlusterFS daemon
sudo systemctl enable glusterd
sudo systemctl start glusterd
```
- **Rationale**: Direct implementation of Anexo 3; includes all dependencies (Java, Docker, GlusterFS)

#### Step 2: Create Cluster Setup Script
```bash
# setup-gluster.sh (run on node1 only)

# Probe peers
sudo gluster peer probe tpa2-node2
sudo gluster peer probe tpa2-node3

# Wait for peer connection
sleep 5
sudo gluster peer status

# Create replicated volume
sudo gluster volume create glustervol replica 3 \
    tpa2-node1:/var/gluster/brick \
    tpa2-node2:/var/gluster/brick \
    tpa2-node3:/var/gluster/brick \
    force

# Start volume
sudo gluster volume start glustervol

# Mount on all nodes
sudo mount -t glusterfs tpa2-node1:/glustervol /var/sharedfiles
```
- **Rationale**: Matches Anexo 3 exactly; replica 3 for fault tolerance; force flag bypasses warnings

#### Step 3: Create Deployment Script
```bash
# deploy.sh
# Build project on local machine
mvn clean package

# Copy JARs to nodes
for node in tpa2-node1 tpa2-node2 tpa2-node3; do
    gcloud compute scp worker/target/worker.jar $node:/home/$USER/worker.jar
done

# Create systemd service on each node
cat << 'EOF' | sudo tee /etc/systemd/system/tpa2-worker.service
[Unit]
Description=TPA2 Email Search Worker
After=network.target glusterd.service

[Service]
Type=simple
User=ubuntu
ExecStart=/usr/bin/java -jar /home/ubuntu/worker.jar
Restart=always

[Install]
WantedBy=multi-user.target
EOF

# Enable and start service
sudo systemctl daemon-reload
sudo systemctl enable tpa2-worker
sudo systemctl start tpa2-worker
```
- **Rationale**: Systemd ensures workers restart on failure; gcloud CLI simplifies GCP deployment

#### Step 4: Create Test Script
```bash
# run-tests.sh
# Copy sample emails to GlusterFS
for node in tpa2-node1; do
    gcloud compute scp EmailFiles/*.txt $node:/var/sharedfiles/
done

# Test search from local machine
java -jar userapp/target/userapp.jar search "gRPC em Java 21" "GCP" "Docker"
```
- **Rationale**: Copy to one node → GlusterFS replicates to all nodes; validates end-to-end system

#### Testing Approach
- **VM Creation**: Created 3 VMs from base image in GCP
- **Cluster Verification**: `sudo gluster volume info` → confirmed 3 bricks
- **Replication Test**: `echo "test" > /var/sharedfiles/test.txt` → verified on all nodes
- **Worker Deployment**: Checked systemd status on all nodes
- **End-to-End**: UserApp → RabbitMQ → Workers → GlusterFS → Response

---

## Key Design Decisions

### 1. Message Serialization: Gson vs Protocol Buffers
**Decision**: Use Gson for JSON serialization
**Rationale**: 
- Anexo 4 explicitly demonstrates Gson usage
- Human-readable messages aid debugging
- Simpler implementation (no .proto files)
- Adequate performance for this use case

### 2. Work Queue vs Pub/Sub
**Decision**: Work queue pattern (competing consumers)
**Rationale**:
- Each search request should be processed exactly once
- Load balancing across workers is essential
- Pub/Sub would deliver each message to all workers (inefficient)

### 3. Manual vs Auto Acknowledgment
**Decision**: Manual acknowledgment
**Rationale**:
- Auto-ack removes messages from queue before processing
- Worker crash would lose unprocessed messages
- Manual ack ensures at-least-once delivery

### 4. Response Queue Strategy: Shared vs Per-Request
**Decision**: Per-request exclusive queues
**Rationale**:
- Eliminates response correlation complexity
- Auto-delete cleans up resources
- Exclusive flag prevents accidental consumption by other clients

### 5. GlusterFS Replica Count: 3
**Decision**: 3-way replication
**Rationale**:
- Tolerates 2 simultaneous node failures
- Odd number prevents split-brain scenarios
- Standard practice for distributed systems (quorum = 2)

### 6. Case-Insensitive Search
**Decision**: Convert both content and substrings to lowercase
**Rationale**:
- Anexo 1 requires finding "gRPC em Java 21" (mixed case)
- User expectations: search should be case-insensitive
- Simple implementation: `toLowerCase()` before `contains()`

### 7. Statistics Aggregation: Local vs Global
**Decision**: Each worker reports local statistics
**Rationale**:
- Global aggregation requires distributed consensus (complex)
- Spread toolkit not yet implemented
- Local stats sufficient for monitoring individual worker health

---

## Testing Strategy

### Unit Testing
- **Common Module**: JSON serialization round-trips for all message types
- **Worker Module**: File search algorithm with various substring combinations
- **UserApp Module**: Command parsing, request creation, response handling

### Integration Testing
- **Local Docker**: 3 workers + RabbitMQ + shared volume
- **Message Flow**: UserApp → RabbitMQ → Worker → Response
- **Load Balancing**: Verified messages distributed across workers
- **Fault Tolerance**: Killed worker mid-processing → message requeued

### End-to-End Testing
- **email017.txt Validation**: Verified findable with "gRPC em Java 21", "GCP", "Docker"
- **Case Sensitivity**: Tested with various case combinations
- **Concurrent Clients**: Multiple UserApp instances simultaneously
- **File Not Found**: Requested non-existent files

### Performance Testing
- **Throughput**: Measured requests/second with varying worker counts
- **Latency**: Measured end-to-end response time
- **Scalability**: Added workers dynamically → observed throughput increase

---

## Future Enhancements

### 1. Spread Toolkit Integration
- Implement leader election for statistics aggregation
- Use group communication for worker coordination
- Enable distributed consensus for global state

### 2. Advanced Search Features
- Regular expression support
- Date range filtering
- Sender/recipient search
- Full-text indexing (Lucene/Elasticsearch)

### 3. Monitoring and Observability
- Prometheus metrics export
- Grafana dashboards for system health
- Distributed tracing (OpenTelemetry)
- Centralized logging (ELK stack)

### 4. Security Enhancements
- TLS encryption for RabbitMQ connections
- Authentication/authorization for UserApp
- GlusterFS encryption at rest
- Message payload encryption

### 5. High Availability
- RabbitMQ clustering (3-node cluster)
- Automatic worker restart on failure (Kubernetes)
- Geographic distribution across GCP regions
- Backup and disaster recovery procedures

---

## Conclusion

The TPA2 distributed email search system successfully demonstrates core distributed computing principles:

- **Asynchronous Communication**: RabbitMQ work queue decouples clients from workers
- **Distributed Storage**: GlusterFS provides replicated, fault-tolerant file storage
- **Horizontal Scalability**: Add workers to increase throughput without code changes
- **Fault Tolerance**: Worker and storage node failures handled transparently
- **Message-Oriented Architecture**: Type-safe JSON messages enable reliable communication

The modular design allows each component to be developed, tested, and deployed independently, while the comprehensive documentation and scripts facilitate deployment in both local Docker and production GCP environments.
