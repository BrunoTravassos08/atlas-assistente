# Atlas · Assistente de IA estilo Jarvis

Protótipo web (mobile-first) de um assistente pessoal de IA com interface reativa estilo HUD. Ele é a base visual e funcional do futuro app Android.

## O que já tem

- **Núcleo animado**: globo 3D com plasma, raios, onda sonora, ondas de choque, satélites e radar. O núcleo reage quando o assistente ouve, pensa e fala.
- **Fundo tecnológico**: malha hexagonal, rede de nós, piso em perspectiva e colunas de dados. Reage ao toque e ao mouse com ondas de água e rastro de luz.
- **Login com a IA de preferência**: ChatGPT (OpenAI) ou Claude (Anthropic), com uma tela de criação do agente e barra de progresso.
- **Briefing de abertura**: cumprimento, notícias dos assuntos de interesse, próximos compromissos e uma pergunta sobre a faculdade ou alguma atividade.
- **Voz**: ditado, respostas faladas e escuta contínua com palavra de ativação ("Atlas, ...").
- **Skills**
  - Agenda e cartões de destaque
  - Tradução PT ⇄ EN/ES, lida em voz no idioma certo
  - PDF: ler, resumir, padronizar todas as páginas no mesmo tamanho (A4, Carta, Ofício...), girar, excluir, reordenar e baixar
  - Pesquisa avançada com relatório
  - Tutor da faculdade

## Como rodar

O arquivo `index.html` foi feito para rodar como Artifact no claude.ai. Lá ele usa os recursos da plataforma: IA (`sample`), voz (`tts` e `dictation`), downloads e o navegador do app Claude desktop para buscar notícias.

Aberto direto no navegador ou no GitHub Pages, a interface, as animações e o editor de PDF funcionam. A IA e a voz da plataforma ficam em modo offline.

## App Android (APK de teste)

**Baixar a versão mais recente:** https://github.com/BrunoTravassos08/atlas-assistente/releases/latest/download/Atlas.apk

O app empacota o `index.html` com o [Capacitor](https://capacitorjs.com). A cada push na `main`, o workflow `.github/workflows/android-apk.yml` compila o APK e publica um novo Release. Também dá para rodar manualmente em **Actions → APK Android → Run workflow**.

Para instalar: abra o link no celular, baixe o `Atlas.apk`, toque no arquivo e permita "instalar apps desconhecidos". As versões seguintes instalam por cima.

### Conectar a IA

Na primeira abertura, escolha ChatGPT ou Claude e cole uma **chave de API** (o app tem um botão que abre a página de chaves de cada empresa). Nem a OpenAI nem a Anthropic oferecem login com a conta do ChatGPT/Claude.ai para apps de terceiros, e a API é cobrada à parte das assinaturas, então a conta de API precisa ter crédito.

- A chave é validada na hora (lista de modelos + uma resposta de teste) e fica salva só no aparelho.
- O app escolhe o modelo mais recente que a chave enxerga.
- Pesquisa e briefing de notícias usam a busca na web do próprio provedor.
- No Android, a voz usa o reconhecimento de fala e o leitor de texto do sistema. A escuta contínua ("Atlas, ...") ainda não existe no app.

### Agente e skills

Ao terminar a configuração, o app cria o agente: envia a personalidade e todas as skills (ferramentas) para o modelo escolhido e faz um teste real para confirmar que ele aceita. Skills pré-instaladas para todo usuário: agenda, cartões de destaque, tradução PT/EN/ES (lida em voz), PDF (ler, padronizar, girar, excluir, reordenar, baixar), pesquisa avançada com busca na web, briefing de notícias e tutor de estudos (plano em etapas no painel, com opção de salvar na agenda). A conversa com o agente fica salva no aparelho e continua entre as aberturas; dá para apagar em Ajustes. Com chave de API não existe "projeto" dentro do app do ChatGPT ou do Claude: o agente vive no Atlas.

### Configuração inicial

O app não vem com interesses nem agenda pré-definidos. Na primeira abertura ele guia a pessoa em 4 etapas: nome, conexão com a IA, interesses (assuntos prontos + cidade, time e assuntos livres) e rotina (horário do briefing, dias, aviso no celular e voz). Ao terminar, o agente já pesquisa os interesses e apresenta o primeiro briefing. Depois, o briefing acontece uma vez por dia, a partir do horário escolhido, na primeira vez que o app é aberto. Dá para refazer tudo em **Ajustes → Refazer configuração**.

### Desempenho e rotação

- Efeitos em modo **Leve** por padrão no app: 30 quadros por segundo, fundo em resolução menor e sem desfoque. Dá para trocar para **Completo** em Ajustes.
- Celular deitado usa um layout próprio: núcleo à esquerda e painel à direita.

Para compilar no computador: `npm install`, `npm run sync` e depois `cd android && ./gradlew assembleDebug` (precisa de Java 21 e Android SDK).

## App para Windows (computador)

**Baixar a versão mais recente:** https://github.com/BrunoTravassos08/atlas-assistente/releases/latest/download/Atlas-Setup.exe

A pasta `desktop/` empacota o mesmo `index.html` com o [Electron](https://www.electronjs.org). O mesmo workflow que gera o APK gera também o instalador `Atlas-Setup.exe` e publica os dois no mesmo Release.

Para instalar: baixe o `Atlas-Setup.exe` no Windows 10 ou 11 e abra. O instalador ainda não tem assinatura digital paga, então o Windows pode mostrar "O Windows protegeu o computador": clique em **Mais informações → Executar assim mesmo**. Depois disso o Atlas se atualiza sozinho a cada nova versão.

O que muda em relação ao Android:

- **Tela grande**: a mesma interface, com núcleo e painel maiores em monitores grandes. O cabeçalho do Atlas vira a barra da janela. **F11** deixa em tela cheia.
- **Voz**: a mesma voz neural offline (Faber, Piper) do APK, rodando no computador. Também dá para usar as vozes do Windows ou a voz do Gemini.
- **Microfone**: o Windows não tem o reconhecimento de voz do Google, então o Atlas grava a fala e a transcreve com a chave do Gemini (grátis) ou da OpenAI. Só com a chave do Claude, o comando precisa ser digitado.
- **Chamar pelo nome**: "Atlas" é reconhecido sem internet, com a janela aberta: a palavra-chave do APK escuta, e o Whisper tiny confirma a frase (evita alarme falso e entende "Atlas, toca uma música" de uma vez; o pedido é transcrito pela IA da chave).
- **Botão flutuante**: no computador vira um atalho de teclado (**Ctrl + Alt + Espaço** por padrão), que chama o Atlas já ouvindo de qualquer programa.
- **Bandeja**: fechar a janela deixa o Atlas perto do relógio. Ele abre junto com o Windows (dá para desligar em Ajustes) e avisa na hora do briefing.
- **Música**: toca direto no YouTube ou YouTube Music (abre no navegador); com o Spotify instalado, abre a busca no Spotify (ou dá o play sozinho, com o Spotify conectado: veja abaixo). Pausar, pular e volume usam as teclas de mídia do Windows.

### ChatGPT pela assinatura (só no Windows)

No Windows, ao escolher ChatGPT, a aba **Conta do ChatGPT** deixa entrar com o plano Plus/Pro, sem chave de API. É o mesmo jeito do HUBIA: o Atlas não chama a API da OpenAI, ele roda o **Codex CLI oficial** (`codex.exe`) logado na conta da pessoa.

1. Na primeira vez, o Atlas baixa `codex.exe` e `codex-code-mode-host.exe` do GitHub da OpenAI (cerca de 170 MB) para `%APPDATA%\Atlas\codex\bin`.
2. `codex login` abre o navegador na página da OpenAI; o Atlas confere `codex login status` a cada 3 s (até 10 min) e recusa login feito por chave de API.
3. Cada pergunta roda `codex exec --json --ephemeral --ignore-user-config --sandbox read-only` com o texto pelo stdin. O processo usa uma pasta própria (`CODEX_HOME`) e roda sem `OPENAI_API_KEY`, para a cobrança sair do plano e não da API.
4. O Codex não conhece as ferramentas do Atlas (agenda, música, PDF…); elas viram blocos `<ferramenta>{json}</ferramenta>` no texto, que o app executa e devolve.

Limites: funciona só no computador (no Android não há como rodar o Codex); cada resposta leva alguns segundos a mais que a API; o uso conta no limite do plano do ChatGPT (quando acaba, o Atlas avisa). Sem chave, a fala é transcrita pelo Whisper tiny do próprio Atlas, sem internet (menos preciso). A chave de API continua disponível na aba **Chave de API**. Testes do módulo: `node --test desktop/test/*.test.js`.

O login com a conta Google (Antigravity/Gemini CLI) **não** foi feito: o Google não permite que outros apps usem a assinatura Google AI Pro/Ultra e já baniu contas que fizeram isso. O Gemini segue pela chave grátis do AI Studio.

## Spotify: o Atlas dá o play sozinho

Com a conta do Spotify conectada (Ajustes › Atalhos e música › Conectar Spotify), o Atlas procura a música pela Web API do Spotify e manda tocar, no celular e no Windows. Se o Spotify estiver fechado, ele abre o app, espera o aparelho aparecer e toca. Sem Premium, ou se algo falhar, continua o jeito antigo (abre a música no app).

Regras do Spotify (desde fevereiro de 2026, modo de desenvolvimento):
- O dono do app no Spotify Developer precisa ter **Premium**, e cada pessoa que usa também precisa de Premium para o play automático.
- No máximo **5 pessoas** por app; cada uma precisa ter o e-mail adicionado em *User Management*.

Para configurar: crie um app em developer.spotify.com/dashboard com a Web API marcada e os Redirect URIs `atlas://spotify` (Android) e `http://127.0.0.1:8898/spotify` (Windows). O Client ID vai em `SPOTIFY_CLIENT_ID` no index.html, ou cada pessoa cola o seu em Ajustes. O login usa PKCE, então não há segredo dentro do app.

Para rodar no computador durante o desenvolvimento: `cd desktop && npm install && npm start`. Para gerar o instalador: `npm run dist` (no Windows).

## Próximos passos (app Android)

- Integração real com as APIs da OpenAI e da Anthropic, usando a chave ou o login do usuário
- Criação do agente no provedor durante a tela de setup
- Palavra de ativação nativa, rodando em segundo plano
- Busca web e notícias pelas ferramentas de busca do provedor
