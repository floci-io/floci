package io.github.hectorvent.floci.core.common;

import java.util.List;
import java.util.Map;

/**
 * Per-service handler for the REST tag endpoints that share the {@code /tags/{resourceArn}}
 * path (API Gateway, EventBridge Scheduler, EKS, etc.).
 *
 * <p>A single {@code SharedTagsController} routes all {@code /tags/{arn}} requests and
 * dispatches to the implementation whose {@link #serviceKey()} matches the {@code service}
 * segment of the request ARN ({@code arn:aws:<service>:<region>:<account>:<resource>}).
 *
 * <p>AWS services on this path are not internally consistent in their wire format. Three
 * shape choices are independent:
 * <ul>
 *   <li>{@link #tagsBodyKey()} — the JSON key for the tag payload ({@code "tags"} or
 *       {@code "Tags"}).
 *   <li>{@link #tagsBodyIsList()} — whether the body holds a list of {@code {Key, Value}}
 *       objects rather than a string-to-string map.
 *   <li>{@link #tagKeysQueryName()} — the query parameter name for {@code UntagResource}
 *       ({@code "tagKeys"} or {@code "TagKeys"}). Surprisingly, most services that use
 *       capitalized {@code "Tags"} in the body still use lowercase {@code "tagKeys"} here.
 * </ul>
 * The defaults match the most common AWS shape (lowercase {@code "tags"} map +
 * lowercase {@code "tagKeys"} + POST), which covers ~73 services. EKS and Pipes can use
 * the defaults unmodified; API Gateway shares the same body shape but must override
 * {@link #tagResourceUsesPut()} because AWS defines it with PUT. Handlers whose service
 * deviates on any axis override the relevant method(s) only.
 *
 * <p>Implementations are responsible for parsing their own ARN resource format and raising
 * {@link io.github.hectorvent.floci.core.common.AwsException} on invalid input.
 */
public interface TagHandler {

    /**
     * The ARN {@code service} segment this handler responds to (e.g. {@code "apigateway"},
     * {@code "scheduler"}, {@code "eks"}). The {@code SharedTagsController} dispatcher
     * extracts the third colon-separated component of the request ARN and looks up the
     * handler whose {@code serviceKey()} equals that value.
     */
    String serviceKey();

    /**
     * JSON key for the tag payload on {@code TagResource} and {@code ListTagsForResource}.
     * Defaults to lowercase {@code "tags"}. Override to {@code "Tags"} for services whose
     * AWS spec capitalizes the key. EventBridge Scheduler is the only floci-registered
     * handler that overrides today; ~40 other AWS services share the same AWS spec and
     * would also need to override if they were added.
     */
    default String tagsBodyKey() {
        return "tags";
    }

    /**
     * Whether the {@code TagResource} body and {@code ListTagsForResource} response hold a
     * list of {@code {Key, Value}} objects rather than a string-to-string map. Defaults to
     * {@code false} (map). Override to {@code true} for services that use the list shape
     * (EventBridge Scheduler, NetworkManager, Recycle Bin).
     */
    default boolean tagsBodyIsList() {
        return false;
    }

    /**
     * Whether the dispatcher should reject malformed {@code TagResource} and
     * {@code UntagResource} payloads with {@code ValidationException} instead of silently
     * coercing them to a no-op. Defaults to {@code false} for back-compat with the looser
     * parsing that pre-existing handlers have always relied on. AWS-spec-strict services
     * (notably EventBridge Scheduler) override to {@code true}.
     *
     * <p>This is independent of {@link #tagsBodyIsList()}: a future map-shaped handler
     * that needs strict validation can opt in here without flipping the body shape.
     */
    default boolean strictTagValidation() {
        return false;
    }

    /**
     * Whether an {@code UntagResource} request may omit the tag-key query parameter.
     * Defaults to {@code false} so strict handlers retain their existing validation.
     */
    default boolean allowEmptyTagKeys() {
        return false;
    }

    /**
     * Query parameter name for {@code UntagResource}. Defaults to lowercase
     * {@code "tagKeys"}, which matches the great majority of AWS services — including
     * most that use capitalized {@code "Tags"} in the body. Override to {@code "TagKeys"}
     * only for services that capitalize the query parameter as well (EventBridge Scheduler
     * is the lone such service in floci today).
     */
    default String tagKeysQueryName() {
        return "tagKeys";
    }

    /**
     * Whether {@code TagResource} uses {@code PUT /tags/{arn}}. Defaults to {@code false}
     * (POST), matching the great majority of AWS services. Override to {@code true} for
     * services that AWS defines with PUT (notably API Gateway, plus a handful of others).
     * The dispatcher rejects the unused HTTP method with
     * {@code 405 MethodNotAllowedException}.
     */
    default boolean tagResourceUsesPut() {
        return false;
    }

    /** HTTP status for successful path-based TagResource requests. */
    default int tagResourceSuccessStatus() {
        return 204;
    }

    /** HTTP status for successful path-based UntagResource requests. */
    default int untagResourceSuccessStatus() {
        return 204;
    }

    Map<String, String> listTags(String region, String arn);

    void tagResource(String region, String arn, Map<String, String> tags);

    void untagResource(String region, String arn, List<String> tagKeys);
}
