# 城市智能垃圾房满溢异味投放违规联动服务

面向物业、环卫、街道、居民四类角色的垃圾房现场治理服务。垃圾房设备上报**厨余桶满溢、其他垃圾容量、异味、门禁、摄像头识别、称重、消杀、清运车到场、心跳**数据；居民刷卡/扫码投放时记录**小区、楼栋、垃圾类型、重量、积分与违规提示**。

核心设计目标：**让设备告警真正变成现场治理动作**。满溢、异味、错投往往同时出现，系统不会把每个告警单独派单，而是把同一垃圾房的未闭环告警**合并成一张“治理联动单”**，在单上派生保洁/消杀/清运/维修/复核/整改任务；积分、投诉、整改结果都与真实投放、清运记录一一对应，四端围绕同一份垃圾房状态更新。

## 技术栈

- Java 17、Spring Boot 3.3、Spring Data JPA、Bean Validation、Actuator
- PostgreSQL 16（生产）、H2（本地/测试，免外部依赖）
- Kafka 3.7（KRaft，无 Zookeeper；设备事件 `device-events`、联动事件 `governance-events`）
- springdoc-openapi + Swagger UI
- Maven、JUnit5、TestRestTemplate、内嵌 Kafka（spring-kafka-test）

## 一键启动（Docker Compose）

需要 Docker 与 Docker Compose v2。

```bash
docker compose up --build
```

启动后：

- 服务地址：http://localhost:8080
- Swagger UI：http://localhost:8080/swagger-ui.html
- OpenAPI JSON：http://localhost:8080/v3/api-docs
- 健康检查：http://localhost:8080/actuator/health

Compose 包含 3 个服务：`postgres`、`kafka`（KRaft 单节点）、`app`，app 会等待二者健康后启动。

## 本地开发与验证（无需 Docker / PostgreSQL / Kafka）

JDK 17 + Maven 3.8+，使用 `local` profile（H2 内存库、Kafka 关闭、设备事件同步处理）：

```bash
# 运行全部测试（11 个联动场景 + 1 个内嵌 Kafka 冒烟）
mvn test

# 本地启动（H2，自动写入种子数据）
mvn spring-boot:run -Dspring-boot.run.profiles=local

# 或打包后运行
mvn package -DskipTests
java -jar target/garbage-room-service.jar --spring.profiles.active=local
```

一键演示脚本（自动构建、启动、调用全链路、停止）：

```bash
scripts/verify.sh
```

## 测试账号 / 种子数据

| 角色 | 标识 | 说明 |
| --- | --- | --- |
| 居民 | 卡号 `CARD1001` | 王建国，阳光花园 12栋1单元，初始 100 积分 |
| 居民 | 卡号 `CARD1002` | 李秀兰，阳光花园 12栋2单元，初始 100 积分 |
| 居民 | 卡号 `CARD1003` | 张伟，翠柳小区 3栋1单元，初始 100 积分 |
| 物业 | 无需登录（演示用） | `GET /api/property/tasks/pending` 看待处理任务 |
| 环卫 | 无需登录（演示用） | `GET /api/sanitation/truck-priorities` 看清运优先级 |
| 街道 | 无需登录（演示用） | `GET /api/street/overview` 看分类质量与投诉趋势 |

垃圾房与小区：

| 垃圾房 | 小区 | 街道 |
| --- | --- | --- |
| `ROOM1` 阳光花园1号垃圾房 | `C001` 阳光花园 | 滨江街道 |
| `ROOM2` 阳光花园2号垃圾房 | `C001` 阳光花园 | 滨江街道 |
| `ROOM3` 翠柳小区1号垃圾房 | `C002` 翠柳小区 | 滨江街道 |

## 联动场景与验证方式

| 场景 | 触发方式 | 联动动作 | 自动化测试 |
| --- | --- | --- | --- |
| 厨余桶满但清运车未到 | 上报 `KITCHEN_FULL` ≥90% 且车未到场 | 环卫最高优先级（100）清运任务；车到场自动销警 | `kitchenFullNoTruck_...` |
| 满溢清运超时升级 | 满溢持续 10 分钟车未到 | **先通知物业临时封桶+引导备用点，再通知环卫调整路线（110）**；居民端展示最近备用点与预计恢复时间 | `kitchenFullSeal_...` |
| 清运车超时仍未到 | 封桶后满溢累计 30 分钟车未到 | **街道端超时督办（120）**，联动单记录清运延迟原因与居民投诉数 | 同上 |
| 封桶点继续投放 | 封桶期间居民在满溢点投厨余 | **不扣居民积分**，摄像头记录转现场管理问题，派物业现场督导；清运离场自动收口 | 同上 |
| 居民错投被识别 | 投放时摄像头 `detectedType` ≠ 投放桶 | 记录违规提示、扣分、派现场复核任务 | `odorAndMissort_...` |
| 摄像头误判申诉 | 居民申诉 + 物业复核成立 | 撤销违规、退还积分、错投告警忽略销单 | 同上 |
| 异味超标/异味投诉 | `ODOR` 上报或居民投诉 | 派消杀+保洁任务；投诉并入同一联动单，整改反馈后闭环 | `odorComplaint_...` |
| 夜间投放高峰 | 20:00–次日06:00 投放 ≥3 次 | 增派夜间保洁值守任务 | `nightPeak_...` |
| 设备断网 | 心跳超时（默认 10 分钟） | 派维修任务；恢复心跳自动销警 | `offline_heartbeatRecovered` |
| 清运后未清洁 | 清运车离场后 15 分钟未清洁确认 | 补派保洁；环卫清洁确认后销警 | `notCleanedAfterCollection_...` |
| 积分未到账 | 断网 `deferPoints=true` 暂挂超时 | 产生整改任务，物业核查补发积分与流水 | `pointsUnsettled_reconciled` |
| 多告警合并 | 满溢+异味+错投同时出现 | **只形成一张联动单**，任务同单去重，全部处置后销单 | `odorAndMissort_...` / `allRolesShareSameRoomStateAndCase` |
| 分类质量/投诉趋势 | 街道端查询 | 按小区正确率、错投量、投诉量、按天趋势 | `streetQualityByCommunity` |
| Kafka 事件链路 | 设备事件发布到 `device-events` | 消费者异步消费后刷新状态并联动 | `KafkaLinkageSmokeTest` |

### 手动调用示例

```bash
# 1) 厨余桶满溢 + 异味（同一垃圾房 -> 合并为一张联动单）
curl -X POST localhost:8080/api/device/events -H 'Content-Type: application/json' \
  -d '{"eventType":"KITCHEN_FULL","roomCode":"ROOM1","binType":"KITCHEN","capacityPct":95}'
curl -X POST localhost:8080/api/device/events -H 'Content-Type: application/json' \
  -d '{"eventType":"ODOR","roomCode":"ROOM1","odorValue":88}'

# 2) 环卫看清运优先级
curl localhost:8080/api/sanitation/truck-priorities

# 2.1) 满溢清运超时升级（可把 occurredAt 回溯为 31 分钟前，立即触发封桶+街道督办）
curl -X POST localhost:8080/api/device/events -H 'Content-Type: application/json' \
  -d '{"eventType":"KITCHEN_FULL","roomCode":"ROOM1","binType":"KITCHEN","capacityPct":96}'
curl -X POST localhost:8080/api/sanitation/escalation-check   # 正常由定时任务自动巡检，这里手动触发

# 2.2) 居民端看封桶状态、预计恢复时间、最近备用投放点
curl localhost:8080/api/resident/rooms/ROOM1/disposal-guide

# 2.3) 封桶期间继续在该点投放 -> 不扣分，记录现场管理问题
curl -X POST localhost:8080/api/resident/disposals -H 'Content-Type: application/json' \
  -d '{"cardNo":"CARD1001","roomCode":"ROOM1","binType":"KITCHEN","weightKg":0.6}'
curl 'localhost:8080/api/property/site-issues?openOnly=true'

# 2.4) 居民投诉清运延迟；街道看超时事件并回填清运延迟原因
curl -X POST localhost:8080/api/resident/complaints -H 'Content-Type: application/json' \
  -d '{"contact":"13800000001","roomCode":"ROOM1","type":"COLLECTION_DELAY","content":"封桶了还没车清运"}'
curl 'localhost:8080/api/street/collection-overdue'
curl -X POST localhost:8080/api/street/cases/CASE-ROOM1-xxxx/delay-reason -H 'Content-Type: application/json' \
  -d '{"reason":"早高峰交通管制，已改派备用车"}'

# 3) 居民错投（投厨余桶，摄像头识别为其他垃圾）
curl -X POST localhost:8080/api/resident/disposals -H 'Content-Type: application/json' \
  -d '{"cardNo":"CARD1001","roomCode":"ROOM1","binType":"KITCHEN","detectedType":"OTHER","weightKg":0.8,"confidence":0.93}'

# 4) 物业看待处理任务并完成消杀
curl localhost:8080/api/property/tasks/pending
curl -X POST localhost:8080/api/property/tasks/1/complete -H 'Content-Type: application/json' \
  -d '{"note":"已消杀冲洗"}'

# 5) 清运车到场/离场 + 环卫清洁确认
curl -X POST localhost:8080/api/device/events -H 'Content-Type: application/json' \
  -d '{"eventType":"TRUCK_ARRIVAL","roomCode":"ROOM1","truckPresent":true}'
curl -X POST localhost:8080/api/device/events -H 'Content-Type: application/json' \
  -d '{"eventType":"TRUCK_ARRIVAL","roomCode":"ROOM1","truckPresent":false}'
curl -X POST localhost:8080/api/sanitation/rooms/ROOM1/clean-confirm

# 6) 四端共享的垃圾房联动视图
curl localhost:8080/api/rooms/ROOM1
```

## API 分组（Swagger UI 可在线试用）

- **设备端** `POST /api/device/events`：9 类设备事件统一上报（启用 Kafka 时异步，投递失败自动降级同步）
- **居民端** `/api/resident/*`：投放、积分与流水、违规提示、误判申诉、异味/清运延迟投诉、`rooms/{roomCode}/disposal-guide` 封桶状态+预计恢复时间+最近备用点
- **物业端** `/api/property/*`：待处理任务、派发/完成、联动单查看、投诉整改反馈、积分核查补发、临时封桶任务、现场管理问题处置
- **环卫端** `/api/sanitation/*`：清运优先级（街道督办120 > 已封桶调线110 > 满溢未到场100）、清运后清洁确认、`escalation-check` 超时巡检
- **街道端** `/api/street/*`：小区分类质量与投诉趋势、`collection-overdue` 清运超时督办事件（延迟原因/投诉数/现场问题数）、回填清运延迟原因
- **垃圾房统一状态** `/api/rooms/{code}`：实时状态 + 活跃告警 + 治理任务（四端共用）

## 联动规则（核心实现说明）

1. **告警不直接派单**：`LinkageEngine.raiseAlert` 先按 `roomCode` 找当前活跃联动单（`GarbageRoom.activeCaseNo`），没有才建单；同类型未闭环告警按 `dedupeKey` 去重。
2. **任务由告警类型派生且同单去重**：满溢→环卫清运（100）、其他垃圾满→清运（80）、异味/投诉→消杀（70）+保洁（60）、断网→维修（75）、错投/误判→复核（55）、夜间高峰→夜间保洁（50）、未清洁→补保洁（85）、积分未到账→整改（45）。
3. **闭环销单**：告警解除时其关联任务自动完成；活跃告警与未完成任务全部清零，联动单才闭环并释放垃圾房状态。全误判单记 `CLOSED_FALSE`，存在真实处置的单记 `RESOLVED`。
4. **账实一致**：每次积分变动写 `points_ledger`（含 `disposalId`）；申诉退还、核查补发同样留痕；投诉、整改均回链联动单。
5. **定时巡检**（`LinkageWatchdog`）：断网心跳、清运后未清洁、积分超时未到账、厨余满溢清运超时升级四类被动场景自动补告警。
6. **满溢清运超时升级**（`CollectionEscalationService`）：厨余满溢持续 `bin-seal-minutes`（默认10分钟）车未到 → 物业临时封桶（`BIN_SEAL` 任务+预计恢复时间公示）并先通知物业、再通知环卫调整路线，居民端 `disposal-guide` 展示最近备用点（同小区→同街道，按坐标距离排序）；满溢累计 `bin-seal-minutes + street-overdue-minutes`（默认30分钟）车仍未到 → `COLLECTION_OVERDUE` 升级街道督办，联动单记录**清运延迟原因**与**居民投诉数量**（`COLLECTION_DELAY` 投诉并入同单累计）。车到场/离场或容量回落自动解封销警。
7. **封桶点投放不扣分**：封桶期间居民在满溢点投厨余，投放真实留痕但不计错投、不扣分，摄像头记录进入 `site_issue` 现场管理问题并派 `SITE_DISCIPLINE` 督导任务（同单去重）；物业可单独处置，清运离场时未处置问题自动收口。
8. **阈值可配**（`app.linkage.*`，见 `application.yml`）：满溢 90%、异味 70、夜间 20:00–06:00/3 次、断网 10 分钟、未清洁 15 分钟、10 分/kg、错投扣 5 分、封桶 10 分钟、街道超时 20 分钟、预计恢复 40 分钟。

## 已执行的验证结论

- `mvn test`：**12 个测试全部通过**（11 个联动全链路场景，含满溢清运超时升级 + 1 个内嵌 Kafka 异步消费冒烟）。
- 本地 `local` profile 实际启动成功，`/actuator/health=UP`，Swagger UI 正常跳转（302→index.html），OpenAPI 共 30 个路径。
- `scripts/verify.sh` 真实 HTTP 演练确认：满溢回溯 31 分钟触发**物业封桶+环卫调线+居民引导**，环卫优先级升为街道督办档（120+时长加分）；居民端引导显示封桶状态、预计恢复时间与最近备用点（ROOM1 518m 最近）；封桶点投放居民积分 100→100 **不扣分**，生成 1 起现场管理问题；清运延迟投诉后街道事件显示投诉数=1、现场问题=1、延迟原因已回填；清运车到场/离场后封桶解除、现场问题自动收口、联动单闭环（activeCaseNo 为空）。
- 真实 HTTP 演练确认：满溢+异味+错投合并为同一联动单；错投扣分 100→95，申诉成立退还→100；消杀、清运到场/离场、清洁确认后联动单闭环、物业待办清零；环卫优先级 ROOM1(100) 排首位；投诉整改后状态 RESOLVED。
- Docker Compose 配置已提供（本工作环境无 Docker 守护进程，未在此机器实际执行镜像构建；容器内构建逻辑与本地 Maven 构建一致）。
