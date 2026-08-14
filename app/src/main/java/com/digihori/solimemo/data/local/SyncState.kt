package com.digihori.solimemo.data.local

enum class SyncState {
    LOCAL_ONLY,
    SYNCED,
    PENDING_UPLOAD,
    PENDING_DELETE,
    CONFLICT,
    SYNC_ERROR,
}

