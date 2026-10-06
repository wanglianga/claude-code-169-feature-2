package com.city.garbage.api.dto;

import com.city.garbage.domain.WasteType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 居民刷卡/扫码投放请求（门禁+摄像头+称重汇聚） */
public record DisposalRequest(
        @NotBlank(message = "cardNo 不能为空") String cardNo,
        @NotBlank(message = "roomCode 不能为空") String roomCode,
        @NotNull(message = "binType 不能为空") WasteType binType,
        /** 摄像头识别类型，不传视为与投放桶一致 */
        WasteType detectedType,
        Double weightKg,
        Double confidence,
        /** true=设备断网/积分通道异常，积分暂挂待核查补发 */
        boolean deferPoints,
        /** 识别摄像头编码，不传时按垃圾房默认 CAM-{roomCode} */
        String cameraCode,
        /** 投放桶位，如 厨余垃圾-1号位 */
        String binPosition
) {
}
