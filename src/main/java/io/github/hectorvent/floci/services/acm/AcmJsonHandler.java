package io.github.hectorvent.floci.services.acm;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.services.acm.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.*;

/**
 * JSON handler for AWS Certificate Manager (ACM) API operations.
 *
 * <p>Implements the AWS JSON 1.1 protocol for ACM operations including
 * certificate request, import, export, listing, and lifecycle management.</p>
 *
 * @see <a href="https://docs.aws.amazon.com/acm/latest/APIReference/Welcome.html">AWS ACM API Reference</a>
 */
@ApplicationScoped
public class AcmJsonHandler {

    private static final Logger LOG = Logger.getLogger(AcmJsonHandler.class);

    private final AcmService service;
    private final ObjectMapper objectMapper;

    @Inject
    public AcmJsonHandler(AcmService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "RequestCertificate" -> handleRequestCertificate(request, region);
            case "DescribeCertificate" -> handleDescribeCertificate(request, region);
            case "GetCertificate" -> handleGetCertificate(request, region);
            case "ListCertificates" -> handleListCertificates(request, region);
            case "DeleteCertificate" -> handleDeleteCertificate(request, region);
            case "ImportCertificate" -> handleImportCertificate(request, region);
            case "ExportCertificate" -> handleExportCertificate(request, region);
            case "AddTagsToCertificate" -> handleAddTagsToCertificate(request, region);
            case "ListTagsForCertificate" -> handleListTagsForCertificate(request, region);
            case "RemoveTagsFromCertificate" -> handleRemoveTagsFromCertificate(request, region);
            case "GetAccountConfiguration" -> handleGetAccountConfiguration(request, region);
            case "PutAccountConfiguration" -> handlePutAccountConfiguration(request, region);
            default -> Response.status(400)
                .entity(new AwsErrorResponse("UnsupportedOperation", "Operation " + action + " is not supported."))
                .build();
        };
    }

    private Response handleRequestCertificate(JsonNode request, String region) {
        String domainName = request.path("DomainName").asText(null);
        if (domainName == null || domainName.isBlank()) {
            return Response.status(400)
                .entity(new AwsErrorResponse("ValidationException",
                    "Value null at 'domainName' failed to satisfy constraint: Member must not be null"))
                .build();
        }
        List<String> sans = parseStringList(request.path("SubjectAlternativeNames"));
        ValidationMethod validationMethod = parseValidationMethod(request.path("ValidationMethod").asText(null));
        String idempotencyToken = request.path("IdempotencyToken").asText(null);
        KeyAlgorithm keyAlgorithm = KeyAlgorithm.fromAwsName(request.path("KeyAlgorithm").asText(null));
        String certAuthorityArn = request.path("CertificateAuthorityArn").asText(null);
        CertificateOptions options = parseOptions(request.path("Options"));
        Map<String, String> tags = parseTags(request.path("Tags"));

        Certificate cert = service.requestCertificate(domainName, sans, validationMethod,
            idempotencyToken, keyAlgorithm, certAuthorityArn, options, tags, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("CertificateArn", cert.getArn());
        return Response.ok(response).build();
    }

    private Response handleDescribeCertificate(JsonNode request, String region) {
        String certificateArn = request.path("CertificateArn").asText();
        Certificate cert = service.describeCertificate(certificateArn, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("Certificate", buildCertificateDetail(cert));
        return Response.ok(response).build();
    }

    private Response handleGetCertificate(JsonNode request, String region) {
        String certificateArn = request.path("CertificateArn").asText();
        Certificate cert = service.getCertificate(certificateArn, region);

        // AWS returns RequestInProgressException for certificates still pending validation
        if (cert.getStatus() == CertificateStatus.PENDING_VALIDATION) {
            throw new io.github.hectorvent.floci.core.common.AwsException(
                "RequestInProgressException",
                "The certificate request is in progress. The certificate body is not yet available.",
                400);
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.put("Certificate", cert.getCertificateBody());
        if (cert.getCertificateChain() != null) {
            response.put("CertificateChain", cert.getCertificateChain());
        }
        return Response.ok(response).build();
    }

    private Response handleListCertificates(JsonNode request, String region) {
        List<CertificateStatus> statuses = parseCertificateStatuses(request.path("CertificateStatuses"));
        List<KeyAlgorithm> keyTypes = parseKeyTypes(request.path("Includes").path("keyTypes"));
        int maxItems = request.path("MaxItems").asInt(100);
        String nextToken = request.path("NextToken").asText(null);

        ListResult result = service.listCertificates(statuses, keyTypes, region, maxItems, nextToken);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaryList = objectMapper.createArrayNode();
        for (Certificate cert : result.certificates()) {
            summaryList.add(buildCertificateSummary(cert));
        }
        response.set("CertificateSummaryList", summaryList);
        if (result.nextToken() != null) {
            response.put("NextToken", result.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response handleDeleteCertificate(JsonNode request, String region) {
        String certificateArn = request.path("CertificateArn").asText();
        service.deleteCertificate(certificateArn, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleImportCertificate(JsonNode request, String region) {
        String certificate = decodeBlob(request.path("Certificate").asText());
        String privateKey = decodeBlob(request.path("PrivateKey").asText());
        String chain = request.path("CertificateChain").asText(null);
        if (chain != null) {
            chain = decodeBlob(chain);
        }
        String existingArn = request.path("CertificateArn").asText(null);
        Map<String, String> tags = parseTags(request.path("Tags"));

        Certificate cert = service.importCertificate(certificate, privateKey, chain, existingArn, tags, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("CertificateArn", cert.getArn());
        return Response.ok(response).build();
    }

    private Response handleExportCertificate(JsonNode request, String region) {
        String certificateArn = request.path("CertificateArn").asText();
        String passphrase = request.path("Passphrase").asText();

        Certificate cert = service.exportCertificate(certificateArn, passphrase, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("Certificate", cert.getCertificateBody());
        if (cert.getCertificateChain() != null) {
            response.put("CertificateChain", cert.getCertificateChain());
        }
        response.put("PrivateKey", cert.getPrivateKey());
        return Response.ok(response).build();
    }

    private Response handleAddTagsToCertificate(JsonNode request, String region) {
        String certificateArn = request.path("CertificateArn").asText();
        Map<String, String> tags = parseTags(request.path("Tags"));

        service.addTagsToCertificate(certificateArn, tags, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListTagsForCertificate(JsonNode request, String region) {
        String certificateArn = request.path("CertificateArn").asText();
        Map<String, String> tags = service.listTagsForCertificate(certificateArn, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode tagsArray = objectMapper.createArrayNode();
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            ObjectNode tagNode = objectMapper.createObjectNode();
            tagNode.put("Key", entry.getKey());
            tagNode.put("Value", entry.getValue());
            tagsArray.add(tagNode);
        }
        response.set("Tags", tagsArray);
        return Response.ok(response).build();
    }

    private Response handleRemoveTagsFromCertificate(JsonNode request, String region) {
        String certificateArn = request.path("CertificateArn").asText();
        List<Map<String, String>> tagSpecs = new ArrayList<>();
        JsonNode tagsNode = request.path("Tags");
        if (tagsNode.isArray()) {
            for (JsonNode tagNode : tagsNode) {
                Map<String, String> spec = new HashMap<>();
                spec.put("Key", tagNode.path("Key").asText());
                if (tagNode.has("Value")) {
                    spec.put("Value", tagNode.path("Value").asText());
                }
                tagSpecs.add(spec);
            }
        }

        service.removeTagsFromCertificate(certificateArn, tagSpecs, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleGetAccountConfiguration(JsonNode request, String region) {
        int daysBeforeExpiry = service.getAccountDaysBeforeExpiry();

        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode expiryEvents = objectMapper.createObjectNode();
        expiryEvents.put("DaysBeforeExpiry", daysBeforeExpiry);
        response.set("ExpiryEvents", expiryEvents);
        return Response.ok(response).build();
    }

    private Response handlePutAccountConfiguration(JsonNode request, String region) {
        String idempotencyToken = request.path("IdempotencyToken").asText(null);
        int daysBeforeExpiry = request.path("ExpiryEvents").path("DaysBeforeExpiry").asInt(45);

        service.putAccountConfiguration(daysBeforeExpiry, idempotencyToken);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    // ============ Helper Methods ============

    private ObjectNode buildCertificateDetail(Certificate cert) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("CertificateArn", cert.getArn());
        node.put("DomainName", cert.getDomainName());

        ArrayNode sans = objectMapper.createArrayNode();
        if (cert.getSubjectAlternativeNames() != null) {
            cert.getSubjectAlternativeNames().forEach(sans::add);
        }
        node.set("SubjectAlternativeNames", sans);
        node.set("SubjectAlternativeNameSummaries", sans.deepCopy());
        node.put("HasAdditionalSubjectAlternativeNames", false);

        node.put("Status", cert.getStatus().name());
        node.put("Type", cert.getType().name());

        if (cert.getKeyAlgorithm() != null) {
            node.put("KeyAlgorithm", cert.getKeyAlgorithm().getAwsName());
        }
        if (cert.getSignatureAlgorithm() != null) {
            node.put("SignatureAlgorithm", cert.getSignatureAlgorithm());
        }
        if (cert.getSerial() != null) {
            node.put("Serial", cert.getSerial());
        }
        if (cert.getSubject() != null) {
            node.put("Subject", cert.getSubject());
        }
        if (cert.getIssuer() != null) {
            node.put("Issuer", cert.getIssuer());
        }

        if (cert.getCreatedAt() != null) {
            node.put("CreatedAt", cert.getCreatedAt().toEpochMilli() / 1000.0);
        }
        if (cert.getIssuedAt() != null) {
            node.put("IssuedAt", cert.getIssuedAt().toEpochMilli() / 1000.0);
        }
        if (cert.getImportedAt() != null) {
            node.put("ImportedAt", cert.getImportedAt().toEpochMilli() / 1000.0);
        }
        if (cert.getNotBefore() != null) {
            node.put("NotBefore", cert.getNotBefore().toEpochMilli() / 1000.0);
        }
        if (cert.getNotAfter() != null) {
            node.put("NotAfter", cert.getNotAfter().toEpochMilli() / 1000.0);
        }

        ArrayNode inUseBy = objectMapper.createArrayNode();
        if (cert.getInUseBy() != null) {
            cert.getInUseBy().forEach(inUseBy::add);
        }
        node.set("InUseBy", inUseBy);

        if (cert.getDomainValidationOptions() != null && !cert.getDomainValidationOptions().isEmpty()) {
            ArrayNode validations = objectMapper.createArrayNode();
            for (DomainValidation dv : cert.getDomainValidationOptions()) {
                ObjectNode dvNode = objectMapper.createObjectNode();
                dvNode.put("DomainName", dv.domainName());
                dvNode.put("ValidationDomain", dv.validationDomain());
                dvNode.put("ValidationStatus", dv.validationStatus());
                dvNode.put("ValidationMethod", dv.validationMethod());
                if (dv.resourceRecord() != null) {
                    ObjectNode rrNode = objectMapper.createObjectNode();
                    rrNode.put("Name", dv.resourceRecord().name());
                    rrNode.put("Type", dv.resourceRecord().type());
                    rrNode.put("Value", dv.resourceRecord().value());
                    dvNode.set("ResourceRecord", rrNode);
                }
                validations.add(dvNode);
            }
            node.set("DomainValidationOptions", validations);
        }

        node.put("RenewalEligibility", "INELIGIBLE");

        ArrayNode keyUsages = objectMapper.createArrayNode();
        ObjectNode ku1 = objectMapper.createObjectNode();
        ku1.put("Name", "DIGITAL_SIGNATURE");
        keyUsages.add(ku1);
        ObjectNode ku2 = objectMapper.createObjectNode();
        ku2.put("Name", "KEY_ENCIPHERMENT");
        keyUsages.add(ku2);
        node.set("KeyUsages", keyUsages);

        ArrayNode extKeyUsages = objectMapper.createArrayNode();
        ObjectNode eku1 = objectMapper.createObjectNode();
        eku1.put("Name", "TLS_WEB_SERVER_AUTHENTICATION");
        eku1.put("OID", "1.3.6.1.5.5.7.3.1");
        extKeyUsages.add(eku1);
        ObjectNode eku2 = objectMapper.createObjectNode();
        eku2.put("Name", "TLS_WEB_CLIENT_AUTHENTICATION");
        eku2.put("OID", "1.3.6.1.5.5.7.3.2");
        extKeyUsages.add(eku2);
        node.set("ExtendedKeyUsages", extKeyUsages);

        if (cert.getCertOptions() != null) {
            ObjectNode opts = objectMapper.createObjectNode();
            opts.put("CertificateTransparencyLoggingPreference",
                cert.getCertOptions().certificateTransparencyLoggingPreference());
            node.set("Options", opts);
        }

        return node;
    }

    private ObjectNode buildCertificateSummary(Certificate cert) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("CertificateArn", cert.getArn());
        node.put("DomainName", cert.getDomainName());

        ArrayNode sans = objectMapper.createArrayNode();
        if (cert.getSubjectAlternativeNames() != null) {
            cert.getSubjectAlternativeNames().forEach(sans::add);
        }
        node.set("SubjectAlternativeNameSummaries", sans);
        node.put("HasAdditionalSubjectAlternativeNames", false);

        node.put("Status", cert.getStatus().name());
        node.put("Type", cert.getType().name());

        if (cert.getKeyAlgorithm() != null) {
            node.put("KeyAlgorithm", cert.getKeyAlgorithm().getAwsName());
        }

        ArrayNode keyUsages = objectMapper.createArrayNode();
        keyUsages.add("DIGITAL_SIGNATURE");
        keyUsages.add("KEY_ENCIPHERMENT");
        node.set("KeyUsages", keyUsages);

        ArrayNode extKeyUsages = objectMapper.createArrayNode();
        extKeyUsages.add("TLS_WEB_SERVER_AUTHENTICATION");
        extKeyUsages.add("TLS_WEB_CLIENT_AUTHENTICATION");
        node.set("ExtendedKeyUsages", extKeyUsages);

        if (cert.getCreatedAt() != null) {
            node.put("CreatedAt", cert.getCreatedAt().toEpochMilli() / 1000.0);
        }
        if (cert.getIssuedAt() != null) {
            node.put("IssuedAt", cert.getIssuedAt().toEpochMilli() / 1000.0);
        }
        if (cert.getImportedAt() != null) {
            node.put("ImportedAt", cert.getImportedAt().toEpochMilli() / 1000.0);
        }
        if (cert.getNotBefore() != null) {
            node.put("NotBefore", cert.getNotBefore().toEpochMilli() / 1000.0);
        }
        if (cert.getNotAfter() != null) {
            node.put("NotAfter", cert.getNotAfter().toEpochMilli() / 1000.0);
        }

        node.put("RenewalEligibility", "INELIGIBLE");
        node.put("InUse", cert.getInUseBy() != null && !cert.getInUseBy().isEmpty());

        return node;
    }

    private List<String> parseStringList(JsonNode node) {
        if (!node.isArray()) return null;
        List<String> list = new ArrayList<>();
        node.forEach(n -> list.add(n.asText()));
        return list;
    }

    private Map<String, String> parseTags(JsonNode tagsNode) {
        if (!tagsNode.isArray()) return null;
        Map<String, String> tags = new HashMap<>();
        for (JsonNode tagNode : tagsNode) {
            String key = tagNode.path("Key").asText();
            String value = tagNode.path("Value").asText(null);
            tags.put(key, value);
        }
        return tags;
    }

    private ValidationMethod parseValidationMethod(String method) {
        if (method == null) return ValidationMethod.DNS;
        try {
            return ValidationMethod.valueOf(method.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new io.github.hectorvent.floci.core.common.AwsException(
                "ValidationException",
                "Invalid validation method: " + method + ". Must be DNS or EMAIL.",
                400);
        }
    }

    private CertificateOptions parseOptions(JsonNode optionsNode) {
        if (optionsNode.isMissingNode()) return null;
        String ctPref = optionsNode.path("CertificateTransparencyLoggingPreference").asText("ENABLED");
        return new CertificateOptions(ctPref, "DISABLED");
    }

    private List<CertificateStatus> parseCertificateStatuses(JsonNode node) {
        if (!node.isArray()) return null;
        List<CertificateStatus> list = new ArrayList<>();
        for (JsonNode n : node) {
            try {
                list.add(CertificateStatus.valueOf(n.asText()));
            } catch (IllegalArgumentException e) {
                LOG.debugv("Ignoring unknown certificate status: {0}", n.asText());
            }
        }
        return list.isEmpty() ? null : list;
    }

    /**
     * Decodes a base64-encoded blob field from the AWS JSON 1.1 wire protocol.
     * AWS SDKs send binary fields (Certificate, PrivateKey, Passphrase, etc.)
     * as base64-encoded strings. If the value is already in PEM format (e.g. from
     * direct HTTP calls), it is returned as-is.
     */
    private String decodeBlob(String value) {
        if (value == null || value.startsWith("-----")) {
            return value;
        }
        try {
            return new String(Base64.getDecoder().decode(value));
        } catch (IllegalArgumentException e) {
            // Not valid base64 — return as-is
            return value;
        }
    }

    private List<KeyAlgorithm> parseKeyTypes(JsonNode node) {
        if (!node.isArray()) return null;
        List<KeyAlgorithm> list = new ArrayList<>();
        for (JsonNode n : node) {
            KeyAlgorithm alg = KeyAlgorithm.fromAwsName(n.asText());
            if (alg != null) {
                list.add(alg);
            }
        }
        return list.isEmpty() ? null : list;
    }
}
