package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

/**
 * The data plane's encryption and its MACs.
 *
 * <p>The routes are the ones the AWS SDK builds: the key is in the path, and the body says
 * what to do with it.
 */
@Path(PaymentCryptographyDataPlaneRouteFilter.INTERNAL_PREFIX)
public class PaymentCryptographyCryptoController extends PaymentCryptographyDataPlaneController {

    private final PaymentCryptographyCryptoHandler handler;

    @Inject
    public PaymentCryptographyCryptoController(PaymentCryptographyCryptoHandler handler,
                                              ObjectMapper objectMapper,
                                              RegionResolver regionResolver) {
        super(objectMapper, regionResolver);
        this.handler = handler;
    }

    @POST
    @Path("/keys/{keyIdentifier:.+}/encrypt")
    public Response encryptData(@PathParam("keyIdentifier") String keyIdentifier, String body,
                                 @Context HttpHeaders headers) {
        return respond(handler.encryptData(request(body, keyIdentifier, "KeyIdentifier"), region(headers)));
    }

    @POST
    @Path("/keys/{keyIdentifier:.+}/decrypt")
    public Response decryptData(@PathParam("keyIdentifier") String keyIdentifier, String body,
                                 @Context HttpHeaders headers) {
        return respond(handler.decryptData(request(body, keyIdentifier, "KeyIdentifier"), region(headers)));
    }

    @POST
    @Path("/keys/{incomingKeyIdentifier:.+}/reencrypt")
    public Response reEncryptData(@PathParam("incomingKeyIdentifier") String incomingKeyIdentifier,
                                  String body, @Context HttpHeaders headers) {
        // The key the block is encrypted under is in the body, and it is the only other
        // key the request may name. The model says the request must name it, so a request
        // that does not is refused there.
        JsonNode request = request(body, incomingKeyIdentifier, "IncomingKeyIdentifier");
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
}
