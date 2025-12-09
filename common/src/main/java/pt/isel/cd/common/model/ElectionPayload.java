package pt.isel.cd.common.model;

import java.util.Objects;

/**
 * Payload for STATS_ELECTION messages.
 * Initiates election for statistics coordinator.
 */
public class ElectionPayload {
    private String candidateId;
    private long electionEpoch;   // Monotonically increasing election counter
    private long uptime;          // Candidate's uptime
    private String requestId;     // Original stats request ID
    private String clientQueue;   // Client queue to send final response
    
    public ElectionPayload() {
    }
    
    public ElectionPayload(String candidateId, long electionEpoch, long uptime, 
                          String requestId, String clientQueue) {
        this.candidateId = candidateId;
        this.electionEpoch = electionEpoch;
        this.uptime = uptime;
        this.requestId = requestId;
        this.clientQueue = clientQueue;
    }
    
    public String getCandidateId() {
        return candidateId;
    }
    
    public void setCandidateId(String candidateId) {
        this.candidateId = candidateId;
    }
    
    public long getElectionEpoch() {
        return electionEpoch;
    }
    
    public void setElectionEpoch(long electionEpoch) {
        this.electionEpoch = electionEpoch;
    }
    
    public long getUptime() {
        return uptime;
    }
    
    public void setUptime(long uptime) {
        this.uptime = uptime;
    }
    
    public String getRequestId() {
        return requestId;
    }
    
    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
    
    public String getClientQueue() {
        return clientQueue;
    }
    
    public void setClientQueue(String clientQueue) {
        this.clientQueue = clientQueue;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ElectionPayload that = (ElectionPayload) o;
        return electionEpoch == that.electionEpoch && 
               Objects.equals(candidateId, that.candidateId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(candidateId, electionEpoch);
    }
    
    @Override
    public String toString() {
        return "ElectionPayload{" +
                "candidateId='" + candidateId + '\'' +
                ", electionEpoch=" + electionEpoch +
                ", uptime=" + uptime +
                ", requestId='" + requestId + '\'' +
                '}';
    }
}
