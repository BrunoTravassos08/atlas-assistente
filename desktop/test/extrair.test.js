// Testes do instalador da voz (node --test desktop/test)
const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const { lerTar, extrairVoz } = require("../extrair");

function montarPacote(dir, voz, comModelo = true){
  const raiz = path.join(dir, "src", `vits-piper-${voz}`);
  fs.mkdirSync(path.join(raiz, "espeak-ng-data", "voices"), { recursive: true });
  if (comModelo) fs.writeFileSync(path.join(raiz, `${voz}.onnx`), Buffer.alloc(2e6, 7));
  fs.writeFileSync(path.join(raiz, "tokens.txt"), "_ 0\n^ 1\n");
  fs.writeFileSync(path.join(raiz, "MODEL_CARD"), "License: CC0");
  fs.writeFileSync(path.join(raiz, "espeak-ng-data", "voices", "um-nome-bem-comprido-".repeat(6) + ".txt"), "x");
  const arq = path.join(dir, "voz.tar.bz2");
  const t = spawnSync("tar", ["-cjf", arq, "-C", path.join(dir, "src"), `vits-piper-${voz}`]);
  assert.strictEqual(t.status, 0, String(t.stderr));
  return arq;
}

test("extrairVoz instala model.onnx, tokens.txt e a licença sem usar o tar", { skip: process.platform === "win32" }, () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "atlas-teste-"));
  try {
    const arq = montarPacote(dir, "pt_BR-jeff-medium");
    const destino = path.join(dir, "vozes", "jeff");
    fs.mkdirSync(path.dirname(destino), { recursive: true });
    extrairVoz(arq, "pt_BR-jeff-medium", destino);
    assert.strictEqual(fs.statSync(path.join(destino, "model.onnx")).size, 2e6);
    assert.strictEqual(fs.readFileSync(path.join(destino, "tokens.txt"), "utf8"), "_ 0\n^ 1\n");
    assert.match(fs.readFileSync(path.join(destino, "LICENCA.txt"), "utf8"), /CC0/);
    assert.ok(!fs.existsSync(destino + ".novo"));
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});

test("extrairVoz avisa quando o pacote não tem a voz ou veio corrompido", { skip: process.platform === "win32" }, () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "atlas-teste-"));
  try {
    const arq = montarPacote(dir, "pt_BR-cadu-medium", false);
    assert.throws(() => extrairVoz(arq, "pt_BR-cadu-medium", path.join(dir, "cadu")), /incompleto/);
    const ruim = path.join(dir, "ruim.tar.bz2");
    fs.writeFileSync(ruim, fs.readFileSync(arq).subarray(0, 200));
    assert.throws(() => extrairVoz(ruim, "pt_BR-cadu-medium", path.join(dir, "cadu")), /corrompido|cortado/);
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});

test("lerTar ignora o que não foi pedido", () => {
  const cab = Buffer.alloc(512);
  cab.write("pasta/a.txt", 0); cab.write("00000000003 ", 124); cab[156] = 48;
  const corpo = Buffer.alloc(512); corpo.write("oi!");
  const tar = Buffer.concat([cab, corpo, Buffer.alloc(1024)]);
  assert.deepStrictEqual(Object.keys(lerTar(tar, () => true)), ["pasta/a.txt"]);
  assert.strictEqual(lerTar(tar, () => true)["pasta/a.txt"].toString(), "oi!");
  assert.deepStrictEqual(lerTar(tar, () => false), {});
});
