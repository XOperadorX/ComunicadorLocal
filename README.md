# 📱 Comunicador Local

Aplicativo Android de comunicação direta sem internet, usando **Bluetooth** (~10m) e **Wi-Fi Direct** (~100m).

---

## ✨ Funcionalidades
- 🔵 **Bluetooth**: conexão até ~10 metros
- 📶 **Wi-Fi Direct**: conexão direta entre aparelhos, até ~100 metros
- 💬 Conversa em tempo real entre dois dispositivos
- 📅 Horário das mensagens exibido
- 📋 Histórico da conversa
- 🔑 Sem necessidade de internet ou rede móvel

---

## 🛠️ Tecnologias
- **Kotlin** — Linguagem principal
- **AndroidX** — Componentes da interface
- **Gradle 8.2** — Build
- **Android SDK 34** — Compilação
- **Mínimo**: Android 8.0 (API 26)

---

## 📂 Estrutura do Projeto

ComunicadorLocal/
├── app/
│ ├── build.gradle
│ └── src/main/
│ ├── AndroidManifest.xml
│ ├── java/com/exemplo/comunicadorlocal/
│ │ ├── MainActivity.kt # Tela principal e lógica
│ │ ├── Mensagem.kt # Modelo de dados
│ │ ├── BluetoothHelper.kt # Gerenciamento Bluetooth
│ │ └── WifiDirectHelper.kt # Gerenciamento Wi-Fi Direct
│ └── res/
│ ├── layout/activity_main.xml
│ └── values/
│ ├── strings.xml
│ └── themes.xml
├── build.gradle
├── settings.gradle
└── gradle.properties


---

## 🚀 Como Compilar

### Pré-requisitos
- JDK 17
- Gradle 8.2
- Android SDK (compileSdk 34)

### Passos
```bash
# Entrar na pasta
cd E:\CRIACAO\vscode\ComunicadorLocal

# Limpar cache
.\gradlew.bat clean

# Gerar APK
.\gradlew.bat assembleDebug


APK pronto → app\build\outputs\apk\debug\app-debug.apk


📲 Como Usar
🔵 Via Bluetooth
Ative o Bluetooth nos dois celulares
Emparelhe os aparelhos
Em um aparelho → toque em "Ficar Disponível"
No outro → toque em "Procurar" e selecione o dispositivo
Conectado! ✅ Digite e envie mensagens
📶 Via Wi-Fi Direct
Em um aparelho → "Ficar Disponível" (aguardando conexão)
No outro → "Procurar" → selecione o dispositivo
Aceite a solicitação de conexão quando solicitado
Conectado! ✅ Distância maior (~100m)
⚠️ Permissões Necessárias
O aplicativo solicita permissões para:
🔵 Bluetooth — escaneamento, anúncio e conexão
📶 Wi-Fi Direct — descoberta e conexão direta
📍 Localização — obrigatória para descoberta de dispositivos



🔧 Resolução de Problemas

Problema	Solução
Não encontra dispositivos	Ative localização + permita acesso
Não conecta	Desative/reative o Bluetooth/Wi-Fi
Mensagens não chegam	Mantenha os aparelhos próximos
Erro de compilação	Apague .gradle/ e app/build/ e recompile

📝 Notas
Versão: 1.0
Autor: Projeto pessoal — Foz do Iguaçu/PR
Atualizado: 18/10/2026
Distribuição livre para uso e modificação
💡 Ideias de Melhorias Futuras
 Suporte a múltiplos contatos
 Envio de imagens
 Criptografia das mensagens
 Mensagens offline
 Ícone personalizado do aplicativo


 

