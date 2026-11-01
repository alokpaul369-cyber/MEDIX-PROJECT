package medix.engine;

import medix.service.SearchService;

/**
 * Daily: deletes search_logs older than 90 days (privacy: keep as little search history as possible).
 */
public class SearchLogCleanupJob implements JobScheduler.Job {

    public static final String NAME = "Search log clean-up";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int run() throws Exception {
        return new SearchService().deleteOldSearchLogs();
    }
}
