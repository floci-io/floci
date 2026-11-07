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
 * The data plane's PIN operations.
 *
 * <p>The routes are the ones the AWS SDK builds: the PIN data is made, verified and
 * translated under the paths the model names.
 */
@Path(PaymentCryptographyDataPlaneRouteFilter.INTERNAL_PREFIX)
public class PaymentCryptographyPinController extends PaymentCryptographyDataPlaneController {

    private final PaymentCryptographyPinOperations operations;

    @Inject
    public PaymentCryptographyPinController(PaymentCryptographyPinOperations operations,
                                             ObjectMapper objectMapper,
                                             RegionResolver regionResolver) {
        super(objectMapper, regionResolver);
        this.operations = operations;
    }

    @POST
    @Path("/pindata/generate")
    public Response generatePinData(String body, @Context HttpHeaders headers) {
        return respond(operations.generatePinData(parse(body), region(headers)));
    }

    @POST
    @Path("/pindata/verify")
    public Response verifyPinData(String body, @Context HttpHeaders headers) {
        return respond(operations.verifyPinData(parse(body), region(headers)));
    }

    @POST
    @Path("/pindata/translate")
    public Response translatePinData(String body, @Context HttpHeaders headers) {
        return respond(operations.translatePinData(parse(body), region(headers)));
    }

    @POST
    @Path("/macemvpinchange/generate")
    public Response generateMacEmvPinChange(String body, @Context HttpHeaders headers) {
        return respond(operations.generateMacEmvPinChange(parse(body), region(headers)));
    }

}
