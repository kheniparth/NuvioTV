package com.nuvio.tv.livetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpstreamSyncScriptTest {
    @Test
    fun mergesUpstreamOnADatedBranchAndPrintsCommits() {
        val tmp = kotlin.io.path.createTempDirectory("upstream-sync").toFile()
        try {
            val upstream = File(tmp, "upstream")
            val fork = File(tmp, "fork")
            git(tmp, "init", "-b", "dev", upstream.absolutePath)
            File(upstream, "base.txt").writeText("base\n")
            git(upstream, "add", "base.txt")
            git(upstream, "commit", "-m", "base")
            git(tmp, "clone", upstream.absolutePath, fork.absolutePath)
            File(upstream, "up.txt").writeText("up\n")
            git(upstream, "add", "up.txt")
            git(upstream, "commit", "-m", "upstream feature")
            File(fork, "fork.txt").writeText("fork\n")
            git(fork, "add", "fork.txt")
            git(fork, "commit", "-m", "fork only")
            git(fork, "remote", "add", "upstream", upstream.absolutePath)
            git(fork, "config", "protocol.file.allow", "always")

            val result = run(
                listOf("bash", repoFile("scripts/upstream-sync.sh").absolutePath),
                fork,
                mapOf(
                    "NUVIO_SYNC_CHECKS" to "true",
                    "GIT_AUTHOR_NAME" to "sync",
                    "GIT_AUTHOR_EMAIL" to "sync@example.com",
                    "GIT_COMMITTER_NAME" to "sync",
                    "GIT_COMMITTER_EMAIL" to "sync@example.com",
                ),
            )
            assertEquals(result.stderr.decodeToString(), 0, result.exit)
            val out = result.stdout.decodeToString()
            assertTrue(out, out.contains("upstream commits:"))
            assertTrue(out, out.contains("upstream feature"))
            val branch = run(listOf("git", "rev-parse", "--abbrev-ref", "HEAD"), fork).stdout.decodeToString().trim()
            assertTrue(branch, branch.matches(Regex("sync/upstream-\\d{8}")))
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun git(dir: File, vararg args: String) {
        val result = run(listOf("git", "-c", "user.name=sync", "-c", "user.email=sync@example.com") + args, dir)
        assertEquals(result.stderr.decodeToString() + result.stdout.decodeToString(), 0, result.exit)
    }

    private fun run(command: List<String>, dir: File, env: Map<String, String> = emptyMap()): Run {
        val builder = ProcessBuilder(command).directory(dir)
        env.forEach { (key, value) -> builder.environment()[key] = value }
        val proc = builder.start()
        val stdout = proc.inputStream.readBytes()
        val stderr = proc.errorStream.readBytes()
        return Run(proc.waitFor(), stdout, stderr)
    }

    private fun repoFile(name: String): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            val candidate = File(dir, name)
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("missing $name from ${System.getProperty("user.dir")}")
    }

    private class Run(val exit: Int, val stdout: ByteArray, val stderr: ByteArray)
}
