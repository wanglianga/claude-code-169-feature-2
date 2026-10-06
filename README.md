# 城市智能垃圾房满溢异味投放违规联动服务

面向物业、环卫、街道、居民四类角色的垃圾房现场治理服务。垃圾房设备上报**厨余桶满溢、其他垃圾容量、异味、门禁、摄像头识别、称重、消杀、清运车到场、心跳**数据；居民刷卡/扫码投放时记录**小区、楼栋、垃圾类型、重量、积分与违规提示**。

核心设计目标：**让设备告警真正变成现场治理动作**。满溢、异味、错投往往同时出现，系统不会把每个告警单独派单，而是把同一垃圾房的未闭环告警**合并成一张“治理联动单”**，在单上派生保洁/消杀/清运/维修/复核/整改任务；积分、投诉、整改结果都与真实投放、清运记录一一对应，四端围绕同一份垃圾房状态更新。

**错投申诉复核**：居民被识别错投（如玻璃瓶投入厨余桶）后可申诉，服务自动调取**投放视频片段、称重变化、桶位、居民说明**组成证据包；复核**误判成立**则积分恢复并把样本回传识别模型，复核**违规成立**则居民收到分类教育任务；复核完成后同步更新居民积分与教育记录。同一摄像头误判率升高时设备自动进入**校准任务**，校准期间该摄像头的错投处罚暂停自动生效，校准完成后恢复。

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
# 运行全部测试（10 个联动场景 + 1 个错投申诉复核全链路 + 1 个内嵌 Kafka 冒烟）
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
| 居民错投被识别 | 投放时摄像头 `detectedType` ≠ 投放桶 | 记录违规提示、扣分、派现场复核任务 | `odorAndMissort_...` |
| 错投申诉复核-误判成立 | 居民申诉（自动调取视频片段/称重/桶位/居民说明证据包）+ 物业复核成立 | 撤销违规、积分恢复、**样本回传识别模型**、错投告警忽略销单 | `appealReview_fullLinkage` |
| 错投申诉复核-违规成立 | 物业复核驳回申诉 | 维持扣分、**居民收到分类教育任务**并完成打卡、错投告警闭环 | 同上 |
| 摄像头误判率升高 | 同一摄像头复核误判率 ≥ 阈值（默认样本≥2 且误判率≥50%） | 设备进入**校准任务**，校准期间该摄像头**错投处罚暂停自动生效**；校准完成恢复 | 同上 |
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

# 3) 居民错投（投厨余桶，摄像头识别为其他垃圾）
curl -X POST localhost:8080/api/resident/disposals -H 'Content-Type: application/json' \
  -d '{"cardNo":"CARD1001","roomCode":"ROOM1","binType":"KITCHEN","detectedType":"OTHER","weightKg":0.8,"confidence":0.93}'

# 3.1) 错投申诉复核：玻璃瓶投入厨余桶 -> 申诉（自动调取证据包）
curl -X POST localhost:8080/api/resident/disposals -H 'Content-Type: application/json' \
  -d '{"cardNo":"CARD1001","roomCode":"ROOM1","binType":"KITCHEN","detectedType":"RECYCLABLE","weightKg":0.6,"confidence":0.95}'
curl -X POST localhost:8080/api/resident/disposals/2/appeal -H 'Content-Type: application/json' \
  -d '{"reason":"我投的是剩菜剩饭，不是玻璃瓶"}'
# 物业查看待复核申诉（含视频片段/称重变化/桶位/居民说明）
curl localhost:8080/api/property/appeals/pending
# 复核误判成立：积分恢复 + 样本回传识别模型；同一摄像头误判率升高则进入校准
curl -X POST localhost:8080/api/property/appeals/1/review -H 'Content-Type: application/json' \
  -d '{"accepted":true,"reviewNote":"视频复核为厨余垃圾，摄像头误判"}'
curl localhost:8080/api/property/model-feedback   # 已回传识别模型的样本
curl localhost:8080/api/property/cameras          # 摄像头误判率与校准状态
# 复核违规成立：居民收到分类教育任务
curl -X POST localhost:8080/api/property/appeals/2/review -H 'Content-Type: application/json' \
  -d '{"accepted":false,"reviewNote":"视频确认违规"}'
curl localhost:8080/api/resident/CARD1001/education
curl -X POST localhost:8080/api/resident/CARD1001/education/1/complete

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
- **居民端** `/api/resident/*`：投放、积分与流水、违规提示、错投申诉（证据包）、我的申诉、分类教育任务与打卡、异味投诉
- **物业端** `/api/property/*`：待处理任务、派发/完成、联动单查看、投诉整改反馈、积分核查补发、**申诉复核（证据包/结论）、摄像头误判统计与校准状态、模型回传样本**
- **环卫端** `/api/sanitation/*`：清运优先级、清运后清洁确认
- **街道端** `/api/street/*`：小区分类质量（正确率/错投/重量）与投诉按天趋势、全街道对比
- **垃圾房统一状态** `/api/rooms/{code}`：实时状态 + 活跃告警 + 治理任务（四端共用）

## 联动规则（核心实现说明）

1. **告警不直接派单**：`LinkageEngine.raiseAlert` 先按 `roomCode` 找当前活跃联动单（`GarbageRoom.activeCaseNo`），没有才建单；同类型未闭环告警按 `dedupeKey` 去重。
2. **任务由告警类型派生且同单去重**：满溢→环卫清运（100）、其他垃圾满→清运（80）、异味/投诉→消杀（70）+保洁（60）、断网→维修（75）、错投/误判→复核（55）、摄像头误判率升高→校准（65）、夜间高峰→夜间保洁（50）、未清洁→补保洁（85）、积分未到账→整改（45）。
3. **闭环销单**：告警解除时其关联任务自动完成；活跃告警与未完成任务全部清零，联动单才闭环并释放垃圾房状态。全误判单记 `CLOSED_FALSE`，存在真实处置的单记 `RESOLVED`。
4. **账实一致**：每次积分变动写 `points_ledger`（含 `disposalId`）；申诉退还、核查补发同样留痕；投诉、整改均回链联动单。
5. **定时巡检**（`LinkageWatchdog`）：断网心跳、清运后未清洁、积分超时未到账三类被动场景自动补告警。
6. **申诉复核四联动**（`AppealReviewService`）：申诉即建复核单并调取证据包（视频片段 `/media/clips/{room}/{disposal}.mp4`、称重变化、桶位、居民说明）；误判成立→积分恢复（写流水）+ 误判样本回传识别模型（`model_feedback_sample`）+ 错投告警忽略销单；违规成立→下达分类教育任务（`education_task`，居民打卡完成）+ 错投告警按真实处置闭环。
7. **摄像头误判率熔断**（`CameraStatService`）：每次复核结论累计该摄像头复核样本与误判数，误判率达阈值自动产生校准告警并派生校准任务；校准期间该摄像头错投只记录不扣分（`penaltySuspended=true`），校准任务完成后统计清零、处罚恢复。
8. **阈值可配**（`app.linkage.*`，见 `application.yml`）：满溢 90%、异味 70、夜间 20:00–06:00/3 次、断网 10 分钟、未清洁 15 分钟、10 分/kg、错投扣 5 分、摄像头误判评估最小样本 2 次、误判率阈值 50%。

## 已执行的验证结论

- `mvn package`：**12 个测试全部通过**（10 个联动全链路场景 + 1 个错投申诉复核全链路 + 1 个内嵌 Kafka 异步消费冒烟；三个测试类各自独立 H2 库，互不影响）。
- 本地 `local` profile 实际启动成功，`/actuator/health=UP`，Swagger UI 可访问，OpenAPI 3.0.1 共 32 个路径。
- 真实 HTTP 演练确认：满溢+异味+错投合并为同一联动单；错投扣分 100→95，申诉成立退还→100；消杀、清运到场/离场、清洁确认后联动单闭环、物业待办清零；环卫优先级 ROOM1(100) 排首位；投诉整改后状态 RESOLVED。
- 错投申诉复核演练确认（`scripts/verify.sh` 实际输出）：玻璃瓶投厨余桶申诉后证据包含视频片段 `/media/clips/ROOM1/2.mp4`、桶位“阳光花园南门 · 厨余垃圾桶位”、称重 0.6kg、居民说明；误判成立积分恢复 100 且样本回传（RECYCLABLE→KITCHEN，SENT）；同一摄像头误判率 2/2=100% 触发校准任务，校准期错投 `penaltySuspended=true` 未扣分；校准完成后错投恢复扣分 95；违规成立后居民收到《垃圾分类教育：厨余垃圾正确投放》任务并打卡完成。
- Docker Compose 配置已提供（本工作环境无 Docker 守护进程，未在此机器实际执行镜像构建；容器内构建逻辑与本地 Maven 构建一致）。
