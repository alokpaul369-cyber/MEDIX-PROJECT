package medix.model;

/**
 * Why an inventory quantity changed: stock_movements.reason.
 * Stored in the database as {@link #name()}; the CHECK constraint in schema.sql lists the same values.
 */
public enum MovementReason {
    ADD, SALE, ADJUST, EXPIRED, RESERVED, RELEASED, PICKED_UP
}
