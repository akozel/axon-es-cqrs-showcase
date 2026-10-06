/**
 * Infrastructure layer, one sub-package per technical concern; they do not depend on each other:
 * <ul>
 *   <li>{@code persistence}: PostgreSQL connection pool, unit-of-work transactions and the JPA event store;</li>
 *   <li>{@code messaging}: command execution on virtual threads with an admission limit.</li>
 * </ul>
 * May depend on the application and the domain; only {@code Application} wires it in.
 */
package by.akozel.accountverification.infrastructure;
