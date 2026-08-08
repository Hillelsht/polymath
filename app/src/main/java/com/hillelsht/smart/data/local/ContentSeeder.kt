package com.hillelsht.smart.data.local

import android.content.Context
import android.util.Log
import com.hillelsht.smart.data.seed.ContentParser
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
) {

    suspend fun seedIfNeeded() {
        val bundled = readBundledPacks()
        bundled.forEach { pack -> installIfChanged(pack, source = SOURCE_BUNDLED) }

        downloadedPackFiles(context).forEach { file ->
            try {
                installIfChanged(
                    ContentParser.parsePack(file.readText(), file.name),
                    source = SOURCE_REMOTE,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Ignoring corrupt downloaded pack ${file.name}", e)
            }
        }

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

    private fun readBundledPacks(): List<ContentParser.ParsedPack> {
        val assets = context.assets
        val enriched = assets.list(ENRICHED_DIR).orEmpty().filter { it.endsWith(".json") }
        val dir = if (enriched.isNotEmpty()) ENRICHED_DIR else AUTHORING_DIR
        val names = if (enriched.isNotEmpty()) enriched
        else assets.list(AUTHORING_DIR).orEmpty().filter { it.endsWith(".json") }

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
