package com.city.garbage.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka 主题与生产/消费装配。
 * 生产环境 app.kafka.enabled=true：设备事件经 topic device-events 异步进入联动引擎，
 * 联动单/任务变化写入 governance-events 供四端大屏订阅。
 */
@Configuration
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaTopicConfig {

    public static final String DEVICE_EVENTS = "device-events";
    public static final String GOVERNANCE_EVENTS = "governance-events";
    /** 误判复核成立后回传识别模型的纠偏样本 */
    public static final String MODEL_FEEDBACK = "model-feedback";

    @Bean
    public NewTopic deviceEventsTopic() {
        return TopicBuilder.name(DEVICE_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic governanceEventsTopic() {
        return TopicBuilder.name(GOVERNANCE_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic modelFeedbackTopic() {
        return TopicBuilder.name(MODEL_FEEDBACK).partitions(1).replicas(1).build();
    }
}
