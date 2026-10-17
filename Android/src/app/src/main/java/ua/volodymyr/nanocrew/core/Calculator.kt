package ua.volodymyr.nanocrew.core

import kotlin.math.PI
import kotlin.math.E
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Small exact-ish calculator the agents can call, because a 3B-class model
 * is unreliable at arithmetic. Supports + - * / % ^, parentheses, unary minus,
 * percent literals like "15%", constants pi/e and common functions.
 */
object Calculator {

    fun evaluate(expression: String): Double {
        val cleaned = expression
            .replace("×", "*").replace("÷", "/").replace("−", "-")
            .replace(",", "")          // thousands separators
            .replace("**", "^")
            .trim()
            .trimEnd('=')
        require(cleaned.isNotBlank()) { "empty expression" }
        val p = Parser(cleaned)
        val v = p.parseExpression()
        p.skipSpaces()
        require(p.done()) { "unexpected '${p.rest()}'" }
        require(!v.isNaN() && !v.isInfinite()) { "result is not a finite number" }
        return v
    }

    /** Human-friendly formatting: integers without ".0", otherwise up to 10 significant decimals. */
    fun format(v: Double): String {
        if (v == floor(v) && abs(v) < 1e15) return v.toLong().toString()
        val r = round(v * 1e10) / 1e10
        return r.toBigDecimal().stripTrailingZeros().toPlainString()
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun done() = i >= s.length
        fun rest() = s.substring(i)
        fun skipSpaces() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun peek(): Char? { skipSpaces(); return if (i < s.length) s[i] else null }

        private fun eat(c: Char): Boolean {
            if (peek() == c) { i++; return true }
            return false
        }

        fun parseExpression(): Double {
            var v = parseTerm()
            while (true) {
                v = when {
                    eat('+') -> v + parseTerm()
                    eat('-') -> v - parseTerm()
                    else -> return v
                }
            }
        }

        private fun parseTerm(): Double {
            var v = parseFactor()
            while (true) {
                v = when {
                    eat('*') -> v * parseFactor()
                    eat('/') -> {
                        val d = parseFactor()
                        require(d != 0.0) { "division by zero" }
                        v / d
                    }
                    peek() == '%' && isModulo() -> { i++; v % parseFactor() }
                    else -> return v
                }
            }
        }

        // "10 % 3" is modulo; "15%" at the end or before an operator is a percent literal.
        private fun isModulo(): Boolean {
            var j = i + 1
            while (j < s.length && s[j].isWhitespace()) j++
            return j < s.length && (s[j].isDigit() || s[j] == '(' || s[j] == '.' || s[j].isLetter())
        }

        private fun parseFactor(): Double {
            val base = parseUnary()
            return if (eat('^')) base.pow(parseFactor()) else base // right-associative
        }

        private fun parseUnary(): Double = when {
            eat('-') -> -parseUnary()
            eat('+') -> parseUnary()
            else -> parsePostfix(parsePrimary())
        }

        private fun parsePostfix(v: Double): Double {
            if (peek() == '%' && !isModulo()) { i++; return v / 100.0 }
            return v
        }

        private fun parsePrimary(): Double {
            val c = peek() ?: throw IllegalArgumentException("expression ended early")
            if (eat('(')) {
                val v = parseExpression()
                require(eat(')')) { "missing ')'" }
                return v
            }
            if (c.isDigit() || c == '.') return parseNumber()
            if (c.isLetter()) return parseName()
            throw IllegalArgumentException("unexpected '$c'")
        }

        private fun parseNumber(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (i < s.length && (s[i] == 'e' || s[i] == 'E') && i + 1 < s.length &&
                (s[i + 1].isDigit() || ((s[i + 1] == '-' || s[i + 1] == '+') && i + 2 < s.length && s[i + 2].isDigit()))
            ) {
                i += 2
                while (i < s.length && s[i].isDigit()) i++
            }
            return s.substring(start, i).toDoubleOrNull()
                ?: throw IllegalArgumentException("bad number '${s.substring(start, i)}'")
        }

        private fun parseName(): Double {
            val start = i
            while (i < s.length && s[i].isLetterOrDigit()) i++
            val name = s.substring(start, i).lowercase()
            when (name) {
                "pi" -> return PI
                "e" -> return E
            }
            require(eat('(')) { "unknown name '$name'" }
            val arg = parseExpression()
            require(eat(')')) { "missing ')' after $name(" }
            return when (name) {
                "sqrt" -> { require(arg >= 0) { "sqrt of negative" }; sqrt(arg) }
                "abs" -> abs(arg)
                "sin" -> sin(Math.toRadians(arg))
                "cos" -> cos(Math.toRadians(arg))
                "tan" -> tan(Math.toRadians(arg))
                "ln" -> ln(arg)
                "log", "log10", "lg" -> log10(arg)
                "round" -> round(arg)
                "floor" -> floor(arg)
                else -> throw IllegalArgumentException("unknown function '$name'")
            }
        }
    }
}
