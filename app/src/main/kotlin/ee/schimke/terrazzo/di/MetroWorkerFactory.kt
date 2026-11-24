package ee.schimke.terrazzo.di

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.MapKey
import dev.zacsweers.metro.SingleIn
import ee.schimke.terrazzo.core.di.AppScope
import kotlin.reflect.KClass

@MapKey
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class WorkerKey(val value: KClass<out ListenableWorker>)

fun interface AssistedWorkerFactory {
  fun create(context: Context, params: WorkerParameters): ListenableWorker
}

@SingleIn(AppScope::class)
@Inject
class MetroWorkerFactory(
  private val workerFactories: Map<KClass<out ListenableWorker>, AssistedWorkerFactory>,
) : WorkerFactory() {
  override fun createWorker(
    appContext: Context,
    workerClassName: String,
    workerParameters: WorkerParameters,
  ): ListenableWorker? {
    val workerClass =
      runCatching { Class.forName(workerClassName).asSubclass(ListenableWorker::class.java) }
        .getOrNull() ?: return null
    val factory = workerFactories[workerClass.kotlin] ?: return null
    return factory.create(appContext, workerParameters)
  }
}
