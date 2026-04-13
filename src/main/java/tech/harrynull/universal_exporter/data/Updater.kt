package tech.harrynull.universal_exporter.data

import appeng.me.helpers.IGridProxyable
import gregtech.api.util.LongData
import gregtech.common.misc.GlobalVariableStorage.GlobalEnergy
import gregtech.common.misc.WirelessNetworkManager
import io.prometheus.metrics.core.metrics.Counter
import io.prometheus.metrics.core.metrics.Gauge
import kekztech.common.tileentities.MTELapotronicSuperCapacitor
import net.minecraft.server.MinecraftServer
import net.minecraft.world.World
import tech.harrynull.universal_exporter.Config
import tech.harrynull.universal_exporter.data.MetricType.GAUGE
import java.lang.management.ManagementFactory
import java.math.BigInteger
import java.util.UUID

private val registeredCounters = mutableMapOf<String, Counter>()
private val registeredGauges = mutableMapOf<String, Gauge>()
private val globalEnergyGauge: Gauge = Gauge.builder()
    .name("global_energy")
    .labelNames("player")
    .register()
private val tpsGauge: Gauge = Gauge.builder()
    .name("tps")
    .help("Server ticks per second (derived from recent tick times)")
    .register()
private val msptGauge: Gauge = Gauge.builder()
    .name("mspt")
    .help("Average milliseconds per tick")
    .register()
private val onlinePlayersGauge: Gauge = Gauge.builder()
    .name("online_players")
    .help("1 if player is online, 0 if offline")
    .labelNames("player")
    .register()
private val knownPlayers = mutableSetOf<String>()
private val loadedChunksGauge: Gauge = Gauge.builder()
    .name("loaded_chunks")
    .help("Number of loaded chunks per dimension")
    .labelNames("dim")
    .register()
private val entityCountGauge: Gauge = Gauge.builder()
    .name("entity_count")
    .help("Number of loaded entities per dimension")
    .labelNames("dim")
    .register()
private val tileEntityCountGauge: Gauge = Gauge.builder()
    .name("tile_entity_count")
    .help("Number of loaded tile entities per dimension")
    .labelNames("dim")
    .register()
private val dimMsptGauge: Gauge = Gauge.builder()
    .name("dim_mspt")
    .help("Average milliseconds per tick per dimension")
    .labelNames("dim")
    .register()
private val jvmHeapUsedGauge: Gauge = Gauge.builder()
    .name("jvm_heap_used_bytes")
    .help("JVM heap memory used in bytes")
    .register()
private val jvmHeapMaxGauge: Gauge = Gauge.builder()
    .name("jvm_heap_max_bytes")
    .help("JVM heap memory max in bytes")
    .register()
private val gcPauseGauge: Gauge = Gauge.builder()
    .name("jvm_gc_pause_seconds")
    .help("Total GC pause time in seconds")
    .labelNames("gc")
    .register()
private val teamClaimedChunksGauge: Gauge = Gauge.builder()
    .name("team_claimed_chunks")
    .help("Number of chunks claimed per team")
    .labelNames("team")
    .register()
private val teamLoadedChunksGauge: Gauge = Gauge.builder()
    .name("team_loaded_chunks")
    .help("Number of force-loaded chunks per team")
    .labelNames("team")
    .register()
private val serverUtilitiesAvailable by lazy {
    cpw.mods.fml.common.Loader.isModLoaded("serverutilities")
}

fun updateCounter(world: World, x: Int, y: Int, z: Int, type: String, amount: Double = 1.0) {
    val metric = TrackedMetrics.get(world).getMetrics().find {
        it.type == MetricType.COUNTER && it.x == x && it.y == y && it.z == z && it.value == type
    } ?: return
    val counter = registeredCounters.getOrPut(metric.name) {
        Counter.builder()
            .name(metric.name)
            .help("Counter metric $metric")
            .labelNames(*metric.labels.keys.toTypedArray())
            .register()
    }
    counter.labelValues(*metric.labels.values.toTypedArray()).inc(amount)
    TrackedMetrics.setMetricActive(metric)
}

fun updateGlobalMetrics() {
    val cfg = Config.GlobalMetrics
    if (cfg.globalEnergy) GlobalEnergy.forEach { user, energy ->
        globalEnergyGauge.labelValues(user.toString()).set(energy.toDouble())
    }
    val server = MinecraftServer.getServer()
    val avgMspt = server.tickTimeArray.average() / 1_000_000.0
    if (cfg.tps) tpsGauge.set(minOf(1000.0 / avgMspt, 20.0))
    if (cfg.mspt) msptGauge.set(avgMspt)
    if (cfg.onlinePlayers) {
        @Suppress("UNCHECKED_CAST")
        val onlinePlayers = (server.configurationManager.playerEntityList as List<net.minecraft.entity.player.EntityPlayerMP>)
            .map { it.commandSenderName }.toSet()
        knownPlayers.addAll(onlinePlayers)
        knownPlayers.forEach { player ->
            onlinePlayersGauge.labelValues(player).set(if (player in onlinePlayers) 1.0 else 0.0)
        }
    }
    if (cfg.jvmHeap) {
        val runtime = Runtime.getRuntime()
        jvmHeapUsedGauge.set((runtime.totalMemory() - runtime.freeMemory()).toDouble())
        jvmHeapMaxGauge.set(runtime.maxMemory().toDouble())
    }
    if (cfg.gcPause) ManagementFactory.getGarbageCollectorMXBeans().forEach { gc ->
        if (gc.collectionTime >= 0) gcPauseGauge.labelValues(gc.name).set(gc.collectionTime / 1000.0)
    }
    if (cfg.serverUtilitiesTeams && serverUtilitiesAvailable) {
        updateServerUtilitiesMetrics()
    }
}

private fun updateServerUtilitiesMetrics() {
    if (!serverutils.data.ClaimedChunks.isActive()) return
    val universe = serverutils.lib.data.Universe.get() ?: return
    for (team in universe.teams) {
        if (!team.isValid) continue
        val chunks = serverutils.data.ServerUtilitiesTeamData.get(team).teamChunks
        teamClaimedChunksGauge.labelValues(team.id).set(chunks.size.toDouble())
        teamLoadedChunksGauge.labelValues(team.id).set(chunks.count { it.isLoaded }.toDouble())
    }
}

@Suppress("UNCHECKED_CAST")
fun updateMetrics(world: World) {
    updateGlobalMetrics()
    val cfg = Config.GlobalMetrics
    val dim = world.provider.getDimensionName()
    if (cfg.loadedChunks) loadedChunksGauge.labelValues(dim).set(world.chunkProvider.loadedChunkCount.toDouble())
    if (cfg.entityCount) entityCountGauge.labelValues(dim).set((world.loadedEntityList as List<*>).size.toDouble())
    if (cfg.tileEntityCount) tileEntityCountGauge.labelValues(dim).set((world.loadedTileEntityList as List<*>).size.toDouble())
    if (cfg.dimMspt) MinecraftServer.getServer().worldTickTimes[world.provider.dimensionId]
        ?.let { dimMsptGauge.labelValues(dim).set(it.average() / 1_000_000.0) }
    val metrics = TrackedMetrics.get(world)
    for (metric in metrics.getMetrics()) {
        when (metric.type) {
            GAUGE -> {
                val obj = objectFromWorld(world, metric.x, metric.y, metric.z)
                val value = accessObject(obj, metric.value) ?: continue
                val sameNameMetrics = metrics.getMetrics()
                    .filter { it.name == metric.name }
                val gauge = registeredGauges.getOrPut(metric.name) {
                    Gauge.builder()
                        .name(metric.name)
                        .help(
                            "Gauge metric ${
                                sameNameMetrics.joinToString(", ")
                            }"
                        )
                        .labelNames(
                            "pos",
                            *(sameNameMetrics
                                .flatMap { it.labels.keys }
                                .toSet()
                                .toTypedArray()
                                )
                        )
                        .register()
                }
                gauge.labelValues("${metric.x},${metric.y},${metric.z}", *metric.labels.values.toTypedArray())
                    .set(value)
            }

            MetricType.AE -> {
                val obj = objectFromWorld(world, metric.x, metric.y, metric.z)
                if (obj !is IGridProxyable) {
                    continue
                }
                try {
                val prefix = if (Config.noMetricNamePrefix) "ae" else metric.name
                val itemGaugeKey = "${prefix}_items"
                val itemGauge = registeredGauges.getOrPut(itemGaugeKey) {
                    Gauge.builder()
                        .name(itemGaugeKey)
                        .help("Number of items in AE system")
                        .labelNames("pos", "exporter", "item", *metric.labels.keys.toTypedArray())
                        .register()
                }
                obj.proxy.storage.itemInventory.storageList.forEach { itemStack ->
                    val count = itemStack.stackSize
                    itemGauge
                        .labelValues(
                            "${metric.x},${metric.y},${metric.z}",
                            metric.name,
                            itemStack.itemStack.displayName,
                            *metric.labels.values.toTypedArray()
                        )
                        .set(count.toDouble())
                }
                val fluidGaugeKey = "${prefix}_fluids"
                val fluidGauge = registeredGauges.getOrPut(fluidGaugeKey) {
                    Gauge.builder()
                        .name(fluidGaugeKey)
                        .help("Number of fluids in AE system")
                        .labelNames("pos", "exporter", "fluid", *metric.labels.keys.toTypedArray())
                        .register()
                }
                obj.proxy.storage.fluidInventory.storageList.forEach { fluidStack ->
                    val count = fluidStack.stackSize
                    fluidGauge
                        .labelValues(
                            "${metric.x},${metric.y},${metric.z}",
                            metric.name,
                            fluidStack.fluidStack.localizedName,
                            *metric.labels.values.toTypedArray()
                        )
                        .set(count.toDouble())
                }
                val cpuGaugeKey = "${prefix}_cpus"
                val cpuGauge = registeredGauges.getOrPut(cpuGaugeKey) {
                    Gauge.builder()
                        .name(cpuGaugeKey)
                        .help("AE CPU usage")
                        .labelNames("pos", "exporter", "cpu", *metric.labels.keys.toTypedArray())
                        .register()
                }

                obj.proxy.crafting.cpus.forEachIndexed { index: Int, cpu ->
                    cpuGauge.labelValues(
                        "${metric.x},${metric.y},${metric.z}",
                        metric.name,
                        cpu.name?.takeIf { it.isNotBlank() } ?: "cpu$index",
                        *metric.labels.values.toTypedArray()
                    ).set(cpu.remainingItemCount.toDouble())
                }
                } catch (_: appeng.me.GridAccessException) { continue }
            }

            MetricType.LSC -> {
                val obj = objectFromWorld(world, metric.x, metric.y, metric.z)
                if (obj !is MTELapotronicSuperCapacitor) continue

                fun lscField(name: String) = getAllFieldsIncludingInherited(obj.javaClass)
                    .first { it.name == name }.also { it.isAccessible = true }

                @Suppress("UNCHECKED_CAST")
                fun <T> fieldValue(name: String): T = lscField(name).get(obj) as T

                val pos = "${metric.x},${metric.y},${metric.z}"
                val customLabelKeys = metric.labels.keys.toTypedArray()
                val customLabelValues = metric.labels.values.toTypedArray()

                val lscPrefix = if (Config.noMetricNamePrefix) "lsc" else metric.name
                fun setGauge(suffix: String, help: String, value: Double) {
                    val key = "${lscPrefix}_$suffix"
                    registeredGauges.getOrPut(key) {
                        Gauge.builder().name(key).help(help)
                            .labelNames("pos", "exporter", *customLabelKeys).register()
                    }.labelValues(pos, metric.name, *customLabelValues).set(value)
                }

                setGauge("stored", "Current EU stored in LSC",
                    fieldValue<BigInteger>("stored").toDouble())
                setGauge("capacity", "Total EU capacity of LSC",
                    fieldValue<BigInteger>("capacity").toDouble())
                setGauge("passive_drain_per_tick", "EU/t passive discharge",
                    fieldValue<Long>("passiveDischargeAmount").toDouble())
                setGauge("input_last_tick", "EU input last tick",
                    fieldValue<Long>("inputLastTick").toDouble())
                setGauge("output_last_tick", "EU output last tick",
                    fieldValue<Long>("outputLastTick").toDouble())
                setGauge("max_eu_in", "Max EU/t input rate",
                    fieldValue<Long>("mMaxEUIn").toDouble())
                setGauge("max_eu_out", "Max EU/t output rate",
                    fieldValue<Long>("mMaxEUOut").toDouble())
                setGauge("wireless_mode", "1 if wireless mode is enabled",
                    if (fieldValue<Boolean>("wireless_mode")) 1.0 else 0.0)

                setGauge("avg_input_100t", "Average EU/t input over last 100 ticks",
                    fieldValue<LongData>("energyInputValues").avgLong().toDouble())
                setGauge("avg_output_100t", "Average EU/t output over last 100 ticks",
                    fieldValue<LongData>("energyOutputValues").avgLong().toDouble())
                setGauge("avg_input_5m", "Average EU/t input over last 5 minutes",
                    fieldValue<LongData>("energyInputValues5m").avgLong().toDouble())
                setGauge("avg_output_5m", "Average EU/t output over last 5 minutes",
                    fieldValue<LongData>("energyOutputValues5m").avgLong().toDouble())
                setGauge("avg_input_1h", "Average EU/t input over last hour",
                    fieldValue<LongData>("energyInputValues1h").avgLong().toDouble())
                setGauge("avg_output_1h", "Average EU/t output over last hour",
                    fieldValue<LongData>("energyOutputValues1h").avgLong().toDouble())

                val uuid = fieldValue<UUID?>("global_energy_user_uuid")
                if (uuid != null) {
                    setGauge("wireless_stored", "EU stored in wireless network",
                        WirelessNetworkManager.getUserEU(uuid).toDouble())
                }

                val capGaugeKey = "${lscPrefix}_capacitors"
                val capGauge = registeredGauges.getOrPut(capGaugeKey) {
                    Gauge.builder().name(capGaugeKey).help("LSC capacitor count by tier")
                        .labelNames("pos", "exporter", "tier", *customLabelKeys).register()
                }
                val capacitors = fieldValue<IntArray>("capacitors")
                for (capacitor in MTELapotronicSuperCapacitor.Capacitor.VALUES) {
                    capGauge.labelValues(pos, metric.name, capacitor.name, *customLabelValues)
                        .set(capacitors[capacitor.ordinal].toDouble())
                }
            }

            MetricType.COUNTER -> {
                continue
            }
        }
        TrackedMetrics.setMetricActive(metric)
    }
}
