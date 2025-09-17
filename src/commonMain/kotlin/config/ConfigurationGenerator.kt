package config

import InstallationConfig
import core.FileUtils
import core.Logger
import core.Platform
import core.ProcessUtils
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import platform.PlatformDetector
import kotlin.experimental.ExperimentalNativeApi

@Serializable
data class MinionConfig(
    val masterIp: String,
    val minionId: String,
    val port: Int = 4506,
    val logLevel: String = "warning",
    val autoAcceptKey: Boolean = false,
    val fileClient: String = "remote",
    val pillarRoots: Map<String, List<String>> = mapOf("base" to listOf("/srv/pillar"))
)

@OptIn(ExperimentalForeignApi::class)
class ConfigurationGenerator {
    private val fileUtils = FileUtils()
    private val logger = Logger


    fun generateMinionConfig(config: InstallationConfig, platform: Platform) {
        logger.info("Generating minion configuration")

        val configDir = getConfigDirectory(platform)
        val configFile = "$configDir/minion"

        val minionConfig = createMinionConfigContent(config)

        // Ensure config directory exists
        fileUtils.createDirectory(configDir)

        // Create minion.d directory for additional configs
        fileUtils.createDirectory("$configDir/minion.d")

        // Write main config file
        if (!fileUtils.writeTextFile(configFile, minionConfig)) {
            throw Exception("Failed to write minion configuration file")
        }

        // Generate additional config files
        generateLoggingConfig(config, platform)
        generatePkiConfig(config, platform)

        // Generate PDV/VR specific configuration usando runBlocking
        runBlocking {
            generatePdvVrConfig(config, platform)
        }

        // Setup directory structure for PDV/VR
        setupPdvVrDirectories(platform)

        logger.info("Configuration files generated successfully")
    }

    @OptIn(ExperimentalNativeApi::class)
    private fun setupPdvVrDirectories(platform: Platform) {
        val isWindows = platform.name.lowercase() == "windows"

        val directories = if (isWindows) {
            listOf(
                "C:\\pdv",
                "C:\\pdv\\exec",
                "C:\\pdv\\backup",
                "C:\\vr"
            )
        } else {
            listOf(
                "/pdv",
                "/pdv/exec",
                "/pdv/backup",
                "/vr"
            )
        }

        directories.forEach { dir ->
            if (!fileUtils.createDirectory(dir)) {
                logger.warn("Failed to create directory: $dir")
            } else {
                logger.info("Created directory: $dir")
            }
        }

        // Criar arquivo de exemplo vr.properties se não existir
        val vrPropsPath = if (isWindows) "C:\\vr\\vr.properties" else "/vr/vr.properties"
        if (!fileUtils.exists(vrPropsPath)) {
            val defaultProps = """
# VR Configuration Properties
# Managed by Salt-Minion Universal Installer
system.numeroloja=1

concentrador.ip=192.168.0.1
concentrador.porta=2123

naofiscal.numerocfe=101

        """.trimIndent()

            fileUtils.writeTextFile(vrPropsPath, defaultProps)
            logger.info("Created default vr.properties file")
        }
    }

    private fun createMinionConfigContent(config: InstallationConfig): String {
        val vrPropertiesFile = if (isWindows) "C:/vr/vr.properties" else "/vr/vr.properties"

        return """
# ... configurações anteriores ...

# Configurações específicas para vr.properties
beacons:
  inotify:
    - files:
        $vrPropertiesFile:
          mask:
            - modify
            - create
            - delete
          backup: True
    - interval: 10

# Mine function para propriedades atuais
mine_functions:
  current_vr_properties:
    - cmd.run: '${if (isWindows) "type" else "cat"} $vrPropertiesFile 2>${if (isWindows) "nul" else "/dev/null"} || echo "File not found"'

# Schedule para backup das propriedades
schedule:
  backup_vr_properties:
    function: cp.push
    args:
      - $vrPropertiesFile
    minutes: 15
    
# ... resto da configuração ...
""".trimIndent()
    }


    private fun generateLoggingConfig(config: InstallationConfig, platform: Platform) {
        val logDir = getLogDirectory(platform)
        fileUtils.createDirectory(logDir)

        val loggingConfig = """
# Salt Minion Logging Configuration

[loggers]
keys=root,salt

[handlers]
keys=console,file

[formatters]
keys=generic

[logger_root]
level=WARNING
handlers=console,file

[logger_salt]
level=WARNING
handlers=console,file
qualname=salt

[handler_console]
class=StreamHandler
args=(sys.stderr,)
level=WARNING
formatter=generic

[handler_file]
class=handlers.RotatingFileHandler
args=('${logDir}/minion.log', 'a', 10485760, 5)
level=WARNING
formatter=generic

[formatter_generic]
format=%(asctime)s [%(name)-15s][%(levelname)-8s] %(message)s
datefmt=%Y-%m-%d %H:%M:%S
        """.trimIndent()

        val configDir = getConfigDirectory(platform)
        fileUtils.writeTextFile("$configDir/logging.conf", loggingConfig)
    }

    private fun generatePkiConfig(config: InstallationConfig, platform: Platform) {
        val pkiDir = getPkiDirectory(platform)
        fileUtils.createDirectory(pkiDir)

        // Create subdirectories for PKI
        fileUtils.createDirectory("$pkiDir/accepted_keys")
        fileUtils.createDirectory("$pkiDir/pending_keys")
        fileUtils.createDirectory("$pkiDir/rejected_keys")
    }

    suspend fun setupDirectoryStructure(platform: Platform) {
        logger.info("Setting up directory structure")

        val directories = listOf(
            getConfigDirectory(platform),
            getLogDirectory(platform),
            getCacheDirectory(platform),
            getPkiDirectory(platform),
            getRunDirectory(platform)
        )

        for (dir in directories) {
            if (!fileUtils.createDirectory(dir)) {
                logger.warn("Failed to create directory: $dir")
            }
        }

        // Set appropriate permissions (platform-specific)
        setPlatformPermissions(platform)
    }

    private suspend fun setPlatformPermissions(platform: Platform) {
        when (platform.name.lowercase()) {
            "linux", "ubuntu", "debian", "centos", "rhel" -> {
                // Set ownership and permissions for Salt directories
                val configDir = getConfigDirectory(platform)
                val logDir = getLogDirectory(platform)
                val pkiDir = getPkiDirectory(platform)

                try {
                    val processUtils = ProcessUtils()

                    // Make config directory readable by salt user
                    processUtils.executeAsRoot("chown", listOf("-R", "root:root", configDir))
                    processUtils.executeAsRoot("chmod", listOf("-R", "755", configDir))

                    // Make PKI directory secure
                    processUtils.executeAsRoot("chmod", listOf("-R", "700", pkiDir))

                    // Make log directory writable
                    processUtils.executeAsRoot("chown", listOf("-R", "root:root", logDir))
                    processUtils.executeAsRoot("chmod", listOf("-R", "755", logDir))

                } catch (e: Exception) {
                    logger.warn("Failed to set permissions: ${e.message}")
                }
            }
        }
    }

    fun getCurrentConfig(platform: Platform): MinionConfig? {
        val configFile = "${getConfigDirectory(platform)}/minion"

        return try {
            val content = fileUtils.readTextFile(configFile) ?: return null
            parseMinionConfig(content)
        } catch (e: Exception) {
            logger.error("Failed to read current config: ${e.message}")
            null
        }
    }

    private fun parseMinionConfig(content: String): MinionConfig {
        var masterIp = "127.0.0.1"
        var minionId = "unknown"
        var port = 4506
        var logLevel = "warning"

        content.split("\n").forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("#") || trimmed.isEmpty()) return@forEach

            val parts = trimmed.split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim()

                when (key) {
                    "master" -> masterIp = value
                    "id" -> minionId = value
                    "master_port" -> port = value.toIntOrNull() ?: 4506
                    "log_level" -> logLevel = value
                }
            }
        }

        return MinionConfig(
            masterIp = masterIp,
            minionId = minionId,
            port = port,
            logLevel = logLevel
        )
    }

    private fun getConfigDirectory(platform: Platform): String {
        return when (platform.name.lowercase()) {
            "windows" -> {
                // Check for new installation path first
                when {
                    fileUtils.exists("C:\\ProgramData\\SaltProject\\Salt\\conf") -> "C:\\ProgramData\\SaltProject\\Salt\\conf"
                    fileUtils.exists("C:\\Program Files\\Salt Project\\Salt\\conf") -> "C:\\Program Files\\Salt Project\\Salt\\conf"
                    fileUtils.exists("C:\\salt\\conf") -> "C:\\salt\\conf"
                    else -> "C:\\ProgramData\\SaltProject\\Salt\\conf" // Default to new path
                }
            }

            else -> "/etc/salt"
        }
    }

    private fun getLogDirectory(platform: Platform): String {
        return when (platform.name.lowercase()) {
            "windows" -> {
                when {
                    fileUtils.exists("C:\\ProgramData\\SaltProject\\Salt\\var\\log\\salt") -> "C:\\ProgramData\\SaltProject\\Salt\\var\\log\\salt"
                    fileUtils.exists("C:\\Program Files\\Salt Project\\Salt\\var\\log\\salt") -> "C:\\Program Files\\Salt Project\\Salt\\var\\log\\salt"
                    fileUtils.exists("C:\\salt\\var\\log\\salt") -> "C:\\salt\\var\\log\\salt"
                    else -> "C:\\ProgramData\\SaltProject\\Salt\\var\\log\\salt"
                }
            }

            else -> "/var/log/salt"
        }
    }

    private fun getCacheDirectory(platform: Platform): String {
        return when (platform.name.lowercase()) {
            "windows" -> {
                when {
                    fileUtils.exists("C:\\ProgramData\\SaltProject\\Salt\\var\\cache\\salt\\minion") -> "C:\\ProgramData\\SaltProject\\Salt\\var\\cache\\salt\\minion"
                    fileUtils.exists("C:\\Program Files\\Salt Project\\Salt\\var\\cache\\salt\\minion") -> "C:\\Program Files\\Salt Project\\Salt\\var\\cache\\salt\\minion"
                    fileUtils.exists("C:\\salt\\var\\cache\\salt\\minion") -> "C:\\salt\\var\\cache\\salt\\minion"
                    else -> "C:\\ProgramData\\SaltProject\\Salt\\var\\cache\\salt\\minion"
                }
            }

            else -> "/var/cache/salt/minion"
        }
    }

    private fun getPkiDirectory(platform: Platform): String {
        return when (platform.name.lowercase()) {
            "windows" -> {
                when {
                    fileUtils.exists("C:\\ProgramData\\SaltProject\\Salt\\conf\\pki\\minion") -> "C:\\ProgramData\\SaltProject\\Salt\\conf\\pki\\minion"
                    fileUtils.exists("C:\\Program Files\\Salt Project\\Salt\\conf\\pki\\minion") -> "C:\\Program Files\\Salt Project\\Salt\\conf\\pki\\minion"
                    fileUtils.exists("C:\\salt\\conf\\pki\\minion") -> "C:\\salt\\conf\\pki\\minion"
                    else -> "C:\\ProgramData\\SaltProject\\Salt\\conf\\pki\\minion"
                }
            }

            else -> "/etc/salt/pki/minion"
        }
    }

    private fun getRunDirectory(platform: Platform): String {
        return when (platform.name.lowercase()) {
            "windows" -> {
                when {
                    fileUtils.exists("C:\\ProgramData\\SaltProject\\Salt\\var\\run\\salt\\minion") -> "C:\\ProgramData\\SaltProject\\Salt\\var\\run\\salt\\minion"
                    fileUtils.exists("C:\\Program Files\\Salt Project\\Salt\\var\\run\\salt\\minion") -> "C:\\Program Files\\Salt Project\\Salt\\var\\run\\salt\\minion"
                    fileUtils.exists("C:\\salt\\var\\run\\salt\\minion") -> "C:\\salt\\var\\run\\salt\\minion"
                    else -> "C:\\ProgramData\\SaltProject\\Salt\\var\\run\\salt\\minion"
                }
            }

            else -> "/var/run/salt/minion"
        }
    }

    private suspend fun generatePdvVrConfig(config: InstallationConfig, platform: Platform) {
        logger.info("Generating PDV/VR monitoring configuration")

        val configDir = getConfigDirectory(platform)
        val isWindows = platform.name.lowercase() == "windows"

        // Configuração específica do PDV/VR
        val pdvVrConfig = createPdvVrConfigContent(isWindows)

        val pdvConfigFile = "$configDir/minion.d/pdv_vr.conf"

        if (!fileUtils.writeTextFile(pdvConfigFile, pdvVrConfig)) {
            throw Exception("Failed to write PDV/VR configuration file")
        }

        // Gerar scripts de monitoramento
        generateMonitoringScripts(platform)

        logger.info("PDV/VR configuration generated successfully")
    }

    private fun createPdvVrConfigContent(isWindows: Boolean): String {
        val pdvPath = if (isWindows) "C:\\pdv" else "/pdv"
        val vrPath = if (isWindows) "C:\\vr" else "/vr"
        val scriptExt = if (isWindows) ".bat" else ".sh"

        return """
# PDV/VR Specific Configuration

# Custom modules for PDV/VR operations
modules:
  - pdv_utils
  - vr_utils

# PDV/VR specific pillar data
pillar:
  pdv:
    base_path: $pdvPath
    exec_path: $pdvPath${if (isWindows) "\\" else "/"}exec
    jar_file: VRPdv.jar
    backup_retention: 7  # days
    allowed_extensions:
      - .jar
      - .properties
      - .xml
      - .json
  
  vr:
    base_path: $vrPath
    properties_file: vr.properties
    backup_enabled: true
    allowed_modifications:
      - database.url
      - database.username
      - server.port
      - logging.level

# File server configuration for PDV/VR
file_roots:
  base:
    - $pdvPath
    - $vrPath

# Custom grain modules
grain_modules:
  - pdv_grains
  - vr_grains

# Execution modules
execution_modules:
  - pdv_manager
  - vr_manager

# Event return configuration
return_events:
  - pdv_events
  - vr_events

# Custom beacons for enhanced monitoring
beacons:
  service:
    - services:
        - java
        - tomcat
        - pdv-service
    - interval: 30
  
  diskusage:
    - /: 
        - 85%
    - $pdvPath:
        - 90%
    - $vrPath:
        - 90%
    - interval: 300

# Event tags for PDV/VR operations
tags:
  - pdv-minion
  - vr-monitor
  - file-watcher
    """.trimIndent()
    }

    private suspend fun generateMonitoringScripts(platform: Platform) {
        val isWindows = platform.name.lowercase() == "windows"
        val configDir = getConfigDirectory(platform)
        val scriptsDir = "$configDir/scripts"

        val scriptGenerator = ScriptGenerator()

        fileUtils.createDirectory(scriptsDir)

        if (isWindows) {
            scriptGenerator.generateBackupScript(scriptsDir)
            scriptGenerator.generateIntegrityScript(scriptsDir)
        } else {
            scriptGenerator.generateBackupScript(scriptsDir)
            scriptGenerator.generateIntegrityScript(scriptsDir)
        }
    }


}