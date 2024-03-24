plugins {
    `java-library` apply false
    `maven-publish`
}

java {
    withSourcesJar()
}

publishing {
    publications {
        register<MavenPublication>("maven") {
            from(components["java"])
        }
    }

    repositories {
        if (project.hasProperty("local")) maven {
            name = "InBuild"
            url = uri(rootProject.layout.buildDirectory.get().asFile.toURI().resolve("localPublication"))
        } else if (System.getenv("CI") != null) maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/bluexin/luaj-ksp")
            credentials {
                username = System.getenv("USERNAME")
                password = System.getenv("TOKEN")
            }
        }
    }
}