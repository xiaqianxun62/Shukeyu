# 数科羽 shukeyu - 后端服务

校园羽毛球球局系统后端：**Java 17 + Spring Boot 3.3 + Redis + MySQL + MyBatis-Plus**

## 模块结构

```
com.yuyue
├── common          # ApiResponse / ErrorCode / 全局异常 / 常量
├── config          # JWT / ELO / WebMvc 配置
├── controller      # Auth / Game / Match / Ranking REST 接口
├── dto             # 请求与响应 DTO（jakarta validation 校验）
├── engine          # 核心引擎
│   ├── EloCalculator    # ELO 计算（K 因子衰减 / 双打组合期望 / 防刷）
│   └── ArrangeEngine    # 自动编排（6 套方案按性别构成自动过滤）
├── entity          # MyBatis-Plus 实体
├── exception       # BizException
├── mapper          # MyBatis-Plus Mapper
├── service         # UserService / GameService / MatchService / RankingService
├── util            # JwtUtil
└── web             # AuthInterceptor / UserContext
```

## 核心设计

### ELO 积分（EloCalculator）

- 期望 `E = 1 / (1 + 10^((对手分-己方分)/400))`，delta = `K * (S - E)`
- **K 因子按场次衰减**：≤20 场 K=40，21-60 场 K=24，>60 场 K=16（新手波动大、老手稳定）
- **双打**：同队两人共用组合期望 E（按队伍平均分计算），但**各自按自己的 K** 更新
- **防刷**：积分差拉开后强方 E 趋近 1，胜方几乎不得分

### 自动编排（ArrangeEngine）

按报名人员性别构成从上到下过滤，命中第一个适用方案：

| # | 方案 | 触发条件 |
|---|------|---------|
| ① | 全单打 | 兜底 |
| ② | 全混双 | 男数 = 女数 |
| ③ | 全男双 | 女数 ≤ 1 |
| ④ | 全女双 | 男数 ≤ 1 |
| ⑤ | 混搭·混双优先 | 男数 > 女数（男多 1 时 1 局男双 vs 混双） |
| ⑥ | 混搭·同性别优先 | 女 > 男，且男 ≥2 女 ≥2 |

### 对局结算（同步）

上报对局结果与现场计分均在**同一事务内同步**调用 `MatchSettleService`：更新 user 积分、写 rating_history、刷 Redis 榜单。按 `settle_status` 幂等，重复提交（改分）不会重复加分。

### Redis

- `yuyue:ranking:elo`（ZSet）：积分榜热点读，官网与小程序同源；每 10 分钟从 MySQL 全量校准，miss 时自动回源重建
- `yuyue:game:reg:count`（Hash）：报名人数计数缓存
- `yuyue:jwt:blacklist`（Set）：登出 token 黑名单

## API 一览

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/register` | 账号+姓名注册 |
| POST | `/api/auth/login` | 登录，返回 JWT |
| GET  | `/api/auth/me` | 当前用户信息 |
| PUT  | `/api/auth/profile` | 编辑个人信息（姓名 / 性别 / 账号，留空=不修改）；`POST /api/auth/profile` 同逻辑 |
| POST | `/api/games` | 发布球局 |
| GET  | `/api/games` | 球局列表 |
| GET  | `/api/games/{id}` | 球局详情（报名列表对外匿名） |
| POST | `/api/games/{id}/register` | 报名（body 可选 `anonymous:false` = 实名） |
| DELETE | `/api/games/{id}/register` | 取消报名（仅报名中可取消） |
| POST | `/api/games/{id}/arrange` | 自动编排（6 方案过滤） |
| POST | `/api/matches` | 上报对局结果，同步结算 ELO |
| GET  | `/api/ranking?n=10` | 积分榜 Top N |

除 `/api/auth/**` 外均需 `Authorization: Bearer <token>`。

## 接口文档（Swagger UI）

应用启动后访问：**http://localhost:8080/api/swagger-ui.html**

- 先调 `POST /api/auth/login` 拿到 token，点页面右上角 **Authorize** 填入即可调试受保护接口（粘贴时不需要带 `Bearer ` 前缀，UI 会自动补上）
- OpenAPI JSON：`/api/v3/api-docs`
- 文档相关路径（对外为 `/api/swagger-ui/**`、`/api/v3/api-docs/**`；拦截器按 context-path 之后的路径匹配，即 `WebMvcConfig` 中放行的 `/swagger-ui/**`、`/v3/api-docs/**`）不会被登录拦截器拦住

## 快速启动

```bash
# 1. 中间件（docker compose 或本机已有 MySQL/Redis 可跳过）
docker run -d --name yuyue-mysql  -e MYSQL_ROOT_PASSWORD=root -p 3306:3306 mysql:8.0
docker run -d --name yuyue-redis  -p 6379:6379 redis:7

# 2. 启动（数据库 + 表结构由 Flyway 自动创建，无需手动执行 SQL）
mvn spring-boot:run
```

环境变量：`MYSQL_HOST` `MYSQL_PORT` `MYSQL_USER` `MYSQL_PASSWORD` `REDIS_HOST` `REDIS_PORT` `JWT_SECRET`

## 数据库迁移（Flyway）

表结构在应用启动时由 Flyway 自动执行，**不需要手动建库或建表**：

- 迁移脚本目录：`src/main/resources/db/migration`，命名规则 `V<版本>__<描述>.sql`（注意是双下划线）
- 已执行过的脚本**不可再修改**（Flyway 会校验 checksum 并报错），新增变更请追加 `V2__xxx.sql`、`V3__xxx.sql`
- 数据库本身由 JDBC 参数 `createDatabaseIfNotExist=true` 自动创建，无需 `CREATE DATABASE`
- `baseline-on-migrate: true`：库中已有表但没有 Flyway 记录时（例如早期手工执行过 SQL），会自动建立基线而不是报错
- 执行记录保存在 `flyway_schema_history` 表，可直接查询迁移状态

## 热部署（DevTools）

已引入 `spring-boot-devtools`，开发期改完代码保存后应用会自动重启（用的是双类加载器，比手动重启快很多）：

- **IDEA**：`Settings → Build, Execution, Deployment → Compiler` 勾选 **Build project automatically**；再按 `Ctrl+Shift+A` 搜索 `Registry`，勾选 `compiler.automake.allow.when.app.running`。之后改代码按 `Ctrl+F9`（或等自动编译）即触发重启
- **命令行 `mvn spring-boot:run`**：DevTools 不会自己编译代码，需另开终端执行 `mvn compile` 才会触发重启，所以开发期建议用 IDE 启动
- 触发条件：`target/classes` 下的文件发生变化（Java 编译产物、`application.yml` 等资源）
- 生效范围：**仅开发期**。devtools 是 `optional` 依赖，`spring-boot-maven-plugin` 打包时会自动排除，生产 jar 里没有它
- 临时关闭：启动参数加 `-Dspring.devtools.restart.enabled=false`

## 测试

```bash
mvn test          # EloCalculatorTest + ArrangeEngineTest（覆盖需求文档全部规则场景）
```
