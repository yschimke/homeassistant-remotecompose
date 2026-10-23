package ee.schimke.terrazzo.wear

import android.app.Application
import ee.schimke.ha.rc.CardDesignLibrary
import ee.schimke.ha.rc.CardMemory
import ee.schimke.ha.rc.FileLocalDesignStore
import ee.schimke.terrazzo.wear.widget.WearNeutralTemplateStore
import ee.schimke.terrazzo.wear.widget.releaseWidgetTemplates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Keeps the card design templates the watch captures from its own converters on disk, as the phone
 * does: a template is captured once per build, display and font scale rather than on every cold
 * start, and [ee.schimke.terrazzo.wear.sync.WearCardWarmup] can capture it before the app opens
 * (the widgets' container-neutral templates likewise, [WearNeutralTemplateStore]). A watch has
 * little memory to spare, so the card caches and the widget templates go back to the system when it
 * asks ([CardMemory]).
 */
class TerrazzoWearApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    CardMemory.install(this, release = ::releaseWidgetTemplates)
    FileLocalDesignStore.of(applicationContext).also { store ->
      CardDesignLibrary.store = store
      // An earlier build's or display's templates no longer apply.
      CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { store.prune() }
    }
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
      WearNeutralTemplateStore.of(applicationContext).prune()
    }
  }
}
