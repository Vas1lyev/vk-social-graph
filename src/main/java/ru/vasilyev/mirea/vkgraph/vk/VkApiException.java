package ru.vasilyev.mirea.vkgraph.vk;

public final class VkApiException extends RuntimeException {
    private final int code;

    public VkApiException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
