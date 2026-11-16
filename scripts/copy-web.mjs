// Copia o protótipo (index.html na raiz) para www/, a pasta que o Capacitor empacota no APK.
import { mkdirSync, copyFileSync } from "node:fs";
mkdirSync("www", { recursive: true });
copyFileSync("index.html", "www/index.html");
console.log("www/index.html atualizado");
