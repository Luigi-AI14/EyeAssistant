package com.freespace.guide.scene

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import android.util.Log
import com.freespace.guide.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class GeminiSceneNarrator(
    private val context: Context? = null,
    private val apiKey: String,
    private val preferredModel: String = DEFAULT_MODEL_NAME,
) {

    fun isConfigured(): Boolean = apiKey.isNotBlank() && apiKey != "YOUR_GEMINI_API_KEY_HERE"

    data class NarrationResult(
        val text: String,
        val modelUsed: String = ""
    )

    suspend fun narrate(
        bitmap: Bitmap,
        isLandscape: Boolean = false,
    ): NarrationResult = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            Log.w(TAG, "[GEMINI-CONFIG] API Key non impostata in local.properties")
            return@withContext NarrationResult(getString(R.string.say_gemini_no_key), "")
        }

        val optimizedBitmap = optimizeBitmapForCloud(bitmap)
        val base64Image = try {
            val stream = ByteArrayOutputStream()
            optimizedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "[GEMINI-COMPRESS-ERROR] Errore compressione JPEG: ${e.message}", e)
            return@withContext NarrationResult(getString(R.string.say_gemini_error), "")
        } finally {
            if (optimizedBitmap != bitmap && !optimizedBitmap.isRecycled) {
                optimizedBitmap.recycle()
            }
        }

        val promptText = if (isLandscape) {
            "Fai una scansione panoramica della stanza: descrivi quali elementi o ostacoli sono presenti da sinistra a destra e se il passaggio è libero."
        } else {
            "Identifica con precisione cosa c'è di fronte a me, dove si trovano gli ostacoli principali e se il passaggio è libero."
        }

        val payload = buildJsonPayload(base64Image, promptText)
        val modelsToTry = listOf(
            preferredModel,
            FALLBACK_37_FLASH,
            FALLBACK_36_FLASH,
            FALLBACK_35_FLASH,
        ).distinct()

        var lastErrorMessage = ""
        var isQuotaExceeded = false
        var isNetworkError = false

        for (model in modelsToTry) {
            Log.i(TAG, "[GEMINI-REQ] Invio richiesta snapshot al modello '$model'...")
            val result = executeGenerateContent(model, payload)
            if (result.isSuccess) {
                val narration = result.getOrNull()?.trim() ?: ""
                if (narration.isNotBlank()) {
                    Log.i(TAG, "[GEMINI-SUCCESS] Risposta ricevuta con successo da '$model': \"$narration\"")
                    return@withContext NarrationResult(narration, model)
                }
            } else {
                val err = result.exceptionOrNull()?.message ?: "Errore sconosciuto"
                if (err.contains("429") || err.contains("quota", ignoreCase = true) || err.contains("RESOURCE_EXHAUSTED", ignoreCase = true)) {
                    isQuotaExceeded = true
                } else if (err.contains("UnknownHost", ignoreCase = true) || err.contains("ConnectException", ignoreCase = true) || err.contains("SocketTimeout", ignoreCase = true)) {
                    isNetworkError = true
                }
                Log.w(TAG, "[GEMINI-RETRY] Modello '$model' non disponibile ($err). Tento modello successivo...")
                lastErrorMessage = err
            }
        }

        Log.e(TAG, "[GEMINI-ALL-FAILED] Tutti i modelli hanno fallito. Ultimo errore: $lastErrorMessage")
        val errorText = when {
            isQuotaExceeded -> getString(R.string.say_gemini_quota_exceeded)
            isNetworkError -> getString(R.string.say_gemini_network_error)
            else -> getString(R.string.say_gemini_error)
        }
        NarrationResult(errorText, "")
    }

    fun buildJsonPayload(base64Image: String, promptText: String): String {
        val root = JSONObject()
        val systemInstruction = JSONObject().apply {
            put("parts", JSONArray().apply {
                put(JSONObject().put("text", SYSTEM_PROMPT))
            })
        }
        root.put("system_instruction", systemInstruction)
        val contents = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", base64Image)
                        })
                    })
                    put(JSONObject().apply {
                        put("text", promptText)
                    })
                })
            })
        }
        root.put("contents", contents)

        val config = JSONObject().apply {
            put("temperature", 0.2)
            put("topK", 20)
            put("topP", 0.9)
            put("maxOutputTokens", 1024)
        }
        root.put("generationConfig", config)

        return root.toString()
    }

    private fun executeGenerateContent(modelName: String, jsonPayload: String): Result<String> {
        var connection: HttpURLConnection? = null
        return try {
            val urlString = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
            val url = URL(urlString)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
            }

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(jsonPayload)
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseBody = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
                val jsonResponse = JSONObject(responseBody)
                val candidates = jsonResponse.optJSONArray("candidates")
                val firstCandidate = candidates?.optJSONObject(0)
                val content = firstCandidate?.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                val text = parts?.optJSONObject(0)?.optString("text")

                if (!text.isNullOrBlank()) {
                    Result.success(text)
                } else {
                    Result.failure(Exception("Risposta vuota o priva di testo"))
                }
            } else {
                val errorBody = try {
                    BufferedReader(InputStreamReader(connection.errorStream ?: connection.inputStream, Charsets.UTF_8)).use { it.readText() }
                } catch (_: Exception) {
                    ""
                }
                Log.e(TAG, "[GEMINI-HTTP-$responseCode] Errore API su $modelName: $errorBody")
                Result.failure(Exception("HTTP $responseCode: $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "[GEMINI-CONN-ERROR] Errore connessione verso $modelName: ${e.message}", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    fun optimizeBitmapForCloud(src: Bitmap, maxDimension: Int = 768): Bitmap {
        val width = src.width
        val height = src.height
        val maxSide = maxOf(width, height)
        if (maxSide <= maxDimension) return src

        val scale = maxDimension.toFloat() / maxSide
        val matrix = Matrix().apply { postScale(scale, scale) }
        return Bitmap.createBitmap(src, 0, 0, width, height, matrix, true)
    }

    private fun getString(resId: Int, vararg args: Any): String {
        val ctx = context
        if (ctx != null) {
            return try {
                if (args.isEmpty()) ctx.getString(resId) else ctx.getString(resId, *args)
            } catch (_: Exception) {
                fallbackString(resId)
            }
        }
        return fallbackString(resId)
    }

    private fun fallbackString(resId: Int): String = when (resId) {
        R.string.say_gemini_no_key -> "Chiave API di Gemini non configurata. Inseriscila in local.properties."
        R.string.say_gemini_network_error -> "Connessione non disponibile. Impossibile contattare Gemini."
        R.string.say_gemini_quota_exceeded -> "Limite di richieste raggiunto. Riprova più tardi."
        R.string.say_gemini_error -> "Errore durante l'analisi della scena."
        R.string.say_scanning -> "Scansione in corso"
        R.string.say_scanning_wait -> "Attendere prego, scansione in corso"
        else -> ""
    }

    companion object {
        private const val TAG = "GuidaGemini"
        const val DEFAULT_MODEL_NAME = "gemini-3.5-flash-lite"
        const val FALLBACK_37_FLASH = "gemini-3.7-flash"
        const val FALLBACK_36_FLASH = "gemini-3.6-flash"
        const val FALLBACK_35_FLASH = "gemini-3.5-flash"

        private const val CONNECT_TIMEOUT_MS = 8000
        private const val READ_TIMEOUT_MS = 14000

        private const val SYSTEM_PROMPT = """
            Sei l'assistente visivo vocale in tempo reale per una persona non vedente.
            Il tuo compito è descrivere con precisione e chiarezza gli elementi e gli ostacoli presenti nella scena (es. porte, sedie, tavoli, pareti, persone, dislivelli, scale, oggetti a terra).

            Regole fondamentali:
            1. Identifica con esattezza gli oggetti presenti e indica chiaramente dove si trovano (es. "alla tua sinistra", "di fronte a te", "sulla tua destra").
            2. Segnala subito e chiaramente se il passaggio di fronte è libero o se c'è un ostacolo imminente.
            3. Rispondi con 1 o 2 frasi concise, naturali e complete in italiano, adatte alla sintesi vocale.
            4. Non usare mai formule visive o preamboli (non dire mai "Nell'immagine vedo" o "Posso osservare").
        """
    }
}
