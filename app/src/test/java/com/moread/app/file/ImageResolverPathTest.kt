package com.moread.app.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** SPEC §2.3：本地图片路径遍历（`..` 越级）必须被拒绝。 */
class ImageResolverPathTest {

    

    @Test fun `relative child image resolves for file documents`() {
        val dir = Files.createTempDirectory("moread-image-test").toFile()
        val doc = File(dir, "doc.md").apply { writeText("# hi") }
        val image = File(dir, "assets/a.png").apply { parentFile.mkdirs(); writeText("x") }

        val uri = ImageResolver.resolveFilePath(doc.absolutePath, "assets/a.png")
        assertEquals(image.canonicalPath, uri)
    }

    @Test fun `parent traversal is rejected`() {
        val dir = Files.createTempDirectory("moread-image-test").toFile()
        val doc = File(dir, "doc.md").apply { writeText("# hi") }
        val secret = File(dir.parentFile, "secret.txt").apply { writeText("secret") }

        assertNull(ImageResolver.resolveFilePath(doc.absolutePath, "../secret.txt"))
        assertNull(ImageResolver.resolveFilePath(doc.absolutePath, "sub/../../secret.txt"))
        assertNull(ImageResolver.resolveFilePath(doc.absolutePath, "http://example.com/a.png"))
        assertNull(ImageResolver.resolveFilePath(doc.absolutePath, "/absolute/path.png"))
    }
}
