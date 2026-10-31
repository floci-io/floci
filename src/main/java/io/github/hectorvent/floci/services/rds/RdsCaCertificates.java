package io.github.hectorvent.floci.services.rds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.VendoredResources;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Each Region's RDS certificate authorities as {@code DescribeCertificates} reports them, vendored in
 * {@code aws/rds-ca-certificates.json} by {@code tools/aws/regen_rds_ca_certificates.py} from the
 * certificate bundles AWS publishes. A Region with no vendored entry, such as a custom one, uses
 * us-east-1's.
 */
final class RdsCaCertificates {

    /** The CA AWS gives a new instance when the account sets no override. */
    static final String DEFAULT_FOR_NEW_LAUNCHES = "rds-ca-rsa2048-g1";
    // The vendored file always has this entry (parse refuses one without it).
    private static final String FALLBACK_REGION = "us-east-1"; // partition-literal: a key into the vendored data
    private static final Map<String, List<CaCertificate>> BY_REGION = VendoredResources.read(
            "aws/rds-ca-certificates.json", "RDS CA certificates", in -> parse(new ObjectMapper().readTree(in)));

    record CaCertificate(String identifier, String thumbprint, Instant validFrom, Instant validTill) {}

    private RdsCaCertificates() {
    }

    /** The Region's CAs in identifier order. */
    static List<CaCertificate> forRegion(String region) {
        List<CaCertificate> certificates = BY_REGION.get(region);
        return certificates != null ? certificates : BY_REGION.get(FALLBACK_REGION);
    }

    /** The Region's CA named {@code identifier}, which AWS matches without regard to case. */
    static Optional<CaCertificate> find(String region, String identifier) {
        return forRegion(region).stream()
                .filter(certificate -> certificate.identifier().equalsIgnoreCase(identifier))
                .findFirst();
    }

    private static Map<String, List<CaCertificate>> parse(JsonNode doc) {
        Map<String, List<CaCertificate>> byRegion = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> region : doc.path("regions").properties()) {
            List<CaCertificate> certificates = new ArrayList<>();
            for (JsonNode certificate : region.getValue()) {
                certificates.add(new CaCertificate(certificate.path("certificateIdentifier").asText(),
                        certificate.path("thumbprint").asText(),
                        Instant.parse(certificate.path("validFrom").asText()),
                        Instant.parse(certificate.path("validTill").asText())));
            }
            byRegion.put(region.getKey(), List.copyOf(certificates));
        }
        if (!byRegion.containsKey(FALLBACK_REGION)) {
            throw new IllegalStateException("aws/rds-ca-certificates.json has no " + FALLBACK_REGION + " entry");
        }
        return Map.copyOf(byRegion);
    }
}
