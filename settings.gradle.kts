// Standard Gradle setup for opening the project in Android Studio.
// NOTE: not verified in the original build environment (Google Maven was unreachable there);
// the verified build is ./build.sh.
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "HaMechutan"
include(":app")
