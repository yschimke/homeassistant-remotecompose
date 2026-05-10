package ee.schimke.terrazzo.monitor

import android.content.Context
import ee.schimke.terrazzo.core.monitor.CardMonitor

fun createMonitor(context: Context): CardMonitor = NoOpCardMonitor()
