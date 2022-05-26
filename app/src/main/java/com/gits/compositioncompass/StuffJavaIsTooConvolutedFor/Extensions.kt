import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.core.widget.addTextChangedListener
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Node

fun View.hasUserContent(): Boolean {
    if (this is EditText)
        return (this as EditText).length() > 0

    return false
}

fun<T> Spinner.getItem(filter: (T) -> Boolean): T? {
    for (i in 1..this.adapter.count) {
        if (filter(adapter.getItem((i-1)) as T))
            return adapter.getItem(i-1) as T
    }

    return null
}

fun<T> Spinner.setSelection(item: T) {
    this.setSelection((this.adapter as ArrayAdapter<T>).getPosition(item))
}

fun View.registerEventHandler(
    button_onClick: (view: View) -> Unit = { },
    spinner_onNothingSelected: (parent: AdapterView<*>?) -> Unit = { },
    spinner_onItemSelected: (parent: AdapterView<*>?, view: View?, position: Int, id: Long) -> Unit = { a,b,c,d -> },
    editText_onFocusChange: (v: View?, hasFocus: Boolean) -> Unit = { a, b -> },
    editText_afterChanged: (s: Editable?) -> Unit = { a -> },
    editText_onClick: (v: View?) -> Unit = { a -> }
    //others go here...
) {
    when (this) {
        is Spinner -> { this.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) { spinner_onNothingSelected(parent) }
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { spinner_onItemSelected(parent, view, position, id) }
        }}

        is EditText -> {
            this.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { }
                override fun afterTextChanged(s: Editable?) { editText_afterChanged(s) }
            })

            this.setOnClickListener(object : View.OnClickListener {
                override fun onClick(v: View?) { editText_onClick(v) }
            })
        }

        is Button -> { this.setOnClickListener(button_onClick) }
        is EditText -> { this.setOnFocusChangeListener(editText_onFocusChange) }
    }
}

fun<T> JSONArray.toList(): List<T> {
    val result = mutableListOf<T>()

    for (i in 0..this.length() - 1) {
        result.add(this[i] as T)
    }

    return result
}


fun JSONObject.getJSONObjectOrNull(s: String): JSONObject? {
    if (this.has(s))
        return this.getJSONObject(s)
    else
        return null
}

fun String.contains(s: String, ignoreCase: Boolean, ignoreSpecialChars: Boolean) =
    if (ignoreSpecialChars)
        this.split(" ").map { it.trim(',', ';', '-', ':', '.') }.joinToString(" ").contains(
            s.split(" ").map { it.trim(',', ';', '-', ':', '.') }.joinToString(" "), ignoreCase)
    else
        this.contains(s, ignoreCase)

fun Vibrator.vibrateLong() =
    this.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))

fun Vibrator.vibrateVeryLong() =
    this.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))