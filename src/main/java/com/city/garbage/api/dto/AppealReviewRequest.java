package com.city.garbage.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 物业/复核员对申诉的复核结论 */
public record AppealReviewRequest(
        /** true=误判成立（恢复积分、样本回传）；false=违规成立（派分类教育任务） */
        boolean accepted,
        @NotBlank(message = "reviewNote 不能为空") String reviewNote,
        /** 复核人，不传默认“现场复核员” */
        String reviewer
) {}
