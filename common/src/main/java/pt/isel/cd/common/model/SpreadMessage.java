package pt.isel.cd.common.model;

import java.util.Objects;

/**
 * Wrapper for all Spread group messages exchanged between Workers.
 */
public class SpreadMessage {
    private SpreadMessageType type;
    private String senderId;      // Worker ID that sent the message
    private long timestamp;       // Message timestamp
    private Object payload;       // Type-specific payload
    
    public SpreadMessage() {
    }
    
    public SpreadMessage(SpreadMessageType type, String senderId, Object payload) {
        this.type = type;
        this.senderId = senderId;
        this.timestamp = System.currentTimeMillis();
        this.payload = payload;
    }
    
    public SpreadMessageType getType() {
        return type;
    }
    
    public void setType(SpreadMessageType type) {
        this.type = type;
    }
    
    public String getSenderId() {
        return senderId;
    }
    
    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    public Object getPayload() {
        return payload;
    }
    
    public void setPayload(Object payload) {
        this.payload = payload;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SpreadMessage that = (SpreadMessage) o;
        return timestamp == that.timestamp && 
               type == that.type && 
               Objects.equals(senderId, that.senderId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(type, senderId, timestamp);
    }
    
    @Override
    public String toString() {
        return "SpreadMessage{" +
                "type=" + type +
                ", senderId='" + senderId + '\'' +
                ", timestamp=" + timestamp +
                ", payload=" + payload +
                '}';
    }
}
