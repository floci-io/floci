package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.services.iam.model.SAMLProvider;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Verifies the specific SAML assertion contract consumed by STS. */
final class SAMLAssertionVerifier {
    private static final String SAML = "urn:oasis:names:tc:SAML:2.0:assertion";
    private static final String DS = "http://www.w3.org/2000/09/xmldsig#";
    private static final String AUDIENCE = "urn:amazon:webservices";

    private SAMLAssertionVerifier() {}

    record Verified(String issuer, String subject, String subjectType, String nameQualifier,
                    Instant expiration, List<RolePair> roles) {}
    record RolePair(String roleArn, String principalArn) {}

    static Verified verify(String encoded, SAMLProvider provider, Instant now) throws InvalidAssertionException {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            var document = SAMLXml.document(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
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
            X509Certificate certificate = certificate(provider.getCertificate());
            var context = new DOMValidateContext(certificate.getPublicKey(), signature);
            context.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.TRUE);
            XMLSignature xmlSignature = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(context);
            if (xmlSignature.getSignedInfo().getReferences().size() != 1
                    || !(xmlSignature.getSignedInfo().getReferences().get(0) instanceof javax.xml.crypto.dsig.Reference reference)
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
                if ("https://signin.aws.amazon.com/saml".equals(recipient)
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
                    if (pair[0].startsWith("arn:aws:iam::") && pair[1].contains(":saml-provider/")) {
                        roles.add(new RolePair(pair[0], pair[1]));
                    } else if (pair[1].startsWith("arn:aws:iam::") && pair[0].contains(":saml-provider/")) {
                        roles.add(new RolePair(pair[1], pair[0]));
                    } else {
                        throw invalid("role pair ARN");
                    }
                }
            }
            if (roles.isEmpty()) {
                throw invalid("role pair missing");
            }
            String nameQualifier = java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((issuer + provider.getArn()).getBytes(StandardCharsets.UTF_8)));
            return new Verified(issuer, name, subjectType, nameQualifier, notOnOrAfter, roles);
        } catch (InvalidAssertionException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidAssertionException(e);
        }
    }

    private static X509Certificate certificate(String encoded) throws Exception {
        byte[] der = Base64.getDecoder().decode(encoded.replaceAll("\\s+", ""));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
    }

    private static Element first(org.w3c.dom.Node parent, String namespace, String local) {
        if (parent == null) {
            return null;
        }
        NodeList list = ((Element) parent).getElementsByTagNameNS(namespace, local);
        return list.getLength() == 0 ? null : (Element) list.item(0);
    }
    private static String childText(org.w3c.dom.Node parent, String namespace, String local) {
        Element e = first(parent, namespace, local);
        return e == null ? null : e.getTextContent().trim();
    }

    private static boolean hasText(org.w3c.dom.Node parent, String namespace, String local, String expected) {
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
        InvalidAssertionException(Throwable cause) { super(cause); }
        InvalidAssertionException(String message) { super(message); }
    }

}
