package io.floci.aws.services.codeartifact;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What IAM asks CodeArtifact of the resources a request names: the tags of the domain, repository or package group an
 * ARN names, and whether a resource of another account is acted on there.
 */
@ApplicationScoped
public class CodeArtifactStoredResourceArns implements StoredResourceArns {

    private final CodeArtifactService codeArtifact;

    @Inject
    public CodeArtifactStoredResourceArns(CodeArtifactService codeArtifact) {
        this.codeArtifact = codeArtifact;
    }

    @Override
    public String namespace() {
        return "codeartifact";
    }

    /** CodeArtifact mints no id into an ARN a request names by its name, so there is nothing to find. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    /**
     * The package group an ARN names as CodeArtifact stores it, which its tag handlers read whatever the ARN's
     * spelling of its pattern, and the package an ARN names by its normalized name, which its handlers read a
     * PyPI or NuGet name as, so IAM checks the resource CodeArtifact acts on.
     */
    @Override
    public Optional<String> resolveStoredArn(String arn) {
        return codeArtifact.storedPackageGroupArn(arn).or(() -> codeArtifact.normalizedPackageArn(arn));
    }

    /** A package group's pattern, as CodeArtifact encodes it in the group's ARN. */
    @Override
    public String encoded(String variable, String value) {
        return CodeArtifactService.encodedPattern(value);
    }

    /**
     * The tags CodeArtifact holds on the domain, repository or package group an ARN names, in the ARN's account and
     * Region; none for a package, which holds no tags, or for a resource CodeArtifact does not hold.
     */
    @Override
    public Map<String, String> storedTags(String arn) {
        if (!AwsArnUtils.isArnFor(arn, "codeartifact")) {
            return Map.of();
        }
        try {
            return StoredResourceArns.withEmptyValues(codeArtifact.listTagsForResource(arn));
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode()) || "ValidationException".equals(e.getErrorCode())) {
                return Map.of();
            }
            throw e;
        }
    }

    /**
     * A resource of another account, which CodeArtifact acts on in that account, where a request's
     * {@code domain-owner} names it and as its tag handlers read an ARN's account, and never as the caller's of that
     * name.
     */
    @Override
    public boolean reachedInItsAccount(String arn, String accountId) {
        if (!AwsArnUtils.isArnFor(arn, "codeartifact")) {
            return false;
        }
        String owner = AwsArnUtils.parse(arn).accountId();
        return !owner.isEmpty() && !owner.equals(accountId);
    }
}
