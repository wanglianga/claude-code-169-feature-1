package com.city.garbage.api;

import com.city.garbage.api.dto.DelayReasonRequest;
import com.city.garbage.domain.GovernanceCase;
import com.city.garbage.service.StreetAnalyticsService;
import com.city.garbage.service.StreetEscalationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 街道端：分类质量、投诉趋势、满溢清运超时督办 */
@Tag(name = "街道端", description = "小区分类质量、错投率、投诉趋势、活跃联动单、清运超时督办")
@RestController
@RequestMapping("/api/street")
@RequiredArgsConstructor
public class StreetController {

    private final StreetAnalyticsService analyticsService;
    private final StreetEscalationService escalationService;

    @Operation(summary = "全街道各小区分类质量对比")
    @GetMapping("/overview")
    public List<Map<String, Object>> overview(@RequestParam(defaultValue = "7") int days) {
        return analyticsService.overview(days);
    }

    @Operation(summary = "单个小区分类质量与投诉趋势（按天）")
    @GetMapping("/communities/{communityCode}/quality")
    public Map<String, Object> quality(@PathVariable String communityCode,
                                       @RequestParam(defaultValue = "7") int days) {
        return analyticsService.communityQuality(communityCode, days);
    }

    @Operation(summary = "满溢清运超时督办事件（清运车超时未到，含延迟原因、居民投诉数、现场问题数）")
    @GetMapping("/collection-overdue")
    public List<Map<String, Object>> collectionOverdue(
            @RequestParam(defaultValue = "false") boolean includeResolved) {
        return escalationService.overdueEvents(includeResolved);
    }

    @Operation(summary = "回填清运延迟原因（车辆故障/交通管制/人手不足/路线冲突等），记入联动单")
    @PostMapping("/cases/{caseNo}/delay-reason")
    public GovernanceCase delayReason(@PathVariable String caseNo,
                                      @Valid @RequestBody DelayReasonRequest req) {
        return escalationService.recordDelayReason(caseNo, req.reason());
    }
}
