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
 * The data plane's AS2805 KEK validation.
 *
 * <p>The route is the one the AWS SDK builds, and the key the validation is made with is in
 * the body, because the reference's operation takes no URI parameter.
 */
@Path(PaymentCryptographyDataPlaneRouteFilter.INTERNAL_PREFIX)
public class PaymentCryptographyKekValidationController extends PaymentCryptographyDataPlaneController {

    private final PaymentCryptographyKekValidationOperations operations;

    @Inject
    public PaymentCryptographyKekValidationController(PaymentCryptographyKekValidationOperations operations,
                                                     ObjectMapper objectMapper,
                                                     RegionResolver regionResolver) {
        super(objectMapper, regionResolver);
        this.operations = operations;
    }

    @POST
    @Path("/as2805kekvalidation/generate")
    public Response generateAs2805KekValidation(String body, @Context HttpHeaders headers) {
        return respond(operations.generate(parse(body), region(headers)));
    }
}
