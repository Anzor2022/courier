package ru.mahnet.courier

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** После перезагрузки телефона снова включает оповещения, если курьер был вошёл в приложение. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val token = context.getSharedPreferences(CourierService.PREFS, Context.MODE_PRIVATE).getString("token", null)
        if (!token.isNullOrEmpty()) {
            try {
                CourierService.start(context)
            } catch (e: Exception) {
                // система не разрешила запуск — служба стартует при открытии приложения
            }
        }
    }
}
