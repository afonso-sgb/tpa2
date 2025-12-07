# TPA2 — AI Agent Plan (Machine-actionable, step-by-step)

**Status: IMPLEMENTATION COMPLETE** ✅

**Purpose.** This document is a machine-actionable plan (markdown) an AI agent can follow to implement, test and deliver the TPA2 distributed system project from start to finish, meeting the functional and non-functional requirements in the assignment. It translates the PDF assignment into ordered tasks, code scaffolding, deployment steps and the final deliverables.

## Implementation Status

✅ **COMPLETED:**
- Multi-module Maven project structure (parent, common, userapp, worker)
- Common module with message models and JSON utilities
- UserApp CLI with RabbitMQ integration
- Worker service with file search capabilities
- Docker deployment files (Dockerfiles, docker-compose.yml)
- GCP deployment scripts (provision, setup-gluster, deploy)
- Comprehensive documentation (README, QUICKSTART)
- Sample email files for testing

📋 **TO BE COMPLETED:**
- Spread toolkit integration for consensus/election
- Full distributed statistics aggregation
- Production GCP deployment and testing
- Final report and presentation

---

## 0. Inputs & references

* Assignment specification and annexes (EmailFiles.zip, examples, Gluster/Spread/Gson guidance).
* Repository with starter code (if any) and the 20 email `.txt` files.
* Assumed environment: GCP VMs (3 nodes), Ubuntu 24 LTS, Java 21, Docker, Spread toolkit, GlusterFS, RabbitMQ container.

> The AI agent **must** operate under the constraints given in the assignment: use RabbitMQ (broker), Spread (group/membership), GlusterFS (replicated files), Java 21 for UserApp & Worker, and run on 3 GCP VMs.

---

## 1. Key assumptions (explicit)

1. There will be exactly 3 VM nodes available for the demo (tpa2-node1..3). Each node has same base image.
2. The initial Gluster volume contains the provided 20 `.txt` email files.
3. The Student group will run RabbitMQ in a single VM as a Docker container; Workers can be distributed.
4. Network configuration (internal IPs, /etc/hosts) will be configured by the provisioning scripts.
5. The agent can create Docker images and copy files to VMs using SSH / gcloud commands.

---

## 2. High level architecture (developer view)

1. **UserApp** (Java 21): CLI or simple GUI that publishes requests to RabbitMQ and consumes replies from dedicated response queue.
2. **RabbitMQ Broker** (Docker): Receives request messages on a `requests` queue; Workers consume from this queue. Replies are posted to response queues supplied by the UserApp (per-request or per-client queue).
3. **Worker** (Java 21): Consumes request messages (work-queue pattern), processes them by searching files on the mounted GlusterFS directory and sends back responses. Maintains local counters for success/fail requests.
4. **Spread Group**: All Workers join the same Spread group to exchange multicast messages and membership updates. Used to run the simple consensus/election for the statistics coordinator.
5. **GlusterFS Volume**: Shared mount `/var/sharedfiles` replicated across nodes where Worker processes look for `.txt` files.

---

## 3. Message formats (JSON via Gson)

Use Gson to serialize/deserialize JSON into byte[] for RabbitMQ/Spread.

### 3.1 Request message (from UserApp -> Broker)

```json
{
  "requestId": "uuid",
  "type": "SEARCH" | "GET_FILE" | "GET_STATS",
  "clientQueue": "client-<uuid>-resp",
  "payload": { ... }
}
```

* `SEARCH` payload: `{ "substrings": ["A","B","C"] }`
* `GET_FILE` payload: `{ "filename": "email017.txt" }`
* `GET_STATS` payload: `{ }
  `

### 3.2 Response message (from Worker -> Broker -> UserApp queue)

```json
{
  "requestId": "uuid",
  "status": "OK" | "NOT_FOUND" | "ERROR",
  "type": "SEARCH_RESULT" | "FILE_CONTENT" | "STATISTICS",
  "payload": { ... }
}
```

* `SEARCH_RESULT`: `{ "filenames": ["/var/sharedfiles/email017.txt"] }`
* `FILE_CONTENT`: `{ "filename":"...", "content":"..." }`
* `STATISTICS`: `{ "totalRequests": 123, "successfulRequests": 80, "failedRequests": 43 }
  `

---

## 4. Consensus / election algorithm (simple, Spread-based)

**Goal:** determine coordinator to collect partial stats and reply to GET_STATS.

**Algorithm (recommended & simple):**

1. Each Worker joins Spread group and publishes a presence message `{ "workerId": "id", "uptime": <ms>, "counter": <localSeq> }` on join.
2. On GET_STATS request, the Worker that received the request multicasts a `STATS_ELECTION` message with a monotonically increasing `electionEpoch` and its `workerId`.
3. On receiving `STATS_ELECTION`, Workers compare (epoch, uptime, workerId) and reply with `ACCEPT` if they agree to let the originator be coordinator, or `VOTE` for the worker with highest priority.
4. Tie-breaker: highest uptime, then lexicographic workerId.
5. If originator receives votes from all active members within a timeout, it becomes coordinator and multicasts `COORDINATOR_ANNOUNCE` and then polls each worker for partial stats via point-to-point Spread messages or rely on members to push partial stats to coordinator.
6. Coordinator aggregates partial stats and sends consolidated `STATISTICS` response to the original `clientQueue` via RabbitMQ.

*Notes:* keep the algorithm simple and well-documented. Simulate failures by forcing a worker to stop and verify election works.

---

## 5. Implementation plan — scaffolding & modules

Create a Maven multi-module project (or independent modules) with the following modules:

* `common` — POJOs for messages, Gson utils, config.
* `userapp` — CLI client to publish requests, wait for responses.
* `worker` — worker server, Spread client, local counters, file search logic.
* `deploy` — Dockerfiles, scripts, Terraform/gcloud scripts (optional) and deployment helpers.

Each Java module: `src/main/java`, `pom.xml` targeting Java 21, shaded/assembly plugin to create runnable jars for local testing (but **do not** include JARs in final zip submission).

---

## 6. Concrete coding tasks

**6.1 `common`**

* Message classes: `RequestMessage`, `ResponseMessage`, `SearchPayload`, `FilePayload`, `StatsPayload`.
* Gson serializer/deserializer helper.
* Config loader reading args/env.

**6.2 `userapp`**

* CLI options: `search`, `get-file`, `get-stats`.
* Create ephemeral response queue per client (e.g., `client-<uuid>-resp`) with TTL; subscribe to it and correlate by `requestId`.
* Publish a `RequestMessage` to `requests` queue with `replyTo` set to client queue name.

**6.3 `worker`**

* RabbitMQ consumer that listens on `requests` queue (work-queue pattern).
* For `SEARCH`: implement efficient file scanning using `Files.list` and streaming; optimize for many files by caching file list modification timestamps and using small worker-thread pool.
* For `GET_FILE`: read requested file and return content.
* For `GET_STATS`: start election as per algorithm and respond with aggregate via RabbitMQ.
* Maintain local counters and persist to disk in `/var/sharedfiles/worker-state-<id>.json` periodically to survive restarts (optional but recommended).
* Spread client integration: join group, send/receive multicast messages for membership & election.

**6.4 Docker & packaging**

* `Dockerfile.worker`: base `eclipse-temurin:21-jre`, copy `worker.jar`, configure entrypoint to pass required args (RabbitMQ host/port, workQueue, Spread config, gluster mount path).
* `Dockerfile.userapp` (optional): lightweight image for demo.
* Compose file (optional) for local testing: rabbitmq, one worker, userapp.

---

## 7. GCP + Gluster + RabbitMQ provisioning (automation)

**Manual quick steps (for reproducibility)**

1. Create base VM image with Ubuntu 24 LTS and required installs (script `scripts/provision-base.sh`): apt update, install openjdk-21, docker, gcc, build-essential, python3, pip.
2. Install Spread (compile from sources) using lab instructions.
3. Install GlusterFS server, create brick and volume (see Anexo 3 commands). Script `scripts/setup-gluster.sh` runs probe, create volume replica 3, start volume, mount `/var/sharedfiles`.
4. On one VM run RabbitMQ container: `docker run -d --hostname rabbit --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management` (adjust ports to internal IPs; prefer internal network only).

**Automation option:** small Terraform + `gcloud compute ssh` + `gcloud compute scp` invoked by `scripts/deploy.sh`.

---

## 8. Testing strategy

1. **Unit tests**: message serialization, search logic functions, containsAllSubstrings.
2. **Integration tests (local)**: run a local RabbitMQ container + single worker + userapp using Docker Compose.
3. **System tests (GCP)**: deploy on 3 VMs, start RabbitMQ on node1, start 3 workers on nodes, run UserApp from local machine or VM to submit:

   * multiple concurrent SEARCH requests (threads) and verify results + measure latency.
   * GET_FILE for an existing file and a non-existing file.
   * GET_STATS requesting aggregated numbers while injecting some failed searches.
4. **Failure tests**: kill one worker during stats election; verify election recovers.

Automated test harness: `scripts/run-tests.sh` that executes the three categories and writes a results report.

---

## 9. Risk assessment & mitigations

* **Gluster replication delays / mount issues** — Mitigate: test Gluster early; run simple replication checks; include fallback: copy files to each node for demo if Gluster fails.
* **Spread compilation issues on Ubuntu 24** — Mitigate: compile locally and include built binaries in deploy if needed.
* **RabbitMQ container networking** — Use internal GCP network and confirm port mappings; prefer internal IPs for inter-VM communication.

---

## 10. Useful commands & snippets (quick reference)

* Start RabbitMQ with management UI:

```bash
docker run -d --hostname rabbit --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management
```

* Create Gluster peers (run on tpa2-node1):

```bash
sudo gluster peer probe tpa2-node2
sudo gluster peer probe tpa2-node3
sudo gluster volume create glustervol replica 3 tpa2-node1:/var/gluster/brick tpa2-node2:/var/gluster/brick tpa2-node3:/var/gluster/brick force
sudo gluster volume start glustervol
sudo mount -t glusterfs tpa2-node1:/glustervol /var/sharedfiles
```

---

## 11. Deliverables the agent will produce (concrete files)

* `TPA2_AI-Agent-Plan.md` (this document)
* Repo: `common`, `userapp`, `worker`, `deploy` with code and `pom.xml` files
* Dockerfiles and `docker-compose.yml` for local testing
* Scripts: `provision-base.sh`, `setup-gluster.sh`, `deploy.sh`, `run-tests.sh`
* `report.pdf` and `slides.pdf`
* Moodle ZIP package

---

## 12. Final notes for the AI agent

* Do **not** change core required technologies (RabbitMQ, Gluster, Spread, Java 21) without professor approval.
* Parameterize all network addresses and ports via command line or environment variables.
* Write clear README and inline comments explaining design choices and how to run the demo.

---

*End of plan.*
