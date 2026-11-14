package app.phrasekeys

import android.content.Context

/** Все настройки клавиатуры. Любое изменение увеличивает [version], чтобы клавиатура перестроилась. */
object Prefs {
    private const val NAME = "phrasekeys"

    const val THEME = "theme"
    const val LAYOUT_IOS = "layout_ios"
    const val SUGGESTIONS = "suggestions"
    const val AUTOCORRECT = "autocorrect"
    const val SMART_PUNCT = "smart_punct"
    const val AUTOCAP = "autocap"
    const val HAPTICS = "haptics"
    const val PREVIEW = "preview"
    const val NUMBER_ROW = "number_row"
    const val HEIGHT = "height" // low | mid | high
    const val SHAPE = "shape" // theme | round | square

    const val PHOTO_FILE = "keyboard_bg.jpg"

    @Volatile
    var version = 0
        private set

    private fun sp(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun bool(context: Context, key: String, default: Boolean): Boolean =
        sp(context).getBoolean(key, default)

    fun str(context: Context, key: String, default: String): String =
        sp(context).getString(key, default) ?: default

    fun putBool(context: Context, key: String, value: Boolean) {
        sp(context).edit().putBoolean(key, value).apply()
        version++
    }

    fun putStr(context: Context, key: String, value: String) {
        sp(context).edit().putString(key, value).apply()
        version++
    }

    /** Сообщить, что что-то изменилось (например, заменили фото). */
    fun bump() {
        version++
    }
}
