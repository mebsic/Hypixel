plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

repositories {
    maven("https://maven.citizensnpcs.co/repo")
}

dependencies {
    implementation("org.mongodb:mongodb-driver-sync:4.11.1")
    implementation("redis.clients:jedis:5.1.2")
    implementation("com.google.code.gson:gson:2.10.1")
    compileOnly("net.citizensnpcs:citizensapi:2.0.30-SNAPSHOT")
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveBaseName.set("Hypixel")
    archiveVersion.set("")
    doFirst {
        archiveFile.get().asFile.delete()
    }
}

val copyShadowJarToDocker by tasks.registering(Copy::class) {
    dependsOn(tasks.shadowJar)
    val shadowArchive = tasks.shadowJar.flatMap { it.archiveFile }
    from(shadowArchive)
    into(rootProject.layout.projectDirectory.dir("docker/development/plugins"))
}

tasks.shadowJar {
    finalizedBy(copyShadowJarToDocker)
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
