package platform

import InstallationConfig
import core.Platform


import core.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock

actual class SystemManager {
    private val processUtils = ProcessUtils()
    private val fileUtils = FileUtils()
    private val logger = Logger

    actual fun isInstalled(platform: Platform): Boolean {
        // Check if salt-minion service exists or binary is present
        val serviceCheck = runBlocking {
            processUtils.execute("systemctl", listOf("list-unit-files", "salt-minion.service"))
        }

        if (serviceCheck.isSuccess && serviceCheck.stdout.contains("salt-minion.service")) {
            return true
        }

        // Check for binary
        return fileUtils.exists("/usr/bin/salt-minion") ||
               fileUtils.exists("/usr/local/bin/salt-minion")
    }

    actual fun getInstalledVersion(platform: Platform): String? {
        val versionResult = runBlocking {
            processUtils.execute("salt-minion", listOf("--version"))
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

        val platform = PlatformDetector().detect()

        when {
            packagePath.endsWith(".deb") -> installDeb(packagePath)
            packagePath.endsWith(".rpm") -> installRpm(packagePath)
            else -> installFromSource(packagePath, config)
        }
    }

    private suspend fun installDeb(packagePath: String) {
        // Update package list first
        val updateResult = processUtils.executeAsRoot("apt-get", listOf("update"))
        if (!updateResult.isSuccess) {
            logger.warn("Failed to update package list")
        }

        // Install the package
        val installResult = processUtils.executeAsRoot("dpkg", listOf("-i", packagePath))

        if (!installResult.isSuccess) {
            // Try to fix dependencies
            logger.info("Fixing dependencies...")
            val fixResult = processUtils.executeAsRoot("apt-get", listOf("install", "-f", "-y"))

            if (!fixResult.isSuccess) {
                throw Exception("Failed to install Salt-Minion: ${installResult.stderr}")
            }
        }
    }

    private suspend fun installRpm(packagePath: String) {
        val installResult = processUtils.executeAsRoot("rpm", listOf("-i", packagePath))

        if (!installResult.isSuccess) {
            // Try with yum/dnf
            val yumResult = processUtils.executeAsRoot("yum", listOf("localinstall", "-y", packagePath))
            if (!yumResult.isSuccess) {
                val dnfResult = processUtils.executeAsRoot("dnf", listOf("localinstall", "-y", packagePath))
                if (!dnfResult.isSuccess) {
                    throw Exception("Failed to install Salt-Minion: ${installResult.stderr}")
                }
            }
        }
    }

    private suspend fun installFromSource(packagePath: String, config: InstallationConfig) {
        // This would involve extracting and compiling from source
        // For now, we'll throw an exception
        throw Exception("Source installation not yet implemented")
    }

    actual fun createService(config: InstallationConfig) {
        logger.info("Creating systemd service")

        val serviceContent = """
[Unit]
Description=The Salt Minion
Documentation=man:salt-minion(1) file:///usr/share/doc/salt/html/contents.html https://docs.saltstack.com/en/latest/
After=network.target salt-master.service

[Service]
KillMode=process
Type=notify
NotifyAccess=all
ExecStart=/usr/bin/salt-minion
LimitNOFILE=8192
User=root
Group=root

[Install]
WantedBy=multi-user.target
        """.trimIndent()

        val serviceFile = "/etc/systemd/system/salt-minion.service"

        if (!fileUtils.writeTextFile(serviceFile, serviceContent)) {
            throw Exception("Failed to create systemd service file")
        }

        // Reload systemd
        runBlocking {
            processUtils.executeAsRoot("systemctl", listOf("daemon-reload"))
            processUtils.executeAsRoot("systemctl", listOf("enable", "salt-minion"))
        }
    }

    actual fun startService(serviceName: String) {
        runBlocking {
            val result = processUtils.executeAsRoot("systemctl", listOf("start", serviceName))
            if (!result.isSuccess) {
                throw Exception("Failed to start service $serviceName: ${result.stderr}")
            }
        }
    }

    actual fun stopService(serviceName: String) {
        runBlocking {
            processUtils.executeAsRoot("systemctl", listOf("stop", serviceName))
        }
    }

    actual fun removeService(serviceName: String) {
        runBlocking {
            processUtils.executeAsRoot("systemctl", listOf("disable", serviceName))
            processUtils.executeAsRoot("systemctl", listOf("stop", serviceName))
        }

        fileUtils.deleteDirectory("/etc/systemd/system/$serviceName.service")

        runBlocking {
            processUtils.executeAsRoot("systemctl", listOf("daemon-reload"))
        }
    }

    actual fun getServiceStatus(serviceName: String): String {
        val result = runBlocking {
            processUtils.execute("systemctl", listOf("is-active", serviceName))
        }

        return if (result.isSuccess) {
            result.stdout.trim()
        } else {
            "inactive"
        }
    }

    actual fun removeInstallation(platform: Platform) {
        logger.info("Removing Salt-Minion installation")

        runBlocking {
            // Remove package
            var removeResult = processUtils.executeAsRoot("apt-get", listOf("remove", "--purge", "-y", "salt-minion"))

            if (!removeResult.isSuccess) {
                // Try yum/dnf
                removeResult = processUtils.executeAsRoot("yum", listOf("remove", "-y", "salt-minion"))
                if (!removeResult.isSuccess) {
                    removeResult = processUtils.executeAsRoot("dnf", listOf("remove", "-y", "salt-minion"))
                }
            }
        }

        // Remove configuration directories
        fileUtils.deleteDirectory("/etc/salt")
        fileUtils.deleteDirectory("/var/cache/salt")
        fileUtils.deleteDirectory("/var/log/salt")
        fileUtils.deleteDirectory("/var/run/salt")
    }

    actual fun backup(platform: Platform) {
        logger.info("Creating backup of existing installation")

        val timestamp = Clock.System.now().epochSeconds
        val backupDir = "/tmp/salt-backup-$timestamp"

        fileUtils.createDirectory(backupDir)

        // Backup configuration
        if (fileUtils.exists("/etc/salt")) {
            fileUtils.copyFile("/etc/salt", "$backupDir/etc-salt")
        }

        // Backup logs (recent ones)
        if (fileUtils.exists("/var/log/salt")) {
            fileUtils.copyFile("/var/log/salt", "$backupDir/var-log-salt")
        }

        logger.info("Backup created at: $backupDir")
    }
}