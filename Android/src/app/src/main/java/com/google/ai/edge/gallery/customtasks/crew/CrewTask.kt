package com.google.ai.edge.gallery.customtasks.crew

import android.content.Context
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.NumberSliderConfig
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.litertlm.Contents
import kotlinx.coroutines.CoroutineScope

/** Model names (from Gallery's model list) the crew can run on. */
val CREW_MODEL_NAMES =
  listOf("Gemma-4-E2B-it", "Gemma-4-E4B-it", "Gemma-3n-E2B-it", "Gemma-3n-E4B-it", "Qwen2.5-1.5B-Instruct")

/** The context the crew asks for when the model allows it (Gallery's default is 4000). */
private const val CREW_CONTEXT_TOKENS = 16000

/**
 * Nano Crew as a Gallery task: a router, planner, workers, critic and writer that take turns on
 * the model Gallery loads, with files, zip editing, follow-ups, undo and history.
 */
class CrewTask(private val context: Context) : CustomTask {
  init {
    // Reports a load that closed the app last time (checked once per app start).
    ua.volodymyr.nanocrew.core.LoadWatch.init(context)
  }

  override val task: Task =
    Task(
      id = CREW_TASK_ID,
      label = "Crew",
      category = Category.LLM,
      icon = Icons.Outlined.Groups,
      newFeature = true,
      models = mutableListOf(),
      modelNames = CREW_MODEL_NAMES,
      description =
        "A crew of on-device agents (router, planner, workers, critic, writer) that work through " +
          "a task together. Attach files or a zip: the crew reads them, answers, or edits files " +
          "inside the zip and gives you the new one. Reply to continue the same task; say \"undo\" " +
          "to take the last change back.",
      shortDescription = "Agents that plan, read, edit and check",
      sourceCodeUrl = "https://github.com/whatmode/gallery",
      supportImage = true,
    )

  // The crew keeps working while you look at another screen.
  override val keepModelAlive: Boolean
    get() = true

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    raiseContext(model)
    val tokens = model.getIntConfigValue(ConfigKeys.MAX_TOKENS, defaultValue = 4000)
    val file = runCatching { java.io.File(model.getPath(context)).length() / 1_048_576 }.getOrDefault(-1L)
    val what = "${model.name} (${file} MB file, $tokens tokens, ${model.currentAccelerator?.label ?: "GPU"})"
    ua.volodymyr.nanocrew.core.LoadWatch.starting(context, what)
    LlmChatModelHelper.initialize(
      context = context,
      model = model,
      taskId = task.id,
      supportImage = task.isVisionEnabled(model),
      supportAudio = false,
      onDone = { error ->
        ua.volodymyr.nanocrew.core.LoadWatch.finished(context, what, error)
        onDone(error)
      },
      systemInstruction = null,
      tools = emptyList(),
      enableConversationConstrainedDecoding = false,
      initialMessages = emptyList(),
      coroutineScope = coroutineScope,
      enableResponseFormat = false,
    )
  }

  /**
   * Gallery starts LLMs with 4000 tokens; the crew sends whole files, so it asks for 16000 when the
   * model's slider allows it and you haven't picked a value of your own.
   */
  private fun raiseContext(model: Model) {
    val slider =
      model.configs.firstOrNull { it.key == ConfigKeys.MAX_TOKENS } as? NumberSliderConfig ?: return
    val current = model.getIntConfigValue(ConfigKeys.MAX_TOKENS, defaultValue = 4000)
    val target = CREW_CONTEXT_TOKENS.coerceAtMost(slider.sliderMax.toInt())
    if (current == slider.defaultValue.toInt() && target > current) {
      Log.d(TAG, "raising ${model.name} context $current → $target for the crew")
      model.configValues = model.configValues + (ConfigKeys.MAX_TOKENS.label to target.toFloat())
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    LlmChatModelHelper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val d = data as CustomTaskData
    CrewHome(modelManagerViewModel = d.modelManagerViewModel, bottomPadding = d.bottomPadding)
  }

  companion object {
    const val CREW_TASK_ID = "nano_crew"
    private const val TAG = "CrewTask"
  }
}
