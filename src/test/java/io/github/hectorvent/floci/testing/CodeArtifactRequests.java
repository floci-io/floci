package io.github.hectorvent.floci.testing;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** Request bodies and management calls the CodeArtifact tests share across formats. */
public final class CodeArtifactRequests {

    private CodeArtifactRequests() {}

    /**
     * An npm publish envelope for one version: its {@code dist} block, carrying the tarball's real
     * sha512 {@code integrity}, and the tarball itself as a base64 {@code _attachments} entry named
     * {@code filename}. {@code name} is the full npm name, {@code @scope/name} for a scoped package.
     */
    public static String npmPublishEnvelope(String name, String version, String filename, byte[] content) {
        return npmPublishEnvelope(name, version, filename, content, null);
    }

    /** As above, with {@code distTagsJson} sent as the envelope's {@code dist-tags}; {@code null} leaves it out. */
    public static String npmPublishEnvelope(String name, String version, String filename, byte[] content,
            String distTagsJson) {
        String distTags = distTagsJson == null ? "" : ",\"dist-tags\":" + distTagsJson;
        return "{\"name\":\"" + name + "\",\"versions\":{\"" + version + "\":{\"name\":\"" + name
                + "\",\"version\":\"" + version + "\",\"dist\":{\"tarball\":\"http://ignored/" + filename
                + "\",\"integrity\":\"" + npmIntegrity(content) + "\"}}}" + distTags + ",\"_attachments\":{\""
                + filename + "\":{\"content_type\":\"application/octet-stream\","
                + "\"data\":\"" + Base64.getEncoder().encodeToString(content) + "\",\"length\":" + content.length
                + "}}}";
    }

    /** The {@code sha512-<base64>} integrity string npm records for {@code content}. */
    public static String npmIntegrity(byte[] content) {
        try {
            return "sha512-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * PutPackageOriginConfiguration for one package, asserting both restrictions came back as sent.
     * {@code namespace} is {@code null} for a format or package without one.
     */
    public static void putPackageOriginConfiguration(String authorization, String domain, String repository,
            String format, String namespace, String packageName, String publish, String upstream) {
        String namespaceParam = namespace == null ? "" : "&namespace=" + namespace;
        given().contentType("application/json").header("Authorization", authorization)
                .body("{\"restrictions\":{\"publish\":\"" + publish + "\",\"upstream\":\"" + upstream + "\"}}")
                .post("/v1/package?domain=" + domain + "&repository=" + repository + "&format=" + format
                        + namespaceParam + "&package=" + packageName)
                .then().statusCode(200)
                .body("originConfiguration.restrictions.publish", equalTo(publish))
                .body("originConfiguration.restrictions.upstream", equalTo(upstream));
    }
}
