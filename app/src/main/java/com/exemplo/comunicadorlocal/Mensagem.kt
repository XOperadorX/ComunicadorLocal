package com.exemplo.comunicadorlocal

data class Mensagem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val remetente: String,
    val texto: String,
    val ehMinha: Boolean,
    var favorita: Boolean = false,
    var pendente: Boolean = false,
    val hora: String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date())
)