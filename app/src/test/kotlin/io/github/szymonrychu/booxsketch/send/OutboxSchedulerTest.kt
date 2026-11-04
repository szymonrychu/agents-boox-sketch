package io.github.szymonrychu.booxsketch.send

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OutboxSchedulerTest {
    private lateinit var context: Context
    private lateinit var scheduler: OutboxScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        scheduler = OutboxScheduler(context)
    }

    private fun infos(name: String) = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()

    private fun laterInfos() = OutboxScheduler.LATER_WORKS.flatMap(::infos)

    private fun pendingLater() = laterInfos().filter { it.state == WorkInfo.State.ENQUEUED }

    @Test
    fun a_sooner_backoff_is_not_queued_behind_an_older_long_one() {
        scheduler.later(300_000)
        scheduler.later(1_000)
        val pending = pendingLater()
        assertThat(pending).hasSize(1)
        assertThat(pending.single().nextScheduleTimeMillis).isLessThan(System.currentTimeMillis() + 10_000)
    }

    @Test
    fun a_later_backoff_is_covered_by_the_sooner_pending_run() {
        scheduler.later(1_000)
        scheduler.later(300_000)
        val pending = pendingLater()
        assertThat(pending).hasSize(1)
        assertThat(pending.single().nextScheduleTimeMillis).isLessThan(System.currentTimeMillis() + 10_000)
    }

    @Test
    fun kick_is_not_delayed_by_a_pending_backoff_run() {
        scheduler.later(300_000)
        scheduler.kick()
        val kick = infos(OutboxScheduler.KICK_WORK).single()
        assertThat(kick.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(kick.nextScheduleTimeMillis).isLessThan(System.currentTimeMillis() + 5_000)
        // The backoff run is still queued, untouched.
        assertThat(pendingLater()).hasSize(1)
    }

    @Test
    fun cancel_all_drops_every_run() {
        scheduler.later(60_000)
        scheduler.kick()
        scheduler.cancelAll()
        val states = (laterInfos() + infos(OutboxScheduler.KICK_WORK)).map { it.state }
        assertThat(states).isNotEmpty()
        assertThat(states.toSet()).containsExactly(WorkInfo.State.CANCELLED)
    }

    @Test
    fun later_called_from_the_running_worker_does_not_cancel_it() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<WorkInfo.State>()
        val done = java.util.concurrent.CountDownLatch(1)
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker =
                    object : CoroutineWorker(appContext, workerParameters) {
                        override suspend fun doWork(): Result {
                            val wm = WorkManager.getInstance(appContext)
                            seen += wm.getWorkInfoById(id).get()!!.state
                            // The running run needs another backoff run, as the runner does.
                            scheduler.later(60_000)
                            seen += wm.getWorkInfoById(id).get()!!.state
                            done.countDown()
                            return Result.success()
                        }
                    }
            }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setWorkerFactory(factory).build())
        scheduler.later(0)
        val first = laterInfos().single()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(first.id)
        assertThat(done.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
        assertThat(seen).containsExactly(WorkInfo.State.RUNNING, WorkInfo.State.RUNNING)
        assertThat(pendingLater()).hasSize(1)
    }
}
