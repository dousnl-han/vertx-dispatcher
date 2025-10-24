package com.dousnl;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
import com.dousnl.balancer.VertxHealthChecker;
import com.dousnl.balancer.VertxLoadBalancer;
import com.dousnl.dispatcher.VertxDispatcher;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vert.x 高性能网关主类
 * 基于异步非阻塞架构，支持高并发请求处理
 */
public class VertxGatewayMain extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(VertxGatewayMain.class);

    private static final int PORT = 8080;

    private HttpServer server;
    private VertxDispatcher dispatcher;
    private VertxLoadBalancer loadBalancer;
    private VertxHealthChecker healthChecker;

    @Override
    public void start(Promise<Void> startPromise) {
        try {
            // 初始化组件
            initializeComponents();

            // 创建路由器
            Router router = createRouter();

            // 启动HTTP服务器
            server = vertx.createHttpServer();
            server.requestHandler(router)
                    .listen(PORT)
                    .onSuccess(server -> {
                        log.info("Vert.x 网关启动成功，端口: {}", PORT);
                        startPromise.complete();
                    })
                    .onFailure(throwable -> {
                        log.error("网关启动失败", throwable);
                        startPromise.fail(throwable);
                    });

        } catch (Exception e) {
            log.error("网关初始化失败", e);
            startPromise.fail(e);
        }
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        if (server != null) {
            server.close()
                    .onSuccess(v -> {
                        log.info("网关服务器已关闭");
                        stopPromise.complete();
                    })
                    .onFailure(throwable -> {
                        log.error("关闭服务器失败", throwable);
                        stopPromise.fail(throwable);
                    });
        } else {
            stopPromise.complete();
        }
    }

    /**
     * 初始化核心组件
     */
    private void initializeComponents() {
        this.loadBalancer = new VertxLoadBalancer(vertx);
        this.healthChecker = new VertxHealthChecker(vertx);
        this.dispatcher = new VertxDispatcher(vertx, loadBalancer, healthChecker);

        // 启动健康检查
        healthChecker.start();

        log.info("核心组件初始化完成");
    }

    /**
     * 创建路由器
     */
    private Router createRouter() {
        Router router = Router.router(vertx);

        // 添加CORS支持
        router.route().handler(CorsHandler.create("*")
                .allowedMethods(java.util.Set.copyOf(io.vertx.core.http.HttpMethod.values()))
                .allowedHeaders(java.util.Set.of("*"))
                .allowCredentials(true));

        // 添加请求体处理
        router.route().handler(BodyHandler.create());

        // 注册路由
        registerRoutes(router);

        return router;
    }

    /**
     * 注册所有路由
     */
    private void registerRoutes(Router router) {
        // 直接路径分发 - 支持所有HTTP方法
        router.route("/user-orch/*").handler(dispatcher::handleDirectDispatch);
        router.route("/order-orch/*").handler(dispatcher::handleDirectDispatch);
        router.route("/product/*").handler(dispatcher::handleDirectDispatch);
        router.route("/payment/*").handler(dispatcher::handleDirectDispatch);
        router.route("/springboot-grpc-server/*").handler(dispatcher::handleDirectDispatch);

        // 分发请求 - 核心功能
        router.post("/gateway/dispatch").handler(dispatcher::handleDispatch);

        // 注册聚合项目
        router.post("/gateway/register").handler(dispatcher::handleRegister);

        // 获取服务状态
        router.get("/gateway/status").handler(dispatcher::handleStatus);

        // 获取熔断器状态
        router.get("/gateway/circuit-breaker-status").handler(healthChecker::handleCircuitBreakerStatus);

        // 测试分发
        router.get("/gateway/test-dispatch").handler(dispatcher::handleTestDispatch);

        // 健康检查端点
        router.get("/health").handler(ctx -> {
            JsonObject health = new JsonObject()
                    .put("status", "UP")
                    .put("timestamp", System.currentTimeMillis())
                    .put("gateway", "Vert.x Gateway");
            ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end(health.encode());
        });

        log.info("路由注册完成");
    }

    /**
     * 主入口方法
     */
    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new VertxGatewayMain())
                .onSuccess(id -> log.info("Vert.x 网关部署成功: {}", id))
                .onFailure(throwable -> {
                    log.error("Vert.x 网关部署失败", throwable);
                    System.exit(1);
                });
    }
}
