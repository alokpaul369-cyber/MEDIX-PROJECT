package medix.engine;

import medix.service.TrustScoreService;

/**
 * Nightly at 02:00 (and at start-up if missed today): the stale-stock trust penalty and every pharmacy's trust score (TrustScoreService.runNightlyCheck).
 */
public class TrustScoreJob implements JobScheduler.Job {

    public static final String NAME = "Trust score (nightly)";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int run() throws Exception {
        return new TrustScoreService().runNightlyCheck();
    }
}
