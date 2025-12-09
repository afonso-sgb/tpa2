package pt.isel.cd.worker.spread;

import com.rabbitmq.client.Channel;
import pt.isel.cd.common.model.*;
import pt.isel.cd.common.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages leader election among workers using Spread group communication.
 * 
 * Algorithm (from structure.md):
 * 1. Worker receiving GET_STATS request initiates election
 * 2. Multicasts STATS_ELECTION with (epoch, uptime, workerId)
 * 3. Workers compare and vote for highest priority candidate
 * 4. Priority: highest uptime, then lexicographic workerId
 * 5. Candidate with majority becomes coordinator
 * 6. Coordinator collects partial stats and aggregates
 */
public class ElectionManager {
    private static final Logger logger = LoggerFactory.getLogger(ElectionManager.class);
    
    private static final int ELECTION_TIMEOUT_MS = 3000;  // 3 seconds for election
    private static final int STATS_COLLECTION_TIMEOUT_MS = 2000;  // 2 seconds to collect stats
    
    private final String workerId;
    private final SpreadAdapter spread;
    private final Channel rabbitChannel;
    private final long startTime;
    
    private final AtomicLong electionEpoch = new AtomicLong(0);
    private final Map<Long, ElectionState> activeElections = new ConcurrentHashMap<>();
    private final Set<Long> processedAnnouncements = ConcurrentHashMap.newKeySet();
    
    // Statistics providers
    private StatsProvider localStatsProvider;
    
    public ElectionManager(String workerId, SpreadAdapter spread, Channel rabbitChannel) {
        this.workerId = workerId;
        this.spread = spread;
        this.rabbitChannel = rabbitChannel;
        this.startTime = System.currentTimeMillis();
        
        // Listen for election messages
        spread.addMessageListener(this::handleSpreadMessage);
    }
    
    public void setStatsProvider(StatsProvider provider) {
        this.localStatsProvider = provider;
    }
    
    /**
     * Initiate election to determine coordinator for statistics aggregation.
     * The election winner will send the response directly to the client.
     */
    public void initiateElection(String requestId, String clientQueue) {
        long epoch = System.currentTimeMillis() * 1000 + (long)(Math.random() * 1000); // Globally unique epoch
        long uptime = System.currentTimeMillis() - startTime;
        
        ElectionState state = new ElectionState(epoch, requestId, clientQueue);
        activeElections.put(epoch, state);
        
        logger.info("Worker [{}] initiating election epoch={} for request={}", 
                   workerId, epoch, requestId);
        
        try {
            // Multicast election message
            ElectionPayload payload = new ElectionPayload(workerId, epoch, uptime, requestId, clientQueue);
            SpreadMessage message = new SpreadMessage(SpreadMessageType.STATS_ELECTION, workerId, payload);
            spread.multicast(message);
            
            // Self-vote
            state.recordVote(workerId, workerId, true);
            
        } catch (Exception e) {
            logger.error("Error initiating election", e);
            state.result.completeExceptionally(e);
        }
    }
    
    /**
     * Collect partial stats from all workers and aggregate.
     * Sends response directly to client queue.
     */
    private void collectAndAggregateStats(long epoch, String requestId, String clientQueue) {
        logger.info("Worker [{}] is coordinator for epoch={}. Collecting stats...", workerId, epoch);
        
        // Run in separate thread to avoid blocking the RabbitMQ consumer thread
        new Thread(() -> {
            try {
                Set<String> members = spread.getGroupMembers();
                members.add(workerId);  // Include self
                
                Map<String, PartialStatsPayload> collectedStats = new ConcurrentHashMap<>();
                CountDownLatch latch = new CountDownLatch(members.size());
                
                // Listen for stats responses
                Consumer<SpreadMessage> statsListener = msg -> {
                    logger.info("Worker [{}] received Spread message type={} in stats listener", workerId, msg.getType());
                    if (msg.getType() == SpreadMessageType.STATS_RESPONSE) {
                        PartialStatsPayload stats = convertPayload(msg.getPayload(), PartialStatsPayload.class);
                        if (stats != null) {
                            logger.info("Worker [{}] collected stats from [{}]: total={}", 
                                       workerId, stats.getWorkerId(), stats.getTotalRequests());
                            collectedStats.put(stats.getWorkerId(), stats);
                            latch.countDown();
                        } else {
                            logger.warn("Worker [{}] received null stats payload", workerId);
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
                            SpreadMessage request = new SpreadMessage(SpreadMessageType.STATS_REQUEST, workerId, epoch);
                            spread.sendTo(memberId, request);
                        }
                    }
                    
                    // Wait for responses (with timeout)
                    boolean completed = latch.await(STATS_COLLECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    
                    if (!completed) {
                        logger.warn("Stats collection timeout. Collected {}/{} responses", 
                                   collectedStats.size(), members.size());
                    }
                    
                    // Aggregate stats
                    StatisticsPayload aggregated = aggregateStats(collectedStats.values(), members.size());
                    
                    // Send response to client
                    ResponseMessage response = new ResponseMessage(
                        requestId,
                        ResponseStatus.OK,
                        ResponseType.STATISTICS,
                        aggregated
                    );
                    
                    byte[] responseBytes = JsonUtil.toJsonBytes(response);
                    rabbitChannel.basicPublish("", clientQueue, null, responseBytes);
                    
                    logger.info("Worker [{}] sent aggregated stats to client: total={}, successful={}, failed={}, workers={}",
                               workerId, aggregated.getTotalRequests(), aggregated.getSuccessfulRequests(),
                               aggregated.getFailedRequests(), aggregated.getWorkerCount());
                    
                } catch (Exception e) {
                    logger.error("Error collecting stats", e);
                    // Send error response
                    try {
                        ResponseMessage errorResponse = new ResponseMessage(
                            requestId,
                            ResponseStatus.ERROR,
                            ResponseType.STATISTICS,
                            "Error collecting statistics: " + e.getMessage()
                        );
                        byte[] responseBytes = JsonUtil.toJsonBytes(errorResponse);
                        rabbitChannel.basicPublish("", clientQueue, null, responseBytes);
                    } catch (IOException sendError) {
                        logger.error("Failed to send error response", sendError);
                    }
                } finally {
                    spread.removeMessageListener(statsListener);
                }
            } catch (Exception e) {
                logger.error("Error in stats collection thread", e);
            }
        }).start();
    }
    
    /**
     * Aggregate partial statistics from all workers.
     */
    private StatisticsPayload aggregateStats(Collection<PartialStatsPayload> partialStats, int workerCount) {
        long totalRequests = 0;
        long successfulRequests = 0;
        long failedRequests = 0;
        
        for (PartialStatsPayload stats : partialStats) {
            totalRequests += stats.getTotalRequests();
            successfulRequests += stats.getSuccessfulRequests();
            failedRequests += stats.getFailedRequests();
        }
        
        logger.info("Aggregated stats from {} workers: total={}, successful={}, failed={}", 
                   partialStats.size(), totalRequests, successfulRequests, failedRequests);
        
        return new StatisticsPayload(totalRequests, successfulRequests, failedRequests, workerCount);
    }
    
    /**
     * Handle incoming Spread messages.
     */
    private void handleSpreadMessage(SpreadMessage message) {
        try {
            switch (message.getType()) {
                case STATS_ELECTION:
                    handleElectionMessage(message);
                    break;
                case ELECTION_VOTE:
                    handleVoteMessage(message);
                    break;
                case COORDINATOR_ANNOUNCE:
                    handleCoordinatorAnnounce(message);
                    break;
                case STATS_REQUEST:
                    handleStatsRequest(message);
                    break;
                case STATS_RESPONSE:
                    // Handled in collectAndAggregateStats
                    break;
                default:
                    // Ignore other message types
                    break;
            }
        } catch (Exception e) {
            logger.error("Error handling Spread message: {}", message.getType(), e);
        }
    }
    
    private void handleElectionMessage(SpreadMessage message) throws Exception {
        ElectionPayload election = convertPayload(message.getPayload(), ElectionPayload.class);
        if (election == null) return;
        
        logger.info("Worker [{}] received election from [{}] epoch={}", 
                   workerId, election.getCandidateId(), election.getElectionEpoch());
        
        long myUptime = System.currentTimeMillis() - startTime;
        
        // Determine who should be coordinator
        String votedFor = electCoordinator(election.getCandidateId(), election.getUptime(), 
                                           workerId, myUptime);
        boolean accept = votedFor.equals(election.getCandidateId());
        
        // Send vote
        VotePayload vote = new VotePayload(workerId, votedFor, election.getElectionEpoch(), accept);
        SpreadMessage voteMsg = new SpreadMessage(SpreadMessageType.ELECTION_VOTE, workerId, vote);
        spread.sendTo(election.getCandidateId(), voteMsg);
        
        logger.debug("Worker [{}] voted for [{}] (accept={})", workerId, votedFor, accept);
    }
    
    private void handleVoteMessage(SpreadMessage message) {
        VotePayload vote = convertPayload(message.getPayload(), VotePayload.class);
        if (vote == null) return;
        
        ElectionState state = activeElections.get(vote.getElectionEpoch());
        if (state == null) {
            logger.warn("Received vote for unknown election epoch={}", vote.getElectionEpoch());
            return;
        }
        
        state.recordVote(vote.getVoterId(), vote.getVotedFor(), vote.isAccept());
        
        // Check if we have enough votes (only process once)
        Set<String> members = spread.getGroupMembers();
        members.add(workerId);
        
        if (state.hasVoteCount(members.size()) && !state.isComplete()) {
            state.markComplete();
            String winner = state.determineWinner();
            logger.info("Election epoch={} complete. Winner: {}", vote.getElectionEpoch(), winner);
            
            // Multicast the coordinator announcement so all workers know who won
            try {
                CoordinatorAnnouncePayload announce = new CoordinatorAnnouncePayload(
                    vote.getElectionEpoch(), winner, state.requestId, state.clientQueue);
                SpreadMessage announceMsg = new SpreadMessage(
                    SpreadMessageType.COORDINATOR_ANNOUNCE, workerId, announce);
                spread.multicast(announceMsg);
                logger.info("Multicasted coordinator announcement: winner={}", winner);
            } catch (Exception e) {
                logger.error("Error multicasting coordinator announcement", e);
            }
        }
    }
    
    private void handleCoordinatorAnnounce(SpreadMessage message) {
        CoordinatorAnnouncePayload announce = convertPayload(message.getPayload(), CoordinatorAnnouncePayload.class);
        if (announce == null) return;
        
        logger.info("Worker [{}] received coordinator announcement: winner={} for epoch={}",
                   workerId, announce.getCoordinatorId(), announce.getElectionEpoch());
        
        // Only process each announcement once
        if (!processedAnnouncements.add(announce.getElectionEpoch())) {
            logger.debug("Worker [{}] already processed announcement for epoch={}", workerId, announce.getElectionEpoch());
            return;
        }
        
        // If this worker is the coordinator, collect stats and send response
        if (announce.getCoordinatorId().equals(workerId)) {
            logger.info("Worker [{}] is the coordinator. Collecting stats...", workerId);
            collectAndAggregateStats(announce.getElectionEpoch(), announce.getRequestId(), announce.getClientQueue());
        } else {
            logger.info("Worker [{}] lost election to [{}]", workerId, announce.getCoordinatorId());
        }
    }
    
    private void handleStatsRequest(SpreadMessage message) throws Exception {
        logger.info("Worker [{}] received stats request from [{}]", workerId, message.getSenderId());
        
        if (localStatsProvider != null) {
            PartialStatsPayload stats = localStatsProvider.getPartialStats();
            SpreadMessage response = new SpreadMessage(SpreadMessageType.STATS_RESPONSE, workerId, stats);
            spread.sendTo(message.getSenderId(), response);
            logger.info("Worker [{}] sent stats response to [{}]: total={}", 
                       workerId, message.getSenderId(), stats.getTotalRequests());
        } else {
            logger.warn("Worker [{}] has no stats provider", workerId);
        }
    }
    
    /**
     * Elect coordinator based on priority: highest uptime, then lexicographic workerId.
     */
    private String electCoordinator(String candidate1, long uptime1, String candidate2, long uptime2) {
        if (uptime1 > uptime2) {
            return candidate1;
        } else if (uptime1 < uptime2) {
            return candidate2;
        } else {
            // Tie-breaker: lexicographic comparison
            return candidate1.compareTo(candidate2) > 0 ? candidate1 : candidate2;
        }
    }
    
    @SuppressWarnings("unchecked")
    private <T> T convertPayload(Object payload, Class<T> clazz) {
        if (payload == null) return null;
        
        try {
            // Gson deserializes nested objects as LinkedTreeMap
            if (payload instanceof Map) {
                // Re-serialize and deserialize with correct type
                String json = JsonUtil.toJson(payload);
                return JsonUtil.fromJson(json, clazz);
            }
            return clazz.cast(payload);
        } catch (Exception e) {
            logger.error("Error converting payload to {}", clazz.getSimpleName(), e);
            return null;
        }
    }
    
    /**
     * State for an ongoing election.
     */
    private static class ElectionState {
        final long epoch;
        final String requestId;
        final String clientQueue;
        final CompletableFuture<String> result = new CompletableFuture<>();
        final Map<String, String> votes = new ConcurrentHashMap<>();  // voterId -> votedFor
        private volatile boolean complete = false;
        
        ElectionState(long epoch, String requestId, String clientQueue) {
            this.epoch = epoch;
            this.requestId = requestId;
            this.clientQueue = clientQueue;
        }
        
        void recordVote(String voterId, String votedFor, boolean accept) {
            votes.put(voterId, votedFor);
        }
        
        boolean hasVoteCount(int expected) {
            return votes.size() >= expected;
        }
        
        boolean isComplete() {
            return complete;
        }
        
        void markComplete() {
            complete = true;
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
    
    /**
     * Interface for providing local statistics.
     */
    public interface StatsProvider {
        PartialStatsPayload getPartialStats();
    }
    
    // Helper function for Consumer
    @FunctionalInterface
    private interface Consumer<T> extends java.util.function.Consumer<T> {}
}
