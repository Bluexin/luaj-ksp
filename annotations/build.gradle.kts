plugins {
    kotlin("jvm") version "1.9.23"
    id("publish")
}

group = "be.bluexin"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}