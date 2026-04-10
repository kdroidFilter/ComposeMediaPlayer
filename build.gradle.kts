plugins {
    alias(libs.plugins.multiplatform).apply(false)
    alias(libs.plugins.android.library).apply(false)
    alias(libs.plugins.android.application).apply(false)
    alias(libs.plugins.kotlinCocoapods).apply(false)
    alias(libs.plugins.dokka).apply(false)
    alias(libs.plugins.vannitktech.maven.publish).apply(false)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

// Code quality
detekt {
    config.setFrom(files("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
}

subprojects {
    if (name == "composeApp") return@subprojects
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    ktlint {
        debug.set(false)
        verbose.set(true)
        android.set(false)
        outputToConsole.set(true)
        ignoreFailures.set(true)
        enableExperimentalRules.set(true)
        filter {
            exclude("**/generated/**")
            include("**/kotlin/**")
        }
    }
}
