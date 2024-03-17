plugins {
    kotlin("jvm") version "1.9.23" apply false
}

group = "be.bluexin"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

buildscript {
    dependencies {
        classpath(kotlin("gradle-plugin", version = "1.9.23"))
    }
}
