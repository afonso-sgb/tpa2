package pt.isel.cd.common.model;

import java.util.Objects;

/**
 * Payload for ELECTION_VOTE messages.
 * Response to election with vote.
 */
public class VotePayload {
    private String voterId;
    private String votedFor;      // Worker ID being voted for
    private long electionEpoch;
    private boolean accept;       // true = accept candidate, false = propose different
    
    public VotePayload() {
    }
    
    public VotePayload(String voterId, String votedFor, long electionEpoch, boolean accept) {
        this.voterId = voterId;
        this.votedFor = votedFor;
        this.electionEpoch = electionEpoch;
        this.accept = accept;
    }
    
    public String getVoterId() {
        return voterId;
    }
    
    public void setVoterId(String voterId) {
        this.voterId = voterId;
    }
    
    public String getVotedFor() {
        return votedFor;
    }
    
    public void setVotedFor(String votedFor) {
        this.votedFor = votedFor;
    }
    
    public long getElectionEpoch() {
        return electionEpoch;
    }
    
    public void setElectionEpoch(long electionEpoch) {
        this.electionEpoch = electionEpoch;
    }
    
    public boolean isAccept() {
        return accept;
    }
    
    public void setAccept(boolean accept) {
        this.accept = accept;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VotePayload that = (VotePayload) o;
        return electionEpoch == that.electionEpoch && 
               Objects.equals(voterId, that.voterId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(voterId, electionEpoch);
    }
    
    @Override
    public String toString() {
        return "VotePayload{" +
                "voterId='" + voterId + '\'' +
                ", votedFor='" + votedFor + '\'' +
                ", electionEpoch=" + electionEpoch +
                ", accept=" + accept +
                '}';
    }
}
