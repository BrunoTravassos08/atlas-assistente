// Motor de voz do Atlas no Windows (processo separado, para não travar a janela):
// - voz neural offline (Piper, pelo sherpa-onnx), a mesma do APK;
// - palavra de ativação "Atlas" sem internet (modelo de palavra-chave do sherpa-onnx).
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

function kwsIniciar(){
  if (!kws){
    const K = path.join(REC, "kws");
    kws = new sherpa.KeywordSpotter({
      featConfig: { sampleRate: 16000, featureDim: 80 },
      modelConfig: { transducer: { encoder: path.join(K, "encoder.onnx"), decoder: path.join(K, "decoder.onnx"), joiner: path.join(K, "joiner.onnx") }, tokens: path.join(K, "tokens.txt"), numThreads: 1, provider: "cpu", debug: 0 },
      keywordsFile: path.join(K, "keywords.txt"), keywordsScore: 1.0, keywordsThreshold: 0.1, maxActivePaths: 4, numTrailingBlanks: 1
    });
  }
  kwsStream = kws.createStream();
}
function kwsAudio(amostras){
  if (!kws || !kwsStream) return;
  kwsStream.acceptWaveform({ samples: amostras, sampleRate: 16000 });
  while (kws.isReady(kwsStream)){
    kws.decode(kwsStream);
    const r = kws.getResult(kwsStream);
    if (r.keyword){ kws.reset(kwsStream); enviar({ t: "palavra", palavra: r.keyword }); }
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
