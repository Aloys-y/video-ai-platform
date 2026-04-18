# 第2章：认证体系（JWT + API Key）

---

## 一、认证体系全景图

```
                          请求进入
                             │
                    ┌────────▼────────┐
                    │ RateLimitInterceptor │  第1道：限流
                    │ (order=1)        │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │ AuthInterceptor  │  第2道：认证
                    │ (order=2)        │
                    └────────┬────────┘
                             │
                 ┌───────────┼───────────┐
                 │                       │
         有 Authorization 头       有 X-API-Key 头
                 │                       │
         ┌───────▼───────┐       ┌───────▼───────┐
         │ JWT Token 认证 │       │ API Key 认证  │
         │               │       │               │
         │ 1.解析Token   │       │ 1.校验格式    │
         │ 2.取userId    │       │ 2.查数据库    │
         │ 3.查数据库    │       │ 3.校验状态    │
         │ 4.校验状态    │       │               │
         └───────┬───────┘       └───────┬───────┘
                 │                       │
                 └───────────┬───────────┘
                             │
                    ┌────────▼────────┐
                    │ UserContext.set  │  存入ThreadLocal
                    │ (user)          │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │ Controller      │  业务处理
                    │ Service.getUser │  任意位置获取用户
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │ afterCompletion │  请求结束
                    │ UserContext.clear│  清除ThreadLocal
                    └─────────────────┘
```

## 二、JWT 详解

### 2.1 JWT 是什么？

JWT（JSON Web Token）是一个**自包含的令牌**，由三部分组成，用 `.` 分隔：

```
eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c3JfYWJjMTIzIiwicm9sZSI6IlVTRVIiLCJpZCI6MX0.签名部分
│                      │                                                  │
│     Header           │              Payload                              │  Signature
│   (算法+类型)         │           (业务数据)                              │  (防篡改)
```

**Header**（告诉服务器用什么算法验证）：
```json
{ "alg": "HS256", "typ": "JWT" }
```

**Payload**（存放业务数据，本项目存了什么）：
```json
{
  "sub": "usr_abc123def456",  // subject = 用户业务ID
  "role": "USER",             // 角色
  "id": 1,                    // 数据库主键ID
  "iat": 1712678400,          // issued at = 签发时间
  "exp": 1712764800           // expiration = 过期时间
}
```

**Signature**（用密钥签名，防止篡改）：
```
HMACSHA256(base64(header) + "." + base64(payload), secretKey)
```
如果有人修改了 Payload 中的 role 从 USER 改成 ADMIN，签名就对不上了，服务器会拒绝。

### 2.2 JwtUtil 源码逐行分析

```java
@Component
public class JwtUtil {

    // 密钥：从配置文件读取，默认值仅用于开发
    @Value("${videoai.jwt.secret:default-dev-secret-key-must-be-at-least-32-chars}")
    private String secret;

    // 过期时间：默认 86400000ms = 24小时
    @Value("${videoai.jwt.expiration:86400000}")
    private long expiration;

    // 将字符串密钥转为 HMAC-SHA 密钥对象
    // 为什么要求至少32字符？因为 HS256 需要 256位 = 32字节
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
```

**生成 Token**：
```java
    public String generateToken(User user) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(user.getUserId())    // sub = 用户业务ID
                .claim("role", user.getRole()) // 自定义字段：角色
                .claim("id", user.getId())     // 自定义字段：主键
                .issuedAt(now)                 // 签发时间
                .expiration(expiryDate)        // 过期时间
                .signWith(getSigningKey())     // 用密钥签名
                .compact();                    // 拼接成 xxx.yyy.zzz
    }
```

**解析 Token**：
```java
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())   // 用同一个密钥验证签名
                .build()
                .parseSignedClaims(token)      // 解析，签名不匹配会抛异常
                .getPayload();                 // 返回 Payload（Claims）
    }
```

**验证 Token**：
```java
    public boolean isTokenValid(String token) {
        try {
            parseToken(token);  // 解析成功就是有效的
            return true;
        } catch (Exception e) {  // 签名错误、过期都会抛异常
            return false;
        }
    }
```

### 2.3 JWT vs Session 对比

| 特性 | JWT | Session |
|------|-----|---------|
| 存储位置 | 客户端（Token） | 服务端（内存/Redis） |
| 扩展性 | 无状态，天然支持多实例 | 需要共享Session（Redis/数据库） |
| 安全性 | Token 泄露即冒用 | Session ID 泄露同理 |
| 注销难度 | **无法主动失效**（除非加黑名单） | 直接删除即可 |
| 适用场景 | 前后端分离、移动端、API | 传统 Web、需要即时注销 |

**本项目的选择**：JWT 适合 API 平台（无状态、多端），但缺点是无法主动注销。如果需要"踢人"，需要加 Redis 黑名单。

## 三、注册登录流程

### 3.1 注册流程

```
客户端                     AuthService                          数据库
  │                          │                                   │
  │ POST /auth/register      │                                   │
  │ {username, email, pwd}   │                                   │
  │─────────────────────────>│                                   │
  │                          │ SELECT * FROM user                │
  │                          │ WHERE username=?                  │
  │                          │──────────────────────────────────>│
  │                          │<──── null（不存在）───────────────│
  │                          │                                   │
  │                          │ userService.createUser()          │
  │                          │  ├─ generateApiKey()              │
  │                          │  │  → sk_live_xxxxxxxxxxx         │
  │                          │  ├─ generateApiSecret()           │
  │                          │  │  → 32字节随机数                 │
  │                          │  ├─ BCrypt.hashpw(password, 12)  │
  │                          │  │  → 密码哈希                     │
  │                          │  ├─ BCrypt.hashpw(secret, 12)    │
  │                          │  │  → Secret哈希                   │
  │                          │  └─ INSERT INTO user              │
  │                          │──────────────────────────────────>│
  │                          │                                   │
  │                          │ jwtUtil.generateToken(user)       │
  │                          │  → 生成JWT Token                  │
  │                          │                                   │
  │<─ {token, userId,        │                                   │
  │    username, role,        │                                   │
  │    apiKey} ──────────────│                                   │
  │                          │                                   │
  │ ⚠️ 注册返回完整 apiKey     │                                   │
  │    只出现这一次！          │                                   │
```

### 3.2 关键代码：BCrypt 哈希

```java
// 加密（注册时）
String hashed = BCrypt.hashpw(rawPassword, BCrypt.gensalt(12));
// 原始密码: "123456"
// 哈希结果: "$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
//           算法  cost    盐值（22字符）        哈希值（31字符）

// 验证（登录时）
BCrypt.checkpw(rawPassword, hashed);  // → true/false
```

**BCrypt vs MD5 的本质区别**：

| 特性 | MD5 | BCrypt |
|------|-----|--------|
| 速度 | **极快**（1秒亿次） | **故意慢**（1秒几千次） |
| 盐值 | 需要手动加 | **自动内建** |
| 彩虹表 | 容易被破解 | 几乎不可能 |
| cost factor | 无 | 可调节（本项目用12 = 2^12轮） |
| 用途 | 文件校验 | **密码存储** |

> MD5 快是优点也是缺点——攻击者用 GPU 每秒可以暴力尝试几十亿次。BCrypt 故意设计得很慢，让暴力破解的成本高到不可接受。

### 3.3 API Key 生成策略

```java
private String generateApiKey(boolean isProduction) {
    String prefix = isProduction ? "sk_live_" : "sk_test_";
    byte[] randomBytes = new byte[24];          // 24字节 = 192位
    RANDOM.nextBytes(randomBytes);              // 安全随机数
    String randomPart = Base64.getUrlEncoder()  // URL安全Base64
            .withoutPadding()
            .encodeToString(randomBytes);       // → 32字符
    return prefix + randomPart;
    // 结果示例: sk_live_a3JfBx2mNp7QvR8sT1wYz4
}
```

**设计要点**：
- 前缀 `sk_live_` / `sk_test_`：一看就知道是密钥+环境，参考 Stripe 的设计
- `SecureRandom` 而非 `Random`：`SecureRandom` 使用操作系统熵源，密码学安全
- 24字节随机数 → Base64 后32字符 → 碰撞概率为 2^-192，可以忽略
- 数据库唯一索引兜底：即使碰撞也会报错

### 3.4 注册 vs 登录返回值的差异

```java
// 注册：返回完整 apiKey
return AuthResponse.builder()
        .apiKey(user.getApiKey())        // ← 完整的 sk_live_xxx
        .build();

// 登录：返回脱敏 apiKey
return AuthResponse.builder()
        .apiKey(user.getMaskedApiKey())  // ← sk_liv***1wYz
        .build();
```

**为什么？** API Key 相当于密码，注册时生成并完整返回**只有这一次机会**。之后登录只返回脱敏版本，让用户确认是哪个 Key，但不暴露完整值。如果忘了就得重新生成。

## 四、拦截器双模式认证

### 4.1 优先级设计

```java
public boolean preHandle(HttpServletRequest request, ...) {
    // 1. 优先尝试 JWT
    String authHeader = request.getHeader("Authorization");
    if (authHeader != null && authHeader.startsWith("Bearer ")) {
        return handleJwtAuth(token, response);
    }

    // 2. 其次尝试 API Key
    String apiKey = request.getHeader("X-API-Key");
    if (apiKey != null) {
        return handleApiKeyAuth(apiKey, response);
    }

    // 3. 都没有 → 拒绝
    writeErrorResponse(response, ErrorCode.PARAM_MISSING, "缺少认证凭证");
    return false;
}
```

**为什么 JWT 优先？**
- JWT 是无状态的，解析 Token 就能拿到 userId，**不需要查库**（但本项目为了校验用户状态还是查了）
- API Key 必须查数据库才能找到用户
- Web 端用 JWT 更频繁，优先处理减少延迟

### 4.2 两种认证的使用场景

| 认证方式 | Header | 适用场景 | 特点 |
|---------|--------|---------|------|
| JWT Token | `Authorization: Bearer xxx` | Web 前端登录后 | 有过期时间，自动失效 |
| API Key | `X-API-Key: sk_live_xxx` | 程序调用 API | 永久有效，需手动轮换 |

### 4.3 路径排除（不需要认证的接口）

```java
.excludePathPatterns(
    "/error",              // Spring 错误页
    "/druid/**",           // Druid 监控
    "/actuator/**",        // 健康检查
    "/swagger-ui/**",      // Swagger 文档
    "/v3/api-docs/**",     // OpenAPI 规范
    "/auth/register",      // 注册
    "/auth/login",         // 登录
    "/test/user/**",       // 测试接口
    "/api/public/**"       // 公开接口
)
```

**思考**：如果把 `/auth/register` 和 `/auth/login` 也加上认证，会发生什么？→ 死循环！用户还没登录就要认证，永远登录不了。

## 五、UserContext（ThreadLocal）

### 5.1 为什么需要 ThreadLocal？

```java
// 没有ThreadLocal的写法（痛苦）
public void uploadFile(Long userId, ...) {  // 每个方法都要传 userId
    UploadSession session = createSession(userId, ...);
    saveFile(userId, ...);
    log.info("User {} uploaded file", userId);
}

// 有ThreadLocal的写法（优雅）
public void uploadFile(...) {
    Long userId = UserContext.getUserId();  // 随时随地获取
    ...
}
```

### 5.2 ThreadLocal 原理

```
Tomcat 线程池
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│ Thread-1    │  │ Thread-2    │  │ Thread-3    │
│ ┌─────────┐ │  │ ┌─────────┐ │  │ ┌─────────┐ │
│ │User:A   │ │  │ │User:B   │ │  │ │null     │ │
│ └─────────┘ │  │ └─────────┘ │  │ └─────────┘ │
└─────────────┘  └─────────────┘  └─────────────┘
    请求A             请求B           空闲线程

每个线程内部有一个 Map（ThreadLocalMap），
key = UserContext.USER_HOLDER，value = User 对象
线程之间完全隔离，互不干扰
```

### 5.3 ⚠️ 内存泄漏风险（面试必问）

```java
// 拦截器的 afterCompletion 中必须清理！
@Override
public void afterCompletion(...) {
    UserContext.clear();  // 移除 ThreadLocal 条目
}
```

**为什么不清理会出问题？**

Tomcat 使用线程池处理请求，线程是复用的：
```
请求A进来 → Thread-1 设置 UserContext(A)
请求A处理完 → 但没清理！
请求B进来 → 复用 Thread-1
Service中调用 UserContext.getUser() → 返回了用户A的信息！！！
这是严重的安全漏洞：用户B看到了用户A的数据
```

### 5.4 @Async 异步线程的问题

如果用了 `@Async`：
```java
@Async
public void asyncTask() {
    User user = UserContext.getUser(); // ← null！
}
```

原因：`@Async` 会用新的线程执行，普通 `ThreadLocal` 不会传递到子线程。

**解决方案**：
1. 在调用方手动传递：`asyncTask(UserContext.getUserId())`
2. 使用 `InheritableThreadLocal`（但有坑）
3. 使用 `TransmittableThreadLocal`（阿里开源，推荐）

## 六、核心问题解答

### Q1：JWT 的结构？为什么不需要服务端存储 Session？

JWT 的三部分：Header（算法）、Payload（数据）、Signature（签名）。

不需要 Session 是因为 JWT 是**自包含**的——Token 本身就包含了用户信息，签名保证了不可篡改。服务端只需要密钥就能验证，不需要存储任何东西。

### Q2：拦截器里 JWT 和 API Key 的判断优先级？

JWT 优先。设计理由：
1. JWT 解析更快（不需要查库就能拿到 userId）
2. Web 端主要用 JWT，频率更高
3. API Key 是给程序调用用的，场景更少

### Q3：UserContext 用 ThreadLocal，用 @Async 会出什么问题？

会拿到 `null`，因为 `@Async` 创建新线程，普通 `ThreadLocal` 不会传递。解决办法是在调用前手动传参，或者用阿里的 `TransmittableThreadLocal`。

### Q4：BCrypt 和 MD5 的本质区别？

**速度**。MD5 太快了，GPU 每秒可以暴力破解几十亿次。BCrypt **故意设计得很慢**，每次哈希要经过 2^cost 轮运算（本项目 cost=12 = 4096轮），让暴力破解的成本高到不可接受。而且 BCrypt 自带随机盐值，不需要手动管理。

### Q5：API Key 注册返回完整 key，登录只返回脱敏的？

API Key 相当于长期凭证，只在**创建时完整展示一次**。之后只返回脱敏版本（`sk_liv***1wYz`），让用户确认是哪个 Key。如果忘了就重新生成，不支持查看原始值。

## 七、面试模拟

### "JWT 认证是怎么实现的？"
> "用户注册/登录成功后，服务端用 HMAC-SHA256 算法和密钥签发 JWT Token，payload 包含 userId、role 和过期时间。客户端在后续请求的 Authorization 头携带 Token。拦截器解析 Token 验证签名，从中取出 userId 查库确认用户状态，然后将 User 对象存入 ThreadLocal。请求结束后在 afterCompletion 中清理 ThreadLocal，防止线程池复用导致的信息泄漏。"

### "Token 泄露了怎么办？"
> "JWT 是无状态的，一旦签发就无法主动失效。应对方案有三种：1）短期过期 + Refresh Token，减少泄露窗口；2）Redis 黑名单，注销时把 Token ID 加入黑名单；3）Token 绑定设备指纹，异常设备拒绝访问。"

### "大文件上传怎么做的？" → 这个在第5章详细讲 😄