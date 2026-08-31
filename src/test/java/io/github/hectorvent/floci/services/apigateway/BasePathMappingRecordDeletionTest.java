package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.core.common.AwsException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Deleting a mapping by the record a caller already holds must remove that record and no other.
 *
 * <p>The v2 endpoints select a mapping by an id derived from the path it is stored under, then
 * delete it. Going back through the normalising lookup would turn a record stored as "/" or "" —
 * which state written before writes were canonicalised can still hold — into the canonical root,
 * deleting the wrong record and leaving the selected one behind.
 */
@QuarkusTest
class BasePathMappingRecordDeletionTest {

    private static final String REGION = "us-east-1";

    @Inject
    ApiGatewayService service;

    private String domainWithRootMapping(String domainName) {
        service.createDomainName(REGION, Map.of("domainName", domainName));
        service.createBasePathMapping(REGION, domainName,
                Map.of("restApiId", "api123", "stage", "prod"));
        return domainName;
    }

    @Test
    void deletingByARootLikePathDoesNotRemoveTheCanonicalRecord() {
        String domain = domainWithRootMapping("record-deletion.example.com");

        // No record is stored under "/", so this must find nothing rather than normalise its way
        // onto the canonical root.
        AwsException e = assertThrows(AwsException.class,
                () -> service.deleteBasePathMappingRecord(REGION, domain, "/"));
        assertEquals("NotFoundException", e.getErrorCode());

        assertNotNull(service.getBasePathMapping(REGION, domain, "(none)"),
                "the canonical root mapping was deleted by a request for another record");
    }

    @Test
    void deletingByTheStoredPathRemovesThatRecord() {
        String domain = domainWithRootMapping("record-deletion-2.example.com");

        service.deleteBasePathMappingRecord(REGION, domain, "(none)");

        assertThrows(AwsException.class, () -> service.getBasePathMapping(REGION, domain, "(none)"));
    }
}
