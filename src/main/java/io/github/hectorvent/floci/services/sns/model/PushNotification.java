package io.github.hectorvent.floci.services.sns.model;

import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

/**
 * In-memory capture record for a push notification delivered to a platform endpoint.
 * Not persisted — Floci is a mock; the deque holds the last N notifications so tests
 * can assert what would have been sent to APNS or FCM.
 */
@RegisterForReflection
public record PushNotification(
        String endpointArn,
        String platformApplicationArn,
        String platform,
        String token,
        String payload,
        String subject,
        Map<String, MessageAttributeValue> messageAttributes,
        String messageId,
        Instant timestamp
) {}
