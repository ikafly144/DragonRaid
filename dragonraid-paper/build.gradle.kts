import xyz.jpenilla.resourcefactory.paper.PaperPluginYaml

plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.resource.factory.paper)
    alias(libs.plugins.run.paper)
}

base {
    archivesName.set("DragonRaid")
}

dependencies {
    implementation(project(":dragonraid-api"))
    implementation(project(":dragonraid-core"))
    implementation(project(":dragonraid-instance"))
    implementation(project(":dragonraid-model"))
    implementation(project(":dragonraid-script"))
    implementation(project(":dragonraid-party"))
    implementation(project(":dragonraid-finder"))
    implementation(project(":dragonraid-achievement"))
    implementation(project(":dragonraid-reward"))
    implementation(project(":dragonraid-economy"))
    implementation(project(":dragonraid-item"))
    implementation(project(":dragonraid-stat"))

    compileOnly(libs.folia.api)
    compileOnly(libs.packetevents.spigot)
    compileOnly(libs.thenextlvl.worlds)
    compileOnly(libs.bettermodel.bukkit.api)

    testImplementation(libs.folia.api)
    testImplementation(libs.packetevents.spigot)
    testImplementation(libs.thenextlvl.worlds)
    testImplementation(libs.bettermodel.bukkit.api)
}

paperPluginYaml {
    name = "DragonRaid"
    version = project.version.toString()
    main = "net.sabafly.dragonraid.DragonRaidPlugin"
    apiVersion = "26.3"
    foliaSupported = true
    description = project.description
    authors.add("ikafly144")

    dependencies {
        server {
            register("packetevents") {
                load = PaperPluginYaml.Load.BEFORE
                required = true
                joinClasspath = true
            }
            register("Worlds") {
                load = PaperPluginYaml.Load.BEFORE
                required = true
                joinClasspath = true
            }
            register("BetterModel") {
                load = PaperPluginYaml.Load.BEFORE
                required = true
                joinClasspath = true
            }
        }
    }
}

tasks {
    processResources {
        filteringCharset = "UTF-8"
    }

    jar {
        archiveClassifier.set("thin")
    }

    shadowJar {
        archiveClassifier.set("")
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        mergeServiceFiles()
        relocate("com.zaxxer.hikari", "net.sabafly.dragonraid.libs.hikari")
        relocate("org.spongepowered.configurate", "net.sabafly.dragonraid.libs.configurate")
        relocate("io.leangen.geantyref", "net.sabafly.dragonraid.libs.geantyref")
        relocate("net.kyori.option", "net.sabafly.dragonraid.libs.option")
    }

    assemble {
        dependsOn(shadowJar)
    }

    runPaper.folia.registerTask {
        minecraftVersion("26.3")
        runDirectory.set(layout.projectDirectory.dir("run/folia"))
        pluginJars.from(shadowJar.flatMap { it.archiveFile })
        downloadPlugins {
            github(
                "retrooper",
                "packetevents",
                "v${libs.versions.packetevents.get()}",
                "packetevents-spigot-${libs.versions.packetevents.get()}.jar"
            )
            modrinth(
                "worlds-1",
                libs.versions.worlds.get()
            )
            github(
                "toxicity188",
                "BetterModel",
                libs.versions.bettermodel.get(),
                "bettermodel-${libs.versions.bettermodel.get()}-paper.jar"
            )
        }
    }
}
