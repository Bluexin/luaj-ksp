plugins {
    `kotlin-dsl`
//    `kotlin-dsl-precompiled-script-plugins`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}