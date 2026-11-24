package io.floci.aws.services.iam;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsRegionFacts;
import io.floci.aws.core.common.Digests;
import io.floci.aws.services.iam.model.SAMLProvider;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Verifies the specific SAML assertion contract consumed by STS. */
final class SAMLAssertionVerifier {
    private static final String SAML = "urn:oasis:names:tc:SAML:2.0:assertion";
    private static final String DS = "http://www.w3.org/2000/09/xmldsig#";
    private static final String AUDIENCE = "urn:amazon:webservices";
    static final String ASSERTION_INVALID = "The SAML assertion is invalid.";
    static final String SIGNATURE_INVALID = "Response signature invalid";
    private static final String ROLE_SESSION_NAME = "https://aws.amazon.com/SAML/Attributes/RoleSessionName";
    /** What an attribute carrying a session tag is named, the tag's key following the colon. */
    private static final String PRINCIPAL_TAG = "https://aws.amazon.com/SAML/Attributes/PrincipalTag:";
    /** What a session tag attribute naming no key is refused with, this package's own wording. */
    static final String TAG_KEY_MISSING =
            "A PrincipalTag attribute must name the tag key after the colon.";
    /**
     * What an attribute naming the keys to set as transitive is named: "include another Attribute
     * element with the Name attribute set to
     * https://aws.amazon.com/SAML/Attributes/TransitiveTagKeys", which unlike a tag attribute "is
     * an optional multivalued attribute", so each of its values is one key.
     */
    private static final String TRANSITIVE_TAG_KEYS =
            "https://aws.amazon.com/SAML/Attributes/TransitiveTagKeys";
    // The pattern AWS names in its error for a RoleSessionName attribute outside it.
    static final String ROLE_SESSION_NAME_INVALID = "RoleSessionName in AuthnResponse must match [a-zA-Z_0-9+=,.@-]{2,64}";
    private static final Pattern ROLE_SESSION_NAME_PATTERN = Pattern.compile("[a-zA-Z_0-9+=,.@-]{2,64}");
    // SAML 2.0 core 5.4.4. AWS rejects any other reference transform as an invalid signature.
    private static final Set<String> ALLOWED_TRANSFORMS = Set.of(
            Transform.ENVELOPED, CanonicalizationMethod.EXCLUSIVE, CanonicalizationMethod.EXCLUSIVE_WITH_COMMENTS);

    private SAMLAssertionVerifier() {}

    /**
     * {@code roleSessionName} is null when the assertion carries no RoleSessionName attribute,
     * {@code principalTags} is empty when it carries no session tags, and
     * {@code transitiveTagKeys} is empty when it asks for none to be transitive, which is AWS's
     * default for a session.
     */
    record Verified(String issuer, String subject, String subjectType, String nameQualifier,
                    Instant expiration, List<RolePair> roles, String roleSessionName,
                    Map<String, String> principalTags, List<String> transitiveTagKeys) {}
    record RolePair(String roleArn, String principalArn) {}

    static Verified verify(String encoded, SAMLProvider provider, Instant now) throws InvalidAssertionException {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            Document document = SAMLXml.document(new String(bytes, StandardCharsets.UTF_8));
            Element assertion = document.getDocumentElement();
            if ("Response".equals(assertion.getLocalName())) {
                assertion = first(assertion, SAML, "Assertion");
            }
            if (assertion == null || !"Assertion".equals(assertion.getLocalName())
                    || !SAML.equals(assertion.getNamespaceURI())) {
                throw invalid("assertion root");
            }
            String assertionId = assertion.getAttribute("ID");
            if (assertionId == null || assertionId.isBlank()) {
                throw invalid("assertion ID");
            }
            assertion.setIdAttribute("ID", true);
            Element signature = first(assertion, DS, "Signature");
            if (signature == null) {
                throw invalid("signature missing");
            }
            Element signedInfo = first(signature, DS, "SignedInfo");
            if (signedInfo == null) {
                throw invalid("signed info missing");
            }
            // Checked on the DOM because unmarshalling some transform parameters fails in the native image.
            NodeList transforms = signedInfo.getElementsByTagNameNS(DS, "Transform");
            for (int i = 0; i < transforms.getLength(); i++) {
                String algorithm = ((Element) transforms.item(i)).getAttribute("Algorithm");
                if (!ALLOWED_TRANSFORMS.contains(algorithm)) {
                    throw new InvalidAssertionException("reference transform " + algorithm, SIGNATURE_INVALID);
                }
            }
            X509Certificate certificate = certificate(provider.getCertificate());
            DOMValidateContext context = new DOMValidateContext(certificate.getPublicKey(), signature);
            context.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.TRUE);
            XMLSignature xmlSignature = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(context);
            if (xmlSignature.getSignedInfo().getReferences().size() != 1
                    || !(xmlSignature.getSignedInfo().getReferences().get(0) instanceof Reference reference)
                    || !("#" + assertionId).equals(reference.getURI())) {
                throw invalid("signature reference");
            }
            if (!xmlSignature.validate(context)) {
                throw invalid("signature validation");
            }

            String issuer = childText(assertion, SAML, "Issuer");
            if (!provider.getEntityId().equals(issuer)) {
                throw invalid("issuer");
            }
            Element conditions = first(assertion, SAML, "Conditions");
            if (conditions == null) {
                throw invalid("conditions missing");
            }
            Instant notBefore = instant(conditions.getAttribute("NotBefore"));
            Instant notOnOrAfter = instant(conditions.getAttribute("NotOnOrAfter"));
            if (notBefore != null && now.isBefore(notBefore)) {
                throw invalid("not before");
            }
            if (notOnOrAfter == null || !now.isBefore(notOnOrAfter)) {
                throw invalid("assertion expiry");
            }
            Element restriction = first(conditions, SAML, "AudienceRestriction");
            if (restriction == null || !hasText(restriction, SAML, "Audience", AUDIENCE)) {
                throw invalid("audience");
            }

            Element subject = first(assertion, SAML, "Subject");
            String name = childText(subject, SAML, "NameID");
            if (name == null || name.isBlank()) {
                throw invalid("subject");
            }
            Element nameId = first(subject, SAML, "NameID");
            String subjectType = nameId == null ? null : nameId.getAttribute("Format");
            NodeList confirmations = subject == null
                    ? null : ((Element) subject).getElementsByTagNameNS(SAML, "SubjectConfirmation");
            if (confirmations == null || confirmations.getLength() == 0) {
                throw invalid("subject confirmation missing");
            }
            boolean validBearer = false;
            for (int i = 0; i < confirmations.getLength(); i++) {
                Element confirmation = (Element) confirmations.item(i);
                if (!"urn:oasis:names:tc:SAML:2.0:cm:bearer".equals(confirmation.getAttribute("Method"))) {
                    continue;
                }
                Element confirmationData = first(confirmation, SAML, "SubjectConfirmationData");
                if (confirmationData == null) {
                    continue;
                }
                String recipient = confirmationData.getAttribute("Recipient");
                Instant confirmationExpiry = instant(confirmationData.getAttribute("NotOnOrAfter"));
                if (expectedRecipient(provider).equals(recipient)
                        && confirmationExpiry != null && now.isBefore(confirmationExpiry)) {
                    validBearer = true;
                    break;
                }
            }
            if (!validBearer) {
                throw invalid("valid bearer subject confirmation missing");
            }

            List<RolePair> roles = new ArrayList<>();
            NodeList attributes = assertion.getElementsByTagNameNS(SAML, "Attribute");
            for (int i = 0; i < attributes.getLength(); i++) {
                Element attribute = (Element) attributes.item(i);
                if (!"https://aws.amazon.com/SAML/Attributes/Role".equals(attribute.getAttribute("Name"))) {
                    continue;
                }
                NodeList values = attribute.getElementsByTagNameNS(SAML, "AttributeValue");
                for (int j = 0; j < values.getLength(); j++) {
                    String[] pair = values.item(j).getTextContent().trim().split(",", -1);
                    if (pair.length != 2) {
                        throw invalid("role pair format");
                    }
                    if (AwsArnUtils.isArnFor(pair[0], "iam") && pair[1].contains(":saml-provider/")) {
                        roles.add(new RolePair(pair[0], pair[1]));
                    } else if (AwsArnUtils.isArnFor(pair[1], "iam") && pair[0].contains(":saml-provider/")) {
                        roles.add(new RolePair(pair[1], pair[0]));
                    } else {
                        throw invalid("role pair ARN");
                    }
                }
            }
            if (roles.isEmpty()) {
                throw invalid("role pair missing");
            }
            String roleSessionName = roleSessionName(attributes);
            String nameQualifier = Base64.getEncoder().encodeToString(
                    Digests.sha1((issuer + provider.getArn()).getBytes(StandardCharsets.UTF_8)));
            return new Verified(issuer, name, subjectType, nameQualifier, notOnOrAfter, roles,
                    roleSessionName, principalTags(attributes), transitiveTagKeys(attributes));
        } catch (InvalidAssertionException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidAssertionException(e);
        }
    }

    /**
     * The value of the RoleSessionName attribute, whose name is case-sensitive and which "contains
     * one AttributeValue element", or null when the assertion has no such attribute.
     */
    private static String roleSessionName(NodeList attributes) throws InvalidAssertionException {
        for (int i = 0; i < attributes.getLength(); i++) {
            Element attribute = (Element) attributes.item(i);
            if (!ROLE_SESSION_NAME.equals(attribute.getAttribute("Name"))) {
                continue;
            }
            Element value = first(attribute, SAML, "AttributeValue");
            String sessionName = value == null ? "" : value.getTextContent();
            if (!ROLE_SESSION_NAME_PATTERN.matcher(sessionName).matches()) {
                throw new InvalidAssertionException("role session name", ROLE_SESSION_NAME_INVALID);
            }
            return sessionName;
        }
        return null;
    }

    /**
     * The session tags the assertion passes: "to pass SAML attributes as session tags, include the
     * Attribute element with the Name attribute set to
     * https://aws.amazon.com/SAML/Attributes/PrincipalTag:{TagKey}. Use the AttributeValue element
     * to specify the value of the tag. Include a separate Attribute element for each session tag."
     *
     * <p>An attribute carries exactly one value, since "you must pass a single value for each
     * session tag" and STS "does not support multi-valued session tags", and an attribute carrying
     * none or several is refused rather than read as the first of them or dropped: a tag a policy
     * would have been evaluated with cannot go missing quietly. An attribute whose name ends at
     * the colon names no key and is refused the same way. The value is taken as the assertion
     * signed it, spaces included, tag values being allowed them, where trimming it would hand a
     * policy a value the provider never asserted. Two attributes naming one key leave the later,
     * which is how a tag key already on an identity resolves, a second tag replacing the first
     * rather than being added beside it.
     */
    private static Map<String, String> principalTags(NodeList attributes) throws InvalidAssertionException {
        Map<String, String> tags = new LinkedHashMap<>();
        for (int i = 0; i < attributes.getLength(); i++) {
            Element attribute = (Element) attributes.item(i);
            String name = attribute.getAttribute("Name");
            if (name == null || !name.startsWith(PRINCIPAL_TAG)) {
                continue;
            }
            String key = name.substring(PRINCIPAL_TAG.length());
            if (key.isEmpty()) {
                throw new InvalidAssertionException("principal tag key", TAG_KEY_MISSING);
            }
            NodeList values = attribute.getElementsByTagNameNS(SAML, "AttributeValue");
            if (values.getLength() != 1) {
                throw new InvalidAssertionException("principal tag values", singleValueRequired(key));
            }
            tags.put(key, values.item(0).getTextContent());
        }
        return tags;
    }

    /**
     * The keys the assertion asks to set as transitive, in the order its values read.
     *
     * <p>This attribute is the one AWS publishes as multivalued, each value naming one key, so
     * several values are read rather than refused as a session tag's would be, and an attribute
     * carrying none asks for nothing. The value is taken as the assertion signed it, as a tag key
     * attribute's is. Which of them select a tag is the session's business, not this one's: a key
     * naming no session tag carries no tag onward, and nothing published says AWS refuses it.
     */
    private static List<String> transitiveTagKeys(NodeList attributes) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < attributes.getLength(); i++) {
            Element attribute = (Element) attributes.item(i);
            if (!TRANSITIVE_TAG_KEYS.equals(attribute.getAttribute("Name"))) {
                continue;
            }
            NodeList values = attribute.getElementsByTagNameNS(SAML, "AttributeValue");
            for (int j = 0; j < values.getLength(); j++) {
                keys.add(values.item(j).getTextContent());
            }
        }
        return keys;
    }

    /** What an attribute carrying no single value for its tag is refused with, Floci's wording. */
    static String singleValueRequired(String key) {
        return "Session tag " + key + " must carry exactly one AttributeValue:"
                + " a session tag takes a single value.";
    }

    private static X509Certificate certificate(String encoded) throws Exception {
        byte[] der = Base64.getDecoder().decode(encoded.replaceAll("\\s+", ""));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
    }

    private static Element first(Node parent, String namespace, String local) {
        if (parent == null) {
            return null;
        }
        NodeList list = ((Element) parent).getElementsByTagNameNS(namespace, local);
        return list.getLength() == 0 ? null : (Element) list.item(0);
    }
    private static String childText(Node parent, String namespace, String local) {
        Element e = first(parent, namespace, local);
        return e == null ? null : e.getTextContent().trim();
    }

    private static boolean hasText(Node parent, String namespace, String local, String expected) {
        NodeList list = ((Element) parent).getElementsByTagNameNS(namespace, local);
        for (int i = 0; i < list.getLength(); i++) {
            if (expected.equals(list.item(i).getTextContent().trim())) {
                return true;
            }
        }
        return false;
    }

    private static Instant instant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(e);
        }
    }
    private static InvalidAssertionException invalid(String reason) {
        return new InvalidAssertionException(reason);
    }

    static final class InvalidAssertionException extends Exception {
        private final String awsMessage;

        InvalidAssertionException(Throwable cause) {
            super(cause);
            this.awsMessage = ASSERTION_INVALID;
        }

        InvalidAssertionException(String message) {
            this(message, ASSERTION_INVALID);
        }

        InvalidAssertionException(String reason, String awsMessage) {
            super(reason);
            this.awsMessage = awsMessage;
        }

        String awsMessage() {
            return awsMessage;
        }
    }

    /**
     * The console sign-on URL a bearer assertion must name, in the provider's partition. The CDK
     * publishes none for {@code aws-iso-e}, {@code aws-iso-f} and {@code aws-eusc}; there the
     * commercial URL is kept, as every partition used before the per-partition table, rather than
     * refusing every assertion.
     */
    // Package-private for unit testing.
    static String expectedRecipient(SAMLProvider provider) {
        String partition = AwsArnUtils.parse(provider.getArn()).partition();
        return AwsRegionFacts.samlSignOnUrl(partition)
                .or(() -> AwsRegionFacts.samlSignOnUrl("aws"))
                .orElseThrow();
    }
}
