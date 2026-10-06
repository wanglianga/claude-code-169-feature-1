package com.city.garbage;

import com.city.garbage.domain.*;
import com.city.garbage.repo.*;
import com.city.garbage.service.SealManagementService;
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
 * 厨余桶满溢清运超时升级全链路：
 * 满溢 -> 物业临时封桶并引导备用点 -> 通知环卫调路线 -> 封桶期间投放转现场管理问题（不扣分）
 * -> 清运超时升级街道（事件记录延迟原因、投诉数量、现场投放人次）-> 车到场/清运后解封闭环。
 */
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SealEscalationIntegrationTest {

    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper om;
    @Autowired TimeService timeService;
    @Autowired GarbageRoomRepository roomRepository;
    @Autowired AlertRepository alertRepository;
    @Autowired WorkTaskRepository taskRepository;
    @Autowired GovernanceCaseRepository caseRepository;
    @Autowired ComplaintRepository complaintRepository;
    @Autowired TruckVisitRepository visitRepository;
    @Autowired ResidentRepository residentRepository;
    @Autowired DisposalRepository disposalRepository;
    @Autowired PointsLedgerRepository ledgerRepository;
    @Autowired SealManagementService sealService;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @BeforeEach
    void resetBaseline() {
        timeService.setClock(Clock.fixed(Instant.parse("2026-10-05T04:00:00Z"), ZONE));
        OffsetDateTime now = timeService.now();
        // 全部垃圾房复位为干净可用，清掉历史联动数据，保证本场景断言确定
        disposalRepository.deleteAll();
        ledgerRepository.deleteAll();
        alertRepository.deleteAll();
        taskRepository.deleteAll();
        caseRepository.deleteAll();
        complaintRepository.deleteAll();
        visitRepository.deleteAll();
        for (GarbageRoom r : roomRepository.findAll()) {
            r.setKitchenCapacityPct(0);
            r.setOtherCapacityPct(0);
            r.setKitchenFull(false);
            r.setCurrentOdor(0);
            r.setTruckPresent(false);
            r.setOnline(true);
            r.setLastHeartbeatAt(now);
            r.setKitchenFullSince(null);
            r.setOtherFullSince(null);
            r.setActiveCaseNo(null);
            r.setKitchenSealed(false);
            r.setSealedAt(null);
            r.setSealedBy(null);
            r.setSealedNote(null);
            r.setAlternativeRoomCode(null);
            r.setEstimatedRestoreAt(null);
            r.setOverdueEscalated(false);
            r.setOverdueEscalatedAt(null);
            roomRepository.save(r);
        }
        residentRepository.findByCardNo("CARD1001").ifPresent(p -> {
            p.setTotalPoints(100);
            residentRepository.save(p);
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

    @Test
    void kitchenFull_seal_guide_overdueEscalate_truckRestores() {
        OffsetDateTime t0 = timeService.now();

        // 1) 厨余桶满溢：先派物业“临时封桶引导”，再派环卫“清运调度”（同一张联动单）
        post("/api/device/events", event("KITCHEN_FULL", "ROOM1",
                Map.of("binType", "KITCHEN", "capacityPct", 95)));
        assertThat(openAlerts("ROOM1", AlertType.KITCHEN_FULL_NO_TRUCK)).isEqualTo(1);
        String caseNo = roomRepository.findByCode("ROOM1").orElseThrow().getActiveCaseNo();
        assertThat(caseNo).startsWith("CASE-ROOM1-");
        List<WorkTask> tasks = taskRepository.findByCaseNo(caseNo);
        WorkTask sealTask = tasks.stream().filter(t -> t.getType() == TaskType.TEMP_SEAL).findFirst().orElseThrow();
        WorkTask truckTask = tasks.stream().filter(t -> t.getType() == TaskType.TRUCK_DISPATCH).findFirst().orElseThrow();
        assertThat(sealTask.getAssigneeRole()).isEqualTo("PROPERTY");
        assertThat(truckTask.getAssigneeRole()).isEqualTo("SANITATION");
        assertThat(truckTask.getPriority()).isGreaterThanOrEqualTo(sealTask.getPriority());

        // 2) 物业确认临时封桶：指定备用点 ROOM3、预计 45 分钟恢复；封桶任务闭环
        JsonNode sealed = post("/api/property/rooms/ROOM1/seal", Map.of(
                "operator", "张物业",
                "alternativeRoomCode", "ROOM3",
                "estimatedRestoreMinutes", 45,
                "note", "厨余桶已满，临时封桶"));
        assertThat(sealed.get("kitchenSealed").asBoolean()).isTrue();
        assertThat(sealed.get("alternativeRoomCode").asText()).isEqualTo("ROOM3");
        OffsetDateTime restore = OffsetDateTime.parse(sealed.get("estimatedRestoreAt").asText())
                .toInstant().atOffset(java.time.ZoneOffset.UTC);
        assertThat(restore.toInstant()).isEqualTo(t0.plusMinutes(45).toInstant());
        assertThat(taskRepository.findById(sealTask.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.DONE);

        // 环卫优先级体现“已封桶，申请调整路线”
        JsonNode priorities = get("/api/sanitation/truck-priorities");
        JsonNode room1p = null;
        for (JsonNode p : priorities) if ("ROOM1".equals(p.get("roomCode").asText())) room1p = p;
        assertThat(room1p).isNotNull();
        assertThat(room1p.get("kitchenSealed").asBoolean()).isTrue();
        assertThat(room1p.get("reason").asText()).contains("已封桶");

        // 3) 居民端投放指引：展示封桶状态、最近可用投放点（推荐 ROOM3）、预计恢复时间
        JsonNode guidance = get("/api/resident/rooms/ROOM1/disposal-guidance");
        assertThat(guidance.get("kitchenSealed").asBoolean()).isTrue();
        OffsetDateTime guidanceRestore = OffsetDateTime.parse(guidance.get("estimatedRestoreAt").asText());
        assertThat(guidanceRestore.toInstant()).isEqualTo(t0.plusMinutes(45).toInstant());
        boolean recommendRoom3 = false;
        for (JsonNode alt : guidance.get("alternativePoints")) {
            assertThat(alt.get("roomCode").asText()).isNotEqualTo("ROOM1"); // 满溢点本身不作为备选
            if ("ROOM3".equals(alt.get("roomCode").asText())) {
                assertThat(alt.get("recommended").asBoolean()).isTrue();
                recommendRoom3 = true;
            }
        }
        assertThat(recommendRoom3).isTrue();

        // 4) 封桶期间居民继续投放（含一次“错投”识别）：不扣分，转现场管理问题
        JsonNode d1 = post("/api/resident/disposals", Map.of(
                "cardNo", "CARD1001", "roomCode", "ROOM1", "binType", "KITCHEN",
                "weightKg", 1.0, "confidence", 0.99));
        JsonNode d2 = post("/api/resident/disposals", Map.of(
                "cardNo", "CARD1001", "roomCode", "ROOM1", "binType", "KITCHEN",
                "detectedType", "OTHER", "weightKg", 0.5, "confidence", 0.9));
        assertThat(d1.get("sealedRoomDisposal").asBoolean()).isTrue();
        assertThat(d1.get("pointsChange").asInt()).isZero();
        assertThat(d2.get("sealedRoomDisposal").asBoolean()).isTrue();
        assertThat(d2.get("wrongSort").asBoolean()).isFalse(); // 封桶期间不算居民错投
        assertThat(d2.get("violationMessage").asText()).contains("不计分").contains("备用");
        assertThat(get("/api/resident/CARD1001/profile").get("totalPoints").asInt()).isEqualTo(100);
        assertThat(openAlerts("ROOM1", AlertType.SEALED_SITE_DISPOSAL)).isEqualTo(1); // 去重为一条
        GovernanceCase gc = caseRepository.findByCaseNo(caseNo).orElseThrow();
        assertThat(gc.getSealedDisposalCount()).isEqualTo(2);
        assertThat(taskRepository.findByCaseNo(caseNo)).extracting(WorkTask::getType)
                .contains(TaskType.SITE_MANAGEMENT);

        // 5) 居民满溢投诉并入同一联动单，计入投诉数量
        JsonNode complaint = post("/api/resident/complaints", Map.of(
                "contact", "13900000000", "roomCode", "ROOM1", "type", "FULL_OVERFLOW",
                "content", "厨余桶满了一直没人清，味道很大"));
        assertThat(complaint.get("caseNo").asText()).isEqualTo(caseNo);
        assertThat(caseRepository.findByCaseNo(caseNo).orElseThrow().getComplaintCount()).isEqualTo(1);

        // 6) 环卫回填清运延迟原因，记录到事件
        JsonNode delayCase = post("/api/sanitation/rooms/ROOM1/delay-reason",
                Map.of("reason", "清运车故障，已调派备用车"));
        assertThat(delayCase.get("collectionDelayReason").asText()).contains("清运车故障");

        // 7) 封桶超过阈值清运车仍未到场 -> 升级提醒街道
        GarbageRoom room1 = roomRepository.findByCode("ROOM1").orElseThrow();
        room1.setSealedAt(t0.minusMinutes(31));
        roomRepository.save(room1);
        assertThat(sealService.checkCollectionOverdue()).isEqualTo(1);
        assertThat(sealService.checkCollectionOverdue()).isZero(); // 不重复升级
        assertThat(openAlerts("ROOM1", AlertType.COLLECTION_OVERDUE)).isEqualTo(1);
        assertThat(roomRepository.findByCode("ROOM1").orElseThrow().isOverdueEscalated()).isTrue();

        JsonNode overdues = get("/api/street/collection-overdues");
        JsonNode overdue = null;
        for (JsonNode o : overdues) if ("ROOM1".equals(o.get("roomCode").asText())) overdue = o;
        assertThat(overdue).isNotNull();
        assertThat(overdue.get("complaintCount").asInt()).isEqualTo(1);
        assertThat(overdue.get("sealedDisposalCount").asInt()).isEqualTo(2);
        assertThat(overdue.get("collectionDelayReason").asText()).contains("清运车故障");
        assertThat(overdue.get("alternativeRoomCode").asText()).isEqualTo("ROOM3");

        // 8) 清运车到场 -> 街道超时升级解除；此时仍封桶，直至清运离场清空
        post("/api/device/events", event("TRUCK_ARRIVAL", "ROOM1", Map.of("truckPresent", true)));
        assertThat(openAlerts("ROOM1", AlertType.COLLECTION_OVERDUE)).isZero();
        assertThat(roomRepository.findByCode("ROOM1").orElseThrow().isOverdueEscalated()).isFalse();
        assertThat(roomRepository.findByCode("ROOM1").orElseThrow().isKitchenSealed()).isTrue();

        // 9) 清运离场桶清空 -> 解封、现场管理问题闭环、居民端恢复正常、联动单闭环
        post("/api/device/events", event("TRUCK_ARRIVAL", "ROOM1", Map.of("truckPresent", false)));
        GarbageRoom restored = roomRepository.findByCode("ROOM1").orElseThrow();
        assertThat(restored.isKitchenSealed()).isFalse();
        assertThat(restored.getAlternativeRoomCode()).isNull();
        assertThat(restored.getEstimatedRestoreAt()).isNull();
        assertThat(restored.getActiveCaseNo()).isNull();
        assertThat(openAlerts("ROOM1", AlertType.SEALED_SITE_DISPOSAL)).isZero();
        assertThat(openAlerts("ROOM1", AlertType.KITCHEN_FULL_NO_TRUCK)).isZero();

        JsonNode guidanceAfter = get("/api/resident/rooms/ROOM1/disposal-guidance");
        assertThat(guidanceAfter.get("kitchenSealed").asBoolean()).isFalse();
        assertThat(guidanceAfter.get("estimatedRestoreAt").isNull()).isTrue();
    }
}
