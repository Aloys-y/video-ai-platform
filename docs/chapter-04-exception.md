# 第4章：全局异常处理 & 统一响应

---

## 一、整体设计思路

```
Controller                    Service                     数据库
   │                            │                           │
   │  uploadService.init()      │                           │
   │───────────────────────────>│                           │
   │                            │ 发现用户名已存在            │
   │                            │ throw BusinessException    │
   │                            │   (USERNAME_EXISTS)        │
   │                            │                           │
   │  ← 异常冒泡到 Controller    │                           │
   │                            │                           │
   ▼                            │                           │
┌──────────────────────────────────────────────────────────┐
│            @RestControllerAdvice                          │
│            GlobalExceptionHandler                         │
│                                                          │
│  @ExceptionHandler(BusinessException.class)              │
│  → ApiResponse.error(USERNAME_EXISTS)                    │
│  → HTTP 400 + {success:false, code:23003, message:"..."} │
└──────────────────────────────────────────────────────────┘
```

**核心思想**：Service 层只管抛异常，不关心怎么返回给前端；全局异常处理器统一捕获，转换成标准格式。

## 二、统一响应 ApiResponse

### 2.1 响应结构

```json
// 成功响应
{
    "success": true,
    "code": 0,
    "message": "操作成功",
    "data": {
        "userId": "usr_abc123",
        "token": "eyJhbGci..."
    },
    "traceId": null,
    "timestamp": 1713098400000
}

// 失败响应
{
    "success": false,
    "code": 23003,
    "message": "用户名已存在",
    "data": null,
    "traceId": "a1b2c3d4",
    "timestamp": 1713098400000
}
```

### 2.2 每个字段的作用

| 字段 | 类型 | 成功时 | 失败时 | 为什么需要？ |
|------|------|--------|--------|-------------|
| `success` | Boolean | true | false | 前端快速判断，不用解析 code |
| `code` | Integer | 0 | 具体错误码 | 精确区分错误类型 |
| `message` | String | "操作成功" | 具体提示 | 可直接展示给用户 |
| `data` | T | 业务数据 | null | 承载返回内容 |
| `traceId` | String | null | "a1b2c3d4" | 排查问题用 |
| `timestamp` | Long | 时间戳 | 时间戳 | 排查问题用 |

### 2.3 静态工厂方法（设计模式）

```java
// 成功
return ApiResponse.success(user);          // {success:true, code:0, data:user}
return ApiResponse.success();              // {success:true, code:0, data:null}

// 失败
return ApiResponse.error(ErrorCode.USER_NOT_FOUND);
// {success:false, code:23001, message:"用户不存在", traceId:"a1b2c3d4"}

return ApiResponse.error(ErrorCode.PARAM_ERROR, "文件名不能为空");
// {success:false, code:10001, message:"参数错误: 文件名不能为空", traceId:"e5f6g7h8"}
```

**为什么用静态工厂而不是构造器？**
- 语义清晰：`success()` / `error()` 一眼就知道含义
- 省略重复字段：成功时不需要 traceId，失败时自动生成
- 统一创建逻辑：所有响应走同一个入口，便于加日志/监控

## 三、错误码体系 ErrorCode

### 3.1 分段设计

```
错误码区间     分类             示例
──────────────────────────────────────────
0             成功              SUCCESS(0, "操作成功")
1xxxx         系统级错误         SYSTEM_ERROR(10000)
2xxxx         业务级错误
  20xxx       上传相关           UPLOAD_FILE_TOO_LARGE(20006)
  21xxx       任务相关           TASK_NOT_FOUND(21001)
  22xxx       配额相关           QUOTA_EXCEEDED(22001)
  23xxx       用户相关           USERNAME_EXISTS(23003)
3xxxx         第三方服务         AI_SERVICE_ERROR(30001)
4xxxx         限流熔断           RATE_LIMIT_EXCEEDED(40001)
```

**为什么分段而不是连续编号？**
1. **一眼定位**：看到 23003 就知道是用户相关错误
2. **扩展方便**：配额模块 22xxx 用完了，加个 22999 就行，不影响其他模块
3. **前端可按段处理**：2xxxx 弹提示框，3xxxx 提示"服务暂时不可用"，4xxxx 提示"频繁操作"

### 3.2 为什么用枚举而不是魔法数字？

```java
// ❌ 魔法数字，没人知道 23003 是什么意思
throw new BusinessException(23003, "用户名已存在");

// ✅ 枚举，自文档化
throw new BusinessException(ErrorCode.USERNAME_EXISTS);
```

枚举的好处：
- **编译期检查**：拼错了直接报错，不会到线上才发现
- **集中管理**：所有错误码在一个文件里，不会重复
- **IDE 友好**：输入 `ErrorCode.` 自动提示所有选项

## 四、BusinessException 业务异常

### 4.1 设计

```java
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
```

**为什么继承 RuntimeException 而不是 Exception？**

```
Exception（受检异常）
├── 必须用 try-catch 或 throws 声明
├── 每个调用层都要处理，代码冗余
└── 适合可恢复的异常（如 IOException）

RuntimeException（非受检异常）
├── 不强制 try-catch
├── 自动冒泡到上层，由全局处理器统一捕获
└── 适合不可恢复的业务错误（如"用户名已存在"）
```

业务异常是不可恢复的——用户名已存在，你 catch 住了也做不了什么，不如直接抛出去让全局处理器处理。

### 4.2 在 Service 中使用

```java
// AuthService.java
public AuthResponse register(RegisterRequest request) {
    if (userMapper.selectByUsername(request.getUsername()) != null) {
        throw new BusinessException(ErrorCode.USERNAME_EXISTS);
        // ← 抛完就完事了，不用管怎么返回给前端
    }
    // ... 正常业务逻辑
}
```

**对比没有全局异常处理器时的写法**：

```java
// ❌ 每个 Controller 都要 try-catch
public ApiResponse register(@RequestBody RegisterRequest request) {
    try {
        AuthResponse response = authService.register(request);
        return ApiResponse.success(response);
    } catch (BusinessException e) {
        return ApiResponse.error(e.getErrorCode());
    } catch (Exception e) {
        return ApiResponse.error(ErrorCode.SYSTEM_ERROR);
    }
    // 10个接口就要写10遍这种模板代码
}

// ✅ 有全局异常处理器后
public ApiResponse register(@RequestBody RegisterRequest request) {
    AuthResponse response = authService.register(request);
    return ApiResponse.success(response);
    // 异常？全局处理器自动兜底
}
```

## 五、GlobalExceptionHandler 全局异常处理器

### 5.1 三层异常捕获

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 第1层：业务异常（最常见）
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        log.warn("Business error: code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        HttpStatus status = mapHttpStatus(e.getErrorCode());
        return ResponseEntity.status(status).body(ApiResponse.error(e.getErrorCode()));
    }

    // 第2层：参数校验异常（@Valid 触发）
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(...) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String detail = fieldError.getField() + ": " + fieldError.getDefaultMessage();
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.PARAM_ERROR, detail));
    }

    // 第3层：兜底异常（所有未预期的异常）
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("Unexpected error: ", e);  // 注意：未知错误用 error 级别
        return ResponseEntity.status(500).body(ApiResponse.error(ErrorCode.SYSTEM_ERROR));
    }
}
```

### 5.2 异常捕获优先级

```
抛出 BusinessException
   │
   ├── 匹配 @ExceptionHandler(BusinessException.class)  → ✅ 第1层捕获
   │
抛出 MethodArgumentNotValidException
   │
   ├── 不匹配 BusinessException
   ├── 匹配 @ExceptionHandler(MethodArgumentNotValidException.class)  → ✅ 第2层捕获
   │
抛出 NullPointerException（或其他未预期的）
   │
   ├── 不匹配 BusinessException
   ├── 不匹配 MethodArgumentNotValidException
   ├── 匹配 @ExceptionHandler(Exception.class)  → ✅ 第3层兜底捕获
```

**规则**：Spring 会匹配**最具体**的异常类型。Exception 是所有异常的父类，所以永远是最后兜底。

### 5.3 HTTP 状态码映射

```java
private HttpStatus mapHttpStatus(ErrorCode errorCode) {
    int code = errorCode.getCode();
    if (code >= 40000) return HttpStatus.TOO_MANY_REQUESTS;     // 429
    if (code >= 30000) return HttpStatus.SERVICE_UNAVAILABLE;    // 503
    if (code == 23005 || code == 40003 || code == 40004) 
        return HttpStatus.UNAUTHORIZED;                          // 401
    if (code == 23002) return HttpStatus.FORBIDDEN;             // 403
    return HttpStatus.BAD_REQUEST;                               // 400
}
```

| 错误码范围 | HTTP 状态码 | 含义 |
|-----------|------------|------|
| 1xxxx | 400 Bad Request | 客户端参数有误 |
| 2xxxx | 400 / 401 / 403 | 业务错误，具体看类型 |
| 3xxxx | 503 Service Unavailable | 第三方服务不可用 |
| 4xxxx | 429 Too Many Requests | 限流/熔断 |

**为什么既要有业务错误码又要有 HTTP 状态码？**
- HTTP 状态码给**网关/负载均衡/浏览器**看的（网关看到 503 可能切流量）
- 业务错误码给**前端/客户端**看的（前端根据 code 决定弹什么提示）

### 5.4 日志级别策略

```java
// 业务异常：warn 级别（用户操作不规范，不是 bug）
log.warn("Business error: code={}, message={}", ...);

// 未知异常：error 级别（系统 bug，需要告警）
log.error("Unexpected error: ", e);
```

**为什么要区分？**
- warn 每天可能有几万条（用户输错密码、文件太大等），正常现象
- error 应该尽量少，每出现一条都意味着有 bug，需要排查
- 如果全用 error，告警系统会被淹没，真正的 bug 反而被忽略

## 六、`@RestControllerAdvice` 原理

### 6.1 它能捕获什么？不能捕获什么？

```
请求进入
   │
   ▼
┌──────────┐     ❌ Filter 中的异常 → 捕获不到
│ Filter   │
└────┬─────┘
     ▼
┌──────────┐     ❌ Interceptor 中的异常 → 捕获不到
│拦截器     │
└────┬─────┘
     ▼
┌──────────┐     ✅ Controller 中的异常 → 能捕获
│Controller│     ✅ Controller 调用的 Service 异常 → 能捕获（冒泡上来）
└────┬─────┘
     ▼
   响应
```

`@RestControllerAdvice` 本质上是一个 AOP 切面，只拦截**进入 Controller 方法**之后抛出的异常。Filter 和 Interceptor 在 Controller 之前，不在它的管辖范围。

这就是为什么第2章 AuthInterceptor 要用 `writeErrorResponse` 手动写响应。

## 七、核心问题解答

### Q1：为什么不用 try-catch，而用全局异常处理器？

try-catch 的问题：
1. **代码重复**：每个 Controller 方法都要写一遍
2. **容易遗漏**：忘了一个 catch，前端就收到 Spring 默认错误页
3. **职责不清**：Controller 应该只做路由，不应该处理异常格式

全局异常处理器的好处：
1. **一次编写，全局生效**
2. **Service 层只管抛异常**，不需要关心怎么返回给前端
3. **统一格式**，不可能出现遗漏

### Q2：traceId 有什么用？

```
用户反馈："我刚才上传失败了"
客服："请提供 traceId"
用户："traceId 是 a1b2c3d4"
开发：grep "a1b2c3d4" app.log
  → [WARN] a1b2c3d4 Business error: code=22001, message=配额不足
  → 瞬间定位问题
```

生产环境中每天几百万条日志，没有 traceId 就是大海捞针。

### Q3：success 和 code 是否冗余？

不冗余，用途不同：
- `success`：前端做**流程控制**（if success → 跳转首页，else → 弹错误提示）
- `code`：前端做**精确处理**（code=40003 → Token 过期跳登录页，code=22001 → 提示配额不足）

## 八、面试模拟

### "全局异常处理是怎么做的？"
> "我们定义了 BusinessException 业务异常类，封装 ErrorCode 错误码枚举。Service 层遇到业务错误直接抛出，由 GlobalExceptionHandler 统一捕获。处理器分三层：BusinessException 处理已知业务错误，MethodArgumentNotValidException 处理参数校验失败，Exception 兜底处理所有未知异常。所有异常统一转换为 ApiResponse 格式返回给前端，包含 success、code、message、traceId 等字段。"

### "错误码怎么设计的？"
> "采用分段设计：0 表示成功，1xxxx 系统级错误，2xxxx 业务错误（20上传/21任务/22配额/23用户），3xxxx 第三方服务错误，4xxxx 限流熔断。好处是一眼就能定位错误类别，前端可以按段做统一处理。所有错误码定义在枚举中，避免魔法数字。"