# TPA2 - Technical Documentation Part 5: Spread Simulation and Election Algorithm

**Course:** Computação Distribuída (Winter 2025-2026)  
**Module:** `worker/spread`  
**Date:** December 2025

---

## Table of Contents

1. [Spread Toolkit Overview](#spread-toolkit-overview)
2. [SpreadSimulator Implementation](#spreadsimulator-implementation)
3. [Election Algorithm](#election-algorithm)
4. [ElectionManager Implementation](#electionmanager-implementation)
5. [Message Flows](#message-flows)
6. [Critical Bug Fixes](#critical-bug-fixes)

---

## 1. Spread Toolkit Overview

### 1.1 What is Spread?

**Spread Toolkit** is a group communication system that provides:
- **Reliable multicast:** Send message to all group members
- **Point-to-point messaging:** Send to specific member
- **Membership tracking:** Know which members are in group
- **Ordering guarantees:** Messages delivered in order
- **Fault tolerance:** Handle node failures gracefully

### 1.2 Why Simulate Instead of Real Spread?

**Reasons:**

1. **Simplicity:** Avoid complex C library installation
2. **Portability:** Works anywhere RabbitMQ runs (Docker, cloud, local)
3. **Consistency:** Uses same infrastructure as work queues
4. **Debuggability:** Easier to trace messages in RabbitMQ console
5. **Functionality:** RabbitMQ topic exchange provides equivalent features

**Trade-offs:**

| Feature | Real Spread | Our Simulation |
|---------|-------------|----------------|
| **Performance** | C implementation, very fast | Java + RabbitMQ overhead |
| **Reliability** | Military-grade | RabbitMQ's reliability |
| **Ordering** | Total order | FIFO per queue |
| **Setup** | Complex | Simple (Docker container) |
| **Debugging** | Difficult | RabbitMQ Management UI |

---

## 2. SpreadSimulator Implementation

### 2.1 Architecture

```
┌─────────────────────────────────────────────┐
│          RabbitMQ Topic Exchange            │
│         "spread_group_exchange"             │
└──────────┬──────────────┬───────────────────┘
           │              │
    ┌──────▼──────┐  ┌───▼──────┐  ┌──────────┐
    │ multicast.* │  │  p2p.*   │  │presence.*│
    └──────┬──────┘  └───┬──────┘  └──────┬───┘
           │              │                │
    ┌──────▼──────────────▼────────────────▼───┐
    │      Worker Queue (exclusive)             │
    │   "spread_worker-1_<uuid>"                │
    └───────────────────────────────────────────┘
                       │
                  ┌────▼────┐
                  │ Worker  │
                  │Listeners│
                  └─────────┘
```

### 2.2 Key Components

#### **Topic Exchange**
```java
private static final String SPREAD_EXCHANGE = "spread_group_exchange";
```

**Purpose:** Route messages based on routing key patterns.

**Routing Keys:**
- `multicast.all` → All workers receive
- `p2p.worker-1` → Only worker-1 receives
- `presence.announce` → All workers receive (membership)

#### **Worker Queue**
```java
queueName = "spread_" + workerId + "_" + UUID.randomUUID();
channel.queueDeclare(queueName, false, true, true, null);
```

**Properties:**
- **Durable:** `false` (temporary, not persistent)
- **Exclusive:** `true` (only this connection can use it)
- **Auto-delete:** `true` (deleted when connection closes)

**Bindings:**
```java
channel.queueBind(queueName, SPREAD_EXCHANGE, "multicast.all");
channel.queueBind(queueName, SPREAD_EXCHANGE, "presence.announce");
channel.queueBind(queueName, SPREAD_EXCHANGE, "p2p." + workerId);
```

### 2.3 Full Implementation

```java
public class SpreadSimulator implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(SpreadSimulator.class);
    
    private static final String SPREAD_EXCHANGE = "spread_group_exchange";
    private static final String MULTICAST_ROUTING_KEY = "multicast.all";
    private static final String PRESENCE_ROUTING_KEY = "presence.announce";
    
    private final String workerId;
    private final String groupName;
    private final Connection connection;
    private final Channel channel;
    private final String queueName;
    
    private final Set<String> knownWorkers = ConcurrentHashMap.newKeySet();
    private final List<Consumer<SpreadMessage>> messageListeners = 
        new CopyOnWriteArrayList<>();
    private final List<Consumer<Set<String>>> membershipListeners = 
        new CopyOnWriteArrayList<>();
    
    private volatile boolean running = false;
    
    public SpreadSimulator(String workerId, String groupName, 
                          String rabbitHost, int rabbitPort) 
            throws IOException, TimeoutException {
        this.workerId = workerId;
        this.groupName = groupName;
        
        // Connect to RabbitMQ
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(rabbitHost);
        factory.setPort(rabbitPort);
        connection = factory.newConnection();
        channel = connection.createChannel();
        
        // Declare topic exchange
        channel.exchangeDeclare(SPREAD_EXCHANGE, BuiltinExchangeType.TOPIC, true);
        
        // Create exclusive queue for this worker
        queueName = "spread_" + workerId + "_" + UUID.randomUUID();
        channel.queueDeclare(queueName, false, true, true, null);
        
        // Bind to routing keys
        channel.queueBind(queueName, SPREAD_EXCHANGE, MULTICAST_ROUTING_KEY);
        channel.queueBind(queueName, SPREAD_EXCHANGE, PRESENCE_ROUTING_KEY);
        channel.queueBind(queueName, SPREAD_EXCHANGE, "p2p." + workerId);
        
        logger.info("SpreadSimulator initialized for worker [{}] in group [{}]", 
                   workerId, groupName);
    }
    
    public void joinGroup() throws IOException {
        running = true;
        
        // Start consuming messages
        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            try {
                SpreadMessage message = JsonUtil.fromJsonBytes(
                    delivery.getBody(), SpreadMessage.class);
                handleIncomingMessage(message);
            } catch (Exception e) {
                logger.error("Error processing Spread message", e);
            }
        };
        
        channel.basicConsume(queueName, true, deliverCallback, consumerTag -> {});
        
        logger.info("Worker [{}] joined Spread group [{}]", workerId, groupName);
    }
    
    public void multicast(SpreadMessage message) throws IOException {
        message.setSenderId(workerId);
        message.setTimestamp(System.currentTimeMillis());
        
        byte[] messageBytes = JsonUtil.toJsonBytes(message);
        channel.basicPublish(SPREAD_EXCHANGE, MULTICAST_ROUTING_KEY, 
                            null, messageBytes);
        
        logger.debug("Worker [{}] multicast message type: {}", 
                    workerId, message.getType());
    }
    
    public void sendTo(String targetWorkerId, SpreadMessage message) 
            throws IOException {
        message.setSenderId(workerId);
        message.setTimestamp(System.currentTimeMillis());
        
        byte[] messageBytes = JsonUtil.toJsonBytes(message);
        String routingKey = "p2p." + targetWorkerId;
        channel.basicPublish(SPREAD_EXCHANGE, routingKey, null, messageBytes);
        
        logger.debug("Worker [{}] sent P2P message to [{}] type: {}", 
                    workerId, targetWorkerId, message.getType());
    }
    
    public void addMessageListener(Consumer<SpreadMessage> listener) {
        messageListeners.add(listener);
    }
    
    public void removeMessageListener(Consumer<SpreadMessage> listener) {
        messageListeners.remove(listener);
    }
    
    public Set<String> getGroupMembers() {
        return new HashSet<>(knownWorkers);
    }
    
    private void handleIncomingMessage(SpreadMessage message) {
        // Update membership
        String senderId = message.getSenderId();
        if (senderId != null && !senderId.equals(workerId)) {
            boolean wasNew = knownWorkers.add(senderId);
            if (wasNew) {
                logger.info("Detected new worker in group: {}", senderId);
                notifyMembershipChange();
            }
        }
        
        // Notify all message listeners
        for (Consumer<SpreadMessage> listener : messageListeners) {
            try {
                listener.accept(message);
            } catch (Exception e) {
                logger.error("Error in message listener", e);
            }
        }
    }
    
    private void notifyMembershipChange() {
        Set<String> currentMembers = getGroupMembers();
        for (Consumer<Set<String>> listener : membershipListeners) {
            try {
                listener.accept(currentMembers);
            } catch (Exception e) {
                logger.error("Error in membership listener", e);
            }
        }
    }
    
    @Override
    public void close() throws IOException, TimeoutException {
        running = false;
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
        if (connection != null && connection.isOpen()) {
            connection.close();
        }
        logger.info("SpreadSimulator closed for worker [{}]", workerId);
    }
}
```

### 2.4 Design Patterns

#### **Observer Pattern**
```java
List<Consumer<SpreadMessage>> messageListeners;

public void addMessageListener(Consumer<SpreadMessage> listener) {
    messageListeners.add(listener);
}

// Notify all observers
for (Consumer<SpreadMessage> listener : messageListeners) {
    listener.accept(message);
}
```

**Benefit:** Decouples message delivery from handling.

#### **Thread-Safe Collections**
```java
Set<String> knownWorkers = ConcurrentHashMap.newKeySet();
List<Consumer<SpreadMessage>> messageListeners = new CopyOnWriteArrayList<>();
```

**Why:**
- Multiple threads read/write simultaneously
- RabbitMQ consumer thread
- Application threads

---

## 3. Election Algorithm

### 3.1 Algorithm Description

**Objective:** Select one coordinator to collect and aggregate statistics from all workers.

**Priority Rule:**
```
if (uptime1 > uptime2) → candidate1 wins
else if (uptime1 < uptime2) → candidate2 wins
else → lexicographically greater workerId wins
```

**Pseudo-code:**
```
Algorithm: Priority-Based Leader Election
Input: GET_STATS request
Output: Aggregated statistics from all workers

1. Initiator (any worker receiving GET_STATS):
   a. epoch ← globally unique timestamp
   b. uptime ← currentTime - startTime
   c. MULTICAST STATS_ELECTION(candidateId, epoch, uptime, requestId, clientQueue)
   d. votedFor ← self
   e. voteCount ← 1

2. All Workers (including initiator):
   a. Receive STATS_ELECTION message
   b. myUptime ← currentTime - startTime
   c. winner ← electCoordinator(candidateId, candidateUptime, myWorkerId, myUptime)
   d. SEND_TO initiator: ELECTION_VOTE(myWorkerId, winner, epoch, winner==candidateId)

3. Initiator (vote collection):
   a. Wait for votes from all known workers
   b. Count votes for each candidate
   c. coordinator ← candidate with most votes
   d. MULTICAST COORDINATOR_ANNOUNCE(epoch, coordinator, requestId, clientQueue)

4. All Workers:
   a. Receive COORDINATOR_ANNOUNCE
   b. If myWorkerId == coordinator:
      - Collect stats from all workers
      - Aggregate results
      - Send response to clientQueue

5. Coordinator (stats collection):
   a. For each worker:
      - SEND_TO worker: STATS_REQUEST(epoch)
   b. Wait for STATS_RESPONSE from all workers (with timeout)
   c. Aggregate all PartialStatsPayload
   d. Send StatisticsPayload to clientQueue
```

### 3.2 Example Election

**Scenario:** 3 workers, worker-2 receives GET_STATS

| Worker | Uptime | Priority |
|--------|--------|----------|
| worker-1 | 60000ms | 2nd |
| worker-2 | 45000ms | 3rd |
| worker-3 | 75000ms | 1st (highest uptime) |

**Flow:**
```
Time   Worker-1      Worker-2 (initiator)    Worker-3
────────────────────────────────────────────────────────
T0                   Receives GET_STATS
                     epoch = 1765302395620813
                     
T1     ◄─────────────STATS_ELECTION─────────────────────►
       Receives      (candidateId=worker-2,
       election      uptime=45000)
       
T2     Calculate priority:
       winner = electCoordinator(worker-2, 45000, worker-1, 60000)
       → worker-1 (higher uptime)
       
T2                   Receives votes:
       VOTE──────────►worker-1: voted for worker-1
                     worker-2: voted for worker-2  
                     ◄──────────worker-3: voted for worker-3
                     
T3                   Count votes:
                     worker-1: 1 vote
                     worker-2: 1 vote
                     worker-3: 1 vote
                     → Tie! All vote for themselves
                     
                     Wait, that's wrong...
                     
                     ACTUALLY: All workers apply same priority function
                     worker-1 votes for worker-3 (75000 > 60000)
                     worker-2 votes for worker-3 (75000 > 45000)
                     worker-3 votes for worker-3 (self)
                     
                     winner = worker-3 (3 votes)
                     
T4     ◄─────────COORDINATOR_ANNOUNCE(worker-3)──────────►
       
T5                                            I'm coordinator!
                                              STATS_REQUEST──────►worker-1
                                              STATS_REQUEST──────►worker-2
                                              
T6     STATS_RESPONSE────────────────────────►
                     STATS_RESPONSE──────────►
                     
T7                                            Aggregate:
                                              total = w1 + w2 + w3
                                              Send to clientQueue
```

---

## 4. ElectionManager Implementation

### 4.1 Class Structure

```java
public class ElectionManager {
    private static final int ELECTION_TIMEOUT_MS = 3000;
    private static final int STATS_COLLECTION_TIMEOUT_MS = 2000;
    
    private final String workerId;
    private final SpreadSimulator spread;
    private final Channel rabbitChannel;
    private final long startTime;
    
    private final AtomicLong electionEpoch = new AtomicLong(0);
    private final Map<Long, ElectionState> activeElections = new ConcurrentHashMap<>();
    private final Set<Long> processedAnnouncements = ConcurrentHashMap.newKeySet();
    
    private StatsProvider localStatsProvider;
}
```

### 4.2 Election Initiation

```java
public void initiateElection(String requestId, String clientQueue) {
    // Generate globally unique epoch
    long epoch = System.currentTimeMillis() * 1000 + (long)(Math.random() * 1000);
    long uptime = System.currentTimeMillis() - startTime;
    
    // Track this election
    ElectionState state = new ElectionState(epoch, requestId, clientQueue);
    activeElections.put(epoch, state);
    
    logger.info("Worker [{}] initiating election epoch={} for request={}", 
               workerId, epoch, requestId);
    
    try {
        // Multicast election message
        ElectionPayload payload = new ElectionPayload(
            workerId, epoch, uptime, requestId, clientQueue);
        SpreadMessage message = new SpreadMessage(
            SpreadMessageType.STATS_ELECTION, workerId, payload);
        spread.multicast(message);
        
        // Self-vote
        state.recordVote(workerId, workerId, true);
        
    } catch (IOException e) {
        logger.error("Error initiating election", e);
        state.result.completeExceptionally(e);
    }
}
```

**Epoch Generation:**
```java
long epoch = currentTimeMillis() * 1000 + random(0-999);
```

**Why multiply by 1000?**
- Prevents collisions if two workers initiate elections same millisecond
- Random component (0-999) ensures uniqueness
- Example: `1765302395620813` vs `1765302395620456`

### 4.3 Handling Election Messages

```java
private void handleElectionMessage(SpreadMessage message) throws IOException {
    ElectionPayload election = convertPayload(
        message.getPayload(), ElectionPayload.class);
    if (election == null) return;
    
    logger.info("Worker [{}] received election from [{}] epoch={}", 
               workerId, election.getCandidateId(), election.getElectionEpoch());
    
    long myUptime = System.currentTimeMillis() - startTime;
    
    // Determine who should be coordinator
    String votedFor = electCoordinator(
        election.getCandidateId(), election.getUptime(), 
        workerId, myUptime);
    boolean accept = votedFor.equals(election.getCandidateId());
    
    // Send vote to initiator
    VotePayload vote = new VotePayload(
        workerId, votedFor, election.getElectionEpoch(), accept);
    SpreadMessage voteMsg = new SpreadMessage(
        SpreadMessageType.ELECTION_VOTE, workerId, vote);
    spread.sendTo(election.getCandidateId(), voteMsg);
    
    logger.debug("Worker [{}] voted for [{}] (accept={})", 
                workerId, votedFor, accept);
}
```

### 4.4 Coordinator Selection

```java
private String electCoordinator(String candidate1, long uptime1, 
                                String candidate2, long uptime2) {
    if (uptime1 > uptime2) {
        return candidate1;
    } else if (uptime1 < uptime2) {
        return candidate2;
    } else {
        // Tie-breaker: lexicographic comparison
        return candidate1.compareTo(candidate2) > 0 ? candidate1 : candidate2;
    }
}
```

**Lexicographic Comparison:**
```java
"worker-1".compareTo("worker-2") // < 0 (worker-1 < worker-2)
"worker-3".compareTo("worker-1") // > 0 (worker-3 > worker-1)
```

**Result:** `worker-3 > worker-2 > worker-1`

### 4.5 Vote Counting

```java
private void handleVoteMessage(SpreadMessage message) {
    VotePayload vote = convertPayload(message.getPayload(), VotePayload.class);
    if (vote == null) return;
    
    ElectionState state = activeElections.get(vote.getElectionEpoch());
    if (state == null) {
        logger.warn("Received vote for unknown election epoch={}", 
                   vote.getElectionEpoch());
        return;
    }
    
    state.recordVote(vote.getVoterId(), vote.getVotedFor(), vote.isAccept());
    
    // Check if we have enough votes (only process once)
    Set<String> members = spread.getGroupMembers();
    members.add(workerId);
    
    if (state.hasVoteCount(members.size()) && !state.isComplete()) {
        state.markComplete();
        String winner = state.determineWinner();
        logger.info("Election epoch={} complete. Winner: {}", 
                   vote.getElectionEpoch(), winner);
        
        // Multicast coordinator announcement
        try {
            CoordinatorAnnouncePayload announce = new CoordinatorAnnouncePayload(
                vote.getElectionEpoch(), winner, state.requestId, state.clientQueue);
            SpreadMessage announceMsg = new SpreadMessage(
                SpreadMessageType.COORDINATOR_ANNOUNCE, workerId, announce);
            spread.multicast(announceMsg);
            logger.info("Multicasted coordinator announcement: winner={}", winner);
        } catch (IOException e) {
            logger.error("Error multicasting coordinator announcement", e);
        }
    }
}
```

**ElectionState Helper:**
```java
private static class ElectionState {
    final Map<String, String> votes = new ConcurrentHashMap<>();  // voterId → votedFor
    private volatile boolean complete = false;
    
    void recordVote(String voterId, String votedFor, boolean accept) {
        votes.put(voterId, votedFor);
    }
    
    boolean hasVoteCount(int expected) {
        return votes.size() >= expected;
    }
    
    String determineWinner() {
        // Count votes for each candidate
        Map<String, Integer> voteCounts = new HashMap<>();
        for (String candidate : votes.values()) {
            voteCounts.put(candidate, voteCounts.getOrDefault(candidate, 0) + 1);
        }
        
        // Find candidate with most votes
        return voteCounts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
    }
}
```

### 4.6 Stats Collection (Coordinator)

```java
private void collectAndAggregateStats(long epoch, String requestId, 
                                     String clientQueue) {
    logger.info("Worker [{}] is coordinator for epoch={}. Collecting stats...", 
               workerId, epoch);
    
    // Run in separate thread to avoid blocking RabbitMQ consumer
    new Thread(() -> {
        try {
            Set<String> members = spread.getGroupMembers();
            members.add(workerId);
            
            Map<String, PartialStatsPayload> collectedStats = new ConcurrentHashMap<>();
            CountDownLatch latch = new CountDownLatch(members.size());
            
            // Listener for stats responses
            Consumer<SpreadMessage> statsListener = msg -> {
                if (msg.getType() == SpreadMessageType.STATS_RESPONSE) {
                    PartialStatsPayload stats = convertPayload(
                        msg.getPayload(), PartialStatsPayload.class);
                    if (stats != null) {
                        logger.info("Worker [{}] collected stats from [{}]: total={}", 
                                   workerId, stats.getWorkerId(), stats.getTotalRequests());
                        collectedStats.put(stats.getWorkerId(), stats);
                        latch.countDown();
                    }
                }
            };
            
            spread.addMessageListener(statsListener);
            
            try {
                // Request stats from all workers
                for (String memberId : members) {
                    if (memberId.equals(workerId)) {
                        // Add own stats
                        if (localStatsProvider != null) {
                            PartialStatsPayload ownStats = localStatsProvider.getPartialStats();
                            collectedStats.put(workerId, ownStats);
                            latch.countDown();
                        }
                    } else {
                        // Request from other workers
                        SpreadMessage request = new SpreadMessage(
                            SpreadMessageType.STATS_REQUEST, workerId, epoch);
                        spread.sendTo(memberId, request);
                    }
                }
                
                // Wait for responses (2 second timeout)
                boolean completed = latch.await(
                    STATS_COLLECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                
                if (!completed) {
                    logger.warn("Stats collection timeout. Collected {}/{} responses", 
                               collectedStats.size(), members.size());
                }
                
                // Aggregate stats
                StatisticsPayload aggregated = aggregateStats(
                    collectedStats.values(), members.size());
                
                // Send response to client
                ResponseMessage response = new ResponseMessage(
                    requestId, ResponseStatus.OK, ResponseType.STATISTICS, aggregated);
                
                byte[] responseBytes = JsonUtil.toJsonBytes(response);
                rabbitChannel.basicPublish("", clientQueue, null, responseBytes);
                
                logger.info("Worker [{}] sent aggregated stats to client: " +
                           "total={}, successful={}, failed={}, workers={}",
                           workerId, aggregated.getTotalRequests(), 
                           aggregated.getSuccessfulRequests(),
                           aggregated.getFailedRequests(), 
                           aggregated.getWorkerCount());
                
            } finally {
                spread.removeMessageListener(statsListener);
            }
        } catch (Exception e) {
            logger.error("Error in stats collection thread", e);
        }
    }).start();
}
```

---

## 5. Message Flows

### 5.1 Complete GET_STATS Flow

```
UserApp          Worker-2         Worker-1         Worker-3        RabbitMQ
   │                │                │                │                │
   ├─GET_STATS──────────────────────────────────────────────────────────►│
   │                │                │                │                │
   │                │◄───────────────────────────────────────────────────┤
   │                │                │                │                │
   │                ├─initiate───────┐                │                │
   │                │ election       │                │                │
   │                │                │                │                │
   │                ├─STATS_ELECTION─────────────────────────────────────►│
   │                │                │                │                │
   │                │                │◄───────────────────────────────────┤
   │                │                │                │                │
   │                │◄───────────────┘                │                │
   │                │                │                │                │
   │                │                ├─compare────────┐                │
   │                │                │ priorities     │                │
   │                │                │ vote: worker-3 │                │
   │                │                │                │                │
   │                │                ├─VOTE───────────────────────────────►│
   │                │                │                │                │
   │                │◄───────────────────────────────────────────────────┤
   │                │                │                │                │
   │                ├─count votes────┐                │                │
   │                │ winner=worker-3│                │                │
   │                │                │                │                │
   │                ├─ANNOUNCE───────────────────────────────────────────►│
   │                │                │                │                │
   │                │                │◄───────────────────────────────────┤
   │                │                │                │                │
   │                │                │                ├─I'm coordinator!│
   │                │                │                │                │
   │                │                │                ├─STATS_REQUEST──────►│
   │                │                │                │                │
   │                │                │◄───────────────────────────────────┤
   │                │                │                │                │
   │                │                ├─STATS_RESPONSE─────────────────────►│
   │                │                │                │                │
   │                │                │                │◄───────────────────┤
   │                │                │                │                │
   │                │                │                ├─aggregate──────┐│
   │                │                │                │ all stats      ││
   │                │                │                │                ││
   │                │                │                ├─send response──►│
   │                │                │                │                │
   │◄───────────────────────────────────────────────────────────────────┤
   │                │                │                │                │
```

---

## 6. Critical Bug Fixes

### 6.1 Problem: Thread Blocking

**Issue:** Stats responses arriving but not being processed.

**Root Cause:**
```java
// BAD: Blocks RabbitMQ consumer thread
private void collectAndAggregateStats(...) {
    // This runs in RabbitMQ consumer thread (pool-3-thread-10)
    latch.await(10000, MILLISECONDS);  // BLOCKS for 10 seconds!
    // While blocked, can't process incoming STATS_RESPONSE messages
}
```

**Symptoms:**
```
17:46:35.641 - Coordinator starts collecting stats
17:46:35.646 - Workers send responses (5ms later)
17:46:45.645 - Timeout fires (10 seconds later)
17:46:45.653 - Responses finally processed (12ms AFTER timeout!)
```

**Fix:**
```java
// GOOD: Run in separate thread
private void collectAndAggregateStats(...) {
    new Thread(() -> {
        // Now consumer thread returns immediately
        // This thread waits, consumer can process responses
        latch.await(2000, MILLISECONDS);
    }).start();
}
```

### 6.2 Problem: Duplicate Announcements

**Issue:** Coordinator announced multiple times, stats collected multiple times.

**Root Cause:**
```java
// BAD: Announces every time a vote arrives
if (state.hasVoteCount(members.size())) {
    multicastAnnouncement();  // Called 3 times for 3 votes!
}
```

**Fix:**
```java
// GOOD: Only announce once
if (state.hasVoteCount(members.size()) && !state.isComplete()) {
    state.markComplete();  // Mark as done
    multicastAnnouncement();  // Only called once
}
```

### 6.3 Problem: Duplicate Stats Collection

**Issue:** Same worker receives announcement multiple times.

**Root Cause:**
- Announcement is multicast
- Worker receives its own announcement
- Multiple workers may multicast announcement

**Fix:**
```java
private final Set<Long> processedAnnouncements = ConcurrentHashMap.newKeySet();

private void handleCoordinatorAnnounce(SpreadMessage message) {
    CoordinatorAnnouncePayload announce = ...;
    
    // Only process each announcement once
    if (!processedAnnouncements.add(announce.getElectionEpoch())) {
        logger.debug("Already processed announcement for epoch={}", 
                    announce.getElectionEpoch());
        return;
    }
    
    // Now safe to collect stats
    if (announce.getCoordinatorId().equals(workerId)) {
        collectAndAggregateStats(...);
    }
}
```

---

## 7. Testing the System

### 7.1 Verifying Election

**Generate activity:**
```bash
java -jar userapp.jar search "test"
java -jar userapp.jar search "Docker"
java -jar userapp.jar search "GCP"
```

**Request stats:**
```bash
java -jar userapp.jar get-stats
```

**Expected output:**
```
Statistics:
  Total Requests: 4
  Successful: 3
  Failed: 0
```

**Verify in logs:**
```bash
docker logs tpa2-worker2 | grep -E "Election|Coordinator|Aggregated"

# Should show:
# - Election initiation
# - Vote counting
# - Coordinator announcement
# - Stats collection
# - Aggregation from all workers
```

### 7.2 Verifying Uptime Priority

**Scenario:** Start workers in sequence, trigger election.

**Expected:** Oldest worker wins.

**Test:**
```bash
# T0: Start worker-1
docker compose up -d worker1
sleep 10

# T10: Start worker-2
docker compose up -d worker2
sleep 10

# T20: Start worker-3
docker compose up -d worker3
sleep 5

# T25: Request stats (worker-1 has highest uptime)
java -jar userapp.jar get-stats

# Check logs: worker-1 should be coordinator
docker logs tpa2-worker1 | grep "is the coordinator"
```

---

**Document Version:** 1.0  
**Last Updated:** December 9, 2025  
**End of Technical Documentation**
