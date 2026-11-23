package br.com.atlas.assistente;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.webkit.WebView;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Abre links no navegador de verdade do aparelho (não numa aba interna),
 * informa qual navegador e qual versão estão instalados (para avisar quando
 * estão desatualizados) e se os apps do ChatGPT e do Claude estão instalados.
 */
@CapacitorPlugin(name = "AtlasSistema")
public class AtlasSistema extends Plugin {

    private PackageManager pm() { return getContext().getPackageManager(); }

    private Intent viewIntent(String url) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addCategory(Intent.CATEGORY_BROWSABLE);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private JSObject pacote(String pkg) {
        JSObject o = new JSObject();
        o.put("pacote", pkg);
        try {
            PackageInfo pi = pm().getPackageInfo(pkg, 0);
            o.put("instalado", true);
            o.put("versao", pi.versionName == null ? "" : pi.versionName);
            o.put("major", major(pi.versionName));
            o.put("nome", String.valueOf(pi.applicationInfo.loadLabel(pm())));
        } catch (Exception e) {
            o.put("instalado", false);
        }
        return o;
    }

    private static int major(String v) {
        if (v == null) return 0;
        try {
            String m = v.trim().split("[^0-9]")[0];
            return m.isEmpty() ? 0 : Integer.parseInt(m);
        } catch (Exception e) {
            return 0;
        }
    }

    @PluginMethod
    public void abrirLink(PluginCall call) {
        String url = call.getString("url");
        boolean escolher = Boolean.TRUE.equals(call.getBoolean("escolher", false));
        String pacoteAlvo = call.getString("pacote");
        if (url == null) { call.reject("sem url"); return; }
        try {
            Intent i = viewIntent(url);
            if (pacoteAlvo != null && !pacoteAlvo.isEmpty()) i.setPackage(pacoteAlvo);
            if (escolher) {
                Intent c = Intent.createChooser(i, "Abrir com qual navegador?");
                c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getActivity().startActivity(c);
            } else {
                getActivity().startActivity(i);
            }
            call.resolve();
        } catch (ActivityNotFoundException e) {
            call.reject("nenhum navegador");
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void abrirLoja(PluginCall call) {
        String pkg = call.getString("pacote", "com.android.chrome");
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getActivity().startActivity(i);
        } catch (Exception e) {
            try {
                getActivity().startActivity(viewIntent("https://play.google.com/store/apps/details?id=" + pkg));
            } catch (Exception ignored) { }
        }
        call.resolve();
    }

    @PluginMethod
    public void info(PluginCall call) {
        JSObject r = new JSObject();
        Intent web = viewIntent("https://platform.openai.com/");

        // navegador padrão (null quando o usuário ainda não escolheu um)
        String padrao = null;
        ResolveInfo def = pm().resolveActivity(web, PackageManager.MATCH_DEFAULT_ONLY);
        if (def != null && def.activityInfo != null && !"android".equals(def.activityInfo.packageName)) {
            padrao = def.activityInfo.packageName;
            r.put("padrao", pacote(padrao));
        }

        // todos os navegadores instalados
        JSArray lista = new JSArray();
        Set<String> vistos = new HashSet<>();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PackageManager.MATCH_ALL : 0;
        List<ResolveInfo> todos = pm().queryIntentActivities(web, flags);
        for (ResolveInfo ri : todos) {
            String p = ri.activityInfo.packageName;
            if (vistos.add(p)) lista.put(pacote(p));
        }
        r.put("navegadores", lista);

        // WebView do sistema (usado pelas abas internas)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PackageInfo wv = WebView.getCurrentWebViewPackage();
            if (wv != null) {
                JSObject o = new JSObject();
                o.put("pacote", wv.packageName);
                o.put("versao", wv.versionName);
                o.put("major", major(wv.versionName));
                r.put("webview", o);
            }
        }
        r.put("chrome", pacote("com.android.chrome"));
        r.put("chatgpt", pacote("com.openai.chatgpt"));
        r.put("claude", pacote("com.anthropic.claude"));
        r.put("gemini", pacote("com.google.android.apps.bard"));
        r.put("android", Build.VERSION.SDK_INT);
        call.resolve(r);
    }

    // ---------- música: tocar em outro app e voltar para o Atlas ----------
    private static final String[][] APPS_MUSICA = {
        {"spotify", "com.spotify.music", "Spotify", "spotify:search:"},
        {"ytmusic", "com.google.android.apps.youtube.music", "YouTube Music", "https://music.youtube.com/search?q="},
        {"youtube", "com.google.android.youtube", "YouTube", "https://www.youtube.com/results?search_query="},
        {"deezer", "deezer.android.app", "Deezer", "https://www.deezer.com/search/"},
        {"soundcloud", "com.soundcloud.android", "SoundCloud", "https://soundcloud.com/search?q="},
        {"amazon", "com.amazon.mp3", "Amazon Music", "https://music.amazon.com.br/search/"}
    };

    private boolean instalado(String pkg) {
        try { pm().getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    private Intent intentTocar(String busca, String pkg, String foco) {
        Intent i = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH);
        i.putExtra(SearchManager.QUERY, busca);
        i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, foco);
        if ("vnd.android.cursor.item/artist".equals(foco)) i.putExtra(MediaStore.EXTRA_MEDIA_ARTIST, busca);
        if ("vnd.android.cursor.item/audio".equals(foco)) i.putExtra(MediaStore.EXTRA_MEDIA_TITLE, busca);
        i.setPackage(pkg);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        return i;
    }

    @PluginMethod
    public void appsMusica(PluginCall call) {
        JSArray lista = new JSArray();
        for (String[] a : APPS_MUSICA) {
            if (!instalado(a[1])) continue;
            JSObject o = new JSObject();
            o.put("id", a[0]); o.put("pacote", a[1]); o.put("nome", a[2]);
            o.put("tocaDireto", !pm().queryIntentActivities(intentTocar("x", a[1], "vnd.android.cursor.item/*"), 0).isEmpty());
            lista.put(o);
        }
        JSObject r = new JSObject();
        r.put("apps", lista);
        r.put("tocando", audio().isMusicActive());
        call.resolve(r);
    }

    private AudioManager audio() { return (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE); }

    private void voltarParaAtlas() {
        Activity act = getActivity();
        if (act == null) return;
        try {
            ActivityManager am = (ActivityManager) act.getSystemService(Context.ACTIVITY_SERVICE);
            am.moveTaskToFront(act.getTaskId(), ActivityManager.MOVE_TASK_NO_USER_ACTION);
        } catch (Exception ignored) { }
        try {
            Intent volta = new Intent(act, MainActivity.class);
            volta.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            act.startActivity(volta);
        } catch (Exception ignored) { }
    }

    @PluginMethod
    public void tocarMusica(PluginCall call) {
        String busca = call.getString("busca", "").trim();
        String app = call.getString("app", "");
        String tipo = call.getString("tipo", "");
        boolean voltar = Boolean.TRUE.equals(call.getBoolean("voltar", true));
        if (busca.isEmpty()) { call.reject("sem busca"); return; }
        final String foco = "artista".equals(tipo) ? "vnd.android.cursor.item/artist"
            : "musica".equals(tipo) ? "vnd.android.cursor.item/audio"
            : "playlist".equals(tipo) ? "vnd.android.cursor.item/playlist"
            : "album".equals(tipo) ? "vnd.android.cursor.item/album" : "vnd.android.cursor.item/*";

        // ordem: o app pedido, depois Spotify e os outros instalados
        java.util.ArrayList<String[]> ordem = new java.util.ArrayList<>();
        for (String[] a : APPS_MUSICA) if (a[0].equals(app)) ordem.add(a);
        for (String[] a : APPS_MUSICA) if (!a[0].equals(app)) ordem.add(a);

        Activity act = getActivity();
        for (String[] a : ordem) {
            if (!instalado(a[1])) continue;
            final String pkg = a[1];
            JSObject r = new JSObject();
            r.put("app", a[0]); r.put("nome", a[2]); r.put("pacote", pkg);

            // abre o app já pedindo a música
            final Intent i = intentTocar(busca, pkg, foco);
            boolean direto = !pm().queryIntentActivities(i, 0).isEmpty();
            try {
                if (direto) {
                    act.startActivity(i);
                } else {
                    Intent v = new Intent(Intent.ACTION_VIEW, Uri.parse(a[3] + Uri.encode(busca)));
                    v.setPackage(pkg);
                    v.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    act.startActivity(v);
                }
            } catch (Exception e) {
                continue;
            }

            // o app abriu mas às vezes (Spotify fechado) só faz a busca e não toca:
            // com ele já aberto, pede de novo (aí ele toca) e, em último caso, aperta o play
            if (direto) {
                final Handler h = new Handler(Looper.getMainLooper());
                final long inicio = SystemClock.uptimeMillis();
                final boolean[] mandou = {false, false};
                final android.content.Context ctx = getContext().getApplicationContext();
                h.postDelayed(new Runnable() {
                    @Override public void run() {
                        long t = SystemClock.uptimeMillis() - inicio;
                        boolean tocando = audio().isMusicActive();
                        if (!tocando && !mandou[0] && t > 3500) { try { ctx.startActivity(i); } catch (Exception ignorado) { } mandou[0] = true; }
                        if (!tocando && !mandou[1] && t > 6500) { tecla(KeyEvent.KEYCODE_MEDIA_PLAY); mandou[1] = true; }
                        if ((tocando && t > 1500) || t > 10000) {
                            if (voltar) voltarParaAtlas();
                            return;
                        }
                        h.postDelayed(this, 500);
                    }
                }, 1200);
            }
            r.put("metodo", direto ? "tocar" : "busca");
            call.resolve(r);
            return;
        }
        call.reject("nenhum app de música");
    }

    // ---------- Spotify: login (volta em atlas://spotify) e abrir o app para ele virar um aparelho ----------
    private PluginCall spotifyCall = null;
    private String spotifyVolta = null;

    private boolean ehVoltaSpotify(Intent i) {
        Uri d = i == null ? null : i.getData();
        return d != null && "atlas".equals(d.getScheme()) && "spotify".equals(d.getHost());
    }

    @Override
    public void load() {
        Activity act = getActivity();
        // o Android pode ter fechado o Atlas durante o login: o endereço de volta chega na abertura
        if (act != null && ehVoltaSpotify(act.getIntent())) spotifyVolta = act.getIntent().getDataString();
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        if (!ehVoltaSpotify(intent)) return;
        String url = intent.getDataString();
        if (spotifyCall != null) {
            JSObject r = new JSObject();
            r.put("url", url);
            spotifyCall.resolve(r);
            spotifyCall = null;
        } else {
            spotifyVolta = url;
        }
    }

    @PluginMethod
    public void spotifyLogin(PluginCall call) {
        String url = call.getString("url", "");
        if (!url.startsWith("https://accounts.spotify.com/")) { call.reject("link inválido"); return; }
        if (spotifyCall != null) spotifyCall.reject("cancelado");
        spotifyVolta = null;
        try {
            getActivity().startActivity(viewIntent(url));
            spotifyCall = call;   // responde quando o navegador voltar para atlas://spotify
        } catch (Exception e) {
            call.reject("nenhum navegador");
        }
    }

    @PluginMethod
    public void spotifyRetorno(PluginCall call) {
        JSObject r = new JSObject();
        if (spotifyVolta != null) r.put("url", spotifyVolta);
        spotifyVolta = null;
        call.resolve(r);
    }

    @PluginMethod
    public void abrirSpotify(PluginCall call) {
        String uri = call.getString("uri", "spotify:");
        if (!uri.startsWith("spotify:")) uri = "spotify:";
        if (!instalado("com.spotify.music")) { call.reject("Spotify não instalado"); return; }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            i.setPackage("com.spotify.music");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            getActivity().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void voltarAtlas(PluginCall call) {
        new Handler(Looper.getMainLooper()).postDelayed(this::voltarParaAtlas, 600);
        call.resolve();
    }

    private void tecla(int code) {
        AudioManager am = audio();
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    @PluginMethod
    public void controleMidia(PluginCall call) {
        String acao = call.getString("acao", "");
        AudioManager am = audio();
        switch (acao) {
            case "pausar": tecla(KeyEvent.KEYCODE_MEDIA_PAUSE); break;
            case "continuar": tecla(KeyEvent.KEYCODE_MEDIA_PLAY); break;
            case "alternar": tecla(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); break;
            case "proxima": tecla(KeyEvent.KEYCODE_MEDIA_NEXT); break;
            case "anterior": tecla(KeyEvent.KEYCODE_MEDIA_PREVIOUS); break;
            case "aumentar_volume": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0); break;
            case "diminuir_volume": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0); break;
            default: call.reject("ação desconhecida"); return;
        }
        JSObject r = new JSObject();
        r.put("ok", true);
        r.put("volume", am.getStreamVolume(AudioManager.STREAM_MUSIC));
        r.put("volumeMax", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        r.put("tocando", am.isMusicActive());
        call.resolve(r);
    }
}
