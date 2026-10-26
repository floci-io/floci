package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SsmPathLabelFilterIntegrationTest {

    private static final String ACCOUNT = "000000006500";
    private static final String REGION = "eu-west-3";
    private final String path = "/label-filter-" + UUID.randomUUID();
    private final Set<OwnedParameter> owned = new LinkedHashSet<>();

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void deleteOwnedParameters() {
        for (OwnedParameter parameter : owned) {
            ok(parameter.account(), parameter.region(), "DeleteParameter", Map.of("Name", parameter.name()));
        }
    }

    @Test
    void returnsOnlyLabeledParametersAndPreservesUnfilteredReads() {
        put("config", "released");
        put("unlabeled", "other");
        label("config", 1, "release");

        assertNames(byLabel("release"), "config");
        assertNames(byLabel("missing"));
        assertNames(byLabel("Release"));
        assertNames(ok("GetParametersByPath", Map.of("Path", path)), "config", "unlabeled");
    }

    @Test
    void returnsTheLabeledVersionInsteadOfTheLatestValue() {
        put("config", "released");
        label("config", 1, "release");
        JsonPath original = ok("GetParameter", Map.of("Name", path + "/config:release"));
        put("config", "not-released");

        JsonPath result = byLabel("release");
        assertEquals(List.of("released"), result.getList("Parameters.Value"));
        assertEquals(List.of(1L), result.getList("Parameters.Version", Long.class));
        assertEquals(original.getString("Parameter.ARN"), result.getString("Parameters[0].ARN"));
        assertEquals(original.getDouble("Parameter.LastModifiedDate"),
                result.getDouble("Parameters[0].LastModifiedDate"));
        assertNull(result.getString("Parameters[0].Selector"));
        assertEquals("not-released", ok("GetParameter", Map.of("Name", path + "/config"))
                .getString("Parameter.Value"));
    }

    @Test
    void followsMovedAndRemovedLabels() {
        put("config", "old");
        label("config", 1, "release");
        put("config", "new");
        label("config", 2, "release");
        assertEquals(List.of(2L), byLabel("release").getList("Parameters.Version", Long.class));

        ok("UnlabelParameterVersion", Map.of("Name", path + "/config", "ParameterVersion", 2,
                "Labels", List.of("release")));
        assertNames(byLabel("release"));
    }

    @Test
    void combinesLabelValuesAndFiltersWithoutDuplicatingAVersion() {
        put("config", "first");
        label("config", 1, "release", "stable");
        put("config", "second");
        label("config", 2, "preview");

        assertEquals(List.of(1L, 2L), byLabel("release", "stable", "preview")
                .getList("Parameters.Version", Long.class));
        JsonPath intersection = query(false, List.of(filter("Label", "release", "preview"), filter("Label", "stable")));
        assertEquals(List.of(1L), intersection.getList("Parameters.Version", Long.class));
        assertNames(query(false, List.of(filter("Label", "release"), filter("Label", "preview"))));
    }

    @Test
    void combinesLabelsWithTheExistingTypeAndKeyIdFilters() {
        put("text", "text");
        label("text", 1, "release");
        put(ACCOUNT, REGION, "secret", "secret", "SecureString", "alias/release-key");
        label("secret", 1, "release");
        put(ACCOUNT, REGION, "unlabeled", "secret", "SecureString", "alias/release-key");

        assertNames(query(false, List.of(filter("Label", "release"), filter("Type", "SecureString"))), "secret");
        assertNames(query(false, List.of(filter("Label", "release"), filter("KeyId", "alias/release-key"))), "secret");
        assertNames(query(false, List.of(filter("Label", "release"), filter("Type", "String"),
                filter("KeyId", "alias/release-key"))));
    }

    @Test
    void usesTheLabeledVersionsTypeWhenCombiningFilters() {
        put("config", "old-text");
        label("config", 1, "release");
        put(ACCOUNT, REGION, "config", "new-secret", "SecureString", "alias/release-key");

        JsonPath oldType = query(false, List.of(filter("Label", "release"), filter("Type", "String")));
        assertEquals(List.of("old-text"), oldType.getList("Parameters.Value", String.class));
        assertEquals(List.of("String"), oldType.getList("Parameters.Type", String.class));
        assertNames(query(false, List.of(filter("Label", "release"), filter("Type", "SecureString"))));
        assertNames(query(false, List.of(filter("Type", "SecureString"))), "config");
    }

    @Test
    void usesTheLabeledVersionsKeyIdWhenCombiningFilters() {
        put(ACCOUNT, REGION, "config", "old-secret", "SecureString", "alias/old-key");
        label("config", 1, "release");
        put(ACCOUNT, REGION, "config", "new-secret", "SecureString", "alias/new-key");

        JsonPath oldKey = query(false, List.of(filter("Label", "release"), filter("KeyId", "alias/old-key")));
        assertEquals(List.of("old-secret"), oldKey.getList("Parameters.Value", String.class));
        assertNames(query(false, List.of(filter("Label", "release"), filter("KeyId", "alias/new-key"))));
        assertNames(query(false, List.of(filter("KeyId", "alias/new-key"))), "config");
    }

    @Test
    void pagesDifferentLabeledVersionsOfTheSameParameter() {
        for (int version = 1; version <= 10; version++) {
            put("config", "value-" + version);
        }
        label("config", 9, "release");
        label("config", 10, "preview");
        put("unlabeled", "other");
        List<Long> versions = new ArrayList<>();
        String token = null;
        int pages = 0;
        do {
            Map<String, Object> request = new HashMap<>(Map.of("Path", path, "MaxResults", 1,
                    "ParameterFilters", List.of(filter("Label", "release", "preview"))));
            if (token != null) {
                request.put("NextToken", token);
            }
            JsonPath result = ok("GetParametersByPath", request);
            assertNames(result, "config");
            versions.addAll(result.getList("Parameters.Version", Long.class));
            token = result.getString("NextToken");
            assertTrue(++pages <= 2, "Pagination must make progress");
        } while (token != null);
        assertEquals(List.of(9L, 10L), versions);
    }

    @Test
    void resumesAfterTheCursorVersionLosesItsLabel() {
        put("config", "first");
        label("config", 1, "release");
        put("config", "second");
        label("config", 2, "preview");
        List<Map<String, Object>> filters = List.of(filter("Label", "release", "preview"));
        String token = ok("GetParametersByPath", Map.of("Path", path, "MaxResults", 1,
                "ParameterFilters", filters)).getString("NextToken");
        assertTrue(token != null, "The second labeled version needs a continuation token");
        ok("UnlabelParameterVersion", Map.of("Name", path + "/config", "ParameterVersion", 1,
                "Labels", List.of("release")));

        JsonPath next = ok("GetParametersByPath", Map.of("Path", path, "MaxResults", 1,
                "ParameterFilters", filters, "NextToken", token));
        assertEquals(List.of(2L), next.getList("Parameters.Version", Long.class));
        assertNull(next.getString("NextToken"));
    }

    @Test
    void keepsPathRegionAndAccountIsolation() {
        put("direct", "local");
        label("direct", 1, "release");
        put("nested/config", "nested");
        label("nested/config", 1, "release");
        for (String[] scope : List.of(new String[]{ACCOUNT, "ap-southeast-2"},
                new String[]{"000000006501", REGION})) {
            put(scope[0], scope[1], "foreign", "other", "String", null);
            ok(scope[0], scope[1], "LabelParameterVersion", Map.of("Name", path + "/foreign",
                    "ParameterVersion", 1, "Labels", List.of("release")));
        }
        assertNames(byLabel("release"), "direct");
        assertNames(query(true, List.of(filter("Label", "release"))), "direct", "nested/config");
    }

    private JsonPath byLabel(String... labels) {
        return query(false, List.of(filter("Label", labels)));
    }

    private JsonPath query(boolean recursive, List<Map<String, Object>> filters) {
        return ok("GetParametersByPath", Map.of("Path", path, "Recursive", recursive, "ParameterFilters", filters));
    }

    private static Map<String, Object> filter(String key, String... values) {
        return Map.of("Key", key, "Values", List.of(values));
    }

    private void label(String suffix, long version, String... labels) {
        ok("LabelParameterVersion", Map.of("Name", path + "/" + suffix, "ParameterVersion", version,
                "Labels", List.of(labels)));
    }

    private void put(String suffix, String value) {
        put(ACCOUNT, REGION, suffix, value, "String", null);
    }

    private void put(String account, String region, String suffix, String value, String type, String keyId) {
        String name = path + "/" + suffix;
        Map<String, Object> request = new HashMap<>(Map.of("Name", name, "Value", value, "Type", type,
                "Overwrite", true));
        if (keyId != null) {
            request.put("KeyId", keyId);
        }
        ok(account, region, "PutParameter", request);
        owned.add(new OwnedParameter(account, region, name));
    }

    private void assertNames(JsonPath response, String... suffixes) {
        assertEquals(List.of(suffixes).stream().map(suffix -> path + "/" + suffix).sorted().toList(),
                response.getList("Parameters.Name", String.class).stream().sorted().toList());
    }

    private JsonPath ok(String action, Map<String, ?> request) {
        return ok(ACCOUNT, REGION, action, request);
    }

    private JsonPath ok(String account, String region, String action, Map<String, ?> request) {
        return given().contentType("application/x-amz-json-1.1")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account + "/20261025/" + region
                        + "/ssm/aws4_request, SignedHeaders=host, Signature=abc")
                .header("X-Amz-Target", "AmazonSSM." + action).body(request).post("/")
                .then().statusCode(200).extract().jsonPath();
    }

    private record OwnedParameter(String account, String region, String name) {
    }
}
