package medix.engine;

import medix.service.ReservationService;

/**
 * Every minute: PENDING / CONFIRMED / READY reservations past expires_at become EXPIRED, their units are released (RELEASED) and patient and pharmacy are notified.
 */
public class ReservationExpiryJob implements JobScheduler.Job {

    public static final String NAME = "Reservation expiry";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int run() throws Exception {
        return new ReservationService().expireOverdue();
    }
}
