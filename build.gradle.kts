import xyz.jpenilla.resourcefactory.paper.PaperPluginYaml

plugins {
    java
    alias(libs.plugins.shadow)
    alias(libs.plugins.resource.factory.paper)
    alias(libs.plugins.run.paper)
}

group = "net.sabafly.dragonraid"
version = "0.1.0-SNAPSHOT"
description = "Timeline-based MMO raid plugin for Ender Dragon fights on Folia"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get().toInt()))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    compileOnly(libs.folia.api)
    compileOnly(libs.packetevents.spigot)

    implementation(libs.configurate.yaml) {
        exclude(group = "org.yaml", module = "snakeyaml")
    }
    implementation(libs.sqlite.jdbc)
    implementation(libs.hikaricp) {
        exclude(group = "org.slf4j")
    }

    testImplementation(libs.folia.api)
    testImplementation(libs.packetevents.spigot)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
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
        }
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(libs.versions.java.get().toInt())
    }

    processResources {
        filteringCharset = "UTF-8"
    }

    test {
        useJUnitPlatform()
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
        }
    }
}
