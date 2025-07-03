plugins {
    id("qupath-conventions")
    `maven-publish`
}

qupathExtension {
    name = "qupath-extension-instanseg"
    version = "0.1.0"
    group = "io.github.qupath"
    description = "A QuPath extension for running inference with the InstanSeg deep learning model"
    automaticModule = "qupath.extension.instanseg"
}

dependencies {

    implementation(libs.bundles.qupath)
    implementation(libs.bundles.logging)
    implementation(libs.qupath.fxtras)
    implementation(libs.bundles.markdown)

    implementation(libs.bioimageio.spec)
    implementation(libs.deepJavaLibrary)
    implementation("io.github.qupath:qupath-extension-djl:0.4.0")

    // For testing
    testImplementation(libs.junit)

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
