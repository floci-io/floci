package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * {@link TagHandler} implementation for API Gateway.
 *
 * <p>ARN formats: {@code arn:aws:apigateway:<region>::/restapis/<apiId>} for a REST API and
 * {@code arn:aws:apigateway:<region>::/domainnames/<domainName>} for a custom domain. The
 * {@code apiId} or domain name is the canonical identifier the underlying {@link ApiGatewayService}
 * uses for its tag store.
 */
@ApplicationScoped
public class ApiGatewayTagHandler implements TagHandler {

    private static final String DOMAIN_NAMES = "/domainnames/";
    private static final String STAGES = "/stages/";

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
        String stageName = stageNameFromArn(arn);
        return stageName != null
                ? service.getStageTags(region, apiIdFromArn(arn), stageName)
                : service.getTags(region, apiIdFromArn(arn));
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        String domainName = domainNameFromArn(arn);
        String stageName = stageNameFromArn(arn);
        if (domainName != null) {
            service.tagDomainName(region, domainName, tags);
        } else if (stageName != null) {
            service.tagStage(region, apiIdFromArn(arn), stageName, tags);
        } else {
            service.tagResource(region, apiIdFromArn(arn), tags);
        }
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        String domainName = domainNameFromArn(arn);
        String stageName = stageNameFromArn(arn);
        if (domainName != null) {
            service.untagDomainName(region, domainName, tagKeys);
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
}
