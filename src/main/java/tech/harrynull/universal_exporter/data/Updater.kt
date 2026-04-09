package tech.harrynull.universal_exporter.data

import appeng.me.helpers.IGridProxyable
import gregtech.api.util.LongData
import gregtech.common.misc.GlobalVariableStorage.GlobalEnergy
import gregtech.common.misc.WirelessNetworkManager
import io.prometheus.metrics.core.metrics.Counter
import io.prometheus.metrics.core.metrics.Gauge
import kekztech.common.tileentities.MTELapotronicSuperCapacitor
import net.minecraft.world.World
import tech.harrynull.universal_exporter.data.MetricType.GAUGE
import java.math.BigInteger
import java.util.UUID

private val registeredCounters = mutableMapOf<String, Counter>()
private val registeredGauges = mutableMapOf<String, Gauge>()
private val globalEnergyGauge: Gauge = Gauge.builder()
    .name("global_energy")
    .labelNames("player")
    .register()

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

fun updateGlobalEnergyGauge() {
    GlobalEnergy.forEach { user, energy ->
        globalEnergyGauge.labelValues(user.toString()).set(energy.toDouble())
    }
}

fun updateMetrics(world: World) {
    updateGlobalEnergyGauge()
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
                val itemGaugeKey = "${metric.name}_items"
                val itemGauge = registeredGauges.getOrPut(itemGaugeKey) {
                    Gauge.builder()
                        .name(itemGaugeKey)
                        .help("Number of items in AE system")
                        .labelNames("pos", "item", *metric.labels.keys.toTypedArray())
                        .register()
                }
                obj.proxy.storage.itemInventory.storageList.forEach { itemStack ->
                    val count = itemStack.stackSize
                    itemGauge
                        .labelValues(
                            "${metric.x},${metric.y},${metric.z}",
                            itemStack.itemStack.displayName,
                            *metric.labels.values.toTypedArray()
                        )
                        .set(count.toDouble())
                }
                val fluidGaugeKey = "${metric.name}_fluids"
                val fluidGauge = registeredGauges.getOrPut(fluidGaugeKey) {
                    Gauge.builder()
                        .name(fluidGaugeKey)
                        .help("Number of fluids in AE system")
                        .labelNames("pos", "fluid", *metric.labels.keys.toTypedArray())
                        .register()
                }
                obj.proxy.storage.fluidInventory.storageList.forEach { fluidStack ->
                    val count = fluidStack.stackSize
                    fluidGauge
                        .labelValues(
                            "${metric.x},${metric.y},${metric.z}",
                            fluidStack.fluidStack.localizedName,
                            *metric.labels.values.toTypedArray()
                        )
                        .set(count.toDouble())
                }
                val cpuGaugeKey = "${metric.name}_cpus"
                val cpuGauge = registeredGauges.getOrPut(cpuGaugeKey) {
                    Gauge.builder()
                        .name(cpuGaugeKey)
                        .help("AE CPU usage")
                        .labelNames("pos", "cpu", *metric.labels.keys.toTypedArray())
                        .register()
                }

                obj.proxy.crafting.cpus.forEachIndexed { index: Int, cpu ->
                    cpuGauge.labelValues(
                        "${metric.x},${metric.y},${metric.z}",
                        cpu.name?.takeIf { it.isNotBlank() } ?: "cpu$index",
                        *metric.labels.values.toTypedArray()
                    ).set(cpu.remainingItemCount.toDouble())
                }
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

                fun setGauge(suffix: String, help: String, value: Double) {
                    val key = "${metric.name}_$suffix"
                    registeredGauges.getOrPut(key) {
                        Gauge.builder().name(key).help(help)
                            .labelNames("pos", *customLabelKeys).register()
                    }.labelValues(pos, *customLabelValues).set(value)
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

                val capGaugeKey = "${metric.name}_capacitors"
                val capGauge = registeredGauges.getOrPut(capGaugeKey) {
                    Gauge.builder().name(capGaugeKey).help("LSC capacitor count by tier")
                        .labelNames("pos", "tier", *customLabelKeys).register()
                }
                val capacitors = fieldValue<IntArray>("capacitors")
                for (capacitor in MTELapotronicSuperCapacitor.Capacitor.VALUES) {
                    capGauge.labelValues(pos, capacitor.name, *customLabelValues)
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
