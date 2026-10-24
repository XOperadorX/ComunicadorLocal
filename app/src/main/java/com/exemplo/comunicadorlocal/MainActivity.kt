package com.exemplo.comunicadorlocal

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
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

    private enum class Modo { NENHUM, BLUETOOTH, WIFI_DIRECT }
    private enum class EstadoBluetooth { CONECTADO, ESPERANDO, DESCONECTADO }

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (!chatVisivel || modoAtual != Modo.BLUETOOTH) return
            when (msg.what) {
                BluetoothHelper.MENSAGEM_RECEBIDA ->
                    adicionarMensagem(msg.obj.toString(), btHelper.nomeConectado(), ehMinha = false)
                BluetoothHelper.STATUS_CONECTADO -> {
                    aparelhoSelecionado = msg.obj?.toString()
                    atualizarStatusBluetooth(
                        EstadoBluetooth.CONECTADO,
                        "Conectado: ${aparelhoSelecionado ?: "Aparelho Bluetooth"}"
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
                            "Conectado: ${btHelper.nomeConectado()}"
                        )
                    } else if (alvoDesconectado) {
                        atualizarStatusBluetooth(
                            EstadoBluetooth.DESCONECTADO,
                            "Desconectado: ${aparelhoSelecionado ?: "aparelho Bluetooth"}"
                        )
                    } else {
                        atualizarStatusBluetooth(EstadoBluetooth.ESPERANDO, "Esperando para conectar...")
                    }
                }
                BluetoothHelper.STATUS_DESCONECTADO -> {
                    btnEnviar.isEnabled = true
                    alvoDesconectado = true
                    atualizarStatusBluetooth(
                        EstadoBluetooth.DESCONECTADO,
                        "Desconectado: ${aparelhoSelecionado ?: "aparelho Bluetooth"}"
                    )
                    registrarComando("conexão encerrada; aguardando novamente")
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (chatVisivel && modoAtual == Modo.BLUETOOTH) iniciarBtServidor()
                    }, 500)
                }
                BluetoothHelper.STATUS_ERRO -> {
                    btnEnviar.isEnabled = true
                    alvoDesconectado = true
                    atualizarStatusBluetooth(
                        EstadoBluetooth.DESCONECTADO,
                        "Desconectado: ${aparelhoSelecionado ?: "aparelho Bluetooth"}"
                    )
                    registrarComando("Bluetooth desconectado")
                }
                BluetoothHelper.STATUS_CONEXAO_FALHOU -> {
                    btnEnviar.isEnabled = true
                    alvoDesconectado = true
                    atualizarStatusBluetooth(
                        EstadoBluetooth.DESCONECTADO,
                        "Desconectado: ${aparelhoSelecionado ?: "aparelho Bluetooth"}"
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
        mostrarTelaInicial()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (chatVisivel) voltarParaSelecao()
                else finish()
            }
        })
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
        tvStatus.text = "Conecte-se a outro aparelho para começar."
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
        listaConversa.setOnItemClickListener { _, _, position, _ ->
            mensagemSelecionada = position
            adaptadorConversa.notifyDataSetChanged()
            registrarComando("mensagem ${position + 1} selecionada")
        }

        mensagens.forEach { mensagem ->
            mensagem.favorita = favoritos().contains(chaveFavorita(mensagem))
        }
        adaptadorConversa.notifyDataSetChanged()
        if (mensagens.isNotEmpty()) listaConversa.setSelection(mensagens.lastIndex)

        if (usandoBluetooth) iniciarBtServidor()
    }

    private fun voltarParaSelecao() {
        btHelper.pararTudo()
        wifiHelper.pararTudo()
        alvoDesconectado = false
        aparelhoSelecionado = null
        mostrarTelaInicial()
    }

    private fun adicionarMensagem(texto: String, remetente: String, ehMinha: Boolean) {
        val mensagem = Mensagem(remetente = remetente, texto = texto, ehMinha = ehMinha)
        mensagem.favorita = favoritos().contains(chaveFavorita(mensagem))
        mensagens.add(mensagem)
        salvarMensagens()
        adaptadorConversa.notifyDataSetChanged()
        listaConversa.setSelection(adaptadorConversa.count - 1)
        if (!ehMinha) registrarComando("receber mensagem")
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
            atualizarStatusBluetooth(EstadoBluetooth.CONECTADO, "Conectado: ${btHelper.nomeConectado()}")
        } else if (alvoDesconectado) {
            atualizarStatusBluetooth(
                EstadoBluetooth.DESCONECTADO,
                "Desconectado: ${aparelhoSelecionado ?: "aparelho Bluetooth"}"
            )
        } else {
            atualizarStatusBluetooth(EstadoBluetooth.ESPERANDO, "Esperando para conectar...")
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
                EstadoBluetooth.ESPERANDO,
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
                    EstadoBluetooth.ESPERANDO,
                    "Esperando para conectar: $aparelhoSelecionado..."
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
                        hora = item.getString("hora")
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
                    hora = item.getString("hora")
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

    private fun atualizarStatusBluetooth(estado: EstadoBluetooth, texto: String) {
        if (!::tvStatus.isInitialized) return
        tvStatus.text = texto
        val (fundo, frente) = when (estado) {
            EstadoBluetooth.CONECTADO -> Color.rgb(220, 237, 200) to Color.rgb(46, 125, 50)
            EstadoBluetooth.ESPERANDO -> Color.rgb(255, 243, 205) to Color.rgb(154, 103, 0)
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
    }
}
