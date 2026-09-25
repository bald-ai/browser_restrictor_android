import java.util.Properties
import java.io.File as LintOutputFile

plugins {
    jacoco
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Coverage and machine-readable results are internal to explicit manual sessions.
val manualVerification = providers.environmentVariable("VERIFICATION_SESSION_ROOT").isPresent
val collectQualityCoverage = providers.environmentVariable("QUALITY_COVERAGE").orNull == "1"

// Optional measurement compiler; never used by normal builds or packaged artifacts.
val qualityProbes = providers.gradleProperty("qualityProbes").map(String::toBoolean).getOrElse(false)
check(!collectQualityCoverage || manualVerification) { "Run coverage through scripts/verification-session.py." }
check(!qualityProbes || (manualVerification && collectQualityCoverage)) { "Run measurement probes through scripts/verification-session.py metrics." }
val qualityProbeDirectory = rootProject.layout.projectDirectory.dir("quality/probes")
if (qualityProbes) {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        if (name == "compileDebugKotlin") {
            inputs.file(qualityProbeDirectory.file("build/quality-probes.jar"))
            compilerOptions.freeCompilerArgs.addAll(
                "-Xplugin=${qualityProbeDirectory.file("build/quality-probes.jar").asFile.absolutePath}",
                "-P", "plugin:friction.quality.probes:manifest=${rootProject.file("quality/metrics/kotlin-probes.tsv").absolutePath}",
            )
        }
    }
    tasks.withType<Test>().configureEach {
        systemProperty("quality.probe.events", rootProject.file("quality/metrics/kotlin-probe-events.txt").absolutePath)
        doFirst { rootProject.file("quality/metrics/kotlin-probe-events.txt").writeText("") }
    }
}

jacoco { toolVersion = "0.8.13" }

val cleanTestResults = tasks.register<Delete>("cleanTestResults") {
    delete(layout.buildDirectory.dir("test-cache"))
    doLast {
        // AGP creates these empty directories even with coverage disabled.
        if (!manualVerification) {
            val directory = layout.buildDirectory.dir("outputs/unit_test_code_coverage").get().asFile
            directory.walkBottomUp().filter { it.isDirectory && it.list()?.isEmpty() == true }.forEach { it.delete() }
        }
    }
}

val cleanLintReports = tasks.register<Delete>("cleanLintReports") {
    delete(fileTree(layout.buildDirectory.dir("reports")) { include("lint-results-*.txt") })
    doLast {
        val directory = layout.buildDirectory.dir("reports").get().asFile
        if (directory.list()?.isEmpty() == true) directory.delete()
    }
}
tasks.matching { it.name == "lint" || it.name.matches(Regex("lint(Debug|Release)")) }.configureEach {
    finalizedBy(cleanLintReports)
}

tasks.withType<Test>().configureEach {
    reports.html.required.set(false)
    reports.junitXml.required.set(manualVerification)
    val resultsDirectory = layout.buildDirectory.dir("test-cache/$name")
    binaryResultsDirectory.set(resultsDirectory)
    finalizedBy(cleanTestResults)
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // Compose plus multiple Robolectric SDK sandboxes exceed Gradle's 512 MiB default.
    maxHeapSize = "2g"
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> {
        isEnabled = collectQualityCoverage
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

tasks.register<JacocoReport>("unitCoverage") {
    doFirst { check(manualVerification && collectQualityCoverage) { "Run coverage through scripts/verification-session.py." } }
    dependsOn("testDebugUnitTest")
    executionData(fileTree(layout.buildDirectory) {
        include("jacoco/testDebugUnitTest.exec", "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
    })
    sourceDirectories.setFrom(files("src/main/java"))
    classDirectories.setFrom(fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
        exclude("**/BuildConfig.class", "**/R.class", "**/R$*.class")
    }, fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) {
        exclude("**/BuildConfig.class", "**/R.class", "**/R$*.class")
    })
    reports { xml.required.set(true); html.required.set(false) }
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

val v1BackendUrl = verifierSetting("v1BackendUrl", "V1_BACKEND_URL")

fun buildConfigString(value: String): String = "\"" + value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r") + "\""

val validateV1Configuration by tasks.registering {
    group = "verification"
    doLast {
        check(!qualityProbes) { "Measurement probes must never be packaged." }
        check(v1BackendUrl.startsWith("https://") &&
            !v1BackendUrl.contains("exercise-verifier-production")) {
            "Friction requires an HTTPS v1BackendUrl / V1_BACKEND_URL."
        }
    }
}

// Keep compilation and unit tests available without deployment configuration,
// but never package an APK or bundle with an unusable exercise flow.
androidComponents.onVariants { variant ->
    val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
    tasks.matching { it.name == "package$variantName" || it.name == "bundle$variantName" }
        .configureEach {
            dependsOn(validateV1Configuration)
        }
}

android {
    lint {
        htmlReport = false
        xmlReport = false
        sarifReport = false
        textReport = true
        textOutput = LintOutputFile("stdout")
    }
    if (qualityProbes) sourceSets.getByName("debug").java.srcDir(qualityProbeDirectory.dir("runtime"))
    namespace = "com.michalkrsik.internetrestrictor"
    compileSdk = 36
    testBuildType = providers.gradleProperty("androidTestBuildType").getOrElse("debug")

    defaultConfig {
        // Must remain distinct from the separate Calculator/GPS prototype.
        applicationId = "com.michalkrsik.browserrestrictor"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("boolean", "IS_V1", "true")
        buildConfigField("String", "V1_BACKEND_URL", buildConfigString(v1BackendUrl))

    }

    signingConfigs.getByName("debug") {
        providers.environmentVariable("ANDROID_DEBUG_KEYSTORE_PATH").orNull?.let {
            storeFile = rootProject.file(it)
        }
    }

    buildTypes {
        create("v1Dev") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".v1dev"
            versionNameSuffix = "-v1-dev"
            matchingFallbacks += listOf("debug")
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "IS_V1", "true")

        }
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
    add("v1DevImplementation", "androidx.compose.ui:ui-test-manifest")

    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20250107")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("org.mockito:mockito-core:5.14.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
