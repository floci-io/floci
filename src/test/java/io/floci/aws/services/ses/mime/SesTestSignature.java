package io.floci.aws.services.ses.mime;

/**
 * Hands the content scan's test signature to tests outside this package, so the accessor on
 * {@link SesContentScan} stays package-private and no test source spells the string out.
 */
public final class SesTestSignature {

    private SesTestSignature() {
    }

    public static String value() {
        return SesContentScan.signature();
    }
}
