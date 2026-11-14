package app.phrasekeys

import android.graphics.Color

/** Оформление клавиатуры: цвета, скругление, тень, прозрачность клавиш. */
class KeyboardTheme(
    val id: String,
    val name: String,
    val bg: Int,
    val bgEnd: Int,
    val key: Int,
    val keySpecial: Int,
    val text: Int,
    val hint: Int,
    val accent: Int,
    val accentText: Int,
    val radiusDp: Int,
    val shadow: Boolean,
    val keyAlpha: Int = 255,
    val photo: Boolean = false
) {
    val gradient: Boolean get() = bg != bgEnd
}

object Themes {

    private fun c(hex: String): Int = Color.parseColor(hex)

    private fun solid(
        id: String, name: String, bg: String, key: String, special: String,
        text: String, hint: String, accent: String, accentText: String,
        radius: Int, shadow: Boolean
    ) = KeyboardTheme(
        id, name, c(bg), c(bg), c(key), c(special), c(text), c(hint),
        c(accent), c(accentText), radius, shadow
    )

    private fun gradient(
        id: String, name: String, top: String, bottom: String, key: String, special: String,
        text: String, hint: String, accent: String, accentText: String,
        radius: Int, keyAlpha: Int
    ) = KeyboardTheme(
        id, name, c(top), c(bottom), c(key), c(special), c(text), c(hint),
        c(accent), c(accentText), radius, false, keyAlpha
    )

    val all: List<KeyboardTheme> = listOf(
        solid(
            "ios_light", "iPhone светлая", "#D1D3D9", "#FFFFFF", "#ABB0BA",
            "#000000", "#6E727B", "#007AFF", "#FFFFFF", 6, true
        ),
        solid(
            "ios_dark", "iPhone тёмная", "#2A2A2C", "#636366", "#424245",
            "#FFFFFF", "#AEAEB2", "#0A84FF", "#FFFFFF", 6, true
        ),
        solid(
            "material_light", "Android светлая", "#E8EAED", "#FFFFFF", "#DADCE0",
            "#202124", "#5F6368", "#1A73E8", "#FFFFFF", 10, false
        ),
        solid(
            "material_dark", "Android тёмная", "#202124", "#3C4043", "#2D2E31",
            "#E8EAED", "#9AA0A6", "#8AB4F8", "#202124", 10, false
        ),
        solid(
            "amoled", "AMOLED чёрная", "#000000", "#1C1C1E", "#0F0F10",
            "#FFFFFF", "#8E8E93", "#30D158", "#000000", 8, false
        ),
        solid(
            "green", "Зелёная", "#0E3B2C", "#1E6B4E", "#14523A",
            "#FFFFFF", "#A8D5C2", "#D9B44A", "#1B1B1B", 8, true
        ),
        solid(
            "sand", "Песочная", "#E9DEC6", "#FFF9EA", "#D6C6A0",
            "#3B2F1E", "#7A6A4C", "#B8860B", "#FFFFFF", 8, true
        ),
        solid(
            "pink", "Розовая", "#F7D6E2", "#FFFFFF", "#EFB3CA",
            "#4A2332", "#8A5A6E", "#E91E63", "#FFFFFF", 10, true
        ),
        solid(
            "mint", "Мятная", "#D4F1E8", "#FFFFFF", "#A9DCCB",
            "#12362B", "#4F7F6F", "#12A37F", "#FFFFFF", 9, true
        ),
        gradient(
            "sunset", "Закат", "#F7797D", "#FBD786", "#FFFFFF", "#FFE3D1",
            "#3B1F14", "#7A4A38", "#D6336C", "#FFFFFF", 10, 215
        ),
        gradient(
            "ocean", "Океан", "#2193B0", "#6DD5ED", "#FFFFFF", "#CFEFF7",
            "#0B2E3B", "#36707F", "#0D47A1", "#FFFFFF", 10, 220
        ),
        gradient(
            "night", "Ночное небо", "#0F2027", "#2C5364", "#FFFFFF", "#000000",
            "#FFFFFF", "#B0C4CE", "#4FC3F7", "#00263A", 10, 46
        ),
        gradient(
            "purple", "Фиолетовая", "#7F00FF", "#E100FF", "#FFFFFF", "#E9D5FF",
            "#2B0A4A", "#6D4C8F", "#5B21B6", "#FFFFFF", 10, 225
        ),
        KeyboardTheme(
            "photo", "Своё фото", c("#333333"), c("#333333"), c("#FFFFFF"), c("#D0D0D0"),
            c("#111111"), c("#444444"), c("#007AFF"), c("#FFFFFF"), 8, false, 205, true
        )
    )

    fun byId(id: String): KeyboardTheme = all.firstOrNull { it.id == id } ?: all[0]

    /** Следующая тема по кругу; «Своё фото» пропускается, если фото не выбрано. */
    fun next(currentId: String, hasPhoto: Boolean): KeyboardTheme {
        val list = if (hasPhoto) all else all.filter { !it.photo }
        val index = list.indexOfFirst { it.id == currentId }
        return list[(index + 1) % list.size]
    }
}
