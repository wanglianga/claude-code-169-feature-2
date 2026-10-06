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
 * 错投申诉复核全链路集成测试（H2 + 同步事件总线）。
 * 场景：居民被识别为“玻璃瓶投入厨余桶”后申诉 ->
 * 服务调取证据包（视频片段、称重变化、桶位、居民说明）->
 * 误判成立：积分恢复 + 样本回传识别模型；
 * 同一摄像头误判率升高：设备进入校准任务，错投处罚暂停自动生效；
 * 校准完成：处罚恢复；违规成立：居民收到分类教育任务并完成打卡。
 * 使用独立居民 CARD2001，不干扰其他测试类的绝对积分断言。
 */
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:appeal-review;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class AppealReviewIntegrationTest {

    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper om;
    @Autowired TimeService timeService;
    @Autowired GarbageRoomRepository roomRepository;
    @Autowired ResidentRepository residentRepository;
    @Autowired CommunityRepository communityRepository;
    @Autowired AlertRepository alertRepository;
    @Autowired WorkTaskRepository taskRepository;
    @Autowired CameraStatRepository cameraStatRepository;
    @Autowired EducationTaskRepository educationRepository;
    @Autowired ModelFeedbackSampleRepository feedbackRepository;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String CARD = "CARD2001";

    @BeforeEach
    void setUp() {
        // 固定北京时间 12:00，避免触发夜间高峰联动
        timeService.setClock(Clock.fixed(Instant.parse("2026-10-05T04:00:00Z"), ZONE));
        OffsetDateTime now = timeService.now();
        roomRepository.findAll().forEach(r -> {
            r.setOnline(true);
            r.setLastHeartbeatAt(now);
            roomRepository.save(r);
        });
        // 独立演示居民：find-or-create 并固定初始积分，保证断言确定
        Resident r = residentRepository.findByCardNo(CARD).orElseGet(() -> {
            Resident n = new Resident(CARD, "赵复核",
                    communityRepository.findAll().get(0).getId(), "12栋9单元");
            return residentRepository.save(n);
        });
        r.setTotalPoints(100);
        residentRepository.save(r);
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

    /** 玻璃瓶投入厨余桶：投 KITCHEN，摄像头识别为 RECYCLABLE */
    private JsonNode glassIntoKitchen() {
        return post("/api/resident/disposals", Map.of(
                "cardNo", CARD, "roomCode", "ROOM1", "binType", "KITCHEN",
                "detectedType", "RECYCLABLE", "weightKg", 0.6, "confidence", 0.95));
    }

    private long openAlerts(String room, AlertType type) {
        return alertRepository.findByRoomCodeAndStatusIn(room,
                        List.of(AlertStatus.OPEN, AlertStatus.LINKED)).stream()
                .filter(a -> a.getType() == type).count();
    }

    private int points() {
        return get("/api/resident/" + CARD + "/profile").get("totalPoints").asInt();
    }

    @Test
    void appealReview_fullLinkage() {
        // ---------- 1. 错投识别：玻璃瓶 -> 厨余桶，扣分生效 ----------
        JsonNode d1 = glassIntoKitchen();
        long d1Id = d1.get("id").asLong();
        assertThat(d1.get("wrongSort").asBoolean()).isTrue();
        assertThat(d1.get("cameraCode").asText()).isEqualTo("CAM-ROOM1");
        assertThat(d1.get("penaltySuspended").asBoolean()).isFalse();
        assertThat(points()).isEqualTo(95); // 100 - 5

        // ---------- 2. 居民申诉：服务调取证据包 ----------
        JsonNode appeal1 = post("/api/resident/disposals/" + d1Id + "/appeal",
                Map.of("reason", "我投的是剩菜剩饭，不是玻璃瓶，请复核"));
        long appeal1Id = appeal1.get("id").asLong();
        assertThat(appeal1.get("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(appeal1.get("videoClipUrl").asText()).contains("/ROOM1/" + d1Id + ".mp4");
        assertThat(appeal1.get("weightDeltaKg").asDouble()).isEqualTo(0.6);
        assertThat(appeal1.get("binPosition").asText()).contains("厨余垃圾");
        assertThat(appeal1.get("residentStatement").asText()).contains("剩菜剩饭");
        assertThat(appeal1.get("cameraCode").asText()).isEqualTo("CAM-ROOM1");

        // 重复申诉被拒
        ResponseEntity<JsonNode> dup = http.exchange("/api/resident/disposals/" + d1Id + "/appeal",
                HttpMethod.POST, new HttpEntity<>(Map.of("reason", "再申诉一次"), json()), JsonNode.class);
        assertThat(dup.getStatusCode().is4xxClientError()).isTrue();

        // 物业待复核列表可见证据包
        JsonNode pending = get("/api/property/appeals/pending");
        assertThat(pending.toString()).contains("\"id\":" + appeal1Id);

        // ---------- 3. 复核误判成立：积分恢复 + 样本回传识别模型 ----------
        JsonNode reviewed1 = post("/api/property/appeals/" + appeal1Id + "/review",
                Map.of("accepted", true, "reviewNote", "视频复核为厨余垃圾，摄像头误判"));
        assertThat(reviewed1.get("status").asText()).isEqualTo("MISJUDGE_CONFIRMED");
        assertThat(points()).isEqualTo(100); // 积分恢复
        JsonNode ledger = get("/api/resident/" + CARD + "/points-ledger");
        assertThat(ledger.toString()).contains("申诉成立退还");

        JsonNode feedback = get("/api/property/model-feedback");
        assertThat(feedback.size()).isGreaterThanOrEqualTo(1);
        JsonNode sample = feedback.get(0);
        assertThat(sample.get("cameraCode").asText()).isEqualTo("CAM-ROOM1");
        assertThat(sample.get("detectedType").asText()).isEqualTo("RECYCLABLE");
        assertThat(sample.get("correctedType").asText()).isEqualTo("KITCHEN");
        assertThat(sample.get("status").asText()).isEqualTo("SENT");

        // 摄像头统计：1/1 误判，但未达最小样本数评估线之外 -> 已计入
        CameraStat stat1 = cameraStatRepository.findByCameraCode("CAM-ROOM1").orElseThrow();
        assertThat(stat1.getReviewTotal()).isEqualTo(1);
        assertThat(stat1.getMisjudgeCount()).isEqualTo(1);
        assertThat(stat1.isSuspended()).isFalse();

        // ---------- 4. 第二次误判成立：误判率 2/2=100% 超阈值 -> 设备进入校准 ----------
        JsonNode d2 = glassIntoKitchen();
        JsonNode appeal2 = post("/api/resident/disposals/" + d2.get("id").asLong() + "/appeal",
                Map.of("reason", "又是误判，投的是果皮"));
        post("/api/property/appeals/" + appeal2.get("id").asLong() + "/review",
                Map.of("accepted", true, "reviewNote", "复核为厨余，误判"));
        assertThat(points()).isEqualTo(100); // 第二次扣分也已退还

        CameraStat stat2 = cameraStatRepository.findByCameraCode("CAM-ROOM1").orElseThrow();
        assertThat(stat2.getReviewTotal()).isEqualTo(2);
        assertThat(stat2.getMisjudgeCount()).isEqualTo(2);
        assertThat(stat2.isSuspended()).isTrue();
        assertThat(openAlerts("ROOM1", AlertType.CAMERA_CALIBRATION)).isEqualTo(1);
        // 派生摄像头校准任务
        List<WorkTask> calibTasks = taskRepository.findAll().stream()
                .filter(t -> t.getType() == TaskType.CAMERA_CALIBRATION && t.getStatus() != TaskStatus.DONE)
                .toList();
        assertThat(calibTasks).hasSize(1);
        assertThat(get("/api/property/cameras").toString()).contains("\"suspended\":true");

        // ---------- 5. 校准期间：错投处罚暂停自动生效（不扣分，仍记录待复核） ----------
        JsonNode d3 = glassIntoKitchen();
        long d3Id = d3.get("id").asLong();
        assertThat(d3.get("wrongSort").asBoolean()).isTrue();
        assertThat(d3.get("penaltySuspended").asBoolean()).isTrue();
        assertThat(d3.get("pointsChange").asInt()).isEqualTo(0);
        assertThat(d3.get("violationMessage").asText()).contains("暂缓");
        assertThat(points()).isEqualTo(100); // 未扣分

        // 暂缓处罚的投放仍可申诉，误判成立无需退还（本就没扣）
        JsonNode appeal3 = post("/api/resident/disposals/" + d3Id + "/appeal",
                Map.of("reason", "校准期间被标记，实为厨余"));
        JsonNode reviewed3 = post("/api/property/appeals/" + appeal3.get("id").asLong() + "/review",
                Map.of("accepted", true, "reviewNote", "确认误判"));
        assertThat(reviewed3.get("status").asText()).isEqualTo("MISJUDGE_CONFIRMED");
        assertThat(points()).isEqualTo(100);

        // ---------- 6. 完成校准任务：处罚恢复自动生效 ----------
        long calibTaskId = calibTasks.get(0).getId();
        post("/api/property/tasks/" + calibTaskId + "/complete",
                Map.of("note", "已重新标定摄像头角度与识别参数"));
        CameraStat stat3 = cameraStatRepository.findByCameraCode("CAM-ROOM1").orElseThrow();
        assertThat(stat3.isSuspended()).isFalse();
        assertThat(stat3.getReviewTotal()).isEqualTo(0); // 统计清零重新累计
        assertThat(openAlerts("ROOM1", AlertType.CAMERA_CALIBRATION)).isZero();

        // ---------- 7. 校准后错投恢复扣分；复核违规成立 -> 分类教育任务 ----------
        JsonNode d4 = glassIntoKitchen();
        long d4Id = d4.get("id").asLong();
        assertThat(d4.get("penaltySuspended").asBoolean()).isFalse();
        assertThat(points()).isEqualTo(95); // 处罚恢复：100 - 5

        JsonNode appeal4 = post("/api/resident/disposals/" + d4Id + "/appeal",
                Map.of("reason", "我认为没投错"));
        JsonNode reviewed4 = post("/api/property/appeals/" + appeal4.get("id").asLong() + "/review",
                Map.of("accepted", false, "reviewNote", "视频确认玻璃瓶投入厨余桶，违规成立"));
        assertThat(reviewed4.get("status").asText()).isEqualTo("VIOLATION_CONFIRMED");
        assertThat(points()).isEqualTo(95); // 维持扣分

        // 居民收到分类教育任务（教育记录更新）
        JsonNode eduList = get("/api/resident/" + CARD + "/education");
        assertThat(eduList.size()).isEqualTo(1);
        JsonNode edu = eduList.get(0);
        assertThat(edu.get("status").asText()).isEqualTo("PENDING");
        assertThat(edu.get("title").asText()).contains("厨余垃圾");
        assertThat(edu.get("content").asText()).contains("可回收物");
        assertThat(get("/api/resident/" + CARD + "/profile").get("educationPending").asInt()).isEqualTo(1);

        // 违规成立后错投告警以真实处置闭环
        assertThat(openAlerts("ROOM1", AlertType.MISSORT)).isZero();

        // 居民完成教育打卡
        JsonNode done = post("/api/resident/" + CARD + "/education/" + edu.get("id").asLong() + "/complete",
                Map.of());
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(get("/api/resident/" + CARD + "/profile").get("educationPending").asInt()).isEqualTo(0);

        // ---------- 8. 收尾：本测试在 ROOM1 的告警/任务全部闭环，释放垃圾房状态 ----------
        assertThat(openAlerts("ROOM1", AlertType.MISSORT)).isZero();
        assertThat(openAlerts("ROOM1", AlertType.CAMERA_MISJUDGE)).isZero();
        assertThat(openAlerts("ROOM1", AlertType.CAMERA_CALIBRATION)).isZero();
        GarbageRoom room1 = roomRepository.findByCode("ROOM1").orElseThrow();
        assertThat(room1.getActiveCaseNo()).isNull();
        // 我的申诉单都已有结论
        JsonNode myAppeals = get("/api/resident/" + CARD + "/appeals");
        assertThat(myAppeals.size()).isEqualTo(4);
        for (JsonNode a : myAppeals) {
            assertThat(a.get("status").asText()).isNotEqualTo("PENDING_REVIEW");
        }
    }
}
