package io.github.hectorvent.floci.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * The data plane of Payment Cryptography (Smithy restJson1).
 *
 * <p>The data plane is a service of its own: it has its own host, its own sign name and its
 * own protocol, and its operations are routed by the path rather than by a target header. The
 * routes are the ones the AWS SDK builds, so a client reaches them without knowing anything
 * about this service.
 *
 * <p>A path that names the key is the key the operation acts on. The body may name one
 * besides it, and then the two must agree, so a request cannot act on a key other than the
 * one its path names.
 *
 * <p>{@code /mac}, {@code /pindata}, {@code /cardvalidationdata} and {@code /cryptogram} are
 * two-segment paths, which S3's path-style routes would claim, so
 * {@link PaymentCryptographyDataPlaneRouteFilter} moves them for the requests signed for this
 * service.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PaymentCryptographyDataPlaneController {

    private final PaymentCryptographyDataPlaneHandler handler;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public PaymentCryptographyDataPlaneController(PaymentCryptographyDataPlaneHandler handler,
                                                 ObjectMapper objectMapper,
                                                 RegionResolver regionResolver) {
        this.handler = handler;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/keys/{keyIdentifier}/encrypt")
    public Response encryptData(@PathParam("keyIdentifier") String keyIdentifier, String body,
                                 @Context HttpHeaders headers) {
        return respond(handler.encryptData(request(body, keyIdentifier, "KeyIdentifier"), region(headers)));
    }

    @POST
    @Path("/keys/{keyIdentifier}/decrypt")
    public Response decryptData(@PathParam("keyIdentifier") String keyIdentifier, String body,
                                 @Context HttpHeaders headers) {
        return respond(handler.decryptData(request(body, keyIdentifier, "KeyIdentifier"), region(headers)));
    }

    @POST
    @Path("/keys/{incomingKeyIdentifier}/reencrypt")
    public Response reEncryptData(@PathParam("incomingKeyIdentifier") String incomingKeyIdentifier,
                                  String body, @Context HttpHeaders headers) {
        JsonNode request = request(body, incomingKeyIdentifier, "IncomingKeyIdentifier");
        // The key the block is encrypted under is in the body, and it is the only other key
        // the request may name.
        String outgoing = request.path("OutgoingKeyIdentifier").asText();
        if (outgoing.isEmpty()) {
            throw new AwsException("ValidationException", "OutgoingKeyIdentifier is required", 400);
        }
        return respond(handler.reEncryptData(request, region(headers)));
    }

    @POST
    @Path("/mac/generate")
    public Response generateMac(String body, @Context HttpHeaders headers) {
        return respond(handler.generateMac(parse(body), region(headers)));
    }

    @POST
    @Path("/mac/verify")
    public Response verifyMac(String body, @Context HttpHeaders headers) {
        return respond(handler.verifyMac(parse(body), region(headers)));
    }

    @POST
    @Path("/pindata/generate")
    public Response generatePinData(String body, @Context HttpHeaders headers) {
        return respond(handler.generatePinData(parse(body), region(headers)));
    }

    @POST
    @Path("/pindata/verify")
    public Response verifyPinData(String body, @Context HttpHeaders headers) {
        return respond(handler.verifyPinData(parse(body), region(headers)));
    }

    @POST
    @Path("/pindata/translate")
    public Response translatePinData(String body, @Context HttpHeaders headers) {
        return respond(handler.translatePinData(parse(body), region(headers)));
    }

    @POST
    @Path("/cardvalidationdata/generate")
    public Response generateCardValidationData(String body, @Context HttpHeaders headers) {
        return respond(handler.generateCardValidationData(parse(body), region(headers)));
    }

    @POST
    @Path("/cardvalidationdata/verify")
    public Response verifyCardValidationData(String body, @Context HttpHeaders headers) {
        return respond(handler.verifyCardValidationData(parse(body), region(headers)));
    }

    @POST
    @Path("/cryptogram/verify")
    public Response verifyAuthRequestCryptogram(String body, @Context HttpHeaders headers) {
        return respond(handler.verifyAuthRequestCryptogram(parse(body), region(headers)));
    }

    /** The body of a request, with the key the path names. */
    private JsonNode request(String body, String pathIdentifier, String member) {
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
    private JsonNode parse(String body) {
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

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private static Response respond(Map<String, Object> answer) {
        return Response.ok(answer).build();
    }
}
