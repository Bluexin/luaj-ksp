plugins {
    kotlin("jvm")
}

group = "be.bluexin"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":luaj-ksp-annotations"))
    implementation("com.google.devtools.ksp:symbol-processing-api:1.9.23-1.0.19")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}
