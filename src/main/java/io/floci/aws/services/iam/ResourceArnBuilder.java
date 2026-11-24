package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.RequestPathLabels;
import io.floci.aws.core.common.RequestResources;
import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Constructs the target resource ARN for a request so the policy evaluator
 * can match it against Resource patterns in policy documents.
 *
 * Returns {@code *} when the resource cannot be determined, which matches
 * permissive wildcard policies.
 */
@ApplicationScoped
public class ResourceArnBuilder {

    private final ObjectMapper objectMapper;
    /**
     * Looked up lazily through a provider, to read the ARN a resource the request-resources table names by a name its
     * service follows with a minted id, or by members its minted ARN does not hold, is stored under: this builder is
     * constructed by the enforcement filter, which the services themselves do not depend on, and a direct injection
     * would close that loop.
     */
    private final Instance<StoredResourceArns> storedResourceArns;
    /**
     * Looked up lazily as well, to read a path's labels as the namespace's handlers read them where the router's
     * normalized path gives them otherwise (an S3 key's own slashes).
     */
    private final Instance<RequestPathLabels> requestPathLabels;

    @Inject
    public ResourceArnBuilder(ObjectMapper objectMapper, Instance<StoredResourceArns> storedResourceArns,
                              Instance<RequestPathLabels> requestPathLabels) {
        this.objectMapper = objectMapper;
        this.storedResourceArns = storedResourceArns;
        this.requestPathLabels = requestPathLabels;
    }

    public ResourceArnBuilder(ObjectMapper objectMapper, Instance<StoredResourceArns> storedResourceArns) {
        this(objectMapper, storedResourceArns, null);
    }

    public ResourceArnBuilder() {
        this(new ObjectMapper());
    }

    public ResourceArnBuilder(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    public String build(String credentialScope, ContainerRequestContext ctx,
                        String region, String accountId) {
        List<String> list = buildResources(credentialScope, ctx, region, accountId);
        return list.isEmpty() ? "*" : list.getFirst();
    }

    public List<String> buildResources(String credentialScope, ContainerRequestContext ctx,
                                       String region, String accountId) {
        return buildResources(credentialScope, ctx, region, accountId, AwsRegions.partitionFor(region));
    }

    /**
     * The resources a request is checked against, minted in {@code partition}, the request's, as its service mints its
     * own, so a Region no partition publishes gets the deployment's partition rather than {@code aws}.
     */
    public List<String> buildResources(String credentialScope, ContainerRequestContext ctx,
                                       String region, String accountId, String partition) {
        return buildResources(credentialScope, null, ctx, region, accountId, partition);
    }

    /**
     * The resources a request is checked against, {@code operation} the one it is dispatched as, as
     * {@code <namespace>:<Operation>}: the resources its request names for that operation (see
     * {@link RequestResources}), and {@code *} without one.
     */
    public List<String> buildResources(String credentialScope, String operation, ContainerRequestContext ctx,
                                       String region, String accountId, String partition) {
        if (credentialScope == null) {
            return List.of("*");
        }
        return namedResources(credentialScope, operation, ctx, region, accountId, partition);
    }

    /**
     * The resources a request of a namespace this builder has no branch for names, from the vendored table of
     * request resources (see {@link RequestResources}); {@code *} when it names none, or the operation is not one
     * of the namespace. A resource whose service mints an id after its name, or as all of its ARN, is the one the
     * namespace's {@link StoredResourceArns} finds under that name or the members naming it. A label of the
     * request's path is read as the namespace's {@link RequestPathLabels} reads it, where it reads one otherwise.
     */
    private List<String> namedResources(String credentialScope, String operation, ContainerRequestContext ctx,
                                        String region, String accountId, String partition) {
        if (operation == null || !operation.startsWith(credentialScope + ":")) {
            return List.of("*");
        }
        List<String> named = RequestResources.of(operation, ctx, partition, region, accountId,
                storedResourceArns(credentialScope), labels(credentialScope, ctx));
        return named.isEmpty() ? List.of("*") : named;
    }

    /**
     * The checks an operation AWS authorizes by actions its request's parts each take is made of, each action on the
     * resources its part names (see {@link RequestResources#targets}), read as {@link #buildResources} reads the
     * others: KMS's ReEncrypt, a DynamoDB transaction's items or PartiQL statements. Empty for every other operation.
     */
    public List<RequestResources.Target> targets(String credentialScope, String operation, ContainerRequestContext ctx,
                                                 String region, String accountId, String partition) {
        if (credentialScope == null || operation == null || !operation.startsWith(credentialScope + ":")
                || !RequestResources.reads(operation)) {
            return List.of();
        }
        return RequestResources.targets(operation, ctx, partition, region, accountId,
                storedResourceArns(credentialScope), labels(credentialScope, ctx));
    }

    /**
     * The labels of the request's path, as the route it was routed by gives them, each read as the namespace's
     * {@link RequestPathLabels} reads it where it reads one otherwise.
     */
    private Map<String, String> labels(String credentialScope, ContainerRequestContext ctx) {
        Map<String, String> labels = IamActionRegistry.pathLabels(credentialScope, ctx);
        RequestPathLabels read = requestPathLabels(credentialScope);
        Map<String, String> otherwise = read == null ? Map.of() : read.labels(ctx, labels);
        if (otherwise.isEmpty()) {
            return labels;
        }
        Map<String, String> merged = new HashMap<>(labels);
        merged.putAll(otherwise);
        return Map.copyOf(merged);
    }

    /** The registered reader of the namespace's path labels; null when there is none. */
    private RequestPathLabels requestPathLabels(String namespace) {
        if (requestPathLabels == null || requestPathLabels.isUnsatisfied()) {
            return null;
        }
        for (RequestPathLabels read : requestPathLabels) {
            if (read.namespace().equals(namespace)) {
                return read;
            }
        }
        return null;
    }

    /**
     * The requests the service of {@code operation}, the one the request is dispatched as, makes to another service as
     * the caller on what the resources it is checked on hold (see {@link RequestResources#forwarded}), each resource
     * looked up in its own service's stored state; none when the table gives the operation none.
     */
    public List<RequestResources.Forwarded> forwarded(String operation, List<String> resources, String accountId) {
        if (operation == null || operation.indexOf(':') < 0) {
            return List.of();
        }
        return RequestResources.forwarded(operation, resources, accountId,
                storedResourceArns(operation.substring(0, operation.indexOf(':'))), this::storedResourceArns);
    }

    /** The registered finder of the namespace's stored ARNs; null when there is none. */
    private StoredResourceArns storedResourceArns(String namespace) {
        if (storedResourceArns == null || storedResourceArns.isUnsatisfied()) {
            return null;
        }
        for (StoredResourceArns stored : storedResourceArns) {
            if (stored.namespace().equals(namespace)) {
                return stored;
            }
        }
        return null;
    }

    /**
     * The ARN a shared tagging route acts on, read where its controller reads it: after {@code /tags/} or
     * {@code /v1/tags/} in the path; on {@code POST /tags}, the body's {@code resourceArn}; and on the other
     * bare routes, the {@code resourceArn} query parameter. Null for any other route, or when the request
     * names none.
     */
    public String taggedArn(ContainerRequestContext ctx) {
        String path = IamActionRegistry.decodedPath(ctx);
        for (String route : List.of("/tags", "/v1/tags")) {
            if (path.startsWith(route + "/")) {
                return path.substring(route.length() + 1);
            }
        }
        if ("/tags".equals(path) && "POST".equalsIgnoreCase(ctx.getMethod())) {
            JsonNode body = readJsonBody(ctx);
            return body == null ? null : body.path("resourceArn").asText(null);
        }
        if ("/tags".equals(path) || "/v1/tags".equals(path)) {
            return ctx.getUriInfo().getQueryParameters().getFirst("resourceArn");
        }
        return null;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /** The request's JSON or CBOR body, buffered so the handler still reads it, or null when it has none. */
    public JsonNode readJsonBody(ContainerRequestContext ctx) {
        Object cached = ctx.getProperty("floci.bufferedJsonBody");
        if (cached instanceof JsonNode node) {
            return node;
        }
        InputStream in = ctx.getEntityStream();
        if (in == null) {
            return null;
        }
        byte[] body;
        try {
            body = in.readAllBytes();
        } catch (IOException e) {
            ctx.setEntityStream(new ByteArrayInputStream(new byte[0]));
            return null;
        }
        ctx.setEntityStream(new ByteArrayInputStream(body));
        if (body.length == 0) {
            return null;
        }
        try {
            JsonNode node = RequestBodyReader.readTree(body, ctx.getMediaType(), objectMapper);
            ctx.setProperty("floci.bufferedJsonBody", node);
            return node;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A Query-protocol parameter, read from the same place the handler reads it. For a form POST
     * that is the body alone: {@code AwsQueryController} dispatches on the form parameters and never
     * sees the URL query, so authorizing a URL value would check one resource and act on another.
     * An invalid percent escape is rejected by the shared form decoder before authorization.
     */
    private String firstFormParam(ContainerRequestContext ctx, String name) {
        if (RequestBodyReader.isForm(ctx)) {
            return RequestBodyReader.formField(ctx, name);
        }
        return ctx.getUriInfo().getQueryParameters().getFirst(name);
    }
}
