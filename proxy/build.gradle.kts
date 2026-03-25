plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.2.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.2.0-SNAPSHOT")
    implementation(project(":core"))
    implementation("org.mongodb:mongodb-driver-sync:4.11.1")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("redis.clients:jedis:5.1.2")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<JavaCompile> {
    options.release.set(17)
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveBaseName.set("HypixelProxy")
    archiveVersion.set("")
    doFirst {
        archiveFile.get().asFile.delete()
    }
}

val copyShadowJarToDocker by tasks.registering(Copy::class) {
    dependsOn(tasks.shadowJar)
    from(tasks.shadowJar.flatMap { it.archiveFile })
    into(rootProject.layout.projectDirectory.dir("docker/plugins"))
}

tasks.shadowJar {
    finalizedBy(copyShadowJarToDocker)
}

tasks.jar {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
