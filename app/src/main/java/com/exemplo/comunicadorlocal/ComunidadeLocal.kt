package com.exemplo.comunicadorlocal

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ComunidadeLocal(
    private val activity: AppCompatActivity,
    private val aoVoltar: () -> Unit,
    private val selecionarImagem: () -> Unit,
    private val selecionarVideo: () -> Unit,
    private val selecionarAudio: () -> Unit,
    private val tirarFoto: () -> Unit,
    private val solicitarPermissaoAudio: () -> Unit
) {
    private data class Comentario(
        val autor: String,
        val texto: String,
        val criadoEm: Long
    )

    private data class Publicacao(
        val id: String,
        val autor: String,
        val texto: String,
        val criadaEm: Long,
        var curtidaPorMim: Boolean = false,
        val comentarios: MutableList<Comentario> = mutableListOf(),
        val arquivo: String? = null,
        val mimeType: String? = null,
        val nomeArquivo: String? = null,
        val tipoMidia: String? = null
    )

    private val preferencias
        get() = activity.getSharedPreferences(PREFERENCIAS, AppCompatActivity.MODE_PRIVATE)
    private val publicacoes = mutableListOf<Publicacao>()
    private lateinit var listaPublicacoes: LinearLayout
    private lateinit var edNovaPublicacao: EditText
    private var gravadorAudio: MediaRecorder? = null
    private var arquivoAudioGravado: File? = null
    private var btnGravarAudio: Button? = null

    fun mostrar() {
        activity.setContentView(R.layout.activity_comunidade)
        listaPublicacoes = activity.findViewById(R.id.listaPublicacoes)
        edNovaPublicacao = activity.findViewById(R.id.edNovaPublicacao)
        btnGravarAudio = activity.findViewById(R.id.btnGravarAudioComunidade)
        carregarPublicacoes()
        atualizarBotaoPerfil()
        activity.findViewById<Button>(R.id.btnVoltarComunidade).setOnClickListener { aoVoltar() }
        activity.findViewById<Button>(R.id.btnPerfilComunidade).setOnClickListener {
            editarPerfil()
        }
        activity.findViewById<Button>(R.id.btnPublicar).setOnClickListener {
            publicar()
        }
        activity.findViewById<Button>(R.id.btnImagemComunidade).setOnClickListener {
            selecionarImagem()
        }
        activity.findViewById<Button>(R.id.btnVideoComunidade).setOnClickListener {
            selecionarVideo()
        }
        activity.findViewById<Button>(R.id.btnAudioComunidade).setOnClickListener {
            selecionarAudio()
        }
        activity.findViewById<Button>(R.id.btnFotoComunidade).setOnClickListener {
            tirarFoto()
        }
        btnGravarAudio?.setOnClickListener {
            if (gravadorAudio == null) solicitarPermissaoAudio()
            else pararGravacaoAudio()
        }
        renderizarPublicacoes()
    }

    fun adicionarMidia(uri: Uri, tipo: String) {
        var arquivo: File? = null
        try {
            val nome = nomeDoArquivo(uri)
            val mimeType = activity.contentResolver.getType(uri)
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    nome.substringAfterLast('.', "").lowercase(Locale.ROOT)
                )
            if (mimeType == null || tipo !in TIPOS_MIDIA || !mimeType.startsWith(prefixoMime(tipo))) {
                Toast.makeText(
                    activity,
                    "Selecione um arquivo de ${tipoParaExibicao(tipo)} válido.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            val arquivoSalvo = criarArquivoMidia()
            arquivo = arquivoSalvo
            val entrada = activity.contentResolver.openInputStream(uri)
                ?: throw IOException("Não foi possível abrir o arquivo selecionado.")
            entrada.use { origem ->
                arquivoSalvo.outputStream().use { destino -> origem.copyTo(destino) }
            }
            adicionarMidiaLocal(arquivoSalvo, tipo, mimeType, nome)
        } catch (exception: IOException) {
            arquivo?.delete()
            Toast.makeText(activity, "Não foi possível salvar a mídia selecionada.", Toast.LENGTH_LONG).show()
        } catch (exception: SecurityException) {
            arquivo?.delete()
            Toast.makeText(activity, "O app não tem acesso ao arquivo selecionado.", Toast.LENGTH_LONG).show()
        } catch (exception: IllegalArgumentException) {
            arquivo?.delete()
            Toast.makeText(activity, "O arquivo selecionado não é válido.", Toast.LENGTH_LONG).show()
        }
    }

    fun adicionarMidiaLocal(arquivo: File, tipo: String, mimeType: String, nome: String = arquivo.name) {
        if (!arquivo.isFile || !arquivo.canRead() || arquivo.length() == 0L) {
            arquivo.delete()
            Toast.makeText(activity, "Não foi possível ler a mídia selecionada.", Toast.LENGTH_LONG).show()
            return
        }
        if (tipo !in TIPOS_MIDIA || !mimeType.startsWith(prefixoMime(tipo))) {
            Toast.makeText(activity, "O formato da mídia não é compatível.", Toast.LENGTH_LONG).show()
            return
        }
        val legenda = edNovaPublicacao.text.toString().trim()
        publicacoes.add(
            0,
            Publicacao(
                id = java.util.UUID.randomUUID().toString(),
                autor = nomePerfil(),
                texto = legenda.ifBlank { textoPadraoMidia(tipo) },
                criadaEm = System.currentTimeMillis(),
                arquivo = arquivo.absolutePath,
                mimeType = mimeType,
                nomeArquivo = nome,
                tipoMidia = tipo
            )
        )
        salvarPublicacoes()
        edNovaPublicacao.text.clear()
        renderizarPublicacoes()
        Toast.makeText(activity, "Mídia publicada na comunidade deste aparelho.", Toast.LENGTH_SHORT).show()
    }

    fun iniciarGravacaoAudio() {
        if (gravadorAudio != null) return
        var arquivo: File? = null
        var gravador: MediaRecorder? = null
        try {
            arquivo = criarArquivoMidia(".m4a")
            gravador = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(activity)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            gravador.setAudioSource(MediaRecorder.AudioSource.MIC)
            gravador.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            gravador.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            gravador.setOutputFile(arquivo.absolutePath)
            gravador.prepare()
            gravador.start()
            arquivoAudioGravado = arquivo
            gravadorAudio = gravador
            btnGravarAudio?.text = "Parar gravação"
            Toast.makeText(activity, "Gravando áudio. Toque em Parar gravação para publicar.", Toast.LENGTH_LONG).show()
        } catch (exception: IOException) {
            gravador?.release()
            arquivo?.delete()
            Toast.makeText(activity, "Não foi possível iniciar a gravação de áudio.", Toast.LENGTH_LONG).show()
        } catch (exception: IllegalStateException) {
            gravador?.release()
            arquivo?.delete()
            Toast.makeText(activity, "O gravador de áudio não está disponível.", Toast.LENGTH_LONG).show()
        }
    }

    fun pararGravacaoAudio(cancelar: Boolean = false) {
        val gravador = gravadorAudio ?: return
        val arquivo = arquivoAudioGravado
        gravadorAudio = null
        arquivoAudioGravado = null
        btnGravarAudio?.text = "Gravar áudio"
        var gravacaoConcluida = false
        try {
            gravador.stop()
            gravacaoConcluida = !cancelar
        } catch (exception: RuntimeException) {
            if (!cancelar) {
                Toast.makeText(activity, "A gravação foi curta demais ou não pôde ser concluída.", Toast.LENGTH_LONG).show()
            }
        } finally {
            gravador.release()
        }
        if (!gravacaoConcluida || arquivo == null) {
            arquivo?.delete()
            return
        }
        adicionarMidiaLocal(arquivo, "audio", MIME_AUDIO_GRAVADO, "audio-comunidade.m4a")
    }

    private fun nomePerfil(): String =
        preferencias.getString(CHAVE_PERFIL, NOME_PADRAO).orEmpty().ifBlank { NOME_PADRAO }

    private fun atualizarBotaoPerfil() {
        activity.findViewById<Button>(R.id.btnPerfilComunidade).text = nomePerfil()
    }

    private fun editarPerfil() {
        val campo = EditText(activity).apply {
            setText(nomePerfil())
            hint = "Seu nome no aparelho"
            filters = arrayOf(InputFilter.LengthFilter(LIMITE_NOME))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        AlertDialog.Builder(activity)
            .setTitle("Perfil local")
            .setMessage("Este nome aparece nas suas publicações e comentários neste aparelho.")
            .setView(campo)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                val nome = campo.text.toString().trim()
                if (nome.isEmpty()) {
                    Toast.makeText(activity, "Informe um nome para o perfil.", Toast.LENGTH_SHORT).show()
                } else {
                    preferencias.edit().putString(CHAVE_PERFIL, nome).apply()
                    atualizarBotaoPerfil()
                }
            }
            .show()
    }

    private fun publicar() {
        val texto = edNovaPublicacao.text.toString().trim()
        if (texto.isEmpty()) {
            Toast.makeText(activity, "Escreva algo antes de publicar.", Toast.LENGTH_SHORT).show()
            return
        }
        publicacoes.add(
            0,
            Publicacao(
                id = java.util.UUID.randomUUID().toString(),
                autor = nomePerfil(),
                texto = texto,
                criadaEm = System.currentTimeMillis()
            )
        )
        salvarPublicacoes()
        edNovaPublicacao.text.clear()
        renderizarPublicacoes()
    }

    private fun renderizarPublicacoes() {
        listaPublicacoes.removeAllViews()
        if (publicacoes.isEmpty()) {
            listaPublicacoes.addView(TextView(activity).apply {
                text = "Ainda não há publicações. Seja a primeira pessoa a compartilhar algo!"
                setTextColor(Color.rgb(84, 110, 122))
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(28), dp(20), dp(28))
            })
            return
        }
        publicacoes.sortedByDescending { it.criadaEm }.forEach { publicacao ->
            listaPublicacoes.addView(criarCartao(publicacao))
        }
    }

    private fun criarCartao(publicacao: Publicacao): View {
        val cartao = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(220, 228, 230))
            }
        }
        val parametrosCartao = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(10)
        }
        val cabecalho = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cabecalho.addView(TextView(activity).apply {
            text = "${publicacao.autor} · ${formatarData(publicacao.criadaEm)}"
            setTextColor(Color.rgb(38, 50, 56))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cabecalho.addView(Button(activity).apply {
            text = "Excluir"
            setOnClickListener { confirmarExclusao(publicacao) }
        })
        cartao.addView(cabecalho)

        cartao.addView(TextView(activity).apply {
            text = publicacao.texto
            setTextColor(Color.rgb(38, 50, 56))
            textSize = 16f
            setPadding(0, dp(10), 0, dp(10))
        })

        val caminhoMidia = publicacao.arquivo
        val tipoMidia = publicacao.tipoMidia
        if (caminhoMidia != null && tipoMidia != null) {
            cartao.addView(Button(activity).apply {
                text = "Abrir ${tipoParaExibicao(tipoMidia)}: ${publicacao.nomeArquivo ?: "mídia"}"
                setOnClickListener { abrirMidia(publicacao) }
            })
        }

        val acoes = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        acoes.addView(Button(activity).apply {
            text = if (publicacao.curtidaPorMim) "♥ Curtido · 1" else "♡ Curtir · 0"
            setOnClickListener {
                publicacao.curtidaPorMim = !publicacao.curtidaPorMim
                salvarPublicacoes()
                renderizarPublicacoes()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        acoes.addView(Button(activity).apply {
            text = "Comentar · ${publicacao.comentarios.size}"
            setOnClickListener { adicionarComentario(publicacao) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cartao.addView(acoes)

        publicacao.comentarios.forEach { comentario ->
            cartao.addView(TextView(activity).apply {
                text = "${comentario.autor} · ${formatarData(comentario.criadoEm)}\n${comentario.texto}"
                setTextColor(Color.rgb(69, 90, 100))
                textSize = 13f
                setPadding(dp(8), dp(6), dp(8), dp(6))
            })
        }
        return cartao.apply { layoutParams = parametrosCartao }
    }

    private fun adicionarComentario(publicacao: Publicacao) {
        val campo = EditText(activity).apply {
            hint = "Escreva um comentário..."
            minLines = 2
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(LIMITE_COMENTARIO))
        }
        AlertDialog.Builder(activity)
            .setTitle("Comentar")
            .setView(campo)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Enviar") { _, _ ->
                val texto = campo.text.toString().trim()
                if (texto.isEmpty()) {
                    Toast.makeText(activity, "Escreva um comentário antes de enviar.", Toast.LENGTH_SHORT).show()
                } else {
                    publicacao.comentarios.add(
                        Comentario(nomePerfil(), texto, System.currentTimeMillis())
                    )
                    salvarPublicacoes()
                    renderizarPublicacoes()
                }
            }
            .show()
    }

    private fun confirmarExclusao(publicacao: Publicacao) {
        AlertDialog.Builder(activity)
            .setTitle("Excluir publicação")
            .setMessage("Esta publicação será removida deste aparelho.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Excluir") { _, _ ->
                publicacoes.removeAll { it.id == publicacao.id }
                salvarPublicacoes()
                publicacao.arquivo?.let(::File)?.delete()
                renderizarPublicacoes()
            }
            .show()
    }

    private fun carregarPublicacoes() {
        publicacoes.clear()
        val dados = preferencias.getString(CHAVE_PUBLICACOES, null) ?: return
        try {
            val array = JSONArray(dados)
            for (indice in 0 until array.length()) {
                val item = array.getJSONObject(indice)
                val comentarios = mutableListOf<Comentario>()
                val listaComentarios = item.optJSONArray("comentarios") ?: JSONArray()
                for (posicao in 0 until listaComentarios.length()) {
                    val comentario = listaComentarios.getJSONObject(posicao)
                    comentarios.add(
                        Comentario(
                            autor = comentario.getString("autor"),
                            texto = comentario.getString("texto"),
                            criadoEm = comentario.getLong("criadaEm")
                        )
                    )
                }
                publicacoes.add(
                    Publicacao(
                        id = item.getString("id"),
                        autor = item.getString("autor"),
                        texto = item.getString("texto"),
                        criadaEm = item.getLong("criadaEm"),
                        curtidaPorMim = item.optBoolean("curtidaPorMim"),
                        comentarios = comentarios,
                        arquivo = item.optString("arquivo").takeIf { it.isNotBlank() },
                        mimeType = item.optString("mimeType").takeIf { it.isNotBlank() },
                        nomeArquivo = item.optString("nomeArquivo").takeIf { it.isNotBlank() },
                        tipoMidia = item.optString("tipoMidia").takeIf { it in TIPOS_MIDIA }
                    )
                )
            }
        } catch (_: JSONException) {
            publicacoes.clear()
            Toast.makeText(
                activity,
                "Não foi possível recuperar as publicações salvas.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun salvarPublicacoes() {
        val array = JSONArray()
        publicacoes.forEach { publicacao ->
            val comentarios = JSONArray()
            publicacao.comentarios.forEach { comentario ->
                comentarios.put(
                    JSONObject()
                        .put("autor", comentario.autor)
                        .put("texto", comentario.texto)
                        .put("criadaEm", comentario.criadoEm)
                )
            }
            array.put(
                JSONObject()
                    .put("id", publicacao.id)
                    .put("autor", publicacao.autor)
                    .put("texto", publicacao.texto)
                    .put("criadaEm", publicacao.criadaEm)
                    .put("curtidaPorMim", publicacao.curtidaPorMim)
                    .put("comentarios", comentarios)
                    .put("arquivo", publicacao.arquivo)
                    .put("mimeType", publicacao.mimeType)
                    .put("nomeArquivo", publicacao.nomeArquivo)
                    .put("tipoMidia", publicacao.tipoMidia)
            )
        }
        preferencias.edit().putString(CHAVE_PUBLICACOES, array.toString()).apply()
    }

    private fun abrirMidia(publicacao: Publicacao) {
        val caminho = publicacao.arquivo ?: return
        val mimeType = publicacao.mimeType ?: return
        try {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                File(caminho)
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
        } catch (exception: ActivityNotFoundException) {
            Toast.makeText(activity, "Instale um aplicativo compatível para abrir esta mídia.", Toast.LENGTH_LONG).show()
        } catch (exception: IllegalArgumentException) {
            Toast.makeText(activity, "Não foi possível abrir o arquivo de mídia.", Toast.LENGTH_LONG).show()
        } catch (exception: SecurityException) {
            Toast.makeText(activity, "O app não tem permissão para abrir esta mídia.", Toast.LENGTH_LONG).show()
        }
    }

    private fun criarArquivoMidia(extensao: String = ".media"): File {
        val diretorio = File(activity.filesDir, "$DIRETORIO_ANEXOS/comunidade")
        if (!diretorio.exists() && !diretorio.mkdirs()) {
            throw IOException("Não foi possível preparar o armazenamento de mídias.")
        }
        return File.createTempFile("comunidade-", extensao, diretorio)
    }

    private fun nomeDoArquivo(uri: Uri): String {
        val cursor = activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val coluna = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (coluna >= 0) it.getString(coluna)?.takeIf(String::isNotBlank)?.let { nome -> return nome }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank) ?: "midia"
    }

    private fun prefixoMime(tipo: String): String = when (tipo) {
        "imagem" -> "image/"
        "video" -> "video/"
        "audio" -> "audio/"
        else -> ""
    }

    private fun tipoParaExibicao(tipo: String): String = when (tipo) {
        "imagem" -> "imagem"
        "video" -> "vídeo"
        "audio" -> "áudio"
        else -> "mídia"
    }

    private fun textoPadraoMidia(tipo: String): String = when (tipo) {
        "imagem" -> "Foto compartilhada"
        "video" -> "Vídeo compartilhado"
        "audio" -> "Áudio compartilhado"
        else -> "Mídia compartilhada"
    }

    private fun formatarData(millis: Long): String =
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(millis))

    private fun dp(valor: Int): Int =
        (valor * activity.resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFERENCIAS = "comunidade_local"
        private const val CHAVE_PERFIL = "perfil"
        private const val CHAVE_PUBLICACOES = "publicacoes"
        private const val NOME_PADRAO = "Você"
        private const val MIME_AUDIO_GRAVADO = "audio/mp4"
        private const val DIRETORIO_ANEXOS = "message-media"
        private const val LIMITE_NOME = 40
        private const val LIMITE_COMENTARIO = 500
        private val TIPOS_MIDIA = setOf("imagem", "video", "audio")
    }
}
