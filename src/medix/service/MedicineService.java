package medix.service;

import java.sql.SQLException;
import java.util.List;
import medix.dao.InventoryDao;
import medix.dao.MedicineDao;
import medix.dao.ReservationDao;
import medix.engine.AvailabilityCache;
import medix.model.Medicine;
import medix.model.MedicineForm;
import medix.model.Role;
import medix.util.Session;
import medix.util.ValidationUtil;

/**
 * The medicine catalog (admin). Pharmacies pick from this list when they add stock.
 */
public class MedicineService {

    public static final String MSG_DUPLICATE = "This medicine is already in the catalog.";

    private final MedicineDao medicineDao = new MedicineDao();
    private final InventoryDao inventoryDao = new InventoryDao();
    private final ReservationDao reservationDao = new ReservationDao();
    private final AuditService auditService = new AuditService();

    /** All medicines whose brand or generic name contains the text (blank = all), by brand. */
    public List<Medicine> search(String text) throws SQLException {
        return medicineDao.searchByName(text == null ? "" : text);
    }

    public Medicine findById(int id) throws SQLException {
        return medicineDao.findById(id);
    }

    public int add(Medicine m) throws ServiceException, SQLException {
        requireAdmin();
        normalizeAndCheck(m, 0);
        int id = medicineDao.insert(m);
        auditService.log("MEDICINE_ADDED", "medicines", id, label(m));
        return id;
    }

    public void update(Medicine m) throws ServiceException, SQLException {
        requireAdmin();
        if (medicineDao.findById(m.getId()) == null) {
            throw new ServiceException("This medicine no longer exists.");
        }
        normalizeAndCheck(m, m.getId());
        medicineDao.update(m);
        AvailabilityCache.invalidateMedicine(m.getId());
        auditService.log("MEDICINE_UPDATED", "medicines", m.getId(), label(m));
    }

    /** Deletes the medicine unless stock or reservations still use it. */
    public void delete(int id) throws ServiceException, SQLException {
        requireAdmin();
        Medicine m = medicineDao.findById(id);
        if (m == null) {
            throw new ServiceException("This medicine no longer exists.");
        }
        int stock = inventoryDao.countByMedicine(id);
        if (stock > 0) {
            throw new ServiceException(label(m) + " cannot be deleted because " + stock
                    + " inventory row(s) use it.\nPharmacies must remove that stock first.");
        }
        int reservations = reservationDao.countByMedicine(id);
        if (reservations > 0) {
            throw new ServiceException(label(m) + " cannot be deleted because " + reservations
                    + " reservation(s) refer to it.");
        }
        medicineDao.delete(id);
        AvailabilityCache.invalidateMedicine(id);
        auditService.log("MEDICINE_DELETED", "medicines", id, label(m));
    }

    /** Autocomplete row, e.g. "Seclo 20 mg Capsule – Omeprazole". */
    public static String suggestionLabel(Medicine m) {
        return label(m) + " – " + m.getGenericName();
    }

    /** e.g. "Napa 500 mg Tablet". */
    public static String label(Medicine m) {
        return m.getBrandName() + " " + m.getStrength() + " " + m.getForm();
    }

    private void normalizeAndCheck(Medicine m, int excludeId) throws ServiceException, SQLException {
        m.setBrandName(clean(m.getBrandName()));
        m.setGenericName(clean(m.getGenericName()));
        m.setStrength(clean(m.getStrength()));
        m.setManufacturer(ValidationUtil.trimToNull(clean(m.getManufacturer())));
        if (m.getBrandName().isEmpty() || m.getGenericName().isEmpty() || m.getStrength().isEmpty()) {
            throw new ServiceException("Brand name, generic name and strength are required.");
        }
        if (m.getForm() == null) {
            throw new ServiceException("Choose the form (tablet, syrup, ...).");
        }
        if (medicineDao.findDuplicate(m.getBrandName(), m.getStrength(), m.getForm(), excludeId) != null) {
            throw new ServiceException(MSG_DUPLICATE);
        }
    }

    /** Trims and collapses repeated spaces ("500  mg" → "500 mg"). */
    private static String clean(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    public static MedicineForm[] getForms() {
        return MedicineForm.values();
    }

    private static void requireAdmin() throws ServiceException {
        if (!Session.hasRole(Role.ADMIN)) {
            throw new ServiceException("Only an admin can change the medicine catalog.");
        }
    }
}
