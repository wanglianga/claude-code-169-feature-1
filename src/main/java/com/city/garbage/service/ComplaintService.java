package com.city.garbage.service;

import com.city.garbage.domain.*;
import com.city.garbage.repo.*;
import com.city.garbage.support.BusinessException;
import com.city.garbage.support.TimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 投诉接入：异味投诉并入对应垃圾房联动单，随保洁消杀整改结果一并反馈闭环；
 * 满溢/清运类投诉（FULL_OVERFLOW）同样回链当前联动单，作为超时升级时的居民投诉数量依据。
 */
@Service
@RequiredArgsConstructor
public class ComplaintService {

    private final ComplaintRepository complaintRepository;
    private final GarbageRoomRepository roomRepository;
    private final GovernanceCaseRepository caseRepository;
    private final LinkageEngine engine;
    private final TimeService timeService;

    @Transactional
    public Complaint create(Long residentId, String contact, String roomCode, String type, String content) {
        GarbageRoom room = roomCode == null ? null
                : roomRepository.findByCode(roomCode).orElseThrow(() -> new BusinessException("垃圾房不存在: " + roomCode));
        String complaintType = type == null ? "ODOR" : type;
        Complaint c = new Complaint();
        c.setResidentId(residentId);
        c.setContact(contact);
        c.setRoomCode(roomCode);
        c.setCommunityId(room != null ? room.getCommunityId() : null);
        c.setType(complaintType);
        c.setContent(content);
        c.setStatus(ComplaintStatus.OPEN);
        c.setCreatedAt(timeService.now());
        c = complaintRepository.save(c);

        if (room != null && "ODOR".equals(complaintType)) {
            Alert alert = engine.raiseAlert(room, AlertType.ODOR_COMPLAINT, AlertSeverity.HIGH,
                    "居民投诉异味：" + content, "ODOR_COMPLAINT:" + c.getId(), null, c.getId());
            c.setCaseNo(alert.getCaseNo());
            c.setStatus(ComplaintStatus.LINKED);
            complaintRepository.save(c);
            incrementComplaintCount(alert.getCaseNo());
        } else if (room != null && room.getActiveCaseNo() != null) {
            // 满溢/清运类投诉直接回链当前联动单，计入居民投诉数量（街道超时督办依据）
            c.setCaseNo(room.getActiveCaseNo());
            c.setStatus(ComplaintStatus.LINKED);
            complaintRepository.save(c);
            incrementComplaintCount(room.getActiveCaseNo());
        }
        return c;
    }

    private void incrementComplaintCount(String caseNo) {
        if (caseNo == null) return;
        caseRepository.findByCaseNo(caseNo).ifPresent(gc -> {
            gc.setComplaintCount(gc.getComplaintCount() + 1);
            caseRepository.save(gc);
        });
    }

    /** 物业完成整改后反馈，投诉随联动单闭环 */
    @Transactional
    public Complaint reply(Long complaintId, String reply) {
        Complaint c = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new BusinessException("投诉不存在: " + complaintId));
        c.setReply(reply);
        c.setStatus(ComplaintStatus.RESOLVED);
        c.setResolvedAt(timeService.now());
        complaintRepository.save(c);
        if (c.getCaseNo() != null && "ODOR".equals(c.getType())) {
            // 投诉整改完成，解除投诉类告警（传感器异味告警由消杀/读数回落解除）
            engine.resolveByType(c.getRoomCode(), AlertType.ODOR_COMPLAINT, "投诉已整改反馈：" + reply);
        }
        return c;
    }
}
