package br.com.atlas.assistente;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;

/**
 * Não lê notificações: existe só porque o Android exige um "ouvinte de notificações" liberado
 * para um app poder controlar o player de outro (dar play no Spotify com a música pedida).
 */
public class MidiaListener extends NotificationListenerService {
    static ComponentName componente(Context c) { return new ComponentName(c, MidiaListener.class); }

    static boolean liberado(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(componente(c).flattenToString());
    }
}
