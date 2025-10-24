package com.dousnl.bean;

import io.vertx.core.json.JsonObject;

/**
 * Vert.x 分发请求对象
 */
public class VertxDispatchRequest {

    private final String requestId;
    private final String path;
    private final String method;
    private final JsonObject headers;
    private final String body;
    private final JsonObject parameters;

    public VertxDispatchRequest(String requestId, String path, String method,
                                JsonObject headers, String body, JsonObject parameters) {
        this.requestId = requestId;
        this.path = path;
        this.method = method;
        this.headers = headers != null ? headers : new JsonObject();
        this.body = body != null ? body : "";
        this.parameters = parameters != null ? parameters : new JsonObject();
    }

    public String getRequestId() { return requestId; }
    public String getPath() { return path; }
    public String getMethod() { return method; }
    public JsonObject getHeaders() { return headers; }
    public String getBody() { return body; }
    public JsonObject getParameters() { return parameters; }
}





