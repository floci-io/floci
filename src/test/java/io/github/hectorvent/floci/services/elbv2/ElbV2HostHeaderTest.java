package io.github.hectorvent.floci.services.elbv2;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ElbV2HostHeaderTest {

    @ParameterizedTest
    @CsvSource({
        "/index.html, example.test, 80, example.test",
        "/index.html, example.test:80, 80, example.test",
        "/index.html, example.test:8443, 443, example.test",
        "/index.html, example.test, 8080, example.test:8080",
        "/index.html, example.test:8080, 8080, example.test:8080",
        "/index.html, example.test:8443, 8080, example.test:8443",
        "https://absolute.example.test/index.html, example.test, 443, absolute.example.test",
        "http://absolute.example.test:8080/index.html, example.test, 80, absolute.example.test",
        "http://absolute.example.test/index.html, example.test, 8080, absolute.example.test:8080",
        "/index.html, [2001:db8::8]:8443, 443, [2001:db8::8]",
        "/index.html, [2001:db8::8], 8080, [2001:db8::8]:8080",
        "http://[2001:db8::8]:8443/index.html, example.test, 80, [2001:db8::8]"
    })
    void matchesTheDocumentedListenerAndAuthorityRules(String target, String host, int port, String expected) {
        assertEquals(expected, ElbV2HostHeader.normalize(target, host, port));
    }
}
