package ch.pocketpc.nearbyglasses

import android.content.Context
import android.text.method.LinkMovementMethod
import android.util.AttributeSet
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

/**
 * Preference element that renders HTML content in the summary TextView.
 *
 * Supports:
 *  - HTML formatting
 *  - clickable links
 *  - multi-line text
 *  Yves Jeanrenaud, 2026
 */
class HtmlPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle
) : Preference(context, attrs, defStyleAttr) {

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val summaryView =
            holder.findViewById(android.R.id.summary) as? TextView
                ?: return

        val summaryText = summary?.toString() ?: return

        // Convert HTML into styled text
        summaryView.text = HtmlCompat.fromHtml(
            summaryText,
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )

        // Enable clickable links
        summaryView.movementMethod = LinkMovementMethod.getInstance()

        // Allow multi-line text
        summaryView.maxLines = Int.MAX_VALUE
        summaryView.ellipsize = null
    }
}