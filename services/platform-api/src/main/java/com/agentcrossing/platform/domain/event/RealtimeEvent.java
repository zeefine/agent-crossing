package com.agentcrossing.platform.domain.event;

import java.time.Instant;

public record RealtimeEvent(
        // 用 Long 不用 long：MyBatis 的 ConstructorResolver 按 boxed 类型反射，
        // primitive long 在 resultMap 里实际匹配不到 record 的 ctor。
        Long eventId,
        String threadId,
        String type,
        Object payload,
        Instant createdAt) {
}
