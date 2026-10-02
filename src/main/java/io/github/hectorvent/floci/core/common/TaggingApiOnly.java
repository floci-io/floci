package io.github.hectorvent.floci.core.common;

import jakarta.inject.Qualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link TagHandler} for a service whose tag operations live in its own protocol, so
 * neither the {@code /tags/{arn}} nor the {@code /v1/tags/{arn}} path serves it.
 *
 * <p>A qualified bean is not a {@code @Default} bean, and it does not carry {@link V1Tags}, so
 * only the Resource Groups Tagging API's {@code @Any Instance<TagHandler>} reaches it.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
public @interface TaggingApiOnly {
}
