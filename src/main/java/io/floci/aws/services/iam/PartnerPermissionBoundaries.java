package io.floci.aws.services.iam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The permissions boundaries AWS registers for a delegation partner out of band, held here so a
 * request can name one that resolves to a document.
 *
 * <p>The partner guide says of the long-term half of temporary delegation that it "limit[s] the
 * permissions of any roles that are created or managed through temporary access. Long-term
 * permissions are implemented as IAM permissions boundaries", and those boundaries are registered
 * by emailing AWS rather than through any API, under ARNs of the published shape
 * {@code arn:aws:iam::partner:policy/permission_boundary/<partner_domain>/<policy_name>_<date>},
 * which carries the word {@code partner} where an account id goes. So no caller can create one and
 * no account holds one, and a request naming a boundary names a document this emulator has no way
 * to know. This reads those documents from a file instead, which is the one deviation the feature
 * needs: AWS has no API for it either.
 *
 * <p>Unset, which is the default, means no boundary is registered, so a delegation request carries
 * none and the role it creates takes none, which is the account that never onboarded. Set, the file
 * holds a JSON object of partner account id to an object of boundary ARN to policy document, which
 * is the shape of the registration it stands in for: AWS registers a boundary for a partner, and
 * {@code RolePermissionRestrictionArns} is an output of the request rather than an input to it, so
 * a request filed by a registered partner is the one that carries its boundaries.
 */
record PartnerPermissionBoundaries(Map<String, List<String>> arnsByPartner,
                                   Map<String, String> documentsByArn) {

    private static final Logger LOG = Logger.getLogger(PartnerPermissionBoundaries.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Nothing registered, which is what every deployment that configures no file holds. */
    static final PartnerPermissionBoundaries NONE =
            new PartnerPermissionBoundaries(Map.of(), Map.of());

    /**
     * What the configured file registers, or {@link #NONE} when no file is configured. A file that
     * cannot be read registers nothing rather than failing startup: an unreadable registration is
     * the same situation as an account that never onboarded, and the emulator has to keep serving
     * the rest of IAM either way, so it is logged and carried on from.
     */
    static PartnerPermissionBoundaries load(Optional<String> configuredPath) {
        String path = configuredPath.filter(value -> !value.isBlank()).orElse(null);
        if (path == null) {
            return NONE;
        }
        try {
            Map<String, Map<String, Object>> declared = MAPPER.readValue(Files.readString(Path.of(path)),
                    new TypeReference<Map<String, Map<String, Object>>>() {});
            Map<String, List<String>> arnsByPartner = new LinkedHashMap<>();
            Map<String, String> documentsByArn = new LinkedHashMap<>();
            declared.forEach((partnerAccountId, boundaries) -> {
                List<String> arns = new ArrayList<>();
                boundaries.forEach((arn, declaredDocument) -> {
                    String document = documentOf(declaredDocument);
                    if (document != null) {
                        arns.add(arn);
                        documentsByArn.put(arn, document);
                    }
                });
                if (!arns.isEmpty()) {
                    arnsByPartner.put(partnerAccountId, List.copyOf(arns));
                }
            });
            LOG.infov("Registered {0} partner permission boundaries for {1} partner accounts from {2}",
                    documentsByArn.size(), arnsByPartner.size(), path);
            return new PartnerPermissionBoundaries(Map.copyOf(arnsByPartner), Map.copyOf(documentsByArn));
        } catch (IOException | RuntimeException failure) {
            // The trace goes with it: a missing file and a corrupt one both end here, and the
            // operator is left reading every partner as unregistered either way.
            LOG.warn("Reading partner permission boundaries from " + path
                    + " failed, so none is registered", failure);
            return NONE;
        }
    }

    /** The boundaries registered for a partner account, in the order the file names them. */
    List<String> arnsFor(String partnerAccountId) {
        return partnerAccountId == null ? List.of()
                : arnsByPartner.getOrDefault(partnerAccountId, List.of());
    }

    /**
     * The document an entry holds, whether the file writes it as a JSON object or as a string
     * carrying one, and null for an entry holding neither, which is dropped rather than registered.
     */
    private static String documentOf(Object declared) {
        if (declared == null) {
            return null;
        }
        if (declared instanceof String text) {
            return text.isBlank() ? null : text;
        }
        try {
            return MAPPER.writeValueAsString(declared);
        } catch (JsonProcessingException failure) {
            // Unreachable for a tree Jackson itself parsed, and an entry nobody can write back is
            // one nobody can resolve, so it is dropped with the reason named.
            LOG.warnv("A registered partner permission boundary could not be written back: {0}",
                    failure.getMessage());
            return null;
        }
    }
}
