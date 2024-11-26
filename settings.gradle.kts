pluginManagement {
    plugins {
        id("org.bytedeco.gradle-javacpp-platform") version "1.5.10"
        id("org.openjfx.javafxplugin") version "0.1.0"
    }
}

rootProject.name = "qupath-extension-instanseg"
var qupathVersion = "0.6.0-SNAPSHOT"
gradle.extra["qupath.app.version"] = qupathVersion

dependencyResolutionManagement {

    // Access QuPath's version catalog for dependency versions
    versionCatalogs {
        create("libs") {
            from("io.github.qupath:qupath-catalog:$qupathVersion")
        }
    }

    repositories {
        mavenCentral()

        // Add scijava - which is where QuPath's jars are hosted
        maven {
            url = uri("https://maven.scijava.org/content/repositories/releases")
        }

        maven {
            url = uri("https://maven.scijava.org/content/repositories/snapshots")
        }

    }
}
