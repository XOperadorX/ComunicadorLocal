package com.exemplo.comunicadorlocal

data class Mensagem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val remetente: String,
    val texto: String,
    val ehMinha: Boolean,
    var favorita: Boolean = false,
    var pendente: Boolean = false,
    val tipo: String = "texto",
    val arquivo: String? = null,
    val mimeType: String? = null,
    val nomeArquivo: String? = null,
    val hora: String = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date())
)