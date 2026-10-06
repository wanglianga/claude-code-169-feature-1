package com.city.garbage.api;

import com.city.garbage.api.dto.DelayReasonRequest;
import com.city.garbage.domain.GovernanceCase;
import com.city.garbage.domain.TruckVisit;
import com.city.garbage.service.SanitationService;
import com.city.garbage.service.SealManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 环卫端：清运车到场/离场由设备 TRUCK_ARRIVAL 事件上报，
 * 这里看清运优先级、做清运后清洁确认、回填清运延迟原因。
 */
@Tag(name = "环卫端", description = "清运优先级、清运后清洁确认、清运延迟原因回填")
@RestController
@RequestMapping("/api/sanitation")
@RequiredArgsConstructor
public class SanitationController {

    private final SanitationService sanitationService;
    private final SealManagementService sealService;

    @Operation(summary = "清运优先级列表（厨余满溢未到场 > 其他垃圾满溢 > 容量/满溢时长）")
    @GetMapping("/truck-priorities")
    public List<Map<String, Object>> priorities() {
        return sanitationService.priorityList();
    }

    @Operation(summary = "清运完成后清洁确认（不确认超过阈值将产生清运后未清洁告警）")
    @PostMapping("/rooms/{roomCode}/clean-confirm")
    public TruckVisit confirmCleaned(@PathVariable String roomCode,
                                     @RequestParam(required = false) String note) {
        return sanitationService.confirmCleaned(roomCode, note);
    }

    @Operation(summary = "回填清运延迟原因（车辆故障/交通管制/排班不足等），记入联动事件供街道督办")
    @PostMapping("/rooms/{roomCode}/delay-reason")
    public GovernanceCase reportDelayReason(@PathVariable String roomCode,
                                            @Valid @RequestBody DelayReasonRequest req) {
        return sealService.reportDelayReason(roomCode, req.reason());
    }
}
