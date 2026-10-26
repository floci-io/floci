package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every IAM namespace Floci serves whose operations the Service Reference lists with {@code iam:PassRole} is
 * one the vendored table of passed roles reads, or one it names as not read yet, so a service Floci adds is
 * not left passing roles unchecked.
 */
@QuarkusTest
class PassedRoleNamespacesIntegrationTest {

    @Inject
    ResolvedServiceCatalog catalog;

    @Test
    void everyNamespaceFlociServesThatPassesARoleIsReadOrNamedAsNotReadYet() {
        Set<String> served = new TreeSet<>();
        for (ServiceDescriptor descriptor : catalog.all()) {
            for (String scope : descriptor.credentialScopes()) {
                served.add(catalog.canonicalCredentialScope(scope));
            }
        }
        Set<String> unread = new TreeSet<>(served);
        unread.retainAll(PassedRoleMembers.table().otherNamespaces());
        assertEquals(Set.of(), unread, "add these to NAMESPACES or NOT_COVERED in tools/aws/regen_iam_passed_roles.py");
    }
}
