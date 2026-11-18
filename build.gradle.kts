plugins {
    base
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.resource.factory.paper) apply false
    alias(libs.plugins.run.paper) apply false
}

val targetJavaVersion = libs.versions.java.get().toInt()
val junitJupiterDep = libs.junit.jupiter
val junitLauncherDep = libs.junit.platform.launcher
val mockitoCoreDep = libs.mockito.core
val mockitoJunitDep = libs.mockito.junit.jupiter

allprojects {
    group = "net.sabafly.dragonraid"
    version = "0.1.0-SNAPSHOT"
    description = "Boss Raid & MMO Content Platform Plugin for Folia"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.codemc.io/repository/maven-releases/")
        maven("https://repo.thenextlvl.net/releases")
    }
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
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
