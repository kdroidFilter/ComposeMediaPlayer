@file:OptIn(ExperimentalWasmDsl::class)

import com.vanniktech.maven.publish.SonatypeHost
import org.gradle.kotlin.dsl.withType
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import java.util.*

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.vannitktech.maven.publish)
    alias(libs.plugins.dokka)
}

group = "io.github.kdroidfilter.composemediaplayer"
version = "0.3.1"

val osName = System.getProperty("os.name").lowercase(Locale.getDefault())
val osArch = System.getProperty("os.arch").lowercase(Locale.getDefault())

val fxClassifier = when {
    osName.contains("linux") && osArch.contains("aarch64") -> "linux-aarch64"
    osName.contains("linux") -> "linux"
    osName.contains("windows") -> "win"
    osName.contains("mac") && osArch.contains("aarch64") -> "mac-aarch64"
    osName.contains("mac") -> "mac"
    else -> throw IllegalStateException("Unsupported OS: $osName")
}

val javafxVersion = "22.0.1"

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
            implementation(libs.kotlin.logging)
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


        }

        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.gst1.java.core)
            implementation(libs.gst1.java.swing)
            implementation(libs.jna)
            implementation(libs.jna.platform)
            implementation(libs.slf4j.simple)

            compileOnly("org.openjfx:javafx-base:${javafxVersion}:${fxClassifier}")
            compileOnly("org.openjfx:javafx-graphics:${javafxVersion}:${fxClassifier}")
            compileOnly("org.openjfx:javafx-swing:${javafxVersion}:${fxClassifier}")
            compileOnly("org.openjfx:javafx-media:${javafxVersion}:${fxClassifier}")
        }

        iosMain.dependencies {
        }

        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser.wasm.js)
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
