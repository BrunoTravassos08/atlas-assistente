// Motor de voz do Atlas no Windows (processo separado, para não travar a janela):
// - voz neural offline (Piper, pelo sherpa-onnx), a mesma do APK;
// - palavra de ativação "Atlas" sem internet (palavra-chave + detector de fala + Whisper tiny).
// Conversa com o processo principal por mensagens (process.parentPort).
const path = require("node:path");
const fs = require("node:fs");
const sherpa = require("sherpa-onnx-node");

const porta = process.parentPort;
let REC = "", VOZES = "", THREADS = 2;
let tts = null, vozAtual = null, erroCarga = null;
let geracao = 0;
let kws = null, kwsStream = null;

const enviar = m => porta.postMessage(m);

function pastaDaVoz(id){
  if (id === "faber") return path.join(REC, "piper", "faber");
  return path.join(VOZES, id);
}
function instalada(id){
  try { return id === "faber" || fs.statSync(path.join(VOZES, id, "model.onnx")).size > 1e6; } catch { return false; }
}
function garantirVoz(id){
  if (!instalada(id)) id = "faber";
  if (tts && vozAtual === id) return;
  const d = pastaDaVoz(id), t0 = Date.now();
  tts = new sherpa.OfflineTts({
    model: { vits: { model: path.join(d, "model.onnx"), tokens: path.join(d, "tokens.txt"), dataDir: path.join(REC, "espeak-ng-data") }, numThreads: THREADS, provider: "cpu", debug: 0 },
    maxNumSentences: 1
  });
  vozAtual = id; erroCarga = null;
  enviar({ t: "log", msg: `voz ${id} carregada em ${Date.now() - t0} ms` });
}

// divide em frases curtas, para começar a falar logo (igual ao APK)
function frases(texto){
  const t = String(texto).replace(/\s+/g, " ").trim(), out = [];
  let atual = "";
  for (let i = 0; i < t.length; i++){
    const c = t[i]; atual += c;
    const fim = ".!?;:\n".includes(c) && (i + 1 >= t.length || t[i + 1] === " ");
    const longa = atual.length > 180 && c === ",";
    if (fim || longa){ const f = atual.trim(); if (f) out.push(f); atual = ""; }
  }
  if (atual.trim()) out.push(atual.trim());
  return out;
}
// fade curto no começo e no fim da frase: evita estalos
function suavizar(s, taxa){
  const ini = Math.min(s.length >> 2, Math.round(taxa / 200)), fim = Math.min(s.length >> 2, Math.round(taxa / 25));
  for (let i = 0; i < ini; i++) s[i] *= i / ini;
  for (let i = 0; i < fim; i++) s[s.length - 1 - i] *= i / fim;
  return s;
}
const proximoTick = () => new Promise(r => setImmediate(r));

async function falar({ id, texto, voz, velocidade }){
  const minha = ++geracao;
  try {
    garantirVoz(voz || "faber");
    for (const f of frases(texto)){
      await proximoTick();            // deixa chegar um "parar" entre as frases
      if (minha !== geracao) break;
      const a = tts.generate({ text: f, sid: 0, speed: velocidade || 1, enableExternalBuffer: false });
      if (minha !== geracao) break;
      enviar({ t: "audio", id, taxa: a.sampleRate, amostras: suavizar(Float32Array.from(a.samples), a.sampleRate) });
    }
    enviar({ t: "fim", id, interrompido: minha !== geracao });
  } catch (e){
    erroCarga = String(e?.message || e);
    enviar({ t: "fim", id, erro: erroCarga });
  }
}

// Palavra "Atlas": três peças trabalhando juntas, tudo sem internet.
// 1) o modelo de palavra-chave (rápido, mas no Windows erra muito sozinho);
// 2) o detector de fala (silero) recorta cada frase dita;
// 3) o Whisper tiny transcreve a frase curta e confere se ela tem o nome.
// Se o Whisper não estiver disponível, volta ao modo antigo (só a palavra-chave).
const NOME = /(?:^|[^a-zà-ú])(atlas|atlass|atlaz|atl[aá]s|atlis|atles|hatlas)(?![a-zà-ú])/i;
// no começo da frase o Whisper tiny costuma ouvir "Atlas" como "Atos"/"Atas"
const NOME_INICIO = /^\s*(?:(?:e|ei|ey|hey|oi|ô|olá|ola)[\s,!.]+)?(atos|atas|átos|atlos|ats)(?![a-zà-ú])/i;
const NAO = /atlet|atl[aâ]nt|at[aâ]nt|atetis|atr[aá]s/i;
let vad = null, whisper = null, vadResto = new Float32Array(0), tAudio = 0, kwsHit = 0;
function kwsIniciar(){
  if (!kws){
    const K = path.join(REC, "kws");
    kws = new sherpa.KeywordSpotter({
      featConfig: { sampleRate: 16000, featureDim: 80 },
      modelConfig: { transducer: { encoder: path.join(K, "encoder.onnx"), decoder: path.join(K, "decoder.onnx"), joiner: path.join(K, "joiner.onnx") }, tokens: path.join(K, "tokens.txt"), numThreads: 1, provider: "cpu", debug: 0 },
      keywordsFile: path.join(K, "keywords.txt"), keywordsScore: 1.5, keywordsThreshold: 0.06, maxActivePaths: 4, numTrailingBlanks: 1
    });
    try {
      const W = path.join(REC, "whisper"), V = path.join(REC, "silero_vad.onnx");
      if (fs.existsSync(path.join(W, "decoder.onnx")) && fs.existsSync(V)){
        vad = new sherpa.Vad({ sileroVad: { model: V, threshold: 0.45, minSilenceDuration: 0.35, minSpeechDuration: 0.15, windowSize: 512, maxSpeechDuration: 6 }, sampleRate: 16000, numThreads: 1, provider: "cpu", debug: 0 }, 30);
        whisper = new sherpa.OfflineRecognizer({ featConfig: { sampleRate: 16000, featureDim: 80 }, modelConfig: { whisper: { encoder: path.join(W, "encoder.onnx"), decoder: path.join(W, "decoder.onnx"), language: "pt", task: "transcribe" }, tokens: path.join(W, "tokens.txt"), numThreads: THREADS, provider: "cpu", debug: 0 } });
        enviar({ t: "log", msg: "escuta do nome: usando o Whisper para confirmar" });
      }
    } catch (e){ vad = whisper = null; enviar({ t: "log", msg: "escuta do nome sem Whisper: " + (e?.message || e) }); }
  }
  kwsStream = kws.createStream();
  vad?.reset(); vadResto = new Float32Array(0); tAudio = 0; kwsHit = 0;
}
function transcrever(amostras){
  const r = whisper.createStream();
  r.acceptWaveform({ samples: amostras, sampleRate: 16000 });
  whisper.decode(r);
  return whisper.getResult(r).text.trim();
}
function avaliarFrase(seg){
  const amostras = Float32Array.from(seg.samples), dur = amostras.length / 16000;
  const kwsNaFrase = kwsHit && kwsHit >= seg.start - 8000;
  kwsHit = 0;
  if (dur > 6.5) return;
  const texto = transcrever(amostras);
  const m = texto.match(NOME) || texto.match(NOME_INICIO);
  if (m){
    const resto = texto.slice(m.index + m[0].length).replace(/^[\s,.!?:;-]+/, "").trim();
    const comPedido = resto.split(/\s+/).filter(w => /[a-zà-ú]/i.test(w)).length >= 2;
    // com pedido junto, manda o áudio para a página transcrever direito (o Whisper tiny erra o resto da frase)
    enviar({ t: "palavra", palavra: "ATLAS", texto, comando: comPedido ? resto : "", audio: comPedido ? amostras : null });
  } else if (kwsNaFrase && dur < 2.5 && texto.split(/\s+/).length <= 3 && !NAO.test(texto)){
    enviar({ t: "palavra", palavra: "ATLAS", texto, comando: "" });
  } else if (kwsNaFrase) enviar({ t: "log", msg: `escuta do nome: descartei "${texto}"` });
}
function kwsAudio(amostras){
  if (!kws || !kwsStream) return;
  tAudio += amostras.length;
  kwsStream.acceptWaveform({ samples: amostras, sampleRate: 16000 });
  while (kws.isReady(kwsStream)){
    kws.decode(kwsStream);
    const r = kws.getResult(kwsStream);
    if (r.keyword){
      kws.reset(kwsStream);
      if (!whisper) return enviar({ t: "palavra", palavra: r.keyword });
      kwsHit = tAudio;
    }
  }
  if (!whisper) return;
  const b = new Float32Array(vadResto.length + amostras.length); b.set(vadResto); b.set(amostras, vadResto.length);
  let i = 0;
  for (; i + 512 <= b.length; i += 512) vad.acceptWaveform(b.subarray(i, i + 512));
  vadResto = b.slice(i);
  while (kwsStream && !vad.isEmpty()){
    const seg = vad.front(false); vad.pop();
    avaliarFrase(seg);
  }
}

porta.on("message", ({ data: m }) => {
  try {
    if (m.t === "init"){ REC = m.recursos; VOZES = m.vozes; THREADS = m.threads || 2; setTimeout(() => { try { garantirVoz("faber"); } catch (e){ erroCarga = String(e?.message || e); enviar({ t: "log", msg: "falha ao carregar a voz: " + erroCarga }); } }, 50); }
    else if (m.t === "info") enviar({ t: "info", id: m.id, pronta: !!tts, voz: vozAtual, erro: erroCarga });
    else if (m.t === "carregar"){ try { garantirVoz(m.voz); enviar({ t: "carregada", id: m.id, voz: vozAtual }); } catch (e){ erroCarga = String(e?.message || e); enviar({ t: "carregada", id: m.id, erro: erroCarga }); } }
    else if (m.t === "descarregar"){ if (vozAtual === m.voz){ tts = null; vozAtual = null; } }
    else if (m.t === "falar") falar(m);
    else if (m.t === "parar") geracao++;
    else if (m.t === "kws-iniciar"){ try { kwsIniciar(); enviar({ t: "kws-ok" }); } catch (e){ enviar({ t: "kws-erro", erro: String(e?.message || e) }); } }
    else if (m.t === "kws-audio") kwsAudio(m.amostras);
    else if (m.t === "kws-parar") kwsStream = null;
  } catch (e){ enviar({ t: "log", msg: "motor: " + (e?.message || e) }); }
});
