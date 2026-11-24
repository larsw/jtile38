// Port of github.com/tidwall/expr v0.14.0 expr_test.go (MIT License, Copyright (c) 2022 Joshua J Baker)
// Kotlin port Copyright jtile38 authors, MIT License.

package io.jtile38.expr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExprTest {
    /** Go strings are UTF-8 bytes; expr works on byte strings (one char per byte). */
    private fun bs(s: String) = String(s.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)

    /** The upstream test table: expression to expected string (or error message). */
    private val testTable =
        listOf(
            "true.hello == '11'" to "false",
            ".1" to "0.1",
            ".1e-1" to "0.01",
            ".1e-1 + 5" to "5.01",
            "0.1" to "0.1",
            "1u64" to "1",
            "1.0u64" to "SyntaxError",
            "-1i64" to "-1",
            "-1.0u64" to "SyntaxError",
            "" to "undefined",
            " " to "undefined",
            "()" to "SyntaxError",
            "\"\\\"" to "SyntaxError",
            "1" to "1",
            "-1" to "-1",
            "- 1" to "-1",
            " - 1" to "-1",
            " - -1" to "1",
            "- - 1" to "1",
            "- - - -1" to "1",
            "- - - -1 - 2" to "-1",
            "+1" to "1",
            "+ 1" to "1",
            " + 1" to "1",
            " + +1" to "1",
            " + +-1" to "-1",
            " + +-+ +- -1" to "-1",
            "-+-+-+-1 - 2" to "-1",
            "(" to "SyntaxError",
            "(1" to "SyntaxError",
            "(1)" to "1",
            "( 1 )" to "1",
            "--1" to "SyntaxError",
            "1--" to "SyntaxError",
            "1++" to "SyntaxError",
            "++1" to "SyntaxError",
            "-+1" to "-1",
            "\"hello\"" to "hello",
            "\"hel\\nlo\"" to "hel\u000alo",
            "\"hi\"+1" to "hi1",
            "\"hi\"-1" to "NaN",
            "1+1-0.5" to "1.5",
            "2*4" to "8",
            "(2*4" to "SyntaxError",
            "\"2*4" to "SyntaxError",
            "1 > 2" to "false",
            "1 > 2 || 3 > 2" to "true",
            "2 > 3" to "false",
            "3 > 2 || (2 > 3 && 1 < 2)" to "true",
            "(1 < 2 && 3 > 2) + 10" to "11",
            "999 + 777 * (888 + (0.5 + 1.5)) * (0.5 + true)" to "1038294",
            "999 + 777 * (888 / 0.456) / true" to "1514104.2631578946",
            "999 + 777 * (888 / 0.456) / 0" to "Infinity",
            "999 + 777 * (888 / 0.456) / 0" to "Infinity",
            "1.0e1" to "10",
            "1.0E1" to "10",
            "1.0e+1" to "10",
            "1.0E+1" to "10",
            "1.0e-1" to "0.1",
            "1.0E-1" to "0.1",
            "-1.0E-1" to "-0.1",
            "\"he\\\"\\b\\fllo\"" to "he\"\u0008\u000cllo",
            "(\"hello\\\\\\t\\/\\r\\n\\t\\\\\\\"world\")" to "hello\\\u0009/\u000d\u000a\u0009\\\"world",
            "\"hello" to "SyntaxError",
            "1 | 2" to "3",
            "1 & 2" to "0",
            "5 & 4" to "4",
            "5 ^ 4" to "1",
            "500 ^" to "SyntaxError",
            "500 &" to "SyntaxError",
            "500 |" to "SyntaxError",
            "500 ^ 700" to "840",
            "500u64 ^ 700u64" to "840",
            "500i64 ^ 700i64" to "840",
            "numobj(500) ^ numobj(700)" to "840",
            "'500' ^ '700'" to "840",
            "500 & 700" to "180",
            "500u64 & 700u64" to "180",
            "numobj(500) & numobj(700)" to "180",
            "500i64 & 700i64" to "180",
            "'500' & '700'" to "180",
            "500 | 700" to "1020",
            "500u64 | 700u64" to "1020",
            "500i64 | 700i64" to "1020",
            "numobj(500) | numobj(700)" to "1020",
            "'500' | '700'" to "1020",
            "500 | -700" to "-524",
            "-500 & -700" to "-1020",
            "500 ^ -700" to "-848",
            "(%\$#) | 500 | (%\$#)" to "SyntaxError",
            "(%\$#) & -500 & (%\$#)" to "SyntaxError",
            "(%\$#) ^ 500 ^ (%\$#)" to "SyntaxError",
            "(%\$# | 500 | (%\$#" to "SyntaxError",
            "(%\$# & -500 & (%\$#" to "SyntaxError",
            "(%\$# ^ 500 ^ (%\$#" to "SyntaxError",
            "(400) | (500) ^ (%\$#) & (%\$#" to "SyntaxError",
            "(%\$#) & (-500 & (%\$#" to "SyntaxError",
            "(%\$#) ^ (500 ^ (%\$#" to "SyntaxError",
            "numobj(-80808080) & numobj(-80808080)" to "OperatorError: bad news",
            "numobj(-80808080) | numobj(-80808080)" to "OperatorError: bad news",
            "numobj(-80808080) ^ numobj(-80808080)" to "OperatorError: bad news",
            "(1 && 2}" to "SyntaxError",
            "1 != 2" to "true",
            "1 ! 2" to "SyntaxError",
            "1 >= 2" to "false",
            "1 == 2" to "false",
            "1 = 2" to "SyntaxError",
            "1 == " to "SyntaxError",
            " == 1" to "SyntaxError",
            "\"Example emoji, KO: \\ud83d\\udd13, \\ud83c\\udfc3 OK: \\u2764\\ufe0f \"" to
                "Example emoji, KO: \ud83d\udd13, \ud83c\udfc3 OK: \u2764\ufe0f ",
            "\"Example emoji, KO: \\u{d83d}\\udd13, \\ud83c\\udfc3 OK: \\u2764\\ufe0f \"" to
                "Example emoji, KO: \ud83d\udd13, \ud83c\udfc3 OK: \u2764\ufe0f ",
            "\"Example emoji, KO: \\u{d83d}\\u{dd13}, \\ud83c\\udfc3 OK: \\u2764\\ufe0f \"" to
                "Example emoji, KO: \ud83d\udd13, \ud83c\udfc3 OK: \u2764\ufe0f ",
            "\"Example emoji, KO: \\u{d83d}\\u{dd13}, \\u{d83c}\\udfc3 OK: \\u2764\\ufe0f \"" to
                "Example emoji, KO: \ud83d\udd13, \ud83c\udfc3 OK: \u2764\ufe0f ",
            "\"Example emoji, KO: \\u{d83d}\\u{dd13}, \\u{d83c}\\u{dfc3} OK: \\u2764\\ufe0f \"" to
                "Example emoji, KO: \ud83d\udd13, \ud83c\udfc3 OK: \u2764\ufe0f ",
            "\"Example emoji, KO: \\u{d83d}\\u{dd13}, \\u{d83c}\\u{dfc3} OK: \\u{2764}\\ufe0f \"" to
                "Example emoji, KO: \ud83d\udd13, \ud83c\udfc3 OK: \u2764\ufe0f ",
            "\"Example emoji, KO: \\u{d83d}\\u{dd13}, \\u{d83c}\\u{dfc3} OK: \\u{2764}\\u{fe0f} \"" to
                "Example emoji, KO: \ud83d\udd13, \ud83c\udfc3 OK: \u2764\ufe0f ",
            "\"KO: \\xffsd\"" to "KO: \u00ffsd",
            "\"KO: \\ud8\"" to "SyntaxError",
            "\"KO: \\zd8\"" to "KO: zd8",
            "\"KO: \u0000\"" to "SyntaxError",
            "false == true" to "false",
            "false + true" to "1",
            "false - true" to "-1",
            "NaN + 1" to "NaN",
            "NaN * 1" to "NaN",
            "0.24ab31 - 1" to "SyntaxError",
            "0 + {1}" to "SyntaxError",
            "0 + [1]" to "01",
            "hello + 2" to "ReferenceError: hello is not defined",
            "i64(\"-9223372036854775808\")" to "-9223372036854775808",
            "-9223372036854775808i64" to "-9223372036854775808",
            "i64(\"9223372036854775807\")" to "9223372036854775807",
            "9223372036854775807i64" to "9223372036854775807",
            "i64(\"-9223372036854775808\")" to "-9223372036854775808",
            "u64(\"18446744073709551615\") - u64(\"18446744073709551614\")" to "1",
            "18446744073709551615u64 - 18446744073709551614u64" to "1",
            "u64(\"18446744073709551614\") + u64(\"1\")" to "18446744073709551615",
            "i64(\"-9223372036854775808\") + i64(\"1\")" to "-9223372036854775807",
            "i64(\"9223372036854775807\") - i64(\"1\")" to "9223372036854775806",
            "i64(\"9223372036854775807\") - 1" to "9223372036854776000",
            "u64(\"9223372036854775807\") - 1" to "9223372036854776000",
            "u64(1) > 0" to "true",
            "u64(1) >= 0" to "true",
            "u64(0) >= 0" to "true",
            "i64(0) >= 0" to "true",
            "i64(0) >= 0" to "true",
            "i64(-1) >= 0" to "false",
            "i64(-1) >= i64(0)" to "false",
            "u64(1) >= u64(0)" to "true",
            "u64(1) > u64(0)" to "true",
            "\"1\" >= \"2\" " to "false",
            "\"2\" >= \"2\" " to "true",
            "\"2\" >= \"10\" " to "true",
            "\"1\" > \"2\" " to "false",
            "\"2\" > \"2\" " to "false",
            "\"2\" > \"10\" " to "true",
            "i64(2) > i64(10)" to "false",
            "i64(2) == i64(10)" to "false",
            "i64(10) == i64(10)" to "true",
            "u64(10) == u64(10)" to "true",
            "u64(2) == u64(10)" to "false",
            "\"2\" == \"2\"" to "true",
            "\"2\" == \"3\"" to "false",
            "\"2\" != \"2\"" to "false",
            "\"2\" != \"3\"" to "true",
            "i64(2) != i64(10)" to "true",
            "i64(2) != i64(2)" to "false",
            "u64(2) != u64(10)" to "true",
            "u64(2) != u64(2)" to "false",
            "true != false" to "true",
            "true != true" to "false",
            "true < false" to "false",
            "false < true" to "true",
            "true <= false" to "false",
            "false <= true" to "true",
            "\"2\" * \"4\"" to "8",
            "\"2\" + \"4\"" to "24",
            "i64(2) * i64(4)" to "8",
            "u64(2) * u64(4)" to "8",
            "i64(8) / i64(2)" to "4",
            "u64(8) / u64(2)" to "4",
            "2 <= 4" to "true",
            "4 <= 2" to "false",
            "i64(2) <= i64(4)" to "true",
            "i64(4) <= i64(2)" to "false",
            "u64(2) <= u64(4)" to "true",
            "u64(4) <= u64(2)" to "false",
            "\"2\" < \"2\"" to "false",
            "\"2\" < \"3\"" to "true",
            "\"10\" < \"2\"" to "true",
            "i64(2) < i64(2)" to "false",
            "i64(2) < i64(3)" to "true",
            "u64(2) < u64(2)" to "false",
            "u64(2) < u64(3)" to "true",
            "\"2\" <= \"1\"" to "false",
            "\"2\" <= \"2\"" to "true",
            "\"2\" <= \"3\"" to "true",
            "\"10\" <= \"2\"" to "true",
            "true && false" to "false",
            "true || false" to "true",
            "\"1\" || false" to "true",
            "1 || false" to "true",
            "0 || false" to "false",
            "100 + blank_err" to "ReferenceError: blank_err is not defined",
            "100 + custom_err" to "ReferenceError: hiya",
            "\"a \\u\\\"567\"" to "SyntaxError",
            "(hello) + (jello" to "ReferenceError: hello is not defined",
            "(1) + (jello" to "SyntaxError",
            "(1) && " to "SyntaxError",
            " && (1)" to "SyntaxError",
            "1 < (}2) < (1)" to "SyntaxError",
            "1 + - 2" to "-1",
            "1 +" to "SyntaxError",
            "-1 + 2" to "1",
            "/1" to "SyntaxError",
            "10 % 2" to "0",
            "10 % 3" to "1",
            "i64(10) % i64(3)" to "1",
            "u64(10) % u64(3)" to "1",
            "\"10\" % \"3\"" to "1",
            "(1 || (2 > 5)) && (4 < 5 || 5 < 4)" to "true",
            "true == !!true" to "true",
            "true == !!true == !false" to "true",
            "true == ! ! true == !false" to "true",
            "true == ! ! true == ! ( 1 == 2 ) " to "true",
            "cust(123)" to "123",
            "cust(1) + cust(4)" to "5",
            "cust(1) - cust(4)" to "-3",
            "cust(2) * cust(4)" to "8",
            "cust(2) / cust(4)" to "0.5",
            "cust(10) % cust(3)" to "1",
            "cust(10) < cust(3)" to "false",
            "cust(10) <= cust(3)" to "false",
            "cust(10) > cust(3)" to "true",
            "cust(10) >= cust(3)" to "true",
            "cust(10) == cust(3)" to "false",
            "cust(10) != cust(3)" to "true",
            "cust(10) && cust(0)" to "false",
            "cust(10) || cust(3)" to "true",
            "cust(10) || cust(3)" to "true",
            "-cust(999)" to "OperatorError: not this time",
            "cust(-90909090) + cust(-90909090)" to "undefined",
            "cust(-80808080) + cust(-80808080)" to "OperatorError: bad news",
            "0x1" to "1",
            "0xZ" to "SyntaxError",
            "Infinity" to "Infinity",
            "-Infinity" to "-Infinity",
            "0xFFFFFFFF" to "4294967295",
            "0xFFFFFFFF+1" to "4294967296",
            "0xFFFFFFFFFFFFFFFF" to "18446744073709552000",
            "0xFFFFFFFFFFFFFFFF+1" to "18446744073709552000",
            "true ? 1 : 2" to "1",
            "false ? 1 : 2" to "2",
            "false ? 1 : true ? 2 : 3" to "2",
            "false ? 1 : false ? 2 : 3" to "3",
            "5*2-10 ? 1 : (3*3-9 < 1 || 6+6-12 ? 8 : false) ? 2 : 3" to "2",
            "(false ? 1 : 2" to "SyntaxError",
            "(false) ? (0xTT) : (0xTT)" to "SyntaxError",
            "(true) ? (0xTT) : (0xTT)" to "SyntaxError",
            "(true) ? (0xTT) : (0xTT" to "SyntaxError",
            "(true) ? (0xTT) 123" to "SyntaxError",
            "(0xTT) ? (0xTT) : 123" to "SyntaxError",
            "1e+10 > 0 ? \"big\" : \"small\"" to "big",
            "undefined" to "undefined",
            "true ? () : ()" to "SyntaxError",
            "undefined + 10" to "NaN",
            "null" to "null",
            "null + 10" to "10",
            "undefined + undefined" to "NaN",
            "null + null" to "0",
            "null + undefined" to "NaN",
            "!undefined" to "true",
            "!!undefined" to "false",
            "!null" to "true",
            "!!null" to "false",
            "null??1" to "1",
            "null??0" to "0",
            "undefined??1+1" to "2",
            "undefined??0+1" to "1",
            "false??1+1" to "false",
            "true??1+1" to "true",
            "false??1+1" to "false",
            "true??1+1" to "true",
            "(false??1)+1" to "1",
            "(true??1)+1" to "2",
            "(cust(1)??cust(2))+1" to "2",
            "'hello \\'\"\"\\a\\xFF\\p world'" to "hello '\"\"a\u00ffp world",
            "'\\xFG'" to "SyntaxError",
            "\"\\u{A}\"" to "\u000a",
            "\"\\u{21}\"" to "!",
            "\"\\u{AFFF}\"" to "\uafff",
            "\"\\u{1f516}\"" to "\ud83d\udd16",
            "\"\\v\"" to "\u000b",
            "\"\\0\"" to "\u0000",
            "\"\\u{YY}\"" to "SyntaxError",
            "\"\\u{FF" to "SyntaxError",
            "1,2,3,4" to "4",
            "1=,2,3,4" to "SyntaxError",
            "1(,2,3,4" to "SyntaxError",
            "1,2,3,(4+)" to "SyntaxError",
            "6<7 , 2>5 , 5" to "5",
            "hello ?. world" to "ReferenceError: hello is not defined",
            "this?.that(\"1\",\"2\")" to "ReferenceError: this is not defined",
            "  != 100" to "SyntaxError",
            "  >= 100" to "SyntaxError",
            " (1) != (\"\\'1" to "SyntaxError",
            "1 != 2 > 1 != 1" to "true",
            "1 != 2 < 1 != 1" to "false",
            "1 != 1 < 2 != 1" to "true",
            "u64+\"hello\"" to "[Function: u64]hello",
            "0.123123i64" to "SyntaxError",
            "new" to "SyntaxError",
            "howdy.myfn1().myfn2(\"1\",2,\"3\") == 6" to "true",
            "howdy.myfn1.there" to "undefined",
            "howdy.myfn3.there" to "Uncaught TypeError: Cannot read properties of undefined (reading 'there')",
            "howdy.myfn3?.there" to "undefined",
            "howdy.myfn1#e" to "SyntaxError",
            "howdy.myfn1.#e" to "SyntaxError",
            "#howdy.myfn1.#e" to "SyntaxError",
            "howdy[\"do\"]" to "undefined",
            "howdy[9i8203]" to "SyntaxError",
            "howdy[\"did\"]" to "ReferenceError: fantastic",
            "howdy.myfn1(9999)" to "CallError: fantastic",
            "((0i64)%0i64)" to "NaN",
            "((0i64)/0i64)" to "NaN",
            "((0u64)%0u64)" to "NaN",
            "((0u64)/0u64)" to "NaN",
            "64" to "64",
            "u64" to "[Function: u64]",
            "i64" to "[Function: i64]",
            "1 == \"1\"" to "true",
            "1 === \"1\"" to "false",
            "1 !== \"1\"" to "true",
            "\"1\" === \"1\"" to "true",
            "\"1\" === \"2\"" to "false",
            "\"1\" !== \"2\"" to "true",
            "false !== true" to "true",
            "false !== ! true" to "false",
            "cust1 < cust2" to "OperatorError: too bad 1",
            "cust1 <= cust2" to "OperatorError: too bad 1",
            "cust1 == cust2" to "OperatorError: too bad 1",
            "cust1 > cust2" to "OperatorError: too bad 2",
            "cust1 >= cust2" to "OperatorError: too bad 2",
            "cust3 < cust2" to "false",
            "cust3 <= cust2" to "OperatorError: too bad 2",
            "cust2 > cust3" to "false",
            "cust2 >= cust3" to "OperatorError: too bad 2",
            "cust2 == cust3" to "OperatorError: too bad 2",
            "cust3 == cust2" to "OperatorError: too bad 2",
            "cust3 !== cust2" to "OperatorError: too bad 2",
            "cust3 != cust2" to "OperatorError: too bad 2",
            "true.hello == undefined" to "true",
            "true.hello == '11'" to "false",
            "true.hello == null" to "false",
            "null == null" to "true",
            "[1,2,(3,4,'a','b'),3,1==2,3.5+4.5]" to "1,2,b,3,false,8",
            "11*1" to "11",
            "11*2" to "22",
            "[11]*2" to "22",
            "[11,22]*2" to "NaN",
            "\"hello\" =~ \"he.*\"" to "true",
            "\"hello\" =~ \"hi.*\"" to "false",
            "\"abc123\" =~ \"\\\\d+\"" to "true",
            "\"abc\" =~ \"\\\\d+\"" to "false",
            "\"test\" =~ \"(?i)test\"" to "true",
            "\"test\" =~ \"[test\"" to "false",
            "\"test\" =~ \"[test\" || \"test\" == \"test\"" to "true",
        )

    private class Thing(
        val f: Double,
    ) : Inter,
        Uinter,
        Floater,
        Booler,
        Stringer {
        override fun int64() = f.toLong()

        override fun uint64() = f.toULong()

        override fun float64() = f

        override fun bool() = f != 0.0

        override fun string() = Conv.formatFloat(f)
    }

    private val testOptions =
        Context(
            extender =
                Extender.of(
                    ref = { info, _ ->
                        if (!info.chain) {
                            when (info.ident) {
                                "i64", "u64", "cust", "numobj" -> Value.function(info.ident)
                                "custom_err" -> throw Exception("hiya")
                                "howdy" -> Value.string("hiya")
                                "cust1", "cust2", "cust3" -> Value.obj(info.ident)
                                else -> Value.UNDEFINED
                            }
                        } else {
                            when (info.ident) {
                                "myfn1", "myfn2" -> Value.function(info.ident)
                                "did" -> throw Exception("fantastic")
                                else -> Value.UNDEFINED
                            }
                        }
                    },
                    call = { info, _ ->
                        when (info.ident) {
                            "i64" -> {
                                Value.int64(
                                    info.args
                                        .at(0)
                                        .toString()
                                        .toLongOrNull() ?: 0,
                                )
                            }

                            "u64" -> {
                                Value.uint64(
                                    info.args
                                        .at(0)
                                        .toString()
                                        .toULongOrNull() ?: 0u,
                                )
                            }

                            "cust" -> {
                                Value.obj(
                                    info.args
                                        .at(0)
                                        .toString()
                                        .toLong(),
                                )
                            }

                            "myfn1" -> {
                                if (info.args.at(0).toString() == "9999") throw Exception("fantastic")
                                info.value
                            }

                            "myfn2" -> {
                                var sum = 0.0
                                for (i in 0 until info.args.len()) sum += info.args.at(i).float64()
                                Value.float64(sum)
                            }

                            "numobj" -> {
                                Value.obj(info.args.at(0).float64())
                            }

                            else -> {
                                Value.UNDEFINED
                            }
                        }
                    },
                    op = { info, _ ->
                        val a = info.left
                        val b = info.right
                        if (a.number() == -90909090.0 || b.number() == -90909090.0) {
                            Value.UNDEFINED
                        } else if (a.number() == -80808080.0 || b.number() == -80808080.0) {
                            throw Exception("bad news")
                        } else {
                            when (info.op) {
                                Op.ADD -> {
                                    Value.number(a.number() + b.number())
                                }

                                Op.SUB -> {
                                    Value.number(a.number() - b.number())
                                }

                                Op.MUL -> {
                                    if (a.number() == 999.0 || b.number() == 999.0) throw Exception("not this time")
                                    Value.number(a.number() * b.number())
                                }

                                Op.DIV -> {
                                    Value.number(a.number() / b.number())
                                }

                                Op.MOD -> {
                                    Value.number(a.number() % b.number())
                                }

                                Op.EQ -> {
                                    when (a.toString()) {
                                        "cust1" -> throw Exception("too bad 1")
                                        "cust2", "cust3" -> throw Exception("too bad 2")
                                        else -> Value.bool(a.number() == b.number())
                                    }
                                }

                                Op.LT -> {
                                    when (a.toString()) {
                                        "cust1" -> {
                                            throw Exception("too bad 1")
                                        }

                                        "cust2" -> {
                                            throw Exception("too bad 2")
                                        }

                                        "cust3" -> {
                                            if (b.toString() == "cust2") Value.bool(false) else throw Exception("too bad 3")
                                        }

                                        else -> {
                                            Value.bool(a.number() < b.number())
                                        }
                                    }
                                }

                                Op.AND -> {
                                    Value.bool(a.bool() && b.bool())
                                }

                                Op.OR -> {
                                    Value.bool(a.bool() || b.bool())
                                }

                                Op.COAL -> {
                                    if (!a.bool()) b else a
                                }

                                Op.BIT_AND -> {
                                    Value.int64(a.int64() and b.int64())
                                }

                                Op.BIT_OR -> {
                                    Value.int64(a.int64() or b.int64())
                                }

                                Op.BIT_XOR -> {
                                    Value.int64(a.int64() xor b.int64())
                                }

                                Op.REGEX -> {
                                    try {
                                        Value.bool(Regex(b.toString()).containsMatchIn(a.toString()))
                                    } catch (_: Exception) {
                                        Value.bool(false)
                                    }
                                }

                                else -> {
                                    Value.UNDEFINED
                                }
                            }
                        }
                    },
                ),
        )

    private fun eval(
        expr: String,
        ctx: Context?,
    ): Value =
        try {
            Expr.eval(expr, ctx)
        } catch (e: Exception) {
            Value.string(e.message ?: "")
        }

    @Test
    fun evalTable() {
        for ((i, pair) in testTable.withIndex()) {
            val (expr, expect) = pair
            val got = eval(bs(expr), testOptions).toString()
            assertEquals(bs(expect), got, "$i: for '$expr'")
        }
    }

    @Test
    fun conversions() {
        assertFalse(eval("true && false", testOptions).bool())
        assertTrue(eval("true && true", testOptions).bool())
        assertEquals(1.0, eval("true && true", testOptions).float64())
        assertEquals(0.0, eval("true && false", testOptions).float64())
        assertEquals(1L, eval("true && true", testOptions).int64())
        assertEquals(0uL, eval("true && false", testOptions).uint64())
        assertEquals(123uL, eval("i64(123)", testOptions).uint64())
        assertEquals(-123L, eval("i64(-123)", testOptions).int64())
        assertEquals(123L, eval("u64(123)", testOptions).int64())
        assertEquals("ReferenceError: u64 is not defined", eval("u64(1)", null).toString())
        assertEquals(true, eval("true && true", testOptions).value())
        assertEquals(123.0, eval("123", testOptions).value())
        assertEquals("123", eval("\"123\"", testOptions).value())
        assertEquals(123L, eval("i64(123)", testOptions).value())
        assertEquals(123uL, eval("u64(123)", testOptions).value())
        assertEquals(1, Value.obj(1).value())
        assertNull(Value.UNDEFINED.value())
        assertEquals("??", Op.COAL.toString())
    }

    @Test
    fun customExtenders() {
        var sops = Context(extender = Extender.of(ref = { _, _ -> Value.obj("hello") }, op = { _, _ -> Value.UNDEFINED }))
        val e = assertFailsWith<ExprException> { Expr.eval("u64(1) + 1", sops) }
        assertEquals("Uncaught TypeError: u64 is not a function", e.message)

        sops =
            Context(
                extender =
                    Extender.of(ref = { _, _ -> Value.obj(Thing(999.0)) }, op = { info, _ ->
                        val a = info.left
                        val s = "[${a.int64()}:${a.uint64()}:${a.bool()}:$a:${String.format("%.0f", a.float64())}]"
                        Value.string(s + s)
                    }),
            )
        assertEquals("[999:999:true:999:999][999:999:true:999:999]", Expr.eval("abc + 1", sops).toString())
        sops = Context(extender = Extender.of())
        assertEquals("ReferenceError: abc is not defined", eval("abc + 1", sops).toString())
        sops = Context(extender = Extender.of(ref = { _, _ -> Value.obj(Thing(999.0)) }))
        assertEquals("undefined", eval("abc + 1", sops).toString())
    }

    @Test
    fun computedForEachEval() {
        val vals = ArrayList<Value>()
        val ctx =
            Context(
                userData = 1,
                extender =
                    Extender.of(ref = { info, _ ->
                        if (info.ident == "a") Value.function("a") else Value.UNDEFINED
                    }, call = { info, _ ->
                        for (i in 0 until info.args.len()) vals.add(info.args.at(i))
                        Value.UNDEFINED
                    }),
            )
        val v = Expr.eval("a(1,('a','b','c'),3,4)", ctx)
        assertTrue(v.isUndefined)
        assertEquals("[1, c, 3, 4]", vals.toString())
    }

    @Test
    fun evalForEach() {
        var vals = ArrayList<Long>()
        var res = Expr.evalForEach("1,2,3,4", { vals.add(it.int64()) }, null)
        assertEquals(4L, res.int64())
        assertEquals(listOf(1L, 2L, 3L, 4L), vals)

        vals = ArrayList()
        res =
            Expr.evalForEach("1,2,3,4", {
                vals.add(it.int64())
                if (vals.size == 3) throw ErrStop
            }, null)
        assertEquals(3L, res.int64())
        assertEquals(listOf(1L, 2L, 3L), vals)

        vals = ArrayList()
        res =
            Expr.evalForEach("1,2,3,4", {
                vals.add(it.int64())
                if (vals.size == 4) throw ErrStop
            }, null)
        assertEquals(4L, res.int64())
        assertEquals(listOf(1L, 2L, 3L, 4L), vals)

        vals = ArrayList()
        assertFailsWith<IllegalStateException> {
            Expr.evalForEach("1,2,3,4", {
                vals.add(it.int64())
                if (vals.size == 3) throw IllegalStateException("fail")
            }, null)
        }
        vals = ArrayList()
        assertFailsWith<IllegalStateException> {
            Expr.evalForEach("1,2,3,4", {
                vals.add(it.int64())
                if (vals.size == 4) throw IllegalStateException("fail")
            }, null)
        }
    }

    private fun testParseString(
        data: String,
        expect: String,
        expectOk: Boolean,
    ) {
        var r = Expr.parseString(data)
        if (r != null && r.second.length != data.length) r = null
        assertEquals(expectOk, r != null, data)
        assertEquals(expect, r?.first ?: "", data)
    }

    @Test
    fun parseString() {
        testParseString("", "", false)
        testParseString("\"", "", false)
        testParseString("\"\\", "", false)
        testParseString("\"1\"1", "", false)
        testParseString("``", "", true)
        testParseString("`1`", "1", true)
        testParseString("`2`", "2", true)
        testParseString("`2\"`", "2\"", true)
        testParseString("'2'", "2", true)
        testParseString("'2\\1'", "21", true)
        testParseString("'hh\"ii'", "hh\"ii", true)
        assertNull(Expr.readIdent(""))
    }

    @Test
    fun noDoOp() {
        assertFailsWith<ExprException> { Expr.eval("cust1 + 1", Context()) }
    }

    @Test
    fun typeOf() {
        assertEquals("number", Value.number(0.0).typeOf())
        assertEquals("boolean", Value.bool(false).typeOf())
        assertEquals("number", Value.int64(0).typeOf())
        assertEquals("number", Value.uint64(0u).typeOf())
        assertEquals("string", Value.string("").typeOf())
        assertEquals("function", Value.function("").typeOf())
        assertEquals("undefined", Value.UNDEFINED.typeOf())
        assertEquals("object", Value.NULL.typeOf())
        assertEquals("object", Value.obj("hello").typeOf())
    }

    @Test
    fun readme() {
        val self = HashMap<String, Value>()
        self["minX"] = Value.number(112.8192)
        self["minY"] = Value.number(33.4738)
        self["maxX"] = Value.number(113.9146)
        self["maxY"] = Value.number(34.3367)
        val ctx =
            Context(
                userData = self,
                extender =
                    Extender.of(ref = { info, c ->
                        @Suppress("UNCHECKED_CAST")
                        if (info.chain) {
                            (c.userData as Map<String, Value>)[info.ident] ?: Value.UNDEFINED
                        } else if (info.ident == "this") {
                            Value.obj(c.userData)
                        } else {
                            Value.UNDEFINED
                        }
                    }),
            )
        assertEquals(
            "113.36689999999999,33.905249999999995",
            Expr.eval("((this.minX + this.maxX) / 2) + \",\" + ((this.minY + this.maxY) / 2)", ctx).toString(),
        )
    }

    @Test
    fun caseInsensitive() {
        assertFalse(Expr.eval("\"Hello\" == \"hello\"", Context(noCase = false)).bool())
        assertTrue(Expr.eval("\"Hello\" == \"hello\"", Context(noCase = true)).bool())
        assertFalse(Expr.eval("\"Hello\" < \"hello\"", Context(noCase = true)).bool())
        assertFalse(Expr.eval("\"Hello\" > \"hello\"", Context(noCase = true)).bool())
        assertFalse(Expr.eval("\"hello\" > \"Hello\"", Context(noCase = true)).bool())
        assertFalse(Expr.eval("\"Jello\" == \"Hello\"", Context(noCase = true)).bool())
        assertFalse(Expr.eval("\"jello\" == \"hello\"", Context(noCase = true)).bool())
    }

    @Test
    fun numberFormatting() {
        assertEquals("0.0000001", Expr.eval("0.0000001").toString())
        assertEquals("100000000000000000000", Expr.eval("1e20").toString())
        assertEquals("-0", Expr.eval("-0").toString())
        assertEquals("SyntaxError", eval("1e400", null).toString())
        assertEquals("0.30000000000000004", Expr.eval("0.1 + 0.2").toString())
    }
}
