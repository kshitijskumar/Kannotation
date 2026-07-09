package io.kshitij.typestring

/**
 * Marks a sealed node (or a non-sealed abstract node that would otherwise break the sealed
 * hierarchy chain) as a single leaf for `typeString` generation: the processor stops recursing
 * at this node and emits one `is <this> -> "<dotted.path>"` branch covering its entire subtree,
 * instead of descending into its children.
 *
 * Has no effect when placed on the same class as `@GenerateTypeString`, or on a class not
 * reachable from any `@GenerateTypeString`-annotated root.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS)
annotation class TypeStringLeaf
