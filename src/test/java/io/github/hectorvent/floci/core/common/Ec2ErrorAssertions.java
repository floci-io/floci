package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testutil.AwsRequestSigner;

import java.net.URI;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

final class Ec2ErrorAssertions {

    private Ec2ErrorAssertions() {
    }

    static void assertGetError(AwsRequestSigner signer, URI endpoint, boolean presigned,
                               int expectedStatus, String expectedCode, String expectedMessage) throws Exception {
        Map<String, String> query = Map.of("Action", "DescribeVpcs", "Version", "2016-11-15");
        if (presigned) {
            given().urlEncodingEnabled(false)
                    .get(signer.presignedUri("GET", endpoint, query, new byte[0])).then()
                    .statusCode(expectedStatus).body("Response.Errors.Error.Code", equalTo(expectedCode))
                    .body("Response.Errors.Error.Message", startsWith(expectedMessage))
                    .body("Response.RequestID", not(emptyOrNullString()));
        } else {
            given().filter(signer).queryParams(query).get(endpoint).then()
                    .statusCode(expectedStatus).body("Response.Errors.Error.Code", equalTo(expectedCode))
                    .body("Response.Errors.Error.Message", startsWith(expectedMessage))
                    .body("Response.RequestID", not(emptyOrNullString()));
        }
    }
}
