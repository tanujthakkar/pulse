import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.pulse"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.pulse"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "app.pulse.PulseTestRunner"
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":feature:connect"))
    implementation(project(":feature:live"))
    implementation(project(":feature:sync"))
    implementation(project(":feature:debug"))
    implementation(project(":link"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.intents)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}

androidComponents {
    onVariants { variant ->
        val capital = variant.name.replaceFirstChar { it.uppercase() }
        val merged = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
        tasks.register("check${capital}ManifestHasNoInternet") {
            group = "verification"
            description = "Fail if the ${variant.name} merged manifest declares INTERNET."
            inputs.file(merged)
            doLast {
                val manifest = merged.get().asFile
                if (manifest.readText().contains("android.permission.INTERNET")) {
                    throw GradleException("android.permission.INTERNET is forbidden in $manifest")
                }
            }
        }
        tasks.register("check${capital}ManifestHasNoLocation") {
            group = "verification"
            description = "Fail if the ${variant.name} merged manifest declares a location permission."
            inputs.file(merged)
            doLast {
                val manifest = merged.get().asFile
                val text = manifest.readText()
                val forbidden = listOf(
                    "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.ACCESS_COARSE_LOCATION",
                ).filter { text.contains(it) }
                if (forbidden.isNotEmpty()) {
                    throw GradleException(
                        "Location permission is forbidden in $manifest: $forbidden",
                    )
                }
                if (!text.contains("neverForLocation")) {
                    throw GradleException(
                        "BLUETOOTH_SCAN must set neverForLocation in $manifest",
                    )
                }
            }
        }
    }
}

afterEvaluate {
    tasks.named("check").configure {
        dependsOn(tasks.matching { it.name.endsWith("ManifestHasNoInternet") })
        dependsOn(tasks.matching { it.name.endsWith("ManifestHasNoLocation") })
    }
}
