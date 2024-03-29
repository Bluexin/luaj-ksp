plugins {
    kotlin("jvm")
    id("publish")
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
    implementation(project(":luaj-ksp-annotations"))
    implementation("com.google.devtools.ksp:symbol-processing-api:1.9.23-1.0.19")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("dev.zacsweers.kctfork:ksp:0.4.1")
    testImplementation("com.github.wagyourtail.luaj:luaj-jse:05e2b7d76a")
    testImplementation(kotlin("reflect"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
