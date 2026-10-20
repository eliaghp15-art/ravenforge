package ravenforge.fml.common.eventhandler;

/** Minimal Forge-compatible event base. */
public class Event {
    public enum Result { DENY, DEFAULT, ALLOW }

    private boolean canceled;
    private Result result = Result.DEFAULT;

    public boolean isCancelable() {
        return true;
    }

    public boolean isCanceled() {
        return this.canceled;
    }

    public void setCanceled(boolean cancel) {
        if (!isCancelable()) {
            throw new UnsupportedOperationException("Attempted to cancel a non-cancelable event: " + getClass().getName());
        }
        this.canceled = cancel;
    }

    public boolean hasResult() {
        return false;
    }

    public Result getResult() {
        return this.result;
    }

    public void setResult(Result result) {
        this.result = result;
    }
}
