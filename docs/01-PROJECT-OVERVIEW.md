# TPA2 - Distributed Email Search System
## Technical Documentation Part 1: Project Overview and Architecture

**Course:** Computação Distribuída (Winter 2025-2026)  
**Authors:** [Your Team]  
**Date:** December 2025

---

## Table of Contents

1. [Project Objective](#project-objective)
2. [System Architecture](#system-architecture)
3. [Key Technologies](#key-technologies)
4. [Design Decisions](#design-decisions)
5. [Message Flow Overview](#message-flow-overview)

---

## 1. Project Objective

### 1.1 Problem Statement

The goal of this project is to build a **distributed email search system** that allows multiple clients to search through email files stored in a distributed filesystem, retrieve file contents, and obtain system-wide statistics. The system must:

- **Handle concurrent requests** from multiple user applications
- **Distribute workload** across multiple worker nodes
- **Provide fault tolerance** through message queuing and acknowledgment
- **Aggregate statistics** from all workers using distributed consensus
- **Scale horizontally** by adding more worker nodes

### 1.2 Core Requirements

1. **SEARCH Operation:** Find all email files containing specified substrings (case-insensitive)
2. **GET_FILE Operation:** Retrieve the full content of a specific email file
3. **GET_STATS Operation:** Return aggregated statistics from all workers (total requests, successful, failed)
4. **Distributed Storage:** Use GlusterFS for replicated file storage across multiple nodes
5. **Message Broker:** Use RabbitMQ for reliable message passing between components
6. **Consensus Algorithm:** Implement leader election using Spread Toolkit for statistics aggregation

### 1.3 Success Criteria

- Multiple UserApp instances can send requests simultaneously
- Workers process requests fairly (round-robin distribution)
- Statistics reflect the actual global state across all workers
- System handles worker failures gracefully (message requeue)
- Responses arrive within reasonable time (< 30 seconds)

---

## 2. System Architecture

### 2.1 High-Level Architecture

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  UserApp 1  │     │  UserApp 2  │     │  UserApp N  │
│  (Client)   │     │  (Client)   │     │  (Client)   │
└──────┬──────┘     └──────┬──────┘     └──────┬──────┘
       │                   │                   │
       └───────────────────┼───────────────────┘
                           │
                           ▼
                  ┌────────────────┐
                  │   RabbitMQ     │
                  │   (Broker)     │
                  └────────┬───────┘
                           │
       ┌───────────────────┼───────────────────┐
       │                   │                   │
       ▼                   ▼                   ▼
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Worker 1   │◄───►│  Worker 2   │◄───►│  Worker 3   │
│             │     │             │     │             │
└──────┬──────┘     └──────┬──────┘     └──────┬──────┘
       │                   │                   │
       └───────────────────┼───────────────────┘
                           │
                           ▼
                  ┌────────────────┐
                  │   GlusterFS    │
                  │ (Shared Files) │
                  └────────────────┘

       Spread Group Communication (via RabbitMQ)
       ◄─────────────────────────────────────────►
```

### 2.2 Component Descriptions

#### **UserApp (Client Application)**
- **Purpose:** Command-line interface for users to interact with the system
- **Responsibilities:**
  - Parse user commands (search, get-file, get-stats)
  - Send requests to RabbitMQ work queue
  - Create temporary response queue for receiving results
  - Wait for responses with timeout (30 seconds)
  - Display results to the user

#### **RabbitMQ (Message Broker)**
- **Purpose:** Central message routing and queuing system
- **Responsibilities:**
  - Maintain work queue (`requests`) for distributing tasks
  - Route responses to client-specific queues
  - Provide message persistence and acknowledgment
  - Enable fair dispatch (QoS=1) to prevent worker overload
  - Support Spread simulation via topic exchange

#### **Worker (Processing Nodes)**
- **Purpose:** Process search requests and manage distributed statistics
- **Responsibilities:**
  - Consume requests from RabbitMQ work queue
  - Search files in GlusterFS using efficient algorithms
  - Send responses back to client queues
  - Participate in leader election for statistics
  - Track local request statistics

#### **GlusterFS (Distributed Filesystem)**
- **Purpose:** Replicated storage for email files
- **Responsibilities:**
  - Provide consistent view of files across all workers
  - Handle file replication (3-node replica)
  - Enable concurrent read access from multiple workers

#### **Spread Simulator (Group Communication)**
- **Purpose:** Enable worker-to-worker communication for consensus
- **Responsibilities:**
  - Multicast messages to all workers
  - Point-to-point messaging between specific workers
  - Track group membership
  - Support leader election algorithm

---

## 3. Key Technologies

### 3.1 Technology Stack

| Component | Technology | Version | Purpose |
|-----------|-----------|---------|---------|
| **Programming Language** | Java | 21 | Main implementation language |
| **Build Tool** | Maven | 3.9+ | Dependency management and build |
| **Message Broker** | RabbitMQ | 3-management | Asynchronous message passing |
| **Distributed FS** | GlusterFS | Latest | Replicated file storage |
| **Logging** | SLF4J + Logback | Latest | Structured logging |
| **JSON Serialization** | Gson | 2.10.1 | Message serialization |
| **Containerization** | Docker | Latest | Deployment and isolation |
| **Cloud Platform** | GCP (Compute Engine) | N/A | Production deployment |

### 3.2 Why These Technologies?

#### **RabbitMQ**
- ✅ Built-in work queue pattern with fair dispatch
- ✅ Message persistence and acknowledgment for reliability
- ✅ Topic exchange for simulating Spread Toolkit
- ✅ Easy to set up and manage
- ✅ Excellent Java client library

#### **GlusterFS**
- ✅ POSIX-compatible (works with standard file I/O)
- ✅ Automatic replication across nodes
- ✅ No single point of failure
- ✅ Scales horizontally
- ✅ Simple setup and maintenance

#### **Java 21**
- ✅ Strong concurrency support (threads, executors)
- ✅ Excellent ecosystem and libraries
- ✅ Virtual threads for handling many connections
- ✅ Familiar to team members
- ✅ Production-ready and stable

---

## 4. Design Decisions

### 4.1 Multi-Module Maven Project

**Decision:** Organize code into three Maven modules: `common`, `userapp`, `worker`

**Rationale:**
- **Separation of Concerns:** Common code (models, utilities) shared across modules
- **Independent Deployment:** UserApp and Worker can be deployed separately
- **Cleaner Dependencies:** Each module declares only what it needs
- **Easier Testing:** Can test modules in isolation

**Structure:**
```
tpa2/
├── pom.xml              # Parent POM
├── common/              # Shared models and utilities
│   ├── pom.xml
│   └── src/main/java/
├── userapp/             # Client application
│   ├── pom.xml
│   └── src/main/java/
└── worker/              # Worker processing nodes
    ├── pom.xml
    └── src/main/java/
```

### 4.2 Request-Response Pattern

**Decision:** Use asynchronous request-response with correlation IDs

**Rationale:**
- **Scalability:** Clients don't block waiting for workers
- **Fault Tolerance:** If a worker fails, message can be requeued
- **Flexibility:** Supports multiple concurrent requests per client
- **Standard Pattern:** Well-documented RabbitMQ pattern

**Implementation:**
1. Client generates unique `requestId` (UUID)
2. Client creates temporary response queue (`client-<uuid>-resp`)
3. Client publishes request to work queue with `requestId` and `clientQueue`
4. Worker processes request and publishes response to `clientQueue`
5. Client matches response using `requestId`

### 4.3 Spread Toolkit Simulation

**Decision:** Simulate Spread using RabbitMQ topic exchange instead of real Spread

**Rationale:**
- **Simplicity:** Avoid complex Spread installation and configuration
- **Portability:** Works anywhere RabbitMQ runs (Docker, cloud, local)
- **Functionality:** Topic exchange provides equivalent multicast and P2P messaging
- **Testing:** Easier to test and debug than actual Spread
- **Consistency:** Uses same infrastructure as work queues

**Implementation:**
- **Multicast:** Publish to `multicast.all` routing key → all workers receive
- **Point-to-Point:** Publish to `p2p.<workerId>` routing key → specific worker receives
- **Membership:** Track active workers via presence announcements

### 4.4 Leader Election Algorithm

**Decision:** Priority-based election with uptime as primary criterion

**Rationale:**
- **Fairness:** Longest-running worker likely has most stable state
- **Determinism:** All workers agree on same coordinator given same inputs
- **Simplicity:** No complex consensus protocols needed
- **Efficiency:** Election completes in ~3 seconds

**Algorithm:**
```
Priority Comparison:
1. If uptime1 > uptime2 → candidate1 wins
2. If uptime1 < uptime2 → candidate2 wins
3. If uptime1 == uptime2 → lexicographic comparison of workerIds

Election Process:
1. Initiator multicasts STATS_ELECTION(epoch, uptime, workerId)
2. All workers vote for highest-priority candidate
3. Initiator counts votes, multicasts COORDINATOR_ANNOUNCE(winner)
4. Winner collects stats from all workers and responds to client
```

---

## 5. Message Flow Overview

### 5.1 SEARCH Request Flow

```
UserApp                RabbitMQ              Worker             GlusterFS
   │                      │                    │                   │
   ├─(1) SEARCH req──────►│                    │                   │
   │    [requests queue]  │                    │                   │
   │                      ├─(2) Deliver────────►│                   │
   │                      │    [QoS=1]         │                   │
   │                      │                    ├─(3) Read files────►│
   │                      │                    │◄─(4) File content─┤
   │                      │                    │                   │
   │                      │◄─(5) SEARCH resp──┤                   │
   │◄─(6) Response───────┤    [client queue]  │                   │
   │    [matched files]   │                    ├─(7) ACK──────────►│
   │                      │                    │                   │
```

### 5.2 GET_STATS Request Flow (with Election)

```
UserApp     RabbitMQ      Worker1       Worker2       Worker3
   │           │             │             │             │
   ├─STATS────►│             │             │             │
   │           ├─Deliver─────►│             │             │
   │           │             ├─ELECTION────►│             │
   │           │             ├─────────────►├─────────────►│
   │           │             │◄─VOTE────────┤             │
   │           │             │◄─────────────────VOTE──────┤
   │           │             ├─ANNOUNCE─────►│             │
   │           │             ├─────────────►├─────────────►│
   │           │             │ (Winner=W1)  │             │
   │           │             ├─STATS_REQ────►│             │
   │           │             ├─────────────►├─STATS_REQ───►│
   │           │             │◄─STATS_RESP──┤             │
   │           │             │◄─────────────────STATS_RESP┤
   │           │             ├─Aggregate────┐             │
   │           │             │              │             │
   │           │◄─Response───┤◄─────────────┘             │
   │◄──Result──┤             │                            │
```

**Steps:**
1. Worker1 receives GET_STATS request
2. Worker1 initiates election, multicasts to all workers
3. All workers vote for coordinator (highest uptime)
4. Worker1 multicasts winner announcement
5. Winner requests stats from all workers (P2P)
6. Winner aggregates stats and responds to client

---

## 6. Key Features

### 6.1 Fair Dispatch
- **QoS Setting:** Each worker processes max 1 message at a time
- **Benefit:** Prevents fast workers from hogging all requests
- **Implementation:** `channel.basicQos(1)` in Worker initialization

### 6.2 Message Persistence
- **Durable Queues:** Work queue survives RabbitMQ restarts
- **Acknowledgment:** Workers acknowledge after successful processing
- **Requeue on Failure:** Failed messages return to queue for retry

### 6.3 Case-Insensitive Search
- **Requirement:** Search must find matches regardless of case
- **Implementation:** Convert both content and search terms to lowercase
- **Example:** "Docker" matches "docker", "DOCKER", "Docker"

### 6.4 Concurrent Request Handling
- **UserApp:** Uses `CompletableFuture` to track multiple pending requests
- **Workers:** Process requests independently with thread pools
- **Thread Safety:** All shared state protected by concurrent data structures

---

## Next Documentation Parts

- **Part 2:** Detailed code explanation for `common` module
- **Part 3:** UserApp implementation details
- **Part 4:** Worker implementation details
- **Part 5:** Spread simulation and election algorithm
- **Part 6:** Deployment and testing

---

**Document Version:** 1.0  
**Last Updated:** December 9, 2025
