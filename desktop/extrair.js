// Abre o pacote .tar.bz2 de uma voz Piper (release tts-models do sherpa-onnx) sem depender do tar do Windows.
// O tar.exe de algumas versões do Windows 10 não entende .bz2, e aí a voz nunca instalava.
// Roda num processo separado (utilityProcess) para a janela não travar enquanto descompacta (~10 s).
const fs = require("node:fs");
const path = require("node:path");
const bunzip = require("seek-bzip");

// lê os arquivos de um .tar (formato ustar/GNU) e devolve só os que `quero(nome)` aceitar
function lerTar(buf, quero){
  const achados = {};
  let pos = 0, nomeLongo = null;
  while (pos + 512 <= buf.length){
    const cab = buf.subarray(pos, pos + 512);
    if (cab.every(b => b === 0)) break;   // fim do arquivo
    const texto = (i, n) => cab.subarray(i, i + n).toString("utf8").replace(/\0.*$/s, "");
    const tam = parseInt(texto(124, 12).trim() || "0", 8) || 0;
    const tipo = String.fromCharCode(cab[156] || 48);
    const prefixo = texto(345, 155);
    let nome = nomeLongo || (prefixo ? prefixo + "/" : "") + texto(0, 100);
    nomeLongo = null;
    const ini = pos + 512, fim = ini + tam;
    if (fim > buf.length) throw new Error("pacote da voz cortado (baixou pela metade)");
    if (tipo === "L") nomeLongo = buf.subarray(ini, fim).toString("utf8").replace(/\0.*$/s, "");
    else if ((tipo === "0" || tipo === "\0") && quero(nome)) achados[nome] = buf.subarray(ini, fim);
    pos = ini + Math.ceil(tam / 512) * 512;
  }
  return achados;
}

// voz = "pt_BR-jeff-medium"; grava model.onnx, tokens.txt e LICENCA.txt em `destino`
function extrairVoz(arq, voz, destino){
  let tar;
  try { tar = bunzip.decode(fs.readFileSync(arq)); }
  catch (e){ throw new Error("o arquivo da voz veio corrompido (" + (e?.message || e) + ")"); }
  const base = `vits-piper-${voz}/`;
  const f = lerTar(tar, n => n.startsWith(base) && !n.slice(base.length).includes("/"));
  const modelo = f[base + voz + ".onnx"], tokens = f[base + "tokens.txt"];
  if (!modelo || modelo.length < 1e6 || !tokens) throw new Error("arquivo da voz incompleto");
  const tmp = destino + ".novo";
  fs.rmSync(tmp, { recursive: true, force: true });
  fs.mkdirSync(tmp, { recursive: true });
  fs.writeFileSync(path.join(tmp, "model.onnx"), modelo);
  fs.writeFileSync(path.join(tmp, "tokens.txt"), tokens);
  const lic = f[base + "MODEL_CARD"] || f[base + "README.md"];
  if (lic) fs.writeFileSync(path.join(tmp, "LICENCA.txt"), lic);
  fs.rmSync(destino, { recursive: true, force: true });
  fs.renameSync(tmp, destino);
}

module.exports = { lerTar, extrairVoz };

// quando roda como processo do Electron: recebe { arq, voz, destino } e responde { ok } ou { erro }
if (process.parentPort){
  process.parentPort.once("message", ({ data }) => {
    try { extrairVoz(data.arq, data.voz, data.destino); process.parentPort.postMessage({ ok: true }); }
    catch (e){ process.parentPort.postMessage({ erro: String(e?.message || e) }); }
  });
}
