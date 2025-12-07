package pt.isel.cd.common.model;

import java.util.List;
import java.util.Objects;

/**
 * Payload for SEARCH_RESULT responses.
 */
public class SearchResultPayload {
    private List<String> filenames;

    public SearchResultPayload() {
    }

    public SearchResultPayload(List<String> filenames) {
        this.filenames = filenames;
    }

    public List<String> getFilenames() {
        return filenames;
    }

    public void setFilenames(List<String> filenames) {
        this.filenames = filenames;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SearchResultPayload that = (SearchResultPayload) o;
        return Objects.equals(filenames, that.filenames);
    }

    @Override
    public int hashCode() {
        return Objects.hash(filenames);
    }

    @Override
    public String toString() {
        return "SearchResultPayload{" +
                "filenames=" + filenames +
                '}';
    }
}
