package pt.isel.cd.common.config;

/**
 * Configuration constants for RabbitMQ queues and exchanges.
 */
public class QueueConfig {
    
    /**
     * Main queue for work distribution to workers.
     */
    public static final String REQUESTS_QUEUE = "requests";
    
    /**
     * Prefix for client-specific response queues.
     */
    public static final String CLIENT_QUEUE_PREFIX = "client-";
    
    /**
     * TTL for client queues (milliseconds) - 5 minutes.
     */
    public static final int CLIENT_QUEUE_TTL = 300000;
    
    /**
     * Default RabbitMQ host.
     */
    public static final String DEFAULT_RABBITMQ_HOST = "localhost";
    
    /**
     * Default RabbitMQ port.
     */
    public static final int DEFAULT_RABBITMQ_PORT = 5672;
    
    private QueueConfig() {
        // Utility class
    }
}
