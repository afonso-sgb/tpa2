package pt.isel.cd.common.model;

import java.util.Objects;

/**
 * Payload for FILE_CONTENT responses.
 */
public class FileContentPayload {
    private String filename;
    private String content;

    public FileContentPayload() {
    }

    public FileContentPayload(String filename, String content) {
        this.filename = filename;
        this.content = content;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileContentPayload that = (FileContentPayload) o;
        return Objects.equals(filename, that.filename) && Objects.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(filename, content);
    }

    @Override
    public String toString() {
        return "FileContentPayload{" +
                "filename='" + filename + '\'' +
                ", content='" + content + '\'' +
                '}';
    }
}
