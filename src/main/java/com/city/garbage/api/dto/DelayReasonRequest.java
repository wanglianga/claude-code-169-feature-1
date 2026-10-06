package com.city.garbage.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 街道/环卫回填清运延迟原因 */
public record DelayReasonRequest(@NotBlank(message = "reason 不能为空") String reason) {
}
