package app.phrasekeys

import android.content.Context

/**
 * Предсказание слова по смыслу: что обычно идёт после предыдущего слова.
 * Две части: встроенная модель (файлы ru и en bigrams в assets) и «личная» — клавиатура запоминает,
 * какие слова вы ставите рядом в отправленных сообщениях.
 */
object Predictor {

    const val START = "<s>"

    private const val NAME = "phrasekeys_memory"
    private const val KEY_PAIRS = "bigrams"
    private const val MAX_PAIRS = 5000

    // «предыдущее слово» -> лучшие следующие
    private val builtIn = HashMap<String, Array<String>>()

    // «предыдущее слово» -> следующее -> сколько раз
    private val personal = HashMap<String, HashMap<String, Int>>()
    private var pairCount = 0

    @Volatile
    private var loaded = false
    private var loading = false

    private val TOKEN = Regex("[\\p{L}']+")
    private val SENTENCE_SPLIT = Regex("[.!?\\n]+")

    fun ensureLoaded(context: Context) {
        synchronized(this) {
            if (loaded || loading) return
            loading = true
        }
        val app = context.applicationContext
        Thread {
            try {
                for (file in listOf("ru_bigrams.txt", "en_bigrams.txt")) {
                    app.assets.open(file).bufferedReader(Charsets.UTF_8).useLines { loadBuiltIn(it) }
                }
                loadPersonal(app.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY_PAIRS, "") ?: "")
            } catch (e: Exception) {
                // без модели просто не будет предсказаний
            }
            loaded = true
        }.start()
    }

    /** Строки вида «слово<TAB>след1 след2 …». */
    fun loadBuiltIn(lines: Sequence<String>) {
        synchronized(this) {
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val key = line.substring(0, tab)
                val next = line.substring(tab + 1).trim().split(' ').filter { it.isNotEmpty() }
                if (next.isEmpty()) continue
                val old = builtIn[key]
                builtIn[key] = if (old == null) next.toTypedArray() else (old.toList() + next).distinct().toTypedArray()
            }
            loaded = true
        }
    }

    fun loadPersonal(saved: String) {
        synchronized(this) {
            personal.clear()
            pairCount = 0
            for (line in saved.split('\n')) {
                val parts = line.split('\t')
                if (parts.size != 3) continue
                val n = parts[2].toIntOrNull() ?: continue
                personal.getOrPut(parts[0]) { HashMap() }[parts[1]] = n
                pairCount++
            }
        }
    }

    fun isReady(): Boolean = loaded

    /**
     * Слова, которые вероятнее всего идут после [prev] (null — начало предложения).
     * [prefix] — уже набранное начало слова (может быть пустым).
     */
    fun predict(prev: String?, prefix: String, n: Int): List<String> {
        if (!loaded) return emptyList()
        val key = prev?.lowercase()?.replace('’', '\'') ?: START
        val low = prefix.lowercase()
        val score = HashMap<String, Int>()
        synchronized(this) {
            personal[key]?.forEach { (w, c) -> score[w] = 100 + c * 20 }
            builtIn[key]?.forEachIndexed { i, w ->
                val s = 60 - i * 8
                val old = score[w]
                score[w] = if (old == null) s else old + s
            }
        }
        if (score.isEmpty()) return emptyList()
        return score.entries
            .filter { low.isEmpty() || (it.key.length > low.length && it.key.startsWith(low)) }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(n)
            .map { it.key }
    }

    /** Запомнить пары слов из отправленного сообщения. */
    fun learn(context: Context, text: String) {
        var changed = false
        synchronized(this) {
            for (sentence in text.split(SENTENCE_SPLIT)) {
                val words = TOKEN.findAll(sentence).map { it.value.lowercase().replace('’', '\'') }.toList()
                if (words.isEmpty() || words.size > 40) continue
                var prev = START
                for (w in words) {
                    if (w.length > 24) break
                    val map = personal.getOrPut(prev) { HashMap() }
                    val old = map[w]
                    if (old == null) pairCount++
                    map[w] = (old ?: 0) + 1
                    changed = true
                    prev = w
                }
            }
            if (pairCount > MAX_PAIRS) prune()
        }
        if (changed) save(context)
    }

    private fun prune() {
        // выбрасываем самые редкие пары
        val all = ArrayList<Triple<String, String, Int>>()
        for ((p, m) in personal) for ((w, c) in m) all.add(Triple(p, w, c))
        all.sortBy { it.third }
        var drop = pairCount - MAX_PAIRS + 500
        for ((p, w, _) in all) {
            if (drop <= 0) break
            personal[p]?.remove(w)
            if (personal[p]?.isEmpty() == true) personal.remove(p)
            pairCount--
            drop--
        }
    }

    private fun save(context: Context) {
        val sb = StringBuilder()
        synchronized(this) {
            for ((p, m) in personal) for ((w, c) in m) {
                sb.append(p).append('\t').append(w).append('\t').append(c).append('\n')
            }
        }
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PAIRS, sb.toString()).apply()
    }

    fun clear(context: Context) {
        synchronized(this) {
            personal.clear()
            pairCount = 0
        }
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().remove(KEY_PAIRS).apply()
    }
}
