# 第1章：项目架构 & 多模块设计

---

## 一、整体架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                      客户端（浏览器/App）                         │
└──────────────────────────┬──────────────────────────────────────┘
                           │ HTTP 请求
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│  video-api（端口 8080）                                           │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌───────────────┐    │
│  │Controller│→ │ Service  │→ │          │  │ Interceptor   │    │
│  │  接口层   │  │  业务层   │  │          │  │ (认证/限流)    │    │
│  └──────────┘  └──────────┘  │          │  └───────────────┘    │
│                              │          │                       │
│  依赖 ↓                      │          │                       │
└──────────────────────────────┼──────────┼───────────────────────┘
                               │          │
┌──────────────────────────────┼──────────┼───────────────────────┐
│  video-infrastructure        │          │                       │
│  ┌──────────┐  ┌──────────┐  │          │                       │
│  │  Mapper   │  │ MinIO    │  │          │                       │
│  │ (MySQL)   │  │ Storage  │  │          │                       │
│  └──────────┘  └──────────┘  │          │                       │
│  ┌──────────┐  ┌──────────┐  │          │                       │
│  │  Redis    │  │  Kafka   │  │          │                       │
│  │ (Redisson)│  │ Producer │  │          │                       │
│  └──────────┘  └──────────┘  │          │                       │
│                              │          │                       │
│  依赖 ↓                      │          │                       │
└──────────────────────────────┼──────────┼───────────────────────┘
                               │          │
┌──────────────────────────────┼──────────┼───────────────────────┐
│  video-common（纯POJO + 工具）  │          │                       │
│  ┌──────────┐  ┌──────────┐  │          │                       │
│  │  Domain   │  │  DTO     │  │          │                       │
│  │ (实体类)   │  │(请求/响应)│  │          │                       │
│  └──────────┘  └──────────┘  │          │                       │
│  ┌──────────┐  ┌──────────┐  │          │                       │
│  │  Enum     │  │  Utils   │  │          │                       │
│  │ (枚举)    │  │ (工具类)  │  │          │                       │
│  └──────────┘  └──────────┘  │          │                       │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  video-worker（端口 8081）—— 后台任务处理                         │
│  Kafka Consumer → 视频抽帧 → AI分析 → 结果写回DB                  │
│  同样依赖 infrastructure + common                                │
└─────────────────────────────────────────────────────────────────┘
```

## 二、模块依赖关系

```
video-api  ──依赖──▶  video-infrastructure  ──依赖──▶  video-common
video-worker ─依赖──▶ video-infrastructure  ──依赖──▶ video-common
```

**关键规则：依赖方向是单向的，只能从上往下依赖，不能反向！**

### 各模块职责

| 模块 | 职责 | 能放什么 | 不能放什么 |
|------|------|---------|-----------|
| **video-common** | 纯定义层，零基础设施依赖 | 实体类、DTO、枚举、工具类、异常定义 | Mapper、Redis操作、Kafka配置 |
| **video-infrastructure** | 基础设施层，封装所有中间件操作 | Mapper、Redis配置、Kafka配置、MinIO操作、MySQL配置 | Controller、Service业务逻辑 |
| **video-api** | API服务，面向用户 | Controller、Service、Interceptor、JWT工具 | 直接操作数据库（必须通过infrastructure） |
| **video-worker** | 后台Worker | Kafka消费者、任务处理逻辑 | Controller、用户认证 |

### video-common vs video-infrastructure 的区别

这是一个**经典面试题**：

- **video-common**：放的是"是什么"，即领域模型（User、UploadSession）、枚举（TaskStatus）、DTO、工具类。它**不依赖任何中间件**，只有纯粹的 Java 类和少量轻量依赖（Hutool、Lombok）。
- **video-infrastructure**：放的是"怎么存"，即对中间件的具体操作。MySQL 的 Mapper、Redis 的操作、Kafka 的配置、MinIO 的 StorageService 都在这里。

**为什么分开？** 因为领域模型（common）是业务的核心，不应该因为换了数据库或缓存就修改。如果将来从 MySQL 换成 PostgreSQL，只需要改 infrastructure 层，common 层完全不动。这就是**依赖倒置原则（DIP）**的体现。

## 三、Maven 依赖管理详解

### 3.1 根 pom.xml 的关键设计

#### `<packaging>pom</packaging>`
根项目不是用来打包的，它只是一个**容器**，用来管理子模块。

#### `<modules>` 定义子模块
```xml
<modules>
    <module>video-common</module>
    <module>video-infrastructure</module>
    <module>video-api</module>
    <module>video-worker</module>
</modules>
```
注意顺序：common 在前，因为其他模块依赖它。Maven Reactor 会按依赖关系自动排序，但写对顺序更清晰。

#### `<dependencyManagement>` vs `<dependencies>`（面试高频！）

```
<dependencyManagement>          <dependencies>
  ┌───────────────────┐          ┌───────────────────┐
  │ 只是"声明"版本号   │          │ 真正"引入"依赖     │
  │ 不会实际下载       │          │ 会下载到项目        │
  │ 子模块按需引用     │          │ 所有子模块都有      │
  │ 不写版本号就继承   │          │                    │
  └───────────────────┘          └───────────────────┘
```

**举例**：
```xml
<!-- 父 pom 的 dependencyManagement -->
<dependencyManagement>
    <dependency>
        <groupId>com.google.guava</groupId>
        <artifactId>guava</artifactId>
        <version>33.0.0-jre</version>  <!-- 只声明版本 -->
    </dependency>
</dependencyManagement>

<!-- 子模块 video-api 的 dependencies -->
<dependencies>
    <dependency>
        <groupId>com.google.guava</groupId>
        <artifactId>guava</artifactId>
        <!-- 不需要写版本号！自动继承父 pom 的版本 -->
    </dependency>
</dependencies>
```

**好处**：
1. 统一版本管理——40个依赖不会出现Guava 5个版本打架的情况
2. 子模块按需引入——video-common 不需要 Guava 就不引
3. 依赖范围控制——不会因为不小心传递了不该传的依赖

#### 公共 `<dependencies>`（所有子模块自动获得）
```xml
<dependencies>
    <!-- Lombok：所有模块都用 -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <scope>provided</scope>
    </dependency>
</dependencies>
```
只把**真正每个模块都用**的放在这里，比如 Lombok。

### 3.2 依赖传递链

```
video-api 依赖 video-infrastructure
    └── video-infrastructure 依赖 video-common
            └── video-common 依赖 hutool, lombok
```

所以 video-api **自动获得**了 video-common 的所有类！这就是 Maven 的依赖传递。

**注意 video-common 的依赖都是 `<scope>provided</scope>`**：
```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    <scope>provided</scope>  <!-- 不会传递给依赖 common 的模块 -->
</dependency>
```
`provided` 意味着：编译时可用，但不会传递给依赖此模块的其他模块。因为 common 只是用了 MyBatis-Plus 的 `@TableName` 等注解，实际运行时由 infrastructure 提供完整的 MyBatis-Plus 依赖。

## 四、数据库表设计详解（5张表）

### 4.1 ER关系图

```
user (1) ──▶ (N) upload_session (1) ──▶ (1) analysis_task (N) ──▶ (N) ai_call_log
  │
  └──────▶ (1) user_quota
```

### 4.2 每张表的字段分析

#### user 表 —— 用户账号
| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT AUTO_INCREMENT | 物理主键，自增，性能最优 |
| `user_id` | VARCHAR(32) UNIQUE | **业务主键**，对外暴露的ID |
| `password` | VARCHAR(128) | BCrypt哈希，原始密码的2^10次hash |
| `api_key` | VARCHAR(64) UNIQUE | 开放API认证用 |
| `api_secret` | VARCHAR(128) | BCrypt加密存储，验证时不解密，直接对比 |
| `role` | VARCHAR(20) | USER/ADMIN/VIP，预留扩展 |
| `rate_limit` | INT | 单用户限流QPS，支持个性化配置 |

**设计亮点**：
- 物理主键 `id` 和业务主键 `user_id` 分离——防止暴露内部数据量，防止枚举攻击
- `password` 和 `api_secret` 都是 BCrypt 哈希——即使数据库泄露，也无法还原明文
- `rate_limit` 放在用户表——每个用户可以独立配置限流策略

#### upload_session 表 —— 上传会话（核心！）
| 字段 | 类型 | 说明 |
|------|------|------|
| `upload_id` | VARCHAR(64) UNIQUE | 上传会话唯一标识 |
| `file_hash` | VARCHAR(64) | 文件MD5，**秒传**的关键 |
| `total_chunks` | INT | 总分片数 |
| `uploaded_chunks` | JSON | 已上传分片索引，如 `[0,1,3]`——**断点续传**的关键 |
| `status` | TINYINT | 0:上传中→1:已完成→2:已合并→3:已过期→4:失败 |

**设计亮点**：
- `uploaded_chunks` 用 JSON 类型——MySQL 8.0 原生支持，可以用 `JSON_ARRAY_APPEND` 原子追加
- `file_hash` 建了索引——秒传查询 `WHERE file_hash=? AND status=2` 走索引
- 联合索引 `idx_status_created (status, created_at)`——方便定期清理过期会话

#### analysis_task 表 —— 分析任务
| 字段 | 类型 | 说明 |
|------|------|------|
| `task_id` | VARCHAR(64) UNIQUE | 任务唯一标识 |
| `status` | VARCHAR(20) | 状态机：PENDING→PROCESSING→COMPLETED/FAILED |
| `retry_count` / `max_retry` | INT | 失败重试机制，最多3次 |
| `result` | JSON | AI分析结果，JSON灵活存储不同模型输出 |
| `tokens_used` | BIGINT | Token消耗量，用于成本统计 |

**设计亮点**：
- 状态机设计——status 只能单向流转，不会出现状态混乱
- 重试字段——retry_count 记录已重试次数，max_retry 控制上限
- `result` 用 JSON——不同 AI 模型返回格式不同，JSON 可以灵活适配

#### user_quota 表 —— 用户配额
| 字段 | 类型 | 说明 |
|------|------|------|
| `quota_monthly` / `used_monthly` | BIGINT | 月度Token配额/已用量 |
| `quota_daily` / `used_daily` | BIGINT | 每日Token配额/已用量 |
| `reset_daily_at` / `reset_monthly_at` | DATETIME | 配额重置时间 |

**设计亮点**：双层配额——每日限额防突发，月度限额控成本。AI API 调用很贵，这是**成本控制**的关键表。

#### ai_call_log 表 —— AI调用日志
每次 AI 调用都记录：用了多少 Token、花了多少钱、耗时多少。这是**成本审计**的基础。

### 4.3 索引设计原则

```
UNIQUE KEY   → 业务唯一性保证（user_id, api_key, upload_id, task_id）
INDEX        → 高频查询条件（status, user_id, file_hash）
联合 INDEX   → 组合查询（status + created_at）
```

**为什么不给所有字段加索引？** 索引提升查询速度但降低写入速度，需要权衡。只给**实际查询用到的**字段加索引。

## 五、核心问题解答

### Q1：为什么要分四个模块？各模块的职责边界是什么？

**四个模块解决的核心问题：关注点分离**

| 模块 | 关注点 | 变化的原因 |
|------|--------|-----------|
| video-common | 业务定义 | 业务需求变化 |
| video-infrastructure | 技术实现 | 换数据库、换缓存 |
| video-api | 用户交互 | 接口变更 |
| video-worker | 后台处理 | 处理逻辑变更 |

例如：如果从 MinIO 换成阿里云 OSS，只需要改 infrastructure 层的 `StorageService`，api、worker 层完全不需要改。

### Q2：如果未来要加一个 admin 管理后台模块，怎么复用？

新建 `video-admin` 模块：
```xml
<artifactId>video-admin</artifactId>
<dependencies>
    <dependency>
        <groupId>com.videoai</groupId>
        <artifactId>video-infrastructure</artifactId>
    </dependency>
    <!-- 加上 Spring Boot Web、Security 等 -->
</dependencies>
```

admin 模块复用 infrastructure 的 Mapper、Redis 操作，但有自己的 Controller（管理接口）和 Service（管理逻辑），与 video-api 完全独立部署。

### Q3：Maven 依赖传递和 `<dependencyManagement>` 的区别？

| 特性 | `<dependencyManagement>` | `<dependencies>` |
|------|-------------------------|-------------------|
| 是否实际引入 | ❌ 只声明版本 | ✅ 真正引入 |
| 子模块是否自动获得 | ❌ 需要显式引用（但不用写版本） | ✅ 自动传递 |
| 用途 | 统一版本管理 | 实际使用依赖 |
| 类比 | 超市货架上的价格标签 | 你真正放进购物车的商品 |

## 六、面试模拟

### "介绍一下你的项目架构"
> "这是一个基于 Spring Boot 3.2 的多模块项目，分为 API 服务和 Worker 服务两个独立部署单元。按照职责单一原则拆分为四个 Maven 模块：common 层放领域模型和枚举，不依赖任何中间件；infrastructure 层封装 MySQL/Redis/Kafka/MinIO 操作；api 层面向用户提供 REST 接口；worker 层消费 Kafka 消息做后台处理。依赖方向是单向的，api → infrastructure → common，保证了层间的低耦合。"

### "为什么用 MyBatis-Plus 而不是 JPA？"
> "三个原因：1）SQL 可控性更强，复杂查询性能优化更灵活；2）MyBatis-Plus 提供了通用 CRUD，开发效率不输 JPA；3）国内主流，团队上手成本低。JPA 适合简单 CRUD 场景，复杂查询用 JPQL 或原生 SQL 反而更麻烦。"