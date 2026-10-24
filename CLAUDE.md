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

## Ponte BCBRNativo
O site chama `window.BCBRNativo.dados(JSON.stringify(resumo))` sempre que os dados mudarem e no carregamento. O método é assíncrono: valida, salva e atualiza recursos nativos; JSON inválido preserva o último resumo válido. `window.BCBRNativo.versao()` devolve `BuildConfig.VERSION_NAME`. A ponte existe na WebView e deve ser usada somente pelo conteúdo confiável do site; evitar iframes/scripts não confiáveis.

Pendentes (tarefas criadas nativamente, texto cru; o site interpreta data/hora ao importar): `window.BCBRNativo.pendentes()` devolve o JSON array da fila (ou `[]`); `window.BCBRNativo.confirmar(JSON.stringify([ids]))` remove da fila os ids importados e atualiza os widgets. O site importa as pendentes ao abrir a MainActivity.

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
`tipo`: estudo|ferramenta|tarefas. `noite`: BR|HSI|"". `tipoDia`: estudo|descanso|sobreaviso. `ev` e `agenda` entram nos avisos de evento. Urgências das 07h/13h vêm das tarefasHoje não feitas; tarefasSem aparecem no widget e não recebem uma data artificial. Resumo limitado a 1 MiB. Nenhuma consulta de rede em widgets ou workers.

## Pedidos do Artur
- Widget pequeno para adicionar tarefa (rápido, nativo, texto ou voz).
- Atalho "Falar tarefa" ao segurar o ícone (ditado salva direto).
- Atalhos ao segurar o ícone: tarefa e RI do dia.
- Widget grande com tarefas do dia e gerais, mais widget calendário.
- Notificações de eventos, plantões e tarefas urgentes.
- APK grátis pelo GitHub Releases; sem chaves no código; sem commit/push nesta criação.

## Estado atual
Implementação inicial completa, workflow e testes da lógica do resumo/avisos preparados. A máquina de criação tem somente Java 8, sem Gradle e sem Android SDK; build Android e testes ainda dependem da primeira execução no Actions. A integração depende de o site chamar a ponte; este projeto não altera o site. Validar aparência dos widgets e entrega de alarmes em aparelho real após instalar o APK.

## Histórico
- 2026-10-23: versão 1.1 (CI usa 1.1.N): QuickAddActivity/VoiceTaskActivity, fila de pendentes, ponte pendentes()/confirmar(), atalho Falar tarefa, PendingTasksTest. Não testado em aparelho (sem SDK local).
- 2026-10-23: criação do projeto Android, WebView/ponte, splash/ícone/atalhos, três widgets, notificações locais, atualização periódica, workflow de APK e documentação.
