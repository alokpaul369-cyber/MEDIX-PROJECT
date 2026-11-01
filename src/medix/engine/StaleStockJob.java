package medix.engine;

import medix.service.AlertService;

/**
 * Every hour: pharmacies whose newest inventory.updated_at is 24+ hours old get one STALE notification per day (the nightly trust check, TrustScoreJob, turns that into a trust penalty).
 */
public class StaleStockJob implements JobScheduler.Job {

    public static final String NAME = "Stale stock";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int run() throws Exception {
        return new AlertService().runStaleCheck();
    }
}
