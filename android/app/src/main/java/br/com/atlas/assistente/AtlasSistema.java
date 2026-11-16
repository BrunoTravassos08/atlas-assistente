package br.com.atlas.assistente;

import android.content.ActivityNotFoundException;
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
        r.put("android", Build.VERSION.SDK_INT);
        call.resolve(r);
    }
}
