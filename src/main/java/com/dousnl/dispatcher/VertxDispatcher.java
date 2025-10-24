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
                log.info("开始处理分发请求: {}", request.getRequestId());
                log.debug("请求详情: 方法={}, 路径={}, 请求体长度={}", 
                         request.getMethod(), request.getPath(), request.getBody().length());
                
                // 1. 确定目标服务
                String targetService = determineTargetService(request);
                log.info("确定目标服务: {} -> {}", request.getPath(), targetService);

                // 2. 获取可用项目
                List<VertxAggregateProject> availableProjects = getAvailableProjects(targetService);

                if (availableProjects.isEmpty()) {
                    log.error("没有可用的聚合项目: {}", targetService);
                    promise.complete(VertxDispatchResult.failure("没有可用的聚合项目: " + targetService));
                    return;
                }

                // 3. 负载均衡选择
                VertxAggregateProject selectedProject = loadBalancer.select(availableProjects, request);
                log.info("负载均衡选择项目: {} -> {}", targetService, selectedProject.getEndpoint());

                // 4. 执行分发
                executeDispatchAsync(request, selectedProject)
                        .onSuccess(result -> {
                            log.info("分发请求成功: {} -> {} ({}ms)", 
                                    request.getRequestId(), selectedProject.getEndpoint(), result.getProcessingTime());
                            promise.complete(result);
                        })
                        .onFailure(throwable -> {
                            log.error("分发请求失败: {} -> {}", 
                                    request.getRequestId(), selectedProject.getEndpoint(), throwable);
                            promise.fail(throwable);
                        });

            } catch (Exception e) {
                log.error("分发异常: {} - {}", request.getRequestId(), e.getMessage(), e);
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
        
        log.debug("确定目标服务 - Host: {}, Path: {}", host, path);

        // 基于域名和路径的路由规则
        if (host.contains("dushu.com")) {
            log.debug("匹配dushu.com域名");
            if (path.startsWith("/user-orch/")) {
                log.debug("匹配user-orch路径");
                return "user-orch";
            }
            if (path.startsWith("/order-orch/")) {
                log.debug("匹配order-orch路径");
                return "order-orch";
            }
        }

        // 兼容原有规则
        if (path.startsWith("/springboot-grpc-server/")) {
            log.debug("匹配springboot-grpc-server路径");
            return "springboot-grpc-server";
        } else if (path.startsWith("/order/")) {
            log.debug("匹配order-service路径");
            return "order-service";
        } else if (path.startsWith("/product/")) {
            log.debug("匹配product-service路径");
            return "product-service";
        } else if (path.startsWith("/payment/")) {
            log.debug("匹配payment-service路径");
            return "payment-service";
        }

        log.debug("使用默认服务");
        return "default-service";
    }

    /**
     * 获取可用项目
     */
    private List<VertxAggregateProject> getAvailableProjects(String serviceName) {
        List<VertxAggregateProject> allProjects = projectRegistry.getOrDefault(serviceName, new ArrayList<>());
        log.debug("服务 {} 注册的项目总数: {}", serviceName, allProjects.size());
        
        List<VertxAggregateProject> healthyProjects = allProjects.stream()
                .filter(VertxAggregateProject::isHealthy)
                .toList();
                
        log.debug("服务 {} 健康项目数: {}", serviceName, healthyProjects.size());
        
        if (healthyProjects.isEmpty()) {
            log.warn("服务 {} 没有健康的项目可用", serviceName);
            allProjects.forEach(project -> {
                log.debug("项目详情: {} - 健康状态: {}", project.getEndpoint(), project.isHealthy());
            });
        }
        
        return healthyProjects;
    }

    /**
     * 异步执行分发
     */
    private io.vertx.core.Future<VertxDispatchResult> executeDispatchAsync(
            VertxDispatchRequest request, VertxAggregateProject project) {

        return io.vertx.core.Future.future(promise -> {
            long startTime = System.currentTimeMillis();

            log.info("开始分发请求: {} -> {}", request.getRequestId(), project.getEndpoint());
            log.debug("请求详情 - 方法: {}, 路径: {}, 请求体: {}", 
                     request.getMethod(), request.getPath(), request.getBody());

            try {
                // 获取HTTP客户端
                HttpClient client = getOrCreateHttpClient(project.getEndpoint());
                log.debug("获取到HTTP客户端: {}", project.getEndpoint());

                // 构建请求
                HttpMethod httpMethod = HttpMethod.valueOf(request.getMethod());
                String fullUrl = project.getEndpoint() + request.getPath();
                
                log.info("构建完整URL: {} (方法: {})", fullUrl, httpMethod);
                log.debug("请求头: {}", request.getHeaders().encodePrettily());

                // 创建HTTP请求 - 非函数调用方式
                io.vertx.core.Future<HttpClientRequest> requestFuture = client.request(httpMethod, fullUrl);
                
                // 处理请求创建结果
                requestFuture.onComplete(ar -> {
                    if (ar.succeeded()) {
                        HttpClientRequest clientRequest = ar.result();
                        log.debug("HTTP请求创建成功: {}", fullUrl);
                        
                        // 添加请求头 - 过滤和修正不合适的请求头
                        log.debug("开始添加请求头...");
                        
                        // 先添加必要的API请求头
                        clientRequest.putHeader("Content-Type", "application/json");
                        clientRequest.putHeader("Accept", "application/json");
                        log.debug("添加默认请求头: Content-Type = application/json");
                        log.debug("添加默认请求头: Accept = application/json");
                        
                        request.getHeaders().forEach(entry -> {
                            String key = entry.getKey();
                            String value = String.valueOf(entry.getValue());
                            
                            // 过滤掉浏览器特有的请求头
                            if (shouldFilterHeader(key)) {
                                log.debug("过滤请求头: {} = {}", key, value);
                                return;
                            }
                            
                            // 修正Host头
                            if ("Host".equalsIgnoreCase(key)) {
                                // 从endpoint中提取正确的host和port
                                try {
                                    java.net.URL url = new java.net.URL(project.getEndpoint());
                                    String correctHost = url.getHost() + ":" + (url.getPort() == -1 ? url.getDefaultPort() : url.getPort());
                                    clientRequest.putHeader("Host", correctHost);
                                    log.debug("修正Host请求头: {} -> {}", value, correctHost);
                                } catch (Exception e) {
                                    log.warn("无法解析endpoint，使用原始Host头: {}", value);
                                    clientRequest.putHeader(key, value);
                                }
                            } else if ("Content-Type".equalsIgnoreCase(key) || "Accept".equalsIgnoreCase(key)) {
                                // 覆盖默认的Content-Type和Accept
                                clientRequest.putHeader(key, value);
                                log.debug("覆盖请求头: {} = {}", key, value);
                            } else {
                                clientRequest.putHeader(key, value);
                                log.debug("添加请求头: {} = {}", key, value);
                            }
                        });

                        // 发送请求体
                        io.vertx.core.buffer.Buffer requestBody = io.vertx.core.buffer.Buffer.buffer(request.getBody());
                        log.debug("发送请求体，大小: {} bytes", requestBody.length());
                        
                        // 发送请求
                        io.vertx.core.Future<io.vertx.core.http.HttpClientResponse> sendFuture = clientRequest.send(requestBody);
                        
                        // 处理发送结果
                        sendFuture.onComplete(sendAr -> {
                            if (sendAr.succeeded()) {
                                io.vertx.core.http.HttpClientResponse response = sendAr.result();
                                long duration = System.currentTimeMillis() - startTime;
                                
                                log.info("HTTP请求发送成功: {} -> {} ({}ms) [状态码: {}]", 
                                        request.getRequestId(), project.getEndpoint(), duration, response.statusCode());
                                
                                // 处理响应体
                                response.bodyHandler(body -> {
                                    String responseBody = body.toString();
                                    log.debug("收到响应体，大小: {} bytes", responseBody.length());
                                    log.debug("响应体内容: {}", responseBody);

                                    // 记录请求结果
                                    boolean isSuccess = response.statusCode() < 400;
                                    healthChecker.recordRequestResult(project.getServiceName(), isSuccess);
                                    
                                    if (!isSuccess) {
                                        log.warn("请求返回错误状态码: {} - 响应体: {}", response.statusCode(), responseBody);
                                    }

                                    // 收集响应头
                                    Map<String, Object> responseHeaders = new HashMap<>();
                                    response.headers().forEach(entry -> {
                                        responseHeaders.put(entry.getKey(), entry.getValue());
                                        log.debug("响应头: {} = {}", entry.getKey(), entry.getValue());
                                    });

                                    VertxDispatchResult result = VertxDispatchResult.success(
                                            responseBody, project.getEndpoint(), duration, 
                                            response.statusCode(), responseHeaders
                                    );

                                    log.info("请求完成: {} -> {} ({}ms) [{}]", 
                                            request.getRequestId(), project.getEndpoint(), duration, response.statusCode());

                                    promise.complete(result);
                                });
                                
                                // 处理响应体读取失败
                                response.exceptionHandler(throwable -> {
                                    long duration1 = System.currentTimeMillis() - startTime;
                                    log.error("读取响应体失败: {}", throwable.getMessage(), throwable);
                                    
                                    healthChecker.recordRequestResult(project.getServiceName(), false);
                                    promise.complete(VertxDispatchResult.failure(
                                            "读取响应体失败: " + throwable.getMessage(), duration1
                                    ));
                                });
                                
                            } else {
                                // 发送请求失败
                                long duration = System.currentTimeMillis() - startTime;
                                Throwable throwable = sendAr.cause();
                                
                                log.error("HTTP请求发送失败: {} -> {} ({}ms)", 
                                        request.getRequestId(), project.getEndpoint(), duration, throwable);
                                log.error("发送失败详情: {}", throwable.getMessage(), throwable);

                                // 记录失败
                                healthChecker.recordRequestResult(project.getServiceName(), false);

                                promise.complete(VertxDispatchResult.failure(
                                        "HTTP请求发送失败: " + throwable.getMessage(), duration
                                ));
                            }
                        });
                        
                    } else {
                        // 创建HTTP请求失败
                        long duration = System.currentTimeMillis() - startTime;
                        Throwable throwable = ar.cause();
                        
                        log.error("创建HTTP请求失败: {} -> {} ({}ms)", 
                                request.getRequestId(), project.getEndpoint(), duration, throwable);
                        log.error("创建失败详情: {}", throwable.getMessage(), throwable);

                        promise.complete(VertxDispatchResult.failure(
                                "创建HTTP请求失败: " + throwable.getMessage(), duration
                        ));
                    }
                });
                
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                log.error("执行分发时发生异常: {} -> {} ({}ms)", 
                        request.getRequestId(), project.getEndpoint(), duration, e);
                log.error("异常详情: {}", e.getMessage(), e);
                
                promise.complete(VertxDispatchResult.failure(
                        "执行分发异常: " + e.getMessage(), duration
                ));
            }
        });
    }

    /**
     * 判断是否应该过滤请求头
     */
    private boolean shouldFilterHeader(String headerName) {
        if (headerName == null) {
            return true;
        }
        
        String lowerName = headerName.toLowerCase();
        
        // 过滤浏览器特有的请求头
        return lowerName.startsWith("sec-") ||           // 浏览器安全策略头
               lowerName.startsWith("sec-ch-") ||       // 浏览器客户端提示头
               "upgrade-insecure-requests".equals(lowerName) ||  // 浏览器安全升级
               "sec-fetch-site".equals(lowerName) ||     // 浏览器安全策略
               "sec-fetch-mode".equals(lowerName) ||     // 浏览器安全策略
               "sec-fetch-dest".equals(lowerName) ||     // 浏览器安全策略
               "sec-fetch-user".equals(lowerName) ||     // 浏览器安全策略
               "dnt".equals(lowerName) ||                // Do Not Track
               "save-data".equals(lowerName);            // 数据保存偏好
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
     * 注册聚合项目
     */
    public void unRegisterProject(String serviceName, VertxAggregateProject project) {

        List<VertxAggregateProject> vertxAggregateProjects = projectRegistry.get(serviceName);
        if (vertxAggregateProjects != null) {
            vertxAggregateProjects.remove(project);
        }
        log.info("注销聚合项目: {} -> {}", serviceName, project.getEndpoint());
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

    public void handleUnRegister(RoutingContext ctx) {
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
        unRegisterProject(serviceName, project);

        JsonObject response = new JsonObject()
                .put("message", "聚合项目注销成功: " + serviceName + " -> " + endpoint);

        ctx.response()
                .putHeader("Content-Type", "application/json")
                .end(response.encode());
    }
}

