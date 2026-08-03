package com.calleridapp.numberlookup.ui.blocklist

import com.calleridapp.numberlookup.data.BarredEntry

/**
 * A blocklist row ready for display.
 *
 * @param entry  the underlying blocked number + timestamp.
 * @param label  resolved contact name, or a friendly fallback.
 * @param isSpam when true the row uses the red-tinted "spam" treatment.
 */
data class BarredRowUi(
    val entry: BarredEntry,
    val label: String,
    val isSpam: Boolean,
)
