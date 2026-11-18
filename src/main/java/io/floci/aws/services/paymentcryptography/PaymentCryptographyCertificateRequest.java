package io.floci.aws.services.paymentcryptography;

import io.floci.aws.core.common.AwsException;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The certificate signing request a key pair signs.
 *
 * <p>The request is a PKCS#10 one: the subject and the public half of the key, signed by the
 * private half. The digest is the one the request names, and the signature algorithm is the
 * one the key's own algorithm gives with that digest, so an RSA key signs with an RSA
 * algorithm and an elliptic curve one with an ECDSA algorithm.
 */
final class PaymentCryptographyCertificateRequest {

    /**
     * The subject's members, and the attribute each is written as.
     *
     * <p>The order is the one the reference's own request carries, most general member
     * first, so the subject of two requests with the same members is the same subject. The
     * reference's example reads {@code C=US, ST=Virginia, L=Arlington, O=Amazon,
     * OU=PaymentCryptography, CN=MyCertificateAWSUSEAST}, which is the order the members
     * are written in here.
     */
    private static final Map<String, ASN1ObjectIdentifier> SUBJECT_TYPES = subjectTypes();

    /**
     * The members whose attribute holds an IA5String, which is ASCII alone.
     *
     * <p>A value outside ASCII cannot be written as such an attribute, and the encoder would
     * narrow it to a byte of its own, so the request would carry an address the caller did
     * not name. A request that names one is refused instead.
     */
    private static final Set<String> IA5_MEMBERS = Set.of("EmailAddress");

    private PaymentCryptographyCertificateRequest() {
    }

    private static Map<String, ASN1ObjectIdentifier> subjectTypes() {
        Map<String, ASN1ObjectIdentifier> types = new LinkedHashMap<>();
        types.put("Country", BCStyle.C);
        types.put("StateOrProvince", BCStyle.ST);
        types.put("City", BCStyle.L);
        types.put("Organization", BCStyle.O);
        types.put("OrganizationUnit", BCStyle.OU);
        types.put("CommonName", BCStyle.CN);
        types.put("EmailAddress", BCStyle.E);
        return types;
    }

    /**
     * The subject of the request, as the members the model names.
     *
     * <p>A member the request does not name is not in the subject, and the one it does
     * require, the common name, is. A member whose attribute cannot hold the value the
     * request names is refused, because the request would otherwise carry a value the
     * caller never wrote.
     *
     * @param fields the subject's members
     * @return the subject
     */
    static X500Name subject(Map<String, String> fields) {
        X500NameBuilder builder = new X500NameBuilder(BCStyle.INSTANCE);
        for (Map.Entry<String, ASN1ObjectIdentifier> entry : SUBJECT_TYPES.entrySet()) {
            String value = fields.get(entry.getKey());
            if (value != null && !value.isEmpty()) {
                requireAscii(entry.getKey(), value);
                builder.addRDN(entry.getValue(), value);
            }
        }
        return builder.build();
    }

    /**
     * The refusal of a value the attribute of a member cannot hold.
     *
     * @param member the member of the subject
     * @param value the value the request names
     */
    private static void requireAscii(String member, String value) {
        if (!IA5_MEMBERS.contains(member)) {
            return;
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) > 127) {
                throw new AwsException("ValidationException",
                        "the " + member + " of the subject holds a character that is not ASCII, "
                                + "and the attribute it is written as holds ASCII alone", 400);
            }
        }
    }

    /**
     * The request, in PEM.
     *
     * @param subject the subject of the request
     * @param publicKey the public half of the key pair
     * @param privateKey the private half of the key pair
     * @param digest the digest the request names
     * @param rsa whether the key pair is an RSA one
     * @return the request, in PEM
     */
    static String pem(X500Name subject, PublicKey publicKey, PrivateKey privateKey,
                      String digest, boolean rsa) {
        String algorithm = algorithm(digest, rsa);
        try {
            ContentSigner signer = new JcaContentSignerBuilder(algorithm).build(privateKey);
            // The request carries the public half in the form a certificate names it, which
            // is the encoded form of the key.
            SubjectPublicKeyInfo publicKeyInfo =
                    SubjectPublicKeyInfo.getInstance(publicKey.getEncoded());
            byte[] encoded = new PKCS10CertificationRequestBuilder(subject, publicKeyInfo)
                    .build(signer).getEncoded();
            return "-----BEGIN CERTIFICATE REQUEST-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                            .encodeToString(encoded)
                    + "\n-----END CERTIFICATE REQUEST-----\n";
        } catch (OperatorCreationException | IOException e) {
            throw new AwsException("ValidationException",
                    "the certificate signing request could not be signed: " + e.getMessage(), 400);
        }
    }

    /**
     * The signature algorithm a digest names.
     *
     * <p>The digest names the digest of the signature algorithm, and the key's own
     * algorithm gives the rest, so a SHA256 digest signs with a SHA256withRSA algorithm
     * for an RSA key and with a SHA256withECDSA one for an elliptic curve key. The model
     * settles the digest, so the four the model names are the four that arrive.
     *
     * @param digest the digest the request names
     * @param rsa whether the key pair is an RSA one
     * @return the signature algorithm
     */
    private static String algorithm(String digest, boolean rsa) {
        return digest + "with" + (rsa ? "RSA" : "ECDSA");
    }
}
