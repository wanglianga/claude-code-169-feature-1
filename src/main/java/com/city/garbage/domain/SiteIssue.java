package com.city.garbage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 现场管理问题：封桶/满溢期间居民仍在该点投放、周边乱倾倒等，
 * 由摄像头记录进入现场管理问题（派物业现场督导处置），而非简单扣居民积分。
 */
@Getter
@Setter
@Entity
@Table(name = "site_issue", indexes = {
        @Index(name = "idx_site_issue_room", columnList = "room_code"),
        @Index(name = "idx_site_issue_status", columnList = "status")
})
public class SiteIssue {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "issue_type", nullable = false, length = 32)
    private SiteIssueType issueType;

    @Column(name = "room_code", nullable = false, length = 32)
    private String roomCode;

    @Column(name = "community_id")
    private Long communityId;

    /** 触发问题的居民（可空，摄像头无法识别时为空） */
    @Column(name = "resident_id")
    private Long residentId;

    @Column(length = 64)
    private String cardNo;

    /** 关联投放记录（由投放接口触发时） */
    @Column(name = "disposal_id")
    private Long disposalId;

    /** 并入的治理联动单号 */
    @Column(name = "case_no", length = 40)
    private String caseNo;

    /** 关联现场治理任务（物业现场督导） */
    @Column(name = "task_id")
    private Long taskId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SiteIssueStatus status = SiteIssueStatus.OPEN;

    @Column(length = 255)
    private String detail;

    @Column(name = "handle_note", length = 255)
    private String handleNote;

    private OffsetDateTime createdAt;
    private OffsetDateTime handledAt;
}
