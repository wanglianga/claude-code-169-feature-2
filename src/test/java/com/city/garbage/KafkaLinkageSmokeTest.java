package com.city.garbage;

import com.city.garbage.domain.GarbageRoom;
import com.city.garbage.repo.GarbageRoomRepository;
import com.city.garbage.support.TimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 内嵌 Kafka 验证：设备事件 -> topic device-events -> 消费者 -> 垃圾房状态联动。
 */
@EmbeddedKafka(partitions = 1, topics = "device-events")
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.kafka.enabled=true")
@TestPropertySource(properties = {"spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.datasource.url=jdbc:h2:mem:kafka-smoke;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
class KafkaLinkageSmokeTest {

    @Autowired TestRestTemplate http;
    @Autowired GarbageRoomRepository roomRepository;
    @Autowired TimeService timeService;

    @Test
    void deviceEventTravelsThroughKafka() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> event = Map.of(
                "eventType", "KITCHEN_FULL",
                "roomCode", "ROOM1",
                "deviceCode", "DEV-FULL",
                "binType", "KITCHEN",
                "capacityPct", 97,
                "occurredAt", timeService.now().toString());

        ResponseEntity<Map> resp = http.exchange("/api/device/events", HttpMethod.POST,
                new HttpEntity<>(event, headers), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            GarbageRoom room = roomRepository.findByCode("ROOM1").orElseThrow();
            assertThat(room.isKitchenFull()).isTrue();
            assertThat(room.getKitchenCapacityPct()).isEqualTo(97);
            assertThat(room.getActiveCaseNo()).startsWith("CASE-ROOM1-");
        });
    }
}
