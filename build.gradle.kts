plugins {
    java
    alias(libs.plugins.shadow)
}

group = "io.github.ikafly144"
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

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(libs.versions.java.get().toInt())
    }

    processResources {
        val props = mapOf("version" to project.version.toString())
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }

    test {
        useJUnitPlatform()
    }

    jar {
        archiveClassifier.set("thin")
    }

    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
        filesMatching("META-INF/services/**") {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
        relocate("com.zaxxer.hikari", "io.github.ikafly144.dragonraid.libs.hikari")
    }

    assemble {
        dependsOn(shadowJar)
    }
}
