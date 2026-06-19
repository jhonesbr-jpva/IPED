package iped.rcp.views.trees;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageDataProvider;
import org.eclipse.swt.widgets.Display;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.engine.data.Category;
import iped.rcp.views.gallery.SwtImages;

/**
 * Loads the legacy IPED category icons (PNG art shipped in iped-app under
 * {@code iped/app/ui/cat/}) as SWT images for the category tree decorators
 * (legacy {@code CategoryTreeCellRenderer}/{@code IconManager} parity).
 *
 * <p>
 * The PNGs are embedded in the {@code iped.rcp.libs} wrapper bundle (the engine
 * and iped-app share one OSGi class space), so they must be reached through a
 * wrapper class loader ({@link Category}); a first-party bundle class loader
 * does not see resources nested in the wrapper's {@code Bundle-ClassPath}. SWT
 * decodes PNG natively, so this path is independent of the JDK ImageIO
 * png-reader add-exports the bridged AWT viewers need.
 *
 * <p>
 * The category objects in the engine tree keep their canonical (non-localized)
 * name, which matches the icon file names one to one — only the display label is
 * localized. The root has its name replaced by the localized label upstream, so
 * it is mapped explicitly to the {@code Categories} icon. Lookups miss
 * gracefully to the {@code blank} icon, exactly like the legacy default.
 */
final class CategoryIcons {

    private static final Logger LOGGER = LoggerFactory.getLogger(CategoryIcons.class);

    private static final String CAT_PATH = "/iped/app/ui/cat/";
    private static final String ROOT_ICON = "Categories";
    private static final String FALLBACK_ICON = "blank";

    /**
     * Display size in pixels at 100% — the native PNGs are 32x32 and the legacy
     * tree renders categories at {@code IconManager.defaultCategorySize}. The
     * actual paint size is derived per monitor zoom from this base.
     */
    private static final int ICON_SIZE = 20;

    private final Display display;
    /** Cached by icon base name; misses cache a {@code null} to avoid retrying. */
    private final Map<String, Image> cache = new HashMap<>();

    CategoryIcons(Display display) {
        this.display = display;
    }

    /** The decorator for a category node, or the blank fallback. */
    Image forCategory(Category category) {
        String name = category.getParent() == null ? ROOT_ICON : category.getName();
        Image icon = load(name);
        return icon != null ? icon : load(FALLBACK_ICON);
    }

    private Image load(String baseName) {
        if (baseName == null || baseName.isBlank()) {
            return null;
        }
        if (cache.containsKey(baseName)) {
            return cache.get(baseName);
        }
        Image image = null;
        // Category is loaded by the wrapper bundle class loader, whose
        // Bundle-ClassPath includes the embedded iped-app jar with the icons.
        try (InputStream is = Category.class.getResourceAsStream(CAT_PATH + baseName + ".png")) {
            if (is != null) {
                // Decode once (32x32 native) and scale per monitor zoom to the
                // legacy display size — crisp on HiDPI, alpha preserved.
                ImageData source = new ImageData(is);
                ImageDataProvider provider = zoom -> {
                    int size = Math.max(1, Math.round(ICON_SIZE * zoom / 100f));
                    return SwtImages.scaledTo(source, size, size);
                };
                image = new Image(display, provider);
            }
        } catch (Exception e) {
            LOGGER.debug("Could not load category icon '{}'", baseName, e);
        }
        cache.put(baseName, image);
        return image;
    }

    /** Disposes every loaded image; call from the tree dispose listener. */
    void dispose() {
        for (Image image : cache.values()) {
            if (image != null && !image.isDisposed()) {
                image.dispose();
            }
        }
        cache.clear();
    }
}
