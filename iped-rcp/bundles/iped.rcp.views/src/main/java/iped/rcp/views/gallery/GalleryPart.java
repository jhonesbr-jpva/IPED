package iped.rcp.views.gallery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.eclipse.e4.core.di.annotations.Optional;
import org.eclipse.e4.ui.di.UIEventTopic;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.workbench.modeling.ESelectionService;
import org.eclipse.nebula.widgets.gallery.DefaultGalleryItemRenderer;
import org.eclipse.nebula.widgets.gallery.Gallery;
import org.eclipse.nebula.widgets.gallery.GalleryItem;
import org.eclipse.nebula.widgets.gallery.NoGroupRenderer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;

import iped.data.IItemId;
import iped.rcp.api.ItemId;
import iped.rcp.api.SelectionContext;
import iped.rcp.api.UiEventTopics;
import iped.rcp.core.search.ResultSet;
import iped.rcp.core.search.SearchService;
import iped.rcp.core.session.CaseSession;
import iped.rcp.core.session.ICaseSessionManager;
import iped.rcp.views.SearchBarPart;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;

/**
 * Gallery part (task T026, FR-008, research R12, SC-004): Nebula Gallery in
 * virtual mode over the ACTIVE result set (same backing as the results
 * table — data-model "ResultSetModel"), with the legacy {@code GalleryModel}
 * thumbnail pipeline running on a worker pool ({@link GalleryThumbProvider})
 * and an LRU image cache. Selection is published through the e4 selection
 * service (synchronized with viewers/panels).
 */
public class GalleryPart {

    /** SWTBot widget id (contract of FiltersGalleryTest - T024). */
    public static final String GALLERY_WIDGET_ID = "iped.rcp.views.gallery";

    /** Same bound as the legacy GalleryModel image cache. */
    private static final int IMAGE_CACHE_SIZE = 1000;

    @Inject
    private SearchService searchService;

    @Inject
    private ICaseSessionManager sessionManager;

    @Inject
    private ESelectionService selectionService;

    @Inject
    private UISynchronize uiSync;

    @Inject
    private MPart part;

    private Gallery gallery;
    private GalleryThumbProvider thumbProvider;
    private ExecutorService executor;

    private volatile long renderedGeneration = -1;
    /** Bumps on every reset so stale decode completions are dropped. */
    private volatile long galleryEpoch;
    private final Set<IItemId> pendingDecodes = ConcurrentHashMap.newKeySet();

    /** LRU thumbnail cache; eviction disposes the SWT image (UI thread only). */
    private final LinkedHashMap<IItemId, Image> imageCache = new LinkedHashMap<>(128, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<IItemId, Image> eldest) {
            if (size() > IMAGE_CACHE_SIZE) {
                disposeCachedImage(eldest.getKey(), eldest.getValue());
                return true;
            }
            return false;
        }
    };
    /** Which item currently displays each cached image (for safe disposal). */
    private final Map<IItemId, GalleryItem> imageUsers = new HashMap<>();

    @PostConstruct
    public void createComposite(Composite parent) {
        parent.setLayout(new FillLayout());

        gallery = new Gallery(parent, SWT.VIRTUAL | SWT.V_SCROLL | SWT.MULTI | SWT.BORDER);
        gallery.setData(SearchBarPart.SWTBOT_KEY, GALLERY_WIDGET_ID);

        NoGroupRenderer groupRenderer = new NoGroupRenderer();
        int thumbSize = currentThumbSize();
        groupRenderer.setItemSize(thumbSize, thumbSize + 20);
        groupRenderer.setMinMargin(3);
        groupRenderer.setAutoMargin(true);
        gallery.setGroupRenderer(groupRenderer);

        DefaultGalleryItemRenderer itemRenderer = new DefaultGalleryItemRenderer();
        itemRenderer.setShowLabels(true);
        gallery.setItemRenderer(itemRenderer);

        gallery.addListener(SWT.SetData, this::fillItem);
        gallery.addListener(SWT.Selection, this::onSelection);

        gallery.setItemCount(1);

        ResultSet current = searchService.getCurrent();
        if (current != null) {
            onResultsChanged(current.generation());
        }
    }

    private int currentThumbSize() {
        GalleryThumbProvider provider = provider();
        return provider != null ? provider.getThumbSize() : 160;
    }

    private GalleryThumbProvider provider() {
        if (thumbProvider == null) {
            CaseSession session = sessionManager.getSession();
            if (session != null) {
                thumbProvider = new GalleryThumbProvider(session.getSource());
                executor = Executors.newFixedThreadPool(Math.max(1, thumbProvider.getGalleryThreads()));
            }
        }
        return thumbProvider;
    }

    private void fillItem(Event event) {
        GalleryItem item = (GalleryItem) event.item;
        ResultSet current = searchService.getCurrent();
        int size = current != null ? current.size() : 0;
        if (item.getParentItem() == null) {
            // the single virtual group backing the flat gallery
            item.setItemCount(size);
            item.setExpanded(true);
            return;
        }
        int index = event.index;
        if (current == null || index >= size) {
            return;
        }
        IItemId itemId = current.result().getItem(index);
        item.setData(itemId);

        Image cached = imageCache.get(itemId);
        if (cached != null && !cached.isDisposed()) {
            item.setImage(cached);
            imageUsers.put(itemId, item);
            return;
        }
        scheduleDecode(itemId, item);
    }

    private void scheduleDecode(IItemId itemId, GalleryItem item) {
        GalleryThumbProvider provider = provider();
        if (provider == null || executor == null || !pendingDecodes.add(itemId)) {
            return;
        }
        final long epoch = galleryEpoch;
        executor.execute(() -> {
            try {
                if (epoch != galleryEpoch) {
                    return;
                }
                GalleryThumbProvider.Thumb thumb = provider.decode(itemId);
                if (epoch != galleryEpoch) {
                    return;
                }
                uiSync.asyncExec(() -> applyThumb(epoch, itemId, item, thumb));
            } finally {
                pendingDecodes.remove(itemId);
            }
        });
    }

    /** UI thread: create/cache the SWT image and update the tile. */
    private void applyThumb(long epoch, IItemId itemId, GalleryItem item, GalleryThumbProvider.Thumb thumb) {
        if (epoch != galleryEpoch || gallery.isDisposed() || item.isDisposed()) {
            return;
        }
        item.setText(thumb.name());
        if (thumb.image() != null && !imageCache.containsKey(itemId)) {
            Image image = new Image(gallery.getDisplay(), thumb.image());
            imageCache.put(itemId, image);
            imageUsers.put(itemId, item);
            item.setImage(image);
        }
        gallery.redraw();
    }

    private void disposeCachedImage(IItemId itemId, Image image) {
        try {
            GalleryItem user = imageUsers.remove(itemId);
            if (gallery != null && !gallery.isDisposed() && user != null && !user.isDisposed()
                    && user.getImage() == image) {
                user.setImage(null);
            }
        } catch (org.eclipse.swt.SWTException e) {
            // item disposed concurrently with the eviction: ignore
        }
        if (!image.isDisposed()) {
            image.dispose();
        }
    }

    private void onSelection(Event event) {
        GalleryItem[] selection = gallery.getSelection();
        List<ItemId> selected = new ArrayList<>(selection.length);
        for (GalleryItem item : selection) {
            if (item.getData() instanceof IItemId itemId) {
                selected.add(new ItemId(itemId.getSourceId(), itemId.getId()));
            }
        }
        ItemId active = selected.isEmpty() ? null : selected.get(0);
        selectionService.setSelection(new SelectionContext(active, selected, part.getElementId()));
    }

    /** New active result (search/filter/sort): rebuild the virtual gallery. */
    @Inject
    @Optional
    public void onResultsChanged(@UIEventTopic(UiEventTopics.RESULTS_CHANGED) Long generation) {
        if (gallery == null || gallery.isDisposed() || generation == null) {
            return;
        }
        ResultSet current = searchService.getCurrent();
        if (current == null || current.generation() == renderedGeneration) {
            return;
        }
        renderedGeneration = current.generation();
        uiSync.asyncExec(() -> {
            if (gallery.isDisposed()) {
                return;
            }
            galleryEpoch++;
            disposeAllImages();
            // reset the virtual group: clearAll invalidates cached tiles and
            // the next paint re-asks the group item count
            gallery.setItemCount(0);
            gallery.setItemCount(1);
            gallery.clearAll();
            gallery.redraw();
        });
    }

    @Inject
    @Optional
    public void onCaseClosed(@UIEventTopic(UiEventTopics.CASE_CLOSED) Object payload) {
        uiSync.asyncExec(() -> {
            if (gallery != null && !gallery.isDisposed()) {
                galleryEpoch++;
                disposeAllImages();
                gallery.setItemCount(0);
            }
            shutdownDecoder();
        });
    }

    private void disposeAllImages() {
        // when the gallery itself is gone its items must not be touched
        // (item.isDisposed() may lag the parent disposal)
        boolean galleryAlive = gallery != null && !gallery.isDisposed();
        for (Map.Entry<IItemId, Image> entry : imageCache.entrySet()) {
            if (galleryAlive) {
                try {
                    GalleryItem user = imageUsers.get(entry.getKey());
                    if (user != null && !user.isDisposed() && user.getImage() == entry.getValue()) {
                        user.setImage(null);
                    }
                } catch (org.eclipse.swt.SWTException e) {
                    // item disposed concurrently with the part: ignore
                }
            }
            if (!entry.getValue().isDisposed()) {
                entry.getValue().dispose();
            }
        }
        imageCache.clear();
        imageUsers.clear();
    }

    private void shutdownDecoder() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        thumbProvider = null;
        pendingDecodes.clear();
    }

    @PreDestroy
    public void dispose() {
        galleryEpoch++;
        shutdownDecoder();
        disposeAllImages();
    }
}
