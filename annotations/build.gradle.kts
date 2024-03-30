plugins {
    kotlin("jvm") version "1.9.23"
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
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    compileOnly("com.github.wagyourtail.luaj:luaj-jse:05e2b7d76a")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}