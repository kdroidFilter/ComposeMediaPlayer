@file:OptIn(ExperimentalWasmDsl::class, ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import java.io.ByteArrayOutputStream

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
val javacppVersion = libs.versions.javacpp.get()


tasks.withType<DokkaTask>().configureEach {
    moduleName.set("Compose Media Player")
    offlineMode.set(true)
}


kotlin {
    jvmToolchain(17)
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

    iosX64()
    iosArm64()
    iosSimulatorArm64()


    cocoapods {
        version = version.toString()
        summary = "A multiplatform video player library for Compose applications"
        homepage = "https://github.com/kdroidFilter/Compose-Media-Player"
        name = "ComposeMediaPlayer"

        framework {
            baseName = "ComposeMediaPlayer"
            isStatic = false
            transitiveExport = false // This is default.
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
            implementation(libs.javacpp)
            implementation("org.bytedeco:javacpp:$javacppVersion:windows-x86_64")
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
        minSdk = 23
    }
}

val buildMacArm: TaskProvider<Exec> = tasks.register<Exec>("buildNativeMacArm") {
    onlyIf { System.getProperty("os.name").startsWith("Mac") }
    workingDir(rootDir)
    commandLine(
        "swiftc", "-emit-library", "-emit-module", "-module-name", "NativeVideoPlayer",
        "-target", "arm64-apple-macosx14.0",
        "-o", "mediaplayer/src/jvmMain/resources/darwin-aarch64/libNativeVideoPlayer.dylib",
        "mediaplayer/src/jvmMain/kotlin/io/github/kdroidfilter/composemediaplayer/mac/native/NativeVideoPlayer.swift",
        "-O", "-whole-module-optimization"
    )
}

val buildMacX64: TaskProvider<Exec> = tasks.register<Exec>("buildNativeMacX64") {
    onlyIf { System.getProperty("os.name").startsWith("Mac") }
    workingDir(rootDir)
    commandLine(
        "swiftc", "-emit-library", "-emit-module", "-module-name", "NativeVideoPlayer",
        "-target", "x86_64-apple-macosx14.0",
        "-o", "mediaplayer/src/jvmMain/resources/darwin-x86-64/libNativeVideoPlayer.dylib",
        "mediaplayer/src/jvmMain/kotlin/io/github/kdroidfilter/composemediaplayer/mac/native/NativeVideoPlayer.swift",
        "-O", "-whole-module-optimization"
    )
}

val buildWin: TaskProvider<Exec> = tasks.register<Exec>("buildNativeWin") {
    onlyIf { System.getProperty("os.name").startsWith("Windows") }
    workingDir(rootDir.resolve("winlib"))
    commandLine("cmd", "/c", "build.bat")
}

val buildWinJni: TaskProvider<Exec> = tasks.register<Exec>("buildNativeWinJni") {
    onlyIf {
        System.getProperty("os.name").startsWith("Windows") &&
            !System.getProperty("os.arch").lowercase().contains("arm")
    }
    dependsOn(buildWin)

    notCompatibleWithConfigurationCache("Uses dynamic toolchain discovery and writes argfiles at execution time.")

    val javacppToolClasspath = configurations.detachedConfiguration(
        dependencies.create("org.bytedeco:javacpp:$javacppVersion")
    )
    val jvmMainRuntimeClasspath = configurations.named("jvmMainRuntimeClasspath")
    val jvmMainClasses = tasks.named("jvmMainClasses")
    dependsOn(jvmMainClasses)

    val outputDir = rootDir.resolve("mediaplayer/src/jvmMain/resources/windows-x86_64")
    val includeDir = rootDir.resolve("winlib")
    val linkDir = rootDir.resolve("winlib/build-x64/Release")

    doFirst {
        outputDir.mkdirs()
    }

    doFirst {
        val kotlinStdlibFiles = jvmMainRuntimeClasspath.get().files.filter {
            it.name.startsWith("kotlin-stdlib")
        }
        val jvmKotlinClasses = layout.buildDirectory.dir("classes/kotlin/jvm/main").get().asFile
        val jvmJavaClasses = layout.buildDirectory.dir("classes/java/jvm/main").get().asFile
        val userClasspath = (listOf(jvmKotlinClasses, jvmJavaClasses) + kotlinStdlibFiles)
            .distinct()
        val classpathLine = userClasspath.joinToString(";") { it.absolutePath }
        val javacppJar = javacppToolClasspath.singleFile.absolutePath
        val javaPath = File(System.getProperty("java.home"), "bin/java.exe").absolutePath

        val vswhere = File(
            "C:/Program Files (x86)/Microsoft Visual Studio/Installer/vswhere.exe"
        )
        val vcvars = if (vswhere.exists()) {
            val output = ByteArrayOutputStream()
            exec {
                commandLine(
                    vswhere.absolutePath,
                    "-latest",
                    "-products", "*",
                    "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
                    "-property", "installationPath"
                )
                standardOutput = output
                isIgnoreExitValue = true
            }
            val installPath = output.toString().trim()
            if (installPath.isNotEmpty()) {
                File(installPath, "VC/Auxiliary/Build/vcvars64.bat").absolutePath
            } else {
                null
            }
        } else {
            null
        }

        val argFile = File(outputDir, "javacpp-args.txt")
        argFile.writeText(
            listOf(
                "-jar",
                javacppJar,
                "-classpath",
                classpathLine,
                "-Xcompiler",
                "/D_CRT_SECURE_NO_WARNINGS",
                "-Dplatform=windows-x86_64",
                "-Dplatform.includepath=${includeDir.absolutePath}",
                "-Dplatform.linkpath=${linkDir.absolutePath}",
                "-d",
                outputDir.absolutePath,
                "io.github.kdroidfilter.composemediaplayer.windows.MediaFoundationLib",
            ).joinToString(System.lineSeparator())
        )

        val builderCmd = "\"$javaPath\" \"@${argFile.absolutePath}\""

        val cmd = if (vcvars != null && File(vcvars).exists()) {
            "call \"$vcvars\" && $builderCmd"
        } else {
            builderCmd
        }

        commandLine("cmd", "/c", cmd)
    }
}

// tâche d’agrégation
tasks.register("buildNativeLibraries") {
    dependsOn(buildMacArm, buildMacX64, buildWin, buildWinJni)
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
