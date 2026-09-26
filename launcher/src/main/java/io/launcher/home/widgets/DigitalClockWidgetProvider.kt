package io.launcher.home.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import io.launcher.home.R

class DigitalClockWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
    ) {
        val textColor = context.getWidgetTextColor(appWidgetId)
        val is24Hours = DateFormat.is24HourFormat(context)

        val views = RemoteViews(context.packageName, R.layout.lnch_widget_digital_clock).apply {
            setInt(
                R.id.widget_holderUi,
                "setBackgroundColor",
                context.getWidgetBackgroundColor(appWidgetId)
            )

            // TextClock has no single "show 12/24 hours" switch, keep one of the two around
            setViewVisibility(R.id.widget_text_clock_24Ui, if (is24Hours) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.widget_text_clock_12Ui, if (is24Hours) View.GONE else View.VISIBLE)

            setTextColor(R.id.widget_text_clock_24Ui, textColor)
            setTextColor(R.id.widget_text_clock_12Ui, textColor)
            setTextColor(R.id.widget_dateUi, textColor)
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
