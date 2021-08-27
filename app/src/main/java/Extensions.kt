import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner

fun View.hasUserContent(): Boolean {
    if (this is EditText)
        return (this as EditText).length() > 0

    return false
}

fun<T> View.registerEventHandler(
    button_onClick: (view: View) -> Unit = { },
    spinner_onNothingSelected: (parent: AdapterView<*>?) -> Unit = { },
    spinner_onItemSelected: (parent: AdapterView<*>?, view: View?, position: Int, id: Long) -> Unit = { a,b,c,d -> },
    editText_onFocusChange: (v: View?, hasFocus: Boolean) -> Unit = { a, b -> }
    //others go here...
) {
    when (this) {
        is Spinner -> {
            this.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {
                    spinner_onNothingSelected(parent)
                }

                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
                    spinner_onItemSelected(parent, view, position, id)
            }
        }

        is Button -> { this.setOnClickListener(button_onClick) }
        is EditText -> { this.setOnFocusChangeListener(editText_onFocusChange) }
    }
}