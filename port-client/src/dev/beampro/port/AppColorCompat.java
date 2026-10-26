package dev.beampro.port;

import android.opengl.GLES20;
import android.util.Log;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Pair each app texture's actual GL conversion state with its copy material. */
public final class AppColorCompat {
    static final int EXTERNAL=0x8D65, OVERRIDE=0x8FBF, SRGB=0x8C40;
    private static final String EXTENSION="GL_EXT_texture_format_sRGB_override";
    private static final Map<Object,Boolean> software=Collections.synchronizedMap(new WeakHashMap<Object,Boolean>());
    public static final String PREF_COLOR_SRGB_CLAMP = "pref_color_srgb_clamp";
    public static final String PREF_COLOR_LIMITED_RANGE = "pref_color_limited_range";

    private static volatile boolean srgbClamp = false;
    private static volatile boolean limitedRange = false;

    private AppColorCompat() {}

    public static void setSrgbClamp(boolean enabled) {
        srgbClamp = enabled;
        Log.i("UnboundColor", "sRGB gamut clamp set to " + enabled);
    }

    public static boolean isSrgbClamp() {
        return srgbClamp;
    }

    public static void setLimitedRange(boolean enabled) {
        limitedRange = enabled;
        Log.i("UnboundColor", "Limited range (16-235) remap set to " + enabled);
    }

    public static boolean isLimitedRange() {
        return limitedRange;
    }

    /**
     * Applies sRGB gamut clamp (sRGB -> DCI-P3 D65 in linear space) and/or
     * full-to-limited range remap (0..255 -> 16..235).
     *
     * @param r Red component [0..1]
     * @param g Green component [0..1]
     * @param b Blue component [0..1]
     * @param clampSrgb Whether to apply chromaticity clamp into DCI-P3 gamut
     * @param remapRange Whether to compress range from [0, 1] to [16/255, 235/255]
     * @return transformed [r, g, b] float array in [0..1]
     */
    public static float[] applyColorTransform(float r, float g, float b, boolean clampSrgb, boolean remapRange) {
        float outR = r;
        float outG = g;
        float outB = b;

        if (clampSrgb) {
            // Matrix: sRGB -> DCI-P3 (D65) in linear space
            // [ 0.822462  0.177538  0.000000 ]
            // [ 0.033194  0.966806  0.000000 ]
            // [ 0.017083  0.072397  0.910520 ]
            float cr = 0.822462f * outR + 0.177538f * outG + 0.000000f * outB;
            float cg = 0.033194f * outR + 0.966806f * outG + 0.000000f * outB;
            float cb = 0.017083f * outR + 0.072397f * outG + 0.910520f * outB;

            outR = Math.max(0.0f, Math.min(1.0f, cr));
            outG = Math.max(0.0f, Math.min(1.0f, cg));
            outB = Math.max(0.0f, Math.min(1.0f, cb));
        }

        if (remapRange) {
            // Full range [0, 1] -> Limited range [16/255, 235/255]
            // Scale: (235.0 - 16.0) / 255.0 = 219.0 / 255.0 = 0.85882355f
            // Offset: 16.0 / 255.0 = 0.0627451f
            outR = 0.0627451f + outR * 0.85882355f;
            outG = 0.0627451f + outG * 0.85882355f;
            outB = 0.0627451f + outB * 0.85882355f;
        }

        return new float[]{outR, outG, outB};
    }

    public static int generateExternalTexture(Object owner) {
        int[] ids=new int[1];
        GLES20.glGenTextures(1,ids,0);
        GLES20.glBindTexture(EXTERNAL,ids[0]);
        GLES20.glTexParameteri(EXTERNAL,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(EXTERNAL,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(EXTERNAL,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(EXTERNAL,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        int setupError=GLES20.glGetError();
        if(setupError!=GLES20.GL_NO_ERROR)Log.w("UnboundColor","Texture setup GL error="+setupError);
        boolean hardware=false;
        String extensions=GLES20.glGetString(GLES20.GL_EXTENSIONS);
        if(hasExtension(extensions,EXTENSION)) {
            GLES20.glTexParameteri(EXTERNAL,OVERRIDE,SRGB);
            int setError=GLES20.glGetError();
            int[] actual=new int[1];
            GLES20.glGetTexParameteriv(EXTERNAL,OVERRIDE,actual,0);
            int queryError=GLES20.glGetError();
            hardware=setError==GLES20.GL_NO_ERROR&&queryError==GLES20.GL_NO_ERROR&&actual[0]==SRGB;
            if(!hardware) {
                // Never use shader decode over a partially accepted override.
                GLES20.glTexParameteri(EXTERNAL,OVERRIDE,0);
                int resetError=GLES20.glGetError();
                Log.w("UnboundColor","sRGB override unavailable: set="+setError+" query="+queryError+" reset="+resetError);
                if(resetError!=GLES20.GL_NO_ERROR)
                    hardware=queryError==GLES20.GL_NO_ERROR?actual[0]==SRGB:setError==GLES20.GL_NO_ERROR;
            }
        }
        software.put(owner,!hardware);
        Log.i("UnboundColor","texture="+ids[0]+" decode="+(hardware?"hardware":"shader")
                +" renderer="+GLES20.glGetString(GLES20.GL_RENDERER));
        return ids[0];
    }

    // Called through the original AndroidSurfaceTexture wrapper on Unity's thread.
    // No GL calls here: capability negotiation happened on the render thread.
    public static boolean needsSoftwareDecode(Object owner) {
        if (srgbClamp || limitedRange) {
            return true;
        }
        Boolean value=software.get(owner);
        return value!=null&&value;
    }

    static boolean hasExtension(String list,String wanted) {
        if(list==null)return false;
        for(String token:list.split("\\s+"))if(wanted.equals(token))return true;
        return false;
    }
}
