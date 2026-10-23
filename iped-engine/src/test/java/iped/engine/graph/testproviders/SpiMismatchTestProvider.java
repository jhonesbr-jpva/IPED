package iped.engine.graph.testproviders;

/**
 * Provider implementing another (future) version of the extension point: expected rejection
 * SPI_MISMATCH.
 */
public class SpiMismatchTestProvider extends BaseTestProvider {

    @Override
    public int getSpiVersion() {
        return CURRENT_SPI_VERSION + 1;
    }

}
