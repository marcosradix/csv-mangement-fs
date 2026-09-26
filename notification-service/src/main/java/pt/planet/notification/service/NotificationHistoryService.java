package pt.planet.notification.service;

import org.springframework.stereotype.Service;
import pt.planet.notification.dto.NotificationRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class NotificationHistoryService {

    private static final int MAX_HISTORY = 200;

    private final List<NotificationRecord> records = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<UUID, NotificationRecord> byExportId = new ConcurrentHashMap<>();

    public void recordNotification(NotificationRecord record) {
        if (record == null) return;
        records.add(0, record);
        if (record.exportId() != null) {
            byExportId.put(record.exportId(), record);
        }
        if (records.size() > MAX_HISTORY) {
            records.remove(records.size() - 1);
        }
    }

    public List<NotificationRecord> getRecentNotifications() {
        return Collections.unmodifiableList(new ArrayList<>(records));
    }

    public Optional<NotificationRecord> findByExportId(UUID exportId) {
        if (exportId == null) return Optional.empty();
        return Optional.ofNullable(byExportId.get(exportId));
    }

    public int count() {
        return records.size();
    }
}
