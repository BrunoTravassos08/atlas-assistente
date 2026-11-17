package br.com.atlas.assistente;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
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

    @PluginMethod
    public void iniciar(PluginCall call) {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            call.reject("microfone não liberado", "sem_permissao");
            return;
        }
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
            float[] amostras = new float[buf.length];
            while (rodando) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) continue;
                float[] x = n == amostras.length ? amostras : new float[n];
                for (int i = 0; i < n; i++) x[i] = buf[i] / 32768f;
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
        call.resolve(r);
    }

    @Override
    protected void handleOnPause() {
        // fora da tela não escuta (o JS volta a ligar quando o app reaparece)
        pararThread();
        super.handleOnPause();
    }

    @Override
    protected void handleOnDestroy() {
        pararThread();
        if (spotter != null) { spotter.release(); spotter = null; }
        super.handleOnDestroy();
    }
}
