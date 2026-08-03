package com.calleridapp.numberlookup.ui.recents

import androidx.annotation.StringRes
import com.calleridapp.numberlookup.data.CallEntry

/** Top filter tabs. */
enum class CallFilter { ALL, INCOMING, OUTGOING, MISSED }

/**
 * Sort order applied by the toolbar sort button. Date sorts keep the
 * Today/Yesterday/… grouping; name sorts flatten the list (no date headers).
 */
enum class CallSort { NEWEST, OLDEST, NAME_ASC, NAME_DESC }

/** A row in the recents list: either a date section header or a call. */
sealed interface RecentRow {
    data class Header(@param:StringRes val titleRes: Int) : RecentRow
    data class Call(val entry: CallEntry) : RecentRow
}
