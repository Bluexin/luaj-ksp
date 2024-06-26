import com.google.devtools.ksp.gradle.KspTaskJvm

plugins {
    kotlin("jvm") version "1.9.23"
    id("com.google.devtools.ksp") version "1.9.23-1.0.19"
    idea
}

group = "be.bluexin"

repositories {
    mavenCentral()
    maven {
        name = "JitPack"
        url = uri("https://jitpack.io")
    }
}

dependencies {
    ksp(project(":luaj-ksp-processor"))
    implementation(kotlin("stdlib-jdk8"))
    implementation(project(":luaj-ksp-annotations"))
    implementation("com.github.wagyourtail.luaj:luaj-jse:f062b53a34")
    implementation("io.github.oshai:kotlin-logging-jvm:5.1.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.3")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation(kotlin("reflect"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)

    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
        resources.srcDir("build/generated/ksp/main/resources")
    }
    sourceSets.test {
        kotlin.srcDir("build/generated/ksp/test/kotlin")
        resources.srcDir("build/generated/ksp/test/resources")
    }
}

tasks.withType<ProcessResources> {
    duplicatesStrategy = DuplicatesStrategy.WARN
}

ksp {

}
