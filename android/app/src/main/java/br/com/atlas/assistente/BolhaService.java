package br.com.atlas.assistente;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;

/**
 * Botão flutuante do Atlas por cima dos outros apps (permissão "Sobrepor a outros apps").
 * Tocar abre um painel rápido que escuta o pedido, manda para o Atlas (que continua rodando
 * em segundo plano) e mostra/fala a resposta ali mesmo, sem abrir o app. Arrastar muda o lugar.
 * Fica escondido enquanto o Atlas está na tela. Roda como serviço em primeiro plano.
 */
public class BolhaService extends Service {
    static final String ACAO_OUVIR = "br.com.atlas.assistente.OUVIR";
    static final String ACAO_BRIEFING = "br.com.atlas.assistente.BRIEFING";
    static final String ACAO_DESLIGAR = "br.com.atlas.assistente.BOLHA_DESLIGAR";
    static final String EXTRA_PEDIDO = "pedido";
    static final String PREFS = "atlas_bolha";
    private static final String CANAL = "bolha";
    private static final int NOTIF_ID = 4711;
    private static final int CIANO = Color.parseColor("#22D3EE");

    static volatile BolhaService instancia;
    static volatile boolean appNaFrente = false;

    private WindowManager wm;
    private NucleoView bolha;
    private WindowManager.LayoutParams lp;
    private LinearLayout painel;
    private WindowManager.LayoutParams lpPainel;
    private TextView txtEstado, txtTexto;
    private SpeechRecognizer sr;
    private boolean ouvindo = false;
    private long esperandoDesde = 0, toqueNucleo = 0;
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

    /** Resposta do Atlas (JS) para o pedido feito no painel. */
    static void resposta(String texto, boolean fim) {
        BolhaService s = instancia;
        if (s != null) s.main.post(() -> s.mostrarResposta(texto, fim));
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        instancia = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    private boolean temMicrofone() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACAO_DESLIGAR.equals(intent.getAction())) {
            salvar(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean ok = false;
        if (Build.VERSION.SDK_INT >= 34) {
            // com o tipo "microfone" o painel pode ouvir mesmo com o Atlas fora da tela
            if (temMicrofone()) {
                try {
                    ServiceCompat.startForeground(this, NOTIF_ID, notificacao(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                    ok = true;
                } catch (Exception ignorado) { }
            }
            if (!ok) {
                try { ServiceCompat.startForeground(this, NOTIF_ID, notificacao(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE); ok = true; }
                catch (Exception ignorado) { }
            }
        } else if (Build.VERSION.SDK_INT >= 30 && temMicrofone()) {
            try { ServiceCompat.startForeground(this, NOTIF_ID, notificacao(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE); ok = true; }
            catch (Exception ignorado) { }
        }
        if (!ok) {
            try { ServiceCompat.startForeground(this, NOTIF_ID, notificacao(), 0); ok = true; } catch (Exception ignorado) { }
        }
        if (!ok) { stopSelf(); return START_NOT_STICKY; }
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
            .setContentText("Toque no núcleo do Atlas para falar com ele.")
            .setContentIntent(abrir)
            .addAction(0, "Desligar", desligar)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build();
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private int tipoJanela() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
    }

    /* ---------------- o núcleo (bolinha) ---------------- */

    /** Desenha o núcleo do Atlas: esfera ciano brilhante com anéis; pulsa enquanto ouve. */
    static class NucleoView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean ativo = false;
        NucleoView(Context c) { super(c); }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, r = Math.min(w, h) / 2f;
            float t = (SystemClock.uptimeMillis() % 1600) / 1600f;
            float pulso = ativo ? (float) (0.5 + 0.5 * Math.sin(t * Math.PI * 2)) : 0f;
            // brilho externo
            p.setStyle(Paint.Style.FILL);
            p.setShader(new RadialGradient(cx, cy, r, new int[]{Color.argb(150, 34, 211, 238), Color.argb(0, 34, 211, 238)}, null, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, p);
            // esfera
            float re = r * (0.62f + 0.05f * pulso);
            p.setShader(new RadialGradient(cx - re * 0.25f, cy - re * 0.3f, re * 1.3f,
                new int[]{Color.WHITE, Color.parseColor("#7DF9FF"), Color.parseColor("#0891B2"), Color.parseColor("#04122A")},
                new float[]{0f, 0.25f, 0.6f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, re, p);
            p.setShader(null);
            // anéis
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r * 0.06f);
            p.setColor(Color.argb(230, 34, 211, 238));
            c.drawCircle(cx, cy, re + r * 0.1f, p);
            p.setStrokeWidth(r * 0.03f);
            p.setColor(Color.argb(ativo ? (int) (120 + 120 * pulso) : 110, 167, 139, 250));
            c.drawCircle(cx, cy, re + r * (0.2f + 0.08f * pulso), p);
            if (ativo) postInvalidateOnAnimation();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void criarBolha() {
        if (bolha != null || !podeSobrepor(this)) return;
        int tam = dp(64);
        bolha = new NucleoView(this);
        bolha.setContentDescription("Falar com o Atlas");
        lp = new WindowManager.LayoutParams(tam, tam, tipoJanela(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        lp.x = p.getInt("x", dm.widthPixels - tam - dp(4));
        lp.y = p.getInt("y", dm.heightPixels / 3);

        final int toque = dp(8);
        bolha.setOnTouchListener(new View.OnTouchListener() {
            float x0, y0; int px, py; boolean moveu;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        x0 = e.getRawX(); y0 = e.getRawY(); px = lp.x; py = lp.y; moveu = false;
                        toqueNucleo = SystemClock.uptimeMillis();
                        v.setScaleX(0.92f); v.setScaleY(0.92f);
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
                        v.setScaleX(1f); v.setScaleY(1f);
                        if (moveu) {
                            // gruda na borda mais próxima
                            DisplayMetrics m = getResources().getDisplayMetrics();
                            lp.x = lp.x + v.getWidth() / 2 < m.widthPixels / 2 ? dp(2) : m.widthPixels - v.getWidth() - dp(2);
                            lp.y = Math.max(0, Math.min(lp.y, m.heightPixels - v.getHeight()));
                            try { wm.updateViewLayout(bolha, lp); } catch (Exception ignorado) {}
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("x", lp.x).putInt("y", lp.y).apply();
                            posicionarPainel();
                        } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                            if (painel != null) fecharPainel(); else abrirPainel();
                        }
                        return true;
                }
                return false;
            }
        });
        try { wm.addView(bolha, lp); } catch (Exception e) { bolha = null; }
    }

    /* ---------------- o painel rápido ---------------- */

    private TextView botao(String txt, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(txt);
        b.setTextColor(CIANO);
        b.setTextSize(12);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(6), dp(9), dp(6), dp(9));
        GradientDrawable f = new GradientDrawable();
        f.setCornerRadius(dp(10));
        f.setColor(Color.argb(40, 34, 211, 238));
        f.setStroke(dp(1), Color.argb(140, 34, 211, 238));
        b.setBackground(f);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(p);
        return b;
    }

    private void abrirPainel() {
        if (painel != null) return;
        painel = new LinearLayout(this);
        painel.setOrientation(LinearLayout.VERTICAL);
        painel.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable fundo = new GradientDrawable();
        fundo.setCornerRadius(dp(18));
        fundo.setColor(Color.parseColor("#F2040914"));
        fundo.setStroke(dp(1), Color.argb(170, 34, 211, 238));
        painel.setBackground(fundo);
        painel.setElevation(dp(10));

        LinearLayout topo = new LinearLayout(this);
        topo.setOrientation(LinearLayout.HORIZONTAL);
        topo.setGravity(Gravity.CENTER_VERTICAL);
        TextView titulo = new TextView(this);
        titulo.setText("ATLAS");
        titulo.setLetterSpacing(0.3f);
        titulo.setTextColor(Color.WHITE);
        titulo.setTypeface(Typeface.DEFAULT_BOLD);
        titulo.setTextSize(13);
        topo.addView(titulo, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        txtEstado = new TextView(this);
        txtEstado.setTextColor(CIANO);
        txtEstado.setTextSize(11);
        topo.addView(txtEstado);
        TextView fechar = new TextView(this);
        fechar.setText("  ✕");
        fechar.setTextColor(Color.argb(200, 255, 255, 255));
        fechar.setTextSize(16);
        fechar.setPadding(dp(8), 0, 0, 0);
        fechar.setOnClickListener(v -> fecharPainel());
        topo.addView(fechar);
        painel.addView(topo);

        ScrollView rolagem = new ScrollView(this);
        txtTexto = new TextView(this);
        txtTexto.setTextColor(Color.parseColor("#E6F7FF"));
        txtTexto.setTextSize(15);
        txtTexto.setLineSpacing(0, 1.15f);
        txtTexto.setPadding(0, dp(10), 0, dp(12));
        rolagem.addView(txtTexto);
        painel.addView(rolagem, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(110)));

        LinearLayout botoes = new LinearLayout(this);
        botoes.setOrientation(LinearLayout.HORIZONTAL);
        botoes.addView(botao("🎤 Falar", v -> ouvir()));
        botoes.addView(botao("⏯ Música", v -> { tecla(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); estado("música: play/pausa"); }));
        botoes.addView(botao("📰 Briefing", v -> abrirAtlas(ACAO_BRIEFING, null)));
        botoes.addView(botao("Abrir", v -> abrirAtlas(Intent.ACTION_MAIN, null)));
        painel.addView(botoes);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int largura = Math.min(dm.widthPixels - dp(24), dp(360));
        lpPainel = new WindowManager.LayoutParams(largura, WindowManager.LayoutParams.WRAP_CONTENT, tipoJanela(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT);
        lpPainel.gravity = Gravity.TOP | Gravity.START;
        painel.setOnTouchListener((v, e) -> {
            // tocou fora do painel (e fora do núcleo): fecha, mas a fala continua
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE && !ouvindo) {
                // o toque no próprio núcleo também chega aqui: ele mesmo decide abrir/fechar
                main.postDelayed(() -> { if (SystemClock.uptimeMillis() - toqueNucleo > 400) fecharPainel(); }, 120);
                return true;
            }
            return false;
        });
        posicionarPainel();
        try { wm.addView(painel, lpPainel); } catch (Exception e) { painel = null; return; }
        ouvir();
    }

    private void posicionarPainel() {
        if (lpPainel == null || lp == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        lpPainel.x = (dm.widthPixels - lpPainel.width) / 2;
        int alturaPainel = dp(210);
        int abaixo = lp.y + dp(70);
        lpPainel.y = abaixo + alturaPainel < dm.heightPixels ? abaixo : Math.max(dp(8), lp.y - alturaPainel - dp(6));
        if (painel != null) try { wm.updateViewLayout(painel, lpPainel); } catch (Exception ignorado) {}
    }

    private void fecharPainel() {
        pararEscuta();
        if (painel != null) { try { wm.removeView(painel); } catch (Exception ignorado) {} }
        painel = null; lpPainel = null; txtEstado = null; txtTexto = null;
        esperandoDesde = 0;
        if (bolha != null) { bolha.ativo = false; bolha.invalidate(); }
    }

    private void estado(String s) { if (txtEstado != null) txtEstado.setText(s); }

    private void texto(String s) { if (txtTexto != null) txtTexto.setText(s); }

    private void tecla(int code) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    private void abrirAtlas(String acao, String pedido) {
        Intent i = new Intent(this, MainActivity.class)
            .setAction(acao)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        if (pedido != null) i.putExtra(EXTRA_PEDIDO, pedido);
        fecharPainel();
        try { startActivity(i); } catch (Exception ignorado) {}
    }

    /* ---------------- escuta do painel ---------------- */

    private void ouvir() {
        if (painel == null) return;
        if (!temMicrofone()) { estado(""); texto("Libere o microfone para o Atlas (abra o app e toque no microfone uma vez)."); return; }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { abrirAtlas(ACAO_OUVIR, null); return; }
        AtlasBolha.avisar("parar_fala", null); // se o Atlas estiver falando, cala para ouvir
        if (sr == null) {
            sr = SpeechRecognizer.createSpeechRecognizer(this);
            sr.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle b) { estado("ouvindo…"); }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float v) {}
                @Override public void onBufferReceived(byte[] b) {}
                @Override public void onEndOfSpeech() { estado("entendendo…"); }
                @Override public void onEvent(int t, Bundle b) {}
                @Override public void onPartialResults(Bundle b) {
                    ArrayList<String> r = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (r != null && !r.isEmpty() && !TextUtils.isEmpty(r.get(0))) texto("“" + r.get(0) + "”");
                }
                @Override public void onResults(Bundle b) {
                    ouvindo = false;
                    if (bolha != null) { bolha.ativo = false; bolha.invalidate(); }
                    ArrayList<String> r = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String t = r == null || r.isEmpty() ? "" : r.get(0).trim();
                    if (t.isEmpty()) { estado(""); texto("Não ouvi nada. Toque em Falar e tente de novo."); return; }
                    pedir(t);
                }
                @Override public void onError(int erro) {
                    ouvindo = false;
                    if (bolha != null) { bolha.ativo = false; bolha.invalidate(); }
                    estado("");
                    if (erro == SpeechRecognizer.ERROR_NO_MATCH || erro == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) texto("Não ouvi nada. Toque em Falar e tente de novo.");
                    else if (erro == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) texto("O Android não deixou ouvir com o Atlas fechado. Toque em Abrir.");
                    else if (erro == SpeechRecognizer.ERROR_NETWORK || erro == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) texto("Sem internet para entender a voz. Tente de novo.");
                    else texto("Não consegui ouvir agora (erro " + erro + "). Toque em Falar.");
                }
            });
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        ouvindo = true;
        esperandoDesde = 0;
        if (bolha != null) { bolha.ativo = true; bolha.invalidate(); }
        estado("ouvindo…");
        texto("Pode falar.");
        try { sr.cancel(); sr.startListening(i); } catch (Exception e) { ouvindo = false; texto("Não consegui ligar o microfone."); }
    }

    private void pararEscuta() {
        ouvindo = false;
        if (sr != null) { try { sr.cancel(); } catch (Exception ignorado) {} }
    }

    /** Manda o pedido para o Atlas; se ele não estiver aberto em segundo plano, abre o app com o pedido. */
    private void pedir(String t) {
        texto("“" + t + "”");
        if (!AtlasBolha.avisar("pedido", t)) { abrirAtlas(ACAO_OUVIR, t); return; }
        estado("pensando…");
        final long minha = SystemClock.uptimeMillis();
        esperandoDesde = minha;
        main.postDelayed(() -> {
            if (esperandoDesde == minha && painel != null) { estado(""); texto("O Atlas está demorando. Toque em Abrir para ver no app."); esperandoDesde = 0; }
        }, 45000);
    }

    private void mostrarResposta(String t, boolean fim) {
        esperandoDesde = 0;
        if (painel == null) return;
        estado(fim ? "" : "falando…");
        if (t != null && !t.isEmpty()) texto(t);
    }

    /** Mostra o núcleo só com o Atlas fora da tela. */
    private void atualizar() {
        if (appNaFrente) {
            fecharPainel();
            if (bolha != null) { try { wm.removeView(bolha); } catch (Exception ignorado) {} bolha = null; }
        } else criarBolha();
    }

    @Override
    public void onDestroy() {
        fecharPainel();
        if (sr != null) { try { sr.destroy(); } catch (Exception ignorado) {} sr = null; }
        if (bolha != null) { try { wm.removeView(bolha); } catch (Exception ignorado) {} bolha = null; }
        if (instancia == this) instancia = null;
        super.onDestroy();
    }
}
