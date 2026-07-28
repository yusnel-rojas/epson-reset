package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.update.AppVersion
import nl.redlabs.epsonreset.update.UpdateCheck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckTest {

    private fun release(tag: String, page: String = "https://example.invalid/r", name: String? = null) =
        """{ "tag_name": "$tag", "html_url": "$page"${name?.let { ""","name":"$it"""" } ?: ""} }"""

    // --- version comparison -------------------------------------------------

    @Test
    fun `a higher component is newer`() {
        assertEquals(1, UpdateCheck.compare("1.3.0", "1.2.9"))
        assertEquals(-1, UpdateCheck.compare("1.2.9", "1.3.0"))
        assertEquals(0, UpdateCheck.compare("1.2.3", "1.2.3"))
    }

    @Test
    fun `components compare as numbers, not as text`() {
        assertEquals(1, UpdateCheck.compare("1.10.0", "1.9.0"))
        assertEquals(-1, UpdateCheck.compare("2.0.0", "10.0.0"))
    }

    @Test
    fun `a leading v is not part of the version`() {
        assertEquals(0, UpdateCheck.compare("v1.2.3", "1.2.3"))
        assertEquals(1, UpdateCheck.compare("v1.2.4", "1.2.3"))
    }

    @Test
    fun `a missing component reads as zero`() {
        assertEquals(0, UpdateCheck.compare("1.2", "1.2.0"))
        assertEquals(1, UpdateCheck.compare("1.3", "1.2.9"))
    }

    @Test
    fun `a release beats its own pre-releases`() {
        assertEquals(1, UpdateCheck.compare("1.2.0", "1.2.0-rc1"))
        assertEquals(-1, UpdateCheck.compare("1.2.0-rc1", "1.2.0"))
        assertEquals(0, UpdateCheck.compare("1.2.0-rc1", "1.2.0-rc1"))
    }

    @Test
    fun `build metadata says nothing about ordering`() {
        assertEquals(0, UpdateCheck.compare("1.2.0+abc123", "1.2.0"))
    }

    @Test
    fun `a version that isn't one refuses to compare`() {
        assertNull(UpdateCheck.compare("dev", "1.2.3"))
        assertNull(UpdateCheck.compare("1.2.3", "dev"))
        assertNull(UpdateCheck.compare("", "1.2.3"))
        assertNull(UpdateCheck.compare("1..3", "1.2.3"))
        assertNull(UpdateCheck.compare("nightly-2026-01-01", "1.2.3"))
    }

    @Test
    fun `isNewer is false whenever the comparison could not be made`() {
        assertTrue(UpdateCheck.isNewer("1.2.4", "1.2.3"))
        assertFalse(UpdateCheck.isNewer("1.2.3", "1.2.4"))
        assertFalse(UpdateCheck.isNewer("1.2.3", "1.2.3"))
        assertFalse(UpdateCheck.isNewer("1.2.4", AppVersion.DEV))
    }

    // --- parsing GitHub's reply ---------------------------------------------

    @Test
    fun `a release object yields its tag and page`() {
        val parsed = assertNotNull(UpdateCheck.parse(release("v1.4.0", "https://example.invalid/1.4.0")))

        assertEquals("v1.4.0", parsed.version)
        assertEquals("https://example.invalid/1.4.0", parsed.page)
    }

    @Test
    fun `a reply without a usable tag is not a release`() {
        assertNull(UpdateCheck.parse("""{ "message": "Not Found" }"""))
        assertNull(UpdateCheck.parse("""{ "tag_name": "" }"""))
        assertNull(UpdateCheck.parse("not json"))
    }

    @Test
    fun `a release with no page falls back to the releases page`() {
        val parsed = assertNotNull(UpdateCheck.parse("""{ "tag_name": "v1.4.0" }"""))

        assertEquals(UpdateCheck.RELEASES_PAGE, parsed.page)
    }

    // --- the check as a whole -----------------------------------------------

    @Test
    fun `a newer release is offered`() {
        val result = UpdateCheck.check(current = "1.2.0") { release("v1.3.0") }

        val available = assertIs<UpdateCheck.Result.Available>(result)
        assertEquals("v1.3.0", available.release.version)
    }

    @Test
    fun `the running version is up to date against itself`() {
        assertIs<UpdateCheck.Result.UpToDate>(UpdateCheck.check(current = "1.3.0") { release("v1.3.0") })
    }

    @Test
    fun `a build ahead of the latest release is not told to downgrade`() {
        assertIs<UpdateCheck.Result.UpToDate>(UpdateCheck.check(current = "1.4.0") { release("v1.3.0") })
    }

    @Test
    fun `a dev build is never told to upgrade`() {
        val result = UpdateCheck.check(current = AppVersion.DEV) { release("v9.9.9") }

        assertIs<UpdateCheck.Result.Unknown>(result)
    }

    @Test
    fun `a network failure is a value, not an exception`() {
        val result = UpdateCheck.check(current = "1.2.0") { error("connection refused") }

        assertEquals("connection refused", assertIs<UpdateCheck.Result.Failed>(result).detail)
    }

    @Test
    fun `a repository with no releases yet is not an update`() {
        val result = UpdateCheck.check(current = "1.2.0") { """{ "message": "Not Found" }""" }

        assertIs<UpdateCheck.Result.Unknown>(result)
    }

    @Test
    fun `the version resource is readable, whatever it says`() {
        assertTrue(AppVersion.current.isNotBlank())
    }
}
