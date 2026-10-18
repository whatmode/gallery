package ua.volodymyr.nanocrew.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossRefTest {

    private val kwm = """
        |def act_focused(what):
        |    n = focused()
        |    if what == "min":
        |        minimize(n)
        |    elif what == "close":
        |        close(n)
        |
        |def minimize(wid=None):
        |    home()
        |
        |def close(wid):
        |    msg(f"[con_id={wid}] kill")
        |
        |if __name__ == "__main__":
        |    import sys
        |    if len(sys.argv) == 3 and sys.argv[1] == "act":
        |        act_focused(sys.argv[2])
    """.trimMargin()
    private val files = mapOf("kit/lib/kwm.py" to kwm, "kit/bridge.py" to "def windows_do(d):\n    fn = {\"minimize\": kwm.minimize}.get(a)\n")

    @Test fun findsDefinitionsUsedFromAnotherFile() {
        val ctx = CrossRef.context("    fn = {\"minimize\": kwm.minimize, \"close\": kwm.close}.get(a)", "kit/bridge.py", files, 3_000)
        assertTrue(ctx, ctx.contains("def minimize(wid=None):\n    home()"))
        assertTrue(ctx, ctx.contains("def close(wid):"))
    }

    @Test fun findsTheHandlerOfAScriptsArguments() {
        val ctx = CrossRef.context("bindsym Mod4+Down exec python3 /usr/lib/kwm.py act min", "kit/sway.conf", files, 3_000)
        assertTrue(ctx, ctx.contains("def act_focused(what):"))
        assertTrue(ctx, ctx.contains("act_focused(sys.argv[2])"))
    }

    @Test fun lookupIgnoresCalls() {
        val l = CrossRef.lookup("kwm.minimize", files, 1_000)
        assertTrue(l, l.contains("def minimize"))
        assertFalse(l, l.contains("line 4 ---"))
        assertTrue(CrossRef.lookup("nothing_here", files, 500).contains("not defined"))
    }

    @Test fun thinkingIsSplitOff() {
        assertEquals("OK" to "OK", Thinking.split("OK").let { (it.first ?: "OK") to it.second })
        val (t, a) = Thinking.split("<|channel>thought\nlet me check the comma<channel|>PROBLEM: missing comma")
        assertEquals("let me check the comma", t)
        assertEquals("PROBLEM: missing comma", a)
        assertNull(Thinking.split("NONE").first)
    }
}
