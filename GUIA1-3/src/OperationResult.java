/**
 * Operation result wrapper
 */
public class OperationResult {
    private boolean success;
    private String message;
    private String content;

    public OperationResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public OperationResult(boolean success, String message, String content) {
        this.success = success;
        this.message = message;
        this.content = content;
    }

    // Getters and Setters
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}