// Port of github.com/tidwall/expr v0.14.0 (MIT License, Copyright (c) 2022 Joshua J Baker)
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.expr

/** An evaluation error. Messages match Go, e.g. "SyntaxError" or "ReferenceError: x is not defined". */
open class ExprException(
    message: String,
    internal val udef: Boolean = false,
) : Exception(message)

/** Thrown from an [Expr.evalForEach] iterator to stop the iteration early (Go `expr.ErrStop`). */
object ErrStop : Exception("stop", null, false, false)

/** An operator for custom values, passed to [Extender.op]. */
enum class Op(
    val symbol: String,
) {
    ADD("+"),
    SUB("-"),
    MUL("*"),
    DIV("/"),
    MOD("%"),
    LT("<"),
    EQ("=="),
    STEQ("==="),
    AND("&&"),
    OR("||"),
    BIT_OR("|"),
    BIT_XOR("^"),
    BIT_AND("&"),
    COAL("??"),
    REGEX("=~"),
    ;

    override fun toString() = symbol
}

class RefInfo(
    val chain: Boolean,
    val value: Value,
    val ident: String,
)

class OpInfo(
    val left: Value,
    val op: Op,
    val right: Value,
)

class CallInfo(
    val chain: Boolean,
    val value: Value,
    val ident: String,
    val args: Value,
)

/**
 * Custom evaluation of references, operators and function calls. Exceptions thrown by these
 * functions become evaluation errors ("ReferenceError: ...", "OperatorError: ...",
 * "CallError: ...").
 */
interface Extender {
    /** Evaluates a property or variable. */
    fun ref(
        info: RefInfo,
        ctx: Context,
    ): Value

    /** Custom operators on values. */
    fun op(
        info: OpInfo,
        ctx: Context,
    ): Value

    /** Function calls for custom values. */
    fun call(
        info: CallInfo,
        ctx: Context,
    ): Value

    companion object {
        /** Go `expr.NewExtender`: missing functions return undefined. */
        fun of(
            ref: ((RefInfo, Context) -> Value)? = null,
            call: ((CallInfo, Context) -> Value)? = null,
            op: ((OpInfo, Context) -> Value)? = null,
        ): Extender =
            object : Extender {
                override fun ref(
                    info: RefInfo,
                    ctx: Context,
                ) = ref?.invoke(info, ctx) ?: Value.UNDEFINED

                override fun op(
                    info: OpInfo,
                    ctx: Context,
                ) = op?.invoke(info, ctx) ?: Value.UNDEFINED

                override fun call(
                    info: CallInfo,
                    ctx: Context,
                ) = call?.invoke(info, ctx) ?: Value.UNDEFINED
            }
    }
}

/** Context for [Expr.eval]. With [noCase], string comparisons are case-insensitive. */
class Context(
    var userData: Any? = null,
    var extender: Extender? = null,
    var noCase: Boolean = false,
)

/**
 * A Javascript-like expression evaluator (github.com/tidwall/expr).
 *
 * Supports numbers (with `u64`/`i64` suffixes and `0x` hex), strings, `true`/`false`/`null`/
 * `undefined`/`NaN`/`Infinity`, arrays, member access and optional chaining (`a.b`, `a?.b`,
 * `a[b]`), calls, and the operators `* / % + - < <= > >= == != === !== =~ & ^ | && || ?? ?:` and
 * `,`. Identifiers are resolved by the [Extender].
 */
object Expr {
    // Operator precedence, see
    // https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Operators/Operator_Precedence
    private const val STEP_COMMA = 1 shl 1 //  1: Comma / Sequence
    private const val STEP_TERNS = 1 shl 2 //  3: Conditional (ternary) operator
    private const val STEP_LOGICAL_OR = 1 shl 3 //  4: Logical OR (||) Nullish coalescing operator (??)
    private const val STEP_LOGICAL_AND = 1 shl 4 //  5: Logical AND (&&)
    private const val STEP_BITWISE_OR = 1 shl 5 //  6: Bitwise OR (|)
    private const val STEP_BITWISE_XOR = 1 shl 6 //  7: Bitwise XOR (^)
    private const val STEP_BITWISE_AND = 1 shl 7 //  8: Bitwise AND (&)
    private const val STEP_EQUALITY = 1 shl 8 //  9: Equality (== ) (!=) (=~)
    private const val STEP_COMPS = 1 shl 9 // 10: Comparison (<) (<=) (>) (>=)
    private const val STEP_SUMS = 1 shl 10 // 12: Summation (-) (+)
    private const val STEP_FACTS = 1 shl 11 // 13: Factors (*) (/)

    private val opSteps =
        IntArray(256).also {
            it[','.code] = STEP_COMMA
            it['?'.code] = STEP_TERNS or STEP_LOGICAL_OR
            it[':'.code] = STEP_TERNS
            it['|'.code] = STEP_LOGICAL_OR or STEP_BITWISE_OR
            it['&'.code] = STEP_LOGICAL_AND or STEP_BITWISE_AND
            it['^'.code] = STEP_BITWISE_XOR
            it['='.code] = STEP_COMPS or STEP_EQUALITY
            it['!'.code] = STEP_EQUALITY
            it['<'.code] = STEP_COMPS
            it['>'.code] = STEP_COMPS
            it['+'.code] = STEP_SUMS
            it['-'.code] = STEP_SUMS
            it['*'.code] = STEP_FACTS
            it['/'.code] = STEP_FACTS
            it['%'.code] = STEP_FACTS
            it['~'.code] = STEP_EQUALITY
        }

    private class EvalContext(
        val steps: Int,
        var iter: ((Value) -> Unit)?,
        val base: Context?,
    )

    /** Evaluates [expr]. Throws [ExprException] (or an extender's exception) on errors. */
    fun eval(
        expr: String,
        ctx: Context? = null,
    ): Value = evalForEach(expr, null, ctx)

    /**
     * Iterates over a series of comma delimited expressions and returns the last value. Throwing
     * [ErrStop] from [iter] stops the iteration early and returns the last value; any other
     * exception is rethrown.
     */
    fun evalForEach(
        expr0: String,
        iter: ((Value) -> Unit)?,
        ctx: Context?,
    ): Value {
        val expr = trim(expr0)
        if (expr.isEmpty()) return Value.UNDEFINED
        // Determine which steps are (possibly) needed by scanning every byte in the input
        // expression and looking for potential candidate characters.
        var steps = 0
        for (c in expr) if (c.code < 256) steps = steps or opSteps[c.code]
        if (iter != null) steps = steps or STEP_COMMA
        return evalExpr(expr, EvalContext(steps, iter, ctx))
    }

    private fun errUndefined(
        ident: String,
        chain: Boolean,
    ): ExprException =
        if (chain) {
            ExprException("Uncaught TypeError: Cannot read properties of undefined (reading '$ident')", true)
        } else {
            ExprException("ReferenceError: $ident is not defined", true)
        }

    private fun errMessage(e: Exception) = e.message ?: ""

    private fun errOperator(e: Exception) = ExprException("OperatorError: " + errMessage(e))

    private fun errReference(e: Exception) = ExprException("ReferenceError: " + errMessage(e))

    private fun errCall(e: Exception) = ExprException("CallError: " + errMessage(e))

    private fun errSyntax() = ExprException("SyntaxError")

    private fun closech(open: Char): Char =
        when (open) {
            '(' -> ')'
            '[' -> ']'
            '{' -> '}'
            else -> open
        }

    private fun parseFloat(s: String): Double? {
        if (s.length <= 15) {
            var n = 0.0
            var i = 0
            var sign = false
            if (s.isNotEmpty() && s[0] == '-') {
                sign = true
                i++
            }
            var isInt = i < s.length
            while (isInt && i < s.length) {
                val c = s[i]
                if (c in '0'..'9') n = n * 10 + (c - '0') else isInt = false
                i++
            }
            if (isInt) return if (sign) n * -1 else n
        }
        // Large numeric strings are put through the built-in converter.
        return GoStrconv.parseFloat(s)
    }

    private fun evalAtom(
        expr0: String,
        ctx: EvalContext,
    ): Value {
        var expr = trim(expr0)
        if (expr.isEmpty()) throw errSyntax()
        var left = Value.UNDEFINED
        var leftReady = false

        // first look for non-chainable atoms
        val c0 = expr[0]
        if (c0 == '0' && expr.length > 1 && (expr[1] == 'x' || expr[1] == 'X')) {
            // hexadecimal
            val x = GoStrconv.parseUint(expr.substring(2), 16) ?: throw errSyntax()
            return Value.float64(x.toDouble())
        }
        when (c0) {
            '0', '-', '.', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                if (expr.length > 3 && expr.endsWith("64")) {
                    if (expr[expr.length - 3] == 'u') {
                        val x = GoStrconv.parseUint(expr.substring(0, expr.length - 3), 10) ?: throw errSyntax()
                        return Value.uint64(x)
                    }
                    if (expr[expr.length - 3] == 'i') {
                        val x = GoStrconv.parseInt(expr.substring(0, expr.length - 3)) ?: throw errSyntax()
                        return Value.int64(x)
                    }
                }
                val x = parseFloat(expr) ?: throw errSyntax()
                return Value.float64(x)
            }

            '"', '\'' -> {
                val (s, raw) = parseString(expr) ?: throw errSyntax()
                left = Value.string(s)
                leftReady = true
                expr = expr.substring(raw.length)
            }

            '(', '{', '[' -> {
                val g = readGroup(expr)
                when (g[0]) {
                    '(' -> {
                        // paren groups can be evaluated and used as the leading value.
                        left = evalExpr(g.substring(1, g.length - 1), ctx)
                    }

                    '[' -> {
                        left = multiExprsToArray(g.substring(1, g.length - 1), ctx)
                    }

                    // '{' not currently allowed as a leading value
                    else -> {
                        throw errSyntax()
                    }
                }
                leftReady = true
                expr = expr.substring(g.length)
            }
        }

        var leftIdent = ""
        if (!leftReady) {
            // probably a chainable identifier
            val ident = readIdent(expr) ?: throw errSyntax()
            left =
                when (ident) {
                    "new", "typeof", "void", "await", "in", "instanceof", "yield" -> throw errSyntax()
                    "true" -> Value.bool(true)
                    "false" -> Value.bool(false)
                    "NaN" -> Value.float64(Double.NaN)
                    "Infinity" -> Value.float64(Double.POSITIVE_INFINITY)
                    "undefined" -> Value.UNDEFINED
                    "null" -> Value.NULL
                    else -> getRefValue(false, Value.UNDEFINED, ident, false, ctx.base)
                }
            expr = expr.substring(ident.length)
            leftIdent = ident
        }

        var leftLeft = Value.UNDEFINED
        var hasLeftLeft = false

        // read each chained component
        var optChain = false
        while (true) {
            // There are more components to read
            expr = trim(expr)
            if (expr.isEmpty()) break
            when (expr[0]) {
                '?', '.' -> {
                    if (expr[0] == '?') {
                        // Optional chaining
                        if (expr.length == 1 || expr[1] != '.') throw errSyntax()
                        expr = expr.substring(1)
                        optChain = true
                    }
                    // Member Access
                    expr = trim(expr.substring(1))
                    val ident = readIdent(expr) ?: throw errSyntax()
                    val v = getRefValue(true, left, ident, optChain, ctx.base)
                    leftLeft = left
                    hasLeftLeft = true
                    left = v
                    expr = expr.substring(ident.length)
                    leftIdent = ident
                }

                '(', '[' -> {
                    val g = readGroup(expr)
                    if (g[0] == '(') {
                        // Function call
                        if (left.kind != Kind.FUNC) throw ExprException("Uncaught TypeError: $leftIdent is not a function")
                        var v = Value.UNDEFINED
                        val ext = ctx.base?.extender
                        if (ext != null) {
                            val args = multiExprsToArray(g.substring(1, g.length - 1), ctx)
                            val info = CallInfo(hasLeftLeft, leftLeft, left.funcIdent(), args)
                            v =
                                try {
                                    ext.call(info, ctx.base)
                                } catch (e: Exception) {
                                    throw errCall(e)
                                }
                        }
                        leftLeft = left
                        hasLeftLeft = true
                        left = v
                    } else {
                        // Computed Member Access
                        val last = evalExpr(g.substring(1, g.length - 1), ctx)
                        val ident = last.toString()
                        val v = getRefValue(true, left, ident, optChain, ctx.base)
                        leftLeft = left
                        hasLeftLeft = true
                        left = v
                    }
                    expr = expr.substring(g.length)
                }

                else -> {
                    throw errSyntax()
                }
            }
        }
        return left
    }

    private fun multiExprsToArray(
        expr: String,
        ctx: EvalContext,
    ): Value {
        val arr = ArrayList<Value>()
        evalForEach(expr, { arr.add(it) }, ctx.base)
        return Value.array(arr)
    }

    private fun getRefValue(
        chain: Boolean,
        left: Value,
        ident: String,
        optChain: Boolean,
        ctx: Context?,
    ): Value {
        try {
            val ext = ctx?.extender ?: throw errUndefined(ident, chain)
            val v =
                try {
                    ext.ref(RefInfo(chain, left, ident), ctx)
                } catch (e: Exception) {
                    throw errReference(e)
                }
            if (v.kind == Kind.UNDEF && left.kind == Kind.UNDEF) throw errUndefined(ident, chain)
            return v
        } catch (e: ExprException) {
            if (optChain && e.udef) return Value.UNDEFINED
            throw e
        }
    }

    private fun isIdStart(c: Char) = c == '$' || c == '_' || (c in 'A'..'Z') || (c in 'a'..'z')

    private fun isIdContinue(c: Char) = isIdStart(c) || (c in '0'..'9')

    /** Reads an ASCII identifier from the start of [expr]. */
    internal fun readIdent(expr: String): String? {
        if (expr.isEmpty() || !isIdStart(expr[0])) return null
        var i = 1
        while (i < expr.length && isIdContinue(expr[i])) i++
        return expr.substring(0, i)
    }

    /**
     * Parses a Javascript encoded string at the start of [data], returning the unescaped string
     * and the raw string including quotes, or null for a syntax error.
     */
    internal fun parseString(data: String): Pair<String, String>? {
        var esc = false
        if (data.length < 2) return null
        val qch = data[0]
        var i = 1
        while (i < data.length) {
            if (data[i] < ' ') break
            if (data[i] == '\\') {
                esc = true
                i++
                if (i == data.length) return null
                when (data[i]) {
                    'u' -> {
                        if (i + 1 < data.length && data[i + 1] == '{') {
                            i += 2
                            var end = false
                            while (i < data.length) {
                                if (data[i] == '}') {
                                    end = true
                                    break
                                }
                                if (!ishex(data[i])) return null
                                i++
                            }
                            if (!end) return null
                        } else {
                            repeat(4) {
                                i++
                                if (i >= data.length || !ishex(data[i])) return null
                            }
                        }
                    }

                    'x' -> {
                        repeat(2) {
                            i++
                            if (i >= data.length || !ishex(data[i])) return null
                        }
                    }
                }
            } else if (data[i] == qch) {
                var s = data.substring(1, i)
                if (esc) s = unescapeString(s)
                return Pair(s, data.substring(0, i + 1))
            }
            i++
        }
        return null
    }

    /** The rune from \uXXXX, \u{X...} or \xXX at the start of [data], and the chars used. */
    private fun runeit(
        data: String,
        which: Char,
    ): Pair<Int, Int> {
        val hex: String
        val n: Int
        if (which == 'x') {
            hex = data.substring(0, 2)
            n = 2
        } else if (data[0] == '{') {
            val e = data.indexOf('}')
            hex = data.substring(1, e)
            n = e + 1
        } else {
            hex = data.substring(0, 4)
            n = 4
        }
        // Go: x, _ = strconv.ParseUint(hex, 16, 64); rune(x)
        val x: Long =
            if (hex.isEmpty()) {
                0
            } else {
                GoStrconv.parseUint(hex, 16)?.toLong() ?: -1L
            }
        return Pair(x.toInt(), n)
    }

    private fun isSurrogate(r: Int) = r in 0xD800..0xDFFF

    /** Unescapes a Javascript string that was validated by [parseString]. */
    private fun unescapeString(data: String): String {
        val str = StringBuilder(data.length)
        var i = 0
        while (i < data.length) {
            if (data[i] != '\\') {
                str.append(data[i])
                i++
                continue
            }
            i++
            when (data[i]) {
                '0' -> {
                    str.append('\u0000')
                }

                'b' -> {
                    str.append('\b')
                }

                'f' -> {
                    str.append('\u000c')
                }

                'n' -> {
                    str.append('\n')
                }

                'r' -> {
                    str.append('\r')
                }

                't' -> {
                    str.append('\t')
                }

                'v' -> {
                    str.append('\u000b')
                }

                'u' -> {
                    i++
                    var (r, n) = runeit(data.substring(i), 'u')
                    i += n
                    if (isSurrogate(r)) {
                        // need another code
                        if (data.length - i >= 6 && data[i] == '\\' && data[i + 1] == 'u') {
                            // we expect it to be correct so just consume it
                            i += 2
                            val (r2, n2) = runeit(data.substring(i), 'u')
                            i += n2
                            r = decodeSurrogates(r, r2)
                        }
                    }
                    appendRune(str, r)
                    i-- // backtrack index by one
                }

                'x' -> {
                    i++
                    val (r, n) = runeit(data.substring(i), 'x')
                    i += n
                    appendRune(str, r)
                    i-- // backtrack index by one
                }

                else -> {
                    str.append(data[i])
                }
            }
            i++
        }
        return str.toString()
    }

    /** Go `utf16.DecodeRune`. */
    private fun decodeSurrogates(
        r1: Int,
        r2: Int,
    ): Int =
        if (r1 in 0xD800..0xDBFF && r2 in 0xDC00..0xDFFF) {
            (((r1 - 0xD800) shl 10) or (r2 - 0xDC00)) + 0x10000
        } else {
            0xFFFD
        }

    /** Appends the UTF-8 encoding of [r0] as byte chars (Go `utf8.EncodeRune`). */
    private fun appendRune(
        sb: StringBuilder,
        r0: Int,
    ) {
        val r = if (r0 < 0 || r0 > 0x10FFFF || isSurrogate(r0)) 0xFFFD else r0
        when {
            r < 0x80 -> {
                sb.append(r.toChar())
            }

            r < 0x800 -> {
                sb.append((0xC0 or (r shr 6)).toChar())
                sb.append((0x80 or (r and 0x3F)).toChar())
            }

            r < 0x10000 -> {
                sb.append((0xE0 or (r shr 12)).toChar())
                sb.append((0x80 or ((r shr 6) and 0x3F)).toChar())
                sb.append((0x80 or (r and 0x3F)).toChar())
            }

            else -> {
                sb.append((0xF0 or (r shr 18)).toChar())
                sb.append((0x80 or ((r shr 12) and 0x3F)).toChar())
                sb.append((0x80 or ((r shr 6) and 0x3F)).toChar())
                sb.append((0x80 or (r and 0x3F)).toChar())
            }
        }
    }

    private fun fact(
        left: Value,
        op: Char,
        expr0: String,
        ctx: EvalContext,
    ): Value {
        val expr = trim(expr0)
        if (expr.isEmpty()) throw errSyntax()
        val right = evalAtom(expr, ctx)
        return when (op) {
            '*' -> mul(left, right, ctx)
            '/' -> div(left, right, ctx)
            '%' -> mod(left, right, ctx)
            else -> right
        }
    }

    private fun evalFacts(
        expr: String,
        ctx: EvalContext,
    ): Value {
        var s = 0
        var left = Value.UNDEFINED
        var op = '\u0000'
        var i = 0
        while (i < expr.length) {
            when (expr[i]) {
                '*', '/', '%' -> {
                    left = fact(left, op, expr.substring(s, i), ctx)
                    op = expr[i]
                    s = i + 1
                }

                '(', '[', '{', '"', '\'' -> {
                    i += readGroup(expr.substring(i)).length - 1
                }
            }
            i++
        }
        return fact(left, op, expr.substring(s), ctx)
    }

    private fun sum(
        left: Value,
        op: Char,
        expr0: String,
        neg: Boolean,
        ctx: EvalContext,
    ): Value {
        val expr = trim(expr0)
        if (expr.isEmpty()) throw errSyntax()
        // parse factors of expression
        var right = evalAuto(STEP_SUMS shl 1, expr, ctx)
        if (neg) {
            // make right negative
            right = mul(right, Value.float64(-1.0), ctx)
        }
        return when (op) {
            '+' -> add(left, right, ctx)
            '-' -> sub(left, right, ctx)
            else -> right
        }
    }

    private fun evalSums(
        expr: String,
        ctx: EvalContext,
    ): Value {
        var s = 0
        var left = Value.UNDEFINED
        var op = '\u0000'
        var fill = false
        var neg = false
        var i = 0
        while (i < expr.length) {
            when (val c = expr[i]) {
                '-', '+' -> {
                    if (!fill) {
                        // -- not allowed
                        if (i > 0 && expr[i - 1] == c) throw errSyntax()
                        if (c == '-') neg = !neg
                        s = i + 1
                    } else if (i > 0 && (expr[i - 1] == 'e' || expr[i - 1] == 'E')) {
                        // scientific notation
                    } else {
                        if (neg && s > 0 && s < expr.length && expr[s - 1] == '-' && expr[s] in '0'..'9') {
                            s--
                            neg = false
                        }
                        left = sum(left, op, expr.substring(s, i), neg, ctx)
                        op = c
                        s = i + 1
                        fill = false
                        neg = false
                    }
                }

                '(', '[', '{', '"', '\'' -> {
                    i += readGroup(expr.substring(i)).length - 1
                    fill = true
                }

                else -> {
                    if (!fill && !isspace(c)) fill = true
                }
            }
            i++
        }
        if (neg && s > 0 && s < expr.length && expr[s - 1] == '-' && expr[s] in '0'..'9') {
            s--
            neg = false
        }
        return sum(left, op, expr.substring(s), neg, ctx)
    }

    // Two-character operators are encoded as the first char + 32, like in Go.
    private const val OP_LTE = '<'.code + 32
    private const val OP_GTE = '>'.code + 32
    private const val OP_SEQ = '='.code + 32
    private const val OP_SNEQ = '!'.code + 32

    private fun comp(
        left: Value,
        op: Int,
        expr0: String,
        ctx: EvalContext,
    ): Value {
        val expr = trim(expr0)
        if (expr.isEmpty()) throw errSyntax()
        // parse next expression
        val right = evalAuto(STEP_COMPS shl 1, expr, ctx)
        return when (op) {
            '<'.code -> lt(left, right, ctx)
            OP_LTE -> lte(left, right, ctx)
            '>'.code -> gt(left, right, ctx)
            OP_GTE -> gte(left, right, ctx)
            else -> right
        }
    }

    private fun evalComps(
        expr: String,
        ctx: EvalContext,
    ): Value {
        var s = 0
        var left = Value.UNDEFINED
        var op = 0
        var i = 0
        while (i < expr.length) {
            when (expr[i]) {
                '<', '>' -> {
                    var opch = expr[i].code
                    var opsz = 1
                    if (i < expr.length - 1 && expr[i + 1] == '=') {
                        opch += 32
                        opsz++
                    }
                    left = comp(left, op, expr.substring(s, i), ctx)
                    op = opch
                    i = i + opsz - 1
                    s = i + 1
                }

                '(', '[', '{', '"', '\'' -> {
                    i += readGroup(expr.substring(i)).length - 1
                }
            }
            i++
        }
        return comp(left, op, expr.substring(s), ctx)
    }

    private fun equal(
        left: Value,
        op: Int,
        expr0: String,
        ctx: EvalContext,
    ): Value {
        var neg = false
        var boolit = false
        var expr = trim(expr0)
        while (true) {
            if (expr.isEmpty()) throw errSyntax()
            if (expr[0] != '!') break
            neg = !neg
            boolit = true
            expr = trim(expr.substring(1))
        }
        // parse next expression
        var right = evalAuto(STEP_EQUALITY shl 1, expr, ctx)
        if (boolit) {
            if (right.kind != Kind.BOOL) right = Value.bool(right.bool())
            if (neg) right = Value.bool(!right.asBool())
        }
        return when (op) {
            '='.code -> eq(left, right, ctx)
            '!'.code -> neq(left, right, ctx)
            OP_SEQ -> seq(left, right, ctx)
            OP_SNEQ -> sneq(left, right, ctx)
            '~'.code -> doOp(Op.REGEX, left, right, ctx)
            else -> right
        }
    }

    private fun evalEquality(
        expr: String,
        ctx: EvalContext,
    ): Value {
        var s = 0
        var left = Value.UNDEFINED
        var op = 0
        var i = 0
        while (i < expr.length) {
            when (expr[i]) {
                '=', '!' -> {
                    var opch = expr[i].code
                    var opsz = 1
                    var isOp = true
                    if (expr[i] == '=') {
                        if (i > 0 && (expr[i - 1] == '>' || expr[i - 1] == '<')) {
                            isOp = false
                        } else {
                            if (i == expr.length - 1 || (expr[i + 1] != '=' && expr[i + 1] != '~')) throw errSyntax()
                            opsz++
                            if (expr[i + 1] == '~') {
                                opch = '~'.code
                            } else if (i + 2 < expr.length && expr[i + 2] == '=') {
                                // strict ===
                                opch += 32
                                opsz++
                            }
                        }
                    } else {
                        if (i == expr.length - 1 || expr[i + 1] != '=') {
                            isOp = false
                        } else {
                            opsz++
                            if (i + 2 < expr.length && expr[i + 2] == '=') {
                                // strict !==
                                opch += 32
                                opsz++
                            }
                        }
                    }
                    if (isOp) {
                        left = equal(left, op, expr.substring(s, i), ctx)
                        op = opch
                        i = i + opsz - 1
                        s = i + 1
                    }
                }

                '(', '[', '{', '"', '\'' -> {
                    i += readGroup(expr.substring(i)).length - 1
                }
            }
            i++
        }
        return equal(left, op, expr.substring(s), ctx)
    }

    /** One step of a left-to-right chain of binary operators with the same precedence. */
    private fun binaryStep(
        left: Value,
        op: Char,
        expr0: String,
        next: Int,
        ctx: EvalContext,
        apply: (Value, Char, Value) -> Value,
    ): Value {
        val expr = trim(expr0)
        if (expr.isEmpty()) throw errSyntax()
        val right = evalAuto(next, expr, ctx)
        return if (op == '\u0000') right else apply(left, op, right)
    }

    /** evalBitwiseXOR, evalBitwiseOR and evalBitwiseAND, which share their structure in Go. */
    private fun evalBitwise(
        expr: String,
        opChar: Char,
        next: Int,
        ctx: EvalContext,
        apply: (Value, Value) -> Value,
    ): Value {
        var s = 0
        var left = Value.UNDEFINED
        var op = '\u0000'
        var i = 0
        val f = { l: Value, _: Char, r: Value -> apply(l, r) }
        while (i < expr.length) {
            when (expr[i]) {
                opChar -> {
                    left = binaryStep(left, op, expr.substring(s, i), next, ctx, f)
                    op = expr[i]
                    s = i + 1
                }

                '(', '[', '{', '"', '\'' -> {
                    i += readGroup(expr.substring(i)).length - 1
                }
            }
            i++
        }
        return binaryStep(left, op, expr.substring(s), next, ctx, f)
    }

    private fun evalBitwiseXOR(
        expr: String,
        ctx: EvalContext,
    ) = evalBitwise(expr, '^', STEP_BITWISE_XOR shl 1, ctx) { a, b -> xor(a, b, ctx) }

    private fun evalBitwiseOR(
        expr: String,
        ctx: EvalContext,
    ) = evalBitwise(expr, '|', STEP_BITWISE_OR shl 1, ctx) { a, b -> bor(a, b, ctx) }

    private fun evalBitwiseAND(
        expr: String,
        ctx: EvalContext,
    ) = evalBitwise(expr, '&', STEP_BITWISE_AND shl 1, ctx) { a, b -> band(a, b, ctx) }

    private fun evalLogicalAND(
        expr: String,
        ctx: EvalContext,
    ): Value {
        var s = 0
        var left = Value.UNDEFINED
        var op = '\u0000'
        var i = 0
        val f = { l: Value, _: Char, r: Value -> and(l, r, ctx) }
        while (i < expr.length) {
            when (expr[i]) {
                '&' -> {
                    if (i + 1 == expr.length) throw errSyntax()
                    if (expr[i + 1] != '&') {
                        // bitwise AND
                        i++
                    } else {
                        left = binaryStep(left, op, expr.substring(s, i), STEP_LOGICAL_AND shl 1, ctx, f)
                        op = expr[i]
                        i++
                        s = i + 1
                    }
                }

                '(', '[', '{', '"', '\'' -> {
                    i += readGroup(expr.substring(i)).length - 1
                }
            }
            i++
        }
        return binaryStep(left, op, expr.substring(s), STEP_LOGICAL_AND shl 1, ctx, f)
    }

    private fun evalLogicalOR(
        expr: String,
        ctx: EvalContext,
    ): Value {
        var s = 0
        var left = Value.UNDEFINED
        var op = '\u0000'
        var i = 0
        val f = { l: Value, o: Char, r: Value -> if (o == '|') or(l, r, ctx) else coalesce(l, r, ctx) }
        while (i < expr.length) {
            val c = expr[i]
            if (c == '?' && i + 1 < expr.length && expr[i + 1] == '.') {
                // '?.' operator
                i += 2
                continue
            }
            when (c) {
                '?', '|' -> {
                    if (i + 1 == expr.length) throw errSyntax()
                    if (expr[i + 1] != c) {
                        // bitwise OR
                        i++
                    } else {
                        left = binaryStep(left, op, expr.substring(s, i), STEP_LOGICAL_OR shl 1, ctx, f)
                        op = c
                        i++
                        s = i + 1
                    }
                }

                '(', '[', '{', '"', '\'' -> {
                    i += readGroup(expr.substring(i)).length - 1
                }
            }
            i++
        }
        return binaryStep(left, op, expr.substring(s), STEP_LOGICAL_OR shl 1, ctx, f)
    }

    private fun evalTerns(
        expr: String,
        ctx: EvalContext,
    ): Value {
        var cond = ""
        var s = 0
        var depth = 0
        var i = 0
        while (i < expr.length) {
            when (expr[i]) {
                '?' -> {
                    if (i + 1 < expr.length && (expr[i + 1] == '?' || expr[i + 1] == '.')) {
                        // '??' or '?.' operator
                        i++
                    } else {
                        if (depth == 0) {
                            cond = expr.substring(0, i)
                            s = i + 1
                        }
                        depth++
                    }
                }

                ':' -> {
                    depth--
                    if (depth == 0) {
                        val left = expr.substring(s, i)
                        val right = expr.substring(i + 1)
                        val res = evalExpr(cond, ctx)
                        return if (res.bool()) evalExpr(left, ctx) else evalExpr(right, ctx)
                    }
                }

                '(', '[', '{', '"', '\'' -> {
                    i += readGroup(expr.substring(i)).length - 1
                }
            }
            i++
        }
        if (depth == 0) return evalAuto(STEP_TERNS shl 1, expr, ctx)
        throw errSyntax()
    }

    private fun evalComma(
        expr: String,
        ctx: EvalContext,
    ): Value {
        var s = 0
        val iter = ctx.iter
        var i = 0
        while (i < expr.length) {
            when (expr[i]) {
                ',' -> {
                    ctx.iter = null // disable the iter
                    val res =
                        try {
                            evalAuto(STEP_COMMA shl 1, expr.substring(s, i), ctx)
                        } finally {
                            ctx.iter = iter // enable the iter
                        }
                    if (iter != null) {
                        try {
                            iter(res)
                        } catch (_: ErrStop) {
                            return res
                        }
                    }
                    s = i + 1
                }

                '(', '[', '{', '"', '\'' -> {
                    i += readGroup(expr.substring(i)).length - 1
                }
            }
            i++
        }
        val res = evalAuto(STEP_COMMA shl 1, expr.substring(s), ctx)
        if (iter != null) {
            try {
                iter(res)
            } catch (_: ErrStop) {
                return res
            }
        }
        return res
    }

    private fun evalAuto(
        step: Int,
        expr: String,
        ctx: EvalContext,
    ): Value {
        // Like the Go switch with fallthrough: start at [step] and use the first step that the
        // expression (possibly) needs.
        var st = step
        while (st <= STEP_FACTS) {
            if ((ctx.steps and st) == st) {
                return when (st) {
                    STEP_COMMA -> evalComma(expr, ctx)
                    STEP_TERNS -> evalTerns(expr, ctx)
                    STEP_LOGICAL_OR -> evalLogicalOR(expr, ctx)
                    STEP_LOGICAL_AND -> evalLogicalAND(expr, ctx)
                    STEP_BITWISE_OR -> evalBitwiseOR(expr, ctx)
                    STEP_BITWISE_XOR -> evalBitwiseXOR(expr, ctx)
                    STEP_BITWISE_AND -> evalBitwiseAND(expr, ctx)
                    STEP_EQUALITY -> evalEquality(expr, ctx)
                    STEP_COMPS -> evalComps(expr, ctx)
                    STEP_SUMS -> evalSums(expr, ctx)
                    else -> evalFacts(expr, ctx)
                }
            }
            st = st shl 1
        }
        return evalAtom(expr, ctx)
    }

    private fun evalExpr(
        expr: String,
        ctx: EvalContext,
    ): Value = evalAuto(STEP_COMMA, expr, ctx)

    private fun readGroup(data: String): String {
        val g = squash(data) ?: throw errSyntax()
        if (g.length < 2 || g[g.length - 1] != closech(data[0])) throw errSyntax()
        return g
    }

    /**
     * Returns the group at the start of [data] (which starts with `[`, `{`, `(`, `"` or `'`),
     * ignoring all nested groups and strings, or null if it is not terminated.
     */
    private fun squash(data: String): String? {
        var i = 0
        var depth = 0
        if (data[0] != '"' && data[0] != '\'') {
            i = 1
            depth = 1
        }
        while (i < data.length) {
            val c = data[i]
            if (c < '"' || c > '}') {
                i++
                continue
            }
            when (c) {
                '"', '\'' -> {
                    i++
                    val s2 = i
                    while (i < data.length) {
                        if (data[i] > '\\') {
                            i++
                            continue
                        }
                        if (data[i] == c) {
                            // look for an escaped slash
                            if (data[i - 1] == '\\') {
                                var n = 0
                                var j = i - 2
                                while (j > s2 - 1) {
                                    if (data[j] != '\\') break
                                    n++
                                    j--
                                }
                                if (n % 2 == 0) {
                                    i++
                                    continue
                                }
                            }
                            break
                        }
                        i++
                    }
                    if (depth == 0) {
                        if (i >= data.length) return null
                        return data.substring(0, i + 1)
                    }
                }

                '{', '[', '(' -> {
                    depth++
                }

                '}', ']', ')' -> {
                    depth--
                    if (depth == 0) return data.substring(0, i + 1)
                }
            }
            i++
        }
        return null
    }

    private fun isspace(c: Char) = c == '\t' || c == '\n' || c == '\u000b' || c == '\u000c' || c == '\r' || c == ' '

    private fun ishex(c: Char) = (c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F')

    private fun trim(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && isspace(s[a])) a++
        while (a < b && isspace(s[b - 1])) b--
        return if (a == 0 && b == s.length) s else s.substring(a, b)
    }

    // ---- operators ----

    private fun doOp(
        op: Op,
        a: Value,
        b: Value,
        ctx: EvalContext,
    ): Value {
        val base = ctx.base
        val ext = base?.extender ?: throw ExprException("OperatorError: undefined ")
        return try {
            ext.op(OpInfo(a, op, b), base)
        } catch (e: Exception) {
            throw errOperator(e)
        }
    }

    private fun add(
        a: Value,
        b: Value,
        ctx: EvalContext,
    ): Value {
        if (a.kind == Kind.OBJ || b.kind == Kind.OBJ) return doOp(Op.ADD, a, b, ctx)
        if (a.kind == b.kind) {
            when (a.kind) {
                Kind.FLOAT -> {
                    return Value.float64(a.asFloat64() + b.asFloat64())
                }

                Kind.INT -> {
                    return Value.int64(a.bits + b.bits)
                }

                Kind.UINT -> {
                    return Value.uint64(a.asUint64() + b.asUint64())
                }

                Kind.STR -> {
                    return Value.string(a.str + b.str)
                }

                Kind.BOOL, Kind.UNDEF, Kind.NULL -> {
                    return Value.float64(a.float64() + b.float64())
                }

                else -> {}
            }
        } else if (a.isnum() && b.isnum()) {
            return Value.float64(a.float64() + b.float64())
        }
        return Value.string(a.toString() + b.toString())
    }

    private inline fun bitwise(
        op: Op,
        a: Value,
        b: Value,
        ctx: EvalContext,
        f: (Long, Long) -> Long,
    ): Value {
        if (a.kind == Kind.OBJ || b.kind == Kind.OBJ) return doOp(op, a, b, ctx)
        if (a.kind == b.kind) {
            when (a.kind) {
                Kind.INT -> {
                    return Value.int64(f(a.bits, b.bits))
                }

                Kind.UINT -> {
                    return Value.uint64(f(a.bits, b.bits).toULong())
                }

                else -> {}
            }
        }
        return Value.float64(Conv.itof(f(a.int64(), b.int64())))
    }

    private fun bor(
        a: Value,
        b: Value,
        ctx: EvalContext,
    ) = bitwise(Op.BIT_OR, a, b, ctx) { x, y -> x or y }

    private fun band(
        a: Value,
        b: Value,
        ctx: EvalContext,
    ) = bitwise(Op.BIT_AND, a, b, ctx) { x, y -> x and y }

    private fun xor(
        a: Value,
        b: Value,
        ctx: EvalContext,
    ) = bitwise(Op.BIT_XOR, a, b, ctx) { x, y -> x xor y }

    private fun sub(
        a: Value,
        b: Value,
        ctx: EvalContext,
    ): Value {
        if (a.kind == Kind.OBJ || b.kind == Kind.OBJ) return doOp(Op.SUB, a, b, ctx)
        if (a.kind == b.kind) {
            when (a.kind) {
                Kind.FLOAT -> {
                    return Value.float64(a.asFloat64() - b.asFloat64())
                }

                Kind.INT -> {
                    return Value.int64(a.bits - b.bits)
                }

                Kind.UINT -> {
                    return Value.uint64(a.asUint64() - b.asUint64())
                }

                else -> {}
            }
        }
        return Value.float64(a.float64() - b.float64())
    }

    private fun mul(
        a: Value,
        b: Value,
        ctx: EvalContext,
    ): Value {
        if (a.kind == Kind.OBJ || b.kind == Kind.OBJ) return doOp(Op.MUL, a, b, ctx)
        if (a.kind == b.kind) {
            when (a.kind) {
                Kind.FLOAT -> {
                    return Value.float64(a.asFloat64() * b.asFloat64())
                }

                Kind.INT -> {
                    return Value.int64(a.bits * b.bits)
                }

                Kind.UINT -> {
                    return Value.uint64(a.asUint64() * b.asUint64())
                }

                else -> {}
            }
        }
        return Value.float64(a.float64() * b.float64())
    }

    private fun div(
        a: Value,
        b: Value,
        ctx: EvalContext,
    ): Value {
        if (a.kind == Kind.OBJ || b.kind == Kind.OBJ) return doOp(Op.DIV, a, b, ctx)
        if (a.kind == b.kind) {
            when (a.kind) {
                Kind.FLOAT -> {
                    return Value.float64(a.asFloat64() / b.asFloat64())
                }

                Kind.INT -> {
                    if (b.bits == 0L) return Value.float64(Double.NaN)
                    return Value.int64(a.bits / b.bits)
                }

                Kind.UINT -> {
                    if (b.bits == 0L) return Value.float64(Double.NaN)
                    return Value.uint64(a.asUint64() / b.asUint64())
                }

                else -> {}
            }
        }
        return Value.float64(a.float64() / b.float64())
    }

    private fun mod(
        a: Value,
        b: Value,
        ctx: EvalContext,
    ): Value {
        if (a.kind == Kind.OBJ || b.kind == Kind.OBJ) return doOp(Op.MOD, a, b, ctx)
        if (a.kind == b.kind) {
            when (a.kind) {
                Kind.INT -> {
                    if (b.bits == 0L) return Value.float64(Double.NaN)
                    return Value.int64(a.bits % b.bits)
                }

                Kind.UINT -> {
                    if (b.bits == 0L) return Value.float64(Double.NaN)
                    return Value.uint64(a.asUint64() % b.asUint64())
                }

                else -> {}
            }
        }
        // Go math.Mod has the same semantics as the JVM's floating point remainder.
        return Value.float64(a.float64() % b.float64())
    }

    private fun tolower(c: Char): Char = if (c in 'A'..'Z') c + 32 else c

    private fun stringLessInsensitive(
        a: String,
        b: String,
    ): Boolean {
        var i = 0
        while (i < a.length && i < b.length) {
            val ca = tolower(a[i])
            val cb = tolower(b[i])
            if (ca < cb) return true
            if (ca > cb) return false
            i++
        }
        return a.length < b.length
    }

    private fun stringEqualInsensitive(
        a: String,
        b: String,
    ): Boolean {
        if (a.length != b.length) return false
        for (i in a.indices) if (tolower(a[i]) != tolower(b[i])) return false
        return true
    }

    private fun noCase(ctx: EvalContext?) = ctx?.base?.noCase == true

    private fun lt(
        a: Value,
        b: Value,
        ctx: EvalContext,
    ): Value {
        if (a.kind == Kind.OBJ || b.kind == Kind.OBJ) return doOp(Op.LT, a, b, ctx)
        if (a.kind == b.kind) {
            when (a.kind) {
                Kind.FLOAT -> {
                    return Value.bool(a.asFloat64() < b.asFloat64())
                }

                Kind.INT -> {
                    return Value.bool(a.bits < b.bits)
                }

                Kind.UINT -> {
                    return Value.bool(a.asUint64() < b.asUint64())
                }

                Kind.STR -> {
                    val less = if (noCase(ctx)) stringLessInsensitive(a.str, b.str) else a.str < b.str
                    return Value.bool(less)
                }

                else -> {}
            }
        }
        return Value.bool(a.float64() < b.float64())
    }

    private fun lte(
        a: Value,
        b: Value,
        ctx: EvalContext,
    ): Value {
        val t = lt(a, b, ctx)
        if (t.bool()) return t
        return eq(a, b, ctx)
    }

    private fun gt(
        a: Value,
        b: Value,
        ctx: EvalContext,
    ): Value = lt(b, a, ctx)

    private fun gte(
        a: Value,
        b: Value,
        ctx: EvalContext,
    ): Value {
        val t = gt(a, b, ctx)
        if (t.bool()) return t
        return eq(a, b, ctx)
    }

    private fun eq(
        a: Value,
        b: Value,
        ctx: EvalContext,
    ): Value {
        if (a.kind == Kind.OBJ || b.kind == Kind.OBJ) return doOp(Op.EQ, a, b, ctx)
        if (a.kind == b.kind) {
            when (a.kind) {
                Kind.FLOAT -> {
                    return Value.bool(a.asFloat64() == b.asFloat64())
                }

                Kind.INT, Kind.UINT -> {
                    return Value.bool(a.bits == b.bits)
                }

                Kind.STR -> {
                    val equal = if (noCase(ctx)) stringEqualInsensitive(a.str, b.str) else a.str == b.str
                    return Value.bool(equal)
                }

                Kind.BOOL -> {
                    return Value.bool(a.asBool() == b.asBool())
                }

                Kind.UNDEF, Kind.NULL -> {
                    return Value.bool(true)
                }

                else -> {}
            }
        }
        if (a.kind != b.kind) {
            return Value.bool(a.float64() == b.float64()) // MARK: float equality
        }
        if (lt(a, b, ctx).bool()) return Value.bool(false)
        return Value.bool(!lt(b, a, ctx).bool())
    }

    private fun seq(
        a: Value,
        b: Value,
        ctx: EvalContext,
    ): Value = if (a.kind == b.kind) eq(a, b, ctx) else Value.bool(false)

    private fun neq(
        a: Value,
        b: Value,
        ctx: EvalContext,
    ): Value = Value.bool(!eq(a, b, ctx).bool())

    private fun sneq(
        a: Value,
        b: Value,
        ctx: EvalContext,
    ): Value = Value.bool(!seq(a, b, ctx).bool())

    private fun and(
        a: Value,
        b: Value,
        ctx: EvalContext,
    ): Value {
        if (a.kind == Kind.OBJ || b.kind == Kind.OBJ) return doOp(Op.AND, a, b, ctx)
        return Value.bool(a.bool() && b.bool())
    }

    private fun or(
        a: Value,
        b: Value,
        ctx: EvalContext,
    ): Value {
        if (a.kind == Kind.OBJ || b.kind == Kind.OBJ) return doOp(Op.OR, a, b, ctx)
        return Value.bool(a.bool() || b.bool())
    }

    private fun coalesce(
        a: Value,
        b: Value,
        ctx: EvalContext,
    ): Value {
        if (a.kind == Kind.OBJ || b.kind == Kind.OBJ) return doOp(Op.COAL, a, b, ctx)
        return when (a.kind) {
            Kind.UNDEF, Kind.NULL -> b
            else -> a
        }
    }
}
