package io.floci.aws.services.s3;

import java.util.List;
import java.util.Map;

/**
 * The S3 condition keys a check Floci makes on a caller's behalf offers, named once for every place that
 * makes one: a direct listing, a signed listing another service makes as a role (Redshift COPY), and a
 * Step Functions Distributed Map reading a bucket.
 */
public final class S3ConditionKeys {

    /** The key prefix an {@code s3:ListBucket} listing names. */
    public static final String PREFIX = "s3:prefix";

    private S3ConditionKeys() {}

    /** The context of a listing under {@code prefix}, offered as the empty string when the listing names none. */
    public static Map<String, List<String>> listing(String prefix) {
        return Map.of(PREFIX, List.of(prefix == null ? "" : prefix));
    }
}
