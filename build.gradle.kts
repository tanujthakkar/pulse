plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

tasks.register("checkNoInternetPermission") {
    group = "verification"
    description = "Fail if any manifest declares android.permission.INTERNET."
    val root = layout.projectDirectory
    doLast {
        val hits = root.asFile.walkTopDown()
            .onEnter { dir -> dir.name != "build" && dir.name != ".gradle" && dir.name != ".git" }
            .filter { it.isFile && it.name == "AndroidManifest.xml" }
            .filter { file ->
                file.readText().contains("android.permission.INTERNET")
            }
            .toList()
        if (hits.isNotEmpty()) {
            throw GradleException(
                "android.permission.INTERNET is forbidden. Found in: " +
                    hits.joinToString { it.relativeTo(root.asFile).path },
            )
        }
    }
}

tasks.register("checkNoLocationPermission") {
    group = "verification"
    description = "Fail if any manifest declares ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION."
    val root = layout.projectDirectory
    doLast {
        val forbidden = listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
        )
        val hits = root.asFile.walkTopDown()
            .onEnter { dir -> dir.name != "build" && dir.name != ".gradle" && dir.name != ".git" }
            .filter { it.isFile && it.name == "AndroidManifest.xml" }
            .filter { file -> forbidden.any { needle -> file.readText().contains(needle) } }
            .toList()
        if (hits.isNotEmpty()) {
            throw GradleException(
                "Location permission is forbidden. Found in: " +
                    hits.joinToString { it.relativeTo(root.asFile).path },
            )
        }
    }
}

subprojects {
    afterEvaluate {
        tasks.findByName("check")?.dependsOn(rootProject.tasks.named("checkNoInternetPermission"))
        tasks.findByName("check")?.dependsOn(rootProject.tasks.named("checkNoLocationPermission"))
    }
}
