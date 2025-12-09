package pt.isel.cd.worker;

import com.rabbitmq.client.*;
import pt.isel.cd.common.config.QueueConfig;
import pt.isel.cd.common.model.*;
import pt.isel.cd.common.util.JsonUtil;
import pt.isel.cd.worker.spread.ElectionManager;
import pt.isel.cd.worker.spread.SpreadSimulator;
import pt.isel.cd.worker.spread.SpreadAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Worker - Processes search requests from RabbitMQ and searches files in GlusterFS.
 * Includes Spread integration for leader election and distributed statistics aggregation.
 */
public class Worker {
    private static final Logger logger = LoggerFactory.getLogger(Worker.class);
    
    private final String workerId;
    private final Connection connection;
    private final Channel channel;
    private final Path sharedFilesPath;
    private final long startTime;
    
    // Spread integration for consensus and election (supports both simulation and real)
    private final SpreadAdapter spread;
    private final ElectionManager electionManager;
    
    // Statistics counters
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);

    /**
     * Constructor for local development (Spread simulation mode)
     */
    public Worker(String workerId, String rabbitMqHost, int rabbitMqPort, String sharedFilesDir) 
            throws IOException, TimeoutException {
        this(workerId, rabbitMqHost, rabbitMqPort, sharedFilesDir, null, null);
    }
    
    /**
     * Constructor with optional Spread parameters (for GCP deployment)
     * 
     * @param spreadHost Spread daemon host (e.g., "4803@localhost") - if null, uses simulation
     * @param spreadGroup Spread group name (e.g., "tpa2_workers") - if null, uses "email_workers"
     */
    public Worker(String workerId, String rabbitMqHost, int rabbitMqPort, String sharedFilesDir,
                  String spreadHost, String spreadGroup) 
            throws IOException, TimeoutException {
        this.workerId = workerId;
        this.sharedFilesPath = Paths.get(sharedFilesDir);
        this.startTime = System.currentTimeMillis();
        
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(rabbitMqHost);
        factory.setPort(rabbitMqPort);
        
        connection = factory.newConnection();
        channel = connection.createChannel();
        
        // Declare requests queue (durable, for work distribution)
        channel.queueDeclare(QueueConfig.REQUESTS_QUEUE, true, false, false, null);
        
        // Set QoS to process one message at a time (fair dispatch)
        channel.basicQos(1);
        
        // Initialize Spread (real or simulation)
        String groupName = spreadGroup != null ? spreadGroup : "email_workers";
        if (spreadHost != null) {
            // GCP mode: Use real Spread Toolkit
            try {
                logger.info("Worker [{}] using REAL Spread Toolkit, host [{}], group [{}]", 
                           workerId, spreadHost, groupName);
                spread = new pt.isel.cd.worker.spread.RealSpreadConnection(workerId, groupName, spreadHost);
            } catch (Exception e) {
                logger.error("ERROR: Failed to connect to Spread daemon", e);
                logger.error("  Make sure Spread daemon is running on {}", spreadHost);
                logger.error("  Command: docker compose -f docker-compose-spread.yml up -d");
                throw new RuntimeException("Failed to connect to Spread daemon", e);
            }
        } else {
            // Local mode: Use RabbitMQ simulation
            logger.info("Worker [{}] using SIMULATED Spread (RabbitMQ), group [{}]", 
                       workerId, groupName);
            SpreadSimulator simulator = new SpreadSimulator(workerId, groupName, rabbitMqHost, rabbitMqPort);
            simulator.joinGroup();
            spread = simulator;
        }
        
        // Initialize election manager
        electionManager = new ElectionManager(workerId, spread, channel);
        electionManager.setStatsProvider(this::getPartialStats);
        
        // Announce presence to the group
        announcePresence();
        
        logger.info("Worker [{}] initialized. RabbitMQ: {}:{}, Files: {}", 
                    workerId, rabbitMqHost, rabbitMqPort, sharedFilesDir);
    }
    
    private void announcePresence() {
        try {
            long uptime = System.currentTimeMillis() - startTime;
            WorkerPresencePayload presence = new WorkerPresencePayload(
                workerId, uptime, totalRequests.get());
            SpreadMessage message = new SpreadMessage(
                SpreadMessageType.WORKER_PRESENCE, workerId, presence);
            spread.multicast(message);
            logger.info("Worker [{}] announced presence to group", workerId);
        } catch (Exception e) {
            logger.error("Error announcing presence", e);
        }
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
                
                // If response is null, another worker will handle it (e.g., election loser)
                if (response != null) {
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
        
        Map<String, String> matchingEmails = new HashMap<>();
        
        try (Stream<Path> paths = Files.walk(sharedFilesPath)) {
            paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".txt"))
                .forEach(path -> {
                    try {
                        String emailMessage = Files.readString(path);
                        if (containsAllSubstrings(emailMessage, substrings)) {
                            // Use filename only (not full path) as key
                            matchingEmails.put(path.getFileName().toString(), emailMessage);
                        }
                    } catch (IOException e) {
                        logger.error("Read error in file: {} - {}", path, e.getMessage());
                    }
                });
            
            logger.info("Worker [{}] found {} matching files", workerId, matchingEmails.size());
            
            SearchResultPayload resultPayload = new SearchResultPayload(matchingEmails);
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

    private boolean containsAllSubstrings(String message, List<String> substrings) {
        String lowerMessage = message.toLowerCase();
        for (String substr : substrings) {
            if (!lowerMessage.contains(substr.toLowerCase())) {
                return false;
            }
        }
        return true;
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
        logger.info("Worker [{}] initiating election for statistics aggregation", workerId);
        
        try {
            // Initiate election - the winner will send the response directly
            electionManager.initiateElection(request.getRequestId(), request.getClientQueue());
            
            // Return null so this worker doesn't send a duplicate response
            // The election winner will handle sending the response
            return null;
            
        } catch (Exception e) {
            logger.error("Error during statistics election", e);
            // Fallback to local stats if election fails
            StatisticsPayload localStats = new StatisticsPayload(
                totalRequests.get(),
                successfulRequests.get(),
                failedRequests.get(),
                1  // Only this worker
            );
            
            return new ResponseMessage(
                request.getRequestId(),
                ResponseStatus.OK,
                ResponseType.STATISTICS,
                localStats
            );
        }
    }
    
    /**
     * Get partial statistics from this worker for aggregation.
     */
    private PartialStatsPayload getPartialStats() {
        return new PartialStatsPayload(
            workerId,
            totalRequests.get(),
            successfulRequests.get(),
            failedRequests.get()
        );
    }

    private void sendResponse(ResponseMessage response) throws IOException {
        // The response needs to include routing info - we'll store it during request processing
        // For now, this is a simplified version that would need the clientQueue from the request
        byte[] responseBytes = JsonUtil.toJsonBytes(response);
        logger.debug("Worker [{}] prepared response for request {}", workerId, response.getRequestId());
        // Actual sending will be done in processRequest with proper routing
    }

    public void close() throws Exception {
        if (spread != null) {
            spread.close();
        }
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
        if (connection != null && connection.isOpen()) {
            connection.close();
        }
        logger.info("Worker [{}] closed", workerId);
    }

    public static void main(String[] args) {
        // Default values (final for lambda access)
        final String[] config = new String[5]; // workerId, rabbitHost, fileDir, spreadHost, spreadGroup
        config[0] = "worker-" + UUID.randomUUID().toString().substring(0, 8); // workerId
        config[1] = "localhost"; // rabbitHost
        config[2] = "./EmailFiles"; // fileDir
        config[3] = null;  // spreadHost - null = use simulation
        config[4] = null;  // spreadGroup - null = use default
        final int[] portConfig = new int[1]; // rabbitPort
        portConfig[0] = 5672;
        
        // Parse command-line arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--worker-id":
                    if (i + 1 < args.length) config[0] = args[++i];
                    break;
                case "--rabbit-host":
                    if (i + 1 < args.length) config[1] = args[++i];
                    break;
                case "--rabbit-port":
                    if (i + 1 < args.length) portConfig[0] = Integer.parseInt(args[++i]);
                    break;
                case "--file-dir":
                    if (i + 1 < args.length) config[2] = args[++i];
                    break;
                case "--spread-host":
                    if (i + 1 < args.length) config[3] = args[++i];
                    break;
                case "--spread-group":
                    if (i + 1 < args.length) config[4] = args[++i];
                    break;
                case "--help":
                    printUsage();
                    return;
            }
        }
        
        // Also support environment variables (for Docker)
        if (System.getenv("WORKER_ID") != null) config[0] = System.getenv("WORKER_ID");
        if (System.getenv("RABBIT_HOST") != null) config[1] = System.getenv("RABBIT_HOST");
        if (System.getenv("RABBIT_PORT") != null) portConfig[0] = Integer.parseInt(System.getenv("RABBIT_PORT"));
        if (System.getenv("FILE_DIR") != null) config[2] = System.getenv("FILE_DIR");
        if (System.getenv("SPREAD_HOST") != null) config[3] = System.getenv("SPREAD_HOST");
        if (System.getenv("SPREAD_GROUP") != null) config[4] = System.getenv("SPREAD_GROUP");
        
        String mode = (config[3] != null) ? "PRODUCTION (Real Spread)" : "DEVELOPMENT (Simulated)";
        logger.info("Starting Worker [{}] in {} mode", config[0], mode);
        logger.info("  RabbitMQ: {}:{}", config[1], portConfig[0]);
        logger.info("  File Directory: {}", config[2]);
        if (config[3] != null) {
            logger.info("  Spread Host: {}", config[3]);
            logger.info("  Spread Group: {}", config[4] != null ? config[4] : "email_workers (default)");
        }
        
        try {
            Worker worker = new Worker(config[0], config[1], portConfig[0], config[2], config[3], config[4]);
            worker.start();
            
            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down worker [{}]...", config[0]);
                try {
                    worker.close();
                } catch (Exception e) {
                    logger.error("Error during shutdown", e);
                }
            }));
            
            // Keep running
            logger.info("Worker [{}] is running. Press CTRL+C to exit.", config[0]);
            Thread.currentThread().join();
            
        } catch (Exception e) {
            logger.error("Fatal error in worker", e);
            System.exit(1);
        }
    }
    
    private static void printUsage() {
        System.out.println("Worker - Distributed Email Search System");
        System.out.println();
        System.out.println("Usage: java -jar worker.jar [OPTIONS]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --worker-id <id>        Worker identifier (default: auto-generated)");
        System.out.println("  --rabbit-host <host>    RabbitMQ hostname (default: localhost)");
        System.out.println("  --rabbit-port <port>    RabbitMQ port (default: 5672)");
        System.out.println("  --file-dir <directory>  Email files directory (default: ./EmailFiles)");
        System.out.println("  --spread-host <host>    Spread daemon host (e.g., 4803@localhost)");
        System.out.println("                          If not specified, uses RabbitMQ simulation");
        System.out.println("  --spread-group <group>  Spread group name (default: email_workers)");
        System.out.println("  --help                  Show this help message");
        System.out.println();
        System.out.println("Environment Variables (for Docker):");
        System.out.println("  WORKER_ID, RABBIT_HOST, RABBIT_PORT, FILE_DIR, SPREAD_HOST, SPREAD_GROUP");
        System.out.println();
        System.out.println("Examples:");
        System.out.println();
        System.out.println("  Local development (simulation):");
        System.out.println("    java -jar worker.jar --worker-id worker-1");
        System.out.println();
        System.out.println("  GCP production (real Spread):");
        System.out.println("    java -jar worker.jar --worker-id worker-1 \\");
        System.out.println("      --rabbit-host 10.128.0.8 \\");
        System.out.println("      --spread-host 4803@localhost \\");
        System.out.println("      --spread-group tpa2_workers \\");
        System.out.println("      --file-dir /var/sharedfiles/emails");
        System.out.println();
    }
}
