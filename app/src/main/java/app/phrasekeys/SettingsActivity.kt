package app.phrasekeys

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/** Экран настроек: подключение, темы, фото на фон, умный ввод и «Мои фразы». */
class SettingsActivity : Activity() {

    private lateinit var column: LinearLayout
    private lateinit var customEdit: EditText
    private lateinit var personalEdit: EditText
    private lateinit var themeGrid: FlowLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)

        column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(16), dp(16), dp(16), dp(32))

        buildSetupSection()
        buildThemeSection()
        buildLayoutSection()
        buildSmartSection()
        buildMemorySection()
        buildPhrasesSection()

        val scroll = ScrollView(this)
        scroll.addView(column)
        setContentView(scroll)
    }

    override fun onPause() {
        super.onPause()
        if (::customEdit.isInitialized) {
            PhraseLibrary.saveCustomText(this, customEdit.text.toString())
        }
        if (::personalEdit.isInitialized) {
            Memory.savePersonalText(this, personalEdit.text.toString())
        }
    }

    // ------------------------------------------------------------------ секции

    private fun buildSetupSection() {
        column.addView(heading("Подключение"))
        column.addView(body("1. Включите «Клавиатура Фразы» в списке клавиатур."))
        column.addView(button("Открыть список клавиатур") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        column.addView(body("2. Выберите её текущей клавиатурой."))
        column.addView(button("Выбрать клавиатуру") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })
        column.addView(body("3. Нажмите на поле ниже и попробуйте. Изменения применяются сразу."))
        val test = EditText(this)
        test.hint = "Поле для проверки"
        test.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        column.addView(test)
    }

    private fun buildThemeSection() {
        column.addView(heading("Тема и фон"))
        themeGrid = FlowLayout(this)
        themeGrid.setSpacing(dp(10), dp(10))
        column.addView(themeGrid)
        renderThemes()
        column.addView(button("Выбрать своё фото на фон") { pickPhoto() })
        column.addView(button("Убрать своё фото") {
            File(filesDir, Prefs.PHOTO_FILE).delete()
            if (Prefs.str(this, Prefs.THEME, "ios_light") == "photo") {
                Prefs.putStr(this, Prefs.THEME, "ios_light")
            } else {
                Prefs.bump()
            }
            renderThemes()
        })
    }

    private fun buildLayoutSection() {
        column.addView(heading("Вид клавиатуры"))
        column.addView(switchRow("Раскладка как на iPhone", Prefs.LAYOUT_IOS, true))
        column.addView(body("Высота клавиш"))
        column.addView(
            segmented(
                Prefs.HEIGHT, "mid",
                listOf("low" to "Низкая", "mid" to "Средняя", "high" to "Высокая")
            )
        )
        column.addView(body("Размер букв и цифр"))
        column.addView(
            segmented(
                Prefs.LETTER_SIZE, "m",
                listOf("s" to "Мелкие", "m" to "Средние", "l" to "Крупные")
            )
        )
        column.addView(body("Форма клавиш"))
        column.addView(
            segmented(
                Prefs.SHAPE, "theme",
                listOf("theme" to "Как в теме", "round" to "Круглые", "square" to "Квадратные")
            )
        )
        column.addView(switchRow("Ряд цифр сверху", Prefs.NUMBER_ROW, true))
        column.addView(switchRow("Увеличенная буква при нажатии", Prefs.PREVIEW, true))
        column.addView(switchRow("Стеклянные объёмные клавиши", Prefs.GLASS, true))
        column.addView(switchRow("Вибрация клавиш", Prefs.HAPTICS, true))
    }

    private fun buildSmartSection() {
        column.addView(heading("Умный ввод"))
        column.addView(switchRow("Подсказки слов (как T9)", Prefs.SUGGESTIONS, true))
        column.addView(switchRow("Подсказки и там, где приложение их запрещает", Prefs.FORCE_SUGGEST, true))
        column.addView(
            hint(
                "Некоторые приложения (например, Instagram) просят клавиатуру не показывать подсказки. " +
                    "С этим переключателем подсказки и автоисправление работают везде, кроме паролей."
            )
        )
        column.addView(switchRow("Автоисправление опечаток", Prefs.AUTOCORRECT, true))
        column.addView(switchRow("Умная пунктуация", Prefs.SMART_PUNCT, true))
        column.addView(
            hint(
                "Убирает пробел перед запятой и точкой, ставит точку по двойному пробелу, " +
                    "добавляет пробел после запятой. Запятые по правилам грамматики " +
                    "автоматически не расставляются."
            )
        )
        column.addView(switchRow("Заглавная буква в начале предложения", Prefs.AUTOCAP, true))
        column.addView(
            hint(
                "Если слово исправилось неверно, сразу нажмите «стереть»: вернётся ваш вариант, " +
                    "и клавиатура больше не будет его исправлять."
            )
        )
        column.addView(button("Забыть выученные слова") {
            SpellEngine.resetUserWords(this)
            Toast.makeText(this, "Готово", Toast.LENGTH_SHORT).show()
        })
    }

    private fun buildMemorySection() {
        column.addView(heading("Мои данные и частые фразы"))
        column.addView(switchRow("Запоминать частые фразы, почты и числа", Prefs.LEARN, true))
        column.addView(
            hint(
                "Когда вы отправляете сообщение, клавиатура запоминает фразу, почты, телефоны и даты. " +
                    "После второго раза они появляются в подсказках и во вкладке «Частые». " +
                    "Пароли, коды и номера карт не запоминаются. Всё хранится только на телефоне."
            )
        )
        column.addView(button("Забыть частые фразы") {
            Memory.clearLearned(this)
            Toast.makeText(this, "Готово", Toast.LENGTH_SHORT).show()
        })

        column.addView(body("Мои данные: по одной строке «Название: значение». Они в панели во вкладке «Данные», а почта и телефон сами предлагаются в нужных полях."))
        personalEdit = EditText(this)
        personalEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        personalEdit.minLines = 6
        personalEdit.gravity = Gravity.TOP or Gravity.START
        personalEdit.hint = PERSONAL_TEMPLATE
        personalEdit.setText(Memory.personalText(this))
        column.addView(
            personalEdit,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        column.addView(button("Вставить пример") {
            if (personalEdit.text.isBlank()) personalEdit.setText(PERSONAL_TEMPLATE)
        })
        column.addView(button("Сохранить данные") {
            Memory.savePersonalText(this, personalEdit.text.toString())
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        })
    }

    private fun buildPhrasesSection() {
        column.addView(heading("Мои фразы"))
        column.addView(body("По одной фразе на строку. Они появятся во вкладке «Мои» панели фраз."))
        customEdit = EditText(this)
        customEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        customEdit.minLines = 5
        customEdit.gravity = Gravity.TOP or Gravity.START
        customEdit.setText(PhraseLibrary.loadCustomText(this))
        column.addView(
            customEdit,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        column.addView(button("Сохранить") {
            PhraseLibrary.saveCustomText(this, customEdit.text.toString())
            Prefs.bump()
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        })
    }

    // ------------------------------------------------------------------ темы

    private fun renderThemes() {
        themeGrid.removeAllViews()
        val current = Prefs.str(this, Prefs.THEME, "ios_light")
        val hasPhoto = File(filesDir, Prefs.PHOTO_FILE).exists()
        for (t in Themes.all) {
            if (t.photo && !hasPhoto) continue
            themeGrid.addView(themeCard(t, t.id == current))
        }
    }

    private fun themeCard(t: KeyboardTheme, selected: Boolean): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL

        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER
        val bg = if (t.gradient) {
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(t.bg, t.bgEnd))
        } else {
            GradientDrawable().also { it.setColor(t.bg) }
        }
        bg.cornerRadius = dp(10).toFloat()
        bg.setStroke(if (selected) dp(3) else dp(1), if (selected) Color.parseColor("#007AFF") else Color.parseColor("#33000000"))
        if (t.photo) {
            val bmp = BitmapFactory.decodeFile(File(filesDir, Prefs.PHOTO_FILE).absolutePath)
            if (bmp != null) box.background = BitmapDrawable(resources, bmp) else box.background = bg
        } else {
            box.background = bg
        }
        for (i in 0 until 4) {
            val k = View(this)
            val fill = if (i == 3) t.accent else keyColor(t)
            val shape = GradientDrawable()
            shape.setColor(fill)
            shape.cornerRadius = dp(4).toFloat()
            k.background = shape
            val lp = LinearLayout.LayoutParams(dp(18), dp(26))
            lp.setMargins(dp(3), 0, dp(3), 0)
            box.addView(k, lp)
        }
        card.addView(box, LinearLayout.LayoutParams(dp(110), dp(58)))

        val label = TextView(this)
        label.text = t.name
        label.gravity = Gravity.CENTER
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        if (selected) label.setTypeface(null, Typeface.BOLD)
        card.addView(label, LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))

        card.setOnClickListener {
            Prefs.putStr(this, Prefs.THEME, t.id)
            renderThemes()
        }
        return card
    }

    private fun keyColor(t: KeyboardTheme): Int =
        Color.argb(Color.alpha(t.key) * t.keyAlpha / 255, Color.red(t.key), Color.green(t.key), Color.blue(t.key))

    private fun pickPhoto() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "image/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        @Suppress("DEPRECATION")
        startActivityForResult(Intent.createChooser(intent, "Фото для фона"), REQ_PHOTO)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PHOTO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            val ok = savePhoto(uri)
            runOnUiThread {
                if (ok) {
                    Prefs.putStr(this, Prefs.THEME, "photo")
                    renderThemes()
                } else {
                    Toast.makeText(this, "Не удалось открыть фото", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun savePhoto(uri: Uri): Boolean {
        try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            var sample = 1
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2

            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            var bmp: Bitmap = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return false

            val rotation = contentResolver.openInputStream(uri)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
            if (rotation != 0) {
                val m = Matrix()
                m.postRotate(rotation.toFloat())
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            FileOutputStream(File(filesDir, Prefs.PHOTO_FILE)).use {
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, it)
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    // ------------------------------------------------------------------ элементы интерфейса

    private fun switchRow(label: String, key: String, default: Boolean): Switch {
        val sw = Switch(this)
        sw.text = label
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        sw.isChecked = Prefs.bool(this, key, default)
        sw.setPadding(0, dp(10), 0, dp(10))
        sw.setOnCheckedChangeListener { _, checked -> Prefs.putBool(this, key, checked) }
        return sw
    }

    private fun segmented(key: String, default: String, options: List<Pair<String, String>>): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val current = Prefs.str(this, key, default)
        for ((value, label) in options) {
            val b = Button(this)
            b.isAllCaps = false
            b.text = if (value == current) "✓ $label" else label
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            if (value == current) b.setTypeface(null, Typeface.BOLD)
            b.setOnClickListener {
                Prefs.putStr(this, key, value)
                val parent = row.parent as? ViewGroup
                if (parent != null) {
                    val index = parent.indexOfChild(row)
                    parent.removeViewAt(index)
                    parent.addView(segmented(key, default, options), index)
                }
            }
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return row
    }

    private fun heading(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        tv.setTypeface(null, Typeface.BOLD)
        tv.setPadding(0, dp(24), 0, dp(6))
        return tv
    }

    private fun body(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        tv.setPadding(0, dp(8), 0, dp(4))
        return tv
    }

    private fun hint(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        tv.setTextColor(Color.parseColor("#666666"))
        tv.setPadding(0, 0, 0, dp(6))
        return tv
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val PERSONAL_TEMPLATE =
            "Почта: name@mail.ru\nИмя: Иван\nФамилия: Иванов\nОтчество: Иванович\n" +
                "ФИО: Иванов Иван Иванович\nТелефон: +7 900 000-00-00\nДата рождения: 01.01.1990"

        private const val REQ_PHOTO = 101
    }
}
