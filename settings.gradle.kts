dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = java.net.URI("https://jitpack.io") }
    }
}

rootProject.name = "CloudstreamPlugins"

val disabled = listOf<String>()

// Safely check both directory existence and build.gradle.kts existence
rootProject.projectDir.listFiles()?.forEach { dir ->
    if (dir.isDirectory && !dir.name.startsWith(".") && !disabled.contains(dir.name)) {
        val buildFile = File(dir, "build.gradle.kts")
        if (buildFile.exists()) {
            include(dir.name)
        }
    }
}
