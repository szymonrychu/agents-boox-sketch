pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://repo.boox.com/repository/maven-public/") {
            content {
                includeGroupByRegex("com\\.onyx.*")
                // Transitive dependencies of onyxsdk-base that only the Onyx repository serves.
                includeGroup("pub.devrel")
                includeGroup("com.tencent")
            }
        }
    }
}

rootProject.name = "agents-boox-sketch"

include(":core:model", ":core:api", ":ink:onyx", ":app")
