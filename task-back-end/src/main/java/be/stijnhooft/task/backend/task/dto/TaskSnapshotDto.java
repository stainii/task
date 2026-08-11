package be.stijnhooft.task.backend.task.dto;

import java.util.List;

/// What `GET /api/tasks` returns: the open tasks with their history, **and the cursor they were
/// read at**.
///
/// The cursor is the point. Without it a client does snapshot-then-stream and every patch that
/// lands between the response completing and the stream attaching is lost forever - invisibly, since
/// both calls succeed ([ADR-0004](../../../../../../../../docs/adr/0004-one-write-verb-two-clocks-offline-sync.md)).
/// With it the client streams from the watermark and re-receives whatever overlapped, which is free:
/// a patch it already has is a no-op by id.
///
/// The snapshot is rare by design - first run and hard reset only. Normal boot renders from local
/// storage before any network.
public record TaskSnapshotDto(long epoch, long watermark, List<TaskDto> tasks) {
}
