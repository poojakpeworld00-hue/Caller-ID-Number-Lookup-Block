package io.launcher.home.dialogs

import android.app.Activity
import android.app.AlertDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.ensureBackgroundThread
import io.launcher.home.databinding.LnchDialogRenameItemBinding
import io.launcher.home.extensions.homeScreenGridItemsDB
import io.launcher.home.models.HomeScreenGridItem

class RenameItemTray(val activity: Activity, val item: HomeScreenGridItem, val callback: () -> Unit) {

    init {
        val binding = LnchDialogRenameItemBinding.inflate(activity.layoutInflater)
        val view = binding.root
        binding.renameItemEdittextUi.setText(item.title)

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(view, this, org.fossify.commons.R.string.rename) { alertDialog ->
                    alertDialog.showKeyboard(binding.renameItemEdittextUi)
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val newTitle = binding.renameItemEdittextUi.value
                        if (newTitle.isNotEmpty()) {
                            ensureBackgroundThread {
                                val result = activity.homeScreenGridItemsDB.updateItemTitle(newTitle, item.id!!)
                                if (result == 1) {
                                    callback()
                                    alertDialog.dismiss()
                                } else {
                                    activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
                                }
                            }
                        } else {
                            activity.toast(org.fossify.commons.R.string.value_cannot_be_empty)
                        }
                    }
                }
            }
    }
}
