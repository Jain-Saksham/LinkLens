package com.linklens.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An absolute http(s) URL with a host. Stricter than Hibernate's {@code @URL}, which also accepts
 * schemes like {@code ftp:} or {@code file:} that a redirect service should never emit.
 * Null is considered valid; combine with {@code @NotBlank} for required fields.
 */
@Documented
@Constraint(validatedBy = HttpUrlValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface HttpUrl {

    String message() default "must be a valid http or https URL";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
