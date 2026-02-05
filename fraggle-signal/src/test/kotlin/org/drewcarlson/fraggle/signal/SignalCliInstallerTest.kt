package org.drewcarlson.fraggle.signal

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.*

class SignalCliInstallerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var installer: SignalCliInstaller

    @BeforeEach
    fun setup() {
        installer = SignalCliInstaller(tempDir, "0.13.23")
    }

    @Nested
    inner class InstallDirTests {

        @Test
        fun `installDir includes version in path`() {
            val installDir = installer.installDir

            assertTrue(installDir.toString().contains("signal-cli-0.13.23"))
            assertEquals(tempDir.resolve("signal-cli-0.13.23"), installDir)
        }

        @Test
        fun `different versions have different install directories`() {
            val installer1 = SignalCliInstaller(tempDir, "0.13.22")
            val installer2 = SignalCliInstaller(tempDir, "0.13.23")

            assertTrue(installer1.installDir != installer2.installDir)
        }
    }

    @Nested
    inner class IsInstalledTests {

        @Test
        fun `isInstalled returns false when directory does not exist`() {
            assertFalse(installer.isInstalled())
        }

        @Test
        fun `isInstalled returns false when directory exists but no binary`() {
            installer.installDir.createDirectories()

            assertFalse(installer.isInstalled())
        }

        @Test
        fun `getSignalCliPath returns null when not installed`() {
            assertNull(installer.getSignalCliPath())
        }
    }

    @Nested
    inner class LinuxNativeBinaryTests {

        @Test
        fun `getSignalCliPath returns binary path on Linux when installed`() {
            // Only run this test on Linux
            if (!SignalCliInstaller.isLinux()) {
                return
            }

            // Simulate an installed native binary
            val installDir = installer.installDir
            installDir.createDirectories()
            val binaryPath = installDir.resolve("signal-cli")
            binaryPath.writeText("#!/bin/sh\necho test")
            binaryPath.toFile().setExecutable(true)

            val result = installer.getSignalCliPath()

            assertNotNull(result)
            assertEquals(binaryPath, result)
            assertTrue(installer.isInstalled())
        }
    }

    @Nested
    inner class JavaVersionTests {

        @Test
        fun `getSignalCliPath returns script path on non-Linux when installed`() {
            // Only run this test on non-Linux
            if (SignalCliInstaller.isLinux()) {
                return
            }

            // Simulate an installed Java version
            val installDir = installer.installDir
            val binDir = installDir.resolve("signal-cli-0.13.23").resolve("bin")
            binDir.createDirectories()
            val scriptPath = binDir.resolve("signal-cli")
            scriptPath.writeText("#!/bin/sh\necho test")

            val result = installer.getSignalCliPath()

            assertNotNull(result)
            assertEquals(scriptPath, result)
            assertTrue(installer.isInstalled())
        }
    }

    @Nested
    inner class UninstallTests {

        @Test
        fun `uninstall removes installation directory`() {
            // Create a fake installation
            val installDir = installer.installDir
            installDir.createDirectories()
            installDir.resolve("signal-cli").writeText("fake binary")
            installDir.resolve("lib").createDirectory()
            installDir.resolve("lib/dep.jar").writeText("fake jar")

            assertTrue(installDir.exists())

            val result = installer.uninstall()

            assertTrue(result)
            assertFalse(installDir.exists())
        }

        @Test
        fun `uninstall returns true when directory does not exist`() {
            assertFalse(installer.installDir.exists())

            val result = installer.uninstall()

            assertTrue(result)
        }
    }

    @Nested
    inner class CompanionTests {

        @Test
        fun `isLinux returns expected value`() {
            val osName = System.getProperty("os.name").lowercase()
            val expected = osName.contains("linux")

            assertEquals(expected, SignalCliInstaller.isLinux())
        }

        @Test
        fun `DEFAULT_VERSION is set`() {
            assertEquals("0.13.23", SignalCliInstaller.DEFAULT_VERSION)
        }
    }
}
