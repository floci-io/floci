package io.floci.aws.services.fis;

import io.floci.aws.core.common.AwsQueryResponse;
import io.floci.aws.core.common.Ec2ErrorResponses;
import io.floci.aws.core.common.ProtocolErrorResponses;
import io.floci.aws.core.common.ServiceDescriptor;
import io.floci.aws.core.common.ServiceProtocol;
import io.floci.aws.core.common.WireProtocol;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;

/**
 * The error an impaired call gets. FIS's action reference says only that "the specific response depends on each
 * service and API", so each service answers with its own documented error for the failure: EC2 with the server
 * errors its error code list gives, and every other service with the common errors the Kinesis and SNS API references
 * list, in the envelope its protocol reads. A Query or REST-XML service's {@code ErrorResponse} carries the service's
 * own XML namespace, as its own errors do.
 */
final class FisApiErrors {

    private FisApiErrors() {
    }

    /**
     * @param descriptor the service the call reaches, or {@code null} when the catalog has none for it
     */
    static Response response(FisApiFault.Kind kind, String service, WireProtocol protocol,
                             ServiceDescriptor descriptor, ContainerRequestContext ctx) {
        if ("ec2".equals(service)) {
            return switch (kind) {
                case INTERNAL -> Ec2ErrorResponses.of("InternalError", "An internal error has occurred.", 500);
                case THROTTLE -> Ec2ErrorResponses.of("RequestLimitExceeded",
                        "The maximum request rate permitted by the Amazon EC2 APIs has been exceeded for your account.",
                        503);
                case UNAVAILABLE -> Ec2ErrorResponses.of("Unavailable",
                        "The server is overloaded and can't handle the request.", 503);
            };
        }
        CommonError error = CommonError.of(kind);
        if ("s3".equals(service)) {
            return ProtocolErrorResponses.s3Xml(error.code(), error.message(), null, error.status());
        }
        if (ProtocolErrorResponses.isCbor(protocol)) {
            return ProtocolErrorResponses.cbor(ctx, error.code(), error.message(), error.status());
        }
        boolean restXml = descriptor != null && descriptor.supportsProtocol(ServiceProtocol.REST_XML);
        if (restXml || protocol == WireProtocol.AWS_QUERY || ProtocolErrorResponses.isFormEncoded(ctx.getMediaType())) {
            return AwsQueryResponse.error(error.code(), error.message(),
                    descriptor == null ? null : descriptor.xmlNamespace(), error.status());
        }
        return ProtocolErrorResponses.json(error.code(), error.message(), error.status());
    }

    /** The common errors as the Kinesis and SNS API references word them. */
    private record CommonError(String code, String message, int status) {

        static CommonError of(FisApiFault.Kind kind) {
            return switch (kind) {
                case INTERNAL -> new CommonError("InternalFailure", "The request can't be processed right now "
                        + "because of an internal server issue. Try again later. If the problem persists, contact "
                        + "AWS Support.", 500);
                case THROTTLE -> new CommonError("ThrottlingException", "Your request rate is too high. The AWS SDKs "
                        + "automatically retry requests that receive this exception. Reduce the frequency of "
                        + "requests.", 400);
                case UNAVAILABLE -> new CommonError("ServiceUnavailable",
                        "The service is temporarily unavailable. Try again later.", 503);
            };
        }
    }
}
