package iped.rcp.views.report;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IMultiBookmarks;
import iped.engine.data.IPEDMultiSource;
import iped.engine.data.ReportInfo;
import iped.rcp.core.bookmarks.BookmarkService;
import iped.rcp.core.i18n.Messages;
import iped.rcp.core.session.ICaseSessionManager;
import iped.utils.LocalizedFormat;

/**
 * Report wizard (task T023, FR-015, parity {@code ReportDialog} +
 * {@code ReportInfoDialog}): selects bookmarks (with the per-bookmark
 * "thumbs only"/no-content flag), output and options, fills the case info
 * and spawns the SAME report generation pipeline as the current UI - a
 * plain-classpath child JVM running {@code iped.app.bootstrap.Bootstrap}
 * with {@code -Diped.ui.report} over a bookmark state snapshot ({@code
 * .iped} temp file), reading the engine from the case's own {@code lib/}
 * (self-contained case contract).
 */
public class ReportWizard extends Wizard {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReportWizard.class);

    /** Same switch the legacy UI passes to the child JVM (Bootstrap.UI_REPORT_SYS_PROP). */
    private static final String UI_REPORT_SYS_PROP = "iped.ui.report";
    private static final String BOOTSTRAP_MAIN = "iped.app.bootstrap.Bootstrap";
    private static final String SEARCH_APP_JAR = "lib/iped-search-app.jar";
    private static final String NO_LINKED_ITEMS_OPTION = "--nolinkeditems";

    private final ICaseSessionManager sessionManager;
    private final BookmarkService bookmarkService;

    private OptionsPage optionsPage;
    private CaseInfoPage caseInfoPage;

    public ReportWizard(ICaseSessionManager sessionManager, BookmarkService bookmarkService) {
        this.sessionManager = sessionManager;
        this.bookmarkService = bookmarkService;
        setWindowTitle(Messages.getString("ReportDialog.Title"));
        setNeedsProgressMonitor(true);
    }

    @Override
    public void addPages() {
        optionsPage = new OptionsPage();
        caseInfoPage = new CaseInfoPage();
        addPage(optionsPage);
        addPage(caseInfoPage);
    }

    @Override
    public boolean performFinish() {
        String output = optionsPage.outputText.getText().trim();
        if (output.isEmpty()) {
            MessageDialog.openError(getShell(), Messages.getString("ReportDialog.ErrorTitle"),
                    Messages.getString("ReportDialog.OutputRequired"));
            return false;
        }
        try {
            launchReport(new File(output));
            return true;
        } catch (IOException e) {
            LOGGER.error("Error launching report generation", e);
            MessageDialog.openError(getShell(), Messages.getString("ReportDialog.ErrorTitle"),
                    Messages.getString("ReportDialog.ReportError"));
            return false;
        }
    }

    private void launchReport(File output) throws IOException {
        IPEDMultiSource source = sessionManager.getSession().getSource();
        IMultiBookmarks bookmarks = source.getMultiBookmarks();

        // bookmark selection travels inside the state snapshot (legacy flow)
        Set<String> noContent = new HashSet<>();
        for (TableItem item : optionsPage.bookmarksTable.getItems()) {
            String name = item.getText(0);
            bookmarks.setInReport(name, item.getChecked());
            if (Boolean.TRUE.equals(item.getData("nocontent"))) {
                noContent.add(name);
            }
        }
        File input = File.createTempFile("report", ".iped");
        bookmarks.saveState(input);

        File caseInfoFile = caseInfoPage.toReportInfo().writeReportInfoFile();

        File moduleDir = source.getAtomicSources().get(0).getModuleDir();
        String javaBin = "java";
        if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
            javaBin = new File(moduleDir, "jre\\bin\\java.exe").getAbsolutePath();
        }
        List<String> cmd = new ArrayList<>(Arrays.asList(javaBin, "-cp",
                new File(moduleDir, SEARCH_APP_JAR).getAbsolutePath(), "-D" + UI_REPORT_SYS_PROP, BOOTSTRAP_MAIN,
                "-d", input.getAbsolutePath(), "-o", output.getAbsolutePath()));
        cmd.addAll(Arrays.asList("-asap", caseInfoFile.getAbsolutePath()));

        String keywords = optionsPage.keywordsText.getText().trim();
        if (!keywords.isEmpty()) {
            cmd.addAll(Arrays.asList("-l", keywords));
        }
        if (optionsPage.noAttachs.getSelection()) {
            cmd.add("--nopstattachs");
        }
        if (optionsPage.noLinkedItems.getSelection()) {
            cmd.add(NO_LINKED_ITEMS_OPTION);
        }
        if (optionsPage.append.getSelection()) {
            cmd.add("--append");
        }
        for (String label : noContent) {
            cmd.add("-nocontent");
            cmd.add(label);
        }

        LOGGER.info("Report command: {}", cmd);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        Display display = getShell().getDisplay();

        Job job = Job.create(Messages.getString("ReportDialog.Title"), (IProgressMonitor monitor) -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        LOGGER.info(line);
                    }
                }
                int result = process.waitFor();
                display.asyncExec(() -> {
                    if (result == 0) {
                        MessageDialog.openInformation(null, Messages.getString("ReportDialog.Title"),
                                Messages.getString("ReportDialog.ReportFinished"));
                    } else {
                        MessageDialog.openError(null, Messages.getString("ReportDialog.ErrorTitle"),
                                Messages.getString("ReportDialog.ReportError"));
                    }
                });
                return Status.OK_STATUS;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Status.CANCEL_STATUS;
            } catch (IOException e) {
                LOGGER.error("Error monitoring report process", e);
                return Status.error(e.getMessage(), e);
            }
        });
        job.setUser(true);
        job.schedule();
    }

    /** Page 1: bookmarks to include, output folder and options. */
    private final class OptionsPage extends WizardPage {

        private Table bookmarksTable;
        private Text outputText;
        private Text keywordsText;
        private Button noAttachs;
        private Button noLinkedItems;
        private Button append;

        OptionsPage() {
            super("report-options");
            setTitle(Messages.getString("ReportDialog.Title"));
            setDescription(Messages.getString("ReportDialog.ChooseLabel"));
        }

        @Override
        public void createControl(Composite parent) {
            Composite content = new Composite(parent, SWT.NONE);
            content.setLayout(new GridLayout(3, false));

            bookmarksTable = new Table(content, SWT.CHECK | SWT.BORDER | SWT.FULL_SELECTION);
            bookmarksTable.setHeaderVisible(true);
            GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1);
            tableData.heightHint = 240;
            bookmarksTable.setLayoutData(tableData);

            TableColumn nameColumn = new TableColumn(bookmarksTable, SWT.LEFT);
            nameColumn.setText(Messages.getString("ReportDialog.TableHeader1"));
            nameColumn.setWidth(280);
            TableColumn countColumn = new TableColumn(bookmarksTable, SWT.RIGHT);
            countColumn.setText("#");
            countColumn.setWidth(80);
            TableColumn thumbsColumn = new TableColumn(bookmarksTable, SWT.CENTER);
            thumbsColumn.setText(Messages.getString("ReportDialog.TableHeader2"));
            thumbsColumn.setWidth(110);

            for (String name : bookmarkService.getBookmarkNames()) {
                TableItem item = new TableItem(bookmarksTable, SWT.NONE);
                item.setText(0, name);
                item.setText(1, LocalizedFormat.format(bookmarkService.getBookmarkCount(name)));
                item.setText(2, "");
                item.setData("nocontent", Boolean.FALSE);
            }
            // toggle the per-bookmark no-content flag by clicking its cell
            bookmarksTable.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseDown(MouseEvent event) {
                    TableItem item = bookmarksTable.getItem(new Point(event.x, event.y));
                    if (item != null && item.getBounds(2).contains(event.x, event.y)) {
                        boolean next = !Boolean.TRUE.equals(item.getData("nocontent"));
                        item.setData("nocontent", next);
                        item.setText(2, next ? "X" : "");
                    }
                }
            });

            Label outputLabel = new Label(content, SWT.NONE);
            outputLabel.setText(Messages.getString("ReportDialog.Output"));
            outputText = new Text(content, SWT.BORDER);
            outputText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            Button browseOutput = new Button(content, SWT.PUSH);
            browseOutput.setText("...");
            browseOutput.addListener(SWT.Selection, event -> {
                DirectoryDialog dialog = new DirectoryDialog(getShell(), SWT.SAVE);
                String dir = dialog.open();
                if (dir != null) {
                    outputText.setText(dir);
                }
            });

            Label keywordsLabel = new Label(content, SWT.NONE);
            keywordsLabel.setText(Messages.getString("ReportDialog.KeywordsFile"));
            keywordsText = new Text(content, SWT.BORDER);
            keywordsText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            Button browseKeywords = new Button(content, SWT.PUSH);
            browseKeywords.setText("...");
            browseKeywords.addListener(SWT.Selection, event -> {
                FileDialog dialog = new FileDialog(getShell(), SWT.OPEN);
                String file = dialog.open();
                if (file != null) {
                    keywordsText.setText(file);
                }
            });

            noAttachs = new Button(content, SWT.CHECK);
            noAttachs.setText(Messages.getString("ReportDialog.NoAttachments"));
            noAttachs.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
            noLinkedItems = new Button(content, SWT.CHECK);
            noLinkedItems.setText(Messages.getString("ReportDialog.noLinkedItems"));
            noLinkedItems.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
            append = new Button(content, SWT.CHECK);
            append.setText(Messages.getString("ReportDialog.AddToReport"));
            append.setToolTipText(Messages.getString("ReportDialog.AppendWarning"));
            append.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

            setControl(content);
        }
    }

    /** Page 2: case information (parity {@code ReportInfoDialog}). */
    private static final class CaseInfoPage extends WizardPage {

        private Text reportNumber;
        private Text reportDate;
        private Text reportTitle;
        private Text examiners;
        private Text caseNumber;
        private Text requestForm;
        private Text requestDate;
        private Text requester;
        private Text labCaseNumber;
        private Text labCaseDate;
        private Text evidenceDesc;

        CaseInfoPage() {
            super("report-case-info");
            setTitle(Messages.getString("ReportDialog.FillInfo"));
            setDescription(Messages.getString("ReportDialog.CaseInfo"));
        }

        @Override
        public void createControl(Composite parent) {
            Composite content = new Composite(parent, SWT.NONE);
            content.setLayout(new GridLayout(2, false));

            reportNumber = field(content, "ReportDialog.ReportNum");
            reportDate = field(content, "ReportDialog.ReportDate");
            reportTitle = field(content, "ReportDialog.ReportTitle");
            examiners = field(content, "ReportDialog.Examiner");
            caseNumber = field(content, "ReportDialog.Investigation");
            requestForm = field(content, "ReportDialog.Request");
            requestDate = field(content, "ReportDialog.RequestDate");
            requester = field(content, "ReportDialog.Requester");
            labCaseNumber = field(content, "ReportDialog.Record");
            labCaseDate = field(content, "ReportDialog.RecordDate");

            Label label = new Label(content, SWT.NONE);
            label.setText(Messages.getString("ReportDialog.Evidences"));
            label.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
            evidenceDesc = new Text(content, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
            GridData descData = new GridData(SWT.FILL, SWT.FILL, true, true);
            descData.heightHint = 80;
            evidenceDesc.setLayoutData(descData);

            setControl(content);
        }

        private Text field(Composite parent, String key) {
            Label label = new Label(parent, SWT.NONE);
            label.setText(Messages.getString(key));
            Text text = new Text(parent, SWT.BORDER);
            text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            return text;
        }

        ReportInfo toReportInfo() {
            ReportInfo info = new ReportInfo();
            info.reportNumber = reportNumber.getText().trim();
            info.reportDate = reportDate.getText().trim();
            info.reportTitle = reportTitle.getText().trim();
            info.fillExaminersFromText(examiners.getText().trim());
            info.caseNumber = caseNumber.getText().trim();
            info.requestForm = requestForm.getText().trim();
            info.requestDate = requestDate.getText().trim();
            info.requester = requester.getText().trim();
            info.labCaseNumber = labCaseNumber.getText().trim();
            info.labCaseDate = labCaseDate.getText().trim();
            info.fillEvidenceFromText(evidenceDesc.getText().trim());
            return info;
        }
    }
}
