package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureFlagIonEncoderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** {@code promo} has two rules and a default of off; {@code plain} is a variant flag with only a default. */
    private static final String VARIANT_FLAGS = "{\"flags\":{},\"version\":\"1\",\"values\":{"
            + "\"promo\":{\"_variants\":["
            + "{\"attributeValues\":{\"storeId\":\"uk\"},\"enabled\":true,\"name\":\"uk\",\"rule\":\"(eq $storeId \\\"uk\\\")\"},"
            + "{\"attributeValues\":{\"storeId\":\"ie\"},\"enabled\":false,\"name\":\"ie\",\"rule\":\"(eq $storeId \\\"ie\\\")\"},"
            + "{\"enabled\":false,\"name\":\"default\"}]},"
            + "\"plain\":{\"_variants\":[{\"enabled\":true,\"name\":\"default\"}]}}}";

    /**
     * The stream AWS AppConfigData sends for {@link #VARIANT_FLAGS}, in the layout of a captured AWS response: the
     * version marker, a symbol table importing AWS's {@code ops} and {@code anns} tables, then {@code promo} and
     * {@code plain}. Decoded, it reads
     * <pre>
     * promo::[uk::[(eq $storeId "uk"), "{\"_variant\":\"uk\",\"enabled\":true,\"storeId\":\"uk\"}"],
     *         ie::[(eq $storeId "ie"), "{\"_variant\":\"ie\",\"enabled\":false}"],
     *         "{\"_variant\":\"default\",\"enabled\":false}"]
     * plain::["{\"_variant\":\"default\",\"enabled\":true}"]
     * </pre>
     * where {@code eq} is symbol 13 (the {@code ops} table's fourth) and the rest are local symbols from 45.
     */
    private static final String EXPECTED_ION = "e00100ea"
            + "eebe8183deba86be99db84836f7073852101882117dc8484616e6e7385210188210c"
            + "87be9b8570726f6d6f82756b882473746f7265496482696585706c61696e"
            + "ee019d81adbe0198eebd81aebeb9c7710d712f82756b8eaf7b225f76617269616e74223a22756b222c22656e61626c6564223a747275652c2273746f72654964223a22756b227d"
            + "eeaf81b0beabc7710d712f8269658ea17b225f76617269616e74223a226965222c22656e61626c6564223a66616c73657d"
            + "8ea67b225f76617269616e74223a2264656661756c74222c22656e61626c6564223a66616c73657d"
            + "eeab81b1bea78ea57b225f76617269616e74223a2264656661756c74222c22656e61626c6564223a747275657d";

    private static final String EXPECTED_TEXT = "promo::["
            + "uk::[(eq $storeId \"uk\"), '''{\"_variant\":\"uk\",\"enabled\":true,\"storeId\":\"uk\"}'''],"
            + "ie::[(eq $storeId \"ie\"), '''{\"_variant\":\"ie\",\"enabled\":false}'''],"
            + "'''{\"_variant\":\"default\",\"enabled\":false}'''] "
            + "plain::['''{\"_variant\":\"default\",\"enabled\":true}''']";

    @Test
    void encodesVariantFlagsAsAwsDoes() {
        byte[] ion = FeatureFlagIonEncoder.encode(VARIANT_FLAGS.getBytes(StandardCharsets.UTF_8), MAPPER);

        assertArrayEquals(HexFormat.of().parseHex(EXPECTED_ION), ion);
    }

    @Test
    void readsBackAsIonWithTheSharedTablesImported() {
        byte[] ion = FeatureFlagIonEncoder.encode(VARIANT_FLAGS.getBytes(StandardCharsets.UTF_8), MAPPER);

        assertEquals(IonTestSupport.parse(EXPECTED_TEXT), IonTestSupport.decode(ion));
    }

    @Test
    void detectsVariants() {
        assertTrue(FeatureFlagIonEncoder.hasVariants(VARIANT_FLAGS.getBytes(StandardCharsets.UTF_8), MAPPER));
        assertFalse(FeatureFlagIonEncoder.hasVariants(
                "{\"values\":{\"basic\":{\"enabled\":true}}}".getBytes(StandardCharsets.UTF_8), MAPPER));
        assertFalse(FeatureFlagIonEncoder.hasVariants(
                "{\"values\":{\"variant\":{\"_variants\":[]}}}".getBytes(StandardCharsets.UTF_8), MAPPER));
        assertFalse(FeatureFlagIonEncoder.hasVariants("not json".getBytes(StandardCharsets.UTF_8), MAPPER));
    }

    @Test
    void encodesABasicFlagAsAStringOfItsRetrievalValue() {
        String mixed = "{\"values\":{"
                + "\"promo\":{\"_variants\":["
                + "{\"enabled\":true,\"name\":\"uk\",\"rule\":\"(eq $storeId \\\"uk\\\")\"},"
                + "{\"enabled\":false,\"name\":\"default\"}]},"
                + "\"basic\":{\"enabled\":true,\"limit\":5,\"_createdAt\":\"2026-01-01T00:00:00Z\"},"
                + "\"off\":{\"enabled\":false,\"limit\":5}}}";

        byte[] ion = FeatureFlagIonEncoder.encode(mixed.getBytes(StandardCharsets.UTF_8), MAPPER);

        assertEquals(IonTestSupport.parse("promo::["
                        + "uk::[(eq $storeId \"uk\"), '''{\"_variant\":\"uk\",\"enabled\":true}'''],"
                        + "'''{\"_variant\":\"default\",\"enabled\":false}'''] "
                        + "basic::'''{\"enabled\":true,\"limit\":5}''' "
                        + "off::'''{\"enabled\":false}'''"),
                IonTestSupport.decode(ion));
    }

    @Test
    void leavesRulesItCannotEncodeToTheJsonPath() {
        String other = "{\"values\":{\"f\":{\"_variants\":["
                + "{\"enabled\":true,\"name\":\"a\",\"rule\":\"(and (eq $storeId \\\"uk\\\") (eq $tier \\\"gold\\\"))\"},"
                + "{\"enabled\":false,\"name\":\"default\"}]}}}";

        assertNull(FeatureFlagIonEncoder.encode(other.getBytes(StandardCharsets.UTF_8), MAPPER));
    }

    @Test
    void refusesADocumentWithoutADefaultVariant() {
        String noDefault = "{\"values\":{\"f\":{\"_variants\":["
                + "{\"enabled\":true,\"name\":\"a\",\"rule\":\"(eq $storeId \\\"uk\\\")\"}]}}}";

        assertNull(FeatureFlagIonEncoder.encode(noDefault.getBytes(StandardCharsets.UTF_8), MAPPER));
        assertNull(FeatureFlagIonEncoder.encode("[]".getBytes(StandardCharsets.UTF_8), MAPPER));
    }

    @Test
    void recognisesTheAgentsAcceptHeader() {
        assertTrue(AppConfigDataService.acceptsFeatureFlagIon(
                "application/ion;type=AWS.AppConfig.FeatureFlags;q=1.0,*/*;q=0.1"));
        assertTrue(AppConfigDataService.acceptsFeatureFlagIon("application/ion; type=AWS.AppConfig.FeatureFlags"));
        assertTrue(AppConfigDataService.acceptsFeatureFlagIon("text/plain, Application/ION;Type=aws.appconfig.featureflags"));
        assertFalse(AppConfigDataService.acceptsFeatureFlagIon("application/ion;type=AWS.AppConfig.FeatureFlags;q=0"));
        assertFalse(AppConfigDataService.acceptsFeatureFlagIon("application/ion;type=AWS.AppConfig.FeatureFlags;q=0.0000"));
        assertTrue(AppConfigDataService.acceptsFeatureFlagIon("application/ion;type=AWS.AppConfig.FeatureFlags;q=0.5"));
        assertFalse(AppConfigDataService.acceptsFeatureFlagIon("application/ion;type=Other"));
        assertFalse(AppConfigDataService.acceptsFeatureFlagIon("application/ion"));
        assertFalse(AppConfigDataService.acceptsFeatureFlagIon("application/json"));
        assertFalse(AppConfigDataService.acceptsFeatureFlagIon("*/*"));
        assertFalse(AppConfigDataService.acceptsFeatureFlagIon(null));
    }
}
