package test;

import java.util.Map;

enum Kind { SCENE, ACTION, CHARACTER, PAREN, DIALOGUE }

final class ParaOut {
    final int index;
    final int page;
    final Kind kind;
    final String style;     // e.g. "DIALOGUE_x170_f12" or "SCENE_S8_x80_f12"
    final float minX;
    final float fontSize;
    final String text;

    ParaOut(int index, int page, Kind kind, String style, float minX, float fontSize, String text) {
        this.index = index;
        this.page = page;
        this.kind = kind;
        this.style = style;
        this.minX = minX;
        this.fontSize = fontSize;
        this.text = text;
    }
}
