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

## Próximos passos (app Android)

- Integração real com as APIs da OpenAI e da Anthropic, usando a chave ou o login do usuário
- Criação do agente no provedor durante a tela de setup
- Palavra de ativação nativa, rodando em segundo plano
- Busca web e notícias pelas ferramentas de busca do provedor
