package com.city.garbage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 垃圾房当前聚合状态：四类角色（物业/环卫/街道/居民）看到的是同一份状态。
 * 设备事件不断刷新该表，联动引擎据此产生告警与治理动作。
 */
@Getter
@Setter
@Entity
@Table(name = "garbage_room")
public class GarbageRoom {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "community_id", nullable = false)
    private Long communityId;

    @Column(length = 128)
    private String address;

    /* ---- 设备/桶态实时字段 ---- */
    private Integer kitchenCapacityPct = 0;
    private Integer otherCapacityPct = 0;
    private boolean kitchenFull = false;
    private Integer currentOdor = 0;
    private boolean doorOpen = false;
    private boolean truckPresent = false;
    private boolean online = true;
    private OffsetDateTime lastHeartbeatAt;
    private OffsetDateTime kitchenFullSince;
    private OffsetDateTime otherFullSince;
    private OffsetDateTime lastDisinfectionAt;
    private OffsetDateTime lastCollectionAt;
    private OffsetDateTime lastCleanedAt;

    /** 夜间投放计数（配合 nightCountDate 按日清零） */
    private Integer nightDisposalCount = 0;
    private String nightCountDate;

    /** 当前活跃的治理联动单号（无则为空），保证多告警合并到同一单 */
    @Column(length = 40)
    private String activeCaseNo;

    /* ---- 满溢临时封桶 / 清运超时升级字段 ---- */

    /** 厨余桶是否已被物业临时封桶 */
    @Column(name = "kitchen_sealed", nullable = false)
    private boolean kitchenSealed = false;

    /** 封桶时间（同时作为向环卫催调路线、街道超时计时的起点） */
    private OffsetDateTime sealedAt;

    /** 执行封桶的物业人员 */
    @Column(length = 64)
    private String sealedBy;

    @Column(length = 255)
    private String sealedNote;

    /** 封桶时引导居民前往的备用投放点编码 */
    @Column(name = "alternative_room_code", length = 32)
    private String alternativeRoomCode;

    /** 居民端展示的预计恢复投放时间 */
    private OffsetDateTime estimatedRestoreAt;

    /** 清运超时是否已升级提醒街道（一次封桶周期只升级一次） */
    @Column(name = "overdue_escalated", nullable = false)
    private boolean overdueEscalated = false;

    private OffsetDateTime overdueEscalatedAt;
}
