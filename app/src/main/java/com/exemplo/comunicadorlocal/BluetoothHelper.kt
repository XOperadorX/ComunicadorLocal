package com.exemplo.comunicadorlocal

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Message
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class BluetoothHelper(
    private val activity: MainActivity,
    private val handler: Handler
) {
    companion object {
        const val MENSAGEM_RECEBIDA = 1
        const val STATUS_CONECTADO = 2
        const val STATUS_ERRO = 3
        private val UUID_BT = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var adaptador = BluetoothAdapter.getDefaultAdapter()
    private var servidor: ServidorThread? = null
    private var cliente: ClienteThread? = null
    private var conectado: ConectadoThread? = null

    fun iniciarServidor(nome: String) {
        servidor = ServidorThread(nome)
        servidor?.start()
    }

    fun conectar(dispositivo: BluetoothDevice) {
        cliente = ClienteThread(dispositivo)
        cliente?.start()
    }

    fun enviarMensagem(texto: String): Boolean {
        return conectado?.enviar(texto) ?: false
    }

    fun pararTudo() {
        servidor?.cancelar()
        cliente?.cancelar()
        conectado?.cancelar()
        servidor = null
        cliente = null
        conectado = null
    }

    private inner class ServidorThread(nome: String) : Thread() {
        private val socket: BluetoothServerSocket? = adaptador.listenUsingRfcommWithServiceRecord(nome, UUID_BT)
        override fun run() {
            var socket: BluetoothSocket?
            while (true) {
                socket = this@ServidorThread.socket?.accept()
                if (socket != null) {
                    conectado = ConectadoThread(socket)
                    conectado?.start()
                    handler.obtainMessage(STATUS_CONECTADO).sendToTarget()
                    this@ServidorThread.socket?.close()
                    break
                }
            }
        }
        fun cancelar() { socket?.close() }
    }

    private inner class ClienteThread(val dispositivo: BluetoothDevice) : Thread() {
        private val socket = dispositivo.createRfcommSocketToServiceRecord(UUID_BT)
        override fun run() {
            try {
                adaptador.cancelDiscovery()
                socket.connect()
                conectado = ConectadoThread(socket)
                conectado?.start()
                handler.obtainMessage(STATUS_CONECTADO).sendToTarget()
            } catch (e: IOException) {
                handler.obtainMessage(STATUS_ERRO, "Não foi possível conectar").sendToTarget()
            }
        }
        fun cancelar() { socket.close() }
    }

    private inner class ConectadoThread(private val socket: BluetoothSocket) : Thread() {
        private val entrada: InputStream = socket.inputStream
        private val saida: OutputStream = socket.outputStream
        private val buffer = ByteArray(1024)
        override fun run() {
            var lido: Int
            while (true) {
                try {
                    lido = entrada.read(buffer)
                    val texto = String(buffer, 0, lido)
                    handler.obtainMessage(MENSAGEM_RECEBIDA, texto).sendToTarget()
                } catch (e: IOException) { break }
            }
        }
        fun enviar(texto: String): Boolean {
            return try {
                saida.write(texto.toByteArray())
                true
            } catch (e: IOException) { false }
        }
        fun cancelar() { socket.close() }
    }
}