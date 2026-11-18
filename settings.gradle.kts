plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
}

rootProject.name = "DragonRaid"

include(
    "dragonraid-api",
    "dragonraid-core",
    "dragonraid-instance",
    "dragonraid-model",
    "dragonraid-script",
    "dragonraid-party",
    "dragonraid-finder",
    "dragonraid-achievement",
    "dragonraid-reward",
    "dragonraid-economy",
    "dragonraid-item",
    "dragonraid-stat",
    "dragonraid-paper",
)
