package com.example.marsel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Redémarre le relais en arrière-plan après un redémarrage du téléphone ou
 * une mise à jour de l'application — uniquement si l'utilisateur l'a activé
 * dans les Réglages. Ne fait rien sinon (aucune alerte n'est reprise ici :
 * une alerte en cours est reprise par l'écran à la réouverture de l'app).
 */
class MarselBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (RelaySettings.isEnabled(context)) MarselForegroundService.sync(context)
            }
        }
    }
}
