import xyz.jpenilla.resourcefactory.paper.PaperPluginYaml

plugins {
    alias(libs.plugins.resource.factory.paper)
}

base {
    archivesName.set("DragonRaidTestHarness")
}

dependencies {
    compileOnly(project(":dragonraid-api"))
    compileOnly(libs.folia.api)
}

paperPluginYaml {
    name = "DragonRaidTestHarness"
    version = project.version.toString()
    main = "net.sabafly.dragonraid.testharness.DragonRaidTestHarnessPlugin"
    apiVersion = "26.3"
    foliaSupported = true
    description = "Non-bundled real-environment E2E test runner and interactive dev fixture plugin for DragonRaid"
    authors.add("ikafly144")

    dependencies {
        server {
            register("DragonRaid") {
                load = PaperPluginYaml.Load.BEFORE
                required = true
                joinClasspath = true
            }
            register("Worlds") {
                load = PaperPluginYaml.Load.BEFORE
                required = true
                joinClasspath = true
            }
        }
    }
}
