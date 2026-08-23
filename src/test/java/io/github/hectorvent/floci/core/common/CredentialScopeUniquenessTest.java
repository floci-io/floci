package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two descriptors claiming the same SigV4 credential scope is a silent bug: the catalog
 * builds its scope index with a plain map put, so the descriptor declared later in the list
 * wins and the earlier service's enablement resolution moves without any error. The
 * Bedrock control plane and bedrock-runtime share the AWS signing name {@code bedrock} and
 * hit exactly this while the control plane was being added.
 *
 * <p>A service that legitimately answers to several signing names declares them all on one
 * descriptor (mwaa: {@code airflow} + {@code mwaa}), which this check permits. What it
 * rejects is the same name on two different descriptors.
 */
@QuarkusTest
class CredentialScopeUniquenessTest {

    @Inject
    ResolvedServiceCatalog catalog;

    @Test
    void noCredentialScopeIsClaimedByTwoDescriptors() {
        Map<String, String> owner = new LinkedHashMap<>();
        Map<String, String> collisions = new TreeMap<>();

        for (ServiceDescriptor descriptor : catalog.all()) {
            for (String scope : descriptor.credentialScopes()) {
                String previous = owner.putIfAbsent(scope, descriptor.externalKey());
                if (previous != null && !previous.equals(descriptor.externalKey())) {
                    collisions.put(scope, previous + " and " + descriptor.externalKey());
                }
            }
        }

        assertTrue(collisions.isEmpty(),
                "credential scopes claimed by more than one descriptor: " + collisions);
    }
}
