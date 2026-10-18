import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

class WifiDirectHelper(
    private val contexto: Context,
    private val handler: Handler,
    private val escutaEventos: EventosWifiDirect
) : WifiP2pManager.ChannelListener {

    interface EventosWifiDirect {
        fun dispositivoEncontrado(disp: WifiP2pDevice)
        fun conectado(nome: String)
        fun mensagemRecebida(texto: String, remetente: String)
        fun erro(mensagem: String)
    }

    private val gerenciador: WifiP2pManager? by lazy {
        contexto.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }
    private var canal: WifiP2pManager.Channel? = null
    private var servidorSocket: ServerSocket? = null
    private var clienteSocket: Socket? = null
    private val PORTA = 8888
    private var threadRecebimento: Thread? = null

    val estaDisponivel: Boolean get() = gerenciador != null

    fun inicializar() {
        canal = gerenciador?.initialize(contexto, contexto.mainLooper, this)
    }

    override fun onChannelDisconnected() {
        escutaEventos.erro("Canal Wi-Fi Direct desconectado")
    }

    fun procurarDispositivos() {
        if (gerenciador == null || canal == null) {
            escutaEventos.erro("Wi-Fi Direct não disponível")
            return
        }
        gerenciador?.discoverPeers(canal, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                escutaEventos.erro("Falha ao procurar: $reason")
            }
        })
    }

    fun processarResultadoBusca(resultado: WifiP2pManager.PeerListListener) {
        gerenciador?.requestPeers(canal, resultado)
    }

    fun conectar(dispositivo: WifiP2pDevice) {
        val config = android.net.wifi.p2p.WifiP2pConfig().apply {
            deviceAddress = dispositivo.deviceAddress
        }
        gerenciador?.connect(canal, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                escutaEventos.conectado(dispositivo.deviceName)
            }
            override fun onFailure(reason: Int) {
                escutaEventos.erro("Falha na conexão: $reason")
            }
        })
    }

    fun iniciarServidor() {
        Thread {
            try {
                servidorSocket = ServerSocket(PORTA)
                while (true) {
                    clienteSocket = servidorSocket!!.accept()
                    iniciarRecebimento(clienteSocket!!)
                }
            } catch (e: Exception) {
                if (e !is InterruptedException) escutaEventos.erro("Servidor encerrado")
            }
        }.start()
    }

    private fun iniciarRecebimento(socket: Socket) {
        threadRecebimento = Thread {
            try {
                val entrada = BufferedReader(InputStreamReader(socket.getInputStream()))
                while (true) {
                    val linha = entrada.readLine() ?: break
                    handler.post {
                        escutaEventos.mensagemRecebida(linha, "Pessoa próxima")
                    }
                }
            } catch (_: Exception) {}
        }
        threadRecebimento?.start()
    }

    fun enviarMensagem(texto: String): Boolean {
        val socket = clienteSocket ?: return false
        return try {
            val saida = PrintWriter(socket.getOutputStream(), true)
            saida.println(texto)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun pararTudo() {
        threadRecebimento?.interrupt()
        clienteSocket?.close()
        servidorSocket?.close()
        gerenciador?.stopPeerDiscovery(canal, null)
        gerenciador?.cancelConnect(canal, null)
    }
}