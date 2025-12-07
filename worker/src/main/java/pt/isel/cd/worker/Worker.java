package pt.isel.cd.worker;

import com.rabbitmq.client.*;
import pt.isel.cd.common.config.QueueConfig;
import pt.isel.cd.common.model.*;
import pt.isel.cd.common.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Worker - Processes search requests from RabbitMQ and searches files in GlusterFS.
 * Note: Spread integration for consensus/election is simplified in this version.
 */
public class Worker {
    private static final Logger logger = LoggerFactory.getLogger(Worker.class);
    
    private final String workerId;
    private final Connection connection;
    private final Channel channel;
    private final Path sharedFilesPath;
    
    // Statistics counters
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);

    public Worker(String workerId, String rabbitMqHost, int rabbitMqPort, String sharedFilesDir) 
            throws IOException, TimeoutException {
        this.workerId = workerId;
        this.sharedFilesPath = Paths.get(sharedFilesDir);
        
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(rabbitMqHost);
        factory.setPort(rabbitMqPort);
        
        connection = factory.newConnection();
        channel = connection.createChannel();
        
        // Declare requests queue (durable, for work distribution)
        channel.queueDeclare(QueueConfig.REQUESTS_QUEUE, true, false, false, null);
        
        // Set QoS to process one message at a time (fair dispatch)
        channel.basicQos(1);
        
        logger.info("Worker [{}] initialized. RabbitMQ: {}:{}, Files: {}", 
                    workerId, rabbitMqHost, rabbitMqPort, sharedFilesDir);
    }

    public void start() throws IOException {
        logger.info("Worker [{}] starting to consume requests...", workerId);
        
        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            long startTime = System.currentTimeMillis();
            try {
                RequestMessage request = JsonUtil.parseRequest(delivery.getBody());
                logger.info("Worker [{}] processing request: {} (type: {})", 
                            workerId, request.getRequestId(), request.getType());
                
                totalRequests.incrementAndGet();
                
                ResponseMessage response = processRequest(request);
                
                // Send response to client queue
                if (request.getClientQueue() != null && !request.getClientQueue().isEmpty()) {
                    byte[] responseBytes = JsonUtil.toJsonBytes(response);
                    channel.basicPublish("", request.getClientQueue(), null, responseBytes);
                    logger.debug("Worker [{}] sent response to {}", workerId, request.getClientQueue());
                }
                
                if (response.getStatus() == ResponseStatus.OK) {
                    successfulRequests.incrementAndGet();
                } else {
                    failedRequests.incrementAndGet();
                }
                
                // Acknowledge the message
                channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                
                long elapsed = System.currentTimeMillis() - startTime;
                logger.info("Worker [{}] completed request {} in {} ms", 
                            workerId, request.getRequestId(), elapsed);
                
            } catch (Exception e) {
                logger.error("Worker [{}] error processing request", workerId, e);
                failedRequests.incrementAndGet();
                // Reject and requeue the message
                channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
            }
        };
        
        // Start consuming with manual acknowledgment
        channel.basicConsume(QueueConfig.REQUESTS_QUEUE, false, deliverCallback, consumerTag -> {});
    }

    private ResponseMessage processRequest(RequestMessage request) {
        try {
            switch (request.getType()) {
                case SEARCH:
                    return handleSearch(request);
                case GET_FILE:
                    return handleGetFile(request);
                case GET_STATS:
                    return handleGetStats(request);
                default:
                    return new ResponseMessage(
                        request.getRequestId(), 
                        ResponseStatus.ERROR, 
                        null, 
                        "Unknown request type"
                    );
            }
        } catch (Exception e) {
            logger.error("Error processing request", e);
            return new ResponseMessage(
                request.getRequestId(), 
                ResponseStatus.ERROR, 
                null, 
                e.getMessage()
            );
        }
    }

    private ResponseMessage handleSearch(RequestMessage request) {
        SearchPayload payload = (SearchPayload) request.getPayload();
        List<String> substrings = payload.getSubstrings();
        
        logger.info("Worker [{}] searching for: {}", workerId, substrings);
        
        List<String> matchingFiles = new ArrayList<>();
        
        try (Stream<Path> paths = Files.walk(sharedFilesPath)) {
            matchingFiles = paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".txt"))
                .filter(path -> containsAllSubstrings(path, substrings))
                .map(Path::toString)
                .collect(Collectors.toList());
            
            logger.info("Worker [{}] found {} matching files", workerId, matchingFiles.size());
            
            SearchResultPayload resultPayload = new SearchResultPayload(matchingFiles);
            return new ResponseMessage(
                request.getRequestId(),
                ResponseStatus.OK,
                ResponseType.SEARCH_RESULT,
                resultPayload
            );
            
        } catch (IOException e) {
            logger.error("Error searching files", e);
            return new ResponseMessage(
                request.getRequestId(),
                ResponseStatus.ERROR,
                ResponseType.SEARCH_RESULT,
                null
            );
        }
    }

    private boolean containsAllSubstrings(Path file, List<String> substrings) {
        try {
            String content = Files.readString(file);
            return substrings.stream().allMatch(content::contains);
        } catch (IOException e) {
            logger.error("Error reading file: {}", file, e);
            return false;
        }
    }

    private ResponseMessage handleGetFile(RequestMessage request) {
        FilePayload payload = (FilePayload) request.getPayload();
        String filename = payload.getFilename();
        
        logger.info("Worker [{}] retrieving file: {}", workerId, filename);
        
        try {
            Path filePath;
            
            // If filename is absolute path, use it; otherwise, look in shared directory
            if (Paths.get(filename).isAbsolute()) {
                filePath = Paths.get(filename);
            } else {
                filePath = sharedFilesPath.resolve(filename);
            }
            
            if (!Files.exists(filePath)) {
                logger.warn("Worker [{}] file not found: {}", workerId, filePath);
                return new ResponseMessage(
                    request.getRequestId(),
                    ResponseStatus.NOT_FOUND,
                    ResponseType.FILE_CONTENT,
                    null
                );
            }
            
            String content = Files.readString(filePath);
            FileContentPayload resultPayload = new FileContentPayload(filename, content);
            
            return new ResponseMessage(
                request.getRequestId(),
                ResponseStatus.OK,
                ResponseType.FILE_CONTENT,
                resultPayload
            );
            
        } catch (IOException e) {
            logger.error("Error reading file: {}", filename, e);
            return new ResponseMessage(
                request.getRequestId(),
                ResponseStatus.ERROR,
                ResponseType.FILE_CONTENT,
                null
            );
        }
    }

    private ResponseMessage handleGetStats(RequestMessage request) {
        logger.info("Worker [{}] collecting statistics", workerId);
        
        // In a full implementation, this would:
        // 1. Initiate Spread-based consensus/election
        // 2. Collect stats from all workers
        // 3. Aggregate and return
        // For now, return local worker stats
        
        StatisticsPayload stats = new StatisticsPayload(
            totalRequests.get(),
            successfulRequests.get(),
            failedRequests.get()
        );
        
        return new ResponseMessage(
            request.getRequestId(),
            ResponseStatus.OK,
            ResponseType.STATISTICS,
            stats
        );
    }

    private void sendResponse(ResponseMessage response) throws IOException {
        // The response needs to include routing info - we'll store it during request processing
        // For now, this is a simplified version that would need the clientQueue from the request
        byte[] responseBytes = JsonUtil.toJsonBytes(response);
        logger.debug("Worker [{}] prepared response for request {}", workerId, response.getRequestId());
        // Actual sending will be done in processRequest with proper routing
    }

    public void close() throws IOException, TimeoutException {
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
        if (connection != null && connection.isOpen()) {
            connection.close();
        }
        logger.info("Worker [{}] closed", workerId);
    }

    public static void main(String[] args) {
        String workerId = System.getenv().getOrDefault("WORKER_ID", "worker-" + System.currentTimeMillis());
        String rabbitMqHost = System.getenv().getOrDefault("RABBITMQ_HOST", QueueConfig.DEFAULT_RABBITMQ_HOST);
        int rabbitMqPort = Integer.parseInt(System.getenv().getOrDefault("RABBITMQ_PORT", 
                                                                          String.valueOf(QueueConfig.DEFAULT_RABBITMQ_PORT)));
        String sharedFilesDir = System.getenv().getOrDefault("SHARED_FILES_DIR", "/var/sharedfiles");

        logger.info("Starting Worker with ID: {}", workerId);
        logger.info("RabbitMQ: {}:{}", rabbitMqHost, rabbitMqPort);
        logger.info("Shared Files Directory: {}", sharedFilesDir);

        try {
            Worker worker = new Worker(workerId, rabbitMqHost, rabbitMqPort, sharedFilesDir);
            worker.start();
            
            // Keep the application running
            logger.info("Worker [{}] is running. Press CTRL+C to exit.", workerId);
            Thread.currentThread().join();
            
        } catch (Exception e) {
            logger.error("Fatal error in worker", e);
            System.exit(1);
        }
    }
}
