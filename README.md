# 城市智能垃圾房满溢异味投放违规联动服务

面向物业、环卫、街道、居民四类角色的垃圾房现场治理服务。垃圾房设备上报**厨余桶满溢、其他垃圾容量、异味、门禁、摄像头识别、称重、消杀、清运车到场、心跳**数据；居民刷卡/扫码投放时记录**小区、楼栋、垃圾类型、重量、积分与违规提示**。

核心设计目标：**让设备告警真正变成现场治理动作**。满溢、异味、错投往往同时出现，系统不会把每个告警单独派单，而是把同一垃圾房的未闭环告警**合并成一张“治理联动单”**，在单上派生保洁/消杀/清运/维修/复核/整改任务；积分、投诉、整改结果都与真实投放、清运记录一一对应，四端围绕同一份垃圾房状态更新。

## 技术栈

- Java 17、Spring Boot 3.3、Spring Data JPA、Bean Validation、Actuator
- PostgreSQL 16（生产）、H2（本地/测试，免外部依赖）
- Kafka 3.7（KRaft，无 Zookeeper；设备事件 `device-events`、联动事件 `governance-events`、误判样本回传 `model-feedback`）
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
# 运行全部测试（错投申诉复核 3 个专项场景 + 10 个联动场景 + 1 个内嵌 Kafka 冒烟）
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
| 错投申诉复核（误判成立） | 居民对“玻璃瓶投厨余桶”等判罚申诉 | 服务调取**视频片段/重量变化/桶位/居民说明**；误判成立则**恢复积分**、**样本回传识别模型**、错投告警忽略销单 | `evidenceCollected_misjudge...` / `odorAndMissort_...` |
| 错投申诉复核（违规成立） | 复核认定确为玻璃瓶投厨余桶 | 维持处罚，向居民派发**分类教育任务**，完成学习答题后更新教育记录并闭环复核单 | `violationConfirmed_assignsEducation...` |
| 摄像头误判率升高 | 同一摄像头错投复核误判率 ≥50%（≥2 样本） | 设备进入**摄像头校准任务**，期间该摄像头**错投处罚暂停自动生效**；校准完成清零窗口、恢复执法 | `misjudgeRateHigh_calibrationSuspendsPenalty...` |
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
  -d '{"cardNo":"CARD1001","roomCode":"ROOM1","binType":"KITCHEN","detectedType":"OTHER","weightKg":0.8,"confidence":0.93,"cameraCode":"CAM-ROOM1","binPosition":"厨余垃圾-1号位"}'

# 3b) 居民申诉 -> 服务自动调取视频/重量/桶位证据 -> 复核误判成立（积分恢复+样本回传）
curl -X POST localhost:8080/api/resident/disposals/1/appeal -H 'Content-Type: application/json' \
  -d '{"reason":"我投的是剩菜，不是玻璃瓶"}'
curl -X POST localhost:8080/api/resident/disposals/1/appeal-review -H 'Content-Type: application/json' \
  -d '{"accepted":true,"reviewNote":"录像为剩菜，识别误判","reviewer":"张复核"}'
# 复核违规成立则派分类教育任务；居民完成后复核单闭环
curl -X POST localhost:8080/api/resident/education/1/complete -H 'Content-Type: application/json' \
  -d '{"resultNote":"完成5道答题，全部正确"}'

# 3c) 摄像头误判率升高 -> 校准；查看校准中摄像头与全部摄像头质量；完成校准恢复处罚
curl localhost:8080/api/property/cameras
curl localhost:8080/api/property/cameras/calibrating
curl -X POST localhost:8080/api/property/cameras/CAM-ROOM1/calibration/complete \
  -H 'Content-Type: application/json' -d '{"note":"重新标定角度并热更新模型"}'

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
- **居民端** `/api/resident/*`：投放（含摄像头编码/桶位）、积分与流水、违规提示、**错投申诉（自动取证）**、复核结论、**分类教育任务**、异味投诉
- **物业端** `/api/property/*`：待处理任务、派发/完成、联动单查看、投诉整改反馈、积分核查补发、**申诉复核队列/复核结论**、**摄像头识别质量与校准**、误判样本重传
- **环卫端** `/api/sanitation/*`：清运优先级、清运后清洁确认
- **街道端** `/api/street/*`：小区分类质量（正确率/错投/重量）与投诉按天趋势、全街道对比
- **垃圾房统一状态** `/api/rooms/{code}`：实时状态 + 活跃告警 + 治理任务（四端共用）

## 联动规则（核心实现说明）

1. **告警不直接派单**：`LinkageEngine.raiseAlert` 先按 `roomCode` 找当前活跃联动单（`GarbageRoom.activeCaseNo`），没有才建单；同类型未闭环告警按 `dedupeKey` 去重。
2. **任务由告警类型派生且同单去重**：满溢→环卫清运（100）、其他垃圾满→清运（80）、异味/投诉→消杀（70）+保洁（60）、断网→维修（75）、错投/误判→复核（55）、夜间高峰→夜间保洁（50）、未清洁→补保洁（85）、积分未到账→整改（45）。
3. **闭环销单**：告警解除时其关联任务自动完成；活跃告警与未完成任务全部清零，联动单才闭环并释放垃圾房状态。全误判单记 `CLOSED_FALSE`，存在真实处置的单记 `RESOLVED`。
4. **账实一致**：每次积分变动写 `points_ledger`（含 `disposalId`）；申诉退还、核查补发同样留痕；投诉、整改均回链联动单。
5. **定时巡检**（`LinkageWatchdog`）：断网心跳、清运后未清洁、积分超时未到账三类被动场景自动补告警。
6. **阈值可配**（`app.linkage.*`，见 `application.yml`）：满溢 90%、异味 70、夜间 20:00–06:00/3 次、断网 10 分钟、未清洁 15 分钟、10 分/kg、错投扣 5 分。
7. **错投申诉复核**（`AppealReviewService`）：申诉即生成复核单并自动取证——投放视频片段（取时间窗内 `CAMERA_DETECTION` 事件，缺省按投放时间±15s 生成片段引用）、重量变化（窗口内首/末 `WEIGHT` 地磅读数之差，缺失回退投放称重）、桶位与居民说明。误判成立：撤销违规、恢复扣分、误判样本写入 `model_feedback_sample` 并经 Kafka `model-feedback` 回传识别模型（本地无 Kafka 时模拟回传），错投/误判告警忽略销单；违规成立：维持处罚并写 `education_record` 派分类教育任务，居民完成学习答题后复核单闭环（`CLOSED`）。
8. **摄像头校准联动**（`CameraCalibrationService`）：按摄像头滚动统计错投判定数与复核误判数，误判率（误判数/错投判定数）≥50% 且复核样本≥2 时，产生 `CAMERA_MISJUDGE_RATE_HIGH` 告警并派优先级 90 的 `CAMERA_CALIBRATION` 任务；校准期（`CALIBRATING`）该摄像头错投**扣分暂停自动生效**（记录 `penaltySuspended=true`、不扣分、转人工逐单复核）。校准完成清零统计窗口、解除告警与校准任务、恢复正常执法。

## 已执行的验证结论

- `mvn test`：**14 个测试全部通过**（错投申诉复核 3 个专项场景 + 10 个联动全链路场景 + 1 个内嵌 Kafka 异步消费冒烟）。
- 本地 `local` profile 实际启动成功，`/actuator/health=UP`，Swagger UI 302 跳转为正常，OpenAPI 3.0.1 共 **34 个路径**（新增申诉/证据/教育/摄像头/样本回传 10 个路径）。
- 真实 HTTP 演练确认：
  - 申诉自动取证——视频片段引用、重量 10.0→10.8kg（Δ0.8）、桶位“厨余垃圾-1号位”、居民说明齐备；
  - **误判成立**：积分 95→100 恢复，误判样本 `SENT`（本地模拟回传 `model-feedback`），错投/误判告警销单；
  - **违规成立**：维持处罚，派“厨余垃圾分类：玻璃瓶等不属于厨余”教育任务，完成答题后教育记录 `DONE`、复核单 `CLOSED`；
  - **摄像头误判率升高**：2/2 误判 → 状态 `CALIBRATING`、误判率 1.0、`CAMERA_MISJUDGE_RATE_HIGH` 告警与校准任务生成；校准期错投 `penaltySuspended=true`、不扣分；完成校准后统计清零、状态 `CALIBRATED`，再次错投恢复扣 5 分。
- Docker Compose 配置已提供（本工作环境无 Docker 守护进程，未在此机器实际执行镜像构建；容器内构建逻辑与本地 Maven 构建一致）。
