package com.digihori.solimemo.data.local

enum class SyncState {
    LOCAL_ONLY,
    SYNCED,
    PENDING_UPLOAD,
    PENDING_DELETE,
    PENDING_RESTORE,
    PENDING_PURGE,
    CONFLICT,
    SYNC_ERROR,
}
