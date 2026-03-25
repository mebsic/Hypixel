plugins {
    id("java")
}

group = "io.github.mebsic"
version = "1.0.0"
buildDir = file(".root-build")

subprojects {
    apply(plugin = "java")

    group = rootProject.group
    version = rootProject.version

    java {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    repositories {
        mavenCentral()
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        maven("https://repo.md-5.net/content/repositories/snapshots/")
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
    }

    dependencies {
        compileOnly("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT")
        testImplementation(platform("org.junit:junit-bom:5.10.0"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    tasks.test {
        useJUnitPlatform()
    }
}

tasks.register("shadowAll") {
    dependsOn(
        ":core:shadowJar",
        ":murdermystery:shadowJar",
        ":proxy:shadowJar",
        ":build:shadowJar"
    )
}

tasks.build {
    dependsOn("shadowAll")
}
