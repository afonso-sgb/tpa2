package pt.isel.cd.common.model;

import java.util.Objects;

/**
 * Response message sent from Workers to UserApp via RabbitMQ.
 */
public class ResponseMessage {
    private String requestId;
    private ResponseStatus status;
    private ResponseType type;
    private Object payload;

    public ResponseMessage() {
    }

    public ResponseMessage(String requestId, ResponseStatus status, ResponseType type, Object payload) {
        this.requestId = requestId;
        this.status = status;
        this.type = type;
        this.payload = payload;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public ResponseStatus getStatus() {
        return status;
    }

    public void setStatus(ResponseStatus status) {
        this.status = status;
    }

    public ResponseType getType() {
        return type;
    }

    public void setType(ResponseType type) {
        this.type = type;
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
        ResponseMessage that = (ResponseMessage) o;
        return Objects.equals(requestId, that.requestId) && status == that.status && 
               type == that.type && Objects.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(requestId, status, type, payload);
    }

    @Override
    public String toString() {
        return "ResponseMessage{" +
                "requestId='" + requestId + '\'' +
                ", status=" + status +
                ", type=" + type +
                ", payload=" + payload +
                '}';
    }
}
