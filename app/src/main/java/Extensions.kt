import android.view.View
import android.widget.AdapterView
import android.widget.EditText
import android.widget.Spinner

fun View.hasUserContent(): Boolean {
    if (this is EditText)
        return (this as EditText).length() > 0

    return false
}

//for handlers not available in gui xml
fun<T> View.registerEventHandler(
    onClick: (T) -> Unit = { },
    onItemSelected: (T) -> Unit
    //others go here...
) {
    when (this) {
        is Spinner -> {
            val spinner = this as Spinner
            this.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {

                }

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    onItemSelected(spinner as T)
                }
            }
        }
    }
}