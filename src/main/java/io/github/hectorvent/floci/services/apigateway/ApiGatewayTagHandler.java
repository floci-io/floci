package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * {@link TagHandler} implementation for API Gateway.
 *
 * <p>ARN formats: {@code arn:aws:apigateway:<region>::/restapis/<apiId>} for a REST API,
 * {@code arn:aws:apigateway:<region>::/domainnames/<domainName>} for a custom domain,
 * {@code arn:aws:apigateway:<region>::/apikeys/<apiKeyId>} for an API key and
 * {@code arn:aws:apigateway:<region>::/usageplans/<usagePlanId>} for a usage plan. The
 * {@code apiId}, domain name, key id or plan id is the canonical identifier the underlying
 * {@link ApiGatewayService} uses for its tag store.
 */
@ApplicationScoped
public class ApiGatewayTagHandler implements TagHandler {

    private static final String API_KEYS = "/apikeys/";
    private static final String DOMAIN_NAMES = "/domainnames/";
    private static final String STAGES = "/stages/";
    private static final String USAGE_PLANS = "/usageplans/";

    private final ApiGatewayService service;

    @Inject
    public ApiGatewayTagHandler(ApiGatewayService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "apigateway";
    }

    @Override
    public boolean tagResourceUsesPut() {
        return true;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        String domainName = domainNameFromArn(arn);
        if (domainName != null) {
            return service.getDomainNameTags(region, domainName);
        }
        String apiKeyId = topLevelIdFromArn(arn, API_KEYS);
        if (apiKeyId != null) {
            return service.getApiKey(region, apiKeyId).getTags();
        }
        String usagePlanId = topLevelIdFromArn(arn, USAGE_PLANS);
        if (usagePlanId != null) {
            return service.getUsagePlan(region, usagePlanId).getTags();
        }
        String stageName = stageNameFromArn(arn);
        return stageName != null
                ? service.getStageTags(region, apiIdFromArn(arn), stageName)
                : service.getTags(region, apiIdFromArn(arn));
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        String domainName = domainNameFromArn(arn);
        String apiKeyId = topLevelIdFromArn(arn, API_KEYS);
        String usagePlanId = topLevelIdFromArn(arn, USAGE_PLANS);
        String stageName = stageNameFromArn(arn);
        if (domainName != null) {
            service.tagDomainName(region, domainName, tags);
        } else if (apiKeyId != null) {
            service.tagApiKey(region, apiKeyId, tags);
        } else if (usagePlanId != null) {
            service.tagUsagePlan(region, usagePlanId, tags);
        } else if (stageName != null) {
            service.tagStage(region, apiIdFromArn(arn), stageName, tags);
        } else {
            service.tagResource(region, apiIdFromArn(arn), tags);
        }
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        String domainName = domainNameFromArn(arn);
        String apiKeyId = topLevelIdFromArn(arn, API_KEYS);
        String usagePlanId = topLevelIdFromArn(arn, USAGE_PLANS);
        String stageName = stageNameFromArn(arn);
        if (domainName != null) {
            service.untagDomainName(region, domainName, tagKeys);
        } else if (apiKeyId != null) {
            service.untagApiKey(region, apiKeyId, tagKeys);
        } else if (usagePlanId != null) {
            service.untagUsagePlan(region, usagePlanId, tagKeys);
        } else if (stageName != null) {
            service.untagStage(region, apiIdFromArn(arn), stageName, tagKeys);
        } else {
            service.untagResource(region, apiIdFromArn(arn), tagKeys);
        }
    }

    private static String apiIdFromArn(String arn) {
        String[] parts = arn.split("/restapis/");
        if (parts.length < 2) {
            throw new AwsException("BadRequestException", "Invalid resource ARN: " + arn, 400);
        }
        return parts[1].split("/")[0];
    }

    /**
     * The stage a {@code /restapis/<apiId>/stages/<stageName>} ARN names, or null for any other ARN.
     * Stages carry their own tags; without this they would land on the REST API.
     */
    private static String stageNameFromArn(String arn) {
        int at = arn.indexOf(STAGES);
        if (at < 0 || !arn.contains("/restapis/")) {
            return null;
        }
        String stageName = arn.substring(at + STAGES.length());
        return stageName.isEmpty() || stageName.contains("/") ? null : stageName;
    }

    /**
     * The domain a {@code /domainnames/<name>} ARN names, or null for any other ARN. A base path
     * mapping ARN continues past the domain and is not taggable, so it falls through to the REST API
     * parse and its "invalid ARN" answer.
     */
    private static String domainNameFromArn(String arn) {
        int at = arn.indexOf(DOMAIN_NAMES);
        if (at < 0) {
            return null;
        }
        String domainName = arn.substring(at + DOMAIN_NAMES.length());
        return domainName.isEmpty() || domainName.contains("/") ? null : domainName;
    }

    /**
     * The id an ARN whose resource part is exactly {@code <prefix><id>} names, such as
     * {@code /apikeys/<apiKeyId>} or {@code /usageplans/<usagePlanId>}, or null for any other ARN,
     * including one that nests the prefix under another resource.
     */
    private static String topLevelIdFromArn(String arn, String prefix) {
        if (!AwsArnUtils.isArn(arn)) {
            return null;
        }
        String resource = AwsArnUtils.parse(arn).resource();
        if (!resource.startsWith(prefix)) {
            return null;
        }
        String id = resource.substring(prefix.length());
        return id.isEmpty() || id.contains("/") ? null : id;
    }
}
