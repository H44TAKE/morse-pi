package br.pi.morse

import java.text.Normalizer

object Morse {
    private val TABLE = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.",
        'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..",
        'M' to "--", 'N' to "-.", 'O' to "---", 'P' to ".--.", 'Q' to "--.-", 'R' to ".-.",
        'S' to "...", 'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-",
        'Y' to "-.--", 'Z' to "--..",
        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
        '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----.",
        '.' to ".-.-.-", ',' to "--..--", '?' to "..--..", '!' to "-.-.--", '/' to "-..-.",
        '-' to "-....-", '(' to "-.--.", ')' to "-.--.-", ':' to "---...", '@' to ".--.-.",
    )

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }.uppercase()

    fun textToMorse(text: String): String =
        normalize(text).split(Regex("\\s+")).mapNotNull { w ->
            val letters = w.mapNotNull { TABLE[it] }
            if (letters.isEmpty()) null else letters.joinToString(" ")
        }.joinToString(" / ")

    private val REVERSE = TABLE.entries.associate { it.value to it.key }
    private val SPOKEN = mapOf(
        '.' to "ponto", ',' to "vírgula", '?' to "interrogação", '!' to "exclamação",
        '/' to "barra", '-' to "hífen", '(' to "abre parênteses", ')' to "fecha parênteses",
        ':' to "dois pontos", '@' to "arroba",
    )

    fun isMorse(s: String) = s.isNotBlank() && Regex("[.\\-/ ]+").matches(s.trim())

    fun decodeLetter(code: String): String = REVERSE[code]?.toString() ?: "?"

    fun fromMorse(code: String): String =
        code.trim().split(Regex("\\s*/\\s*")).joinToString(" ") { w ->
            w.split(" ").filter { it.isNotEmpty() }.joinToString("") { decodeLetter(it) }
        }

    /** Um pedaço do Morse. `spoken` vem preenchido só no 1º sinal de cada letra: o que falar nesse instante. */
    data class Step(val on: Boolean, val ms: Int, val spoken: String?)

    /** ponto=1u, traço=3u, entre símbolos=1u, entre letras=3u, entre palavras=7u. */
    fun steps(morse: String, unit: Int): List<Step> {
        val out = ArrayList<Step>()
        val words = morse.split(" / ")
        words.forEachIndexed { wi, word ->
            val letters = word.trim().split(" ").filter { it.isNotEmpty() }
            letters.forEachIndexed { li, letter ->
                val spoken = REVERSE[letter]?.let { SPOKEN[it] ?: it.toString() }
                letter.forEachIndexed { si, sym ->
                    out.add(Step(true, unit * (if (sym == '-') 3 else 1), if (si == 0) spoken else null))
                    if (si < letter.length - 1) out.add(Step(false, unit, null))
                }
                if (li < letters.size - 1) out.add(Step(false, unit * 3, null))
            }
            if (wi < words.size - 1) out.add(Step(false, unit * 7, null))
        }
        return out
    }

    /** (ligado, duração em ms). */
    fun timeline(morse: String, unit: Int): List<Pair<Boolean, Int>> =
        steps(morse, unit).map { it.on to it.ms }
}
