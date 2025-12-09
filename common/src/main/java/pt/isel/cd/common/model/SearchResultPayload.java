package pt.isel.cd.common.model;

import java.util.Map;
import java.util.Objects;

/**
 * Payload for SEARCH_RESULT responses.
 * Contains a map of filename -> email content (as per Anexo 2 specification)
 */
public class SearchResultPayload {
    private Map<String, String> results;  // Map<filename, emailContent>

    public SearchResultPayload() {
    }

    public SearchResultPayload(Map<String, String> results) {
        this.results = results;
    }

    public Map<String, String> getResults() {
        return results;
    }

    public void setResults(Map<String, String> results) {
        this.results = results;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SearchResultPayload that = (SearchResultPayload) o;
        return Objects.equals(results, that.results);
    }

    @Override
    public int hashCode() {
        return Objects.hash(results);
    }

    @Override
    public String toString() {
        return "SearchResultPayload{" +
                "results=" + (results != null ? results.size() + " files" : "null") +
                '}';
    }
}
