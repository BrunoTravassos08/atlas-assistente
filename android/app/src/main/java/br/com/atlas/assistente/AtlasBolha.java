package br.com.atlas.assistente;

import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Liga/desliga o botão flutuante (BolhaService) e avisa o JS quando o Atlas foi aberto por ele. */
@CapacitorPlugin(name = "AtlasBolha")
public class AtlasBolha extends Plugin {
    static volatile AtlasBolha ativo;

    /** Repassa um pedido do painel rápido para o Atlas (JS). Falso se o app não está rodando. */
    static boolean avisar(String evento, String texto) {
        AtlasBolha p = ativo;
        if (p == null || p.getBridge() == null) return false;
        JSObject d = new JSObject();
        if (texto != null) d.put("texto", texto);
        p.notifyListeners("pedido".equals(evento) ? "pedido" : "parar", d);
        return true;
    }

    @PluginMethod
    public void responder(PluginCall call) {
        BolhaService.resposta(call.getString("texto", ""), Boolean.TRUE.equals(call.getBoolean("fim", false)));
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        if (ativo == this) ativo = null;
        super.handleOnDestroy();
    }

    private JSObject estado() {
        JSObject r = new JSObject();
        r.put("permissao", BolhaService.podeSobrepor(getContext()));
        r.put("ligada", BolhaService.ligada(getContext()));
        r.put("rodando", BolhaService.instancia != null);
        return r;
    }

    @Override
    public void load() {
        ativo = this;
        // aberto pela bolinha com o app fechado
        if (getActivity() != null) avisarSeOuvir(getActivity().getIntent());
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        avisarSeOuvir(intent);
    }

    private void avisarSeOuvir(Intent i) {
        if (i == null) return;
        String a = i.getAction();
        if (BolhaService.ACAO_OUVIR.equals(a)) {
            JSObject d = new JSObject();
            String pedido = i.getStringExtra(BolhaService.EXTRA_PEDIDO);
            if (pedido != null) d.put("pedido", pedido);
            i.setAction(Intent.ACTION_MAIN); // não repetir ao girar a tela
            i.removeExtra(BolhaService.EXTRA_PEDIDO);
            notifyListeners("ouvir", d, true);
        } else if (BolhaService.ACAO_BRIEFING.equals(a)) {
            i.setAction(Intent.ACTION_MAIN);
            notifyListeners("briefing", new JSObject(), true);
        }
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        BolhaService.appVisivel(true);
        if (BolhaService.ligada(getContext()) && BolhaService.instancia == null) BolhaService.iniciar(getContext());
    }

    @Override
    protected void handleOnPause() {
        BolhaService.appVisivel(false);
        super.handleOnPause();
    }

    @PluginMethod
    public void info(PluginCall call) { call.resolve(estado()); }

    @PluginMethod
    public void pedirPermissao(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch (Exception e) {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        }
        call.resolve(estado());
    }

    @PluginMethod
    public void ligar(PluginCall call) {
        if (!BolhaService.podeSobrepor(getContext())) { call.reject("permissão de sobrepor não liberada", "sem_permissao"); return; }
        BolhaService.salvar(getContext(), true);
        BolhaService.appVisivel(true);
        BolhaService.iniciar(getContext());
        call.resolve(estado());
    }

    @PluginMethod
    public void desligar(PluginCall call) {
        BolhaService.salvar(getContext(), false);
        BolhaService.parar(getContext());
        call.resolve(estado());
    }
}
