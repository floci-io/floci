package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainNamesTest {

    @Test
    void aDomainIsWithinItselfAndHoldsItsSubdomains() {
        assertTrue(DomainNames.isWithin("example.com", "example.com"));
        assertTrue(DomainNames.isWithin("app.example.com", "example.com"));
        assertTrue(DomainNames.isWithin("a.b.example.com", "example.com"));
    }

    @Test
    void aSharedSuffixWithoutALabelBoundaryIsNotASubdomain() {
        assertFalse(DomainNames.isWithin("badexample.com", "example.com"));
        assertFalse(DomainNames.isWithin("example.com", "app.example.com"));
        assertFalse(DomainNames.isWithin("EXAMPLE.com", "example.com"), "names are compared as given");
    }
}
