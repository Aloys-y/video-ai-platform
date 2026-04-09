# 安全审查报告

**审查时间**: 2026-04-09
**审查范围**: 未提交的全量代码变更（新增 + 修改文件）
**审查结果**: **发现 6 个高危、8 个中危、6 个低危问题，暂不建议提交**

---

## 高危问题 (HIGH)

### H1. API Secret 使用 MD5 + 静态盐值哈希
- **文件**: `video-api/.../service/UserService.java:137-142`
- **描述**: `hashSecret()` 使用 MD5 + 硬编码盐值 `"video_ai_salt_2024"` 对 API Secret 进行哈希。MD5 已被密码学破解，静态盐值使彩虹表攻击可一次性针对全库。
- **建议**: 替换为 BCrypt：引入 `org.mindrot:jbcrypt`，使用 `BCrypt.hashpw(secret, BCrypt.gensalt(12))`。

### H2. 明文密钥通过日志框架输出
- **文件**: `UserService.java:74` / `DataInitializer.java:75-78`
- **描述**: API Secret 和 API Key 通过 `log.info()` 明文打印。INFO 级别的日志会持久化到日志文件、日志聚合系统，任何有日志访问权限的人都能获取密钥。
```java
log.info("API Secret (please save): {}", apiSecret);  // UserService
log.info("API Key: {}", user.getApiKey());              // DataInitializer
```
- **建议**: 通过响应 DTO 返回密钥，不要通过日志输出。如必须展示，使用 `System.out.println` 直接输出到控制台，且只输出掩码版本。

### H3. CORS 配置允许任意源携带凭证
- **文件**: `video-api/.../config/WebMvcConfig.java:65-71`
- **描述**: `allowedOriginPatterns("*")` + `allowCredentials(true)` 允许任何网站发起带凭证的跨域请求，等同于禁用同源策略。
- **建议**: 即使开发环境也应指定具体源（如 `http://localhost:3000`），通过 profile 配置区分环境。

### H4. 测试接口无环境守卫
- **文件**: `video-api/.../controller/TestController.java`
- **描述**: `TestController` 暴露了无鉴权的用户创建接口 `POST /test/user/create`，但没有 `@Profile("dev")` 注解。生产环境部署后，任何人都能创建用户。
- **建议**: 添加 `@Profile("dev")` 到 `TestController` 类上。

### H5. SQL 种子数据中硬编码可预测的 API Key
- **文件**: `sql/schema.sql:29-34`
- **描述**: 一个格式为 `sk_live_TestKey...` 的硬编码测试密钥写入版本控制历史。此密钥高度可预测，且所有环境一致。
- **建议**: 删除 SQL 中的种子密钥，仅依赖 `DataInitializer`（已用 `@Profile("dev")` 保护）在开发环境动态生成。

### H6. User 实体缺少敏感字段保护
- **文件**: `video-common/.../domain/User.java`
- **描述**: `User` 实体带有 `@Data`（Lombok），`toString()` 会输出 `apiKey` 和 `apiSecret`。实体被存入 `UserContext` ThreadLocal 贯穿整个请求周期。若任何代码误打日志或序列化该对象，密钥泄露。
- **建议**: 在 `apiSecret` 字段添加 `@ToString.Exclude` 和 `@JsonIgnore`。考虑创建独立的 `AuthUser` DTO 供 `UserContext` 使用，不含 `apiSecret`。

---

## 中危问题 (MEDIUM)

### M1. API Key 通过 URL Query 参数传递
- **文件**: `video-api/.../interceptor/AuthInterceptor.java:114-116`
- **描述**: 支持通过 `?api_key=xxx` 传递密钥，密钥会出现在 URL 访问日志、浏览器历史、Referer 头和代理日志中。
- **建议**: 移除 query 参数支持，仅保留 `X-API-Key` Header 方式。

### M2. 用户级限流实际为死代码
- **文件**: `video-api/.../interceptor/RateLimitInterceptor.java:82-83`
- **描述**: 限流拦截器在 order(1) 运行，认证拦截器在 order(2) 运行。限流执行时 `UserContext.getUser()` 始终为 null，用户级限流永远不会触发。
- **建议**: 在限流拦截器中直接从请求提取 API Key 进行用户级限流（不依赖完整认证流程），或将用户级限流移至认证之后。

### M3. CreateUserRequest 缺少输入校验
- **文件**: `video-api/.../controller/TestController.java:75-78`
- **描述**: `username` 和 `email` 无 `@NotBlank`、`@Size`、`@Email`、`@Pattern` 校验，可提交空值、超长字符串或 XSS 载荷。
- **建议**: 添加 `@NotBlank @Size(max=64) @Pattern(regexp="^[a-zA-Z0-9_]+$")` 于 username，`@Email @Size(max=128)` 于 email。

### M4. InitUploadRequest 缺少关键字段校验
- **文件**: `video-common/.../dto/request/InitUploadRequest.java`
- **描述**:
  - `fileName` 无 `@Size(max=255)` 约束
  - `fileHash` 无格式校验（应为 32 位十六进制 MD5）
  - `metadata` 无大小限制
- **建议**: 添加 `@Size(max=255)` 于 fileName，`@Pattern(regexp="^[a-fA-F0-9]{32}$")` 于 fileHash，`@Size(max=4096)` 于 metadata。

### M5. Kafka 反序列化信任所有包
- **文件**: `application-dev.yml.example:69` / `worker application-dev.yml.example:36`
- **描述**: `spring.json.trusted.packages: "*"` 禁用了 Kafka 反序列化安全检查，允许从消息载荷实例化任意 Java 类（不安全反序列化风险）。
- **建议**: 指定具体信任包：`com.videoai.common`。

### M6. Redis 密码通过命令行参数传递
- **文件**: `docker/docker-compose.yml:36-39`
- **描述**: `--requirepass "${REDIS_PASSWORD}"` 使密码在 `docker inspect`、`ps aux` 中可见。
- **建议**: healthcheck 改用 `REDISCLI_AUTH` 环境变量：`["CMD", "sh", "-c", "REDISCLI_AUTH=$REDIS_PASSWORD redis-cli ping"]`。

### M7. DataInitializer 中测试用户 Secret 为明文字符串
- **文件**: `video-api/.../config/DataInitializer.java:64`
- **描述**: `user.setApiSecret("hashed_secret_for_test")` 使用静态可预测值，并非真正哈希。
- **建议**: 使用 `UserService.hashSecret()` 生成真正的哈希值。

### M8. 测试路径排除无生产环境保护
- **文件**: `video-api/.../config/WebMvcConfig.java:51-52`
- **描述**: `/test/user/**` 从认证中排除，但无 profile 区分，生产环境同样可访问。
- **建议**: 与 H4 一起解决，添加 `@Profile("dev")` 后测试端点在生产环境不会注册。

---

## 低危问题 (LOW)

| # | 文件 | 问题 | 建议 |
|---|------|------|------|
| L1 | `AuthInterceptor.java:122` | API Key 格式校验过于宽松，仅检查前缀和最小长度 | 添加正则：`^sk_(live\|test)_[A-Za-z0-9_-]{20,}$` |
| L2 | `UserMapper.java:25` | `SELECT *` 返回 `api_secret`，每次认证请求都加载敏感字段 | 按需查询，认证时排除 `api_secret` |
| L3 | `application.yml:43` | SQL 日志（含参数值）在基础配置中开启，生产环境可能泄露数据 | 移至 `application-dev.yml` |
| L4 | `docker-compose.yml:87` | MinIO 使用 `latest` 镜像标签，不可复现构建 | 锁定具体版本号 |
| L5 | `application-dev.yml.example` | Druid 监控控制台暴露在 `/api/druid/`，且已排除认证 | 确保仅 dev profile 启用 |
| L6 | `docker-compose.yml` | 所有中间件端口映射到宿主机，生产部署时应禁止 | 添加注释说明仅用于开发 |

---

## 修复优先级建议

1. **立即修复** (阻塞提交):
   - H1: MD5 → BCrypt
   - H2: 停止日志输出明文密钥
   - H4: TestController 添加 `@Profile("dev")`
   - H5: 移除 SQL 中硬编码 API Key

2. **本次提交前修复**:
   - H3: 修复 CORS 配置
   - H6: User 实体敏感字段保护
   - M2: 修复用户限流死代码
   - M4: InitUploadRequest 输入校验

3. **后续迭代修复**:
   - 其余中危和低危问题
