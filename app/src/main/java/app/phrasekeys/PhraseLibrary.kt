package app.phrasekeys

import android.content.Context

data class PhraseCategory(
    val title: String,
    val phrases: List<String>,
    val emoji: Boolean = false,
    /** KIND_PHRASES — обычные; KIND_DATA — «Мои данные»; KIND_FREQUENT — выученное. */
    val kind: Int = 0,
    /** Что вставлять по нажатию (если отличается от подписи на кнопке). */
    val values: List<String>? = null
)

const val KIND_PHRASES = 0
const val KIND_DATA = 1
const val KIND_FREQUENT = 2
const val KIND_CLIPBOARD = 3

/**
 * Встроенные фразы + «Мои фразы» пользователя.
 * Чтобы поменять встроенный набор, правьте список [builtIn] ниже.
 */
object PhraseLibrary {

    private const val PREFS = "phrasekeys"
    private const val KEY_CUSTOM = "custom_phrases"

    val builtIn: List<PhraseCategory> = listOf(
        PhraseCategory(
            "Приветствия",
            listOf(
                "Ассаламу алейкум",
                "Ассаламу алейкум ва рахматуллахи ва баракатух",
                "Ва алейкум ассалам",
                "Ва алейкум ассалам ва рахматуллахи ва баракатух",
                "Ассаламу алейкум, брат",
                "Ва алейкум ассалам, брат",
                "Ассаламу алейкум, сестра",
                "Ва алейкум ассалам, сестра",
            )
        ),
        PhraseCategory(
            "Благодарность",
            listOf(
                "Барака Аллаху фика",
                "Барака Аллаху фики",
                "Баракаллаху фикум",
                "Джазак Аллаху хайран",
                "Ва ийяк",
                "Афван",
            )
        ),
        PhraseCategory(
            "Зикр и дуа",
            listOf(
                "Бисмиллях",
                "Альхамдулиллях",
                "Ма ша Аллах",
                "Ин ша Аллах",
                "Субханаллах",
                "Аллаху акбар",
                "Астагфирулла",
                "Ля иляха илляллах",
                "Ля хауля ва ля куввата илля биллях",
                "Аллаху аълям",
                "Амин",
            )
        ),
        PhraseCategory(
            "Пожелания",
            listOf(
                "Да воздаст тебе Аллах благом",
                "Да примет Аллах твои деяния",
                "Да облегчит Аллах",
                "Шафакаллах",
                "Да убережёт тебя Аллах",
                "Да даст тебе Аллах терпения",
            )
        ),
        PhraseCategory(
            "Праздники",
            listOf(
                "Джума мубарак",
                "Ид мубарак",
                "Рамадан мубарак",
                "Такаббаля Ллаху минна ва минкум",
            )
        ),
        PhraseCategory(
            "Прощание",
            listOf(
                "Фи аманиллях",
                "До встречи, ин ша Аллах",
                "Увидимся, ин ша Аллах",
                "Спокойной ночи, да хранит тебя Аллах",
                "Хорошего дня",
            )
        ),
        PhraseCategory(
            "Обычные",
            listOf(
                "Привет",
                "Как дела?",
                "Хорошо, спасибо",
                "Спасибо большое",
                "Пожалуйста",
                "Скоро буду",
                "Позвоню позже",
                "Сейчас занят, отвечу позже",
                "Хорошо, договорились",
                "Понял, принял",
                "Извини, пожалуйста",
            )
        ),
    )

    /** Смайлы: вставляются без пробела после. */
    val emojiCategories: List<PhraseCategory> = listOf(
        PhraseCategory(
            "😊",
            listOf(
                "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇",
                "🙂", "😉", "😍", "🥰", "😘", "😋", "😎", "🤗", "🤔", "😐",
                "😏", "😴", "😢", "😭", "😡", "😱", "🙄", "😬", "🥺", "😌",
            ),
            emoji = true
        ),
        PhraseCategory(
            "👍",
            listOf(
                "👍", "👎", "👌", "✌", "🤝", "🙏", "👏", "🙌", "💪", "👋",
                "🤲", "☝", "👆", "👇", "👉", "👈", "✋", "🤞",
            ),
            emoji = true
        ),
        PhraseCategory(
            "❤",
            listOf(
                "❤", "💙", "💚", "💛", "🧡", "💜", "🖤", "🤍", "💔", "💕",
                "💖", "✨", "🔥", "⭐", "🌟", "💯", "✅", "❌", "⚡", "🎉", "🎁",
            ),
            emoji = true
        ),
        PhraseCategory(
            "🕌",
            listOf(
                "🕌", "🕋", "🤲", "📿", "🌙", "☪", "🌹", "🌿", "🕊", "📖",
                "🤍", "💚", "🙏", "✨", "🌴", "⭐",
            ),
            emoji = true
        ),
    )

    fun loadCustomText(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CUSTOM, "") ?: ""

    fun saveCustomText(context: Context, text: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM, text)
            .apply()
    }

    private fun loadCustom(context: Context): List<String> =
        loadCustomText(context).lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** Список категорий для панели: «Мои» (если есть) + встроенные + смайлы. */
    fun categories(context: Context): List<PhraseCategory> {
        val custom = loadCustom(context)
        val base = if (custom.isEmpty()) builtIn else listOf(PhraseCategory("Мои", custom)) + builtIn
        val personal = Memory.personal(context)
        val data = PhraseCategory(
            "Данные",
            personal.map { if (it.label.isEmpty()) it.value else "${it.label}: ${it.value}" },
            kind = KIND_DATA,
            values = personal.map { it.value }
        )
        val frequent = Memory.frequent(context)
        val freq = PhraseCategory("Частые", frequent, kind = KIND_FREQUENT, values = frequent)
        val clips = ClipStore.history()
        val clipboard = PhraseCategory(
            "Буфер",
            clips.map { it.replace('\n', ' ').take(70) },
            kind = KIND_CLIPBOARD,
            values = clips
        )
        return base + clipboard + data + freq + emojiCategories
    }
}
