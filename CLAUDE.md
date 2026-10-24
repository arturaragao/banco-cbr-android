# Banco CBR Android

## O que é
Invólucro Android Kotlin do site https://arturaragao.github.io/banco-cbr-app2/, applicationId `io.github.arturaragao.bancocbr`, minSdk 26 e compileSdk/targetSdk 35. Sem Firebase, backend nativo ou serviço pago. Não comitar nem publicar sem pedido explícito. Arquivos UTF-8 sem BOM; preferir apply_patch.

## Arquitetura
- `MainActivity`: WebView sem ActionBar, JS/DOM storage/IndexedDB/service worker, cookies, HTTPS, sem mixed content ou zoom. Histórico pelo voltar. Navegação fora dos hosts arturaragao.github.io e api.github.com abre externamente. Extra `acao` ou URI `bancocbr://abrir?acao=tarefa|ri` vira query no site. Splash e atalhos estáticos em XML.
- `QuickAddActivity` (diálogo nativo, sem WebView, teclado aberto, 🎤, rascunho em SharedPreferences `draft`) e `VoiceTaskActivity` (transparente, dispara RecognizerIntent pt-BR e salva direto; sem reconhecedor abre a QuickAdd). Widget pequeno e atalho "Nova tarefa" abrem a QuickAdd; atalho "Falar tarefa" abre a VoiceTask. Ambas salvam na fila via `PendingStore` e atualizam widgets (RefreshCoordinator), sem rede.
- `PendingTasks` (lógica pura, testada) / `PendingStore`: fila "pendentes" em SharedPreferences `pending`, JSON `[{id(UUID),texto,criadoEm(ISO),voz}]`, thread-safe; fila ilegível é copiada para a chave `corrompido`. Pendentes aparecem no widget de tarefas em "Gerais (sem data)".
- `Summary`/`SummaryStore`: validação JSON e último resumo em SharedPreferences privadas; sem backup. Datas no fuso do dispositivo; tarefasHoje só vale quando `hoje` coincide com a data atual.
- `RefreshCoordinator`: serializa gravação, atualização de widgets e reagendamento; `BancoApplication` registra WorkManager periódico de 3h, sem rede.
- `Widgets`: três AppWidgetProviders, RemoteViews, lista rolável via TasksRemoteViewsService e calendário com linhas de sete células. Feitas são ocultadas; urgentes em negrito com a cor do tipo. Calendário usa o mês atual; somente dias presentes no resumo têm detalhes.
- `NotificationPlan`: regras puras dos horários. `Notifications`: canal Avisos, até quatro próximos alarmes (um por tipo) e um de meia-noite, todos inexact com setAndAllowWhileIdle. Cada entrega recalcula a mensagem com o último resumo e arma o próximo aviso. Deduplica por tipo/data. Não usa SCHEDULE_EXACT_ALARM.
- Receivers: reboot, mudança de data/hora/fuso e atualização do APK restauram agendamento e widgets. Android pode atrasar alarmes; forçar parada bloqueia eventos até abrir novamente.
- Gradle Kotlin DSL: AGP 8.13.2, Kotlin 2.2.21, Gradle 8.13, JDK 17. Versões por propriedades. CI testa/linta, assina release se tiver os dois secrets e publica `latest` com tag versionada; senão debug. O JAR do wrapper é gerado pelo CI se ausente.

- `Updater` (1.2): atualização pelo GitHub Releases sem Play Store. Ao abrir (máx. a cada 6 h) consulta `releases/latest` (sem token); tag v1.2.N, N = versionCode = run_number (`UpdateVersion`, testado). Mais novo → diálogo "Atualizar" → baixa o APK no cache → FileProvider `<pkg>.updates` → instalador do Android (1 toque; silencioso é impossível fora da Play Store). 1ª vez pede "instalar apps desconhecidos" e instala ao voltar (`installPending`). `UpdateWorker` (12 h, com rede) só notifica, 1x por versão; tocar abre o app com extra `atualizar`. Assinatura release sempre com a mesma chave (senão não instala por cima).
- `Ideas` (widget/atalho "Ideia"; 1.3 = tela própria): `IdeaActivity` (layout `idea.xml`, tema `Theme.BancoCBR.Idea`, cartão no rodapé) usa `SpeechRecognizer` dentro do app (permissão RECORD_AUDIO pedida na 1ª vez) em laço: cada resultado/silêncio (NO_MATCH/SPEECH_TIMEOUT) começa outra rodada até o Artur tocar ⏸; parcial aparece no texto (`Ideas.join`); 3 erros seguidos pausam. Pausado: edita, 🎤 continua, Salvar/Cancelar. Sem reconhecedor ou sem permissão: digita. Salvar → `IdeaStore` (SharedPreferences `ideias`, chave `fila`, linhas `- [ ] AAAA-MM-DD HH:MM — texto`) → `IdeaWorker` (WorkManager único "ideias", APPEND_OR_REPLACE, precisa de rede) acrescenta em `ideias/caixa.md` do repo privado `arturaragao/banco-cbr-app` (GET sha + PUT; 409/422 tenta 2x) **e** `QuickAdd.save(kind="ideia")` põe na fila de pendentes com `"tipo":"ideia"`: o site (1.19.0+) cria 1 tarefa Ferramenta sem data "💡 título" (texto inteiro nos detalhes) e ela aparece no widget de tarefas na hora. Chave: a MainActivity lê `localStorage.bcbr_gh_token` e guarda em `ideias/token`; sem chave a ideia espera na fila. No PC, `~/memoria/scripts/inicio.ps1` (SessionStart) lê as linhas `- [ ]` com `gh api` e entrega ao Claude. Zero tokens no celular.

## Ponte BCBRNativo
O site chama `window.BCBRNativo.dados(JSON.stringify(resumo))` sempre que os dados mudarem e no carregamento. O método é assíncrono: valida, salva e atualiza recursos nativos; JSON inválido preserva o último resumo válido. `window.BCBRNativo.versao()` devolve `BuildConfig.VERSION_NAME`. A ponte existe na WebView e deve ser usada somente pelo conteúdo confiável do site; evitar iframes/scripts não confiáveis.

Pendentes (tarefas criadas nativamente, texto cru; o site interpreta data/hora ao importar): `window.BCBRNativo.pendentes()` devolve o JSON array da fila `[{id,texto,criadoEm,voz,tipo?}]` (ou `[]`; `tipo:"ideia"` só no widget Ideia); `window.BCBRNativo.confirmar(JSON.stringify([ids]))` remove da fila os ids importados e atualiza os widgets. O site importa as pendentes ao abrir a MainActivity.

Formato do resumo (de hoje até +35 dias):
```json
{
  "geradoEm": "2026-10-23T08:00:00Z",
  "hoje": "2026-10-23",
  "tarefasHoje": [{"id":"1","texto":"Revisar tórax","tipo":"estudo","feito":false,"urg":true}],
  "tarefasSem": [{"id":"2","texto":"Comprar livro","tipo":"tarefas","feito":false,"urg":false}],
  "dias": {"2026-10-23":{"noite":"BR","ev":["Reunião X"],"agenda":"","meta":20,"feito":10,"tipoDia":"estudo"}},
  "mes": "2026-10"
}
```
`tipo`: estudo|ferramenta|tarefas. `noite`: BR|HSI|"". `tipoDia`: estudo|descanso|sobreaviso. `ev` e `agenda` entram nos avisos de evento. Urgências das 07h/13h vêm das tarefasHoje não feitas; tarefasSem aparecem no widget e não recebem uma data artificial. Resumo limitado a 1 MiB. Nenhuma consulta de rede em widgets ou workers (exceções: `UpdateWorker`, só a API pública de releases; `IdeaWorker`, só `ideias/caixa.md`).

## Pedidos do Artur
- Widget pequeno para adicionar tarefa (rápido, nativo, texto ou voz).
- Atalho "Falar tarefa" ao segurar o ícone (ditado salva direto).
- Atalhos ao segurar o ícone: tarefa e RI do dia.
- Widget grande com tarefas do dia e gerais, mais widget calendário.
- Notificações de eventos, plantões e tarefas urgentes.
- Atualização automática do app (24/10/2026): sem baixar à mão, sem tokens.
- Widget/atalho "Ideia" (24/10/2026): ditar ideia de ferramenta no celular sem abrir o Claude (sem tokens), confirmar o texto, gravar no GitHub; o PC oferece no início da sessão.
- Ideia (24/10/2026, 2º pedido): gravar até ele tocar para parar (pausas na fala não encerram), painel mais bonito, versão do APK ao lado da do site; ideias viram tarefas na aba Semana (lista automática, sem tokens).
- Anexos no widget Ideia e Compartilhar (24/10/2026, no lugar do bot de Telegram).
- APK grátis pelo GitHub Releases; sem chaves no código; sem commit/push nesta criação.

## Estado atual
Implementação inicial completa, workflow e testes da lógica do resumo/avisos preparados. A máquina de criação tem somente Java 8, sem Gradle e sem Android SDK; build Android e testes ainda dependem da primeira execução no Actions. A integração depende de o site chamar a ponte; este projeto não altera o site. Validar aparência dos widgets e entrega de alarmes em aparelho real após instalar o APK. Widget Ideia: falta testar no aparelho (voz → diálogo → linha em `banco-cbr-app/ideias/caixa.md`); o lado do PC foi testado com uma ideia gravada pela API no mesmo formato.

## Histórico
- 2026-10-24: versão 1.5: anexos no widget Ideia (Compartilhar → Banco CBR de qualquer app, botão 📎 Anexar; cópia local, sobem para `ideias/anexos/` antes da linha, sufixo " · anexos: ...", limite 15 MB, Uri persistente/cópia no rascunho). 1.4: confere atualização a cada abertura (1x/min) e de 15 em 15 min. Feito pelo Codex, revisado pelo Codex medio; CI verde; não testado em aparelho.
- 2026-10-24: versão 1.3 (CI usa 1.3.N): tela própria do widget Ideia (gravação contínua até ⏸, texto ao vivo, cartão no rodapé), permissão RECORD_AUDIO, `<queries>` RecognitionService, campo `tipo` na fila de pendentes (ideia vira tarefa 💡 Ferramenta no site 1.19.0), testes `Ideas.join` e tipo; ícones dos atalhos (segurar o ícone) redondos coloridos `ic_sc_*` (+ verde, 🎤 verde, 🎤 amarelo da Ideia, RI azul). Não testado em aparelho (sem SDK local).
- 2026-10-24: widget e atalho "Ideia" (IdeaActivity/IdeaStore/IdeaWorker/IdeaWidget, IdeasTest); MainActivity copia `bcbr_gh_token` do site para o lado nativo; `inicio.ps1` da memória mostra as ideias novas. Não testado em aparelho.
- 2026-10-24: versão 1.2 (CI usa 1.2.N): atualizador pelo Releases (diálogo ao abrir + notificação a cada 12 h), FileProvider, REQUEST_INSTALL_PACKAGES, UpdateVersionTest. A 1.2 precisa ser instalada à mão uma vez; dali em diante o app avisa e instala com 1 toque.
- 2026-10-23: versão 1.1 (CI usa 1.1.N): QuickAddActivity/VoiceTaskActivity, fila de pendentes, ponte pendentes()/confirmar(), atalho Falar tarefa, PendingTasksTest. Não testado em aparelho (sem SDK local).
- 2026-10-23: criação do projeto Android, WebView/ponte, splash/ícone/atalhos, três widgets, notificações locais, atualização periódica, workflow de APK e documentação.
