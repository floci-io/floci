package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

/**
 * The data plane's card validation values and the authorization cryptograms.
 *
 * <p>The routes are the ones the AWS SDK builds: the card validation value of a card account,
 * and the cryptogram an authorization request carries.
 */
@Path(PaymentCryptographyDataPlaneRouteFilter.INTERNAL_PREFIX)
public class PaymentCryptographyCardController extends PaymentCryptographyDataPlaneController {

    private final PaymentCryptographyCardValidationOperations cardValidation;

    private final PaymentCryptographyAuthCryptogramOperations authCryptogram;

    @Inject
    public PaymentCryptographyCardController(PaymentCryptographyCardValidationOperations cardValidation,
                                             PaymentCryptographyAuthCryptogramOperations authCryptogram,
                                             ObjectMapper objectMapper,
                                             RegionResolver regionResolver) {
        super(objectMapper, regionResolver);
        this.cardValidation = cardValidation;
        this.authCryptogram = authCryptogram;
    }

    @POST
    @Path("/cardvalidationdata/generate")
    public Response generateCardValidationData(String body, @Context HttpHeaders headers) {
        return respond(cardValidation.generate(parse(body), region(headers)));
    }

    @POST
    @Path("/cardvalidationdata/verify")
    public Response verifyCardValidationData(String body, @Context HttpHeaders headers) {
        return respond(cardValidation.verify(parse(body), region(headers)));
    }

    @POST
    @Path("/cryptogram/generate")
    public Response generateAuthRequestCryptogram(String body, @Context HttpHeaders headers) {
        return respond(authCryptogram.generate(parse(body), region(headers)));
    }

    @POST
    @Path("/cryptogram/verify")
    public Response verifyAuthRequestCryptogram(String body, @Context HttpHeaders headers) {
        return respond(authCryptogram.verify(parse(body), region(headers)));
    }
}
