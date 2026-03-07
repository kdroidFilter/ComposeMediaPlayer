@file:OptIn(ExperimentalWasmDsl::class)

import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.vannitktech.maven.publish)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlinCocoapods)
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
    @Suppress("DEPRECATION")
    androidTarget { publishLibraryVariants("release") }
    jvm()
    js {
        browser()
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
        iosX64(),
    ).forEach { target ->
        target.compilations.getByName("main") {
            // The default file path is src/nativeInterop/cinterop/<interop-name>.def
            val nskeyvalueobserving by cinterops.creating
        }
    }


    cocoapods {
        version = version.toString()
        summary = "A multiplatform video player library for Compose applications"
        homepage = "https://github.com/kdroidFilter/Compose-Media-Player"
        name = "ComposeMediaPlayer"

        framework {
            baseName = "ComposeMediaPlayer"
            isStatic = false
            @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
            transitiveExport = false
        }

        // Maps custom Xcode configuration to NativeBuildType
        xcodeConfigurationToNativeBuildType["CUSTOM_DEBUG"] = NativeBuildType.DEBUG
        xcodeConfigurationToNativeBuildType["CUSTOM_RELEASE"] = NativeBuildType.RELEASE
    }

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
            implementation(libs.androidx.core)
        }

        androidUnitTest.dependencies {
            implementation(kotlin("test"))
            implementation(kotlin("test-junit"))
            implementation(libs.kotlinx.coroutines.test)
        }

        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.gst1.java.core)
            implementation(libs.jna.jpms)
            implementation(libs.jna.platform.jpms)
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

        webMain.dependencies {
            implementation(libs.kotlinx.browser)
            implementation(compose.ui)

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
    compileSdk = 36

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

val nativeResourceDir = layout.projectDirectory.dir("src/jvmMain/resources")

val buildNativeMacOs by tasks.registering(Exec::class) {
    description = "Compiles the Swift native library into macOS dylibs (arm64 + x64)"
    group = "build"
    val hasPrebuilt = nativeResourceDir
        .dir("darwin-aarch64")
        .file("libNativeVideoPlayer.dylib")
        .asFile
        .exists()
    enabled = Os.isFamily(Os.FAMILY_MAC) && !hasPrebuilt

    val nativeDir = layout.projectDirectory.dir("src/jvmMain/native/macos")
    inputs.dir(nativeDir)
    outputs.dir(nativeResourceDir)
    workingDir(nativeDir)
    commandLine("bash", "build.sh")
}

val buildNativeWindows by tasks.registering(Exec::class) {
    description = "Compiles the C++ native library into Windows DLLs (x64 + ARM64)"
    group = "build"
    val hasPrebuilt = nativeResourceDir
        .dir("win32-x86-64")
        .file("NativeVideoPlayer.dll")
        .asFile
        .exists()
    enabled = Os.isFamily(Os.FAMILY_WINDOWS) && !hasPrebuilt

    val nativeDir = layout.projectDirectory.dir("src/jvmMain/native/windows")
    inputs.dir(nativeDir)
    outputs.dir(nativeResourceDir)
    workingDir(nativeDir)
    commandLine("cmd", "/c", nativeDir.file("build.bat").asFile.absolutePath)
}

tasks.configureEach {
    if (name == "jvmProcessResources" || name == "sourcesJar") {
        dependsOn(buildNativeMacOs, buildNativeWindows)
    }
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

    publishToMavenCentral()
    signAllPublications()
}
