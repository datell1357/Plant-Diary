package com.planterior.helper.feature.weather

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

internal class RebindableWeatherLocationGateway(initial: WeatherLocationGateway) :
    WeatherLocationGateway {
    private val bindings = MutableStateFlow(Binding(0L, initial))

    fun update(gateway: WeatherLocationGateway) {
        synchronized(bindings) {
            val current = bindings.value
            if (current.gateway === gateway) return
            current.gateway?.cancel()
            bindings.value = Binding(current.generation + 1, gateway)
        }
    }

    fun remove(gateway: WeatherLocationGateway) {
        synchronized(bindings) {
            val current = bindings.value
            if (current.gateway !== gateway) return
            gateway.cancel()
            bindings.value = Binding(current.generation + 1, null)
        }
    }

    override fun permission(): LocationPermission =
        bindings.value.gateway?.permission() ?: LocationPermission.Denied(canAskAgain = true)

    override suspend fun requestPermission(): LocationPermission =
        withLatest(WeatherLocationGateway::requestPermission)

    override suspend fun approximateLocation(): ApproximateLocation? =
        withLatest(WeatherLocationGateway::approximateLocation)

    override fun cancel() {
        bindings.value.gateway?.cancel()
    }

    private suspend fun <T> withLatest(operation: suspend (WeatherLocationGateway) -> T): T {
        while (true) {
            val binding = bindings.first { it.gateway != null }
            try {
                when (
                    val outcome = coroutineScope {
                        val value = async { operation(requireNotNull(binding.gateway)) }
                        val rebound = async {
                            bindings.first { it.generation != binding.generation }
                        }
                        try {
                            select<GatewayOutcome<T>> {
                                value.onAwait { GatewayOutcome.Value(it) }
                                rebound.onAwait { GatewayOutcome.Rebound }
                            }
                        } finally {
                            value.cancel()
                            rebound.cancel()
                        }
                    }
                ) {
                    is GatewayOutcome.Value -> {
                        if (bindings.value.generation == binding.generation) {
                            return outcome.value
                        }
                    }
                    GatewayOutcome.Rebound -> Unit
                }
            } catch (error: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (bindings.value.generation == binding.generation) {
                    bindings.first { it.generation != binding.generation }
                }
            } catch (error: Exception) {
                if (bindings.value.generation == binding.generation) throw error
            }
        }
    }

    private data class Binding(
        val generation: Long,
        val gateway: WeatherLocationGateway?,
    )

    private sealed interface GatewayOutcome<out T> {
        data class Value<T>(val value: T) : GatewayOutcome<T>

        data object Rebound : GatewayOutcome<Nothing>
    }
}
