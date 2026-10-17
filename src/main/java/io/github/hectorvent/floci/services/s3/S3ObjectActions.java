package io.github.hectorvent.floci.services.s3;

import java.util.Map;

/**
 * The version actions AWS checks for an S3 object request that names a {@code versionId}, in place of
 * the action the request is otherwise checked as. The source is the Service Authorization Reference
 * for S3, which gives each its own action.
 */
public final class S3ObjectActions {

    /**
     * The object actions that have a version counterpart, which AWS checks in their place when the
     * request names a {@code versionId}. Retention, legal hold, restore and the multipart actions
     * have none, and are checked as they are.
     */
    private static final Map<String, String> VERSION_ACTIONS = Map.ofEntries(
            Map.entry("s3:GetObject", "s3:GetObjectVersion"),
            Map.entry("s3:DeleteObject", "s3:DeleteObjectVersion"),
            Map.entry("s3:GetObjectAcl", "s3:GetObjectVersionAcl"),
            Map.entry("s3:PutObjectAcl", "s3:PutObjectVersionAcl"),
            Map.entry("s3:GetObjectTagging", "s3:GetObjectVersionTagging"),
            Map.entry("s3:PutObjectTagging", "s3:PutObjectVersionTagging"),
            Map.entry("s3:DeleteObjectTagging", "s3:DeleteObjectVersionTagging"),
            Map.entry("s3:GetObjectAnnotation", "s3:GetObjectVersionAnnotation"),
            Map.entry("s3:ListObjectAnnotations", "s3:ListObjectVersionAnnotations"),
            Map.entry("s3:PutObjectAnnotation", "s3:PutObjectVersionAnnotation"),
            Map.entry("s3:DeleteObjectAnnotation", "s3:DeleteObjectVersionAnnotation"));

    private S3ObjectActions() {
    }

    /** What a request naming {@code versionId} is checked as: {@code action}'s version counterpart, if it has one. */
    public static String forVersion(String action, String versionId) {
        return versionId == null ? action : VERSION_ACTIONS.getOrDefault(action, action);
    }

    /** True for an action AWS checks only for a request naming a version, which carries {@code s3:versionid}. */
    public static boolean isVersionAction(String action) {
        return VERSION_ACTIONS.containsValue(action);
    }
}
