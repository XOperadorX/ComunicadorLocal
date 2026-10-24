package com.exemplo.comunicadorlocal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

class WifiDirectHelper(
    private val contexto: Context,
    private val handler: Handler,
    private val escutaEventos: EventosWifiDirect
) : WifiP2pManager.ChannelListener {

    interface EventosWifiDirect {
        fun dispositivosEncontrados(dispositivos: List<WifiP2pDevice>) {}
        fun conectado(nome: String) {}
        fun mensagemRecebida(texto: String, remetente: String) {}
        fun erro(mensagem: String) {}
        fun status(mensagem: String) {}
    }

    private val gerenciador: WifiP2pManager? by lazy {
        contexto.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }
    private var canal: WifiP2pManager.Channel? = null
    @Volatile private var servidorSocket: ServerSocket? = null
    @Volatile private var conexaoSocket: Socket? = null
    @Volatile private var escritor: PrintWriter? = null
    @Volatile private var threadServidor: Thread? = null
    @Volatile private var threadCliente: Thread? = null
    private var canalReceptorRegistrado = false
    @Volatile private var servidorIniciado = false
    @Volatile private var clienteIniciado = false
    @Volatile private var buscaAtiva = false
    @Volatile private var grupoSolicitado = false
    private var nomeDispositivo = "Aparelho próximo"

    private val receptor = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (buscaAtiva) atualizarDispositivos()
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> atualizarConexao()
            }
        }
    }

    val estaDisponivel: Boolean get() = gerenciador != null

    fun inicializar() {
        if (!canalReceptorRegistrado) {
            val filtro = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(
                contexto,
                receptor,
                filtro,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            canalReceptorRegistrado = true
        }
        canal = gerenciador?.initialize(contexto, Looper.getMainLooper(), this)
        if (gerenciador == null || canal == null) {
            notificarErro("Wi-Fi Direct não está disponível neste aparelho.")
        }
    }

    override fun onChannelDisconnected() {
        notificarErro("A conexão com o serviço Wi-Fi Direct foi interrompida.")
    }

    fun procurarDispositivos() {
        val manager = gerenciador
        val channel = canal
        if (manager == null || channel == null) {
            notificarErro("Wi-Fi Direct não está disponível.")
            return
        }
        try {
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    buscaAtiva = true
                    notificarStatus("Busca Wi-Fi Direct iniciada...")
                }

                override fun onFailure(reason: Int) {
                    buscaAtiva = false
                    val motivo = when (reason) {
                        WifiP2pManager.ERROR -> "erro interno"
                        WifiP2pManager.P2P_UNSUPPORTED -> "não suportado pelo aparelho"
                        WifiP2pManager.BUSY -> "serviço ocupado; tente novamente"
                        else -> "código $reason"
                    }
                    notificarErro("Falha ao procurar aparelhos: $motivo.")
                }
            })
        } catch (_: SecurityException) {
            notificarErro("Permissão de dispositivos Wi-Fi próximos não concedida.")
        }
    }

    fun iniciarServidor() {
        val manager = gerenciador
        val channel = canal
        if (manager == null || channel == null) {
            notificarErro("Wi-Fi Direct não está disponível.")
            return
        }
        try {
            manager.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    grupoSolicitado = true
                    notificarStatus("Sala Wi-Fi Direct criada. Aguardando outro aparelho...")
                }

                override fun onFailure(reason: Int) {
                    grupoSolicitado = false
                    notificarErro("Não foi possível criar a sala Wi-Fi Direct (código $reason).")
                }
            })
        } catch (_: SecurityException) {
            notificarErro("Permissão de dispositivos Wi-Fi próximos não concedida.")
        }
    }

    fun conectar(dispositivo: WifiP2pDevice) {
        val manager = gerenciador
        val channel = canal
        if (manager == null || channel == null) {
            notificarErro("Wi-Fi Direct não está disponível.")
            return
        }
        nomeDispositivo = dispositivo.deviceName.ifBlank { "Aparelho próximo" }
        try {
            manager.connect(
                channel,
                WifiP2pConfig().apply { deviceAddress = dispositivo.deviceAddress },
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        grupoSolicitado = true
                        notificarStatus("Solicitação enviada. Aguardando conexão...")
                    }

                    override fun onFailure(reason: Int) {
                        grupoSolicitado = false
                        notificarErro("Não foi possível conectar ao aparelho (código $reason).")
                    }
                }
            )
        } catch (_: SecurityException) {
            notificarErro("Permissão de dispositivos Wi-Fi próximos não concedida.")
        }
    }

    fun enviarMensagem(texto: String): Boolean {
        val socket = conexaoSocket ?: return false
        val output = escritor ?: return false
        if (!socket.isConnected || socket.isClosed) return false
        synchronized(output) {
            output.println(texto)
            return !output.checkError()
        }
    }

    fun pararTudo() {
        threadServidor?.interrupt()
        threadCliente?.interrupt()
        fecharSockets()
        servidorIniciado = false
        clienteIniciado = false
        try {
            val manager = gerenciador
            val channel = canal
            if (buscaAtiva && manager != null && channel != null) {
                manager.stopPeerDiscovery(channel, null)
                buscaAtiva = false
            }
            if (grupoSolicitado && manager != null && channel != null) {
                manager.removeGroup(channel, null)
                grupoSolicitado = false
            }
        } catch (exception: SecurityException) {
            Log.w(TAG, "Sem permissão para encerrar a sessão Wi-Fi Direct.", exception)
        }
    }

    fun encerrar() {
        pararTudo()
        if (canalReceptorRegistrado) {
            contexto.unregisterReceiver(receptor)
            canalReceptorRegistrado = false
        }
    }

    private fun atualizarDispositivos() {
        val manager = gerenciador ?: return
        val channel = canal ?: return
        try {
            manager.requestPeers(channel) { lista ->
                escutaEventos.dispositivosEncontrados(lista.deviceList.toList())
            }
        } catch (_: SecurityException) {
            notificarErro("Permissão de dispositivos Wi-Fi próximos não concedida.")
        }
    }

    private fun atualizarConexao() {
        val manager = gerenciador ?: return
        val channel = canal ?: return
        try {
            manager.requestConnectionInfo(channel) { info ->
                if (!info.groupFormed) {
                    notificarStatus("Aguardando conexão Wi-Fi Direct...")
                } else if (info.isGroupOwner) {
                    iniciarServidorSocket()
                } else {
                    iniciarClienteSocket(info.groupOwnerAddress?.hostAddress)
                }
            }
        } catch (_: SecurityException) {
            notificarErro("Permissão de dispositivos Wi-Fi próximos não concedida.")
        }
    }

    @Synchronized
    private fun iniciarServidorSocket() {
        if (servidorIniciado || conexaoSocket?.isConnected == true) return
        servidorIniciado = true
        threadServidor = Thread {
            try {
                val server = ServerSocket(PORTA)
                servidorSocket = server
                notificarStatus("Sala criada. Aguardando mensagem de outro aparelho...")
                estabelecerConexao(server.accept())
            } catch (exception: IOException) {
                if (!Thread.currentThread().isInterrupted) {
                    notificarErro("Não foi possível iniciar a conversa Wi-Fi Direct.")
                }
            } finally {
                try {
                    servidorSocket?.close()
                } catch (_: IOException) {
                }
                servidorSocket = null
                servidorIniciado = false
            }
        }.apply { start() }
    }

    @Synchronized
    private fun iniciarClienteSocket(endereco: String?) {
        if (clienteIniciado || conexaoSocket?.isConnected == true || endereco.isNullOrBlank()) return
        clienteIniciado = true
        threadCliente = Thread {
            var socket: Socket? = null
            try {
                var tentativa = 0
                while (socket == null && tentativa < TENTATIVAS_CONEXAO) {
                    if (Thread.currentThread().isInterrupted) return@Thread
                    try {
                        socket = Socket().apply {
                            connect(InetSocketAddress(endereco, PORTA), TIMEOUT_CONEXAO_MS)
                        }
                    } catch (exception: IOException) {
                        tentativa++
                        if (tentativa == TENTATIVAS_CONEXAO) throw exception
                        Thread.sleep(INTERVALO_RETENTATIVA_MS)
                    }
                }
                socket?.let { estabelecerConexao(it) }
            } catch (_: IOException) {
                notificarErro("Não foi possível estabelecer a conversa Wi-Fi Direct.")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                clienteIniciado = false
            }
        }.apply { start() }
    }

    private fun estabelecerConexao(socket: Socket) {
        try {
            val output = PrintWriter(
                OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                true
            )
            conexaoSocket = socket
            escritor = output
        } catch (exception: IOException) {
            try {
                socket.close()
            } catch (_: IOException) {
            }
            throw exception
        }
        handler.post { escutaEventos.conectado(nomeDispositivo) }
        Thread {
            try {
                val leitor = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                while (true) {
                    val linha = leitor.readLine() ?: break
                    handler.post { escutaEventos.mensagemRecebida(linha, nomeDispositivo) }
                }
            } catch (_: IOException) {
                if (!socket.isClosed) notificarErro("A conexão Wi-Fi Direct foi encerrada.")
            } finally {
                fecharSockets()
            }
        }.start()
    }

    @Synchronized
    private fun fecharSockets() {
        try {
            conexaoSocket?.close()
        } catch (_: IOException) {
        }
        try {
            servidorSocket?.close()
        } catch (_: IOException) {
        }
        conexaoSocket = null
        escritor = null
    }

    private fun notificarErro(mensagem: String) {
        handler.post { escutaEventos.erro(mensagem) }
    }

    private fun notificarStatus(mensagem: String) {
        handler.post { escutaEventos.status(mensagem) }
    }

    companion object {
        private const val TAG = "WifiDirectHelper"
        private const val PORTA = 8888
        private const val TENTATIVAS_CONEXAO = 10
        private const val TIMEOUT_CONEXAO_MS = 2_000
        private const val INTERVALO_RETENTATIVA_MS = 500L
    }
}
