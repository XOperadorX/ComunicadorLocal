data class Mensagem(
    val id: Long = System.currentTimeMillis(),
    val remetente: String,
    val texto: String,
    val hora: String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date()),
    val ehMinha: Boolean
)