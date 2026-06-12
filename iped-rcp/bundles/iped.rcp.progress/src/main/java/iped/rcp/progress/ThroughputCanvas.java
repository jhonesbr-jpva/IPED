package iped.rcp.progress;

import java.util.ArrayDeque;
import java.util.Deque;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;

/**
 * Lightweight throughput history sparkline (GB/h) for the progress window
 * (PG-03 — the progress-ui-events contract lists a throughput graph next to
 * the textual rates). One sample is appended per engine "update" event.
 */
class ThroughputCanvas extends Canvas {

    private static final int MAX_SAMPLES = 600;

    private final Deque<Long> samples = new ArrayDeque<>();
    private long maxSample = 1;

    ThroughputCanvas(Composite parent) {
        super(parent, SWT.DOUBLE_BUFFERED);
        addPaintListener(e -> paint(e.gc));
    }

    /** UI thread only. */
    void addSample(long gbPerHour) {
        long sample = Math.max(0, gbPerHour);
        samples.addLast(sample);
        if (samples.size() > MAX_SAMPLES) {
            samples.removeFirst();
        }
        maxSample = Math.max(1, samples.stream().mapToLong(Long::longValue).max().orElse(1));
        redraw();
    }

    private void paint(GC gc) {
        Rectangle area = getClientArea();
        gc.setBackground(getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND));
        gc.fillRectangle(area);
        gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_WIDGET_BORDER));
        gc.drawRectangle(0, 0, area.width - 1, area.height - 1);
        if (samples.isEmpty() || area.width < 4 || area.height < 4) {
            return;
        }
        Color line = getDisplay().getSystemColor(SWT.COLOR_LIST_SELECTION);
        gc.setForeground(line);
        gc.setBackground(line);
        int n = samples.size();
        double xStep = (area.width - 2) / (double) Math.max(1, MAX_SAMPLES - 1);
        int prevX = -1, prevY = -1;
        int i = 0;
        long last = 0;
        for (long sample : samples) {
            int x = 1 + (int) Math.round(i * xStep);
            int y = area.height - 2 - (int) Math.round((area.height - 4) * (sample / (double) maxSample));
            if (prevX >= 0) {
                gc.drawLine(prevX, prevY, x, y);
            }
            prevX = x;
            prevY = y;
            last = sample;
            i++;
        }
        gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_LIST_FOREGROUND));
        String label = last + " / " + maxSample + " GB/h";
        gc.drawString(label, area.width - gc.textExtent(label).x - 4, 2, true);
    }
}
