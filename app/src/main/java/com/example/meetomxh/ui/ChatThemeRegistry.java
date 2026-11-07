package com.example.meetomxh.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import androidx.core.graphics.ColorUtils;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Stable server IDs; intentionally designed light/dark surfaces, shared by preview and chat. */
public final class ChatThemeRegistry {
    public static final class Theme {
        public final String id,name;
        private final int lightBackground,darkBackground,lightSent,darkSent,darkReceived;
        Theme(String id,String name,String lightBg,String darkBg,String sent,String darkSent,String received){
            this.id=id;this.name=name;lightBackground=Color.parseColor(lightBg);darkBackground=Color.parseColor(darkBg);
            lightSent=Color.parseColor(sent);this.darkSent=Color.parseColor(darkSent);darkReceived=Color.parseColor(received);
        }
        public Palette palette(Context context){
            boolean dark=(context.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
            return new Palette(dark?darkBackground:lightBackground,dark?darkSent:lightSent,
                    dark?darkReceived:Color.WHITE,dark?0xFFF1F5F9:0xFF17212B,dark?0xFFCBD5E1:0xFF475569,
                    dark?0xFF94A3B8:lightSent,dark?0xFF94A3B8:0xFF64748B,dark?0xFF171B24:Color.WHITE);
        }
    }
    public static final class Palette {
        public final int background,sent,received,text,secondary,accent,border,input,sentText,receivedText;
        Palette(int bg,int sent,int received,int text,int secondary,int accent,int border,int input){
            background=bg;this.sent=sent;this.received=received;this.text=text;this.secondary=secondary;
            this.accent=accent;this.border=border;this.input=input;sentText=legible(sent);receivedText=legible(received);
        }
    }
    public static final List<Theme> ALL=Collections.unmodifiableList(Arrays.asList(
        new Theme("meeto_pink","Meeto Pink","#FFF1F6","#21121B","#9D174D","#BE185D","#38232F"),
        new Theme("ocean_blue","Ocean Blue","#EFF8FF","#101D2A","#075985","#0369A1","#203344"),
        new Theme("lavender","Lavender","#F5F0FF","#1C162A","#6D28D9","#6D28D9","#30243F"),
        new Theme("midnight","Midnight","#EDF2FC","#0D1426","#1E3A8A","#1E40AF","#202D46"),
        new Theme("forest","Forest","#EEF8F1","#101F19","#166534","#166534","#20382C"),
        new Theme("sunset","Sunset","#FFF4EB","#271A12","#9A3412","#9A3412","#403024"),
        new Theme("rose","Rose","#FFF1F2","#27151C","#9F1239","#9F1239","#402631"),
        new Theme("graphite","Graphite","#F1F3F6","#15191E","#374151","#374151","#2B323C"),
        new Theme("coffee","Coffee","#FAF3EB","#241B16","#78350F","#92400E","#3D3027"),
        new Theme("aurora","Aurora","#EFF3FF","#141A2D","#4338CA","#4338CA","#293052")
    ));
    public static Theme get(String id){for(Theme theme:ALL)if(theme.id.equals(id))return theme;return ALL.get(0);}
    // Opaque black or white maximizes contrast (always >=4.5:1 for these surfaces).
    public static int legible(int background){return ColorUtils.calculateContrast(Color.WHITE,background)>=4.5?Color.WHITE:Color.BLACK;}
    public static GradientDrawable shape(int color,int radius,Context context){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius*context.getResources().getDisplayMetrics().density);return d;
    }
    private ChatThemeRegistry(){}
}
