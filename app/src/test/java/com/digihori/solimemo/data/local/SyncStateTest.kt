package com.digihori.solimemo.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncStateTest {
    @Test
    fun converterRoundTripsEverySyncState() {
        val converters = Converters()

        SyncState.entries.forEach { state ->
            assertEquals(state, converters.stringToSyncState(converters.syncStateToString(state)))
        }
    }
}

