package com.freespace.guide.ml

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import java.nio.ByteBuffer

class FloorSegmenter private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    val modelName: String,

    val threads: Int,
) : AutoCloseable {

    private val inputName: String = session.inputNames.first()
    private val inputShape: LongArray = shapeOf(session.inputInfo.values.first().info)
        ?: longArrayOf(1, 512, 288, 4)
    private val outputShape: LongArray = shapeOf(session.outputInfo.values.first().info)
        ?: longArrayOf(1, 128, 72)

    val inputHeight: Int = inputShape[1].toInt()
    val inputWidth: Int = inputShape[2].toInt()
    val inputChannels: Int = inputShape[3].toInt()
    val maskHeight: Int = outputShape[1].toInt()
    val maskWidth: Int = outputShape[2].toInt()

    @Volatile
    var lastInferenceMs: Long = 0L
        private set

    fun run(frame: ByteBuffer): ByteBuffer? {
        frame.rewind()
        val startedAt = System.nanoTime()
        return try {
            OnnxTensor.createTensor(environment, frame, inputShape, OnnxJavaType.UINT8).use { input ->
                session.run(mapOf(inputName to input)).use { outputs ->
                    (outputs[0] as? OnnxTensor)?.byteBuffer
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Inferenza fallita", error)
            null
        } finally {
            lastInferenceMs = (System.nanoTime() - startedAt) / 1_000_000
        }
    }

    override fun close() {

        runCatching { session.close() }
    }

    companion object {
        private const val TAG = "Guida"

        private val CANDIDATES = listOf("floornet_int8.onnx", "floornet_fp32.onnx")

        private val THREAD_CANDIDATES = intArrayOf(2, 4, 6)

        fun create(context: Context): FloorSegmenter? {
            val environment = OrtEnvironment.getEnvironment()
            for (name in CANDIDATES) {
                val bytes = readAsset(context, name) ?: continue
                val chosen = fastestSession(environment, bytes) ?: continue
                val segmenter = FloorSegmenter(environment, chosen.first, name, chosen.second)
                Log.i(TAG, "Modello '$name': ingresso ${segmenter.inputWidth}x" +
                    "${segmenter.inputHeight}, maschera ${segmenter.maskWidth}x${segmenter.maskHeight}")
                return segmenter
            }
            Log.e(TAG, "Nessun modello negli asset (attesi: ${CANDIDATES.joinToString()})")
            return null
        }

        private fun fastestSession(
            environment: OrtEnvironment,
            model: ByteArray,
        ): Pair<OrtSession, Int>? {
            var best: OrtSession? = null
            var bestThreads = 0
            var bestNanos = Long.MAX_VALUE

            for (threads in THREAD_CANDIDATES) {
                val candidate = openSession(environment, model, threads) ?: continue
                val elapsed = timeSession(candidate)
                if (elapsed < bestNanos) {
                    best?.close()
                    best = candidate
                    bestThreads = threads
                    bestNanos = elapsed
                } else {
                    candidate.close()
                }
                Log.i(TAG, "$threads thread: %.1f ms".format(elapsed / 1e6))
            }

            val winner = best ?: return null
            Log.i(TAG, "Scelti $bestThreads thread (%.1f ms per inferenza)"
                .format(bestNanos / 1e6))
            return winner to bestThreads
        }

        private fun timeSession(session: OrtSession): Long {
            val environment = OrtEnvironment.getEnvironment()
            val shape = (session.inputInfo.values.first().info as? TensorInfo)?.shape
                ?: return Long.MAX_VALUE
            val elements = shape.fold(1L) { total, side -> total * side }
            val buffer = java.nio.ByteBuffer.allocateDirect(elements.toInt())
                .order(java.nio.ByteOrder.nativeOrder())
            val name = session.inputNames.first()

            val samples = LongArray(MEASURED_RUNS)
            return try {
                repeat(WARMUP_RUNS + MEASURED_RUNS) { run ->
                    buffer.rewind()
                    val startedAt = System.nanoTime()
                    OnnxTensor.createTensor(environment, buffer, shape, OnnxJavaType.UINT8)
                        .use { input -> session.run(mapOf(name to input)).close() }
                    if (run >= WARMUP_RUNS) samples[run - WARMUP_RUNS] = System.nanoTime() - startedAt
                }
                samples.sorted()[MEASURED_RUNS / 2]
            } catch (error: Exception) {
                Log.w(TAG, "Misura fallita: ${error.message}")
                Long.MAX_VALUE
            }
        }

        private const val WARMUP_RUNS = 2
        private const val MEASURED_RUNS = 3

        private fun readAsset(context: Context, name: String): ByteArray? = try {
            context.assets.open(name).use { it.readBytes() }
        } catch (error: Exception) {
            null
        }

        private fun openSession(
            environment: OrtEnvironment,
            model: ByteArray,
            threads: Int,
        ): OrtSession? {
            try {
                val options = OrtSession.SessionOptions()
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

                options.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                options.setIntraOpNumThreads(1)
                return environment.createSession(model, options)
            } catch (error: Exception) {
                Log.w(TAG, "XNNPACK non disponibile (${error.message}), ripiego sulla CPU.")
            }
            return try {
                val options = OrtSession.SessionOptions()
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                options.setIntraOpNumThreads(threads)
                environment.createSession(model, options)
            } catch (error: Exception) {
                Log.e(TAG, "Sessione non creata", error)
                null
            }
        }

        private fun shapeOf(info: Any?): LongArray? =
            (info as? TensorInfo)?.shape?.takeIf { shape -> shape.all { it > 0 } }
    }
}
