package com.kafkasl.phonewhisper

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LocalMeasurementAdmissionTest {
    @Test
    fun onlyOneMeasurementOwnerCanHoldTheProcessWideLease() {
        val benchmark = LocalMeasurementAdmission.tryAcquire(LocalMeasurementAdmission.Owner.BENCHMARK)
        assertNotNull(benchmark)
        assertNull(LocalMeasurementAdmission.tryAcquire(LocalMeasurementAdmission.Owner.DICTATION))

        benchmark!!.close()
        val dictation = LocalMeasurementAdmission.tryAcquire(LocalMeasurementAdmission.Owner.DICTATION)
        assertNotNull(dictation)
        dictation!!.close()
    }

    @Test
    fun leaseCloseIsIdempotentAndDoesNotReleaseAReplacementLease() {
        val first = LocalMeasurementAdmission.tryAcquire(LocalMeasurementAdmission.Owner.BENCHMARK)
        assertNotNull(first)
        first!!.close()
        first.close()

        val second = LocalMeasurementAdmission.tryAcquire(LocalMeasurementAdmission.Owner.BENCHMARK)
        assertNotNull(second)
        first.close()
        assertNull(LocalMeasurementAdmission.tryAcquire(LocalMeasurementAdmission.Owner.DICTATION))
        second!!.close()
    }
}
