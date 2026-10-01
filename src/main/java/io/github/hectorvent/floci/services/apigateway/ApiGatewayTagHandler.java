package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final String REST_APIS = "/restapis/";
    private static final String USAGE_PLANS = "/usageplans/";
    private static final Pattern STAGE_RESOURCE = Pattern.compile("/restapis/[^/]+/stages/([^/]+)");

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
        rejectAccountOrOtherRegion(region, arn);
        String domainName = topLevelIdFromArn(arn, DOMAIN_NAMES);
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
                : service.getTags(region, restApiIdFromArn(arn));
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        rejectAccountOrOtherRegion(region, arn);
        String domainName = topLevelIdFromArn(arn, DOMAIN_NAMES);
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
            service.tagResource(region, restApiIdFromArn(arn), tags);
        }
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        rejectAccountOrOtherRegion(region, arn);
        String domainName = topLevelIdFromArn(arn, DOMAIN_NAMES);
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
            service.untagResource(region, restApiIdFromArn(arn), tagKeys);
        }
    }

    /**
     * API Gateway ARNs carry no account: AWS rejects one that does, even the caller's own, before
     * resolving anything, and answers an ARN naming another region as a missing resource. A string
     * that is not an ARN is left to the parse and its errors.
     */
    private static void rejectAccountOrOtherRegion(String region, String arn) {
        if (!AwsArnUtils.isArn(arn)) {
            return;
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        // ponytail: AWS names the resource type in both messages ("on RestApi <id>", "Invalid API
        // identifier specified <account>:<id>"); Floci uses one generic message per error.
        if (!parsed.accountId().isEmpty()) {
            AwsArnUtils.Arn canonical = new AwsArnUtils.Arn(
                    parsed.partition(), parsed.service(), parsed.region(), "", parsed.resource());
            throw new AwsException("BadRequestException", "Expected ARN " + canonical + ", not " + arn, 400);
        }
        if (!parsed.region().isEmpty() && !parsed.region().equals(region)) {
            throw new AwsException("NotFoundException", "Invalid resource identifier specified", 404);
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
     * The id a {@code /restapis/<apiId>} ARN names. Any other ARN, such as a deployment or resource
     * nested under a REST API, is rejected as AWS rejects it rather than tagging the REST API.
     */
    private static String restApiIdFromArn(String arn) {
        String apiId = topLevelIdFromArn(arn, REST_APIS);
        if (apiId == null) {
            throw new AwsException("BadRequestException", "Invalid ARN specified in the request", 400);
        }
        return apiId;
    }

    /**
     * The stage an ARN whose resource part is exactly {@code /restapis/<apiId>/stages/<stageName>}
     * names, or null for any other ARN. Stages carry their own tags; without this they would land on
     * the REST API.
     */
    private static String stageNameFromArn(String arn) {
        if (!AwsArnUtils.isArn(arn)) {
            return null;
        }
        Matcher matcher = STAGE_RESOURCE.matcher(AwsArnUtils.parse(arn).resource());
        return matcher.matches() ? matcher.group(1) : null;
    }

    /**
     * The id an ARN whose resource part is exactly {@code <prefix><id>} names, such as
     * {@code /apikeys/<apiKeyId>} or {@code /domainnames/<domainName>}, or null for any other ARN,
     * including one that nests the prefix under another resource. A base path mapping ARN continues
     * past the domain, so it falls through to the REST API parse and its "invalid ARN" answer.
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
