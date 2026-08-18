import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    application
}

group = "com.fakestripe"
version = "0.1.2"

repositories {
    mavenCentral()
}

val ktorVersion = "3.5.2"
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
    testImplementation("com.stripe:stripe-java:33.1.0")
}

application {
    mainClass.set("com.fakestripe.ApplicationKt")
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "11"
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
