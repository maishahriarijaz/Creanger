package com.creanger.app.network.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * Generic paginated response from backend.
 */
public class PaginatedResponse<T> {

    @NonNull
    public final List<T> items;
    @Nullable
    public final String nextCursor;      // Cursor for next page
    @Nullable
    public final String prevCursor;      // Cursor for previous page
    public final boolean hasMore;
    public final int totalCount;         // Total count (if provided)
    public final int pageSize;

    public PaginatedResponse(@NonNull List<T> items, @Nullable String nextCursor, @Nullable String prevCursor, boolean hasMore, int totalCount, int pageSize) {
        this.items = items != null ? Collections.unmodifiableList(items) : Collections.emptyList();
        this.nextCursor = nextCursor;
        this.prevCursor = prevCursor;
        this.hasMore = hasMore;
        this.totalCount = totalCount;
        this.pageSize = pageSize;
    }

    public static <T> PaginatedResponse<T> empty() {
        return new PaginatedResponse<>(Collections.emptyList(), null, null, false, 0, 0);
    }

    public static <T> PaginatedResponse<T> singlePage(@NonNull List<T> items) {
        return new PaginatedResponse<>(items, null, null, false, items.size(), items.size());
    }

    @Override
    @NonNull
    public String toString() {
        return "PaginatedResponse{items=" + items.size() + ", hasMore=" + hasMore + ", nextCursor=" + nextCursor + '}';
    }
}