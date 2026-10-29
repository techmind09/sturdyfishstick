dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = java.net.URI("https://jitpack.io") }
    }
}

rootProject.name = "CloudstreamPlugins"

// List any directory names you want to temporarily disable
val disabled = listOf<String>()

// Automatically include all directories containing a build.gradle.kts file
File(rootDir, ".").listFiles()?.forEach { dir ->
    if (dir.isDirectory && !dir.name.startsWith(".") && !disabled.contains(dir.name)) {
        if (File(dir, "build.gradle.kts").exists()) {
            include(dir.name)
        }
    }
}
