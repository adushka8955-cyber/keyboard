package app.phrasekeys

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Клавиатура с обычными буквами (RU/EN, цифры/символы) и панелью готовых фраз.
 * Кнопка «Фразы» в нижнем ряду открывает панель, «АБВ» возвращает к буквам.
 */
class PhraseKeyboardService : InputMethodService() {

    private enum class Layer { RU, EN, SYMBOLS }
    private enum class Shift { OFF, ONCE, CAPS }

    private var layer = Layer.RU
    private var lastLetterLayer = Layer.RU
    private var shift = Shift.OFF
    private var lastShiftTap = 0L
    private var categoryIndex = 0

    private lateinit var keysView: LinearLayout
    private lateinit var phrasesView: LinearLayout
    private lateinit var tabsRow: LinearLayout
    private lateinit var chipsFlow: FlowLayout

    private val letterKeys = ArrayList<TextView>()
    private var shiftKey: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    private val deleteRunnable = object : Runnable {
        override fun run() {
            deleteOne()
            handler.postDelayed(this, 50)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        letterKeys.clear()
        shiftKey = null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.kb_bg))
            setPadding(dp(3), dp(6), dp(3), dp(6))
        }

        keysView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        phrasesView = buildPhrasesView()
        phrasesView.visibility = View.GONE

        val h = dp(panelHeightDp())
        root.addView(keysView, LinearLayout.LayoutParams(MATCH, h))
        root.addView(phrasesView, LinearLayout.LayoutParams(MATCH, h))

        renderKeys()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!::keysView.isInitialized) return

        val inputClass = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
        layer = when (inputClass) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME -> Layer.SYMBOLS
            else -> if (layer == Layer.SYMBOLS) lastLetterLayer else layer
        }
        shift = Shift.OFF
        updateAutoShift()
        if (!restarting) showKeys()
        renderKeys()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        handler.removeCallbacks(deleteRunnable)
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
    }

    // ------------------------------------------------------------------ letters

    private fun renderKeys() {
        keysView.removeAllViews()
        letterKeys.clear()
        shiftKey = null

        val rows = when (layer) {
            Layer.RU -> RU_ROWS
            Layer.EN -> EN_ROWS
            Layer.SYMBOLS -> SYM_ROWS
        }

        val row0 = newRow()
        rows[0].forEach { addLetterKey(row0, it) }
        keysView.addView(row0, rowLp())

        val row1 = newRow()
        val gap = (rows[0].length - rows[1].length) / 2f
        if (gap > 0f) row1.addView(spacer(), LinearLayout.LayoutParams(0, MATCH, gap))
        rows[1].forEach { addLetterKey(row1, it) }
        if (gap > 0f) row1.addView(spacer(), LinearLayout.LayoutParams(0, MATCH, gap))
        keysView.addView(row1, rowLp())

        val row2 = newRow()
        if (layer == Layer.SYMBOLS) {
            rows[2].forEach { addLetterKey(row2, it) }
            row2.addView(makeBackspace(), keyLp(2f))
        } else {
            row2.addView(makeShift(), keyLp(1.5f))
            rows[2].forEach { addLetterKey(row2, it) }
            row2.addView(makeBackspace(), keyLp(1.5f))
        }
        keysView.addView(row2, rowLp())

        keysView.addView(makeBottomRow(), rowLp())
        refreshLetterCase()
    }

    private fun addLetterKey(row: LinearLayout, ch: Char) {
        val key = makeKey(ch.toString())
        key.tag = ch
        key.setOnClickListener {
            haptic(it)
            typeChar(ch)
        }
        val alt = ALT_CHARS[ch]
        if (alt != null) {
            key.setOnLongClickListener {
                haptic(it)
                val out = if (shift != Shift.OFF) alt.uppercaseChar() else alt
                commit(out.toString())
                if (shift == Shift.ONCE) shift = Shift.OFF
                refreshLetterCase()
                true
            }
        }
        letterKeys.add(key)
        row.addView(key, keyLp(1f))
    }

    private fun typeChar(ch: Char) {
        val out = if (shift != Shift.OFF) ch.uppercaseChar() else ch
        commit(out.toString())
        if (shift == Shift.ONCE) shift = Shift.OFF
        refreshLetterCase()
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
            key.text = when (shift) {
                Shift.OFF -> "⇧"
                Shift.ONCE -> "⇧"
                Shift.CAPS -> "⇪"
            }
            val active = shift != Shift.OFF
            key.setTextColor(color(if (active) R.color.accent_text else R.color.key_text))
            key.background = keyBackground(
                color(if (active) R.color.accent else R.color.key_special)
            )
        }
    }

    private fun updateAutoShift() {
        if (layer == Layer.SYMBOLS || shift == Shift.CAPS) return
        val info = currentInputEditorInfo ?: return
        val ic = currentInputConnection ?: return
        val caps = if (info.inputType != 0) ic.getCursorCapsMode(info.inputType) else 0
        shift = if (caps != 0) Shift.ONCE else Shift.OFF
    }

    // --------------------------------------------------------------- bottom row

    private fun makeBottomRow(): LinearLayout {
        val row = newRow()

        val phrases = makeKey("Фразы", accent = true, textSp = 14f)
        phrases.setTypeface(null, Typeface.BOLD)
        phrases.setOnClickListener {
            haptic(it)
            showPhrases()
        }
        row.addView(phrases, keyLp(1.7f))

        val symbolsLabel = when {
            layer != Layer.SYMBOLS -> "?123"
            lastLetterLayer == Layer.RU -> "АБВ"
            else -> "ABC"
        }
        val symbols = makeKey(symbolsLabel, special = true, textSp = 14f)
        symbols.setOnClickListener {
            haptic(it)
            layer = if (layer == Layer.SYMBOLS) lastLetterLayer else Layer.SYMBOLS
            renderKeys()
        }
        row.addView(symbols, keyLp(1.3f))

        val lang = makeKey(
            if (lastLetterLayer == Layer.RU) "RU" else "EN",
            special = true,
            textSp = 14f
        )
        lang.setOnClickListener {
            haptic(it)
            lastLetterLayer = if (lastLetterLayer == Layer.RU) Layer.EN else Layer.RU
            layer = lastLetterLayer
            renderKeys()
        }
        lang.setOnLongClickListener {
            haptic(it)
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            true
        }
        row.addView(lang, keyLp(1f))

        row.addView(simpleKey(","), keyLp(1f))

        val spaceLabel = when {
            layer == Layer.SYMBOLS -> ""
            layer == Layer.RU -> "Русский"
            else -> "English"
        }
        val space = makeKey(spaceLabel, textSp = 12f)
        space.setTextColor(color(R.color.key_hint))
        space.setOnClickListener {
            haptic(it)
            commit(" ")
        }
        row.addView(space, keyLp(3.5f))

        row.addView(simpleKey("."), keyLp(1f))

        val enter = makeKey("⏎", special = true)
        enter.setOnClickListener {
            haptic(it)
            pressEnter()
        }
        row.addView(enter, keyLp(1.5f))

        return row
    }

    private fun simpleKey(text: String): TextView {
        val key = makeKey(text)
        key.setOnClickListener {
            haptic(it)
            commit(text)
        }
        return key
    }

    private fun pressEnter() {
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

    // ---------------------------------------------------------------- backspace

    private fun makeBackspace(): TextView {
        val key = makeKey("⌫", special = true)
        attachBackspace(key)
        return key
    }

    private fun attachBackspace(key: View) {
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
    }

    private fun deleteOne() {
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    // ------------------------------------------------------------ phrases panel

    private fun buildPhrasesView(): LinearLayout {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val back = makeKey("АБВ", accent = true, textSp = 14f)
        back.setTypeface(null, Typeface.BOLD)
        back.setOnClickListener {
            haptic(it)
            showKeys()
        }
        top.addView(back, LinearLayout.LayoutParams(dp(60), dp(40)).apply {
            setMargins(dp(3), 0, dp(6), 0)
        })

        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
        }
        tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        scroller.addView(tabsRow, FrameLayout.LayoutParams(WRAP, MATCH))
        top.addView(scroller, LinearLayout.LayoutParams(0, dp(40), 1f))

        val del = makeKey("⌫", special = true)
        attachBackspace(del)
        top.addView(del, LinearLayout.LayoutParams(dp(60), dp(40)).apply {
            setMargins(dp(6), 0, dp(3), 0)
        })

        panel.addView(top, LinearLayout.LayoutParams(MATCH, dp(44)))

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        chipsFlow = FlowLayout(this).apply {
            setSpacing(dp(6), dp(6))
            setPadding(dp(3), dp(6), dp(3), dp(6))
        }
        scroll.addView(chipsFlow, FrameLayout.LayoutParams(MATCH, WRAP))
        panel.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        return panel
    }

    private fun showPhrases() {
        keysView.visibility = View.GONE
        phrasesView.visibility = View.VISIBLE
        renderPhrases()
    }

    private fun showKeys() {
        phrasesView.visibility = View.GONE
        keysView.visibility = View.VISIBLE
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
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            tab.setPadding(dp(14), 0, dp(14), 0)
            tab.setTextColor(color(if (selected) R.color.accent_text else R.color.key_text))
            tab.background = keyBackground(
                color(if (selected) R.color.accent else R.color.key), radiusDp = 16
            )
            tab.isClickable = true
            tab.setOnClickListener {
                haptic(it)
                categoryIndex = index
                renderPhrases()
            }
            tabsRow.addView(tab, LinearLayout.LayoutParams(WRAP, MATCH).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }

        chipsFlow.removeAllViews()
        for (phrase in categories[categoryIndex].phrases) {
            val chip = TextView(this)
            chip.text = phrase
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            chip.setTextColor(color(R.color.key_text))
            chip.setPadding(dp(14), dp(9), dp(14), dp(9))
            chip.background = keyBackground(color(R.color.key), radiusDp = 18)
            chip.isClickable = true
            chip.setOnClickListener {
                haptic(it)
                commit("$phrase ")
            }
            chipsFlow.addView(chip)
        }

        (chipsFlow.parent as? ScrollView)?.scrollTo(0, 0)
    }

    // ------------------------------------------------------------------ helpers

    private fun commit(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    private fun haptic(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun makeKey(
        label: String,
        special: Boolean = false,
        accent: Boolean = false,
        textSp: Float = 20f
    ): TextView {
        val key = TextView(this)
        key.text = label
        key.gravity = Gravity.CENTER
        key.includeFontPadding = false
        key.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
        key.setTextColor(color(if (accent) R.color.accent_text else R.color.key_text))
        val bg = when {
            accent -> R.color.accent
            special -> R.color.key_special
            else -> R.color.key
        }
        key.background = keyBackground(color(bg))
        key.isClickable = true
        return key
    }

    private fun keyBackground(normal: Int, radiusDp: Int = 8): StateListDrawable {
        val states = StateListDrawable()
        states.addState(
            intArrayOf(android.R.attr.state_pressed),
            roundRect(shade(normal, 0.8f), radiusDp)
        )
        states.addState(intArrayOf(), roundRect(normal, radiusDp))
        return states
    }

    private fun roundRect(fill: Int, radiusDp: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(fill)
        d.cornerRadius = dp(radiusDp).toFloat()
        return d
    }

    private fun shade(c: Int, factor: Float): Int = Color.argb(
        Color.alpha(c),
        (Color.red(c) * factor).toInt(),
        (Color.green(c) * factor).toInt(),
        (Color.blue(c) * factor).toInt()
    )

    private fun newRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    private fun rowLp() = LinearLayout.LayoutParams(MATCH, 0, 1f)

    private fun keyLp(weight: Float) = LinearLayout.LayoutParams(0, MATCH, weight).apply {
        setMargins(dp(3), dp(4), dp(3), dp(4))
    }

    private fun spacer() = View(this)

    private fun color(id: Int): Int = getColor(id)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun panelHeightDp(): Int =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 168 else 216

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private val RU_ROWS = listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю")
        private val EN_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private val SYM_ROWS = listOf("1234567890", "@#₽%&-+()/", "*\"':;!?=")

        /** Долгое нажатие на букву вводит запасной символ. */
        private val ALT_CHARS = mapOf('е' to 'ё')
    }
}
