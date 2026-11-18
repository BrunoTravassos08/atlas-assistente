// Prepara a versão desktop:
// 1) copia o index.html da raiz (a mesma interface do APK) para desktop/app/;
// 2) baixa a voz neural Faber (Piper) e o modelo da palavra "Atlas" para desktop/recursos/.
// Os modelos são grandes, então não ficam no git: o GitHub Actions roda este script antes de empacotar.
import { mkdirSync, readFileSync, writeFileSync, existsSync, statSync, copyFileSync, rmSync, readdirSync, cpSync, mkdtempSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import { tmpdir } from "node:os";

const AQUI = dirname(fileURLToPath(import.meta.url));
const DESK = join(AQUI, "..");
const RAIZ = join(DESK, "..");
const REC = join(DESK, "recursos");

// ---------- 1) interface ----------
mkdirSync(join(DESK, "app"), { recursive: true });
const run = process.env.GITHUB_RUN_NUMBER;
let html = readFileSync(join(RAIZ, "index.html"), "utf8");
if (run) html = html.replaceAll("__VERSAO_APP__", `0.1.${run}`);
writeFileSync(join(DESK, "app", "index.html"), html);
console.log("app/index.html atualizado" + (run ? ` (versão 0.1.${run})` : ""));

// ---------- 2) modelos de voz ----------
const SHERPA = "https://github.com/k2-fsa/sherpa-onnx/releases/download";
const VOZ = "pt_BR-faber-medium";
const KWS_MODELO = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01";
const temArquivo = f => existsSync(f) && statSync(f).size > 0;
// no Windows usa o tar do sistema (bsdtar), que já entende .tar.bz2
const TAR = process.platform === "win32" ? join(process.env.SystemRoot || "C:\\Windows", "System32", "tar.exe") : "tar";

async function baixarExtrair(url, destino){
  for (let tentativa = 1; ; tentativa++){
    try {
      const r = await fetch(url, { redirect: "follow" });
      if (!r.ok) throw new Error("HTTP " + r.status);
      const arq = join(destino, "pacote.tar.bz2");
      writeFileSync(arq, Buffer.from(await r.arrayBuffer()));
      const t = spawnSync(TAR, ["-xjf", arq, "-C", destino], { stdio: "inherit" });
      if (t.status !== 0) throw new Error("tar falhou");
      rmSync(arq, { force: true });
      return;
    } catch (e){
      if (tentativa >= 4) throw e;
      console.log(`  falhou (${e.message}), tentando de novo…`);
      await new Promise(r => setTimeout(r, 3000 * tentativa));
    }
  }
}

const PIPER = join(REC, "piper", "faber");
const ESPEAK = join(REC, "espeak-ng-data");
if (!temArquivo(join(PIPER, "model.onnx")) || !existsSync(join(ESPEAK, "pt_dict"))){
  console.log(`Baixando a voz ${VOZ}…`);
  const tmp = mkdtempSync(join(tmpdir(), "atlas-voz-"));
  await baixarExtrair(`${SHERPA}/tts-models/vits-piper-${VOZ}.tar.bz2`, tmp);
  const d = join(tmp, `vits-piper-${VOZ}`);
  mkdirSync(PIPER, { recursive: true });
  copyFileSync(join(d, `${VOZ}.onnx`), join(PIPER, "model.onnx"));
  copyFileSync(join(d, "tokens.txt"), join(PIPER, "tokens.txt"));
  copyFileSync(join(d, "MODEL_CARD"), join(PIPER, "MODEL_CARD"));
  // dados de pronúncia: só o português e o que é comum a todas as línguas (o mesmo recorte do APK)
  rmSync(ESPEAK, { recursive: true, force: true });
  mkdirSync(ESPEAK, { recursive: true });
  for (const item of ["phondata", "phondata-manifest", "phonindex", "phontab", "intonations", "pt_dict", "lang", "voices"]){
    const de = join(d, "espeak-ng-data", item);
    if (existsSync(de)) cpSync(de, join(ESPEAK, item), { recursive: true });
  }
  rmSync(tmp, { recursive: true, force: true });
}

const KWS = join(REC, "kws");
if (!temArquivo(join(KWS, "encoder.onnx"))){
  console.log("Baixando o modelo da palavra Atlas…");
  const tmp = mkdtempSync(join(tmpdir(), "atlas-kws-"));
  await baixarExtrair(`${SHERPA}/kws-models/${KWS_MODELO}.tar.bz2`, tmp);
  const k = join(tmp, KWS_MODELO);
  mkdirSync(KWS, { recursive: true });
  for (const p of ["encoder", "decoder", "joiner"]) copyFileSync(join(k, `${p}-epoch-12-avg-2-chunk-16-left-64.int8.onnx`), join(KWS, `${p}.onnx`));
  copyFileSync(join(k, "tokens.txt"), join(KWS, "tokens.txt"));
  rmSync(tmp, { recursive: true, force: true });
}
// jeitos de pronunciar "Atlas": a mesma lista do APK (scripts/preparar-voz.sh), para os dois ouvirem igual
// (no Windows o git pode trazer o arquivo com fim de linha \r\n)
const sh = readFileSync(join(RAIZ, "scripts", "preparar-voz.sh"), "utf8").replace(/\r\n/g, "\n");
const lista = (sh.match(/<<'PALAVRAS'\n([\s\S]*?)\nPALAVRAS/) || [])[1];
if (!lista) throw new Error("não achei a lista de palavras em scripts/preparar-voz.sh");
writeFileSync(join(KWS, "keywords.txt"), lista.trim() + "\n");

const tam = dir => readdirSync(dir, { recursive: true }).reduce((s, f) => { try { const st = statSync(join(dir, String(f))); return s + (st.isFile() ? st.size : 0); } catch { return s; } }, 0);
console.log(`recursos prontos: ${(tam(REC) / 1048576).toFixed(1)} MB`);
