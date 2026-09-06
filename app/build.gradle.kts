import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun verifierSetting(propertyName: String, environmentName: String): String =
    providers.gradleProperty(propertyName).orNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: localProperties.getProperty(propertyName)?.trim()?.takeIf { it.isNotEmpty() }
        ?: providers.environmentVariable(environmentName).orNull?.trim().orEmpty()

val verifierUrl = verifierSetting("verifyUrl", "VERIFY_URL")
val verifierSecret = verifierSetting("verifySharedSecret", "VERIFY_SHARED_SECRET")

fun buildConfigString(value: String): String = "\"" + value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r") + "\""

val validateVerifierConfiguration by tasks.registering {
    group = "verification"
    description = "Prevent distributing an app that cannot start exercise verification."
    doLast {
        val missing = buildList {
            if (verifierUrl.isBlank()) add("verifyUrl / VERIFY_URL")
            if (verifierSecret.isBlank()) add("verifySharedSecret / VERIFY_SHARED_SECRET")
        }
        check(missing.isEmpty()) {
            "Cannot package Internet Restrictor: missing ${missing.joinToString()}. " +
                "Set these in local.properties, Gradle properties, or environment variables. " +
                "Exercise recording requires a configured verifier."
        }
    }
}

// Keep compilation and unit tests available without deployment configuration,
// but never package an APK or bundle with an unusable exercise flow.
androidComponents.onVariants { variant ->
    val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
    tasks.matching { it.name == "package$variantName" || it.name == "bundle$variantName" }
        .configureEach { dependsOn(validateVerifierConfiguration) }
}

android {
    namespace = "com.michalkrsik.internetrestrictor"
    compileSdk = 36

    defaultConfig {
        // Must remain distinct from the separate Calculator/GPS prototype.
        applicationId = "com.michalkrsik.browserrestrictor"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        buildConfigField(
            "String",
            "VERIFY_URL",
            buildConfigString(verifierUrl),
        )
        buildConfigField(
            "String",
            "VERIFY_SHARED_SECRET",
            buildConfigString(verifierSecret),
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjspecify-annotations=strict")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.savedstate:savedstate-ktx:1.3.3")
    implementation("androidx.datastore:datastore:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-video:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20250107")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
