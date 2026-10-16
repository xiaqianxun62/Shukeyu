# 数科羽 shukeyu - 后端服务

校园羽毛球球局系统后端：**Java 17 + Spring Boot 3.3 + Redis + MariaDB/MySQL + MyBatis-Plus + Flyway**

统一响应体 `ApiResponse{code,message,data}`：HTTP 状态码恒为 200，`code === 0` 才算成功；接口统一挂在 context-path `/api` 下。

## 模块结构

```
com.yuyue
├── aspect         # @OperationLog 操作日志切面（AOP）
├── common         # ApiResponse / ErrorCode / GlobalExceptionHandler / Constants
├── config         # JWT / ELO / CORS / WebMvc / OpenAPI / 上传 配置
├── controller     # 13 个 REST 控制器（见下方 API 一览）
├── dto            # 请求与响应 DTO（jakarta validation 校验）
├── engine         # 核心引擎
│   ├── EloCalculator    # ELO 计算（K 因子衰减 / 双打组合期望 / 防刷）
│   ├── ArrangeEngine    # 自动编排（6 套方案按性别构成自动过滤）
│   ├── RotationEngine   # 场地轮转轮排（机会均等、实力均衡）
│   └── TournamentEngine # 赛事引擎（抽签 / 小组积分 / 淘汰对阵）
├── entity         # MyBatis-Plus 实体
├── exception      # BizException
├── mapper         # MyBatis-Plus Mapper
├── service        # UserService / GameService / MatchService / MatchSettleService /
│                  # RankingService / RotationService / TournamentService /
│                  # VerificationService / SettingsService / VisitLogService /
│                  # CaptchaService / EloSimulateService / EloRulesService /
│                  # GameCleanupService / RegistrationSnapshotService / WeChatService
├── util           # JwtUtil / ImageUtils / MatchFormatUtil
└── web            # AuthInterceptor / 访问日志拦截器 / UserContext
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

### 轮排（RotationEngine）

场地轮转上场：按轮生成各场地对阵与休息名单，机会均等、实力均衡。支持纯试算（不落库）与按球局报名名单生成（落库、可计分）。

### 赛事（TournamentEngine）

大型比赛全生命周期：赛事创建 → 报名（可配对双打搭档）→ 抽签 → 小组赛积分榜 → 淘汰赛对阵图 → 逐场录分 → 最终名次。

### 对局结算（同步）

上报对局结果与现场计分均在**同一事务内同步**调用 `MatchSettleService`：更新 user 积分、写 rating_history、刷 Redis 榜单。按 `settle_status` 幂等，重复提交（改分）不会重复加分；提交 0:0 撤销本场会退回已结算积分。

### Redis

- `yuyue:ranking:elo`（ZSet）：积分榜热点读，官网与小程序同源；每 10 分钟从 MySQL 全量校准，miss 时自动回源重建
- `yuyue:game:reg:count`（Hash）：报名人数计数缓存
- `yuyue:jwt:blacklist`（Set）：登出 token 黑名单

## API 一览

除以下公开接口外，均需 `Authorization: Bearer <token>`：

- **免登录入口**：`/auth/captcha` `/auth/name-check` `/auth/register` `/auth/login` `/auth/wxlogin`、`/elo/**`（试算工具）、`/rotation/plan`、`/uploads/**`、`/actuator/**`
- **公开只读**（GET，带 token 时软鉴权、用于标记「我的」）：`/ranking*`、`/games` 列表与详情、`/games/{id}/matches`、`/games/title-check`、`/courts`、`/tournaments` 列表与详情/对阵图/赛程、`/settings/home-poem`

注意：`/tournaments/partners/lookup` 虽是 GET 但不匹配公开规则，仍需登录；`/rotation/game/**`（球局轮排）也需登录。

### 认证与用户 `auth` / `users`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/api/auth/captcha` | 图形验证码（登录/注册必填），返回 `{uuid, image}` |
| GET  | `/api/auth/name-check` | 昵称查重（公开） |
| POST | `/api/auth/register` | 账号+姓名+验证码注册，返回 JWT |
| POST | `/api/auth/login` | 登录（含验证码），返回 JWT |
| POST | `/api/auth/wxlogin` | 微信小程序 code 换 openid 登录，首登自动注册 |
| POST/PUT | `/api/auth/profile` | 完善/编辑个人信息（留空=不修改） |
| GET  | `/api/auth/me` | 当前用户信息 |
| POST | `/api/auth/logout` | 登出（token 入黑名单立即失效） |
| POST | `/api/auth/avatar` | 上传个人头像（multipart `file`） |
| GET  | `/api/users` | 管理员：列出所有用户 |
| PUT  | `/api/users/{id}/admin?admin=` | 管理员：设置/取消管理员身份 |

### 球局 `games`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/games` | 发布球局 |
| GET  | `/api/games` | 球局列表 |
| GET  | `/api/games/my` | 我参与过的球局 |
| GET  | `/api/games/title-check` | 标题查重（公开，编辑传 excludeId） |
| POST | `/api/games/upload-cover` | 上传球局封面，返回 `/uploads/xxx` |
| GET  | `/api/games/{id}` | 球局详情（报名列表对外匿名） |
| PUT/DELETE | `/api/games/{id}` | 编辑 / 删除球局（仅发布人） |
| PUT  | `/api/games/{id}/hide` · `/unhide` | 隐藏 / 取消隐藏 |
| POST | `/api/games/{id}/register` | 报名（body 可选 `anonymous:false` = 实名） |
| DELETE | `/api/games/{id}/register` | 取消报名（仅报名中可取消） |
| GET  | `/api/games/{id}/matches` | 球局对阵与比分（未登录只看显示昵称） |
| POST | `/api/games/{id}/arrange` | 自动编排（6 方案过滤） |

### 对局与积分 `matches` / `ranking` / `me`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/matches` | 上报对局结果，同步结算 ELO |
| PUT  | `/api/matches/{id}/score` | 现场计分（改分留痕；0:0 撤销本场并退分） |
| GET  | `/api/ranking?n=10` | 积分榜 Top N |
| GET  | `/api/ranking/rebuild` | 全量重建 Redis 榜单 |
| GET  | `/api/me/matches` | 我的历史场次 |
| GET  | `/api/me/rating-history` | 我的积分变动曲线 |

### 轮排 `rotation` / ELO 试算 `elo`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/rotation/plan` | 轮排试算（纯计算不落库） |
| POST | `/api/rotation/game/{gameId}` | 按球局报名名单生成轮排（落库可计分，仅发起人） |
| GET  | `/api/rotation/game/{gameId}` | 还原球局已生成的轮排表 |
| POST | `/api/elo/simulate` | ELO 编排+结算试算（不写库） |
| GET  | `/api/elo/rules` | K 因子档位与典型场景加减分 |

### 赛事 `tournaments`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/api/tournaments` | 赛事广场（报名中的排前面，标记我已报名） |
| POST | `/api/tournaments` | 创建赛事 |
| GET  | `/api/tournaments/{id}` | 赛事详情（组别报名情况 + 领奖台） |
| GET  | `/api/tournaments/{id}/bracket` | 对阵图（小组赛积分榜 + 淘汰赛逐轮） |
| GET  | `/api/tournaments/{id}/matches` | 赛程列表 |
| POST | `/api/tournaments/{id}/draw` | 抽签 |
| PUT  | `/api/tournaments/{id}/cancel` | 取消赛事 |
| GET  | `/api/tournaments/partners/lookup?account=` | 按账号查双打搭档 |
| POST | `/api/tournaments/{id}/entries` | 报名 |
| DELETE | `/api/tournaments/{id}/entries/{entryId}` | 取消报名 |
| PUT  | `/api/tournaments/matches/{matchId}/score` | 赛程录分 |

### 管理与站点 `courts` / `verification` / `settings` / `visit-logs`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET/POST | `/api/courts` | 球场列表（公开）/ 新增（管理员） |
| GET  | `/api/courts/admin` | 管理员球场列表 |
| PUT/DELETE | `/api/courts/{id}` | 编辑 / 删除球场（管理员） |
| PUT  | `/api/courts/{id}/toggle` | 启用/停用（管理员） |
| GET/POST/PUT/DELETE | `/api/verification/questions[/{id}]` | 身份校验问题库 CRUD（管理员） |
| GET  | `/api/verification/random` | 随机抽一题（用户端） |
| POST | `/api/verification/submit` | 提交答案，答对通过身份校验 |
| GET/PUT | `/api/settings/home-poem` | 首页诗句文案（公开读 / 管理员改） |
| GET  | `/api/visit-logs?ip=&limit=` | 访问日志（管理员，按 IP 过滤） |

## 接口文档（Swagger UI）

应用启动后访问：**http://localhost:8080/api/swagger-ui.html**

- 先调 `POST /api/auth/login` 拿到 token，点页面右上角 **Authorize** 填入即可调试受保护接口（粘贴时不需要带 `Bearer ` 前缀，UI 会自动补上）
- OpenAPI JSON：`/api/v3/api-docs`
- 文档相关路径（对外为 `/api/swagger-ui/**`、`/api/v3/api-docs/**`；拦截器按 context-path 之后的路径匹配，即 `WebMvcConfig` 中放行的 `/swagger-ui/**`、`/v3/api-docs/**`）不会被登录拦截器拦住

## 快速启动

```bash
# 1. 中间件（docker compose 或本机已有 MySQL/MariaDB/Redis 可跳过）
docker run -d --name yuyue-mysql  -e MYSQL_ROOT_PASSWORD=root -p 3306:3306 mysql:8.0
docker run -d --name yuyue-redis  -p 6379:6379 redis:7

# 2. 启动（数据库 + 表结构由 Flyway 自动创建，无需手动执行 SQL）
mvn spring-boot:run
```

环境变量：`MYSQL_HOST` `MYSQL_PORT` `MYSQL_USER` `MYSQL_PASSWORD` `REDIS_HOST` `REDIS_PORT` `REDIS_PASSWORD` `JWT_SECRET`

> 驱动使用 `mariadb-java-client`（JDBC URL 为 `jdbc:mariadb://...`）：官方驱动支持 ed25519/unix_socket 等认证插件，MySQL Connector/J 在握手阶段会报 `AuthenticationProvider.BadAuthenticationPlugin`。连 MySQL 8 也可用该驱动。

## 数据库迁移（Flyway）

表结构在应用启动时由 Flyway 自动执行，**不需要手动建库或建表**：

- 迁移脚本目录：`src/main/resources/db/migration`，命名规则 `V<版本>__<描述>.sql`（注意是双下划线）
- 已执行过的脚本**不可再修改**（Flyway 会校验 checksum 并报错），新增变更请追加更高版本号的 SQL（当前已到 V28）
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
mvn test   # EloCalculatorTest + ArrangeEngineTest + TournamentEngineTest + RotationEngineTest
           # 覆盖需求文档全部规则场景
```

## 相关项目

- PC 官网 / 管理后台：[shukeyu-vue](../shukeyu-vue)（Vue 3 + Vite）
- 微信小程序 / H5：[shukeyu-wxapp](../shukeyu-wxapp)（uni-app）
