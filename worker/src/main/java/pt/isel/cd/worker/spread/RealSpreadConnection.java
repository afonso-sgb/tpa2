package pt.isel.cd.worker.spread;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spread.AdvancedMessageListener;
import spread.MembershipInfo;
import spread.SpreadConnection;
import spread.SpreadException;
import spread.SpreadGroup;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * Real Spread Toolkit connection implementation.
 * Connects to actual Spread daemon for production GCP deployment.
 */
public class RealSpreadConnection implements SpreadAdapter, AdvancedMessageListener {
    private static final Logger logger = LoggerFactory.getLogger(RealSpreadConnection.class);
    
    private final String memberId;
    private final String groupName;
    private final SpreadConnection connection;
    private final SpreadGroup group;
    private final Set<Consumer<pt.isel.cd.common.model.SpreadMessage>> messageListeners = new CopyOnWriteArraySet<>();
    private final Set<String> currentMembers = ConcurrentHashMap.newKeySet();
    
    /**
     * Create connection to Spread daemon.
     * 
     * @param memberId Unique member identifier (e.g., "worker-001")
     * @param groupName Spread group name (e.g., "tpa2_workers")
     * @param spreadHost Spread daemon address (e.g., "4803@localhost" or "4803@10.128.0.2")
     */
    public RealSpreadConnection(String memberId, String groupName, String spreadHost) 
            throws SpreadException, UnknownHostException {
        this.memberId = memberId;
        this.groupName = groupName;
        
        // Parse spread host: "port@hostname"
        String[] parts = spreadHost.split("@");
        int port = parts.length > 1 ? Integer.parseInt(parts[0]) : 4803;
        String host = parts.length > 1 ? parts[1] : parts[0];
        
        logger.info("Connecting to Spread daemon at {}:{} as [{}]", host, port, memberId);
        
        // Create connection
        connection = new SpreadConnection();
        connection.connect(InetAddress.getByName(host), port, memberId, false, true);
        
        // Add this as message listener
        connection.add(this);
        
        // Join group
        group = new SpreadGroup();
        group.join(connection, groupName);
        
        logger.info("Successfully joined Spread group [{}] as [{}]", groupName, memberId);
    }
    
    @Override
    public void multicast(pt.isel.cd.common.model.SpreadMessage message) throws Exception {
        spread.SpreadMessage msg = new spread.SpreadMessage();
        msg.setReliable();
        msg.addGroup(groupName);
        
        // Serialize our message object to bytes
        byte[] data = serializeMessage(message);
        msg.setData(data);
        
        connection.multicast(msg);
        logger.debug("Multicasted message type={} from [{}]", message.getType(), memberId);
    }
    
    @Override
    public void sendTo(String targetMemberId, pt.isel.cd.common.model.SpreadMessage message) throws Exception {
        spread.SpreadMessage msg = new spread.SpreadMessage();
        msg.setReliable();
        msg.addGroup(targetMemberId);  // In Spread, private messages use member ID as group
        
        byte[] data = serializeMessage(message);
        msg.setData(data);
        
        connection.multicast(msg);
        logger.debug("Sent P2P message type={} from [{}] to [{}]", 
                    message.getType(), memberId, targetMemberId);
    }
    
    @Override
    public void addMessageListener(Consumer<pt.isel.cd.common.model.SpreadMessage> listener) {
        messageListeners.add(listener);
    }
    
    @Override
    public void removeMessageListener(Consumer<pt.isel.cd.common.model.SpreadMessage> listener) {
        messageListeners.remove(listener);
    }
    
    @Override
    public Set<String> getGroupMembers() {
        return new HashSet<>(currentMembers);
    }
    
    @Override
    public void close() throws Exception {
        if (connection != null && connection.isConnected()) {
            group.leave();
            connection.disconnect();
            logger.info("Disconnected from Spread group [{}]", groupName);
        }
    }
    
    // AdvancedMessageListener implementation
    
    @Override
    public void regularMessageReceived(spread.SpreadMessage spreadMsg) {
        try {
            // Deserialize message
            byte[] data = spreadMsg.getData();
            pt.isel.cd.common.model.SpreadMessage message = deserializeMessage(data);
            
            // Notify all listeners
            for (Consumer<pt.isel.cd.common.model.SpreadMessage> listener : messageListeners) {
                try {
                    listener.accept(message);
                } catch (Exception e) {
                    logger.error("Error in message listener", e);
                }
            }
            
            logger.debug("Received message type={} from [{}]", 
                        message.getType(), message.getSenderId());
                        
        } catch (Exception e) {
            logger.error("Error processing received message", e);
        }
    }
    
    @Override
    public void membershipMessageReceived(spread.SpreadMessage msg) {
        try {
            MembershipInfo info = msg.getMembershipInfo();
            SpreadGroup[] members = info.getMembers();
            
            // Update member list
            currentMembers.clear();
            for (SpreadGroup member : members) {
                currentMembers.add(member.toString());
            }
            
            if (info.isCausedByJoin()) {
                logger.info("Member joined group [{}]: {} (total: {})", 
                           groupName, info.getJoined(), members.length);
            } else if (info.isCausedByLeave()) {
                logger.info("Member left group [{}]: {} (total: {})", 
                           groupName, info.getLeft(), members.length);
            } else if (info.isCausedByDisconnect()) {
                logger.warn("Member disconnected from group [{}]: {} (total: {})", 
                           groupName, info.getDisconnected(), members.length);
            }
            
        } catch (Exception e) {
            logger.error("Error processing membership message", e);
        }
    }
    
    // Serialization helpers
    
    private byte[] serializeMessage(pt.isel.cd.common.model.SpreadMessage message) throws Exception {
        // Use JSON serialization
        return pt.isel.cd.common.util.JsonUtil.toJsonBytes(message);
    }
    
    private pt.isel.cd.common.model.SpreadMessage deserializeMessage(byte[] data) throws Exception {
        return pt.isel.cd.common.util.JsonUtil.fromJsonBytes(data, pt.isel.cd.common.model.SpreadMessage.class);
    }
}
