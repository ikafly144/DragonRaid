import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration
import xyz.jpenilla.resourcefactory.paper.PaperPluginYaml
import xyz.jpenilla.runpaper.task.RunServer
import xyz.jpenilla.runtask.pluginsapi.PluginDownloadService
import xyz.jpenilla.runtask.service.DownloadsAPIService

plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.resource.factory.paper)
    alias(libs.plugins.run.paper)
}

base {
    archivesName.set("DragonRaid")
}

val testHarnessPlugin: Configuration = configurations.create("testHarnessPlugin") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
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

    testHarnessPlugin(project(":dragonraid-test-harness"))
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

val targetJava = libs.versions.java.get().toInt()
val packeteventsVer = libs.versions.packetevents.get()
val worldsVer = libs.versions.worlds.get()
val bettermodelVer = libs.versions.bettermodel.get()
val includeHarnessInRunFolia = !providers.gradleProperty("withoutTestHarness").isPresent

fun RunServer.configureRequiredExternalPlugins() {
    minecraftVersion("26.3")
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(targetJava))
        }
    )
    ignoreUnsupportedJvm()
    downloadPlugins {
        github(
            "retrooper",
            "packetevents",
            "v$packeteventsVer",
            "packetevents-spigot-$packeteventsVer.jar"
        )
        modrinth(
            "worlds-1",
            worldsVer
        )
        github(
            "toxicity188",
            "BetterModel",
            bettermodelVer,
            "bettermodel-$bettermodelVer-paper.jar"
        )
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

    val interactiveRunDir = layout.projectDirectory.dir("run/folia")
    runPaper.folia.registerTask {
        notCompatibleWithConfigurationCache("Interactive Folia dev server task")
        configureRequiredExternalPlugins()
        runDirectory.set(interactiveRunDir)
        pluginJars.from(shadowJar.flatMap { it.archiveFile })
        if (includeHarnessInRunFolia) {
            pluginJars.from(testHarnessPlugin)
        }
        jvmArgs(
            "-Xms2G",
            "-Xmx4G",
            "-Dcom.mojang.eula.agree=true",
            "-Ddragonraid.harness.mode=interactive"
        )
        doFirst {
            val dir = interactiveRunDir.asFile.toPath()
            Files.createDirectories(dir)
            val eula = dir.resolve("eula.txt")
            if (!Files.exists(eula)) {
                Files.writeString(eula, "eula=true\n", StandardCharsets.UTF_8)
            }
            val props = dir.resolve("server.properties")
            if (!Files.exists(props)) {
                Files.writeString(
                    props,
                    """
                    online-mode=false
                    enforce-secure-profile=false
                    motd=DragonRaid Folia 26.3 Local Test Server
                    view-distance=8
                    simulation-distance=6
                    spawn-protection=0
                    """.trimIndent() + "\n",
                    StandardCharsets.UTF_8
                )
            }
        }
    }

    val e2eBaseDir = layout.buildDirectory.dir("folia-integration-test")
    val e2eRunDir = e2eBaseDir.map { it.dir("run") }
    val e2eReportDir = e2eBaseDir.map { it.dir("report") }

    register<RunServer>("foliaIntegrationTest") {
        group = "verification"
        description = "Boots a real headless Folia 26.3 server with external plugins and runs end-to-end integration checks."
        notCompatibleWithConfigurationCache("Executes live headless Folia 26.3 server process")
        timeout.set(Duration.ofMinutes(5))

        displayName.set("Folia E2E")
        downloadsApiService.set(DownloadsAPIService.folia(project))
        pluginDownloadService.set(PluginDownloadService.paper(project))
        configureRequiredExternalPlugins()

        runDirectory.set(e2eRunDir)
        pluginJars.from(shadowJar.flatMap { it.archiveFile })
        pluginJars.from(testHarnessPlugin)

        var consolePipeOut: PipedOutputStream? = null

        doFirst {
            val basePath = e2eBaseDir.get().asFile.toPath()
            if (Files.exists(basePath)) {
                basePath.toFile().deleteRecursively()
            }
            val runPath = e2eRunDir.get().asFile.toPath()
            val reportPath = e2eReportDir.get().asFile.toPath()
            Files.createDirectories(runPath)
            Files.createDirectories(reportPath)

            val freePort = ServerSocket(0).use { it.localPort }
            Files.writeString(runPath.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8)
            Files.writeString(
                runPath.resolve("server.properties"),
                """
                server-port=$freePort
                online-mode=false
                enforce-secure-profile=false
                allow-nether=false
                level-type=minecraft\:flat
                generate-structures=false
                view-distance=4
                simulation-distance=4
                spawn-protection=0
                max-players=8
                motd=DragonRaid Headless Folia E2E
                """.trimIndent() + "\n",
                StandardCharsets.UTF_8
            )

            jvmArgs(
                "-Xms1G",
                "-Xmx3G",
                "-Dcom.mojang.eula.agree=true",
                "-Ddragonraid.harness.mode=auto",
                "-Ddragonraid.harness.reportDir=${reportPath.toAbsolutePath()}",
                "-Ddragonraid.harness.timeoutSeconds=120"
            )

            // Attach a controlled stdin pipe + safety monitor so the server still stops cleanly
            // even if a plugin fails at the Paper plugin-loader stage before onEnable runs.
            val pipeOut = PipedOutputStream()
            val pipeIn = PipedInputStream(pipeOut)
            consolePipeOut = pipeOut
            standardInput = pipeIn

            val latestLog = runPath.resolve("logs/latest.log")
            val enabledMarker = reportPath.resolve("harness-enabled.marker")
            val disabledMarker = reportPath.resolve("harness-disabled.marker")
            val monitor = Thread({
                val deadlineMs = System.currentTimeMillis() + 180_000L
                var serverDoneSeenAt = 0L
                try {
                    while (System.currentTimeMillis() < deadlineMs && !Files.exists(disabledMarker)) {
                        Thread.sleep(1_000L)
                        if (serverDoneSeenAt == 0L && Files.isRegularFile(latestLog)) {
                            val text = Files.readString(latestLog, StandardCharsets.UTF_8)
                            if (text.contains("Done (")) {
                                serverDoneSeenAt = System.currentTimeMillis()
                            }
                        }
                        if (serverDoneSeenAt > 0L
                            && !Files.exists(enabledMarker)
                            && System.currentTimeMillis() - serverDoneSeenAt > 15_000L
                        ) {
                            pipeOut.write("stop\n".toByteArray(StandardCharsets.UTF_8))
                            pipeOut.flush()
                            break
                        }
                    }
                    if (!Files.exists(disabledMarker)) {
                        pipeOut.write("stop\n".toByteArray(StandardCharsets.UTF_8))
                        pipeOut.flush()
                    }
                } catch (_: Throwable) {
                    // Stream closed on normal process exit.
                }
            }, "Folia-E2E-SafetyMonitor")
            monitor.isDaemon = true
            monitor.start()
        }

        doLast {
            try {
                consolePipeOut?.close()
            } catch (_: Throwable) {
            }

            val runPath = e2eRunDir.get().asFile.toPath()
            val reportPath = e2eReportDir.get().asFile.toPath()
            val latestLog = runPath.resolve("logs/latest.log")
            val resultsJson = reportPath.resolve("e2e-results.json")
            val resultsXml = reportPath.resolve("TEST-FoliaRealServerE2ETest.xml")
            val disabledMarker = reportPath.resolve("harness-disabled.marker")
            val watchdogDump = reportPath.resolve("watchdog-timeout-threaddump.txt")

            val logText = if (Files.isRegularFile(latestLog)) {
                Files.readString(latestLog, StandardCharsets.UTF_8)
            } else {
                ""
            }

            if (Files.exists(watchdogDump)) {
                throw GradleException(
                    "Folia E2E watchdog timed out! Thread dump:\n" +
                        Files.readString(watchdogDump, StandardCharsets.UTF_8)
                )
            }
            if (!Files.isRegularFile(resultsJson) || !Files.isRegularFile(resultsXml)) {
                throw GradleException(
                    "Folia E2E report files were not generated at $reportPath.\nServer log tail:\n" +
                        logText.lines().takeLast(80).joinToString("\n")
                )
            }

            val jsonContent = Files.readString(resultsJson, StandardCharsets.UTF_8)
            if (!jsonContent.contains("\"failed\": 0") || jsonContent.contains("\"total\": 0")) {
                throw GradleException(
                    "One or more Folia E2E scenarios failed!\n$jsonContent\nServer log tail:\n" +
                        logText.lines().takeLast(80).joinToString("\n")
                )
            }
            if (!Files.exists(disabledMarker)) {
                throw GradleException("DragonRaidTestHarness did not complete clean onDisable() shutdown.")
            }
            if (!logText.contains("DragonRaid enabled.")) {
                throw GradleException("Server log never recorded 'DragonRaid enabled.'")
            }

            val severeOrThreadErrors = logText.lines().filter { line ->
                line.contains("Thread failed main thread check") ||
                    line.contains(" ERROR]:") ||
                    line.contains("[SEVERE]")
            }
            if (severeOrThreadErrors.isNotEmpty()) {
                throw GradleException(
                    "Server log contained ERROR/SEVERE/Folia thread-check violations:\n" +
                        severeOrThreadErrors.joinToString("\n")
                )
            }
            println("Folia 26.3 E2E verification succeeded!\n$jsonContent")
        }
    }
}
