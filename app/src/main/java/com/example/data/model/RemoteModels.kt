package com.example.data.model

data class DiscoveredTv(
    val serviceName: String,
    val host: String,
    val port: Int = 6466,
    val pairingPort: Int = 6467,
    val isSaved: Boolean = false
)

sealed class TvConnectionState {
    data object Disconnected : TvConnectionState()
    data class Connecting(val tvName: String, val host: String) : TvConnectionState()
    data class PairingRequired(
        val tvName: String,
        val host: String,
        val pairingPort: Int,
        val remotePort: Int,
        val errorMessage: String? = null
    ) : TvConnectionState()
    data class Connected(
        val tvName: String,
        val host: String,
        val remotePort: Int,
        val currentAppPackage: String? = null,
        val currentVolume: Int? = null,
        val maxVolume: Int? = null,
        val isMuted: Boolean = false
    ) : TvConnectionState()
    data class Error(val message: String, val host: String? = null) : TvConnectionState()
}

data class TvImePromptState(
    val isTvImeVisible: Boolean = false,
    val currentText: String = "",
    val fieldCounter: Int = 0,
    val imeCounter: Int = 0
)

enum class TvKeyboardLanguage {
    ENGLISH,
    KOREAN
}

enum class TvImeType(val displayName: String) {
    GBOARD("Gboard"),
    GOOGLE_KOREAN("Google 한국어"),
    LEANBACK("Android TV 키보드"),
    SAMSUNG("삼성 키보드"),
    XIAOMI("샤오미 키보드"),
    STANDARD("TV 기본 키보드")
}

data class TvImeStatus(
    val language: TvKeyboardLanguage = TvKeyboardLanguage.ENGLISH,
    val imeId: String? = null,
    val imeType: TvImeType = TvImeType.STANDARD,
    val subtypeLocale: String? = null,
    val isDetected: Boolean = false
)

data class PhysicalKeyAction(
    val keyCode: Int,
    val shift: Boolean = false,
    val ctrl: Boolean = false
)

data class RealTimeTextEditResult(
    val backspaceCount: Int = 0,
    val keyActions: List<PhysicalKeyAction> = emptyList()
)

data class PhysicalKeySpec(
    val id: String,
    val englishLabel: String,
    val englishShiftLabel: String,
    val koreanLabel: String,
    val koreanShiftLabel: String,
    val keyCode: Int,
    val supportsShift: Boolean = true
) {
    fun labelFor(language: TvKeyboardLanguage, shifted: Boolean): String = when (language) {
        TvKeyboardLanguage.ENGLISH -> if (shifted) englishShiftLabel else englishLabel
        TvKeyboardLanguage.KOREAN -> if (shifted) koreanShiftLabel else koreanLabel
    }
}

/**
 * Per-TV-IME physical keycode mapping for English QWERTY and Korean 2-bulsik (두벌식) layouts.
 * Decomposes virtual-keyboard Hangul composition transitions and direct ASCII keystrokes into
 * immediate Android KeyEvent actions (+ Shift) without custom UI keyboard clutter.
 */
object TvImeKeycodeMap {

    val numberRow: List<PhysicalKeySpec> = listOf(
        numSpec("1", TvKeyCodes.KEYCODE_1),
        numSpec("2", TvKeyCodes.KEYCODE_2),
        numSpec("3", TvKeyCodes.KEYCODE_3),
        numSpec("4", TvKeyCodes.KEYCODE_4),
        numSpec("5", TvKeyCodes.KEYCODE_5),
        numSpec("6", TvKeyCodes.KEYCODE_6),
        numSpec("7", TvKeyCodes.KEYCODE_7),
        numSpec("8", TvKeyCodes.KEYCODE_8),
        numSpec("9", TvKeyCodes.KEYCODE_9),
        numSpec("0", TvKeyCodes.KEYCODE_0)
    )

    val topRow: List<PhysicalKeySpec> = listOf(
        alphaSpec("q", "q", "Q", "ㅂ", "ㅃ", TvKeyCodes.KEYCODE_Q),
        alphaSpec("w", "w", "W", "ㅈ", "ㅉ", TvKeyCodes.KEYCODE_W),
        alphaSpec("e", "e", "E", "ㄷ", "ㄸ", TvKeyCodes.KEYCODE_E),
        alphaSpec("r", "r", "R", "ㄱ", "ㄲ", TvKeyCodes.KEYCODE_R),
        alphaSpec("t", "t", "T", "ㅅ", "ㅆ", TvKeyCodes.KEYCODE_T),
        alphaSpec("y", "y", "Y", "ㅛ", "ㅛ", TvKeyCodes.KEYCODE_Y),
        alphaSpec("u", "u", "U", "ㅕ", "ㅕ", TvKeyCodes.KEYCODE_U),
        alphaSpec("i", "i", "I", "ㅑ", "ㅑ", TvKeyCodes.KEYCODE_I),
        alphaSpec("o", "o", "O", "ㅐ", "ㅒ", TvKeyCodes.KEYCODE_O),
        alphaSpec("p", "p", "P", "ㅔ", "ㅖ", TvKeyCodes.KEYCODE_P)
    )

    val homeRow: List<PhysicalKeySpec> = listOf(
        alphaSpec("a", "a", "A", "ㅁ", "ㅁ", TvKeyCodes.KEYCODE_A),
        alphaSpec("s", "s", "S", "ㄴ", "ㄴ", TvKeyCodes.KEYCODE_S),
        alphaSpec("d", "d", "D", "ㅇ", "ㅇ", TvKeyCodes.KEYCODE_D),
        alphaSpec("f", "f", "F", "ㄹ", "ㄹ", TvKeyCodes.KEYCODE_F),
        alphaSpec("g", "g", "G", "ㅎ", "ㅎ", TvKeyCodes.KEYCODE_G),
        alphaSpec("h", "h", "H", "ㅗ", "ㅗ", TvKeyCodes.KEYCODE_H),
        alphaSpec("j", "j", "J", "ㅓ", "ㅓ", TvKeyCodes.KEYCODE_J),
        alphaSpec("k", "k", "K", "ㅏ", "ㅏ", TvKeyCodes.KEYCODE_K),
        alphaSpec("l", "l", "L", "ㅣ", "ㅣ", TvKeyCodes.KEYCODE_L)
    )

    val bottomRow: List<PhysicalKeySpec> = listOf(
        alphaSpec("z", "z", "Z", "ㅋ", "ㅋ", TvKeyCodes.KEYCODE_Z),
        alphaSpec("x", "x", "X", "ㅌ", "ㅌ", TvKeyCodes.KEYCODE_X),
        alphaSpec("c", "c", "C", "ㅊ", "ㅊ", TvKeyCodes.KEYCODE_C),
        alphaSpec("v", "v", "V", "ㅍ", "ㅍ", TvKeyCodes.KEYCODE_V),
        alphaSpec("b", "b", "B", "ㅠ", "ㅠ", TvKeyCodes.KEYCODE_B),
        alphaSpec("n", "n", "N", "ㅜ", "ㅜ", TvKeyCodes.KEYCODE_N),
        alphaSpec("m", "m", "M", "ㅡ", "ㅡ", TvKeyCodes.KEYCODE_M)
    )

    val commaSpec = PhysicalKeySpec(
        id = "comma",
        englishLabel = ",",
        englishShiftLabel = ",",
        koreanLabel = ",",
        koreanShiftLabel = ",",
        keyCode = TvKeyCodes.COMMA,
        supportsShift = false
    )

    val periodSpec = PhysicalKeySpec(
        id = "period",
        englishLabel = ".",
        englishShiftLabel = ".",
        koreanLabel = ".",
        koreanShiftLabel = ".",
        keyCode = TvKeyCodes.PERIOD,
        supportsShift = false
    )

    val allSpecs: List<PhysicalKeySpec> = numberRow + topRow + homeRow + bottomRow + listOf(commaSpec, periodSpec)

    private val CHOSEONG_KEYS = arrayOf(
        "ㄱ", "ㄲ", "ㄴ", "ㄷ", "ㄸ", "ㄹ", "ㅁ", "ㅂ", "ㅃ", "ㅅ",
        "ㅆ", "ㅇ", "ㅈ", "ㅉ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"
    )

    private val JUNGSEONG_KEYS: Array<List<String>> = arrayOf(
        listOf("ㅏ"), listOf("ㅐ"), listOf("ㅑ"), listOf("ㅒ"), listOf("ㅓ"),
        listOf("ㅔ"), listOf("ㅕ"), listOf("ㅖ"), listOf("ㅗ"),
        listOf("ㅗ", "ㅏ"), listOf("ㅗ", "ㅐ"), listOf("ㅗ", "ㅣ"),
        listOf("ㅛ"), listOf("ㅜ"),
        listOf("ㅜ", "ㅓ"), listOf("ㅜ", "ㅔ"), listOf("ㅜ", "ㅣ"),
        listOf("ㅠ"), listOf("ㅡ"), listOf("ㅡ", "ㅣ"), listOf("ㅣ")
    )

    private val JONGSEONG_KEYS: Array<List<String>> = arrayOf(
        emptyList(),
        listOf("ㄱ"), listOf("ㄲ"), listOf("ㄱ", "ㅅ"), listOf("ㄴ"),
        listOf("ㄴ", "ㅈ"), listOf("ㄴ", "ㅎ"), listOf("ㄷ"), listOf("ㄹ"),
        listOf("ㄹ", "ㄱ"), listOf("ㄹ", "ㅁ"), listOf("ㄹ", "ㅂ"), listOf("ㄹ", "ㅅ"),
        listOf("ㄹ", "ㅌ"), listOf("ㄹ", "ㅍ"), listOf("ㄹ", "ㅎ"), listOf("ㅁ"),
        listOf("ㅂ"), listOf("ㅂ", "ㅅ"), listOf("ㅅ"), listOf("ㅆ"),
        listOf("ㅇ"), listOf("ㅈ"), listOf("ㅊ"), listOf("ㅋ"),
        listOf("ㅌ"), listOf("ㅍ"), listOf("ㅎ")
    )

    private val COMPOUND_COMPAT_JAMO = mapOf(
        '\u3133' to listOf("ㄱ", "ㅅ"), // ㄳ
        '\u3135' to listOf("ㄴ", "ㅈ"), // ㄵ
        '\u3136' to listOf("ㄴ", "ㅎ"), // ㄶ
        '\u313A' to listOf("ㄹ", "ㄱ"), // ㄺ
        '\u313B' to listOf("ㄹ", "ㅁ"), // ㄻ
        '\u313C' to listOf("ㄹ", "ㅂ"), // ㄼ
        '\u313D' to listOf("ㄹ", "ㅅ"), // ㄽ
        '\u313E' to listOf("ㄹ", "ㅌ"), // ㄾ
        '\u313F' to listOf("ㄹ", "ㅍ"), // ㄿ
        '\u3140' to listOf("ㄹ", "ㅎ"), // ㅀ
        '\u3144' to listOf("ㅂ", "ㅅ"), // ㅄ
        '\u3158' to listOf("ㅗ", "ㅏ"), // ㅘ
        '\u3159' to listOf("ㅗ", "ㅐ"), // ㅙ
        '\u315A' to listOf("ㅗ", "ㅣ"), // ㅚ
        '\u315D' to listOf("ㅜ", "ㅓ"), // ㅝ
        '\u315E' to listOf("ㅜ", "ㅔ"), // ㅞ
        '\u315F' to listOf("ㅜ", "ㅣ"), // ㅟ
        '\u3162' to listOf("ㅡ", "ㅣ")  // ㅢ
    )

    private val TOKEN_TO_ACTION: Map<String, PhysicalKeyAction> = buildMap {
        for (spec in allSpecs) {
            put(spec.englishLabel, PhysicalKeyAction(spec.keyCode, shift = false))
            if (spec.supportsShift && spec.englishShiftLabel != spec.englishLabel) {
                put(spec.englishShiftLabel, PhysicalKeyAction(spec.keyCode, shift = true))
            }
            if (spec.koreanLabel != spec.englishLabel) {
                put(spec.koreanLabel, PhysicalKeyAction(spec.keyCode, shift = false))
            }
            if (spec.supportsShift && spec.koreanShiftLabel != spec.koreanLabel) {
                put(spec.koreanShiftLabel, PhysicalKeyAction(spec.keyCode, shift = true))
            }
        }
        put(" ", PhysicalKeyAction(TvKeyCodes.SPACE))
        put("\n", PhysicalKeyAction(TvKeyCodes.ENTER))
        put("`", PhysicalKeyAction(TvKeyCodes.GRAVE))
        put("~", PhysicalKeyAction(TvKeyCodes.GRAVE, shift = true))
        put("!", PhysicalKeyAction(TvKeyCodes.KEYCODE_1, shift = true))
        put("@", PhysicalKeyAction(TvKeyCodes.AT))
        put("#", PhysicalKeyAction(TvKeyCodes.POUND))
        put("$", PhysicalKeyAction(TvKeyCodes.KEYCODE_4, shift = true))
        put("%", PhysicalKeyAction(TvKeyCodes.KEYCODE_5, shift = true))
        put("^", PhysicalKeyAction(TvKeyCodes.KEYCODE_6, shift = true))
        put("&", PhysicalKeyAction(TvKeyCodes.KEYCODE_7, shift = true))
        put("*", PhysicalKeyAction(TvKeyCodes.STAR))
        put("(", PhysicalKeyAction(TvKeyCodes.KEYCODE_9, shift = true))
        put(")", PhysicalKeyAction(TvKeyCodes.KEYCODE_0, shift = true))
        put("-", PhysicalKeyAction(TvKeyCodes.MINUS))
        put("_", PhysicalKeyAction(TvKeyCodes.MINUS, shift = true))
        put("=", PhysicalKeyAction(TvKeyCodes.EQUALS))
        put("+", PhysicalKeyAction(TvKeyCodes.PLUS))
        put("[", PhysicalKeyAction(TvKeyCodes.LEFT_BRACKET))
        put("{", PhysicalKeyAction(TvKeyCodes.LEFT_BRACKET, shift = true))
        put("]", PhysicalKeyAction(TvKeyCodes.RIGHT_BRACKET))
        put("}", PhysicalKeyAction(TvKeyCodes.RIGHT_BRACKET, shift = true))
        put("\\", PhysicalKeyAction(TvKeyCodes.BACKSLASH))
        put("|", PhysicalKeyAction(TvKeyCodes.BACKSLASH, shift = true))
        put(";", PhysicalKeyAction(TvKeyCodes.SEMICOLON))
        put(":", PhysicalKeyAction(TvKeyCodes.SEMICOLON, shift = true))
        put("'", PhysicalKeyAction(TvKeyCodes.APOSTROPHE))
        put("\"", PhysicalKeyAction(TvKeyCodes.APOSTROPHE, shift = true))
        put("<", PhysicalKeyAction(TvKeyCodes.COMMA, shift = true))
        put(">", PhysicalKeyAction(TvKeyCodes.PERIOD, shift = true))
        put("/", PhysicalKeyAction(TvKeyCodes.SLASH))
        put("?", PhysicalKeyAction(TvKeyCodes.SLASH, shift = true))
    }

    fun resolveImeType(imeId: String?): TvImeType {
        val normalized = imeId.orEmpty().lowercase()
        return when {
            normalized.contains("inputmethod.korean") || normalized.contains("koreanime") ->
                TvImeType.GOOGLE_KOREAN
            normalized.contains("leanback") ->
                TvImeType.LEANBACK
            normalized.contains("inputmethod.latin") || normalized.contains("gboard") ->
                TvImeType.GBOARD
            normalized.contains("samsung") || normalized.contains("honeyboard") ->
                TvImeType.SAMSUNG
            normalized.contains("xiaomi") || normalized.contains("mitv") ->
                TvImeType.XIAOMI
            else -> TvImeType.STANDARD
        }
    }

    fun resolveLanguageSwitchAction(imeType: TvImeType): PhysicalKeyAction = when (imeType) {
        TvImeType.GOOGLE_KOREAN -> PhysicalKeyAction(TvKeyCodes.SPACE, shift = true)
        TvImeType.GBOARD,
        TvImeType.LEANBACK,
        TvImeType.SAMSUNG,
        TvImeType.XIAOMI,
        TvImeType.STANDARD -> PhysicalKeyAction(TvKeyCodes.LANGUAGE_SWITCH)
    }

    fun resolveKeyAction(
        imeType: TvImeType,
        language: TvKeyboardLanguage,
        spec: PhysicalKeySpec,
        shifted: Boolean
    ): PhysicalKeyAction {
        val useShift = when {
            !spec.supportsShift -> false
            language == TvKeyboardLanguage.KOREAN && shifted ->
                // Korean 2-bulsik only produces distinct jamo on Shift+Q/W/E/R/T/O/P
                spec.koreanShiftLabel != spec.koreanLabel
            else -> shifted
        }
        return PhysicalKeyAction(
            keyCode = spec.keyCode,
            shift = useShift
        )
    }

    fun findSpecById(id: String): PhysicalKeySpec? =
        allSpecs.firstOrNull { it.id.equals(id, ignoreCase = true) }

    fun resolveCharacterForIme(
        imeType: TvImeType,
        language: TvKeyboardLanguage,
        ch: Char
    ): PhysicalKeyAction? {
        return resolveTokenToPhysicalAction(imeType, ch.toString())
    }

    fun resolveTokenToPhysicalAction(
        imeType: TvImeType = TvImeType.STANDARD,
        token: String
    ): PhysicalKeyAction? {
        // Reserve room for IME-specific quirks if needed while keeping keycode mapping unified
        return TOKEN_TO_ACTION[token]
    }

    /**
     * Decomposes text (including precomposed Hangul syllables U+AC00..U+D7A3 and compound jamo)
     * into individual 2-bulsik keycap tokens in keystroke order.
     */
    fun decomposeTo2BulsikTokens(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val result = ArrayList<String>(text.length * 2)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val code = ch.code
            when {
                code in 0xAC00..0xD7A3 -> {
                    val syllable = code - 0xAC00
                    val cho = syllable / (21 * 28)
                    val jung = (syllable % (21 * 28)) / 28
                    val jong = syllable % 28
                    result.add(CHOSEONG_KEYS[cho])
                    result.addAll(JUNGSEONG_KEYS[jung])
                    if (jong > 0) {
                        result.addAll(JONGSEONG_KEYS[jong])
                    }
                    i++
                }
                ch in COMPOUND_COMPAT_JAMO -> {
                    result.addAll(COMPOUND_COMPAT_JAMO.getValue(ch))
                    i++
                }
                code in 0x1100..0x1112 -> {
                    result.add(CHOSEONG_KEYS[code - 0x1100])
                    i++
                }
                code in 0x1161..0x1175 -> {
                    result.addAll(JUNGSEONG_KEYS[code - 0x1161])
                    i++
                }
                code in 0x11A8..0x11C2 -> {
                    result.addAll(JONGSEONG_KEYS[code - 0x11A7])
                    i++
                }
                else -> {
                    val cp = Character.codePointAt(text, i)
                    val charCount = Character.charCount(cp)
                    result.add(text.substring(i, i + charCount))
                    i += charCount
                }
            }
        }
        return result
    }

    /**
     * Computes real-time Backspace count + physical KeyEvent actions for any phone keyboard edit
     * (English QWERTY keystrokes, Korean 2-bulsik IME syllable composition & Backspace, Space, numbers).
     */
    fun computeRealTimeEditActions(
        previousText: String,
        previousSelStart: Int,
        previousSelEnd: Int,
        nextText: String,
        imeType: TvImeType = TvImeType.STANDARD
    ): RealTimeTextEditResult {
        if (previousText == nextText) {
            return RealTimeTextEditResult()
        }

        var prefixLen = previousText.commonPrefixWith(nextText).length
        if (prefixLen > 0 && prefixLen < previousText.length && Character.isLowSurrogate(previousText[prefixLen])) {
            prefixLen--
        }

        val prevRem = previousText.substring(prefixLen)
        val nextRem = nextText.substring(prefixLen)
        var suffixLen = prevRem.commonSuffixWith(nextRem).length
        if (suffixLen > 0 && suffixLen < prevRem.length) {
            val prevCut = previousText.length - suffixLen
            val nextCut = nextText.length - suffixLen
            if (Character.isLowSurrogate(previousText[prevCut]) ||
                (nextCut in 1 until nextText.length && Character.isLowSurrogate(nextText[nextCut]))
            ) {
                suffixLen--
            }
        }

        val prevMid = previousText.substring(prefixLen, previousText.length - suffixLen)
        val nextMid = nextText.substring(prefixLen, nextText.length - suffixLen)

        if (prevMid.isEmpty() && nextMid.isEmpty()) {
            return RealTimeTextEditResult()
        }

        // Pure deletion (Backspace on English/committed Hangul, or selection deletion)
        if (nextMid.isEmpty()) {
            val delCount = Character.codePointCount(prevMid, 0, prevMid.length).coerceAtLeast(1)
            return RealTimeTextEditResult(backspaceCount = delCount)
        }

        // Pure insertion (typing new ASCII/Hangul/digits/space)
        if (prevMid.isEmpty()) {
            val actions = decomposeTo2BulsikTokens(nextMid)
                .mapNotNull { token -> resolveTokenToPhysicalAction(imeType, token) }
            return RealTimeTextEditResult(keyActions = actions)
        }

        // Hangul syllable composition / jamo Backspace / autocorrect transition inside prevMid -> nextMid
        val prevTokens = decomposeTo2BulsikTokens(prevMid)
        val nextTokens = decomposeTo2BulsikTokens(nextMid)

        var tokenPrefix = 0
        val minSize = minOf(prevTokens.size, nextTokens.size)
        while (tokenPrefix < minSize && prevTokens[tokenPrefix] == nextTokens[tokenPrefix]) {
            tokenPrefix++
        }

        var tokenSuffix = 0
        while (tokenSuffix < minSize - tokenPrefix &&
            prevTokens[prevTokens.size - 1 - tokenSuffix] == nextTokens[nextTokens.size - 1 - tokenSuffix]
        ) {
            tokenSuffix++
        }

        val prevTailCount = (prevTokens.size - tokenPrefix - tokenSuffix).coerceAtLeast(0)
        val nextTail = nextTokens.subList(tokenPrefix, nextTokens.size - tokenSuffix)

        val deleteCount = if (tokenPrefix == 0 && tokenSuffix == 0 && prevMid.length > 1) {
            // Bulk replacement over committed characters deletes character-by-character rather than decomposed jamo
            Character.codePointCount(prevMid, 0, prevMid.length)
        } else {
            prevTailCount
        }
        val actions = nextTail.mapNotNull { token -> resolveTokenToPhysicalAction(imeType, token) }

        return RealTimeTextEditResult(
            backspaceCount = deleteCount,
            keyActions = actions
        )
    }

    private fun numSpec(digit: String, keyCode: Int) = PhysicalKeySpec(
        id = digit,
        englishLabel = digit,
        englishShiftLabel = digit,
        koreanLabel = digit,
        koreanShiftLabel = digit,
        keyCode = keyCode,
        supportsShift = false
    )

    private fun alphaSpec(
        id: String,
        enLower: String,
        enUpper: String,
        koUnshifted: String,
        koShifted: String,
        keyCode: Int
    ) = PhysicalKeySpec(
        id = id,
        englishLabel = enLower,
        englishShiftLabel = enUpper,
        koreanLabel = koUnshifted,
        koreanShiftLabel = koShifted,
        keyCode = keyCode,
        supportsShift = true
    )
}
