package com.planterior.helper

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ReleasePackagingSourceContractTest {
    private val root = repositoryRoot()

    @Test
    fun `release manifest uses adaptive launcher network security and disabled backup`() {
        val application =
            xml(root.resolve("app/src/main/AndroidManifest.xml"))
                .getElementsByTagName("application")
                .item(0) as Element

        assertEquals("@mipmap/ic_launcher", application.androidAttribute("icon"))
        assertEquals("@mipmap/ic_launcher_round", application.androidAttribute("roundIcon"))
        assertEquals(
            "@xml/network_security_config",
            application.androidAttribute("networkSecurityConfig"),
        )
        assertEquals("false", application.androidAttribute("usesCleartextTraffic"))
        assertEquals("false", application.androidAttribute("allowBackup"))
        assertEquals("@xml/backup_rules", application.androidAttribute("fullBackupContent"))
        assertEquals(
            "@xml/data_extraction_rules",
            application.androidAttribute("dataExtractionRules"),
        )
    }

    @Test
    fun `production denies cleartext and debug permits only emulator hosts`() {
        val production = xml(root.resolve("app/src/main/res/xml/network_security_config.xml"))
        val productionBase = production.getElementsByTagName("base-config").item(0) as Element
        assertEquals("false", productionBase.getAttribute("cleartextTrafficPermitted"))
        assertEquals(0, production.getElementsByTagName("domain-config").length)

        val debug = xml(root.resolve("app/src/debug/res/xml/network_security_config.xml"))
        val debugBase = debug.getElementsByTagName("base-config").item(0) as Element
        assertEquals("false", debugBase.getAttribute("cleartextTrafficPermitted"))
        val domainConfig = debug.getElementsByTagName("domain-config").item(0) as Element
        assertEquals("true", domainConfig.getAttribute("cleartextTrafficPermitted"))
        val domains =
            domainConfig
                .getElementsByTagName("domain")
                .asElements()
                .map { it.textContent.trim() }
                .toSet()
        assertEquals(setOf("10.0.2.2", "localhost"), domains)

        val authDebugManifest =
            root.resolve("feature/auth/src/debug/AndroidManifest.xml").readText()
        assertFalse(authDebugManifest.contains("usesCleartextTraffic=\"true\""))
    }

    @Test
    fun `backup and data extraction rules exclude every storage domain consistently`() {
        val expected =
            setOf(
                "root",
                "file",
                "database",
                "sharedpref",
                "external",
                "device_root",
                "device_file",
                "device_database",
                "device_sharedpref",
            )
        val legacy = excludedDomains(xml(root.resolve("app/src/main/res/xml/backup_rules.xml")))
        val modern = xml(root.resolve("app/src/main/res/xml/data_extraction_rules.xml"))
        val cloud = modern.getElementsByTagName("cloud-backup").item(0) as Element
        val transfer = modern.getElementsByTagName("device-transfer").item(0) as Element

        assertEquals(expected, legacy)
        assertEquals(expected, excludedDomains(cloud))
        assertEquals(expected, excludedDomains(transfer))
    }

    @Test
    fun `adaptive icon declares background foreground and round launcher resources`() {
        val icon = xml(root.resolve("app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml"))
        val round = xml(root.resolve("app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml"))

        listOf(icon, round).forEach { adaptive ->
            assertEquals("adaptive-icon", adaptive.documentElement.tagName)
            assertEquals(
                "@color/ic_launcher_background",
                (adaptive.getElementsByTagName("background").item(0) as Element).androidAttribute(
                    "drawable"
                ),
            )
            assertEquals(
                "@drawable/ic_launcher_foreground",
                (adaptive.getElementsByTagName("foreground").item(0) as Element).androidAttribute(
                    "drawable"
                ),
            )
        }
        assertTrue(
            Files.isRegularFile(root.resolve("app/src/main/res/mipmap-anydpi/ic_launcher.xml"))
        )
        assertTrue(
            Files.isRegularFile(
                root.resolve("app/src/main/res/mipmap-anydpi/ic_launcher_round.xml")
            )
        )
        val foreground =
            root.resolve("app/src/main/res/drawable/ic_launcher_foreground.xml").readText()
        assertTrue(foreground.contains("#2E7D32"))
        assertTrue(foreground.contains("#FFFFFF"))
    }

    @Test
    fun `release consumes generated profile from a real generator module`() {
        val settings = root.resolve("settings.gradle.kts").readText()
        val appBuild = root.resolve("app/build.gradle.kts").readText()
        val generatorBuild = root.resolve("baselineprofile/build.gradle.kts").readText()
        val generator =
            root
                .resolve(
                    "baselineprofile/src/main/kotlin/com/planterior/helper/baselineprofile/BaselineProfileGenerator.kt"
                )
                .readText()
        val profile =
            root.resolve("app/src/release/generated/baselineProfiles/baseline-prof.txt").readText()

        assertTrue(settings.contains("\":baselineprofile\""))
        assertTrue(appBuild.contains("alias(libs.plugins.androidx.baselineprofile)"))
        assertTrue(appBuild.contains("baselineProfile(project(\":baselineprofile\"))"))
        assertTrue(generatorBuild.contains("alias(libs.plugins.android.test)"))
        assertTrue(generatorBuild.contains("alias(libs.plugins.androidx.baselineprofile)"))
        assertTrue(generatorBuild.contains("targetProjectPath = \":app\""))
        assertTrue(generator.contains("BaselineProfileRule"))
        assertTrue(generator.contains("startActivityAndWait()"))
        assertTrue(generator.contains("By.text(\"로그인하고 시작하기\")"))
        assertTrue(generator.contains("By.text(\"Google로 계속하기\")"))
        assertTrue(generator.contains("device.pressBack()"))
        assertTrue(
            profile.lineSequence().any { it.contains("Lcom/planterior/helper/MainActivity;") }
        )
        assertTrue(
            profile.lineSequence().any {
                it.contains("feature/auth") || it.contains("feature/home")
            }
        )
    }

    private fun excludedDomains(document: org.w3c.dom.Document): Set<String> =
        excludedDomains(document.documentElement)

    private fun excludedDomains(element: Element): Set<String> =
        element
            .getElementsByTagName("exclude")
            .asElements()
            .onEach { assertEquals(".", it.getAttribute("path")) }
            .map { it.getAttribute("domain") }
            .toSet()

    private fun xml(path: Path) =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(path.toFile())

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)

    private fun org.w3c.dom.NodeList.asElements(): List<Element> =
        (0 until length).map { item(it) as Element }

    private fun repositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (!Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent ?: error("Repository root unavailable")
        }
        return current
    }
}
