package com.city.garbage;

import com.city.garbage.domain.*;
import com.city.garbage.repo.*;
import com.city.garbage.support.TimeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 联动全链路集成测试（H2 + 同步事件总线，不依赖外部 Kafka/PG）。
 * 覆盖：满溢未到场、异味+错投合并单、夜间高峰、断网、异味投诉、
 * 清运后未清洁、积分未到账、摄像头误判申诉，以及四端围绕同一状态联动闭环。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:linkage-flow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class LinkageFlowIntegrationTest {

    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper om;
    @Autowired TimeService timeService;
    @Autowired GarbageRoomRepository roomRepository;
    @Autowired AlertRepository alertRepository;
    @Autowired WorkTaskRepository taskRepository;
    @Autowired GovernanceCaseRepository caseRepository;
    @Autowired TruckVisitRepository visitRepository;
    @Autowired DisposalRepository disposalRepository;
    @Autowired ResidentRepository residentRepository;
    @Autowired ComplaintRepository complaintRepository;
    @Autowired com.city.garbage.service.LinkageWatchdog watchdog;
    @Autowired com.city.garbage.service.SanitationService sanitationService;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @AfterEach
    void resetClock() {
        timeService.setClock(Clock.system(ZONE));
    }

    @BeforeEach
    void fixedDaytimeClock() {
        // 固定北京时间 12:00，避免夜间高峰判定受真实运行时段影响（夜间场景单独改时钟）
        timeService.setClock(Clock.fixed(Instant.parse("2026-10-05T04:00:00Z"), ZONE));
        // 跨测试复用上下文：统一刷新心跳，避免无关断网告警；断网场景单独回溯心跳
        OffsetDateTime now = timeService.now();
        roomRepository.findAll().forEach(r -> {
            r.setOnline(true);
            r.setLastHeartbeatAt(now);
            roomRepository.save(r);
        });
    }

    private HttpHeaders json() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private JsonNode post(String url, Object body) {
        ResponseEntity<JsonNode> resp = http.exchange(url, HttpMethod.POST,
                new HttpEntity<>(body, json()), JsonNode.class);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("POST %s -> %s %s", url, resp.getStatusCode(), resp.getBody()).isTrue();
        return resp.getBody();
    }

    private JsonNode get(String url) {
        ResponseEntity<JsonNode> resp = http.getForEntity(url, JsonNode.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).as("GET %s -> %s", url, resp.getStatusCode()).isTrue();
        return resp.getBody();
    }

    private Map<String, Object> event(String type, String room, Map<String, Object> extra) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("eventType", type);
        m.put("roomCode", room);
        m.put("deviceCode", "DEV-" + type);
        m.put("occurredAt", timeService.now().toString());
        if (extra != null) m.putAll(extra);
        return m;
    }

    private long openAlerts(String room, AlertType type) {
        return alertRepository.findByRoomCodeAndStatusIn(room,
                        List.of(AlertStatus.OPEN, AlertStatus.LINKED)).stream()
                .filter(a -> a.getType() == type).count();
    }

    // ---------- 1. 厨余桶满溢未到场 -> 环卫最高优先级 -> 车到场销警 ----------
    @Test @Order(1)
    void kitchenFullNoTruck_createsTopPriority_thenTruckArrivesCloses() {
        post("/api/device/events", event("KITCHEN_FULL", "ROOM1", Map.of("binType", "KITCHEN", "capacityPct", 95)));
        // 再来一次相同满溢，同类型告警必须去重
        post("/api/device/events", event("KITCHEN_FULL", "ROOM1", Map.of("binType", "KITCHEN", "capacityPct", 96)));

        assertThat(openAlerts("ROOM1", AlertType.KITCHEN_FULL_NO_TRUCK)).isEqualTo(1);

        JsonNode priorities = get("/api/sanitation/truck-priorities");
        JsonNode top = priorities.get(0);
        assertThat(top.get("roomCode").asText()).isEqualTo("ROOM1"); // 厨余满溢未到场必须排第一
        int room1Score = top.get("priorityScore").asInt();
        assertThat(room1Score).isEqualTo(100);

        // 物业列表看不到清运任务，环卫列表可以
        JsonNode propertyTasks = get("/api/property/tasks/pending");
        for (JsonNode t : propertyTasks) assertThat(t.get("type").asText()).isNotEqualTo("TRUCK_DISPATCH");
        JsonNode sanitationTasks = get("/api/sanitation/truck-priorities");
        assertThat(sanitationTasks.size()).isGreaterThan(0);

        // 清运车到场 -> 告警解除，清运任务自动完成，联动单闭环
        post("/api/device/events", event("TRUCK_ARRIVAL", "ROOM1", Map.of("truckPresent", true)));
        assertThat(openAlerts("ROOM1", AlertType.KITCHEN_FULL_NO_TRUCK)).isZero();
        GarbageRoom room1 = roomRepository.findByCode("ROOM1").orElseThrow();
        assertThat(room1.getActiveCaseNo()).isNull();
    }

    // ---------- 2. 异味 + 错投合并成一张联动单；消杀/保洁/复核任务联动；申诉成立退分 ----------
    @Test @Order(2)
    void odorAndMissort_mergeOneCase_appealAcceptedRefundsPoints() {
        post("/api/device/events", event("ODOR", "ROOM1", Map.of("odorValue", 88)));

        // 先正确投放 2kg 厨余，得 20 分
        post("/api/resident/disposals", Map.of(
                "cardNo", "CARD1001", "roomCode", "ROOM1", "binType", "KITCHEN",
                "detectedType", "KITCHEN", "weightKg", 2.0, "confidence", 0.99));
        // 再错投：投厨余桶，识别为其他垃圾
        JsonNode missort = post("/api/resident/disposals", Map.of(
                "cardNo", "CARD1001", "roomCode", "ROOM1", "binType", "KITCHEN",
                "detectedType", "OTHER", "weightKg", 0.8, "confidence", 0.93));
        Long disposalId = missort.get("id").asLong();
        assertThat(missort.get("wrongSort").asBoolean()).isTrue();
        assertThat(missort.get("violationMessage").asText()).contains("疑似错投");

        // 初始100 + 正确投放20 = 120；错投扣 5 分 -> 115
        JsonNode profile = get("/api/resident/CARD1001/profile");
        assertThat(profile.get("totalPoints").asInt()).isEqualTo(115);

        // 异味、错投合并为同一张联动单，垃圾房状态上只有一个活跃单号
        JsonNode roomView = get("/api/rooms/ROOM1");
        String caseNo = roomView.get("room").get("activeCaseNo").asText();
        assertThat(caseNo).startsWith("CASE-ROOM1-");
        JsonNode alerts = roomView.get("activeAlerts");
        assertThat(alerts).hasSize(2);
        // 物业待办：消杀、保洁（异味）、错投复核 = 3
        List<WorkTask> pending = taskRepository.findByCaseNo(caseNo).stream()
                .filter(t -> t.getStatus() != TaskStatus.DONE).toList();
        assertThat(pending).extracting(WorkTask::getType)
                .containsExactlyInAnyOrder(TaskType.DISINFECTION, TaskType.CLEANING, TaskType.RECHECK_SORTING);

        // 完成消杀 -> 异味告警解除，消杀/保洁任务完成，剩错投复核
        Long disinfectionTaskId = taskRepository.findByCaseNo(caseNo).stream()
                .filter(t -> t.getType() == TaskType.DISINFECTION).findFirst().orElseThrow().getId();
        post("/api/property/tasks/" + disinfectionTaskId + "/complete", Map.of("note", "已消杀并冲洗"));
        assertThat(openAlerts("ROOM1", AlertType.ODOR)).isZero();

        // 居民申诉摄像头误判 -> 复核成立：撤销违规、退还 5 分
        post("/api/resident/disposals/" + disposalId + "/appeal", Map.of("reason", "投的是剩菜，摄像头误判"));
        JsonNode reviewed = post("/api/resident/disposals/" + disposalId + "/appeal-review",
                Map.of("accepted", true, "reviewNote", "现场录像复核为厨余，误判"));
        assertThat(reviewed.get("status").asText()).isEqualTo("APPEAL_ACCEPTED");
        assertThat(get("/api/resident/CARD1001/profile").get("totalPoints").asInt()).isEqualTo(120);

        // 所有告警忽略/解除，联动单以误判关闭，垃圾房释放
        assertThat(openAlerts("ROOM1", AlertType.MISSORT)).isZero();
        assertThat(openAlerts("ROOM1", AlertType.CAMERA_MISJUDGE)).isZero();
        // 异味为真实告警并已消杀解除（RESOLVED），错投为误判被忽略（IGNORED），联动单整体 RESOLVED
        assertThat(roomRepository.findByCode("ROOM1").orElseThrow().getActiveCaseNo()).isNull();
        GovernanceCase gc = caseRepository.findByCaseNo(caseNo).orElseThrow();
        assertThat(gc.getStatus()).isEqualTo(CaseStatus.RESOLVED);
        // 积分流水与投放记录一一对应，且出现退还流水
        JsonNode ledger = get("/api/resident/CARD1001/points-ledger");
        assertThat(ledger.toString()).contains("申诉成立退还");
    }

    // ---------- 3. 夜间投放高峰 ----------
    @Test @Order(3)
    void nightPeak_triggersCleaningTask() {
        timeService.setClock(Clock.fixed(Instant.parse("2026-10-05T13:30:00Z"), ZONE)); // 北京时间 21:30
        for (int i = 0; i < 3; i++) {
            post("/api/resident/disposals", Map.of(
                    "cardNo", "CARD1002", "roomCode", "ROOM2", "binType", "KITCHEN",
                    "weightKg", 1.0, "confidence", 0.99));
        }
        assertThat(openAlerts("ROOM2", AlertType.NIGHT_PEAK)).isEqualTo(1);
        GarbageRoom room2 = roomRepository.findByCode("ROOM2").orElseThrow();
        assertThat(room2.getActiveCaseNo()).isNotNull();
        List<WorkTask> tasks = taskRepository.findByCaseNo(room2.getActiveCaseNo());
        assertThat(tasks).extracting(WorkTask::getType).contains(TaskType.CLEANING);
    }

    // ---------- 4. 设备断网 -> 维修任务 -> 心跳恢复销警 ----------
    @Test @Order(4)
    void offline_heartbeatRecovered() {
        GarbageRoom room = roomRepository.findByCode("ROOM2").orElseThrow();
        room.setLastHeartbeatAt(timeService.now().minusMinutes(30));
        room.setOnline(true);
        roomRepository.save(room);

        watchdog.scan();
        assertThat(roomRepository.findByCode("ROOM2").orElseThrow().isOnline()).isFalse();
        assertThat(openAlerts("ROOM2", AlertType.DEVICE_OFFLINE)).isEqualTo(1);
        boolean hasRepair = taskRepository.findByCaseNo(roomRepository.findByCode("ROOM2").orElseThrow().getActiveCaseNo())
                .stream().anyMatch(t -> t.getType() == TaskType.REPAIR && t.getStatus() != TaskStatus.DONE);
        assertThat(hasRepair).isTrue();

        post("/api/device/events", event("HEARTBEAT", "ROOM2", Map.of()));
        assertThat(openAlerts("ROOM2", AlertType.DEVICE_OFFLINE)).isZero();
        assertThat(roomRepository.findByCode("ROOM2").orElseThrow().isOnline()).isTrue();
        // 夜间高峰仍未处理，联动单仍应存在
        assertThat(roomRepository.findByCode("ROOM2").orElseThrow().getActiveCaseNo()).isNotNull();
    }

    // ---------- 5. 清运后未清洁 -> 补保洁 -> 清洁确认闭环 ----------
    @Test @Order(5)
    void notCleanedAfterCollection_thenConfirmed() {
        // 车到场再离场（离场时间回溯 20 分钟）
        post("/api/device/events", event("TRUCK_ARRIVAL", "ROOM3", Map.of("truckPresent", true)));
        post("/api/device/events", event("TRUCK_ARRIVAL", "ROOM3", Map.of("truckPresent", false)));
        TruckVisit visit = visitRepository.findFirstByRoomCodeAndStatusInOrderByArrivedAtDesc(
                        "ROOM3", List.of(TruckVisitStatus.DEPARTED)).orElseThrow();
        visit.setDepartedAt(timeService.now().minusMinutes(20));
        visitRepository.save(visit);

        int raised = sanitationService.checkNotCleaned(15);
        assertThat(raised).isEqualTo(1);
        assertThat(openAlerts("ROOM3", AlertType.NOT_CLEANED_AFTER_COLLECTION)).isEqualTo(1);
        String caseNo = roomRepository.findByCode("ROOM3").orElseThrow().getActiveCaseNo();
        assertThat(taskRepository.findByCaseNo(caseNo)).extracting(WorkTask::getType)
                .contains(TaskType.CLEANING);

        JsonNode confirmed = post("/api/sanitation/rooms/ROOM3/clean-confirm?note=已冲洗地面", Map.of());
        // TestRestTemplate 对无 body 的 POST 传 Map.of 作为请求体
        assertThat(visitRepository.findById(visit.getId()).orElseThrow().getStatus())
                .isEqualTo(TruckVisitStatus.CONFIRMED);
        assertThat(openAlerts("ROOM3", AlertType.NOT_CLEANED_AFTER_COLLECTION)).isZero();
    }

    // ---------- 6. 积分未到账（断网暂挂）-> 告警 -> 核查补发 -> 账实一致 ----------
    @Test @Order(6)
    void pointsUnsettled_reconciled() {
        JsonNode r = post("/api/resident/disposals", Map.of(
                "cardNo", "CARD1002", "roomCode", "ROOM2", "binType", "OTHER",
                "weightKg", 3.0, "deferPoints", true));
        Long disposalId = r.get("id").asLong();
        assertThat(r.get("status").asText()).isEqualTo("PENDING_POINTS");

        DisposalRecord rec = disposalRepository.findById(disposalId).orElseThrow();
        rec.setCreatedAt(timeService.now().minusSeconds(120));
        disposalRepository.save(rec);

        watchdog.scan();
        assertThat(openAlerts("ROOM2", AlertType.POINTS_UNSETTLED)).isEqualTo(1);
        int before = residentRepository.findByCardNo("CARD1002").orElseThrow().getTotalPoints();

        JsonNode result = post("/api/property/points/reconcile", Map.of());
        assertThat(result.get("settledCount").asInt()).isGreaterThanOrEqualTo(1);

        DisposalRecord after = disposalRepository.findById(disposalId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(DisposalStatus.SETTLED);
        assertThat(after.getPointsChange()).isEqualTo(30); // 3kg * 10
        assertThat(residentRepository.findByCardNo("CARD1002").orElseThrow().getTotalPoints())
                .isEqualTo(before + 30);
        assertThat(openAlerts("ROOM2", AlertType.POINTS_UNSETTLED)).isZero();
        // 每笔补发都有流水，且与该投放记录对应
        boolean ledgerMatched = false;
        JsonNode ledger = get("/api/resident/CARD1002/points-ledger");
        for (JsonNode l : ledger) {
            if (disposalId.equals(l.get("disposalId") == null ? null : l.get("disposalId").asLong())
                    && l.get("reason").asText().contains("核查补发")) {
                ledgerMatched = true;
            }
        }
        assertThat(ledgerMatched).isTrue();
    }

    // ---------- 7. 异味投诉并入联动单，整改反馈后闭环 ----------
    @Test @Order(7)
    void odorComplaint_linkedAndReplied() {
        JsonNode c = post("/api/resident/complaints", Map.of(
                "contact", "13800000000", "roomCode", "ROOM1", "type", "ODOR",
                "content", "1号垃圾房臭味很大，影响开窗"));
        Long complaintId = c.get("id").asLong();
        assertThat(openAlerts("ROOM1", AlertType.ODOR_COMPLAINT)).isEqualTo(1);
        String caseNo = roomRepository.findByCode("ROOM1").orElseThrow().getActiveCaseNo();
        assertThat(caseNo).isNotNull();

        JsonNode replied = post("/api/property/complaints/" + complaintId + "/reply",
                Map.of("reply", "已安排消杀清洗，异味消除，请复查"));
        assertThat(replied.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(openAlerts("ROOM1", AlertType.ODOR_COMPLAINT)).isZero();
        assertThat(complaintRepository.findById(complaintId).orElseThrow().getStatus())
                .isEqualTo(ComplaintStatus.RESOLVED);
    }

    // ---------- 8. 环卫优先级：满溢未到场 > 其他垃圾满溢 ----------
    @Test @Order(8)
    void sanitationPriorityOrder() {
        post("/api/device/events", event("KITCHEN_FULL", "ROOM2", Map.of("binType", "KITCHEN", "capacityPct", 98)));
        post("/api/device/events", event("OTHER_CAPACITY", "ROOM3", Map.of("binType", "OTHER", "capacityPct", 95)));
        JsonNode priorities = get("/api/sanitation/truck-priorities");
        String firstRoom = priorities.get(0).get("roomCode").asText();
        // ROOM2 厨余满溢且车未到必须排在 ROOM3（其他垃圾满溢）前面
        int idx2 = -1, idx3 = -1;
        for (int i = 0; i < priorities.size(); i++) {
            String code = priorities.get(i).get("roomCode").asText();
            if ("ROOM2".equals(code)) idx2 = i;
            if ("ROOM3".equals(code)) idx3 = i;
        }
        assertThat(idx2).isGreaterThanOrEqualTo(0);
        assertThat(idx3).isGreaterThan(idx2);
        assertThat(firstRoom).isEqualTo("ROOM2");
    }

    // ---------- 9. 街道端分类质量与投诉趋势 ----------
    @Test @Order(9)
    void streetQualityByCommunity() {
        // C002（翠柳小区/ROOM3）此前无投放，这里投 1 对 1 错 => 正确率 50%
        post("/api/resident/disposals", Map.of(
                "cardNo", "CARD1003", "roomCode", "ROOM3", "binType", "OTHER",
                "weightKg", 1.5, "confidence", 0.99));
        post("/api/resident/disposals", Map.of(
                "cardNo", "CARD1003", "roomCode", "ROOM3", "binType", "KITCHEN",
                "detectedType", "OTHER", "weightKg", 0.5, "confidence", 0.9));

        JsonNode q = get("/api/street/communities/C002/quality?days=7");
        assertThat(q.get("communityName").asText()).isEqualTo("翠柳小区");
        assertThat(q.get("totalDisposals").asInt()).isEqualTo(2);
        assertThat(q.get("wrongSortCount").asInt()).isEqualTo(1);
        assertThat(q.get("correctRatePct").asDouble()).isEqualTo(50.0);
        assertThat(q.get("totalWeightKg").asDouble()).isEqualTo(2.0);
        assertThat(q.get("dailyTrend").isArray()).isTrue();
        JsonNode overview = get("/api/street/overview?days=7");
        assertThat(overview.size()).isEqualTo(2);
    }

    // ---------- 10. 四端共享同一垃圾房状态与同一联动单号 ----------
    @Test @Order(10)
    void allRolesShareSameRoomStateAndCase() {
        // ROOM2 当前已被厨余满溢（测试8）与夜间高峰（测试3）等占用同一活跃单
        GarbageRoom room2 = roomRepository.findByCode("ROOM2").orElseThrow();
        String roomCase = room2.getActiveCaseNo();
        assertThat(roomCase).isNotNull();

        JsonNode roomView = get("/api/rooms/ROOM2");
        assertThat(roomView.get("room").get("activeCaseNo").asText()).isEqualTo(roomCase);
        for (JsonNode a : roomView.get("activeAlerts")) {
            assertThat(a.get("caseNo").asText()).isEqualTo(roomCase);
        }
        for (JsonNode t : roomView.get("tasks")) {
            assertThat(t.get("caseNo").asText()).isEqualTo(roomCase);
        }
        // 环卫优先级列表指向同一活跃单号
        JsonNode priorities = get("/api/sanitation/truck-priorities");
        boolean sameCase = false;
        for (JsonNode p : priorities) {
            if ("ROOM2".equals(p.get("roomCode").asText())) {
                sameCase = roomCase.equals(p.get("activeCaseNo").asText());
            }
        }
        assertThat(sameCase).isTrue();
    }
}
