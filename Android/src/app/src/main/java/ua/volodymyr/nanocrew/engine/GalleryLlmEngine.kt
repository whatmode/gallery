package ua.volodymyr.nanocrew.engine

import android.graphics.Bitmap
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ua.volodymyr.nanocrew.core.Budget
import ua.volodymyr.nanocrew.core.DebugLog
import ua.volodymyr.nanocrew.core.LlmEngine
import ua.volodymyr.nanocrew.core.LlmRequest

/**
 * The crew's model inside Crew Gallery: whatever LiteRT-LM model Gallery has loaded for the Crew
 * task (Gemma 4 E2B/E4B…). Gallery downloads and starts it; each agent call opens its own short
 * conversation on that engine, with the agent's system prompt and sampling, and closes it after.
 */
class GalleryLlmEngine(private val currentModel: () -> Model?) : LlmEngine {

  private val mutex = Mutex()
  private var seq = 0

  private val _waiting = MutableStateFlow<String?>(null)
  /** Why the crew is waiting (model still loading), shown on the running step. */
  val waiting: StateFlow<String?> = _waiting.asStateFlow()

  /** The context the loaded model was started with (Gallery's "Max tokens" setting). */
  fun contextTokens(model: Model): Int =
    model.getIntConfigValue(ConfigKeys.MAX_TOKENS, defaultValue = 4000).coerceAtLeast(1024)

  private suspend fun instance(): Pair<Model, LlmModelInstance> {
    var waitedMs = 0L
    while (true) {
      val model = currentModel() ?: throw NanoException("Pick a model with the chip at the top first.")
      val inst = model.instance as? LlmModelInstance
      if (inst != null) {
        _waiting.value = null
        return model to inst
      }
      if (model.initStatusFlow.value is Model.InitializationStatus.Failed) {
        _waiting.value = null
        throw NanoException("${model.displayName.ifBlank { model.name }} couldn't be loaded. Try another model from the chip at the top.")
      }
      _waiting.value = "Waiting for ${model.displayName.ifBlank { model.name }} to load…"
      delay(500)
      waitedMs += 500
      if (waitedMs > 180_000) {
        _waiting.value = null
        throw NanoException("The model didn't finish loading in 3 minutes.")
      }
    }
  }

  override suspend fun generate(request: LlmRequest, onChunk: (String) -> Unit): String =
    mutex.withLock {
      val (model, inst) = instance()
      val tokens = contextTokens(model)
      Budget.useGemma = true
      Budget.gemmaTokens = tokens
      val call = ++seq
      val t0 = System.currentTimeMillis()
      val cap = (request.maxOutputTokens * OUTPUT_SCALE).coerceAtMost(tokens / 4).coerceAtLeast(64)
      val accel = model.currentAccelerator ?: Accelerator.GPU
      DebugLog.d(
        "model",
        "#$call ${model.name} · $accel · ${tokens} ctx · system ${request.system.length} + prompt ${request.prompt.length} chars · " +
          "max $cap tok · temp ${request.temperature}\nprompt: " + DebugLog.peek(request.prompt, 240),
      )
      val conversation =
        inst.engine.createConversation(
          ConversationConfig(
            systemInstruction = Contents.of(request.system),
            samplerConfig =
              if (accel == Accelerator.NPU || accel == Accelerator.TPU) null
              else
                SamplerConfig(
                  topK = request.topK.coerceAtLeast(1),
                  topP = 0.95,
                  temperature = request.temperature.toDouble().coerceAtLeast(0.0),
                ),
          )
        )
      val contents = buildList {
        (request.image as? Bitmap)?.let { add(Content.ImageBytes(it.toPng())) }
        add(Content.Text(request.prompt))
      }
      val out = StringBuilder()
      var first = true
      val capChars = cap * 4
      try {
        withContext(Dispatchers.Default) {
          conversation
            .sendMessageAsync(Contents.of(contents))
            .catch { t -> if (t is CancellationException) throw t else throw NanoException("Model error: ${t.message}") }
            .collect { msg ->
              val piece = msg.toString()
              if (piece.isEmpty()) return@collect
              if (first) {
                DebugLog.d("model", "#$call first text after ${System.currentTimeMillis() - t0} ms")
                first = false
              }
              val add =
                if (out.isNotEmpty() && piece.length > out.length && piece.startsWith(out)) piece.substring(out.length)
                else piece
              out.append(add)
              onChunk(add)
              if (out.length >= capChars) throw OutputCapReached()
            }
        }
        val ms = System.currentTimeMillis() - t0
        DebugLog.d(
          "model",
          "#$call done in $ms ms · ${out.length} chars (~${"%.1f".format(out.length / 4.0 / (ms.coerceAtLeast(1) / 1000.0))} tok/s)\nanswer: " +
            DebugLog.peek(out.toString(), 240),
        )
        out.toString()
      } catch (c: OutputCapReached) {
        runCatching { conversation.cancelProcess() }
        DebugLog.d("model", "#$call stopped at the output cap (${out.length} chars)")
        out.toString()
      } catch (c: CancellationException) {
        runCatching { conversation.cancelProcess() }
        DebugLog.d("model", "#$call cancelled after ${System.currentTimeMillis() - t0} ms")
        throw c
      } catch (t: Throwable) {
        DebugLog.e("model", "#$call failed", t)
        throw if (t is NanoException) t else NanoException("Model error: ${t.message ?: t::class.java.simpleName}")
      } finally {
        runCatching { conversation.close() }
      }
    }

  private class OutputCapReached : RuntimeException()

  private fun Bitmap.toPng(): ByteArray =
    ByteArrayOutputStream().use {
      compress(Bitmap.CompressFormat.PNG, 100, it)
      it.toByteArray()
    }

  companion object {
    /** The agents' output limits are sized for Gemini Nano; a bigger context gets three times as much. */
    const val OUTPUT_SCALE = 3
  }
}
