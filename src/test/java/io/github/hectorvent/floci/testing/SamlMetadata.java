package io.github.hectorvent.floci.testing;

/**
 * The SAML metadata document a test registers a provider with. AWS declares
 * {@code SAMLMetadataDocument} at a minimum of 1000 characters, which a document carrying nothing
 * but an entity id and one signing key does not reach, so the filler comment brings it to that
 * length. The document is built here rather than copied into each test: six of them registered a
 * provider with a document of their own, and the constraints read off the model hold every one of
 * them to the same bound.
 */
public final class SamlMetadata {

    /** The minimum the model declares for the document. */
    public static final int MINIMUM_LENGTH = 1000;

    private static final String DEFAULT_CERTIFICATE = "Y2VydA==";

    private SamlMetadata() {
    }

    /** The document for {@code entityId}, carrying a stand-in signing certificate. */
    public static String forEntity(String entityId) {
        return forEntity(entityId, DEFAULT_CERTIFICATE);
    }

    /** The same, carrying {@code certificateBase64} as the signing certificate. */
    public static String forEntity(String entityId, String certificateBase64) {
        String document = "<md:EntityDescriptor xmlns:md=\"urn:oasis:names:tc:SAML:2.0:metadata\""
                + " entityID=\"" + entityId + "\"><md:IDPSSODescriptor>"
                + "<md:KeyDescriptor use=\"signing\">"
                + "<ds:KeyInfo xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"><ds:X509Data>"
                + "<ds:X509Certificate>" + certificateBase64 + "</ds:X509Certificate>"
                + "</ds:X509Data></ds:KeyInfo></md:KeyDescriptor></md:IDPSSODescriptor>";
        String end = "</md:EntityDescriptor>";
        return document + padding(document.length() + end.length()) + end;
    }

    /**
     * A comment long enough to bring the document to the model's minimum, which parses as nothing
     * at all: the document's entity id and signing certificate are what the service reads.
     */
    private static String padding(int length) {
        int wanted = MINIMUM_LENGTH - length;
        if (wanted <= 0) {
            return "";
        }
        String open = "<!-- ";
        String close = " -->";
        int filler = Math.max(1, wanted - open.length() - close.length());
        return open + "x".repeat(filler) + close;
    }
}
