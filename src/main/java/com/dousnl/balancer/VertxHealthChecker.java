package com.dousnl.balancer;

import com.dousnl.dispatcher.VertxDispatcher;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// 在文件顶部导入 JSON 类
import com.alibaba.fastjson.JSON;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Vert.x 健康检查和熔断器
 * 提供异步健康检查和熔断机制
 */
public class VertxHealthChecker {

    private static final Logger log = LoggerFactory.getLogger(VertxHealthChecker.class);

    private final Vertx vertx;
    private final Map<String, VertxCircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    // 健康检查间隔（毫秒）
    private static final long HEALTH_CHECK_INTERVAL = 50000; // 50秒

    public VertxHealthChecker(Vertx vertx) {
        this.vertx = vertx;
    }

    /**
     * 启动健康检查
     */
    public void start() {
        // 启动定时健康检查任务
        vertx.setPeriodic(HEALTH_CHECK_INTERVAL, id -> {
            performHealthCheck();
        });

        log.info("健康检查已启动，间隔: {}ms", HEALTH_CHECK_INTERVAL);
    }

    /**
     * 执行健康检查
     */
    private void performHealthCheck() {
        log.debug("开始执行健康检查...");

        circuitBreakers.forEach((serviceName, circuitBreaker) -> {
            vertx.executeBlocking(promise -> {
                try {
                    boolean isHealthy = checkServiceHealth(serviceName);
                    circuitBreaker.updateHealthStatus(isHealthy);

                    if (isHealthy) {
                        log.debug("服务 {} 健康检查通过", serviceName);
                        Map registry = VertxDispatcher.getRegistry();
                        registry.forEach((key, value) -> {log.info("服务：{},服务地址：{}", key, JSON.toJSONString(value));});
                    } else {
                        log.warn("服务 {} 健康检查失败", serviceName);
                    }

                    promise.complete();
                } catch (Exception e) {
                    log.error("服务 {} 健康检查异常: {}", serviceName, e.getMessage());
                    circuitBreaker.updateHealthStatus(false);
                    promise.fail(e);
                }
            });
        });
    }

    /**
     * 检查单个服务健康状态
     */
    private boolean checkServiceHealth(String serviceName) {
        try {
            // 模拟健康检查逻辑
            // 实际实现中，这里应该发送HTTP请求到服务的健康检查端点

            // 模拟网络延迟
            Thread.sleep(100 + (int)(Math.random() * 200));

            // 模拟90%的成功率
            return Math.random() > 0.1;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 注册服务的熔断器
     */
    public void registerCircuitBreaker(String serviceName) {
        circuitBreakers.putIfAbsent(serviceName, new VertxCircuitBreaker(serviceName));
        log.info("注册服务熔断器: {}", serviceName);
    }

    /**
     * 检查服务是否可用（考虑熔断状态）
     */
    public boolean isServiceAvailable(String serviceName) {
        VertxCircuitBreaker circuitBreaker = circuitBreakers.get(serviceName);
        if (circuitBreaker == null) {
            return true; // 没有熔断器，默认可用
        }

        return circuitBreaker.isRequestAllowed();
    }

    /**
     * 记录请求结果
     */
    public void recordRequestResult(String serviceName, boolean success) {
        VertxCircuitBreaker circuitBreaker = circuitBreakers.get(serviceName);
        if (circuitBreaker != null) {
            circuitBreaker.recordRequest(success);
        }
    }

    /**
     * 处理熔断器状态查询
     */
    public void handleCircuitBreakerStatus(RoutingContext ctx) {
        JsonObject status = new JsonObject();

        circuitBreakers.forEach((serviceName, circuitBreaker) -> {
            JsonObject breakerStatus = new JsonObject();
            breakerStatus.put("state", circuitBreaker.getState().name());
            breakerStatus.put("failureCount", circuitBreaker.getFailureCount());
            breakerStatus.put("successCount", circuitBreaker.getSuccessCount());
            breakerStatus.put("lastFailureTime", circuitBreaker.getLastFailureTime());
            status.put(serviceName, breakerStatus);
        });

        ctx.response()
                .putHeader("Content-Type", "application/json")
                .end(status.encode());
    }
}

/**
 * Vert.x 熔断器实现
 */
class VertxCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(VertxCircuitBreaker.class);

    // 熔断器状态
    public enum State {
        CLOSED,    // 关闭状态，正常处理请求
        OPEN,      // 开启状态，拒绝所有请求
        HALF_OPEN  // 半开状态，允许部分请求通过
    }

    private final String serviceName;
    private volatile State state = State.CLOSED;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);

    // 熔断阈值
    private static final int FAILURE_THRESHOLD = 5;
    private static final long TIMEOUT_MS = 60000; // 1分钟
    private static final int HALF_OPEN_MAX_REQUESTS = 3;

    public VertxCircuitBreaker(String serviceName) {
        this.serviceName = serviceName;
    }

    /**
     * 检查是否允许请求
     */
    public boolean isRequestAllowed() {
        switch (state) {
            case CLOSED:
                return true;

            case OPEN:
                if (System.currentTimeMillis() - lastFailureTime.get() > TIMEOUT_MS) {
                    state = State.HALF_OPEN;
                    successCount.set(0);
                    log.info("熔断器 {} 进入半开状态", serviceName);
                    return true;
                }
                return false;

            case HALF_OPEN:
                return successCount.get() < HALF_OPEN_MAX_REQUESTS;

            default:
                return false;
        }
    }

    /**
     * 记录请求结果
     */
    public void recordRequest(boolean success) {
        if (success) {
            onSuccess();
        } else {
            onFailure();
        }
    }

    /**
     * 请求成功处理
     */
    private void onSuccess() {
        successCount.incrementAndGet();
        failureCount.set(0);

        if (state == State.HALF_OPEN) {
            if (successCount.get() >= HALF_OPEN_MAX_REQUESTS) {
                state = State.CLOSED;
                log.info("熔断器 {} 恢复正常，进入关闭状态", serviceName);
            }
        }
    }

    /**
     * 请求失败处理
     */
    private void onFailure() {
        int failures = failureCount.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());

        if (failures >= FAILURE_THRESHOLD) {
            state = State.OPEN;
            log.warn("熔断器 {} 触发熔断，进入开启状态", serviceName);
        }
    }

    /**
     * 更新健康状态
     */
    public void updateHealthStatus(boolean healthy) {
        recordRequest(healthy);
    }

    // Getters
    public State getState() { return state; }
    public int getFailureCount() { return failureCount.get(); }
    public int getSuccessCount() { return successCount.get(); }
    public long getLastFailureTime() { return lastFailureTime.get(); }
}
