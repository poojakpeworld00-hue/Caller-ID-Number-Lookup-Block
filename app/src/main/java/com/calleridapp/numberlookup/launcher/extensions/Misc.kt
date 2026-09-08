package com.calleridapp.numberlookup.launcher.extensions

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ShortcutInfo
import com.calleridapp.numberlookup.util.GuardRail

fun ShortcutInfo?.getLabel() = this?.longLabel?.toString().ifNullOrEmpty { this?.shortLabel?.toString() } ?: ""

private fun String?.ifNullOrEmpty(block: () -> String?) = this?.ifEmpty { block() } ?: block()

/**
 * [AppWidgetManager.getInstalledProviders] without the crash.
 *
 * The provider list crosses a Binder as a `ParceledListSlice`, and fetching it can fail in more
 * than one way, all of them out of this app's hands and all of them thrown from whichever thread
 * asked:
 *
 *  - `BadParcelableException: Failure retrieving array; only received 18 of 133` — the later
 *    chunks never arrive. Seen on devices with a lot of widgets installed.
 *  - `DeadSystemRuntimeException` — system_server went down mid-call. The process is going with
 *    it; there is no point being the app that shouts about it on the way out.
 *
 * Nothing to fix on this side and nothing to retry usefully: the caller either gets the list or it
 * does not, which is why this catches broadly rather than by type.
 *
 * Every caller here is already written for "no provider matched" (widgets get placed as pseudo
 * widgets, or skipped), so degrading to an empty list costs a home-screen refresh rather than the
 * whole Activity — the crash landed in `MainActivity.onResume` via `HomeScreenGrid.fetchGridItems`,
 * which took the launcher down with it.
 */
fun AppWidgetManager.installedProvidersSafe(): List<AppWidgetProviderInfo> =
    runCatching { installedProviders.orEmpty() }
        .onFailure { GuardRail.error("Widgets", "installedProviders unavailable", it) }
        .getOrDefault(emptyList())

/**
 * [PackageManager.queryIntentActivities] without the crash.
 *
 * Same `ParceledListSlice` failure as [installedProvidersSafe] — `Failure retrieving array; only
 * received 125 of 180` — on the other bulk list this launcher asks the system for. It is the app
 * list behind the drawer and the icon grid, so it is the biggest slice the app fetches and the
 * one most likely to come back short.
 *
 * An empty list means a refresh that finds no apps; `refreshLaunchers` runs again on the next
 * resume. The crash it replaces killed the launcher from `onResume`, which on a device where this
 * app holds the HOME role means the home screen itself going down.
 */
fun PackageManager.queryIntentActivitiesSafe(intent: Intent, flags: Int): List<ResolveInfo> =
    runCatching { queryIntentActivities(intent, flags).orEmpty() }
        .onFailure { GuardRail.error("Launchers", "queryIntentActivities failed", it) }
        .getOrDefault(emptyList())
