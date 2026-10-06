import org.gradle.api.provider.ListProperty

/** The `coverage { }` block of a module, see `java-conventions`. */
interface CoverageExtension {

    /**
     * Class files left out of the coverage report and check, as Ant patterns relative to the classes directory, e.g.
     * `by/akozel/example/Application.class`. Name every class explicitly and say why next to it.
     */
    val excludedClasses: ListProperty<String>
}
