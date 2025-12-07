package pt.isel.cd.common.model;

import java.util.List;
import java.util.Objects;

/**
 * Payload for SEARCH requests.
 */
public class SearchPayload {
    private List<String> substrings;

    public SearchPayload() {
    }

    public SearchPayload(List<String> substrings) {
        this.substrings = substrings;
    }

    public List<String> getSubstrings() {
        return substrings;
    }

    public void setSubstrings(List<String> substrings) {
        this.substrings = substrings;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SearchPayload that = (SearchPayload) o;
        return Objects.equals(substrings, that.substrings);
    }

    @Override
    public int hashCode() {
        return Objects.hash(substrings);
    }

    @Override
    public String toString() {
        return "SearchPayload{" +
                "substrings=" + substrings +
                '}';
    }
}
