package iped.semantic.model;

import java.nio.file.Path;

import iped.semantic.SemanticPaths;
import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.config.SemanticSearchConfig.Source;

/**
 * The {@link EmbedderProvider} of an installation: the local model folder or the remote destination
 * configured per modality. It opens exactly what the configuration names, never another source
 * (FR-044).
 */
public final class EmbedderFactory implements EmbedderProvider {

    private final SemanticSearchConfig config;
    private final Path nativeDir;

    public EmbedderFactory(SemanticSearchConfig config) {
        this(config, SemanticPaths.nativeDir());
    }

    public EmbedderFactory(SemanticSearchConfig config, Path nativeDir) {
        this.config = config;
        this.nativeDir = nativeDir;
    }

    @Override
    public Embedder open(Modality modality) throws EmbeddingException {
        if (config.getModelSource(modality) == Source.REMOTE) {
            return RemoteEmbedders.open(config, modality);
        }
        Path dir = localModelDir(modality);
        if (!LocalOnnxEmbedder.isInstalled(dir)) {
            throw new EmbeddingException("local model missing: " + dir.resolve(LocalOnnxEmbedder.MANIFEST)
                    + " does not exist", null, true);
        }
        LocalOnnxEmbedder e = LocalOnnxEmbedder.open(dir, nativeDir);
        if (modality == Modality.VISUAL && !e.supportsImages()) {
            throw new EmbeddingException("the model in " + dir + " has no image encoder and cannot serve the visual "
                    + "modality", null, true);
        }
        return e;
    }

    public Path localModelDir(Modality modality) {
        return SemanticPaths.resolve(config.getLocalModel(modality));
    }

    @Override
    public Source source(Modality modality) {
        return config.getModelSource(modality);
    }

    @Override
    public String destination(Modality modality) {
        return config.getModelSource(modality) == Source.REMOTE ? RemoteEmbedders.safeDestination(
                config.getRemoteEndpoint(modality)) : null;
    }

    @Override
    public String describeLocation(Modality modality) {
        if (config.getModelSource(modality) == Source.REMOTE) {
            return "remote model at " + destination(modality);
        }
        return "local model folder " + localModelDir(modality);
    }
}
