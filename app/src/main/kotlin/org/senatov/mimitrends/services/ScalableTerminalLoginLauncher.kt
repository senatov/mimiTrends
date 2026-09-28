package org.senatov.mimitrends.services

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit

/** Opens the official interactive device-code flow without handling credentials in the app. */
internal object ScalableTerminalLoginLauncher {
    fun launch() {
        require(System.getProperty("os.name").contains("mac", ignoreCase = true)) {
            "Open a terminal and run sc login --local-read-only"
        }
        val executable = findExecutable()
        val script = Files.createTempFile("mimitrends-scalable-login-", ".command")
        Files.writeString(script, scriptFor(executable))
        Files.setPosixFilePermissions(
            script, setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE
            )
        )
        script.toFile().deleteOnExit()
        val process = ProcessBuilder("/usr/bin/open", "-a", "Terminal", script.toString()).start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Could not open Terminal")
        }
        if (process.exitValue() != 0) error("Could not open Terminal")
    }

    internal fun scriptFor(executable: Path): String = """
        #!/bin/zsh
        log_file=${'$'}(mktemp -t mimitrends-scalable-login)
        trap 'rm -f "${'$'}log_file"' EXIT
        ${shellQuote(executable.toString())} login --local-read-only >"${'$'}log_file" 2>&1 &
        login_pid=${'$'}!
        for attempt in {1..30}; do
          activate_url=${'$'}(grep -Eo 'https://secure\.scalable\.capital/activate[^ )]+' "${'$'}log_file" | head -1)
          if [[ -n "${'$'}activate_url" ]]; then
            echo
            echo 'Opening the Scalable authorization page in your browser...'
            open "${'$'}activate_url"
            break
          fi
          sleep 1
        done
        wait "${'$'}login_pid"
        result=${'$'}?
        cat "${'$'}log_file"
        echo
        if (( result == 0 )); then
          echo 'Scalable login complete. MiMiTrends will check the session automatically.'
        else
          echo 'Scalable login did not complete. You can retry from MiMiTrends.'
        fi
        echo 'You may close this Terminal window.'
        exit "${'$'}result"
    """.trimIndent() + "\n"

    private fun findExecutable(): Path {
        val name = "sc"
        val candidates = listOf(Path.of("/opt/homebrew/bin", name), Path.of("/usr/local/bin", name)) +
                System.getenv("PATH").orEmpty().split(':').filter(String::isNotBlank).map { Path.of(it, name) }
        return candidates.firstOrNull(Files::isExecutable)
            ?: error("Scalable CLI is not installed")
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}
