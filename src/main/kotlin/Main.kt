import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.nio.file.*
import java.net.URI
import java.util.Comparator
import java.util.zip.ZipInputStream
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode

// ---------------- CONFIG ----------------

data class UpdaterConfig(
    var archiveDigest: String? = null,
    var removesDigest: String? = null
)

// ---------------- MAIN ----------------

fun main() {
    println("=== Updater started ===")

    val baseDir = Paths.get("..").toAbsolutePath().normalize()
    println("Base directory: $baseDir")

    // === Самоопределение пути к текущему updater'у (для самообновления) ===
    val currentUpdater = try {
        val location = MainKt::class.java.protectionDomain.codeSource.location
        if (location != null) {
            Paths.get(URI(location.toString())).normalize()
        } else null
    } catch (e: Exception) {
        println("Не удалось определить путь к updater'у (не JAR?)")
        null
    }

    val updaterFileName = currentUpdater?.fileName?.toString() ?: ""

    val minecraftDir = baseDir.resolve("minecraft")
    if (!Files.exists(minecraftDir)) {
        println("Minecraft directory does not exist, creating: $minecraftDir")
        Files.createDirectories(minecraftDir)
    }

    val configFile = baseDir.resolve("updater-config.json")
    val mapper = ObjectMapper()

    val config: UpdaterConfig =
        if (Files.exists(configFile)) {
            mapper.readValue(configFile.toFile(), UpdaterConfig::class.java)
        } else {
            println("Config file not found, creating new one")
            UpdaterConfig()
        }

    println("Loaded config: $config")

    val client = OkHttpClient()
    val apiUrl = "https://api.github.com/repos/Zeragorn-ru/glorp-2-modpack/releases/latest"

    println("Fetching latest release info from GitHub…")

    val response = client.newCall(
        Request.Builder()
            .url(apiUrl)
            .header("User-Agent", "Updater")
            .build()
    ).execute()

    if (!response.isSuccessful) {
        error("GitHub API request failed: ${response.code}")
    }

    val body = response.body!!.string()

    println("=== GitHub JSON response start ===")
    println(body)
    println("=== GitHub JSON response end ===")

    val root: JsonNode = mapper.readTree(body)
    val assetsNode = root["assets"]

    if (assetsNode == null || !assetsNode.isArray) {
        println("No assets array found")
        saveConfig(configFile, mapper, config)
        return
    }

    println("Assets parsed (${assetsNode.size()}):")
    assetsNode.forEach {
        println(" - ${it["name"].asText()}")
    }

    // ---------------- REMOVES ----------------

    val removesAsset = assetsNode.find { it["name"].asText() == "removes.txt" }

    if (removesAsset != null) {
        val digest = removesAsset["digest"].asText()
        println("Found removes.txt with digest $digest")

        println("removes.txt downloading…")
        val removesPath = minecraftDir.resolve("removes.txt")

        Files.deleteIfExists(removesPath)
        downloadFile(client, removesAsset["browser_download_url"].asText(), removesPath)

        Files.readAllLines(removesPath).forEach { line ->
            if (line.isNotBlank()) {
                val target = minecraftDir.resolve(line.trim())
                if (Files.exists(target)) {

                    // Пропускаем самоудаление сейчас — сделаем после выхода программы
                    if (currentUpdater != null && target == currentUpdater) {
                        println("Skipping self-delete for now (will be done after exit)")
                    } else {
                        println("Removing: $target")
                        try {
                            if (Files.isDirectory(target)) {
                                Files.walk(target)
                                    .sorted(Comparator.reverseOrder())
                                    .forEach { Files.delete(it) }
                            } else {
                                Files.delete(target)
                            }
                        } catch (e: Exception) {
                            System.err.println("Не удалось удалить $target → ${e.message}")
                        }
                    }
                }
            }
        }

        Files.deleteIfExists(removesPath)
        config.removesDigest = digest
    } else {
        println("No removes.txt found in release")
    }

    // ---------------- ARCHIVE ----------------

    val archiveAsset = assetsNode.find { it["name"].asText() == "update.zip" }

    if (archiveAsset != null) {
        val digest = archiveAsset["digest"].asText()
        println("Found archive with digest $digest")

        if (digest != config.archiveDigest) {
            println("Archive changed, downloading and unpacking…")
            val tempZip = baseDir.resolve("temp.zip")

            Files.deleteIfExists(tempZip)
            downloadFile(client, archiveAsset["browser_download_url"].asText(), tempZip)
            unzip(tempZip, baseDir, currentUpdater)   // ← передаём для самообновления

            Files.deleteIfExists(tempZip)
            config.archiveDigest = digest
        } else {
            println("Archive unchanged, skipping")
        }
    } else {
        println("No archive found in release")
    }

    // === Подготовка самообновления (удалит старую версию и поставит новую после выхода) ===
    if (currentUpdater != null) {
        Runtime.getRuntime().addShutdownHook(object : Thread() {
            override fun run() {
                val newUpdater = currentUpdater.resolveSibling(updaterFileName + ".new")
                if (Files.exists(newUpdater)) {
                    try {
                        Files.deleteIfExists(currentUpdater)
                        Files.move(newUpdater, currentUpdater)
                        println("=== Updater successfully self-updated! ===")
                    } catch (e: Exception) {
                        System.err.println("Self-update failed: ${e.message}")
                    }
                }
            }
        })
        println("Self-update prepared — will apply after program exit")
    }

    saveConfig(configFile, mapper, config)

    println("Updater finished successfully")
    println("=== Updater finished ===")
}

// ---------------- HELPERS ----------------

fun downloadFile(client: OkHttpClient, url: String, target: Path) {
    println("Downloading $url → $target")
    client.newCall(
        Request.Builder()
            .url(url)
            .header("User-Agent", "Updater")
            .build()
    ).execute().use { resp ->
        if (!resp.isSuccessful) error("Download failed: ${resp.code}")
        Files.copy(resp.body!!.byteStream(), target)
    }
}

fun unzip(zipPath: Path, targetDir: Path, currentUpdater: Path?) {
    println("Unpacking archive: $zipPath to $targetDir")
    ZipInputStream(Files.newInputStream(zipPath)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            val outPath = targetDir.resolve(entry.name)

            // Если в архиве лежит новая версия самого updater'а — распаковываем как .new
            val finalPath = if (currentUpdater != null && entry.name == currentUpdater.fileName.toString()) {
                currentUpdater.resolveSibling(currentUpdater.fileName.toString() + ".new")
            } else {
                outPath
            }

            if (entry.isDirectory) {
                Files.createDirectories(finalPath)
            } else {
                Files.createDirectories(finalPath.parent)
                if (Files.exists(finalPath)) {
                    println("Skipping existing file: $finalPath")
                } else {
                    Files.copy(zip as InputStream, finalPath)
                    println("Created file: $finalPath")
                }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
}

fun saveConfig(path: Path, mapper: ObjectMapper, config: UpdaterConfig) {
    println("Saving config…")
    mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), config)
}