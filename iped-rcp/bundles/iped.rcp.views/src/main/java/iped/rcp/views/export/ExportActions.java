package iped.rcp.views.export;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.apache.lucene.document.Document;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IItem;
import iped.engine.data.IPEDMultiSource;
import iped.engine.util.Util;
import iped.rcp.api.ItemId;
import iped.rcp.core.i18n.Messages;
import iped.rcp.core.session.ICaseSessionManager;
import iped.rcp.views.ResultColumns;

/**
 * Item export actions (task T022, FR-015): copies the selected items to a
 * folder (legacy {@code CopyFiles} parity: true-extension names validated by
 * {@code Util.getValidFilename}, duplicate names deduplicated with
 * {@code Util.concat}, 1000-files-per-subfolder layout) and exports item
 * properties to CSV (legacy {@code CopyProperties} parity: UTF-8 with BOM,
 * semicolon separator, localized headers). Both run in Jobs off the UI
 * thread; output is deterministic for identical input (Principle IV).
 *
 * <p>
 * Test hook: {@code -Diped.rcp.export.dir} bypasses the native directory
 * dialog (native dialogs cannot be driven by SWTBot - T014).
 */
public final class ExportActions {

    /** Test hook honored instead of the native directory dialog. */
    public static final String EXPORT_DIR_PROP = "iped.rcp.export.dir";

    private static final Logger LOGGER = LoggerFactory.getLogger(ExportActions.class);

    private static final int MAX_FILES_PER_SUBDIR = 1000;

    private ExportActions() {
    }

    /** Copies the selected items (content) to a target folder. */
    public static void exportSelectedItems(Shell shell, List<ItemId> items, ICaseSessionManager sessionManager) {
        if (items.isEmpty()) {
            return;
        }
        File target = chooseDirectory(shell);
        if (target == null) {
            return;
        }
        IPEDMultiSource source = sessionManager.getSession().getSource();
        Job job = Job.create(Messages.getString("ExportItems.Exporting"), (IProgressMonitor monitor) -> {
            try {
                copyItems(source, items, target, monitor);
                return Status.OK_STATUS;
            } catch (IOException e) {
                LOGGER.error("Item export failed", e);
                return Status.error(e.getMessage(), e);
            }
        });
        job.setUser(true);
        job.schedule();
    }

    /** Exports the properties (visible columns) of the selected items to CSV. */
    public static void exportSelectedProperties(Shell shell, List<ItemId> items, List<String> fields,
            ICaseSessionManager sessionManager) {
        if (items.isEmpty()) {
            return;
        }
        File csv = chooseCsvFile(shell);
        if (csv == null) {
            return;
        }
        IPEDMultiSource source = sessionManager.getSession().getSource();
        Job job = Job.create(Messages.getString("ExportItems.Exporting"), (IProgressMonitor monitor) -> {
            try {
                writeProperties(source, items, fields, csv);
                return Status.OK_STATUS;
            } catch (IOException e) {
                LOGGER.error("Properties export failed", e);
                return Status.error(e.getMessage(), e);
            }
        });
        job.setUser(true);
        job.schedule();
    }

    private static void copyItems(IPEDMultiSource source, List<ItemId> items, File dir, IProgressMonitor monitor)
            throws IOException {
        monitor.beginTask(Messages.getString("ExportItems.Exporting"), items.size());
        File subdir = dir;
        int exported = 0;
        int subdirCount = 1;
        for (ItemId itemId : items) {
            if (monitor.isCanceled()) {
                return;
            }
            if (items.size() > MAX_FILES_PER_SUBDIR && exported % MAX_FILES_PER_SUBDIR == 0) {
                do {
                    subdir = new File(dir, Integer.toString(subdirCount++));
                } while (!subdir.mkdir());
            }
            IItem item = source.getItemByItemId(new iped.engine.data.ItemId(itemId.sourceId(), itemId.id()));
            if (item != null && !item.isDir()) {
                String dstName = Util.getValidFilename(Util.getNameWithTrueExt(item));
                File dst = new File(subdir, dstName);
                int num = 1;
                while (dst.exists()) {
                    dst = new File(subdir, Util.concat(dstName, num++));
                }
                try (InputStream in = item.getBufferedInputStream();
                        OutputStream out = new BufferedOutputStream(Files.newOutputStream(dst.toPath()))) {
                    in.transferTo(out);
                } catch (IOException e) {
                    LOGGER.warn("Error exporting item {}", item.getPath(), e);
                } finally {
                    item.dispose();
                }
            }
            exported++;
            monitor.worked(1);
        }
        monitor.done();
    }

    private static void writeProperties(IPEDMultiSource source, List<ItemId> items, List<String> fields, File csv)
            throws IOException {
        try (Writer writer = new OutputStreamWriter(new BufferedOutputStream(Files.newOutputStream(csv.toPath())),
                StandardCharsets.UTF_8)) {
            writer.write('\uFEFF'); // UTF-8 BOM, legacy CopyProperties parity
            for (String field : fields) {
                writer.write("\"" + ResultColumns.labelOf(field).replace("\"", "\"\"") + "\";");
            }
            writer.write("\r\n");
            for (ItemId itemId : items) {
                Document doc = source.getReader()
                        .document(source.getLuceneId(new iped.engine.data.ItemId(itemId.sourceId(), itemId.id())));
                for (String field : fields) {
                    String[] values = doc.getValues(field);
                    String value = values.length == 0 ? "" : String.join(" | ", values);
                    writer.write("\"" + value.replace("\"", "\"\"") + "\";");
                }
                writer.write("\r\n");
            }
        }
    }

    private static File chooseDirectory(Shell shell) {
        String preset = System.getProperty(EXPORT_DIR_PROP);
        if (preset != null && !preset.isBlank()) {
            return new File(preset);
        }
        DirectoryDialog dialog = new DirectoryDialog(shell, SWT.SAVE);
        dialog.setText(Messages.getString("ExportItems.ChooseFolder"));
        String dir = dialog.open();
        return dir != null ? new File(dir) : null;
    }

    private static File chooseCsvFile(Shell shell) {
        String preset = System.getProperty(EXPORT_DIR_PROP);
        if (preset != null && !preset.isBlank()) {
            return new File(preset, "properties.csv");
        }
        FileDialog dialog = new FileDialog(shell, SWT.SAVE);
        dialog.setFilterExtensions(new String[] { "*.csv" });
        dialog.setFileName("properties.csv");
        dialog.setOverwrite(true);
        String file = dialog.open();
        return file != null ? new File(file) : null;
    }
}
