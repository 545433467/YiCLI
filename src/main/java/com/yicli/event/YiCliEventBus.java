package com.yicli.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 进程内轻量事件总线：按事件类型分发到订阅者。
 * 订阅者异常一律吞掉并记录日志，绝不影响 Agent / 工具主流程。
 */
public class YiCliEventBus {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(YiCliEventBus.class);

    private final Map<String, List<Consumer<YiCliEvent>>> subscribers = new ConcurrentHashMap<>();

    public void subscribe(String eventType, Consumer<YiCliEvent> listener) {
        if (eventType == null || listener == null) {
            return;
        }
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public void publish(YiCliEvent event) {
        if (event == null) {
            return;
        }
        List<Consumer<YiCliEvent>> listeners = subscribers.get(event.type());
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        for (Consumer<YiCliEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                log.warn("event subscriber failed for type={}", event.type(), e);
            }
        }
    }
}
