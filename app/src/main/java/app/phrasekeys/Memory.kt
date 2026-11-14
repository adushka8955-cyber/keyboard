package app.phrasekeys

import android.content.Context

/** Личная запись: «Почта: ivan@mail.ru» -> label = «Почта», value = «ivan@mail.ru». */
class PersonalItem(val label: String, val value: String)

/**
 * Память клавиатуры:
 *  • «Мои данные» — почта, ФИО, телефон, даты и т.п., которые пользователь вписал сам;
 *  • выученное — фразы, почты и числа, которые человек часто отправляет.
 * Всё хранится только на телефоне, в закрытых настройках приложения.
 */
object Memory {

    private const val NAME = "phrasekeys_memory"
    private const val KEY_PERSONAL = "personal"
    private const val KEY_LEARNED = "learned"

    /** Сколько раз надо отправить, чтобы фраза начала предлагаться. */
    const val MIN_COUNT = 2
    private const val MAX_ITEMS = 300

    private val EMAIL = Regex("""[\p{L}\p{N}._%+-]+@[\p{L}\p{N}.-]+\.\p{L}{2,}""")
    private val SPACES = Regex("\\s+")
    private const val TRIM_CHARS = ".,;:!?()\"'«»"

    // текст -> сколько раз встретился; порядок = от старых к недавним
    private var learned: LinkedHashMap<String, Int>? = null

    private fun sp(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ мои данные

    fun personalText(context: Context): String = sp(context).getString(KEY_PERSONAL, "") ?: ""

    fun savePersonalText(context: Context, text: String) {
        sp(context).edit().putString(KEY_PERSONAL, text).apply()
    }

    fun personal(context: Context): List<PersonalItem> =
        personalText(context).lines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@mapNotNull null
            val colon = line.indexOf(':')
            if (colon in 1..24) {
                val value = line.substring(colon + 1).trim()
                if (value.isEmpty()) null else PersonalItem(line.substring(0, colon).trim(), value)
            } else {
                PersonalItem("", line)
            }
        }

    // ------------------------------------------------------------------ выученное

    private fun load(context: Context): LinkedHashMap<String, Int> {
        learned?.let { return it }
        val map = LinkedHashMap<String, Int>()
        val saved = sp(context).getString(KEY_LEARNED, "") ?: ""
        for (line in saved.split('\n')) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val count = line.substring(0, tab).toIntOrNull() ?: continue
            val text = line.substring(tab + 1)
            if (text.isNotEmpty()) map[text] = count
        }
        learned = map
        return map
    }

    private fun save(context: Context, map: Map<String, Int>) {
        val sb = StringBuilder()
        for ((text, count) in map) sb.append(count).append('\t').append(text).append('\n')
        sp(context).edit().putString(KEY_LEARNED, sb.toString()).apply()
    }

    private fun bump(map: LinkedHashMap<String, Int>, key: String) {
        val n = (map.remove(key) ?: 0) + 1
        map[key] = n
    }

    private fun prune(map: LinkedHashMap<String, Int>) {
        if (map.size <= MAX_ITEMS) return
        val drop = map.size - MAX_ITEMS + 30
        // sortedBy стабильна: при равном счёте уходят самые старые
        val victims = map.entries.sortedBy { it.value }.take(drop).map { it.key }
        for (v in victims) map.remove(v)
    }

    /**
     * Вызывается, когда сообщение отправлено (поле очистилось) или закончили поле почты/телефона.
     * Запоминает почты, телефоны, даты и (если [withPhrase]) саму фразу целиком.
     */
    fun learnMessage(context: Context, raw: String, withPhrase: Boolean) {
        val text = raw.trim().replace(SPACES, " ")
        if (text.isEmpty() || text.length > 160) return
        val map = load(context)
        var changed = false

        for (piece in text.split(' ')) {
            val token = piece.trim { it in TRIM_CHARS }
            if (isLearnableToken(token)) {
                bump(map, token)
                changed = true
            }
        }
        if (withPhrase && isLearnablePhrase(text)) {
            bump(map, text)
            changed = true
        }
        if (changed) {
            prune(map)
            save(context, map)
        }
    }

    private fun isLearnablePhrase(text: String): Boolean {
        val words = text.split(' ')
        if (words.size !in 2..12 || text.length !in 6..90) return false
        if (words.any { it.contains('@') }) return false
        // не запоминаем фразы с длинными числами (карты, коды)
        if (Regex("\\d{6,}").containsMatchIn(text.replace(" ", ""))) return false
        return true
    }

    /** Почта, телефон, дата, время, длинное число. Короткие коды и номера карт — нет. */
    fun isLearnableToken(t: String): Boolean {
        if (t.length < 5 || t.length > 40) return false
        if (EMAIL.matches(t)) return true
        val digits = t.count { it.isDigit() }
        if (digits < 3) return false
        if (t.any { it.isLetter() }) return false
        if (!t.all { it.isDigit() || it in "+-./:()" }) return false
        if (t.all { it.isDigit() }) {
            if (digits < 7) return false // коды из СМС, ПИН
            if (digits in 13..19 && luhn(t)) return false // номер карты
        }
        return true
    }

    private fun luhn(number: String): Boolean {
        var sum = 0
        var alt = false
        for (i in number.length - 1 downTo 0) {
            var d = number[i] - '0'
            if (alt) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            alt = !alt
        }
        return sum % 10 == 0
    }

    /** Почта/номер/число: после такого значения пробел не нужен. */
    fun isTokenLike(text: String): Boolean = isLearnableToken(text) && !text.contains(' ')

    fun isEmail(text: String): Boolean = EMAIL.matches(text)

    private val DATE = Regex("""\d{1,2}[./]\d{1,2}[./]\d{2,4}""")

    fun isPhone(text: String): Boolean =
        text.count { it.isDigit() } >= 7 && text.none { it.isLetter() } && !DATE.matches(text)

    /** Частые фразы и числа для вкладки «Частые»: от самых частых. */
    fun frequent(context: Context): List<String> =
        load(context).entries
            .filter { it.value >= MIN_COUNT }
            .sortedByDescending { it.value }
            .take(40)
            .map { it.key }

    fun forget(context: Context, text: String) {
        val map = load(context)
        if (map.remove(text) != null) save(context, map)
    }

    fun clearLearned(context: Context) {
        learned = LinkedHashMap()
        sp(context).edit().remove(KEY_LEARNED).apply()
    }

    // ------------------------------------------------------------------ подбор подсказок

    /** Значения из «Моих данных» и выученные почты/числа, начинающиеся с [token]. */
    fun tokenMatches(context: Context, token: String): List<String> {
        if (token.length < 2) return emptyList()
        val out = LinkedHashSet<String>()
        for (p in personal(context)) {
            if (!p.value.contains(' ') && startsWithLonger(p.value, token)) out.add(p.value)
        }
        if (token.length >= 3) {
            for ((text, count) in load(context).entries.sortedByDescending { it.value }) {
                if (count >= MIN_COUNT && isTokenLike(text) && startsWithLonger(text, token)) {
                    out.add(text)
                }
            }
        }
        return out.toList()
    }

    /** Фразы и ФИО, с которых начинается набранная строка [line]. */
    fun phraseMatches(context: Context, line: String): List<String> {
        val out = LinkedHashSet<String>()
        val learnedPhrases = load(context).entries
            .filter { it.value >= MIN_COUNT && !isTokenLike(it.key) }
            .sortedByDescending { it.value }
        for ((text, _) in learnedPhrases) {
            if (startsWithLonger(text, line)) out.add(text)
        }
        for (p in personal(context)) {
            if (p.value.contains(' ') && startsWithLonger(p.value, line)) out.add(p.value)
        }
        return out.toList()
    }

    /** Для полей «почта» и «телефон»: подходящие значения (при пустом поле — все). */
    fun fieldValues(context: Context, phone: Boolean, token: String): List<String> {
        val out = LinkedHashSet<String>()
        fun fits(v: String) = if (phone) isPhone(v) else isEmail(v)
        for (p in personal(context)) {
            if (fits(p.value) && (token.isEmpty() || startsWithLonger(p.value, token))) out.add(p.value)
        }
        for ((text, count) in load(context).entries.sortedByDescending { it.value }) {
            if (count >= MIN_COUNT && fits(text) && (token.isEmpty() || startsWithLonger(text, token))) {
                out.add(text)
            }
        }
        return out.toList()
    }

    private fun startsWithLonger(full: String, prefix: String): Boolean =
        full.length > prefix.length && full.startsWith(prefix, ignoreCase = true)
}
