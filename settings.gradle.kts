plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "luaj-ksp"

arrayOf(
    "processor",
    "annotations",
    "sample",
).forEach {
    include(it)
    val subProject = project(":$it")
    subProject.name = "${rootProject.name}-${subProject.name}"
}
