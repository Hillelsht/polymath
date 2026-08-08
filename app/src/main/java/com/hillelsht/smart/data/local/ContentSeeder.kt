package com.hillelsht.smart.data.local

import android.content.Context
import android.util.Log
import com.hillelsht.smart.data.seed.ContentParser
import com.hillelsht.smart.data.seed.VideoParser
import java.io.File

/**
 * Loads content packs into the database.
 *
 * Sources, in order of preference:
 *  1. `assets/packs/` — the CI-enriched packs bundled with the app (images + details).
 *  2. `assets/content/` — the raw authoring files, used only until the pipeline has run.
 *  3. `filesDir/packs/` — packs downloaded from the repo at runtime.
 *
 * Each pack is seeded independently, keyed by its version: unchanged packs are skipped,
 * changed ones have their facts replaced. Review history is keyed by fact id in a separate
 * table and is never touched, so re-seeding never costs progress.
 */
class ContentSeeder(
    private val context: Context,
    private val factDao: FactDao,
    private val packDao: PackDao,
    private val videoDao: VideoDao,
) {

    suspend fun seedIfNeeded() {
        val bundled = readBundledPacks()
        bundled.forEach { pack -> installIfChanged(pack, source = SOURCE_BUNDLED) }

        downloadedPackFiles(context).forEach { file ->
            try {
                if (file.name == VIDEOS_FILE) {
                    installVideosIfChanged(VideoParser.parse(file.readText(), file.name))
                } else {
                    installIfChanged(
                        ContentParser.parsePack(file.readText(), file.name),
                        source = SOURCE_REMOTE,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ignoring corrupt downloaded pack ${file.name}", e)
            }
        }

        seedBundledVideos()

        // Facts seeded before the pack system existed carry an empty packId; the re-seed
        // above replaces them by id, and this sweeps any that no longer exist at all.
        factDao.clearPack("")
    }

    suspend fun installIfChanged(pack: ContentParser.ParsedPack, source: String) {
        val installed = packDao.byId(pack.packId)
        if (installed != null && installed.version == pack.version) return

        factDao.clearPack(pack.packId)
        factDao.insertAll(pack.facts.map { it.toEntity() })
        packDao.upsert(
            PackEntity(
                id = pack.packId,
                version = pack.version,
                factCount = pack.facts.size,
                source = source,
            ),
        )
        Log.i(TAG, "Seeded pack ${pack.packId} v${pack.version} (${pack.facts.size} facts, $source)")
    }

    /** Replaces the whole catalog when its version changes; watched state is keyed separately. */
    suspend fun installVideosIfChanged(catalog: VideoParser.ParsedCatalog) {
        val installed = packDao.byId(catalog.packId)
        if (installed != null && installed.version == catalog.version) return

        videoDao.clear()
        videoDao.insertAll(catalog.videos.map { it.toEntity() })
        packDao.upsert(
            PackEntity(
                id = catalog.packId,
                version = catalog.version,
                factCount = catalog.videos.size,
                source = SOURCE_BUNDLED,
            ),
        )
        Log.i(TAG, "Seeded video catalog v${catalog.version} (${catalog.videos.size} videos)")
    }

    private suspend fun seedBundledVideos() {
        // A downloaded catalog (handled above) wins over the bundled one; only seed bundled
        // when nothing newer has been fetched.
        val downloaded = File(downloadedPacksDir(context), VIDEOS_FILE).exists()
        if (downloaded) return

        // Only the pipeline-built catalog is ever seeded: its ids came from YouTube itself.
        // There is deliberately no hand-authored fallback — that is how a shelf of dead links
        // would get shipped.
        val assets = context.assets
        if (!assets.list(ENRICHED_DIR).orEmpty().contains(VIDEOS_FILE)) return
        val path = "$ENRICHED_DIR/$VIDEOS_FILE"

        try {
            val raw = assets.open(path).bufferedReader().use { it.readText() }
            installVideosIfChanged(VideoParser.parse(raw, path))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to seed bundled video catalog", e)
        }
    }

    private fun readBundledPacks(): List<ContentParser.ParsedPack> {
        val assets = context.assets
        val enriched = assets.list(ENRICHED_DIR).orEmpty()
            .filter { it.endsWith(".json") && it !in NON_FACT_FILES }
        val dir = if (enriched.isNotEmpty()) ENRICHED_DIR else AUTHORING_DIR
        val names = if (enriched.isNotEmpty()) enriched
        else assets.list(AUTHORING_DIR).orEmpty()
            .filter { it.endsWith(".json") && it !in NON_FACT_FILES }

        return names.mapNotNull { name ->
            val path = "$dir/$name"
            try {
                val raw = assets.open(path).bufferedReader().use { it.readText() }
                ContentParser.parsePack(raw, path)
            } catch (e: Exception) {
                // A single malformed file must not stop the rest of the curriculum.
                Log.e(TAG, "Failed to load $path", e)
                null
            }
        }
    }

    companion object {
        const val SOURCE_BUNDLED = "bundled"
        const val SOURCE_REMOTE = "remote"

        private const val TAG = "ContentSeeder"
        const val VIDEOS_FILE = "videos.json"
        private val NON_FACT_FILES = setOf("videos.json", "channels.json")
        private const val ENRICHED_DIR = "packs"
        private const val AUTHORING_DIR = "content"

        fun downloadedPacksDir(context: Context): File =
            File(context.filesDir, "packs").apply { mkdirs() }

        fun downloadedPackFiles(context: Context): List<File> =
            downloadedPacksDir(context).listFiles { f -> f.extension == "json" }
                ?.sortedBy { it.name }
                .orEmpty()
    }
}
