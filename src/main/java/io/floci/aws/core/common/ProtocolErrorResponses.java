package io.floci.aws.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.UUID;

/**
 * Error responses a filter answers with before any controller runs, built in the envelope the caller's wire protocol
 * reads, since the controller that would otherwise render the error never sees the request.
 */
public final class ProtocolErrorResponses {

    private ProtocolErrorResponses() {
    }

    /**
     * An error outside S3 and EC2, in the shape the caller's protocol reads: a Query {@code ErrorResponse} for a
     * form-encoded request, the request's own encoding for a CBOR call, and JSON otherwise. Query names some failures
     * without the {@code Exception} suffix, and some differently altogether, so it takes its own code.
     */
    public static Response of(WireProtocol protocol, ContainerRequestContext ctx, String queryCode,
                              String code, String message, int status) {
        if (isCbor(protocol)) {
            return cbor(ctx, code, message, status);
        }
        if (protocol == WireProtocol.AWS_QUERY || isFormEncoded(ctx.getMediaType())) {
            return queryXml(queryCode, message, status);
        }
        return json(code, message, status);
    }

    /** A Query {@code ErrorResponse}, which the REST-XML services other than S3 answer with too. */
    public static Response queryXml(String code, String message, int status) {
        return queryXml(code, message, status, "Sender");
    }

    /** {@link #queryXml(String, String, int)} with the error's {@code Type}, {@code Receiver} for a server fault. */
    public static Response queryXml(String code, String message, int status, String type) {
        String xml = new XmlBuilder()
                .start("ErrorResponse")
                  .start("Error")
                    .elem("Type", type)
                    .elem("Code", code)
                    .elem("Message", message)
                  .end("Error")
                  .elem("RequestId", UUID.randomUUID().toString())
                .end("ErrorResponse")
                .build();
        return Response.status(status).type(MediaType.APPLICATION_XML).entity(xml).build();
    }

    /** S3's bare {@code <Error>} body, naming the bucket or object in {@code Resource} when given one. */
    public static Response s3Xml(String code, String message, String resourcePath, int status) {
        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("Error")
                  .elem("Code", code)
                  .elem("Message", message);
        if (resourcePath != null && !resourcePath.isBlank()) {
            xml.elem("Resource", resourcePath);
        }
        xml.elem("RequestId", UUID.randomUUID().toString())
           .end("Error");
        return Response.status(status).type(MediaType.APPLICATION_XML).entity(xml.build()).build();
    }

    /** A JSON error in the shape {@link AwsExceptionMapper} gives every other JSON failure. */
    public static Response json(String code, String message, int status) {
        return Response.status(status).type(MediaType.APPLICATION_JSON)
                .entity(new AwsErrorResponse(code, message)).build();
    }

    /**
     * A CBOR client cannot read a JSON body, so an error has to arrive in the encoding the request used, the same
     * shape the rpcv2 controller returns for its own errors.
     */
    public static Response cbor(ContainerRequestContext ctx, String code, String message, int status) {
        String requestContentType = ctx.getHeaderString(AwsCborContentTypeFilter.ORIGINAL_CONTENT_TYPE_HEADER);
        if (requestContentType == null) {
            requestContentType = ctx.getHeaderString("Content-Type");
        }
        return CborErrorResponses.of(new AwsException(code, message, status),
                CborErrorResponses.mediaTypeFor(requestContentType));
    }

    public static boolean isCbor(WireProtocol protocol) {
        return protocol == WireProtocol.RPCV2_CBOR || protocol == WireProtocol.AWS_CBOR_TARGET;
    }

    public static boolean isFormEncoded(MediaType mediaType) {
        return mediaType != null
                && "application".equalsIgnoreCase(mediaType.getType())
                && "x-www-form-urlencoded".equalsIgnoreCase(mediaType.getSubtype());
    }
}
