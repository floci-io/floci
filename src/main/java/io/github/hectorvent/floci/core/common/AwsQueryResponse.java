package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.UUID;

/**
 * Shared helpers for building AWS Query-protocol (form-encoded POST → XML) responses.
 *
 * <p>Every Query-protocol service handler should call these methods instead of
 * hand-rolling its own {@code ResponseMetadata}, envelope, or error skeleton.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Simple envelope
 * return Response.ok(AwsQueryResponse.envelope("CreateQueue", null, resultXml)).build();
 *
 * // Namespaced envelope (e.g. SNS)
 * return Response.ok(AwsQueryResponse.envelope("CreateTopic", AwsNamespaces.SNS, resultXml)).build();
 *
 * // Error
 * return AwsQueryResponse.error("InvalidParameterValue", "Queue name too long", null, 400);
 * }</pre>
 */
public final class AwsQueryResponse {

    private AwsQueryResponse() {}

    /**
     * Produces a {@code <ResponseMetadata>} block with a random request ID:
     * <pre>{@code
     * <ResponseMetadata><RequestId>UUID</RequestId></ResponseMetadata>
     * }</pre>
     */
    public static String responseMetadata() {
        return new XmlBuilder()
                .start("ResponseMetadata")
                  .elem("RequestId", UUID.randomUUID().toString())
                .end("ResponseMetadata")
                .build();
    }

    /**
     * Wraps a result fragment in the standard Query-protocol outer envelope:
     * <pre>{@code
     * <{action}Response xmlns="{xmlns}">
     *   <{action}Result>{result}</{action}Result>
     *   <ResponseMetadata><RequestId>UUID</RequestId></ResponseMetadata>
     * </{action}Response>
     * }</pre>
     *
     * @param action the AWS action name (e.g. {@code "CreateQueue"})
     * @param xmlns  the XML namespace URI, or {@code null} for namespace-free responses (e.g. SQS)
     * @param result the inner XML fragment placed inside the Result element
     */
    public static String envelope(String action, String xmlns, String result) {
        return new XmlBuilder()
                .start(action + "Response", xmlns)
                  .start(action + "Result")
                    .raw(result)
                  .end(action + "Result")
                  .raw(responseMetadata())
                .end(action + "Response")
                .build();
    }

    /**
     * Same as {@link #envelope} but for actions whose response element does not include
     * a {@code Result} child (e.g. {@code DeleteQueue}, {@code DeleteTopic}).
     */
    public static String envelopeNoResult(String action, String xmlns) {
        return new XmlBuilder()
                .start(action + "Response", xmlns)
                  .raw(responseMetadata())
                .end(action + "Response")
                .build();
    }

    /**
     * Same as {@link #envelope} but with an empty Result element.
     * Some services (e.g. SES) require the Result element even if it is empty.
     */
    public static String envelopeEmptyResult(String action, String xmlns) {
        return envelope(action, xmlns, "");
    }

    /**
     * Builds a Query-protocol XML error response and returns a JAX-RS {@link Response}.
     *
     * <pre>{@code
     * <ErrorResponse xmlns="{xmlns}">
     *   <Error>
     *     <Type>Sender</Type>
     *     <Code>{code}</Code>
     *     <Message>{message}</Message>
     *   </Error>
     *   <RequestId>UUID</RequestId>
     * </ErrorResponse>
     * }</pre>
     *
     * @param xmlns the namespace URI, or {@code null} for namespace-free error responses (e.g. SQS)
     */
    public static Response error(String code, String message, String xmlns, int status) {
        String type = status >= 500 ? "Receiver" : "Sender";
        return error(code, message, xmlns, status, type);
    }

    /**
     * Builds a Query-protocol XML error response with an explicit error type and returns a JAX-RS {@link Response}.
     *
     * @param xmlns the namespace URI, or {@code null} for namespace-free error responses (e.g. SQS)
     * @param type  the error type, typically "Sender" for 4xx or "Receiver" for 5xx
     */
    public static Response error(String code, String message, String xmlns, int status, String type) {
        String xml = new XmlBuilder()
                .start("ErrorResponse", xmlns)
                  .start("Error")
                    .elem("Type", type)
                    .elem("Code", code)
                    .elem("Message", message)
                  .end("Error")
                  .raw(responseMetadata())
                .end("ErrorResponse")
                .build();
        return Response.status(status).entity(xml).type(MediaType.APPLICATION_XML).build();
    }
}
