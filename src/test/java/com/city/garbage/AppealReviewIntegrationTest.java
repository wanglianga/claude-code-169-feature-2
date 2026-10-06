package com.city.garbage;

import com.city.garbage.domain.*;
import com.city.garbage.repo.*;
import com.city.garbage.support.TimeService;
import com.fasterxml.jackson.databind.JsonNode;
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
 * 错投申诉复核专项集成测试（H2 + 同步事件总线）：
 * A. 取证（视频片段/重量变化/桶位/居民说明）+ 误判成立 -> 积分恢复 + 样本回传模型；
 * B. 违规成立 -> 派分类教育任务，完成后更新教育记录并闭环复核单；
 * C. 同一摄像头误判率升高 -> 进入校准、错投处罚暂停自动生效 -> 校准完成恢复执法。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:appeal_review;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class AppealReviewIntegrationTest {

    @Autowired TestRestTemplate http;
    @Autowired TimeService timeService;
    @Autowired GarbageRoomRepository roomRepository;
    @Autowired AlertRepository alertRepository;
    @Autowired WorkTaskRepository taskRepository;
    @Autowired DisposalRepository disposalRepository;
    @Autowired ResidentRepository residentRepository;
    @Autowired CameraDeviceRepository cameraRepository;
    @Autowired ModelFeedbackSampleRepository sampleRepository;
    @Autowired EducationRecordRepository educationRepository;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @BeforeEach
    void fixedClock() {
        timeService.setClock(Clock.fixed(Instant.parse("2026-10-05T05:30:00Z"), ZONE)); // 北京 13:30
        OffsetDateTime now = timeService.now();
        roomRepository.findAll().forEach(r -> {
            r.setOnline(true);
            r.setLastHeartbeatAt(now);
            roomRepository.save(r);
        });
    }

    @AfterEach
    void resetClock() {
        timeService.setClock(Clock.system(ZONE));
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

    private Map<String, Object> deviceEvent(String type, String room, Map<String, Object> extra, OffsetDateTime at) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("eventType", type);
        m.put("roomCode", room);
        m.put("deviceCode", "DEV-" + type);
        m.put("occurredAt", at.toString());
        if (extra != null) m.putAll(extra);
        return m;
    }

    private JsonNode wrongDisposal(String card, String room, String camera) {
        return post("/api/resident/disposals", Map.of(
                "cardNo", card, "roomCode", room, "binType", "KITCHEN",
                "detectedType", "OTHER", "weightKg", 0.8, "confidence", 0.9,
                "cameraCode", camera, "binPosition", "厨余垃圾-2号位"));
    }

    private long openAlerts(String room, AlertType type) {
        return alertRepository.findByRoomCodeAndStatusIn(room,
                        List.of(AlertStatus.OPEN, AlertStatus.LINKED)).stream()
                .filter(a -> a.getType() == type).count();
    }

    // ---------- A. 取证 + 误判成立：积分恢复、样本回传识别模型 ----------
    @Test @Order(1)
    void evidenceCollected_misjudgeConfirmed_refundsAndFeedsModel() {
        String cam = "CAM-TEST-A";
        OffsetDateTime t0 = timeService.now();

        // 投放前地磅读数 10.0kg（10秒前）；摄像头识别事件（5秒前）；投放后读数 10.8kg（10秒后）
        post("/api/device/events", deviceEvent("WEIGHT", "ROOM1",
                Map.of("binType", "KITCHEN", "weightKg", 10.0), t0.minusSeconds(10)));
        post("/api/device/events", deviceEvent("CAMERA_DETECTION", "ROOM1",
                Map.of("deviceCode", cam, "detectedType", "OTHER", "confidence", 0.9), t0.minusSeconds(5)));

        JsonNode missort = wrongDisposal("CARD1001", "ROOM1", cam);
        Long disposalId = missort.get("id").asLong();
        int pointsAfterPenalty = get("/api/resident/CARD1001/profile").get("totalPoints").asInt();

        post("/api/device/events", deviceEvent("WEIGHT", "ROOM1",
                Map.of("binType", "KITCHEN", "weightKg", 10.8), t0.plusSeconds(10)));

        // 申诉：服务自动调取视频片段、重量变化、桶位
        JsonNode appeal = post("/api/resident/disposals/" + disposalId + "/appeal",
                Map.of("reason", "我投的是剩菜，没有玻璃瓶"));
        assertThat(appeal.get("status").asText()).isEqualTo("EVIDENCE_COLLECTED");
        assertThat(appeal.get("cameraCode").asText()).isEqualTo(cam);
        assertThat(appeal.get("videoClip").asText()).startsWith("clips/ROOM1/").endsWith(".mp4");
        assertThat(appeal.get("videoStartAt")).isNotNull();
        assertThat(appeal.get("binPosition").asText()).isEqualTo("厨余垃圾-2号位");
        assertThat(appeal.get("residentStatement").asText()).contains("剩菜");
        assertThat(appeal.get("weightBeforeKg").asDouble()).isEqualTo(10.0);
        assertThat(appeal.get("weightAfterKg").asDouble()).isEqualTo(10.8);
        assertThat(appeal.get("weightDeltaKg").asDouble()).isEqualTo(0.8);

        // 误判成立：积分恢复、样本回传识别模型
        JsonNode reviewed = post("/api/resident/disposals/" + disposalId + "/appeal-review",
                Map.of("accepted", true, "reviewNote", "录像为剩菜，识别误判", "reviewer", "张复核"));
        assertThat(reviewed.get("status").asText()).isEqualTo("MISJUDGE_CONFIRMED");
        Long sampleId = reviewed.get("feedbackSampleId").asLong();
        assertThat(get("/api/resident/CARD1001/profile").get("totalPoints").asInt())
                .isEqualTo(pointsAfterPenalty + 5);

        ModelFeedbackSample sample = sampleRepository.findById(sampleId).orElseThrow();
        assertThat(sample.getStatus()).isEqualTo(FeedbackStatus.SENT); // 本地模式模拟回传成功
        assertThat(sample.getPredictedType()).isEqualTo(WasteType.OTHER);
        assertThat(sample.getActualType()).isEqualTo(WasteType.KITCHEN);
        assertThat(sample.getVideoClip()).endsWith(".mp4");

        // 投放记录已撤销违规，错投/误判告警均已销单
        assertThat(disposalRepository.findById(disposalId).orElseThrow().getStatus())
                .isEqualTo(DisposalStatus.APPEAL_ACCEPTED);
        assertThat(openAlerts("ROOM1", AlertType.MISSORT)).isZero();
        assertThat(openAlerts("ROOM1", AlertType.CAMERA_MISJUDGE)).isZero();

        // 摄像头画像：错投判定 1 次、误判确认 1 次（样本不足 2，暂不校准）
        CameraDevice camera = cameraRepository.findByCameraCode(cam).orElseThrow();
        assertThat(camera.getMissortFlaggedCount()).isEqualTo(1);
        assertThat(camera.getMisjudgeConfirmedCount()).isEqualTo(1);
        assertThat(camera.getCalibrationStatus()).isEqualTo(CameraCalibrationStatus.NORMAL);
    }

    // ---------- B. 违规成立：派分类教育任务，完成后闭环 ----------
    @Test @Order(2)
    void violationConfirmed_assignsEducation_andCompletes() {
        String cam = "CAM-TEST-B";
        JsonNode missort = wrongDisposal("CARD1002", "ROOM2", cam);
        Long disposalId = missort.get("id").asLong();

        post("/api/resident/disposals/" + disposalId + "/appeal",
                Map.of("reason", "玻璃瓶随手扔了"));
        JsonNode reviewed = post("/api/property/appeals/disposals/" + disposalId + "/review",
                Map.of("accepted", false, "reviewNote", "录像清晰显示玻璃瓶投入厨余桶", "reviewer", "李主管"));
        assertThat(reviewed.get("status").asText()).isEqualTo("VIOLATION_CONFIRMED");
        Long eduId = reviewed.get("educationRecordId").asLong();

        // 居民端收到分类教育任务，处罚维持
        EducationRecord edu = educationRepository.findById(eduId).orElseThrow();
        assertThat(edu.getStatus()).isEqualTo(EducationStatus.ASSIGNED);
        assertThat(edu.getTopic()).contains("厨余");
        JsonNode profile = get("/api/resident/CARD1002/profile");
        assertThat(profile.get("educationPendingCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(disposalRepository.findById(disposalId).orElseThrow().getStatus())
                .isEqualTo(DisposalStatus.APPEAL_REJECTED);
        assertThat(openAlerts("ROOM2", AlertType.MISSORT)).isZero();

        // 复核队列里已无此单
        JsonNode pending = get("/api/property/appeals/pending");
        for (JsonNode a : pending) assertThat(a.get("disposalId").asLong()).isNotEqualTo(disposalId);

        // 完成学习+答题 -> 教育记录更新、复核单闭环
        JsonNode done = post("/api/resident/education/" + eduId + "/complete",
                Map.of("resultNote", "已完成5道答题，全部正确"));
        assertThat(done.get("status").asText()).isEqualTo("DONE");
        assertThat(educationRepository.findById(eduId).orElseThrow().getStatus())
                .isEqualTo(EducationStatus.DONE);
        JsonNode appealView = get("/api/resident/disposals/" + disposalId + "/appeal");
        assertThat(appealView.get("status").asText()).isEqualTo("CLOSED");
    }

    // ---------- C. 同摄像头误判率升高 -> 校准 -> 处罚暂停 -> 校准后恢复 ----------
    @Test @Order(3)
    void misjudgeRateHigh_calibrationSuspendsPenalty_thenResumes() {
        String cam = "CAM-TEST-C";
        String room = "ROOM3";

        // 连续 2 次错投均被复核为误判 -> 误判率 2/2
        JsonNode d1 = wrongDisposal("CARD1003", room, cam);
        JsonNode d2 = wrongDisposal("CARD1003", room, cam);
        for (JsonNode d : List.of(d1, d2)) {
            long id = d.get("id").asLong();
            post("/api/resident/disposals/" + id + "/appeal", Map.of("reason", "均为厨余，误判"));
            post("/api/resident/disposals/" + id + "/appeal-review",
                    Map.of("accepted", true, "reviewNote", "录像确认为厨余"));
        }

        CameraDevice camera = cameraRepository.findByCameraCode(cam).orElseThrow();
        assertThat(camera.getCalibrationStatus()).isEqualTo(CameraCalibrationStatus.CALIBRATING);
        assertThat(camera.getLastMisjudgeRate()).isEqualTo(1.0);
        assertThat(openAlerts(room, AlertType.CAMERA_MISJUDGE_RATE_HIGH)).isEqualTo(1);
        String caseNo = roomRepository.findByCode(room).orElseThrow().getActiveCaseNo();
        assertThat(taskRepository.findByCaseNo(caseNo)).extracting(WorkTask::getType)
                .contains(TaskType.CAMERA_CALIBRATION);
        assertThat(get("/api/property/cameras/calibrating").toString()).contains(cam);

        // 校准期间再次“错投”：处罚暂停自动生效（不扣分，标记 penaltySuspended）
        int balanceBefore = get("/api/resident/CARD1003/profile").get("totalPoints").asInt();
        JsonNode d3 = wrongDisposal("CARD1003", room, cam);
        Long id3 = d3.get("id").asLong();
        DisposalRecord r3 = disposalRepository.findById(id3).orElseThrow();
        assertThat(r3.isPenaltySuspended()).isTrue();
        assertThat(r3.getPointsChange()).isZero();
        assertThat(r3.getViolationMessage()).contains("暂停自动生效");
        assertThat(get("/api/resident/CARD1003/profile").get("totalPoints").asInt())
                .isEqualTo(balanceBefore);

        // 完成校准：误判率告警解除、校准任务完成、统计清零、恢复 NORMAL 执法
        JsonNode calibrated = post("/api/property/cameras/" + cam + "/calibration/complete",
                Map.of("note", "已重新标定摄像头角度并更新模型"));
        assertThat(calibrated.get("calibrationStatus").asText()).isEqualTo("CALIBRATED");
        assertThat(openAlerts(room, AlertType.CAMERA_MISJUDGE_RATE_HIGH)).isZero();
        CameraDevice after = cameraRepository.findByCameraCode(cam).orElseThrow();
        assertThat(after.getMissortFlaggedCount()).isZero();
        assertThat(after.getMisjudgeConfirmedCount()).isZero();

        // 校准完成后恢复正常执法：再次错投恢复扣分
        int balanceAfterCalibration = get("/api/resident/CARD1003/profile").get("totalPoints").asInt();
        JsonNode d4 = wrongDisposal("CARD1003", room, cam);
        DisposalRecord r4 = disposalRepository.findById(d4.get("id").asLong()).orElseThrow();
        assertThat(r4.isPenaltySuspended()).isFalse();
        assertThat(r4.getPointsChange()).isEqualTo(-5);
        assertThat(get("/api/resident/CARD1003/profile").get("totalPoints").asInt())
                .isEqualTo(balanceAfterCalibration - 5);
    }
}
