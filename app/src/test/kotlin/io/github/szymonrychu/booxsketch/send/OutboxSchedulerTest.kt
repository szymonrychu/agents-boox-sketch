package io.github.szymonrychu.booxsketch.send

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
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

    @Test
    fun later_never_cancels_the_run_that_asked_for_it() {
        scheduler.later(60_000)
        scheduler.later(30_000)
        val states = infos(OutboxScheduler.LATER_WORK).map { it.state }
        assertThat(states).hasSize(2)
        assertThat(states).doesNotContain(WorkInfo.State.CANCELLED)
    }

    @Test
    fun kick_is_not_delayed_by_a_pending_backoff_run() {
        scheduler.later(300_000)
        scheduler.kick()
        val kick = infos(OutboxScheduler.KICK_WORK).single()
        assertThat(kick.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(kick.nextScheduleTimeMillis).isLessThan(System.currentTimeMillis() + 5_000)
        // The backoff run is still queued, untouched.
        assertThat(infos(OutboxScheduler.LATER_WORK).single().state).isEqualTo(WorkInfo.State.ENQUEUED)
    }

    @Test
    fun cancel_all_drops_both_runs() {
        scheduler.later(60_000)
        scheduler.kick()
        scheduler.cancelAll()
        assertThat((infos(OutboxScheduler.LATER_WORK) + infos(OutboxScheduler.KICK_WORK)).map { it.state })
            .containsExactly(WorkInfo.State.CANCELLED, WorkInfo.State.CANCELLED)
    }
}
