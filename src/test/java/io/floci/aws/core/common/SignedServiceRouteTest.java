package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SignedServiceRouteTest {

    private final ResolvedServiceCatalog catalog = mock(ResolvedServiceCatalog.class);

    @Test
    void underMatchesTheRootAndPathsBeneathItOnly() {
        Predicate<String> plans = SignedServiceRoute.under("/malware-protection-plan");

        assertTrue(plans.test("/malware-protection-plan"));
        assertTrue(plans.test("/malware-protection-plan/abc"));
        assertFalse(plans.test("/malware-protection-planner"));
        assertFalse(plans.test("/other/malware-protection-plan"));
    }

    @Test
    void theInternalPrefixMustBeOneSegmentNoS3BucketCanBeNamed() {
        assertDoesNotThrow(() -> new SignedServiceRoute(catalog, "dlm", "/_dlm", path -> false));
        // A bucket may be named re2, so a prefix without the underscore could shadow one.
        assertThrows(IllegalArgumentException.class,
                () -> new SignedServiceRoute(catalog, "resource-explorer-2", "/re2", path -> false));
        assertThrows(IllegalArgumentException.class,
                () -> new SignedServiceRoute(catalog, "dlm", "/_dlm/policies", path -> false));
    }
}
