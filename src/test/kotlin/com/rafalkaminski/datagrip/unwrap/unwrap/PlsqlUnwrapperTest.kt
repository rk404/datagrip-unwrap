package com.rafalkaminski.datagrip.unwrap.unwrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Deflater

class PlsqlUnwrapperTest {

    // Próbki wygenerowane przez Oracle 10.2 (https://oracle-base.com/articles/10g/wrap-and-dbms_ddl-10gr2.php).
    private val wrapUtilitySample = """
CREATE OR REPLACE FUNCTION get_date_string wrapped
a000000
b2
abcd
abcd
abcd
abcd
abcd
abcd
abcd
abcd
abcd
abcd
abcd
abcd
abcd
abcd
abcd
8
71 ae
P29RDhRZX0orO0ED/mMF8i12Glkwg8eZgcfLCNL+XlquYvSuoVah8JbRPpdHDOrnwLK9spte
58d0wDO4dGUJuHSLwMAy/tKGCamhAs7G1hohrO/WTHaEcTKOd0xx9RBzc/XvN2dM6+zZPXLp
r1UqFBwU/Sx2010pwUjXpqZCvywG

/
""".trimStart('\n')

    private val originalFunction = """
FUNCTION get_date_string RETURN VARCHAR2 AS
BEGIN
  RETURN TO_CHAR(SYSDATE, 'DD-MON-YYYY');
END get_date_string;
""".trim('\n')

    @Test
    fun charmapIsPermutationOfAllBytes() {
        assertEquals(256, PlsqlUnwrapper.CHARMAP.size)
        assertEquals((0..255).toSet(), PlsqlUnwrapper.CHARMAP.toSet())
    }

    @Test
    fun unwrapsRealSampleFromWrapUtility() {
        val result = PlsqlUnwrapper.unwrap(wrapUtilitySample)
        assertEquals(1, result.unitsUnwrapped)
        // Oracle zapisuje etykietę końcową wielkimi literami ("END GET_DATE_STRING;"), reszta zgadza się z oryginałem.
        val expected = originalFunction.replace("END get_date_string;", "END GET_DATE_STRING;")
        assertEquals(norm("CREATE OR REPLACE $expected\n\n/\n"), norm(result.text))
    }

    @Test
    fun unwrapsDbmsDdlLayoutWithSeparateLengthLines() {
        val text = """
CREATE OR REPLACE FUNCTION get_date_string wrapped

a000000
b2
abcd
abcd
abcd
abcd
abcd
abcd
abcd
abcd
abcd
abcd
abcd
abcd
abcd
abcd
abcd
8
6f
aa
mV4eMSJ8EqqgErJT91l6UZ0pdDUwgyr6LZ5GfHSmUPiJfkEObQpeDb6D7glajI+ONulxdqC1
0HvOPP4eJpQs5zxsKXpj6XL1
fvieXyWCr3BTzXTqcGYhfXrtqDVPztR/o+9UZ8l5OijDSsRW
ZPv6rISzFyqeEsCBweFUFyxd

""".trimStart('\n')
        val result = PlsqlUnwrapper.unwrap(text)
        assertEquals(1, result.unitsUnwrapped)
        assertEquals(
            "CREATE OR REPLACE FUNCTION get_date_string RETURN VARCHAR2 AS BEGIN RETURN TO_CHAR(SYSDATE, 'DD-MON-YYYY'); END GET_DATE_STRING;",
            result.text.trim(),
        )
    }

    @Test
    fun leavesPlainTextUntouched() {
        val sql = "SELECT 1 FROM dual;\n-- wrapped in a comment\n"
        val result = PlsqlUnwrapper.unwrap(sql)
        assertEquals(0, result.unitsUnwrapped)
        assertEquals(sql, result.text)
        assertFalse(PlsqlUnwrapper.containsWrapped(sql))
    }

    @Test
    fun unwrapsOnlyWrappedUnitsInMixedFile() {
        val text = "PROMPT start\n$wrapUtilitySample\nPROMPT end\n$wrapUtilitySample"
        val result = PlsqlUnwrapper.unwrap(text)
        assertEquals(2, result.unitsUnwrapped)
        assertTrue(result.text.startsWith("PROMPT start\nCREATE OR REPLACE FUNCTION"))
        assertTrue(result.text.contains("PROMPT end\nCREATE OR REPLACE FUNCTION"))
        assertFalse(result.text.contains("abcd"))
    }

    @Test
    fun preservesCrlfLineEndings() {
        val result = PlsqlUnwrapper.unwrap(wrapUtilitySample.replace("\n", "\r\n"))
        assertTrue(result.text.contains("\r\n"))
        assertFalse(Regex("(?<!\r)\n").containsMatchIn(result.text))
    }

    @Test
    fun roundTripKeepsEditionablePackageBodyPrefix() {
        val body = "PACKAGE BODY pkg AS\n  PROCEDURE p IS BEGIN NULL; END;\nEND pkg;"
        val text = "CREATE OR REPLACE EDITIONABLE PACKAGE BODY pkg wrapped\n" + encodeUnit(body, objectType = "b") + "\n/\n"
        val result = PlsqlUnwrapper.unwrap(text)
        assertEquals("CREATE OR REPLACE EDITIONABLE $body\n/\n", result.text)
    }

    @Test
    fun corruptBase64IsReportedAsUnwrapException() {
        val broken = wrapUtilitySample.replace("P29RDhRZ", "P29R!hRZ").replace("!", "=")
        try {
            PlsqlUnwrapper.unwrap(broken)
            fail("expected UnwrapException")
        } catch (e: PlsqlUnwrapper.UnwrapException) {
            assertTrue(e.message!!.contains("get_date_string"))
        }
    }

    @Test
    fun corruptCompressedDataIsReportedAsUnwrapException() {
        val text = "CREATE OR REPLACE FUNCTION f wrapped\n" + encodeUnit("FUNCTION f RETURN NUMBER AS BEGIN RETURN 1; END f;", "8", corrupt = true) + "\n/\n"
        try {
            PlsqlUnwrapper.unwrap(text)
            fail("expected UnwrapException")
        } catch (e: PlsqlUnwrapper.UnwrapException) {
            // ok
        }
    }

    private fun norm(s: String) = s.replace("\r\n", "\n").lines().joinToString("\n") { it.trimEnd() }.trim()

    /** Odwrotność dekodera, wyłącznie na potrzeby testów: zlib -> odwrócone podstawienie -> SHA-1 + base64. */
    private fun encodeUnit(source: String, objectType: String, corrupt: Boolean = false): String {
        val inverse = IntArray(256).also { inv -> PlsqlUnwrapper.CHARMAP.forEachIndexed { i, v -> inv[v] = i } }
        val plain = (source + "\u0000").toByteArray(Charsets.UTF_8)
        val deflater = Deflater()
        deflater.setInput(plain)
        deflater.finish()
        val compressed = ByteArrayOutputStream().also { out ->
            val buf = ByteArray(4096)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        }.toByteArray()
        deflater.end()
        if (corrupt) compressed[2] = (compressed[2].toInt() xor 0x5a).toByte()
        val substituted = ByteArray(compressed.size) { inverse[compressed[it].toInt() and 0xff].toByte() }
        val sha = MessageDigest.getInstance("SHA-1").digest(substituted)
        val b64 = Base64.getEncoder().encodeToString(sha + substituted).chunked(72).joinToString("\n")
        val header = listOf("a000000", "b2") + List(15) { "abcd" } + listOf(objectType, "${"%x".format(plain.size)} ${"%x".format(b64.length)}")
        return (header + b64).joinToString("\n")
    }
}
