package tech.harrynull.universal_exporter

import net.minecraftforge.common.config.Configuration
import java.io.File

object Config {
    var host: String = "0.0.0.0"
    var port: Int = 9400
    var updateIntervalTicks: Int = 100
    var noMetricNamePrefix: Boolean = false

    object GlobalMetrics {
        var globalEnergy: Boolean = true
        var tps: Boolean = true
        var mspt: Boolean = true
        var onlinePlayers: Boolean = true
        var loadedChunks: Boolean = true
        var entityCount: Boolean = true
        var tileEntityCount: Boolean = true
        var dimMspt: Boolean = true
        var jvmHeap: Boolean = true
        var gcPause: Boolean = true
        var serverUtilitiesTeams: Boolean = true
    }

    private const val CATEGORY_GLOBAL_METRICS = "globalMetrics"

    @JvmStatic
    fun synchronizeConfiguration(configFile: File?) {
        val configuration = Configuration(configFile)

        host = configuration.getString(
            "host",
            Configuration.CATEGORY_GENERAL,
            "0.0.0.0",
            "Host to bind to for the metrics server"
        )
        port = configuration.getInt(
            "port",
            Configuration.CATEGORY_GENERAL,
            9400,
            0,
            65536,
            "Port to bind to for the metrics server"
        )
        updateIntervalTicks = configuration.getInt(
            "updateIntervalTicks",
            Configuration.CATEGORY_GENERAL,
            100,
            1,
            Integer.MAX_VALUE,
            "Gauge value update intervals"
        )
        noMetricNamePrefix = configuration.getBoolean(
            "noMetricNamePrefix",
            Configuration.CATEGORY_GENERAL,
            false,
            "When enabled, metric names are not prefixed with the exporter name. Use the 'exporter' label to identify which instance a metric belongs to."
        )

        GlobalMetrics.globalEnergy = configuration.getBoolean("globalEnergy", CATEGORY_GLOBAL_METRICS, true, "Export global wireless energy per player")
        GlobalMetrics.tps = configuration.getBoolean("tps", CATEGORY_GLOBAL_METRICS, true, "Export server TPS (1-minute average)")
        GlobalMetrics.mspt = configuration.getBoolean("mspt", CATEGORY_GLOBAL_METRICS, true, "Export server milliseconds per tick")
        GlobalMetrics.onlinePlayers = configuration.getBoolean("onlinePlayers", CATEGORY_GLOBAL_METRICS, true, "Export online/offline status per player")
        GlobalMetrics.loadedChunks = configuration.getBoolean("loadedChunks", CATEGORY_GLOBAL_METRICS, true, "Export loaded chunk count per dimension")
        GlobalMetrics.entityCount = configuration.getBoolean("entityCount", CATEGORY_GLOBAL_METRICS, true, "Export loaded entity count per dimension")
        GlobalMetrics.tileEntityCount = configuration.getBoolean("tileEntityCount", CATEGORY_GLOBAL_METRICS, true, "Export loaded tile entity count per dimension")
        GlobalMetrics.dimMspt = configuration.getBoolean("dimMspt", CATEGORY_GLOBAL_METRICS, true, "Export milliseconds per tick per dimension")
        GlobalMetrics.jvmHeap = configuration.getBoolean("jvmHeap", CATEGORY_GLOBAL_METRICS, true, "Export JVM heap used/max bytes")
        GlobalMetrics.gcPause = configuration.getBoolean("gcPause", CATEGORY_GLOBAL_METRICS, true, "Export JVM GC pause time")
        GlobalMetrics.serverUtilitiesTeams = configuration.getBoolean("serverUtilitiesTeams", CATEGORY_GLOBAL_METRICS, true, "Export ServerUtilities claimed/loaded chunk counts per team")

        if (configuration.hasChanged()) {
            configuration.save()
        }
    }
}
