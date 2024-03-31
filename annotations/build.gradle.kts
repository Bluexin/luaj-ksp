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
    compileOnly("com.github.wagyourtail.luaj:luaj-jse:f062b53a34")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}