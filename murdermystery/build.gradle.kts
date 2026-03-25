plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

dependencies {
    compileOnly(project(":core"))
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.mongodb:mongodb-driver-sync:4.11.1")
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveBaseName.set("MurderMystery")
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
