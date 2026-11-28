package io.floci.aws.services.codeartifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.services.iam.ResourcePolicyProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Supplies the resource policies of a CodeArtifact domain, repository, package or package group to IAM policy
 * evaluation, with the account that owns it, the one in its ARN, also when it has none, so a request reaching
 * another account's resource by its {@code domain-owner} is judged as cross-account, as AWS judges it: "A principal
 * in another account is only granted access when allowed by at least one resource policy and at least one
 * identity-based policy." A repository, and a package in it, takes its repository's policy and its domain's, either
 * of which may allow the request ("When both policies are present, then both policies will be evaluated and an
 * action is allowed if allowed by either policy"); a domain, and a package group in it, its domain's.
 */
@ApplicationScoped
public class CodeArtifactResourcePolicyProvider implements ResourcePolicyProvider {

    private final CodeArtifactService codeArtifact;
    private final ObjectMapper objectMapper;

    @Inject
    public CodeArtifactResourcePolicyProvider(CodeArtifactService codeArtifact, ObjectMapper objectMapper) {
        this.codeArtifact = codeArtifact;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String credentialScope, String resourceArn) {
        Optional<AwsArnUtils.Arn> parsed = ResourcePolicyProvider.regionalArn(resourceArn, "codeartifact");
        if (parsed.isEmpty()) {
            return List.of();
        }
        AwsArnUtils.Arn arn = parsed.get();
        String[] parts = arn.resource().split("/", -1);
        // An ARN with its domain open (a create's, or a list's) names no one domain.
        if (parts.length < 2 || parts[1].isEmpty() || parts[1].contains("*")) {
            return List.of();
        }
        List<String> documents = new ArrayList<>();
        if (("repository".equals(parts[0]) || "package".equals(parts[0])) && parts.length > 2
                && !parts[2].contains("*")) {
            codeArtifact.repositoryPolicyDocument(arn.accountId(), arn.region(), parts[1], parts[2])
                    .ifPresent(documents::add);
        }
        codeArtifact.domainPolicyDocument(arn.accountId(), arn.region(), parts[1]).ifPresent(documents::add);
        if (documents.isEmpty()) {
            return List.of(new ResourcePolicy(null, arn.accountId()));
        }
        return documents.stream().map(document -> new ResourcePolicy(
                ResourcePolicyProvider.withResource(objectMapper, document, resourceArn),
                arn.accountId())).toList();
    }
}
