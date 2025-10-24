@echo off
echo ========================================
echo 测试Vertx网关和Spring Boot服务连接
echo ========================================

echo.
echo 1. 检查Spring Boot服务是否运行...
curl -s http://localhost:8081/springboot-grpc-server/actuator/health
if %errorlevel% neq 0 (
    echo [错误] Spring Boot服务未运行，请先启动服务
    echo 启动命令: cd D:\my-workspace\springboot-gprc-server ^&^& mvn spring-boot:run
    pause
    exit /b 1
)
echo [成功] Spring Boot服务运行正常

echo.
echo 2. 检查网关是否运行...
curl -s http://localhost:8080/health
if %errorlevel% neq 0 (
    echo [错误] 网关未运行，请先启动网关
    echo 启动命令: cd D:\my-workspace\vertx-dispatcher ^&^& mvn spring-boot:run
    pause
    exit /b 1
)
echo [成功] 网关运行正常

echo.
echo 3. 注册服务到网关...
curl -X POST http://localhost:8080/gateway/register ^
  -H "Content-Type: application/json" ^
  -d "{\"serviceName\": \"springboot-grpc-server\", \"projectName\": \"springboot-grpc-server\", \"endpoint\": \"http://localhost:8081\"}"

echo.
echo 4. 检查服务注册状态...
curl -s http://localhost:8080/gateway/status

echo.
echo 5. 测试直接访问Spring Boot服务...
curl -s http://localhost:8081/springboot-grpc-server/hello1

echo.
echo 6. 测试网关分发...
curl -s http://localhost:8080/springboot-grpc-server/hello1

echo.
echo 7. 测试其他接口...
curl -s http://localhost:8080/springboot-grpc-server/hello
curl -s http://localhost:8080/springboot-grpc-server/status

echo.
echo ========================================
echo 测试完成！
echo ========================================
pause

