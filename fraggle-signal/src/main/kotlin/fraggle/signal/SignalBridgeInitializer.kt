package fraggle.signal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import fraggle.chat.BridgeInitializer
import fraggle.chat.InitStepResult
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.createDirectories

/**
 * Initializer for Signal bridge registration.
 *
 * Handles the two-step registration process for signal-cli:
 * 1. Register with captcha token to receive verification code
 * 2. Verify with the received code
 *
 * The phone number is read from configuration. Users need to provide:
 * - A captcha token from https://signalcaptchas.org/registration/generate
 * - The verification code received via SMS/voice
 */
class SignalBridgeInitializer(
    private val config: SignalConfig,
    private val installer: SignalCliInstaller? = createInstaller(config),
) : BridgeInitializer {

    private val logger = LoggerFactory.getLogger(SignalBridgeInitializer::class.java)

    companion object {
        private fun createInstaller(config: SignalConfig): SignalCliInstaller? {
            if (!config.autoInstall) return null
            val appsDir = config.appsDir?.let { java.nio.file.Path.of(it) }
                ?: java.nio.file.Path.of("data/apps")
            return SignalCliInstaller(appsDir, config.signalCliVersion)
        }
    }

    private var resolvedCliPath: String? = null

    override val bridgeName = "signal"
    override val description = "Register Signal account with phone verification"

    private enum class State {
        CHECK_STATUS,
        AWAITING_CAPTCHA,
        REGISTERING,
        AWAITING_VERIFICATION,
        VERIFYING,
        COMPLETE,
    }

    private var state = State.CHECK_STATUS
    private var lastError: String? = null

    override suspend fun isInitialized(): Boolean {
        if (config.phoneNumber.isBlank()) {
            return false
        }

        // Use getUserStatus to verify the account is registered and authenticated
        // This returns quickly and will error if auth is expired or incomplete
        return try {
            val result = runSignalCli("getUserStatus", config.phoneNumber)
            // getUserStatus returns 0 if the account is properly registered
            result.exitCode == 0
        } catch (e: Exception) {
            logger.debug("Signal account check failed: ${e.message}")
            false
        }
    }

    override suspend fun initialize(userInput: String?): InitStepResult {
        return when (state) {
            State.CHECK_STATUS -> {
                if (config.phoneNumber.isBlank()) {
                    return InitStepResult.Error(
                        "No phone number configured. Please set 'fraggle.bridges.signal.phone' in your configuration.",
                        recoverable = false
                    )
                }

                if (isInitialized()) {
                    state = State.COMPLETE
                    return InitStepResult.Complete("Signal account is already registered and ready to use.")
                }

                // Ensure config directory exists
                Path(config.configDir).createDirectories()

                state = State.AWAITING_CAPTCHA
                InitStepResult.PromptRequired(
                    prompt = "Enter captcha token",
                    helpText = """
                        |To register your Signal account, you need a captcha token.
                        |
                        |1. Open https://signalcaptchas.org/registration/generate in your browser
                        |2. Complete the captcha challenge
                        |3. Copy the token that appears (starts with 'signalcaptcha://')
                        |
                        |Phone number: ${config.phoneNumber}
                    """.trimMargin(),
                    sensitive = false
                )
            }

            State.AWAITING_CAPTCHA -> {
                val captcha = userInput?.trim()
                if (captcha.isNullOrBlank()) {
                    return InitStepResult.PromptRequired(
                        prompt = "Enter captcha token",
                        helpText = "Captcha token cannot be empty. Get one from https://signalcaptchas.org/registration/generate"
                    )
                }

                state = State.REGISTERING
                doRegistration(captcha)
            }

            State.REGISTERING -> {
                // This shouldn't happen as registration transitions directly
                InitStepResult.Error("Unexpected state during registration")
            }

            State.AWAITING_VERIFICATION -> {
                val code = userInput?.trim()?.replace("-", "")?.replace(" ", "")
                if (code.isNullOrBlank()) {
                    return InitStepResult.PromptRequired(
                        prompt = "Enter verification code",
                        helpText = "Please enter the 6-digit code sent to ${config.phoneNumber}"
                    )
                }

                if (!code.matches(Regex("^\\d{6}$"))) {
                    return InitStepResult.PromptRequired(
                        prompt = "Enter verification code",
                        helpText = "Invalid code format. Please enter the 6-digit code."
                    )
                }

                state = State.VERIFYING
                doVerification(code)
            }

            State.VERIFYING -> {
                // This shouldn't happen as verification transitions directly
                InitStepResult.Error("Unexpected state during verification")
            }

            State.COMPLETE -> {
                InitStepResult.Complete("Signal account is registered and ready to use.")
            }
        }
    }

    private suspend fun doRegistration(captcha: String): InitStepResult {
        logger.info("Starting Signal registration for ${config.phoneNumber}")

        val result = runSignalCli("register", "--captcha", captcha)

        return if (result.exitCode == 0) {
            state = State.AWAITING_VERIFICATION
            InitStepResult.PromptRequired(
                prompt = "Enter the 6-digit code",
                helpText = """
                    |Registration request sent successfully.
                    |A verification code has been sent to ${config.phoneNumber} via SMS.
                """.trimMargin()
            )
        } else {
            state = State.AWAITING_CAPTCHA
            lastError = result.stderr
            InitStepResult.Error(
                message = "Registration failed: ${result.stderr.ifBlank { result.stdout }}",
                recoverable = true
            )
        }
    }

    private suspend fun doVerification(code: String): InitStepResult {
        logger.info("Verifying Signal registration for ${config.phoneNumber}")

        val result = runSignalCli("verify", code)

        return if (result.exitCode == 0) {
            state = State.COMPLETE
            logger.info("Signal registration completed successfully")
            InitStepResult.Complete(
                "Signal account registered successfully! You can now use the Signal bridge."
            )
        } else {
            state = State.AWAITING_VERIFICATION
            lastError = result.stderr
            InitStepResult.Error(
                message = "Verification failed: ${result.stderr.ifBlank { result.stdout }}",
                recoverable = true
            )
        }
    }

    override fun reset() {
        state = State.CHECK_STATUS
        lastError = null
    }

    private suspend fun resolveSignalCliPath(): String {
        // Use explicit path if configured
        config.signalCliPath?.let { return it }

        // Use cached path if already resolved
        resolvedCliPath?.let { return it }

        // Try auto-install if enabled
        if (config.autoInstall && installer != null) {
            // Check if already installed
            installer.getSignalCliPath()?.let {
                resolvedCliPath = it.toString()
                logger.info("Using installed signal-cli at $it")
                return it.toString()
            }

            // Check if in PATH before downloading
            if (SignalCliInstaller.isInPath()) {
                resolvedCliPath = "signal-cli"
                logger.info("Using signal-cli from system PATH")
                return "signal-cli"
            }

            // Install signal-cli
            val installed = installer.ensureInstalled()
            if (installed != null) {
                resolvedCliPath = installed.toString()
                return installed.toString()
            }
        }

        // Fall back to system PATH
        resolvedCliPath = "signal-cli"
        return "signal-cli"
    }

    private suspend fun runSignalCli(vararg args: String): CommandResult {
        val cli = resolveSignalCliPath()

        return withContext(Dispatchers.IO) {
            val command = listOf(
                cli,
                "-a", config.phoneNumber,
                "--config", config.configDir,
            ) + args.toList()

            logger.debug("Running: ${command.joinToString(" ")}")

            try {
                val process = ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start()

                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()

                val completed = process.waitFor(60, TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    return@withContext CommandResult(-1, "", "Command timed out")
                }

                CommandResult(process.exitValue(), stdout.trim(), stderr.trim())
            } catch (e: Exception) {
                logger.error("Failed to run signal-cli: ${e.message}")
                CommandResult(-1, "", e.message ?: "Unknown error")
            }
        }
    }

    private data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}
