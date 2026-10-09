package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.ServicePrincipals;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the tree to {@code iam/service-linked-roles.tsv}.
 *
 * <p>The table replaced a three-entry override map in {@link IamService} and a hard-coded name in
 * each of four other services, which disagreed with it: creating the Connect role through
 * {@code CreateServiceLinkedRole} gave {@code AWSServiceRoleForConnect} while
 * {@code ConnectService} worked with {@code AWSServiceRoleForAmazonConnect}, two roles for one
 * purpose. These cases exist so a row going missing shows up here rather than as a silent fallback
 * to the derived name.
 */
class ServiceLinkedRolesTest {

    /**
     * The principals another service builds an ARN for. Each must be in the table: without a row,
     * {@link ServiceLinkedRoles#roleName} quietly derives a name instead, which is the drift the
     * table exists to end.
     */
    private static final Map<String, String> CONSUMED_BY_OTHER_SERVICES = Map.of(
            ServicePrincipals.of("connect"), "AWSServiceRoleForAmazonConnect",
            ServicePrincipals.of("guardduty"), "AWSServiceRoleForAmazonGuardDuty",
            ServicePrincipals.of("macie"), "AWSServiceRoleForAmazonMacie",
            ServicePrincipals.of("ram"), "AWSServiceRoleForResourceAccessManager");

    @Test
    void everyPrincipalAnotherServiceBuildsAnArnForIsInTheTable() {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> expected : CONSUMED_BY_OTHER_SERVICES.entrySet()) {
            if (ServiceLinkedRoles.find(expected.getKey()).isEmpty()) {
                missing.add(expected.getKey());
            }
        }
        assertTrue(missing.isEmpty(),
                "These principals are used to build a role ARN elsewhere in the tree, so a missing "
                        + "row means that service and CreateServiceLinkedRole disagree about the "
                        + "role name again: " + missing);
    }

    @Test
    void theNamesThoseServicesNeedAreTheOnesAwsMints() {
        for (Map.Entry<String, String> expected : CONSUMED_BY_OTHER_SERVICES.entrySet()) {
            assertEquals(expected.getValue(), ServiceLinkedRoles.roleName(expected.getKey()).orElseThrow(),
                    expected.getKey() + " must resolve to the name AWS mints");
        }
    }

    /** A sample of names that no capitalisation of the principal would produce. */
    @Test
    void aNameTheTableCarriesIsNotTheDerivedOne() {
        assertEquals("AWSServiceRoleForCertificateManager",
                ServiceLinkedRoles.roleName(ServicePrincipals.of("acm")).orElseThrow());
        assertEquals("AWSServiceRoleForECS",
                ServiceLinkedRoles.roleName(ServicePrincipals.of("ecs")).orElseThrow());
        assertEquals("AWSServiceRoleForLakeFormationDataAccess",
                ServiceLinkedRoles.roleName(ServicePrincipals.of("lakeformation")).orElseThrow());
        assertEquals("AWSServiceRoleForAmazonGuardDutyMalwareProtection",
                ServiceLinkedRoles.roleName(
                        ServicePrincipals.of("malware-protection.guardduty")).orElseThrow());
    }

    /** A principal the table does not cover keeps the name this emulator always derived. */
    @Test
    void anUncoveredPrincipalFallsBackToTheDerivedName() {
        assertEquals("AWSServiceRoleForUnrecordedprobe",
                ServiceLinkedRoles.roleName("unrecordedprobe.amazonaws.com").orElseThrow());
        assertTrue(ServiceLinkedRoles.roleName("").isEmpty(),
                "a principal with nothing to derive from must not become a bare prefix");
    }

    /** A legacy partition spelling reaches the same row. */
    @Test
    void aPartitionFormOfAPrincipalResolvesToTheSameRow() {
        assertEquals(ServiceLinkedRoles.roleName(ServicePrincipals.of("acm")).orElseThrow(),
                ServiceLinkedRoles.roleName("acm.amazonaws.com.cn").orElseThrow());
    }

    @Test
    void suffixSupportIsWhatWasRecordedAndPermissiveWhenNothingWas() {
        assertEquals(ServiceLinkedRoles.CustomSuffixSupport.ALLOWED,
                ServiceLinkedRoles.customSuffixSupport(ServicePrincipals.of("autoscaling")));
        assertEquals(ServiceLinkedRoles.CustomSuffixSupport.REFUSED,
                ServiceLinkedRoles.customSuffixSupport(ServicePrincipals.of("ecs")));
        assertEquals(ServiceLinkedRoles.CustomSuffixSupport.UNKNOWN,
                ServiceLinkedRoles.customSuffixSupport("unrecordedprobe.amazonaws.com"));
    }

    /**
     * Only a recorded create can establish suffix support, so a row from any other source must say
     * {@code UNKNOWN}. The generator checks this too; it is here because this is the copy that runs.
     */
    @Test
    void onlyARecordingEverClaimsSuffixSupport() {
        List<String> wrong = new ArrayList<>();
        for (ServiceLinkedRoles.Entry entry : ServiceLinkedRoles.all().values()) {
            if (!"recording".equals(entry.source())
                    && entry.customSuffix() != ServiceLinkedRoles.CustomSuffixSupport.UNKNOWN) {
                wrong.add(entry.principal() + " is " + entry.source() + " but claims "
                        + entry.customSuffix());
            }
        }
        assertTrue(wrong.isEmpty(), "suffix support can only come from a recorded create: " + wrong);
    }

    /**
     * {@link ServiceLinkedRoles#all()} promises the table's own order, which is sorted by
     * principal. Worth a case because the obvious way to make the map immutable, {@code Map.copyOf},
     * silently does not keep it, and a failure message listing rows in hash order is harder to read.
     */
    @Test
    void theTableIteratesInTheOrderTheFileHasIt() {
        List<String> actual = new ArrayList<>(ServiceLinkedRoles.all().keySet());
        List<String> sorted = new ArrayList<>(actual);
        sorted.sort(null);
        assertEquals(sorted, actual, "the table is sorted by principal, so iteration should be too");
    }

    @Test
    void theTableLoadedAndEveryNameLooksLikeAServiceLinkedRole() {
        Map<String, ServiceLinkedRoles.Entry> all = ServiceLinkedRoles.all();
        // A floor rather than the exact count, which belongs in the file's own header where the
        // generator keeps it right. The point here is that the resource loaded at all.
        assertTrue(all.size() > 100, "the table should carry the whole generated set, got " + all.size());
        List<String> malformed = new ArrayList<>();
        for (ServiceLinkedRoles.Entry entry : all.values()) {
            if (!entry.roleName().startsWith(ServiceLinkedRoles.ROLE_NAME_PREFIX)) {
                malformed.add(entry.principal() + " -> " + entry.roleName());
            }
        }
        assertTrue(malformed.isEmpty(), "every role name starts with the AWS prefix: " + malformed);
    }
}
