# Banco CBR

App Android (8.0 ou superior) para o [Banco CBR](https://arturaragao.github.io/banco-cbr-app2/), com WebView, widgets e avisos locais. Sem Firebase ou servidor de notificações.

## Instalar

No celular, abra no Chrome o [download do APK](https://github.com/arturaragao/banco-cbr-android/releases/latest/download/banco-cbr.apk). Ao abrir o arquivo, permita **Instalar apps desconhecidos** para o Chrome nas configurações do Android e confirme a instalação. O link funciona depois da primeira execução bem-sucedida do workflow.

## Usar

Abra o app para carregar os dados. O site deve chamar `BCBRNativo.dados(json)` para sincronizar o resumo nativo. Segure o ícone para **Nova tarefa** ou **RI do dia**. Na tela inicial, adicione os widgets **Adicionar tarefa**, **Tarefas de hoje** (lista rolável de hoje e gerais) e **Calendário** (BR/HSI, eventos e questões).

Permita notificações no primeiro uso (Android 13+). O canal **Avisos** mostra resumo às 07h, urgências pendentes às 13h, plantão às 17h e eventos/plantão de amanhã às 20h. Os horários seguem o fuso do celular e podem atrasar pela economia de bateria. Tudo usa o último resumo salvo; reabra o app para trazer alterações do site. O cache do mês só contém os dias enviados pelo site. Tarefas datadas antigas deixam de aparecer após a virada do dia.

## Build

O Actions executa em pushes na `main` e manualmente, prepara JDK 17/SDK 35/Gradle 8.13 e gera o wrapper se faltar o JAR. Executa testes unitários, lint e build; publica `banco-cbr.apk` no release **latest**, tag `v1.0.N`.

Para release assinado, configure os secrets `KEYSTORE_B64` (PKCS12 em base64, alias `bcbr`) e `KEYSTORE_PASS` (senha do arquivo e da chave). Sem ambos, gera debug; assinaturas debug de runners diferentes podem exigir desinstalar a versão anterior. Mantenha a mesma chave release para atualizações. Nenhuma chave é incluída no projeto.

Localmente é necessário JDK 17 e Android SDK 35. Se faltar o JAR, rode `gradle wrapper --gradle-version 8.13`; depois `./gradlew testDebugUnitTest lintDebug assembleDebug`. No Windows use `gradlew.bat`. Versão configurável por `-PversionCode=1 -PversionName=1.0.0`. Compatibilidade: [AGP 8.13.2 / Gradle 8.13 / JDK 17](https://developer.android.com/build/releases/agp-8-13-0-release-notes).
