package com.city.garbage.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 环卫回填清运延迟原因（车辆故障/交通管制/排班不足等），记入联动事件 */
public record DelayReasonRequest(@NotBlank String reason) {}
