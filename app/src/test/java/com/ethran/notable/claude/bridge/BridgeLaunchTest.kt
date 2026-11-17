package com.ethran.notable.claude.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Runs the real launch shell script with a temporary HOME, as Termux would. */
class BridgeLaunchTest {

    private val python = listOf("python3", "python").firstOrNull { which(it) != null }

    private fun which(cmd: String): String? =
        System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, cmd) }.firstOrNull { it.canExecute() }?.path

    /** Stands in for bridge.py: prints its arguments and exits. */
    private val fakeBridge = BridgeScript("import sys; print('ARGS', *sys.argv[1:])\n".toByteArray())

    /** Starts a sleeping python whose command line is [extra]; returns it and its pid. */
    private fun sleeper(dir: File, vararg extra: String): Pair<Process, String> {
        val pidFile = File(dir, "sleeper-${System.nanoTime()}.pid")
        val code = "import os,sys,time; open(sys.argv[1],'w').write(str(os.getpid())); time.sleep(60)"
        val process = ProcessBuilder(listOf(python!!, "-c", code, pidFile.path) + extra).start()
        repeat(100) { if (pidFile.length() > 0) return process to pidFile.readText().trim(); Thread.sleep(50) }
        error("sleeper did not start")
    }

    private fun launch(home: File, script: BridgeScript = fakeBridge, path: String? = null): Pair<Int, String> {
        // -c rather than -lc: a login shell would read this machine's profile.
        val args = BridgeLaunch.arguments(script, 8765, "secret-token-123456", "claude", 900)
            .toMutableList().also { it[0] = "-c" }
        val process = ProcessBuilder(listOf(which("bash") ?: "/bin/bash") + args).redirectErrorStream(true).apply {
            environment()["HOME"] = home.path
            if (path != null) environment()["PATH"] = path
        }.start()
        assertTrue(process.waitFor(20, TimeUnit.SECONDS))
        return process.exitValue() to process.inputStream.bufferedReader().readText()
    }

    @Test
    fun `writes the script and starts it with python and the bridge arguments`() {
        assumeTrue(python != null)
        val home = Files.createTempDirectory("home").toFile()
        val (exit, output) = launch(home)
        assertEquals(output, 0, exit)
        assertTrue(output, output.contains("ARGS --port 8765 --token secret-token-123456 --claude claude --idle-timeout 900"))
        val installed = File(home, ".notable-claude/bridge.py").readBytes()
        assertArrayEquals("import sys; print('ARGS', *sys.argv[1:])\n".toByteArray(), installed)
        home.deleteRecursively()
    }

    @Test
    fun `stops a bridge still running from before`() {
        assumeTrue(python != null)
        val home = Files.createTempDirectory("home").toFile()
        val dir = File(home, ".notable-claude").apply { mkdirs() }
        // An "old bridge": a process whose command line mentions bridge.py.
        val (old, pid) = sleeper(dir, "bridge.py")
        File(dir, "bridge.pid").writeText(pid)
        launch(home)
        assertTrue("the old bridge must be stopped", old.waitFor(5, TimeUnit.SECONDS))
        home.deleteRecursively()
    }

    @Test
    fun `leaves alone an unrelated process with a recycled pid`() {
        assumeTrue(python != null)
        val home = Files.createTempDirectory("home").toFile()
        val dir = File(home, ".notable-claude").apply { mkdirs() }
        val (other, pid) = sleeper(dir)
        File(dir, "bridge.pid").writeText(pid)
        launch(home)
        assertFalse(other.waitFor(500, TimeUnit.MILLISECONDS))
        other.destroy()
        home.deleteRecursively()
    }

    @Test
    fun `reports a missing python with its own exit code`() {
        val home = Files.createTempDirectory("home").toFile()
        // A PATH with the tools the script needs, but no python.
        val bin = Files.createTempDirectory("bin").toFile()
        listOf("mkdir", "cat", "grep", "base64", "mv").forEach { tool ->
            which(tool)?.let { Files.createSymbolicLink(File(bin, tool).toPath(), File(it).toPath()) }
        }
        val (exit, output) = launch(home, path = bin.path)
        assertEquals(output, BridgeLaunch.EXIT_NO_PYTHON, exit)
        assertTrue(output.contains("pkg install python"))
        home.deleteRecursively()
        bin.deleteRecursively()
    }

    @Test
    fun `the shipped script's version is its sha256, as the bridge reports it`() {
        val asset = File("src/main/assets/claude/bridge.py")
        assumeTrue(asset.exists())
        val bytes = asset.readBytes()
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(expected, BridgeScript(bytes).version)
    }
}
