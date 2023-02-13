package org.emulinker.kaillera.pico

import com.codahale.metrics.ConsoleReporter
import com.codahale.metrics.CsvReporter
import com.codahale.metrics.MetricFilter
import com.google.common.flogger.FluentLogger
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.MINUTES
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO_PARALLELISM_PROPERTY_NAME
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val logger = FluentLogger.forEnclosingClass()

/** Main entry point for the Kaillera server. */
fun main(): Unit = runBlocking {
  val component = DaggerAppComponent.create()
  val flags = component.runtimeFlags
  // Change number of Dispatchers.IO coroutines.
  System.setProperty(IO_PARALLELISM_PROPERTY_NAME, flags.numIoDispatchers.toString())
  // Use log4j as the flogger backend.
  System.setProperty(
    "flogger.backend_factory",
    "com.google.common.flogger.backend.log4j2.Log4j2BackendFactory#getInstance"
  )

  logger.atInfo().log("EmuLinker server Starting...")
  logger.atInfo().log(component.releaseInfo.welcome)
  logger
    .atInfo()
    .log(
      "EmuLinker server is running @ %s",
      DateTimeFormatter.ISO_ZONED_DATE_TIME.withZone(ZoneId.systemDefault()).format(Instant.now())
    )

  component.kailleraServerController.start() // Apparently cannot be removed.
  launch(Dispatchers.IO) {
    component.server.start(
      component.udpSocketProvider,
      coroutineContext + CoroutineName("ConnectServer")
    )
  }

  component.masterListUpdater.start()
  if (flags.metricsEnabled) {
    val metrics = component.metricRegistry
    //    metrics.registerAll(ThreadStatesGaugeSet())
    //    metrics.registerAll(MemoryUsageGaugeSet())

    // TODO(nue): Pass this data to a central server so we can see how performance changes over
    // time in prod.
    // "graphite" is the name of a service in docker-compose.yaml.
    //    val graphite = Graphite(java.net.InetSocketAddress("graphite", 2003))
    //    val reporter =
    //      GraphiteReporter.forRegistry(metrics)
    //        .convertRatesTo(TimeUnit.SECONDS)
    //        .convertDurationsTo(TimeUnit.MILLISECONDS)
    //        .filter(MetricFilter.ALL)
    //        .build(graphite)

    ConsoleReporter.forRegistry(metrics)
      .convertRatesTo(TimeUnit.SECONDS)
      .convertDurationsTo(TimeUnit.MILLISECONDS)
      .filter(MetricFilter.ALL)
      .build()
      .start(10, MINUTES)

    val file = File("./metrics/")
    file.mkdirs()
    CsvReporter.forRegistry(metrics)
      .convertRatesTo(TimeUnit.SECONDS)
      .convertDurationsTo(TimeUnit.MILLISECONDS)
      .filter(MetricFilter.ALL)
      .build(file)
      .start(5, MINUTES)
  }
}
