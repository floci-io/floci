package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.services.lambdaweb.model.WebFunction;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionEndpoint;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionRevision;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The wire shapes of Lambda Web responses. AWS emits members in alphabetical order with microsecond timestamps. */
final class LambdaWebResponses {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    /** How a resource is shown: as stored, or in the transitional state a create or update reports. */
    enum View { STORED, CREATED, UPDATED }

    private LambdaWebResponses() {
    }

    static Map<String, Object> function(WebFunction fn) {
        Map<String, Object> out = node();
        out.put("functionName", fn.functionName);
        out.put("functionArn", fn.functionArn);
        out.put("state", "Active");
        out.put("stateReason", "The function is active.");
        out.put("createdAt", timestamp(fn.createdAt));
        out.put("updatedAt", timestamp(fn.updatedAt));
        return out;
    }

    /** CreateWebFunction shows its first revision and endpoint as Pending. */
    static Map<String, Object> createdFunction(WebFunction fn, WebFunctionRevision revision,
                                               WebFunctionEndpoint endpoint) {
        Map<String, Object> out = function(fn);
        out.put("revision", revisionSummary(revision, View.CREATED));
        out.put("endpoint", endpointSummary(endpoint, View.CREATED));
        if (!fn.tags.isEmpty()) {
            out.put("tags", new TreeMap<>(fn.tags));
        }
        return out;
    }

    static Map<String, Object> revisionSummary(WebFunctionRevision revision, View view) {
        Map<String, Object> out = node();
        out.put("revisionArn", revision.revisionArn);
        out.put("revisionId", revision.revisionId);
        putIfPresent(out, "description", revision.description);
        putRevisionState(out, view);
        out.put("createdAt", timestamp(revision.createdAt));
        return out;
    }

    static Map<String, Object> revision(WebFunctionRevision revision, View view) {
        Map<String, Object> out = revisionSummary(revision, view);
        out.put("functionArn", revision.functionArn);
        putIfPresent(out, "kmsKeyArn", revision.kmsKeyArn);
        Map<String, Object> s3Object = node();
        s3Object.put("bucket", revision.buildConfig.bucket);
        s3Object.put("key", revision.buildConfig.key);
        putIfPresent(s3Object, "versionId", revision.buildConfig.versionId);
        Map<String, Object> buildConfig = node();
        buildConfig.put("codeConfig", Map.of("s3Object", s3Object));
        buildConfig.put("runtimeConfig", Map.of("runtime", revision.buildConfig.runtime));
        out.put("buildConfig", buildConfig);
        WebFunctionRevision.ServiceConfig service = revision.serviceConfig;
        Map<String, Object> serviceConfig = node();
        serviceConfig.put("executionRoleArn", service.executionRoleArn);
        serviceConfig.put("timeoutSeconds", service.timeoutSeconds);
        serviceConfig.put("maxConcurrencyPerEnvironment", service.maxConcurrencyPerEnvironment);
        putIfPresent(serviceConfig, "egressNetworkConnectorArn", service.egressNetworkConnectorArn);
        if (service.environmentVariables != null) {
            serviceConfig.put("environmentVariables", new TreeMap<>(service.environmentVariables));
        }
        Map<String, Object> loggingConfig = node();
        loggingConfig.put("logGroup", service.logGroup);
        loggingConfig.put("applicationLogLevel", service.applicationLogLevel);
        loggingConfig.put("systemLogLevel", service.systemLogLevel);
        serviceConfig.put("telemetryConfig", Map.of("loggingConfig", loggingConfig));
        out.put("serviceConfig", serviceConfig);
        return out;
    }

    private static void putRevisionState(Map<String, Object> out, View view) {
        if (view == View.CREATED) {
            out.put("state", "Pending");
            out.put("stateReason", "The revision is being activated.");
        } else {
            out.put("state", "Active");
            out.put("stateReason", "The revision is active.");
        }
    }

    /** The summary has no functionArn and no per-region block. */
    static Map<String, Object> endpointSummary(WebFunctionEndpoint endpoint, View view) {
        Map<String, Object> out = node();
        out.put("endpointArn", endpoint.endpointArn);
        out.put("endpointName", endpoint.endpointName);
        putIfPresent(out, "description", endpoint.description);
        out.put("endpointType", endpoint.endpointType);
        out.put("domainName", endpoint.domainName);
        out.put("authType", endpoint.authType);
        out.put("autoDeploymentMode", endpoint.autoDeploymentMode);
        out.put("revisionWeights", weights(endpoint.revisionWeights));
        out.put("regions", List.copyOf(endpoint.regions));
        putConfigs(out, endpoint);
        putEndpointState(out, endpoint, view, false);
        out.put("createdAt", timestamp(endpoint.createdAt));
        out.put("updatedAt", timestamp(endpoint.updatedAt));
        return out;
    }

    /** An update response shows the per-region block as it was before the update. */
    static Map<String, Object> endpoint(WebFunctionEndpoint endpoint, WebFunctionEndpoint regionalSource, View view) {
        Map<String, Object> out = endpointSummary(endpoint, view);
        out.put("functionArn", endpoint.functionArn);
        Map<String, Object> regional = node();
        for (String region : endpoint.regions) {
            Map<String, Object> entry = node();
            String regionalDomain = regionalSource.regionalDomainNames.get(region);
            if ("PerRegion".equals(endpoint.endpointType) && regionalDomain != null) {
                entry.put("domainName", regionalDomain);
            }
            entry.put("authType", regionalSource.authType);
            entry.put("revisionWeights", weights(regionalSource.revisionWeights));
            putConfigs(entry, regionalSource);
            putEndpointState(entry, regionalSource, view, true);
            regional.put(region, entry);
        }
        out.put("regionalEndpoints", regional);
        return out;
    }

    private static void putConfigs(Map<String, Object> out, WebFunctionEndpoint endpoint) {
        if (endpoint.scalingConfig != null) {
            Map<String, Object> scaling = node();
            putIfPresent(scaling, "maxEnvironments", endpoint.scalingConfig.maxEnvironments);
            out.put("scalingConfig", scaling);
        }
        if (endpoint.throttleConfig != null) {
            Map<String, Object> throttle = node();
            putIfPresent(throttle, "rateLimit", endpoint.throttleConfig.rateLimit);
            out.put("throttleConfig", throttle);
        }
    }

    private static void putEndpointState(Map<String, Object> out, WebFunctionEndpoint endpoint, View view,
                                         boolean regional) {
        if (view == View.CREATED) {
            out.put("state", "Pending");
            out.put("stateReason", regional
                    ? "The endpoint is being activated in the region."
                    : "The endpoint is being activated and is not yet invokable in any region.");
            return;
        }
        out.put("state", "Active");
        out.put("stateReason", regional ? "The endpoint is active in the region." : "The endpoint is active and invokable.");
        if (view == View.UPDATED) {
            out.put("updateStatus", "InProgress");
            out.put("updateStatusReason", regional
                    ? "The endpoint is being updated in the region."
                    : "The endpoint is being updated.");
        } else if (endpoint.updateStatus != null) {
            out.put("updateStatus", endpoint.updateStatus);
            out.put("updateStatusReason", regional ? "The endpoint is updated in the region." : "The endpoint is updated.");
        }
    }

    private static List<Map<String, Object>> weights(List<WebFunctionEndpoint.RevisionWeight> weights) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WebFunctionEndpoint.RevisionWeight weight : weights) {
            Map<String, Object> entry = node();
            entry.put("revisionId", weight.revisionId);
            entry.put("weight", weight.weight);
            out.add(entry);
        }
        return out;
    }

    static Map<String, Object> accountSettings(LambdaWebService.AccountSettings settings) {
        Map<String, Object> quotas = node();
        quotas.put("maxTotalArmVCpus", LambdaWebService.ACCOUNT_MAX_ARM_VCPUS);
        quotas.put("maxTotalRateLimit", LambdaWebService.ACCOUNT_MAX_RATE_LIMIT);
        quotas.put("maxRevisionsPerFunction", LambdaWebService.MAX_REVISIONS_PER_FUNCTION);
        quotas.put("maxEndpointsPerFunction", LambdaWebService.MAX_ENDPOINTS_PER_FUNCTION);
        Map<String, Object> out = node();
        out.put("accountQuotas", quotas);
        out.put("accountUsage", Map.of("functionCount", settings.functionCount()));
        return out;
    }

    static Map<String, Object> policy(LambdaWebService.ResourcePolicy policy) {
        Map<String, Object> out = node();
        out.put("Policy", policy.policy());
        out.put("RevisionId", policy.revisionId());
        return out;
    }

    static Map<String, Object> list(String member, List<Map<String, Object>> items, String nextToken) {
        Map<String, Object> out = node();
        out.put(member, items);
        putIfPresent(out, "nextToken", nextToken);
        return out;
    }

    static String timestamp(Instant instant) {
        return TIMESTAMP.format(instant);
    }

    private static void putIfPresent(Map<String, Object> out, String member, Object value) {
        if (value != null) {
            out.put(member, value);
        }
    }

    private static Map<String, Object> node() {
        return new TreeMap<>();
    }
}
