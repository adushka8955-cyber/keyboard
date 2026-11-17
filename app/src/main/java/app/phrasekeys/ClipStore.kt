package app.phrasekeys

/** Недавно скопированное: плашка «вставить» сверху и вкладка «Буфер». Хранится только в памяти. */
object ClipStore {
    private const val FRESH_MS = 10 * 60 * 1000L
    private const val MAX = 12

    private val items = ArrayList<String>()
    private var current: String? = null
    private var time = 0L
    private var used = false

    /** Новое содержимое буфера. Возвращает true, если это действительно новая копия. */
    fun onClip(text: String, now: Long, ageMs: Long = 0L): Boolean {
        if (text == current) return false
        current = text
        time = now - minOf(ageMs, FRESH_MS * 4)
        used = false
        items.remove(text)
        items.add(0, text)
        while (items.size > MAX) items.removeAt(items.size - 1)
        return true
    }

    /** Свежая, ещё не вставленная копия — или null. */
    fun fresh(now: Long): String? {
        val c = current ?: return null
        return if (!used && now - time < FRESH_MS) c else null
    }

    fun markUsed() {
        used = true
    }

    fun history(): List<String> = items.toList()

    fun remove(text: String) {
        items.remove(text)
        if (text == current) used = true
    }
}
