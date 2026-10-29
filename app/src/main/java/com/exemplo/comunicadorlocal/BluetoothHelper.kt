package com.exemplo.comunicadorlocal

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.util.Base64
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.util.UUID

class BluetoothHelper(private val handler: Handler) {
    companion object {
        const val MENSAGEM_RECEBIDA = 1
        const val STATUS_CONECTADO = 2
        const val STATUS_ERRO = 3
        const val STATUS_DESCONECTADO = 4
        const val STATUS_ESPERANDO = 5
        const val STATUS_CONEXAO_FALHOU = 6
        const val PREFIXO_ANEXO_INICIO = "CML2_MEDIA_BEGIN|"
        const val PREFIXO_ANEXO_PARTE = "CML2_MEDIA_CHUNK|"
        const val PREFIXO_ANEXO_FIM = "CML2_MEDIA_END|"
        const val PREFIXO_ANEXO_CANCELAR = "CML2_MEDIA_ABORT|"
        private val UUID_BT = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val adaptador: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    @Volatile private var servidor: ServidorThread? = null
    @Volatile private var cliente: ClienteThread? = null
    @Volatile private var conexaoAtiva: ConectadoThread? = null
    @Volatile private var nomeDispositivoConectado: String? = null

    fun conectado(): Boolean = conexaoAtiva != null

    fun nomeConectado(): String = nomeDispositivoConectado ?: "Aparelho Bluetooth"

    @Synchronized
    fun iniciarServidor(nome: String) {
        if (conexaoAtiva != null || servidor?.isAlive == true) return
        servidor = ServidorThread(nome).also { it.start() }
    }

    @Synchronized
    fun conectar(dispositivo: BluetoothDevice) {
        if (cliente?.isAlive == true || conexaoAtiva != null) return
        servidor?.cancelar()
        servidor = null
        cliente = ClienteThread(dispositivo).also { it.start() }
    }

    fun enviarMensagem(texto: String): Boolean = conexaoAtiva?.enviar(texto) ?: false

    fun enviarArquivo(
        inicio: String,
        id: String,
        entrada: InputStream,
        progresso: (Long) -> Unit
    ): Boolean = conexaoAtiva?.enviarArquivo(inicio, id, entrada, progresso) ?: false

    @Synchronized
    fun pararTudo() {
        servidor?.cancelar()
        cliente?.cancelar()
        conexaoAtiva?.cancelar()
        servidor = null
        cliente = null
        conexaoAtiva = null
        nomeDispositivoConectado = null
    }

    @Synchronized
    private fun estabelecerConexao(socket: BluetoothSocket, nomeDispositivo: String) {
        if (conexaoAtiva != null) {
            socket.close()
            return
        }
        servidor?.cancelar()
        nomeDispositivoConectado = nomeDispositivo
        conexaoAtiva = ConectadoThread(socket).also {
            it.start()
            handler.obtainMessage(STATUS_CONECTADO, nomeDispositivo).sendToTarget()
        }
    }

    private fun erro(mensagem: String) {
        handler.obtainMessage(STATUS_ERRO, mensagem).sendToTarget()
    }

    private inner class ServidorThread(private val nome: String) : Thread() {
        @Volatile private var socketServidor: BluetoothServerSocket? = null

        override fun run() {
            try {
                val bluetooth = adaptador ?: throw IOException("Bluetooth não disponível.")
                socketServidor = bluetooth.listenUsingRfcommWithServiceRecord(nome, UUID_BT)
                handler.obtainMessage(STATUS_ESPERANDO).sendToTarget()
                val socket = socketServidor?.accept()
                if (socket != null) {
                    val nomeRemoto = try {
                        socket.remoteDevice.name?.takeIf { it.isNotBlank() } ?: "Aparelho Bluetooth"
                    } catch (_: SecurityException) {
                        "Aparelho Bluetooth"
                    }
                    estabelecerConexao(socket, nomeRemoto)
                }
            } catch (_: IOException) {
                if (!isInterrupted) erro("Não foi possível aguardar conexão Bluetooth.")
            } catch (_: SecurityException) {
                erro("Permissão de Bluetooth não concedida.")
            } finally {
                try {
                    socketServidor?.close()
                } catch (_: IOException) {
                }
                socketServidor = null
            }
        }

        fun cancelar() {
            interrupt()
            try {
                socketServidor?.close()
            } catch (_: IOException) {
            }
        }
    }

    private inner class ClienteThread(private val dispositivo: BluetoothDevice) : Thread() {
        @Volatile private var socket: BluetoothSocket? = null

        override fun run() {
            try {
                socket = dispositivo.createRfcommSocketToServiceRecord(UUID_BT)
                socket?.connect()
                val nome = try {
                    dispositivo.name?.takeIf { it.isNotBlank() } ?: "Aparelho Bluetooth"
                } catch (_: SecurityException) {
                    "Aparelho Bluetooth"
                }
                socket?.let { estabelecerConexao(it, nome) }
            } catch (_: IOException) {
                if (!isInterrupted) {
                    handler.obtainMessage(STATUS_CONEXAO_FALHOU).sendToTarget()
                }
                cancelar()
            } catch (_: SecurityException) {
                erro("Permissão de Bluetooth não concedida.")
                cancelar()
            }
        }

        fun cancelar() {
            interrupt()
            try {
                socket?.close()
            } catch (_: IOException) {
            }
        }
    }

    private inner class ConectadoThread(private val socket: BluetoothSocket) : Thread() {
        private val escrita = PrintWriter(
            OutputStreamWriter(socket.outputStream, StandardCharsets.UTF_8),
            false
        )

        override fun run() {
            try {
                val leitura = BufferedReader(
                    InputStreamReader(socket.inputStream, StandardCharsets.UTF_8)
                )
                while (true) {
                    val linha = leitura.readLine() ?: break
                    handler.obtainMessage(MENSAGEM_RECEBIDA, linha).sendToTarget()
                }
            } catch (_: IOException) {
            } finally {
                val encerradaPeloUsuario = isInterrupted
                cancelar()
                desconectado(this, !encerradaPeloUsuario)
            }
        }

        fun enviar(texto: String): Boolean = synchronized(escrita) {
            escreverLinha(texto)
        }

        fun enviarArquivo(
            inicio: String,
            id: String,
            entrada: InputStream,
            progresso: (Long) -> Unit
        ): Boolean = synchronized(escrita) {
            if (!escreverLinha(inicio)) return@synchronized false
            val buffer = ByteArray(12 * 1024)
            var total = 0L
            try {
                while (true) {
                    val lidos = entrada.read(buffer)
                    if (lidos < 0) break
                    val trecho = Base64.encodeToString(buffer, 0, lidos, Base64.NO_WRAP)
                    if (!escreverLinha("$PREFIXO_ANEXO_PARTE$id|$trecho")) {
                        escreverLinha("$PREFIXO_ANEXO_CANCELAR$id")
                        return@synchronized false
                    }
                    total += lidos
                    progresso(total)
                }
                escreverLinha("$PREFIXO_ANEXO_FIM$id")
            } catch (exception: IOException) {
                escreverLinha("$PREFIXO_ANEXO_CANCELAR$id")
                false
            }
        }

        private fun escreverLinha(texto: String): Boolean {
            if (!socket.isConnected) return false
            escrita.println(texto)
            escrita.flush()
            return !escrita.checkError()
        }

        fun cancelar() {
            interrupt()
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    @Synchronized
    private fun desconectado(conexao: ConectadoThread, notificar: Boolean) {
        if (conexaoAtiva !== conexao) return
        conexaoAtiva = null
        nomeDispositivoConectado = null
        if (notificar) handler.obtainMessage(STATUS_DESCONECTADO).sendToTarget()
    }
}
