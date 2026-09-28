package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.core.common.AwsArnUtils;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An Amazon States Language service-integration id, {@code arn:<partition>:states:::<service>:<api>}
 * with the optional {@code aws-sdk:} prefix and {@code .sync}, {@code .sync:2} or
 * {@code .waitForTaskToken} suffix, the only three AWS defines: any other suffix is not an
 * integration id and does not parse. The partition segment is the state machine's, not a fixed
 * {@code aws}: the CDK builds these as {@code arn:${Aws.PARTITION}:states:::...}
 * ({@code aws-stepfunctions-tasks/lib/private/task-utils.ts}), so a China state machine carries
 * {@code arn:aws-cn:states:::lambda:invoke} and must dispatch exactly like the commercial one.
 *
 * @param partition the partition segment as written
 * @param service   the integrated service ({@code lambda}, {@code dynamodb}, {@code sfn}, ...)
 * @param api       the API name as written, e.g. {@code invoke} or {@code startExecution}
 * @param sdk       true for the {@code aws-sdk:} family, false for an optimized integration
 * @param suffix    {@code ""}, {@code .sync}, {@code .sync:2} or {@code .waitForTaskToken}
 */
public record StatesIntegration(String partition, String service, String api, boolean sdk, String suffix) {

    private static final Pattern RESOURCE = Pattern.compile(
            "^arn:(" + AwsArnUtils.PARTITION_REGEX + "):states:::(aws-sdk:)?([a-z0-9-]+):([A-Za-z0-9]+)"
                    + "(\\.sync:2|\\.sync|\\.waitForTaskToken)?$");

    private static final Pattern TAIL = Pattern.compile("^arn:" + AwsArnUtils.PARTITION_REGEX + ":states:::(.*)$");

    public static Optional<StatesIntegration> parse(String resource) {
        if (resource == null) {
            return Optional.empty();
        }
        Matcher matcher = RESOURCE.matcher(resource);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new StatesIntegration(matcher.group(1), matcher.group(3), matcher.group(4),
                matcher.group(2) != null, matcher.group(5) == null ? "" : matcher.group(5)));
    }

    /**
     * Everything after {@code states:::} in any partition, for a resource that has that shape
     * but is not necessarily a well-formed integration id.
     */
    public static Optional<String> tail(String resource) {
        if (resource == null) {
            return Optional.empty();
        }
        Matcher matcher = TAIL.matcher(resource);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /**
     * True for the optimized integration {@code <service>:<api>} with no suffix. A suffixed id
     * is a different integration (a {@code .sync} sendMessage does not exist), so a dispatch
     * that does not read the suffix must not accept one; {@link #isAnySuffix} is for those that do.
     */
    public boolean is(String service, String api) {
        return isAnySuffix(service, api) && suffix.isEmpty();
    }

    /** True for the {@code aws-sdk:<service>:<api>} integration with no suffix. */
    public boolean isSdk(String service, String api) {
        return sdk && this.service.equals(service) && this.api.equals(api) && suffix.isEmpty();
    }

    /** True for the optimized integration {@code <service>:<api>} whatever its suffix, for dispatches that read it. */
    public boolean isAnySuffix(String service, String api) {
        return !sdk && this.service.equals(service) && this.api.equals(api);
    }

    public boolean isOptimizedService(String service) {
        return !sdk && this.service.equals(service);
    }

    public boolean isSdkService(String service) {
        return sdk && this.service.equals(service);
    }

    /** The id without its suffix, as an error message names it. */
    public String withoutSuffix() {
        return "arn:" + partition + ":states:::" + (sdk ? "aws-sdk:" : "") + service + ":" + api;
    }

    @Override
    public String toString() {
        return withoutSuffix() + suffix;
    }
}
