package com.city.garbage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 联动升级通知留痕：满溢后按顺序通知物业（封桶）、环卫（调整路线）、街道（超时督办），
 * 同时广播到 governance-events 供四端大屏/短信网关订阅。
 */
@Getter
@Setter
@Entity
@Table(name = "notification_record", indexes = {
        @Index(name = "idx_notify_room", columnList = "room_code"),
        @Index(name = "idx_notify_target", columnList = "target_role")
})
public class NotificationRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** PROPERTY / SANITATION / STREET / RESIDENT */
    @Column(name = "target_role", nullable = false, length = 16)
    private String targetRole;

    /** BIN_SEAL / ROUTE_REROUTE / OVERDUE_ESCALATION */
    @Column(nullable = false, length = 32)
    private String notifyType;

    @Column(name = "room_code", nullable = false, length = 32)
    private String roomCode;

    @Column(name = "case_no", length = 40)
    private String caseNo;

    @Column(length = 255)
    private String content;

    /** 幂等键，同一满溢周期内同类通知只发一次 */
    @Column(name = "dedupe_key", length = 120)
    private String dedupeKey;

    private OffsetDateTime createdAt;
}
