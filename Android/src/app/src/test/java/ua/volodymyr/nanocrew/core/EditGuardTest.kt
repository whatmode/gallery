package ua.volodymyr.nanocrew.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditGuardTest {

    private val cpp = "#include \"systemclient.h\"\n#include <QJsonDocument>\n#include <QJsonObject>\n\n" +
        "void SystemClient::load() {\n    auto d = QJsonDocument::fromJson(x);\n}\n"
    private val header = "#pragma once\n\n#include <QObject>\n\nclass Shell : public QObject {\n    Q_OBJECT\npublic:\n    Shell();\n};\n"

    private fun patch(path: String, text: String, instruction: String, vararg blocks: Patching.Block) =
        Patching.apply(text, blocks.toList()) { b -> EditGuard.checkBlock(path, b.find, b.replace, instruction) }

    @Test fun includeIsAddedByTheAppNotSwapped() {
        val s = EditGuard.includeShortcut("native/src/systemclient.cpp", cpp, "change line 18 to include <QQuickItem>")!!
        assertTrue(s.changed)
        assertTrue(s.text.contains("#include <QJsonDocument>"))
        assertTrue(s.text.contains("#include <QJsonObject>\n#include <QQuickItem>\n"))
        assertEquals("@@ line 4\n+ #include <QQuickItem>", s.diff)
    }

    @Test fun headerKeepsItsClass() {
        val s = EditGuard.includeShortcut("native/src/shell.h", header, "change line 1 to include <QQuickItem>")!!
        assertTrue(s.text.contains("#include <QObject>\n#include <QQuickItem>\n"))
        assertTrue(s.text.contains("class Shell : public QObject {"))
    }

    @Test fun includeGoesAfterHeaderGuardWhenThereAreNone() {
        val s = EditGuard.includeShortcut("shell.h", "#ifndef SHELL_H\n#define SHELL_H\n\nclass Shell {};\n#endif\n", "add #include <QQuickItem>")!!
        assertTrue(s.text.startsWith("#ifndef SHELL_H\n#define SHELL_H\n\n#include <QQuickItem>\nclass"))
        assertTrue(!EditGuard.includeShortcut("shell.h", s.text, "add #include <QQuickItem>")!!.changed)
    }

    @Test fun biggerRequestsStillGoToTheEditor() {
        assertNull(EditGuard.includeShortcut("a.cpp", cpp, "include <QQuickItem> and make Shell inherit QQuickItem instead of QObject, update the constructor"))
    }

    @Test fun kotlinImport() {
        val s = EditGuard.includeShortcut("X.kt", "package a.b\n\nclass X\n", "add import kotlinx.coroutines.flow.Flow")!!
        assertEquals("package a.b\n\nimport kotlinx.coroutines.flow.Flow\n\nclass X\n", s.text)
    }

    @Test fun swappingAnIncludeIsRejected() {
        val r = patch("systemclient.cpp", cpp, "change line 18 to include <QQuickItem>",
            Patching.Block("\n#include <QJsonDocument>", "#include <QQuickItem>"))
        assertEquals(0, r.applied)
    }

    @Test fun deletingTheClassIsRejected() {
        val r = patch("shell.h", header, "change line 1 to include <QQuickItem>", Patching.Block("\nclass Shell : public QObject {", ""))
        assertEquals(0, r.applied)
    }

    @Test fun goodChangesStillApply() {
        assertEquals(1, patch("systemclient.cpp", cpp, "include <QQuickItem>",
            Patching.Block("#include <QJsonObject>", "#include <QJsonObject>\n#include <QQuickItem>")).applied)
        assertEquals(1, patch("shell.h", header, "make Shell inherit QQuickItem",
            Patching.Block("class Shell : public QObject {", "class Shell : public QQuickItem {")).applied)
        assertEquals(1, patch("systemclient.cpp", cpp, "remove the unused QJsonObject include",
            Patching.Block("#include <QJsonObject>", "")).applied)
    }

    @Test fun bracketBalance() {
        assertNull(EditGuard.checkFile("shell.h", header, header))
        assertNotNull(EditGuard.checkFile("shell.h", header, header.replace("class Shell : public QObject {\n", "")))
        assertNull(EditGuard.checkFile("a.cpp", "int a(){}\n", "int a(){ auto s = \"{(\"; // }}}\n /* ((( */ }\n"))
        assertNull(EditGuard.checkFile("README.md", "x", "{{{"))
    }

    @Test fun anyPhrasingOfAddIncludeIsSafe() {
        for (p in listOf(
            "change line 18 to include <QQuickItem>",
            "replace line 18 with #include <QQuickItem>",
            "change line 2 to `#include <QQuickItem>`",
            "Replace line 1 with include `<QQuickItem>`",
        )) {
            val s = EditGuard.includeShortcut("x/systemclient.cpp", cpp, p)!!
            assertTrue(p, s.text.contains("<QJsonDocument>") && s.text.contains("<QQuickItem>"))
            assertTrue(p, EditGuard.includeShortcut("x/shell.h", header, p)!!.text.contains("class Shell : public QObject {"))
            assertEquals(p, 0, patch("systemclient.cpp", cpp, p, Patching.Block("\n#include <QJsonDocument>", "#include <QQuickItem>")).applied)
            assertEquals(p, 0, patch("shell.h", header, p, Patching.Block("\nclass Shell : public QObject {", "")).applied)
            assertNotNull(p, EditGuard.checkFile("systemclient.cpp", cpp, cpp.replace("#include <QJsonDocument>", "#include <QQuickItem>"), p))
            assertNotNull(p, EditGuard.checkFile("shell.h", header, header.replace("class Shell : public QObject {\n", ""), p))
        }
    }

    @Test fun namedRemovalsStillWork() {
        val i = "remove QJsonDocument and add #include <QQuickItem>"
        val r = patch("a.cpp", cpp, i, Patching.Block("#include <QJsonDocument>", "#include <QQuickItem>"))
        assertEquals(1, r.applied)
        assertNull(EditGuard.checkFile("a.cpp", cpp, r.text, i))
        assertNotNull(EditGuard.checkFile("a.cpp", cpp, cpp, "add #include <QQuickItem>"))
    }

    @Test fun callArgumentsMustBeKnownByTheScript() {
        val files = mapOf("kit/kwm.py" to "def act(what):\n    if what == \"min\":\n        minimize()\n    elif what == \"close\":\n        close()\n")
        assertNotNull(EditGuard.checkCalls("bindsym Mod4+Down exec python3 /x/kwm.py act min", "bindsym Mod4+Down exec python3 /x/kwm.py act minimize", files))
        assertNull(EditGuard.checkCalls("bindsym Mod4+Down exec python3 /x/kwm.py act min", "bindsym Mod4+Down exec python3 /x/kwm.py act close", files))
    }

    @Test fun jsonMustStayValid() {
        val good = "{\n  \"notes\": [\n    \"one\",\n    \"two\"\n  ]\n}\n"
        assertNotNull(EditGuard.checkFile("www/version.json", good, good.replace("\"one\",", "\"one\""), "fix buttons"))
        assertNull(EditGuard.checkFile("www/version.json", good, good.replace("\"one\"", "\"uno\""), "fix buttons"))
    }

    @Test fun miscopiedFindStillLandsWithoutTheTypos() {
        val file = "x = 1\n\ndef windows_do(d):\n    sys.path.insert(0, \"/usr/local/lib/kalyna\")\n    import kwm\n    a = d.get(\"action\")\n    return a\n"
        val find = "def windowsdo(d):\n    sys.path.insert(0, \"/usr/lib/kalyna\")\n    import kwm\n    a = d.get(\"action\")"
        val repl = "def windowsdo(d):\n    sys.path.insert(0, \"/usr/lib/kalyna\")\n    import kwm\n    a = d.get(\"action\")\n    log(a)"
        val r = Patching.apply(file, listOf(Patching.Block(find, repl)))
        assertEquals(1, r.applied)
        assertTrue(r.text.contains("def windows_do(d):") && r.text.contains("/usr/local/lib/kalyna") && r.text.contains("    log(a)"))
        // A short miscopy is not enough to be sure where it goes.
        assertEquals(0, Patching.apply(file, listOf(Patching.Block("def windowsdo(d):", "def windowsdo(x):"))).applied)
    }
}
