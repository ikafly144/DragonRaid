import xyz.jpenilla.resourcefactory.paper.PaperPluginYaml

plugins {
    java
    alias(libs.plugins.shadow)
    alias(libs.plugins.resource.factory.paper)
    alias(libs.plugins.run.paper)
}

val targetJavaVersion = libs.versions.java.get().toInt()
val junitJupiterDep = libs.junit.jupiter
val junitLauncherDep = libs.junit.platform.launcher
val mockitoCoreDep = libs.mockito.core
val mockitoJunitDep = libs.mockito.junit.jupiter

allprojects {
    group = "net.sabafly.dragonraid"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.codemc.io/repository/maven-releases/")
        maven("https://repo.thenextlvl.net/releases")
    }
}

subprojects {
    apply(plugin = "java-library")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
        }
    }

    dependencies {
        "testImplementation"(junitJupiterDep)
        "testImplementation"(mockitoCoreDep)
        "testImplementation"(mockitoJunitDep)
        "testRuntimeOnly"(junitLauncherDep)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(targetJavaVersion)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
    }
}

dependencies {
    implementation(project(":dragonraid-api"))
    implementation(project(":dragonraid-core"))
    implementation(project(":dragonraid-instance"))
    implementation(project(":dragonraid-model"))
    implementation(project(":dragonraid-script"))
    implementation(project(":dragonraid-reward"))
    implementation(project(":dragonraid-achievement"))

    compileOnly(libs.folia.api)
    compileOnly(libs.packetevents.spigot)
    compileOnly(libs.thenextlvl.worlds)
    compileOnly(libs.bettermodel.bukkit.api)

    implementation(libs.configurate.yaml) {
        exclude(group = "org.yaml", module = "snakeyaml")
    }
    implementation(libs.sqlite.jdbc)
    implementation(libs.hikaricp) {
        exclude(group = "org.slf4j")
    }

    testImplementation(libs.folia.api)
    testImplementation(libs.packetevents.spigot)
    testImplementation(libs.thenextlvl.worlds)
    testImplementation(libs.bettermodel.bukkit.api)
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
