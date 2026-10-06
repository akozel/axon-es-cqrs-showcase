/**
 * Base configuration of Axon Framework 5 shared by the services, one sub-package per technical concern; they do not
 * depend on each other:
 * <ul>
 *   <li>{@code persistence}: PostgreSQL connection pool, unit-of-work transactions and the JPA event store;</li>
 *   <li>{@code messaging}: command execution on virtual threads with an admission limit.</li>
 * </ul>
 * Knows no service: a service's composition root combines them with its own entities and handlers.
 */
package by.akozel.axon.foundation;
