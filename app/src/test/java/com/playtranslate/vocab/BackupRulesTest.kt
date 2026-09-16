package com.playtranslate.vocab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pins the backup policy to the hidden-words store: the ONE directory that
 * rides Auto Backup and device transfer is [HiddenWordsStore.BACKUP_DIR],
 * named by an include in every section of both rule files (the 12+ file's
 * cloud-backup and device-transfer, the 29-30 file's full-backup-content).
 * The DIRECTORY, not the store file: a crash mid-write leaves a rollback
 * journal beside the file until the store's next open, and a restore of
 * the file alone would be a half-applied write with nothing to roll it
 * back, whereas the pair restores as the post-crash state SQLite recovers
 * from. The belt-and-braces excludes stay in place for the domains that
 * hold secrets and private data, and the manifest flag is on so the rules
 * are live. Guards the silent-rename hazard (a moved store would simply
 * stop being backed up) and the reopened-channel hazard (an include that
 * goes missing would make the defaults back up everything, which is what
 * the kept excludes and this test are for). Plain JVM: reads the source
 * files, no Android involved.
 */
class BackupRulesTest {

    private fun source(relative: String): File =
        listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
            ?: error("not found from ${File(".").absolutePath}: $relative")

    private fun rules(element: Element): List<Triple<String, String, String>> =
        (0 until element.childNodes.length)
            .map { element.childNodes.item(it) }
            .filterIsInstance<Element>()
            .filter { it.tagName == "include" || it.tagName == "exclude" }
            .map { Triple(it.tagName, it.getAttribute("domain"), it.getAttribute("path")) }

    private fun assertSection(name: String, section: Element) {
        val r = rules(section)
        // Every rule carries a path: a path-less rule is silently skipped by
        // the platform, which is the documented syntax trap.
        assertTrue("$name: every rule needs a path", r.all { it.third.isNotEmpty() })
        assertTrue(
            "$name: the store's directory is included (journal rides along)",
            Triple("include", "file", HiddenWordsStore.BACKUP_DIR) in r,
        )
        assertEquals("$name: exactly one include", 1, r.count { it.first == "include" })
        assertFalse(
            "$name: the file domain root is no longer excluded (it would swallow the include)",
            Triple("exclude", "file", ".") in r,
        )
        assertFalse(
            "$name: nothing under the store's directory is excluded",
            r.any { it.first == "exclude" && it.second == "file" && it.third.startsWith(HiddenWordsStore.BACKUP_DIR) },
        )
        // The other residents of files/ stay out by name.
        assertTrue(Triple("exclude", "file", "vad") in r)
        assertTrue(Triple("exclude", "file", "crashes") in r)
        // The secrets and private-data guards do not rest on include-only
        // semantics alone. Every one of these domains is a SIBLING of files/.
        for (domain in listOf("sharedpref", "database", "external",
            "device_root", "device_file", "device_database", "device_sharedpref")) {
            assertTrue("$name: $domain root excluded", Triple("exclude", domain, ".") in r)
        }
        // Never the root domain: it is the whole data directory, an ANCESTOR of
        // files/, and the platform matches directory excludes by path prefix on
        // restore (before the includes), so a root exclude silently rejects every
        // restored file. Caught on Thor: backup logged the store file, restore
        // wrote nothing.
        assertFalse(
            "$name: the root domain must never be excluded (it swallows every restore)",
            r.any { it.first == "exclude" && it.second == "root" },
        )
    }

    private fun parse(relative: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(source(relative)).documentElement

    @Test
    fun api31RulesIncludeTheStoreInBothChannels() {
        val root = parse("src/main/res/xml/data_extraction_rules.xml")
        assertEquals("data-extraction-rules", root.tagName)
        val sections = root.getElementsByTagName("*").let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }
        }.filter { it.parentNode === root }
        assertEquals(
            "cloud-backup and device-transfer only; cross-platform-transfer must stay absent",
            listOf("cloud-backup", "device-transfer"), sections.map { it.tagName },
        )
        sections.forEach { assertSection(it.tagName, it) }
    }

    @Test
    fun api29RulesIncludeTheStore() {
        val root = parse("src/main/res/xml/backup_rules.xml")
        assertEquals("full-backup-content", root.tagName)
        assertSection(root.tagName, root)
    }

    @Test
    fun manifestTurnsBackupOnAndReferencesBothRuleFiles() {
        val manifest = source("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"true\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
    }

    @Test
    fun storePathIsUnderFilesDirInsideTheBackedUpDirectory() {
        // The include is in the "file" domain, i.e. relative to filesDir: the
        // store must resolve there, never under no_backup, and directly
        // inside the included directory so its journal sibling is covered.
        assertEquals("vocab", HiddenWordsStore.BACKUP_DIR)
        assertEquals("vocab/hidden_words.sqlite", HiddenWordsStore.BACKUP_PATH)
        assertFalse(HiddenWordsStore.BACKUP_DIR.startsWith("/"))
        assertEquals(HiddenWordsStore.BACKUP_DIR, File(HiddenWordsStore.BACKUP_PATH).parent)
    }
}
