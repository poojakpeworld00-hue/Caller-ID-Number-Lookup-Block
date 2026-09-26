package io.launcher.home.receivers

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import io.launcher.home.R

class LockDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return context.getString(R.string.launcher_lock_device_admin_warning)
    }
}
