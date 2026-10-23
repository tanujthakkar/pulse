plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register("checkWireIsPureJvm") {
    group = "verification"
    description = "Fail if :wire applies an Android plugin or depends on Android/AndroidX."
    val sourceRoot = layout.projectDirectory.dir("src")
    val compileClasspath = configurations.named("compileClasspath")
    inputs.files(sourceRoot)
    inputs.files(compileClasspath)
    doLast {
        val forbiddenPlugins = listOf("com.android.library", "com.android.application")
        forbiddenPlugins.forEach { pluginId ->
            if (plugins.hasPlugin(pluginId)) {
                throw GradleException(":wire must stay a pure JVM module, but it applied $pluginId")
            }
        }
        val kotlinFiles = sourceRoot.asFile.walkTopDown().filter { it.extension == "kt" }
        val androidImports = kotlinFiles.filter { file ->
            file.readLines().any { line -> line.trimStart().startsWith("import android.") }
        }.toList()
        if (androidImports.isNotEmpty()) {
            throw GradleException(
                ":wire must not import android.*. Found in: " +
                    androidImports.joinToString { it.relativeTo(sourceRoot.asFile).path },
            )
        }
        val androidArtifacts = compileClasspath.get().incoming.resolutionResult.allComponents
            .mapNotNull { it.moduleVersion }
            .filter { module ->
                val group = module.group
                group == "com.android.support" ||
                    group.startsWith("androidx.") ||
                    group.startsWith("com.google.android") ||
                    group.startsWith("android.")
            }
            .map { "${it.group}:${it.name}:${it.version}" }
            .distinct()
        if (androidArtifacts.isNotEmpty()) {
            throw GradleException(
                ":wire must not depend on Android artifacts: ${androidArtifacts.joinToString()}",
            )
        }
    }
}

kover {
    reports {
        filters {
            includes {
                classes("app.pulse.wire.codec.*")
            }
        }
        verify {
            rule {
                minBound(90)
            }
        }
    }
}

tasks.named("check") {
    dependsOn("checkWireIsPureJvm")
    dependsOn("koverVerify")
}
