package io.floci.aws.testing;

import io.floci.aws.testutil.AwsRequestSigner;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * REST requests signed as a caller and sent to the test's Floci, and the answer IAM gives one whose caller lacks the
 * permission, for the IAM tests of a REST service, whose requests are checked on the routes its model gives.
 */
public final class SignedRestRequests {

    private SignedRestRequests() {
    }

    /** Sends {@code body}, none when null, as {@code contentType} to {@code path} under {@code endpoint}. */
    public static Response send(URI endpoint, AwsRequestSigner signer, String method, String path, String body,
                                String contentType) throws Exception {
        return send(endpoint, signer, method, path, body, contentType, Map.of());
    }

    /** As above; with {@code headers} signed and sent beside the signer's own. */
    public static Response send(URI endpoint, AwsRequestSigner signer, String method, String path, String body,
                                String contentType, Map<String, String> headers) throws Exception {
        URI uri = endpoint.resolve(path);
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        // The signer signs the extra headers but returns only its own, so the request carries both.
        RequestSpecification request = given().urlEncodingEnabled(false).headers(headers)
                .headers(signer.headersFor(method, uri, headers, bytes));
        if (body != null) {
            request = request.contentType(contentType).body(bytes);
        }
        return request.request(method, uri);
    }

    /** IAM refused the request with a 403 naming the action the caller lacks. */
    public static void denied(Response response, String action) {
        response.then().statusCode(403).body(containsString("not authorized to perform: " + action));
    }
}
