@file:OptIn(ExperimentalWasmDsl::class)

import io.github.kdroidfilter.nucleus.desktop.application.dsl.CompressionLevel
import org.apache.tools.ant.taskdefs.condition.Os
import io.github.kdroidfilter.nucleus.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.android.application)
    alias(libs.plugins.nucleus)
}


kotlin {
    jvmToolchain(17)

    @Suppress("DEPRECATION")
    androidTarget()
    jvm()
    js(IR) {
        outputModuleName.set("composeApp")
        browser {
            val rootDirPath = project.rootDir.path
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                outputFileName = "composeApp.js"
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    // Serve sources to debug inside browser
                    static(rootDirPath)
                    static(projectDirPath)
                }
            }
        }
        binaries.executable()
    }
    wasmJs {
        outputModuleName.set("composeApp")
        browser {
            val rootDirPath = project.rootDir.path
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                outputFileName = "composeApp.js"
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    // Serve sources to debug inside browser
                    static(rootDirPath)
                    static(projectDirPath)
                }
            }
        }
        binaries.executable()
    }
    if (Os.isFamily(Os.FAMILY_MAC)) {
        listOf(
            iosArm64(),
            iosSimulatorArm64(),
        ).forEach {
            it.binaries.framework {
                baseName = "ComposeApp"
                isStatic = true
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.compose.material.icons.extended)
            implementation(project(":mediaplayer"))
            implementation(libs.filekit.dialogs.compose)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activityCompose)
            implementation(libs.androidx.core)
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.nucleus.graalvm.runtime)
        }
        webMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
    }
}

android {
    namespace = "sample.app"
    compileSdk = 36

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = 36

        applicationId = "sample.app.androidApp"
        versionCode = 1
        versionName = "1.0.0"
    }
}

dependencies {
    debugImplementation(libs.compose.ui.tooling)
}

nucleus.application {
    mainClass = "sample.app.MainKt"

    nativeDistributions {
        targetFormats(TargetFormat.Dmg, TargetFormat.Nsis, TargetFormat.Deb)
        packageName = "Compose Media Player"
        description = "A Kotlin Multiplatform media player built with Compose"
        vendor = "KDroidFilter"
        cleanupNativeLibs = true
        packageVersion = "1.0.0"
        compressionLevel = CompressionLevel.Maximum
        windows {
            shortcut = true
        }
    }

    graalvm {
        isEnabled = true
        imageName = "compose-media-player"
        javaLanguageVersion = 25
        jvmVendor = JvmVendorSpec.BELLSOFT
        buildArgs.addAll(
            "-H:+AddAllCharsets",
            "-Djava.awt.headless=false"
        )
    }
}


