package io.floci.aws.services.iam;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vendored IAM action resource map, and the cases that justify vendoring it rather than
 * reading the resource off the request.
 */
class IamActionResourcesTest {

    @Test
    void theResourceTypeIsNotTheParameterTheActionTakes() {
        // The whole reason this is AWS's data and not a rule over the request. Each of these
        // carries a parameter naming something other than what it is authorized against.
        assertEquals(List.of("group"), IamActionResources.typesOf("AddUserToGroup"));
        assertEquals(List.of("group"), IamActionResources.typesOf("RemoveUserFromGroup"));
        assertEquals(List.of("instance-profile"),
                IamActionResources.typesOf("AddRoleToInstanceProfile"));
        assertEquals(List.of("instance-profile"),
                IamActionResources.typesOf("RemoveRoleFromInstanceProfile"));
        assertEquals(List.of("user"), IamActionResources.typesOf("EnableMFADevice"));
        assertEquals(List.of("user"), IamActionResources.typesOf("DeactivateMFADevice"));
        assertEquals(List.of("user"), IamActionResources.typesOf("ResyncMFADevice"));
    }

    @Test
    void anActionAwsAuthorizesAgainstNothingCarriesNoType() {
        assertEquals(List.of(), IamActionResources.typesOf("ListUsers"));
        assertEquals(List.of(), IamActionResources.typesOf("GetAccountSummary"));
        assertEquals(List.of(), IamActionResources.typesOf("SimulateCustomPolicy"));
    }

    @Test
    void anActionNobodyPublishesReadsTheSameWayAsOneWithNoResource() {
        assertEquals(List.of(), IamActionResources.typesOf("MakeBelieve"));
        assertEquals(List.of(), IamActionResources.typesOf(null));
        // Which is why the resource-less ones are still listed: absence is a gap, an empty list
        // is a wildcard on purpose, and only the map itself can tell them apart.
        assertTrue(IamActionResources.all().containsKey("ListUsers"));
        assertFalse(IamActionResources.all().containsKey("MakeBelieve"));
    }

    @Test
    void anActionCanBeAuthorizedAgainstSeveralTypes() {
        // These take a principal ARN, so the request names whichever type it is.
        assertEquals(List.of("group", "role", "user"),
                IamActionResources.typesOf("SimulatePrincipalPolicy"));
        assertEquals(List.of("group", "policy", "role", "user"),
                IamActionResources.typesOf("GenerateServiceLastAccessedDetails"));
    }

    @Test
    void everyTypeNamedByAnActionCarriesTheArnFormatAwsMintsItIn() {
        Map<String, List<String>> formats = IamActionResources.arnFormats();
        for (Map.Entry<String, List<String>> entry : IamActionResources.all().entrySet()) {
            for (String resourceType : entry.getValue()) {
                assertTrue(formats.containsKey(resourceType),
                        entry.getKey() + " names " + resourceType + ", which has no ARN format");
            }
        }
        assertEquals(List.of("arn:${Partition}:iam::${Account}:user/${UserNameWithPath}"),
                formats.get("user"));
    }

    @Test
    void aMapMissingItsActionsIsABuildDefect() {
        assertThrows(IllegalStateException.class, () -> parse("{\"resourceArnFormats\":{}}"));
        assertThrows(IllegalStateException.class, () -> parse("{\"actionResources\":{}}"));
    }

    @Test
    void aTypeWithNoArnFormatIsABuildDefect() {
        // The two halves have to agree, because the minting reads the format to check itself.
        assertThrows(IllegalStateException.class, () -> parse("""
                {"actionResources":{"GetUser":["user"]},
                 "resourceArnFormats":{"role":["arn:${Partition}:iam::${Account}:role/${Name}"]}}"""));
    }

    @Test
    void theReadAndListActionsAreReadOnly() {
        assertTrue(IamActionResources.isReadOnly("GetUser"));
        assertTrue(IamActionResources.isReadOnly("ListAccessKeys"));
        assertTrue(IamActionResources.isReadOnly("GenerateCredentialReport"));
        assertTrue(IamActionResources.isReadOnly("SimulatePrincipalPolicy"));
        assertFalse(IamActionResources.isReadOnly("CreateUser"));
        assertFalse(IamActionResources.isReadOnly("PutRolePolicy"));
        assertFalse(IamActionResources.isReadOnly("TagUser"));
        assertFalse(IamActionResources.isReadOnly("NotAnAction"));
    }

    @Test
    void aReadOnlyActionTheFileDoesNotCarryIsABuildDefect() {
        assertThrows(IllegalStateException.class, () -> parse("""
                {"actionResources":{"GetUser":["user"]},
                 "resourceArnFormats":{"user":["arn:${Partition}:iam::${Account}:user/${Name}"]},
                 "readOnlyActions":["ListUsers"]}"""));
    }

    @Test
    void aBlankEntryIsABuildDefect() {
        assertThrows(IllegalStateException.class, () -> parse("""
                {"actionResources":{"GetUser":[""]},
                 "resourceArnFormats":{"user":["arn:${Partition}:iam::${Account}:user/${Name}"]}}"""));
    }

    private static IamActionResources.Catalog parse(String json) throws IOException {
        return IamActionResources.parse(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }
}
