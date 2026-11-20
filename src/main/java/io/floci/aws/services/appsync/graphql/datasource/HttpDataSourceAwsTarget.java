package io.floci.aws.services.appsync.graphql.datasource;

import io.floci.aws.core.common.AwsArnShapes;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsEndpoints;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsPartitions;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.appsync.model.DataSource;
import org.apache.hc.core5.http.ContentType;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The audited AWS operations an HTTP data source may send to the emulator's own listener. */
record HttpDataSourceAwsTarget(String accountId, String sourceApiArn, String roleArn, String service, String region) {

    static HttpDataSourceAwsTarget parse(DataSource source, HttpDataSourceRequest request,
                                         String apiRegion, RegionResolver regions) {
        Map<String, Object> config = source.getHttpConfig();
        String sourceArn = source.getDataSourceArn();
        String roleArn = source.getServiceRoleArn();
        if (config == null || !(config.get("authorizationConfig") instanceof Map<?, ?> auth)
                || !"AWS_IAM".equals(auth.get("authorizationType"))
                || !(auth.get("awsIamConfig") instanceof Map<?, ?> iam)
                || !(iam.get("signingRegion") instanceof String region)
                || !AwsPartitions.isPublishedRegion(region)
                || !region.equals(region.trim().toLowerCase(Locale.ROOT))
                || !(iam.get("signingServiceName") instanceof String service)
                || !Set.of("scheduler", "states").contains(service)) {
            throw invalid("HTTP AWS_IAM requires a known signing region and a supported signing service");
        }
        URI endpoint;
        try {
            endpoint = URI.create(String.valueOf(config.get("endpoint")));
        } catch (IllegalArgumentException e) {
            throw invalid("Invalid HTTP AWS_IAM endpoint");
        }
        String expectedHost = AwsEndpoints.host(service, region);
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || !expectedHost.equalsIgnoreCase(endpoint.getHost())
                || endpoint.getPort() != -1 || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null || !(endpoint.getRawPath().isEmpty() || "/".equals(endpoint.getRawPath()))
                || !expectedHost.equalsIgnoreCase(request.uri().getHost())
                || !"https".equalsIgnoreCase(request.uri().getScheme()) || request.uri().getPort() != -1) {
            throw invalid("HTTP AWS_IAM endpoint must be the signing service's standard regional HTTPS endpoint");
        }
        if (!AwsArnUtils.isArnFor(sourceArn, "appsync") || !AwsArnUtils.isArnFor(roleArn, "iam")) {
            throw denied("HTTP AWS_IAM requires a data source ARN and an IAM service role");
        }
        AwsArnUtils.Arn owner = AwsArnUtils.parse(sourceArn);
        AwsArnUtils.Arn role = AwsArnUtils.parse(roleArn);
        if (!owner.accountId().equals(regions.getAccountId()) || !owner.region().equals(apiRegion)
                || !RegionResolver.isKnownRegion(apiRegion)
                || !owner.partition().equals(regions.partitionForRegion(apiRegion))
                || !owner.partition().equals(regions.partitionForRegion(region))
                || !owner.resource().matches("apis/[A-Za-z0-9_-]+/datasources/[A-Za-z0-9_-]+")
                || !AwsArnShapes.isIamRole(role) || !role.region().isEmpty() || role.resource().endsWith("/")
                || !role.partition().equals(owner.partition()) || !role.accountId().equals(owner.accountId())) {
            throw denied("HTTP AWS_IAM service role and endpoint must share the data source's account and partition");
        }
        validateOperation(request, service);
        String apiResource = owner.resource().substring(0, owner.resource().indexOf("/datasources/"));
        String apiArn = new AwsArnUtils.Arn(owner.partition(), "appsync", owner.region(), owner.accountId(), apiResource).toString();
        return new HttpDataSourceAwsTarget(owner.accountId(), apiArn, roleArn, service, region);
    }

    private static void validateOperation(HttpDataSourceRequest request, String service) {
        // These are the only caller headers the two audited protocols need. In particular, no
        // authentication, routing, forwarding or internal-control headers can reach the listener.
        Set<String> headers = "states".equals(service)
                ? Set.of("content-type", "accept", "x-amz-target") : Set.of("content-type", "accept");
        if (!headers.containsAll(request.headers().keySet())) {
            throw invalid("Unsupported header for a signed HTTP data source request");
        }
        if (request.headers().containsKey("content-type")) {
            try {
                ContentType type = ContentType.parse(request.headers().get("content-type"));
                Set<String> allowed = "states".equals(service)
                        ? Set.of("application/json", "application/x-amz-json-1.0")
                        : Set.of("application/json");
                if (!allowed.contains(type.getMimeType()) || !StandardCharsets.UTF_8.equals(type.getCharset(StandardCharsets.UTF_8))) {
                    throw invalid("Signed HTTP data sources require a UTF-8 JSON content type");
                }
            } catch (IllegalArgumentException e) {
                throw invalid("Invalid signed HTTP content type");
            }
        }
        String path = request.uri().getRawPath();
        String query = request.uri().getRawQuery();
        if ("scheduler".equals(service)) {
            if (!Set.of("GET", "POST", "DELETE").contains(request.method())
                    || !path.matches("/schedules/[A-Za-z0-9_.-]{1,64}")
                    || "/schedules/.".equals(path) || "/schedules/..".equals(path)) {
                throw invalid("Signed Scheduler requests support CreateSchedule, GetSchedule and DeleteSchedule only");
            }
            if (query != null && !query.isEmpty()) {
                for (String pair : query.split("&")) {
                    String name = pair.split("=", 2)[0];
                    if (!Set.of("groupName", "clientToken").contains(name)) {
                        throw invalid("Unsupported signed Scheduler query parameter");
                    }
                }
            }
        } else if (!"POST".equals(request.method()) || !"/".equals(path) || query != null
                || request.headers().get("x-amz-target") == null
                || !Set.of("AWSStepFunctions.SendTaskSuccess", "AWSStepFunctions.SendTaskFailure")
                        .contains(request.headers().get("x-amz-target"))) {
            throw invalid("Signed Step Functions requests support SendTaskSuccess and SendTaskFailure only");
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("MappingTemplate", message, 400);
    }

    private static AwsException denied(String message) {
        return new AwsException("AccessDeniedException", message, 403);
    }
}
