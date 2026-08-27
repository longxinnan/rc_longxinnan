# rc_longxinnan

基于 Spring Boot 的 Java 工程，使用 Maven 管理依赖。

## 技术栈

- Java 21
- Maven 3.9.15
- Spring Boot 3.5.4
- spring-boot-starter-web

## 快速开始

```bash
# 编译打包
mvn clean package

# 启动应用（默认端口 8080）
mvn spring-boot:run
```

启动后访问 <http://localhost:8080/> 可以看到示例接口返回内容。

## 测试

```bash
mvn test
```
