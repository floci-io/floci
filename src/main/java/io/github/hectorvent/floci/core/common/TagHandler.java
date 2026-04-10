package io.github.hectorvent.floci.core.common;

import java.util.List;
import java.util.Map;

/**
 * Per-service handler for the REST tag endpoints that share the {@code /tags/{resourceArn}}
 * path (API Gateway, EventBridge Scheduler, EFS, etc.).
 *
 * <p>A single {@code SharedTagsController} routes all {@code /tags/{arn}} requests and
 * dispatches to the implementation whose {@link #serviceKey()} matches the {@code service}
 * segment of the request ARN ({@code arn:aws:<service>:<region>:<account>:<resource>}).
 *
 * <p>Implementations are responsible for parsing their own ARN resource format and raising
 * {@link io.github.hectorvent.floci.core.common.AwsException} on invalid input.
 */
public interface TagHandler {

    /**
     * The ARN {@code service} segment this handler responds to (e.g. {@code "apigateway"},
     * {@code "scheduler"}, {@code "elasticfilesystem"}). The {@code SharedTagsController}
     * dispatcher extracts the third colon-separated component of the request ARN and looks
     * up the handler whose {@code serviceKey()} equals that value.
     */
    String serviceKey();

    Map<String, String> listTags(String region, String arn);

    void tagResource(String region, String arn, Map<String, String> tags);

    void untagResource(String region, String arn, List<String> tagKeys);
}
