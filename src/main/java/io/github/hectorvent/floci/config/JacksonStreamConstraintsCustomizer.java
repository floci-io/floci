package io.github.hectorvent.floci.config;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

/**
 * Raises Jackson's default {@code StreamReadConstraints.maxStringLength} from the built-in 20 MB
 * to a value derived from {@code floci.services.s3.max-object-size-mb} (default 512 MB).
 *
 * <p>Jackson 2.15+ introduced a hard cap of 20 000 000 characters per string token
 * ({@link StreamReadConstraints#DEFAULT_MAX_STRING_LEN}). When the AWS SDK sends large binary
 * payloads whose chunked-encoding wrapper is processed inside
 * {@code S3Controller#decodeAwsChunked}, or when any other request path routes a large body
 * through the Quarkus-managed Jackson reader, the cap causes Jackson to throw a
 * {@code StreamConstraintsException}, which in turn surfaces as HTTP 413 Content Too Large.
 *
 * <p>This customizer scopes the raised limit exclusively to the CDI-managed
 * {@link ObjectMapper} (via its {@link com.fasterxml.jackson.core.JsonFactory}).
 * It intentionally does <em>not</em> call
 * {@link StreamReadConstraints#overrideDefaultStreamReadConstraints} so that other
 * {@code ObjectMapper} instances in the JVM (e.g. the ones inside {@code HybridStorage},
 * {@code PersistentStorage}, or {@code AwsJsonController}) keep their own, unrelated
 * defaults and are not silently affected by the S3 upload size setting.
 *
 * <p><strong>Unit note:</strong> {@code maxStringLength} is a <em>character</em> count, not a
 * byte count. The conversion {@code maxObjectSizeMb * 1024 * 1024} assumes one character per
 * byte, which holds for the ASCII chunked-encoding headers and payloads sent by the AWS SDK.
 * Multi-byte UTF-8 sequences would consume fewer character slots than bytes, so this is a
 * conservative (safe) upper bound.
 */
@Singleton
public class JacksonStreamConstraintsCustomizer implements ObjectMapperCustomizer {

    private static final Logger LOG = Logger.getLogger(JacksonStreamConstraintsCustomizer.class);

    private final EmulatorConfig config;

    @Inject
    public JacksonStreamConstraintsCustomizer(EmulatorConfig config) {
        this.config = config;
    }

    @Override
    public void customize(ObjectMapper objectMapper) {
        int maxMb = config.services().s3().maxObjectSizeMb();
        // maxStringLength is a character count. We treat 1 MB = 1024 * 1024 characters,
        // which is a safe upper bound for the ASCII chunked-encoding content the AWS SDK sends.
        // Guard against overflow: Integer.MAX_VALUE is ~2 GB, well above any sane upload size.
        long maxChars = (long) maxMb * 1024L * 1024L;
        int maxCharsInt = (int) Math.min(maxChars, Integer.MAX_VALUE);

        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxStringLength(maxCharsInt)
                .build();

        objectMapper.getFactory().setStreamReadConstraints(constraints);

        LOG.infov("Jackson maxStringLength set to {0} MB ({1} characters) on the Quarkus-managed ObjectMapper",
                maxMb, maxCharsInt);
    }
}
