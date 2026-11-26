// Atlas para Windows: a mesma interface do APK (app/index.html) numa janela do Electron,
// com o que no Android vinha de plugins nativos refeito para o computador:
// voz neural offline, palavra "Atlas", música, lembretes, atalho global, bandeja e atualização automática.
const { app, BrowserWindow, Tray, Menu, globalShortcut, ipcMain, shell, session, Notification, nativeImage, utilityProcess, net, screen, systemPreferences, desktopCapturer } = require("electron");
const path = require("node:path");
const fs = require("node:fs");
const os = require("node:os");
const { spawn } = require("node:child_process");
const codex = require("./codex");   // ChatGPT pela assinatura (Codex CLI oficial), jeito do HUBIA

const NOME = "Atlas";
const EM_BANDEJA = process.argv.includes("--bandeja");
const ICONE = path.join(__dirname, "icone.png");
const RECURSOS = app.isPackaged ? path.join(process.resourcesPath, "recursos") : path.join(__dirname, "recursos");

app.setAppUserModelId("br.com.atlas.assistente");   // notificações do Windows com o nome e o ícone do Atlas
if (!app.requestSingleInstanceLock()){ app.quit(); return; }

/* ---------- ajustes do app desktop (fora do localStorage, porque o processo principal também usa) ---------- */
const ARQ_CFG = path.join(app.getPath("userData"), "desktop.json");
const PADRAO = { abrirComWindows: true, fecharParaBandeja: true, atalho: "Control+Alt+Space", lembretes: [], avisouBandeja: false };
let cfg = PADRAO;
try { cfg = Object.assign({}, PADRAO, JSON.parse(fs.readFileSync(ARQ_CFG, "utf8"))); } catch {}
let salvarT = null;
const salvar = () => { clearTimeout(salvarT); salvarT = setTimeout(() => { try { fs.writeFileSync(ARQ_CFG, JSON.stringify(cfg, null, 1)); } catch {} }, 300); };
function aplicarInicioAutomatico(){
  if (!app.isPackaged || process.platform !== "win32") return;
  app.setLoginItemSettings({ openAtLogin: !!cfg.abrirComWindows, args: ["--bandeja"] });
}

/* ---------- janela ---------- */
let win = null, tray = null, sair = false;
function limitesSalvos(){
  const b = cfg.janela;
  if (!b) return { width: 1280, height: 800 };
  const visivel = screen.getAllDisplays().some(d => b.x < d.workArea.x + d.workArea.width - 80 && b.x + b.width > d.workArea.x + 80 && b.y >= d.workArea.y - 10 && b.y < d.workArea.y + d.workArea.height - 80);
  return visivel ? { x: b.x, y: b.y, width: b.width, height: b.height } : { width: b.width, height: b.height };
}
function criarJanela(){
  win = new BrowserWindow({
    ...limitesSalvos(),
    minWidth: 900, minHeight: 600,
    show: false,
    title: NOME,
    icon: ICONE,
    backgroundColor: "#040914",
    // sem a barra branca do Windows: o cabeçalho do Atlas vira a barra, e os botões ficam por cima
    titleBarStyle: "hidden",
    titleBarOverlay: { color: "#040914", symbolColor: "#8fb0cf", height: 44 },
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      spellcheck: false,
      backgroundThrottling: false,              // o briefing e a palavra "Atlas" continuam com a janela atrás de outras
      autoplayPolicy: "no-user-gesture-required"
    }
  });
  win.setMenuBarVisibility(false);
  win.loadFile(path.join(__dirname, "app", "index.html"));
  win.once("ready-to-show", () => { if (cfg.maximizada) win.maximize(); if (!EM_BANDEJA) win.show(); });
  const lembrar = () => { if (!win || win.isMinimized() || win.isFullScreen()) return; cfg.maximizada = win.isMaximized(); if (!cfg.maximizada) cfg.janela = win.getBounds(); salvar(); };
  win.on("resize", lembrar); win.on("move", lembrar);
  win.on("close", e => {
    if (sair || !cfg.fecharParaBandeja) return;
    e.preventDefault(); win.hide();
    if (!cfg.avisouBandeja){
      cfg.avisouBandeja = true; salvar();
      notificar(`${NOME} continua aqui`, `Ele fica perto do relógio, na bandeja. Aperte ${nomeAtalho()} para falar com ele a qualquer hora.`);
    }
  });
  // links abrem no navegador do computador, nunca dentro do Atlas
  win.webContents.setWindowOpenHandler(({ url }) => { if (/^https?:\/\//i.test(url)) shell.openExternal(url); return { action: "deny" }; });
  win.webContents.on("will-navigate", (e, url) => { if (!url.startsWith("file:")){ e.preventDefault(); if (/^https?:\/\//i.test(url)) shell.openExternal(url); } });
  win.webContents.on("before-input-event", (e, i) => {
    if (i.type !== "keyDown") return;
    if (i.key === "F11"){ win.setFullScreen(!win.isFullScreen()); e.preventDefault(); }
    if (i.key === "F12" && !app.isPackaged) win.webContents.toggleDevTools();
  });
}
function mostrar(){
  if (!win) return;
  if (win.isMinimized()) win.restore();
  win.show(); win.focus();
}
const enviar = (canal, dados) => { try { win?.webContents.send(canal, dados); } catch {} };

/* ---------- internet: o Atlas busca notícias, clima e Instagram direto dos sites (como o app Android) ----------
   O navegador bloquearia por CORS; aqui o Atlas é um app, então liberamos as respostas para a página local. */
// identidade de navegador comum (sem "Electron"): alguns sites do Google recusam o app com 403
const UA_CHROME = () => `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${process.versions.chrome} Safari/537.36`;
// pedidos de texto feitos pelo processo principal, como o HTTP nativo do APK: sem Origin, sem CORS
async function httpNativo({ url, ms = 15000, headers = {} }){
  if (!/^https:\/\//i.test(String(url))) throw new Error("link inválido");
  const ctl = new AbortController(), t = setTimeout(() => ctl.abort(), ms);
  try {
    const r = await net.fetch(String(url), { signal: ctl.signal, headers: Object.assign({ "User-Agent": UA_CHROME(), "Accept-Language": "pt-BR,pt;q=0.9", "Accept": "*/*" }, headers) });
    return { status: r.status, data: await r.text() };
  } finally { clearTimeout(t); }
}
function liberarCors(){
  const ses = session.defaultSession;
  ses.setUserAgent(UA_CHROME(), "pt-BR,pt");
  // a página é local (file://): o Origin "null" faz o Google Notícias responder 403, então não mandamos
  ses.webRequest.onBeforeSendHeaders((det, cb) => {
    const h = Object.assign({}, det.requestHeaders);
    for (const k of Object.keys(h)) if (/^(origin|sec-fetch-site|sec-fetch-mode|sec-fetch-dest)$/i.test(k)) delete h[k];
    cb({ requestHeaders: h });
  });
  ses.webRequest.onHeadersReceived((det, cb) => {
    if (!/^https?:/i.test(det.url)) return cb({});
    const h = {};
    for (const [k, v] of Object.entries(det.responseHeaders || {})) if (!/^access-control-allow-(origin|headers|methods|credentials)$/i.test(k)) h[k] = v;
    h["Access-Control-Allow-Origin"] = ["*"];
    h["Access-Control-Allow-Headers"] = ["*"];
    h["Access-Control-Allow-Methods"] = ["GET, POST, PUT, PATCH, DELETE, OPTIONS"];
    const r = { responseHeaders: h };
    if (det.method === "OPTIONS") r.statusLine = "HTTP/1.1 204 No Content";
    cb(r);
  });
  // microfone e notificações liberados para o próprio Atlas; o resto, negado
  const OK = new Set(["media", "audioCapture", "notifications", "clipboard-sanitized-write", "speaker-selection", "display-capture"]);
  ses.setPermissionRequestHandler((wc, perm, cb) => cb(OK.has(perm)));
  ses.setPermissionCheckHandler((wc, perm) => OK.has(perm));
  // visualizador da música: o som que sai do computador (loopback), sem microfone e sem mostrar a tela
  ses.setDisplayMediaRequestHandler((req, cb) => {
    desktopCapturer.getSources({ types: ["screen"] }).then(f => cb(f[0] ? { video: f[0], audio: "loopback" } : {})).catch(() => cb({}));
  });
}

/* ---------- bandeja e atalho global (o "botão flutuante" do computador) ---------- */
const nomeAtalho = () => String(cfg.atalho || "").replace("Control", "Ctrl").replace("Space", "Espaço").replace(/\+/g, " + ");
function montarBandeja(){
  if (!tray){
    tray = new Tray(nativeImage.createFromPath(ICONE).resize({ width: 16, height: 16 }));
    tray.setToolTip(NOME);
    tray.on("click", mostrar);
  }
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: `Abrir o ${NOME}`, click: mostrar },
    { label: `Falar com o ${NOME}   (${nomeAtalho()})`, click: () => { mostrar(); enviar("desk-evento", { ev: "ouvir" }); } },
    { label: "Ouvir o briefing agora", click: () => { mostrar(); enviar("desk-evento", { ev: "briefing" }); } },
    { type: "separator" },
    { label: "Abrir junto com o Windows", type: "checkbox", checked: !!cfg.abrirComWindows, click: i => { cfg.abrirComWindows = i.checked; salvar(); aplicarInicioAutomatico(); enviar("desk-evento", { ev: "config" }); } },
    { type: "separator" },
    { label: `Sair do ${NOME}`, click: () => { sair = true; app.quit(); } }
  ]));
}
function registrarAtalho(){
  globalShortcut.unregisterAll();
  if (!cfg.atalho) return true;
  try {
    return globalShortcut.register(cfg.atalho, () => {
      const naFrente = win?.isVisible() && win?.isFocused();
      mostrar();
      enviar("desk-evento", { ev: naFrente ? "microfone" : "ouvir" });
    });
  } catch { return false; }
}

/* ---------- notificações e lembrete do briefing ---------- */
function notificar(titulo, corpo, aoClicar){
  if (!Notification.isSupported()) return;
  const n = new Notification({ title: titulo, body: corpo, icon: ICONE, silent: false });
  n.on("click", () => { mostrar(); aoClicar?.(); });
  n.show();
}
// mesmo formato do LocalNotifications do Capacitor: weekday 1 = domingo … 7 = sábado
let ultimoDisparo = "";
function conferirLembretes(){
  const d = new Date(), chave = `${d.toDateString()} ${d.getHours()}:${d.getMinutes()}`;
  if (chave === ultimoDisparo) return;
  for (const l of cfg.lembretes || []){
    const on = l.schedule?.on || {};
    if (on.hour !== d.getHours() || on.minute !== d.getMinutes()) continue;
    if (on.weekday && on.weekday !== d.getDay() + 1) continue;
    ultimoDisparo = chave;
    notificar(l.title || NOME, l.body || "", () => enviar("desk-evento", { ev: "briefing" }));
    break;
  }
}

/* ---------- música: abre no app ou no site e controla pelas teclas de mídia do Windows ---------- */
const temSpotify = () => { try { return !!app.getApplicationNameForProtocol("spotify://"); } catch { return false; } };
function appsMusica(){
  const l = [];
  if (temSpotify()) l.push({ id: "spotify", nome: "Spotify" });
  l.push({ id: "youtube", nome: "YouTube (toca direto)" }, { id: "ytmusic", nome: "YouTube Music (toca direto)" }, { id: "deezer", nome: "Deezer (site)" });
  return l;
}
async function primeiroVideo(busca){
  try {
    const r = await net.fetch(`https://www.youtube.com/results?search_query=${encodeURIComponent(busca)}&hl=pt-BR`, { headers: { "Accept-Language": "pt-BR,pt;q=0.9" } });
    const m = (await r.text()).match(/"videoId":"([\w-]{11})"/);
    return m ? m[1] : null;
  } catch { return null; }
}
async function tocarMusica({ busca, app: qual }){
  const q = String(busca || "").slice(0, 120);
  if (qual === "spotify" && temSpotify()){
    await shell.openExternal(`spotify:search:${encodeURIComponent(q)}`);
    return { app: "spotify", nome: "Spotify", metodo: "busca" };
  }
  if (qual === "deezer"){
    await shell.openExternal(`https://www.deezer.com/br/search/${encodeURIComponent(q)}`);
    return { app: "deezer", nome: "Deezer", metodo: "busca" };
  }
  const music = qual === "ytmusic";
  const id = await primeiroVideo(q);
  if (id){
    await shell.openExternal(music ? `https://music.youtube.com/watch?v=${id}` : `https://www.youtube.com/watch?v=${id}`);
    return { app: music ? "ytmusic" : "youtube", nome: music ? "YouTube Music" : "YouTube", metodo: "tocar" };
  }
  await shell.openExternal(music ? `https://music.youtube.com/search?q=${encodeURIComponent(q)}` : `https://www.youtube.com/results?search_query=${encodeURIComponent(q)}`);
  return { app: music ? "ytmusic" : "youtube", nome: music ? "YouTube Music" : "YouTube", metodo: "busca" };
}
/* ---------- Spotify: login com volta em http://127.0.0.1:8898/spotify e pedidos à Web API ---------- */
let spotifyServ = null;
function spotifyLogin({ url }){
  if (!/^https:\/\/accounts\.spotify\.com\//.test(String(url))) throw new Error("link inválido");
  try { spotifyServ?.close(); } catch {}
  return new Promise((res, rej) => {
    const http = require("node:http");
    let fim = null;
    const acabar = (erro, volta) => { clearTimeout(fim); try { spotifyServ?.close(); } catch {} spotifyServ = null; erro ? rej(new Error(erro)) : res({ url: volta }); };
    spotifyServ = http.createServer((req, resp) => {
      if (!req.url.startsWith("/spotify")){ resp.writeHead(404); resp.end(); return; }
      const ok = !/[?&]error=/.test(req.url);
      resp.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
      resp.end(`<!doctype html><meta charset="utf-8"><title>Atlas</title><body style="background:#040914;color:#e8f6ff;font:18px system-ui;display:grid;place-items:center;height:100vh;margin:0"><div style="text-align:center"><h2 style="color:${ok ? "#1ed760" : "#ff6b6b"}">${ok ? "Spotify conectado ao Atlas" : "O Spotify não foi conectado"}</h2><p>Pode fechar esta aba e voltar ao Atlas.</p></div>`);
      win?.show(); win?.focus();
      acabar(null, "http://127.0.0.1:8898" + req.url);
    });
    spotifyServ.on("error", e => acabar(e.code === "EADDRINUSE" ? "A porta 8898 está ocupada por outro programa." : e.message));
    spotifyServ.listen(8898, "127.0.0.1", () => { shell.openExternal(String(url)); });
    fim = setTimeout(() => acabar("O login do Spotify não terminou."), 5 * 60 * 1000);
  });
}
async function httpReq({ method = "GET", url, headers = {}, body }){
  if (!/^https:\/\/(api|accounts)\.spotify\.com\//.test(String(url))) throw new Error("endereço não permitido");
  const r = await net.fetch(String(url), { method, headers, body: body == null ? undefined : String(body) });
  return { status: r.status, data: await r.text() };
}
async function abrirSpotify({ uri }){
  const u = /^spotify:[\w:]+$/.test(String(uri)) ? String(uri) : "spotify:";
  // sem o app instalado, abre o Web Player no navegador (ele também vira um aparelho do Spotify)
  if (temSpotify()) await shell.openExternal(u);
  else await shell.openExternal("https://open.spotify.com/" + u.split(":").slice(1).join("/"));
  return {};
}
// teclas de mídia (as mesmas do teclado): funcionam com Spotify, YouTube no navegador, Deezer etc.
const TECLAS = { alternar: [0xB3], pausar: [0xB3], continuar: [0xB3], proxima: [0xB0], anterior: [0xB1], aumentar_volume: Array(5).fill(0xAF), diminuir_volume: Array(5).fill(0xAE) };
function controleMidia({ acao }){
  return new Promise((res, rej) => {
    const vks = TECLAS[acao];
    if (!vks) return rej(new Error("ação desconhecida"));
    if (process.platform !== "win32") return rej(new Error("só no Windows"));
    const ps = `Add-Type -Namespace A -Name K -MemberDefinition '[DllImport("user32.dll")] public static extern void keybd_event(byte b, byte s, uint f, System.UIntPtr e);';` +
      vks.map(v => `[A.K]::keybd_event(${v},0,1,[System.UIntPtr]::Zero);[A.K]::keybd_event(${v},0,3,[System.UIntPtr]::Zero);`).join("");
    const p = spawn("powershell.exe", ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", ps], { windowsHide: true });
    p.on("error", rej);
    p.on("exit", c => c === 0 ? res({}) : rej(new Error("teclas de mídia falharam")));
  });
}

/* ---------- motor de voz (processo separado) ---------- */
let motor = null, motorSeq = 0;
const pendentes = new Map();
const VOZES = [
  ["faber", "Faber", "pt_BR-faber-medium", "CC0"],
  ["cadu", "Cadu", "pt_BR-cadu-medium", "CC0"],
  ["jeff", "Jeff", "pt_BR-jeff-medium", "CC0"],
  ["dii", "Dii", "pt_BR-dii-high", "CC BY-NC-SA 4.0 (uso não comercial)"],
  ["miro", "Miro", "pt_BR-miro-high", "CC BY-NC-SA 4.0 (uso não comercial)"]
];
const PASTA_VOZES = path.join(app.getPath("userData"), "vozes");
const vozInstalada = id => { try { return id === "faber" || fs.statSync(path.join(PASTA_VOZES, id, "model.onnx")).size > 1e6; } catch { return false; } };
let baixando = false, erroMotor = null;
function iniciarMotor(){
  try {
    motor = utilityProcess.fork(path.join(__dirname, "motor.js"), [], { serviceName: "Atlas voz", stdio: "ignore" });
  } catch (e){ erroMotor = String(e?.message || e); return; }
  motor.postMessage({ t: "init", recursos: RECURSOS, vozes: PASTA_VOZES, threads: Math.max(1, Math.min(4, Math.floor(os.cpus().length / 2))) });
  motor.on("message", m => {
    if (m.t === "audio") enviar("voz-audio", m);
    else if (m.t === "fim") enviar("voz-fim", m);
    else if (m.t === "palavra") enviar("escuta-palavra", m);
    else if (m.t === "log") enviar("desk-log", m.msg);
    if (m.id != null && pendentes.has(m.id) && ["info", "carregada", "texto"].includes(m.t)){ pendentes.get(m.id)(m); pendentes.delete(m.id); }
    if (m.t === "kws-ok" || m.t === "kws-erro"){ const f = pendentes.get("kws"); pendentes.delete("kws"); f?.(m); }
  });
  motor.on("exit", c => { motor = null; erroMotor = `o motor de voz parou (código ${c})`; enviar("desk-log", erroMotor); enviar("voz-fim", { id: -1, erro: erroMotor }); });
}
const perguntarMotor = (msg, chave) => new Promise(res => {
  if (!motor) return res({ erro: erroMotor || "motor de voz desligado" });
  const id = chave || ++motorSeq;
  pendentes.set(id, res); motor.postMessage(Object.assign({ id }, msg));
  setTimeout(() => { if (pendentes.has(id)){ pendentes.delete(id); res({ erro: "o motor de voz não respondeu" }); } }, 30000);
});
// Baixa a voz do release tts-models do sherpa-onnx e instala em userData/vozes/<id>.
// Avisa a página a cada passo (voz-progresso) e, se algo falhar, manda o motivo em português.
const avisarVoz = d => enviar("voz-progresso", Object.assign({ ativo: true }, d));
function extrairPacote(arq, voz, destino){
  return new Promise((res, rej) => {
    let p;
    try { p = utilityProcess.fork(path.join(__dirname, "extrair.js"), [], { serviceName: "Atlas instalar voz", stdio: "ignore" }); }
    catch (e){ return rej(new Error("não consegui abrir o instalador da voz (" + (e?.message || e) + ")")); }
    let fim = false;
    const acabar = (erro) => { if (fim) return; fim = true; clearTimeout(t); try { p.kill(); } catch {} erro ? rej(new Error(erro)) : res(); };
    const t = setTimeout(() => acabar("a instalação da voz demorou demais"), 5 * 60e3);
    p.on("message", m => acabar(m?.erro || null));
    p.on("exit", c => acabar(`o instalador da voz parou (código ${c})`));
    p.postMessage({ arq, voz, destino });
  });
}
async function baixarVoz(id){
  const v = VOZES.find(x => x[0] === id);
  if (!v || id === "faber") throw new Error("voz desconhecida");
  if (baixando) throw new Error("já existe um download em andamento");
  baixando = true;
  let tmp = null, erro = null;
  const ctl = new AbortController();
  let parado = null;
  const vigiar = () => { clearTimeout(parado); parado = setTimeout(() => ctl.abort(), 45e3); };   // 45 s sem chegar nada = travou
  try {
    avisarVoz({ id, fase: "baixando", pct: 0, mb: 0 });
    fs.mkdirSync(PASTA_VOZES, { recursive: true });
    tmp = fs.mkdtempSync(path.join(os.tmpdir(), "atlas-voz-"));
    vigiar();
    let r;
    try { r = await net.fetch(`https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-${v[2]}.tar.bz2`, { signal: ctl.signal, cache: "no-store" }); }
    catch (e){ throw new Error(ctl.signal.aborted ? "o GitHub não respondeu. Confira a internet e tente de novo" : `sem conexão com o GitHub (${e?.message || e})`); }
    if (!r.ok) throw new Error(`o GitHub recusou o download (HTTP ${r.status})`);
    const total = +r.headers.get("content-length") || 0, arq = path.join(tmp, "voz.tar.bz2"), out = fs.createWriteStream(arq);
    const totalMb = total ? Math.round(total / 1048576) : 0;
    let lidos = 0, ultimo = -1;
    try {
      for await (const parte of r.body){
        vigiar();
        if (!out.write(Buffer.from(parte))) await new Promise(res => out.once("drain", res));
        lidos += parte.length;
        const pct = total ? Math.min(99, Math.floor(lidos * 100 / total)) : -1, mb = Math.round(lidos / 1048576);
        if (pct !== ultimo || (pct < 0 && mb !== ultimo)){ ultimo = pct < 0 ? mb : pct; avisarVoz({ id, fase: "baixando", pct, mb, totalMb }); }
      }
    } catch (e){ throw new Error(ctl.signal.aborted ? "o download travou (a internet caiu?). Tente de novo" : `o download parou no meio (${e?.message || e})`); }
    finally { clearTimeout(parado); }
    await new Promise((res, rej) => out.end(e => e ? rej(e) : res()));
    if (total && lidos < total) throw new Error("o download parou no meio. Tente de novo");
    avisarVoz({ id, fase: "instalando", pct: 100, mb: Math.round(lidos / 1048576), totalMb });
    motor?.postMessage({ t: "descarregar", voz: id });
    await extrairPacote(arq, v[2], path.join(PASTA_VOZES, id));
    if (!vozInstalada(id)) throw new Error("a voz não ficou instalada");
    return { id };
  } catch (e){
    erro = String(e?.message || e);
    if (/EACCES|EPERM|ENOSPC/.test(erro)) erro = /ENOSPC/.test(erro) ? "falta espaço no disco para a voz (precisa de uns 150 MB livres)" : "o Windows não deixou gravar a voz na pasta do Atlas";
    enviar("desk-log", `voz neural: falhou ao baixar ${id}: ${erro}`);
    throw new Error(erro);
  } finally {
    clearTimeout(parado);
    baixando = false;
    if (tmp) try { fs.rmSync(tmp, { recursive: true, force: true }); } catch {}
    enviar("voz-progresso", { id, ativo: false, erro });
  }
}

/* ---------- ponte com a página ---------- */
function ponte(){
  const h = (canal, fn) => ipcMain.handle(canal, async (e, a) => fn(a || {}));
  // sistema
  h("sis-info", () => ({ plataforma: process.platform, versao: app.getVersion() }));
  h("http-get", httpNativo);
  h("sis-abrir", ({ url }) => { if (!/^(https?:|mailto:|tel:)/i.test(String(url))) throw new Error("link inválido"); return shell.openExternal(String(url)); });
  h("sis-apps-musica", () => ({ apps: appsMusica() }));
  h("sis-tocar", tocarMusica);
  h("sis-midia", controleMidia);
  h("sis-spotify-login", spotifyLogin);
  h("sis-spotify-abrir", abrirSpotify);
  h("sis-voltar", () => { setTimeout(() => { win?.show(); win?.focus(); }, 400); return {}; });
  h("http-req", httpReq);
  // voz
  h("voz-info", async () => {
    const m = await perguntarMotor({ t: "info" });
    return { vozes: VOZES.map(([id, nome, , licenca]) => ({ id, nome, licenca, instalada: vozInstalada(id), embutida: id === "faber" })), pronta: !!m.pronta, voz: m.voz || null, erro: m.erro || erroMotor || null, baixando };
  });
  h("voz-carregar", async ({ voz }) => { const r = await perguntarMotor({ t: "carregar", voz }); if (r.erro) throw new Error(r.erro); return { voz: r.voz }; });
  h("voz-baixar", ({ id }) => baixarVoz(id));
  h("voz-remover", ({ id }) => { if (!id || id === "faber") throw new Error("a voz embutida não pode ser removida"); motor?.postMessage({ t: "descarregar", voz: id }); fs.rmSync(path.join(PASTA_VOZES, id), { recursive: true, force: true }); return {}; });
  ipcMain.on("voz-falar", (e, m) => { if (!motor) return enviar("voz-fim", { id: m.id, erro: erroMotor || "motor de voz desligado" }); motor.postMessage(Object.assign({ t: "falar" }, m)); });
  ipcMain.on("voz-parar", () => motor?.postMessage({ t: "parar" }));
  // palavra "Atlas"
  h("kws-iniciar", async () => { const r = await perguntarMotor({ t: "kws-iniciar" }, "kws"); if (r.erro) throw new Error(r.erro); return {}; });
  ipcMain.on("kws-audio", (e, amostras) => motor?.postMessage({ t: "kws-audio", amostras }));
  ipcMain.on("kws-parar", () => motor?.postMessage({ t: "kws-parar" }));
  // fala → texto sem internet (Whisper tiny), para quem usa o ChatGPT pela assinatura e não tem chave
  h("stt-local", async ({ amostras }) => { const r = await perguntarMotor({ t: "transcrever", amostras }); if (r.erro) throw new Error(r.erro); return { texto: r.texto || "" }; });
  // lembretes
  h("lembretes-agendar", ({ notifications }) => { const ids = new Set((notifications || []).map(n => n.id)); cfg.lembretes = (cfg.lembretes || []).filter(l => !ids.has(l.id)).concat(notifications || []); salvar(); return {}; });
  h("lembretes-cancelar", ({ notifications }) => { const ids = new Set((notifications || []).map(n => n.id)); cfg.lembretes = (cfg.lembretes || []).filter(l => !ids.has(l.id)); salvar(); return {}; });
  // ajustes do desktop
  // acesso ao microfone nas Configurações do Windows (Privacidade › Microfone)
  h("desk-mic", () => ({ acesso: ["win32", "darwin"].includes(process.platform) ? systemPreferences.getMediaAccessStatus("microphone") : "granted" }));
  h("desk-mic-ajustes", () => process.platform === "win32" ? shell.openExternal("ms-settings:privacy-microphone") : null);
  h("desk-config", () => ({ abrirComWindows: !!cfg.abrirComWindows, fecharParaBandeja: !!cfg.fecharParaBandeja, atalho: cfg.atalho, atalhoNome: nomeAtalho(), atalhoOk: !cfg.atalho || globalShortcut.isRegistered(cfg.atalho), versao: app.getVersion(), atualizacao }));
  h("desk-config-gravar", a => {
    if ("abrirComWindows" in a){ cfg.abrirComWindows = !!a.abrirComWindows; aplicarInicioAutomatico(); }
    if ("fecharParaBandeja" in a) cfg.fecharParaBandeja = !!a.fecharParaBandeja;
    let atalhoOk = true;
    if ("atalho" in a){ const antes = cfg.atalho; cfg.atalho = String(a.atalho || ""); atalhoOk = registrarAtalho(); if (!atalhoOk){ cfg.atalho = antes; registrarAtalho(); } }
    salvar(); montarBandeja();
    return { atalhoOk };
  });
  h("desk-atualizar", () => { if (atualizacao?.pronta){ sair = true; require("electron-updater").autoUpdater.quitAndInstall(true, true); } return {}; });
  // ChatGPT pela assinatura: instalar o Codex, entrar com a conta, conversar
  h("codex-status", () => codex.status());
  h("codex-instalar", () => codex.instalar(p => enviar("desk-evento", { ev: "codex", ...p })));
  h("codex-login", () => codex.login());
  h("codex-login-cancelar", () => codex.cancelarLogin());
  h("codex-abrir-login", ({ url }) => { if (!/^https:\/\/auth\.openai\.com\//.test(String(url))) throw new Error("link inválido"); return shell.openExternal(String(url)); });
  h("codex-sair", () => codex.sair());
  h("codex-chat", a => codex.chat(a, ev => enviar("codex-evento", { id: a.id, ...ev })));
  h("codex-cancelar", a => codex.cancelar(a));
  h("desk-tela-cheia", () => { win?.setFullScreen(!win.isFullScreen()); return { cheia: win?.isFullScreen() }; });
}

/* ---------- atualização automática pelo GitHub Releases ---------- */
let atualizacao = null;
function verificarAtualizacao(){
  if (!app.isPackaged) return;
  let au;
  try { au = require("electron-updater").autoUpdater; } catch { return; }
  au.autoDownload = true; au.autoInstallOnAppQuit = true;
  au.on("update-available", i => { atualizacao = { versao: i.version, pronta: false }; enviar("desk-evento", { ev: "atualizacao", ...atualizacao }); });
  au.on("update-downloaded", i => {
    atualizacao = { versao: i.version, pronta: true };
    enviar("desk-evento", { ev: "atualizacao", ...atualizacao });
    notificar(`${NOME} ${i.version} pronto`, "A nova versão entra quando você fechar o Atlas. Clique aqui para atualizar agora.", () => { sair = true; au.quitAndInstall(true, true); });
  });
  au.on("error", e => enviar("desk-log", "atualização: " + (e?.message || e)));
  const checar = () => au.checkForUpdates().catch(() => {});
  setTimeout(checar, 15000); setInterval(checar, 6 * 3600e3);
}

app.on("second-instance", mostrar);
app.whenReady().then(() => {
  liberarCors();
  codex.iniciar({ dados: app.getPath("userData"), fetchFn: (...a) => net.fetch(...a) });
  ponte();
  iniciarMotor();
  criarJanela();
  montarBandeja();
  registrarAtalho();
  aplicarInicioAutomatico();
  setInterval(conferirLembretes, 15000);
  verificarAtualizacao();
});
app.on("before-quit", () => { sair = true; });
app.on("will-quit", () => { globalShortcut.unregisterAll(); try { motor?.kill(); } catch {} });
app.on("window-all-closed", () => { if (sair || !cfg.fecharParaBandeja) app.quit(); });
