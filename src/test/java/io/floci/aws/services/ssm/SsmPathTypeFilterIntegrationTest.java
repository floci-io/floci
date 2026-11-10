package io.floci.aws.services.ssm;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SsmPathTypeFilterIntegrationTest {

    private static final String REGION = "eu-west-3";
    private final List<OwnedParameter> owned = new ArrayList<>();
    private String path;

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createParameters() {
        path = "/type-filter-" + UUID.randomUUID();
        put("text", "String", REGION);
        put("list", "StringList", REGION);
        put("secret", "SecureString", REGION);
        put("nested/text", "String", REGION);
        put("foreign", "StringList", "ap-southeast-2");
    }

    @AfterEach
    void deleteOwnedParameters() {
        for (OwnedParameter parameter : owned) {
            ssm("DeleteParameter", Map.of("Name", parameter.name()), parameter.region()).then().statusCode(200);
        }
    }

    @ParameterizedTest
    @MethodSource("typeFilters")
    void appliesTypeFiltersWithinTheRequestedPathAndRegion(List<Map<String, Object>> filters, List<String> expected) {
        Response response = ssm("GetParametersByPath", Map.of("Path", path, "ParameterFilters", filters), REGION)
                .then().statusCode(200).extract().response();
        assertNames(response, expected);
    }

    static Stream<Arguments> typeFilters() {
        return Stream.of(
                Arguments.of(List.of(filter(null, "StringList")), List.of("list")),
                Arguments.of(List.of(filter("Equals", "String")), List.of("text")),
                Arguments.of(List.of(filter("BeginsWith", "String")), List.of("list", "text")),
                Arguments.of(List.of(filter("Equals", "StringList", "SecureString")), List.of("list", "secret")),
                Arguments.of(List.of(filter("BeginsWith", "String"), filter("Equals", "StringList")), List.of("list")),
                Arguments.of(List.of(filter("Equals", "String"), filter("Equals", "StringList")), List.of()));
    }

    @Test
    void preservesUnfilteredOneLevelResults() {
        Response response = ssm("GetParametersByPath", Map.of("Path", path), REGION)
                .then().statusCode(200).extract().response();
        assertNames(response, List.of("list", "secret", "text"));
    }

    @Test
    void combinesTypeWithRecursivePathSelection() {
        Response response = ssm("GetParametersByPath", Map.of("Path", path + "/", "Recursive", true,
                        "ParameterFilters", List.of(filter(null, "String"))), REGION)
                .then().statusCode(200).extract().response();
        assertNames(response, List.of("nested/text", "text"));
    }

    @Test
    void rejectsAnInvalidTypeOptionEvenForAnEmptyPath() {
        Response response = ssm("GetParametersByPath", Map.of("Path", path + "/empty",
                        "ParameterFilters", List.of(filter("Contains", "String"))), REGION)
                .then().statusCode(400).extract().response();
        assertTrue(response.jsonPath().getString("__type").endsWith("InvalidFilterOption"));
    }

    @Test
    void rejectsEmptyTypeValuesEvenForAnEmptyPath() {
        Response response = ssm("GetParametersByPath", Map.of("Path", path + "/empty",
                        "ParameterFilters", List.of(filter(null))), REGION)
                .then().statusCode(400).extract().response();
        assertTrue(response.jsonPath().getString("__type").endsWith("InvalidFilterValue"));
    }

    @ParameterizedTest
    @MethodSource("keyIdFilters")
    void appliesKeyIdAndCombinedFilters(List<Map<String, Object>> filters, List<String> expected) {
        putKeyedParameter("key-one", "alias/key-one");
        putKeyedParameter("key-two", "alias/key-two");
        Response response = ssm("GetParametersByPath", Map.of("Path", path, "ParameterFilters", filters), REGION)
                .then().statusCode(200).extract().response();
        assertNames(response, expected);
    }

    static Stream<Arguments> keyIdFilters() {
        return Stream.of(
                Arguments.of(List.of(filterForKey("KeyId", null, "alias/aws/ssm")), List.of("secret")),
                Arguments.of(List.of(filterForKey("KeyId", "Equals", "alias/key-one")), List.of("key-one")),
                Arguments.of(List.of(filterForKey("KeyId", "BeginsWith", "alias/key-")), List.of("key-one", "key-two")),
                Arguments.of(List.of(filterForKey("KeyId", null, "alias/aws/ssm", "alias/key-two")),
                        List.of("secret", "key-two")),
                Arguments.of(List.of(filter(null, "String"), filterForKey("KeyId", null, "alias/key-one")), List.of()),
                Arguments.of(List.of(filter(null, "SecureString"), filterForKey("KeyId", null, "alias/key-one")),
                        List.of("key-one")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Name", "Path", "Tier", "DataType", "tag:env", "Unknown", "missing", "null"})
    void rejectsInvalidFilterKeysBeforeReturningPathResults(String key) {
        Map<String, Object> invalidFilter = new HashMap<>();
        if (!"missing".equals(key)) {
            invalidFilter.put("Key", "null".equals(key) ? null : key);
        }
        invalidFilter.put("Values", List.of("text"));
        for (String requestedPath : List.of(path, path + "/empty")) {
            Response response = ssm("GetParametersByPath", Map.of("Path", requestedPath,
                            "ParameterFilters", List.of(invalidFilter)), REGION)
                    .then().statusCode(400).extract().response();
            assertTrue(response.jsonPath().getString("__type").endsWith("InvalidFilterKey"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"KeyId", "Label"})
    void rejectsInvalidOptionsForOtherAllowedKeys(String key) {
        Response response = ssm("GetParametersByPath", Map.of("Path", path + "/empty",
                        "ParameterFilters", List.of(filterForKey(key, "Contains", "value"))), REGION)
                .then().statusCode(400).extract().response();
        assertTrue(response.jsonPath().getString("__type").endsWith("InvalidFilterOption"));
        assertEquals("The specified filter option isn't valid. Valid options are Equals and BeginsWith. "
                        + "For Path filter, valid options are Recursive and OneLevel.",
                response.jsonPath().getString("message"));
    }

    @Test
    void rejectsBeginsWithForLabelFilters() {
        Response response = ssm("GetParametersByPath", Map.of("Path", path + "/empty",
                        "ParameterFilters", List.of(filterForKey("Label", "BeginsWith", "value"))), REGION)
                .then().statusCode(400).extract().response();
        assertTrue(response.jsonPath().getString("__type").endsWith("InvalidFilterOption"));
        assertEquals("The specified filter option isn't valid. Valid options are Equals and BeginsWith. "
                        + "For Path filter, valid options are Recursive and OneLevel.",
                response.jsonPath().getString("message"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"KeyId", "Label"})
    void rejectsEmptyValuesForOtherAllowedKeys(String key) {
        Response response = ssm("GetParametersByPath", Map.of("Path", path + "/empty",
                        "ParameterFilters", List.of(filterForKey(key, null))), REGION)
                .then().statusCode(400).extract().response();
        assertTrue(response.jsonPath().getString("__type").endsWith("InvalidFilterValue"));
    }

    private void putKeyedParameter(String suffix, String keyId) {
        String name = path + "/" + suffix;
        ssm("PutParameter", Map.of("Name", name, "Type", "SecureString", "Value", "value", "KeyId", keyId), REGION)
                .then().statusCode(200);
        owned.add(new OwnedParameter(name, REGION));
    }

    private static Map<String, Object> filter(String option, String... values) {
        return filterForKey("Type", option, values);
    }

    private static Map<String, Object> filterForKey(String key, String option, String... values) {
        return option == null ? Map.of("Key", key, "Values", List.of(values))
                : Map.of("Key", key, "Option", option, "Values", List.of(values));
    }

    private void put(String suffix, String type, String region) {
        String name = path + "/" + suffix;
        ssm("PutParameter", Map.of("Name", name, "Type", type, "Value", "a,b"), region).then().statusCode(200);
        owned.add(new OwnedParameter(name, region));
    }

    private void assertNames(Response response, List<String> suffixes) {
        List<String> names = response.jsonPath().getList("Parameters.Name", String.class);
        assertEquals(suffixes.stream().map(suffix -> path + "/" + suffix).sorted().toList(),
                names.stream().sorted().toList());
    }

    private Response ssm(String action, Map<String, ?> request, String region) {
        return given().contentType("application/x-amz-json-1.1")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20261022/" + region + "/ssm/aws4_request")
                .header("X-Amz-Target", "AmazonSSM." + action).body(request).post("/");
    }

    private record OwnedParameter(String name, String region) {
    }
}
