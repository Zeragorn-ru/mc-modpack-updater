import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
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
    val apiUrl = "https://api.github.com/repos/Zeragorn-ru/dustpack/releases/latest"

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

        if (digest != config.removesDigest) {
            println("removes.txt changed, downloading…")
            val removesPath = minecraftDir.resolve("removes.txt")

            downloadFile(client, removesAsset["browser_download_url"].asText(), removesPath)

            Files.readAllLines(removesPath).forEach { line ->
                if (line.isNotBlank()) {
                    val target = minecraftDir.resolve(line)
                    if (Files.exists(target)) {
                        println("Removing: $target")
                        Files.delete(target)
                    }
                }
            }

            Files.deleteIfExists(removesPath)
            config.removesDigest = digest
        } else {
            println("removes.txt unchanged, skipping")
        }
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

            downloadFile(client, archiveAsset["browser_download_url"].asText(), tempZip)
            unzip(tempZip, baseDir)

            Files.deleteIfExists(tempZip)
            config.archiveDigest = digest
        } else {
            println("Archive unchanged, skipping")
        }
    } else {
        println("No archive found in release")
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

fun unzip(zipPath: Path, targetDir: Path) {
    println("Unpacking archive: $zipPath to $targetDir")
    ZipInputStream(Files.newInputStream(zipPath)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            val outPath = targetDir.resolve(entry.name)
            if (entry.isDirectory) {
                Files.createDirectories(outPath)
            } else {
                Files.createDirectories(outPath.parent)
                if (Files.exists(outPath)) {
                    println("Skipping existing file: $outPath")
                } else {
                    Files.copy(zip as InputStream, outPath)
                    println("Created file: $outPath")
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
