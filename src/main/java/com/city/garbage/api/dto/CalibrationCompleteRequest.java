package com.city.garbage.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 摄像头校准完成回填 */
public record CalibrationCompleteRequest(@NotBlank String note) {}
