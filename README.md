# Comunicador Local

Aplicativo Android nativo, desenvolvido em Kotlin, para conversar entre aparelhos próximos por Bluetooth ou Wi-Fi Direct, sem depender de internet ou rede móvel.

## Como funciona

O app tem duas telas:

1. **Seleção de conexão:** escolha Bluetooth ou Wi-Fi Direct. Não é necessário criar conta nem informar senha.
2. **Conversa:** no modo Bluetooth, o app começa automaticamente a aguardar conexões; no Wi-Fi Direct, crie uma sala ou procure outro aparelho.

Na conversa, o status Bluetooth fica verde quando conectado (com o nome do aparelho), amarelo enquanto aguarda e vermelho quando desconectado. Ao tentar conectar a um aparelho pareado, o status mostra o nome selecionado; em caso de falha, fica como **Desconectado: nome do aparelho**.

Mensagens enviadas sem conexão Bluetooth ficam salvas na fila privada do app e são transmitidas quando a conexão for estabelecida; elas aparecem marcadas como pendentes. O histórico da conversa também é salvo no telefone e permanece após sair do app ou arquivar. **Limpar** apaga o histórico atual e as mensagens pendentes. **Arquivar** salva uma cópia sem apagar o histórico e abre a lista de arquivos por data e hora; selecione um arquivo para ler ou exclua apenas essa cópia. Os arquivos e favoritos ficam no armazenamento privado do app. A linha inferior informa a ação executada. A tela inicial exibe a versão **1.2**.

## Conectar aparelhos

### Bluetooth

1. Instale o app nos dois aparelhos e conceda a permissão de Bluetooth solicitada.
2. Pareie os aparelhos nas configurações do Android.
3. No primeiro aparelho, escolha **Bluetooth**; ele ficará disponível automaticamente.
4. No segundo, escolha **Bluetooth**, toque em **Conectar pareado** e selecione o primeiro aparelho.
5. Quando o status indicar conexão, digite e envie mensagens.

O app procura aparelhos que já estejam pareados pelo Android; o pareamento não é feito dentro do app. Se a conexão for encerrada, o app volta automaticamente a aguardar outra conexão.

Toque em uma mensagem para selecioná-la e use **Favoritar** para marcá-la ou removê-la dos favoritos. **Arquivar** salva a conversa no armazenamento privado do app, e **Limpar** apaga as mensagens da conversa atual. A linha inferior da tela informa o comando executado.

### Wi-Fi Direct

1. Instale o app nos dois aparelhos e conceda a permissão de dispositivos Wi-Fi próximos.
2. No aparelho que criará a sala, escolha **Wi-Fi Direct** e toque em **Criar Sala**.
3. No outro aparelho, escolha **Wi-Fi Direct**, toque em **Procurar** e selecione a sala.
4. Aguarde a conexão e então envie mensagens.

O Wi-Fi Direct precisa estar disponível e ativado nos aparelhos. A descoberta e a conexão podem variar conforme o fabricante e a versão do Android.

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
