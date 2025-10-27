pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Views"
include(":app")

// Use git submodule for Quartz dependency
includeBuild("${rootDir}/external/amethyst") {
    dependencySubstitution {
        substitute(module("com.vitorpamplona.amethyst:quartz"))
            .using(project(":quartz"))
    }
}

// Benchmark module disabled for now - plugin version conflict with AGP 8.13
// To enable: update AGP or use compatible baseline profile plugin version
// include(":benchmark")
 