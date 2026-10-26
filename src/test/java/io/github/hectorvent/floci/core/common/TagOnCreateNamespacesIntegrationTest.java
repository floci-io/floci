package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every IAM namespace Floci serves whose operations the Service Reference lists with a tagging action is one
 * the vendored tag-on-create table reads, or one it names as not read yet, so a service Floci adds does not
 * tag what it creates unchecked.
 */
@QuarkusTest
class TagOnCreateNamespacesIntegrationTest {

    @Inject
    ResolvedServiceCatalog catalog;

    @Test
    void everyNamespaceFlociServesThatTagsOnCreationIsReadOrNamedAsNotReadYet() {
        Set<String> served = new TreeSet<>();
        for (ServiceDescriptor descriptor : catalog.all()) {
            for (String scope : descriptor.credentialScopes()) {
                served.add(catalog.canonicalCredentialScope(scope));
            }
        }
        Set<String> unread = new TreeSet<>(served);
        unread.retainAll(TagOnCreateMembers.table().otherNamespaces());
        assertEquals(Set.of(), unread, "add these to NAMESPACES or NOT_COVERED in tools/aws/regen_iam_tag_on_create.py");
    }
}
