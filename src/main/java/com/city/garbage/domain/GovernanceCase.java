package com.city.garbage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 治理联动单：同一垃圾房当前所有未闭环告警合并到一张单，
 * 物业保洁消杀、环卫清运、积分/申诉、投诉整改全部挂在单上联动闭环。
 */
@Getter
@Setter
@Entity
@Table(name = "governance_case", indexes = {
        @Index(name = "idx_case_room", columnList = "room_code"),
        @Index(name = "idx_case_status", columnList = "status")
})
public class GovernanceCase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_no", nullable = false, unique = true, length = 40)
    private String caseNo;

    @Column(name = "room_code", nullable = false, length = 32)
    private String roomCode;

    @Column(name = "community_id")
    private Long communityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CaseStatus status = CaseStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AlertSeverity severity = AlertSeverity.MEDIUM;

    /** 单上合并的告警类型，逗号分隔 */
    @Column(name = "alert_types", length = 255)
    private String alertTypes = "";

    @Column(length = 512)
    private String summary;

    /** 整改结果说明（保洁/消杀/清运完成后的现场反馈） */
    @Column(name = "rectify_note", length = 512)
    private String rectifyNote;

    private int openTaskCount = 0;

    /* ---- 满溢清运超时升级相关留痕 ---- */

    /** 环卫回填的清运延迟原因（如车辆故障、交通管制、排班不足） */
    @Column(name = "collection_delay_reason", length = 255)
    private String collectionDelayReason;

    /** 本单封桶/超时期间累计的居民投诉数量 */
    @Column(name = "complaint_count", nullable = false)
    private int complaintCount = 0;

    /** 封桶期间居民仍到现场投放的人次（摄像头记录进入现场管理问题） */
    @Column(name = "sealed_disposal_count", nullable = false)
    private int sealedDisposalCount = 0;

    /** 是否已向街道升级超时提醒 */
    @Column(name = "overdue_escalated", nullable = false)
    private boolean overdueEscalated = false;

    private OffsetDateTime overdueEscalatedAt;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime resolvedAt;

    public boolean containsAlertType(AlertType type) {
        if (alertTypes == null || alertTypes.isBlank()) return false;
        for (String t : alertTypes.split(",")) {
            if (t.equals(type.name())) return true;
        }
        return false;
    }

    public void addAlertType(AlertType type) {
        if (containsAlertType(type)) return;
        alertTypes = alertTypes.isBlank() ? type.name() : alertTypes + "," + type.name();
    }
}
