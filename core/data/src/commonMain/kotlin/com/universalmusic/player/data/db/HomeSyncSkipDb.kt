package com.universalmusic.player.data.db

import com.universalmusic.player.data.sync.HomeSyncSkipStore
import com.universalmusic.player.data.sync.SkippedCopy
import com.universalmusic.player.data.sync.SkippedCopyKind
import com.universalmusic.player.data.sync.VaultCopyDirection
import com.universalmusic.player.domain.failure.StorageArea

class DbHomeSyncSkipStore(storage: KainosStorage) : HomeSyncSkipStore {
    private val storage = AreaStorage(storage, StorageArea.LIBRARY)

    override suspend fun skipped(peerDeviceId: String): List<SkippedCopy> = storage.read { db ->
        db.homeSyncSkipQueries.selectForPeer(peerDeviceId).executeAsList().mapNotNull { row ->
            readStorageRow(strict = false) {
                SkippedCopy(
                    peerDeviceId = row.peer_device_id,
                    kind = SkippedCopyKind.valueOf(row.kind),
                    subject = row.subject,
                    direction = VaultCopyDirection.valueOf(row.direction),
                    fileIdentity = row.file_identity,
                    label = row.label,
                    sizeBytes = row.size_bytes,
                    skippedAtMs = row.skipped_at_ms,
                )
            }
        }
    }

    override suspend fun skip(copies: List<SkippedCopy>) {
        if (copies.isEmpty()) return
        storage.write { db ->
            for (copy in copies) {
                db.homeSyncSkipQueries.upsert(
                    copy.peerDeviceId,
                    copy.kind.name,
                    copy.subject,
                    copy.direction.name,
                    copy.fileIdentity,
                    copy.label,
                    copy.sizeBytes,
                    copy.skippedAtMs,
                )
            }
        }
    }

    override suspend fun unskip(
        peerDeviceId: String,
        kind: SkippedCopyKind,
        subject: String,
        direction: VaultCopyDirection,
    ) {
        storage.write { db -> db.homeSyncSkipQueries.deleteOne(peerDeviceId, kind.name, subject, direction.name) }
    }
}
