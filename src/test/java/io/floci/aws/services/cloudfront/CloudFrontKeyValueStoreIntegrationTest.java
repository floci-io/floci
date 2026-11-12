package io.floci.aws.services.cloudfront;

import io.floci.aws.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.xml.HasXPath.hasXPath;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** CloudFront key value stores: the control plane under /2020-05-31 and the cloudfront-keyvaluestore data plane. */
@QuarkusTest
class CloudFrontKeyValueStoreIntegrationTest {

    private static final String CLOUDFRONT = "/2020-05-31/";
    private static final String DATA_PLANE = "/key-value-stores/";
    private static final String XML = "application/xml";
    private static final String NS = "http://cloudfront.amazonaws.com/doc/2020-05-31/";
    private static final String FUNCTION_CODE = "function handler(event) { return event.request; }";
    private static final String SIGV4A_AUTHORIZATION = "AWS4-ECDSA-P256-SHA256 "
            + "Credential=000000000000/20260101/cloudfront-keyvaluestore/aws4_request, "
            + "SignedHeaders=host;x-amz-date, Signature=00";

    private record Store(String name, String arn, String etag, String dataEtag) {}

    private static String uniqueName() {
        return "kvs-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Store createStore(String name, String comment) {
        Response created = given().contentType(XML)
                .body("<CreateKeyValueStoreRequest xmlns=\"%s\"><Name>%s</Name><Comment>%s</Comment></CreateKeyValueStoreRequest>"
                        .formatted(NS, name, comment))
                .when().post(CLOUDFRONT + "key-value-store");
        created.then().statusCode(201);
        String xml = created.asString();
        String arn = XmlParser.extractFirst(xml, "ARN", null);
        Response described = given().urlEncodingEnabled(false).header("Authorization", SIGV4A_AUTHORIZATION)
                .when().get(DATA_PLANE + encoded(arn));
        described.then().statusCode(200);
        return new Store(name, arn, created.header("ETag"), described.header("ETag"));
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String storePath(Store store) {
        return DATA_PLANE + encoded(store.arn());
    }

    private static RequestSpecification dataPlane() {
        return given().urlEncodingEnabled(false).header("Authorization", SIGV4A_AUTHORIZATION);
    }

    private static Response putKey(Store store, String etag, String key, String value) {
        return dataPlane().header("If-Match", etag).contentType("application/json")
                .body("{\"Value\":\"%s\"}".formatted(value))
                .when().put(storePath(store) + "/keys/" + encoded(key));
    }

    private static String createFunction(String name, String runtime, String kvsArn) {
        String associations = kvsArn == null ? ""
                : "<KeyValueStoreAssociations><Quantity>1</Quantity><Items><KeyValueStoreAssociation>"
                + "<KeyValueStoreARN>" + kvsArn + "</KeyValueStoreARN>"
                + "</KeyValueStoreAssociation></Items></KeyValueStoreAssociations>";
        return """
                <CreateFunctionRequest xmlns="%s">
                  <Name>%s</Name>
                  <FunctionConfig><Comment>kvs</Comment><Runtime>%s</Runtime>%s</FunctionConfig>
                  <FunctionCode>%s</FunctionCode>
                </CreateFunctionRequest>
                """.formatted(NS, name, runtime, associations,
                Base64.getEncoder().encodeToString(FUNCTION_CODE.getBytes(StandardCharsets.UTF_8)));
    }

    // ── Control plane ─────────────────────────────────────────────────────────

    @Test
    void controlPlaneCreatesDescribesListsUpdatesAndDeletes() {
        String name = uniqueName();

        Response created = given().contentType(XML)
                .body("<CreateKeyValueStoreRequest xmlns=\"%s\"><Name>%s</Name><Comment>first</Comment></CreateKeyValueStoreRequest>"
                        .formatted(NS, name))
                .when().post(CLOUDFRONT + "key-value-store");
        created.then()
                .statusCode(201)
                .header("ETag", notNullValue())
                .header("Location", containsString("/key-value-store/" + name))
                .body(hasXPath("local-name(/*)", equalTo("KeyValueStore")))
                .body(hasXPath("namespace-uri(/*)", equalTo(NS)))
                .body(hasXPath("/*[local-name()='KeyValueStore']/*[local-name()='Status']", equalTo("READY")));
        String arn = XmlParser.extractFirst(created.asString(), "ARN", null);
        assertNotNull(arn);
        assertEquals(true, arn.matches("arn:aws:cloudfront::\\d{12}:key-value-store/[0-9a-f-]{36}"));

        Response described = given().when().get(CLOUDFRONT + "key-value-store/" + name);
        described.then().statusCode(200).header("ETag", equalTo(created.header("ETag")));
        assertEquals(arn, XmlParser.extractFirst(described.asString(), "ARN", null));

        given().when().get(CLOUDFRONT + "key-value-store")
                .then().statusCode(200)
                .body(hasXPath("local-name(/*)", equalTo("KeyValueStoreList")))
                .body(hasXPath("//*[local-name()='KeyValueStore']/*[local-name()='Name'][text()='" + name + "']"));

        Response updated = given().contentType(XML).header("If-Match", created.header("ETag"))
                .body("<UpdateKeyValueStoreRequest xmlns=\"%s\"><Comment>second</Comment></UpdateKeyValueStoreRequest>"
                        .formatted(NS))
                .when().put(CLOUDFRONT + "key-value-store/" + name);
        updated.then().statusCode(200);
        assertNotEquals(created.header("ETag"), updated.header("ETag"));
        assertEquals("second", XmlParser.extractFirst(updated.asString(), "Comment", null));

        given().header("If-Match", updated.header("ETag"))
                .when().delete(CLOUDFRONT + "key-value-store/" + name)
                .then().statusCode(204);
        given().when().get(CLOUDFRONT + "key-value-store/" + name).then().statusCode(404);
    }

    @Test
    void controlPlaneErrorsUseTheModeledCodes() {
        Store store = createStore(uniqueName(), "errors");

        given().contentType(XML)
                .body("<CreateKeyValueStoreRequest xmlns=\"%s\"><Name>%s</Name></CreateKeyValueStoreRequest>"
                        .formatted(NS, store.name()))
                .when().post(CLOUDFRONT + "key-value-store")
                .then().statusCode(409).body(hasXPath("//*[local-name()='Code']", equalTo("EntityAlreadyExists")));
        given().when().get(CLOUDFRONT + "key-value-store/does-not-exist")
                .then().statusCode(404).body(hasXPath("//*[local-name()='Code']", equalTo("EntityNotFound")));
        given().contentType(XML)
                .body("<CreateKeyValueStoreRequest xmlns=\"%s\"><Name>not valid!</Name></CreateKeyValueStoreRequest>"
                        .formatted(NS))
                .when().post(CLOUDFRONT + "key-value-store")
                .then().statusCode(400).body(hasXPath("//*[local-name()='Code']", equalTo("InvalidArgument")));
        given().header("If-Match", "stale")
                .when().delete(CLOUDFRONT + "key-value-store/" + store.name())
                .then().statusCode(400).body(hasXPath("//*[local-name()='Code']", equalTo("InvalidIfMatchVersion")));
    }

    // ── Data plane ────────────────────────────────────────────────────────────

    @Test
    void dataPlaneRoundTripsKeysThroughAPercentEncodedArn() {
        Store store = createStore(uniqueName(), "data plane");

        Response put = putKey(store, store.dataEtag(), "demo.example.com", "3-5-19-1234");
        put.then().statusCode(200)
                .body("ItemCount", equalTo(1))
                .body("TotalSizeInBytes", equalTo("demo.example.com".length() + "3-5-19-1234".length()))
                .header("ETag", notNullValue());
        assertNotEquals(store.dataEtag(), put.header("ETag"));

        dataPlane().when().get(storePath(store) + "/keys/" + encoded("demo.example.com"))
                .then().statusCode(200)
                .body("Key", equalTo("demo.example.com"))
                .body("Value", equalTo("3-5-19-1234"))
                .body("ItemCount", equalTo(1));

        dataPlane().when().get(storePath(store))
                .then().statusCode(200)
                .body("KvsARN", equalTo(store.arn()))
                .body("ItemCount", equalTo(1))
                .body("Status", equalTo("READY"))
                .body("Created", notNullValue())
                .header("ETag", equalTo(put.header("ETag")));

        dataPlane().header("If-Match", put.header("ETag"))
                .when().delete(storePath(store) + "/keys/" + encoded("demo.example.com"))
                .then().statusCode(200).body("ItemCount", equalTo(0));
        dataPlane().when().get(storePath(store) + "/keys/" + encoded("demo.example.com"))
                .then().statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    @Test
    void aKeyMayContainSlashesAndSpaces() {
        Store store = createStore(uniqueName(), "odd keys");
        String key = "tenant/a b?c";

        putKey(store, store.dataEtag(), key, "v1").then().statusCode(200);

        dataPlane().when().get(storePath(store) + "/keys/" + encoded(key))
                .then().statusCode(200).body("Key", equalTo(key)).body("Value", equalTo("v1"));
    }

    @Test
    void dataPlaneWritesNeedTheCurrentEtag() {
        Store store = createStore(uniqueName(), "etag");
        Response first = putKey(store, store.dataEtag(), "a", "1");
        first.then().statusCode(200);

        putKey(store, store.dataEtag(), "b", "2").then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("ValidationException"));
        dataPlane().contentType("application/json").body("{\"Value\":\"2\"}")
                .when().put(storePath(store) + "/keys/b")
                .then().statusCode(400)
                .header("X-Amzn-Errortype", equalTo("ValidationException"));
        putKey(store, first.header("ETag"), "b", "2").then().statusCode(200).body("ItemCount", equalTo(2));
    }

    @Test
    void updateKeysRejectsMoreThanFiftyEntriesAndAPutWithoutAValue() {
        Store store = createStore(uniqueName(), "limits");

        StringBuilder puts = new StringBuilder();
        for (int i = 0; i < 51; i++) {
            puts.append(i == 0 ? "" : ",").append("{\"Key\":\"k").append(i).append("\",\"Value\":\"v\"}");
        }
        dataPlane().header("If-Match", store.dataEtag()).contentType("application/json")
                .body("{\"Puts\":[" + puts + "]}")
                .when().post(storePath(store) + "/keys")
                .then().statusCode(400).header("X-Amzn-Errortype", equalTo("ValidationException"));

        dataPlane().header("If-Match", store.dataEtag()).contentType("application/json")
                .body("{\"Puts\":[{\"Key\":\"a\",\"Value\":\"1\"},{\"Key\":\"b\"}]}")
                .when().post(storePath(store) + "/keys")
                .then().statusCode(400).header("X-Amzn-Errortype", equalTo("ValidationException"));

        dataPlane().when().get(storePath(store))
                .then().statusCode(200).body("ItemCount", equalTo(0)).header("ETag", equalTo(store.dataEtag()));
    }

    @Test
    void controlPlaneAndDataPlaneEtagsAreIndependent() {
        Store store = createStore(uniqueName(), "etags");

        putKey(store, store.dataEtag(), "a", "1").then().statusCode(200);
        Response updated = given().contentType(XML).header("If-Match", store.etag())
                .body("<UpdateKeyValueStoreRequest xmlns=\"%s\"><Comment>changed</Comment></UpdateKeyValueStoreRequest>"
                        .formatted(NS))
                .when().put(CLOUDFRONT + "key-value-store/" + store.name());
        updated.then().statusCode(200);

        Response written = dataPlane().when().get(storePath(store));
        putKey(store, written.header("ETag"), "b", "2").then().statusCode(200).body("ItemCount", equalTo(2));
        given().header("If-Match", updated.header("ETag"))
                .when().delete(CLOUDFRONT + "key-value-store/" + store.name())
                .then().statusCode(204);
    }

    @Test
    void createWithAnImportSourceIsRejectedInsteadOfCreatingAnEmptyStore() {
        String name = uniqueName();
        given().contentType(XML)
                .body(("<CreateKeyValueStoreRequest xmlns=\"%s\"><Name>%s</Name><ImportSource><SourceType>S3</SourceType>"
                        + "<SourceARN>arn:aws:s3:::bucket/keys.json</SourceARN></ImportSource></CreateKeyValueStoreRequest>")
                        .formatted(NS, name))
                .when().post(CLOUDFRONT + "key-value-store")
                .then().statusCode(400).body(hasXPath("//*[local-name()='Code']", equalTo("InvalidArgument")));
        given().when().get(CLOUDFRONT + "key-value-store/" + name).then().statusCode(404);
    }

    @Test
    void deletingAStoreDropsItsTags() {
        String name = uniqueName();
        Response created = given().contentType(XML)
                .body(("<CreateKeyValueStoreRequest xmlns=\"%s\"><Name>%s</Name></CreateKeyValueStoreRequest>")
                        .formatted(NS, name))
                .when().post(CLOUDFRONT + "key-value-store");
        created.then().statusCode(201);
        String arn = XmlParser.extractFirst(created.asString(), "ARN", null);
        given().contentType(XML).queryParam("Operation", "Tag").queryParam("Resource", arn)
                .body("<Tags xmlns=\"%s\"><Items><Tag><Key>team</Key><Value>a</Value></Tag></Items></Tags>".formatted(NS))
                .when().post(CLOUDFRONT + "tagging").then().statusCode(204);

        given().header("If-Match", created.header("ETag"))
                .when().delete(CLOUDFRONT + "key-value-store/" + name).then().statusCode(204);

        given().queryParam("Resource", arn).when().get(CLOUDFRONT + "tagging")
                .then().statusCode(200).body(not(containsString("team")));
    }
    @Test
    void malformedKeyWritesAreValidationErrorsAndChangeNothing() {
        Store store = createStore(uniqueName(), "shapes");
        Response seeded = putKey(store, store.dataEtag(), "a", "1");
        seeded.then().statusCode(200);
        String etag = seeded.header("ETag");

        String[] bodies = {"{\"Value\":{}}", "{\"Value\":5}"};
        for (String body : bodies) {
            dataPlane().header("If-Match", etag).contentType("application/json").body(body)
                    .when().put(storePath(store) + "/keys/a")
                    .then().statusCode(400).header("X-Amzn-Errortype", equalTo("ValidationException"));
        }
        String[] batches = {"{\"Puts\":[{\"Key\":\"a\",\"Value\":5}]}", "{\"Puts\":[{\"Key\":7,\"Value\":\"v\"}]}",
                "{\"Deletes\":\"a\"}", "{\"Puts\":{\"Key\":\"a\",\"Value\":\"v\"}}", "[]"};
        for (String body : batches) {
            dataPlane().header("If-Match", etag).contentType("application/json").body(body)
                    .when().post(storePath(store) + "/keys")
                    .then().statusCode(400).header("X-Amzn-Errortype", equalTo("ValidationException"));
        }

        dataPlane().when().get(storePath(store) + "/keys/a").then().statusCode(200).body("Value", equalTo("1"));
        dataPlane().when().get(storePath(store)).then().statusCode(200).header("ETag", equalTo(etag));
    }

    @Test
    void listKeyValueStoresHonoursTheStatusFilter() {
        Store store = createStore(uniqueName(), "status");

        given().queryParam("Status", "READY").when().get(CLOUDFRONT + "key-value-store")
                .then().statusCode(200).body(containsString(store.name()));
        given().queryParam("Status", "PENDING").when().get(CLOUDFRONT + "key-value-store")
                .then().statusCode(200).body(not(containsString(store.name())));
    }
    @Test
    void aMalformedMaxResultsIsAValidationError() {
        Store store = createStore(uniqueName(), "page");

        dataPlane().queryParam("MaxResults", "abc").when().get(storePath(store) + "/keys")
                .then().statusCode(400).header("X-Amzn-Errortype", equalTo("ValidationException"));
    }

    @Test
    void anImportSourceWithoutASourceArnIsRejectedToo() {
        String name = uniqueName();
        given().contentType(XML)
                .body(("<CreateKeyValueStoreRequest xmlns=\"%s\"><Name>%s</Name><ImportSource><SourceType>S3"
                        + "</SourceType></ImportSource></CreateKeyValueStoreRequest>").formatted(NS, name))
                .when().post(CLOUDFRONT + "key-value-store")
                .then().statusCode(400).body(hasXPath("//*[local-name()='Code']", equalTo("InvalidArgument")));
        given().when().get(CLOUDFRONT + "key-value-store/" + name).then().statusCode(404);
    }

    @Test
    void updateKeyValueStoreWithoutACommentIsRejected() {
        Store store = createStore(uniqueName(), "kept");

        given().contentType(XML).header("If-Match", store.etag())
                .body("<UpdateKeyValueStoreRequest xmlns=\"%s\"></UpdateKeyValueStoreRequest>".formatted(NS))
                .when().put(CLOUDFRONT + "key-value-store/" + store.name())
                .then().statusCode(400);
        given().when().get(CLOUDFRONT + "key-value-store/" + store.name())
                .then().statusCode(200).body(hasXPath("//*[local-name()='Comment']", equalTo("kept")));
    }
    @Test
    void updateKeysAppliesPutsAndDeletesAtOnce() {
        Store store = createStore(uniqueName(), "batch");
        Response seeded = dataPlane().header("If-Match", store.dataEtag()).contentType("application/json")
                .body("{\"Puts\":[{\"Key\":\"a\",\"Value\":\"1\"},{\"Key\":\"b\",\"Value\":\"2\"}]}")
                .when().post(storePath(store) + "/keys");
        seeded.then().statusCode(200).body("ItemCount", equalTo(2));

        dataPlane().header("If-Match", seeded.header("ETag")).contentType("application/json")
                .body("{\"Puts\":[{\"Key\":\"c\",\"Value\":\"3\"}],\"Deletes\":[{\"Key\":\"a\"}]}")
                .when().post(storePath(store) + "/keys")
                .then().statusCode(200).body("ItemCount", equalTo(2));

        dataPlane().when().get(storePath(store) + "/keys")
                .then().statusCode(200)
                .body("Items.Key", contains("b", "c"));
    }

    @Test
    void listKeysPagesInKeyOrder() {
        Store store = createStore(uniqueName(), "paging");
        Response seeded = dataPlane().header("If-Match", store.dataEtag()).contentType("application/json")
                .body("{\"Puts\":[{\"Key\":\"c\",\"Value\":\"3\"},{\"Key\":\"a\",\"Value\":\"1\"},"
                        + "{\"Key\":\"b\",\"Value\":\"2\"}]}")
                .when().post(storePath(store) + "/keys");
        seeded.then().statusCode(200);

        Response first = dataPlane().queryParam("MaxResults", 2).when().get(storePath(store) + "/keys");
        first.then().statusCode(200).body("Items", hasSize(2)).body("Items.Key", contains("a", "b"));
        String token = first.jsonPath().getString("NextToken");
        assertNotNull(token);

        dataPlane().queryParam("MaxResults", 2).queryParam("NextToken", token)
                .when().get(storePath(store) + "/keys")
                .then().statusCode(200)
                .body("Items.Key", contains("c"))
                .body("NextToken", nullValue());

        dataPlane().queryParam("MaxResults", 51).when().get(storePath(store) + "/keys")
                .then().statusCode(400).header("X-Amzn-Errortype", equalTo("ValidationException"));
    }

    @Test
    void keyAndValueSizeLimitsAreValidationErrors() {
        Store store = createStore(uniqueName(), "limits");

        putKey(store, store.dataEtag(), "k".repeat(513), "v").then().statusCode(400)
                .header("X-Amzn-Errortype", equalTo("ValidationException"));
        putKey(store, store.dataEtag(), "k", "v".repeat(1025)).then().statusCode(400)
                .header("X-Amzn-Errortype", equalTo("ValidationException"));
        putKey(store, store.dataEtag(), "k".repeat(512), "v".repeat(1024)).then().statusCode(200);
    }

    @Test
    void anUnknownStoreArnIsResourceNotFound() {
        String arn = "arn:aws:cloudfront::000000000000:key-value-store/00000000-0000-0000-0000-000000000000";

        dataPlane().when().get(DATA_PLANE + encoded(arn))
                .then().statusCode(404).header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    // ── Functions ─────────────────────────────────────────────────────────────

    @Test
    void aFunctionCanBeAssociatedWithAStoreAndTheStoreIsProtectedWhileInUse() {
        Store store = createStore(uniqueName(), "in use");
        String functionName = "fn-" + UUID.randomUUID().toString().substring(0, 8);

        Response function = given().contentType(XML).body(createFunction(functionName, "cloudfront-js-2.0", store.arn()))
                .when().post(CLOUDFRONT + "function");
        function.then().statusCode(201);
        assertEquals(store.arn(), XmlParser.extractFirst(function.asString(), "KeyValueStoreARN", null));

        given().when().get(CLOUDFRONT + "function/" + functionName + "/describe")
                .then().statusCode(200)
                .body(hasXPath("//*[local-name()='KeyValueStoreAssociation']/*[local-name()='KeyValueStoreARN']",
                        equalTo(store.arn())));
        given().when().get(CLOUDFRONT + "function")
                .then().statusCode(200)
                .body(hasXPath("//*[local-name()='KeyValueStoreARN'][text()='" + store.arn() + "']"));

        given().header("If-Match", store.etag())
                .when().delete(CLOUDFRONT + "key-value-store/" + store.name())
                .then().statusCode(409)
                .body(hasXPath("//*[local-name()='Code']", equalTo("CannotDeleteEntityWhileInUse")));

        given().header("If-Match", function.header("ETag"))
                .when().delete(CLOUDFRONT + "function/" + functionName)
                .then().statusCode(204);
        given().header("If-Match", store.etag())
                .when().delete(CLOUDFRONT + "key-value-store/" + store.name())
                .then().statusCode(204);
    }

    @Test
    void aFunctionCannotUseAnUnknownStoreOrTheFirstRuntime() {
        Store store = createStore(uniqueName(), "runtime");
        String unknownArn = "arn:aws:cloudfront::000000000000:key-value-store/00000000-0000-0000-0000-000000000000";

        given().contentType(XML)
                .body(createFunction("fn-" + UUID.randomUUID().toString().substring(0, 8), "cloudfront-js-2.0", unknownArn))
                .when().post(CLOUDFRONT + "function")
                .then().statusCode(404).body(hasXPath("//*[local-name()='Code']", equalTo("EntityNotFound")));
        given().contentType(XML)
                .body(createFunction("fn-" + UUID.randomUUID().toString().substring(0, 8), "cloudfront-js-1.0", store.arn()))
                .when().post(CLOUDFRONT + "function")
                .then().statusCode(400).body(hasXPath("//*[local-name()='Code']", equalTo("InvalidArgument")));
    }
}
