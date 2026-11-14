package io.floci.aws.services.elbv2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.elbv2.model.Listener;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The listener's two certificate members, as what is stored reads them. Listeners are persisted
 * through a plain {@link ObjectMapper}, which is what these round trips use, and a listener saved
 * before the default and the list were kept apart has to come back serving the same certificates.
 */
class ElbV2ListenerCertificateModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void aListenerSavedWithOneCertificateListReadsItsHeadAsTheDefault() throws Exception {
        Listener restored = MAPPER.readValue("{\"listenerArn\":\"arn:listener\","
                + "\"certificates\":[\"arn:default\",\"arn:sni\"]}", Listener.class);

        assertEquals("arn:default", restored.getDefaultCertificate());
        assertEquals(List.of("arn:sni"), restored.getCertificateList());
        assertEquals(List.of("arn:default", "arn:sni"), restored.servedCertificates());
    }

    @Test
    void aListenerSavedWithNoCertificatesCarriesNoDefault() throws Exception {
        Listener restored = MAPPER.readValue("{\"certificates\":[]}", Listener.class);

        assertNull(restored.getDefaultCertificate());
        assertTrue(restored.servedCertificates().isEmpty());
    }

    @Test
    void aListenerIsSavedWithItsDefaultAndItsListApart() throws Exception {
        Listener listener = new Listener();
        listener.setDefaultCertificate("arn:default");
        listener.setCertificateList(List.of("arn:default", "arn:sni"));

        String saved = MAPPER.writeValueAsString(listener);
        Listener restored = MAPPER.readValue(saved, Listener.class);

        assertFalse(saved.contains("\"certificates\""), "the one flat list is not written any more");
        assertEquals("arn:default", restored.getDefaultCertificate());
        assertEquals(List.of("arn:default", "arn:sni"), restored.getCertificateList());
        // A default the certificate list holds as well is served once.
        assertEquals(List.of("arn:default", "arn:sni"), restored.servedCertificates());
    }
}
