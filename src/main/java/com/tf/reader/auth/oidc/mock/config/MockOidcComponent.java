package com.tf.reader.auth.oidc.mock.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * A part of the local mock identity provider: a component that exists <b>only</b> when
 * {@code mock-oidc.enabled} is explicitly true.
 *
 * <p><b>Why every class in this package is annotated rather than the package being excluded from
 * the component scan.</b> A mock identity provider is a machine for minting identities for
 * arbitrary users, so "switched off" has to mean the beans and the endpoints <em>do not exist</em>,
 * not that they exist and nobody calls them. Carrying the condition on each class is the version
 * of that guarantee which cannot be undone by moving a package, renaming one, or adding a class
 * somebody forgets to exclude - the condition travels with the code.
 *
 * <p>{@code SecurityArchitectureTest} asserts that every class in this package is either
 * annotated with this or conditional in its own right, so a new mock component that forgets it
 * fails the build rather than shipping switched on.
 *
 * <p>{@link MockOidcController} is the one exception in form only: it needs {@code @RestController}
 * for Spring MVC to detect it as a handler at all, and carries the same
 * {@code @ConditionalOnProperty} directly.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
@ConditionalOnProperty(prefix = "mock-oidc", name = "enabled", havingValue = "true")
public @interface MockOidcComponent {
}
