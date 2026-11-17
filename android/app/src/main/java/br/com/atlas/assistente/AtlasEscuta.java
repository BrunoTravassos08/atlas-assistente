package br.com.atlas.assistente;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.util.Log;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

/**
 * Escuta a palavra "Atlas" sem internet (sherpa-onnx, modelo de palavra-chave em assets/kws).
 * Só roda com o app na frente. Ao ouvir, solta o microfone e avisa o JS ("palavra"),
 * que abre o reconhecimento de voz normal para captar o pedido.
 */
@CapacitorPlugin(name = "AtlasEscuta")
public class AtlasEscuta extends Plugin {
    private static final String TAG = "AtlasEscuta";
    private static final int TAXA = 16000;
    private KeywordSpotter spotter;
    private volatile boolean rodando = false;
    private Thread thread;
    private String erroModelo = null;

    private synchronized KeywordSpotter spotter() {
        if (spotter != null || erroModelo != null) return spotter;
        try {
            String[] arquivos = getContext().getAssets().list("kws");
            if (arquivos == null || arquivos.length < 5) throw new IllegalStateException("modelo ausente");
            OnlineTransducerModelConfig t = new OnlineTransducerModelConfig();
            t.setEncoder("kws/encoder.onnx");
            t.setDecoder("kws/decoder.onnx");
            t.setJoiner("kws/joiner.onnx");
            OnlineModelConfig m = new OnlineModelConfig();
            m.setTransducer(t);
            m.setTokens("kws/tokens.txt");
            m.setNumThreads(1);
            m.setProvider("cpu");
            FeatureConfig f = new FeatureConfig();
            f.setSampleRate(TAXA);
            f.setFeatureDim(80);
            KeywordSpotterConfig c = new KeywordSpotterConfig();
            c.setFeatConfig(f);
            c.setModelConfig(m);
            c.setKeywordsFile("kws/keywords.txt");
            c.setMaxActivePaths(8);
            c.setKeywordsScore(1.0f);
            c.setKeywordsThreshold(0.1f);
            c.setNumTrailingBlanks(1);
            spotter = new KeywordSpotter(getContext().getAssets(), c);
        } catch (Throwable e) {
            erroModelo = String.valueOf(e.getMessage());
            Log.e(TAG, "modelo", e);
        }
        return spotter;
    }

    /* ---------- modo Google: reconhecimento de voz do aparelho em ciclo, procurando o nome ---------- */
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer sr;
    private volatile boolean googleAtivo = false;
    private boolean noAparelho = false, semPacote = false, avisouOuvindo = false;
    private Pattern nome = Pattern.compile("\\b(atlas|atlass|attlas|adlas|atlaz|atilas|atlis)\\b");

    private static String normalizar(String t) {
        String n = Normalizer.normalize(String.valueOf(t).toLowerCase(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private void criarReconhecedor() {
        if (sr != null) { try { sr.destroy(); } catch (Exception ignorado) {} sr = null; }
        // o reconhecedor "no aparelho" (Android 13+) não usa internet nem faz bipe; se não tiver o português baixado, usa o normal
        if (Build.VERSION.SDK_INT >= 33 && !semPacote && SpeechRecognizer.isOnDeviceRecognitionAvailable(getContext())) {
            try { sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(getContext()); noAparelho = true; } catch (Exception e) { sr = null; }
        }
        if (sr == null) { sr = SpeechRecognizer.createSpeechRecognizer(getContext()); noAparelho = false; }
        sr.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float v) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onEvent(int t, Bundle b) {}
            @Override public void onPartialResults(Bundle b) {
                String t = primeiro(b);
                if (t != null && !avisouOuvindo && nome.matcher(normalizar(t)).find()) {
                    avisouOuvindo = true;
                    notifyListeners("ouvindo", new JSObject());
                }
            }
            @Override public void onResults(Bundle b) {
                ArrayList<String> rs = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (rs != null) for (String t : rs) {
                    Matcher m = nome.matcher(normalizar(t));
                    if (m.find()) {
                        String depois = normalizar(t).substring(m.end()).trim();
                        // tira o nome do texto original mantendo acentos
                        String original = t.trim();
                        String[] palavras = original.split("\\s+");
                        int nDepois = depois.isEmpty() ? 0 : depois.split(" ").length;
                        StringBuilder cmd = new StringBuilder();
                        for (int i = Math.max(0, palavras.length - nDepois); i < palavras.length; i++) cmd.append(i > palavras.length - nDepois ? " " : "").append(palavras[i]);
                        googleAtivo = false;
                        JSObject d = new JSObject();
                        d.put("palavra", "ATLAS");
                        d.put("texto", original);
                        d.put("comando", cmd.toString().replaceAll("^[,.!?\\s]+", ""));
                        notifyListeners("palavra", d);
                        return;
                    }
                }
                proximo(80);
            }
            @Override public void onError(int erro) {
                if (!googleAtivo) return;
                if ((erro == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || erro == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) && noAparelho) {
                    // sem o pacote de português no aparelho: passa para o reconhecedor normal
                    semPacote = true; noAparelho = false; // não tenta de novo o "no aparelho"
                    try { sr.destroy(); } catch (Exception ignorado) {}
                    sr = SpeechRecognizer.createSpeechRecognizer(getContext());
                    sr.setRecognitionListener(this);
                    proximo(200);
                    return;
                }
                if (erro == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    googleAtivo = false;
                    JSObject e = new JSObject(); e.put("erro", "sem permissão do microfone");
                    notifyListeners("parou", e);
                    return;
                }
                int espera = erro == SpeechRecognizer.ERROR_NETWORK || erro == SpeechRecognizer.ERROR_NETWORK_TIMEOUT || erro == SpeechRecognizer.ERROR_SERVER ? 3000
                    : erro == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 700
                    : erro == SpeechRecognizer.ERROR_CLIENT ? 400 : 120;
                if (erro == SpeechRecognizer.ERROR_CLIENT || erro == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    try { sr.cancel(); } catch (Exception ignorado) {}
                }
                proximo(espera);
            }
        });
    }

    private static String primeiro(Bundle b) {
        ArrayList<String> rs = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return rs == null || rs.isEmpty() ? null : rs.get(0);
    }

    private void proximo(int ms) {
        main.removeCallbacksAndMessages(null);
        main.postDelayed(this::ouvirGoogle, ms);
    }

    private void ouvirGoogle() {
        if (!googleAtivo) return;
        if (sr == null) criarReconhecedor();
        avisouOuvindo = false;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getContext().getPackageName());
        try { sr.startListening(i); } catch (Exception e) { criarReconhecedor(); proximo(500); }
    }

    private void pararGoogle() {
        googleAtivo = false;
        main.removeCallbacksAndMessages(null);
        main.post(() -> { if (sr != null) { try { sr.cancel(); } catch (Exception ignorado) {} } });
    }

    @PluginMethod
    public void iniciar(PluginCall call) {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            call.reject("microfone não liberado", "sem_permissao");
            return;
        }
        if ("google".equals(call.getString("modo", "google"))) {
            if (!SpeechRecognizer.isRecognitionAvailable(getContext())) { call.reject("sem reconhecimento de voz", "sem_google"); return; }
            pararThread();
            if (!googleAtivo) { googleAtivo = true; main.post(this::ouvirGoogle); }
            call.resolve();
            return;
        }
        pararGoogle();
        if (rodando) { call.resolve(); return; }
        pararThread(); // espera a escuta anterior soltar o microfone
        rodando = true;
        final PluginCall pedido = call;
        thread = new Thread(() -> ouvir(pedido), "atlas-escuta");
        thread.start();
    }

    private void ouvir(PluginCall pedido) {
        KeywordSpotter kws = spotter();
        if (kws == null) {
            rodando = false;
            pedido.reject("modelo da palavra não carregou: " + erroModelo, "sem_modelo");
            return;
        }
        int min = AudioRecord.getMinBufferSize(TAXA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = null;
        OnlineStream s = null;
        String achou = null;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, TAXA, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, TAXA / 5 * 2));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("microfone ocupado");
            rec.startRecording();
            s = kws.createStream("");
            pedido.resolve();
            short[] buf = new short[TAXA / 10];
            double nivel = 0;
            float[] amostras = new float[buf.length];
            while (rodando) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) continue;
                float[] x = n == amostras.length ? amostras : new float[n];
                // ganho automático: voz de longe chega baixa e o modelo não reconhece
                double soma = 0;
                for (int i = 0; i < n; i++) { x[i] = buf[i] / 32768f; soma += x[i] * x[i]; }
                double rms = Math.sqrt(soma / n) + 1e-7;
                nivel = rms > nivel ? rms : nivel * 0.97 + rms * 0.03;
                float g = (float) Math.min(10.0, 0.1 / Math.max(nivel, 1e-4));
                for (int i = 0; i < n; i++) x[i] = Math.max(-1f, Math.min(1f, x[i] * g));
                s.acceptWaveform(x, TAXA);
                while (kws.isReady(s)) {
                    kws.decode(s);
                    KeywordSpotterResult r = kws.getResult(s);
                    if (r.getKeyword() != null && !r.getKeyword().isEmpty()) {
                        achou = r.getKeyword();
                        kws.reset(s);
                        rodando = false;
                        break;
                    }
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "ouvir", e);
            if (rodando) { rodando = false; try { pedido.reject("não consegui ouvir: " + e.getMessage(), "erro"); } catch (Throwable ignorado) {} }
            JSObject err = new JSObject();
            err.put("erro", String.valueOf(e.getMessage()));
            notifyListeners("parou", err);
        } finally {
            if (s != null) try { s.release(); } catch (Throwable ignorado) {}
            if (rec != null) {
                try { rec.stop(); } catch (Throwable ignorado) {}
                rec.release();
            }
            rodando = false;
        }
        // o microfone já foi solto: o reconhecimento de voz do Android pode usá-lo
        if (achou != null) {
            JSObject d = new JSObject();
            d.put("palavra", achou);
            notifyListeners("palavra", d);
        }
    }

    private void pararThread() {
        rodando = false;
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(1500); } catch (InterruptedException ignorado) {}
        }
        thread = null;
    }

    @PluginMethod
    public void parar(PluginCall call) {
        pararGoogle();
        pararThread();
        call.resolve();
    }

    @PluginMethod
    public void info(PluginCall call) {
        JSObject r = new JSObject();
        r.put("rodando", rodando);
        try {
            String[] a = getContext().getAssets().list("kws");
            r.put("modelo", a != null && a.length >= 5);
        } catch (Exception e) { r.put("modelo", false); }
        if (erroModelo != null) r.put("erro", erroModelo);
        r.put("google", googleAtivo);
        r.put("noAparelho", noAparelho);
        call.resolve(r);
    }

    @Override
    protected void handleOnPause() {
        // fora da tela não escuta (o JS volta a ligar quando o app reaparece)
        pararGoogle();
        pararThread();
        super.handleOnPause();
    }

    @Override
    protected void handleOnDestroy() {
        pararThread();
        googleAtivo = false;
        main.post(() -> { if (sr != null) { try { sr.destroy(); } catch (Exception ignorado) {} sr = null; } });
        if (spotter != null) { spotter.release(); spotter = null; }
        super.handleOnDestroy();
    }
}
