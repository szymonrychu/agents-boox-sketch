package io.github.szymonrychu.booxsketch.documents

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

class ShareBundleTest {
    @get:Rule val tmp = TemporaryFolder()

    private val a = "0b7f6a1e-2c3d-4e5f-8a9b-0c1d2e3f4a5b"
    private val b = "11111111-2222-4333-8444-555555555555"
    private val md = "# Spec\n\n![image](images/$a.png)\n\ntext\n\n![image](images/$b.png)\n\n![image](images/$a.png)\n"

    private fun entries(zip: java.io.File): Map<String, ByteArray> =
        ZipFile(zip).use { z -> z.entries().toList().associate { it.name to z.getInputStream(it).readBytes() } }

    @Test
    fun zip_has_markdown_and_every_image() =
        runBlocking<Unit> {
            val zip = ShareBundle.build(tmp.root, 4, md) { id -> byteArrayOf(id.first().code.toByte()) }
            assertThat(zip.name).isEqualTo("spec-v4.zip")
            val e = entries(zip)
            assertThat(e.keys).containsExactly("spec-v4.md", "images/$a.png", "images/$b.png")
            // The markdown is unchanged: its references already point into images/.
            assertThat(String(e.getValue("spec-v4.md"))).isEqualTo(md)
            assertThat(e.getValue("images/$a.png")).isEqualTo(byteArrayOf('0'.code.toByte()))
        }

    @Test
    fun missing_image_listed() =
        runBlocking<Unit> {
            val zip = ShareBundle.build(tmp.root, 4, md) { id -> if (id == a) byteArrayOf(1) else null }
            val e = entries(zip)
            assertThat(e.keys).containsExactly("spec-v4.md", "images/$a.png", "MISSING.txt")
            assertThat(String(e.getValue("MISSING.txt"))).contains(b)
        }

    @Test
    fun a_new_bundle_removes_earlier_ones() =
        runBlocking<Unit> {
            ShareBundle.build(tmp.root, 4, md) { byteArrayOf(1) }
            ShareBundle.build(tmp.root, 5, md) { byteArrayOf(1) }
            assertThat(
                tmp.root
                    .listFiles()
                    .orEmpty()
                    .map { it.name },
            ).containsExactly("spec-v5.zip")
        }

    @Test
    fun no_images_no_zip_path_taken() {
        assertThat(ShareBundle.needsBundle("# Spec\n\nno pictures, ![web](https://example.invalid/x.png)")).isFalse()
        assertThat(ShareBundle.needsBundle(md)).isTrue()
    }
}
