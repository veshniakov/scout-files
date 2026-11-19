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

rootProject.name = "Scout"
include(":app")
include(":core:model")
include(":core:vfs")
include(":core:database")
include(":core:security")
include(":core:designsystem")
include(":providers:local")
include(":core:operations")
include(":providers:archive")
include(":providers:sftp")
include(":providers:smb")
include(":providers:ftp")
include(":providers:webdav")
include(":providers:gdrive")
include(":feature:connections")
include(":feature:explorer")
