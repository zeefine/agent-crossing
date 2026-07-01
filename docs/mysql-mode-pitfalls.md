---
title: MySQL 模式踩坑清单
doc_kind: ops-checklist
created: 2026-06-15
updated: 2026-06-15
status: active
---

# MySQL 模式踩坑清单

> 仅在 `agent-crossing.storage-mode=mysql`（profile `mysql` 激活）时生效。
> InMemory 模式默认走单元测试覆盖，但**MyBatis 那条路径单测不命中**——所有问题都要在跑起来的 mysql 服务上才暴露。

## 1. 为什么写这份清单

2026-06-15 的一次例行重启发现 mysql 模式下 `POST /api/chat/threads` 一直 500。挖下去发现是**三层连锁 bug**，每修一层暴露下一层。三个 bug 的成因都不是低级失误，都是 MyBatis + Spring + Jackson 配合时的**默认行为反直觉**导致的隐性失败。

这份文档把这三类陷阱固化下来，**添加新 MyBatis 映射 / 改 record / 加 type handler 前先看一眼**。

## 2. 必修：Catch-all 异常处理器**必须打栈**

`@RestControllerAdvice` 的兜底 `@ExceptionHandler(Exception.class)` 如果不打日志，所有非业务异常都会变成"Unexpected server error"消失。**没有栈就没有定位**。

✅ 当前实现（`api/error/GlobalExceptionHandler.java:34`）：

```java
@ExceptionHandler(Exception.class)
public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
    log.error("Unexpected server error", exception);  // ← 必须这一行
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.failed("INTERNAL_ERROR", "Unexpected server error"));
}
```

**审查规则**：任何 `@ExceptionHandler` 都必须有 `log.error(..., exception)` 或 `log.warn(..., exception)`。业务 4xx 可以用 `log.debug` 控制噪音，但**不能完全静默**。

## 3. Pitfall 1：Jackson 默认不支持 Java 8 时间类型

### 症状

任何 record 含 `Instant` / `LocalDateTime` 字段，走 `realtime_event.payload` 序列化时炸：

```
com.fasterxml.jackson.databind.exc.InvalidDefinitionException:
  Java 8 date/time type `java.time.Instant` not supported by default:
  add Module "com.fasterxml.jackson.datatype:jackson-datatype-jsr310"
```

具体到 v1.1，`ChatThread` 作为 `thread` 事件 payload 时直接 500。

### 根因

`ObjectJsonTypeHandler` 用 `new ObjectMapper()` 裸构造（`mybatis/typehandler/ObjectJsonTypeHandler.java:14`）。Jackson 默认 ObjectMapper **不注册 JSR310 模块**，碰到 Instant 直接挂。

### 修法

```java
private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
```

`WRITE_DATES_AS_TIMESTAMPS=false` 让时间走 ISO 8601 字符串，跟前端 / Python 端的 wire 格式一致。

### 规则

**任何手写 ObjectMapper 都必须显式注册 JSR310** 并禁用时间戳序列化。Spring Boot 自动配置的 ObjectMapper（`@Autowired ObjectMapper`）默认已经做了这件事，**优先注入而不是自己 new**。如果必须 new（如 `static final` 字段、类型处理器），照上面那行写。

## 4. Pitfall 2：MyBatis 全局 `BaseTypeHandler<Object>` 会截获原始类型

### 症状

任何带 `LIMIT` 或其他 int/long 参数的 SELECT 报：

```
SQLSyntaxErrorException: ... near ''1'' at line 5
```

注意 `''1''` 是 MySQL 错误消息里的转义形式，实际意思是 SQL 里出现了 `'1'`（带引号的字符串字面量）。`LIMIT '1'` 是非法语法。

### 根因

`MybatisConfig.java:50` 曾经这样写：

```java
factoryBean.setTypeHandlers(
        new InstantTypeHandler(),
        new JsonNodeTypeHandler(),
        new ObjectJsonTypeHandler());  // ← 罪魁祸首
```

`ObjectJsonTypeHandler extends BaseTypeHandler<Object>`——它的 baseType 是 `Object`。MyBatis 把 mapper 方法的 `@Param` 参数通过 `ParamMap` 装箱成 `Object`，**所有原始类型（int / long / boolean）走 Map 后类型都是 Object**。MyBatis 查 handler 时拿到 Object → 命中 `ObjectJsonTypeHandler` → 用它的 `setNonNullParameter` → `ObjectMapper.writeValueAsString(1)` → 给 `ps.setString(i, "1")` → SQL 里渲染出 `LIMIT '1'`。

### 修法

**全局只注册 baseType 是具体类的 handler**（如 `Instant`、`JsonNode`），永远别全局注册 `BaseTypeHandler<Object>`：

```java
factoryBean.setTypeHandlers(new InstantTypeHandler(), new JsonNodeTypeHandler());
```

`ObjectJsonTypeHandler` 只在 mapper XML 里**按需显式引用**：

```xml
#{payload, typeHandler=com.agentcrossing.platform.infrastructure.persistence.mybatis.typehandler.ObjectJsonTypeHandler}
```

### 规则

**审查 type handler 注册时只问一句**：handler 的 baseType 是不是某个具体的应用类型？

- ✅ `BaseTypeHandler<Instant>` → 全局注册 OK
- ✅ `BaseTypeHandler<JsonNode>` → 全局注册 OK
- ✅ `BaseTypeHandler<MyDomainEnum>` → 全局注册 OK
- ❌ `BaseTypeHandler<Object>` → **永远只在 XML 显式引用，绝不全局**
- ❌ `BaseTypeHandler<Map>` / `BaseTypeHandler<List>` → 同上

## 5. Pitfall 3：ResultMap 的 javaType 必须跟 Java record/class 严格匹配

### 症状

INSERT 成功，回读 SELECT 报：

```
ReflectionException: Error instantiating class ... with invalid types
  (java.lang.Long, java.lang.String, java.lang.String,
   com.fasterxml.jackson.databind.JsonNode, java.time.Instant)
Cause: java.lang.NoSuchMethodException: <init>(Long, String, String, JsonNode, Instant)
```

### 根因

`RealtimeEvent` record 当时定义为：

```java
public record RealtimeEvent(
        long eventId,        // primitive long
        String threadId,
        String type,
        Object payload,      // Object
        Instant createdAt) {}
```

mapper XML 写的是：

```xml
<idArg column="event_id" javaType="long"/>
<arg column="payload" javaType="com.fasterxml.jackson.databind.JsonNode" .../>
```

MyBatis 的 `ConstructorResolver` 用反射 `getDeclaredConstructor(...)` 找 ctor。`javaType="long"` 被解析成 `Long.class`（boxed），`javaType="JsonNode"` 就是 JsonNode.class。它**严格匹配类型签名**：

- 找 `<init>(Long, String, String, JsonNode, Instant)` → **不存在**
- record 实际签名是 `<init>(long, String, String, Object, Instant)` → 不匹配 boxed/Object 差异

Java 反射不会"宽容地" autobox 也不会"宽容地"接 `JsonNode → Object`。

### 修法

两边对齐：

**record 改 boxed**：

```java
public record RealtimeEvent(
        Long eventId,        // ← Long 不是 long
        String threadId,
        String type,
        Object payload,
        Instant createdAt) {}
```

**XML 改成基类型**：

```xml
<idArg column="event_id" javaType="java.lang.Long"/>
<arg column="payload" javaType="java.lang.Object"
     typeHandler="...JsonNodeTypeHandler"/>
```

`typeHandler` 仍然是 JsonNodeTypeHandler——它返回 JsonNode，但 JsonNode IS-A Object，所以可以塞进 ctor 的 Object slot。

### 规则

**新增 ResultMap 时三步自查**：

1. **打开对应的 record/class 文件**，肉眼对一遍每个字段的 java 类型
2. XML 的 `javaType=` 必须**严格相等**于 record 的字段类型：
   - record 是 `Object` → XML 写 `javaType="java.lang.Object"`，不能写 typeHandler 返回的具体类
   - record 是 `Long` → XML 写 `java.lang.Long`；如果 record 用 primitive `long`，**改 record 用 Long**（MyBatis ctor 解析 boxed 友好）
3. 跑一遍**真实的 mysql 路径**（不是 InMemory 单测），调一次写 + 一次读，确认 ctor 能命中

### 反复出现的实例

同一个 audit cycle 内 (2026-06-15) 这条规则违反了**两次**，每次都是 v1.0 时期的 record 当初按 Java 习惯写了 primitive，MyBatis 全部都得改成 boxed：

| record | 字段 | 修前 | 修后 |
|---|---|---|---|
| `RealtimeEvent` | `eventId` | `long` | `Long` |
| `Task` | `depth` | `int` | `Integer` |

**新加任何 mysql-持久化 record 时**：所有数值字段一律写 boxed (`Integer / Long / Double / Boolean`)，别用 primitive。除非你能保证它永远不进 MyBatis ResultMap。

## 6. 通用 Checklist：新增 MyBatis 映射前

每次给 mysql 模式新增 mapper / repository / type handler 时，按这张表走：

- [ ] **手写 ObjectMapper**：注册 `JavaTimeModule()` + `disable(WRITE_DATES_AS_TIMESTAMPS)`。优先用 Spring 注入的 ObjectMapper。
- [ ] **TypeHandler 注册**：`MybatisConfig.setTypeHandlers(...)` 名单里**只能有 baseType 为具体类的 handler**。`BaseTypeHandler<Object>` 一律在 XML 显式引用。
- [ ] **ResultMap javaType**：用 IDE 跳到对应 record/class，肉眼对每个 ctor 参数类型，XML 严格相等。primitive vs boxed 当成不同类型。
- [ ] **真实路径自测**：起一次 `SPRING_PROFILES_ACTIVE=mysql`，对新加的 endpoint 调一次 write + 一次 read，确认 200。**别只跑 mvn test**（默认 memory 模式）。
- [ ] **异常处理器**：如果新加 `@ExceptionHandler`，配 `log.error(..., exception)` 或 `log.warn`。

## 7. 长期改进项

- 加一套 **MyBatis 集成测试** 用 H2 或 testcontainers，让 mysql 路径进 CI。当前所有单测都走 InMemory，MyBatis 漏的 bug 只能等 dev-up 复现。
- 考虑用 `@Autowired ObjectMapper` 代替手写——把 Jackson 配置集中到一处。
- 引入 lint / static check 规则：catch-all `@ExceptionHandler` 必须含 `log.error`，否则编译失败。

## 8. 参考

- 本次完整调试记录：见 v1.1 阶段对话，bug A / bug B 三连修
- 相关文件：
  - `services/platform-api/src/main/java/com/agentcrossing/platform/api/error/GlobalExceptionHandler.java`
  - `services/platform-api/src/main/java/com/agentcrossing/platform/infrastructure/persistence/mybatis/config/MybatisConfig.java`
  - `services/platform-api/src/main/java/com/agentcrossing/platform/infrastructure/persistence/mybatis/typehandler/ObjectJsonTypeHandler.java`
  - `services/platform-api/src/main/java/com/agentcrossing/platform/domain/event/RealtimeEvent.java`
  - `services/platform-api/src/main/resources/mapper/RealtimeEventMapper.xml`
