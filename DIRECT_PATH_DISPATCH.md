# 直接路径分发功能说明

## 🎯 功能概述

现在Vertx网关支持直接路径分发，无需通过`/gateway/dispatch`接口，可以直接访问：

```
http://localhost:8080/user-orch/hello
```

## 🛣️ 支持的路由

### 1. 用户相关路由
- `http://localhost:8080/user-orch/*` → Spring Boot gRPC服务 (8081)

### 2. 其他路由
- `http://localhost:8080/order-orch/*` → 订单服务
- `http://localhost:8080/product/*` → 产品服务  
- `http://localhost:8080/payment/*` → 支付服务

## 🚀 使用示例

### 1. 启动服务

#### 启动Vertx网关 (8080端口)
```bash
cd D:\my-workspace\vertx-dispatcher
mvn spring-boot:run
```

#### 启动Spring Boot gRPC服务 (8081端口)
```bash
cd D:\my-workspace\springboot-gprc-server
mvn spring-boot:run
```

### 2. 注册服务到网关
```bash
curl -X POST http://localhost:8080/gateway/register \
  -H "Content-Type: application/json" \
  -d '{
    "serviceName": "springboot-grpc-server",
    "projectName": "springboot-grpc-server",
    "endpoint": "http://localhost:8081"
  }'
```

### 3. 直接访问测试

#### GET请求测试
```bash
# 访问hello接口
curl http://localhost:8080/user-orch/hello

# 访问profile接口
curl http://localhost:8080/user-orch/profile

# 访问status接口
curl http://localhost:8080/user-orch/status
```

#### POST请求测试
```bash
# 创建用户
curl -X POST http://localhost:8080/user-orch/create \
  -H "Content-Type: application/json" \
  -d '{
    "name": "张三",
    "email": "zhangsan@example.com"
  }'
```

## 📊 请求流程

```
客户端请求 → Vertx网关(8080) → Spring Boot服务(8081) → 返回响应
```

### 详细流程：
1. **客户端发送请求**: `GET http://localhost:8080/user-orch/hello`
2. **网关接收请求**: Vertx网关接收HTTP请求
3. **路径匹配**: 网关匹配`/user-orch/*`路由规则
4. **服务选择**: 根据负载均衡算法选择可用服务实例
5. **请求转发**: 转发到`http://localhost:8081/user-orch/hello`
6. **响应返回**: 将Spring Boot服务的响应返回给客户端

## 🔧 配置说明

### 1. 网关路由配置
```java
// 直接路径分发 - 支持所有HTTP方法
router.route("/user-orch/*").handler(dispatcher::handleDirectDispatch);
router.route("/order-orch/*").handler(dispatcher::handleDirectDispatch);
router.route("/product/*").handler(dispatcher::handleDirectDispatch);
router.route("/payment/*").handler(dispatcher::handleDirectDispatch);
```

### 2. 服务注册
服务启动时会自动注册到网关，包含以下信息：
- 服务名称
- 服务端点
- 健康状态
- 权重配置

## 🛠️ 技术特性

### 1. 支持所有HTTP方法
- GET, POST, PUT, DELETE, PATCH等
- 自动转发请求体和请求头
- 保持原始HTTP语义

### 2. 负载均衡
- 轮询算法
- 随机算法
- 加权轮询算法
- 最少连接算法

### 3. 健康检查
- 自动检测服务健康状态
- 熔断器保护
- 故障转移

### 4. 性能优化
- 异步非阻塞处理
- 连接池复用
- 响应头转发
- 状态码保持

## 🔍 监控和调试

### 1. 查看服务状态
```bash
# 查看所有服务状态
curl http://localhost:8080/gateway/status

# 查看熔断器状态
curl http://localhost:8080/gateway/circuit-breaker-status
```

### 2. 健康检查
```bash
# 网关健康检查
curl http://localhost:8080/health

# Spring Boot服务健康检查
curl http://localhost:8081/actuator/health
```

### 3. 日志查看
网关会记录详细的请求分发日志：
- 请求接收日志
- 服务选择日志
- 转发请求日志
- 响应处理日志

## 🚨 故障排除

### 1. 常见问题

#### 服务未注册
```bash
# 检查服务是否注册成功
curl http://localhost:8080/gateway/status
```

#### 路由不匹配
- 确保请求路径以`/user-orch/`开头
- 检查网关路由配置

#### 服务不可用
- 检查Spring Boot服务是否正常运行
- 查看服务健康状态
- 检查网络连接

### 2. 调试步骤

1. **检查网关状态**
   ```bash
   curl http://localhost:8080/health
   ```

2. **检查服务注册**
   ```bash
   curl http://localhost:8080/gateway/status
   ```

3. **测试直接访问**
   ```bash
   curl http://localhost:8081/user-orch/hello
   ```

4. **测试网关分发**
   ```bash
   curl http://localhost:8080/user-orch/hello
   ```

## 📈 性能特性

- **高并发**: 基于Vert.x事件循环，支持高并发请求
- **低延迟**: 异步非阻塞处理，最小化延迟
- **高可用**: 健康检查和熔断器保护
- **可扩展**: 支持多实例负载均衡

现在你可以直接使用 `http://localhost:8080/user-orch/hello` 这样的URL来访问服务，网关会自动分发到对应的Spring Boot服务！

