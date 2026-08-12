package com.hillelsht.smart.data.local

import android.content.Context
import android.util.Log
import com.hillelsht.smart.data.seed.ChannelParser
import com.hillelsht.smart.data.seed.ContentParser
import com.hillelsht.smart.domain.PackInstall
import com.hillelsht.smart.domain.model.Language
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
    private val channelDao: ChannelDao,
) {

    suspend fun seedIfNeeded() {
        val bundled = readBundledPacks()
        bundled.forEach { pack -> installIfChanged(pack, source = SOURCE_BUNDLED) }

        downloadedPackFiles(context).forEach { file ->
            try {
                if (file.name == CHANNELS_FILE) {
                    installChannels(ChannelParser.parse(file.readText(), file.name))
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

        seedBundledChannels()

        // Facts seeded before the pack system existed carry an empty packId; the re-seed
        // above replaces them by id, and this sweeps any that no longer exist at all.
        factDao.clearPack("")
    }

    suspend fun installIfChanged(pack: ContentParser.ParsedPack, source: String) {
        val installed = packDao.byId(pack.packId)
        if (installed != null && installed.version == pack.version) return

        // A generated library shard only ever adds and updates — see [PackInstall] for why
        // clearing one would destroy review history the learner has earned. Curated packs are
        // still replaced wholesale, because a new version of one is a correction.
        if (PackInstall.replacesExistingFacts(pack.packId)) {
            factDao.clearPack(pack.packId)
        }
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

    /**
     * Installs the channel allowlist. Channels are the only Watch content that ships; the
     * videos themselves are fetched live, so there is no catalogue here to go stale.
     */
    suspend fun installChannels(parsed: ChannelParser.ParsedChannels) {
        if (parsed.channels.isEmpty()) return
        val installed = packDao.byId(CHANNELS_PACK)
        if (installed != null && installed.version == parsed.version) return

        channelDao.clear()
        channelDao.insertAll(
            parsed.channels.map {
                ChannelEntity(
                    id = it.id,
                    handle = it.handle,
                    categoryId = it.category.id,
                    displayName = it.displayName,
                    language = it.language.tag,
                )
            },
        )
        packDao.upsert(
            PackEntity(
                id = CHANNELS_PACK,
                version = parsed.version,
                factCount = parsed.channels.size,
                source = SOURCE_BUNDLED,
            ),
        )
        Log.i(TAG, "Seeded ${parsed.channels.size} watch channels (v${parsed.version})")
    }

    /**
     * Installs the bundled allowlist, preferring the probed copy in `packs/`.
     *
     * The authoring file in `content/` lists handles, not the UC ids the feed endpoint needs,
     * so before the pipeline has run it parses to nothing and the tab stays empty until the
     * app fetches the probed list over the network. That is the right failure: a handle the
     * app cannot resolve is not a channel it can read.
     */
    private suspend fun seedBundledChannels() {
        if (File(downloadedPacksDir(context), CHANNELS_FILE).exists()) return
        val assets = context.assets
        val dir = listOf(ENRICHED_DIR, AUTHORING_DIR)
            .firstOrNull { assets.list(it).orEmpty().contains(CHANNELS_FILE) }
            ?: return
        try {
            val raw = assets.open("$dir/$CHANNELS_FILE").bufferedReader().use { it.readText() }
            installChannels(ChannelParser.parse(raw, CHANNELS_FILE))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to seed watch channels", e)
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

        // A translated pack sits one level down, in a folder named for its language tag —
        // `packs/ru/geography.json` — mirroring how it is published. The listing above only
        // sees files, so without this a translated pack ships inside the APK and is never
        // installed: the Read tab and the daily plan come up empty in that language while the
        // bytes sit right there in assets.
        val translated = Language.entries
            .filter { it != Language.default }
            .flatMap { language ->
                assets.list("$dir/${language.tag}").orEmpty()
                    .filter { it.endsWith(".json") && it !in NON_FACT_FILES }
                    .map { "$dir/${language.tag}/$it" }
            }

        return (names.map { "$dir/$it" } + translated).mapNotNull { path ->
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
        const val CHANNELS_FILE = "channels.json"
        private const val CHANNELS_PACK = "watch-channels"
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
