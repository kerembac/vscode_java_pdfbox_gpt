package test;


import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class PosDump {

    // One "glyph" with position
static class G {
    final int page;
    final float x;
    final float y;
    final float w;
    final float fontSize;
    final String ch;
    final int yKey;

    G(int page, float x, float y, float w, float fontSize, String ch) {
        this.page = page;
        this.x = x;
        this.y = y;
        this.w = w;
        this.fontSize = fontSize;
        this.ch = ch;
        this.yKey = Math.round(y * 2); // 0.5pt bucket (change 2->1 for 1pt)
    }
}


    // One reconstructed line
    static class Line {
        final int page;
        final int yKey;
        final float y; // keep original for gap calcs if you want
        float minX = Float.MAX_VALUE;
        float maxX = 0;
        float avgFont = 0;
        int fontCount = 0;
        final StringBuilder text = new StringBuilder();

        Line(int page, int yKey, float y) {
            this.page = page;
            this.yKey = yKey;
            this.y = y;
        }

        void addGlyph(G g) {
            minX = Math.min(minX, g.x);
            maxX = Math.max(maxX, g.x + g.w);
            avgFont += g.fontSize;
            fontCount++;
            text.append(g.ch);
        }

        float avgFontSize() {
            return fontCount == 0 ? 0 : (avgFont / fontCount);
        }
    }

    // Custom stripper that captures TextPositions
    static class CaptureStripper extends PDFTextStripper {
        final List<G> glyphs = new ArrayList<>();

        CaptureStripper() throws IOException {
            super();
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String string, List<TextPosition> textPositions) throws IOException {
            int page = getCurrentPageNo();
            for (TextPosition tp : textPositions) {
                String u = tp.getUnicode();
                if (u == null || u.isEmpty()) continue;

                // Ignore pure control chars
                if (u.equals("\r") || u.equals("\n")) continue;

                glyphs.add(new G(
                        page,
                        tp.getXDirAdj(),
                        tp.getYDirAdj(),
                        tp.getWidthDirAdj(),
                        tp.getFontSizeInPt(),
                        u
                ));
            }
        }
    }

    public static void main(String[] args) throws Exception {
        String pdfPath   = args.length > 0 ? args[0] : "input.pdf";
        int startPage    = args.length > 1 ? Integer.parseInt(args[1]) : 1;   // 1-based
        int maxPages     = args.length > 2 ? Integer.parseInt(args[2]) : 5;
        int maxParas     = args.length > 3 ? Integer.parseInt(args[3]) : 200;

        try (PDDocument doc = Loader.loadPDF(new File(pdfPath))) {
            int total = doc.getNumberOfPages();
            int start = Math.max(1, Math.min(startPage, total));
            int end   = Math.min(total, start + maxPages - 1);

            CaptureStripper stripper = new CaptureStripper();
            stripper.setStartPage(start);
            stripper.setEndPage(end);

            stripper.getText(doc);

            List<Line> lines = buildLines(stripper.glyphs);
            List<String> paras = groupLinesIntoParagraphs(lines, maxParas);
File out = new File("out.json");
List<ParaOut> outParas = JsonOut.toParaOut(paras);
JsonOut.writeParasJson(new File("out.json"), outParas);
System.out.println("Wrote: " + out.getAbsolutePath());

for (int i = 0; i < paras.size(); i++) {
    String[] parts = paras.get(i).split("\n", 2);
    String kind = parts[0];
    String body = parts.length > 1 ? parts[1] : "";
    System.out.printf("PARA_%d [%s]%n%s%n%n", i, kind, body);
}

        }

}
static String kindLabel(Kind k, SceneScore sc) {
    if (k != Kind.SCENE) return k.name();

    // purely for logging / grouping visibility
    // SCENE_1 = strong; SCENE_2 = weaker
    int tier = (sc != null && sc.score >= 7) ? 1 : 2;
    return "SCENE_" + tier;
}

private static List<String> groupLinesIntoParagraphs(List<Line> lines, int maxParas) {

    // sort reading order: page asc, y asc (top -> bottom in your current coordinate system)
    lines.sort(Comparator
            .comparingInt((Line l) -> l.page)
            .thenComparing((Line l) -> l.y)
            .thenComparing(l -> l.minX)); // helps stabilize order within same Y

    // estimate baseline line gap using median gap (next - prev)
    List<Float> gaps = new ArrayList<>();
    for (int i = 1; i < lines.size(); i++) {
        Line a = lines.get(i - 1);
        Line b = lines.get(i);
        if (a.page != b.page) continue;
        gaps.add(b.y - a.y);
    }
    float baseline = median(gaps, 13f);
    float paraBreakGap = baseline * 1.6f;

    List<String> paras = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    Line prev = null;

    // --- kind-aware state ---
    Kind curKind = null;
    boolean dialogueMode = false;

    // track max scene score inside current paragraph (only used when curKind==SCENE)
    int curSceneScoreMax = 0;

    // indent baseline for current paragraph
    float curParaMinX = -1f;

    float curMinXMin = Float.MAX_VALUE;
    float curFontAvg = 0f;
    int curFontCount = 0;

    // NEW: remember a baseline "action" indent we've seen, to allow safe dialogue→action switch
    float seenActionMinX = Float.NaN;

    // helper: detect page numbers / footer-ish single tokens (extra grouping only)
    // examples: "7.", "11.", "8.", "5."
    final java.util.regex.Pattern PAGE_NO = java.util.regex.Pattern.compile("^\\d{1,3}\\s*[\\.)]$");

    // helper: detect transition-ish parentheticals (extra grouping only)
    // examples: "(KESME)", "(CUT TO)", "(FADE OUT)"
    final java.util.regex.Pattern TRANS_PAREN = java.util.regex.Pattern.compile("^\\(\\s*[A-ZÇĞİÖŞÜ ]{3,}\\s*\\)$");

    // helper: detect "corrupt-ish" line (extra grouping only)
    // long runs without spaces, or many underscores, etc.
    // (we do NOT change kind; we only change style label)
    java.util.function.Predicate<String> looksCorrupt = (String txt) -> {
        if (txt == null) return false;
        String s = txt.trim();
        if (s.isEmpty()) return false;

        long underscores = s.chars().filter(ch -> ch == '_').count();
        if (underscores >= 6) return true;

        // very long token without spaces (after cleanup) tends to be an artifact
        int maxRun = 0, run = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) run++;
            else { maxRun = Math.max(maxRun, run); run = 0; }
        }
        maxRun = Math.max(maxRun, run);
        return maxRun >= 35;
    };

    for (Line ln : lines) {
        String t = normalize(ln.text.toString());
        if (t.isBlank()) continue;
        if (t.equalsIgnoreCase("Created using Celtx")) continue;

        // IMPORTANT: fix underscore + spaced-letter artifacts early
        t = cleanupWeirdInterleaving(t);
        if (t.isBlank()) continue;

        // --- scene scorer (non-destructive) ---
        SceneScore sc = sceneScore(t);

        boolean sceneIndentLikely = ln.minX <= 90f; // tune later
        boolean isStrongScene = sc.score >= 6 && sceneIndentLikely;

        // classify (KEEP ORDER: SCENE first)
        Kind lineKind;
        if (isStrongScene) lineKind = Kind.SCENE;
        else if (isCharacterCue(t)) lineKind = Kind.CHARACTER;
        else if (isParenthetical(t)) lineKind = Kind.PAREN;
        else if (dialogueMode) lineKind = Kind.DIALOGUE;
        else lineKind = Kind.ACTION;

        // --- SAFE dialogue→action escape hatch (does NOT affect character/scene detection) ---
        // If we're in dialogue mode and we see a line that is "dialogue by mode"
        // but its indent is close to the action baseline, treat it as ACTION and end dialogue mode.
        // This helps: dialogue-chain accidentally swallowing action blocks.
        if (dialogueMode && lineKind == Kind.DIALOGUE && !Float.isNaN(seenActionMinX)) {
            // If indent is near action indent (or smaller), it's probably action.
            if (ln.minX <= (seenActionMinX + 15f)) { // tune later
                lineKind = Kind.ACTION;
                dialogueMode = false;
            }
        }

        // Temp debug:
        if (t.contains("HIRT")) {
            System.out.println("DBG HIRT t=[" + t + "] isScene=" + isSceneHeading(t)
                    + " isChar=" + isCharacterCue(t));
        }

        boolean newPara;

        if (prev == null) newPara = true;
        else if (ln.page != prev.page) newPara = true;
        else {
            float gap = ln.y - prev.y;
            newPara = gap >= paraBreakGap;
        }

        // force boundaries for these
        if (lineKind == Kind.SCENE || lineKind == Kind.CHARACTER || lineKind == Kind.PAREN) {
            newPara = true;
        }

        // kind change breaks paragraph
        if (!newPara && curKind != null && curKind != lineKind) {
            newPara = true;
        }

        // indent jump splits paragraphs for ACTION/DIALOGUE (your existing core test)
        if (!newPara && curKind != null && curKind == lineKind) {
            if (lineKind == Kind.ACTION || lineKind == Kind.DIALOGUE) {
                if (curParaMinX >= 0 && Math.abs(ln.minX - curParaMinX) >= 35f) { // tune later
                    newPara = true;
                }
            }
        }

        if (newPara) {
            if (cur.length() > 0) {
                Kind k = (curKind == null ? Kind.ACTION : curKind);
                float paraFont = (curFontCount > 0) ? (curFontAvg / curFontCount) : 0f;

                String bodyTrim = cur.toString().trim();
                String header = paraHeader(k, curSceneScoreMax, curMinXMin, paraFont);

                // --- EXTRA STYLE GROUPING ONLY (no behavior change) ---
                // 1) Page numbers: keep kind as-is, but force distinct style label.
                if (PAGE_NO.matcher(bodyTrim).matches()) {
                    // Example: ACTION_x110_f12 becomes ACTION_PG_x110_f12
                    header = k.name() + "_PG_" + "x" + bucket10(curMinXMin) + "_f" + bucketFont(paraFont);
                }

                // 2) Transition-like parenthetical: group separately
                if (k == Kind.PAREN && TRANS_PAREN.matcher(bodyTrim).matches()) {
                    // Example: PAREN_TR_x240_f12
                    header = "PAREN_TR_" + "x" + bucket10(curMinXMin) + "_f" + bucketFont(paraFont);
                }

                // 3) Corrupt-ish paragraph: group separately
                if (looksCorrupt.test(bodyTrim)) {
                    // Example: ACTION_CORR_x110_f12
                    header = k.name() + "_CORR_" + "x" + bucket10(curMinXMin) + "_f" + bucketFont(paraFont);
                }

                paras.add(header + "\n" + bodyTrim);
                if (paras.size() >= maxParas) break;
                cur.setLength(0);
            }

            curKind = lineKind;
            curMinXMin = ln.minX;
            curFontAvg = ln.avgFontSize();
            curFontCount = 1;

            // reset scene max for the new paragraph
            curSceneScoreMax = (lineKind == Kind.SCENE) ? sc.score : 0;

            curParaMinX = ln.minX;

        } else {
            cur.append("\n");
        }

        cur.append(t);
        prev = ln;

        // update current para stats
        curMinXMin = Math.min(curMinXMin, ln.minX);
        curFontAvg += ln.avgFontSize();
        curFontCount++;

        // update max score while building a SCENE paragraph
        if (curKind == Kind.SCENE) {
            curSceneScoreMax = Math.max(curSceneScoreMax, sc.score);
        }

        // record action baseline (for later dialogue→action switching)
        if (lineKind == Kind.ACTION) {
            if (Float.isNaN(seenActionMinX)) seenActionMinX = ln.minX;
            else seenActionMinX = Math.min(seenActionMinX, ln.minX);
        }

        // update mode
        if (lineKind == Kind.CHARACTER) dialogueMode = true;
        else if (lineKind == Kind.SCENE) dialogueMode = false;
    }

    if (paras.size() < maxParas && cur.length() > 0) {
        Kind k = (curKind == null ? Kind.ACTION : curKind);
        float paraFont = (curFontCount > 0) ? (curFontAvg / curFontCount) : 0f;

        String bodyTrim = cur.toString().trim();
        String header = paraHeader(k, curSceneScoreMax, curMinXMin, paraFont);

        // same extra grouping at end
        if (PAGE_NO.matcher(bodyTrim).matches()) {
            header = k.name() + "_PG_" + "x" + bucket10(curMinXMin) + "_f" + bucketFont(paraFont);
        }
        if (k == Kind.PAREN && TRANS_PAREN.matcher(bodyTrim).matches()) {
            header = "PAREN_TR_" + "x" + bucket10(curMinXMin) + "_f" + bucketFont(paraFont);
        }
        if (looksCorrupt.test(bodyTrim)) {
            header = k.name() + "_CORR_" + "x" + bucket10(curMinXMin) + "_f" + bucketFont(paraFont);
        }

        paras.add(header + "\n" + bodyTrim);
    }

    return paras;
}


static String cleanupWeirdInterleaving(String s) {
    if (s == null || s.isEmpty()) return s;

    // A) underscore-heavy text => remove underscores between letters, replace rest with spaces
    int underscoreCount = (int) s.chars().filter(ch -> ch == '_').count();
    if (underscoreCount > 0 && underscoreCount >= Math.max(2, s.length() / 5)) {
        // remove underscores between letters
        s = s.replaceAll("(?<=\\p{L})_(?=\\p{L})", "");
        s = s.replace('_', ' ');
    }

    // B) spaced letters like "s ? r a d a" (or "S A T S U M A")
    // If most tokens are 1 char, join them back.
    String[] toks = s.trim().split("\\s+");
    if (toks.length >= 8) {
        int oneChar = 0;
        for (String t : toks) if (t.length() == 1) oneChar++;
        if (oneChar >= (int)(toks.length * 0.70)) {
            StringBuilder sb = new StringBuilder();
            for (String t : toks) sb.append(t);
            s = sb.toString();
        }
    }

    return s;
}

private static String guessType(String paragraphText) {
    // VERY rough starter: classify scene headings vs action by pattern
    String firstLine = paragraphText.split("\n", 2)[0].trim();

    // Your observed scene headings: "7KIRIK ...", "2BIR ...", etc.
    if (firstLine.matches("^\\d+\\s*\\S+.*") && firstLine.equals(firstLine.toUpperCase())) {
        return "SceneHeading?";
    }
    return "Action?";
}

private static float median(List<Float> xs, float fallback) {
    if (xs == null || xs.isEmpty()) return fallback;
    List<Float> s = new ArrayList<>(xs);
    s.sort(Float::compare);
    int mid = s.size() / 2;
    return s.size() % 2 == 0 ? (s.get(mid - 1) + s.get(mid)) / 2f : s.get(mid);
}

private static List<Line> buildLines(List<G> glyphs) {
    glyphs.sort(Comparator
            .comparingInt((G g) -> g.page)
            .thenComparingInt((G g) -> g.yKey)
            .thenComparingDouble(g -> g.x));

    List<Line> lines = new ArrayList<>();
    Line cur = null;

    for (G g : glyphs) {
        if (cur == null || cur.page != g.page || cur.yKey != g.yKey) {
            cur = new Line(g.page, g.yKey, g.y);
            lines.add(cur);
        }
        cur.addGlyph(g);
    }
    return lines;
}


private static String normalize(String s) {
    s = s.replace("\u00A0", " ");
    s = s.replaceAll("[\\t]+", " ");
    s = s.replaceAll("[ ]{2,}", " ");
    return s.replaceAll("\\s+$", ""); // only trim end
}
enum Kind { SCENE, CHARACTER, PAREN, DIALOGUE, ACTION }

static boolean isSceneHeading(String s) {
    String t = s.trim();
    // your headings like "7KIRIK ...", "3?ERMIN EV. IÇ. GECE"
    // Accept '?' because of encoding issues
    return t.matches("^\\d+\\s*\\S.*") && t.equals(t.toUpperCase());
}
static boolean isCharacterCue(String s) {
    String t = s.trim();
    if (t.length() < 2 || t.length() > 30) return false;

    // Strip optional numeric prefix like "1.HIRT", "2) HIRT", "3 - HIRT"
    String cue = t.replaceFirst("^\\s*\\d+\\s*[\\.)\\-]?\\s*", "").trim();
    if (cue.isEmpty()) return false;

    // If the remaining cue is a clean uppercase name, ACCEPT it immediately.
    // This prevents "1.HIRT" being killed by isSceneHeading().
    if (cue.matches("^[A-ZÇĞİÖŞÜ\\?\\- ]+$")) {
        long letters = cue.chars().filter(Character::isLetter).count();
        if (letters >= 2) return true;
    }

    // Other rejections (keep)
    if (t.contains("/")) return false;

    // Only now apply the scene veto for other cases
    if (isSceneHeading(t)) return false;

    return false;
}




static boolean isParenthetical(String s) {
    String t = s.trim();
    return t.startsWith("(");
}
static class SceneScore {
    final int score;
    final boolean hasSceneNumber;
    final int sepCount;
    final int blockCount;
    final boolean mostlyCaps;
    SceneScore(int score, boolean hasSceneNumber, int sepCount, int blockCount, boolean mostlyCaps) {
        this.score = score;
        this.hasSceneNumber = hasSceneNumber;
        this.sepCount = sepCount;
        this.blockCount = blockCount;
        this.mostlyCaps = mostlyCaps;
    }
}

// separators we treat as “scene separators” (tune later)
static final String SCN_SEPS = "./\\-–—|,";

static SceneScore sceneScore(String s) {
    String t = s.trim();
    if (t.isEmpty()) return new SceneScore(0, false, 0, 0, false);

    // has leading scene number (e.g., "7KHT..." or "7 KHT...")
    boolean hasNum = t.matches("^\\d{1,4}.*");

    // count separator-ish chars (., /, etc.)
    int sepCount = 0;
    for (int i = 0; i < t.length(); i++) {
        if (SCN_SEPS.indexOf(t.charAt(i)) >= 0) sepCount++;
    }

    // block count heuristic: split by separators and spaces, count non-empty “chunks”
    String tmp = t;
    for (int i = 0; i < SCN_SEPS.length(); i++) tmp = tmp.replace(SCN_SEPS.charAt(i), ' ');
    String[] chunks = tmp.trim().split("\\s+");
    int blockCount = 0;
    for (String c : chunks) if (!c.isEmpty()) blockCount++;

    // “mostly caps” (supports Turkish letters). We don’t require ALL CAPS.
    int letters = 0, upperLetters = 0;
    for (int i = 0; i < t.length(); i++) {
        char ch = t.charAt(i);
        if (Character.isLetter(ch)) {
            letters++;
            if (Character.toUpperCase(ch) == ch) upperLetters++;
        }
    }
    boolean mostlyCaps = letters >= 2 && (upperLetters / (double) letters) >= 0.90;

    // scoring (tune later)
    int score = 0;
    if (hasNum) score += 3;
    if (mostlyCaps) score += 2;
    if (sepCount >= 2) score += 2;      // "INT./EXT." etc
    else if (sepCount == 1) score += 1;

    if (blockCount >= 3) score += 1;    // “KHT MUTFAK IÇ GÜN” becomes 4 blocks

    return new SceneScore(score, hasNum, sepCount, blockCount, mostlyCaps);
}

static String sceneFlags(SceneScore sc) {
    // compact flags for your style key
    return "SCN" + (sc.hasSceneNumber ? "1" : "0")
            + "S" + Math.min(sc.sepCount, 9)
            + "B" + Math.min(sc.blockCount, 9)
            + "C" + (sc.mostlyCaps ? "1" : "0");
}

static String paraHeader(Kind k, int sceneScoreMax, float minX, float fontPt) {
    String x = "x" + bucket10(minX);
    String f = "f" + bucketFont(fontPt);

    if (k == Kind.SCENE) {
        return "SCENE_S" + sceneScoreMax + "_" + x + "_" + f;
    }
    return k.name() + "_" + x + "_" + f;
}

static int bucket10(float v) {
    // 76 -> 80, 108 -> 110 (matches your earlier debug vibe)
    return Math.round(v / 10f) * 10;
}

static int bucketFont(float pt) {
    // 11.7 -> 12, 9.6 -> 10
    return Math.round(pt);
}
static boolean isTempBypassHirtCue(String t) {
    // Matches: "1.HIRT", "2.HIRT", "1. HIRT", "12.HIRT" (case-insensitive)
    String s = t.trim().toUpperCase(Locale.ROOT);
    return s.matches("^\\d{1,3}\\s*\\.\\s*HIRT$");
}


}
