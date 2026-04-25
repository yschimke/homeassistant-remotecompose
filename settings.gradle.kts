pluginManagement {
    repositories {
        gradlePluginPortal()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "homeassistant-remotecompose"

include(
    ":ha-model",
    ":ha-client",
    ":rc-components",
    ":rc-converter",
    ":rc-card-shutter",
    ":previews",
    ":demo-app",
    ":terrazzo-core",
    ":app",
    ":wear",
    ":tv",
    ":integration",
    ":addon-server",
)
