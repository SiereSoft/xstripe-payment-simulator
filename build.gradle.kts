import org.gradle.jvm.tasks.Jar

plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
    application
}

group = "com.fakestripe"
// The single source of the simulator's version: /v1/admin/health, the Server header, the
// webhook User-Agent and the service info all read it from the generated resource below.
version = "0.3.0"

repositories {
    mavenCentral()
}

val ktorVersion = "2.3.12"
val logbackVersion = "1.6.1"
val serializationVersion = "1.6.3"
val nettyVersion = "4.1.136.Final"

dependencies {
    // Ktor 2.3.12 pulls Netty 4.1.111, which carries several advisories including
    // two high-severity request-smuggling issues. Constrain the whole Netty graph
    // to a patched line without changing the Ktor version.
    implementation(platform("io.netty:netty-bom:$nettyVersion"))

    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers-jvm:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation(kotlin("test"))
    // The "killer test": Stripe's own official SDK, unmodified, pointed at the simulator.
    testImplementation("com.stripe:stripe-java:33.2.0")
}

// The shipped image runs on a Java 11 JRE, so compile against and test on JDK 11 even when
// Gradle itself runs on a newer JDK.
kotlin {
    jvmToolchain(11)
}

application {
    mainClass.set("com.fakestripe.MainKt")
}

val generateVersion by tasks.registering {
    val out = layout.buildDirectory.dir("generated/version")
    val v = project.version.toString()
    inputs.property("version", v)
    outputs.dir(out)
    doLast {
        val file = out.get().file("fake-stripe-version.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$v\n")
    }
}
sourceSets.main { resources.srcDir(generateVersion) }

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

kover {
    reports {
        filters {
            excludes {
                // Only the trivial process entry point (Main.kt): it reads the real environment and
                // blocks until the listeners stop. Everything it calls is covered by tests.
                classes("com.fakestripe.MainKt")
            }
        }
        verify {
            rule("Line coverage stays at or above 90 %") {
                minBound(90)
            }
        }
    }
}

tasks.check { dependsOn(tasks.koverVerify) }

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
