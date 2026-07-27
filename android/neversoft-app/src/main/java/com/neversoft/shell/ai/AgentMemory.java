package com.neversoft.shell.ai;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Lightweight on-device memory inspired by CClaw's SQLite memory backend. */
public final class AgentMemory extends SQLiteOpenHelper {
    private static final String DB_NAME = "neversoft-memory.db";
    private static final int DB_VERSION = 1;

    public AgentMemory(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_memories_category ON memories(category)");
        db.execSQL("CREATE INDEX idx_memories_created_at ON memories(created_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 only for now.
    }

    public synchronized void store(String category, String content) {
        if (content == null || content.trim().isEmpty()) return;
        android.content.ContentValues values = new android.content.ContentValues();
        values.put("category", category == null || category.isEmpty() ? "custom" : category);
        values.put("content", content);
        values.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insert("memories", null, values);
    }

    public synchronized List<String> search(String query, int limit) {
        List<String> out = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return out;
        int safeLimit = Math.max(1, Math.min(20, limit));
        String like = "%" + query.trim().replace("%", "\\%").replace("_", "\\_") + "%";
        try (Cursor cursor = getReadableDatabase().rawQuery(
            "SELECT category, content FROM memories WHERE content LIKE ? ESCAPE '\\' ORDER BY created_at DESC LIMIT " + safeLimit,
            new String[] { like })) {
            while (cursor.moveToNext()) {
                out.add("[" + cursor.getString(0) + "] " + cursor.getString(1));
            }
        }
        return out;
    }

    public synchronized List<String> recent(int limit) {
        List<String> out = new ArrayList<>();
        int safeLimit = Math.max(1, Math.min(20, limit));
        try (Cursor cursor = getReadableDatabase().rawQuery(
            "SELECT category, content FROM memories ORDER BY created_at DESC LIMIT " + safeLimit, null)) {
            while (cursor.moveToNext()) {
                out.add("[" + cursor.getString(0) + "] " + cursor.getString(1));
            }
        }
        return out;
    }

    public synchronized void clearConversation() {
        getWritableDatabase().delete("memories", "category=?", new String[] { "conversation" });
    }
}
