dependencies {
    api(project(":dragonraid-api"))
    compileOnly(libs.folia.api)
    implementation(libs.configurate.yaml) {
        exclude(group = "org.yaml", module = "snakeyaml")
    }
    testImplementation(libs.folia.api)
}
