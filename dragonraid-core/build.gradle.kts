dependencies {
    api(project(":dragonraid-api"))

    compileOnly(libs.folia.api)
    compileOnly(libs.packetevents.spigot)

    api(libs.configurate.yaml) {
        exclude(group = "org.yaml", module = "snakeyaml")
    }
    implementation(libs.sqlite.jdbc)
    implementation(libs.hikaricp) {
        exclude(group = "org.slf4j")
    }

    testImplementation(project(":dragonraid-script"))
    testImplementation(libs.folia.api)
    testImplementation(libs.packetevents.spigot)
}

sourceSets {
    named("test") {
        resources {
            srcDir(rootProject.file("dragonraid-paper/src/main/resources"))
        }
    }
}
