package pt.isel.cd.common.model;

/**
 * Payload for announcing the elected coordinator.
 */
public class CoordinatorAnnouncePayload {
    private long electionEpoch;
    private String coordinatorId;
    private String requestId;
    private String clientQueue;

    public CoordinatorAnnouncePayload() {
    }

    public CoordinatorAnnouncePayload(long electionEpoch, String coordinatorId, String requestId, String clientQueue) {
        this.electionEpoch = electionEpoch;
        this.coordinatorId = coordinatorId;
        this.requestId = requestId;
        this.clientQueue = clientQueue;
    }

    public long getElectionEpoch() {
        return electionEpoch;
    }

    public void setElectionEpoch(long electionEpoch) {
        this.electionEpoch = electionEpoch;
    }

    public String getCoordinatorId() {
        return coordinatorId;
    }

    public void setCoordinatorId(String coordinatorId) {
        this.coordinatorId = coordinatorId;
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
    public String toString() {
        return "CoordinatorAnnouncePayload{" +
                "electionEpoch=" + electionEpoch +
                ", coordinatorId='" + coordinatorId + '\'' +
                ", requestId='" + requestId + '\'' +
                ", clientQueue='" + clientQueue + '\'' +
                '}';
    }
}
