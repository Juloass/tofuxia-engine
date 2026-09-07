package fr.tofuxia.renderer;

/** Explicit ownership check for swapchain, frame, queue, and presentation operations. */
final class RenderThreadGuard {
    private final Thread owner;

    RenderThreadGuard() {
        owner = Thread.currentThread();
    }

    void check(String operation) {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException(operation + " must run on render thread '" + owner.getName()
                    + "', not '" + Thread.currentThread().getName() + "'");
        }
    }

    Thread owner() {
        return owner;
    }
}
