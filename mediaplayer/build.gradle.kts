@file:OptIn(ExperimentalWasmDsl::class)

import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.vannitktech.maven.publish)
    alias(libs.plugins.dokka)
}

group = "io.github.kdroidfilter.composemediaplayer"

val ref = System.getenv("GITHUB_REF") ?: ""
val version = if (ref.startsWith("refs/tags/")) {
    val tag = ref.removePrefix("refs/tags/")
    if (tag.startsWith("v")) tag.substring(1) else tag
} else "dev"


tasks.withType<DokkaTask>().configureEach {
    moduleName.set("Compose Media Player")
    offlineMode.set(true)
}


kotlin {
    jvmToolchain(17)
    androidTarget { publishLibraryVariants("release") }
    jvm()
    wasmJs {
        browser()
        binaries.executable()
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
            api(libs.filekit.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kermit)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        androidMain.dependencies {
            implementation(libs.androidcontextprovider)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.ui)
            implementation(libs.androidx.activityCompose)
        }

        androidUnitTest.dependencies {
            implementation(kotlin("test"))
            implementation(kotlin("test-junit"))
            implementation(libs.kotlinx.coroutines.test)
        }

        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.gst1.java.core)
            implementation(libs.jna)
            implementation(libs.jna.platform)
            implementation(libs.slf4j.simple)
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(kotlin("test-junit"))
            implementation(libs.kotlinx.coroutines.test)
        }

        iosMain.dependencies {
        }

        iosTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser.wasm.js)
        }

        wasmJsTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

    }

    //https://kotlinlang.org/docs/native-objc-interop.html#export-of-kdoc-comments-to-generated-objective-c-headers
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
        compilations["main"].compileTaskProvider.configure {
            compilerOptions {
                freeCompilerArgs.add("-Xexport-kdoc")
            }
        }
    }

}

android {
    namespace = "io.github.kdroidfilter.composemediaplayer"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
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
}

val buildMacX64: TaskProvider<Exec> = tasks.register<Exec>("buildNativeMacX64") {
    onlyIf { System.getProperty("os.name").startsWith("Mac") }
    workingDir(rootDir)
    commandLine("swiftc", "-emit-library", "-emit-module", "-module-name", "NativeVideoPlayer",
        "-target", "x86_64-apple-macosx14.0",
        "-o", "mediaplayer/src/jvmMain/resources/darwin-x86-64/libNativeVideoPlayer.dylib",
        "mediaplayer/src/jvmMain/kotlin/io/github/kdroidfilter/composemediaplayer/mac/native/NativeVideoPlayer.swift",
        "-O", "-whole-module-optimization")
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


mavenPublishing {
    coordinates(
        groupId = "io.github.kdroidfilter",
        artifactId = "composemediaplayer",
        version = version.toString()
    )

    pom {
        name.set("Compose Media Player")
        description.set("A multiplatform video player library for Compose applications.")
        inceptionYear.set("2025")
        url.set("https://github.com/kdroidFilter/Compose-Media-Player")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("kdroidfilter")
                name.set("Elyahou Hadass")
                email.set("elyahou.hadass@gmail.com")
            }
        }

        scm {
            connection.set("scm:git:git://github.com/kdroidFilter/Compose-Media-Player.git")
            developerConnection.set("scm:git:ssh://git@github.com:kdroidFilter/Compose-Media-Player.git")
            url.set("https://github.com/kdroidFilter/Compose-Media-Player")
        }
    }

    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    signAllPublications()
}
