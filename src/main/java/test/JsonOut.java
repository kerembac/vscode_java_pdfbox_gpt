package test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class JsonOut {
    static void writeParasJson(File outFile, List<ParaOut> paras) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8)) {
            w.write("{\"paragraphs\":[\n");
            for (int i = 0; i < paras.size(); i++) {
                ParaOut p = paras.get(i);
                w.write("  {");
                w.write("\"index\":" + p.index + ",");
                w.write("\"page\":" + p.page + ",");
                w.write("\"kind\":\"" + p.kind + "\",");
                w.write("\"style\":\"" + esc(p.style) + "\",");
                w.write("\"minX\":" + trimFloat(p.minX) + ",");
                w.write("\"fontSize\":" + trimFloat(p.fontSize) + ",");
                w.write("\"text\":\"" + esc(p.text) + "\"");
                w.write("}");
                if (i < paras.size() - 1) w.write(",");
                w.write("\n");
            }
            w.write("]}\n");
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "\\n");
    }

    private static String trimFloat(float f) {
        // keep stable compact floats: 170 -> "170", 170.5 -> "170.5"
        if (Math.abs(f - Math.round(f)) < 0.0001) return Integer.toString(Math.round(f));
        return Float.toString(f);
    }
    public static List<ParaOut> toParaOut(List<String> paras) {
    List<ParaOut> out = new ArrayList<>();
    for (int i = 0; i < paras.size(); i++) {
        String raw = paras.get(i);
        String[] parts = raw.split("\n", 2);
        String label = parts.length > 0 ? parts[0].trim() : "ACTION";
        String body  = parts.length > 1 ? parts[1] : "";

        Kind kind = parseKind(label);

        // style = the full label (e.g. "DIALOGUE_x170_f12" or "SCENE_S8_x80_f12")
        String style = label;

        float minX = parseBucketValue(label, "_x");   // reads 170 from "_x170"
        float font = parseBucketValue(label, "_f");   // reads 12 from "_f12"

        // page is unknown at this stage (because List<String> lost it); set 0 for now.
        out.add(new ParaOut(i, 0, kind, style, minX, font, body));
    }
    return out;
}

private static Kind parseKind(String label) {
    // label examples: "ACTION_x110_f12", "SCENE_S8_x80_f12", "CHARACTER_x240_f12"
    String up = label.toUpperCase(Locale.ROOT);
    if (up.startsWith("SCENE")) return Kind.SCENE;
    if (up.startsWith("CHARACTER")) return Kind.CHARACTER;
    if (up.startsWith("DIALOGUE")) return Kind.DIALOGUE;
    if (up.startsWith("PAREN")) return Kind.PAREN;
    return Kind.ACTION;
}

private static float parseBucketValue(String label, String marker) {
    int idx = label.indexOf(marker);
    if (idx < 0) return 0f;
    idx += marker.length();
    int end = idx;
    while (end < label.length() && Character.isDigit(label.charAt(end))) end++;
    if (end == idx) return 0f;
    try { return Float.parseFloat(label.substring(idx, end)); }
    catch (Exception ignored) { return 0f; }
}

}
