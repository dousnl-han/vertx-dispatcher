package com.dousnl.dispatcher;

import com.dousnl.balancer.VertxHealthChecker;
import com.dousnl.balancer.VertxLoadBalancer;
import com.dousnl.bean.VertxAggregateProject;
import com.dousnl.bean.VertxDispatchRequest;
import com.dousnl.bean.VertxDispatchResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vert.x 路由分发器
 * 负责请求路由、负载均衡和下游调用
 */
public class VertxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(VertxDispatcher.class);

    private final Vertx vertx;
    private final VertxLoadBalancer loadBalancer;
    private final VertxHealthChecker healthChecker;

    // 聚合项目注册表
    private final static Map<String, List<VertxAggregateProject>> projectRegistry = new ConcurrentHashMap<>();

    // HTTP客户端池
    private final Map<String, HttpClient> clientPool = new ConcurrentHashMap<>();

    public VertxDispatcher(Vertx vertx, VertxLoadBalancer loadBalancer, VertxHealthChecker healthChecker) {
        this.vertx = vertx;
        this.loadBalancer = loadBalancer;
        this.healthChecker = healthChecker;
    }

    public static Map getRegistry() {
        return projectRegistry;
    }

    /**
     * 处理直接路径分发
     */
    public void handleDirectDispatch(RoutingContext ctx) {
        String requestId = UUID.randomUUID().toString();
        String path = ctx.request().path();
        String method = ctx.request().method().name();
        String body = ctx.body().asString();
        
        // 构建请求头
        JsonObject headers = new JsonObject();
        ctx.request().headers().forEach(entry -> {
            headers.put(entry.getKey(), entry.getValue());
        });
        
        // 构建参数
        JsonObject parameters = new JsonObject();
        ctx.queryParams().forEach(param -> {
            parameters.put(param.getKey(), param.getValue());
        });

        // 创建分发请求对象
        VertxDispatchRequest request = new VertxDispatchRequest(
                requestId, path, method, headers, body, parameters
        );

        log.info("直接路径分发请求: {} {} -> {}", method, path, headers);

        // 异步分发请求
        dispatchRequestAsync(request)
                .onSuccess(result -> {
                    if (result.isSuccess()) {
                        // 设置响应头
                        Map<String, Object> responseHeaders = result.getResponseHeaders();
                        responseHeaders.forEach((key, value) -> ctx.response().putHeader(key, String.valueOf(value)));
                        ctx.response()
                                .setStatusCode(result.getStatusCode())
                                .end(result.getMessage());
                    } else {
                        ctx.response()
                                .setStatusCode(500)
                                .putHeader("Content-Type", "application/json")
                                .end(new JsonObject()
                                        .put("error", result.getMessage())
                                        .put("requestId", requestId)
                                        .encode());
                    }
                })
                .onFailure(throwable -> {
                    log.error("直接分发请求失败: {}", throwable.getMessage(), throwable);
                    ctx.response()
                            .setStatusCode(500)
                            .putHeader("Content-Type", "application/json")
                            .end(new JsonObject()
                                    .put("error", "分发失败: " + throwable.getMessage())
                                    .put("requestId", requestId)
                                    .encode());
                });
    }

    /**
     * 处理分发请求
     */
    public void handleDispatch(RoutingContext ctx) {
        JsonObject requestData = ctx.getBodyAsJson();
        if (requestData == null) {
            ctx.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("error", "请求体不能为空").encode());
            return;
        }

        String requestId = UUID.randomUUID().toString();
        String path = requestData.getString("path", "/");
        String method = requestData.getString("method", "GET");
        String body = requestData.getString("body", "");
        JsonObject headers = requestData.getJsonObject("headers", new JsonObject());
        JsonObject parameters = requestData.getJsonObject("parameters", new JsonObject());

        // 从请求头中获取Host
        String host = ctx.request().getHeader("Host");
        if (host != null) {
            headers.put("Host", host);
        }

        // 创建分发请求对象
        VertxDispatchRequest request = new VertxDispatchRequest(
                requestId, path, method, headers, body, parameters
        );

        // 异步分发请求
        dispatchRequestAsync(request)
                .onSuccess(result -> {
                    JsonObject response = new JsonObject()
                            .put("requestId", requestId)
                            .put("success", result.isSuccess())
                            .put("message", result.getMessage())
                            .put("targetEndpoint", result.getTargetEndpoint() != null ? result.getTargetEndpoint() : "")
                            .put("processingTime", result.getProcessingTime());

                    ctx.response()
                            .putHeader("Content-Type", "application/json")
                            .end(response.encode());
                })
                .onFailure(throwable -> {
                    log.error("分发请求失败: {}", throwable.getMessage(), throwable);
                    JsonObject errorResponse = new JsonObject()
                            .put("requestId", requestId)
                            .put("success", false)
                            .put("message", "分发失败: " + throwable.getMessage());

                    ctx.response()
                            .setStatusCode(500)
                            .putHeader("Content-Type", "application/json")
                            .end(errorResponse.encode());
                });
    }

    /**
     * 异步分发请求
     */
    public io.vertx.core.Future<VertxDispatchResult> dispatchRequestAsync(VertxDispatchRequest request) {
        return io.vertx.core.Future.future(promise -> {
            try {
                // 1. 确定目标服务
                String targetService = determineTargetService(request);
                log.debug("目标服务: {} -> {}", request.getPath(), targetService);

                // 2. 获取可用项目
                List<VertxAggregateProject> availableProjects = getAvailableProjects(targetService);

                if (availableProjects.isEmpty()) {
                    promise.complete(VertxDispatchResult.failure("没有可用的聚合项目: " + targetService));
                    return;
                }

                // 3. 负载均衡选择
                VertxAggregateProject selectedProject = loadBalancer.select(availableProjects, request);

                // 4. 执行分发
                executeDispatchAsync(request, selectedProject)
                        .onSuccess(promise::complete)
                        .onFailure(promise::fail);

            } catch (Exception e) {
                log.error("分发异常: {}", e.getMessage(), e);
                promise.complete(VertxDispatchResult.failure("分发异常: " + e.getMessage()));
            }
        });
    }

    /**
     * 确定目标服务
     */
    private String determineTargetService(VertxDispatchRequest request) {
        String host = request.getHeaders().getString("Host", "").toLowerCase();
        String path = request.getPath();

        // 基于域名和路径的路由规则
        if (host.contains("dushu.com")) {
            if (path.startsWith("/user-orch/")) {
                return "user-orch";
            }
            if (path.startsWith("/order-orch/")) {
                return "order-orch";
            }
        }

        // 兼容原有规则

        if (path.startsWith("/springboot-grpc-server/")) {
            return "springboot-grpc-server";
        } else if (path.startsWith("/order/")) {
            return "order-service";
        } else if (path.startsWith("/product/")) {
            return "product-service";
        } else if (path.startsWith("/payment/")) {
            return "payment-service";
        }

        return "default-service";
    }

    /**
     * 获取可用项目
     */
    private List<VertxAggregateProject> getAvailableProjects(String serviceName) {
        List<VertxAggregateProject> projects = projectRegistry.getOrDefault(serviceName, new ArrayList<>());
        return projects.stream()
                .filter(VertxAggregateProject::isHealthy)
                .toList();
    }

    /**
     * 异步执行分发
     */
    private io.vertx.core.Future<VertxDispatchResult> executeDispatchAsync(
            VertxDispatchRequest request, VertxAggregateProject project) {

        return io.vertx.core.Future.future(promise -> {
            long startTime = System.currentTimeMillis();

            log.info("分发请求: {} -> {}", request.getRequestId(), project.getEndpoint());

            // 获取HTTP客户端
            HttpClient client = getOrCreateHttpClient(project.getEndpoint());

            // 构建请求
            HttpMethod httpMethod = HttpMethod.valueOf(request.getMethod());
            String fullUrl = project.getEndpoint() + request.getPath();
            //Future<HttpClientRequest> clientRequest = client.request(httpMethod, project.getEndpoint() + request.getPath());


            // client.request() 返回 Future<HttpClientRequest>
            client.request(httpMethod, fullUrl)
                    .onSuccess(clientRequest -> {
                        // 添加请求头
                        request.getHeaders().forEach(entry -> {
                            clientRequest.putHeader(entry.getKey(), String.valueOf(entry.getValue()));
                        });

                        // 发送请求
                        clientRequest.send(io.vertx.core.buffer.Buffer.buffer(request.getBody()))
                                .onSuccess(response -> {
                                    long duration = System.currentTimeMillis() - startTime;

                                    response.bodyHandler(body -> {
                                        String responseBody = body.toString();

                                        // 记录请求结果
                                        healthChecker.recordRequestResult(project.getServiceName(), response.statusCode() < 400);

                                        // 收集响应头
                                        Map<String, Object> responseHeaders = new HashMap<>();
                                        response.headers().forEach(entry -> {
                                            responseHeaders.put(entry.getKey(), entry.getValue());
                                        });

                                        VertxDispatchResult result = VertxDispatchResult.success(
                                                responseBody, project.getEndpoint(), duration, 
                                                response.statusCode(), responseHeaders
                                        );

                                        log.debug("请求完成: {} -> {} ({}ms) [{}]", request.getRequestId(), project.getEndpoint(), duration, response.statusCode());

                                        promise.complete(result);
                                    });
                                })
                                .onFailure(throwable -> {
                                    long duration = System.currentTimeMillis() - startTime;

                                    log.error("HTTP请求失败", throwable);

                                    // 记录失败
                                    healthChecker.recordRequestResult(project.getServiceName(), false);

                                    promise.complete(VertxDispatchResult.failure(
                                            "HTTP请求失败: " + throwable.getMessage(), duration
                                    ));
                                });
                    })
                    .onFailure(throwable -> {
                        long duration = System.currentTimeMillis() - startTime;

                        log.error("创建HTTP请求失败", throwable);

                        promise.complete(VertxDispatchResult.failure(
                                "创建HTTP请求失败: " + throwable.getMessage(), duration
                        ));
                    });
        });
    }

    /**
     * 获取或创建HTTP客户端
     */
    private HttpClient getOrCreateHttpClient(String endpoint) {
        return clientPool.computeIfAbsent(endpoint, this::createHttpClient);
    }

    /**
     * 创建HTTP客户端
     */
    private HttpClient createHttpClient(String endpoint) {
        try {
            // 从endpoint解析host和port
            java.net.URL url = new java.net.URL(endpoint);
            String host = url.getHost();
            int port = url.getPort();
            if (port == -1) {
                port = url.getDefaultPort();
            }
            
            log.info("创建HTTP客户端: {}:{}", host, port);
            
            HttpClientOptions options = new HttpClientOptions()
                    .setConnectTimeout(50000)  // 50秒连接超时
                    .setIdleTimeout(30)         // 30秒空闲超时
                    .setKeepAlive(true)        // 保持连接
                    .setMaxPoolSize(20)        // 最大连接池大小
                    .setPoolCleanerPeriod(50000) // 50秒清理周期
                    .setDefaultPort(port)      // 使用解析出的端口
                    .setDefaultHost(host);     // 使用解析出的主机

            return vertx.createHttpClient(options);
        } catch (Exception e) {
            log.error("解析endpoint失败: {}, 使用默认配置", endpoint, e);
            // 如果解析失败，使用默认配置
            HttpClientOptions options = new HttpClientOptions()
                    .setConnectTimeout(50000)
                    .setIdleTimeout(30)
                    .setKeepAlive(true)
                    .setMaxPoolSize(20)
                    .setPoolCleanerPeriod(50000)
                    .setDefaultPort(8081)      // 设置默认端口
                    .setDefaultHost("localhost"); // 设置默认主机
            return vertx.createHttpClient(options);
        }
    }

    /**
     * 注册聚合项目
     */
    public void registerProject(String serviceName, VertxAggregateProject project) {
        projectRegistry.computeIfAbsent(serviceName, k -> new ArrayList<>()).add(project);
        healthChecker.registerCircuitBreaker(serviceName);
        log.info("注册聚合项目: {} -> {}", serviceName, project.getEndpoint());
    }

    /**
     * 处理注册请求
     */
    public void handleRegister(RoutingContext ctx) {
        JsonObject projectData = ctx.getBodyAsJson();
        if (projectData == null) {
            ctx.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("error", "请求体不能为空").encode());
            return;
        }

        String serviceName = projectData.getString("serviceName");
        String projectName = projectData.getString("projectName");
        String endpoint = projectData.getString("endpoint");

        if (serviceName == null || projectName == null || endpoint == null) {
            ctx.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("error", "缺少必要参数").encode());
            return;
        }

        VertxAggregateProject project = new VertxAggregateProject(projectName, endpoint, serviceName);
        registerProject(serviceName, project);

        JsonObject response = new JsonObject()
                .put("message", "聚合项目注册成功: " + serviceName + " -> " + endpoint);

        ctx.response()
                .putHeader("Content-Type", "application/json")
                .end(response.encode());
    }

    /**
     * 处理状态查询
     */
    public void handleStatus(RoutingContext ctx) {
        JsonObject status = new JsonObject();

        projectRegistry.forEach((serviceName, projects) -> {
            JsonObject serviceStatus = new JsonObject();
            serviceStatus.put("totalProjects", projects.size());
            serviceStatus.put("healthyProjects", projects.stream().mapToInt(p -> p.isHealthy() ? 1 : 0).sum());
            serviceStatus.put("endpoints", projects.stream().map(VertxAggregateProject::getEndpoint).toList());
            status.put(serviceName, serviceStatus);
        });

        ctx.response()
                .putHeader("Content-Type", "application/json")
                .end(status.encode());
    }

    /**
     * 处理测试分发
     */
    public void handleTestDispatch(RoutingContext ctx) {
        VertxDispatchRequest request = new VertxDispatchRequest(
                UUID.randomUUID().toString(),
                "/user-orch/profile",
                "GET",
                new JsonObject().put("Host", "dushu.com"),
                "",
                new JsonObject()
        );

        dispatchRequestAsync(request)
                .onSuccess(result -> {
                    String message = result.isSuccess() ?
                            "测试分发成功: " + result.getMessage() :
                            "测试分发失败: " + result.getMessage();

                    ctx.response()
                            .putHeader("Content-Type", "application/json")
                            .end(new JsonObject().put("message", message).encode());
                })
                .onFailure(throwable -> {
                    ctx.response()
                            .setStatusCode(500)
                            .putHeader("Content-Type", "application/json")
                            .end(new JsonObject().put("error", "测试失败: " + throwable.getMessage()).encode());
                });
    }
}

