package com.nuvio.tv.livetv.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

internal const val LIVE_TV_EPG_CACHE_TTL_MILLIS = 24L * 60L * 60L * 1000L

/**
 * Channel list until the source signature changes, guide for [LIVE_TV_EPG_CACHE_TTL_MILLIS].
 * Memory first, then `cacheDir/live_tv`.
 * shortcut: a crash mid-write can leave a bad file, it is ignored and fetched again.
 */
internal data class CachedChannels(
    val channels: List<LiveTvChannel>,
    val epgUrls: List<String>?,
)

class LiveTvCache(private val dir: File) {
    private val gson = Gson()
    private val channelType = object : TypeToken<ChannelFile>() {}.type
    private val epgType = object : TypeToken<EpgFile>() {}.type
    private var channels: ChannelFile? = null
    private var epg: EpgFile? = null

    internal fun channels(signature: String): CachedChannels? {
        channels?.takeIf { it.signature == signature }?.let { return it.toCached() }
        val loaded = read<ChannelFile>("channels.json", channelType)?.takeIf { it.signature == signature } ?: return null
        channels = loaded
        return loaded.toCached()
    }

    fun saveChannels(signature: String, channels: List<LiveTvChannel>, epgUrls: List<String> = emptyList()) {
        val file = ChannelFile(signature, System.currentTimeMillis(), channels.map { it.toDto() }, epgUrls)
        this.channels = file
        write("channels.json", file)
    }

    fun epg(signature: String, nowMillis: Long): Map<String, List<LiveTvEpgProgram>>? {
        fun fresh(file: EpgFile) = file.signature == signature && nowMillis - file.savedAtMillis < LIVE_TV_EPG_CACHE_TTL_MILLIS
        epg?.takeIf(::fresh)?.let { return it.programs }
        val loaded = read<EpgFile>("epg.json", epgType)
        if (loaded == null || !fresh(loaded)) return null
        epg = loaded
        return loaded.programs
    }

    fun saveEpg(signature: String, nowMillis: Long, programs: Map<String, List<LiveTvEpgProgram>>) {
        val file = EpgFile(signature, nowMillis, programs)
        epg = file
        write("epg.json", file)
    }

    private fun <T> read(name: String, type: java.lang.reflect.Type): T? = runCatching {
        val text = File(dir, name).readText()
        gson.fromJson<T>(text, type)
    }.getOrNull()

    private fun write(name: String, value: Any) {
        runCatching {
            dir.mkdirs()
            File(dir, name).writeText(gson.toJson(value))
        }
    }

    private data class ChannelFile(
        val signature: String,
        val savedAtMillis: Long,
        val channels: List<ChannelDto>,
        val epgUrls: List<String>? = null,
    )

    private fun ChannelFile.toCached() = CachedChannels(channels.map { it.toModel() }, epgUrls)

    private data class ChannelDto(
        val id: String? = null,
        val name: String? = null,
        val streamUrl: String? = null,
        val logoUrl: String? = null,
        val group: String? = null,
        val playlistId: String? = null,
        val playlistName: String? = null,
        val headers: Map<String, String>? = null,
        val streamType: String? = null,
        val stalkerCommand: String? = null,
        val epgId: String? = null,
    )

    private data class EpgFile(
        val signature: String,
        val savedAtMillis: Long,
        val programs: Map<String, List<LiveTvEpgProgram>>,
    )

    private fun LiveTvChannel.toDto() = ChannelDto(
        id, name, streamUrl, logoUrl, group, playlistId, playlistName, headers, streamType, stalkerCommand, epgId,
    )

    private fun ChannelDto.toModel() = LiveTvChannel(
        id = id.orEmpty(),
        name = name.orEmpty(),
        streamUrl = streamUrl.orEmpty(),
        logoUrl = logoUrl,
        group = group,
        playlistId = playlistId,
        playlistName = playlistName,
        headers = headers.orEmpty(),
        streamType = streamType,
        stalkerCommand = stalkerCommand,
        epgId = epgId,
    )
}
