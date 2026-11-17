package app.phrasekeys

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.math.abs

/**
 * Клавиатура: буквы RU/EN, цифры и символы, строка подсказок (T9-стиль), автоисправление,
 * правила пунктуации, панель фраз и смайлов, темы и стиль iPhone/Android.
 */
class PhraseKeyboardService : InputMethodService() {

    private enum class Layer { RU, EN, SYMBOLS }
    private enum class Shift { OFF, ONCE, CAPS }

    private class Correction(val original: String, val fixed: String, val delimiter: String)
    private class WordInfo(val raw: String, val skip: Boolean, val sentenceStart: Boolean)

    // ------------------------------------------------------------------ состояние

    private var layer = Layer.RU
    private var lastLetterLayer = Layer.RU
    private var shift = Shift.OFF
    private var lastShiftTap = 0L
    private var lastSpaceTime = 0L
    private var categoryIndex = 0
    private var lastCorrection: Correction? = null
    private var builtVersion = -1
    private var menuOpen = false
    private var symbolPage = 0

    // снимок настроек
    private var theme: KeyboardTheme = Themes.all[0]
    private var iosLayout = true
    private var suggestionsOn = true
    private var autocorrectOn = true
    private var punctOn = true
    private var autocapOn = true
    private var hapticsOn = true
    private var previewOn = true
    private var numberRow = true
    private var heightMode = "mid"
    private var letterSize = "m"
    private var shape = "theme"

    // свойства текущего поля ввода
    private var plainField = false
    private var noSuggestFlag = false
    private var forceSuggest = true

    /** Приложение запретило подсказки (флаг NO_SUGGESTIONS), и мы это уважаем. */
    private val blockedByApp: Boolean get() = noSuggestFlag && !forceSuggest
    private var secretField = false
    private var emailField = false
    private var phoneField = false
    private var memoryOn = true
    private var snapshot: String? = null

    private lateinit var root: FrameLayout
    private lateinit var keysView: LinearLayout
    private lateinit var phrasesView: LinearLayout
    private lateinit var tabsRow: LinearLayout
    private lateinit var chipsFlow: FlowLayout
    private lateinit var arrow: ImageView
    private lateinit var stripView: LinearLayout
    private lateinit var menuView: LinearLayout
    private lateinit var suggestRow: LinearLayout
    private lateinit var preview: TextView

    private val suggestViews = ArrayList<TextView>()
    private var currentSuggestions: List<Suggestion> = emptyList()
    private val letterKeys = ArrayList<TextView>()
    private var shiftKey: GlyphKey? = null
    private var glassOn = true

    private val handler = Handler(Looper.getMainLooper())

    // Одно общее обновление после набора: один запрос к приложению вместо пяти.
    private val updateRunnable = Runnable { processUpdate() }
    private var hasPending = false
    private var pendingOldStart = 0
    private var pendingNewStart = 0
    private var pendingNewEnd = 0

    private var appliedShift: Shift? = null
    private var afterDelimiter: Char = NO_CHAR
    private var shownSignature = ""
    private var arrowOpen = false
    private var predictOn = true
    private var clipboardOn = true
    private var clipIcon: VectorGlyph? = null
    private var clipManager: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { readClipboard(true) }

    private fun requestUpdate(delayMs: Long) {
        handler.removeCallbacks(updateRunnable)
        handler.postDelayed(updateRunnable, delayMs)
    }

    private fun fetchBefore(n: Int = 200): String? =
        currentInputConnection?.getTextBeforeCursor(n, 0)?.toString()

    private fun processUpdate() {
        if (!::keysView.isInitialized) return
        val oldStart = pendingOldStart
        val newStart = pendingNewStart
        val newEnd = pendingNewEnd
        hasPending = false
        val before = fetchBefore() ?: ""
        if (afterDelimiter != NO_CHAR && !before.endsWith(afterDelimiter)) afterDelimiter = NO_CHAR
        trackMemory(oldStart, newStart, newEnd, before)
        updateAutoShift(before)
        refreshLetterCase()
        refreshSuggestions(before)
    }
    private val deleteRunnable = object : Runnable {
        override fun run() {
            deleteOne()
            handler.postDelayed(this, 50)
        }
    }

    private val keyRadiusDp: Int
        get() = when (shape) {
            "round" -> 14
            "square" -> 2
            else -> theme.radiusDp
        }

    private val smart: Boolean get() = punctOn && !plainField
    private val auto: Boolean
        get() = autocorrectOn && !plainField && !blockedByApp && SpellEngine.isReady()

    // ------------------------------------------------------------------ жизненный цикл

    override fun onCreate() {
        super.onCreate()
        SpellEngine.ensureLoaded(this)
        Predictor.ensureLoaded(this)
        clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        try {
            clipManager?.addPrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            clipManager?.removePrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    /** Читает буфер обмена и запоминает новое. Секретное (пароли) пропускаем. */
    private fun readClipboard(fromListener: Boolean = false) {
        if (!clipboardOn) return
        try {
            val cm = clipManager ?: return
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            if (Build.VERSION.SDK_INT >= 33 &&
                clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
            ) return
            val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim() ?: return
            if (text.isEmpty() || text.length > 2000) return
            // возраст копии: старое содержимое буфера не выдаём за свежее
            val stamp = clip.description.timestamp
            val age = when {
                stamp > 0 -> (System.currentTimeMillis() - stamp).coerceAtLeast(0)
                fromListener -> 0L
                else -> Long.MAX_VALUE / 4
            }
            if (ClipStore.onClip(text, SystemClock.uptimeMillis(), age) && ::keysView.isInitialized) {
                requestUpdate(60)
            }
        } catch (e: Exception) {
            // доступа к буферу нет — просто без плашки
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    private fun readPrefs() {
        theme = Themes.byId(Prefs.str(this, Prefs.THEME, "ios_light"))
        iosLayout = Prefs.bool(this, Prefs.LAYOUT_IOS, true)
        suggestionsOn = Prefs.bool(this, Prefs.SUGGESTIONS, true)
        autocorrectOn = Prefs.bool(this, Prefs.AUTOCORRECT, true)
        punctOn = Prefs.bool(this, Prefs.SMART_PUNCT, true)
        autocapOn = Prefs.bool(this, Prefs.AUTOCAP, true)
        hapticsOn = Prefs.bool(this, Prefs.HAPTICS, true)
        previewOn = Prefs.bool(this, Prefs.PREVIEW, true)
        memoryOn = Prefs.bool(this, Prefs.LEARN, true)
        glassOn = Prefs.bool(this, Prefs.GLASS, true)
        forceSuggest = Prefs.bool(this, Prefs.FORCE_SUGGEST, true)
        predictOn = Prefs.bool(this, Prefs.PREDICT, true)
        clipboardOn = Prefs.bool(this, Prefs.CLIPBOARD, true)
        numberRow = Prefs.bool(this, Prefs.NUMBER_ROW, true)
        heightMode = Prefs.str(this, Prefs.HEIGHT, "mid")
        letterSize = Prefs.str(this, Prefs.LETTER_SIZE, "m")
        shape = Prefs.str(this, Prefs.SHAPE, "theme")
    }

    override fun onCreateInputView(): View {
        readPrefs()
        builtVersion = Prefs.version
        letterKeys.clear()
        suggestViews.clear()
        shiftKey = null
        menuOpen = false

        val frame = FrameLayout(this)
        root = frame
        frame.background = backgroundDrawable()
        addPhotoLayer(frame)

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(3), 0, dp(3), dp(6))

        stripView = buildStrip()
        content.addView(stripView, LinearLayout.LayoutParams(MATCH, dp(STRIP_DP)))

        keysView = LinearLayout(this)
        keysView.orientation = LinearLayout.VERTICAL
        phrasesView = buildPhrasesView()
        phrasesView.visibility = View.GONE
        menuView = buildMenuView()
        menuView.visibility = View.GONE

        val body = FrameLayout(this)
        body.addView(keysView, FrameLayout.LayoutParams(MATCH, MATCH))
        body.addView(phrasesView, FrameLayout.LayoutParams(MATCH, MATCH))
        body.addView(menuView, FrameLayout.LayoutParams(MATCH, MATCH))
        content.addView(body, LinearLayout.LayoutParams(MATCH, dp(bodyHeightDp())))

        frame.addView(content, FrameLayout.LayoutParams(MATCH, WRAP))

        preview = TextView(this)
        preview.gravity = Gravity.CENTER
        preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        preview.setTextColor(theme.text)
        preview.background = roundRect(withAlpha(theme.key, 255), 10)
        preview.elevation = dp(6).toFloat()
        preview.visibility = View.GONE
        frame.addView(preview, FrameLayout.LayoutParams(dp(56), dp(52)))

        renderKeys()
        refreshSuggestions()
        return frame
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (builtVersion != Prefs.version) {
            setInputView(onCreateInputView())
        } else {
            readPrefs()
        }

        val inputType = info?.inputType ?: 0
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        plainField = inputClass != InputType.TYPE_CLASS_TEXT || variation in PLAIN_VARIATIONS
        noSuggestFlag = (inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        val isText = inputClass == InputType.TYPE_CLASS_TEXT
        secretField = (isText && variation in SECRET_VARIATIONS) ||
            (inputClass == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        emailField = isText && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        phoneField = inputClass == InputType.TYPE_CLASS_PHONE
        if (!restarting) snapshot = null

        layer = when (inputClass) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME -> Layer.SYMBOLS
            else -> if (layer == Layer.SYMBOLS) lastLetterLayer else layer
        }
        lastCorrection = null
        afterDelimiter = NO_CHAR
        shift = Shift.OFF
        readClipboard()
        val before = fetchBefore() ?: ""
        updateAutoShift(before)
        if (!restarting) {
            closeMenu(false)
            showKeys()
        }
        renderKeys()
        refreshSuggestions(before)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        handler.removeCallbacks(deleteRunnable)
        lastCorrection = null
        val last = snapshot
        if (last != null && learnAllowed && (emailField || phoneField)) {
            Memory.learnMessage(this, last, false)
        }
        snapshot = null
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        if (!::keysView.isInitialized) return
        if (!hasPending) {
            pendingOldStart = oldSelStart
            hasPending = true
        }
        pendingNewStart = newSelStart
        pendingNewEnd = newSelEnd
        requestUpdate(25)
    }

    private val learnAllowed: Boolean
        get() = memoryOn && !secretField && !blockedByApp &&
            (!plainField || emailField || phoneField)

    /**
     * Следим за текстом поля. Когда поле разом опустело (сообщение отправили) —
     * запоминаем, что было набрано: фразу, почты, телефоны, даты.
     */
    private fun trackMemory(oldStart: Int, newStart: Int, newEnd: Int, before: String) {
        if (!learnAllowed) {
            snapshot = null
            return
        }
        val prev = snapshot
        if (newStart == 0 && newEnd == 0 && oldStart >= 4 && prev != null && before.isEmpty() &&
            currentInputConnection?.getTextAfterCursor(1, 0).isNullOrEmpty()
        ) {
            Memory.learnMessage(this, prev, true)
            if (predictOn) Predictor.learn(this, prev)
            snapshot = null
            return
        }
        snapshot = if (before.length < 200) before else null
    }

    // ------------------------------------------------------------------ фон и размеры

    private fun backgroundDrawable(): GradientDrawable =
        if (theme.gradient) {
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(theme.bg, theme.bgEnd)
            )
        } else {
            val d = GradientDrawable()
            d.setColor(theme.bg)
            d
        }

    private fun addPhotoLayer(frame: FrameLayout) {
        if (!theme.photo) return
        val file = File(filesDir, Prefs.PHOTO_FILE)
        if (!file.exists()) return
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
        val image = ImageView(this)
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.setImageBitmap(bitmap)
        frame.addView(image, FrameLayout.LayoutParams(MATCH, MATCH))
        val dim = View(this)
        dim.setBackgroundColor(Color.argb(70, 0, 0, 0))
        frame.addView(dim, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun bodyHeightDp(): Int {
        var rowDp = when (heightMode) {
            "low" -> 40f
            "high" -> 50f
            else -> 44f
        }
        if (isLandscape()) rowDp *= 0.78f
        val rows = if (numberRow) 5 else 4
        return (rowDp * rows).toInt()
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun letterSp(): Float {
        val base = when (letterSize) {
            "s" -> 15f
            "l" -> 20f
            else -> 17f
        }
        return if (isLandscape()) base * 0.85f else base
    }

    // ------------------------------------------------------------------ строка подсказок

    /**
     * Верхняя полоска: в покое — тонкая, только маленькая стрелка слева. Пока печатаешь —
     * вырастает и показывает подсказки. Нажатие на стрелку (или на саму полоску) выдвигает меню.
     */
    private fun buildStrip(): LinearLayout {
        val strip = LinearLayout(this)
        strip.orientation = LinearLayout.HORIZONTAL
        strip.gravity = Gravity.CENTER_VERTICAL
        strip.isClickable = true
        strip.setOnClickListener {
            haptic(it)
            toggleMenu()
        }

        arrow = ImageView(this)
        arrow.scaleType = ImageView.ScaleType.CENTER
        arrow.setImageDrawable(VectorGlyph(Icons.CHEV_UP, theme.hint))
        arrow.isClickable = true
        arrow.setOnClickListener {
            haptic(it)
            toggleMenu()
        }
        strip.addView(arrow, LinearLayout.LayoutParams(dp(48), MATCH))

        clipIcon = VectorGlyph(Icons.CLIP_TINT, theme.text).also { it.setBounds(0, 0, dp(16), dp(16)) }
        suggestRow = LinearLayout(this)
        suggestRow.orientation = LinearLayout.HORIZONTAL
        suggestRow.gravity = Gravity.CENTER_VERTICAL
        suggestRow.visibility = View.GONE
        for (i in 0 until 3) {
            val tv = TextView(this)
            tv.gravity = Gravity.CENTER
            tv.setSingleLine(true)
            tv.ellipsize = TextUtils.TruncateAt.END
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            tv.setTextColor(theme.text)
            tv.setPadding(dp(6), 0, dp(6), 0)
            tv.isClickable = true
            tv.setOnClickListener {
                val s = currentSuggestions.getOrNull(i)
                if (s != null) {
                    haptic(it)
                    applySuggestion(s)
                }
            }
            suggestViews.add(tv)
            suggestRow.addView(tv, LinearLayout.LayoutParams(0, MATCH, 1f))
            if (i < 2) {
                val divider = View(this)
                divider.setBackgroundColor(withAlpha(theme.hint, 110))
                suggestRow.addView(divider, LinearLayout.LayoutParams(dp(1), dp(18)))
            }
        }
        strip.addView(suggestRow, LinearLayout.LayoutParams(0, MATCH, 1f))
        return strip
    }



    private fun updateStrip(showSuggestions: Boolean) {
        val show = !menuOpen && showSuggestions
        if (arrowOpen != menuOpen) {
            arrowOpen = menuOpen
            arrow.animate().rotation(if (menuOpen) 180f else 0f).setDuration(160).start()
        }
        val vis = if (show) View.VISIBLE else View.GONE
        if (suggestRow.visibility != vis) suggestRow.visibility = vis
        if (!show) shownSignature = ""
    }

    // ------------------------------------------------------------------ меню с пунктами

    private class MenuItem(val icon: Icon, val label: String, val action: () -> Unit)

    private fun buildMenuView(): LinearLayout {
        val menu = LinearLayout(this)
        menu.orientation = LinearLayout.VERTICAL
        menu.isClickable = true
        menu.setPadding(dp(8), dp(6), dp(8), dp(6))
        menu.background = glassPanel()

        val items = listOf(
            MenuItem(Icons.PHRASES, "Фразы") { showPhrases() },
            MenuItem(Icons.EMOJI, "Смайлы") { showEmoji() },
            MenuItem(Icons.THEME, "Тема") { cycleTheme() },
            MenuItem(Icons.SETTINGS, "Настройки") { openSettings() },
            MenuItem(Icons.CLIP, "Буфер") { showCategoryKind(KIND_CLIPBOARD) },
            MenuItem(Icons.DATA, "Данные") { showCategoryKind(KIND_DATA) },
            MenuItem(Icons.FREQUENT, "Частые") { showCategoryKind(KIND_FREQUENT) },
            MenuItem(Icons.GLOBE, "Клавиатура") { showImePicker() }
        )
        for (r in 0 until 2) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            for (c in 0 until 4) {
                val item = items[r * 4 + c]
                row.addView(menuItemView(item), LinearLayout.LayoutParams(0, MATCH, 1f))
            }
            menu.addView(row, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        return menu
    }

    /** Мягкая стеклянная панель меню: полупрозрачная заливка и светлая кромка. */
    private fun glassPanel(): Drawable {
        val d = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(46, 255, 255, 255), Color.argb(14, 255, 255, 255))
        )
        d.cornerRadius = dp(18).toFloat()
        d.setStroke(dp(1), Color.argb(64, 255, 255, 255))
        return d
    }

    private fun menuItemView(item: MenuItem): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.isClickable = true

        val icon = ImageView(this)
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        icon.setImageDrawable(VectorGlyph(item.icon))
        box.addView(icon, LinearLayout.LayoutParams(dp(34), dp(34)))

        val label = TextView(this)
        label.text = item.label
        label.gravity = Gravity.CENTER
        label.setSingleLine(true)
        label.ellipsize = TextUtils.TruncateAt.END
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        label.setTextColor(theme.text)
        label.setPadding(0, dp(4), 0, 0)
        box.addView(label, LinearLayout.LayoutParams(MATCH, WRAP))

        // мягкое «нажатие» как на iPhone
        box.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
        box.setOnClickListener {
            haptic(it)
            closeMenu(false)
            item.action()
        }
        return box
    }

    private fun toggleMenu() {
        if (menuOpen) closeMenu(true) else openMenu()
    }

    /** Меню выезжает снизу вверх поверх клавиш. */
    private fun openMenu() {
        if (!::menuView.isInitialized) return
        menuOpen = true
        menuView.animate().cancel()
        menuView.visibility = View.VISIBLE
        menuView.translationY = dp(bodyHeightDp()).toFloat()
        menuView.animate().translationY(0f).setDuration(180).start()
        refreshSuggestions()
    }

    private fun closeMenu(animated: Boolean) {
        if (!::menuView.isInitialized) return
        val wasOpen = menuOpen
        menuOpen = false
        menuView.animate().cancel()
        if (animated && wasOpen) {
            menuView.animate()
                .translationY(dp(bodyHeightDp()).toFloat())
                .setDuration(150)
                .withEndAction { if (!menuOpen) menuView.visibility = View.GONE }
                .start()
        } else {
            menuView.visibility = View.GONE
            menuView.translationY = 0f
        }
        if (wasOpen) refreshSuggestions()
    }

    private fun showEmoji() {
        val index = PhraseLibrary.categories(this).indexOfFirst { it.emoji }
        if (index >= 0) categoryIndex = index
        showPhrases()
    }

    private fun showCategoryKind(kind: Int) {
        val index = PhraseLibrary.categories(this).indexOfFirst { it.kind == kind }
        if (index >= 0) categoryIndex = index
        showPhrases()
    }

    private fun toggleNumberRow() {
        Prefs.putBool(this, Prefs.NUMBER_ROW, !numberRow)
        rebuildView()
    }

    private fun cycleLetterSize() {
        val next = when (letterSize) {
            "s" -> "m"
            "m" -> "l"
            else -> "s"
        }
        Prefs.putStr(this, Prefs.LETTER_SIZE, next)
        rebuildView()
    }

    private fun cycleHeight() {
        val next = when (heightMode) {
            "low" -> "mid"
            "mid" -> "high"
            else -> "low"
        }
        Prefs.putStr(this, Prefs.HEIGHT, next)
        rebuildView()
    }

    private fun showImePicker() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    private fun rebuildView() {
        handler.post { setInputView(onCreateInputView()) }
    }

    /** Что стоит прямо перед курсором: начало слова и слово до него (для предсказания). */
    private class Ctx(val prefix: String, val prev: String?, val valid: Boolean)

    private fun contextWords(text: String): Ctx {
        var i = text.length
        while (i > 0 && isWordChar(text[i - 1])) i--
        val prefix = text.substring(i)
        // перед началом слова должен быть пробел, начало текста или новая строка
        if (i > 0 && text[i - 1] != ' ' && text[i - 1] != '\n') return Ctx(prefix, null, false)
        var j = i
        while (j > 0 && text[j - 1] == ' ') j--
        if (j == 0) return Ctx(prefix, null, true)
        val c = text[j - 1]
        if (c == '.' || c == '!' || c == '?' || c == '\n') return Ctx(prefix, null, true)
        var k = j
        while (k > 0 && text[k - 1] in ",;:") k--
        var end = k
        while (k > 0 && isWordChar(text[k - 1])) k--
        if (k == end) return Ctx(prefix, null, false)
        return Ctx(prefix, text.substring(k, end), true)
    }

    private fun refreshSuggestions(known: String? = null) {
        if (!::suggestRow.isInitialized || !::phrasesView.isInitialized || !::arrow.isInitialized) return
        if (secretField || blockedByApp || phrasesView.visibility == View.VISIBLE) {
            updateStrip(false)
            return
        }
        val text = known ?: fetchBefore() ?: ""
        val ctx = contextWords(text)
        val tokenEmpty = ctx.prefix.isEmpty() &&
            (text.isEmpty() || text.last() == ' ' || text.last() == '\n')

        // плашка «вставить» из буфера обмена
        val clipText = if (clipboardOn && tokenEmpty) ClipStore.fresh(SystemClock.uptimeMillis()) else null
        val clip = if (clipText != null) Suggestion(clipText, false, 0, false, true) else null

        var mem: List<Suggestion> = if (suggestionsOn && memoryOn) memorySuggestions(text) else emptyList()
        var spell: List<Suggestion> = emptyList()
        var target: String? = null
        var predicted: List<Suggestion> = emptyList()
        if (suggestionsOn && !plainField) {
            val info = wordInfoFrom(text)
            if (info != null && !info.skip) {
                spell = SpellEngine.suggest(info.raw)
                if (auto) target = SpellEngine.autoCorrection(info.raw, info.sentenceStart)
            }
            if (predictOn && ctx.valid) {
                val capital = ctx.prev == null && ctx.prefix.isEmpty() ||
                    (ctx.prefix.isNotEmpty() && ctx.prefix[0].isUpperCase())
                predicted = Predictor.predict(ctx.prev, ctx.prefix, 3).map {
                    val w = if (capital) it.replaceFirstChar { c -> c.uppercaseChar() } else it
                    Suggestion(w, false, ctx.prefix.length, true)
                }
            }
        }

        val list = ArrayList<Suggestion>()
        if (clip != null) list.add(clip)
        if (spell.isEmpty()) {
            list.addAll(mem.take(3))
            list.addAll(predicted)
        } else {
            list.addAll(mem.take(1))
            list.add(spell[0])
            list.addAll(predicted.take(2))
            list.addAll(spell.drop(1))
        }
        val result = list.distinctBy { it.text.lowercase() }.take(3)
        if (result.isEmpty()) {
            updateStrip(false)
            return
        }

        val signature = result.joinToString("|") { "${it.text}/${it.literal}/${it.clip}" } + "#" + target
        currentSuggestions = result
        if (signature != shownSignature || suggestRow.visibility != View.VISIBLE) {
            shownSignature = signature
            for (i in 0 until suggestViews.size) {
                val tv = suggestViews[i]
                val sug = result.getOrNull(i)
                if (sug == null) {
                    tv.text = ""
                    tv.isClickable = false
                    tv.setCompoundDrawables(null, null, null, null)
                } else {
                    tv.text = if (sug.clip) sug.text.replace('\n', ' ') else if (sug.literal) "“${sug.text}”" else sug.text
                    tv.isClickable = true
                    if (sug.clip) {
                        tv.setCompoundDrawables(clipIcon, null, null, null)
                        tv.compoundDrawablePadding = dp(6)
                    } else {
                        tv.setCompoundDrawables(null, null, null, null)
                    }
                }
                val bold = sug != null && !sug.literal && !sug.clip && target != null && sug.text == target
                tv.setTypeface(null, if (bold) Typeface.BOLD else Typeface.NORMAL)
            }
        }
        updateStrip(true)
    }

    /** Подсказки из памяти: данные пользователя, частые фразы, почты и числа. */
    private fun memorySuggestions(before: String): List<Suggestion> {
        val line = before.substringAfterLast('\n')
        val token = line.substringAfterLast(' ')
        val out = ArrayList<Suggestion>()

        if (emailField || phoneField) {
            for (v in Memory.fieldValues(this, phoneField, token)) {
                out.add(Suggestion(v, false, token.length, false))
            }
            return out
        }
        if (plainField) return out

        val complete = before.length < 200 || before.contains('\n')
        val head = line.trimStart()
        if (complete && head.length >= 3) {
            for (p in Memory.phraseMatches(this, head)) {
                out.add(Suggestion(p, false, line.length, true))
            }
        }
        for (v in Memory.tokenMatches(this, token)) {
            out.add(Suggestion(v, false, token.length, !Memory.isTokenLike(v)))
        }
        return out
    }

    private fun applySuggestion(s: Suggestion) {
        val ic = currentInputConnection ?: return
        val tail = if (s.space) " " else ""
        if (s.replace >= 0) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(s.replace, 0)
            ic.commitText(s.text + tail, 1)
            ic.endBatchEdit()
            if (s.clip) ClipStore.markUsed()
        } else {
            val info = wordInfoFrom(fetchBefore(40) ?: return) ?: return
            ic.beginBatchEdit()
            ic.deleteSurroundingText(info.raw.length, 0)
            ic.commitText(s.text + tail, 1)
            ic.endBatchEdit()
            if (s.literal) SpellEngine.learn(this, s.text)
        }
        lastCorrection = null
        lastSpaceTime = 0L
        afterDelimiter = NO_CHAR
        shownSignature = ""
        if (!hasPending) requestUpdate(250)
    }

    private fun cycleTheme() {
        val hasPhoto = File(filesDir, Prefs.PHOTO_FILE).exists()
        val next = Themes.next(theme.id, hasPhoto)
        Prefs.putStr(this, Prefs.THEME, next.id)
        rebuildView()
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        requestHideSelf(0)
    }

    // ------------------------------------------------------------------ слово у курсора

    private fun wordInfoFrom(text: String): WordInfo? {
        val before = if (text.length > 40) text.substring(text.length - 40) else text
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--
        var start = i
        while (start < before.length && isApostrophe(before[start])) start++
        if (start >= before.length) return null
        if (isApostrophe(before[before.length - 1])) return null
        val word = before.substring(start)
        val prev = if (start > 0) before[start - 1] else ' '
        val skip = prev.isDigit() || prev in "@/_#.\\"
        return WordInfo(word, skip, isSentenceStart(before.substring(0, start)))
    }

    private fun isApostrophe(c: Char) = c == '\'' || c == '’'

    private fun isWordChar(c: Char) = c.isLetter() || isApostrophe(c)

    private fun isSentenceStart(head: String): Boolean {
        if (head.isEmpty() || head.all { it.isWhitespace() }) return true
        if (head.endsWith("\n")) return true
        return SENTENCE_END.containsMatchIn(head)
    }

    // ------------------------------------------------------------------ буквы и заглавные

    private fun renderKeys() {
        keysView.removeAllViews()
        letterKeys.clear()
        shiftKey = null

        if (layer == Layer.SYMBOLS) renderSymbols() else renderLetters()

        keysView.addView(makeBottomRow(), rowLp())
        appliedShift = null
        refreshLetterCase()
    }

    private fun renderLetters() {
        val rows = if (layer == Layer.RU) RU_ROWS else EN_ROWS

        if (numberRow) keysView.addView(charRow("1234567890", letterSp() - 1f, 1.2f), rowLp())
        keysView.addView(charRow(rows[0]), rowLp())

        val row1 = newRow()
        val gap = (rows[0].length - rows[1].length) / 2f
        if (gap > 0f) row1.addView(View(this), LinearLayout.LayoutParams(0, MATCH, gap))
        rows[1].forEach { addCharKey(row1, it, letterSp()) }
        if (gap > 0f) row1.addView(View(this), LinearLayout.LayoutParams(0, MATCH, gap))
        keysView.addView(row1, rowLp())

        val row2 = newRow()
        row2.addView(makeShift(), keyLp(1.5f))
        rows[2].forEach { addCharKey(row2, it, letterSp()) }
        row2.addView(makeBackspace(), keyLp(1.5f))
        keysView.addView(row2, rowLp())
    }

    /** Символы: цифры + три страницы знаков (как на Samsung, плюс типографика). */
    private fun renderSymbols() {
        val page = SYMBOL_PAGES[symbolPage]
        keysView.addView(charRow("1234567890", letterSp() - 1f, 1.2f), rowLp())
        keysView.addView(charRow(page[0], weight = 1.2f), rowLp())
        keysView.addView(charRow(page[1], weight = 1.2f), rowLp())

        val row = newRow()
        val toggle = makeKey("${symbolPage + 1}/${SYMBOL_PAGES.size}", special = true, textSp = 13f)
        toggle.setOnClickListener {
            haptic(it)
            symbolPage = (symbolPage + 1) % SYMBOL_PAGES.size
            renderKeys()
        }
        row.addView(toggle, keyLp(1.8f))
        page[2].forEach { addCharKey(row, it, letterSp(), 1.2f) }
        row.addView(makeBackspace(), keyLp(1.8f))
        keysView.addView(row, rowLp())
    }

    private fun charRow(chars: String, sp: Float = letterSp(), weight: Float = 1f): LinearLayout {
        val row = newRow()
        chars.forEach { addCharKey(row, it, sp, weight) }
        return row
    }

    private fun addCharKey(row: LinearLayout, ch: Char, sp: Float, weight: Float = 1f) {
        val key = makeKey(ch.toString(), textSp = sp)
        key.tag = ch
        val alt = ALT_CHARS[ch]
        if (alt != null) {
            // «е» с долгим нажатием «ё»: печатаем при отпускании
            key.setOnClickListener {
                haptic(it)
                onCharKey(ch)
            }
            key.setOnLongClickListener {
                haptic(it)
                val out = if (shift != Shift.OFF) alt.uppercaseChar() else alt
                lastCorrection = null
                afterDelimiter = NO_CHAR
                commit(out.toString())
                if (shift == Shift.ONCE) shift = Shift.OFF
                refreshLetterCase()
                true
            }
        }
        key.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    showPreview(v as TextView)
                    if (alt == null) {
                        // обычные буквы печатаются сразу при касании, без ожидания отпускания
                        haptic(v)
                        onCharKey(ch)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> hidePreview()
            }
            false
        }
        letterKeys.add(key)
        row.addView(key, keyLp(weight))
    }

    private fun showPreview(key: TextView) {
        if (!previewOn || !::preview.isInitialized || root.width == 0) return
        val keyLoc = IntArray(2)
        val rootLoc = IntArray(2)
        key.getLocationInWindow(keyLoc)
        root.getLocationInWindow(rootLoc)
        val bw = preview.layoutParams.width
        val bh = preview.layoutParams.height
        val left = (keyLoc[0] - rootLoc[0] + (key.width - bw) / 2)
            .coerceIn(0, maxOf(0, root.width - bw))
        val top = maxOf(0, keyLoc[1] - rootLoc[1] - bh + dp(10))
        preview.translationX = left.toFloat()
        preview.translationY = top.toFloat()
        if (preview.text != key.text) preview.text = key.text
        preview.visibility = View.VISIBLE
    }

    private fun hidePreview() {
        if (::preview.isInitialized) preview.visibility = View.GONE
    }

    private fun onCharKey(ch: Char) {
        when {
            ch in DELIMITERS -> typeDelimiter(ch)
            ch.isLetter() -> typeLetter(ch)
            else -> {
                lastCorrection = null
                afterDelimiter = NO_CHAR
                commit(ch.toString())
                if (shift == Shift.ONCE) shift = Shift.OFF
                refreshLetterCase()
            }
        }
    }

    private fun typeLetter(ch: Char) {
        lastCorrection = null
        var out = if (shift != Shift.OFF) ch.uppercaseChar() else ch
        if (smart && afterDelimiter != NO_CHAR) {
            // «привет,как» -> «привет, как»
            commit(" ")
            if (afterDelimiter == '!' || afterDelimiter == '?') out = out.uppercaseChar()
        }
        afterDelimiter = NO_CHAR
        commit(out.toString())
        if (shift == Shift.ONCE) shift = Shift.OFF
        refreshLetterCase()
    }

    private fun typeDelimiter(d: Char) {
        val ic = currentInputConnection ?: return
        lastCorrection = null
        var text = if (auto || smart) fetchBefore(40) ?: "" else ""
        if (auto) text = tryAutocorrect(d.toString(), text)
        if (smart && d in ",.!?" && text.length >= 2 && text.last() == ' ' &&
            text[text.length - 2] != ' ' && text[text.length - 2] != '\n'
        ) {
            ic.deleteSurroundingText(1, 0)
            text = text.dropLast(1)
        }
        commit(d.toString())
        afterDelimiter = if (smart && d in ",;!?" && text.isNotEmpty() && text.last().isLetter()) d else NO_CHAR
        if (shift == Shift.ONCE) shift = Shift.OFF
        refreshLetterCase()
    }

    private fun pressSpace() {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        lastCorrection = null
        afterDelimiter = NO_CHAR
        val text = if (auto || smart) fetchBefore(40) ?: "" else ""
        if (smart && now - lastSpaceTime < 700 && text.length >= 2 && text.last() == ' ') {
            // двойной пробел -> «. »
            val c = text[text.length - 2]
            if (c.isLetterOrDigit() || c in ")\"»") {
                ic.deleteSurroundingText(1, 0)
                commit(". ")
                lastSpaceTime = 0L
                return
            }
        }
        if (auto) tryAutocorrect(" ", text)
        commit(" ")
        lastSpaceTime = now
    }

    private fun pressEnter() {
        lastCorrection = null
        afterDelimiter = NO_CHAR
        if (auto) tryAutocorrect("\n", fetchBefore(40) ?: "")
        lastCorrection = null
        val info = currentInputEditorInfo
        val options = info?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (info != null && !noEnterAction &&
            action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            currentInputConnection?.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    /** Если набранное слово — опечатка, заменяет его; запоминает замену для отката. */
    private fun tryAutocorrect(delimiter: String, before: String): String {
        val ic = currentInputConnection ?: return before
        val info = wordInfoFrom(before) ?: return before
        if (info.skip) return before
        val fixed = SpellEngine.autoCorrection(info.raw, info.sentenceStart) ?: return before
        if (fixed == info.raw) return before
        ic.beginBatchEdit()
        ic.deleteSurroundingText(info.raw.length, 0)
        ic.commitText(fixed, 1)
        ic.endBatchEdit()
        lastCorrection = Correction(info.raw, fixed, delimiter)
        return before.dropLast(info.raw.length) + fixed
    }

    private fun makeShift(): TextView {
        val key = makeGlyphKey(Icons.SHIFT, theme.text, special = true)
        key.setOnClickListener {
            haptic(it)
            val now = SystemClock.uptimeMillis()
            shift = when {
                shift == Shift.CAPS -> Shift.OFF
                shift == Shift.ONCE && now - lastShiftTap < 400 -> Shift.CAPS
                shift == Shift.ONCE -> Shift.OFF
                else -> Shift.ONCE
            }
            lastShiftTap = now
            refreshLetterCase()
        }
        shiftKey = key as GlyphKey
        return key
    }

    private fun refreshLetterCase() {
        if (appliedShift == shift) return
        appliedShift = shift
        for (key in letterKeys) {
            val ch = key.tag as Char
            key.text = if (shift != Shift.OFF) ch.uppercaseChar().toString() else ch.toString()
        }
        shiftKey?.let { key ->
            val active = shift != Shift.OFF
            val icon = when (shift) {
                Shift.CAPS -> Icons.SHIFT_CAPS
                Shift.ONCE -> Icons.SHIFT_ON
                else -> Icons.SHIFT
            }
            key.glyph = VectorGlyph(icon, if (active) theme.accentText else theme.text)
            key.invalidate()
            val fill = if (active) theme.accent else withAlpha(theme.keySpecial, theme.keyAlpha)
            key.background = keyBackground(fill)
        }
    }

    private fun updateAutoShift(before: String) {
        if (layer == Layer.SYMBOLS || shift == Shift.CAPS || plainField || !autocapOn) return
        var cap = isSentenceStart(before.takeLast(12))
        if (before.isEmpty()) {
            val ic = currentInputConnection
            val info = currentInputEditorInfo
            if (ic != null && info != null && info.inputType != 0) {
                cap = cap || ic.getCursorCapsMode(info.inputType) != 0
            }
        }
        shift = if (cap) Shift.ONCE else Shift.OFF
    }

    // ------------------------------------------------------------------ нижний ряд

    private fun makeBottomRow(): LinearLayout {
        val row = newRow()
        val small = 14f

        val symbolsLabel = when {
            layer != Layer.SYMBOLS -> "!#1"
            lastLetterLayer == Layer.RU -> "АБВ"
            else -> "ABC"
        }
        val symbols = makeKey(symbolsLabel, special = true, textSp = small)
        symbols.setOnClickListener {
            haptic(it)
            layer = if (layer == Layer.SYMBOLS) lastLetterLayer else Layer.SYMBOLS
            if (layer == Layer.SYMBOLS) symbolPage = 0
            renderKeys()
        }

        // Кнопка смайлов открывает вкладку со смайлами.
        val emoji = makeGlyphKey(Icons.EMOJI, null, special = true, sizeDp = 26)
        emoji.setOnClickListener {
            haptic(it)
            showEmoji()
        }

        // Маленькая кнопка с мечетью открывает панель фраз и смайлов.
        val mosque = makeGlyphKey(Icons.MOSQUE, null, special = true, sizeDp = 30)
        mosque.setOnClickListener {
            haptic(it)
            togglePhrases()
        }

        val ru = lastLetterLayer == Layer.RU
        val space = SpaceKey(this)
        makeKey(if (ru) "Русский" else "English", textSp = 14f, into = space)
        space.tint = theme.hint
        space.setTextColor(theme.hint)
        attachSpaceGestures(space)

        val enterText = enterLabel()
        val enterAccent = enterIsAccent()
        val plainEnter = enterText == "ввод" || enterText == "return" || enterText == "⏎"
        val enter = if (plainEnter) {
            makeGlyphKey(Icons.RETURN, theme.text, special = true, accent = false, sizeDp = 22)
        } else {
            makeKey(
                enterText,
                special = !enterAccent,
                accent = enterAccent,
                textSp = if (enterText.length > 2) 13f else 18f
            )
        }
        enter.setOnClickListener {
            haptic(it)
            pressEnter()
        }

        row.addView(symbols, keyLp(1.4f))
        row.addView(emoji, keyLp(1f))
        row.addView(mosque, keyLp(1f))
        row.addView(simpleKey(','), keyLp(1f))
        row.addView(space, keyLp(2.6f))
        row.addView(simpleKey('.'), keyLp(1f))
        row.addView(enter, keyLp(1.9f))
        return row
    }

    /** Пробел: касание — пробел, свайп влево/вправо — смена языка, долгое нажатие — выбор клавиатуры. */
    private fun attachSpaceGestures(key: TextView) {
        var downX = 0f
        var swiped = false
        var longPressed = false
        val longPress = Runnable {
            longPressed = true
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        key.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    swiped = false
                    longPressed = false
                    v.isPressed = true
                    handler.postDelayed(longPress, 600)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!swiped && abs(event.x - downX) > dp(40)) {
                        swiped = true
                        handler.removeCallbacks(longPress)
                        v.isPressed = false
                        haptic(v)
                        toggleLanguage()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    handler.removeCallbacks(longPress)
                    if (!swiped && !longPressed) {
                        haptic(v)
                        pressSpace()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(longPress)
                }
            }
            true
        }
    }

    private fun toggleLanguage() {
        lastLetterLayer = if (lastLetterLayer == Layer.RU) Layer.EN else Layer.RU
        if (layer != Layer.SYMBOLS) layer = lastLetterLayer
        renderKeys()
    }

    private fun simpleKey(ch: Char): TextView {
        val key = makeKey(ch.toString())
        key.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                haptic(v)
                onCharKey(ch)
            }
            false
        }
        return key
    }

    private fun imeAction(): Int {
        val options = currentInputEditorInfo?.imeOptions ?: 0
        if ((options and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return EditorInfo.IME_ACTION_NONE
        return options and EditorInfo.IME_MASK_ACTION
    }

    private fun enterLabel(): String {
        val ru = lastLetterLayer == Layer.RU
        return when (imeAction()) {
            EditorInfo.IME_ACTION_SEND -> "➤"
            EditorInfo.IME_ACTION_SEARCH -> if (ru) "поиск" else "search"
            EditorInfo.IME_ACTION_GO -> if (ru) "перейти" else "go"
            EditorInfo.IME_ACTION_NEXT -> if (ru) "далее" else "next"
            EditorInfo.IME_ACTION_DONE -> if (ru) "готово" else "done"
            else -> if (iosLayout) (if (ru) "ввод" else "return") else "⏎"
        }
    }

    private fun enterIsAccent(): Boolean = when (imeAction()) {
        EditorInfo.IME_ACTION_SEND,
        EditorInfo.IME_ACTION_SEARCH,
        EditorInfo.IME_ACTION_GO,
        EditorInfo.IME_ACTION_DONE -> true
        else -> false
    }

    // ------------------------------------------------------------------ удаление

    private fun makeBackspace(): TextView {
        val key = makeGlyphKey(Icons.BACKSPACE, theme.text, special = true)
        key.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    haptic(v)
                    deleteOne()
                    handler.postDelayed(deleteRunnable, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(deleteRunnable)
                }
            }
            true
        }
        return key
    }

    private fun deleteOne() {
        afterDelimiter = NO_CHAR
        if (!hasPending) requestUpdate(250)
        val correction = lastCorrection
        if (correction != null) {
            lastCorrection = null
            val ic = currentInputConnection
            val expected = correction.fixed + correction.delimiter
            val before = ic?.getTextBeforeCursor(expected.length, 0)?.toString()
            if (ic != null && before == expected) {
                // откат автоисправления: возвращаем слово как набрано и запоминаем его
                ic.beginBatchEdit()
                ic.deleteSurroundingText(expected.length, 0)
                ic.commitText(correction.original, 1)
                ic.endBatchEdit()
                SpellEngine.learn(this, correction.original)
                return
            }
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    // ------------------------------------------------------------------ панель фраз и смайлов

    private fun buildPhrasesView(): LinearLayout {
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL

        val back = makeKey("АБВ", accent = true, textSp = 14f)
        back.setTypeface(null, Typeface.BOLD)
        back.setOnClickListener {
            haptic(it)
            showKeys()
        }
        val backLp = LinearLayout.LayoutParams(dp(60), dp(40))
        backLp.setMargins(dp(3), 0, dp(6), 0)
        top.addView(back, backLp)

        val scroller = HorizontalScrollView(this)
        scroller.isHorizontalScrollBarEnabled = false
        scroller.overScrollMode = View.OVER_SCROLL_NEVER
        scroller.isFillViewport = true
        tabsRow = LinearLayout(this)
        tabsRow.orientation = LinearLayout.HORIZONTAL
        scroller.addView(tabsRow, FrameLayout.LayoutParams(WRAP, MATCH))
        top.addView(scroller, LinearLayout.LayoutParams(0, dp(40), 1f))

        val del = makeBackspace()
        val delLp = LinearLayout.LayoutParams(dp(60), dp(40))
        delLp.setMargins(dp(6), 0, dp(3), 0)
        top.addView(del, delLp)

        panel.addView(top, LinearLayout.LayoutParams(MATCH, dp(44)))

        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        chipsFlow = FlowLayout(this)
        chipsFlow.setSpacing(dp(6), dp(6))
        chipsFlow.setPadding(dp(3), dp(6), dp(3), dp(6))
        scroll.addView(chipsFlow, FrameLayout.LayoutParams(MATCH, WRAP))
        panel.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        return panel
    }

    private fun togglePhrases() {
        if (phrasesView.visibility == View.VISIBLE) showKeys() else showPhrases()
    }

    private fun showPhrases() {
        keysView.visibility = View.GONE
        phrasesView.visibility = View.VISIBLE
        renderPhrases()
        refreshSuggestions()
    }

    private fun showKeys() {
        if (!::phrasesView.isInitialized) return
        phrasesView.visibility = View.GONE
        keysView.visibility = View.VISIBLE
        refreshSuggestions()
    }

    private fun renderPhrases() {
        val categories = PhraseLibrary.categories(this)
        if (categoryIndex >= categories.size) categoryIndex = 0

        tabsRow.removeAllViews()
        categories.forEachIndexed { index, category ->
            val selected = index == categoryIndex
            val tab = TextView(this)
            tab.text = category.title
            tab.gravity = Gravity.CENTER
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (category.emoji) 18f else 14f)
            tab.setPadding(dp(14), 0, dp(14), 0)
            tab.setTextColor(if (selected) theme.accentText else theme.text)
            val fill = if (selected) theme.accent else withAlpha(theme.key, theme.keyAlpha)
            tab.background = keyBackground(fill, 16, false)
            tab.isClickable = true
            tab.setOnClickListener {
                haptic(it)
                categoryIndex = index
                renderPhrases()
            }
            val lp = LinearLayout.LayoutParams(WRAP, MATCH)
            lp.setMargins(dp(3), dp(3), dp(3), dp(3))
            tabsRow.addView(tab, lp)
        }

        val category = categories[categoryIndex]
        chipsFlow.removeAllViews()
        if (category.phrases.isEmpty() && !category.emoji) {
            addEmptyHint(category)
        }
        category.phrases.forEachIndexed { index, phrase ->
            val value = category.values?.getOrNull(index) ?: phrase
            val chip = TextView(this)
            chip.text = phrase
            chip.isClickable = true
            if (category.emoji) {
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                chip.setPadding(dp(8), dp(4), dp(8), dp(4))
                chip.background = emojiBackground()
                chip.setOnClickListener {
                    haptic(it)
                    lastCorrection = null
                    commit(phrase)
                }
            } else {
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                chip.setTextColor(theme.text)
                chip.setPadding(dp(14), dp(9), dp(14), dp(9))
                chip.background = keyBackground(withAlpha(theme.key, theme.keyAlpha), 18)
                chip.setOnClickListener {
                    haptic(it)
                    lastCorrection = null
                    lastSpaceTime = 0L
                    val tail = when {
                        category.kind == KIND_DATA || category.kind == KIND_CLIPBOARD -> ""
                        Memory.isTokenLike(value) -> ""
                        else -> " "
                    }
                    commit(value + tail)
                }
                if (category.kind == KIND_FREQUENT || category.kind == KIND_CLIPBOARD) {
                    chip.setOnLongClickListener {
                        haptic(it)
                        if (category.kind == KIND_CLIPBOARD) ClipStore.remove(value) else Memory.forget(this, value)
                        renderPhrases()
                        true
                    }
                }
            }
            chipsFlow.addView(chip)
        }
        (chipsFlow.parent as? ScrollView)?.scrollTo(0, 0)
    }

    private fun addEmptyHint(category: PhraseCategory) {
        val tv = TextView(this)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        tv.setTextColor(theme.hint)
        tv.setPadding(dp(10), dp(8), dp(10), dp(8))
        if (category.kind == KIND_DATA) {
            tv.text = "＋ Добавить свои данные (почта, ФИО, телефон…)"
            tv.setTextColor(theme.text)
            tv.background = keyBackground(withAlpha(theme.key, theme.keyAlpha), 18)
            tv.isClickable = true
            tv.setOnClickListener {
                haptic(it)
                openSettings()
            }
        } else {
            tv.text = if (category.kind == KIND_CLIPBOARD) {
                "Здесь появится всё, что вы скопируете. Нажмите и держите, чтобы убрать."
            } else {
                "Здесь появятся фразы, почты и числа, которые вы часто отправляете. " +
                    "Нажмите и держите, чтобы убрать."
            }
        }
        chipsFlow.addView(tv)
    }

    private fun emojiBackground(): Drawable {
        val states = StateListDrawable()
        states.addState(
            intArrayOf(android.R.attr.state_pressed),
            roundRect(withAlpha(theme.keySpecial, 255), 10)
        )
        states.addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        return states
    }

    // ------------------------------------------------------------------ вспомогательное

    private fun commit(text: String) {
        if (!hasPending) requestUpdate(250)
        currentInputConnection?.commitText(text, 1)
    }

    private fun haptic(view: View) {
        if (hapticsOn) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun makeKey(
        label: String,
        special: Boolean = false,
        accent: Boolean = false,
        textSp: Float = letterSp(),
        into: TextView? = null
    ): TextView {
        val key = into ?: TextView(this)
        key.text = label
        key.gravity = Gravity.CENTER
        key.includeFontPadding = false
        key.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
        key.setTextColor(if (accent) theme.accentText else theme.text)
        val base = when {
            accent -> theme.accent
            special -> theme.keySpecial
            else -> theme.key
        }
        key.background = keyBackground(if (accent) base else withAlpha(base, theme.keyAlpha))
        key.isClickable = true
        return key
    }

    /** Клавиша с нарисованным значком (стрелка, стереть, мечеть…). [tint] = null — цветной значок. */
    private fun makeGlyphKey(
        icon: Icon,
        tint: Int?,
        special: Boolean = false,
        accent: Boolean = false,
        sizeDp: Int = 24
    ): TextView {
        val key = GlyphKey(this)
        makeKey("", special = special, accent = accent, into = key)
        key.glyphPx = dp(sizeDp)
        key.glyph = if (tint == null) VectorGlyph(icon) else VectorGlyph(icon, tint)
        return key
    }

    private fun keyBackground(
        fill: Int,
        radius: Int = keyRadiusDp,
        shadow: Boolean = theme.shadow
    ): Drawable {
        val states = StateListDrawable()
        if (glassOn) {
            val r = dp(maxOf(radius, 9)).toFloat()
            val depth = dp(2).toFloat()
            states.addState(
                intArrayOf(android.R.attr.state_pressed),
                GlassDrawable(shade(fill, 0.9f), r, depth, true)
            )
            states.addState(intArrayOf(), GlassDrawable(fill, r, depth, false))
            return states
        }
        states.addState(
            intArrayOf(android.R.attr.state_pressed),
            keyShape(shade(fill, 0.82f), radius, shadow)
        )
        states.addState(intArrayOf(), keyShape(fill, radius, shadow))
        return states
    }

    private fun keyShape(fill: Int, radius: Int, shadow: Boolean): Drawable {
        val top = roundRect(fill, radius)
        if (!shadow) return top
        val bottom = roundRect(Color.argb(90, 0, 0, 0), radius)
        val layers = LayerDrawable(arrayOf<Drawable>(bottom, top))
        layers.setLayerInset(1, 0, 0, 0, dp(1))
        return layers
    }

    private fun roundRect(fill: Int, radius: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(fill)
        d.cornerRadius = dp(radius).toFloat()
        return d
    }

    private fun shade(c: Int, factor: Float): Int = Color.argb(
        Color.alpha(c),
        (Color.red(c) * factor).toInt(),
        (Color.green(c) * factor).toInt(),
        (Color.blue(c) * factor).toInt()
    )

    private fun withAlpha(c: Int, alpha: Int): Int =
        Color.argb(Color.alpha(c) * alpha / 255, Color.red(c), Color.green(c), Color.blue(c))

    private fun newRow(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        return row
    }

    private fun rowLp() = LinearLayout.LayoutParams(MATCH, 0, 1f)

    private fun keyLp(weight: Float): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, MATCH, weight)
        lp.setMargins(dp(3), dp(if (iosLayout) 5 else 4), dp(3), dp(if (iosLayout) 5 else 4))
        return lp
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val STRIP_DP = 36
        private const val NO_CHAR = '\u0000'

        private const val DELIMITERS = ",.!?:;"

        private val RU_ROWS = listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю")
        private val EN_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        /** Три страницы символов; в каждой: два ряда по 10 знаков и ряд из 7. */
        private val SYMBOL_PAGES = listOf(
            listOf("+\u00D7\u00F7=/_<>[]", "!@#\u20BD%^&*()", "-'\":;,?"),
            listOf("`~\\|{}\u20AC\u00A3\u00A5\$", "\u00B0\u2022\u25CB\u25CF\u25A1\u25A0\u2664\u2661\u25C7\u2667", "\u2606\u25AA\u00A4\u00AB\u00BB\u00A1\u00BF"),
            listOf("\u2116\u00A7\u00A9\u00AE\u2122\u00B1\u2248\u2260\u2264\u2265", "\u2026\u2014\u2013\u2190\u2192\u2191\u2193\u2713\u2665\u2605", "\u201C\u201D\u201E\u2030\u00B6\u221E\u221A")
        )

        /** Долгое нажатие на букву вводит запасной символ. */
        private val ALT_CHARS = mapOf('е' to 'ё')

        private val SENTENCE_END = Regex("[.!?…][\"»”)]*\\s+\$")

        /** Поля, где подсказки, автоисправление и автопунктуация выключены. */
        private val SECRET_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )

        private val PLAIN_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI
        )
    }
}
