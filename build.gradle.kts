plugins {
    kotlin("jvm") version "1.9.23"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.fasterxml.jackson.core:jackson-core:2.17.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.0") // ← новая зависимость
}

application {
    mainClass.set("MainKt")
}

// --------------------- FAT JAR ---------------------
tasks.register<Jar>("fatJar") {
    archiveBaseName.set("updater-all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // Основной код проекта
    from(sourceSets.main.get().output)

    // Все runtime-зависимости
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })

    // Главный класс для java -jar
    manifest {
        attributes(
            "Main-Class" to "MainKt"
        )
    }
}
