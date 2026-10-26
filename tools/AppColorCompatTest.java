package dev.beampro.port;

import android.opengl.GLES20;

public final class AppColorCompatTest {
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static Object texture(String extensions,boolean accept,boolean query) {
        GLES20.reset(extensions,accept,query);
        Object owner=new Object();
        check(AppColorCompat.generateExternalTexture(owner)==41,"texture ID retained");
        check(GLES20.bound==AppColorCompat.EXTERNAL,"external texture target retained");
        check(GLES20.filters==4,"filtering and wrapping retained");
        return owner;
    }
    public static void main(String[] args) {
        String extension="GL_EXT_texture_format_sRGB_override";
        Object unsupported=texture("GL_OES_EGL_image_external",false,false);
        check(AppColorCompat.needsSoftwareDecode(unsupported),"unsupported GPU selects shader decode");
        check(GLES20.overrides==0,"unsupported enum never sent");
        Object supported=texture("GL_EXT_other "+extension+"\tGL_EXT_last",true,true);
        check(!AppColorCompat.needsSoftwareDecode(supported),"confirmed GPU decode retains stock shader");
        check(GLES20.override==AppColorCompat.SRGB,"hardware override enabled");
        check(AppColorCompat.needsSoftwareDecode(unsupported),"each texture keeps its own decision");
        Object rejected=texture(extension,false,true);
        check(AppColorCompat.needsSoftwareDecode(rejected),"advertised but rejected override falls back");
        check(GLES20.override==0,"fallback clears override");
        Object unreadable=texture(extension,true,false);
        check(AppColorCompat.needsSoftwareDecode(unreadable),"unverified state resets and uses software");
        check(GLES20.override==0,"no double decode after failed readback");
        Object ignored=texture(extension+"_extra",true,true);
        check(AppColorCompat.needsSoftwareDecode(ignored)&&GLES20.overrides==0,"extension names matched as whole tokens");
        Object absent=texture(null,true,true);
        check(AppColorCompat.needsSoftwareDecode(absent),"missing extension string falls back");
        check(!AppColorCompat.needsSoftwareDecode(new Object()),"unknown owner preserves stock behavior");
        check(!AppColorCompat.needsSoftwareDecode(supported),"later probes do not change another texture");
        int calls=GLES20.calls;
        AppColorCompat.needsSoftwareDecode(absent);
        check(calls==GLES20.calls,"Unity-thread getter never invokes GL");

        // Test sRGB clamp and limited range flags
        check(!AppColorCompat.isSrgbClamp(), "sRGB clamp default false");
        check(!AppColorCompat.isLimitedRange(), "limited range default false");

        AppColorCompat.setSrgbClamp(true);
        check(AppColorCompat.isSrgbClamp(), "sRGB clamp enabled");
        check(AppColorCompat.needsSoftwareDecode(supported), "sRGB clamp forces software decode even on supported GPU");
        AppColorCompat.setSrgbClamp(false);
        check(!AppColorCompat.needsSoftwareDecode(supported), "sRGB clamp reset restores hardware decode");

        AppColorCompat.setLimitedRange(true);
        check(AppColorCompat.isLimitedRange(), "limited range enabled");
        check(AppColorCompat.needsSoftwareDecode(supported), "limited range forces software decode even on supported GPU");
        AppColorCompat.setLimitedRange(false);
        check(!AppColorCompat.needsSoftwareDecode(supported), "limited range reset restores hardware decode");

        // Mathematical verification of color transforms
        // 1. Identity when both disabled
        float[] identity = AppColorCompat.applyColorTransform(0.5f, 0.5f, 0.5f, false, false);
        check(Math.abs(identity[0] - 0.5f) < 1e-5f && Math.abs(identity[1] - 0.5f) < 1e-5f && Math.abs(identity[2] - 0.5f) < 1e-5f, "identity transform");

        // 2. White point preservation in sRGB clamp (1, 1, 1 -> 1, 1, 1)
        float[] white = AppColorCompat.applyColorTransform(1.0f, 1.0f, 1.0f, true, false);
        check(Math.abs(white[0] - 1.0f) < 1e-4f && Math.abs(white[1] - 1.0f) < 1e-4f && Math.abs(white[2] - 1.0f) < 1e-4f, "white point preserved");

        // 3. Black point preservation in sRGB clamp (0, 0, 0 -> 0, 0, 0)
        float[] black = AppColorCompat.applyColorTransform(0.0f, 0.0f, 0.0f, true, false);
        check(black[0] == 0.0f && black[1] == 0.0f && black[2] == 0.0f, "black point preserved");

        // 4. Pure green clamp (0, 1, 0 -> 0.177538, 0.966806, 0.072397)
        float[] green = AppColorCompat.applyColorTransform(0.0f, 1.0f, 0.0f, true, false);
        check(Math.abs(green[0] - 0.177538f) < 1e-4f && Math.abs(green[1] - 0.966806f) < 1e-4f && Math.abs(green[2] - 0.072397f) < 1e-4f, "green gamut clamp");

        // 5. Limited range compression: 0 -> 16/255, 1 -> 235/255
        float[] limitedBlack = AppColorCompat.applyColorTransform(0.0f, 0.0f, 0.0f, false, true);
        check(Math.abs(limitedBlack[0] - (16.0f / 255.0f)) < 1e-4f, "limited black 16/255");
        float[] limitedWhite = AppColorCompat.applyColorTransform(1.0f, 1.0f, 1.0f, false, true);
        check(Math.abs(limitedWhite[0] - (235.0f / 255.0f)) < 1e-4f, "limited white 235/255");

        System.out.println("PASS: "+checks+" app-color capability assertions");
    }
}
