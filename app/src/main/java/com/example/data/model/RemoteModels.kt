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
 * Maps tapping a key directly to Android KeyEvent keycodes (+ Shift) so TV IMEs handle input
 * without phone virtual-keyboard Hangul composition interference.
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
        val str = ch.toString()
        val match = allSpecs.firstOrNull { spec ->
            when (language) {
                TvKeyboardLanguage.ENGLISH ->
                    spec.englishLabel == str || spec.englishShiftLabel == str
                TvKeyboardLanguage.KOREAN ->
                    spec.koreanLabel == str || spec.koreanShiftLabel == str
            }
        } ?: return when (ch) {
            ' ' -> PhysicalKeyAction(TvKeyCodes.SPACE)
            '-' -> PhysicalKeyAction(TvKeyCodes.MINUS)
            '/' -> PhysicalKeyAction(TvKeyCodes.SLASH)
            '@' -> PhysicalKeyAction(TvKeyCodes.AT)
            else -> null
        }

        val needsShift = when (language) {
            TvKeyboardLanguage.ENGLISH -> match.supportsShift && match.englishShiftLabel == str && match.englishLabel != str
            TvKeyboardLanguage.KOREAN -> match.supportsShift && match.koreanShiftLabel == str && match.koreanLabel != str
        }
        return resolveKeyAction(imeType, language, match, needsShift)
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
