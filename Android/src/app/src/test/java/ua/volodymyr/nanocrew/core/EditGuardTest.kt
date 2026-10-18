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
}
