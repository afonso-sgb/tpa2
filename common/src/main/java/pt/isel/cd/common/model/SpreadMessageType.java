package pt.isel.cd.common.model;

import java.util.Objects;

/**
 * Message types for Spread group communication between Workers.
 */
public enum SpreadMessageType {
    /**
     * Worker announces presence when joining the group.
     */
    WORKER_PRESENCE,
    
    /**
     * Initiates statistics election process.
     */
    STATS_ELECTION,
    
    /**
     * Vote in election (accept or propose different coordinator).
     */
    ELECTION_VOTE,
    
    /**
     * Announces elected coordinator.
     */
    COORDINATOR_ANNOUNCE,
    
    /**
     * Request for partial statistics from a worker.
     */
    STATS_REQUEST,
    
    /**
     * Response with partial statistics from a worker.
     */
    STATS_RESPONSE
}
