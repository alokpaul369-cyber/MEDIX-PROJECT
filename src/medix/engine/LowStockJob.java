package medix.engine;

import medix.service.AlertService;

/**
 * Every 15 minutes: non-expired batches with available &lt;= alert level get one LOW_STOCK notification per batch per day, e.g. "Seclo 20 mg (batch SC-2510) has 2 left. Alert level is 10."
 */
public class LowStockJob implements JobScheduler.Job {

    public static final String NAME = "Low stock";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int run() throws Exception {
        return new AlertService().runLowStockCheck();
    }
}
