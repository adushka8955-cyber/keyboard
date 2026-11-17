package app.phrasekeys

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** Вариант в строке подсказок. [literal] = слово «как набрано», его выбор запоминает слово. */
class Suggestion(
    val text: String,
    val literal: Boolean,
    /** >= 0: сколько символов перед курсором заменить (для фраз и данных из памяти). */
    val replace: Int = -1,
    val space: Boolean = true,
    /** Плашка «вставить из буфера обмена». */
    val clip: Boolean = false
)

/**
 * Подсказки слов, автоисправление опечаток (русский и английский).
 * Словари лежат в assets (слово<TAB>частота) и грузятся в фоне при старте клавиатуры.
 */
object SpellEngine {

    private const val KEY_USER = "user_words"
    private const val MIN_AUTO_SCORE = 330 // автоисправляем только на достаточно частые слова
    private const val MIN_AUTO_LENGTH = 4
    private const val RARE_SCORE = 300 // слова реже этого могут оказаться опечатками, попавшими в словарь
    private const val BETTER_BY = 120 // и заменяются, только если замена в ~15 раз частотнее

    private class Cand(val word: String, val raw: Int, val rank: Int)

    private class Lexicon(val alphabet: String) {
        val map = HashMap<String, Int>(200_000)
        lateinit var sorted: Array<String>

        fun finish() {
            val keys = map.keys.toTypedArray()
            keys.sort()
            sorted = keys
        }

        fun contains(word: String): Boolean = map.containsKey(word)

        /** Слова, которые продолжают [prefix], самые частые первыми. */
        fun complete(prefix: String, n: Int): List<String> {
            if (prefix.isEmpty() || n <= 0 || !::sorted.isInitialized) return emptyList()
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (sorted[mid] < prefix) lo = mid + 1 else hi = mid
            }
            val bestWords = arrayOfNulls<String>(n)
            val bestScores = IntArray(n) { -1 }
            var i = lo
            while (i < sorted.size && sorted[i].startsWith(prefix)) {
                val w = sorted[i]
                if (w.length > prefix.length) {
                    val s = map[w] ?: 0
                    if (s > bestScores[n - 1]) {
                        var j = n - 1
                        while (j > 0 && bestScores[j - 1] < s) {
                            bestScores[j] = bestScores[j - 1]
                            bestWords[j] = bestWords[j - 1]
                            j--
                        }
                        bestScores[j] = s
                        bestWords[j] = w
                    }
                }
                i++
            }
            return bestWords.filterNotNull()
        }

        /** Слова на одну правку (опечатку) от [w], лучшие первыми. */
        fun corrections(w: String, max: Int): List<Cand> {
            if (w.isEmpty()) return emptyList()
            val found = HashMap<String, Cand>()
            for (cand in edits1(w)) {
                if (cand.isEmpty() || cand == w) continue
                val s = map[cand] ?: continue
                val rank = s + if (cand[0] == w[0]) 40 else 0
                val old = found[cand]
                if (old == null || old.rank < rank) found[cand] = Cand(cand, s, rank)
            }
            return found.values.sortedByDescending { it.rank }.take(max)
        }

        private fun edits1(w: String): List<String> {
            val n = w.length
            val out = ArrayList<String>(n * (alphabet.length * 2 + 2) + alphabet.length)
            for (i in 0 until n) out.add(w.substring(0, i) + w.substring(i + 1))
            for (i in 0 until n - 1) {
                out.add(w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2))
            }
            for (i in 0 until n) {
                for (c in alphabet) {
                    if (c != w[i]) out.add(w.substring(0, i) + c + w.substring(i + 1))
                }
            }
            for (i in 0..n) {
                for (c in alphabet) out.add(w.substring(0, i) + c + w.substring(i))
            }
            return out
        }
    }

    private val ru = Lexicon("абвгдеёжзийклмнопрстуфхцчшщъыьэюя")
    private val en = Lexicon("abcdefghijklmnopqrstuvwxyz'")

    @Volatile
    private var ready = false

    @Volatile
    private var loading = false

    private val userWords: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val NON_LETTER = Regex("[^\\p{L}']+")

    private val EN_FIX = mapOf(
        "i" to "I", "im" to "I'm", "ive" to "I've", "dont" to "don't", "cant" to "can't",
        "wont" to "won't", "didnt" to "didn't", "doesnt" to "doesn't", "isnt" to "isn't",
        "wasnt" to "wasn't", "arent" to "aren't", "couldnt" to "couldn't",
        "shouldnt" to "shouldn't", "wouldnt" to "wouldn't", "youre" to "you're",
        "thats" to "that's", "whats" to "what's", "theyre" to "they're",
        "havent" to "haven't", "hasnt" to "hasn't", "teh" to "the", "adn" to "and",
        "taht" to "that", "wich" to "which", "thier" to "their"
    )

    fun isReady(): Boolean = ready

    fun ensureLoaded(context: Context) {
        synchronized(this) {
            if (loading) return
            loading = true
        }
        val app = context.applicationContext
        Thread {
            try {
                loadFile(app, "ru_words.txt", ru)
                loadFile(app, "en_words.txt", en)
                loadUserWords(app)
                ready = true
            } catch (e: Exception) {
                loading = false
            }
        }.start()
    }

    private fun loadFile(context: Context, file: String, lex: Lexicon) {
        context.assets.open(file).bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val score = line.substring(tab + 1).trim().toIntOrNull() ?: continue
                lex.map[line.substring(0, tab)] = score
            }
        }
        lex.finish()
    }

    private fun loadUserWords(context: Context) {
        val sp = context.getSharedPreferences("phrasekeys", Context.MODE_PRIVATE)
        val saved = sp.getStringSet(KEY_USER, null)
        if (saved != null) userWords.addAll(saved)
        addPhraseWords(context)
    }

    /** Слова из встроенных и своих фраз не должны «исправляться». */
    private fun addPhraseWords(context: Context) {
        for (category in PhraseLibrary.builtIn) {
            for (phrase in category.phrases) addWords(phrase)
        }
        addWords(PhraseLibrary.loadCustomText(context))
    }

    private fun addWords(text: String) {
        for (w in text.split(NON_LETTER)) {
            if (w.isNotEmpty()) userWords.add(normalize(w))
        }
    }

    fun learn(context: Context, word: String) {
        val w = normalize(word)
        if (w.isEmpty() || !userWords.add(w)) return
        val sp = context.getSharedPreferences("phrasekeys", Context.MODE_PRIVATE)
        val set = HashSet<String>(sp.getStringSet(KEY_USER, null) ?: emptySet<String>())
        set.add(w)
        sp.edit().putStringSet(KEY_USER, set).apply()
    }

    fun resetUserWords(context: Context) {
        context.getSharedPreferences("phrasekeys", Context.MODE_PRIVATE)
            .edit().remove(KEY_USER).apply()
        userWords.clear()
        addPhraseWords(context)
    }

    private fun normalize(word: String): String = word.lowercase().replace('’', '\'')

    /** 1 = кириллица, 2 = латиница, 0 = что-то другое / смесь. */
    private fun scriptOf(w: String): Int {
        var cyr = false
        var lat = false
        for (ch in w) {
            if (ch == '\'' || ch == '’') continue
            when {
                ch in 'а'..'я' || ch in 'А'..'Я' || ch == 'ё' || ch == 'Ё' -> cyr = true
                ch in 'a'..'z' || ch in 'A'..'Z' -> lat = true
                else -> return 0
            }
        }
        return if (cyr && !lat) 1 else if (lat && !cyr) 2 else 0
    }

    private fun lexiconFor(lower: String): Lexicon? = when (scriptOf(lower)) {
        1 -> ru
        2 -> en
        else -> null
    }

    /** Подсказки для слова, которое сейчас набирается (до 3 штук). */
    fun suggest(typed: String): List<Suggestion> {
        if (!ready || typed.isEmpty()) return emptyList()
        val lower = normalize(typed)
        val lex = lexiconFor(lower) ?: return emptyList()
        val out = ArrayList<Suggestion>()

        fun add(s: Suggestion) {
            if (out.size < 3 && out.none { it.text.equals(s.text, ignoreCase = true) }) out.add(s)
        }

        val typedScore = lex.map[lower]
        val known = userWords.contains(lower) || (typedScore != null && typedScore >= RARE_SCORE)
        val completions = if (lower.length >= 2) lex.complete(lower, 4) else emptyList()
        val fixes = if (known) {
            emptyList()
        } else {
            lex.corrections(lower, 3).filter {
                (typedScore == null || it.raw > typedScore) &&
                    (completions.isEmpty() || !lower.startsWith(it.word))
            }
        }
        val typedIsWord = known || typedScore != null

        if (completions.isEmpty()) {
            // Не начало никакого слова: скорее всего опечатка.
            if (fixes.isEmpty()) {
                add(Suggestion(typed, !typedIsWord))
            } else {
                add(Suggestion(matchCase(typed, fixes[0].word), false))
                add(Suggestion(typed, true))
                fixes.getOrNull(1)?.let { add(Suggestion(matchCase(typed, it.word), false)) }
            }
        } else {
            // Слово ещё набирается: показываем продолжения.
            add(Suggestion(typed, !typedIsWord))
            for (w in completions.take(2)) add(Suggestion(matchCase(typed, w), false))
            fixes.firstOrNull()?.let { add(Suggestion(matchCase(typed, it.word), false)) }
            for (w in completions.drop(2)) add(Suggestion(matchCase(typed, w), false))
        }
        return out
    }

    /**
     * Чем заменить слово при нажатии пробела/знака препинания, либо null, если оставить как есть.
     * Заглавные слова не трогаем (это часто имена), кроме начала предложения.
     */
    fun autoCorrection(typed: String, atSentenceStart: Boolean): String? {
        if (!ready || typed.isEmpty()) return null
        val lower = normalize(typed)
        val script = scriptOf(lower)
        if (script == 0) return null

        if (script == 2) {
            val fix = EN_FIX[lower]
            if (fix != null) {
                val result = if (lower == "i") (if (typed == "i") "I" else null) else matchCase(typed, fix)
                return if (result == null || result == typed) null else result
            }
        }

        if (typed.length < MIN_AUTO_LENGTH) return null
        if (typed.length > 1 && typed.all { it.isUpperCase() }) return null
        if (typed.drop(1).any { it.isUpperCase() }) return null
        if (typed[0].isUpperCase() && !atSentenceStart) return null

        val lex = lexiconFor(lower) ?: return null
        if (userWords.contains(lower)) return null
        val typedScore = lex.map[lower]
        if (typedScore != null && typedScore >= RARE_SCORE) return null
        val best = lex.corrections(lower, 1).firstOrNull() ?: return null
        if (best.raw < MIN_AUTO_SCORE) return null
        if (typedScore != null && best.raw < typedScore + BETTER_BY) return null
        return matchCase(typed, best.word)
    }

    private fun matchCase(original: String, replacement: String): String = when {
        replacement.isEmpty() -> replacement
        original.length > 1 && original.all { it.isUpperCase() } -> replacement.uppercase()
        original[0].isUpperCase() -> replacement.replaceFirstChar { it.uppercaseChar() }
        else -> replacement
    }
}
