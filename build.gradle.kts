buildscript {
    val atomicfuVersion: String by project
    dependencies {
        classpath("org.jetbrains.kotlinx:atomicfu-gradle-plugin:$atomicfuVersion")
    }
}
apply(plugin = "kotlinx-atomicfu")

plugins {
    java
    kotlin("jvm") version "1.8.0"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))
    testImplementation(kotlin("test"))
    implementation("org.jctools:jctools-core:3.1.0")
    implementation("com.googlecode.concurrent-trees:concurrent-trees:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.0")
    val junitVersion: String by project
    testImplementation("junit:junit:$junitVersion")
    val lincheckVersion: String by project
    testImplementation("org.jetbrains.kotlinx:lincheck-jvm:$lincheckVersion")
}

kotlin {
    // Use or download latest jdk.
    // Remove to use custom jdk version.
    jvmToolchain(19)
}

tasks {
    withType<Test> {
        // Remove these arguments for Java 8 and older versions
        jvmArgs("--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-exports", "java.base/jdk.internal.util=ALL-UNNAMED",
            "--add-exports", "java.base/sun.security.action=ALL-UNNAMED")
        maxHeapSize = "2g"
    }
}