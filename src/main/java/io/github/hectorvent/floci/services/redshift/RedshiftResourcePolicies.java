package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshiftserverless.RedshiftServerlessService;
import io.github.hectorvent.floci.services.redshiftserverless.model.Namespace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Redshift resource policies, which authorize the sources of zero-ETL integrations into a Redshift
 * Serverless namespace or a provisioned cluster. A policy is stored and echoed, not evaluated. It is
 * kept on the namespace or cluster it belongs to, so deleting the resource deletes its policy.
 */
@ApplicationScoped
public class RedshiftResourcePolicies {

    /** A resource and its policy, as the {@code ResourcePolicy} shape carries them. */
    public record ResourcePolicy(String resourceArn, String policy) {}

    /** The keys AWS writes first, in its order, when it returns a stored policy. */
    private static final List<String> POLICY_KEY_ORDER = List.of("Version", "Statement");
    private static final List<String> STATEMENT_KEY_ORDER =
            List.of("Effect", "Principal", "Action", "Resource", "Condition");

    private static final Pattern NAMESPACE_ID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final RedshiftService redshift;
    private final RedshiftServerlessService serverless;
    private final RegionResolver regionResolver;
    private final ObjectReader strictPolicyReader;
    private final ObjectWriter policyWriter;

    @Inject
    public RedshiftResourcePolicies(RedshiftService redshift, RedshiftServerlessService serverless,
                                    RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.redshift = redshift;
        this.serverless = serverless;
        this.regionResolver = regionResolver;
        this.strictPolicyReader = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.policyWriter = objectMapper.writer(
                new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n")));
    }

    /** Replaces the policy of the resource an ARN names, stored in the form AWS returns it in. */
    public synchronized ResourcePolicy put(String resourceArn, String policy) {
        requireParameter("ResourceArn", resourceArn);
        requireParameter("Policy", policy);
        String normalized = normalized(requireJsonObject(policy));
        Kind kind = kind(resourceArn);
        boolean stored = switch (kind) {
            case NAMESPACE -> serverless.setNamespaceResourcePolicy(resourceArn, regionResolver.getRegion(),
                    normalized).isPresent();
            case CLUSTER -> redshift.setClusterResourcePolicy(resourceArn, normalized).isPresent();
        };
        if (!stored) {
            throw resourceNotFound(kind);
        }
        return new ResourcePolicy(resourceArn, normalized);
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
     * provisioned cluster, and those are the resources that take a policy, in the caller's Region.
     */
    private Kind kind(String resourceArn) {
        if (!AwsArnUtils.looksLikeArn(resourceArn)) {
            throw resourceNotFound(null);
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceArn);
        if (!regionResolver.getRegion().equals(arn.region())) {
            throw new AwsException("InvalidParameterValue", "The Region in the ARN doesn't match the current "
                    + "Region. Change your current Region to the same Region in your resource ARN.", 400);
        }
        if ("redshift-serverless".equals(arn.service()) && arn.resource().startsWith("namespace/")) {
            if (!NAMESPACE_ID.matcher(arn.resource().substring("namespace/".length())).matches()) {
                throw new AwsException("InvalidParameterValue",
                        "The format of the provided identifier is invalid.", 400);
            }
            return Kind.NAMESPACE;
        }
        if ("redshift".equals(arn.service()) && arn.resource().startsWith("cluster:")) {
            return Kind.CLUSTER;
        }
        throw new AwsException("InvalidParameterValue", "Resource type " + resourceType(arn.resource())
                + " isn't supported for the resource policy. Choose another resource type.", 400);
    }

    /** The resource type of an ARN's resource part, such as {@code workgroup} in {@code workgroup/<id>}. */
    private static String resourceType(String resource) {
        int end = resource.length();
        for (char separator : new char[] {'/', ':'}) {
            int at = resource.indexOf(separator);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        return resource.substring(0, end);
    }

    /** The resource's policy; a missing resource and a resource without a policy are both not found. */
    private String currentPolicy(String resourceArn) {
        Kind kind = kind(resourceArn);
        String policy = switch (kind) {
            case NAMESPACE -> serverless.findNamespaceByArn(resourceArn, regionResolver.getRegion())
                    .orElseThrow(() -> resourceNotFound(kind))
                    .getResourcePolicy();
            case CLUSTER -> redshift.findClusterByArn(resourceArn)
                    .orElseThrow(() -> resourceNotFound(kind))
                    .getResourcePolicy();
        };
        if (policy == null) {
            throw resourceNotFound(null);
        }
        return policy;
    }

    /** The policy as a nonempty JSON object; anything else is {@code InvalidPolicyFault}. */
    private ObjectNode requireJsonObject(String policy) {
        JsonNode parsed;
        try {
            parsed = strictPolicyReader.readTree(policy);
        } catch (IOException e) {
            throw invalidPolicy();
        }
        // AWS refuses an empty object too, before it looks the resource up.
        if (!(parsed instanceof ObjectNode object) || object.isEmpty()) {
            throw invalidPolicy();
        }
        return object;
    }

    /**
     * The policy as AWS returns a stored one: {@code Version} and {@code Statement} first, each
     * statement's {@code Effect}, {@code Principal}, {@code Action}, {@code Resource} and
     * {@code Condition} first, any other keys after them in request order, pretty-printed.
     */
    private String normalized(ObjectNode policy) {
        ObjectNode ordered = reordered(policy, POLICY_KEY_ORDER);
        JsonNode statements = ordered.get("Statement");
        if (statements instanceof ObjectNode statement) {
            ordered.set("Statement", reordered(statement, STATEMENT_KEY_ORDER));
        } else if (statements instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i) instanceof ObjectNode statement) {
                    array.set(i, reordered(statement, STATEMENT_KEY_ORDER));
                }
            }
        }
        try {
            return policyWriter.writeValueAsString(ordered);
        } catch (JsonProcessingException e) {
            throw invalidPolicy();
        }
    }

    private static ObjectNode reordered(ObjectNode node, List<String> leadingKeys) {
        ObjectNode ordered = node.objectNode();
        for (String key : leadingKeys) {
            if (node.has(key)) {
                ordered.set(key, node.get(key));
            }
        }
        for (Map.Entry<String, JsonNode> property : node.properties()) {
            if (!ordered.has(property.getKey())) {
                ordered.set(property.getKey(), property.getValue());
            }
        }
        return ordered;
    }

    private static void requireParameter(String name, String value) {
        if (value == null || value.isEmpty()) {
            throw new AwsException("InvalidParameterValue", name + " is required", 400);
        }
    }

    /** Not found; a missing namespace has the message AWS answers with. */
    private static AwsException resourceNotFound(Kind kind) {
        String message = kind == Kind.NAMESPACE ? "The requested namespace doesn't exist."
                : "The resource could not be found.";
        return new AwsException("ResourceNotFoundFault", message, 404);
    }

    private static AwsException invalidPolicy() {
        return new AwsException("InvalidPolicyFault", "The resource policy isn't valid.", 400);
    }
}
