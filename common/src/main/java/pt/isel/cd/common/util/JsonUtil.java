package pt.isel.cd.common.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import pt.isel.cd.common.model.*;

import java.nio.charset.StandardCharsets;

/**
 * Utility class for JSON serialization/deserialization using Gson.
 */
public class JsonUtil {
    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    /**
     * Serialize an object to JSON string.
     */
    public static String toJson(Object obj) {
        return gson.toJson(obj);
    }

    /**
     * Serialize an object to JSON byte array.
     */
    public static byte[] toJsonBytes(Object obj) {
        return toJson(obj).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Deserialize JSON string to object.
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        return gson.fromJson(json, clazz);
    }

    /**
     * Deserialize JSON byte array to object.
     */
    public static <T> T fromJsonBytes(byte[] bytes, Class<T> clazz) {
        String json = new String(bytes, StandardCharsets.UTF_8);
        return fromJson(json, clazz);
    }

    /**
     * Deserialize RequestMessage and cast payload to appropriate type.
     */
    public static RequestMessage parseRequest(byte[] bytes) {
        RequestMessage msg = fromJsonBytes(bytes, RequestMessage.class);
        
        // Convert payload to proper type based on request type
        if (msg.getPayload() != null) {
            String payloadJson = toJson(msg.getPayload());
            switch (msg.getType()) {
                case SEARCH:
                    msg.setPayload(fromJson(payloadJson, SearchPayload.class));
                    break;
                case GET_FILE:
                    msg.setPayload(fromJson(payloadJson, FilePayload.class));
                    break;
                case GET_STATS:
                    // No payload for GET_STATS
                    msg.setPayload(null);
                    break;
            }
        }
        
        return msg;
    }

    /**
     * Deserialize ResponseMessage and cast payload to appropriate type.
     */
    public static ResponseMessage parseResponse(byte[] bytes) {
        ResponseMessage msg = fromJsonBytes(bytes, ResponseMessage.class);
        
        // Convert payload to proper type based on response type
        if (msg.getPayload() != null) {
            String payloadJson = toJson(msg.getPayload());
            switch (msg.getType()) {
                case SEARCH_RESULT:
                    msg.setPayload(fromJson(payloadJson, SearchResultPayload.class));
                    break;
                case FILE_CONTENT:
                    msg.setPayload(fromJson(payloadJson, FileContentPayload.class));
                    break;
                case STATISTICS:
                    msg.setPayload(fromJson(payloadJson, StatisticsPayload.class));
                    break;
            }
        }
        
        return msg;
    }

    private JsonUtil() {
        // Utility class
    }
}
