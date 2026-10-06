package by.akozel.axon.foundation.testing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Marks a test class that runs against the PostgreSQL test container, see {@link PostgresContainer}. The class is
 * skipped, not failed, when no container runtime is reachable.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@EnabledIf(value = "by.akozel.axon.foundation.testing.PostgresContainer#available",
           disabledReason = "No container runtime for Testcontainers: start Podman (podman machine start) or Docker")
public @interface RequiresPostgresContainer {}
