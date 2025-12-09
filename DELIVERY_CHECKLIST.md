# TPA2 - Delivery and Demonstration Checklist

**Course:** Computação Distribuída (Winter 2025-2026)  
**Delivery Deadline:** December 13, 2025, 23:59h  
**Demonstration:** December 15-19, 2025 (max. 10 minutes per group)  

---

## 📦 Delivery Requirements (Moodle Submission)

### ✅ Deliverable 1: Technical Report (PDF)

**Status:** ⚠️ **PENDING - TO BE CREATED**

#### Required Contents:
- [ ] **Objective:** Clear description of system purpose
- [ ] **Assumptions:** Development/configuration/execution prerequisites
- [ ] **Architecture:** System design and component interactions
- [ ] **Configuration:** Step-by-step execution instructions
- [ ] **Message Structure:** Description and justification of message formats
- [ ] **Election Algorithm:** Description of Worker consensus/election using:
  - [ ] Interaction diagrams
  - [ ] Pseudo-code
- [ ] **Conclusions:** Results, limitations, and future work
- [ ] **Appendices:** Configuration details and execution notes

#### ⚠️ Important Guidelines:
- ✅ **DO:** Focus on message structure and election algorithm with diagrams
- ✅ **DO:** Justify design decisions
- ✅ **DO:** Include specific configuration details
- ❌ **DON'T:** Generic theoretical introductions about technologies
- ❌ **DON'T:** Code transcriptions (use pseudo-code instead)

**Quality Note:** Report quality has significant weight in final evaluation.

---

### ✅ Deliverable 2: Source Code (ZIP)

**Status:** ✅ **READY**

#### Required Structure:
```
TPA2-SourceCode.zip
├── common/
│   ├── pom.xml
│   └── src/
├── userapp/
│   ├── pom.xml
│   └── src/
├── worker/
│   ├── pom.xml
│   └── src/
└── pom.xml (parent)
```

#### ✅ Checklist:
- [x] Include `pom.xml` files (parent + all modules)
- [x] Include `src/` directories with source code
- [x] **EXCLUDE** `target/` directories (JARs, compiled classes)
- [x] **EXCLUDE** IntelliJ-specific files (`.idea/`, `*.iml`)
- [x] **EXCLUDE** Maven build artifacts

#### ⚠️ Penalty Warning:
Not respecting this format may result in grade penalization.

**Current Project Structure Compliance:**
- ✅ Multi-module Maven project ready
- ✅ All source code in `src/` directories
- ⚠️ Must exclude `target/` when creating ZIP

---

## 🎯 Demonstration Requirements (10 minutes max)

### Pre-Demo Setup (Before Presentation)

**Status:** ⚠️ **PARTIAL - Needs GCP Deployment**

#### 1.1 GCP Infrastructure
- [ ] **3 VMs running** (tpa2-node1, tpa2-node2, tpa2-node3)
- [ ] **GlusterFS installed and configured** (3-node replica)
- [ ] **Spread Toolkit installed** ⚠️ **NOT IMPLEMENTED YET**
- [ ] **RabbitMQ container running** on one VM
- [ ] **Worker JARs deployed** to all 3 VMs

**Current Status:**
- ✅ GlusterFS scripts ready (`deploy/scripts/provision-base.sh`, `setup-gluster.sh`)
- ✅ Docker deployment tested locally
- ❌ Spread Toolkit not integrated
- ⚠️ GCP deployment not tested

#### 1.2 Local Setup
- [x] **UserApp JAR executable** (no IntelliJ dependency)
- [ ] **UserApp instances running** on group members' computers
- [x] **JAR files built** (`mvn clean package`)

**Current Status:**
- ✅ `userapp/target/userapp.jar` - executable, standalone
- ✅ `worker/target/worker.jar` - executable, standalone
- ✅ Tested locally with Docker

---

### Demonstration Script (10 minutes)

#### Step 2.1: Message Structure and Flow (2-3 minutes)

**Status:** ✅ **READY - Needs Diagrams**

##### Messages to Explain:

**a) UserApp ↔ RabbitMQ:**
```json
{
  "requestId": "uuid",
  "type": "SEARCH | GET_FILE | GET_STATS",
  "clientQueue": "client-<uuid>-resp",
  "payload": { ... }
}
```

**b) Worker → UserApp Response:**
```json
{
  "requestId": "uuid",
  "status": "OK | NOT_FOUND | ERROR",
  "type": "SEARCH_RESULT | FILE_CONTENT | STATISTICS",
  "payload": { ... }
}
```

**c) Workers ↔ Spread Group:**
⚠️ **NOT IMPLEMENTED - Required for Demo**
- Election messages
- Statistics aggregation messages
- Membership updates

**Preparation Needed:**
- [ ] Create interaction diagrams for report
- [ ] Prepare slides showing message flow
- [ ] Implement Spread integration

---

#### Step 2.2: Consensus/Election Algorithm (2-3 minutes)

**Status:** ❌ **NOT IMPLEMENTED - CRITICAL**

##### Required:
- [ ] **Algorithm description** (pseudo-code or diagram)
- [ ] **Demonstration** of worker election
- [ ] **Statistics synchronization** across workers

**Current Implementation:**
- ✅ RabbitMQ work queue (fair dispatch)
- ❌ No Spread-based consensus
- ❌ No leader election
- ⚠️ Statistics are local (per worker), not aggregated

**Action Required:**
1. Implement Spread Toolkit integration
2. Create leader election algorithm
3. Aggregate statistics across workers
4. Document algorithm in report

---

#### Step 2.3: Live Demonstration (5 minutes)

**Status:** ✅ **MOSTLY READY**

##### 2.3.1: Start RabbitMQ Container
**Command:**
```bash
docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management
```
**Status:** ✅ Tested and working

---

##### 2.3.2: Send Request Without Workers
**Command:**
```bash
java -jar userapp.jar search "gRPC em Java 21" "GCP"
```
**Expected:** Request times out (no workers available)  
**Status:** ✅ Ready (timeout implemented)

---

##### 2.3.3: Show Request in RabbitMQ Queue
**Action:** Open http://localhost:15672 (RabbitMQ Management UI)  
**Expected:** Show message in `email_search_queue`  
**Status:** ✅ Ready

---

##### 2.3.4: Launch 1 Worker and Show Response
**Command:**
```bash
java -jar worker.jar
```
**Expected:** UserApp receives response to pending request  
**Status:** ✅ Tested and working

---

##### 2.3.5: Launch 2-3 More Workers
**Commands:**
```bash
# On VM1
ssh tpa2-node1 'java -jar /home/ubuntu/worker.jar'

# On VM2
ssh tpa2-node2 'java -jar /home/ubuntu/worker.jar'

# On VM3
ssh tpa2-node3 'java -jar /home/ubuntu/worker.jar'
```
**Status:** ⚠️ Requires GCP deployment

---

##### 2.3.6: Multiple Search Requests + File Content
**Commands:**
```bash
# Search request
java -jar userapp.jar search "Docker" "GCP"

# Get file content
java -jar userapp.jar get-file email017.txt
```
**Expected:** Show email content containing the substrings  
**Status:** ✅ Tested and working

**Sample Output:**
```
##:email017.txt
De: rodrigo.santiago@techteam.pt
...
Próximos passos:
1. Integrar com RabbitMQ para mensageria assíncrona
2. Deploy em containers Docker
3. Testes nas VMs da plataforma GCP
...
```

---

##### 2.3.7: Simultaneous Statistics Requests (Multiple Computers)
**Commands:**
```bash
# Computer 1
java -jar userapp.jar get-stats

# Computer 2 (at the same time)
java -jar userapp.jar get-stats
```
**Expected:** Both show coherent, aggregated statistics  
**Status:** ⚠️ **REQUIRES SPREAD INTEGRATION**

**Current Behavior:** Each shows local worker stats (not global)

---

##### 2.3.8: Professor's Surprise Use Case
**Status:** ✅ **READY** (system is operational)

**Possible scenarios:**
- Search with different substrings ✅
- Request non-existent file ✅
- Multiple concurrent requests ✅
- Kill worker mid-request → message requeue ✅
- Check RabbitMQ queue status ✅

---

##### 2.3.9: Extra Capabilities to Demonstrate
**Current Strengths:**
- ✅ Case-insensitive search (Anexo 2)
- ✅ Exact output format from PDF (`##:filename`)
- ✅ Proper error handling (NOT_FOUND, ERROR statuses)
- ✅ Fair dispatch (QoS=1) across workers
- ✅ Message persistence and acknowledgment
- ✅ Docker deployment automation
- ✅ Comprehensive logging

---

## 📋 Current Status Summary

### ✅ COMPLETED (90%)
1. ✅ Multi-module Maven project (parent + 3 modules)
2. ✅ UserApp CLI with RabbitMQ integration
3. ✅ Worker with file search (Anexo 2 algorithm)
4. ✅ Message serialization (Gson, Anexo 4)
5. ✅ email017.txt test case (Anexo 1)
6. ✅ Case-insensitive search
7. ✅ Docker deployment (local testing)
8. ✅ GlusterFS deployment scripts (Anexo 3)
9. ✅ Error handling and edge cases
10. ✅ Documentation (README, QUICKSTART, PROJECT.md)

### ⚠️ PENDING (10%)
1. ❌ **Spread Toolkit integration** (CRITICAL for demo)
2. ❌ **Leader election algorithm** (CRITICAL for demo)
3. ❌ **Global statistics aggregation** (CRITICAL for demo)
4. ⚠️ **GCP deployment and testing**
5. ⚠️ **Technical report (PDF)** (CRITICAL for delivery)
6. ⚠️ **Interaction diagrams** for report
7. ⚠️ **Source code ZIP** (exclude target/)

---

## 🚨 CRITICAL ACTIONS REQUIRED

### Priority 1: For Demonstration (Must Have)
- [ ] **Implement Spread Toolkit integration**
  - Join Spread group
  - Send/receive multicast messages
  - Handle membership changes
- [ ] **Implement leader election algorithm**
  - Detect worker membership
  - Elect coordinator
  - Re-elect on failure
- [ ] **Implement global statistics aggregation**
  - Coordinator collects stats from all workers
  - Return aggregated results to UserApp
- [ ] **Test on GCP with 3 VMs**
  - Deploy GlusterFS
  - Deploy Workers
  - Test end-to-end

**Estimated Time:** 6-8 hours

---

### Priority 2: For Delivery (Must Have)
- [ ] **Create Technical Report (PDF)**
  - System objective and architecture
  - Message structure diagrams
  - Election algorithm pseudo-code
  - Configuration instructions
  - Conclusions
- [ ] **Create interaction diagrams**
  - UserApp ↔ RabbitMQ ↔ Worker
  - Worker ↔ Spread ↔ Worker
  - Election sequence diagram
- [ ] **Prepare source code ZIP**
  - Exclude `target/` directories
  - Exclude IntelliJ files
  - Test that it builds from scratch

**Estimated Time:** 4-6 hours

---

### Priority 3: For Demonstration (Nice to Have)
- [ ] Prepare demo slides (message flow, architecture)
- [ ] Test demo script timing (under 10 minutes)
- [ ] Prepare backup scenarios
- [ ] Test screen sharing on Teams

**Estimated Time:** 2-3 hours

---

## 📊 Compliance Matrix

| Requirement | Status | Evidence | Action |
|------------|--------|----------|--------|
| **Delivery: Technical Report** | ❌ Not Started | - | Create PDF with diagrams |
| **Delivery: Source Code ZIP** | ✅ Ready | pom.xml + src/ | Exclude target/ |
| **Demo: 3 GCP VMs** | ⚠️ Scripts Ready | provision-base.sh | Deploy and test |
| **Demo: GlusterFS** | ⚠️ Scripts Ready | setup-gluster.sh | Deploy and test |
| **Demo: Spread Toolkit** | ❌ Not Implemented | - | Integrate and test |
| **Demo: RabbitMQ Container** | ✅ Working | Docker tested | Deploy to GCP |
| **Demo: UserApp JAR** | ✅ Working | userapp.jar | Test on multiple PCs |
| **Demo: Message Structure** | ✅ Implemented | JsonUtil.java | Create diagrams |
| **Demo: Election Algorithm** | ❌ Not Implemented | - | **CRITICAL** |
| **Demo: Search Demo** | ✅ Working | Tested locally | Test on GCP |
| **Demo: Statistics Demo** | ⚠️ Partial | Local stats only | Need Spread |
| **Demo: Simultaneous Requests** | ✅ Working | Tested | Test with Spread |

---

## 📅 Recommended Timeline

### Week of December 9-13 (Before Delivery)

**Monday-Tuesday (Dec 9-10):**
- [ ] Implement Spread Toolkit integration (6 hours)
- [ ] Implement leader election algorithm (4 hours)
- [ ] Implement global statistics (2 hours)

**Wednesday (Dec 11):**
- [ ] Deploy to GCP and test (4 hours)
- [ ] Create interaction diagrams (2 hours)
- [ ] Start technical report (2 hours)

**Thursday (Dec 12):**
- [ ] Complete technical report (4 hours)
- [ ] Prepare source code ZIP (1 hour)
- [ ] Test demo script (2 hours)

**Friday (Dec 13):**
- [ ] Final review and polish (2 hours)
- [ ] **Submit to Moodle before 23:59h** ✓

### Week of December 15-19 (Demonstration)
- [ ] Practice demo with team
- [ ] Prepare backup plans
- [ ] **Deliver 10-minute demonstration** ✓

---

## 🎓 Grading Considerations

### What Will Be Evaluated:
1. **Technical Report Quality** (HIGH WEIGHT)
   - Clarity of architecture description
   - Quality of message structure explanation
   - Quality of election algorithm description
   - Use of diagrams and pseudo-code

2. **Demonstration** (HIGH WEIGHT)
   - System functionality and operationality
   - Election algorithm demonstration
   - Handling of surprise use cases
   - Presentation quality

3. **Code Quality** (MEDIUM WEIGHT)
   - Clean, documented code
   - Proper error handling
   - Adherence to specifications

### What Will Cause Penalties:
- ❌ Incorrect ZIP format (including target/ or .idea/)
- ❌ Missing functionality during demo
- ❌ Poor report quality (generic content, code transcriptions)
- ❌ Non-operational system

---

## ✅ Final Checklist

### Before Delivery (Dec 13, 23:59h):
- [ ] Technical report PDF completed
- [ ] Source code ZIP created (correct format)
- [ ] Both files submitted to Moodle
- [ ] Confirmation email received

### Before Demonstration (Dec 15-19):
- [ ] 3 GCP VMs running and configured
- [ ] GlusterFS operational
- [ ] Spread Toolkit operational
- [ ] RabbitMQ container running
- [ ] Worker JARs deployed to VMs
- [ ] UserApp JARs on group members' computers
- [ ] Demo script practiced (under 10 min)
- [ ] Screen sharing tested on Teams
- [ ] Backup scenarios prepared

---

## 📞 Contact Points

**Questions about requirements:** Check Moodle announcements  
**Technical issues:** Test well in advance  
**Demo scheduling:** Will be announced per class  

---

**Document Status:** Created December 9, 2025  
**Last Updated:** December 9, 2025  
**Project Status:** 90% Complete - Spread Toolkit integration CRITICAL
