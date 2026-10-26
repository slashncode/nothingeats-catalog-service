package io.github.slashncode.nothingeats.catalog.adapters.deals

import io.github.slashncode.nothingeats.catalog.application.PlanDeals
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Plans flash deals on every pod once a minute, so a new deal starts within a minute of the last
 * one ending. A loop in the serving pods, not a CronJob: no JVM start every few minutes, and the
 * planning lock keeps two pods from planning at once.
 */
class DealPlanner(
    private val plan: PlanDeals,
    private val interval: Duration = 1.minutes,
) : LifecycleComponent {
    override val name = "deal-planner"
    override val order = LifecycleOrder.TRAFFIC

    private val log = LoggerFactory.getLogger(DealPlanner::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override suspend fun onReady() {
        job =
            scope.launch {
                while (isActive) {
                    try {
                        val planned = plan()
                        if (planned.started.isNotEmpty()) log.info("Started {} flash deals", planned.started.size)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("Planning deals failed; trying again next round", e)
                    }
                    delay(interval)
                }
            }
    }

    override suspend fun stop() {
        job?.cancelAndJoin()
    }
}
