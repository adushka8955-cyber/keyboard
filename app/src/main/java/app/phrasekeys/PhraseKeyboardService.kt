package app.phrasekeys

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
    private var toolbarOpen = false

    // снимок настроек
    private var theme: KeyboardTheme = Themes.all[0]
    private var iosLayout = true
    private var suggestionsOn = true
    private var autocorrectOn = true
    private var punctOn = true
    private var autocapOn = true
    private var hapticsOn = true
    private var previewOn = true
    private var numberRow = false
    private var heightMode = "mid"
    private var shape = "theme"

    // свойства текущего поля ввода
    private var plainField = false
    private var noSuggestFlag = false

    private lateinit var root: FrameLayout
    private lateinit var keysView: LinearLayout
    private lateinit var phrasesView: LinearLayout
    private lateinit var tabsRow: LinearLayout
    private lateinit var chipsFlow: FlowLayout
    private lateinit var arrow: TextView
    private lateinit var toolbar: LinearLayout
    private lateinit var suggestRow: LinearLayout
    private lateinit var preview: TextView

    private val suggestViews = ArrayList<TextView>()
    private var currentSuggestions: List<Suggestion> = emptyList()
    private val letterKeys = ArrayList<TextView>()
    private var shiftKey: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
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
        get() = autocorrectOn && !plainField && !noSuggestFlag && SpellEngine.isReady()

    // ------------------------------------------------------------------ жизненный цикл

    override fun onCreate() {
        super.onCreate()
        SpellEngine.ensureLoaded(this)
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
        numberRow = Prefs.bool(this, Prefs.NUMBER_ROW, false)
        heightMode = Prefs.str(this, Prefs.HEIGHT, "mid")
        shape = Prefs.str(this, Prefs.SHAPE, "theme")
    }

    override fun onCreateInputView(): View {
        readPrefs()
        builtVersion = Prefs.version
        letterKeys.clear()
        suggestViews.clear()
        shiftKey = null

        val frame = FrameLayout(this)
        root = frame
        frame.background = backgroundDrawable()
        addPhotoLayer(frame)

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(3), dp(2), dp(3), dp(6))

        content.addView(buildStrip(), LinearLayout.LayoutParams(MATCH, dp(STRIP_DP)))

        keysView = LinearLayout(this)
        keysView.orientation = LinearLayout.VERTICAL
        phrasesView = buildPhrasesView()
        phrasesView.visibility = View.GONE

        val body = FrameLayout(this)
        body.addView(keysView, FrameLayout.LayoutParams(MATCH, MATCH))
        body.addView(phrasesView, FrameLayout.LayoutParams(MATCH, MATCH))
        content.addView(body, LinearLayout.LayoutParams(MATCH, dp(bodyHeightDp())))

        frame.addView(content, FrameLayout.LayoutParams(MATCH, WRAP))

        preview = TextView(this)
        preview.gravity = Gravity.CENTER
        preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        preview.setTextColor(theme.text)
        preview.background = roundRect(withAlpha(theme.key, 255), 10)
        preview.elevation = dp(6).toFloat()
        preview.visibility = View.GONE
        frame.addView(preview, FrameLayout.LayoutParams(dp(60), dp(56)))

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

        layer = when (inputClass) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME -> Layer.SYMBOLS
            else -> if (layer == Layer.SYMBOLS) lastLetterLayer else layer
        }
        lastCorrection = null
        shift = Shift.OFF
        updateAutoShift()
        if (!restarting) showKeys()
        renderKeys()
        refreshSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        handler.removeCallbacks(deleteRunnable)
        lastCorrection = null
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
        updateAutoShift()
        refreshLetterCase()
        refreshSuggestions()
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
        val landscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        var rowDp = when (heightMode) {
            "low" -> 44f
            "high" -> 56f
            else -> 50f
        }
        if (landscape) rowDp *= 0.78f
        val rows = if (numberRow) 5 else 4
        return (rowDp * rows).toInt()
    }

    // ------------------------------------------------------------------ строка подсказок

    private fun buildStrip(): LinearLayout {
        val strip = LinearLayout(this)
        strip.orientation = LinearLayout.HORIZONTAL
        strip.gravity = Gravity.CENTER_VERTICAL

        // Маленькая стрелка слева: открывает/скрывает меню (Фразы, Тема, Настройки).
        arrow = TextView(this)
        arrow.gravity = Gravity.CENTER
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        arrow.setTextColor(theme.hint)
        arrow.isClickable = true
        arrow.setOnClickListener {
            haptic(it)
            toolbarOpen = !toolbarOpen
            refreshSuggestions()
        }
        strip.addView(arrow, LinearLayout.LayoutParams(dp(40), MATCH))

        val content = FrameLayout(this)

        toolbar = LinearLayout(this)
        toolbar.orientation = LinearLayout.HORIZONTAL
        toolbar.gravity = Gravity.CENTER_VERTICAL
        toolbar.addView(chip("Фразы", accent = true) { closeToolbar(); togglePhrases() }, chipLp())
        toolbar.addView(chip("Тема") { closeToolbar(); cycleTheme() }, chipLp())
        toolbar.addView(chip("Настройки") { closeToolbar(); openSettings() }, chipLp())
        content.addView(toolbar, FrameLayout.LayoutParams(MATCH, MATCH))

        suggestRow = LinearLayout(this)
        suggestRow.orientation = LinearLayout.HORIZONTAL
        suggestRow.gravity = Gravity.CENTER_VERTICAL
        suggestRow.visibility = View.GONE
        for (i in 0 until 3) {
            val tv = TextView(this)
            tv.gravity = Gravity.CENTER
            tv.setSingleLine(true)
            tv.ellipsize = TextUtils.TruncateAt.END
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
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
                suggestRow.addView(divider, LinearLayout.LayoutParams(dp(1), dp(20)))
            }
        }
        content.addView(suggestRow, FrameLayout.LayoutParams(MATCH, MATCH))

        strip.addView(content, LinearLayout.LayoutParams(0, MATCH, 1f))
        return strip
    }

    private fun closeToolbar() {
        toolbarOpen = false
        refreshSuggestions()
    }

    /** Меню открыто — показываем его; иначе — подсказки (если есть). */
    private fun updateStrip(showSuggestions: Boolean) {
        arrow.text = if (toolbarOpen) "\u2039" else "\u203A"
        toolbar.visibility = if (toolbarOpen) View.VISIBLE else View.GONE
        suggestRow.visibility =
            if (!toolbarOpen && showSuggestions) View.VISIBLE else View.GONE
    }

    private fun chip(label: String, accent: Boolean = false, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.gravity = Gravity.CENTER
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        tv.setPadding(dp(14), 0, dp(14), 0)
        tv.setTextColor(if (accent) theme.accentText else theme.text)
        val fill = if (accent) theme.accent else withAlpha(theme.keySpecial, theme.keyAlpha)
        tv.background = keyBackground(fill, 16, false)
        tv.isClickable = true
        tv.setOnClickListener {
            haptic(it)
            onClick()
        }
        return tv
    }

    private fun chipLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(WRAP, MATCH)
        lp.setMargins(dp(3), dp(5), dp(3), dp(5))
        return lp
    }

    private fun refreshSuggestions() {
        if (!::suggestRow.isInitialized || !::phrasesView.isInitialized || !::arrow.isInitialized) return
        if (!suggestionsOn || plainField || noSuggestFlag ||
            phrasesView.visibility == View.VISIBLE
        ) {
            updateStrip(false)
            return
        }
        val info = currentWordInfo()
        if (info == null || info.skip) {
            updateStrip(false)
            return
        }
        val list = SpellEngine.suggest(info.raw)
        if (list.isEmpty()) {
            updateStrip(false)
            return
        }
        currentSuggestions = list
        val target = if (auto) SpellEngine.autoCorrection(info.raw, info.sentenceStart) else null
        for (i in 0 until suggestViews.size) {
            val tv = suggestViews[i]
            val s = list.getOrNull(i)
            if (s == null) {
                tv.text = ""
                tv.isClickable = false
            } else {
                tv.text = if (s.literal) "“${s.text}”" else s.text
                tv.isClickable = true
            }
            val bold = s != null && !s.literal && target != null && s.text == target
            tv.setTypeface(null, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        updateStrip(true)
    }

    private fun applySuggestion(s: Suggestion) {
        val ic = currentInputConnection ?: return
        val info = currentWordInfo() ?: return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(info.raw.length, 0)
        ic.commitText(s.text + " ", 1)
        ic.endBatchEdit()
        if (s.literal) SpellEngine.learn(this, s.text)
        lastCorrection = null
        lastSpaceTime = 0L
    }

    private fun cycleTheme() {
        val hasPhoto = File(filesDir, Prefs.PHOTO_FILE).exists()
        val next = Themes.next(theme.id, hasPhoto)
        Prefs.putStr(this, Prefs.THEME, next.id)
        handler.post { setInputView(onCreateInputView()) }
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        requestHideSelf(0)
    }

    // ------------------------------------------------------------------ слово у курсора

    private fun currentWordInfo(): WordInfo? {
        val ic = currentInputConnection ?: return null
        val before = ic.getTextBeforeCursor(40, 0)?.toString() ?: return null
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

        val rows = when (layer) {
            Layer.RU -> RU_ROWS
            Layer.EN -> EN_ROWS
            Layer.SYMBOLS -> SYM_ROWS
        }

        if (numberRow && layer != Layer.SYMBOLS) {
            val numbers = newRow()
            "1234567890".forEach { addCharKey(numbers, it) }
            keysView.addView(numbers, rowLp())
        }

        val row0 = newRow()
        rows[0].forEach { addCharKey(row0, it) }
        keysView.addView(row0, rowLp())

        val row1 = newRow()
        val gap = (rows[0].length - rows[1].length) / 2f
        if (gap > 0f) row1.addView(View(this), LinearLayout.LayoutParams(0, MATCH, gap))
        rows[1].forEach { addCharKey(row1, it) }
        if (gap > 0f) row1.addView(View(this), LinearLayout.LayoutParams(0, MATCH, gap))
        keysView.addView(row1, rowLp())

        val row2 = newRow()
        if (layer == Layer.SYMBOLS) {
            rows[2].forEach { addCharKey(row2, it) }
            row2.addView(makeBackspace(), keyLp(2f))
        } else {
            row2.addView(makeShift(), keyLp(1.5f))
            rows[2].forEach { addCharKey(row2, it) }
            row2.addView(makeBackspace(), keyLp(1.5f))
        }
        keysView.addView(row2, rowLp())

        keysView.addView(makeBottomRow(), rowLp())
        refreshLetterCase()
    }

    private fun addCharKey(row: LinearLayout, ch: Char) {
        val key = makeKey(ch.toString())
        key.tag = ch
        key.setOnClickListener {
            haptic(it)
            onCharKey(ch)
        }
        val alt = ALT_CHARS[ch]
        if (alt != null) {
            key.setOnLongClickListener {
                haptic(it)
                val out = if (shift != Shift.OFF) alt.uppercaseChar() else alt
                lastCorrection = null
                commit(out.toString())
                if (shift == Shift.ONCE) shift = Shift.OFF
                refreshLetterCase()
                true
            }
        }
        key.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> showPreview(v as TextView)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> hidePreview()
            }
            false
        }
        letterKeys.add(key)
        row.addView(key, keyLp(1f))
    }

    private fun showPreview(key: TextView) {
        if (!previewOn || !::preview.isInitialized || root.width == 0) return
        val keyLoc = IntArray(2)
        val rootLoc = IntArray(2)
        key.getLocationInWindow(keyLoc)
        root.getLocationInWindow(rootLoc)
        val bw = (key.width * 1.5f).toInt()
        val bh = dp(56)
        val left = (keyLoc[0] - rootLoc[0] + (key.width - bw) / 2)
            .coerceIn(0, maxOf(0, root.width - bw))
        val top = maxOf(0, keyLoc[1] - rootLoc[1] - bh + dp(10))
        val lp = FrameLayout.LayoutParams(bw, bh)
        lp.leftMargin = left
        lp.topMargin = top
        preview.layoutParams = lp
        preview.text = key.text
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
                commit(ch.toString())
                if (shift == Shift.ONCE) shift = Shift.OFF
                refreshLetterCase()
            }
        }
    }

    private fun typeLetter(ch: Char) {
        val ic = currentInputConnection ?: return
        lastCorrection = null
        toolbarOpen = false
        var out = if (shift != Shift.OFF) ch.uppercaseChar() else ch
        if (smart) {
            // «привет,как» -> «привет, как»
            val before = ic.getTextBeforeCursor(2, 0)?.toString()
            if (before != null && before.length == 2 &&
                before[1] in ",;!?" && before[0].isLetter()
            ) {
                commit(" ")
                if (before[1] == '!' || before[1] == '?') out = out.uppercaseChar()
            }
        }
        commit(out.toString())
        if (shift == Shift.ONCE) shift = Shift.OFF
        refreshLetterCase()
    }

    private fun typeDelimiter(d: Char) {
        val ic = currentInputConnection ?: return
        lastCorrection = null
        if (auto) tryAutocorrect(d.toString())
        if (smart && d in ",.!?") {
            // «слово .» -> «слово.»
            val before = ic.getTextBeforeCursor(2, 0)?.toString()
            if (before != null && before.length == 2 && before[1] == ' ' &&
                before[0] != ' ' && before[0] != '\n'
            ) {
                ic.deleteSurroundingText(1, 0)
            }
        }
        commit(d.toString())
        if (shift == Shift.ONCE) shift = Shift.OFF
        refreshLetterCase()
    }

    private fun pressSpace() {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        lastCorrection = null
        if (smart && now - lastSpaceTime < 700) {
            // двойной пробел -> «. »
            val before = ic.getTextBeforeCursor(2, 0)?.toString()
            if (before != null && before.length == 2 && before[1] == ' ' &&
                (before[0].isLetterOrDigit() || before[0] in ")\"»")
            ) {
                ic.deleteSurroundingText(1, 0)
                commit(". ")
                lastSpaceTime = 0L
                return
            }
        }
        if (auto) tryAutocorrect(" ")
        commit(" ")
        lastSpaceTime = now
    }

    private fun pressEnter() {
        lastCorrection = null
        if (auto) tryAutocorrect("\n")
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
    private fun tryAutocorrect(delimiter: String) {
        val ic = currentInputConnection ?: return
        val info = currentWordInfo() ?: return
        if (info.skip) return
        val fixed = SpellEngine.autoCorrection(info.raw, info.sentenceStart) ?: return
        if (fixed == info.raw) return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(info.raw.length, 0)
        ic.commitText(fixed, 1)
        ic.endBatchEdit()
        lastCorrection = Correction(info.raw, fixed, delimiter)
    }

    private fun makeShift(): TextView {
        val key = makeKey("⇧", special = true)
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
        shiftKey = key
        return key
    }

    private fun refreshLetterCase() {
        for (key in letterKeys) {
            val ch = key.tag as Char
            key.text = if (shift != Shift.OFF) ch.uppercaseChar().toString() else ch.toString()
        }
        shiftKey?.let { key ->
            key.text = if (shift == Shift.CAPS) "⇪" else "⇧"
            val active = shift != Shift.OFF
            key.setTextColor(if (active) theme.accentText else theme.text)
            val fill = if (active) theme.accent else withAlpha(theme.keySpecial, theme.keyAlpha)
            key.background = keyBackground(fill)
        }
    }

    private fun updateAutoShift() {
        if (layer == Layer.SYMBOLS || shift == Shift.CAPS || plainField || !autocapOn) return
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        var cap = false
        if (info != null && info.inputType != 0) {
            cap = ic.getCursorCapsMode(info.inputType) != 0
        }
        if (!cap) {
            val before = ic.getTextBeforeCursor(12, 0)?.toString()
            if (before != null) cap = isSentenceStart(before)
        }
        shift = if (cap) Shift.ONCE else Shift.OFF
    }

    // ------------------------------------------------------------------ нижний ряд

    private fun makeBottomRow(): LinearLayout {
        val row = newRow()
        val small = 14f

        val symbolsLabel = when {
            layer != Layer.SYMBOLS -> "?123"
            lastLetterLayer == Layer.RU -> "АБВ"
            else -> "ABC"
        }
        val symbols = makeKey(symbolsLabel, special = true, textSp = small)
        symbols.setOnClickListener {
            haptic(it)
            layer = if (layer == Layer.SYMBOLS) lastLetterLayer else Layer.SYMBOLS
            renderKeys()
        }

        // Маленькая кнопка с мечетью открывает панель фраз и смайлов.
        val mosque = makeKey("\uD83D\uDD4C", special = true, textSp = 18f)
        mosque.setOnClickListener {
            haptic(it)
            togglePhrases()
        }

        val ru = lastLetterLayer == Layer.RU
        val space = makeKey(if (ru) "\u2039   Русский   \u203A" else "\u2039   English   \u203A", textSp = 13f)
        space.setTextColor(theme.hint)
        attachSpaceGestures(space)

        val enterText = enterLabel()
        val enterAccent = enterIsAccent()
        val enter = makeKey(
            enterText,
            special = !enterAccent,
            accent = enterAccent,
            textSp = if (enterText.length > 2) small else 20f
        )
        enter.setOnClickListener {
            haptic(it)
            pressEnter()
        }

        row.addView(symbols, keyLp(1.4f))
        row.addView(mosque, keyLp(1f))
        row.addView(simpleKey(','), keyLp(1f))
        row.addView(space, keyLp(3.6f))
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
        key.setOnClickListener {
            haptic(it)
            onCharKey(ch)
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
        val key = makeKey("⌫", special = true)
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
        for (phrase in category.phrases) {
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
                    commit("$phrase ")
                }
            }
            chipsFlow.addView(chip)
        }
        (chipsFlow.parent as? ScrollView)?.scrollTo(0, 0)
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
        currentInputConnection?.commitText(text, 1)
    }

    private fun haptic(view: View) {
        if (hapticsOn) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun makeKey(
        label: String,
        special: Boolean = false,
        accent: Boolean = false,
        textSp: Float = if (iosLayout) 22f else 20f
    ): TextView {
        val key = TextView(this)
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

    private fun keyBackground(
        fill: Int,
        radius: Int = keyRadiusDp,
        shadow: Boolean = theme.shadow
    ): Drawable {
        val states = StateListDrawable()
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
        private const val STRIP_DP = 44

        private const val DELIMITERS = ",.!?:;"

        private val RU_ROWS = listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю")
        private val EN_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private val SYM_ROWS = listOf("1234567890", "-/:;()₽&@\"", "#+=.,?!'")

        /** Долгое нажатие на букву вводит запасной символ. */
        private val ALT_CHARS = mapOf('е' to 'ё')

        private val SENTENCE_END = Regex("[.!?…][\"»”)]*\\s+\$")

        /** Поля, где подсказки, автоисправление и автопунктуация выключены. */
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
