// Ponte entre a página do Atlas (index.html) e o Windows.
// Expõe window.atlasDesktop com as mesmas funções que o APK recebe dos plugins nativos do Android
// (AtlasVoz, AtlasEscuta, AtlasSistema, LocalNotifications), para a página usar o mesmo código.
const { contextBridge, ipcRenderer } = require("electron");

const chamar = (canal, a) => ipcRenderer.invoke(canal, a).catch(e => { throw { message: String(e?.message || e).replace(/^Error invoking remote method '[^']+': (Error: )?/, "") }; });
const ouvintes = (mapa, ev, cb) => { (mapa[ev] = mapa[ev] || []).push(cb); return { remove(){ mapa[ev] = (mapa[ev] || []).filter(f => f !== cb); } }; };

/* ---------- voz neural (Piper): o motor gera frase a frase, aqui tocamos em sequência ---------- */
let ctxVoz = null, seq = 0, atual = null;
const vozOuv = {};
const audioCtx = () => { ctxVoz = ctxVoz || new AudioContext(); if (ctxVoz.state === "suspended") ctxVoz.resume().catch(() => {}); return ctxVoz; };
function talvezTerminar(){
  const a = atual;
  if (!a || !a.fimMotor || a.pend > 0) return;
  atual = null;
  if (a.erro && !a.tocou) a.reject({ message: a.erro }); else a.resolve({ interrompido: false });
}
ipcRenderer.on("voz-audio", (e, m) => {
  const a = atual; if (!a || m.id !== a.id) return;
  const c = audioCtx();
  const buf = c.createBuffer(1, m.amostras.length, m.taxa);
  buf.copyToChannel(m.amostras instanceof Float32Array ? m.amostras : Float32Array.from(m.amostras), 0);
  const s = c.createBufferSource(); s.buffer = buf; s.connect(c.destination);
  // um respiro antes da primeira frase e ~180 ms entre frases, como no APK
  const t0 = Math.max(c.currentTime + (a.tocou ? 0.02 : 0.08), a.prox);
  s.start(t0); a.prox = t0 + buf.duration + 0.18;
  a.fontes.push(s); a.tocou = true; a.pend++;
  s.onended = () => { if (atual !== a) return; a.pend--; talvezTerminar(); };
});
ipcRenderer.on("voz-fim", (e, m) => {
  const a = atual; if (!a || (m.id !== a.id && m.id !== -1)) return;
  a.fimMotor = true; a.erro = m.erro || null; talvezTerminar();
});
ipcRenderer.on("voz-progresso", (e, d) => (vozOuv.progresso || []).forEach(f => f(d)));
function pararVoz(){
  const a = atual; atual = null;
  if (a){ for (const s of a.fontes){ try { s.stop(); } catch {} } a.resolve({ interrompido: true }); }
  ipcRenderer.send("voz-parar");
}
const voz = {
  info: () => chamar("voz-info"),
  carregar: o => chamar("voz-carregar", o),
  baixar: o => chamar("voz-baixar", o),
  remover: o => chamar("voz-remover", o),
  falar({ texto = "", voz: v = "faber", velocidade = 1 } = {}){
    pararVoz();
    if (!String(texto).trim()) return Promise.resolve({ interrompido: false });
    audioCtx();
    return new Promise((resolve, reject) => {
      atual = { id: ++seq, fontes: [], prox: 0, pend: 0, tocou: false, fimMotor: false, erro: null, resolve, reject };
      ipcRenderer.send("voz-falar", { id: atual.id, texto: String(texto), voz: v, velocidade: +velocidade || 1 });
    });
  },
  parar(){ pararVoz(); return Promise.resolve(); },
  addListener: (ev, cb) => ouvintes(vozOuv, ev, cb)
};

/* ---------- palavra "Atlas" sem internet: o microfone vai, em 16 kHz, para o motor ---------- */
let mic = null;
const escOuv = {};
const emitirEsc = (ev, d) => (escOuv[ev] || []).forEach(f => f(d));
function pararMic(){
  const m = mic; mic = null;
  if (!m) return;
  try { m.proc.disconnect(); m.src.disconnect(); } catch {}
  m.stream.getTracks().forEach(t => t.stop());
  m.ctx.close().catch(() => {});
  ipcRenderer.send("kws-parar");
}
ipcRenderer.on("escuta-palavra", () => { if (!mic) return; pararMic(); emitirEsc("palavra", { palavra: "ATLAS", comando: "" }); });
const escuta = {
  async iniciar(){
    if (mic) return {};
    try { await chamar("kws-iniciar"); } catch (e){ throw { code: "sem_modelo", message: e?.message || "modelo da palavra não carregou" }; }
    let stream;
    try { stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 } }); }
    catch (e){ throw { code: e?.name === "NotAllowedError" ? "sem_permissao" : "sem_microfone", message: e?.message || String(e) }; }
    const ctx = new AudioContext(), src = ctx.createMediaStreamSource(stream), proc = ctx.createScriptProcessor(4096, 1, 1);
    const passo = ctx.sampleRate / 16000; let pos = 0;
    proc.onaudioprocess = ev => {
      if (!mic) return;
      const x = ev.inputBuffer.getChannelData(0), out = [];
      // reduz para 16 kHz tirando a média de cada janela (filtro simples contra chiado)
      while (pos < x.length){
        const a = Math.max(0, Math.floor(pos - passo / 2)), b = Math.min(x.length, Math.ceil(pos + passo / 2));
        let s = 0; for (let i = a; i < b; i++) s += x[i];
        out.push(b > a ? s / (b - a) : 0); pos += passo;
      }
      pos -= x.length;
      ipcRenderer.send("kws-audio", Float32Array.from(out));
    };
    src.connect(proc); proc.connect(ctx.destination);
    mic = { stream, ctx, src, proc };
    stream.getAudioTracks()[0]?.addEventListener("ended", () => { if (mic){ pararMic(); emitirEsc("parou", { erro: "microfone desligado" }); } });
    return {};
  },
  parar(){ pararMic(); return Promise.resolve(); },
  addListener: (ev, cb) => ouvintes(escOuv, ev, cb)
};

/* ---------- sistema: links, música ---------- */
const sistema = {
  info: () => chamar("sis-info"),
  abrirLink: ({ url } = {}) => chamar("sis-abrir", { url }),
  appsMusica: () => chamar("sis-apps-musica"),
  tocarMusica: o => chamar("sis-tocar", o),
  controleMidia: o => chamar("sis-midia", o),
  abrirLoja: () => Promise.resolve()
};

/* ---------- lembrete do briefing (mesmo formato do LocalNotifications) ---------- */
const notificacoes = {
  checkPermissions: () => Promise.resolve({ display: "granted" }),
  requestPermissions: () => Promise.resolve({ display: "granted" }),
  schedule: o => chamar("lembretes-agendar", o),
  cancel: o => chamar("lembretes-cancelar", o)
};

/* ---------- eventos do Windows: atalho global, bandeja, notificação, atualização ---------- */
const evOuv = {};
ipcRenderer.on("desk-evento", (e, d) => (evOuv[d?.ev] || []).forEach(f => f(d)));
ipcRenderer.on("desk-log", (e, msg) => (evOuv.log || []).forEach(f => f(msg)));

contextBridge.exposeInMainWorld("atlasDesktop", {
  plataforma: process.platform,
  voz, escuta, sistema, notificacoes,
  config: {
    ler: () => chamar("desk-config"),
    gravar: o => chamar("desk-config-gravar", o),
    atualizarAgora: () => chamar("desk-atualizar"),
    telaCheia: () => chamar("desk-tela-cheia")
  },
  on: (ev, cb) => ouvintes(evOuv, ev, cb)
});
