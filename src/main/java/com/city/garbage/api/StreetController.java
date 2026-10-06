package com.city.garbage.api;

import com.city.garbage.service.StreetAnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 街道端：按小区查看分类质量与投诉趋势、清运超时督办 */
@Tag(name = "街道端", description = "小区分类质量、错投率、投诉趋势、活跃联动单、满溢清运超时督办")
@RestController
@RequestMapping("/api/street")
@RequiredArgsConstructor
public class StreetController {

    private final StreetAnalyticsService analyticsService;

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

    @Operation(summary = "满溢清运超时督办列表",
            description = "厨余桶临时封桶后清运车超过阈值仍未到场的垃圾房；"
                    + "含封桶时间、超时时长、居民投诉数量、封桶期间现场投放人次、环卫回填的延迟原因")
    @GetMapping("/collection-overdues")
    public List<Map<String, Object>> collectionOverdues() {
        return analyticsService.collectionOverdues();
    }
}
