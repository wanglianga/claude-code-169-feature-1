package com.city.garbage.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 物业确认厨余桶临时封桶，并指定引导居民前往的备用投放点与预计恢复时间 */
public record SealRequest(
        @NotBlank String operator,
        /** 备用投放点垃圾房编码（可空，系统会就近推荐） */
        String alternativeRoomCode,
        /** 预计恢复投放时间（分钟后），空则取默认配置 */
        Integer estimatedRestoreMinutes,
        String note
) {}
