package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every IAM namespace Floci serves whose actions the Service Reference gives resource types is one the vendored
 * table of request resources reads, one whose resources {@code ResourceArnBuilder} builds by hand, or one the table
 * names as not read yet, so a service Floci adds is not left checking its resources on {@code *} unnoticed.
 */
@QuarkusTest
class RequestResourceNamespacesIntegrationTest {

    @Inject
    ResolvedServiceCatalog catalog;

    @Test
    void everyNamespaceFlociServesWithResourceTypesIsReadBuiltByHandOrNamedAsNotReadYet() {
        Set<String> served = new TreeSet<>();
        for (ServiceDescriptor descriptor : catalog.all()) {
            for (String scope : descriptor.credentialScopes()) {
                served.add(catalog.canonicalCredentialScope(scope));
            }
        }
        Set<String> unread = new TreeSet<>(served);
        unread.retainAll(RequestResourceMembers.table().otherNamespaces());
        assertEquals(Set.of(), unread, "add these to NAMESPACES or NOT_COVERED in tools/aws/regen_iam_request_resources.py");
    }
}
