package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshiftserverless.RedshiftServerlessService;
import io.github.hectorvent.floci.services.redshiftserverless.model.Namespace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.util.Optional;

/**
 * Redshift resource policies, which authorize the sources of zero-ETL integrations into a Redshift
 * Serverless namespace or a provisioned cluster. A policy is stored and echoed, not evaluated. It is
 * kept on the namespace or cluster it belongs to, so deleting the resource deletes its policy.
 */
@ApplicationScoped
public class RedshiftResourcePolicies {

    /** A resource and its policy, as the {@code ResourcePolicy} shape carries them. */
    public record ResourcePolicy(String resourceArn, String policy) {}

    private final RedshiftService redshift;
    private final RedshiftServerlessService serverless;
    private final RegionResolver regionResolver;
    private final ObjectReader strictPolicyReader;

    @Inject
    public RedshiftResourcePolicies(RedshiftService redshift, RedshiftServerlessService serverless,
                                    RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.redshift = redshift;
        this.serverless = serverless;
        this.regionResolver = regionResolver;
        this.strictPolicyReader = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** Replaces the policy of the resource an ARN names. */
    public synchronized ResourcePolicy put(String resourceArn, String policy) {
        requireParameter("ResourceArn", resourceArn);
        requireParameter("Policy", policy);
        requireJsonObject(policy);
        boolean stored = switch (kind(resourceArn)) {
            case NAMESPACE -> serverless.setNamespaceResourcePolicy(resourceArn, regionResolver.getRegion(), policy)
                    .isPresent();
            case CLUSTER -> redshift.setClusterResourcePolicy(resourceArn, policy).isPresent();
        };
        if (!stored) {
            throw resourceNotFound();
        }
        return new ResourcePolicy(resourceArn, policy);
    }

    /** The policy of the resource an ARN names. */
    public synchronized ResourcePolicy get(String resourceArn) {
        requireParameter("ResourceArn", resourceArn);
        return new ResourcePolicy(resourceArn, currentPolicy(resourceArn));
    }

    /** Removes the policy of the resource an ARN names. */
    public synchronized void delete(String resourceArn) {
        requireParameter("ResourceArn", resourceArn);
        currentPolicy(resourceArn);
        switch (kind(resourceArn)) {
            case NAMESPACE -> serverless.setNamespaceResourcePolicy(resourceArn, regionResolver.getRegion(), null);
            case CLUSTER -> redshift.setClusterResourcePolicy(resourceArn, null);
        }
    }

    private enum Kind { NAMESPACE, CLUSTER }

    /**
     * Which resource an ARN names. Zero-ETL integrations target a Serverless namespace or a
     * provisioned cluster, and those are the resources that take a policy here.
     */
    private static Kind kind(String resourceArn) {
        if (!AwsArnUtils.looksLikeArn(resourceArn)) {
            throw resourceNotFound();
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceArn);
        if ("redshift-serverless".equals(arn.service()) && arn.resource().startsWith("namespace/")) {
            return Kind.NAMESPACE;
        }
        if ("redshift".equals(arn.service()) && arn.resource().startsWith("cluster:")) {
            return Kind.CLUSTER;
        }
        throw new AwsException("UnsupportedOperation", "The requested operation isn't supported.", 400);
    }

    /** The resource's policy; a missing resource and a resource without a policy are both not found. */
    private String currentPolicy(String resourceArn) {
        Optional<String> policy = switch (kind(resourceArn)) {
            case NAMESPACE -> serverless.findNamespaceByArn(resourceArn, regionResolver.getRegion())
                    .map(Namespace::getResourcePolicy);
            case CLUSTER -> redshift.findClusterByArn(resourceArn).map(Cluster::getResourcePolicy);
        };
        return policy.orElseThrow(RedshiftResourcePolicies::resourceNotFound);
    }

    private void requireJsonObject(String policy) {
        JsonNode parsed;
        try {
            parsed = strictPolicyReader.readTree(policy);
        } catch (IOException e) {
            throw invalidPolicy();
        }
        if (parsed == null || !parsed.isObject()) {
            throw invalidPolicy();
        }
    }

    private static void requireParameter(String name, String value) {
        if (value == null || value.isEmpty()) {
            throw new AwsException("InvalidParameterValue", name + " is required", 400);
        }
    }

    private static AwsException resourceNotFound() {
        return new AwsException("ResourceNotFoundFault", "The resource could not be found.", 404);
    }

    private static AwsException invalidPolicy() {
        return new AwsException("InvalidPolicyFault", "The resource policy isn't valid.", 400);
    }
}
