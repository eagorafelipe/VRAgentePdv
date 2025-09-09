package core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.posix.system

@OptIn(ExperimentalForeignApi::class)
actual class ProcessUtils {
    actual suspend fun execute(
        command: String,
        args: List<String>,
        workingDir: String?,
        captureOutput: Boolean
    ): ProcessResult = withContext(Dispatchers.Default) {

        // Simplified approach using system() call for better compatibility
        val fullCommand = buildString {
            append("\"$command\"")
            if (args.isNotEmpty()) {
                append(" ")
                append(args.joinToString(" ") { "\"$it\"" })
            }
        }

        // Change directory if specified
        val commandWithDir = if (workingDir != null) {
            "cd /d \"$workingDir\" && $fullCommand"
        } else {
            fullCommand
        }

        memScoped {
            val exitCode = system(commandWithDir)
            ProcessResult(exitCode, "", "")
        }
    }

    actual suspend fun executeAsRoot(
        command: String,
        args: List<String>,
        workingDir: String?
    ): ProcessResult {
        // Use PowerShell with elevated privileges
        val psArgs = buildList {
            add("-Command")
            add("Start-Process")
            add("-FilePath")
            add("'$command'")
            if (args.isNotEmpty()) {
                add("-ArgumentList")
                add("'${args.joinToString("','")}'")
            }
            add("-Verb")
            add("RunAs")
            add("-Wait")
            if (workingDir != null) {
                add("-WorkingDirectory")
                add("'$workingDir'")
            }
        }

        return execute("powershell", psArgs, null)
    }
}