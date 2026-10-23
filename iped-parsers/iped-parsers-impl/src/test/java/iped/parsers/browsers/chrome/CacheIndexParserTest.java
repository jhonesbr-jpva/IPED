package iped.parsers.browsers.chrome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import iped.data.IItemReader;

/**
 * Unit tests for the ordering of the Chromium cache sibling files used by {@link CacheIndexParser}: a
 * live block or external file must always be matched before a deleted file with the same name.
 */
public class CacheIndexParserTest {

    private static IItemReader item(String name, boolean deleted) {
        IItemReader item = mock(IItemReader.class);
        when(item.getName()).thenReturn(name);
        when(item.isDeleted()).thenReturn(deleted);
        return item;
    }

    @Test
    public void liveFilesComeFirstKeepingRelativeOrder() {
        IItemReader deletedData4 = item("data_4", true);
        IItemReader liveData1 = item("data_1", false);
        IItemReader liveData4 = item("data_4", false);
        IItemReader deletedExternal = item("f_000001", true);
        List<IItemReader> input = Arrays.asList(deletedData4, liveData1, liveData4, deletedExternal);

        List<IItemReader> sorted = CacheIndexParser.preferLiveFiles(input);

        assertEquals(4, sorted.size());
        assertSame(liveData1, sorted.get(0));
        assertSame(liveData4, sorted.get(1));
        assertSame(deletedData4, sorted.get(2));
        assertSame(deletedExternal, sorted.get(3));
        assertSame("the search result list must not be modified", deletedData4, input.get(0));
    }

    @Test
    public void keepsOrderWhenNothingIsDeleted() {
        IItemReader a = item("data_0", false);
        IItemReader b = item("data_1", false);
        List<IItemReader> sorted = CacheIndexParser.preferLiveFiles(Arrays.asList(a, b));
        assertSame(a, sorted.get(0));
        assertSame(b, sorted.get(1));
    }
}
