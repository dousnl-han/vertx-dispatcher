# Vert.x 网关项目构建指南

## 项目结构
```
vertx-gateway/
├── pom.xml                          # Maven配置文件
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/example/vertx/gateway/
│   │   │       ├── VertxGatewayMain.java      # 主启动类
│   │   │       ├── VertxDispatcher.java       # 核心分发器
│   │   │       ├── VertxModels.java           # 数据模型
│   │   │       ├── VertxLoadBalancer.java     # 负载均衡器
│   │   │       └── VertxHealthChecker.java    # 健康检查和熔断器
│   │   └── resources/
│   │       ├── logback.xml                    # 日志配置
│   │       ├── application.yml                # 应用配置
│   │       └── gateway-config.yaml            # 网关配置
│   └── test/
│       ├── java/
│       │   └── com/example/vertx/gateway/
│       │       ├── VertxGatewayTest.java       # 单元测试
│       │       ├── VertxDispatcherTest.java    # 分发器测试
│       │       └── LoadBalancerTest.java       # 负载均衡测试
│       └── resources/
│           └── test-config.yaml               # 测试配置
├── Dockerfile                                 # Docker构建文件
├── docker-compose.yml                         # Docker编排文件
└── README.md                                  # 项目说明
```

## 构建命令

### 1. 编译项目
```bash
mvn clean compile
```

### 2. 运行测试
```bash
mvn test
```

### 3. 打包应用
```bash
mvn clean package
```

### 4. 运行应用
```bash
# 方式1: 直接运行JAR
java -jar target/vertx-gateway-1.0.0.jar

# 方式2: 使用Maven运行
mvn exec:java -Dexec.mainClass="com.example.vertx.gateway.VertxGatewayMain"

# 方式3: 指定配置文件
java -jar target/vertx-gateway-1.0.0.jar -conf src/main/resources/gateway-config.yaml
```

### 5. 性能测试
```bash
mvn test -Dtest=PerformanceTest
```

## 环境配置

### 开发环境
```bash
mvn clean package -Pdev
```

### 生产环境
```bash
mvn clean package -Pprod
```

### 测试环境
```bash
mvn clean package -Ptest
```

## Docker 构建

### 1. 构建镜像
```bash
docker build -t vertx-gateway:1.0.0 .
```

### 2. 运行容器
```bash
docker run -p 8080:8080 vertx-gateway:1.0.0
```

### 3. Docker Compose
```bash
docker-compose up -d
```

## 依赖说明

### 核心依赖
- **vertx-core**: Vert.x 核心框架
- **vertx-web**: Web 框架和路由
- **vertx-web-client**: HTTP 客户端
- **vertx-config**: 配置管理
- **vertx-service-discovery**: 服务发现
- **vertx-health-check**: 健康检查
- **vertx-micrometer-metrics**: 指标监控

### 工具依赖
- **jackson-databind**: JSON 处理
- **slf4j-api + logback**: 日志框架
- **micrometer-core**: 指标收集
- **micrometer-registry-prometheus**: Prometheus 指标

### 测试依赖
- **junit-jupiter**: 单元测试框架
- **vertx-junit5**: Vert.x 测试支持
- **mockito**: Mock 框架
- **assertj**: 断言库
- **jmh**: 性能测试

## 配置说明

### 应用配置 (application.yml)
```yaml
gateway:
  port: 8080
  host: 0.0.0.0
  routing:
    defaultService: default-service
    rules:
      - domain: dushu.com
        prefix: /user-orch/
        service: user-orch
      - domain: dushu.com
        prefix: /order-orch/
        service: order-orch
  loadBalancer:
    strategy: weightedRoundRobin
  healthCheck:
    interval: 30000
    timeout: 5000
  circuitBreaker:
    failureThreshold: 5
    timeout: 60000
    halfOpenMaxRequests: 3
```

### 日志配置 (logback.xml)
```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <root level="INFO">
        <appender-ref ref="STDOUT" />
    </root>
</configuration>
```

## 性能优化

### JVM 参数
```bash
java -Xms2g -Xmx2g \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=200 \
     -XX:+UseStringDeduplication \
     -Dvertx.logger-delegate-factory-class-name=io.vertx.core.logging.SLF4JLogDelegateFactory \
     -jar target/vertx-gateway-1.0.0.jar
```

### Maven 构建优化
```bash
# 并行构建
mvn -T 4 clean package

# 跳过测试
mvn clean package -DskipTests

# 离线模式
mvn clean package -o
```

## 监控和运维

### 指标端点
- **健康检查**: `GET /health`
- **Prometheus 指标**: `GET /metrics`
- **服务状态**: `GET /gateway/status`
- **熔断器状态**: `GET /gateway/circuit-breaker-status`

### 日志级别
```bash
# 设置日志级别
export LOG_LEVEL=DEBUG
java -jar target/vertx-gateway-1.0.0.jar
```

## 故障排查

### 常见问题
1. **端口占用**: 检查 8080 端口是否被占用
2. **内存不足**: 调整 JVM 堆内存大小
3. **依赖冲突**: 检查 Maven 依赖版本
4. **配置错误**: 验证配置文件格式

### 调试模式
```bash
# 启用调试日志
mvn exec:java -Dexec.mainClass="com.example.vertx.gateway.VertxGatewayMain" -Dlog.level=DEBUG
```

这个 pom.xml 文件包含了 Vert.x 网关项目的所有必要依赖和构建配置，支持开发、测试、生产环境的完整构建流程。
