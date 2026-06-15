package io.github.hectorvent.floci.services.cognito;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CognitoStandardAttributesTest {

    private static final Set<String> EXPECTED_NAMES = Set.of(
            "sub", "name", "given_name", "family_name", "middle_name", "nickname",
            "preferred_username", "profile", "picture", "website", "email",
            "email_verified", "gender", "birthdate", "zoneinfo", "locale",
            "phone_number", "phone_number_verified", "address", "updated_at");

    @Test
    void defaultsContainsAllTwentyStandardAttributes() {
        assertEquals(20, CognitoStandardAttributes.DEFAULTS.size());
        Set<String> names = names(CognitoStandardAttributes.DEFAULTS);
        assertEquals(EXPECTED_NAMES, names);
    }

    @Test
    void subIsRequiredAndImmutable() {
        Map<String, Object> sub = findByName(CognitoStandardAttributes.DEFAULTS, "sub");
        assertEquals("String", sub.get("AttributeDataType"));
        assertEquals(Boolean.TRUE, sub.get("Required"));
        assertEquals(Boolean.FALSE, sub.get("Mutable"));
        @SuppressWarnings("unchecked")
        Map<String, String> constraints = (Map<String, String>) sub.get("StringAttributeConstraints");
        assertEquals("1", constraints.get("MinLength"));
        assertEquals("2048", constraints.get("MaxLength"));
    }

    @Test
    void emailHasStringConstraints() {
        Map<String, Object> email = findByName(CognitoStandardAttributes.DEFAULTS, "email");
        assertEquals("String", email.get("AttributeDataType"));
        assertEquals(Boolean.FALSE, email.get("Required"));
        assertEquals(Boolean.TRUE, email.get("Mutable"));
        @SuppressWarnings("unchecked")
        Map<String, String> constraints = (Map<String, String>) email.get("StringAttributeConstraints");
        assertEquals("0", constraints.get("MinLength"));
        assertEquals("2048", constraints.get("MaxLength"));
    }

    @Test
    void birthdateHasTenCharacterConstraint() {
        Map<String, Object> birthdate = findByName(CognitoStandardAttributes.DEFAULTS, "birthdate");
        @SuppressWarnings("unchecked")
        Map<String, String> constraints = (Map<String, String>) birthdate.get("StringAttributeConstraints");
        assertEquals("10", constraints.get("MinLength"));
        assertEquals("10", constraints.get("MaxLength"));
    }

    @Test
    void updatedAtIsNumberType() {
        Map<String, Object> updatedAt = findByName(CognitoStandardAttributes.DEFAULTS, "updated_at");
        assertEquals("Number", updatedAt.get("AttributeDataType"));
        @SuppressWarnings("unchecked")
        Map<String, String> constraints = (Map<String, String>) updatedAt.get("NumberAttributeConstraints");
        assertNotNull(constraints);
        assertEquals("0", constraints.get("MinValue"));
    }

    @Test
    void emailVerifiedAndPhoneVerifiedAreBooleanType() {
        for (String name : List.of("email_verified", "phone_number_verified")) {
            Map<String, Object> attr = findByName(CognitoStandardAttributes.DEFAULTS, name);
            assertEquals("Boolean", attr.get("AttributeDataType"), name + " should be Boolean");
            assertFalse(attr.containsKey("StringAttributeConstraints"), name + " should have no string constraints");
        }
    }

    @Test
    void noDeveloperOnlyAttributeInStandardAttrs() {
        for (Map<String, Object> attr : CognitoStandardAttributes.DEFAULTS) {
            assertEquals(Boolean.FALSE, attr.get("DeveloperOnlyAttribute"),
                    attr.get("Name") + " should not be developer-only");
        }
    }

    // ── merge() ──────────────────────────────────────────────────────────────

    @Test
    void mergeWithNullReturnsAllDefaults() {
        List<Map<String, Object>> result = CognitoStandardAttributes.merge(null);
        assertEquals(20, result.size());
        assertEquals(EXPECTED_NAMES, names(result));
    }

    @Test
    void mergeWithEmptyListReturnsAllDefaults() {
        List<Map<String, Object>> result = CognitoStandardAttributes.merge(List.of());
        assertEquals(20, result.size());
        assertEquals(EXPECTED_NAMES, names(result));
    }

    @Test
    void mergeAppendsCustomAttributeAfterStandardOnes() {
        List<Map<String, Object>> schema = List.of(
                Map.of("Name", "custom:department", "AttributeDataType", "String"));

        List<Map<String, Object>> result = CognitoStandardAttributes.merge(schema);

        assertEquals(21, result.size());
        assertTrue(names(result).contains("custom:department"));
        assertTrue(names(result).containsAll(EXPECTED_NAMES));
        assertEquals("custom:department", result.get(20).get("Name"),
                "custom attribute must come after all standard ones");
    }

    @Test
    void mergeWithMultipleCustomAttributesAppendsAllInOrder() {
        List<Map<String, Object>> schema = List.of(
                Map.of("Name", "custom:tenant_id", "AttributeDataType", "String"),
                Map.of("Name", "custom:role", "AttributeDataType", "String"));

        List<Map<String, Object>> result = CognitoStandardAttributes.merge(schema);

        assertEquals(22, result.size());
        assertEquals("custom:tenant_id", result.get(20).get("Name"));
        assertEquals("custom:role", result.get(21).get("Name"));
    }

    @Test
    void mergeWithExplicitStandardAttributeOverridesDefault() {
        Map<String, Object> override = Map.of(
                "Name", "email",
                "AttributeDataType", "String",
                "Required", Boolean.TRUE,
                "Mutable", Boolean.TRUE);

        List<Map<String, Object>> result = CognitoStandardAttributes.merge(List.of(override));

        assertEquals(20, result.size(), "override must not create a duplicate");
        Map<String, Object> emailAttr = findByName(result, "email");
        assertEquals(Boolean.TRUE, emailAttr.get("Required"),
                "email Required should reflect the override");
    }

    @Test
    void mergePreservesStandardAttributeOrder() {
        List<Map<String, Object>> result = CognitoStandardAttributes.merge(null);
        // sub must be first
        assertEquals("sub", result.get(0).get("Name"));
        // updated_at must be last among standard attrs (index 19)
        assertEquals("updated_at", result.get(19).get("Name"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Set<String> names(List<Map<String, Object>> attrs) {
        return attrs.stream().map(a -> (String) a.get("Name")).collect(Collectors.toSet());
    }

    private static Map<String, Object> findByName(List<Map<String, Object>> attrs, String name) {
        return attrs.stream()
                .filter(a -> name.equals(a.get("Name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("attribute not found: " + name));
    }
}
