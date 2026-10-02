package com.pis.label;
import java.util.UUID;
/** Restricted Code39 payload with Mod43 check character; not a clinical identifier standard. */
public final class LabelBarcode {
    private LabelBarcode() { }
    private static final String ALPHABET="0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. $/+%";
    public static String create(UUID token) {
        String value="S"+token.toString().replace("-","").toUpperCase(java.util.Locale.ROOT);
        return value+check(value);
    }
    private static char check(String value) { int sum=0; for(char c:value.toCharArray()) sum+=ALPHABET.indexOf(c); return ALPHABET.charAt(sum%43); }
    public static boolean valid(String value) {
        return value!=null && value.length()==34 && value.substring(0,33).matches("S[0-9A-F]{32}") && value.charAt(33)==check(value.substring(0,33));
    }
}
