package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.XmlParser;

/**
 * The four S3 Block Public Access flags, read back from a stored
 * {@code PublicAccessBlockConfiguration} document.
 *
 * <p>The flags are independent and split into two kinds. {@code BlockPublicAcls} and
 * {@code BlockPublicPolicy} reject the write that would introduce public access, so they are
 * evaluated when a policy or ACL is submitted and apply whoever the caller is.
 * {@code IgnorePublicAcls} and {@code RestrictPublicBuckets} suppress access that an existing
 * public ACL or policy would otherwise grant, so they are evaluated during authorization.
 *
 * <p>AWS evaluates a bucket's configuration together with the bucket owner account's
 * configuration and applies the most restrictive combination, which for four independent
 * booleans is a per-flag OR.
 */
record S3BlockPublicAccessSettings(
        boolean blockPublicAcls,
        boolean ignorePublicAcls,
        boolean blockPublicPolicy,
        boolean restrictPublicBuckets) {

    static final S3BlockPublicAccessSettings NONE =
            new S3BlockPublicAccessSettings(false, false, false, false);

    static S3BlockPublicAccessSettings parse(String configurationXml) {
        if (configurationXml == null || configurationXml.isBlank()) {
            return NONE;
        }
        return new S3BlockPublicAccessSettings(
                flag(configurationXml, "BlockPublicAcls"),
                flag(configurationXml, "IgnorePublicAcls"),
                flag(configurationXml, "BlockPublicPolicy"),
                flag(configurationXml, "RestrictPublicBuckets"));
    }

    /** The per-flag OR that AWS describes as "the most restrictive combination". */
    S3BlockPublicAccessSettings mostRestrictive(S3BlockPublicAccessSettings other) {
        if (other == null) {
            return this;
        }
        return new S3BlockPublicAccessSettings(
                blockPublicAcls || other.blockPublicAcls,
                ignorePublicAcls || other.ignorePublicAcls,
                blockPublicPolicy || other.blockPublicPolicy,
                restrictPublicBuckets || other.restrictPublicBuckets);
    }

    boolean blocksAnything() {
        return blockPublicAcls || ignorePublicAcls || blockPublicPolicy || restrictPublicBuckets;
    }

    private static boolean flag(String configurationXml, String elementName) {
        return Boolean.parseBoolean(XmlParser.extractFirst(configurationXml, elementName, "false"));
    }
}
