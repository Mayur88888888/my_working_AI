package io.github.aeewws.codexmobile

import io.github.aeewws.codexmobile.workspace.WorkspaceActionParser
import io.github.aeewws.codexmobile.workspace.WorkspaceFileManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun sanitizeRelativePath_normalizesAndRejectsTraversal() {
        assertEquals("src/main.py", WorkspaceFileManager.sanitizeRelativePath("/src\\main.py"))
        assertEquals("notes.txt", WorkspaceFileManager.sanitizeRelativePath("./notes.txt"))
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceFileManager.sanitizeRelativePath("../secret.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceFileManager.sanitizeRelativePath("   ")
        }
    }

    @Test
    fun mimeTypeForCreate_avoidsDoubleExtensionsForCodeFiles() {
        assertEquals("text/plain", WorkspaceFileManager.mimeTypeForCreate("notes.txt"))
        assertEquals("application/json", WorkspaceFileManager.mimeTypeForCreate("config.json"))
        assertEquals("application/octet-stream", WorkspaceFileManager.mimeTypeForCreate("Main.kt"))
        assertEquals("application/octet-stream", WorkspaceFileManager.mimeTypeForCreate("script.py"))
    }

    @Test
    fun workspaceActionParser_parsesXmlAndFencedWriteDirectives() {
        val output = """
            Here are the files:
            <write_file path="src/hello.py">
            print("hello offline")
            </write_file>

            ```write_file:README.md
            # Offline Project
            ```

            ```kotlin
            // path: app/Main.kt
            fun main() = println("ok")
            ```

            <read_file path="existing/config.json" />
        """.trimIndent()

        val parsed = WorkspaceActionParser.parseDirectives(output)
        assertEquals(3, parsed.writeDirectives.size)
        assertEquals("src/hello.py", parsed.writeDirectives[0].relativePath)
        assertEquals("""print("hello offline")""", parsed.writeDirectives[0].content)
        assertEquals("README.md", parsed.writeDirectives[1].relativePath)
        assertEquals("# Offline Project", parsed.writeDirectives[1].content)
        assertEquals("app/Main.kt", parsed.writeDirectives[2].relativePath)
        assertTrue(parsed.writeDirectives[2].content.startsWith("fun main()"))

        assertEquals(1, parsed.readDirectives.size)
        assertEquals("existing/config.json", parsed.readDirectives[0].relativePath)
    }

    @Test
    fun workspaceActionParser_extractsCodeSnippetsForManualSave() {
        val output = """
            ```python
            def add(a, b):
                return a + b
            ```
        """.trimIndent()

        val snippets = WorkspaceActionParser.extractCodeSnippets(output)
        assertEquals(1, snippets.size)
        assertEquals("python", snippets[0].language)
        assertEquals(null, snippets[0].suggestedPath)
        assertTrue(snippets[0].content.contains("return a + b"))
    }
}
