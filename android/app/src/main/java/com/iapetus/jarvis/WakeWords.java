package com.iapetus.jarvis;

import java.util.Locale;

final class WakeWords {
    static boolean detected(String text) {
        String s=text.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
        return (" "+s+" ").contains(" hey jarvis ");
    }
}
