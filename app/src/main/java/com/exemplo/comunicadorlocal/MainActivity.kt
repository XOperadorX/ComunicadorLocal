package com.exemplo.comunicadorlocal

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.*
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.exemplo.comunicadorlocal.R   // ← ESSA LINHA NÃO PODE FALTAR!

class MainActivity : AppCompatActivity(), WifiDirectHelper.EventosWifiDirect {
    // ... resto do código continua igual

    private lateinit var btHelper: BluetoothHelper
    private lateinit var wifiHelper: WifiDirectHelper
    private lateinit var adaptadorConversa: ArrayAdapter<Mensagem>
    private val mensagens = mutableListOf<Mensagem>()
    
    private lateinit var edMensagem: EditText
    private lateinit var btnEnviar: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvModo: TextView
    private lateinit var listaConversa: ListView
    private var modoAtual = Modo.COM_DESCONHECIDO

    enum class Modo { COM_BLUETOOTH, COM_WIFIDIRECT, COM_DESCONHECIDO }

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                BluetoothHelper.MENSAGEM_RECEBIDA -> {
                    val texto = msg.obj.toString()
                    adicionarMensagem(texto, "Pessoa próxima", ehMinha = false)
                }
                BluetoothHelper.STATUS_CONECTADO -> {
                    tvStatus.text = "✅ Conectado via Bluetooth!"
                    tvModo.text = "Modo: Bluetooth (~10m)"
                    modoAtual = Modo.COM_BLUETOOTH
                    btnEnviar.isEnabled = true
                }
                BluetoothHelper.STATUS_ERRO -> {
                    tvStatus.text = "❌ ${msg.obj}"
                }
            }
        }
    }

    private val resultadoBuscaWifi = WifiP2pManager.PeerListListener { lista ->
        runOnUiThread {
            val dispositivos = lista.deviceList
            if (dispositivos.isEmpty()) {
                Toast.makeText(this, "Nenhum aparelho encontrado", Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }
            val nomes = dispositivos.map { it.deviceName }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("📶 Aparelhos encontrados")
                .setItems(nomes) { _, i ->
                    val escolhido = dispositivos.elementAt(i)
                    tvStatus.text = "🔗 Conectando com ${escolhido.deviceName}..."
                    wifiHelper.conectar(escolhido)
                }
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        inicializarComponentes()
        wifiHelper = WifiDirectHelper(this, Handler(Looper.getMainLooper()), this)
        wifiHelper.inicializar()
        verificarPermissoes()
    }

    private fun inicializarComponentes() {
        edMensagem = findViewById(R.id.edMensagem)
        btnEnviar = findViewById(R.id.btnEnviar)
        tvStatus = findViewById(R.id.tvStatus)
        tvModo = findViewById(R.id.tvModo)
        listaConversa = findViewById(R.id.listaConversa)

        adaptadorConversa = object : ArrayAdapter<Mensagem>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, mensagens
        ) {
            override fun getView(posicao: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(posicao, convertView, parent)
                val m = getItem(posicao)!!
                v.findViewById<TextView>(android.R.id.text1).text =
                    "${m.hora} — ${if (m.ehMinha) "Você" else m.remetente}"
                v.findViewById<TextView>(android.R.id.text2).text = m.texto
                return v
            }
        }
        listaConversa.adapter = adaptadorConversa

        btHelper = BluetoothHelper(this, handler)
        btnEnviar.isEnabled = false

        btnEnviar.setOnClickListener { enviarMensagem() }
        findViewById<Button>(R.id.btnBtDisponivel).setOnClickListener { iniciarBtServidor() }
        findViewById<Button>(R.id.btnBtProcurar).setOnClickListener { procurarBt() }
        findViewById<Button>(R.id.btnWifiDisponivel).setOnClickListener { iniciarWifiServidor() }
        findViewById<Button>(R.id.btnWifiProcurar).setOnClickListener { procurarWifi() }
    }

    private fun adicionarMensagem(texto: String, remetente: String, ehMinha: Boolean) {
        mensagens.add(Mensagem(remetente = remetente, texto = texto, ehMinha = ehMinha))
        adaptadorConversa.notifyDataSetChanged()
        listaConversa.setSelection(adaptadorConversa.count - 1)
    }

    private fun verificarPermissoes() {
        val precisa = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.CHANGE_NETWORK_STATE,
        ).filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (precisa.isNotEmpty()) ActivityCompat.requestPermissions(this, precisa.toTypedArray(), 100)
    }

    private fun iniciarBtServidor() {
        val adaptador = BluetoothAdapter.getDefaultAdapter()
        if (adaptador == null) {
            Toast.makeText(this, "Sem Bluetooth!", Toast.LENGTH_SHORT).show()
            return
        }
        if (!adaptador.isEnabled) {
            Toast.makeText(this, "Ativando Bluetooth...", Toast.LENGTH_SHORT).show()
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        btHelper.iniciarServidor("ComunicadorLocal")
        tvStatus.text = "🔵 Bluetooth: Aguardando conexão..."
        tvModo.text = "Modo: Bluetooth (~10m)"
        modoAtual = Modo.COM_DESCONHECIDO
    }

    private fun procurarBt() {
        val adaptador = BluetoothAdapter.getDefaultAdapter()
        if (adaptador == null || !adaptador.isEnabled) {
            Toast.makeText(this, "Ative o Bluetooth primeiro!", Toast.LENGTH_SHORT).show()
            return
        }
        val dispositivos = adaptador.bondedDevices ?: emptySet()
        if (dispositivos.isEmpty()) {
            Toast.makeText(this, "Nenhum aparelho pareado!", Toast.LENGTH_LONG).show()
            return
        }
        val nomes = dispositivos.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Aparelhos próximos (Bluetooth)")
            .setItems(nomes) { _, i ->
                val escolhido = dispositivos.elementAt(i)
                tvStatus.text = "🔗 Conectando com ${escolhido.name}..."
                btHelper.conectar(escolhido)
            }
            .show()
    }

    private fun iniciarWifiServidor() {
        if (!wifiHelper.estaDisponivel) {
            Toast.makeText(this, "Wi-Fi Direct não disponível", Toast.LENGTH_SHORT).show()
            return
        }
        wifiHelper.iniciarServidor()
        tvStatus.text = "📶 Wi-Fi Direct: Aguardando conexão..."
        tvModo.text = "Modo: Wi-Fi Direct (~100m)"
        modoAtual = Modo.COM_DESCONHECIDO
        Toast.makeText(this, "Pronto! Peça ao outro procurar por você.", Toast.LENGTH_LONG).show()
    }

    private fun procurarWifi() {
        if (!wifiHelper.estaDisponivel) {
            Toast.makeText(this, "Wi-Fi Direct não disponível", Toast.LENGTH_SHORT).show()
            return
        }
        tvStatus.text = "🔍 Procurando aparelhos..."
        wifiHelper.procurarDispositivos()
        wifiHelper.processarResultadoBusca(resultadoBuscaWifi)
    }

    override fun dispositivoEncontrado(disp: WifiP2pDevice) {}

    override fun conectado(nome: String) {
        runOnUiThread {
            tvStatus.text = "✅ Conectado via Wi-Fi Direct!"
            tvModo.text = "Modo: Wi-Fi Direct (~100m)"
            modoAtual = Modo.COM_WIFIDIRECT
            btnEnviar.isEnabled = true
        }
    }

    override fun mensagemRecebida(texto: String, remetente: String) {
        adicionarMensagem(texto, remetente, ehMinha = false)
    }

    override fun erro(mensagem: String) {
        runOnUiThread { tvStatus.text = "❌ $mensagem" }
    }

    private fun enviarMensagem() {
        val texto = edMensagem.text.toString().trim()
        if (texto.isEmpty()) return
        val sucesso = when (modoAtual) {
            Modo.COM_BLUETOOTH -> btHelper.enviarMensagem(texto)
            Modo.COM_WIFIDIRECT -> wifiHelper.enviarMensagem(texto)
            Modo.COM_DESCONHECIDO -> {
                Toast.makeText(this, "Conecte primeiro com alguém!", Toast.LENGTH_SHORT).show()
                return
            }
        }
        if (sucesso) {
            adicionarMensagem(texto, "Você", ehMinha = true)
            edMensagem.text.clear()
        } else {
            Toast.makeText(this, "Falha no envio!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        btHelper.pararTudo()
        wifiHelper.pararTudo()
    }
}