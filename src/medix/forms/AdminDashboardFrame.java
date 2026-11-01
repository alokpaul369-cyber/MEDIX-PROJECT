package medix.forms;

import java.awt.BorderLayout;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.table.DefaultTableModel;
import medix.model.DashboardStats;
import medix.model.ReportView;
import medix.model.ShortageRow;
import medix.service.AdminService;
import medix.service.ServiceException;
import medix.util.ChartUtil;
import medix.util.DateUtil;
import medix.util.ErrorLog;
import medix.util.SidebarUtil;
import medix.util.TableUtil;
import medix.util.UITheme;
import org.jfree.chart.ChartPanel;

/**
 * Admin dashboard: four summary cards, the most searched medicines, searches per day,
 * possible shortages (searched, found nowhere) and the newest open reports.
 * Charts are JFreeChart ChartPanels added in code into pnlChartTop / pnlChartDaily.
 */
public class AdminDashboardFrame extends javax.swing.JFrame {

    private static final String TITLE = "MediX – Dashboard";
    private static final int[] RANGE_DAYS = {7, 30};
    private static final int COL_FAILED = 3;

    private final AdminService adminService = new AdminService();
    private DefaultTableModel shortageModel;
    private DefaultTableModel reportModel;
    private List<ShortageRow> shortages = new ArrayList<ShortageRow>();
    private ChartPanel topChartPanel;
    private ChartPanel dailyChartPanel;

    public AdminDashboardFrame() {
        initComponents();
        applyTheme();
        SidebarUtil.attach(this, pnlSidebar, SidebarUtil.ADMIN_DASHBOARD);
        setupTables();
        setupCharts();
        cmbRange.setModel(new DefaultComboBoxModel<String>(new String[]{"Last 7 days", "Last 30 days"}));
        reload();
    }

    private void applyTheme() {
        UITheme.styleFrame(this);
        UITheme.styleTitle(lblTitle);
        UITheme.styleMuted(lblSubtitle);
        UITheme.styleHeading(lblShortages);
        UITheme.styleHeading(lblOpenReports);
        UITheme.styleSummaryCard(pnlCardVerified, UITheme.SUCCESS);
        UITheme.styleSummaryCard(pnlCardPending, UITheme.WARNING);
        UITheme.styleSummaryCard(pnlCardSearches, UITheme.TEAL);
        UITheme.styleSummaryCard(pnlCardReservations, UITheme.ACCENT);
        for (JLabel caption : new JLabel[]{lblVerifiedCaption, lblPendingCaption, lblSearchesCaption,
            lblReservationsCaption}) {
            UITheme.styleMuted(caption);
        }
        for (JLabel value : new JLabel[]{lblVerifiedValue, lblPendingValue, lblSearchesValue, lblReservationsValue}) {
            value.setFont(UITheme.FONT_TITLE.deriveFont(26f));
            value.setForeground(UITheme.SIDEBAR);
        }
        UITheme.styleMuted(lblVerifiedSub);
        UITheme.styleMuted(lblReservationsSub);
        UITheme.styleLinkButton(btnReviewNow);
        UITheme.styleLinkButton(btnAllReports);
        btnReviewNow.setBorder(BorderFactory.createEmptyBorder());
        UITheme.stylePrimaryButton(btnExportPng);
        btnExportPng.setToolTipText("Saves both charts as " + ChartUtil.EXPORT_WIDTH + "x" + ChartUtil.EXPORT_HEIGHT
                + " PNG files in a folder you choose.");
    }

    private void setupTables() {
        shortageModel = TableUtil.setupTable(tblShortages, "ID", "Medicine", "Area", "Failed");
        TableUtil.setColumnWidth(tblShortages, 1, 170);
        TableUtil.setColumnWidth(tblShortages, 2, 120);
        TableUtil.setColumnWidth(tblShortages, COL_FAILED, 60);
        TableUtil.centerColumn(tblShortages, COL_FAILED);
        TableUtil.setRowBackground(tblShortages, modelRow ->
                shortages.get(modelRow).getFailed() >= AdminService.SHORTAGE_ALERT ? UITheme.ROW_EXPIRED : null);
        tblShortages.setToolTipText("Light red: " + AdminService.SHORTAGE_ALERT + " or more failed searches.");

        reportModel = TableUtil.setupTable(tblOpenReports, "ID", "Pharmacy", "Reason", "Status");
        TableUtil.setColumnWidth(tblOpenReports, 1, 160);
        TableUtil.setColumnWidth(tblOpenReports, 2, 170);
        TableUtil.setColumnWidth(tblOpenReports, 3, 70);
        tblOpenReports.setToolTipText("Double-click a report to handle it.");
    }

    private void setupCharts() {
        topChartPanel = chartPanel(pnlChartTop);
        dailyChartPanel = chartPanel(pnlChartDaily);
    }

    /** A ChartPanel filling the (empty) panel from the form. */
    private static ChartPanel chartPanel(JPanel holder) {
        ChartPanel panel = new ChartPanel(null);
        panel.setMouseZoomable(false);
        panel.setPopupMenu(null);
        panel.setMinimumDrawWidth(200);
        panel.setMinimumDrawHeight(150);
        panel.setMaximumDrawWidth(2400);
        panel.setMaximumDrawHeight(1400);
        UITheme.styleCard(holder);
        holder.setLayout(new BorderLayout());
        holder.add(panel, BorderLayout.CENTER);
        return panel;
    }

    private int selectedDays() {
        int i = cmbRange.getSelectedIndex();
        return RANGE_DAYS[i < 0 ? 0 : i];
    }

    // ---------------------------------------------------------------- loading

    private void reload() {
        int days = selectedDays();
        try {
            showStats(adminService.getStats(days));
            topChartPanel.setChart(ChartUtil.horizontalBar("Most searched medicines",
                    adminService.getTopSearched(days), "Searches"));
            dailyChartPanel.setChart(ChartUtil.dailyLine("Searches per day",
                    adminService.getSearchesPerDay(days), "Searches"));
            showShortages(adminService.getShortages(days));
            showOpenReports(adminService.getLatestOpenReports());
        } catch (ServiceException e) {
            showWarning(e.getMessage());
        } catch (SQLException e) {
            showError("Could not load the dashboard:\n" + ErrorLog.describe(e));
        }
    }

    private void showStats(DashboardStats s) {
        lblVerifiedValue.setText(String.valueOf(s.getVerifiedPharmacies()));
        lblVerifiedSub.setText("in " + s.getAreasCovered() + (s.getAreasCovered() == 1 ? " area" : " areas"));
        lblPendingValue.setText(String.valueOf(s.getPendingPharmacies()));
        btnReviewNow.setVisible(s.getPendingPharmacies() > 0);

        lblSearchesCaption.setText(s.getDays() == 7 ? "Searches this week" : "Searches – last " + s.getDays() + " days");
        lblSearchesValue.setText(String.valueOf(s.getSearches()));
        Integer change = s.getSearchChangePercent();
        if (change == null) {
            UITheme.styleMuted(lblSearchesSub);
            lblSearchesSub.setText("No searches in the " + s.getDays() + " days before");
        } else {
            boolean up = change >= 0;
            UITheme.styleStatus(lblSearchesSub, up ? UITheme.SUCCESS : UITheme.DANGER);
            lblSearchesSub.setText("<html>" + UITheme.symbolHtml(up ? '▲' : '▼', up ? "+" : "-") + " "
                    + (up ? "+" : "") + change + "% vs previous " + s.getDays() + " days</html>");
        }

        lblReservationsValue.setText(s.getCompletedPercent() + "%");
        lblReservationsSub.setText(s.getCompletedReservations() + " of " + s.getClosedReservations()
                + " finished reservations");
    }

    private void showShortages(List<ShortageRow> rows) {
        shortages = rows;
        TableUtil.clear(shortageModel);
        for (ShortageRow r : rows) {
            shortageModel.addRow(new Object[]{r.getMedicineId(), r.getMedicineLabel(), r.getAreaName(), r.getFailed()});
        }
    }

    private void showOpenReports(List<ReportView> rows) {
        TableUtil.clear(reportModel);
        for (ReportView v : rows) {
            reportModel.addRow(new Object[]{v.getReport().getId(), v.getPharmacyName(), v.getReport().getReason(),
                v.getReport().getStatus()});
        }
    }

    // ---------------------------------------------------------------- actions

    /** Saves both charts as 900x600 PNG files in a chosen folder. */
    private void exportPng() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose a folder for the chart images");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File folder = chooser.getSelectedFile();
        String suffix = "_" + selectedDays() + "d_" + DateUtil.today() + ".png";
        File top = new File(folder, "medix_most_searched" + suffix);
        File daily = new File(folder, "medix_searches_per_day" + suffix);
        if ((top.exists() || daily.exists()) && JOptionPane.showConfirmDialog(this,
                "Chart images from today are already in this folder. Replace them?", TITLE,
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            ChartUtil.savePng(topChartPanel.getChart(), top);
            ChartUtil.savePng(dailyChartPanel.getChart(), daily);
            adminService.logChartExport(folder, 2);
            JOptionPane.showMessageDialog(this, "Saved:\n" + top.getName() + "\n" + daily.getName()
                    + "\n\nin " + folder.getAbsolutePath(), TITLE, JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            showError("Could not save the chart images:\n" + ErrorLog.describe(e));
        }
    }

    /** Opens the Reports screen (with the report selected when reportId is not null). */
    private void openReports(Integer reportId) {
        (reportId == null ? new ReportsFrame() : new ReportsFrame(reportId)).setVisible(true);
        dispose();
    }

    private void showWarning(String message) {
        JOptionPane.showMessageDialog(this, message, TITLE, JOptionPane.WARNING_MESSAGE);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, TITLE, JOptionPane.ERROR_MESSAGE);
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        pnlSidebar = new javax.swing.JPanel();
        lblTitle = new javax.swing.JLabel();
        lblSubtitle = new javax.swing.JLabel();
        lblRange = new javax.swing.JLabel();
        cmbRange = new javax.swing.JComboBox<>();
        btnExportPng = new javax.swing.JButton();
        pnlCardVerified = new javax.swing.JPanel();
        lblVerifiedCaption = new javax.swing.JLabel();
        lblVerifiedValue = new javax.swing.JLabel();
        lblVerifiedSub = new javax.swing.JLabel();
        pnlCardPending = new javax.swing.JPanel();
        lblPendingCaption = new javax.swing.JLabel();
        lblPendingValue = new javax.swing.JLabel();
        btnReviewNow = new javax.swing.JButton();
        pnlCardSearches = new javax.swing.JPanel();
        lblSearchesCaption = new javax.swing.JLabel();
        lblSearchesValue = new javax.swing.JLabel();
        lblSearchesSub = new javax.swing.JLabel();
        pnlCardReservations = new javax.swing.JPanel();
        lblReservationsCaption = new javax.swing.JLabel();
        lblReservationsValue = new javax.swing.JLabel();
        lblReservationsSub = new javax.swing.JLabel();
        pnlChartTop = new javax.swing.JPanel();
        pnlChartDaily = new javax.swing.JPanel();
        lblShortages = new javax.swing.JLabel();
        scrShortages = new javax.swing.JScrollPane();
        tblShortages = new javax.swing.JTable();
        lblOpenReports = new javax.swing.JLabel();
        btnAllReports = new javax.swing.JButton();
        scrOpenReports = new javax.swing.JScrollPane();
        tblOpenReports = new javax.swing.JTable();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("MediX – Dashboard");
        setPreferredSize(new java.awt.Dimension(1200, 720));

        javax.swing.GroupLayout pnlSidebarLayout = new javax.swing.GroupLayout(pnlSidebar);
        pnlSidebar.setLayout(pnlSidebarLayout);
        pnlSidebarLayout.setHorizontalGroup(
            pnlSidebarLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 170, Short.MAX_VALUE)
        );
        pnlSidebarLayout.setVerticalGroup(
            pnlSidebarLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 720, Short.MAX_VALUE)
        );

        lblTitle.setText("Dashboard");

        lblSubtitle.setText("How patients and pharmacies use MediX.");

        lblRange.setText("Period");

        cmbRange.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cmbRangeActionPerformed(evt);
            }
        });

        btnExportPng.setText("Export PNG");
        btnExportPng.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnExportPngActionPerformed(evt);
            }
        });

        lblVerifiedCaption.setText("Verified pharmacies");

        lblVerifiedValue.setText("0");

        lblVerifiedSub.setText("in 0 areas");

        javax.swing.GroupLayout pnlCardVerifiedLayout = new javax.swing.GroupLayout(pnlCardVerified);
        pnlCardVerified.setLayout(pnlCardVerifiedLayout);
        pnlCardVerifiedLayout.setHorizontalGroup(
            pnlCardVerifiedLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlCardVerifiedLayout.createSequentialGroup()
                .addGap(14, 14, 14)
                .addGroup(pnlCardVerifiedLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblVerifiedCaption, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblVerifiedValue)
                    .addComponent(lblVerifiedSub, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(14, 14, 14))
        );
        pnlCardVerifiedLayout.setVerticalGroup(
            pnlCardVerifiedLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlCardVerifiedLayout.createSequentialGroup()
                .addGap(12, 12, 12)
                .addComponent(lblVerifiedCaption)
                .addGap(2, 2, 2)
                .addComponent(lblVerifiedValue)
                .addGap(2, 2, 2)
                .addComponent(lblVerifiedSub)
                .addGap(10, 10, Short.MAX_VALUE))
        );

        lblPendingCaption.setText("Waiting for verification");

        lblPendingValue.setText("0");

        btnReviewNow.setText("Review now →");
        btnReviewNow.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnReviewNowActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout pnlCardPendingLayout = new javax.swing.GroupLayout(pnlCardPending);
        pnlCardPending.setLayout(pnlCardPendingLayout);
        pnlCardPendingLayout.setHorizontalGroup(
            pnlCardPendingLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlCardPendingLayout.createSequentialGroup()
                .addGap(14, 14, 14)
                .addGroup(pnlCardPendingLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblPendingCaption, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblPendingValue)
                    .addComponent(btnReviewNow))
                .addGap(14, 14, 14))
        );
        pnlCardPendingLayout.setVerticalGroup(
            pnlCardPendingLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlCardPendingLayout.createSequentialGroup()
                .addGap(12, 12, 12)
                .addComponent(lblPendingCaption)
                .addGap(2, 2, 2)
                .addComponent(lblPendingValue)
                .addGap(2, 2, 2)
                .addComponent(btnReviewNow)
                .addGap(10, 10, Short.MAX_VALUE))
        );

        lblSearchesCaption.setText("Searches this week");

        lblSearchesValue.setText("0");

        lblSearchesSub.setText(" ");

        javax.swing.GroupLayout pnlCardSearchesLayout = new javax.swing.GroupLayout(pnlCardSearches);
        pnlCardSearches.setLayout(pnlCardSearchesLayout);
        pnlCardSearchesLayout.setHorizontalGroup(
            pnlCardSearchesLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlCardSearchesLayout.createSequentialGroup()
                .addGap(14, 14, 14)
                .addGroup(pnlCardSearchesLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblSearchesCaption, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblSearchesValue)
                    .addComponent(lblSearchesSub, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(14, 14, 14))
        );
        pnlCardSearchesLayout.setVerticalGroup(
            pnlCardSearchesLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlCardSearchesLayout.createSequentialGroup()
                .addGap(12, 12, 12)
                .addComponent(lblSearchesCaption)
                .addGap(2, 2, 2)
                .addComponent(lblSearchesValue)
                .addGap(2, 2, 2)
                .addComponent(lblSearchesSub)
                .addGap(10, 10, Short.MAX_VALUE))
        );

        lblReservationsCaption.setText("Reservations completed");

        lblReservationsValue.setText("0%");

        lblReservationsSub.setText("0 of 0");

        javax.swing.GroupLayout pnlCardReservationsLayout = new javax.swing.GroupLayout(pnlCardReservations);
        pnlCardReservations.setLayout(pnlCardReservationsLayout);
        pnlCardReservationsLayout.setHorizontalGroup(
            pnlCardReservationsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlCardReservationsLayout.createSequentialGroup()
                .addGap(14, 14, 14)
                .addGroup(pnlCardReservationsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblReservationsCaption, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblReservationsValue)
                    .addComponent(lblReservationsSub, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(14, 14, 14))
        );
        pnlCardReservationsLayout.setVerticalGroup(
            pnlCardReservationsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlCardReservationsLayout.createSequentialGroup()
                .addGap(12, 12, 12)
                .addComponent(lblReservationsCaption)
                .addGap(2, 2, 2)
                .addComponent(lblReservationsValue)
                .addGap(2, 2, 2)
                .addComponent(lblReservationsSub)
                .addGap(10, 10, Short.MAX_VALUE))
        );

        javax.swing.GroupLayout pnlChartTopLayout = new javax.swing.GroupLayout(pnlChartTop);
        pnlChartTop.setLayout(pnlChartTopLayout);
        pnlChartTopLayout.setHorizontalGroup(
            pnlChartTopLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 480, Short.MAX_VALUE)
        );
        pnlChartTopLayout.setVerticalGroup(
            pnlChartTopLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 240, Short.MAX_VALUE)
        );

        javax.swing.GroupLayout pnlChartDailyLayout = new javax.swing.GroupLayout(pnlChartDaily);
        pnlChartDaily.setLayout(pnlChartDailyLayout);
        pnlChartDailyLayout.setHorizontalGroup(
            pnlChartDailyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 480, Short.MAX_VALUE)
        );
        pnlChartDailyLayout.setVerticalGroup(
            pnlChartDailyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 240, Short.MAX_VALUE)
        );

        lblShortages.setText("Possible shortages – searched, found nowhere");

        scrShortages.setViewportView(tblShortages);

        lblOpenReports.setText("Open reports");

        btnAllReports.setText("All reports →");
        btnAllReports.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnAllReportsActionPerformed(evt);
            }
        });

        tblOpenReports.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                tblOpenReportsMouseClicked(evt);
            }
        });

        scrOpenReports.setViewportView(tblOpenReports);

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addComponent(pnlSidebar, javax.swing.GroupLayout.PREFERRED_SIZE, 170, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(24, 24, 24)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lblTitle)
                            .addComponent(lblSubtitle))
                        .addGap(20, 20, Short.MAX_VALUE)
                        .addComponent(lblRange)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(cmbRange, javax.swing.GroupLayout.PREFERRED_SIZE, 140, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(10, 10, 10)
                        .addComponent(btnExportPng))
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(pnlCardVerified, javax.swing.GroupLayout.DEFAULT_SIZE, 220, Short.MAX_VALUE)
                        .addGap(14, 14, 14)
                        .addComponent(pnlCardPending, javax.swing.GroupLayout.DEFAULT_SIZE, 220, Short.MAX_VALUE)
                        .addGap(14, 14, 14)
                        .addComponent(pnlCardSearches, javax.swing.GroupLayout.DEFAULT_SIZE, 220, Short.MAX_VALUE)
                        .addGap(14, 14, 14)
                        .addComponent(pnlCardReservations, javax.swing.GroupLayout.DEFAULT_SIZE, 220, Short.MAX_VALUE))
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(pnlChartTop, javax.swing.GroupLayout.DEFAULT_SIZE, 480, Short.MAX_VALUE)
                        .addGap(14, 14, 14)
                        .addComponent(pnlChartDaily, javax.swing.GroupLayout.DEFAULT_SIZE, 480, Short.MAX_VALUE))
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lblShortages)
                            .addComponent(scrShortages, javax.swing.GroupLayout.DEFAULT_SIZE, 480, Short.MAX_VALUE))
                        .addGap(14, 14, 14)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(lblOpenReports)
                                .addGap(10, 10, Short.MAX_VALUE)
                                .addComponent(btnAllReports))
                            .addComponent(scrOpenReports, javax.swing.GroupLayout.DEFAULT_SIZE, 480, Short.MAX_VALUE))))
                .addGap(24, 24, 24))
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(pnlSidebar, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
            .addGroup(layout.createSequentialGroup()
                .addGap(20, 20, 20)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(lblTitle)
                        .addGap(4, 4, 4)
                        .addComponent(lblSubtitle))
                    .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                        .addComponent(lblRange)
                        .addComponent(cmbRange, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addComponent(btnExportPng)))
                .addGap(16, 16, 16)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(pnlCardVerified, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(pnlCardPending, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(pnlCardSearches, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(pnlCardReservations, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(14, 14, 14)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(pnlChartTop, javax.swing.GroupLayout.DEFAULT_SIZE, 240, Short.MAX_VALUE)
                    .addComponent(pnlChartDaily, javax.swing.GroupLayout.DEFAULT_SIZE, 240, Short.MAX_VALUE))
                .addGap(14, 14, 14)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblShortages)
                    .addComponent(lblOpenReports)
                    .addComponent(btnAllReports))
                .addGap(6, 6, 6)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(scrShortages, javax.swing.GroupLayout.PREFERRED_SIZE, 170, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(scrOpenReports, javax.swing.GroupLayout.PREFERRED_SIZE, 170, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(20, 20, 20))
        );

        pack();
        setLocationRelativeTo(null);
    }// </editor-fold>//GEN-END:initComponents

    private void cmbRangeActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cmbRangeActionPerformed
        reload();
    }//GEN-LAST:event_cmbRangeActionPerformed

    private void btnExportPngActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnExportPngActionPerformed
        exportPng();
    }//GEN-LAST:event_btnExportPngActionPerformed

    private void btnReviewNowActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnReviewNowActionPerformed
        new VerificationFrame().setVisible(true);
        dispose();
    }//GEN-LAST:event_btnReviewNowActionPerformed

    private void btnAllReportsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnAllReportsActionPerformed
        openReports(null);
    }//GEN-LAST:event_btnAllReportsActionPerformed

    private void tblOpenReportsMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_tblOpenReportsMouseClicked
        if (evt.getClickCount() == 2 && TableUtil.getSelectedId(tblOpenReports) != null) {
            openReports(TableUtil.getSelectedId(tblOpenReports));
        }
    }//GEN-LAST:event_tblOpenReportsMouseClicked

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnAllReports;
    private javax.swing.JButton btnExportPng;
    private javax.swing.JButton btnReviewNow;
    private javax.swing.JComboBox<String> cmbRange;
    private javax.swing.JLabel lblOpenReports;
    private javax.swing.JLabel lblPendingCaption;
    private javax.swing.JLabel lblPendingValue;
    private javax.swing.JLabel lblRange;
    private javax.swing.JLabel lblReservationsCaption;
    private javax.swing.JLabel lblReservationsSub;
    private javax.swing.JLabel lblReservationsValue;
    private javax.swing.JLabel lblSearchesCaption;
    private javax.swing.JLabel lblSearchesSub;
    private javax.swing.JLabel lblSearchesValue;
    private javax.swing.JLabel lblShortages;
    private javax.swing.JLabel lblSubtitle;
    private javax.swing.JLabel lblTitle;
    private javax.swing.JLabel lblVerifiedCaption;
    private javax.swing.JLabel lblVerifiedSub;
    private javax.swing.JLabel lblVerifiedValue;
    private javax.swing.JPanel pnlCardPending;
    private javax.swing.JPanel pnlCardReservations;
    private javax.swing.JPanel pnlCardSearches;
    private javax.swing.JPanel pnlCardVerified;
    private javax.swing.JPanel pnlChartDaily;
    private javax.swing.JPanel pnlChartTop;
    private javax.swing.JPanel pnlSidebar;
    private javax.swing.JScrollPane scrOpenReports;
    private javax.swing.JScrollPane scrShortages;
    private javax.swing.JTable tblOpenReports;
    private javax.swing.JTable tblShortages;
    // End of variables declaration//GEN-END:variables
}
