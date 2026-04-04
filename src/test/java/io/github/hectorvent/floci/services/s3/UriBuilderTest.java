package io.github.hectorvent.floci.services.s3;

import jakarta.ws.rs.core.UriBuilder;
import org.junit.jupiter.api.Test;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.assertEquals;

class UriBuilderTest {
    @Test
    void testUriBuilder() {
        URI uri = URI.create("http://host/?delete");
        URI newUri = UriBuilder.fromUri(uri).replacePath("/b/").build();
        System.out.println("NEW URI: " + newUri.toString());
        System.out.println("NEW URI QUERY: " + newUri.getQuery());
    }
}
