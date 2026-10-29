package com.exemplo.comunicadorlocal

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.os.SystemClock
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.OpenableColumns
import android.util.Base64
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), WifiDirectHelper.EventosWifiDirect {
    private lateinit var btHelper: BluetoothHelper
    private lateinit var wifiHelper: WifiDirectHelper
    private lateinit var adaptadorConversa: ArrayAdapter<Mensagem>
    private val mensagens = mutableListOf<Mensagem>()
    private lateinit var edMensagem: EditText
    private lateinit var btnEnviar: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvComando: TextView
    private lateinit var listaConversa: ListView
    private val mensagensPendentes = mutableListOf<Mensagem>()
    private var modoAtual = Modo.NENHUM
    private var modoPendente = Modo.NENHUM
    private var chatVisivel = false
    private var appEmPrimeiroPlano = false
    private var solicitouPermissaoNotificacoes = false
    private var gravadorAudio: MediaRecorder? = null
    private var arquivoAudioGravado: File? = null
    private var dialogoTransferencia: AlertDialog? = null
    private var anexoRecebido: AnexoRecebido? = null
    private var uriCapturaPendente: Uri? = null
    private var arquivoCapturaPendente: File? = null
    private var mensagemSelecionada = -1
    private var aparelhoSelecionado: String? = null
    private var alvoDesconectado = false

    private val habilitarBluetooth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (modoAtual == Modo.BLUETOOTH && chatVisivel) {
                val adaptador = BluetoothAdapter.getDefaultAdapter()
                if (adaptador?.isEnabled == true) iniciarBtServidor()
                else atualizarStatusBluetooth(EstadoBluetooth.DESCONECTADO, "Bluetooth desativado.")
            }
        }

    private val selecionarImagem =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { enviarAnexo(it, "imagem") }
        }

    private val selecionarAudio =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { enviarAnexo(it, "audio") }
        }

    private val selecionarVideo =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { enviarAnexo(it, "video") }
        }

    private val capturarImagem =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { capturada ->
            val uri = uriCapturaPendente
            val arquivo = arquivoCapturaPendente
            uriCapturaPendente = null
            arquivoCapturaPendente = null
            if (capturada && uri != null && arquivo != null) enviarAnexo(uri, "imagem", arquivo)
            else arquivo?.delete()
        }

    private val permissaoGravacaoAudio =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedida ->
            if (concedida) iniciarGravacaoAudio()
            else Toast.makeText(this, "A permissão de microfone é necessária para gravar áudio.", Toast.LENGTH_LONG).show()
        }

    private val permissaoNotificacoes =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (chatVisivel && modoAtual == Modo.BLUETOOTH) iniciarBtServidor()
        }

    private enum class Modo { NENHUM, BLUETOOTH, WIFI_DIRECT }
    private enum class EstadoBluetooth { CONECTADO, ESPERANDO, CONECTANDO, DESCONECTADO }
    private data class AnexoRecebido(
        val id: String,
        val tipo: String,
        val mimeType: String,
        val nome: String,
        val arquivo: File,
        val saida: FileOutputStream
    )

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (!chatVisivel || modoAtual != Modo.BLUETOOTH) return
            when (msg.what) {
                BluetoothHelper.MENSAGEM_RECEBIDA ->
                    processarMensagemRecebida(msg.obj.toString(), btHelper.nomeConectado())
                BluetoothHelper.STATUS_CONECTADO -> {
                    aparelhoSelecionado = msg.obj?.toString()
                    atualizarStatusBluetooth(
                        EstadoBluetooth.CONECTADO,
                        "● Conectado: ${aparelhoSelecionado ?: "Aparelho Bluetooth"}"
                    )
                    alvoDesconectado = false
                    btnEnviar.isEnabled = true
                    registrarComando("conexão Bluetooth estabelecida")
                    enviarPendentesBluetooth()
                }
                BluetoothHelper.STATUS_ESPERANDO -> {
                    btnEnviar.isEnabled = true
                    if (btHelper.conectado()) {
                        atualizarStatusBluetooth(
                            EstadoBluetooth.CONECTADO,
                            "● Conectado: ${btHelper.nomeConectado()}"
                        )
                    } else if (alvoDesconectado) {
                        atualizarStatusBluetooth(
                            EstadoBluetooth.DESCONECTADO,
                            "● Desconectado: ${aparelhoSelecionado ?: "aparelho Bluetooth"}"
                        )
                    } else {
                        atualizarStatusBluetooth(EstadoBluetooth.ESPERANDO, "● Conectado: aguardando aparelho...")
                    }
                }
                BluetoothHelper.STATUS_DESCONECTADO -> {
                    descartarAnexoRecebido()
                    btnEnviar.isEnabled = true
                    alvoDesconectado = true
                    atualizarStatusBluetooth(
                        EstadoBluetooth.DESCONECTADO,
                        "● Desconectado: ${aparelhoSelecionado ?: "aparelho Bluetooth"}"
                    )
                    registrarComando("conexão encerrada; aguardando novamente")
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (chatVisivel && modoAtual == Modo.BLUETOOTH) iniciarBtServidor()
                    }, 500)
                }
                BluetoothHelper.STATUS_ERRO -> {
                    descartarAnexoRecebido()
                    btnEnviar.isEnabled = true
                    alvoDesconectado = true
                    atualizarStatusBluetooth(
                        EstadoBluetooth.DESCONECTADO,
                        "● Desconectado: ${aparelhoSelecionado ?: "aparelho Bluetooth"}"
                    )
                    registrarComando("Bluetooth desconectado")
                }
                BluetoothHelper.STATUS_CONEXAO_FALHOU -> {
                    descartarAnexoRecebido()
                    btnEnviar.isEnabled = true
                    alvoDesconectado = true
                    atualizarStatusBluetooth(
                        EstadoBluetooth.DESCONECTADO,
                        "● Desconectado: ${aparelhoSelecionado ?: "aparelho Bluetooth"}"
                    )
                    registrarComando("tentativa de conexão falhou")
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (chatVisivel && modoAtual == Modo.BLUETOOTH) iniciarBtServidor()
                    }, 500)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        btHelper = BluetoothHelper(handler)
        wifiHelper = WifiDirectHelper(this, Handler(Looper.getMainLooper()), this)
        carregarMensagens()
        carregarMensagensPendentes()
        wifiHelper.inicializar()
        criarCanalNotificacoes()
        mostrarTelaInicial()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (chatVisivel) voltarParaSelecao()
                else finish()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        appEmPrimeiroPlano = true
    }

    override fun onStop() {
        appEmPrimeiroPlano = false
        super.onStop()
    }

    private fun mostrarTelaInicial() {
        chatVisivel = false
        modoAtual = Modo.NENHUM
        setContentView(R.layout.activity_main)
        findViewById<TextView>(R.id.tvVersao).text = "Versão ${BuildConfig.VERSION_NAME}"
        findViewById<Button>(R.id.btnModoBluetooth).setOnClickListener {
            selecionarModo(Modo.BLUETOOTH)
        }
        findViewById<Button>(R.id.btnModoWifi).setOnClickListener {
            selecionarModo(Modo.WIFI_DIRECT)
        }
    }

    private fun selecionarModo(modo: Modo) {
        val permissoes = when {
            modo == Modo.BLUETOOTH && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                listOf(Manifest.permission.BLUETOOTH_CONNECT)
            modo == Modo.WIFI_DIRECT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            modo == Modo.WIFI_DIRECT ->
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            else -> emptyList()
        }
        val faltantes = permissoes.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (faltantes.isNotEmpty()) {
            modoPendente = modo
            ActivityCompat.requestPermissions(this, faltantes.toTypedArray(), REQUEST_PERMISSOES)
            return
        }
        abrirConversa(modo)
    }

    @Deprecated("Use the system permission result API")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSOES) return

        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            abrirConversa(modoPendente)
        } else {
            Toast.makeText(this, "A permissão é necessária para usar este modo.", Toast.LENGTH_LONG).show()
        }
        modoPendente = Modo.NENHUM
    }

    private fun abrirConversa(modo: Modo) {
        modoAtual = modo
        chatVisivel = true
        setContentView(R.layout.activity_chat)

        edMensagem = findViewById(R.id.edMensagem)
        btnEnviar = findViewById(R.id.btnEnviar)
        tvStatus = findViewById(R.id.tvStatus)
        listaConversa = findViewById(R.id.listaConversa)

        adaptadorConversa = object : ArrayAdapter<Mensagem>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, mensagens
        ) {
            override fun getView(posicao: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(posicao, convertView, parent)
                val mensagem = getItem(posicao)!!
                view.findViewById<TextView>(android.R.id.text1).text =
                    "${if (mensagem.favorita) "★ " else ""}${mensagem.hora} — " +
                        if (mensagem.ehMinha) "Você" else mensagem.remetente
                view.findViewById<TextView>(android.R.id.text2).text = mensagem.texto
                if (mensagem.pendente) {
                    view.findViewById<TextView>(android.R.id.text2)
                        .append("\nAguardando conexão para enviar")
                }
                view.setBackgroundColor(
                    if (posicao == mensagemSelecionada) Color.rgb(227, 242, 253) else Color.TRANSPARENT
                )
                return view
            }
        }
        listaConversa.adapter = adaptadorConversa
        mensagensPendentes.forEach { pendente ->
            val existente = mensagens.firstOrNull { it.id == pendente.id }
            if (existente == null) {
                mensagens.add(pendente)
            } else {
                existente.pendente = true
            }
        }

        val usandoBluetooth = modo == Modo.BLUETOOTH
        findViewById<Button>(R.id.btnVoltar).setOnClickListener { voltarParaSelecao() }
        findViewById<TextView>(R.id.tvModo).text =
            if (usandoBluetooth) "Bluetooth" else "Wi-Fi Direct"
        findViewById<View>(R.id.controlesBluetooth).visibility =
            if (usandoBluetooth) View.VISIBLE else View.GONE
        findViewById<View>(R.id.controlesWifi).visibility =
            if (usandoBluetooth) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.tvDicaConexao).text =
            if (usandoBluetooth) {
                "Pareie os aparelhos nas configurações do Android antes de procurar."
            } else {
                "Em um aparelho, crie uma sala. No outro, procure e selecione o aparelho."
            }

        btnEnviar.isEnabled = usandoBluetooth
        tvStatus.text = "● Desconectado: conecte-se a outro aparelho para começar."
        btnEnviar.setOnClickListener { enviarMensagem() }
        findViewById<Button>(R.id.btnBtDisponivel).setOnClickListener {
            alvoDesconectado = false
            aparelhoSelecionado = null
            iniciarBtServidor()
        }
        findViewById<Button>(R.id.btnBtProcurar).setOnClickListener { procurarBt() }
        findViewById<Button>(R.id.btnWifiDisponivel).setOnClickListener { iniciarWifiServidor() }
        findViewById<Button>(R.id.btnWifiProcurar).setOnClickListener { procurarWifi() }
        tvComando = findViewById(R.id.tvComando)
        tvComando.text = if (usandoBluetooth) {
            "Comando executado: Bluetooth disponível."
        } else {
            "Comando executado: Wi-Fi Direct selecionado."
        }
        findViewById<Button>(R.id.btnLimparMensagens).setOnClickListener { limparMensagens() }
        findViewById<Button>(R.id.btnArquivarMensagens).setOnClickListener { arquivarMensagens() }
        findViewById<Button>(R.id.btnFavoritarMensagem).setOnClickListener { alternarFavorita() }
        findViewById<Button>(R.id.btnEnviarImagem).setOnClickListener {
            selecionarImagem.launch("image/*")
        }
        findViewById<Button>(R.id.btnEnviarAudio).setOnClickListener {
            selecionarAudio.launch("audio/*")
        }
        findViewById<Button>(R.id.btnEnviarVideo).setOnClickListener {
            selecionarVideo.launch("video/*")
        }
        findViewById<Button>(R.id.btnTirarFoto).setOnClickListener { tirarFoto() }
        findViewById<Button>(R.id.btnGravarAudio).setOnClickListener {
            if (gravadorAudio == null) solicitarOuIniciarGravacaoAudio()
            else pararGravacaoAudio()
        }
        listaConversa.setOnItemClickListener { _, _, position, _ ->
            mensagemSelecionada = position
            adaptadorConversa.notifyDataSetChanged()
            registrarComando("mensagem ${position + 1} selecionada")
            mensagens.getOrNull(position)?.let { mensagem ->
                if (mensagem.tipo != "texto") abrirAnexo(mensagem)
            }
        }

        mensagens.forEach { mensagem ->
            mensagem.favorita = favoritos().contains(chaveFavorita(mensagem))
        }
        adaptadorConversa.notifyDataSetChanged()
        if (mensagens.isNotEmpty()) listaConversa.setSelection(mensagens.lastIndex)

        if (usandoBluetooth) {
            val precisaPermissaoNotificacoes = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !solicitouPermissaoNotificacoes &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            if (precisaPermissaoNotificacoes) solicitarPermissaoNotificacoes()
            else iniciarBtServidor()
        }
    }

    private fun voltarParaSelecao() {
        pararGravacaoAudio(enviar = false)
        btHelper.pararTudo()
        wifiHelper.pararTudo()
        alvoDesconectado = false
        aparelhoSelecionado = null
        mostrarTelaInicial()
    }

    private fun adicionarMensagem(
        texto: String,
        remetente: String,
        ehMinha: Boolean,
        tipo: String = "texto",
        arquivo: String? = null,
        mimeType: String? = null,
        nomeArquivo: String? = null
    ) {
        val mensagem = Mensagem(
            remetente = remetente,
            texto = texto,
            ehMinha = ehMinha,
            tipo = tipo,
            arquivo = arquivo,
            mimeType = mimeType,
            nomeArquivo = nomeArquivo
        )
        mensagem.favorita = favoritos().contains(chaveFavorita(mensagem))
        mensagens.add(mensagem)
        salvarMensagens()
        adaptadorConversa.notifyDataSetChanged()
        listaConversa.setSelection(adaptadorConversa.count - 1)
        if (!ehMinha) {
            registrarComando("receber mensagem")
            if (!appEmPrimeiroPlano) notificarMensagem(remetente, texto)
        }
    }

    private fun processarMensagemRecebida(linha: String, remetente: String) {
        when {
            linha.startsWith(BluetoothHelper.PREFIXO_ANEXO_INICIO) -> {
                iniciarRecepcaoAnexo(linha.removePrefix(BluetoothHelper.PREFIXO_ANEXO_INICIO))
                return
            }
            linha.startsWith(BluetoothHelper.PREFIXO_ANEXO_PARTE) -> {
                receberParteAnexo(linha.removePrefix(BluetoothHelper.PREFIXO_ANEXO_PARTE))
                return
            }
            linha.startsWith(BluetoothHelper.PREFIXO_ANEXO_FIM) -> {
                finalizarRecepcaoAnexo(linha.removePrefix(BluetoothHelper.PREFIXO_ANEXO_FIM), remetente)
                return
            }
            linha.startsWith(BluetoothHelper.PREFIXO_ANEXO_CANCELAR) -> {
                cancelarRecepcaoAnexo(linha.removePrefix(BluetoothHelper.PREFIXO_ANEXO_CANCELAR))
                return
            }
        }
        if (!linha.startsWith(PREFIXO_ANEXO)) {
            adicionarMensagem(linha, remetente, ehMinha = false)
            return
        }
        try {
            val dados = String(
                Base64.decode(linha.removePrefix(PREFIXO_ANEXO), Base64.NO_WRAP),
                StandardCharsets.UTF_8
            )
            val json = JSONObject(dados)
            val tipo = json.getString("tipo")
            val mimeType = json.getString("mimeType")
            val nome = json.getString("nome")
            val prefixoMime = when (tipo) {
                "imagem" -> "image/"
                "audio" -> "audio/"
                "video" -> "video/"
                else -> throw IllegalArgumentException("Tipo de anexo inválido.")
            }
            if (!mimeType.startsWith(prefixoMime)) {
                throw IllegalArgumentException("Tipo de anexo inválido.")
            }
            val bytes = Base64.decode(json.getString("dados"), Base64.NO_WRAP)
            val arquivo = salvarAnexo(bytes, mimeType)
            adicionarMensagem(
                textoAnexo(tipo, nome),
                remetente,
                ehMinha = false,
                tipo = tipo,
                arquivo = arquivo.absolutePath,
                mimeType = mimeType,
                nomeArquivo = nome
            )
        } catch (exception: org.json.JSONException) {
            Toast.makeText(this, "Não foi possível interpretar o anexo recebido.", Toast.LENGTH_LONG).show()
        } catch (exception: IllegalArgumentException) {
            Toast.makeText(this, exception.message ?: "Anexo inválido.", Toast.LENGTH_LONG).show()
        } catch (exception: IOException) {
            Toast.makeText(this, "Não foi possível salvar o anexo recebido.", Toast.LENGTH_LONG).show()
        }
    }

    private fun iniciarRecepcaoAnexo(payload: String) {
        try {
            val json = JSONObject(String(Base64.decode(payload, Base64.NO_WRAP), StandardCharsets.UTF_8))
            val id = json.getString("id")
            val tipo = json.getString("tipo")
            val mimeType = json.getString("mimeType")
            val nome = json.getString("nome")
            val prefixoMime = prefixoMime(tipo)
            require(id.matches(Regex("[A-Za-z0-9-]{1,64}")) && mimeType.startsWith(prefixoMime)) {
                "Metadados de mídia inválidos."
            }
            descartarAnexoRecebido()
            val arquivo = criarArquivoAnexo()
            anexoRecebido = AnexoRecebido(
                id,
                tipo,
                mimeType,
                nome,
                arquivo,
                FileOutputStream(arquivo)
            )
        } catch (exception: org.json.JSONException) {
            Toast.makeText(this, "Os dados do anexo recebido são inválidos.", Toast.LENGTH_LONG).show()
        } catch (exception: IOException) {
            Toast.makeText(this, "Não foi possível preparar o arquivo recebido.", Toast.LENGTH_LONG).show()
        } catch (exception: IllegalArgumentException) {
            Toast.makeText(this, "Não foi possível iniciar o recebimento do anexo.", Toast.LENGTH_LONG).show()
        }
    }

    private fun receberParteAnexo(payload: String) {
        val separador = payload.indexOf('|')
        if (separador <= 0) return
        val id = payload.substring(0, separador)
        val recebimento = anexoRecebido ?: return
        if (recebimento.id != id) return
        try {
            val bytes = Base64.decode(payload.substring(separador + 1), Base64.NO_WRAP)
            recebimento.saida.write(bytes)
        } catch (exception: IOException) {
            cancelarRecepcaoAnexo(id)
            Toast.makeText(this, "Falha ao salvar partes do anexo recebido.", Toast.LENGTH_LONG).show()
        } catch (exception: IllegalArgumentException) {
            cancelarRecepcaoAnexo(id)
            Toast.makeText(this, "O anexo recebido contém dados inválidos.", Toast.LENGTH_LONG).show()
        }
    }

    private fun finalizarRecepcaoAnexo(id: String, remetente: String) {
        val recebimento = anexoRecebido ?: return
        if (recebimento.id != id) return
        anexoRecebido = null
        try {
            recebimento.saida.close()
            adicionarMensagem(
                textoAnexo(recebimento.tipo, recebimento.nome),
                remetente,
                ehMinha = false,
                tipo = recebimento.tipo,
                arquivo = recebimento.arquivo.absolutePath,
                mimeType = recebimento.mimeType,
                nomeArquivo = recebimento.nome
            )
        } catch (exception: IOException) {
            recebimento.arquivo.delete()
            Toast.makeText(this, "Não foi possível concluir o anexo recebido.", Toast.LENGTH_LONG).show()
        }
    }

    private fun cancelarRecepcaoAnexo(id: String) {
        val recebimento = anexoRecebido ?: return
        if (recebimento.id != id) return
        anexoRecebido = null
        try {
            recebimento.saida.close()
        } catch (exception: IOException) {
            Toast.makeText(this, "Não foi possível fechar o arquivo recebido.", Toast.LENGTH_LONG).show()
        } finally {
            recebimento.arquivo.delete()
        }
    }

    private fun descartarAnexoRecebido() {
        anexoRecebido?.let { recebimento ->
            try {
                recebimento.saida.close()
            } catch (exception: IOException) {
                Toast.makeText(this, "Não foi possível fechar o anexo anterior.", Toast.LENGTH_LONG).show()
            } finally {
                recebimento.arquivo.delete()
                anexoRecebido = null
            }
        }
    }

    private fun enviarAnexo(uri: Uri, tipo: String, origemTemporaria: File? = null) {
        if (modoAtual != Modo.BLUETOOTH || !btHelper.conectado()) {
            origemTemporaria?.delete()
            Toast.makeText(this, "Conecte-se por Bluetooth antes de enviar arquivos.", Toast.LENGTH_LONG).show()
            return
        }
        definirEnvioAtivo(true)
        Thread({
            var arquivo: File? = null
            var erro: String? = null
            try {
                val prefixoMime = prefixoMime(tipo)
                val mimeType = contentResolver.getType(uri)
                    ?: if (tipo == "imagem") "image/*" else if (tipo == "video") "video/*" else "audio/*"
                if (!mimeType.startsWith(prefixoMime)) {
                    throw IllegalArgumentException("Formato de arquivo não compatível.")
                }
                val nome = nomeDoArquivo(uri)
                arquivo = copiarParaAnexo(uri)
                val tamanho = arquivo.length()
                val id = java.util.UUID.randomUUID().toString()
                val metadados = JSONObject()
                    .put("id", id)
                    .put("tipo", tipo)
                    .put("mimeType", mimeType)
                    .put("nome", nome)
                val inicio = BluetoothHelper.PREFIXO_ANEXO_INICIO + Base64.encodeToString(
                    metadados.toString().toByteArray(StandardCharsets.UTF_8),
                    Base64.NO_WRAP
                )
                runOnUiThread {
                    definirEnvioAtivo(true)
                    if (tamanho > LIMIAR_ANIMACAO_BYTES) mostrarProgressoTransferencia(tamanho)
                }
                val inicioEnvio = SystemClock.elapsedRealtime()
                var ultimaAtualizacao = inicioEnvio
                val enviado = FileInputStream(arquivo).use { entrada ->
                    btHelper.enviarArquivo(inicio, id, entrada) { enviados ->
                        if (tamanho > LIMIAR_ANIMACAO_BYTES) {
                            val agora = SystemClock.elapsedRealtime()
                            if (agora - ultimaAtualizacao >= INTERVALO_ATUALIZACAO_MS || enviados == tamanho) {
                                ultimaAtualizacao = agora
                                atualizarProgressoTransferencia(enviados, tamanho, inicioEnvio)
                            }
                        }
                    }
                }
                if (enviado) {
                    val caminhoArquivo = arquivo.absolutePath
                    runOnUiThread {
                        adicionarMensagem(
                            textoAnexo(tipo, nome),
                            "Você",
                            ehMinha = true,
                            tipo = tipo,
                            arquivo = caminhoArquivo,
                            mimeType = mimeType,
                            nomeArquivo = nome
                        )
                        registrarComando("enviar $tipo por Bluetooth")
                    }
                    arquivo = null
                } else {
                    erro = "A conexão foi interrompida durante o envio do arquivo."
                }
            } catch (exception: IOException) {
                erro = "Não foi possível ler ou salvar o arquivo selecionado."
            } catch (exception: IllegalArgumentException) {
                erro = exception.message ?: "Arquivo inválido."
            } catch (exception: org.json.JSONException) {
                erro = "Não foi possível preparar o arquivo para envio."
            } catch (exception: SecurityException) {
                erro = "O app não tem acesso ao arquivo selecionado."
            } finally {
                arquivo?.delete()
                origemTemporaria?.delete()
                runOnUiThread {
                    ocultarProgressoTransferencia()
                    definirEnvioAtivo(false)
                }
            }
            erro?.let { mensagemErro ->
                runOnUiThread {
                    Toast.makeText(this, mensagemErro, Toast.LENGTH_LONG).show()
                    if (modoAtual == Modo.BLUETOOTH) {
                        atualizarStatusBluetooth(
                            EstadoBluetooth.DESCONECTADO,
                            "● Falha no envio. Verifique a conexão."
                        )
                    }
                }
            }
        }, "envio-anexo").start()
    }

    private fun solicitarOuIniciarGravacaoAudio() {
        if (modoAtual != Modo.BLUETOOTH || !btHelper.conectado()) {
            Toast.makeText(this, "Conecte-se por Bluetooth antes de gravar áudio.", Toast.LENGTH_LONG).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            iniciarGravacaoAudio()
        } else {
            permissaoGravacaoAudio.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun iniciarGravacaoAudio() {
        val diretorio = File(filesDir, DIRETORIO_ANEXOS)
        if (!diretorio.exists() && !diretorio.mkdirs()) {
            Toast.makeText(this, "Não foi possível preparar o armazenamento de áudio.", Toast.LENGTH_LONG).show()
            return
        }
        val arquivo = try {
            File.createTempFile("gravacao-", ".m4a", diretorio)
        } catch (exception: IOException) {
            Toast.makeText(this, "Não foi possível criar o arquivo de áudio.", Toast.LENGTH_LONG).show()
            return
        }
        val gravador = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            gravador.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(96_000)
                setOutputFile(arquivo.absolutePath)
                prepare()
                start()
            }
            gravadorAudio = gravador
            arquivoAudioGravado = arquivo
            findViewById<Button>(R.id.btnGravarAudio).text = "Parar gravação"
            registrarComando("gravar áudio")
        } catch (exception: IOException) {
            gravador.release()
            arquivo.delete()
            Toast.makeText(this, "Não foi possível iniciar a gravação.", Toast.LENGTH_LONG).show()
        } catch (exception: IllegalStateException) {
            gravador.release()
            arquivo.delete()
            Toast.makeText(this, "O gravador de áudio não está disponível.", Toast.LENGTH_LONG).show()
        } catch (exception: RuntimeException) {
            gravador.release()
            arquivo.delete()
            Toast.makeText(this, "Não foi possível configurar a gravação de áudio.", Toast.LENGTH_LONG).show()
        }
    }

    private fun pararGravacaoAudio(enviar: Boolean = true) {
        val gravador = gravadorAudio ?: return
        val arquivo = arquivoAudioGravado
        gravadorAudio = null
        arquivoAudioGravado = null
        findViewById<Button>(R.id.btnGravarAudio)?.text = "Gravar áudio"
        val gravacaoValida = try {
            gravador.stop()
            true
        } catch (exception: RuntimeException) {
            false
        } finally {
            gravador.release()
        }
        if (!gravacaoValida || arquivo == null || !arquivo.isFile || arquivo.length() == 0L) {
            arquivo?.delete()
            if (enviar) {
                Toast.makeText(this, "A gravação ficou muito curta ou não pôde ser finalizada.", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (enviar) {
            try {
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", arquivo)
                enviarAnexo(uri, "audio", arquivo)
            } catch (exception: IllegalArgumentException) {
                arquivo.delete()
                Toast.makeText(this, "Não foi possível preparar o áudio gravado.", Toast.LENGTH_LONG).show()
            }
        } else {
            arquivo.delete()
        }
    }

    private fun copiarParaAnexo(uri: Uri): File {
        val entrada = contentResolver.openInputStream(uri)
            ?: throw IOException("Não foi possível abrir o arquivo selecionado.")
        val arquivo = criarArquivoAnexo()
        try {
            entrada.use { origem ->
                FileOutputStream(arquivo).use { destino -> origem.copyTo(destino) }
            }
        } catch (exception: IOException) {
            arquivo.delete()
            throw exception
        }
        return arquivo
    }

    private fun criarArquivoAnexo(): File {
        val diretorio = File(filesDir, DIRETORIO_ANEXOS)
        if (!diretorio.exists() && !diretorio.mkdirs()) {
            throw IOException("Não foi possível criar o armazenamento de anexos.")
        }
        return File.createTempFile("anexo-", ".tmp", diretorio)
    }

    private fun nomeDoArquivo(uri: Uri): String {
        val nome = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        return nome?.takeIf { it.isNotBlank() }
            ?: when {
                contentResolver.getType(uri)?.startsWith("image/") == true -> "imagem"
                contentResolver.getType(uri)?.startsWith("video/") == true -> "video"
                else -> "audio"
            }
    }

    private fun prefixoMime(tipo: String): String = when (tipo) {
        "imagem" -> "image/"
        "audio" -> "audio/"
        "video" -> "video/"
        else -> throw IllegalArgumentException("Tipo de arquivo não compatível.")
    }

    private fun tirarFoto() {
        if (modoAtual != Modo.BLUETOOTH || !btHelper.conectado()) {
            Toast.makeText(this, "Conecte-se por Bluetooth antes de tirar uma foto para enviar.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val diretorio = File(filesDir, DIRETORIO_ANEXOS)
            if (!diretorio.exists() && !diretorio.mkdirs()) {
                throw IOException("Não foi possível preparar o armazenamento da foto.")
            }
            val arquivo = File.createTempFile("foto-", ".jpg", diretorio)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", arquivo)
            arquivoCapturaPendente = arquivo
            uriCapturaPendente = uri
            capturarImagem.launch(uri)
        } catch (exception: IOException) {
            Toast.makeText(this, "Não foi possível preparar a captura da foto.", Toast.LENGTH_LONG).show()
        } catch (exception: IllegalArgumentException) {
            Toast.makeText(this, "Não foi possível iniciar a câmera.", Toast.LENGTH_LONG).show()
        }
    }

    private fun definirEnvioAtivo(ativo: Boolean) {
        if (!chatVisivel) return
        btnEnviar.isEnabled = !ativo && modoAtual == Modo.BLUETOOTH
        findViewById<Button>(R.id.btnEnviarImagem).isEnabled = !ativo
        findViewById<Button>(R.id.btnEnviarAudio).isEnabled = !ativo
        findViewById<Button>(R.id.btnEnviarVideo).isEnabled = !ativo
        findViewById<Button>(R.id.btnTirarFoto).isEnabled = !ativo
        findViewById<Button>(R.id.btnGravarAudio).isEnabled = !ativo
    }

    private fun mostrarProgressoTransferencia(tamanhoTotal: Long) {
        if (!chatVisivel || isFinishing) return
        val conteudo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(24, 20, 24, 12)
        }
        conteudo.addView(ProgressBar(this).apply { isIndeterminate = true })
        conteudo.addView(TextView(this).apply {
            id = ID_TEXTO_TRANSFERENCIA
            text = "Enviando ${formatarTamanho(tamanhoTotal)}..."
            setPadding(20, 0, 0, 0)
        })
        dialogoTransferencia = AlertDialog.Builder(this)
            .setTitle("Enviando arquivo")
            .setView(conteudo)
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    private fun atualizarProgressoTransferencia(enviados: Long, total: Long, inicio: Long) {
        runOnUiThread {
            val dialogo = dialogoTransferencia ?: return@runOnUiThread
            val decorridoMs = (SystemClock.elapsedRealtime() - inicio).coerceAtLeast(1)
            val kbps = enviados * 8.0 / decorridoMs
            dialogo.findViewById<TextView>(ID_TEXTO_TRANSFERENCIA)?.text =
                "${formatarTamanho(enviados)} / ${formatarTamanho(total)} · ${DecimalFormat("0.##").format(kbps)} kbps"
        }
    }

    private fun ocultarProgressoTransferencia() {
        dialogoTransferencia?.dismiss()
        dialogoTransferencia = null
    }

    private fun formatarTamanho(bytes: Long): String {
        val unidades = arrayOf("B", "KB", "MB", "GB", "TB")
        var tamanho = bytes.toDouble()
        var unidade = 0
        while (tamanho >= 1024 && unidade < unidades.lastIndex) {
            tamanho /= 1024
            unidade++
        }
        return "${DecimalFormat("0.##").format(tamanho)} ${unidades[unidade]}"
    }

    private fun salvarAnexo(bytes: ByteArray, mimeType: String): File {
        val diretorio = File(filesDir, DIRETORIO_ANEXOS)
        if (!diretorio.exists() && !diretorio.mkdirs()) {
            throw IOException("Não foi possível criar o armazenamento de anexos.")
        }
        val extensao = android.webkit.MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(mimeType)
            ?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
            ?: "bin"
        val arquivo = File(diretorio, "${java.util.UUID.randomUUID()}.$extensao")
        FileOutputStream(arquivo).use { it.write(bytes) }
        return arquivo
    }

    private fun textoAnexo(tipo: String, nome: String): String =
        "${when (tipo) {
            "imagem" -> "Imagem"
            "video" -> "Vídeo"
            else -> "Áudio"
        }}: $nome"

    private fun abrirAnexo(mensagem: Mensagem) {
        val caminho = mensagem.arquivo
        if (caminho.isNullOrBlank() || !File(caminho).isFile) {
            Toast.makeText(this, "Este anexo não está mais disponível.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(caminho))
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mensagem.mimeType ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (exception: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "Nenhum aplicativo pode abrir este arquivo.", Toast.LENGTH_LONG).show()
        } catch (exception: IllegalArgumentException) {
            Toast.makeText(this, "Não foi possível abrir este anexo.", Toast.LENGTH_LONG).show()
        }
    }

    private fun criarCanalNotificacoes() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = android.app.NotificationChannel(
                CANAL_NOTIFICACOES,
                "Mensagens recebidas",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(canal)
        }
    }

    private fun solicitarPermissaoNotificacoes() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !solicitouPermissaoNotificacoes &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            solicitouPermissaoNotificacoes = true
            permissaoNotificacoes.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun notificarMensagem(remetente: String, texto: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendente = android.app.PendingIntent.getActivity(
            this,
            0,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notificacao = NotificationCompat.Builder(this, CANAL_NOTIFICACOES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Nova mensagem de $remetente")
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setContentIntent(pendente)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(this).notify(ID_NOTIFICACAO, notificacao)
    }

    private fun iniciarBtServidor() {
        val adaptador = BluetoothAdapter.getDefaultAdapter()
        if (adaptador == null) {
            btnEnviar.isEnabled = false
            atualizarStatusBluetooth(EstadoBluetooth.DESCONECTADO, "Este aparelho não possui Bluetooth.")
            return
        }
        if (!adaptador.isEnabled) {
            atualizarStatusBluetooth(EstadoBluetooth.DESCONECTADO, "Desconectado: Bluetooth desativado")
            habilitarBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        btHelper.iniciarServidor("ComunicadorLocal")
        btnEnviar.isEnabled = btHelper.conectado()
        if (btHelper.conectado()) {
            atualizarStatusBluetooth(EstadoBluetooth.CONECTADO, "● Conectado: ${btHelper.nomeConectado()}")
        } else if (alvoDesconectado) {
            atualizarStatusBluetooth(
                EstadoBluetooth.DESCONECTADO,
                "● Desconectado: ${aparelhoSelecionado ?: "aparelho Bluetooth"}"
            )
        } else {
            atualizarStatusBluetooth(EstadoBluetooth.ESPERANDO, "● Conectado: aguardando aparelho...")
        }
        registrarComando("Bluetooth disponível para receber conexão")
    }

    private fun procurarBt() {
        val adaptador = BluetoothAdapter.getDefaultAdapter()
        if (adaptador == null || !adaptador.isEnabled) {
            aparelhoSelecionado = "Bluetooth"
            alvoDesconectado = true
            atualizarStatusBluetooth(EstadoBluetooth.DESCONECTADO, "Desconectado: Bluetooth")
            return
        }
        val dispositivos = adaptador.bondedDevices?.toList().orEmpty()
        if (dispositivos.isEmpty()) {
            aparelhoSelecionado = null
            alvoDesconectado = false
            atualizarStatusBluetooth(
                EstadoBluetooth.DESCONECTADO,
                "Nenhum pareado. Pareie o aparelho nas configurações do Android."
            )
            return
        }
        registrarComando("procurar aparelhos Bluetooth pareados")
        AlertDialog.Builder(this)
            .setTitle("Aparelhos pareados")
            .setItems(dispositivos.map { it.name ?: it.address }.toTypedArray()) { _, indice ->
                aparelhoSelecionado = dispositivos[indice].name?.takeIf { it.isNotBlank() }
                    ?: dispositivos[indice].address
                alvoDesconectado = false
                atualizarStatusBluetooth(
                    EstadoBluetooth.CONECTANDO,
                    "● Conectando ou ocupado: $aparelhoSelecionado..."
                )
                btHelper.conectar(dispositivos[indice])
            }
            .show()
    }

    private fun iniciarWifiServidor() {
        tvStatus.text = "Criando sala Wi-Fi Direct..."
        wifiHelper.iniciarServidor()
    }

    private fun procurarWifi() {
        tvStatus.text = "Procurando aparelhos próximos..."
        wifiHelper.procurarDispositivos()
    }

    override fun dispositivosEncontrados(dispositivos: List<WifiP2pDevice>) {
        if (!chatVisivel || modoAtual != Modo.WIFI_DIRECT) return
        if (dispositivos.isEmpty()) {
            tvStatus.text = "Nenhum aparelho encontrado. Tente procurar novamente."
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Aparelhos Wi-Fi Direct")
            .setItems(dispositivos.map { it.deviceName.ifBlank { it.deviceAddress } }.toTypedArray()) { _, indice ->
                tvStatus.text = "Conectando via Wi-Fi Direct..."
                wifiHelper.conectar(dispositivos[indice])
            }
            .show()
    }

    override fun conectado(nome: String) {
        if (!chatVisivel || modoAtual != Modo.WIFI_DIRECT) return
        tvStatus.text = "Conectado via Wi-Fi Direct com $nome."
        btnEnviar.isEnabled = true
    }

    override fun mensagemRecebida(texto: String, remetente: String) {
        if (chatVisivel && modoAtual == Modo.WIFI_DIRECT) {
            adicionarMensagem(texto, remetente, ehMinha = false)
        }
    }

    override fun erro(mensagem: String) {
        if (chatVisivel && modoAtual == Modo.WIFI_DIRECT) tvStatus.text = mensagem
    }

    override fun status(mensagem: String) {
        if (chatVisivel && modoAtual == Modo.WIFI_DIRECT) tvStatus.text = mensagem
    }

    private fun enviarMensagem() {
        val texto = edMensagem.text.toString().trim()
        if (texto.isEmpty()) return

        if (modoAtual == Modo.BLUETOOTH) {
            if (btHelper.enviarMensagem(texto)) {
                adicionarMensagem(texto, "Você", ehMinha = true)
                edMensagem.text.clear()
                registrarComando("enviar mensagem Bluetooth")
            } else {
                val mensagem = Mensagem(
                    remetente = "Você",
                    texto = texto,
                    ehMinha = true,
                    pendente = true
                )
                mensagens.add(mensagem)
                mensagensPendentes.add(mensagem)
                salvarMensagens()
                salvarMensagensPendentes()
                adaptadorConversa.notifyDataSetChanged()
                listaConversa.setSelection(adaptadorConversa.count - 1)
                edMensagem.text.clear()
                atualizarStatusBluetooth(EstadoBluetooth.DESCONECTADO, "Desconectado.")
                registrarComando("guardar mensagem na fila Bluetooth")
            }
            return
        }

        val sucesso = when (modoAtual) {
            Modo.BLUETOOTH -> false
            Modo.WIFI_DIRECT -> wifiHelper.enviarMensagem(texto)
            Modo.NENHUM -> false
        }
        if (sucesso) {
            adicionarMensagem(texto, "Você", ehMinha = true)
            edMensagem.text.clear()
            registrarComando("enviar mensagem")
        } else {
            if (modoAtual == Modo.BLUETOOTH) {
                atualizarStatusBluetooth(EstadoBluetooth.DESCONECTADO, "Falha ao enviar. Verifique a conexão.")
            } else {
                tvStatus.text = "Falha ao enviar. Verifique a conexão."
            }
        }
    }

    private fun enviarPendentesBluetooth() {
        val iterador = mensagensPendentes.iterator()
        while (iterador.hasNext()) {
            val mensagem = iterador.next()
            if (!btHelper.enviarMensagem(mensagem.texto)) {
                atualizarStatusBluetooth(EstadoBluetooth.DESCONECTADO, "Desconectado.")
                registrarComando("fila Bluetooth mantida para tentar novamente")
                Handler(Looper.getMainLooper()).postDelayed({
                    if (chatVisivel && modoAtual == Modo.BLUETOOTH) iniciarBtServidor()
                }, 500)
                return
            }
            mensagem.pendente = false
            iterador.remove()
            salvarMensagens()
            salvarMensagensPendentes()
        }
        adaptadorConversa.notifyDataSetChanged()
        if (mensagensPendentes.isEmpty()) registrarComando("enviar mensagens pendentes")
    }

    private fun carregarMensagensPendentes() {
        val dados = getSharedPreferences(PREFERENCIAS_PENDENTES, MODE_PRIVATE)
            .getString(CHAVE_PENDENTES, null) ?: return
        try {
            val array = JSONArray(dados)
            for (indice in 0 until array.length()) {
                val item = array.getJSONObject(indice)
                mensagensPendentes.add(
                    Mensagem(
                        id = item.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                        remetente = item.getString("remetente"),
                        texto = item.getString("texto"),
                        ehMinha = true,
                        favorita = item.optBoolean("favorita"),
                        pendente = true,
                        hora = item.getString("hora"),
                        tipo = item.optString("tipo", "texto"),
                        arquivo = item.optString("arquivo").takeIf { it.isNotBlank() },
                        mimeType = item.optString("mimeType").takeIf { it.isNotBlank() },
                        nomeArquivo = item.optString("nomeArquivo").takeIf { it.isNotBlank() }
                    )
                )
            }
        } catch (_: org.json.JSONException) {
            Toast.makeText(this, "Não foi possível recuperar a fila de mensagens.", Toast.LENGTH_LONG).show()
        }
    }

    private fun salvarMensagensPendentes() {
        val array = JSONArray()
        mensagensPendentes.forEach { mensagem ->
            array.put(
                JSONObject()
                    .put("id", mensagem.id)
                    .put("remetente", mensagem.remetente)
                    .put("texto", mensagem.texto)
                    .put("favorita", mensagem.favorita)
                    .put("hora", mensagem.hora)
                    .put("tipo", mensagem.tipo)
                    .put("arquivo", mensagem.arquivo)
                    .put("mimeType", mensagem.mimeType)
                    .put("nomeArquivo", mensagem.nomeArquivo)
            )
        }
        getSharedPreferences(PREFERENCIAS_PENDENTES, MODE_PRIVATE)
            .edit()
            .putString(CHAVE_PENDENTES, array.toString())
            .apply()
    }

    private fun carregarMensagens() {
        val dados = getSharedPreferences(PREFERENCIAS_CONVERSA, MODE_PRIVATE)
            .getString(CHAVE_MENSAGENS, null) ?: return
        try {
            val array = JSONArray(dados)
            for (indice in 0 until array.length()) {
                val item = array.getJSONObject(indice)
                val mensagem = Mensagem(
                    id = item.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                    remetente = item.getString("remetente"),
                    texto = item.getString("texto"),
                    ehMinha = item.getBoolean("ehMinha"),
                    favorita = item.optBoolean("favorita"),
                    pendente = item.optBoolean("pendente"),
                    hora = item.getString("hora"),
                    tipo = item.optString("tipo", "texto"),
                    arquivo = item.optString("arquivo").takeIf { it.isNotBlank() },
                    mimeType = item.optString("mimeType").takeIf { it.isNotBlank() },
                    nomeArquivo = item.optString("nomeArquivo").takeIf { it.isNotBlank() }
                )
                mensagem.favorita = mensagem.favorita || favoritos().contains(chaveFavorita(mensagem))
                mensagens.add(mensagem)
            }
        } catch (_: org.json.JSONException) {
            Toast.makeText(this, "Não foi possível recuperar as mensagens salvas.", Toast.LENGTH_LONG).show()
        }
    }

    private fun salvarMensagens() {
        val array = JSONArray()
        mensagens.forEach { array.put(mensagemComoJson(it)) }
        getSharedPreferences(PREFERENCIAS_CONVERSA, MODE_PRIVATE)
            .edit()
            .putString(CHAVE_MENSAGENS, array.toString())
            .apply()
    }

    private fun mensagemComoJson(mensagem: Mensagem): JSONObject =
        JSONObject()
            .put("id", mensagem.id)
            .put("hora", mensagem.hora)
            .put("remetente", mensagem.remetente)
            .put("texto", mensagem.texto)
            .put("ehMinha", mensagem.ehMinha)
            .put("favorita", mensagem.favorita)
            .put("pendente", mensagem.pendente)
            .put("tipo", mensagem.tipo)
            .put("arquivo", mensagem.arquivo)
            .put("mimeType", mensagem.mimeType)
            .put("nomeArquivo", mensagem.nomeArquivo)

    private fun atualizarStatusBluetooth(estado: EstadoBluetooth, texto: String) {
        if (!::tvStatus.isInitialized) return
        tvStatus.text = texto
        val (fundo, frente) = when (estado) {
            EstadoBluetooth.CONECTADO -> Color.rgb(220, 237, 200) to Color.rgb(46, 125, 50)
            EstadoBluetooth.ESPERANDO -> Color.rgb(220, 237, 200) to Color.rgb(46, 125, 50)
            EstadoBluetooth.CONECTANDO -> Color.rgb(255, 243, 205) to Color.rgb(154, 103, 0)
            EstadoBluetooth.DESCONECTADO -> Color.rgb(255, 235, 238) to Color.rgb(198, 40, 40)
        }
        tvStatus.setBackgroundColor(fundo)
        tvStatus.setTextColor(frente)
    }

    private fun registrarComando(comando: String) {
        if (::tvComando.isInitialized) tvComando.text = "Comando executado: $comando."
    }

    private fun limparMensagens() {
        mensagens.clear()
        mensagensPendentes.clear()
        salvarMensagens()
        salvarMensagensPendentes()
        mensagemSelecionada = -1
        adaptadorConversa.notifyDataSetChanged()
        registrarComando("limpar mensagens da conversa atual")
    }

    private fun arquivarMensagens() {
        if (mensagens.isEmpty()) {
            registrarComando("arquivar conversa — não há mensagens")
            mostrarArquivadas()
            return
        }
        try {
            val arquivo = JSONObject()
                .put("id", java.util.UUID.randomUUID().toString())
                .put("arquivadaEm", System.currentTimeMillis())
                .put("modo", modoAtual.name)
            val lista = JSONArray()
            mensagens.forEach { mensagem ->
                lista.put(mensagemComoJson(mensagem))
            }
            arquivo.put("mensagens", lista)
            OutputStreamWriter(
                openFileOutput(ARQUIVO_ARQUIVO, MODE_APPEND),
                StandardCharsets.UTF_8
            ).buffered().use {
                it.append(arquivo.toString())
                it.newLine()
            }
            salvarMensagens()
            registrarComando("arquivar ${mensagens.size} mensagem(ns) no telefone; conversa atual mantida")
            mostrarArquivadas()
        } catch (exception: IOException) {
            registrarComando("falha ao arquivar mensagens")
            Toast.makeText(this, "Não foi possível arquivar as mensagens.", Toast.LENGTH_LONG).show()
        }
    }

    private fun mostrarArquivadas() {
        val arquivos = try {
            openFileInput(ARQUIVO_ARQUIVO).bufferedReader(StandardCharsets.UTF_8).useLines { linhas ->
                linhas.filter { it.isNotBlank() }.map { JSONObject(it) }.toList()
            }
        } catch (_: java.io.FileNotFoundException) {
            emptyList()
        } catch (_: org.json.JSONException) {
            Toast.makeText(this, "Não foi possível ler a lista de arquivos.", Toast.LENGTH_LONG).show()
            return
        } catch (exception: IOException) {
            Toast.makeText(this, "Erro ao abrir conversas arquivadas.", Toast.LENGTH_LONG).show()
            return
        }

        if (arquivos.isEmpty()) {
            registrarComando("abrir arquivos — nenhum arquivo salvo")
            AlertDialog.Builder(this)
                .setTitle("Conversas arquivadas")
                .setMessage("Ainda não há conversas arquivadas neste telefone.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val formato = SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", Locale.getDefault())
        val ordenados = arquivos.sortedByDescending { it.optLong("arquivadaEm") }
        val titulos = ordenados.mapIndexed { indice, arquivo ->
            val data = formato.format(Date(arquivo.optLong("arquivadaEm")))
            val quantidade = arquivo.optJSONArray("mensagens")?.length() ?: 0
            "${indice + 1}. $data — $quantidade mensagem(ns)"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Conversas arquivadas")
            .setItems(titulos) { _, indice -> mostrarConteudoArquivo(ordenados[indice], formato) }
            .setNegativeButton("Fechar", null)
            .show()
        registrarComando("mostrar ${ordenados.size} conversa(s) arquivada(s)")
    }

    private fun mostrarConteudoArquivo(arquivo: JSONObject, formato: SimpleDateFormat) {
        val mensagensArquivo = arquivo.optJSONArray("mensagens") ?: JSONArray()
        val dataArquivo = formato.format(Date(arquivo.optLong("arquivadaEm")))
        val conteudo = buildString {
            append("Arquivada em ")
            append(dataArquivo)
            append("\n\n")
            for (indice in 0 until mensagensArquivo.length()) {
                val item = mensagensArquivo.optJSONObject(indice) ?: continue
                append(item.optString("hora"))
                append(" — ")
                append(item.optString("remetente"))
                if (item.optBoolean("favorita")) append(" ★")
                append('\n')
                append(item.optString("texto"))
                if (item.optBoolean("pendente")) append("\n(Pendente de envio)")
                append("\n\n")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Conversa de $dataArquivo")
            .setMessage(conteudo.trim())
            .setPositiveButton("Voltar") { _, _ -> mostrarArquivadas() }
            .setNegativeButton("Excluir arquivo") { _, _ -> confirmarExclusaoArquivo(arquivo) }
            .show()
        registrarComando("ler conversa arquivada de $dataArquivo")
    }

    private fun confirmarExclusaoArquivo(arquivo: JSONObject) {
        AlertDialog.Builder(this)
            .setTitle("Excluir conversa arquivada?")
            .setMessage("Esta ação apaga apenas a cópia arquivada. As mensagens da conversa atual serão mantidas.")
            .setNegativeButton("Cancelar") { _, _ -> mostrarArquivadas() }
            .setPositiveButton("Excluir") { _, _ ->
                excluirArquivo(arquivo)
                mostrarArquivadas()
            }
            .show()
    }

    private fun excluirArquivo(arquivo: JSONObject) {
        try {
            val arquivos = openFileInput(ARQUIVO_ARQUIVO).bufferedReader(StandardCharsets.UTF_8).useLines { linhas ->
                linhas.filter { it.isNotBlank() }.map { JSONObject(it) }
                .filterNot {
                    val idArquivo = arquivo.optString("id")
                    if (idArquivo.isNotBlank()) it.optString("id") == idArquivo
                    else it.optLong("arquivadaEm") == arquivo.optLong("arquivadaEm")
                }
                    .toList()
            }
            OutputStreamWriter(
                openFileOutput(ARQUIVO_ARQUIVO, MODE_PRIVATE),
                StandardCharsets.UTF_8
            ).buffered().use { escritor ->
                arquivos.forEach {
                    escritor.append(it.toString())
                    escritor.newLine()
                }
            }
            registrarComando("excluir cópia arquivada")
        } catch (exception: IOException) {
            Toast.makeText(this, "Não foi possível excluir o arquivo.", Toast.LENGTH_LONG).show()
        } catch (_: org.json.JSONException) {
            Toast.makeText(this, "Não foi possível atualizar a lista de arquivos.", Toast.LENGTH_LONG).show()
        }
    }

    private fun alternarFavorita() {
        val mensagem = mensagens.getOrNull(mensagemSelecionada)
        if (mensagem == null) {
            registrarComando("favoritar mensagem — selecione uma mensagem primeiro")
            Toast.makeText(this, "Toque em uma mensagem para selecioná-la.", Toast.LENGTH_SHORT).show()
            return
        }
        val favoritas = favoritos()
        val chave = chaveFavorita(mensagem)
        mensagem.favorita = if (favoritas.contains(chave)) {
            favoritas.remove(chave)
            false
        } else {
            favoritas.add(chave)
            true
        }
        getSharedPreferences(PREFERENCIAS_FAVORITAS, MODE_PRIVATE)
            .edit()
            .putStringSet(CHAVE_FAVORITAS, favoritas)
            .apply()
        salvarMensagens()
        adaptadorConversa.notifyDataSetChanged()
        registrarComando(
            if (mensagem.favorita) "favoritar mensagem ${mensagemSelecionada + 1}"
            else "remover dos favoritos a mensagem ${mensagemSelecionada + 1}"
        )
    }

    private fun favoritos(): MutableSet<String> =
        getSharedPreferences(PREFERENCIAS_FAVORITAS, MODE_PRIVATE)
            .getStringSet(CHAVE_FAVORITAS, emptySet())
            .orEmpty()
            .toMutableSet()

    private fun chaveFavorita(mensagem: Mensagem): String =
        "${mensagem.hora}\u001f${mensagem.remetente}\u001f${mensagem.texto}"

    override fun onDestroy() {
        chatVisivel = false
        ocultarProgressoTransferencia()
        descartarAnexoRecebido()
        pararGravacaoAudio(enviar = false)
        btHelper.pararTudo()
        wifiHelper.encerrar()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_PERMISSOES = 100
        private const val ARQUIVO_ARQUIVO = "conversas-arquivadas.jsonl"
        private const val PREFERENCIAS_FAVORITAS = "mensagens_favoritas"
        private const val CHAVE_FAVORITAS = "itens"
        private const val PREFERENCIAS_PENDENTES = "mensagens_pendentes"
        private const val CHAVE_PENDENTES = "itens"
        private const val PREFERENCIAS_CONVERSA = "conversa_atual"
        private const val CHAVE_MENSAGENS = "mensagens"
        private const val PREFIXO_ANEXO = "CML1_MEDIA|"
        private const val DIRETORIO_ANEXOS = "message-media"
        private const val CANAL_NOTIFICACOES = "mensagens-recebidas"
        private const val ID_NOTIFICACAO = 1001
        private const val ID_TEXTO_TRANSFERENCIA = 1002
        private const val LIMIAR_ANIMACAO_BYTES = 1024 * 1024L
        private const val INTERVALO_ATUALIZACAO_MS = 250L
    }
}
