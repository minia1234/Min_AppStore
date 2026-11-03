package dev.indi.appstore

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class UpdateScheduleDeviceTest {
    @Test fun existingUpdateCheckUsesSixHoursAndRequiresNetwork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork("update-check").get(10, TimeUnit.SECONDS)
            .single { !it.state.isFinished }
        assertEquals(TimeUnit.HOURS.toMillis(6), work.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
    }
}
