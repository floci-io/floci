package io.github.hectorvent.floci.services.dms.model;

/**
 * One tag as DMS returns it from {@code ListTagsForResource}. {@code resourceArn} is null for a
 * single-ARN request and set when the caller passed {@code ResourceArnList}, which is the only
 * form where AWS documents the field as part of the response.
 */
public record ResourceTag(String resourceArn, String key, String value) {
}
