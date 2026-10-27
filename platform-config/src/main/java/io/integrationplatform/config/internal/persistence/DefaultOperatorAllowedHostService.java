package io.integrationplatform.config.internal.persistence;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.system.application.OperatorAllowedHostService;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DefaultOperatorAllowedHostService implements OperatorAllowedHostService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultOperatorAllowedHostService.class);

    private final JdbcOperatorAllowedHostRepository repository;
    private final DestinationGuard guard;
    private final List<String> startupPatterns;

    DefaultOperatorAllowedHostService(JdbcOperatorAllowedHostRepository repository, DestinationGuard guard,
            List<String> startupPatterns) {
        this.repository = repository;
        this.guard = guard;
        this.startupPatterns = List.copyOf(startupPatterns);
    }

    @Override
    public List<String> list() {
        return repository.findAll();
    }

    @Override
    public List<String> add(String pattern) {
        repository.add(pattern);
        reloadGuard();
        return repository.findAll();
    }

    @Override
    public List<String> remove(String pattern) {
        repository.remove(pattern);
        reloadGuard();
        return repository.findAll();
    }

    private void reloadGuard() {
        List<String> dbPatterns = repository.findAll();
        List<String> merged = new ArrayList<>(startupPatterns);
        merged.addAll(dbPatterns);
        guard.updateAllowList(AllowList.of(merged));
        LOG.info("Destination allow-list reloaded: {} startup + {} runtime = {} total patterns",
                startupPatterns.size(), dbPatterns.size(), merged.size());
    }

    void loadFromDatabase() {
        List<String> dbPatterns = repository.findAll();
        if (!dbPatterns.isEmpty()) {
            List<String> merged = new ArrayList<>(startupPatterns);
            merged.addAll(dbPatterns);
            guard.updateAllowList(AllowList.of(merged));
            LOG.info("Loaded {} runtime allow-list entries from database (merged with {} startup entries)",
                    dbPatterns.size(), startupPatterns.size());
        }
    }
}
