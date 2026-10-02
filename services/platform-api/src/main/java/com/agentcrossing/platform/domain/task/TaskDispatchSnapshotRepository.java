package com.agentcrossing.platform.domain.task;

import java.util.List;

/** Optional optimized storage query. Omits missing/non-QUEUED tasks; order is unspecified. */
public interface TaskDispatchSnapshotRepository {
    List<TaskDispatchSnapshot> findDispatchSnapshots(List<String> taskIds);
}
