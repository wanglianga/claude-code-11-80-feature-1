# 防汛平台状态迁移与规则升级服务

某市防汛平台在汛期中途把固定阈值风险规则升级为多因子规则（同时考虑**上游水位趋势、泵站启停滞后、传感器可信度**）。
旧作业保存的是**网格窗口状态**，新规则需要**泵站—河段—道路三层中间状态**。本服务提供状态迁移与规则升级的全流程治理能力：

- **流定义登记表**：输入主题、事件时间字段、水位基准、状态模式、规则包版本、分区→道路/泵站/河段映射；
- **检查点清单**：每个分区的消费位点、窗口水印、状态快照、输出批次，并提供检查点比对（位点/水印滞后与就绪度）；
- **状态语义证明器**：比较旧新字段、聚合粒度与依赖关系，给出旧状态确实可转换的守恒条件；
- **迁移计划器**：按分区选择 `ADAPT`（经证明的状态适配）/ `REPLAY`（限定时间窗回放）/ `MANUAL`（人工裁定），支持混合路线；
- **双轨校验器**：旧新规则在同一输入截点并行计算并量化风险差异；
- **切换闸门**：仅当关键分区满足证据要求且覆盖率达标时发布新输出，否则维持旧输出并标记待验证；
- **回退控制器**：保留旧规则只读快照，撤回指定范围内的新输出；
- **事件证据账**：每次风险结论记录输入、规则与状态版本，区分 `OLD_OUTPUT` / `NEW_CANDIDATE` / `NEW_OUTPUT` / `RETRACTED`。

## 降雨事件重建与流域雨量贡献数据集

降雨记录若简单按每次强度过阈切场，会把同一场持续风暴拆成多个独立入流；按整夜合并又会把两场雨及其不同河道响应混在一起。本数据集以**事件**为单位重建降雨，并提供流域雨量贡献过程：

- **事件求值器**：把站点采样按**校时误差**对齐到标准 10 分钟时段（`correctedAt = observedAt − clockOffsetSec`），重建降雨事件，输出开始/结束、累计量上下界与空间覆盖；
- **流域映射器**：依据站点→子流域权重（版本化权重集）生成各子流域 10 分钟分区降雨过程，并按子流域面积折算流域面雨量；
- **事件版本账**：站点增补、设备更换、权重变动三类变更落账，按原重建参数重算两方案，保留对已计算风险窗口的影响前后对照；
- **缺测界限器**：缺测时段只给累计雨量上下界（下界=实测累计，上界=实测+缺测段×单时段上界估计），**不用插值点伪装实测**。

### 事件边界两方案

| 方案 | 规则 | 优点 | 代价 |
| --- | --- | --- |
| `FIXED_GAP` | 连续无雨 ≥ 固定间隔（默认 30min）即拆场 | 规则透明、可复算 | 土壤已饱和时可能把对产流重要的间歇误判为新场 |
| `ADAPTIVE_WETNESS` | 前期累计面雨量 ≥ 湿润阈值（默认 12mm，视为土壤饱和）时，间歇须 ≥ 流域响应时间（默认 45min）才拆场 | 更贴近产流物理 | 需校准湿润阈值与响应时间 |

**缺测不按无雨处理**：某时段只要有加权站缺测且无实测降雨，该时段状态为 UNKNOWN。缺测段恰好落在候选无雨间隔内时，事件边界不可判——求值器枚举“缺测段有雨/无雨”两种假设分别划场，输出**候选区间**（`status=CANDIDATE`，按 `candidateSetNo` 分组），**不强行定场**；若各假设划场相同（如湿润方案中间歇必被合并），则定为 `CONFIRMED`。

### 跨日样例（演示数据）

2026-08-14 23:00 → 2026-08-15 02:00（UTC）两站 10 分钟雨量：

- `ST-A`（站钟快 30s）：23:50–00:20 有 30 分钟无雨间隔，00:20 短暂降雨，00:40 起再降雨；
- `ST-B`（站钟慢 40s）：同段 3 个时段**缺测**；
- 权重集 `WS-1`：SB-1（城东 5.2km²）A0.7/B0.3，SB-2（城西 3.8km²）A0.2/B0.8。

核对结论（`scripts/smoke-rainfall.sh` 逐步断言）：

1. **事件是否跨日**：`FIXED_GAP` 候选集 1 两场均不跨日（23:00–23:50、00:20–01:20），候选集 2 单场跨日；`ADAPTIVE_WETNESS` 因前期面雨量 15.4mm ≥ 12mm 且间歇 30min < 响应 45min，定为**单场跨日事件**（窗口末端确认无雨 40min < 45min，`openEnded=true`）；
2. **流域累计范围**：合并事件累计 **[24.369, 29.276]mm**（ST-B 缺测 3 段，上界 = 实测 20.8 + 3×事件内最大实测 3.2 = 30.4mm，不加插值）；空间覆盖 0.89；
3. **风险归因差异**：`FIXED_GAP` 候选集 1 把夜间归因于两场独立入流（15.4mm/MEDIUM + 8.9mm/LOW），`ADAPTIVE_WETNESS` 归因于单场跨日事件（MEDIUM..HIGH，上下界跨档两级并存）；
4. **版本账**：增补 ST-C（证实间歇期有雨）→ 候选区间消解为 CONFIRMED 单场跨日事件；ST-B 设备更换（校时误差 −40s→−5s）→ 采样重对齐、过程线前移；权重变动 → WS-3 重算累计与风险分档。

## 技术栈

Java 25 · Spring Boot 4.0 · Spring Data JPA · PostgreSQL 18 · springdoc-openapi（Swagger UI）· Maven

## 快速开始

### 方式一：Docker Compose 一键启动（应用 + PostgreSQL）

```bash
docker compose up --build
```

启动后访问 http://localhost:8080/swagger-ui.html 。

### 方式二：本地开发

前置：JDK 25、Maven 3.9+、PostgreSQL（库/用户/密码均为 `ccdev`，见 `src/main/resources/application.yml`，可用 `DB_URL/DB_USER/DB_PASSWORD` 覆盖）。

```bash
# 运行测试（使用 ccdev_test 库，首次需创建：CREATE DATABASE ccdev_test OWNER ccdev;）
mvn test

# 打包并启动（首次启动自动建表并写入演示数据）
mvn package -DskipTests
java -jar target/state-migration-service-1.0.0.jar
```

### 测试账号（HTTP Basic）

| 账号 | 密码 | 角色 |
| --- | --- | --- |
| `operator` | `operator123` | OPERATOR |
| `admin` | `admin123` | ADMIN, OPERATOR |

Swagger UI（`/swagger-ui.html`）与 OpenAPI 文档（`/v3/api-docs`）无需登录；`/api/**` 需上述账号。

## 接口一览

| 模块 | 方法与路径 | 说明 |
| --- | --- | --- |
| 流定义 | `POST /api/streams`、`GET /api/streams[/{id}]` | 登记/查询流定义 |
| 规则包 | `POST /api/streams/{id}/rule-packages`、`GET .../rule-packages` | 登记/二次修订；多因子修订使相关比较与证明过期并重新绑定目标版本 |
| 流事件 | `POST /api/streams/{id}/events`、`GET .../events` | 事件写入（位点按分区自增）与查询 |
| 检查点 | `POST /api/streams/{id}/checkpoints`、`GET .../checkpoints` | 登记/查询检查点清单 |
| 检查点比对 | `GET /api/streams/{id}/checkpoint-compare` | 位点/水印滞后、快照与迁移就绪度比对 |
| 证明器 | `POST /api/streams/{id}/proofs`、`GET /api/proofs/{id}` | 生成状态语义证明（守恒条件） |
| 迁移编排 | `POST /api/streams/{id}/migration-plans`、`POST .../execute-all`、`POST /api/migration-plans/{id}/execute`、`POST /api/migration-plans/{id}/adjudicate` | 计划生成/执行/人工裁定 |
| 双轨校验 | `POST /api/streams/{id}/dual-track-runs`、`GET /api/dual-track-runs/{id}/diffs` | 同截点并行计算与差异量化 |
| 切换闸门 | `POST /api/streams/{id}/gate/evaluate`、`POST .../gate/release`、`GET .../gate` | 评估/发布/状态 |
| 回退控制 | `POST /api/streams/{id}/rollbacks`、`GET .../rollbacks` | 撤回输出范围、旧快照只读 |
| 证据账 | `GET /api/streams/{id}/evidence?partitionNo=&kind=` | 按分区/类别查询证据 |
| 雨量站点 | `POST /api/rain/stations`、`GET /api/rain/stations[/{id}]` | 登记/查询雨量站（校时误差、设备编号） |
| 雨量采样 | `POST /api/rain/stations/{id}/samples`、`GET .../samples?from=&to=` | 批量写入 10 分钟采样（按校时误差对齐，同时段重写视为订正）与查询 |
| 权重集 | `POST /api/rain/weight-sets`、`GET /api/rain/weight-sets` | 登记首个站点→子流域权重集（后续变动走版本账） |
| 事件重建 | `POST /api/rain/events/reconstruct`、`GET /api/rain/events[/{id}]` | 两方案重建（起止/累计上下界/空间覆盖/候选区间）与查询 |
| 方案对比 | `GET /api/rain/events/compare?from=&to=` | 候选集、风险窗口与风险归因差异 |
| 子流域过程 | `GET /api/rain/subbasins/{code}/series?from=&to=&scheme=` | 分区 10 分钟雨量上下界与实测覆盖率 |
| 事件版本账 | `POST /api/rain/ledger/changes`、`GET /api/rain/ledger?changeType=` | 站点增补/设备更换/权重变动落账并重算，查询对已算风险窗口的影响 |

## 迁移路线选择语义

- **状态适配（ADAPT）**：旧聚合拆解/提升为三层状态，延迟小。仅当守恒条件满足时选用：
  - `PUMP_ON_OFF_CONSERVATION`：由启停事件推导的窗口内泵运行时长须与快照累计量一致（容差 1 分钟）；
  - `WINDOW_GRANULARITY_MATCH`：旧窗口须为约两小时，与新规则累计粒度一致。
  快照缺失启停事件时**适配器必须拒绝该快照，禁止以零值填充缺失启停区间**；执行时若发现累计量不守恒，计划器自动**转为限定时间窗回放**。
- **限定窗口回放（REPLAY）**：从冻结位点对受影响分区回放原始事件重算三层状态，语义最直接；回放窗口受事件保留范围约束，保留不足时降级为**人工裁定（MANUAL）**。
- 计划器依据状态可逆性（证明结论）、事件保留范围与汛情时效（关键分区优先适配）为各分区选择或混合两条路线。

## 关键业务规则

1. 新规则把某道路由低风险改为高风险、而旧作业尚未完成该分区检查点时，闸门**维持旧输出**并把该分区标记为**待验证**（`pendingVerification`），补齐检查点后方可放行。
2. 双轨期间规则二次修订（如可信度规则 v2.0.0→v2.1.0）：相关双轨比较与适配证明**过期**，引用旧证明的计划置为 `STALE`，目标版本**重新绑定**到新规则包，须重新证明与双轨后才能发布。
3. 回放分区越过切换水印时，证据账区分：已发布新输出（越水印部分**撤回**为 `RETRACTED`）、本次回放结果仅作**新候选**（`NEW_CANDIDATE`）、旧输出（`OLD_OUTPUT`）保持不变。
4. 回退保留旧规则**只读快照**，撤回 `[fromOffset, toOffset]` 范围内的新输出，闸门回到 `HOLD`，流恢复旧规则服务。

## 降雨数据集关键规则

1. **校时对齐**：采样落库即按站点当前校时误差校正（`correctedAt = observedAt − clockOffsetSec`）并归入标准 10 分钟时段；设备更换只重对齐生效时刻之后观测的采样，历史校正值落库可审计。
2. **缺测不伪装**：`MISSING` 采样不落数值；事件累计下界只计实测，上界按“事件内最大实测→窗口最大实测→同时段跨站最大→配置上限（默认 10mm/10min）”估计，估计值仅用于上界，绝不写作实测。
3. **缺测不定场**：缺测段落在候选无雨间隔内时边界不可判，输出候选区间（`CANDIDATE` + `candidateSetNo`），待数据补齐或站点增补后自然消解；不强行定场。
4. **版本账可追溯**：站点增补/设备更换/权重变动触发两方案按原参数重算，旧事件与风险窗口置 `SUPERSEDED`（记录 `supersededByRunNo`），账内保存风险窗口前后对照。
5. **风险窗口分档**：流域面雨量累计按 `riskMediumMm`（默认 15mm）/`riskHighMm`（默认 25mm）定级，上下界分别定级，跨档时两级并存（如 MEDIUM..HIGH）。

## 演示数据（首次启动自动写入）

流 `FY-MAIN`（4 个分区，p0/p1/p2 为关键分区）：

| 分区 | 道路 | 初始状态 | 预期路线 |
| --- | --- | --- | --- |
| 0 | R-001 | 检查点完成，启停守恒满足 | ADAPT |
| 1 | R-002 | 快照缺失启停事件 | REPLAY（禁止零值填充） |
| 2 | R-003 | 旧作业检查点未完成；新规则 LOW→HIGH | REPLAY + 闸门待验证 |
| 3 | R-004 | 检查点完成，空启停守恒满足 | ADAPT |

## 验证方式（本仓库交付前实际执行过的验证）

```bash
# 1) 自动化测试：30 个用例全部通过（证明器/计划器/双轨/闸门/规则修订/回退/证据账/接口鉴权
#    + 降雨重建两方案/跨日/候选区间/缺测上下界/流域映射/版本账三类变更）
mvn test

# 2) 迁移流程端到端冒烟（应用已启动、演示数据为初始状态）：12 步完整升级流程，输出 SMOKE_OK
scripts/smoke.sh

# 3) 降雨数据集端到端冒烟：校时对齐 → 两方案重建 → 候选区间/跨日/累计范围核对 →
#    风险归因差异 → 子流域过程 → 版本账三类变更，输出 RAIN_SMOKE_OK
scripts/smoke-rainfall.sh

# 4) 重复冒烟前先重置演示数据（清空业务表并重启应用重新播种）
scripts/reset-db.sh && scripts/smoke.sh && scripts/smoke-rainfall.sh
```

冒烟流程：证明 → 迁移编排（p0/p3 适配、p1/p2 回放）→ 执行 → 双轨（p2 低→高升级）→ 闸门待验证 →
补齐 p2 检查点 → 规则二次修订 v2.1.0（证明/比较过期、计划 STALE）→ 重新绑定 → 发布新输出 →
p1 回放越过切换水印（已发布输出撤回、结果仅作候选）→ 回退（快照只读、范围撤回）→ 证据账分类统计。

> 说明：本开发容器无 Docker 守护进程，`docker compose up --build` 未在本环境实际执行；
> Dockerfile/Compose 使用标准基础镜像（`maven:3.9-eclipse-temurin-25`、`eclipse-temurin:25-jre`、`postgres:18`），
> 应用本体已通过上述 `mvn test` 与 `scripts/smoke.sh` 在相同 jar 上验证。

## 目录结构

```
src/main/java/com/flood/statemigrate/
├── domain/      # 实体与枚举（流定义/规则包/检查点/证明/计划/双轨/闸门/回退/证据/事件
│                #   + 雨量站/采样/降雨事件/风险窗口/子流域过程/权重集/版本账）
├── repo/        # Spring Data JPA 仓库
├── service/     # 证明器、计划器（适配+回放）、双轨、闸门、回退、证据账、聚合与规则引擎
│                #   + 事件求值器、缺测界限器、流域映射器、事件版本账、降雨查询
├── web/         # REST 控制器与请求 DTO
├── config/      # 安全、OpenAPI、演示数据种子
└── error/       # 统一异常处理
scripts/         # smoke.sh 迁移冒烟、smoke-rainfall.sh 降雨冒烟、reset-db.sh 演示数据重置
docker/          # Maven 镜像仓库配置（构建加速）
Dockerfile · docker-compose.yml
```
