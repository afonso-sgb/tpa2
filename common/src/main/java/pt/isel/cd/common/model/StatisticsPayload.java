package pt.isel.cd.common.model;

import java.util.Objects;

/**
 * Payload for STATISTICS responses.
 */
public class StatisticsPayload {
    private long totalRequests;
    private long successfulRequests;
    private long failedRequests;

    public StatisticsPayload() {
    }

    public StatisticsPayload(long totalRequests, long successfulRequests, long failedRequests) {
        this.totalRequests = totalRequests;
        this.successfulRequests = successfulRequests;
        this.failedRequests = failedRequests;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StatisticsPayload that = (StatisticsPayload) o;
        return totalRequests == that.totalRequests && 
               successfulRequests == that.successfulRequests && 
               failedRequests == that.failedRequests;
    }

    @Override
    public int hashCode() {
        return Objects.hash(totalRequests, successfulRequests, failedRequests);
    }

    @Override
    public String toString() {
        return "StatisticsPayload{" +
                "totalRequests=" + totalRequests +
                ", successfulRequests=" + successfulRequests +
                ", failedRequests=" + failedRequests +
                '}';
    }
}
