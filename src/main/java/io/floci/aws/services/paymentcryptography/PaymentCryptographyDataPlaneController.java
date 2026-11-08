package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * What the data plane's controllers share: the body of a request, the region it is for, and
 * the answer it gives.
 *
 * <p>The data plane is a service of its own (Smithy restJson1): it has its own host, its own
 * sign name and its own protocol, and its operations are routed by the path rather than by a
 * target header. The routes are the ones the AWS SDK builds, so a client reaches them without
 * knowing anything about this service.
 *
 * <p>A path that names the key is the key the operation acts on. The body may name one
 * besides it, and then the two must agree, so a request cannot act on a key other than the
 * one its path names.
 *
 * <p>{@code /mac}, {@code /pindata}, {@code /cardvalidationdata} and {@code /cryptogram} are
 * two-segment paths, which S3's path-style routes would claim, so
 * {@link PaymentCryptographyDataPlaneRouteFilter} moves them for the requests signed for this
 * service.
 *
 * <p>The operations are grouped by what they do, and each group has its own controller: the
 * encryption and the MACs, the PIN data, and the card validation values with the authorization
 * cryptograms. The groups share this base and nothing else, so each of them can be read on
 * its own.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public abstract class PaymentCryptographyDataPlaneController {

    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public PaymentCryptographyDataPlaneController(ObjectMapper objectMapper,
                                                 RegionResolver regionResolver) {
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    /** The body of a request, with the key the path names. */
    protected JsonNode request(String body, String pathIdentifier, String member) {
        JsonNode request = parse(body);
        String named = request.path(member).asText();
        if (!named.isEmpty() && !named.equals(pathIdentifier)) {
            throw new AwsException("ValidationException",
                    member + " in the body is not the key the path names", 400);
        }
        ObjectNode withKey = ((ObjectNode) request).deepCopy();
        withKey.put(member, pathIdentifier);
        return withKey;
    }

    /** The body of a request, which is a JSON object. */
    protected JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new AwsException("ValidationException", "the body must be a JSON object", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("ValidationException", "the body is not valid JSON", 400);
        }
    }

    /** The region the request is for. */
    protected String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    /** The answer of an operation. */
    protected static Response respond(Map<String, Object> answer) {
        return Response.ok(answer).build();
    }
}
