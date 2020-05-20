package org.jenkinsci.plugins.mesos.integration;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;

/**
 * Tag integration tests and apply timeout to <i>each</i> test method.
 *
 * <p>The timeout can be overridden individually.
 *
 * @see org.junit.jupiter.api.Timeout
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Tag("integration")
@Timeout(value = 3, unit = TimeUnit.MINUTES)
public @interface IntegrationTest {}
