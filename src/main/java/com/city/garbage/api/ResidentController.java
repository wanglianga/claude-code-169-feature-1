package com.city.garbage.api;

import com.city.garbage.api.dto.AppealRequest;
import com.city.garbage.api.dto.AppealReviewRequest;
import com.city.garbage.api.dto.ComplaintRequest;
import com.city.garbage.api.dto.DisposalRequest;
import com.city.garbage.domain.Complaint;
import com.city.garbage.domain.DisposalRecord;
import com.city.garbage.domain.PointsLedger;
import com.city.garbage.domain.Resident;
import com.city.garbage.repo.DisposalRepository;
import com.city.garbage.repo.PointsLedgerRepository;
import com.city.garbage.repo.ResidentRepository;
import com.city.garbage.service.ComplaintService;
import com.city.garbage.service.DisposalService;
import com.city.garbage.service.ResidentGuideService;
import com.city.garbage.support.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 居民端：刷卡/扫码投放、积分余额与流水、违规提示、摄像头误判申诉 */
@Tag(name = "居民端", description = "投放、积分、违规提示、误判申诉")
@RestController
@RequestMapping("/api/resident")
@RequiredArgsConstructor
public class ResidentController {

    private final DisposalService disposalService;
    private final ComplaintService complaintService;
    private final ResidentGuideService guideService;
    private final ResidentRepository residentRepository;
    private final PointsLedgerRepository ledgerRepository;
    private final DisposalRepository disposalRepository;

    @Operation(summary = "刷卡/扫码投放",
            description = "记录小区/楼栋/垃圾类型/重量；摄像头识别不一致判错投并提示；"
                    + "断网时 deferPoints=true 积分暂挂，核查后自动补发")
    @PostMapping("/disposals")
    public DisposalRecord dispose(@Valid @RequestBody DisposalRequest req) {
        return disposalService.dispose(req.cardNo(), req.roomCode(), req.binType(),
                req.detectedType(), req.weightKg(), req.confidence(), req.deferPoints());
    }

    @Operation(summary = "我的积分与违规概览")
    @GetMapping("/{cardNo}/profile")
    public Map<String, Object> profile(@PathVariable String cardNo) {
        Resident r = residentRepository.findByCardNo(cardNo)
                .orElseThrow(() -> new BusinessException("卡号未登记: " + cardNo));
        List<DisposalRecord> recent = disposalService.residentHistory(r.getId());
        long wrong = recent.stream().filter(DisposalRecord::isWrongSort).count();
        return Map.of(
                "cardNo", r.getCardNo(),
                "name", r.getName() == null ? "" : r.getName(),
                "buildingName", r.getBuildingName() == null ? "" : r.getBuildingName(),
                "totalPoints", r.getTotalPoints(),
                "disposalCount", recent.size(),
                "wrongSortCount", wrong,
                "latestViolation", recent.stream().filter(DisposalRecord::isWrongSort).findFirst()
                        .map(DisposalRecord::getViolationMessage).orElse(""));
    }

    @Operation(summary = "我的投放记录（含每次违规提示与积分变动）")
    @GetMapping("/{cardNo}/disposals")
    public List<DisposalRecord> disposals(@PathVariable String cardNo) {
        Resident r = residentRepository.findByCardNo(cardNo)
                .orElseThrow(() -> new BusinessException("卡号未登记: " + cardNo));
        return disposalService.residentHistory(r.getId());
    }

    @Operation(summary = "我的积分流水")
    @GetMapping("/{cardNo}/points-ledger")
    public List<PointsLedger> ledger(@PathVariable String cardNo) {
        Resident r = residentRepository.findByCardNo(cardNo)
                .orElseThrow(() -> new BusinessException("卡号未登记: " + cardNo));
        return ledgerRepository.findByResidentIdOrderByCreatedAtDesc(r.getId());
    }

    @Operation(summary = "对摄像头错投判罚发起申诉（误判复核）")
    @PostMapping("/disposals/{id}/appeal")
    public DisposalRecord appeal(@PathVariable Long id, @Valid @RequestBody AppealRequest req) {
        return disposalService.appeal(id, req.reason());
    }

    @Operation(summary = "物业/街道现场复核申诉结论（成立则撤销违规并退还积分）")
    @PostMapping("/disposals/{id}/appeal-review")
    public DisposalRecord review(@PathVariable Long id, @Valid @RequestBody AppealReviewRequest req) {
        return disposalService.reviewAppeal(id, req.accepted(), req.reviewNote());
    }

    @Operation(summary = "居民提交投诉（如异味投诉，自动并入垃圾房联动单）")
    @PostMapping("/complaints")
    public Complaint complaint(@Valid @RequestBody ComplaintRequest req) {
        return complaintService.create(req.residentId(), req.contact(), req.roomCode(), req.type(), req.content());
    }

    @Operation(summary = "投放点引导：封桶状态、预计恢复时间与最近可用投放点",
            description = "厨余桶满溢临时封桶时，居民端据此引导至同小区/同街道最近可用投放点")
    @GetMapping("/rooms/{roomCode}/disposal-guide")
    public Map<String, Object> disposalGuide(@PathVariable String roomCode) {
        return guideService.disposalGuide(roomCode);
    }
}
