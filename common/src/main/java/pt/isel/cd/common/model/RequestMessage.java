package pt.isel.cd.common.model;

import java.util.Objects;

/**
 * Base request message sent from UserApp to Workers via RabbitMQ.
 */
public class RequestMessage {
    private String requestId;
    private RequestType type;
    private String clientQueue;
    private Object payload;

    public RequestMessage() {
    }

    public RequestMessage(String requestId, RequestType type, String clientQueue, Object payload) {
        this.requestId = requestId;
        this.type = type;
        this.clientQueue = clientQueue;
        this.payload = payload;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public RequestType getType() {
        return type;
    }

    public void setType(RequestType type) {
        this.type = type;
    }

    public String getClientQueue() {
        return clientQueue;
    }

    public void setClientQueue(String clientQueue) {
        this.clientQueue = clientQueue;
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
        RequestMessage that = (RequestMessage) o;
        return Objects.equals(requestId, that.requestId) && type == that.type && 
               Objects.equals(clientQueue, that.clientQueue) && Objects.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(requestId, type, clientQueue, payload);
    }

    @Override
    public String toString() {
        return "RequestMessage{" +
                "requestId='" + requestId + '\'' +
                ", type=" + type +
                ", clientQueue='" + clientQueue + '\'' +
                ", payload=" + payload +
                '}';
    }
}
