package io.github.derlocke_ng.kiwi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class AgeKeyTest {
    @Test
    fun aNewKeyLooksLikeAges() {
        val key = AgeKey.new()
        assertTrue(Regex("age1[0-9a-z]{58}").matches(key.public))
        assertTrue(key.secret.startsWith("AGE-SECRET-KEY-1"))
        assertEquals(key.public, AgeKey.of(key.secret).public)
    }

    @Test
    fun refusesWhatIsNoKey() {
        assertEquals(Problem.WrongKey, assertThrows(KiwiException::class.java) { AgeKey.of("nonsense") }.problem)
    }

    @Test
    fun anotherKeyDoesNotOpenIt() {
        FakeControlNode().use { node ->
            // a challenge for one key, tried with another
            val mine = AgeKey.new()
            node.controllers["phone"] = mine.public
            val blob = java.util.Base64.getDecoder().decode(
                ControlApi(node.url, node.ca.pem).call("POST", "/api/controller/hello", jsonOf("name" to "phone")).text("challenge"),
            )
            assertTrue(mine.open(blob).decodeToString().startsWith("token-"))
            assertEquals(Problem.WrongKey, assertThrows(KiwiException::class.java) { AgeKey.new().open(blob) }.problem)
        }
    }

    /** Against the real tool, as the control node uses it (`age -e -r <public>`). Skipped where age is not installed. */
    @Test
    fun opensWhatAgeEncrypts() {
        assumeTrue(runs("age", "--version"))
        val key = AgeKey.new()
        val age = ProcessBuilder("age", "-e", "-r", key.public).redirectErrorStream(false).start()
        age.outputStream.use { it.write("a token\n".toByteArray()) }
        val blob = age.inputStream.readBytes()
        assertTrue(age.waitFor(20, TimeUnit.SECONDS) && age.exitValue() == 0)
        assertEquals("a token", key.open(blob).decodeToString().trim())
    }

    /** age-keygen derives the same public key from our secret. */
    @Test
    fun ageAgreesOnThePublicKey() {
        assumeTrue(runs("age-keygen", "--version"))
        val key = AgeKey.new()
        val file = File.createTempFile("kiwi-test", ".key")
        try {
            file.writeText(key.secret + "\n")
            val p = ProcessBuilder("age-keygen", "-y", file.path).start()
            val out = p.inputStream.readBytes().decodeToString().trim()
            assertTrue(p.waitFor(20, TimeUnit.SECONDS))
            assertEquals(key.public, out)
        } finally {
            file.delete()
        }
    }

    private fun runs(vararg command: String): Boolean = try {
        ProcessBuilder(*command).redirectErrorStream(true).start().let { p ->
            p.inputStream.readBytes()
            p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0
        }
    } catch (e: Exception) {
        false
    }
}
