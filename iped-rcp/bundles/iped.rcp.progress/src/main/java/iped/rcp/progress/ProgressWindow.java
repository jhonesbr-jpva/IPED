package iped.rcp.progress;

import java.awt.GraphicsEnvironment;
import java.beans.PropertyChangeEvent;
import java.io.File;
import java.io.InputStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.TaskBar;
import org.eclipse.swt.widgets.TaskItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IItem;
import iped.engine.Version;
import iped.engine.core.Statistics;
import iped.engine.core.Worker;
import iped.engine.core.Worker.STATE;
import iped.engine.localization.Messages;
import iped.engine.task.AbstractTask;
import iped.engine.task.ExportFileTask;
import iped.engine.task.ParsingTask;
import iped.engine.task.carver.BaseCarveTask;
import iped.engine.util.UIPropertyListenerProvider;
import iped.engine.util.Util;
import iped.parsers.standard.StandardParser;
import iped.utils.LocalizedFormat;

/**
 * Standalone SWT processing progress window (task T038, FR-026, research
 * R10), replacing the Swing {@code ProgressFrame} as the consumer of the
 * {@link UIPropertyListenerProvider} event bus. The publisher side does not
 * change (FR-028): this class registers as a non-UI listener and marshals
 * every update itself with {@link Display#asyncExec} (UI-thread rule,
 * Principle V — the SWT analogue of the EDT discipline).
 *
 * <p>
 * Field-by-field parity with {@code ProgressFrame} (parity inventory
 * PG-01..PG-08): global progress + message/ETA, per-worker task/item table,
 * task and parser time tables, statistics and environment counters, mean and
 * current rates, pause/continue. Additions over the legacy window, allowed by
 * the progress-ui-events contract: an explicit abort button with confirmation
 * (closing the window no longer aborts the processing — registered
 * divergence), a mean items/s row and a throughput history sparkline.
 *
 * <p>
 * This is a plain-classpath component (no OSGi): the processing JVM stays a
 * flat-classpath CLI. {@link #open(UIPropertyListenerProvider)} returns
 * {@code null} when no display is available, so callers can fall back to the
 * console (contract acceptance criterion 4).
 */
public class ProgressWindow implements AutoCloseable {

    /**
     * Test hook (T037): forces {@link #open(UIPropertyListenerProvider)} to
     * behave as in a headless environment. A real no-display environment
     * cannot be simulated on an interactive desktop.
     */
    public static final String FORCE_HEADLESS_PROP = "iped.rcp.progress.headless";

    private static final Logger LOGGER = LoggerFactory.getLogger(ProgressWindow.class);

    private static final int MAX_PROGRESS_BAR = 10000;

    private final UIPropertyListenerProvider provider;

    private Display display;
    private Shell shell;
    private Thread uiThread;

    private Label messageLabel;
    private ProgressBar progressBar;
    private Button openApp, pause, abort;
    private ThroughputCanvas throughput;
    private Table statsTable, envTable, tasksTable, parsersTable, workersTable;
    private Font boldFont;

    // mirrored state of the legacy ProgressFrame
    private int prevVolume;
    private boolean discoverEnded;
    private long rate, instantRate;
    private long secsToEnd;
    private long processingStart;
    private Worker[] workers;
    private String[] lastWorkerTaskItemId;
    private long[] lastWorkerTime;
    private boolean paused = false;
    private String decodingDir = null;
    private long physicalMemory;
    private final Map<String, Long> timesPerParser = new TreeMap<String, Long>();

    // UI-thread only (LocalizedFormat instances are not thread-safe)
    private NumberFormat nf;
    private DecimalFormat pctFormat;

    /**
     * Opens the progress window on its own UI thread and subscribes it to the
     * provider's events.
     *
     * @return the window, or {@code null} when no display is available (the
     *         caller must fall back to the console) or SWT failed to start
     */
    public static ProgressWindow open(UIPropertyListenerProvider provider) {
        if (Boolean.getBoolean(FORCE_HEADLESS_PROP)) {
            LOGGER.info("SWT progress window disabled by {}", FORCE_HEADLESS_PROP);
            return null;
        }
        try {
            if (GraphicsEnvironment.isHeadless()) {
                return null;
            }
        } catch (Throwable t) {
            // no AWT at all: let the SWT display attempt decide below
        }
        ProgressWindow window = new ProgressWindow(provider);
        try {
            window.start();
            return window;
        } catch (Throwable t) {
            LOGGER.warn("SWT progress window could not be opened, falling back ({})", t.toString());
            return null;
        }
    }

    /**
     * Shows a startup/initializer error dialog when a display is available
     * (task T041, FR-027). Safe to call when no window was ever opened: a
     * short-lived display is created just for the dialog.
     *
     * @return true if the dialog was shown
     */
    public static boolean showStartupError(String title, String message) {
        if (Boolean.getBoolean(FORCE_HEADLESS_PROP)) {
            return false;
        }
        try {
            if (GraphicsEnvironment.isHeadless()) {
                return false;
            }
            Display display = new Display();
            try {
                Shell shell = new Shell(display);
                MessageBox box = new MessageBox(shell, SWT.ICON_ERROR | SWT.OK);
                box.setText(title);
                box.setMessage(message);
                box.open();
                shell.dispose();
            } finally {
                display.dispose();
            }
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Could not show SWT startup error dialog ({})", t.toString());
            return false;
        }
    }

    private ProgressWindow(UIPropertyListenerProvider provider) {
        this.provider = provider;
    }

    private void start() throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        uiThread = new Thread(() -> {
            try {
                display = new Display();
                createContents();
                shell.open();
                ready.countDown();
                while (!shell.isDisposed()) {
                    try {
                        if (!display.readAndDispatch()) {
                            display.sleep();
                        }
                    } catch (Throwable t) {
                        // EDT-like resilience: one failing update must not
                        // kill the progress window (the Swing frame survives
                        // listener exceptions the same way)
                        LOGGER.error("Progress window update failed", t);
                    }
                }
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                try {
                    if (display != null && !display.isDisposed()) {
                        display.dispose();
                    }
                } catch (Throwable t) {
                    LOGGER.warn("Error disposing progress display", t);
                }
                ready.countDown();
            }
        }, "SWT-Progress-UI");
        uiThread.setDaemon(true);
        uiThread.start();
        ready.await();
        if (failure.get() != null) {
            throw new IllegalStateException("SWT display could not be created", failure.get());
        }
        provider.addPropertyChangeListener(this::onEvent, false);
    }

    /** Disposes the window; never aborts the processing. */
    @Override
    public void close() {
        Display d = display;
        if (d == null || d.isDisposed()) {
            return;
        }
        try {
            d.syncExec(() -> {
                if (shell != null && !shell.isDisposed()) {
                    // bypass the close-confirmation listener: this is the
                    // programmatic shutdown at the end of the processing
                    shell.dispose();
                }
            });
        } catch (SWTException e) {
            // display disposed concurrently by the user closing the window
        }
    }

    /** Test probe (T037): current message text, read on the UI thread. */
    public String probeMessageText() {
        AtomicReference<String> text = new AtomicReference<>("");
        Display d = display;
        if (d != null && !d.isDisposed()) {
            d.syncExec(() -> {
                if (messageLabel != null && !messageLabel.isDisposed()) {
                    text.set(messageLabel.getText());
                }
            });
        }
        return text.get();
    }

    // ------------------------------------------------------------------
    // UI construction (UI thread)
    // ------------------------------------------------------------------

    private void createContents() {
        nf = LocalizedFormat.getNumberInstance();
        pctFormat = LocalizedFormat.getDecimalInstance("0.0%");

        shell = new Shell(display, SWT.SHELL_TRIM);
        shell.setText(Version.APP_NAME);
        shell.setSize(1100, 500);
        setWindowIcons();
        centerOnScreen();
        GridLayout layout = new GridLayout(1, false);
        shell.setLayout(layout);

        FontData[] base = shell.getFont().getFontData();
        for (FontData fd : base) {
            fd.setStyle(SWT.BOLD);
        }
        boldFont = new Font(display, base);
        shell.addListener(SWT.Dispose, e -> boldFont.dispose());

        Composite top = new Composite(shell, SWT.NONE);
        top.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        top.setLayout(new GridLayout(2, false));

        Composite progressArea = new Composite(top, SWT.NONE);
        progressArea.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        progressArea.setLayout(new GridLayout(1, false));

        messageLabel = new Label(progressArea, SWT.NONE);
        messageLabel.setText(msg("ProgressFrame.Starting"));
        messageLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        progressBar = new ProgressBar(progressArea, SWT.HORIZONTAL);
        progressBar.setMinimum(0);
        progressBar.setMaximum(MAX_PROGRESS_BAR);
        GridData barData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        barData.heightHint = 22;
        progressBar.setLayoutData(barData);

        // PG-03: throughput history graph (contract addition over the legacy
        // window, which only shows the textual mean/current rates)
        throughput = new ThroughputCanvas(progressArea);
        GridData graphData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        graphData.heightHint = 48;
        throughput.setLayoutData(graphData);

        Composite buttons = new Composite(top, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        buttons.setLayout(new GridLayout(1, true));

        openApp = new Button(buttons, SWT.PUSH);
        openApp.setText(msg("ProgressFrame.OpenApp"));
        openApp.setEnabled(false);
        openApp.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        openApp.addListener(SWT.Selection, e -> openAnalysisUi());

        pause = new Button(buttons, SWT.PUSH);
        pause.setText(msg("ProgressFrame.Pause"));
        pause.setEnabled(false);
        pause.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        pause.addListener(SWT.Selection, e -> togglePause());

        abort = new Button(buttons, SWT.PUSH);
        abort.setText(msg("ProgressWindow.Abort"));
        abort.setEnabled(false);
        abort.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        abort.addListener(SWT.Selection, e -> confirmAbort());

        ScrolledComposite scroll = new ScrolledComposite(shell, SWT.H_SCROLL | SWT.V_SCROLL);
        scroll.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        scroll.setExpandHorizontal(true);
        scroll.setExpandVertical(true);

        Composite body = new Composite(scroll, SWT.NONE);
        body.setLayout(new GridLayout(4, false));
        scroll.setContent(body);

        Composite statsColumn = new Composite(body, SWT.NONE);
        statsColumn.setLayoutData(new GridData(SWT.FILL, SWT.FILL, false, true));
        GridLayout statsLayout = new GridLayout(1, false);
        statsLayout.marginWidth = 0;
        statsColumn.setLayout(statsLayout);
        statsTable = createSection(statsColumn, msg("ProgressFrame.Statistics"), 2);
        envTable = createSection(statsColumn, msg("ProgressFrame.Environment"), 2);

        tasksTable = createSection(column(body), msg("ProgressFrame.TaskTimes"), 3);
        parsersTable = createSection(column(body), msg("ProgressFrame.ParserTimes"), 3);
        workersTable = createSection(column(body), msg("ProgressFrame.CurrentItems"), 4);

        scroll.setMinSize(body.computeSize(SWT.DEFAULT, SWT.DEFAULT));

        // closing the window does NOT abort the processing (progress-ui-events
        // contract; divergence vs the legacy frame, which cancelled on close)
        shell.addListener(SWT.Close, e -> {
            MessageBox box = new MessageBox(shell, SWT.ICON_QUESTION | SWT.YES | SWT.NO);
            box.setText(Version.APP_NAME);
            box.setMessage(msg("ProgressWindow.CloseConfirm"));
            e.doit = box.open() == SWT.YES;
        });
    }

    private Composite column(Composite body) {
        Composite col = new Composite(body, SWT.NONE);
        col.setLayoutData(new GridData(SWT.FILL, SWT.FILL, false, true));
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        col.setLayout(layout);
        return col;
    }

    private Table createSection(Composite parent, String title, int columns) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(title);
        label.setFont(boldFont);
        Table table = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION);
        table.setHeaderVisible(false);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        for (int i = 0; i < columns; i++) {
            new TableColumn(table, i == 0 ? SWT.LEFT : SWT.RIGHT);
        }
        return table;
    }

    private void setWindowIcons() {
        List<Image> icons = new ArrayList<>();
        for (String name : new String[] { "process16.png", "process24.png", "process32.png", "process48.png",
                "process64.png" }) {
            try (InputStream is = ProgressWindow.class.getResourceAsStream("/iped/app/icon/" + name)) {
                if (is != null) {
                    icons.add(new Image(display, is));
                }
            } catch (Exception e) {
                // icon loading is cosmetic only
            }
        }
        if (!icons.isEmpty()) {
            shell.setImages(icons.toArray(new Image[0]));
            shell.addListener(SWT.Dispose, e -> icons.forEach(Image::dispose));
        }
    }

    private void centerOnScreen() {
        var bounds = display.getPrimaryMonitor().getBounds();
        var size = shell.getSize();
        shell.setLocation(bounds.x + (bounds.width - size.x) / 2, bounds.y + (bounds.height - size.y) / 2);
    }

    // ------------------------------------------------------------------
    // Event handling (engine threads -> UI thread)
    // ------------------------------------------------------------------

    private void onEvent(PropertyChangeEvent evt) {
        Display d = display;
        if (d == null || d.isDisposed()) {
            return;
        }
        try {
            d.asyncExec(() -> {
                if (shell != null && !shell.isDisposed()) {
                    handleEvent(evt);
                }
            });
        } catch (SWTException e) {
            // display disposed between the check and the dispatch
        }
    }

    private void handleEvent(PropertyChangeEvent evt) {
        if (processingStart == 0) {
            processingStart = System.currentTimeMillis();
            physicalMemory = Util.getPhysicalMemorySize();
            updateTaskbar(0, 0, false);
        }

        if ("discoverEnded".equals(evt.getPropertyName())) {
            discoverEnded = true;
            update();

        } else if ("update".equals(evt.getPropertyName())) {
            update();

        } else if ("decodingDir".equals(evt.getPropertyName())) {
            decodingDir = (String) evt.getNewValue();

        } else if ("mensagem".equals(evt.getPropertyName())) {
            messageLabel.setText((String) evt.getNewValue());
            refreshTables();

        } else if ("workers".equals(evt.getPropertyName())) {
            workers = (Worker[]) evt.getNewValue();
            lastWorkerTaskItemId = new String[workers.length];
            lastWorkerTime = new long[workers.length];
            pause.setEnabled(true);
            abort.setEnabled(true);
        }
    }

    /** Mirrors {@code ProgressFrame.update()} field by field. */
    private void update() {
        Statistics s = Statistics.get();
        if (s == null) {
            return;
        }
        int totalVolume = (int) (s.getCaseData().getDiscoveredVolume() >>> 20); // MB
        int totalItems = s.getCaseData().getDiscoveredEvidences();
        int processedVolume = (int) (s.getVolume() >>> 20); // MB
        int processedItems = s.getProcessed();

        long interval = (System.currentTimeMillis() - processingStart) / 1000 + 1;
        rate = processedVolume * 3600L / ((1 << 10) * interval);
        instantRate = (processedVolume - prevVolume) * 3600L / (1 << 10) + 1;

        if (discoverEnded) {
            float volumeProgress = (float) processedVolume / totalVolume;
            float itemsProgress = (float) processedItems / totalItems;
            int newProgressValue = (int) (MAX_PROGRESS_BAR * (volumeProgress + itemsProgress) / 2);
            // progress only moves forward, even if totalItems increases
            if (newProgressValue > progressBar.getSelection()) {
                progressBar.setSelection(newProgressValue);
            }
        }

        refreshTables();
        if (processedItems > 0) {
            openApp.setEnabled(true);
        }

        String text = messageLabel.getText();
        if (processedItems > 0) {
            text = msg("ProgressFrame.Processing") + processedItems + " / " + totalItems;
        } else if (totalItems > 0) {
            text = msg("ProgressFrame.Found") + totalItems + msg("ProgressFrame.items");
        }

        if (discoverEnded && processingStart != 0) {
            long secsToEndVolume = (totalVolume - processedVolume) * (System.currentTimeMillis() - processingStart)
                    / ((processedVolume + 1) * 1000L);
            long secsToEndItems = (totalItems - processedItems) * (System.currentTimeMillis() - processingStart)
                    / ((processedItems + 1) * 1000L);
            secsToEnd = Math.max(secsToEndVolume, secsToEndItems);
            text += msg("ProgressFrame.FinishIn") + formatHMS(secsToEnd);
        } else if (decodingDir != null) {
            text += " - " + decodingDir;
        }
        messageLabel.setText(text);
        throughput.addSample(instantRate);
        updateTaskbar(totalVolume, processedVolume, discoverEnded);
        prevVolume = processedVolume;
    }

    private void refreshTables() {
        updateStats();
        updateTaskTimes();
        updateParserTimes();
        updateWorkerItems();
    }

    // ------------------------------------------------------------------
    // Section tables (ports of getStats/getTaskTimes/getParserTimes/
    // getItemList from ProgressFrame, HTML tables -> SWT tables)
    // ------------------------------------------------------------------

    private void updateStats() {
        Statistics s = Statistics.get();
        if (s == null) {
            return;
        }
        List<Row> rows = new ArrayList<>();
        long time = (System.currentTimeMillis() - processingStart) / 1000;
        rows.add(Row.of(msg("ProgressFrame.ProcessingTime"), formatHMS(time)));
        rows.add(Row.of(msg("ProgressFrame.EstimatedEnd"), secsToEnd == 0 ? "-" : formatHMS(secsToEnd)));
        rows.add(Row.of(msg("ProgressFrame.MeanSpeed"), nf.format(rate) + " GB/h"));
        rows.add(Row.of(msg("ProgressFrame.CurrentSpeed"), nf.format(instantRate) + " GB/h"));
        // PG-03 contract addition: items/s alongside GB/h
        long interval = (System.currentTimeMillis() - processingStart) / 1000 + 1;
        rows.add(Row.of(msg("ProgressWindow.ItemSpeed"), nf.format(s.getProcessed() / interval) + " items/s"));
        rows.add(Row.of(msg("ProgressFrame.VolumeFound"), formatMB(s.getCaseData().getDiscoveredVolume())));
        rows.add(Row.of(msg("ProgressFrame.VolumeProcessed"), formatMB(s.getVolume())));
        rows.add(Row.of(msg("ProgressFrame.ItemsFound"), nf.format(s.getCaseData().getDiscoveredEvidences())));
        rows.add(Row.of(msg("ProgressFrame.ItemsProcessed"), nf.format(s.getProcessed())));
        rows.add(Row.of(msg("ProgressFrame.ActiveProcessed"), nf.format(s.getActiveProcessed())));
        rows.add(Row.of(msg("ProgressFrame.SubitemsProcessed"), nf.format(s.getSubitemsDiscovered())));
        rows.add(Row.of(msg("ProgressFrame.Carved"), nf.format(BaseCarveTask.getItensCarved())));
        rows.add(Row.of(msg("ProgressFrame.CarvedDiscarded"), nf.format(s.getCorruptCarveIgnored())));
        rows.add(Row.of(msg("ProgressFrame.Exported"), nf.format(ExportFileTask.getItensExtracted())));
        rows.add(Row.of(msg("ProgressFrame.Ignored"), nf.format(s.getIgnored())));
        rows.add(Row.of(msg("ProgressFrame.ParsingErrors"), nf.format(StandardParser.parsingErrors)));
        rows.add(Row.of(msg("ProgressFrame.ReadErrors"), nf.format(s.getIoErrors())));
        rows.add(Row.of(msg("ProgressFrame.Timeouts"), nf.format(s.getTimeouts())));
        setRows(statsTable, rows);

        updateEnvironment();
    }

    private void updateEnvironment() {
        List<Row> rows = new ArrayList<>();
        rows.add(Row.of(msg("ProgressFrame.JavaVersion"), Runtime.version().toString()));
        rows.add(Row.of(msg("ProgressFrame.FreeMemory"), formatMB(Runtime.getRuntime().freeMemory())));
        rows.add(Row.of(msg("ProgressFrame.TotalMemory"), formatMB(Runtime.getRuntime().totalMemory())));
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory < Long.MAX_VALUE) {
            rows.add(Row.of(msg("ProgressFrame.MaxMemory"), formatMB(maxMemory)));
        }
        if (physicalMemory != 0) {
            rows.add(Row.of(msg("ProgressFrame.PhysicalMemory"), formatMB(physicalMemory)));
        }
        long freeMemory = Util.getFreeMemorySize();
        if (physicalMemory > 0 && freeMemory > 0) {
            double memoryUsage = (physicalMemory - freeMemory) / (double) physicalMemory;
            rows.add(Row.of(msg("ProgressFrame.PhysicalMemoryUsage"), pctFormat.format(memoryUsage)));
        }
        double cpuUsage = Util.getSystemCpuLoad();
        if (cpuUsage >= 0) {
            rows.add(Row.of(msg("ProgressFrame.CPUUsage"), pctFormat.format(cpuUsage)));
        }
        if (workers != null && workers.length > 0) {
            try {
                FileStore outputVolume = Files.getFileStore(workers[0].output.getCanonicalFile().toPath());
                FileStore tempVolume = Files
                        .getFileStore(new File(System.getProperty("java.io.tmpdir")).getCanonicalFile().toPath());
                if (outputVolume.equals(tempVolume)) {
                    rows.add(Row.of(msg("ProgressFrame.OutputTempVolume"), outputVolume.toString()));
                    rows.add(Row.of(msg("ProgressFrame.OutputTempFree"), formatFree(outputVolume)));
                } else {
                    rows.add(Row.of(msg("ProgressFrame.OutputVolume"), outputVolume.toString()));
                    rows.add(Row.of(msg("ProgressFrame.OutputFree"), formatFree(outputVolume)));
                    rows.add(Row.of(msg("ProgressFrame.TempVolume"), tempVolume.toString()));
                    rows.add(Row.of(msg("ProgressFrame.TempFree"), formatFree(tempVolume)));
                }
            } catch (Exception e) {
                // volume info is best-effort, as in the legacy frame
            }
        }
        setRows(envTable, rows);
    }

    private void updateTaskTimes() {
        if (workers == null || workers.length == 0) {
            return;
        }
        List<Row> rows = new ArrayList<>();
        long totalTime = 0;
        long[] taskTimes = new long[workers[0].tasks.size()];
        for (Worker worker : workers) {
            for (int i = 0; i < taskTimes.length; i++) {
                long t = worker.tasks.get(i).getTaskTime();
                taskTimes[i] += t;
                totalTime += t;
            }
        }
        if (totalTime < 1) {
            totalTime = 1;
        }
        for (int i = 0; i < taskTimes.length; i++) {
            AbstractTask task = workers[0].tasks.get(i);
            if (task.isEnabled()) {
                long sec = taskTimes[i] / (1000000 * workers.length);
                int pct = (int) ((100 * taskTimes[i] + totalTime / 2) / totalTime);
                rows.add(new Row(new String[] { task.getName(), nf.format(sec) + "s", pct + "%" }, pct, true));
            } else {
                rows.add(new Row(new String[] { task.getName(), "-", "-" }, -1, false));
            }
        }
        setRows(tasksTable, rows);
    }

    private void updateParserTimes() {
        ParsingTask.copyTimesPerParser(timesPerParser);
        if (timesPerParser.isEmpty() || workers == null || workers.length == 0) {
            return;
        }
        List<Row> rows = new ArrayList<>();
        long totalTime = 0;
        for (long parserTime : timesPerParser.values()) {
            totalTime += parserTime;
        }
        if (totalTime < 1) {
            totalTime = 1;
        }
        for (Map.Entry<String, Long> entry : timesPerParser.entrySet()) {
            long sec = entry.getValue() / (1000000 * workers.length);
            int pct = (int) ((100 * entry.getValue() + totalTime / 2) / totalTime);
            rows.add(new Row(new String[] { entry.getKey(), nf.format(sec) + "s", pct + "%" }, pct, true));
        }
        setRows(parsersTable, rows);
    }

    private void updateWorkerItems() {
        if (workers == null) {
            return;
        }
        List<Row> rows = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < workers.length; i++) {
            Worker worker = workers[i];
            if (!worker.isAlive()) {
                continue;
            }
            AbstractTask task = worker.runningTask;
            String taskName = task == null ? null : task.getName();
            IItem evidence = worker.evidence;

            String wt = "-";
            int pct = -1;
            if (taskName != null && evidence != null) {
                String taskId = taskName + evidence.getId();
                if (!taskId.equals(lastWorkerTaskItemId[i])) {
                    lastWorkerTime[i] = now;
                    lastWorkerTaskItemId[i] = taskId;
                } else if (worker.state != STATE.PAUSED) {
                    long t = (now - lastWorkerTime[i]) / 1000;
                    wt = t < 60 ? t + "s" : t / 60 + "m";
                    pct = (int) Math.min(t / 30, 60);
                }
            } else {
                lastWorkerTaskItemId[i] = null;
            }

            String taskCell;
            if (worker.state == STATE.PAUSED) {
                taskCell = msg("ProgressFrame.Paused");
            } else if (worker.state == STATE.PAUSING) {
                taskCell = msg("ProgressFrame.Pausing");
            } else if (task != null) {
                taskCell = taskName;
            } else {
                taskCell = "-";
            }

            String itemCell;
            if (evidence != null) {
                itemCell = evidence.getPath();
                if (evidence.getLength() != null && evidence.getLength() > 0) {
                    itemCell += " (" + nf.format(evidence.getLength()) + " bytes)";
                }
            } else {
                itemCell = msg("ProgressFrame.WaitingItem");
            }

            rows.add(new Row(new String[] { worker.getName(), taskCell, wt, itemCell }, pct,
                    worker.state != STATE.PAUSED));
        }
        setRows(workersTable, rows);
    }

    /** One table row: cells + the legacy percentage-based shading. */
    private record Row(String[] cells, int pct, boolean enabled) {
        static Row of(String label, Object value) {
            return new Row(new String[] { label, String.valueOf(value) }, -1, true);
        }
    }

    private void setRows(Table table, List<Row> rows) {
        table.setRedraw(false);
        try {
            int existing = table.getItemCount();
            for (int i = 0; i < rows.size(); i++) {
                TableItem item = i < existing ? table.getItem(i) : new TableItem(table, SWT.NONE);
                Row row = rows.get(i);
                item.setText(row.cells());
                item.setBackground(rowBackground(row.pct()));
                item.setForeground(row.enabled() ? null : display.getSystemColor(SWT.COLOR_GRAY));
            }
            if (existing > rows.size()) {
                table.remove(rows.size(), existing - 1);
            }
            for (TableColumn column : table.getColumns()) {
                column.pack();
            }
        } finally {
            table.setRedraw(true);
        }
        table.getParent().getParent().layout(true, true);
    }

    /** Same shading formula as the legacy HTML rows. */
    private Color rowBackground(int pct) {
        if (pct < 0) {
            return null;
        }
        int c = pct == 0 ? 255 : 245 - Math.min(75, pct) * 3 / 2;
        return new Color(c, c, 255);
    }

    // ------------------------------------------------------------------
    // Actions (T040)
    // ------------------------------------------------------------------

    private void togglePause() {
        if (workers == null) {
            return;
        }
        paused = !paused;
        pause.setText(paused ? msg("ProgressFrame.Continue") : msg("ProgressFrame.Pause"));
        for (Worker worker : workers) {
            synchronized (worker) {
                worker.state = paused ? Worker.STATE.PAUSING : Worker.STATE.RUNNING;
            }
        }
    }

    private void confirmAbort() {
        MessageBox box = new MessageBox(shell, SWT.ICON_WARNING | SWT.YES | SWT.NO);
        box.setText(Version.APP_NAME);
        box.setMessage(msg("ProgressWindow.AbortConfirm"));
        if (box.open() == SWT.YES) {
            LOGGER.warn("Processing abort requested from the progress window");
            abort.setEnabled(false);
            pause.setEnabled(false);
            provider.cancel(true);
        }
    }

    /**
     * Launches the RCP analysis product over the case being processed
     * (near-live mode, FR-029) or over the finished case (T040, contract
     * case-launcher-packaging). Always a separate process (research R14).
     */
    private void openAnalysisUi() {
        if (workers == null || workers.length == 0) {
            return;
        }
        File caseRoot = workers[0].output.getParentFile();
        File launcher = AnalysisUiLauncher.resolveLauncher(caseRoot);
        if (launcher == null) {
            MessageBox box = new MessageBox(shell, SWT.ICON_INFORMATION | SWT.OK);
            box.setText(Version.APP_NAME);
            box.setMessage(msg("ProgressWindow.UiNotFound"));
            box.open();
            return;
        }
        try {
            AnalysisUiLauncher.launch(launcher, caseRoot);
            LOGGER.info("Analysis UI launched: {} {}", launcher, caseRoot);
        } catch (Exception e) {
            LOGGER.error("Error launching the analysis UI", e);
            MessageBox box = new MessageBox(shell, SWT.ICON_ERROR | SWT.OK);
            box.setText(Version.APP_NAME);
            box.setMessage(msg("ProgressWindow.OpenUiError") + "\n" + e);
            box.open();
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void updateTaskbar(int totalVolume, int processedVolume, boolean discoverEnded) {
        TaskBar taskBar = display.getSystemTaskBar();
        if (taskBar == null) {
            return;
        }
        TaskItem item = taskBar.getItem(shell);
        if (item == null) {
            item = taskBar.getItem(null);
        }
        if (item == null) {
            return;
        }
        item.setProgressState(paused ? SWT.PAUSED : discoverEnded ? SWT.NORMAL : SWT.INDETERMINATE);
        if (discoverEnded && totalVolume > 0) {
            // start from 10%, like the legacy frame, so "paused" early on is visible
            int pct = (int) Math.min(100, 10 + Math.round(90.0 * processedVolume / totalVolume));
            item.setProgress(pct);
        }
    }

    /** Never throws on a missing key (SC-006 discipline): logs and marks. */
    private static String msg(String key) {
        try {
            return Messages.getString(key);
        } catch (MissingResourceException e) {
            LOGGER.error("Missing localization key: {}", key);
            return "!" + key + "!";
        }
    }

    private static String formatHMS(long secs) {
        return secs / 3600 + "h " + (secs / 60) % 60 + "m " + secs % 60 + "s";
    }

    private String formatMB(long value) {
        return nf.format(value >>> 20) + " MB";
    }

    private String formatGB(long value) {
        return nf.format(value >>> 30) + " GB";
    }

    private String formatFree(FileStore store) throws Exception {
        return formatGB(store.getUsableSpace()) + " (" + store.getUsableSpace() * 100 / store.getTotalSpace() + "%)";
    }
}
