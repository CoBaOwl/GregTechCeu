package gregtech.api.util;

import net.minecraftforge.fml.common.FMLCommonHandler;

import java.lang.reflect.Method;

/**
 * Detects a client-side shader pipeline that is <strong>not</strong> OptiFine.
 * <p>
 * {@link Mods#ShadersMod} historically meant "OptiFine is present and a shader pack is loaded", because OptiFine was
 * the only way to run a shader pack on 1.12.2. Standalone Iris/Oculus back-ports now exist and drive the world render
 * the same way, so every guard that asks {@code Mods.ShadersMod.isModLoaded()} — bloom, the depth-texture hook, the CTM
 * hooks, the special vertex lighters — needs to answer "yes" for them too. Otherwise GregTech runs its own bloom
 * framebuffer and lighting passes underneath a deferred pipeline that has its own G-buffer bound, and the two fight
 * over the render target.
 * <p>
 * Resolved reflectively: GregTech has no compile-time dependency on any of these mods, and this class is loaded on the
 * dedicated server too (via the {@link Mods} enum), so it must never link a client-only class directly.
 */
public final class ShaderPipelineCompat {

    /** Yumelium — Sodium + Iris + Nvidium port for Cleanroom. {@code ShaderController.isEnabled()} is its public API. */
    private static final String YUMELIUM_CONTROLLER = "com.yumelium.yumelium.shaders.ShaderController";

    private static boolean resolved;
    private static Method isEnabled;

    private ShaderPipelineCompat() {}

    /**
     * @return true if a non-OptiFine shader pipeline is installed <em>and</em> currently has a pack applied. Queried
     *         live rather than cached — the user can toggle the pipeline at runtime from its keybind or its GUI.
     */
    public static boolean isShaderPackActive() {
        if (!FMLCommonHandler.instance().getSide().isClient()) {
            return false;
        }
        Method method = resolve();
        if (method == null) {
            return false;
        }
        try {
            return (Boolean) method.invoke(null);
        } catch (ReflectiveOperationException | ClassCastException e) {
            // The pipeline changed its API out from under us; stop asking rather than throwing every frame.
            isEnabled = null;
            return false;
        }
    }

    /**
     * @return true if the installed renderer draws the bloom {@code BlockRenderLayer} itself, so GregTech should keep
     *         emitting emissive geometry into that layer instead of folding it into the fallback layer.
     *         <p>
     *         This is a different question from {@link #isShaderPackActive()}. That one asks "is something else in
     *         charge of lighting and post-processing" — true for Yumelium, and it correctly stops us binding our own
     *         bloom framebuffer inside someone else's deferred pipeline. But it also used to suppress the bloom
     *         <em>layer</em>, which threw the emissive geometry in with ordinary cutout quads and made it
     *         unidentifiable. Yumelium maps the layer to its own render pass and tags it emissive for the shader pack,
     *         so it wants the geometry; it just does not want our post-processing.
     */
    public static boolean rendererDrawsBloomLayer() {
        return FMLCommonHandler.instance().getSide().isClient() && Mods.Yumelium.isModLoaded();
    }

    private static Method resolve() {
        if (!resolved) {
            resolved = true;
            if (Mods.Yumelium.isModLoaded()) {
                try {
                    isEnabled = Class.forName(YUMELIUM_CONTROLLER).getMethod("isEnabled");
                } catch (ReflectiveOperationException ignored) {
                    // Present but not the API we know — treat it as "no shader pipeline".
                }
            }
        }
        return isEnabled;
    }
}
