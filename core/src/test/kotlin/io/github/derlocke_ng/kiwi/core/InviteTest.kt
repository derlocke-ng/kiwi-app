package io.github.derlocke_ng.kiwi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteTest {
    @Test
    fun parsesALink() {
        val invite = Invite.parse("  https://control.box.example.org/#join=abcdefgh.0a1b2c3d \n")
        assertEquals("https://control.box.example.org", invite.url)
        assertEquals("abcdefgh.0a1b2c3d", invite.code)
        assertEquals("0a1b2c3d", invite.caMark)
    }

    @Test
    fun refusesWhatIsNoInvite() {
        for (text in listOf(
            "", "hello", "https://control.box.example.org/", "https://control.box.example.org/#join=nodot",
            "http://control.box.example.org/#join=abc.def", "https:///#join=abc.def", "ftp://x/#join=abc.def",
            "https://control.box.example.org/#join=abc.def and more",
        )) {
            val e = assertThrows(text, KiwiException::class.java) { Invite.parse(text) }
            assertEquals(Problem.NotAnInvite, e.problem)
        }
    }

    @Test
    fun knowsItsFleetByTheCa() {
        val ca = "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n"
        val mark = sha256Hex(ca.toByteArray()).take(8)
        assertTrue(Invite("https://x.example", "code.$mark").isFrom(ca))
        assertFalse(Invite("https://x.example", "code.$mark").isFrom(ca + "\n"))
    }

    @Test
    fun namesAreTheControlNodes() {
        assertTrue(isControllerName("phone"))
        assertTrue(isControllerName("pixel-8a"))
        assertTrue(isControllerName("a".repeat(28)))
        assertFalse(isControllerName(""))
        assertFalse(isControllerName("-phone"))
        assertFalse(isControllerName("Phone"))
        assertFalse(isControllerName("a".repeat(29)))
    }

    @Test
    fun makesANameFromAModel() {
        assertEquals("pixel-8a", controllerNameFrom("Pixel 8a"))
        assertEquals("sm-g991b", controllerNameFrom("SM-G991B"))
        assertEquals("phone", controllerNameFrom("  "))
        assertEquals("phone", controllerNameFrom("电话"))
        assertTrue(isControllerName(controllerNameFrom("A very long model name that goes on and on")))
        assertTrue(isControllerName(controllerNameFrom("abcdefghijklmnopqrstuvwxyz - x")))
    }
}
