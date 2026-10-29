# Comunicador Local

Aplicativo Android nativo, desenvolvido em Kotlin, para conversar entre aparelhos próximos por Bluetooth, sem depender de internet ou rede móvel.

## Como funciona

O app tem duas telas:

1. **Seleção de conexão:** escolha Bluetooth. O botão **Wi-Fi Direct** permanece desativado para implementação futura.
2. **Conversa:** o app começa automaticamente a aguardar conexões Bluetooth. Não é necessário criar conta nem informar senha.

Na conversa, o status Bluetooth fica verde como **Conectado**, amarelo como **Conectando** e vermelho como **Desconectado**. O nome do aparelho aparece no status. O Android não informa se o app no outro aparelho está conversando com uma terceira pessoa; por isso, o amarelo indica uma tentativa de conexão, não uma confirmação de que o aparelho está ocupado.

Além de texto, é possível enviar imagens, vídeos e arquivos de áudio sem um limite de tamanho definido pelo app. Também é possível gravar um áudio no app e enviá-lo diretamente, ou tirar uma foto com a câmera e enviá-la. Para anexos maiores que **1 MB**, aparece uma animação de envio com tamanho transferido, tamanho total e velocidade em kbps. A transferência depende do espaço livre no telefone, da memória e da estabilidade/velocidade do Bluetooth. Toque em uma mensagem de mídia para abri-la em um aplicativo compatível. Os dois aparelhos precisam ter a versão 1.5 para transferir mídia.

Cada mensagem registra a data e a hora de envio ou recebimento. Mensagens enviadas sem conexão Bluetooth ficam salvas na fila privada do app e são transmitidas quando a conexão for estabelecida; elas aparecem marcadas como pendentes. Mensagens recebidas geram uma notificação quando a conversa está conectada e o app está em segundo plano; no Android 13 ou superior, permita notificações quando solicitado. O histórico da conversa também é salvo no telefone e permanece após sair do app ou arquivar. **Limpar** apaga o histórico atual e as mensagens pendentes. **Arquivar** salva uma cópia sem apagar o histórico e abre a lista de arquivos por data e hora; selecione um arquivo para ler ou exclua apenas essa cópia. Os arquivos e favoritos ficam no armazenamento privado do app. A linha inferior informa a ação executada. Os botões foram compactados para aproveitar melhor a tela do celular. A tela inicial exibe a versão **1.5**.

## Conectar aparelhos

### Bluetooth

1. Instale o app nos dois aparelhos e conceda a permissão de Bluetooth solicitada.
2. Pareie os aparelhos nas configurações do Android.
3. No primeiro aparelho, escolha **Bluetooth**; ele ficará disponível automaticamente.
4. No segundo, escolha **Bluetooth**, toque em **Conectar pareado** e selecione o primeiro aparelho.
5. Quando o status ficar verde, envie mensagens, imagens, vídeos ou arquivos de áudio; use **Gravar áudio** para gravar ou **Foto** para capturar uma imagem com a câmera. Os anexos exigem que o aparelho esteja conectado e que ambos usem a versão 1.5. Na primeira gravação, permita acesso ao microfone.

O app procura aparelhos que já estejam pareados pelo Android; o pareamento não é feito dentro do app. Se a conexão for encerrada, o app volta automaticamente a aguardar outra conexão. Em vermelho, o nome indica que a conexão foi encerrada ou falhou; amarelo indica conexão em andamento. Não é possível confirmar por Bluetooth se uma conexão falhou porque o outro aparelho está usando o app com outra pessoa ou porque está desconectado.

Toque em uma mensagem para selecioná-la e use **Favoritar** para marcá-la ou removê-la dos favoritos. **Arquivar** salva a conversa no armazenamento privado do app, e **Limpar** apaga as mensagens da conversa atual. A linha inferior da tela informa o comando executado.

### Wi-Fi Direct

O botão está desativado nesta versão. A implementação existente foi mantida no projeto para uso futuro.

## Requisitos

- JDK 17.
- Android SDK Platform 34.
- Android Studio ou Android SDK configurado.
- Dois aparelhos Android para testar a comunicação entre dispositivos.

O projeto usa Gradle 8.2 pelo wrapper, Android Gradle Plugin 8.2.0, `compileSdk`/`targetSdk` 34 e `minSdk` 26 (Android 8.0).

## Compilar o APK

No Windows, execute na raiz do repositório:

```powershell
.\gradlew.bat assembleDebug
```

Para apagar os arquivos gerados e produzir um APK novo:

```powershell
.\gradlew.bat clean
if ($LASTEXITCODE -eq 0) { .\gradlew.bat assembleDebug }
```

O APK de depuração será gerado em `app\build\outputs\apk\debug\app-debug.apk`. Builds de depuração usam uma chave de teste e não devem ser distribuídos como versão oficial.

## Assinar uma versão de release

O projeto assina o release quando as quatro variáveis de ambiente abaixo estão configuradas. As senhas e a chave **não** devem ser adicionadas ao código, ao README, ao Git ou compartilhadas:

- `APP_RELEASE_STORE_FILE`: caminho do arquivo keystore.
- `APP_RELEASE_STORE_PASSWORD`: senha do keystore.
- `APP_RELEASE_KEY_ALIAS`: alias da chave de upload.
- `APP_RELEASE_KEY_PASSWORD`: senha da chave.

Se essas variáveis não estiverem configuradas, as tarefas de release falham em vez de gerar silenciosamente um pacote sem assinatura.

Crie uma chave de upload uma única vez, fora do repositório. Guarde uma cópia de segurança protegida; perder essa chave pode impedir atualizações fora do fluxo de Play App Signing:

```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\.android" | Out-Null
keytool -genkeypair -v -keystore "$env:USERPROFILE\.android\comunicadorlocal-upload.jks" -alias comunicadorlocal-upload -keyalg RSA -keysize 2048 -validity 10000
```

Configure as variáveis no ambiente do seu sistema ou na sessão privada do terminal. No PowerShell, carregue as senhas com `Read-Host -AsSecureString` e exponha-as ao processo Gradle apenas durante a compilação. Não coloque os valores reais no histórico do terminal.

Exemplo de configuração para a sessão atual (substitua o caminho se necessário):

```powershell
$env:APP_RELEASE_STORE_FILE = "$env:USERPROFILE\.android\comunicadorlocal-upload.jks"
$env:APP_RELEASE_KEY_ALIAS = "comunicadorlocal-upload"
$secureStore = Read-Host "Senha do keystore" -AsSecureString
$ptrStore = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureStore)
try { $env:APP_RELEASE_STORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptrStore) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptrStore) }
$secureKey = Read-Host "Senha da chave" -AsSecureString
$ptrKey = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
try { $env:APP_RELEASE_KEY_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptrKey) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptrKey) }
```

Gere o APK ou o Android App Bundle assinado:

```powershell
.\gradlew.bat assembleRelease
.\gradlew.bat bundleRelease
```

Os arquivos ficam em `app\build\outputs\apk\release\app-release.apk` e `app\build\outputs\bundle\release\app-release.aab`. Para publicar no Google Play, use o AAB e configure **Play App Signing** no Play Console; a chave local deve ser tratada como chave de upload. Use a mesma identidade de assinatura em futuras atualizações. Depois do build, limpe as senhas da sessão com:

```powershell
Remove-Item Env:APP_RELEASE_STORE_PASSWORD, Env:APP_RELEASE_KEY_PASSWORD -ErrorAction SilentlyContinue
```

Assinar identifica o publicador e protege a integridade do pacote; isso **não** garante que o Play Protect deixe de bloquear um app sinalizado. Se o Play Protect mostrar uma detecção de segurança, investigue o motivo e solicite análise pelo Google Play Console/Play Protect. Não distribua um APK bloqueado como forma de contornar a proteção.

## Créditos e licença

Copyright © 2026 **mmquasetudo**. Desenvolvedor: **Moises20XX**.

O software é gratuito e distribuído como software livre sob a licença MIT. É permitido usar, copiar, modificar e redistribuir o software, mantendo o aviso de copyright e o texto da licença. Consulte [LICENSE](LICENSE).

## Estrutura

```text
.
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/exemplo/comunicadorlocal/
│       │   ├── BluetoothHelper.kt
│       │   ├── MainActivity.kt
│       │   ├── Mensagem.kt
│       │   └── WifiDirectHelper.kt
│       └── res/
├── build.gradle
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── settings.gradle
```
