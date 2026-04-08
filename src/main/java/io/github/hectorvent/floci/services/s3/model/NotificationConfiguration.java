package io.github.hectorvent.floci.services.s3.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class NotificationConfiguration {

    private List<QueueNotification> queueConfigurations = new ArrayList<>();
    private List<TopicNotification> topicConfigurations = new ArrayList<>();
    private List<LambdaNotification> lambdaFunctionConfigurations = new ArrayList<>();

    public NotificationConfiguration() {
    }

    public List<QueueNotification> getQueueConfigurations() {
        return queueConfigurations;
    }

    public void setQueueConfigurations(List<QueueNotification> queueConfigurations) {
        this.queueConfigurations = queueConfigurations != null ? queueConfigurations : new ArrayList<>();
    }

    public List<TopicNotification> getTopicConfigurations() {
        return topicConfigurations;
    }

    public void setTopicConfigurations(List<TopicNotification> topicConfigurations) {
        this.topicConfigurations = topicConfigurations != null ? topicConfigurations : new ArrayList<>();
    }

    public List<LambdaNotification> getLambdaFunctionConfigurations() {
        return lambdaFunctionConfigurations;
    }

    public void setLambdaFunctionConfigurations(List<LambdaNotification> lambdaFunctionConfigurations) {
        this.lambdaFunctionConfigurations = lambdaFunctionConfigurations != null
                ? lambdaFunctionConfigurations : new ArrayList<>();
    }

    public boolean isEmpty() {
        return queueConfigurations.isEmpty()
               && topicConfigurations.isEmpty()
               && lambdaFunctionConfigurations.isEmpty();
    }
}
