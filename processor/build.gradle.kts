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
    implementation("com.squareup:kotlinpoet-ksp:1.16.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("dev.zacsweers.kctfork:ksp:0.4.1")
    testImplementation("com.github.wagyourtail.luaj:luaj-jse:f062b53a34")
    testImplementation(kotlin("reflect"))
    testImplementation("io.mockk:mockk:1.13.10")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
