/*
 * This file is part of Seeneva Android Reader
 * Copyright (C) 2021 Sergei Solodovnikov
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

import com.android.build.gradle.api.ApkVariantOutput
import com.android.build.gradle.api.ApplicationVariant
import com.android.build.gradle.internal.dsl.SigningConfig
import extension.signProperties

plugins {
    kotlin("kapt")
}

// True if this build is running in CI
val buildUsingCI = !System.getenv("CI").isNullOrEmpty()

android {
    buildFeatures {
        viewBinding = true
    }

    splits {
        abi {
            isEnable = !hasProperty("seeneva.disableSplitApk")

            reset()

            include(*Abi.values().map { it.abiName }.toTypedArray())

            isUniversalApk = true
        }
    }

    signingConfigs {
        register("release") {
            applyPropertiesSigning()

            isV1SigningEnabled = true
            isV2SigningEnabled = true
        }
        named("debug") {
            if (buildUsingCI) {
                // Allow override signing properties on build started by CI
                applyPropertiesSigning()
            }
        }
    }

    defaultConfig {
        applicationId = "app.seeneva.reader"

        resConfigs("en", "ru")
        vectorDrawables.useSupportLibrary = true

        missingDimensionStrategy(RustBuildTypeFlavor.NAME, RustBuildTypeFlavor.RUST_RELEASE)
    }

    buildTypes {
        named("release") {
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            signingConfig = signingConfigs["release"]

            ndk {
                // https://developer.android.com/studio/build/shrink-code.html#native-crash-support
                debugSymbolLevel = if (hasProperty(extension.PROP_NO_DEB_SYMBOLS)) {
                    "none"
                } else {
                    "full"
                }
            }
        }
        named("debug") {
            isMinifyEnabled = false
            isDebuggable = true

            applicationIdSuffix = ".debug"
        }
    }

    flavorDimensions(AppStoreFlavor.NAME)

    productFlavors {
        register(AppStoreFlavor.GOOGLE_PLAY) {
            dimension = AppStoreFlavor.NAME
        }

        register(AppStoreFlavor.FDROID) {
            dimension = AppStoreFlavor.NAME

            versionNameSuffix = "-fdroid"
            //TODO This will be used in future releases (e.g show donate button in the application)
        }
        register(AppStoreFlavor.GITHUB) {
            dimension = AppStoreFlavor.NAME

            versionNameSuffix = "-gh"
        }
    }

    packagingOptions {
        // https://github.com/Kotlin/kotlinx.coroutines#avoiding-including-the-debug-infrastructure-in-the-resulting-apk
        exclude("DebugProbesKt.bin")
        // Not needed right now, but should return if I will use web connections
        exclude("okhttp3/**/publicsuffixes.gz")
    }

    if (buildUsingCI) {
        applicationVariants.configureEach(::configureOutputName)
    }

//    testOptions{
//        unitTests.setIncludeAndroidResources(true)
//    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    implementation(project(":logic")) {
        exclude("androidx.viewpager")
    }

    implementation(Deps.ANDROIDX_APPCOMPAT) {
        exclude("androidx.viewpager")
    }
    implementation(Deps.ANDROIDX_VIEW_PAGER) {
        exclude("androidx.viewpager")
    }
    implementation(Deps.ANDROIDX_RECYCLER_VIEW)
    implementation(Deps.ANDROIDX_RECYCLER_VIEW_SELECTION)
    implementation(Deps.ANDROIDX_FRAGMENT_KTX) {
        exclude("androidx.viewpager")
    }
    implementation(Deps.ANDROIDX_LIFECYCLE_SERVICE)
    implementation(Deps.ANDROIDX_LIFECYCLE_VIEWMODEL)
    implementation(Deps.ANDROIDX_LIFECYCLE_LIVEDATA)
    implementation(Deps.ANDROIDX_LIFECYCLE_JAVA8)
    implementation(Deps.ANDROIDX_PAGING_RUNTIME)
    implementation(Deps.ANDROIDX_CONSTRAINT_LAYOUT)
    implementation(Deps.ANDROIDX_WORK_RUNTIME)
    implementation(Deps.ANDROIDX_SWIPE_REFRESH_LAYOUT)

    implementation(Deps.MATERIAL) {
        exclude("androidx.viewpager")
    }

    implementation(Deps.KOIN_ANDROIDX_VIEWMODEL) {
        exclude("androidx.viewpager")
    }
    implementation(Deps.KOIN_ANDROIDX_WORKMANAGER)

    implementation(Deps.SCALE_IMAGE_VIEW)
}

/**
 * Apply signing params from the Java properties file or Gradle properties if properties file is not provided
 * @param propertiesFileName Java properties file which should be used
 */
fun SigningConfig.applyPropertiesSigning(propertiesFileName: String = "keystore.properties") {
    // Add `keystore.properties` to provide data needed for app signing process:
    // seeneva.storeFile=/path/to/keystore
    // seeneva.storePassword=
    // seeneva.keyAlias=
    // seeneva.keyPassword=

    val signProperties = signProperties(propertiesFileName) ?: return

    storeFile = file(signProperties[extension.PROP_STORE_FILE] as String).absoluteFile
    storePassword = signProperties[extension.PROP_STORE_PASS] as String
    keyAlias = signProperties[extension.PROP_KEY_ALIAS] as String
    keyPassword = signProperties[extension.PROP_KEY_PASS] as String
}

/**
 * Configure naming of output APKs
 * @param variant build variant to configure
 */
fun configureOutputName(variant: ApplicationVariant) {
    val abiSplitEnabled = android.splits.abi.isEnable

    variant.outputs
        .withType<ApkVariantOutput>()
        .configureEach {
            val outputFilters = filters

            outputFileName = buildString {
                append("seeneva-${variant.versionName}")

                if (abiSplitEnabled) {
                    append('-')
                    append(if (outputFilters.isEmpty()) {
                        "universal"
                    } else {
                        outputFilters.joinToString("-") { it.identifier }
                    })
                }

                if (variant.buildType.isDebuggable) {
                    append("-debug")
                }

                if (!variant.isSigningReady) {
                    append("-unsigned")
                }

                append(".apk")
            }
        }
}