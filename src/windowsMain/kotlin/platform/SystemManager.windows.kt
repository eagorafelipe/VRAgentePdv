package platform


import InstallationConfig
import core.FileUtils
import core.Logger
import core.Platform
import core.ProcessUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock

actual class SystemManager {
    private val processUtils = ProcessUtils()
    private val fileUtils = FileUtils()
    private val logger = Logger

    actual fun isInstalled(platform: Platform): Boolean {
        // Check if Salt service exists
        val serviceCheck = runBlocking {
            processUtils.execute("sc", listOf("query", "salt-minion"))
        }

        if (serviceCheck.isSuccess) {
            return true
        }

        // Check for installation directory
        return fileUtils.exists("C:\\salt") ||
               fileUtils.exists("C:\\Program Files\\Salt Project\\Salt")
    }

    actual fun getInstalledVersion(platform: Platform): String? {
        val saltExe = findSaltExecutable() ?: return null

        val versionResult = runBlocking {
            processUtils.execute(saltExe, listOf("--version"))
        }

        return if (versionResult.isSuccess) {
            // Parse version from output like "salt-minion 3006.4"
            val output = versionResult.stdout.trim()
            val parts = output.split(" ")
            if (parts.size >= 2) parts[1] else "unknown"
        } else {
            null
        }
    }

    actual suspend fun install(packagePath: String, config: InstallationConfig) {
        logger.info("Installing Salt-Minion from $packagePath")

        if (packagePath.endsWith(".exe")) {
            installMsi(packagePath, config)
        } else {
            throw Exception("Unsupported package format for Windows")
        }
    }

    private suspend fun installMsi(packagePath: String, config: InstallationConfig) {
        // Silent installation with parameters
        val installArgs = listOf(
            "/S",  // Silent install
            "/master=${config.masterIp}",
            "/minion-name=${config.minionId}",
            "/start-service=1"
        )

        val installResult = processUtils.executeAsRoot(packagePath, installArgs)

        if (!installResult.isSuccess) {
            throw Exception("Failed to install Salt-Minion: ${installResult.stderr}")
        }

        // Wait for installation to complete
        delay(10000)

        // Verify installation
        if (!isInstalled(PlatformDetector().detect())) {
            throw Exception("Installation verification failed")
        }
    }

    actual fun createService(config: InstallationConfig) {
        // Salt Windows installer usually creates the service automatically
        logger.info("Service should be created automatically by installer")

        // Verify service exists
        val serviceCheck = runBlocking {
            processUtils.execute("sc", listOf("query", "salt-minion"))
        }

        if (!serviceCheck.isSuccess) {
            logger.warn("Service not found, attempting manual creation")
            createServiceManually(config)
        }
    }

    private fun createServiceManually(config: InstallationConfig) {
        val saltExe = findSaltExecutable() ?: throw Exception("Salt executable not found")

        runBlocking {
            val createResult = processUtils.executeAsRoot("sc", listOf(
                "create", "salt-minion",
                "binPath=$saltExe",
                "DisplayName=Salt Minion",
                "start=auto"
            ))

            if (!createResult.isSuccess) {
                throw Exception("Failed to create service: ${createResult.stderr}")
            }
        }
    }

    actual fun startService(serviceName: String) {
        runBlocking {
            val result = processUtils.executeAsRoot("sc", listOf("start", serviceName))
            if (!result.isSuccess) {
                // Try with net start as fallback
                val netResult = processUtils.executeAsRoot("net", listOf("start", serviceName))
                if (!netResult.isSuccess) {
                    throw Exception("Failed to start service $serviceName")
                }
            }
        }
    }

    actual fun stopService(serviceName: String) {
        runBlocking {
            val result = processUtils.executeAsRoot("sc", listOf("stop", serviceName))
            if (!result.isSuccess) {
                processUtils.executeAsRoot("net", listOf("stop", serviceName))
            }
        }
    }

    actual fun removeService(serviceName: String) {
        stopService(serviceName)

        runBlocking {
            processUtils.executeAsRoot("sc", listOf("delete", serviceName))
        }
    }

    actual fun getServiceStatus(serviceName: String): String {
        val result = runBlocking {
            processUtils.execute("sc", listOf("query", serviceName))
        }

        return if (result.isSuccess) {
            val output = result.stdout
            when {
                output.contains("RUNNING") -> "running"
                output.contains("STOPPED") -> "stopped"
                output.contains("START_PENDING") -> "starting"
                output.contains("STOP_PENDING") -> "stopping"
                else -> "unknown"
            }
        } else {
            "not-found"
        }
    }

    actual fun removeInstallation(platform: Platform) {
        logger.info("Removing Salt-Minion installation")

        // Try to find uninstaller
        val uninstallerPaths = listOf(
            "C:\\salt\\uninst.exe",
            "C:\\Program Files\\Salt Project\\Salt\\uninst.exe"
        )

        val uninstaller = uninstallerPaths.find { fileUtils.exists(it) }

        if (uninstaller != null) {
            runBlocking {
                processUtils.executeAsRoot(uninstaller, listOf("/S"))
            }
        } else {
            // Manual removal
            logger.warn("Uninstaller not found, performing manual removal")

            // Remove directories
            fileUtils.deleteDirectory("C:\\salt")
            fileUtils.deleteDirectory("C:\\Program Files\\Salt Project\\Salt")

            // Remove from Add/Remove Programs using PowerShell
            runBlocking {
                val psCommand = """
                    Get-WmiObject -Class Win32_Product | Where-Object { ${'$'}_.Name -like "*Salt*" } | ForEach-Object { ${'$'}_.Uninstall() }
                """.trimIndent()

                processUtils.executeAsRoot("powershell", listOf("-Command", psCommand))
            }
        }
    }

    actual fun backup(platform: Platform) {
        logger.info("Creating backup of existing installation")

        val timestamp = Clock.System.now().epochSeconds
        val backupDir = "C:\\temp\\salt-backup-$timestamp"

        fileUtils.createDirectory(backupDir)

        // Backup configuration
        val configDirs = listOf("C:\\salt\\conf", "C:\\Program Files\\Salt Project\\Salt\\conf")
        for (configDir in configDirs) {
            if (fileUtils.exists(configDir)) {
                fileUtils.copyFile(configDir, "$backupDir\\conf")
                break
            }
        }

        logger.info("Backup created at: $backupDir")
    }

    private fun findSaltExecutable(): String? {
        val possiblePaths = listOf(
            "C:\\salt\\salt-minion.exe",
            "C:\\Program Files\\Salt Project\\Salt\\salt-minion.exe"
        )

        return possiblePaths.find { fileUtils.exists(it) }
    }
}