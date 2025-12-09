package pt.isel.cd.userapp;

import com.rabbitmq.client.*;
import pt.isel.cd.common.config.QueueConfig;
import pt.isel.cd.common.model.*;
import pt.isel.cd.common.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * User Application - CLI client for submitting requests and receiving responses.
 */
public class UserApp implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(UserApp.class);
    private static final int RESPONSE_TIMEOUT_SECONDS = 30;
    
    private final Connection connection;
    private final Channel channel;
    private final String clientQueue;
    private final Map<String, CompletableFuture<ResponseMessage>> pendingRequests = new ConcurrentHashMap<>();

    public UserApp(String rabbitMqHost, int rabbitMqPort) throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(rabbitMqHost);
        factory.setPort(rabbitMqPort);
        
        connection = factory.newConnection();
        channel = connection.createChannel();
        
        // Declare requests queue (durable, for work distribution)
        channel.queueDeclare(QueueConfig.REQUESTS_QUEUE, true, false, false, null);
        
        // Create client-specific response queue with TTL
        String queuePrefix = QueueConfig.CLIENT_QUEUE_PREFIX;
        String uniqueId = UUID.randomUUID().toString();
        clientQueue = queuePrefix + uniqueId;
        
        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", QueueConfig.CLIENT_QUEUE_TTL);
        args.put("x-expires", QueueConfig.CLIENT_QUEUE_TTL);
        
        channel.queueDeclare(clientQueue, false, true, true, args);
        
        // Start consuming responses
        startResponseConsumer();
        
        logger.info("UserApp initialized. RabbitMQ: {}:{}, Client Queue: {}", 
                    rabbitMqHost, rabbitMqPort, clientQueue);
    }

    private void startResponseConsumer() throws IOException {
        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            try {
                ResponseMessage response = JsonUtil.parseResponse(delivery.getBody());
                logger.info("Received response: {}", response.getRequestId());
                
                CompletableFuture<ResponseMessage> future = pendingRequests.remove(response.getRequestId());
                if (future != null) {
                    future.complete(response);
                } else {
                    logger.warn("Received response for unknown request: {}", response.getRequestId());
                }
            } catch (Exception e) {
                logger.error("Error processing response", e);
            }
        };
        
        channel.basicConsume(clientQueue, true, deliverCallback, consumerTag -> {});
    }

    /**
     * Search for files containing all specified substrings.
     */
    public SearchResultPayload search(List<String> substrings) throws Exception {
        String requestId = UUID.randomUUID().toString();
        SearchPayload payload = new SearchPayload(substrings);
        RequestMessage request = new RequestMessage(requestId, RequestType.SEARCH, clientQueue, payload);
        
        logger.info("Sending SEARCH request: {}", substrings);
        ResponseMessage response = sendRequestAndWait(request);
        
        if (response.getStatus() == ResponseStatus.OK) {
            return (SearchResultPayload) response.getPayload();
        } else {
            throw new RuntimeException("Search failed: " + response.getStatus());
        }
    }

    /**
     * Retrieve the content of a specific file.
     */
    public FileContentPayload getFile(String filename) throws Exception {
        String requestId = UUID.randomUUID().toString();
        FilePayload payload = new FilePayload(filename);
        RequestMessage request = new RequestMessage(requestId, RequestType.GET_FILE, clientQueue, payload);
        
        logger.info("Sending GET_FILE request: {}", filename);
        ResponseMessage response = sendRequestAndWait(request);
        
        if (response.getStatus() == ResponseStatus.OK) {
            return (FileContentPayload) response.getPayload();
        } else {
            throw new RuntimeException("Get file failed: " + response.getStatus());
        }
    }

    /**
     * Request aggregated statistics from all workers.
     */
    public StatisticsPayload getStats() throws Exception {
        String requestId = UUID.randomUUID().toString();
        RequestMessage request = new RequestMessage(requestId, RequestType.GET_STATS, clientQueue, null);
        
        logger.info("Sending GET_STATS request");
        ResponseMessage response = sendRequestAndWait(request);
        
        if (response.getStatus() == ResponseStatus.OK) {
            return (StatisticsPayload) response.getPayload();
        } else {
            throw new RuntimeException("Get stats failed: " + response.getStatus());
        }
    }

    private ResponseMessage sendRequestAndWait(RequestMessage request) throws Exception {
        CompletableFuture<ResponseMessage> future = new CompletableFuture<>();
        pendingRequests.put(request.getRequestId(), future);
        
        try {
            // Publish request to work queue
            byte[] messageBytes = JsonUtil.toJsonBytes(request);
            channel.basicPublish("", QueueConfig.REQUESTS_QUEUE, null, messageBytes);
            
            // Wait for response with timeout
            return future.get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            pendingRequests.remove(request.getRequestId());
            throw new RuntimeException("Request timed out after " + RESPONSE_TIMEOUT_SECONDS + " seconds");
        }
    }

    public void close() throws IOException, TimeoutException {
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
        if (connection != null && connection.isOpen()) {
            connection.close();
        }
        logger.info("UserApp closed");
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        String command = args[0].toLowerCase();
        String rabbitMqHost = System.getenv().getOrDefault("RABBITMQ_HOST", QueueConfig.DEFAULT_RABBITMQ_HOST);
        int rabbitMqPort = Integer.parseInt(System.getenv().getOrDefault("RABBITMQ_PORT", 
                                                                          String.valueOf(QueueConfig.DEFAULT_RABBITMQ_PORT)));

        try (UserApp app = new UserApp(rabbitMqHost, rabbitMqPort)) {
            switch (command) {
                case "search":
                    if (args.length < 2) {
                        System.err.println("Usage: search <substring1> [substring2 ...]");
                        System.exit(1);
                    }
                    List<String> substrings = Arrays.asList(Arrays.copyOfRange(args, 1, args.length));
                    SearchResultPayload searchResult = app.search(substrings);
                    Map<String, String> results = searchResult.getResults();
                    System.out.println("Found " + results.size() + " email(s) containing all substrings\n");
                    for (String filename : results.keySet()) {
                        System.out.println("##:" + filename);  // Anexo 2 format
                        System.out.println(results.get(filename));
                        System.out.println();
                    }
                    break;

                case "get-file":
                    if (args.length != 2) {
                        System.err.println("Usage: get-file <filename>");
                        System.exit(1);
                    }
                    String filename = args[1];
                    FileContentPayload fileContent = app.getFile(filename);
                    System.out.println("File: " + fileContent.getFilename());
                    System.out.println("Content:");
                    System.out.println(fileContent.getContent());
                    break;

                case "get-stats":
                    StatisticsPayload stats = app.getStats();
                    System.out.println("Statistics:");
                    System.out.println("  Total Requests: " + stats.getTotalRequests());
                    System.out.println("  Successful: " + stats.getSuccessfulRequests());
                    System.out.println("  Failed: " + stats.getFailedRequests());
                    break;

                default:
                    System.err.println("Unknown command: " + command);
                    printUsage();
                    System.exit(1);
            }
        } catch (Exception e) {
            logger.error("Error executing command", e);
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  search <substring1> [substring2 ...]  - Search for files containing all substrings");
        System.out.println("  get-file <filename>                   - Retrieve content of a file");
        System.out.println("  get-stats                             - Get aggregated statistics");
        System.out.println();
        System.out.println("Environment variables:");
        System.out.println("  RABBITMQ_HOST - RabbitMQ host (default: localhost)");
        System.out.println("  RABBITMQ_PORT - RabbitMQ port (default: 5672)");
    }
}
