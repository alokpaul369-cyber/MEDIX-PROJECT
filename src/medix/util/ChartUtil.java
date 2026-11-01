package medix.util;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.AxisLocation;
import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.DateTickUnit;
import org.jfree.chart.axis.DateTickUnitType;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.labels.StandardCategoryItemLabelGenerator;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.chart.renderer.category.StandardBarPainter;
import org.jfree.chart.renderer.xy.XYAreaRenderer;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.time.Day;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;
import medix.model.NamedCount;

/**
 * JFreeChart charts in the MediX colours (admin dashboard). The form adds the ChartPanel
 * into a JPanel placed in the .form, so the Design view still opens.
 */
public final class ChartUtil {

    /** Size of an exported PNG. */
    public static final int EXPORT_WIDTH = 900;
    public static final int EXPORT_HEIGHT = 600;

    private static final Color GRID = Color.decode("#E3E7EC");
    private static final Color AREA_FILL = new Color(UITheme.ACCENT.getRed(), UITheme.ACCENT.getGreen(),
            UITheme.ACCENT.getBlue(), 40);

    private ChartUtil() {
    }

    /** Horizontal bars, biggest on top, the count printed at the end of each bar. */
    public static JFreeChart horizontalBar(String title, List<NamedCount> rows, String valueLabel) {
        DefaultCategoryDataset data = new DefaultCategoryDataset();
        for (NamedCount r : rows) {
            data.addValue(r.getCount(), valueLabel, r.getLabel());
        }
        JFreeChart chart = ChartFactory.createBarChart(title, null, valueLabel, data,
                PlotOrientation.HORIZONTAL, false, true, false);
        CategoryPlot plot = chart.getCategoryPlot();
        plot.setRangeAxisLocation(AxisLocation.BOTTOM_OR_LEFT); // counts under the bars
        stylePlotBackground(chart);
        plot.setRangeGridlinePaint(GRID);
        plot.setNoDataMessage("No searches in this period yet.");
        plot.setNoDataMessageFont(UITheme.FONT_BODY);
        plot.setNoDataMessagePaint(UITheme.TEXT_MUTED);

        BarRenderer bars = (BarRenderer) plot.getRenderer();
        bars.setBarPainter(new StandardBarPainter());
        bars.setShadowVisible(false);
        bars.setSeriesPaint(0, UITheme.ACCENT);
        bars.setMaximumBarWidth(0.08);
        bars.setDefaultItemLabelGenerator(new StandardCategoryItemLabelGenerator());
        bars.setDefaultItemLabelsVisible(true);
        bars.setDefaultItemLabelFont(UITheme.FONT_BODY.deriveFont(11f));
        bars.setDefaultItemLabelPaint(UITheme.TEXT);

        CategoryAxis names = plot.getDomainAxis();
        names.setTickLabelFont(UITheme.FONT_BODY.deriveFont(12f));
        names.setTickLabelPaint(UITheme.TEXT);
        names.setMaximumCategoryLabelWidthRatio(0.4f);
        NumberAxis counts = (NumberAxis) plot.getRangeAxis();
        styleNumberAxis(counts);
        counts.setUpperMargin(0.15); // room for the item labels
        return chart;
    }

    /** Line with a light area fill below it, one point per day (keys yyyy-MM-dd, oldest first). */
    public static JFreeChart dailyLine(String title, Map<String, Integer> perDay, String valueLabel) {
        TimeSeries series = new TimeSeries(valueLabel);
        for (Map.Entry<String, Integer> e : perDay.entrySet()) {
            Date day = DateUtil.parseDate(e.getKey());
            if (day != null) {
                series.add(new Day(day), e.getValue());
            }
        }
        TimeSeriesCollection data = new TimeSeriesCollection(series);
        JFreeChart chart = ChartFactory.createTimeSeriesChart(title, null, valueLabel, data, false, true, false);
        XYPlot plot = chart.getXYPlot();
        stylePlotBackground(chart);
        plot.setDomainGridlinesVisible(false);
        plot.setRangeGridlinePaint(GRID);

        XYAreaRenderer area = new XYAreaRenderer(XYAreaRenderer.AREA);
        area.setSeriesPaint(0, AREA_FILL);
        XYLineAndShapeRenderer line = new XYLineAndShapeRenderer(true, true);
        line.setSeriesPaint(0, UITheme.ACCENT);
        line.setSeriesStroke(0, new BasicStroke(2.2f));
        line.setSeriesShape(0, new java.awt.geom.Ellipse2D.Double(-3, -3, 6, 6));
        plot.setDataset(1, data);
        plot.setRenderer(0, line);
        plot.setRenderer(1, area); // dataset 1 (the fill) is drawn behind dataset 0 (the line)

        DateAxis days = (DateAxis) plot.getDomainAxis();
        days.setTickLabelFont(UITheme.FONT_BODY.deriveFont(11f));
        days.setTickLabelPaint(UITheme.TEXT_MUTED);
        days.setDateFormatOverride(new SimpleDateFormat("dd MMM"));
        days.setTickUnit(new DateTickUnit(DateTickUnitType.DAY, perDay.size() > 10 ? 5 : 1));
        styleNumberAxis((NumberAxis) plot.getRangeAxis());
        return chart;
    }

    /** Saves the chart as a 900x600 PNG. */
    public static void savePng(JFreeChart chart, File file) throws IOException {
        ChartUtils.saveChartAsPNG(file, chart, EXPORT_WIDTH, EXPORT_HEIGHT);
    }

    private static void stylePlotBackground(JFreeChart chart) {
        chart.setBackgroundPaint(UITheme.WHITE);
        chart.getPlot().setBackgroundPaint(UITheme.WHITE);
        chart.getPlot().setOutlineVisible(false);
        if (chart.getTitle() != null) {
            chart.getTitle().setFont(UITheme.FONT_BODY_BOLD.deriveFont(Font.BOLD, 14f));
            chart.getTitle().setPaint(UITheme.SIDEBAR);
        }
    }

    private static void styleNumberAxis(NumberAxis axis) {
        axis.setStandardTickUnits(NumberAxis.createIntegerTickUnits());
        axis.setTickLabelFont(UITheme.FONT_BODY.deriveFont(11f));
        axis.setTickLabelPaint(UITheme.TEXT_MUTED);
        axis.setLabelFont(UITheme.FONT_BODY.deriveFont(11f));
        axis.setLabelPaint(UITheme.TEXT_MUTED);
        axis.setAutoRangeIncludesZero(true);
    }
}
