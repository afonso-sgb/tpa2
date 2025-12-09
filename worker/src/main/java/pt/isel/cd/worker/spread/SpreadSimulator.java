package pt.isel.cd.worker.spread;

import com.rabbitmq.client.*;
import pt.isel.cd.common.model.SpreadMessage;
import pt.isel.cd.common.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Simulates Spread Toolkit using RabbitMQ topic exchange for group communication.
 * 
 * In production, this would be replaced with actual Spread Toolkit integration.
 * This simulation provides:
 * - Group membership via topic exchange
 * - Multicast messaging
 * - Point-to-point messaging
 * - Membership notifications
 * 
 * The simulation uses RabbitMQ's topic exchange to mimic Spread's group communication.
 */
public class SpreadSimulator implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(SpreadSimulator.class);
    
    private static final String SPREAD_EXCHANGE = "spread_group_exchange";
    private static final String MULTICAST_ROUTING_KEY = "multicast.all";
    private static final String PRESENCE_ROUTING_KEY = "presence.announce";
    
    private final String workerId;
    private final String groupName;
    private final Connection connection;
    private final Channel channel;
    private final String queueName;
    
    private final Set<String> knownWorkers = ConcurrentHashMap.newKeySet();
    private final List<Consumer<SpreadMessage>> messageListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Set<String>>> membershipListeners = new CopyOnWriteArrayList<>();
    
    private volatile boolean running = false;
    
    public SpreadSimulator(String workerId, String groupName, String rabbitHost, int rabbitPort) 
            throws IOException, TimeoutException {
        this.workerId = workerId;
        this.groupName = groupName;
        
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(rabbitHost);
        factory.setPort(rabbitPort);
        
        connection = factory.newConnection();
        channel = connection.createChannel();
        
        // Declare topic exchange for Spread group simulation
        channel.exchangeDeclare(SPREAD_EXCHANGE, BuiltinExchangeType.TOPIC, true);
        
        // Create exclusive queue for this worker
        queueName = "spread_" + workerId + "_" + UUID.randomUUID();
        channel.queueDeclare(queueName, false, true, true, null);
        
        // Bind to multicast and presence messages
        channel.queueBind(queueName, SPREAD_EXCHANGE, MULTICAST_ROUTING_KEY);
        channel.queueBind(queueName, SPREAD_EXCHANGE, PRESENCE_ROUTING_KEY);
        channel.queueBind(queueName, SPREAD_EXCHANGE, "p2p." + workerId); // Point-to-point
        
        logger.info("SpreadSimulator initialized for worker [{}] in group [{}]", workerId, groupName);
    }
    
    /**
     * Join the Spread group and start receiving messages.
     */
    public void joinGroup() throws IOException {
        running = true;
        
        // Start consuming messages
        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            try {
                SpreadMessage message = JsonUtil.fromJsonBytes(delivery.getBody(), SpreadMessage.class);
                handleIncomingMessage(message);
            } catch (Exception e) {
                logger.error("Error processing Spread message", e);
            }
        };
        
        channel.basicConsume(queueName, true, deliverCallback, consumerTag -> {});
        
        logger.info("Worker [{}] joined Spread group [{}]", workerId, groupName);
    }
    
    /**
     * Send multicast message to all workers in the group.
     */
    public void multicast(SpreadMessage message) throws IOException {
        message.setSenderId(workerId);
        message.setTimestamp(System.currentTimeMillis());
        
        byte[] messageBytes = JsonUtil.toJsonBytes(message);
        channel.basicPublish(SPREAD_EXCHANGE, MULTICAST_ROUTING_KEY, null, messageBytes);
        
        logger.debug("Worker [{}] multicast message type: {}", workerId, message.getType());
    }
    
    /**
     * Send point-to-point message to a specific worker.
     */
    public void sendTo(String targetWorkerId, SpreadMessage message) throws IOException {
        message.setSenderId(workerId);
        message.setTimestamp(System.currentTimeMillis());
        
        byte[] messageBytes = JsonUtil.toJsonBytes(message);
        String routingKey = "p2p." + targetWorkerId;
        channel.basicPublish(SPREAD_EXCHANGE, routingKey, null, messageBytes);
        
        logger.debug("Worker [{}] sent P2P message to [{}] type: {}", 
                    workerId, targetWorkerId, message.getType());
    }
    
    /**
     * Register a listener for incoming Spread messages.
     */
    public void addMessageListener(Consumer<SpreadMessage> listener) {
        messageListeners.add(listener);
    }
    
    /**
     * Remove a message listener.
     */
    public void removeMessageListener(Consumer<SpreadMessage> listener) {
        messageListeners.remove(listener);
    }
    
    /**
     * Register a listener for membership changes.
     */
    public void addMembershipListener(Consumer<Set<String>> listener) {
        membershipListeners.add(listener);
    }
    
    /**
     * Get current known group members.
     */
    public Set<String> getGroupMembers() {
        return new HashSet<>(knownWorkers);
    }
    
    private void handleIncomingMessage(SpreadMessage message) {
        // Update known workers list
        String senderId = message.getSenderId();
        if (senderId != null && !senderId.equals(workerId)) {
            boolean wasNew = knownWorkers.add(senderId);
            if (wasNew) {
                logger.info("Detected new worker in group: {}", senderId);
                notifyMembershipChange();
            }
        }
        
        // Notify message listeners
        for (Consumer<SpreadMessage> listener : messageListeners) {
            try {
                listener.accept(message);
            } catch (Exception e) {
                logger.error("Error in message listener", e);
            }
        }
    }
    
    private void notifyMembershipChange() {
        Set<String> currentMembers = getGroupMembers();
        for (Consumer<Set<String>> listener : membershipListeners) {
            try {
                listener.accept(currentMembers);
            } catch (Exception e) {
                logger.error("Error in membership listener", e);
            }
        }
    }
    
    public String getWorkerId() {
        return workerId;
    }
    
    public boolean isRunning() {
        return running;
    }
    
    @Override
    public void close() throws IOException, TimeoutException {
        running = false;
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
        if (connection != null && connection.isOpen()) {
            connection.close();
        }
        logger.info("SpreadSimulator closed for worker [{}]", workerId);
    }
}
