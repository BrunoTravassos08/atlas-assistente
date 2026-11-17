package br.com.atlas.assistente;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.util.Log;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

/**
 * Voz neural offline (Piper, pelo sherpa-onnx). A voz Faber vem dentro do APK;
 * as outras vozes em português são baixadas uma vez e ficam no aparelho.
 * Fala frase a frase: enquanto uma frase toca, a próxima já está sendo gerada.
 */
@CapacitorPlugin(name = "AtlasVoz")
public class AtlasVoz extends Plugin {

    private static final String TAG = "AtlasVoz";
    private static final String URL_VOZES = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-";
    private static final String[][] VOZES = {
        // id, nome, arquivo no pacote, licença
        {"faber", "Faber", "pt_BR-faber-medium", "CC0"},
        {"cadu", "Cadu", "pt_BR-cadu-medium", "CC0"},
        {"jeff", "Jeff", "pt_BR-jeff-medium", "CC0"},
        {"dii", "Dii", "pt_BR-dii-high", "CC BY-NC-SA 4.0 (uso não comercial)"},
        {"miro", "Miro", "pt_BR-miro-high", "CC BY-NC-SA 4.0 (uso não comercial)"},
    };

    private final ExecutorService gerador = Executors.newSingleThreadExecutor();
    private final ExecutorService baixador = Executors.newSingleThreadExecutor();
    private OfflineTts tts = null;
    private String vozAtual = null;
    private String erroCarga = null;
    private volatile int geracao = 0;
    private volatile AudioTrack trilha = null;
    private volatile boolean baixando = false;

    private Context ctx() { return getContext().getApplicationContext(); }

    private File pastaVozes() { File f = new File(ctx().getFilesDir(), "vozes"); f.mkdirs(); return f; }

    private boolean instalada(String id) {
        if ("faber".equals(id)) return true;
        File d = new File(pastaVozes(), id);
        return new File(d, "model.onnx").length() > 1000000 && new File(d, "tokens.txt").exists();
    }

    /** Os dados de pronúncia (espeak-ng) precisam estar em arquivo: descompacta uma vez. */
    private String dadosEspeak() throws Exception {
        File destino = new File(ctx().getFilesDir(), "espeak-ng-data");
        File marca = new File(destino, ".ok2");
        if (marca.exists()) return destino.getAbsolutePath();
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(ctx().getAssets().open("piper/espeak-ng-data.zip")))) {
            ZipEntry e; byte[] buf = new byte[65536];
            while ((e = zin.getNextEntry()) != null) {
                File out = new File(ctx().getFilesDir(), e.getName());
                if (!out.getCanonicalPath().startsWith(ctx().getFilesDir().getCanonicalPath())) continue;
                if (e.isDirectory()) { out.mkdirs(); continue; }
                out.getParentFile().mkdirs();
                try (OutputStream os = new FileOutputStream(out)) { int n; while ((n = zin.read(buf)) > 0) os.write(buf, 0, n); }
            }
        }
        marca.createNewFile();
        return destino.getAbsolutePath();
    }

    /** Carrega (ou troca) a voz. Roda sempre na thread do gerador. */
    private void garantirVoz(String id) throws Exception {
        if (!instalada(id)) id = "faber";
        if (tts != null && id.equals(vozAtual)) return;
        long t0 = SystemClock.elapsedRealtime();
        String dados = dadosEspeak();
        OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
        AssetManager am = null;
        if ("faber".equals(id)) {
            am = ctx().getAssets();
            vits.setModel("piper/faber/model.onnx");
            vits.setTokens("piper/faber/tokens.txt");
        } else {
            File d = new File(pastaVozes(), id);
            vits.setModel(new File(d, "model.onnx").getAbsolutePath());
            vits.setTokens(new File(d, "tokens.txt").getAbsolutePath());
        }
        vits.setDataDir(dados);
        OfflineTtsModelConfig modelo = new OfflineTtsModelConfig();
        modelo.setVits(vits);
        modelo.setNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
        modelo.setDebug(false);
        modelo.setProvider("cpu");
        OfflineTtsConfig cfg = new OfflineTtsConfig();
        cfg.setModel(modelo);
        cfg.setMaxNumSentences(1);
        if (tts != null) { try { tts.release(); } catch (Throwable ignored) {} tts = null; }
        tts = new OfflineTts(am, cfg);
        vozAtual = id;
        erroCarga = null;
        Log.i(TAG, "voz " + id + " carregada em " + (SystemClock.elapsedRealtime() - t0) + " ms");
    }

    /** Divide em frases curtas, para começar a falar logo. */
    static List<String> frases(String texto) {
        List<String> out = new ArrayList<>();
        String t = texto.replaceAll("\\s+", " ").trim();
        StringBuilder atual = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            atual.append(c);
            boolean fim = (c == '.' || c == '!' || c == '?' || c == ';' || c == ':' || c == '\n')
                && (i + 1 >= t.length() || t.charAt(i + 1) == ' ');
            boolean longa = atual.length() > 180 && c == ',';
            if (fim || longa) { String f = atual.toString().trim(); if (f.length() > 0) out.add(f); atual.setLength(0); }
        }
        String f = atual.toString().trim();
        if (f.length() > 0) out.add(f);
        return out;
    }

    @Override
    public void load() {
        // aquece a voz padrão em segundo plano, para a primeira fala sair rápido
        gerador.execute(() -> {
            try { garantirVoz("faber"); }
            catch (Throwable e) { erroCarga = String.valueOf(e); Log.e(TAG, "falha ao carregar a voz", e); }
        });
    }

    @PluginMethod
    public void info(PluginCall call) {
        JSObject r = new JSObject();
        JSArray lista = new JSArray();
        for (String[] v : VOZES) {
            JSObject o = new JSObject();
            o.put("id", v[0]); o.put("nome", v[1]); o.put("licenca", v[3]);
            o.put("instalada", instalada(v[0])); o.put("embutida", "faber".equals(v[0]));
            lista.put(o);
        }
        r.put("vozes", lista);
        r.put("pronta", tts != null);
        r.put("voz", vozAtual);
        r.put("erro", erroCarga);
        r.put("baixando", baixando);
        call.resolve(r);
    }

    @PluginMethod
    public void falar(PluginCall call) {
        final String texto = call.getString("texto", "");
        final String voz = call.getString("voz", "faber");
        final float velocidade = call.getFloat("velocidade", 1.0f);
        final int minha = ++geracao;
        pararAudio();
        if (texto.trim().isEmpty()) { call.resolve(); return; }
        final LinkedBlockingQueue<float[]> fila = new LinkedBlockingQueue<>();
        final float[] FIM = new float[0];
        final int[] taxa = {22050};
        final String[] erro = {null};
        // gera as frases uma a uma
        gerador.execute(() -> {
            try {
                garantirVoz(voz);
                taxa[0] = tts.sampleRate();
                for (String f : frases(texto)) {
                    if (minha != geracao) break;
                    GeneratedAudio a = tts.generate(f, 0, velocidade);
                    if (minha != geracao) break;
                    fila.put(a.getSamples());
                }
            } catch (Throwable e) {
                erro[0] = String.valueOf(e);
                Log.e(TAG, "falha ao gerar", e);
            } finally {
                fila.offer(FIM);
            }
        });
        // toca na ordem, enquanto as próximas são geradas
        new Thread(() -> {
            AudioTrack at = null;
            boolean tocouAlgo = false;
            try {
                while (minha == geracao) {
                    float[] s = fila.poll(200, TimeUnit.MILLISECONDS);
                    if (s == null) continue;
                    if (s == FIM) break;
                    if (at == null) {
                        int min = AudioTrack.getMinBufferSize(taxa[0], AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
                        at = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                .setSampleRate(taxa[0])
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setBufferSizeInBytes(Math.max(min, taxa[0] * 4 / 2))
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build();
                        trilha = at;
                        at.play();
                    }
                    int pos = 0;
                    while (pos < s.length && minha == geracao) {
                        int n = at.write(s, pos, Math.min(4096, s.length - pos), AudioTrack.WRITE_BLOCKING);
                        if (n <= 0) break;
                        pos += n;
                    }
                    tocouAlgo = true;
                }
                if (at != null && minha == geracao) {
                    // espera o fim do que já foi escrito
                    long fimMs = SystemClock.elapsedRealtime() + 15000;
                    at.stop();
                    while (at.getPlayState() == AudioTrack.PLAYSTATE_PLAYING && SystemClock.elapsedRealtime() < fimMs && minha == geracao) Thread.sleep(40);
                }
            } catch (Throwable e) {
                if (erro[0] == null) erro[0] = String.valueOf(e);
            } finally {
                if (at != null) { try { at.release(); } catch (Throwable ignored) {} }
                if (trilha == at) trilha = null;
            }
            if (erro[0] != null && !tocouAlgo) call.reject(erro[0]);
            else { JSObject r = new JSObject(); r.put("interrompido", minha != geracao); call.resolve(r); }
        }, "atlas-voz").start();
    }

    private void pararAudio() {
        AudioTrack at = trilha;
        if (at != null) { try { at.pause(); at.flush(); } catch (Throwable ignored) {} }
    }

    @PluginMethod
    public void parar(PluginCall call) {
        geracao++;
        pararAudio();
        call.resolve();
    }

    @PluginMethod
    public void carregar(PluginCall call) {
        final String voz = call.getString("voz", "faber");
        gerador.execute(() -> {
            try { garantirVoz(voz); JSObject r = new JSObject(); r.put("voz", vozAtual); call.resolve(r); }
            catch (Throwable e) { erroCarga = String.valueOf(e); call.reject(String.valueOf(e)); }
        });
    }

    @PluginMethod
    public void baixar(PluginCall call) {
        final String id = call.getString("id", "");
        String[] alvo = null;
        for (String[] v : VOZES) if (v[0].equals(id)) alvo = v;
        if (alvo == null || "faber".equals(id)) { call.reject("voz desconhecida"); return; }
        if (baixando) { call.reject("já existe um download em andamento"); return; }
        final String pacote = alvo[2];
        baixando = true;
        baixador.execute(() -> {
            File destino = new File(pastaVozes(), id);
            File tmp = new File(pastaVozes(), id + ".parcial");
            try {
                deleteRec(tmp); tmp.mkdirs();
                HttpURLConnection c = (HttpURLConnection) new URL(URL_VOZES + pacote + ".tar.bz2").openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(20000);
                c.setReadTimeout(60000);
                long total = c.getContentLengthLong();
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                try (InputStream raw = c.getInputStream()) {
                    ContadorStream cont = new ContadorStream(raw, total, id);
                    try (TarArchiveInputStream tar = new TarArchiveInputStream(new BZip2CompressorInputStream(new BufferedInputStream(cont, 65536)))) {
                        TarArchiveEntry e; byte[] buf = new byte[65536];
                        while ((e = tar.getNextEntry()) != null) {
                            String nome = e.getName();
                            File out = null;
                            if (nome.endsWith(pacote + ".onnx")) out = new File(tmp, "model.onnx");
                            else if (nome.endsWith("/tokens.txt")) out = new File(tmp, "tokens.txt");
                            else if (nome.endsWith("/MODEL_CARD") || nome.endsWith("/README.md")) out = new File(tmp, "LICENCA.txt");
                            if (out == null) continue;
                            try (OutputStream os = new FileOutputStream(out)) { int n; while ((n = tar.read(buf)) > 0) os.write(buf, 0, n); }
                        }
                    }
                }
                if (new File(tmp, "model.onnx").length() < 1000000) throw new Exception("arquivo da voz incompleto");
                deleteRec(destino);
                if (!tmp.renameTo(destino)) throw new Exception("não consegui salvar a voz");
                JSObject r = new JSObject(); r.put("id", id); call.resolve(r);
            } catch (Throwable e) {
                deleteRec(tmp);
                call.reject("Não consegui baixar a voz: " + e.getMessage());
            } finally {
                baixando = false;
            }
        });
    }

    @PluginMethod
    public void remover(PluginCall call) {
        final String id = call.getString("id", "");
        if ("faber".equals(id) || id.isEmpty()) { call.reject("a voz embutida não pode ser removida"); return; }
        gerador.execute(() -> {
            if (id.equals(vozAtual)) { try { if (tts != null) tts.release(); } catch (Throwable ignored) {} tts = null; vozAtual = null; }
            deleteRec(new File(pastaVozes(), id));
            call.resolve();
        });
    }

    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) deleteRec(x);
        f.delete();
    }

    /** Conta os bytes baixados e avisa o app a cada 2%. */
    private class ContadorStream extends java.io.FilterInputStream {
        private long lidos = 0; private final long total; private final String id; private int ultimo = -1;
        ContadorStream(InputStream in, long total, String id) { super(in); this.total = total; this.id = id; }
        private void conta(long n) {
            if (n <= 0) return; lidos += n;
            int pct = total > 0 ? (int) (lidos * 100 / total) : -1;
            if (pct / 2 != ultimo / 2) { ultimo = pct; JSObject o = new JSObject(); o.put("id", id); o.put("pct", pct); o.put("mb", lidos / 1048576); notifyListeners("progresso", o); }
        }
        @Override public int read() throws java.io.IOException { int b = super.read(); if (b >= 0) conta(1); return b; }
        @Override public int read(byte[] b, int off, int len) throws java.io.IOException { int n = super.read(b, off, len); conta(n); return n; }
    }

    @Override
    protected void handleOnDestroy() {
        geracao++;
        pararAudio();
        gerador.execute(() -> { if (tts != null) { try { tts.release(); } catch (Throwable ignored) {} tts = null; } });
        super.handleOnDestroy();
    }
}
