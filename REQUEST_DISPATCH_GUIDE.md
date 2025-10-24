# Vertx网关请求分发机制详解

## 🎯 系统架构

```
客户端请求 → Vertx网关(8080) → Spring Boot gRPC服务(8081)
```

## 📋 请求分发流程

### 1. 网关启动
- **端口**: 8080
- **主类**: `VertxGatewayMain`
- **核心组件**:
  - `VertxDispatcher`: 请求分发器
  - `VertxLoadBalancer`: 负载均衡器
  - `VertxHealthChecker`: 健康检查器

### 2. 服务注册
Spring Boot gRPC服务启动时会自动注册到网关：

**注册接口**: `POST http://localhost:8080/gateway/register`

**注册数据格式**:
```json
{
  "serviceName": "springboot-grpc-server",
  "projectName": "springboot-grpc-server",
  "endpoint": "http://localhost:8081"
}
```

### 3. 请求分发

#### 3.1 分发接口
**接口**: `POST http://localhost:8080/gateway/dispatch`

#### 3.2 请求格式
```json
{
  "path": "/user-orch/profile",
  "method": "GET",
  "headers": {
    "Host": "dushu.com",
    "Content-Type": "application/json"
  },
  "body": "",
  "parameters": {}
}
```

#### 3.3 路由规则
网关根据以下规则确定目标服务：

```java
// 基于域名和路径的路由规则
if (host.contains("dushu.com")) {
    if (path.startsWith("/user-orch/")) {
        return "user-orch";  // 映射到 springboot-grpc-server
    }
    if (path.startsWith("/order-orch/")) {
        return "order-orch";
    }
}

// 兼容原有规则
if (path.startsWith("/user-orch/")) {
    return "springboot-grpc-server";
}
```

## 🔄 完整请求流程

### 1. 客户端发送请求
```bash
curl -X POST http://localhost:8080/gateway/dispatch \
  -H "Content-Type: application/json" \
  -H "Host: dushu.com" \
  -d '{
    "path": "/user-orch/profile",
    "method": "GET",
    "headers": {"Host": "dushu.com"},
    "body": "",
    "parameters": {}
  }'
```

### 2. 网关处理流程
1. **接收请求**: `VertxDispatcher.handleDispatch()`
2. **解析请求**: 提取path、method、headers等
3. **确定目标服务**: 根据路由规则确定服务名
4. **负载均衡**: 从可用服务中选择一个实例
5. **转发请求**: 将请求转发到Spring Boot服务
6. **返回响应**: 将响应返回给客户端

### 3. 目标服务处理
Spring Boot gRPC服务接收请求并处理：
- **服务地址**: `http://localhost:8081`
- **请求路径**: `/user-orch/profile`
- **处理方式**: 通过gRPC或HTTP接口处理

## 🛠️ 关键配置

### 1. 网关配置
```yaml
# Spring Boot服务配置
server:
  port: 8081
spring:
  application:
    name: dousnl-grpc-server

# 网关配置
gateway:
  url: http://localhost:8080  # 网关地址
  service-name: ${spring.application.name}
  port: ${server.port}
```

### 2. 路由映射
| 请求路径 | 目标服务 | 说明 |
|---------|---------|------|
| `/user-orch/*` | `springboot-grpc-server` | 用户相关请求 |
| `/order-orch/*` | `order-service` | 订单相关请求 |
| `/product/*` | `product-service` | 产品相关请求 |
| `/payment/*` | `payment-service` | 支付相关请求 |

## 🔍 监控和调试

### 1. 网关状态查询
```bash
# 查看所有服务状态
curl http://localhost:8080/gateway/status

# 查看熔断器状态
curl http://localhost:8080/gateway/circuit-breaker-status

# 测试分发
curl http://localhost:8080/gateway/test-dispatch
```

### 2. 健康检查
```bash
# 网关健康检查
curl http://localhost:8080/health

# Spring Boot服务健康检查
curl http://localhost:8081/actuator/health
```

## 📊 负载均衡策略

### 1. 支持的算法
- **轮询算法**: `selectRoundRobin()`
- **随机算法**: `selectRandom()`
- **加权轮询**: `selectWeightedRoundRobin()` (默认)
- **最少连接**: `selectLeastConnections()`

### 2. 健康检查
- 定期检查服务健康状态
- 自动标记不健康的服务
- 熔断器保护机制

## 🚀 使用示例

### 1. 启动网关
```bash
cd D:\my-workspace\vertx-dispatcher
mvn spring-boot:run
```

### 2. 启动Spring Boot服务
```bash
cd D:\my-workspace\springboot-gprc-server
mvn spring-boot:run
```

### 3. 测试请求分发
```bash
# 注册服务到网关
curl -X POST http://localhost:8080/gateway/register \
  -H "Content-Type: application/json" \
  -d '{
    "serviceName": "springboot-grpc-server",
    "projectName": "springboot-grpc-server",
    "endpoint": "http://localhost:8081"
  }'

# 发送分发请求
curl -X POST http://localhost:8080/gateway/dispatch \
  -H "Content-Type: application/json" \
  -d '{
    "path": "/user-orch/profile",
    "method": "GET",
    "headers": {"Host": "dushu.com"},
    "body": "",
    "parameters": {}
  }'
```

## 🔧 故障排除

### 1. 常见问题
- **服务未注册**: 检查Spring Boot服务是否成功注册到网关
- **路由不匹配**: 检查请求路径是否符合路由规则
- **服务不可用**: 检查目标服务是否正常运行
- **负载均衡问题**: 检查服务健康状态

### 2. 日志查看
- 网关日志: 查看分发请求的详细日志
- Spring Boot日志: 查看服务接收请求的日志
- 健康检查日志: 查看服务健康状态变化

## 📈 性能优化

### 1. 连接池配置
```java
HttpClientOptions options = new HttpClientOptions()
    .setConnectTimeout(5000)      // 5秒连接超时
    .setIdleTimeout(30)           // 30秒空闲超时
    .setKeepAlive(true)           // 保持连接
    .setMaxPoolSize(20)          // 最大连接池大小
    .setPoolCleanerPeriod(30000); // 30秒清理周期
```

### 2. 异步处理
- 所有请求处理都是异步的
- 非阻塞I/O模型
- 高并发支持

这个架构实现了高性能的请求分发，支持负载均衡、健康检查和熔断保护，确保系统的稳定性和可扩展性。
