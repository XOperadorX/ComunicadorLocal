import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class BluetoothHelper(private val contexto: Context, private val handler: Handler) {
    private val adaptadorBt = BluetoothAdapter.getDefaultAdapter()
    private val UUID_PRINCIPAL = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")
    
    private var servidorThread: ServidorThread? = null
    private var clienteThread: ClienteThread? = null
    private var conectadoThread: ConectadoThread? = null

    val estaAtivo: Boolean get() = adaptadorBt?.isEnabled == true

    fun iniciarServidor(nomeApp: String) {
        servidorThread = ServidorThread(nomeApp)
        servidorThread?.start()
    }

    fun conectar(device: BluetoothDevice) {
        clienteThread = ClienteThread(device)
        clienteThread?.start()
    }

    fun enviarMensagem(texto: String): Boolean {
        val thread = conectadoThread ?: return false
        return thread.escrever(texto.toByteArray())
    }

    fun pararTudo() {
        servidorThread?.cancelar()
        clienteThread?.cancelar()
        conectadoThread?.cancelar()
        conectadoThread = null
    }

    private inner class ServidorThread(nomeApp: String) : Thread() {
        private val socket: BluetoothServerSocket? = adaptadorBt.listenUsingRfcommWithServiceRecord(nomeApp, UUID_PRINCIPAL)
        override fun run() {
            val socket = try { socket?.accept() } catch (e: IOException) { return }
            socket?.use { conectar(it) }
        }
        fun cancelar() { try { socket?.close() } catch (e: IOException) {} }
    }

    private inner class ClienteThread(val dispositivo: BluetoothDevice) : Thread() {
        private var socket: BluetoothSocket? = null
        override fun run() {
            socket = dispositivo.createRfcommSocketToServiceRecord(UUID_PRINCIPAL)
            adaptadorBt.cancelDiscovery()
            try {
                socket?.connect()
                handler.obtainMessage(STATUS_CONECTADO, dispositivo.name).sendToTarget()
                conectar(socket!!)
            } catch (e: IOException) {
                handler.obtainMessage(STATUS_ERRO, "Não foi possível conectar").sendToTarget()
                return
            }
        }
        fun cancelar() { try { socket?.close() } catch (e: IOException) {} }
    }

    private fun conectar(socket: BluetoothSocket) {
        conectadoThread = ConectadoThread(socket).apply { start() }
        handler.obtainMessage(STATUS_CONECTADO).sendToTarget()
    }

    private inner class ConectadoThread(private val socket: BluetoothSocket) : Thread() {
        private val entrada: InputStream = socket.inputStream
        private val saida: OutputStream = socket.outputStream
        override fun run() {
            val buffer = ByteArray(1024)
            var bytesLidos: Int
            while (true) {
                try {
                    bytesLidos = entrada.read(buffer)
                    val texto = String(buffer, 0, bytesLidos)
                    handler.obtainMessage(MENSAGEM_RECEBIDA, texto).sendToTarget()
                } catch (e: IOException) { break }
            }
        }
        fun escrever(dados: ByteArray): Boolean = try {
            saida.write(dados)
            true
        } catch (e: IOException) { false }
        fun cancelar() { try { socket.close() } catch (e: IOException) {} }
    }

    companion object {
        const val MENSAGEM_RECEBIDA = 1
        const val STATUS_CONECTADO = 2
        const val STATUS_ERRO = 3
    }
}