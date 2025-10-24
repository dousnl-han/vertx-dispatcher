package com.dousnl.bean;

import io.vertx.core.json.JsonObject;
import java.util.Map;
import java.util.HashMap;

/**
 * Vert.x 分发结果对象
 */
public class VertxDispatchResult {

    private final boolean success;
    private final String message;
    private final String targetEndpoint;
    private final long processingTime;
    private final int statusCode;
    private final Map<String, Object> responseHeaders;

    private VertxDispatchResult(boolean success, String message, String targetEndpoint, 
                              long processingTime, int statusCode, Map<String, Object> responseHeaders) {
        this.success = success;
        this.message = message;
        this.targetEndpoint = targetEndpoint;
        this.processingTime = processingTime;
        this.statusCode = statusCode;
        this.responseHeaders = responseHeaders != null ? responseHeaders : new HashMap<>();
    }

    public static VertxDispatchResult success(String message, String targetEndpoint, long processingTime) {
        return new VertxDispatchResult(true, message, targetEndpoint, processingTime, 200, new HashMap<>());
    }

    public static VertxDispatchResult success(String message, String targetEndpoint, 
                                             long processingTime, int statusCode, Map<String, Object> headers) {
        return new VertxDispatchResult(true, message, targetEndpoint, processingTime, statusCode, headers);
    }

    public static VertxDispatchResult failure(String message) {
        return new VertxDispatchResult(false, message, null, System.currentTimeMillis(), 500, new HashMap<>());
    }

    public static VertxDispatchResult failure(String message, long processingTime) {
        return new VertxDispatchResult(false, message, null, processingTime, 500, new HashMap<>());
    }

    public boolean isSuccess() { return success; }
    public String getMessage() { return message; }
    public String getTargetEndpoint() { return targetEndpoint; }
    public long getProcessingTime() { return processingTime; }
    public int getStatusCode() { return statusCode; }
    public Map<String, Object> getResponseHeaders() { return responseHeaders; }
}
