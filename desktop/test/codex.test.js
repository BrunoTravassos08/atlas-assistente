// Testes do módulo do ChatGPT pela assinatura (node --test desktop/test)
const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const codex = require("../codex");

test("codexEvent lê as linhas do codex exec --json", () => {
  assert.deepStrictEqual(codex.codexEvent('{"type":"item.completed","item":{"id":"item_1","type":"agent_message","text":"Olá!"}}'), { tipo: "texto", texto: "Olá!" });
  assert.deepStrictEqual(codex.codexEvent('{"type":"item.started","item":{"type":"web_search","query":"placar Sport"}}'), { tipo: "busca", consulta: "placar Sport", fim: false });
  assert.strictEqual(codex.codexEvent('{"type":"item.completed","item":{"type":"web_search","query":"x"}}').fim, true);
  assert.deepStrictEqual(codex.codexEvent('{"type":"turn.completed","usage":{"input_tokens":10}}'), { tipo: "fim", uso: { input_tokens: 10 } });
  assert.deepStrictEqual(codex.codexEvent('{"type":"turn.failed","error":{"message":"You\'ve hit your usage limit"}}'), { tipo: "erro", mensagem: "You've hit your usage limit" });
  // "Reconnecting" e o aviso do code-mode-host não são erro
  assert.strictEqual(codex.codexEvent('{"type":"error","message":"Reconnecting... 2/5 (stream disconnected)"}').tipo, "aviso");
  assert.strictEqual(codex.codexEvent('{"type":"item.completed","item":{"type":"error","message":"Code Mode is unavailable"}}').tipo, "aviso");
  assert.strictEqual(codex.codexEvent('{"type":"error","message":"unexpected status 401"}').tipo, "erro");
  assert.strictEqual(codex.codexEvent("2026-11-25T17:32:53Z ERROR log solto"), null);
  assert.strictEqual(codex.codexEvent('{"type":"thread.started","thread_id":"abc"}').tipo, "inicio");
});

test("lerStatus só aceita o login pela conta do ChatGPT", () => {
  assert.deepStrictEqual(codex.lerStatus("Logged in using ChatGPT"), { logado: true, porChave: false });
  assert.deepStrictEqual(codex.lerStatus("Logged in using an API key - sk-proj-***"), { logado: false, porChave: true });
  assert.deepStrictEqual(codex.lerStatus("Not logged in"), { logado: false, porChave: false });
});

test("classificar separa limite do plano, login e rede", () => {
  assert.strictEqual(codex.classificar("You've hit your usage limit. Try again in 3 hours"), "limite");
  assert.strictEqual(codex.classificar("unexpected status 401 Unauthorized"), "auth");
  assert.strictEqual(codex.classificar("stream disconnected before completion"), "network");
  assert.strictEqual(codex.classificar("algo estranho"), "api");
});

test("env() apaga as chaves de API e usa a pasta própria do Atlas", () => {
  const dados = fs.mkdtempSync(path.join(os.tmpdir(), "atlas-teste-"));
  process.env.OPENAI_API_KEY = "sk-teste"; process.env.CODEX_API_KEY = "x";
  codex.iniciar({ dados });
  const e = codex.env();
  assert.strictEqual(e.OPENAI_API_KEY, undefined);
  assert.strictEqual(e.CODEX_API_KEY, undefined);
  assert.strictEqual(e.CODEX_HOME, path.join(dados, "codex", "home"));
});

test("chat() manda o prompt pelo stdin e devolve o texto final", { skip: process.platform === "win32" }, async () => {
  const dados = fs.mkdtempSync(path.join(os.tmpdir(), "atlas-teste-"));
  codex.iniciar({ dados });
  // um "codex" de mentira: confere os argumentos, lê o stdin e responde em JSONL
  const falso = path.join(dados, "codex", "bin", "codex");
  fs.writeFileSync(falso, `#!/usr/bin/env node
const a = process.argv.slice(2); let entrada = "";
process.stdin.on("data", d => entrada += d).on("end", () => {
  const ok = a[0] === "exec" && a.includes("--json") && a.includes("--ephemeral") && a.includes("read-only") && a.at(-1) === "-" && !process.env.OPENAI_API_KEY;
  const out = l => process.stdout.write(JSON.stringify(l) + "\\n");
  out({ type: "thread.started", thread_id: "t1" });
  out({ type: "error", message: "Reconnecting... 1/5" });
  if (entrada.includes("LIMITE")){ out({ type: "turn.failed", error: { message: "You've hit your usage limit" } }); process.exit(1); }
  out({ type: "item.completed", item: { type: "web_search", query: "teste" } });
  out({ type: "item.completed", item: { type: "agent_message", text: ok ? "eco: " + entrada.trim() + (a.includes('web_search="live"') ? " +web" : "") : "argumentos errados " + a.join(" ") } });
  out({ type: "turn.completed", usage: {} });
});
`, { mode: 0o755 });
  process.env.OPENAI_API_KEY = "sk-teste";
  const evs = [];
  const r = await codex.chat({ id: "a", prompt: "Olá Atlas", web: true }, ev => evs.push(ev.tipo));
  assert.strictEqual(r.texto, "eco: Olá Atlas +web");
  assert.strictEqual(r.buscas, 1);
  assert.ok(evs.includes("aviso") && evs.includes("fim"));
  await assert.rejects(codex.chat({ id: "b", prompt: "LIMITE" }), e => e.code === "limite");
});
