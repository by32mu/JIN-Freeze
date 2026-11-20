package com.jin.freeze.core;

import android.icu.text.Transliterator;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 中文名称转拼音首字母，用于列表搜索：「微信」→ wx，「国泰海通」→ gtht。
 * 走系统 ICU 的 Han-Latin 转写，不额外打包词库；结果由调用方按应用缓存。
 */
public final class Pinyin {
    private static Transliterator han;
    /** 单字转写缓存：整串转写拿不到音节分隔时按字兜底，避免重复转写同一个字。 */
    private static final Map<Character, String> CHAR_CACHE = new HashMap<>();

    private Pinyin() {
    }

    /** 返回小写拼音首字母串；没有可用转写时返回空串。 */
    public static String initials(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String latin = transliterate(text);
        if (latin == null || latin.isEmpty()) {
            return "";
        }
        String out = firstLetters(stripTones(latin));
        // 整串结果的音节分隔不可靠时（多字却只得到一个字母），逐字兜底
        if (hanCount(text) >= 2 && out.length() < 2) {
            out = perChar(text);
        }
        return out;
    }

    private static String transliterate(String text) {
        try {
            if (han == null) {
                han = Transliterator.getInstance("Han-Latin");
            }
            synchronized (Pinyin.class) {
                return han.transliterate(text);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static String stripTones(String latin) {
        return Normalizer.normalize(latin, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
    }

    /** 每个音节/单词取首字母：guo tai hai tong → gtht。 */
    private static String firstLetters(String plain) {
        StringBuilder sb = new StringBuilder();
        boolean start = true;
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if (c >= 'a' && c <= 'z') {
                if (start) {
                    sb.append(c);
                    start = false;
                }
            } else {
                start = true;
            }
        }
        return sb.toString();
    }

    /** 逐字转写兜底：汉字取单字拼音首字母，连续英文数字只取首字符。 */
    private static String perChar(String text) {
        StringBuilder sb = new StringBuilder();
        boolean inAscii = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isHan(c)) {
                inAscii = false;
                sb.append(hanInitial(c));
            } else if (isAsciiLetterOrDigit(c)) {
                if (!inAscii) {
                    sb.append(Character.toLowerCase(c));
                    inAscii = true;
                }
            } else {
                inAscii = false;
            }
        }
        return sb.toString();
    }

    private static char hanInitial(char c) {
        String cached = CHAR_CACHE.get(c);
        if (cached == null) {
            String latin = transliterate(String.valueOf(c));
            String letters = latin == null ? "" : firstLetters(stripTones(latin));
            cached = letters.isEmpty() ? "" : letters.substring(0, 1);
            synchronized (CHAR_CACHE) {
                CHAR_CACHE.put(c, cached);
            }
        }
        return cached.isEmpty() ? 0 : cached.charAt(0);
    }

    private static int hanCount(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isHan(text.charAt(i))) {
                n++;
            }
        }
        return n;
    }

    private static boolean isHan(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    private static boolean isAsciiLetterOrDigit(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
