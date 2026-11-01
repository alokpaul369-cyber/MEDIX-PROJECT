package medix.engine;

import medix.service.AlertService;

/**
 * Daily at 08:00 (and at start-up if missed today): batches expiring within 60 days and already expired batches with units left get an EXPIRY notification (expired batches are already hidden from search).
 */
public class ExpirySweepJob implements JobScheduler.Job {

    public static final String NAME = "Expiry sweep";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int run() throws Exception {
        return new AlertService().runExpirySweep();
    }
}
