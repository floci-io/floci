package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

import java.net.URI;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class DynamoDbListTablesAuthorizationIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    @ParameterizedTest
    @CsvSource({
            "arn:aws:dynamodb:us-east-1:000000000000:table/*,false,true",
            "arn:aws:dynamodb:us-east-1:000000000000:table/*,true,false",
            "arn:aws:dynamodb:us-east-1:000000000000:table/orders,false,false",
            "arn:aws:dynamodb:us-east-1:000000000000:table/orders,true,true",
            "arn:aws:dynamodb:us-east-1:000000000000:table/orders-*,false,false",
            "arn:aws:dynamodb:us-east-1:000000000000:table/orders-*,true,true",
            "arn:aws:dynamodb:us-east-1:000000000000:table/,false,false",
            "arn:aws:dynamodb:us-east-1:000000000000:table/,true,true",
            "arn:aws:dynamodb:us-east-1:000000000000:table/?,false,true",
            "arn:aws:dynamodb:us-east-1:000000000000:table/?,true,false",
            "arn:aws:dynamodb:us-east-1:000000000000:*,false,true",
            "arn:aws:dynamodb:us-east-1:000000000000:*,true,false",
            "arn:aws:dynamodb:eu-central-1:000000000000:table/*,false,false",
            "arn:aws:dynamodb:eu-central-1:000000000000:table/*,true,true",
            "arn:aws:dynamodb:us-east-1:123400009876:table/*,false,false",
            "arn:aws:dynamodb:us-east-1:123400009876:table/*,true,true",
            "arn:aws:dynamodb:*:*:table/*,false,true",
            "arn:aws:dynamodb:*:*:table/*,true,false",
            "arn:aws:dynamodb:us-east-1:000000000000:table/*/index/*,false,false",
            "arn:aws:dynamodb:us-east-1:000000000000:table/*/index/*,true,true",
            "*,false,true",
            "*,true,false"
    })
    void signedSdkUsesTheRegionalTableCollection(String resource, boolean explicitDeny, boolean allowed)
            throws Exception {
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "list-tables-", "list",
                     policy(resource, explicitDeny));
             DynamoDbClient client = DynamoDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(StaticCredentialsProvider.create(
                             AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()))).build()) {
            if (allowed) {
                assertEquals(200, client.listTables(request -> request.limit(1)).sdkHttpResponse().statusCode());
            } else {
                DynamoDbException denied = assertThrows(DynamoDbException.class,
                        () -> client.listTables(request -> request.limit(1)));
                assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"json,false", "json,true", "cbor,false", "cbor,true"})
    void ignoredTableSelectorsCannotRedirectCollectionAuthorization(String protocol, boolean explicitDeny)
            throws Exception {
        boolean cbor = "cbor".equals(protocol);
        ObjectMapper mapper = cbor ? new ObjectMapper(new CBORFactory()) : new ObjectMapper();
        URI uri = cbor ? endpoint.resolve("/service/DynamoDB_20120810/operation/ListTables") : endpoint;
        String contentType = cbor ? "application/cbor" : "application/x-amz-json-1.0";
        Map<String, String> headers = cbor ? Map.of("content-type", contentType, "accept", contentType)
                : Map.of("content-type", contentType, "x-amz-target", "DynamoDB_20120810.ListTables");
        byte[] body = mapper.writeValueAsBytes(Map.of("Limit", 1, "ExclusiveStartTableName", "page-start",
                "TableName", "decoy", "ResourceArn", "arn:aws:dynamodb:us-east-1:000000000000:table/decoy"));
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "list-selectors-", "list",
                policy("arn:aws:dynamodb:us-east-1:000000000000:table/*", explicitDeny))) {
            Response response = given().filter(user.signer("dynamodb")).headers(headers).body(body).post(uri);
            assertEquals(explicitDeny ? (cbor ? 403 : 400) : 200, response.statusCode(), response.asString());
            if (explicitDeny) {
                assertTrue(mapper.readTree(response.asByteArray()).path("__type").asText()
                        .contains("AccessDeniedException"));
            }
        }
    }

    private static String policy(String resource, boolean explicitDeny) {
        String statement = "{\"Effect\":\"%s\",\"Action\":\"dynamodb:ListTables\",\"Resource\":\"%s\"}";
        return "{\"Version\":\"2012-10-17\",\"Statement\":["
                + (explicitDeny ? statement.formatted("Allow", "*") + "," : "")
                + statement.formatted(explicitDeny ? "Deny" : "Allow", resource) + "]}";
    }
}
