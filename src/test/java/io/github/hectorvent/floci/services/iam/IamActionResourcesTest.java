package io.github.hectorvent.floci.services.iam;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
    void everyCreateIsAuthorizedAgainstExactlyOneResourceType() {
        // A create has nothing to read the resource off, so ResourceArnBuilder mints the ARN from
        // the type AWS gives the action. One type is what lets it mint without being told which,
        // and two would leave it picking. Iterated rather than restated: a create added to the
        // resolver that AWS gives no single type falls back to the wildcard, which is the
        // permissive answer and a silent one, so this is what says it out loud.
        for (String create : ResourceArnBuilder.CREATE_PARAMETERS.keySet()) {
            assertEquals(1, IamActionResources.typesOf(create).size(),
                    create + " is no longer authorized against exactly one resource type, so "
                            + "there is no single ARN for it to mint: "
                            + IamActionResources.typesOf(create));
        }
    }

    @Test
    void anArnSpellsTheResourceTypeInFrontOfTheName() {
        // What minting assumes. ResourceArnBuilder builds `:<type>/<name>`, which holds only
        // while AWS spells that type's ARN the same way, and the reference publishes the format,
        // so the assumption is checked rather than carried.
        for (Map.Entry<String, List<String>> entry : IamActionResources.arnFormats().entrySet()) {
            for (String arnFormat : entry.getValue()) {
                assertTrue(arnFormat.contains(":" + entry.getKey() + "/"),
                        "AWS spells a " + entry.getKey() + " ARN as " + arnFormat
                                + ", which this resolver's minting does not match");
            }
        }
    }

    @Test
    void everyResourceTypeInUseIsOneTheResolverCoversOrDeclaresUnresolved() {
        // Read off the resolver's own tables rather than restated, so a type cannot be waved
        // through by adding it to a list in this file. The resolver answers `*` for a type it
        // does not know, which is the permissive answer and a silent one.
        Set<String> covered = new TreeSet<>(ResourceArnBuilder.NAME_PARAMETERS.keySet());
        covered.addAll(ResourceArnBuilder.UNRESOLVED_TYPES.keySet());
        for (String action : ResourceArnBuilder.IAM_ARN_PARAMETERS.keySet()) {
            covered.addAll(IamActionResources.typesOf(action));
        }
        for (String action : ResourceArnBuilder.CREATE_PARAMETERS.keySet()) {
            covered.addAll(IamActionResources.typesOf(action));
        }
        for (String action : ResourceArnBuilder.DERIVED_RESOLVERS.keySet()) {
            covered.addAll(IamActionResources.typesOf(action));
        }
        for (Map.Entry<String, List<String>> entry : IamActionResources.all().entrySet()) {
            for (String resourceType : entry.getValue()) {
                assertTrue(covered.contains(resourceType), "AWS authorizes " + entry.getKey()
                        + " against '" + resourceType + "', which ResourceArnBuilder neither "
                        + "resolves nor lists as unresolved, so the action falls back to *");
            }
        }
    }

    @Test
    void theResourceTypesInUseAreTheOnesTheResolverKnowsAbout() {
        // The upstream half of the same question: which types AWS uses at all. The test above
        // catches a type nothing resolves; this one catches AWS changing the set, including a
        // type disappearing, which leaves a dead entry behind rather than a gap.
        Set<String> inUse = new TreeSet<>();
        IamActionResources.all().values().forEach(inUse::addAll);
        assertEquals(Set.of(
                        // Named by a parameter the resolver reads.
                        "user", "role", "group", "instance-profile", "server-certificate",
                        // Named by an ARN the request carries, or minted by a create.
                        "policy", "mfa", "sms-mfa", "saml-provider", "oidc-provider",
                        // Only on actions the handler does not dispatch.
                        "access-report", "delegation-request", "role-template"),
                inUse,
                "AWS has changed which resource types IAM actions are authorized against; "
                        + "teach ResourceArnBuilder the new one or list it as unresolved");
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
