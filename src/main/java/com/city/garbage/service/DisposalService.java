package com.city.garbage.service;

import com.city.garbage.config.LinkageProperties;
import com.city.garbage.domain.*;
import com.city.garbage.repo.*;
import com.city.garbage.support.BusinessException;
import com.city.garbage.support.TimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 居民投放服务：刷卡/扫码投放 -> 记录小区/楼栋/垃圾类型/重量 ->
 * 摄像头判定错投 -> 积分结算与违规提示 -> 夜间高峰联动。
 * 积分流水始终与真实投放记录一一对应。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisposalService {

    private final DisposalRepository disposalRepository;
    private final ResidentRepository residentRepository;
    private final GarbageRoomRepository roomRepository;
    private final CommunityRepository communityRepository;
    private final PointsLedgerRepository ledgerRepository;
    private final AlertRepository alertRepository;
    private final SiteIssueRepository siteIssueRepository;
    private final LinkageEngine engine;
    private final LinkageProperties props;
    private final TimeService timeService;

    @Transactional
    public DisposalRecord dispose(String cardNo, String roomCode, WasteType binType,
                                  WasteType detectedTypeRaw, Double weightKg, Double confidence,
                                  boolean deferPoints) {
        Resident resident = residentRepository.findByCardNo(cardNo)
                .orElseThrow(() -> new BusinessException("卡号未登记: " + cardNo));
        GarbageRoom room = roomRepository.findByCode(roomCode)
                .orElseThrow(() -> new BusinessException("垃圾房不存在: " + roomCode));
        Community community = communityRepository.findById(room.getCommunityId())
                .orElseThrow(() -> new BusinessException("垃圾房未绑定小区"));
        OffsetDateTime now = timeService.now();

        WasteType detected = detectedTypeRaw != null ? detectedTypeRaw : binType;
        boolean wrong = detected != binType;

        // 厨余桶满溢封桶期间居民仍在该点投放：摄像头记录进入现场管理问题，
        // 派物业现场督导处置，不简单扣居民积分、不计居民错投。
        if (room.isSealed() && binType == WasteType.KITCHEN) {
            return disposeAtSealedBin(resident, community, room, binType, detected, weightKg, confidence, now);
        }

        DisposalRecord r = new DisposalRecord();
        r.setResidentId(resident.getId());
        r.setCardNo(cardNo);
        r.setCommunityId(community.getId());
        r.setBuildingName(resident.getBuildingName());
        r.setRoomCode(roomCode);
        r.setBinType(binType);
        r.setDetectedType(detected);
        r.setWeightKg(weightKg);
        r.setWrongSort(wrong);
        r.setConfidence(confidence);
        r.setCreatedAt(now);

        if (deferPoints) {
            // 设备断网/积分通道异常：投放真实落账，积分暂挂，等待巡检告警与物业核查补发
            r.setPointsChange(0);
            r.setStatus(DisposalStatus.PENDING_POINTS);
            r.setViolationMessage(wrong
                    ? "疑似错投（投" + binType.getLabel() + "识别为" + detected.getLabel() + "），积分通道异常，待核查"
                    : "投放已记录，因积分通道异常积分暂未到账，将自动核查补发");
            r = disposalRepository.save(r);
            if (wrong) {
                Alert alert = engine.raiseAlert(room, AlertType.MISSORT, AlertSeverity.MEDIUM,
                        "居民" + maskCard(cardNo) + "在" + room.getName() + "错投（积分暂挂待核查）",
                        "MISSORT:DISPOSAL:" + r.getId(), r.getId(), null);
                r.setCaseNo(alert.getCaseNo());
                disposalRepository.save(r);
            }
            trackNightPeak(room, now);
            return r;
        }

        int points;
        if (wrong) {
            // 错投：不予积分并记录违规提示，提示现场整改；扣分不使积分为负
            int deduct = Math.min(props.getWrongSortDeduction(), resident.getTotalPoints());
            points = -deduct;
            resident.setTotalPoints(resident.getTotalPoints() - deduct);
            r.setPointsChange(points);
            r.setViolationMessage("疑似错投：您投入" + binType.getLabel() + "桶，摄像头识别为"
                    + detected.getLabel() + "，本次不计分并扣" + deduct + "分，可在3日内申请复核");
            r.setStatus(DisposalStatus.SETTLED);
            r.setSettledAt(now);
        } else {
            points = weightKg != null ? (int) Math.round(weightKg * props.getPointsPerKg()) : 0;
            resident.setTotalPoints(resident.getTotalPoints() + points);
            r.setPointsChange(points);
            r.setViolationMessage("投放正确，+" + points + "积分");
            r.setStatus(DisposalStatus.SETTLED);
            r.setSettledAt(now);
        }
        r = disposalRepository.save(r);
        residentRepository.save(resident);
        ledgerRepository.save(new PointsLedger(resident.getId(), r.getId(), points,
                resident.getTotalPoints(),
                wrong ? "错投扣减：识别为" + detected.getLabel() : binType.getLabel() + "正确投放 " + weightKg + "kg", now));

        if (wrong) {
            Alert alert = engine.raiseAlert(room, AlertType.MISSORT, AlertSeverity.MEDIUM,
                    "居民" + maskCard(cardNo) + "在" + room.getName() + "错投：投" + binType.getLabel()
                            + "识别为" + detected.getLabel() + "（置信度" + confidence + "）",
                    "MISSORT:DISPOSAL:" + r.getId(), r.getId(), null);
            r.setCaseNo(alert.getCaseNo());
            disposalRepository.save(r);
        }

        trackNightPeak(room, now);
        return r;
    }

    /**
     * 封桶期间居民仍到满溢点投放厨余：
     * 投放真实留痕、积分不变（不扣分、不记居民错投），摄像头记录转为现场管理问题，
     * 在当前联动单上派生/刷新物业现场督导任务。
     */
    private DisposalRecord disposeAtSealedBin(Resident resident, Community community, GarbageRoom room,
                                              WasteType binType, WasteType detected, Double weightKg,
                                              Double confidence, OffsetDateTime now) {
        DisposalRecord r = new DisposalRecord();
        r.setResidentId(resident.getId());
        r.setCardNo(resident.getCardNo());
        r.setCommunityId(community.getId());
        r.setBuildingName(resident.getBuildingName());
        r.setRoomCode(room.getCode());
        r.setBinType(binType);
        r.setDetectedType(detected);
        r.setWeightKg(weightKg);
        r.setWrongSort(false);
        r.setConfidence(confidence);
        r.setPointsChange(0);
        r.setStatus(DisposalStatus.SETTLED);
        r.setSettledAt(now);
        r.setViolationMessage("该厨余桶满溢已临时封桶，请改投最近备用点（预计"
                + (room.getEstimatedResumeAt() == null ? "稍后" : room.getEstimatedResumeAt())
                + "恢复）；本次投放不扣分，已转物业现场处置");
        r = disposalRepository.save(r);

        // 确保满溢联动单存在（封桶一定已有单，此处兜底）
        if (room.getActiveCaseNo() == null) {
            engine.raiseAlert(room, AlertType.KITCHEN_FULL_NO_TRUCK, AlertSeverity.URGENT,
                    "厨余桶满溢且清运车未到", "KITCHEN_FULL_NO_TRUCK:" + room.getCode(), null, null);
        }

        SiteIssue issue = new SiteIssue();
        issue.setIssueType(SiteIssueType.DISPOSAL_AT_SEALED_BIN);
        issue.setRoomCode(room.getCode());
        issue.setCommunityId(community.getId());
        issue.setResidentId(resident.getId());
        issue.setCardNo(resident.getCardNo());
        issue.setDisposalId(r.getId());
        issue.setCaseNo(room.getActiveCaseNo());
        issue.setStatus(SiteIssueStatus.OPEN);
        issue.setDetail("封桶期间居民" + maskCard(resident.getCardNo()) + "仍在满溢点投放厨余 "
                + (weightKg == null ? "" : weightKg + "kg") + "，摄像头记录转现场管理问题（不扣居民积分）");
        issue.setCreatedAt(now);
        issue = siteIssueRepository.save(issue);

        long openCount = siteIssueRepository.countByRoomCodeAndStatus(room.getCode(), SiteIssueStatus.OPEN);
        WorkTask siteTask = engine.createSiteTask(room, (int) openCount);
        issue.setTaskId(siteTask.getId());
        issue.setCaseNo(siteTask.getCaseNo());
        siteIssueRepository.save(issue);
        r.setCaseNo(siteTask.getCaseNo());
        disposalRepository.save(r);

        trackNightPeak(room, now);
        return r;
    }

    /** 夜间时段（默认20:00-次日06:00）投放次数达到阈值即触发高峰联动 */
    private void trackNightPeak(GarbageRoom room, OffsetDateTime now) {
        int hour = now.getHour();
        boolean night = hour >= props.getNightStart() || hour < props.getNightEnd();
        if (!night) return;
        String today = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
        if (!today.equals(room.getNightCountDate())) {
            room.setNightCountDate(today);
            room.setNightDisposalCount(0);
        }
        room.setNightDisposalCount(room.getNightDisposalCount() + 1);
        if (room.getNightDisposalCount() >= props.getNightDisposalThreshold()) {
            engine.raiseAlert(room, AlertType.NIGHT_PEAK, AlertSeverity.MEDIUM,
                    "夜间投放高峰：" + today + " 夜间已累计投放" + room.getNightDisposalCount() + "次",
                    "NIGHT_PEAK:" + room.getCode() + ":" + today, null, null);
        }
        roomRepository.save(room);
    }

    /** 居民对摄像头错投判罚申诉：现场复核成立则撤销违规、退还扣分 */
    @Transactional
    public DisposalRecord appeal(Long disposalId, String reason) {
        DisposalRecord r = disposalRepository.findById(disposalId)
                .orElseThrow(() -> new BusinessException("投放记录不存在: " + disposalId));
        if (!r.isWrongSort()) {
            throw new BusinessException("该记录无错投判罚，无需申诉");
        }
        if (r.getStatus() == DisposalStatus.APPEAL_ACCEPTED || r.getStatus() == DisposalStatus.APPEAL_REJECTED) {
            throw new BusinessException("该申诉已处理：" + r.getStatus());
        }
        r.setStatus(DisposalStatus.APPEALING);
        r.setViolationMessage("错投申诉处理中：" + reason);
        return disposalRepository.save(r);
    }

    @Transactional
    public DisposalRecord reviewAppeal(Long disposalId, boolean accepted, String reviewNote) {
        DisposalRecord r = disposalRepository.findById(disposalId)
                .orElseThrow(() -> new BusinessException("投放记录不存在: " + disposalId));
        OffsetDateTime now = timeService.now();
        if (accepted) {
            Resident resident = residentRepository.findById(r.getResidentId())
                    .orElseThrow(() -> new BusinessException("居民不存在"));
            int refund = Math.abs(r.getPointsChange());
            resident.setTotalPoints(resident.getTotalPoints() + refund);
            residentRepository.save(resident);
            r.setPointsChange(0);
            r.setWrongSort(false);
            r.setStatus(DisposalStatus.APPEAL_ACCEPTED);
            r.setViolationMessage("申诉成立，摄像头误判，已撤销违规并退还" + refund + "积分");
            ledgerRepository.save(new PointsLedger(resident.getId(), r.getId(), refund,
                    resident.getTotalPoints(), "申诉成立退还：摄像头误判", now));
            // 摄像头误判告警/错投告警忽略销单
            GarbageRoom room = roomRepository.findByCode(r.getRoomCode())
                    .orElseThrow(() -> new BusinessException("垃圾房不存在"));
            engine.raiseAlert(room, AlertType.CAMERA_MISJUDGE, AlertSeverity.LOW,
                    "投放#" + r.getId() + "摄像头误判，复核成立：" + reviewNote,
                    "CAMERA_MISJUDGE:DISPOSAL:" + r.getId(), r.getId(), null);
            for (Alert a : alertRepository.findByDisposalIdAndStatusIn(r.getId(),
                    List.of(AlertStatus.OPEN, AlertStatus.LINKED))) {
                engine.resolveAlert(a, "复核成立：摄像头误判，" + reviewNote, true);
            }
        } else {
            r.setStatus(DisposalStatus.APPEAL_REJECTED);
            r.setViolationMessage("申诉驳回，维持错投判罚：" + reviewNote);
        }
        return disposalRepository.save(r);
    }

    /** 对长期未结算的投放补发积分（积分未到账整改），补发后解除对应告警与整改任务 */
    @Transactional
    public int settlePendingPoints() {
        OffsetDateTime deadline = timeService.now().minusSeconds(props.getUnsettledSeconds());
        int fixed = 0;
        for (DisposalRecord r : disposalRepository.findAll()) {
            if (r.getStatus() == DisposalStatus.PENDING_POINTS
                    && r.getCreatedAt() != null && r.getCreatedAt().isBefore(deadline)) {
                Resident resident = residentRepository.findById(r.getResidentId()).orElseThrow();
                int points = r.isWrongSort() ? 0
                        : (r.getWeightKg() != null ? (int) Math.round(r.getWeightKg() * props.getPointsPerKg()) : 0);
                resident.setTotalPoints(resident.getTotalPoints() + points);
                residentRepository.save(resident);
                r.setPointsChange(points);
                r.setStatus(DisposalStatus.SETTLED);
                r.setSettledAt(timeService.now());
                r.setViolationMessage(r.isWrongSort() ? "核查完成，维持错投判罚" : "积分核查补发成功，+" + points + "积分");
                disposalRepository.save(r);
                ledgerRepository.save(new PointsLedger(resident.getId(), r.getId(), points,
                        resident.getTotalPoints(), "积分未到账核查补发", timeService.now()));
                for (Alert a : alertRepository.findByDisposalIdAndStatusIn(r.getId(),
                        List.of(AlertStatus.OPEN, AlertStatus.LINKED))) {
                    if (a.getType() == AlertType.POINTS_UNSETTLED) {
                        engine.resolveAlert(a, "已核查补发" + points + "积分", false);
                    }
                }
                fixed++;
            }
        }
        return fixed;
    }

    public List<DisposalRecord> residentHistory(Long residentId) {
        return disposalRepository.findByResidentIdOrderByCreatedAtDesc(residentId);
    }

    private String maskCard(String cardNo) {
        if (cardNo == null || cardNo.length() <= 4) return cardNo;
        return cardNo.substring(0, 2) + "****" + cardNo.substring(cardNo.length() - 2);
    }
}
