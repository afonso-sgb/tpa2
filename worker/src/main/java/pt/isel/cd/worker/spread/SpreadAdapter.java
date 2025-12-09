package pt.isel.cd.worker.spread;

import pt.isel.cd.common.model.SpreadMessage;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Adapter interface for Spread communication.
 * Allows switching between RabbitMQ simulation (local dev) and real Spread (GCP production).
 */
public interface SpreadAdapter extends AutoCloseable {
    
    /**
     * Send multicast message to all group members.
     */
    void multicast(SpreadMessage message) throws Exception;
    
    /**
     * Send point-to-point message to specific member.
     */
    void sendTo(String targetMemberId, SpreadMessage message) throws Exception;
    
    /**
     * Add listener for incoming messages.
     */
    void addMessageListener(Consumer<SpreadMessage> listener);
    
    /**
     * Remove message listener.
     */
    void removeMessageListener(Consumer<SpreadMessage> listener);
    
    /**
     * Get current group members.
     */
    Set<String> getGroupMembers();
}
