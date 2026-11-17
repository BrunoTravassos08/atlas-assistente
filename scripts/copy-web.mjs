// Copia o protótipo (index.html na raiz) para www/, a pasta que o Capacitor empacota no APK.
// No CI, grava a versão do APK (0.1.<número do build>) para aparecer em Ajustes e no diagnóstico.
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
mkdirSync("www", { recursive: true });
const run = process.env.GITHUB_RUN_NUMBER;
let html = readFileSync("index.html", "utf8");
if (run) html = html.replaceAll("__VERSAO_APP__", `0.1.${run}`);
writeFileSync("www/index.html", html);
console.log("www/index.html atualizado" + (run ? ` (versão 0.1.${run})` : ""));
