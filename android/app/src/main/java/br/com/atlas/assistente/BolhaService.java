package br.com.atlas.assistente;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

/**
 * Botão flutuante do Atlas por cima dos outros apps (permissão "Sobrepor a outros apps").
 * Tocar abre o Atlas já ouvindo; arrastar muda o lugar. Fica escondido enquanto o Atlas está na tela.
 * Roda como serviço em primeiro plano (o Android exige a notificação fixa).
 */
public class BolhaService extends Service {
    static final String ACAO_OUVIR = "br.com.atlas.assistente.OUVIR";
    static final String ACAO_DESLIGAR = "br.com.atlas.assistente.BOLHA_DESLIGAR";
    static final String PREFS = "atlas_bolha";
    private static final String CANAL = "bolha";
    private static final int NOTIF_ID = 4711;

    static volatile BolhaService instancia;
    static volatile boolean appNaFrente = false;

    private WindowManager wm;
    private FrameLayout bolha;
    private WindowManager.LayoutParams lp;
    private final Handler main = new Handler(Looper.getMainLooper());

    static boolean ligada(Context c) { return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("ligada", false); }

    static void salvar(Context c, boolean v) { c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("ligada", v).apply(); }

    static boolean podeSobrepor(Context c) { return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(c); }

    /** Liga o serviço (chamar com o app na frente). */
    static void iniciar(Context c) {
        if (!podeSobrepor(c)) return;
        Intent i = new Intent(c, BolhaService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignorado) {}
    }

    static void parar(Context c) { c.stopService(new Intent(c, BolhaService.class)); }

    /** O Atlas foi para a frente (true) ou saiu da tela (false). */
    static void appVisivel(boolean naFrente) {
        appNaFrente = naFrente;
        BolhaService s = instancia;
        if (s != null) s.main.post(s::atualizar);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        instancia = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACAO_DESLIGAR.equals(intent.getAction())) {
            salvar(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            int tipo = Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
            ServiceCompat.startForeground(this, NOTIF_ID, notificacao(), tipo);
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        atualizar();
        return START_STICKY;
    }

    private Notification notificacao() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CANAL) == null) {
            NotificationChannel ch = new NotificationChannel(CANAL, "Botão flutuante", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        int imm = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent abrir = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), imm);
        PendingIntent desligar = PendingIntent.getService(this, 2, new Intent(this, BolhaService.class).setAction(ACAO_DESLIGAR), imm);
        return new NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Atlas: botão flutuante ativo")
            .setContentText("Toque na bolinha para falar com o Atlas.")
            .setContentIntent(abrir)
            .addAction(0, "Desligar", desligar)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build();
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @SuppressLint("ClickableViewAccessibility")
    private void criarBolha() {
        if (bolha != null || !podeSobrepor(this)) return;
        int tam = dp(58);
        bolha = new FrameLayout(this);
        GradientDrawable fundo = new GradientDrawable();
        fundo.setShape(GradientDrawable.OVAL);
        fundo.setColor(Color.parseColor("#E6040914"));
        fundo.setStroke(dp(2), Color.parseColor("#22D3EE"));
        bolha.setBackground(fundo);
        bolha.setElevation(dp(6));
        ImageView icone = new ImageView(this);
        icone.setImageResource(R.mipmap.ic_launcher_round);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(tam - dp(10), tam - dp(10), Gravity.CENTER);
        bolha.addView(icone, ip);
        bolha.setContentDescription("Falar com o Atlas");

        int tipo = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(tam, tam, tipo,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        lp.x = p.getInt("x", dm.widthPixels - tam - dp(8));
        lp.y = p.getInt("y", dm.heightPixels / 3);

        final int toque = dp(8);
        bolha.setOnTouchListener(new View.OnTouchListener() {
            float x0, y0; int px, py; boolean moveu;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        x0 = e.getRawX(); y0 = e.getRawY(); px = lp.x; py = lp.y; moveu = false;
                        v.setAlpha(0.8f);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
                        if (!moveu && Math.hypot(dx, dy) > toque) moveu = true;
                        if (moveu) {
                            lp.x = Math.round(px + dx); lp.y = Math.round(py + dy);
                            try { wm.updateViewLayout(bolha, lp); } catch (Exception ignorado) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setAlpha(1f);
                        if (moveu) {
                            // gruda na borda mais próxima
                            DisplayMetrics m = getResources().getDisplayMetrics();
                            lp.x = lp.x + v.getWidth() / 2 < m.widthPixels / 2 ? dp(4) : m.widthPixels - v.getWidth() - dp(4);
                            lp.y = Math.max(0, Math.min(lp.y, m.heightPixels - v.getHeight()));
                            try { wm.updateViewLayout(bolha, lp); } catch (Exception ignorado) {}
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("x", lp.x).putInt("y", lp.y).apply();
                        } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                            abrirOuvindo();
                        }
                        return true;
                }
                return false;
            }
        });
        try { wm.addView(bolha, lp); } catch (Exception e) { bolha = null; }
    }

    private void abrirOuvindo() {
        Intent i = new Intent(this, MainActivity.class)
            .setAction(ACAO_OUVIR)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try { startActivity(i); } catch (Exception ignorado) {}
    }

    private void removerBolha() {
        if (bolha == null) return;
        try { wm.removeView(bolha); } catch (Exception ignorado) {}
        bolha = null;
    }

    /** Mostra a bolinha só com o Atlas fora da tela. */
    private void atualizar() {
        if (appNaFrente) removerBolha(); else criarBolha();
    }

    @Override
    public void onDestroy() {
        removerBolha();
        if (instancia == this) instancia = null;
        super.onDestroy();
    }
}
