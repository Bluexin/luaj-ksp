package be.bluexin.luajksp.sample;

import be.bluexin.luajksp.annotations.LuajExpose;

@LuajExpose
public class JavaPropertyLikeHolder {

    private String text;

    /**
     * @return the text
     */
    public String getText() {
        return text;
    }

    /**
     * @param text to set
     */
    public void setText(String text) {
        this.text = text;
    }
}
