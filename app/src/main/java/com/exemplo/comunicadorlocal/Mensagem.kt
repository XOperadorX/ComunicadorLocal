package com.exemplo.comunicadorlocal

data class Mensagem(
    val remetente: String,
    val texto: String,
    val ehMinha: Boolean,
    val hora: String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date())
)