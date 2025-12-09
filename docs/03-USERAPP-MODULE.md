# TPA2 - Technical Documentation Part 3: UserApp Module

**Course:** Computação Distribuída (Winter 2025-2026)  
**Module:** `userapp`  
**Date:** December 2025

---

## Table of Contents

1. [Module Overview](#module-overview)
2. [UserApp Class Architecture](#userapp-class-architecture)
3. [Operation Implementations](#operation-implementations)
4. [Command-Line Interface](#command-line-interface)
5. [Concurrency Handling](#concurrency-handling)
6. [Error Handling](#error-handling)

---

## 1. Module Overview

### 1.1 Purpose

The `userapp` module provides a **command-line client application** that allows users to:
- Search for emails containing specific substrings
- Retrieve full content of email files
- Request aggregated statistics from all workers

### 1.2 Key Responsibilities

1. **Parse command-line arguments** and validate input
2. **Establish RabbitMQ connection** and create client queue
3. **Send requests** to the work queue
4. **Wait for responses** with timeout
5. **Display results** to the user in required format
6. **Handle errors gracefully** with appropriate messages

### 1.3 Dependencies

```xml
<dependencies>
    <!-- Common module (models, utilities) -->
    <dependency>
        <groupId>pt.isel.cd</groupId>
        <artifactId>common</artifactId>
        <version>1.0-SNAPSHOT</version>
    </dependency>
    
    <!-- RabbitMQ client -->
    <dependency>
        <groupId>com.rabbitmq.client</groupId>
        <artifactId>amqp-client</artifactId>
        <version>5.20.0</version>
    </dependency>
    
    <!-- SLF4J + Logback for logging -->
    <dependency>
        <groupId>ch.qos.logback</groupId>
        <artifactId>logback-classic</artifactId>
    </dependency>
</dependencies>
```

---

## 2. UserApp Class Architecture

### 2.1 Class Structure

```java
public class UserApp implements AutoCloseable {
    // Constants
    private static final int RESPONSE_TIMEOUT_SECONDS = 30;
    
    // RabbitMQ components
    private final Connection connection;
    private final Channel channel;
    private final String clientQueue;
    
    // Request tracking
    private final Map<String, CompletableFuture<ResponseMessage>> pendingRequests;
    
    // Methods
    public UserApp(String rabbitMqHost, int rabbitMqPort) { ... }
    private void startResponseConsumer() { ... }
    public SearchResultPayload search(List<String> substrings) { ... }
    public FileContentPayload getFile(String filename) { ... }
    public StatisticsPayload getStats() { ... }
    private ResponseMessage sendRequestAndWait(RequestMessage request) { ... }
    public void close() { ... }
    public static void main(String[] args) { ... }
}
```

### 2.2 Instance Variables Explained

#### **Connection and Channel**
```java
private final Connection connection;  // TCP connection to RabbitMQ
private final Channel channel;        // Virtual connection for operations
```

- **Connection:** Manages TCP connection to RabbitMQ server
- **Channel:** Lightweight, multiplexed over connection
- **Why both?** Channels are cheaper than connections; reuse connection

#### **Client Queue**
```java
private final String clientQueue;  // Unique response queue for this instance
```

**Format:** `client-<UUID>-resp`  
**Example:** `client-5a35f85d-0b02-4c85-b0b1-b652c1cc4f8a`

**Purpose:**
- Each UserApp instance has its own response queue
- Prevents responses from being delivered to wrong client
- Auto-deletes when client disconnects (exclusive queue)

**Creation:**
```java
String queuePrefix = QueueConfig.CLIENT_QUEUE_PREFIX;  // "client-"
String uniqueId = UUID.randomUUID().toString();
clientQueue = queuePrefix + uniqueId;

Map<String, Object> args = new HashMap<>();
args.put("x-message-ttl", QueueConfig.CLIENT_QUEUE_TTL);  // 5 minutes
args.put("x-expires", QueueConfig.CLIENT_QUEUE_TTL);      // Auto-delete

channel.queueDeclare(
    clientQueue,  // name
    false,        // durable (false = temporary)
    true,         // exclusive (true = only this connection)
    true,         // auto-delete (true = delete when client disconnects)
    args          // TTL arguments
);
```

#### **Pending Requests Map**
```java
private final Map<String, CompletableFuture<ResponseMessage>> pendingRequests 
    = new ConcurrentHashMap<>();
```

**Purpose:** Track in-flight requests and match responses.

**Key = requestId (UUID):**
```
"a1b2c3d4-5678-..." → CompletableFuture<ResponseMessage>
"d648b71a-530c-..." → CompletableFuture<ResponseMessage>
```

**Workflow:**
1. **Before sending:** Put `requestId → new CompletableFuture()` in map
2. **When response arrives:** Look up future by `requestId`, complete it
3. **Waiting thread:** Blocks on `future.get()` until completed

**Why CompletableFuture?**
- Asynchronous response handling
- Built-in timeout support
- Thread-safe completion from different thread

---

## 3. Operation Implementations

### 3.1 Initialization (Constructor)

```java
public UserApp(String rabbitMqHost, int rabbitMqPort) 
        throws IOException, TimeoutException {
    // 1. Create connection factory
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(rabbitMqHost);
    factory.setPort(rabbitMqPort);
    
    // 2. Establish connection
    connection = factory.newConnection();
    channel = connection.createChannel();
    
    // 3. Declare work queue (ensure it exists)
    channel.queueDeclare(QueueConfig.REQUESTS_QUEUE, true, false, false, null);
    
    // 4. Create client-specific response queue
    String queuePrefix = QueueConfig.CLIENT_QUEUE_PREFIX;
    String uniqueId = UUID.randomUUID().toString();
    clientQueue = queuePrefix + uniqueId;
    
    Map<String, Object> args = new HashMap<>();
    args.put("x-message-ttl", QueueConfig.CLIENT_QUEUE_TTL);
    args.put("x-expires", QueueConfig.CLIENT_QUEUE_TTL);
    
    channel.queueDeclare(clientQueue, false, true, true, args);
    
    // 5. Start consuming responses
    startResponseConsumer();
    
    logger.info("UserApp initialized. RabbitMQ: {}:{}, Client Queue: {}", 
                rabbitMqHost, rabbitMqPort, clientQueue);
}
```

**Step-by-Step:**

1. **Create ConnectionFactory:** Configure RabbitMQ server details
2. **Establish Connection:** Open TCP connection to broker
3. **Declare Work Queue:** Ensure `requests` queue exists (idempotent)
4. **Create Response Queue:** Unique queue for this client's responses
5. **Start Consumer:** Begin listening for responses in background

### 3.2 Response Consumer

```java
private void startResponseConsumer() throws IOException {
    DeliverCallback deliverCallback = (consumerTag, delivery) -> {
        try {
            // Parse response from JSON bytes
            ResponseMessage response = JsonUtil.parseResponse(delivery.getBody());
            logger.info("Received response: {}", response.getRequestId());
            
            // Find pending request by requestId
            CompletableFuture<ResponseMessage> future = 
                pendingRequests.remove(response.getRequestId());
            
            if (future != null) {
                // Complete the future (unblocks waiting thread)
                future.complete(response);
            } else {
                logger.warn("Received response for unknown request: {}", 
                           response.getRequestId());
            }
        } catch (Exception e) {
            logger.error("Error processing response", e);
        }
    };
    
    // Start consuming (auto-acknowledge)
    channel.basicConsume(clientQueue, true, deliverCallback, consumerTag -> {});
}
```

**How It Works:**

1. **Callback Registration:** RabbitMQ calls `deliverCallback` for each message
2. **Parse Response:** Deserialize JSON to ResponseMessage object
3. **Lookup Future:** Find CompletableFuture by requestId
4. **Complete Future:** Signal waiting thread that response arrived
5. **Remove from Map:** Clean up tracking (GC)

**Auto-Acknowledge:**
- `true` = RabbitMQ considers message delivered immediately
- Safe because responses are not critical (client will timeout)
- Simpler than manual acknowledgment for client

### 3.3 SEARCH Operation

```java
public SearchResultPayload search(List<String> substrings) throws Exception {
    // 1. Generate unique request ID
    String requestId = UUID.randomUUID().toString();
    
    // 2. Create payload
    SearchPayload payload = new SearchPayload(substrings);
    
    // 3. Create request message
    RequestMessage request = new RequestMessage(
        requestId, 
        RequestType.SEARCH, 
        clientQueue,  // Where to send response
        payload
    );
    
    // 4. Send and wait for response
    logger.info("Sending SEARCH request: {}", substrings);
    ResponseMessage response = sendRequestAndWait(request);
    
    // 5. Check status and extract payload
    if (response.getStatus() == ResponseStatus.OK) {
        return (SearchResultPayload) response.getPayload();
    } else {
        throw new RuntimeException("Search failed: " + response.getStatus());
    }
}
```

**Flow:**
```
UserApp                    RabbitMQ                    Worker
   │                          │                          │
   ├─(1) Create request───────┤                          │
   │   requestId=abc123       │                          │
   │   type=SEARCH            │                          │
   │   clientQueue=client-xyz │                          │
   │                          │                          │
   ├─(2) Publish to queue─────►│                          │
   │   [requests queue]        │                          │
   │                          │                          │
   ├─(3) Wait on future───────┐│                          │
   │   pendingRequests[abc123]││                          │
   │   = new CompletableFuture││                          │
   │   future.get(30s)        ││                          │
   │                          │├─(4) Deliver to worker────►│
   │                          ││                          │
   │                          ││                          ├─(5) Process
   │                          ││                          │
   │                          │◄─(6) Publish response─────┤
   │                          │   [client-xyz queue]      │
   │                          │                          │
   │◄─(7) Deliver response────┤                          │
   │   future.complete(resp)  │                          │
   │                          │                          │
   ├─(8) Return to caller◄────┘                          │
```

### 3.4 GET_FILE Operation

```java
public FileContentPayload getFile(String filename) throws Exception {
    String requestId = UUID.randomUUID().toString();
    FilePayload payload = new FilePayload(filename);
    RequestMessage request = new RequestMessage(
        requestId, RequestType.GET_FILE, clientQueue, payload);
    
    logger.info("Sending GET_FILE request: {}", filename);
    ResponseMessage response = sendRequestAndWait(request);
    
    if (response.getStatus() == ResponseStatus.OK) {
        return (FileContentPayload) response.getPayload();
    } else {
        throw new RuntimeException("Get file failed: " + response.getStatus());
    }
}
```

**Similar to SEARCH but:**
- Different RequestType and payload class
- May return `NOT_FOUND` status if file doesn't exist

### 3.5 GET_STATS Operation

```java
public StatisticsPayload getStats() throws Exception {
    String requestId = UUID.randomUUID().toString();
    
    // No payload needed for GET_STATS
    RequestMessage request = new RequestMessage(
        requestId, RequestType.GET_STATS, clientQueue, null);
    
    logger.info("Sending GET_STATS request");
    ResponseMessage response = sendRequestAndWait(request);
    
    if (response.getStatus() == ResponseStatus.OK) {
        return (StatisticsPayload) response.getPayload();
    } else {
        throw new RuntimeException("Get stats failed: " + response.getStatus());
    }
}
```

**Special Case:**
- Triggers leader election among workers
- Response comes from election winner (coordinator)
- May take longer than other operations (~3-5 seconds)

### 3.6 Core Send-and-Wait Logic

```java
private ResponseMessage sendRequestAndWait(RequestMessage request) throws Exception {
    // 1. Create future for this request
    CompletableFuture<ResponseMessage> future = new CompletableFuture<>();
    pendingRequests.put(request.getRequestId(), future);
    
    try {
        // 2. Serialize request to JSON bytes
        byte[] messageBytes = JsonUtil.toJsonBytes(request);
        
        // 3. Publish to work queue
        channel.basicPublish(
            "",                           // default exchange
            QueueConfig.REQUESTS_QUEUE,   // routing key (queue name)
            null,                         // properties
            messageBytes                  // message body
        );
        
        // 4. Wait for response with timeout
        return future.get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        
    } catch (java.util.concurrent.TimeoutException e) {
        // Timeout occurred - clean up and throw
        pendingRequests.remove(request.getRequestId());
        throw new RuntimeException(
            "Request timed out after " + RESPONSE_TIMEOUT_SECONDS + " seconds");
    }
}
```

**Concurrency:**
- Multiple threads can call this simultaneously
- Each gets its own CompletableFuture
- ConcurrentHashMap ensures thread-safe map access
- Response consumer runs in separate thread

---

## 4. Command-Line Interface

### 4.1 Main Method

```java
public static void main(String[] args) {
    // 1. Validate arguments
    if (args.length < 1) {
        printUsage();
        System.exit(1);
    }

    // 2. Parse command
    String command = args[0].toLowerCase();
    
    // 3. Get RabbitMQ configuration from environment
    String rabbitMqHost = System.getenv().getOrDefault(
        "RABBITMQ_HOST", QueueConfig.DEFAULT_RABBITMQ_HOST);
    int rabbitMqPort = Integer.parseInt(System.getenv().getOrDefault(
        "RABBITMQ_PORT", String.valueOf(QueueConfig.DEFAULT_RABBITMQ_PORT)));

    // 4. Execute command with try-with-resources
    try (UserApp app = new UserApp(rabbitMqHost, rabbitMqPort)) {
        switch (command) {
            case "search" -> handleSearchCommand(app, args);
            case "get-file" -> handleGetFileCommand(app, args);
            case "get-stats" -> handleGetStatsCommand(app);
            default -> {
                System.err.println("Unknown command: " + command);
                printUsage();
                System.exit(1);
            }
        }
    } catch (Exception e) {
        logger.error("Error executing command", e);
        System.err.println("Error: " + e.getMessage());
        System.exit(1);
    }
}
```

### 4.2 Search Command Handler

```java
case "search":
    if (args.length < 2) {
        System.err.println("Usage: search <substring1> [substring2 ...]");
        System.exit(1);
    }
    
    // Extract substrings from args[1] onwards
    List<String> substrings = Arrays.asList(
        Arrays.copyOfRange(args, 1, args.length));
    
    // Execute search
    SearchResultPayload searchResult = app.search(substrings);
    Map<String, String> results = searchResult.getResults();
    
    // Display results in required format (Anexo 2)
    System.out.println("Found " + results.size() + 
                      " email(s) containing all substrings\n");
    
    for (String filename : results.keySet()) {
        System.out.println("##:" + filename);  // Required format
        System.out.println(results.get(filename));
        System.out.println();
    }
    break;
```

**Example Usage:**
```bash
$ java -jar userapp.jar search "Docker" "GCP"
Found 1 email(s) containing all substrings

##:email017.txt
De: rodrigo.santiago@techteam.pt
Para: manuela.afonso@techteam.pt, antonio.silva@techteam.pt
...
```

**Output Format (Anexo 2 Requirement):**
- Prefix filename with `##:`
- Print full email content below
- Blank line between results

### 4.3 Get-File Command Handler

```java
case "get-file":
    if (args.length != 2) {
        System.err.println("Usage: get-file <filename>");
        System.exit(1);
    }
    
    String filename = args[1];
    FileContentPayload fileContent = app.getFile(filename);
    
    System.out.println("File: " + fileContent.getFilename());
    System.out.println("Content:");
    System.out.println(fileContent.getContent());
    break;
```

**Example Usage:**
```bash
$ java -jar userapp.jar get-file email017.txt
File: email017.txt
Content:
De: rodrigo.santiago@techteam.pt
...
```

### 4.4 Get-Stats Command Handler

```java
case "get-stats":
    StatisticsPayload stats = app.getStats();
    
    System.out.println("Statistics:");
    System.out.println("  Total Requests: " + stats.getTotalRequests());
    System.out.println("  Successful: " + stats.getSuccessfulRequests());
    System.out.println("  Failed: " + stats.getFailedRequests());
    break;
```

**Example Output:**
```bash
$ java -jar userapp.jar get-stats
Statistics:
  Total Requests: 16
  Successful: 13
  Failed: 0
```

---

## 5. Concurrency Handling

### 5.1 Thread Safety

**Components:**

1. **RabbitMQ Channel:** Thread-confined to main thread
   - All publish operations in main thread
   - Consumer callback in separate thread pool

2. **Pending Requests Map:** `ConcurrentHashMap`
   - Thread-safe puts and removes
   - Multiple threads can add/remove simultaneously

3. **CompletableFuture:** Thread-safe completion
   - Can be completed from consumer thread
   - Waited on from main thread

### 5.2 Concurrent Request Example

```java
// Thread 1
future1 = new CompletableFuture<>();
pendingRequests.put("req-1", future1);
channel.basicPublish(...);  // Send request 1
future1.get();  // Wait

// Thread 2 (simultaneously)
future2 = new CompletableFuture<>();
pendingRequests.put("req-2", future2);
channel.basicPublish(...);  // Send request 2
future2.get();  // Wait

// Consumer thread (receives both responses)
response1 arrives → pendingRequests.get("req-1").complete(response1)
response2 arrives → pendingRequests.get("req-2").complete(response2)
```

---

## 6. Error Handling

### 6.1 Error Types and Handling

| Error Type | Cause | Handler | User Message |
|------------|-------|---------|--------------|
| **Connection Error** | RabbitMQ unreachable | Constructor throws | "Error: Connection refused" |
| **Timeout** | No response in 30s | `sendRequestAndWait` | "Request timed out after 30 seconds" |
| **NOT_FOUND** | File doesn't exist | Operation method | "Get file failed: NOT_FOUND" |
| **ERROR Status** | Worker exception | Operation method | "Search failed: ERROR" |
| **Invalid Arguments** | Wrong CLI usage | `main` | Usage message + exit(1) |

### 6.2 Resource Cleanup

```java
@Override
public void close() throws IOException, TimeoutException {
    if (channel != null && channel.isOpen()) {
        channel.close();
    }
    if (connection != null && connection.isOpen()) {
        connection.close();
    }
    logger.info("UserApp closed");
}
```

**Try-with-Resources:**
```java
try (UserApp app = new UserApp(host, port)) {
    // Use app
}  // Automatically calls close()
```

**Benefits:**
- Guaranteed resource cleanup
- Even if exception occurs
- Closes channel before connection (proper order)

---

## 7. Configuration

### 7.1 Environment Variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `RABBITMQ_HOST` | `localhost` | RabbitMQ server hostname |
| `RABBITMQ_PORT` | `5672` | RabbitMQ AMQP port |

**Usage:**
```bash
# Local development (defaults)
java -jar userapp.jar search "test"

# Production (custom RabbitMQ)
RABBITMQ_HOST=10.128.0.2 RABBITMQ_PORT=5672 \
  java -jar userapp.jar search "test"
```

---

## 8. Building and Running

### 8.1 Maven Build

```bash
# Build with dependencies
mvn clean package

# Creates: userapp/target/userapp-1.0-SNAPSHOT-shaded.jar
```

### 8.2 Execution Examples

```bash
# Search for emails
java -jar userapp.jar search "Docker" "GCP" "Java 21"

# Get specific file
java -jar userapp.jar get-file email017.txt

# Get statistics
java -jar userapp.jar get-stats

# With custom RabbitMQ
RABBITMQ_HOST=192.168.1.100 java -jar userapp.jar get-stats
```

---

**Document Version:** 1.0  
**Last Updated:** December 9, 2025  
**Next:** [Part 4 - Worker Module](04-WORKER-MODULE.md)
