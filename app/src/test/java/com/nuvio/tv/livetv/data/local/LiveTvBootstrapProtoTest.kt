package com.nuvio.tv.livetv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LiveTvBootstrapProtoTest {
    @Test
    fun fixtureProtoMatchesLiveTvSettingsJson() {
        val prefs = readPrefs(python("scripts/livetv-bootstrap.py", "--fixture-pb"))
        assertEquals(
            """{"portalUrl":"http://h/stalker_portal/server/load.php","macAddress":"00:1A:79:AA:BB:CC","username":"","password":"","isEnabled":true}""",
            prefs[stringPreferencesKey("stalker_settings")]
        )
        assertEquals(
            """{"serverUrl":"https://x/t","username":"test","password":"test","isEnabled":true}""",
            prefs[stringPreferencesKey("xtream_settings")]
        )
        assertEquals(
            """[{"id":"stalker-m3u","name":"Stalker M3U","type":"Url","source":"https://x/t/playlist.m3u?a=1&b=2","isEnabled":true}]""",
            prefs[stringPreferencesKey("playlists_blob")]
        )
    }

    @Test
    fun mergeKeepsDisabledFlagFavoritesAndOtherPlaylists() {
        val stalker = stringPreferencesKey("stalker_settings")
        val favorites = stringPreferencesKey("favorite_channel_ids_blob")
        val playlists = stringPreferencesKey("playlists_blob")
        val original = preferencesOf(
            stalker to """{"portalUrl":"http://old","macAddress":"00:00:00:00:00:00","username":"","password":"","isEnabled":false}""",
            favorites to """["b","a"]""",
            playlists to """[{"id":"p1","name":"Mine","type":"Url","source":"https://other/list.m3u","isEnabled":true}]""",
        )
        val input = Buffer()
        runBlocking { PreferencesSerializer.writeTo(original, input) }
        val result = python(
            "scripts/livetv-bootstrap.py",
            stdin = input.readByteArray(),
            env = mapOf(
                "PORTAL" to "http://h/stalker_portal/server/load.php",
                "MAC" to "00:1a:79:aa:bb:cc",
                "M3U" to "https://x/t/playlist.m3u?a=1&b=2",
                "XT_URL" to "https://x/t",
                "XT_USER" to "test",
                "XT_PASS" to "test",
            ),
        )
        assertEquals(0, result.exit)
        val prefs = readPrefs(result)
        val written = prefs[stalker]!!
        assertTrue(written.contains(""""isEnabled":false"""))
        assertTrue(written.contains("http://h/stalker_portal/server/load.php"))
        assertEquals("""["b","a"]""", prefs[favorites])
        assertTrue(prefs[playlists]!!.contains("https://other/list.m3u"))
        assertTrue(prefs[playlists]!!.contains("a=1&b=2"))
    }

    @Test
    fun dryRunMasksTokenAndMac() {
        val result = python(
            "scripts/livetv-bootstrap.py",
            "--dry-run",
            env = mapOf(
                "PORTAL" to "http://h/stalker_portal/server/load.php",
                "MAC" to "00:1A:79:AA:BB:CC",
                "M3U" to "https://x/secret-token/playlist.m3u",
                "XT_URL" to "https://x/secret-token",
                "XT_USER" to "test",
                "XT_PASS" to "test",
                "TOKEN" to "secret-token",
            ),
        )
        assertEquals(0, result.exit)
        val text = result.stdout.decodeToString()
        assertFalse(text.contains("secret-token"))
        assertFalse(text.contains("AA:BB:CC"))
        assertTrue(text.contains("***"))
        assertTrue(text.contains("playlist.m3u"))
    }

    @Test
    fun shellRefusesNonEmulatorWithoutDeviceFlag() {
        val result = run(
            listOf("bash", repoFile("scripts/livetv-bootstrap.sh").absolutePath, "--dry-run"),
            env = mapOf("NUVIO_TV_SERIAL" to "192.168.0.8:5555"),
        )
        assertEquals(1, result.exit)
        assertTrue(result.stderr.decodeToString().contains("emulator"))
        assertFalse(result.stdout.decodeToString().contains("macAddress"))
    }

    @Test
    fun pythonSelfTest() {
        val result = python("scripts/livetv-bootstrap.py", "--self-test")
        assertEquals(result.stderr.decodeToString(), 0, result.exit)
        assertTrue(result.stdout.decodeToString().contains("self-test ok"))
    }

    private fun readPrefs(result: Run): Preferences {
        assertEquals(result.stderr.decodeToString(), 0, result.exit)
        return runBlocking { PreferencesSerializer.readFrom(Buffer().write(result.stdout)) }
    }

    private fun python(script: String, vararg args: String, stdin: ByteArray = ByteArray(0), env: Map<String, String> = emptyMap()) =
        run(listOf("python3", repoFile(script).absolutePath) + args, stdin, env)

    private fun run(command: List<String>, stdin: ByteArray = ByteArray(0), env: Map<String, String> = emptyMap()): Run {
        val builder = ProcessBuilder(command)
        env.forEach { (key, value) -> builder.environment()[key] = value }
        val proc = builder.start()
        proc.outputStream.use { it.write(stdin) }
        val stdout = proc.inputStream.readBytes()
        val stderr = proc.errorStream.readBytes()
        return Run(proc.waitFor(), stdout, stderr)
    }

    private fun repoFile(name: String): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(5) {
            val candidate = File(dir, name)
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("missing $name from ${System.getProperty("user.dir")}")
    }

    private class Run(val exit: Int, val stdout: ByteArray, val stderr: ByteArray)
}
