@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.android.application)
}


kotlin {
    jvmToolchain(17)

    androidTarget()
    jvm()
    wasmJs {
        outputModuleName = "composeApp"
        browser {
            val rootDirPath = project.rootDir.path
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                outputFileName = "composeApp.js"
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    static = (static ?: mutableListOf()).apply {
                        // Serve sources to debug inside browser
                        add(rootDirPath)
                        add(projectDirPath)
                    }
                }
            }
        }
        binaries.executable()
    }
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(project(":mediaplayer"))
            implementation(compose.materialIconsExtended)
            implementation(libs.filekit.dialogs.compose)
            implementation(libs.platformtools.darkmodedetector)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activityCompose)
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}

android {
    namespace = "sample.app"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        targetSdk = 35

        applicationId = "sample.app.androidApp"
        versionCode = 1
        versionName = "1.0.0"
    }
}

compose.desktop {
    application {
        mainClass = "sample.app.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "sample"
            packageVersion = "1.0.0"
            linux {
                modules("jdk.security.auth")
            }
            macOS {
                jvmArgs(
                    "-Dapple.awt.application.appearance=system"
                )
            }
        }
    }
}

val buildMacArm: TaskProvider<Exec> = tasks.register<Exec>("buildNativeMacArm") {
    onlyIf { System.getProperty("os.name").startsWith("Mac") }
    workingDir(rootDir)
    commandLine("swiftc", "-emit-library", "-emit-module", "-module-name", "NativeVideoPlayer", 
                "-target", "arm64-apple-macosx14.0", 
                "-o", "mediaplayer/src/jvmMain/resources/darwin-aarch64/libNativeVideoPlayer.dylib", 
                "mediaplayer/src/jvmMain/kotlin/io/github/kdroidfilter/composemediaplayer/mac/native/NativeVideoPlayer.swift", 
                "-O", "-whole-module-optimization")

    inputs.file("mediaplayer/src/jvmMain/kotlin/io/github/kdroidfilter/composemediaplayer/mac/native/NativeVideoPlayer.swift")
    outputs.file("mediaplayer/src/jvmMain/resources/darwin-aarch64/libNativeVideoPlayer.dylib")
}

val buildMacX64: TaskProvider<Exec> = tasks.register<Exec>("buildNativeMacX64") {
    onlyIf { System.getProperty("os.name").startsWith("Mac") }
    workingDir(rootDir)
    commandLine("swiftc", "-emit-library", "-emit-module", "-module-name", "NativeVideoPlayer", 
                "-target", "x86_64-apple-macosx14.0", 
                "-o", "mediaplayer/src/jvmMain/resources/darwin-x86-64/libNativeVideoPlayer.dylib", 
                "mediaplayer/src/jvmMain/kotlin/io/github/kdroidfilter/composemediaplayer/mac/native/NativeVideoPlayer.swift", 
                "-O", "-whole-module-optimization")

    inputs.file("mediaplayer/src/jvmMain/kotlin/io/github/kdroidfilter/composemediaplayer/mac/native/NativeVideoPlayer.swift")
    outputs.file("mediaplayer/src/jvmMain/resources/darwin-x86-64/libNativeVideoPlayer.dylib")
}

val buildWin: TaskProvider<Exec> = tasks.register<Exec>("buildNativeWin") {
    onlyIf { System.getProperty("os.name").startsWith("Windows") }
    workingDir(rootDir.resolve("winlib"))
    commandLine("cmd", "/c", "build.bat")
}

// tâche d’agrégation
tasks.register("buildNativeLibraries") {
    dependsOn(buildMacArm, buildMacX64, buildWin)
}

tasks.register("buildNativeLibsAndRunSampleApp") {
    dependsOn("buildNativeLibraries", ":sample:composeApp:run")
}
