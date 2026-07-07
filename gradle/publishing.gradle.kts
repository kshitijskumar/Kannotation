import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

// Shared publishing config for TypeString's published modules (typestring-annotations,
// typestring-processor). Applied via `apply(from = ...)` since it's used from two
// otherwise-unrelated build files (KMP module + plain JVM module).

configure<PublishingExtension> {
    repositories {
        maven {
            name = "LocalRepo"
            // scripts/publish-library.sh points this at a checkout of the `mvn-repo` branch so
            // Gradle merges into the existing maven-metadata.xml instead of overwriting history.
            // Defaults to a plain build/ dir for local experimentation (./gradlew publish...).
            val repoDir = providers.gradleProperty("mavenRepoDir")
            url = if (repoDir.isPresent) {
                uri(file(repoDir.get()))
            } else {
                uri(rootProject.layout.buildDirectory.dir("mvn-repo"))
            }
        }
    }

    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set(project.name)
            description.set("Part of TypeString, a KSP codegen library for obfuscation-safe sealed class type names (module: ${project.name})")
            url.set("https://github.com/kshitijskumar/Kannotation")

            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://github.com/kshitijskumar/Kannotation/blob/main/LICENSE")
                }
            }
            developers {
                developer {
                    id.set("kshitijskumar")
                    name.set("Kshitij Kumar")
                }
            }
            scm {
                url.set("https://github.com/kshitijskumar/Kannotation")
                connection.set("scm:git:https://github.com/kshitijskumar/Kannotation.git")
                developerConnection.set("scm:git:https://github.com/kshitijskumar/Kannotation.git")
            }
        }
    }
}
