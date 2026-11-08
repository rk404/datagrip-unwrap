package com.rafalkaminski.datagrip.unwrap.unwrap

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Odwija kod PL/SQL zawinięty narzędziem `wrap` / `DBMS_DDL.WRAP` (Oracle 10g i nowsze).
 *
 * Format jednostki:
 * ```
 * CREATE OR REPLACE FUNCTION nazwa wrapped
 * a000000
 * b2                      <- wersja
 * abcd  (x15)
 * 8                       <- typ obiektu
 * 71 ae                   <- długość tekstu / długość bloku base64 (hex)
 * <base64 ...>
 * ```
 * Dekodowanie: base64 -> odcięcie 20 bajtów (SHA-1) -> podstawienie bajtów tablicą [CHARMAP] -> zlib.
 * Zdekodowany tekst nie zawiera prefiksu `CREATE OR REPLACE`, dlatego jest dopisywany z nagłówka.
 *
 * Czysty Kotlin, bez zależności od platformy IntelliJ.
 */
object PlsqlUnwrapper {

    class UnwrapException(message: String, cause: Throwable? = null) : Exception(message, cause)

    data class Result(
        /** Tekst wejściowy, w którym każda zawinięta jednostka została zastąpiona odwiniętą. */
        val text: String,
        /** Liczba odwiniętych jednostek. */
        val unitsUnwrapped: Int,
    )

    private val HEADER = Regex("""^(.*\S)\s+wrapped\s*$""", RegexOption.IGNORE_CASE)
    private val HEX = Regex("""^[0-9a-fA-F]{1,8}$""")
    private val HEX_PAIR = Regex("""^[0-9a-fA-F]{1,8}\s+[0-9a-fA-F]{1,8}$""")
    private val BASE64_LINE = Regex("""^[A-Za-z0-9+/]+={0,2}$""")
    private const val MAGIC = "a000000"
    private const val SHA1_LENGTH = 20

    /** Szybka, tania kontrola, czy tekst wygląda na zawierający zawiniętą jednostkę. */
    fun containsWrapped(text: String): Boolean =
        text.contains("wrapped", ignoreCase = true) && text.contains(MAGIC)

    /**
     * Odwija wszystkie zawinięte jednostki w [text]. Fragmenty, które nie są zawinięte, zostają bez zmian.
     *
     * @throws UnwrapException gdy znaleziona jednostka jest uszkodzona albo ma nieobsługiwany format.
     */
    fun unwrap(text: String): Result {
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.split("\r\n", "\n")
        val out = ArrayList<String>(lines.size)
        var count = 0
        var i = 0
        while (i < lines.size) {
            val unit = parseUnit(lines, i)
            if (unit == null) {
                out.add(lines[i])
                i++
                continue
            }
            val decoded = try {
                decode(unit.base64)
            } catch (e: UnwrapException) {
                throw UnwrapException("Jednostka w linii ${i + 1} (${unit.headerText.trim()}): ${e.message}", e)
            }
            out.add(withHeaderPrefix(unit.headerText, decoded))
            count++
            i = unit.nextLine
        }
        val joined = out.joinToString("\n")
        return Result(if (newline == "\n") joined else joined.replace("\n", newline), count)
    }

    private class WrappedUnit(
        /** Nagłówek bez końcowego `wrapped`, np. `CREATE OR REPLACE FUNCTION get_date_string`. */
        val headerText: String,
        val base64: String,
        /** Indeks pierwszej linii po jednostce. */
        val nextLine: Int,
    )

    /**
     * Zdekodowany tekst zaczyna się od słowa kluczowego typu obiektu (np. `FUNCTION nazwa ...`),
     * bez `CREATE OR REPLACE [EDITIONABLE]`. Dopisujemy ten początek z nagłówka.
     */
    private fun withHeaderPrefix(headerText: String, decoded: String): String {
        val firstWord = decoded.trimStart().takeWhile { !it.isWhitespace() }
        if (firstWord.isEmpty()) return decoded
        val match = Regex("""\b${Regex.escape(firstWord)}\b""", RegexOption.IGNORE_CASE).find(headerText)
        return if (match != null) headerText.substring(0, match.range.first) + decoded else decoded
    }

    /** Zwraca jednostkę zaczynającą się w linii [start] albo null, gdy to nie jest zawinięta jednostka. */
    private fun parseUnit(lines: List<String>, start: Int): WrappedUnit? {
        val header = HEADER.matchEntire(lines[start]) ?: return null
        var j = start + 1
        fun skipBlank() {
            while (j < lines.size && lines[j].isBlank()) j++
        }

        skipBlank()
        // "... wrapped" bez "a000000" to nie jest format 10g+ (np. 9i) albo zwykły tekst: zostawiamy bez zmian.
        if (j >= lines.size || lines[j].trim() != MAGIC) return null
        j++

        // Od tego miejsca to na pewno zawinięta jednostka, więc zły układ jest błędem, a nie pomijaniem.
        fun malformed(what: String): Nothing =
            throw UnwrapException("Jednostka w linii ${start + 1} (${header.groupValues[1].trim()}): $what")

        if (j >= lines.size || !HEX.matches(lines[j].trim())) malformed("brak wersji formatu po $MAGIC")
        j++
        var abcd = 0
        while (j < lines.size && lines[j].trim() == "abcd") {
            abcd++
            j++
        }
        if (abcd == 0) malformed("brak linii 'abcd' w nagłówku")
        if (j >= lines.size || !HEX.matches(lines[j].trim())) malformed("brak typu obiektu w nagłówku")
        j++
        // Długości: albo "71 ae" w jednej linii, albo (DBMS_DDL.WRAP) w dwóch osobnych.
        if (j < lines.size && HEX_PAIR.matches(lines[j].trim())) {
            j++
        } else if (j + 1 < lines.size && HEX.matches(lines[j].trim()) && HEX.matches(lines[j + 1].trim())) {
            j += 2
        } else {
            malformed("brak długości w nagłówku")
        }
        val b64 = StringBuilder()
        while (j < lines.size) {
            val line = lines[j].trim()
            // Pusta linia lub samotny "/" (terminator SQL*Plus) kończy dane.
            if (line.isEmpty() || line == "/" || !BASE64_LINE.matches(line)) break
            b64.append(line)
            j++
        }
        if (b64.isEmpty()) malformed("brak danych base64 (uszkodzony nagłówek lub dane)")
        return WrappedUnit(header.groupValues[1], b64.toString(), j)
    }

    /** Dekoduje blok base64 do tekstu. Tekst nie ma prefiksu `CREATE OR REPLACE`. */
    internal fun decode(base64: String): String {
        val raw = try {
            Base64.getDecoder().decode(base64)
        } catch (e: IllegalArgumentException) {
            throw UnwrapException("niepoprawny base64 (uszkodzone dane)", e)
        }
        if (raw.size <= SHA1_LENGTH) throw UnwrapException("zbyt krótkie dane")
        val body = ByteArray(raw.size - SHA1_LENGTH) { CHARMAP[raw[SHA1_LENGTH + it].toInt() and 0xff].toByte() }
        val inflated = inflate(body)
        // Oracle dopisuje na końcu bajt NUL.
        val end = if (inflated.isNotEmpty() && inflated.last() == 0.toByte()) inflated.size - 1 else inflated.size
        return String(inflated, 0, end, Charsets.UTF_8)
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 3)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(buffer)
                } catch (e: DataFormatException) {
                    throw UnwrapException("błąd dekompresji (nieobsługiwany format albo uszkodzone dane)", e)
                }
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw UnwrapException("dane są obcięte albo niekompletne")
                }
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    // Tablica podstawień bajtów dla Oracle 10g+ (permutacja 0..255).
    // Źródło: pakiet plsqlunwrap 0.0.2, MIT License, Copyright 2023 Lucas Rodrigues (patrz THIRD-PARTY-NOTICES.md).
    // fmt: off
    internal val CHARMAP: IntArray = intArrayOf(
        0x3d, 0x65, 0x85, 0xb3, 0x18, 0xdb, 0xe2, 0x87,
        0xf1, 0x52, 0xab, 0x63, 0x4b, 0xb5, 0xa0, 0x5f,
        0x7d, 0x68, 0x7b, 0x9b, 0x24, 0xc2, 0x28, 0x67,
        0x8a, 0xde, 0xa4, 0x26, 0x1e, 0x03, 0xeb, 0x17,
        0x6f, 0x34, 0x3e, 0x7a, 0x3f, 0xd2, 0xa9, 0x6a,
        0x0f, 0xe9, 0x35, 0x56, 0x1f, 0xb1, 0x4d, 0x10,
        0x78, 0xd9, 0x75, 0xf6, 0xbc, 0x41, 0x04, 0x81,
        0x61, 0x06, 0xf9, 0xad, 0xd6, 0xd5, 0x29, 0x7e,
        0x86, 0x9e, 0x79, 0xe5, 0x05, 0xba, 0x84, 0xcc,
        0x6e, 0x27, 0x8e, 0xb0, 0x5d, 0xa8, 0xf3, 0x9f,
        0xd0, 0xa2, 0x71, 0xb8, 0x58, 0xdd, 0x2c, 0x38,
        0x99, 0x4c, 0x48, 0x07, 0x55, 0xe4, 0x53, 0x8c,
        0x46, 0xb6, 0x2d, 0xa5, 0xaf, 0x32, 0x22, 0x40,
        0xdc, 0x50, 0xc3, 0xa1, 0x25, 0x8b, 0x9c, 0x16,
        0x60, 0x5c, 0xcf, 0xfd, 0x0c, 0x98, 0x1c, 0xd4,
        0x37, 0x6d, 0x3c, 0x3a, 0x30, 0xe8, 0x6c, 0x31,
        0x47, 0xf5, 0x33, 0xda, 0x43, 0xc8, 0xe3, 0x5e,
        0x19, 0x94, 0xec, 0xe6, 0xa3, 0x95, 0x14, 0xe0,
        0x9d, 0x64, 0xfa, 0x59, 0x15, 0xc5, 0x2f, 0xca,
        0xbb, 0x0b, 0xdf, 0xf2, 0x97, 0xbf, 0x0a, 0x76,
        0xb4, 0x49, 0x44, 0x5a, 0x1d, 0xf0, 0x00, 0x96,
        0x21, 0x80, 0x7f, 0x1a, 0x82, 0x39, 0x4f, 0xc1,
        0xa7, 0xd7, 0x0d, 0xd1, 0xd8, 0xff, 0x13, 0x93,
        0x70, 0xee, 0x5b, 0xef, 0xbe, 0x09, 0xb9, 0x77,
        0x72, 0xe7, 0xb2, 0x54, 0xb7, 0x2a, 0xc7, 0x73,
        0x90, 0x66, 0x20, 0x0e, 0x51, 0xed, 0xf8, 0x7c,
        0x8f, 0x2e, 0xf4, 0x12, 0xc6, 0x2b, 0x83, 0xcd,
        0xac, 0xcb, 0x3b, 0xc4, 0x4e, 0xc0, 0x69, 0x36,
        0x62, 0x02, 0xae, 0x88, 0xfc, 0xaa, 0x42, 0x08,
        0xa6, 0x45, 0x57, 0xd3, 0x9a, 0xbd, 0xe1, 0x23,
        0x8d, 0x92, 0x4a, 0x11, 0x89, 0x74, 0x6b, 0x91,
        0xfb, 0xfe, 0xc9, 0x01, 0xea, 0x1b, 0xf7, 0xce,
    )
    // fmt: on
}
