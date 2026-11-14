package app.phrasekeys

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Экран настройки: включить клавиатуру, выбрать её и отредактировать «Мои фразы». */
class SettingsActivity : Activity() {

    private lateinit var customEdit: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)

        val pad = dp(16)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        column.addView(label("Как подключить", 20f, bold = true))
        column.addView(label("1. Включите «Клавиатура Фразы» в списке клавиатур."))
        column.addView(button("Открыть список клавиатур") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        column.addView(label("2. Выберите её текущей клавиатурой."))
        column.addView(button("Выбрать клавиатуру") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })
        column.addView(
            label("3. В любом чате нажмите синюю кнопку «Фразы» и выберите нужную фразу.")
        )

        column.addView(label("Мои фразы", 20f, bold = true).also { addTopGap(it, 24) })
        column.addView(label("По одной фразе на строку. Они появятся в отдельной вкладке «Мои»."))
        customEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
            gravity = Gravity.TOP or Gravity.START
            setText(PhraseLibrary.loadCustomText(this@SettingsActivity))
        }
        column.addView(
            customEdit,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        column.addView(button("Сохранить") {
            PhraseLibrary.saveCustomText(this, customEdit.text.toString())
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        })

        column.addView(label("Проверка", 20f, bold = true).also { addTopGap(it, 24) })
        column.addView(label("Нажмите на поле и попробуйте клавиатуру:"))
        column.addView(
            EditText(this).apply {
                hint = "Напишите что-нибудь"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
        )

        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onPause() {
        super.onPause()
        // Не теряем набранное, даже если пользователь ушёл с экрана без кнопки «Сохранить».
        if (::customEdit.isInitialized) {
            PhraseLibrary.saveCustomText(this, customEdit.text.toString())
        }
    }

    private fun label(text: String, sp: Float = 15f, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        }

    private fun addTopGap(view: TextView, gapDp: Int) {
        view.setPadding(0, dp(gapDp), 0, dp(4))
    }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
