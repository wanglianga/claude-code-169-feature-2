package com.city.garbage.service;

import com.city.garbage.domain.FeedbackStatus;
import com.city.garbage.domain.ModelFeedbackSample;
import com.city.garbage.kafka.KafkaTopicConfig;
import com.city.garbage.repo.ModelFeedbackSampleRepository;
import com.city.garbage.support.TimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 误判样本回传识别模型。
 * 启用 Kafka 时投递到 model-feedback 主题（识别服务订阅纠偏/再训练）；
 * 未启用（本地/演示）时直接置为已回传，保证全链路可验证、可追溯。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelFeedbackService {

    private final ModelFeedbackSampleRepository sampleRepository;
    private final KafkaTemplate<String, Map<String, Object>> kafkaTemplate;
    private final TimeService timeService;

    @Value("${app.kafka.enabled:true}")
    private boolean kafkaEnabled;

    /** 立即回传单条样本 */
    public ModelFeedbackSample send(ModelFeedbackSample sample) {
        if (!kafkaEnabled) {
            markSent(sample, null);
            log.info("Kafka 未启用，误判样本#{} 模拟回传识别模型 camera={}",
                    sample.getId(), sample.getCameraCode());
            return sample;
        }
        try {
            kafkaTemplate.send(KafkaTopicConfig.MODEL_FEEDBACK, sample.getCameraCode(), toPayload(sample))
                    .whenComplete((res, ex) -> {
                        if (ex != null) markFailed(sample, ex.getMessage());
                        else markSent(sample, null);
                    });
        } catch (Exception e) {
            markFailed(sample, e.getMessage());
        }
        return sample;
    }

    /** 重传所有失败/待回传样本，返回成功条数 */
    public int retryPending() {
        int n = 0;
        for (ModelFeedbackSample s : sampleRepository.findByStatus(FeedbackStatus.PENDING)) {
            send(s);
            if (s.getStatus() == FeedbackStatus.SENT) n++;
        }
        for (ModelFeedbackSample s : sampleRepository.findByStatus(FeedbackStatus.FAILED)) {
            send(s);
            if (s.getStatus() == FeedbackStatus.SENT) n++;
        }
        return n;
    }

    private void markSent(ModelFeedbackSample sample, String partition) {
        sample.setStatus(FeedbackStatus.SENT);
        sample.setSentAt(timeService.now());
        sample.setSendError(null);
        sampleRepository.save(sample);
    }

    private void markFailed(ModelFeedbackSample sample, String error) {
        sample.setStatus(FeedbackStatus.FAILED);
        sample.setSendError(error == null ? "unknown" : error.substring(0, Math.min(error.length(), 250)));
        sampleRepository.save(sample);
        log.warn("误判样本#{} 回传识别模型失败: {}", sample.getId(), error);
    }

    private Map<String, Object> toPayload(ModelFeedbackSample s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sampleId", s.getId());
        m.put("disposalId", s.getDisposalId());
        m.put("appealId", s.getAppealId());
        m.put("cameraCode", s.getCameraCode());
        m.put("roomCode", s.getRoomCode());
        m.put("predictedType", s.getPredictedType() == null ? null : s.getPredictedType().name());
        m.put("actualType", s.getActualType() == null ? null : s.getActualType().name());
        m.put("videoClip", s.getVideoClip());
        m.put("reviewNote", s.getReviewNote());
        return m;
    }
}
