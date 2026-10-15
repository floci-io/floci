package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VendoredResourcesTest {

    private static final String PRESENT = "aws/iam-authorized-actions.json";

    @Test
    void aPresentResourceIsHandedToTheParser() {
        int firstByte = VendoredResources.read(PRESENT, "test map", in -> in.read());

        assertEquals('{', firstByte);
    }

    @Test
    void aMissingResourceIsABuildDefect() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> VendoredResources.read("aws/absent.json", "test map", in -> in.read()));

        assertEquals("test map resource not found: aws/absent.json", e.getMessage());
    }

    @Test
    void anUnreadableResourceKeepsItsCause() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> VendoredResources.read(PRESENT, "test map", in -> {
                    throw new IOException("truncated");
                }));

        assertThat(e.getMessage(), containsString(PRESENT));
        assertThat(e.getCause(), instanceOf(IOException.class));
    }
}
