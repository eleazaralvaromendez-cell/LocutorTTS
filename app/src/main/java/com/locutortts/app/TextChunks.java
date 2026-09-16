package com.locutortts.app;

import java.util.ArrayList;
import java.util.List;

public final class TextChunks {
    private TextChunks() {}

    public static List<String> split(String text, int maxLen) {
        List<String> out = new ArrayList<>();
        String left = text.trim();
        while (left.length() > maxLen) {
            int cut = cut(left, maxLen);
            String part = left.substring(0, cut).trim();
            if (!part.isEmpty()) out.add(part);
            left = left.substring(cut).trim();
        }
        if (!left.isEmpty()) out.add(left);
        return out;
    }

    private static int cut(String s, int maxLen) {
        int min = Math.max(1, maxLen - 700);
        for (int i = maxLen; i >= min; i--) {
            char c = s.charAt(i - 1);
            if (c == '.' || c == '!' || c == '?' || c == '\n') return i;
        }
        for (int i = maxLen; i >= min; i--) if (Character.isWhitespace(s.charAt(i - 1))) return i;
        return maxLen;
    }
}
