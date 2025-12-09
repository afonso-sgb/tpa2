package pt.isel.cd.common.model;

import java.util.Objects;

/**
 * Partial statistics from a single worker.
 */
public class PartialStatsPayload {
    private String workerId;
    private long totalRequests;
    private long successfulRequests;
    private long failedRequests;
    private long timestamp;
    
    public PartialStatsPayload() {
    }
    
    public PartialStatsPayload(String workerId, long totalRequests, 
                              long successfulRequests, long failedRequests) {
        this.workerId = workerId;
        this.totalRequests = totalRequests;
        this.successfulRequests = successfulRequests;
        this.failedRequests = failedRequests;
        this.timestamp = System.currentTimeMillis();
    }
    
    public String getWorkerId() {
        return workerId;
    }
    
    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }
    
    public long getTotalRequests() {
        return totalRequests;
    }
    
    public void setTotalRequests(long totalRequests) {
        this.totalRequests = totalRequests;
    }
    
    public long getSuccessfulRequests() {
        return successfulRequests;
    }
    
    public void setSuccessfulRequests(long successfulRequests) {
        this.successfulRequests = successfulRequests;
    }
    
    public long getFailedRequests() {
        return failedRequests;
    }
    
    public void setFailedRequests(long failedRequests) {
        this.failedRequests = failedRequests;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PartialStatsPayload that = (PartialStatsPayload) o;
        return Objects.equals(workerId, that.workerId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(workerId);
    }
    
    @Override
    public String toString() {
        return "PartialStatsPayload{" +
                "workerId='" + workerId + '\'' +
                ", totalRequests=" + totalRequests +
                ", successfulRequests=" + successfulRequests +
                ", failedRequests=" + failedRequests +
                ", timestamp=" + timestamp +
                '}';
    }
}
