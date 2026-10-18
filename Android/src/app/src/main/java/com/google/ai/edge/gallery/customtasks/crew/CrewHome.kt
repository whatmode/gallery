package com.google.ai.edge.gallery.customtasks.crew

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.launch
import ua.volodymyr.nanocrew.core.Phase
import ua.volodymyr.nanocrew.ui.CrewScreen
import ua.volodymyr.nanocrew.ui.CrewViewModel
import ua.volodymyr.nanocrew.ui.HistoryScreen
import ua.volodymyr.nanocrew.ui.SettingsScreen

private val TABS = listOf("Crew", "History", "Settings")

/** The Crew task's screen inside Gallery: Nano Crew's Crew, History and Settings tabs. */
@Composable
fun CrewHome(modelManagerViewModel: ModelManagerViewModel, bottomPadding: Dp) {
  // Scoped to the activity, so a running crew survives leaving and reopening the task.
  val activity = LocalActivity.current as? ComponentActivity
  val vm: CrewViewModel =
    if (activity != null) viewModel(viewModelStoreOwner = activity) else viewModel()

  val mmState by modelManagerViewModel.uiState.collectAsState()
  val model = mmState.selectedModel
  val initStatus by model.initStatusFlow.collectAsState()
  LaunchedEffect(model.name, initStatus, mmState.configValuesUpdateTrigger) {
    vm.attachModel(model, initStatus)
  }

  var tab by rememberSaveable { mutableIntStateOf(0) }
  val status by vm.modelStatus.collectAsStateWithLifecycle()
  val run by vm.run.collectAsStateWithLifecycle()
  val history by vm.history.collectAsStateWithLifecycle()
  val config by vm.config.collectAsStateWithLifecycle()
  val notes by vm.noteCount.collectAsStateWithLifecycle()
  val files by vm.crewFiles.collectAsStateWithLifecycle()
  val importing by vm.importing.collectAsStateWithLifecycle()
  val fileError by vm.fileError.collectAsStateWithLifecycle()
  val outputMessage by vm.outputMessage.collectAsStateWithLifecycle()
  val waiting by vm.engine.waiting.collectAsStateWithLifecycle()
  val debugLines by vm.debugLines.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  var memoryText by remember { androidx.compose.runtime.mutableStateOf(memoryLine(context)) }
  LaunchedEffect(tab) { if (tab == 2) memoryText = memoryLine(context) }

  // Keep the screen on while the agents work.
  val working = run?.phase == Phase.RUNNING
  val view = LocalView.current
  DisposableEffect(working) {
    view.keepScreenOn = working
    onDispose { view.keepScreenOn = false }
  }

  val loadCrash by ua.volodymyr.nanocrew.core.LoadWatch.lastCrash.collectAsStateWithLifecycle()

  Column(Modifier.fillMaxSize().padding(bottom = bottomPadding).imePadding()) {
    PrimaryTabRow(selectedTabIndex = tab) {
      TABS.forEachIndexed { i, label ->
        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
      }
    }
    loadCrash?.let { msg ->
      androidx.compose.material3.Surface(
        color = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
      ) {
        androidx.compose.foundation.layout.Row(
          Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
          verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
          Text(
            msg,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
          )
          androidx.compose.material3.TextButton(onClick = { ua.volodymyr.nanocrew.core.LoadWatch.dismiss() }) { Text("OK") }
        }
      }
    }
    val m = Modifier.fillMaxWidth().weight(1f)
    when (tab) {
      0 ->
        CrewScreen(
          run = run,
          modelStatus = status,
          prefill = null,
          onPrefillUsed = {},
          onStart = vm::startCrew,
          onStop = vm::stopCrew,
          onNew = vm::newCrewTask,
          onCheckModel = { vm.checkModel() },
          onDownload = {},
          modifier = m,
          files = files,
          importing = importing,
          fileError = fileError,
          onFilesPicked = { vm.addFiles(it, CrewViewModel.Tray.CREW) },
          onRemoveFile = { vm.removeFile(it, CrewViewModel.Tray.CREW) },
          onDismissFileError = vm::dismissFileError,
          onSaveOutput = { r, uri -> vm.saveOutput(r, uri) },
          outputMessage = outputMessage,
          onDismissOutputMessage = vm::dismissOutputMessage,
          waiting = waiting,
          onApplyReview = vm::applyReview,
          onDiscardReview = { vm.discardReview() },
        )
      1 ->
        HistoryScreen(
          runs = history,
          onOpen = {
            vm.openRun(it)
            tab = 0
          },
          onDelete = { vm.deleteRun(it) },
          onClearAll = { vm.clearHistory() },
          modifier = m,
        )
      else ->
        SettingsScreen(
          config = config,
          modelStatus = status,
          noteCount = notes,
          onChange = vm::updateConfig,
          onResetPrompts = { vm.resetPrompts() },
          onClearNotes = { vm.clearNotes() },
          onCheckModel = { vm.checkModel() },
          modifier = m,
          debugLines = debugLines,
          debugBytes = remember(debugLines.size) { vm.debugLogSize },
          onShareDebug = { vm.shareDebugLog(context) },
          onClearDebug = { vm.clearDebugLog() },
          memoryText = memoryText,
          onFreeMemory = {
            val before = freeMb(context)
            var unloaded = 0
            for (t in mmState.tasks) {
              for (m in t.models) {
                if (m.instance != null && m.name != model.name) {
                  unloaded++
                  modelManagerViewModel.cleanupModel(context = context, task = t, model = m)
                }
              }
            }
            System.gc()
            Runtime.getRuntime().gc()
            scope.launch {
              kotlinx.coroutines.delay(1500)
              val after = freeMb(context)
              memoryText = memoryLine(context)
              ua.volodymyr.nanocrew.core.DebugLog.d("memory", "free memory: unloaded $unloaded models · $before → $after MB free")
              android.widget.Toast.makeText(
                context,
                "Unloaded $unloaded other model${if (unloaded == 1) "" else "s"} · free ${before} → ${after} MB",
                android.widget.Toast.LENGTH_LONG,
              ).show()
            }
          },
        )
    }
  }
}

private fun memInfo(context: android.content.Context): android.app.ActivityManager.MemoryInfo =
  android.app.ActivityManager.MemoryInfo().also {
    context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it)
  }

private fun freeMb(context: android.content.Context) = memInfo(context).availMem / 1_048_576

private fun memoryLine(context: android.content.Context): String {
  val mi = memInfo(context)
  val gb = { b: Long -> "%.1f".format(b / 1_073_741_824.0) }
  return "${gb(mi.availMem)} GB free of ${gb(mi.totalMem)} GB" + if (mi.lowMemory) " · low!" else ""
}
