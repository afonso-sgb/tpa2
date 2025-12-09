package pt.isel.cd.common.model;

import java.util.Objects;

/**
 * Payload for WORKER_PRESENCE messages.
 * Sent when a worker joins the Spread group.
 */
public class WorkerPresencePayload {
    private String workerId;
    private long uptime;          // Milliseconds since worker started
    private long localSequence;   // Local message counter
    private String version;       // Worker version (optional)
    
    public WorkerPresencePayload() {
    }
    
    public WorkerPresencePayload(String workerId, long uptime, long localSequence) {
        this.workerId = workerId;
        this.uptime = uptime;
        this.localSequence = localSequence;
        this.version = "1.0";
    }
    
    public String getWorkerId() {
        return workerId;
    }
    
    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }
    
    public long getUptime() {
        return uptime;
    }
    
    public void setUptime(long uptime) {
        this.uptime = uptime;
    }
    
    public long getLocalSequence() {
        return localSequence;
    }
    
    public void setLocalSequence(long localSequence) {
        this.localSequence = localSequence;
    }
    
    public String getVersion() {
        return version;
    }
    
    public void setVersion(String version) {
        this.version = version;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WorkerPresencePayload that = (WorkerPresencePayload) o;
        return Objects.equals(workerId, that.workerId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(workerId);
    }
    
    @Override
    public String toString() {
        return "WorkerPresencePayload{" +
                "workerId='" + workerId + '\'' +
                ", uptime=" + uptime +
                ", localSequence=" + localSequence +
                ", version='" + version + '\'' +
                '}';
    }
}
