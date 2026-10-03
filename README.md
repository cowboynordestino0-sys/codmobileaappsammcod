# CoD Suporte

Menu flutuante de utilidades para jogar Call of Duty Mobile com o aparelho no melhor estado possível.
Paleta preto / vermelho / roxo.

**O app usa apenas APIs oficiais do Android (as mesmas de um "modo jogo" de fabricante ou de uma bolha do Discord).**
Ele não lê a memória do jogo, não injeta código, não altera arquivos do jogo e não oferece nenhuma vantagem em partida
(sem mira, sem mapa, sem macros, sem modificação de sensibilidade ou de áudio).

## Como gerar o APK

1. Instale o **Android Studio** (Koala ou mais recente) no computador.
2. `File > Open` e escolha esta pasta (`codsuporte`). Aguarde o Gradle sincronizar (baixa tudo sozinho).
3. Ligue o celular com **Depuração USB** e clique em **Run ▶**, ou gere o arquivo com `Build > Build APK(s)`
   (fica em `app/build/outputs/apk/debug/app-debug.apk`) e instale no celular.

Requisitos: Android 8.0 (API 26) ou superior.

## Primeiro uso

Abra o app **CoD Suporte** e conceda as 4 permissões da tela inicial (cada botão leva direto à tela certa do Android):

| Permissão | Para quê |
|---|---|
| Sobrepor a outros apps | A bolha aparecer sobre o jogo (obrigatória) |
| Modificar configurações do sistema | Controle de brilho |
| Acesso a Não Perturbe | Silenciar notificações no modo jogo |
| Notificações | Aviso fixo do serviço (Android 13+) |

Opcional: em **Alvo do ping** você pode trocar o host/porta medidos (padrão `1.1.1.1:443`).
Depois toque em **Iniciar menu flutuante**, abra o jogo e toque na bolha. Ela pode ser arrastada.

## Funções

| Função | Como funciona de verdade |
|---|---|
| **Ping / jitter / perda** | Mede o tempo de conexão TCP até o host escolhido a cada 2 s (últimas 15 amostras). A cor da bolha muda: roxo claro < 60 ms, rosa < 120 ms, vermelho acima disso ou sem resposta. |
| **RAM livre** | Lê a memória disponível via `ActivityManager`. |
| **Bateria e temperatura** | Lê o broadcast oficial de bateria; avisa em vermelho acima de 42 °C. |
| **Modo jogo** | Liga Não Perturbe (prioridade, o som do jogo continua), mantém a tela ligada, limpa a RAM, zera o cronômetro e abre o CoD Mobile. |
| **Não perturbar** | `NotificationManager.setInterruptionFilter`; restaura o estado anterior ao desligar. |
| **Manter tela ligada** | Flag `KEEP_SCREEN_ON` na janela do menu: a tela não apaga enquanto o menu está ativo. |
| **Brilho** | `Settings.System.SCREEN_BRIGHTNESS` (modo manual). |
| **Volume de mídia** | `AudioManager`. |
| **Limpar RAM** | `killBackgroundProcesses` nos apps que você instalou (nunca o jogo, o launcher ou o próprio app). |
| **Cronômetro + pausa** | Tempo de sessão e lembrete (30/45/60 min) com vibração. |
| **Painel de internet** | Atalho para trocar Wi-Fi / dados móveis sem sair do jogo. |

## Limites honestos

- O ping é até o host que você escolhe, não até o servidor da sua partida. Ele mostra a qualidade da *sua* conexão
  (Wi-Fi, operadora), que é o que você consegue melhorar.
- "Limpar RAM" tem efeito modesto: o Android pode reiniciar apps em segundo plano, e aparelhos com pouca RAM sentem mais a diferença.
- O app não consegue aumentar FPS nem mudar gráficos do jogo; isso só é possível dentro das configurações do próprio CoD Mobile.
- Alguns fabricantes (Xiaomi, Huawei, Samsung com economia agressiva) matam serviços em segundo plano. Se a bolha sumir,
  desative a otimização de bateria para o CoD Suporte nas configurações do aparelho.

## Estrutura

```
app/src/main/java/com/codsuporte/
  MainActivity.kt     tela de permissões, alvo do ping, iniciar/parar
  OverlayService.kt   bolha flutuante, painel e todas as funções
  Tools.kt            ping, RAM, bateria, brilho, volume, Não Perturbe
```
