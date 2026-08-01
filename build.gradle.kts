import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.2.20"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("org.jetbrains.dokka") version "2.0.0"
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
    id("ru.vyarus.animalsniffer") version "2.0.1"
    `maven-publish`
}

group = "io.hafa"
version = "0.1.0"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Emit StringBuilder concatenation rather than invokedynamic/StringConcatFactory,
        // which doesn't exist below Android 34. D8 desugars it, but generating it inline
        // keeps the bytecode itself free of post-API-21 references so animalsniffer can
        // actually verify the Android floor instead of trusting the toolchain.
        freeCompilerArgs.add("-Xstring-concat=inline")
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    // api, not implementation: SessionOptions.httpClient exposes OkHttpClient publicly.
    api("com.squareup.okhttp3:okhttp:5.4.0")

    // Android API-21 signatures; the automated check behind the minSdk-21 claim.
    signature("com.toasttab.android:gummy-bears-api-21:0.15.0@signature")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.4.0")
    testImplementation("com.squareup.okhttp3:mockwebserver3-junit5:5.4.0")
}

tasks.test {
    useJUnitPlatform()
}

// Verify only our own classes against the Android signatures; dependencies ship their
// own compatibility guarantees and would drown the report.
animalsniffer {
    sourceSets = listOf(java.sourceSets.main.get())
}

// Full default rule set plus a public-API doc gate; deviations in config/detekt/detekt.yml.
detekt {
    source.setFrom("src/main/kotlin")
    config.setFrom("$projectDir/config/detekt/detekt.yml")
    buildUponDefaultConfig = true
}

kover {
    reports {
        verify {
            rule {
                bound {
                    minValue = 90
                }
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.named("koverVerify"))
}

// Bundles Dokka's HTML as a javadoc-classified jar; JitPack serves it at javadoc.jitpack.io.
val dokkaJavadocJar by tasks.registering(Jar::class) {
    dependsOn(tasks.named("dokkaGeneratePublicationHtml"))
    from(layout.buildDirectory.dir("dokka/html"))
    archiveClassifier.set("javadoc")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "rmapi-kt"
            from(components["java"])
            artifact(dokkaJavadocJar)
            pom {
                name.set("rmapi-kt")
                description.set(
                    "Kotlin/JVM client for the reMarkable cloud API, a port of rmapi-js",
                )
                url.set("https://github.com/hafaio/rmapi-kt")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("erikbrinkman")
                        name.set("Erik Brinkman")
                        email.set("erik.brinkman@gmail.com")
                    }
                }
                scm {
                    url.set("https://github.com/hafaio/rmapi-kt")
                    connection.set("scm:git:https://github.com/hafaio/rmapi-kt.git")
                    developerConnection.set("scm:git:git@github.com:hafaio/rmapi-kt.git")
                }
            }
        }
    }
}
