plugins {
    `java-library`
    `maven-publish`
    // Include this plugin to avoid downloading JavaCPP dependencies for all platforms
    id("org.bytedeco.gradle-javacpp-platform")
    id("org.openjfx.javafxplugin")
}

val moduleName = "io.github.qupath.extension.instanseg"

base {
    archivesName = rootProject.name
    version = "0.0.1-SNAPSHOT"
    description = "A QuPath extension for running inference with the InstanSeg deep learning model"
    group = "io.github.qupath"
}

javafx {
    version = libs.versions.jdk.get()
    modules = listOf(
        "javafx.controls",
        "javafx.fxml",
        "javafx.web"
    )
}

val qupathVersion = gradle.extra["qupath.app.version"]

/**
 * Define dependencies.
 * - Using 'shadow' indicates that they are already part of QuPath, so you don't need
 *   to include them in your extension. If creating a single 'shadow jar' containing your
 *   extension and all dependencies, these won't be added.
 * - Using 'implementation' indicates that you need the dependency for the extension to work,
 *   and it isn't part of QuPath already. If you are creating a single 'shadow jar', the
 *   dependency should be bundled up in the extension.
 * - Using 'testImplementation' indicates that the dependency is only needed for testing,
 *   but shouldn't be bundled up for use in the extension.
 */
dependencies {
    // Main QuPath user interface jar.
    // Automatically includes other QuPath jars as subdependencies
    implementation("io.github.qupath:qupath-gui-fx:${qupathVersion}")
    
    // For logging - the version comes from QuPath's version catalog at
    // https://github.com/qupath/qupath/blob/main/gradle/libs.versions.toml
    implementation(libs.slf4j)

    implementation(libs.qupath.fxtras)
    implementation(libs.bioimageio.spec)
    implementation(libs.deepJavaLibrary)
    implementation(libs.commonmark)

    implementation("io.github.qupath:qupath-extension-djl:0.4.0-SNAPSHOT")

    testImplementation("io.github.qupath:qupath-gui-fx:${qupathVersion}")
    testImplementation(libs.junit)
}

/*
 * Manifest info
 */
tasks.withType<Jar> {
    manifest {
        attributes(
            mapOf("Implementation-Title" to project.name,
                "Implementation-Version" to project.version,
                "Automatic-Module-Name" to moduleName)
        )
    }
}

/*
 * Copy the LICENSE file into the jar... if we have one (we should!)
 */
tasks.processResources {
  from ("${projectDir}/LICENSE") {
    into("licenses/")
  }
}

/*
 * Define extra 'copyDependencies' task to copy dependencies into the build directory.
 */
tasks.register<Copy>("copyDependencies") {
    description = "Copy dependencies into the build directory for use elsewhere"
    group = "QuPath"

    from(configurations.default)
    into("build/libs")
}

/*
 * Ensure Java compatibility, and include sources and javadocs when building
 */
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.jdk.get())
    }
    withSourcesJar()
    withJavadocJar()
}

/*
 * Create javadocs for all modules/packages in one place.
 * Use -PstrictJavadoc=true to fail on error with doclint (which is rather strict).
 */
tasks.withType<Javadoc> {
	options.encoding = "UTF-8"
	val strictJavadoc = providers.gradleProperty("strictJavadoc").getOrElse("false")
	if (!"true".equals(strictJavadoc, true)) {
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
	}
}

/*
 * Specify that the encoding should be UTF-8 for source files
 */
tasks.withType<JavaCompile> {
    // Include parameter names, so they are available in the script editor via reflection
    options.compilerArgs = listOf("-parameters")
    // Specify source should be UTF8
    options.encoding = "UTF-8"
}

/*
 * Avoid 'Entry .gitkeep is a duplicate but no duplicate handling strategy has been set.'
 * when using withSourcesJar()
 */
tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

/*
 * Support tests with JUnit.
 */
tasks.test {
    useJUnitPlatform()
}

// Looks redundant to include this here and in settings.gradle.kts,
// but helps overcome some gradle trouble when including this as a subproject
// within QuPath itself (which is useful during development).
repositories {

    if ("true" == providers.gradleProperty("use-maven-local").getOrElse("false")) {
        logger.warn("Using Maven local")
        mavenLocal()
    }

    mavenCentral()

    // Add scijava - which is where QuPath's jars are hosted
    maven {
        url = uri("https://maven.scijava.org/content/repositories/releases")
    }

    maven {
        url = uri("https://maven.scijava.org/content/repositories/snapshots")
    }

}


publishing {
    repositories {
        maven {
            name = "SciJava"
            val releasesRepoUrl = uri("https://maven.scijava.org/content/repositories/releases")
            val snapshotsRepoUrl = uri("https://maven.scijava.org/content/repositories/snapshots")
            // Use gradle -Prelease publish
            url = if (project.hasProperty("release")) releasesRepoUrl else snapshotsRepoUrl
            credentials {
                username = System.getenv("MAVEN_USER")
                password = System.getenv("MAVEN_PASS")
            }
        }
    }

    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                licenses {
                    license {
                        name = "Apache License v2.0"
                        url = "http://www.apache.org/licenses/LICENSE-2.0"
                    }
                }
            }
        }
    }
}