// ChatGPT pela assinatura (Plus/Pro) no Windows, do mesmo jeito do HUBIA:
// o Atlas não chama a API da OpenAI. Ele baixa o programa oficial Codex CLI (codex.exe),
// a pessoa entra com a conta do ChatGPT pelo navegador, e cada pergunta roda
// "codex exec --json" como processo filho, lendo a resposta linha a linha.
// O custo sai do plano do ChatGPT, não de crédito de API.
const path = require("node:path");
const fs = require("node:fs");
const os = require("node:os");
const { spawn, spawnSync } = require("node:child_process");

const WIN = process.platform === "win32";
let PASTA = "";          // <dados do Atlas>/codex
let baixar = (...a) => fetch(...a);
let instalando = null, loginProc = null, loginUrl = "";
const ativos = new Map();  // conversas em andamento, para poder cancelar

function iniciar({ dados, fetchFn }){
  PASTA = path.join(dados, "codex");
  if (fetchFn) baixar = fetchFn;
  for (const d of ["bin", "home", "trabalho"]) fs.mkdirSync(path.join(PASTA, d), { recursive: true });
}
const bin = (nome = "codex") => path.join(PASTA, "bin", nome + (WIN ? ".exe" : ""));

// alvo do download conforme o computador
function alvo(){
  const arq = process.arch === "arm64" ? "aarch64" : "x86_64";
  if (WIN) return { sufixo: `${arq}-pc-windows-msvc.exe`, ext: ".tar.gz" };
  if (process.platform === "darwin") return { sufixo: `${arq}-apple-darwin`, ext: ".tar.gz" };
  return { sufixo: `${arq}-unknown-linux-musl`, ext: ".tar.gz" };
}

// ambiente do processo filho: sem chave de API (senão o Codex cobra da API e não do plano),
// e com uma pasta própria do Atlas (login, configurações e MCPs do PC da pessoa ficam de fora)
function env(){
  const e = Object.assign({}, process.env);
  for (const k of ["OPENAI_API_KEY", "CODEX_API_KEY", "OPENAI_BASE_URL", "ELECTRON_RUN_AS_NODE", "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN"]) delete e[k];
  e.CODEX_HOME = path.join(PASTA, "home");
  e.NO_COLOR = "1";
  return e;
}

const instalado = () => fs.existsSync(bin());

function versao(){
  if (!instalado()) return "";
  const r = spawnSync(bin(), ["--version"], { env: env(), windowsHide: true, encoding: "utf8", timeout: 15000 });
  return String(r.stdout || "").trim().replace(/^codex-cli\s*/i, "");
}

// baixa codex.exe e codex-code-mode-host.exe da última versão no GitHub da OpenAI
async function instalar(progresso = () => {}){
  if (instalando) return instalando;
  instalando = (async () => {
    const { sufixo, ext } = alvo();
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "atlas-codex-"));
    try {
      const partes = [["codex", "codex"], ["codex-code-mode-host", "codex-code-mode-host"]];
      for (let i = 0; i < partes.length; i++){
        const [nome, destino] = partes[i];
        const url = `https://github.com/openai/codex/releases/latest/download/${nome}-${sufixo}${ext}`;
        progresso({ etapa: i === 0 ? "Baixando o Codex oficial da OpenAI" : "Baixando o complemento do Codex", pct: 0 });
        const r = await baixar(url);
        if (!r.ok){
          if (i > 0) break;   // o complemento só existe nas versões novas: sem ele o Codex conversa do mesmo jeito
          throw new Error(`não consegui baixar o Codex (HTTP ${r.status})`);
        }
        const total = +r.headers.get("content-length") || 0, arq = path.join(tmp, `${nome}${ext}`), out = fs.createWriteStream(arq);
        let lidos = 0, ultimo = -1;
        for await (const p of r.body){
          out.write(Buffer.from(p)); lidos += p.length;
          const pct = total ? Math.floor(lidos * 100 / total) : -1;
          if (pct !== ultimo){ ultimo = pct; progresso({ etapa: i === 0 ? "Baixando o Codex oficial da OpenAI" : "Baixando o complemento do Codex", pct, mb: Math.round(lidos / 1048576) }); }
        }
        await new Promise((res, rej) => out.end(e => e ? rej(e) : res()));
        const pasta = path.join(tmp, nome);
        fs.mkdirSync(pasta);
        const tar = WIN ? path.join(process.env.SystemRoot || "C:\\Windows", "System32", "tar.exe") : "tar";
        const t = spawnSync(tar, ["-xzf", arq, "-C", pasta], { windowsHide: true });
        if (t.status !== 0) throw new Error("não consegui descompactar o Codex");
        const achado = fs.readdirSync(pasta).find(f => f.startsWith(nome) && (!WIN || f.endsWith(".exe")));
        if (!achado) throw new Error("o pacote do Codex veio incompleto");
        const final = bin(destino);
        try { fs.rmSync(final, { force: true }); } catch {}
        fs.copyFileSync(path.join(pasta, achado), final);
        if (!WIN) fs.chmodSync(final, 0o755);
      }
      progresso({ etapa: "Codex instalado", pct: 100 });
      return { versao: versao() };
    } finally {
      fs.rmSync(tmp, { recursive: true, force: true });
      instalando = null;
    }
  })();
  return instalando;
}

// "codex login status": vale só o login pela conta do ChatGPT (login por chave de API cobraria da API)
function lerStatus(saida){
  const s = String(saida || "");
  if (/not logged in/i.test(s)) return { logado: false, porChave: false };
  const logado = /logged in/i.test(s);
  const porChave = /api key/i.test(s);
  return { logado: logado && !porChave, porChave: logado && porChave };
}
function status(){
  if (!instalado()) return { instalado: false, logado: false, entrando: !!loginProc };
  const r = spawnSync(bin(), ["login", "status"], { env: env(), windowsHide: true, encoding: "utf8", timeout: 20000 });
  const st = lerStatus(`${r.stdout || ""}\n${r.stderr || ""}`);
  return { instalado: true, versao: versao(), ...st, entrando: !!loginProc, url: loginProc ? loginUrl : "" };
}

// login oficial: o Codex abre o navegador na conta do ChatGPT e espera a volta num servidor local
function login({ abrir } = {}){
  if (!instalado()) throw new Error("o Codex ainda não está instalado");
  if (loginProc) return { url: loginUrl };
  loginUrl = "";
  const p = spawn(bin(), ["login"], { env: env(), windowsHide: true, cwd: path.join(PASTA, "trabalho") });
  loginProc = p;
  const ler = b => {
    const m = String(b).match(/https:\/\/auth\.openai\.com\/[^\s"'<>]+/);
    if (m && !loginUrl){ loginUrl = m[0]; }
  };
  p.stdout.on("data", ler); p.stderr.on("data", ler);
  const fim = () => { if (loginProc === p) loginProc = null; clearTimeout(limite); };
  p.on("exit", fim); p.on("error", fim);
  const limite = setTimeout(() => { try { p.kill(); } catch {} }, 10 * 60e3);
  // se o Codex não conseguir abrir o navegador sozinho, o Atlas abre o link
  if (abrir) setTimeout(() => { if (loginProc === p && loginUrl) abrir(loginUrl); }, 4000);
  return { iniciado: true };
}
function cancelarLogin(){ try { loginProc?.kill(); } catch {} loginProc = null; return {}; }

function sair(){
  if (!instalado()) return {};
  spawnSync(bin(), ["logout"], { env: env(), windowsHide: true, timeout: 20000 });
  return {};
}

// Uma linha do "codex exec --json" vira um evento simples para o Atlas.
function codexEvent(linha){
  let j;
  try { j = JSON.parse(String(linha).trim()); } catch { return null; }
  if (!j || typeof j !== "object") return null;
  const it = j.item || {};
  if (j.type === "item.completed" && it.type === "agent_message") return { tipo: "texto", texto: String(it.text || "") };
  if ((j.type === "item.completed" || j.type === "item.started") && it.type === "web_search") return { tipo: "busca", consulta: String(it.query || it.action?.query || ""), fim: j.type === "item.completed" };
  if (j.type === "item.completed" && it.type === "command_execution") return { tipo: "comando", comando: String(it.command || "") };
  if (j.type === "item.completed" && it.type === "error") return { tipo: "aviso", mensagem: String(it.message || "") };
  if (j.type === "turn.completed") return { tipo: "fim", uso: j.usage || null };
  if (j.type === "turn.failed") return { tipo: "erro", mensagem: String(j.error?.message || "o Codex falhou") };
  if (j.type === "error"){
    const m = String(j.message || j.error?.message || "erro do Codex");
    // "Reconnecting... 2/5" é só a rede oscilando: o Codex tenta de novo sozinho
    if (/^(reconnecting|falling back)/i.test(m)) return { tipo: "aviso", mensagem: m };
    return { tipo: "erro", mensagem: m };
  }
  if (j.type === "thread.started") return { tipo: "inicio", id: j.thread_id || "" };
  return null;
}

// classifica o erro para a página mostrar uma frase clara
function classificar(m){
  const s = String(m || "");
  if (/usage limit|rate limit|429|limit reached|too many requests/i.test(s)) return "limite";
  if (/not logged in|unauthorized|401|login|token.*(expired|invalid)|refresh/i.test(s)) return "auth";
  if (/proxy|network|dns|connect|timed? ?out|stream disconnected/i.test(s)) return "network";
  return "api";
}

// Conversa: o texto inteiro vai pelo stdin (a linha de comando do Windows aceita só ~32 mil caracteres)
function chat({ id, prompt, web = false, esforco = "low", modelo = "", ms = 240000 }, aoEvento = () => {}){
  return new Promise((resolve, reject) => {
    if (!instalado()) return reject(Object.assign(new Error("o Codex não está instalado"), { code: "auth" }));
    const args = ["exec", "--json", "--skip-git-repo-check", "--ephemeral", "--ignore-user-config",
      "--sandbox", "read-only", "-C", path.join(PASTA, "trabalho"),
      "-c", `model_reasoning_effort="${esforco}"`, "-c", `web_search="${web ? "live" : "disabled"}"`];
    if (modelo) args.push("-m", modelo);
    args.push("-");
    const p = spawn(bin(), args, { env: env(), windowsHide: true, cwd: path.join(PASTA, "trabalho") });
    ativos.set(id, p);
    let resto = "", texto = "", erro = "", buscas = 0, uso = null, stderr = "", acabou = false;
    const limite = setTimeout(() => { erro = erro || "tempo esgotado"; try { p.kill(); } catch {} }, ms);
    const linha = l => {
      const ev = codexEvent(l); if (!ev) return;
      if (ev.tipo === "texto") texto = ev.texto;
      else if (ev.tipo === "busca" && ev.fim) buscas++;
      else if (ev.tipo === "erro") erro = ev.mensagem;
      else if (ev.tipo === "fim") uso = ev.uso;
      aoEvento(ev);
    };
    p.stdout.on("data", b => {
      resto += b.toString("utf8");
      let i;
      while ((i = resto.indexOf("\n")) >= 0){ const l = resto.slice(0, i); resto = resto.slice(i + 1); if (l.trim()) linha(l); }
    });
    p.stderr.on("data", b => { stderr = (stderr + b.toString("utf8")).slice(-4000); });
    const terminar = cod => {
      if (acabou) return; acabou = true;
      clearTimeout(limite); ativos.delete(id);
      if (resto.trim()) linha(resto);
      if (p.cancelado) return reject(Object.assign(new Error("cancelado"), { code: "cancelled" }));
      if (texto && !erro) return resolve({ texto, buscas, uso });
      const m = erro || stderr.split("\n").filter(l => l.trim() && !/^\d{4}-\d\d-\d\dT/.test(l)).slice(-3).join(" ") || `o Codex saiu com código ${cod}`;
      if (texto) return resolve({ texto, buscas, uso, aviso: m });
      reject(Object.assign(new Error(m), { code: erro === "tempo esgotado" ? "timeout" : classificar(m) }));
    };
    p.on("exit", terminar);
    p.on("error", e => { erro = String(e?.message || e); terminar(-1); });
    p.stdin.on("error", () => {});
    p.stdin.end(String(prompt));
  });
}
function cancelar({ id }){
  const p = ativos.get(id);
  if (p){ p.cancelado = true; try { p.kill(); } catch {} ativos.delete(id); }
  return {};
}

module.exports = { iniciar, instalar, status, login, cancelarLogin, sair, chat, cancelar, codexEvent, lerStatus, classificar, env };
