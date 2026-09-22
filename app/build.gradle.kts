plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

var applicationName = "坤音"
var verName = "26.6.7"
var verCode = 26607

val signingPropertyNames = listOf(
    "KEYSTORE_STORE_FILE",
    "KEYSTORE_PASSWORD",
    "KEYSTORE_KEY_ALIAS",
    "KEYSTORE_KEY_PASSWORD"
)
val signingProperties = signingPropertyNames.associateWith {
    providers.gradleProperty(it).orNull
}
val suppliedSigningProperties = signingProperties.filterValues { !it.isNullOrBlank() }

if (suppliedSigningProperties.isNotEmpty() &&
    suppliedSigningProperties.size != signingPropertyNames.size
) {
    val missing = signingPropertyNames - suppliedSigningProperties.keys
    throw GradleException(
        "Incomplete release signing configuration. Missing Gradle properties: " +
                missing.joinToString()
    )
}
val hasReleaseSigningConfig = suppliedSigningProperties.size == signingPropertyNames.size

android {
    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = rootProject.file(
                    signingProperties.getValue("KEYSTORE_STORE_FILE")!!
                )
                storePassword =
                    signingProperties.getValue("KEYSTORE_PASSWORD")
                keyAlias = signingProperties.getValue("KEYSTORE_KEY_ALIAS")
                keyPassword = signingProperties.getValue("KEYSTORE_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }
    namespace = "com.ikunshare.sound"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.ikunshare.sound"
        minSdk = 24
        targetSdk = 37
        versionName = verName
        versionCode = verCode
        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
        buildConfigField(
            "String",
            "GIT_COMMIT",
            "\"${
                runCatching {
                    providers.exec {
                        commandLine("git", "rev-parse", "--short", "HEAD")
                    }.standardOutput.asText.get().trim()
                }.getOrDefault("unknown")
            }\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            multiDexEnabled = true
            signingConfig = signingConfigs.findByName("release")
        }
        getByName("debug") {
            isJniDebuggable = true
            isMinifyEnabled = false
            multiDexEnabled = false
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

base {
    archivesName.set("${applicationName}-v${verName}")
}

kotlin {
    compilerOptions {
        freeCompilerArgs = listOf(
            "-XXLanguage:+PropertyParamAnnotationDefaultTargetMode"
        )
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("11"))
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.appcompat.resources)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.media)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.google.gson)
    implementation(libs.okhttp)
    implementation(libs.liquid.glass)
    implementation(libs.lyricon.provider)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
}
