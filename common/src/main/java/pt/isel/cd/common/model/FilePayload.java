package pt.isel.cd.common.model;

import java.util.Objects;

/**
 * Payload for GET_FILE requests.
 */
public class FilePayload {
    private String filename;

    public FilePayload() {
    }

    public FilePayload(String filename) {
        this.filename = filename;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FilePayload that = (FilePayload) o;
        return Objects.equals(filename, that.filename);
    }

    @Override
    public int hashCode() {
        return Objects.hash(filename);
    }

    @Override
    public String toString() {
        return "FilePayload{" +
                "filename='" + filename + '\'' +
                '}';
    }
}
