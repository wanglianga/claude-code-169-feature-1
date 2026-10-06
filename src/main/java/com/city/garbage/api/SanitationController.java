package com.city.garbage.api;

import com.city.garbage.domain.TruckVisit;
import com.city.garbage.service.CollectionEscalationService;
import com.city.garbage.service.SanitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 环卫端：清运车到场/离场由设备 TRUCK_ARRIVAL 事件上报，
 * 这里看清运优先级、做清运后清洁确认、手动触发满溢清运超时巡检。
 */
@Tag(name = "环卫端", description = "清运优先级、清运后清洁确认、满溢封桶升级巡检")
@RestController
@RequestMapping("/api/sanitation")
@RequiredArgsConstructor
public class SanitationController {

    private final SanitationService sanitationService;
    private final CollectionEscalationService escalationService;

    @Operation(summary = "清运优先级列表（街道督办 > 已封桶调线 > 厨余满溢未到场 > 其他垃圾满溢）")
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

    @Operation(summary = "立即执行满溢清运超时巡检（满溢超时封桶引导/封桶超时升级街道；正常由定时任务自动执行）")
    @PostMapping("/escalation-check")
    public Map<String, Object> escalationCheck() {
        escalationService.checkSealAndEscalate();
        return Map.of("checked", true);
    }
}
