package io.floci.aws.core.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The generated IAM operation a REST controller serves when AWS has no model for its route. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface IamOperation {

    String namespace();

    String operation();
}
