package io.github.hectorvent.floci.core.common;

import jakarta.inject.Qualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link TagHandler} as belonging to the {@code /v1/tags/{arn}} path rather than the
 * default {@code /tags/{arn}} one.
 *
 * <p>A qualified bean is not a {@code @Default} bean, so {@code SharedTagsController}'s
 * unqualified {@code Instance<TagHandler>} does not see these, and {@code V1TagsController}'s
 * {@code @V1Tags Instance<TagHandler>} sees only these. That keeps each service reachable
 * on the tag path AWS actually defines for it instead of on both.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
public @interface V1Tags {
}
