package io.floci.aws.core.common;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;

import java.net.URLDecoder;
import java.nio.charset.Charset;

/** Percent decoding shared by Query action extraction and IAM resource/context resolution. */
public final class QueryFormDecoder {

    private QueryFormDecoder() {
    }

    public static String decode(String value, Charset charset, ContainerRequestContext ctx) {
        try {
            return URLDecoder.decode(value, charset);
        } catch (IllegalArgumentException invalidEncoding) {
            String message = "The form body contains an invalid percent escape.";
            boolean ec2 = Ec2ErrorResponses.isEc2QueryRequest(ctx, false);
            Response response = ec2 ? Ec2ErrorResponses.of("MalformedQueryString", message, 404)
                    : AwsQueryResponse.error("MalformedQueryString", message, null, 404);
            throw new WebApplicationException(invalidEncoding, response);
        }
    }
}
